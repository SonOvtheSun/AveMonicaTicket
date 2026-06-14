package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.dto.EventAddDTO;
import com.avemonica.ticket.dto.TicketCategoryDTO;
import com.avemonica.ticket.entity.*;
import com.avemonica.ticket.exception.BusinessException;
import com.avemonica.ticket.mapper.*;
import com.avemonica.ticket.service.EventArtistService;
import com.avemonica.ticket.service.EventService;
import com.avemonica.ticket.service.OrderService;
import com.avemonica.ticket.service.UserService;
import com.avemonica.ticket.vo.OrderVO;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.security.core.context.SecurityContextHolder;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.util.StringUtils;

@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {
    @Autowired
    private TicketCategoryMapper ticketMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EventSessionMapper eventSessionMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderSpectatorMapper orderSpectatorMapper;

    @Autowired
    private SpectatorMapper spectatorMapper;

    @Autowired
    private EventMapper eventMapper; // 假设你已有 EventMapper
    @Autowired
    private OrderTicketMapper orderTicketMapper; // 存储真实电子票的 Mapper (对应 tb_order_ticket)

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Order createTicketOrder(Order order, List<Long> spectatorIds) {
        if (order == null) {
            throw new RuntimeException("订单参数不能为空");
        }
        if (order.getEventId() == null) {
            throw new RuntimeException("缺少演出信息");
        }
        if (order.getSessionId() == null) {
            throw new RuntimeException("缺少演出场次信息");
        }
        if (order.getTicketId() == null) {
            throw new RuntimeException("缺少票档信息");
        }
        if (order.getQuantity() == null || order.getQuantity() <= 0) {
            throw new RuntimeException("购票数量不正确");
        }
        if (spectatorIds == null || spectatorIds.size() != order.getQuantity()) {
            throw new RuntimeException("实名观演人数量与购票数量不一致");
        }

        TicketCategory ticket = ticketMapper.selectById(order.getTicketId());
        if (ticket == null) {
            throw new RuntimeException("该票档不存在或已下架");
        }

        /*
         * 多场次模型核心校验：
         * 票档必须同时属于当前 eventId 和 sessionId。
         * 这样可以防止用户绕过前端，拿 A 场次的票档 ID 去买 B 场次。
         */
        if (!Objects.equals(ticket.getEventId(), order.getEventId())
                || !Objects.equals(ticket.getSessionId(), order.getSessionId())) {
            throw new RuntimeException("票档不属于当前演出场次");
        }

        EventSession session = eventSessionMapper.selectById(order.getSessionId());
        if (session == null || !Objects.equals(session.getEventId(), order.getEventId())) {
            throw new RuntimeException("演出场次不存在或不属于当前演出");
        }

        /*
         * 库存扣减必须放在所有归属校验之后。
         * 否则如果先扣库存再发现 sessionId 不合法，会导致库存被错误扣减。
         */
        int updateRows = ticketMapper.deductStock(order.getTicketId(), order.getQuantity());
        if (updateRows == 0) {
            throw new RuntimeException("手慢了，该票档库存不足！");
        }

        BigDecimal payPrice = ticket.getPrice().multiply(new BigDecimal(order.getQuantity()));
        order.setPayPrice(payPrice);
        order.setStatus(1);
        order.setOrderNo(IdWorker.getIdStr());
        order.setCreateTime(LocalDateTime.now());

        this.save(order);

        /*
         * 立即将订单与观演人的绑定关系落入 tb_order_spectator。
         * 这里必须写 sessionId，否则同一个演出下的下午场/晚场会互相冲突。
         */
        for (Long specId : spectatorIds) {
            OrderSpectator os = new OrderSpectator();
            os.setOrderId(order.getId());
            os.setEventId(order.getEventId());
            os.setSessionId(order.getSessionId());
            os.setSpectatorId(specId);
            os.setDeleteToken(0L);
            orderSpectatorMapper.insert(os);
        }

        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId, Long userId) {
        // 1. 基础校验
        Order order = this.getById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new RuntimeException("订单不存在或无权操作");
        }
        if (order.getStatus() != 1) { // 1: 待支付
            throw new RuntimeException("只能取消待支付的订单");
        }

        // 2. 更新订单状态为已取消 (2: 已取消)
        order.setStatus(2);
        this.updateById(order);

        // 3. 回滚库存：把原本扣掉的票还回去
        // （需要在 TicketCategoryMapper 中补充 addStock 方法：UPDATE tb_ticket_category SET remaining_stock = remaining_stock + #{quantity} WHERE id = #{id}）
        ticketMapper.addStock(order.getTicketId(), order.getQuantity());

        // 4. 查询该订单绑定的所有观演人关系
        List<OrderSpectator> osList = orderSpectatorMapper.selectList(
                new LambdaQueryWrapper<OrderSpectator>().eq(OrderSpectator::getOrderId, orderId)
        );

        List<String> redisLockKeys = new ArrayList<>();

        for (OrderSpectator os : osList) {
            // 🚨 核心 1：释放 MySQL 唯一索引（将 delete_token 从 0 改为主键 ID）
            os.setDeleteToken(os.getId());
            orderSpectatorMapper.updateById(os);

            // 收集需要删除的 Redis 短锁 Key
            redisLockKeys.add("event:spectator:lock:" + order.getEventId() + ":" + order.getSessionId() + ":" + os.getSpectatorId());
        }

        // 5. 🚨 核心 2：立刻删除 Redis 短锁，让这些观演人马上重获购票资格！
        if (!redisLockKeys.isEmpty()) {
            redisTemplate.delete(redisLockKeys);
        }
    }

    @Override
    public List<OrderVO> getUserOrderList(Long userId, String status) {
        // 1. 查询该用户的主订单
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .orderByDesc(Order::getCreateTime);

        // 待检票/已完成不能直接按订单主表 status 判断。
        // 因为已支付订单可能主表 status 仍为 3，但票本身还未检票；应基于 ticket.checkStatus 动态归类。
        boolean dynamicTicketStatusFilter = "3".equals(status) || "6".equals(status);
        if (status != null && !"all".equals(status)) {
            if (dynamicTicketStatusFilter) {
                wrapper.in(Order::getStatus, 3, 6);
            } else {
                wrapper.eq(Order::getStatus, Integer.parseInt(status));
            }
        }
        List<Order> orders = this.list(wrapper);

        // 2. 组装 VO 返回给前端
        List<OrderVO> voList = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        for (Order order : orders) {
            OrderVO vo = new OrderVO();
            vo.setId(order.getId().toString());
            vo.setCreateTime(order.getCreateTime() != null ? order.getCreateTime().format(formatter) : "");
            vo.setStatus(order.getStatus());
            vo.setTotalAmount(order.getPayPrice());

            // 2.1 组装演出信息
            Event event = eventMapper.selectById(order.getEventId());
            EventSession session = null;
            if (order.getSessionId() != null) {
                session = eventSessionMapper.selectById(order.getSessionId());
            }

            OrderVO.EventVO eventVO = new OrderVO.EventVO();
            if (event != null) {
                eventVO.setName(event.getTitle());
                eventVO.setPoster(event.getPosterUrl());
                eventVO.setCity(event.getCity());
                eventVO.setVenue(event.getVenue());

                /*
                 * 多场次模型下，订单展示时间必须优先使用 tb_event_session.show_time。
                 * 如果是旧订单或旧数据没有 sessionId，则回退到 tb_event.show_time。
                 */
                LocalDateTime displayShowTime = session != null && session.getShowTime() != null
                        ? session.getShowTime()
                        : event.getShowTime();

                eventVO.setTime(displayShowTime != null ? displayShowTime.format(formatter) : "时间待定");
                eventVO.setRunningTime(event.getRunningTime());
            }
            vo.setEvent(eventVO);
            vo.setEventId(order.getEventId().toString());

            if (order.getStatus() == 3 || order.getStatus() == 6) {
                // 假设你有 payTime 字段，如果没有可以用 updateTime 代替展示
                vo.setPaymentMethod("支付宝支付");
            }

            // 2.2 先查订单绑定的观演人关系，后续给每张电子票补充 viewerName / idCardNo
            List<OrderSpectator> orderSpectators = orderSpectatorMapper.selectList(
                    new LambdaQueryWrapper<OrderSpectator>()
                            .eq(OrderSpectator::getOrderId, order.getId())
                            .eq(OrderSpectator::getDeleteToken, 0L)
                            .orderByAsc(OrderSpectator::getId)
            );

            List<Long> spectatorIds = orderSpectators.stream()
                    .map(OrderSpectator::getSpectatorId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            Map<Long, Object> spectatorMap = buildSpectatorMap(spectatorIds);

            // 2.3 组装电子票信息
            List<OrderVO.TicketVO> ticketVOs = new ArrayList<>();
            TicketCategory category = ticketMapper.selectById(order.getTicketId());
            String categoryName = category != null ? category.getName() : "未知票档";

            if (order.getStatus() == 1 || order.getStatus() == 2) {
                // 状态A：待支付或已取消 -> 根本还没出票，但仍展示本订单选择的观演人
                for (int i = 0; i < order.getQuantity(); i++) {
                    OrderVO.TicketVO tVO = new OrderVO.TicketVO();
                    tVO.setId("T_PENDING_" + order.getId() + "_" + i);
                    tVO.setName(categoryName);
                    tVO.setCheckStatus(4); // 后台配座中/未出票
                    fillTicketSpectatorInfo(tVO, getSpectatorByIndex(i, orderSpectators, spectatorMap));
                    ticketVOs.add(tVO);
                }
            } else {
                // 状态B：已支付 -> 去 tb_order_ticket 查真实的票
                List<OrderTicket> realTickets = orderTicketMapper.selectList(
                        new LambdaQueryWrapper<OrderTicket>().eq(OrderTicket::getOrderId, order.getId())
                );

                if (realTickets.isEmpty()) {
                    // 状态C：虽然支付了，但 Kafka 后台还没消费完，真实的票还没落库
                    for (int i = 0; i < order.getQuantity(); i++) {
                        OrderVO.TicketVO tVO = new OrderVO.TicketVO();
                        tVO.setId("T_QUEUE_" + order.getId() + "_" + i);
                        tVO.setName(categoryName);
                        tVO.setCheckStatus(4);
                        fillTicketSpectatorInfo(tVO, getSpectatorByIndex(i, orderSpectators, spectatorMap));
                        ticketVOs.add(tVO);
                    }
                } else {
                    // 状态D：完美出票，下发座位号、二维码和对应观演人信息
                    for (int i = 0; i < realTickets.size(); i++) {
                        OrderTicket rt = realTickets.get(i);
                        OrderVO.TicketVO tVO = new OrderVO.TicketVO();
                        tVO.setId(rt.getId().toString());
                        tVO.setName(rt.getTicketName());
                        tVO.setSeatInfo(rt.getSeatInfo());
                        tVO.setCheckStatus(rt.getCheckStatus());
                        tVO.setQrCode(rt.getQrCode());

                        // 优先使用电子票表中的 spectatorId；如果实体里暂时没有该字段，则按订单观演人绑定顺序兜底。
                        Object ticketSpectatorId = readProperty(rt, "getSpectatorId");
                        Object spectator = null;
                        if (ticketSpectatorId != null) {
                            spectator = spectatorMap.get(toLong(ticketSpectatorId));
                        }
                        if (spectator == null) {
                            spectator = getSpectatorByIndex(i, orderSpectators, spectatorMap);
                        }
                        fillTicketSpectatorInfo(tVO, spectator);

                        ticketVOs.add(tVO);
                    }
                }
            }
            vo.setTickets(ticketVOs);

            // 2.4 对“待检票 / 已完成”做动态过滤，避免待检票订单被后端归到已完成列表里
            if (dynamicTicketStatusFilter && !status.equals(resolveOrderCategoryKey(vo))) {
                continue;
            }

            voList.add(vo);
        }
        return voList;
    }

    private Map<Long, Object> buildSpectatorMap(List<Long> spectatorIds) {
        if (spectatorIds == null || spectatorIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<?> spectators = spectatorMapper.selectBatchIds(spectatorIds);
        Map<Long, Object> spectatorMap = new HashMap<>();
        for (Object spectator : spectators) {
            Object id = readProperty(spectator, "getId");
            Long spectatorId = toLong(id);
            if (spectatorId != null) {
                spectatorMap.put(spectatorId, spectator);
            }
        }
        return spectatorMap;
    }

    private Object getSpectatorByIndex(int index, List<OrderSpectator> orderSpectators, Map<Long, Object> spectatorMap) {
        if (orderSpectators == null || index < 0 || index >= orderSpectators.size()) {
            return null;
        }
        Long spectatorId = orderSpectators.get(index).getSpectatorId();
        return spectatorId == null ? null : spectatorMap.get(spectatorId);
    }

    private void fillTicketSpectatorInfo(OrderVO.TicketVO ticketVO, Object spectator) {
        if (ticketVO == null || spectator == null) return;

        Object id = readProperty(spectator, "getId");
        if (id != null) {
            ticketVO.setSpectatorId(String.valueOf(id));
        }

        String viewerName = firstNotBlank(
                readStringProperty(spectator, "getName"),
                readStringProperty(spectator, "getRealName"),
                readStringProperty(spectator, "getViewerName"),
                readStringProperty(spectator, "getAudienceName"),
                readStringProperty(spectator, "getSpectatorName")
        );
        ticketVO.setViewerName(viewerName);

        String idCardNo = firstNotBlank(
                readStringProperty(spectator, "getIdCard"),
                readStringProperty(spectator, "getIdCardNo"),
                readStringProperty(spectator, "getIdentityNo"),
                readStringProperty(spectator, "getCertNo"),
                readStringProperty(spectator, "getCertificateNo")
        );
        ticketVO.setIdCardNo(idCardNo);
    }

    private String resolveOrderCategoryKey(OrderVO vo) {
        if (vo == null) return "other";
        Integer status = vo.getStatus();
        if (Objects.equals(status, 1)) return "1"; // 待支付
        if (Objects.equals(status, 2)) return "2"; // 已取消
        if (Objects.equals(status, 4)) return "4"; // 退款中等

        List<OrderVO.TicketVO> tickets = vo.getTickets();
        if (tickets == null || tickets.isEmpty()) {
            return "6";
        }

        // 注意：当前 VO 注释约定 checkStatus：1=未检票，2=已检票，4=未出票。
        boolean allChecked = tickets.stream().allMatch(t -> Objects.equals(t.getCheckStatus(), 2));
        if (allChecked) {
            return "3"; // 已完成
        }

        if (isOrderEventOver(vo)) {
            return "ended"; // 已结束但未全部检票，不应该归入“已完成”
        }

        return "6"; // 待检票
    }

    private boolean isOrderEventOver(OrderVO vo) {
        try {
            if (vo == null || vo.getEvent() == null || !StringUtils.hasText(vo.getEvent().getTime())) {
                return false;
            }
            LocalDateTime showTime = LocalDateTime.parse(vo.getEvent().getTime(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            int runningTime = vo.getEvent().getRunningTime() == null ? 120 : vo.getEvent().getRunningTime();
            return LocalDateTime.now().isAfter(showTime.plusMinutes(runningTime));
        } catch (Exception e) {
            return false;
        }
    }

    private Object readProperty(Object target, String getterName) {
        if (target == null || !StringUtils.hasText(getterName)) return null;
        try {
            Method method = target.getClass().getMethod(getterName);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readStringProperty(Object target, String getterName) {
        Object value = readProperty(target, getterName);
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String firstNotBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (StringUtils.hasText(value)) return value;
        }
        return null;
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteOrder(Long orderId, Long userId) {
        Order order = this.getById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new RuntimeException("订单不存在或无权操作");
        }

        // 只有已取消（2）或已完成（3）的订单允许删除。待支付（1）和待检票（6）不能删！
        if (order.getStatus() == 1 || order.getStatus() == 6) {
            throw new RuntimeException("当前订单状态不允许删除，请先取消订单");
        }

        // 执行物理删除 (如果有逻辑删除需求，可以改为 update status)
        this.removeById(orderId);

        // 清理关系表，防止脏数据
        orderSpectatorMapper.delete(new LambdaQueryWrapper<OrderSpectator>().eq(OrderSpectator::getOrderId, orderId));
    }

}
