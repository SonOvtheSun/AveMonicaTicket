package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.entity.EventSession;
import com.avemonica.ticket.entity.Order;
import com.avemonica.ticket.entity.OrderSpectator;
import com.avemonica.ticket.entity.OrderTicket;
import com.avemonica.ticket.entity.Spectator;
import com.avemonica.ticket.entity.TicketCategory;
import com.avemonica.ticket.mapper.EventMapper;
import com.avemonica.ticket.mapper.EventSessionMapper;
import com.avemonica.ticket.mapper.OrderMapper;
import com.avemonica.ticket.mapper.OrderSpectatorMapper;
import com.avemonica.ticket.mapper.OrderTicketMapper;
import com.avemonica.ticket.mapper.SpectatorMapper;
import com.avemonica.ticket.mapper.TicketCategoryMapper;
import com.avemonica.ticket.service.OrderService;
import com.avemonica.ticket.vo.OrderVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {

    @Autowired
    private TicketCategoryMapper ticketMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EventSessionMapper eventSessionMapper;

    @Autowired
    private OrderSpectatorMapper orderSpectatorMapper;

    @Autowired
    private SpectatorMapper spectatorMapper;

    @Autowired
    private EventMapper eventMapper;

    @Autowired
    private OrderTicketMapper orderTicketMapper;

    private static final int ORDER_STATUS_PENDING_PAY = 1;
    private static final int ORDER_STATUS_CANCELED = 2;
    private static final int ORDER_STATUS_PAID = 3;
    private static final int ORDER_STATUS_CHECK_PENDING = 6;

    private static final int TICKET_CHECK_STATUS_PENDING_ISSUE = 4;
    private static final int TICKET_CHECK_STATUS_CHECKED = 2;

    private static final String KEY_SPEC_LOCK = "event:spectator:lock:";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Kafka 消费者调用的真正建单逻辑。
     * 新模型下订单必须绑定 eventId + sessionId + ticketId，不再兼容 event 级别票档。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Order createTicketOrder(Order order, List<Long> spectatorIds) {
        validateCreateOrderArgs(order, spectatorIds);

        TicketCategory ticket = ticketMapper.selectById(order.getTicketId());
        if (ticket == null) {
            throw new RuntimeException("该票档不存在或已下架");
        }

        // 防止用户用 A 场次的 ticketId 购买 B 场次。
        if (!Objects.equals(ticket.getEventId(), order.getEventId())
                || !Objects.equals(ticket.getSessionId(), order.getSessionId())) {
            throw new RuntimeException("票档不属于当前演出场次");
        }

        EventSession session = eventSessionMapper.selectById(order.getSessionId());
        if (session == null || !Objects.equals(session.getEventId(), order.getEventId())) {
            throw new RuntimeException("演出场次不存在或不属于当前演出");
        }

        // 所有归属校验通过后再扣库存，避免错误扣减。
        int updateRows = ticketMapper.deductStock(order.getTicketId(), order.getQuantity());
        if (updateRows == 0) {
            throw new RuntimeException("手慢了，该票档库存不足！");
        }

        BigDecimal payPrice = ticket.getPrice().multiply(BigDecimal.valueOf(order.getQuantity()));
        order.setPayPrice(payPrice);
        order.setStatus(ORDER_STATUS_PENDING_PAY);
        order.setOrderNo(IdWorker.getIdStr());
        order.setCreateTime(LocalDateTime.now());
        this.save(order);

        // 一票一证关系立即落库，且必须写入 sessionId。
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
        Order order = this.getById(orderId);
        if (order == null || !Objects.equals(order.getUserId(), userId)) {
            throw new RuntimeException("订单不存在或无权操作");
        }
        if (!Objects.equals(order.getStatus(), ORDER_STATUS_PENDING_PAY)) {
            throw new RuntimeException("只能取消待支付的订单");
        }

        order.setStatus(ORDER_STATUS_CANCELED);
        this.updateById(order);

        ticketMapper.addStock(order.getTicketId(), order.getQuantity());

        List<OrderSpectator> osList = orderSpectatorMapper.selectList(
                new LambdaQueryWrapper<OrderSpectator>()
                        .eq(OrderSpectator::getOrderId, orderId)
                        .eq(OrderSpectator::getDeleteToken, 0L)
        );

        List<String> redisLockKeys = new ArrayList<>();
        for (OrderSpectator os : osList) {
            // 释放 MySQL 唯一索引：活跃关系 deleteToken=0，取消后改为自身主键。
            os.setDeleteToken(os.getId());
            orderSpectatorMapper.updateById(os);

            redisLockKeys.add(KEY_SPEC_LOCK + sceneKey(order.getEventId(), order.getSessionId()) + ":" + os.getSpectatorId());
        }

        if (!redisLockKeys.isEmpty()) {
            redisTemplate.delete(redisLockKeys);
        }
    }

    @Override
    public List<OrderVO> getUserOrderList(Long userId, String status) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .orderByDesc(Order::getCreateTime);

        // 待检票/已完成基于电子票 checkStatus 动态归类，不能只看订单主表 status。
        boolean dynamicTicketStatusFilter = "3".equals(status) || "6".equals(status);
        if (StringUtils.hasText(status) && !"all".equals(status)) {
            if (dynamicTicketStatusFilter) {
                wrapper.in(Order::getStatus, ORDER_STATUS_PAID, ORDER_STATUS_CHECK_PENDING);
            } else {
                wrapper.eq(Order::getStatus, Integer.parseInt(status));
            }
        }

        List<Order> orders = this.list(wrapper);
        List<OrderVO> voList = new ArrayList<>();

        for (Order order : orders) {
            OrderVO vo = buildOrderVO(order);

            if (dynamicTicketStatusFilter && !status.equals(resolveOrderCategoryKey(vo))) {
                continue;
            }

            voList.add(vo);
        }

        return voList;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteOrder(Long orderId, Long userId) {
        Order order = this.getById(orderId);
        if (order == null || !Objects.equals(order.getUserId(), userId)) {
            throw new RuntimeException("订单不存在或无权操作");
        }

        if (Objects.equals(order.getStatus(), ORDER_STATUS_PENDING_PAY)
                || Objects.equals(order.getStatus(), ORDER_STATUS_CHECK_PENDING)) {
            throw new RuntimeException("当前订单状态不允许删除，请先取消订单");
        }

        this.removeById(orderId);
        orderSpectatorMapper.delete(new LambdaQueryWrapper<OrderSpectator>().eq(OrderSpectator::getOrderId, orderId));
    }

    private void validateCreateOrderArgs(Order order, List<Long> spectatorIds) {
        if (order == null) throw new RuntimeException("订单参数不能为空");
        if (order.getUserId() == null) throw new RuntimeException("缺少用户信息");
        if (order.getEventId() == null) throw new RuntimeException("缺少演出信息");
        if (order.getSessionId() == null) throw new RuntimeException("缺少演出场次信息");
        if (order.getTicketId() == null) throw new RuntimeException("缺少票档信息");
        if (order.getQuantity() == null || order.getQuantity() <= 0) throw new RuntimeException("购票数量不正确");
        if (spectatorIds == null || spectatorIds.size() != order.getQuantity()) {
            throw new RuntimeException("实名观演人数量与购票数量不一致");
        }
    }

    private OrderVO buildOrderVO(Order order) {
        OrderVO vo = new OrderVO();
        vo.setId(String.valueOf(order.getId()));
        vo.setCreateTime(order.getCreateTime() != null ? order.getCreateTime().format(DATE_TIME_FORMATTER) : "");
        vo.setStatus(order.getStatus());
        vo.setTotalAmount(order.getPayPrice());
        vo.setEventId(String.valueOf(order.getEventId()));

        Event event = eventMapper.selectById(order.getEventId());
        EventSession session = eventSessionMapper.selectById(order.getSessionId());
        vo.setEvent(buildEventVO(event, session));

        if (Objects.equals(order.getStatus(), ORDER_STATUS_PAID)
                || Objects.equals(order.getStatus(), ORDER_STATUS_CHECK_PENDING)) {
            vo.setPaymentMethod("支付宝支付");
        }

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

        Map<Long, Spectator> spectatorMap = buildSpectatorMap(spectatorIds);
        TicketCategory category = ticketMapper.selectById(order.getTicketId());
        String categoryName = category != null ? category.getName() : "未知票档";

        vo.setTickets(buildTicketVOs(order, orderSpectators, spectatorMap, categoryName));
        return vo;
    }

    private OrderVO.EventVO buildEventVO(Event event, EventSession session) {
        OrderVO.EventVO eventVO = new OrderVO.EventVO();
        if (event == null) {
            return eventVO;
        }

        eventVO.setName(event.getTitle());
        eventVO.setPoster(event.getPosterUrl());
        eventVO.setCity(event.getCity());
        eventVO.setVenue(event.getVenue());
        eventVO.setRunningTime(event.getRunningTime());

        // 新模型只使用订单绑定的场次时间；如果缺失，明确提示数据异常，不回退到 event.showTime。
        eventVO.setTime(session != null && session.getShowTime() != null
                ? session.getShowTime().format(DATE_TIME_FORMATTER)
                : "场次信息缺失");

        return eventVO;
    }

    private List<OrderVO.TicketVO> buildTicketVOs(
            Order order,
            List<OrderSpectator> orderSpectators,
            Map<Long, Spectator> spectatorMap,
            String categoryName
    ) {
        List<OrderVO.TicketVO> ticketVOs = new ArrayList<>();

        if (Objects.equals(order.getStatus(), ORDER_STATUS_PENDING_PAY)
                || Objects.equals(order.getStatus(), ORDER_STATUS_CANCELED)) {
            for (int i = 0; i < order.getQuantity(); i++) {
                OrderVO.TicketVO ticketVO = createPendingTicketVO(order, i, categoryName);
                fillTicketSpectatorInfo(ticketVO, getSpectatorByIndex(i, orderSpectators, spectatorMap));
                ticketVOs.add(ticketVO);
            }
            return ticketVOs;
        }

        List<OrderTicket> realTickets = orderTicketMapper.selectList(
                new LambdaQueryWrapper<OrderTicket>()
                        .eq(OrderTicket::getOrderId, order.getId())
                        .orderByAsc(OrderTicket::getId)
        );

        if (realTickets.isEmpty()) {
            for (int i = 0; i < order.getQuantity(); i++) {
                OrderVO.TicketVO ticketVO = createQueueTicketVO(order, i, categoryName);
                fillTicketSpectatorInfo(ticketVO, getSpectatorByIndex(i, orderSpectators, spectatorMap));
                ticketVOs.add(ticketVO);
            }
            return ticketVOs;
        }

        for (OrderTicket realTicket : realTickets) {
            OrderVO.TicketVO ticketVO = new OrderVO.TicketVO();
            ticketVO.setId(String.valueOf(realTicket.getId()));
            ticketVO.setName(realTicket.getTicketName());
            ticketVO.setSeatInfo(realTicket.getSeatInfo());
            ticketVO.setCheckStatus(realTicket.getCheckStatus());
            ticketVO.setQrCode(realTicket.getQrCode());

            Spectator spectator = realTicket.getSpectatorId() == null
                    ? null
                    : spectatorMap.get(realTicket.getSpectatorId());
            fillTicketSpectatorInfo(ticketVO, spectator);

            ticketVOs.add(ticketVO);
        }

        return ticketVOs;
    }

    private OrderVO.TicketVO createPendingTicketVO(Order order, int index, String categoryName) {
        OrderVO.TicketVO ticketVO = new OrderVO.TicketVO();
        ticketVO.setId("T_PENDING_" + order.getId() + "_" + index);
        ticketVO.setName(categoryName);
        ticketVO.setCheckStatus(TICKET_CHECK_STATUS_PENDING_ISSUE);
        return ticketVO;
    }

    private OrderVO.TicketVO createQueueTicketVO(Order order, int index, String categoryName) {
        OrderVO.TicketVO ticketVO = new OrderVO.TicketVO();
        ticketVO.setId("T_QUEUE_" + order.getId() + "_" + index);
        ticketVO.setName(categoryName);
        ticketVO.setCheckStatus(TICKET_CHECK_STATUS_PENDING_ISSUE);
        return ticketVO;
    }

    private Map<Long, Spectator> buildSpectatorMap(List<Long> spectatorIds) {
        if (spectatorIds == null || spectatorIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Spectator> spectators = spectatorMapper.selectBatchIds(spectatorIds);
        Map<Long, Spectator> spectatorMap = new HashMap<>();
        for (Spectator spectator : spectators) {
            spectatorMap.put(spectator.getId(), spectator);
        }
        return spectatorMap;
    }

    private Spectator getSpectatorByIndex(int index, List<OrderSpectator> orderSpectators, Map<Long, Spectator> spectatorMap) {
        if (orderSpectators == null || index < 0 || index >= orderSpectators.size()) {
            return null;
        }
        Long spectatorId = orderSpectators.get(index).getSpectatorId();
        return spectatorId == null ? null : spectatorMap.get(spectatorId);
    }

    private void fillTicketSpectatorInfo(OrderVO.TicketVO ticketVO, Spectator spectator) {
        if (ticketVO == null || spectator == null) {
            return;
        }
        ticketVO.setSpectatorId(String.valueOf(spectator.getId()));
        ticketVO.setViewerName(spectator.getName());
        ticketVO.setIdCardNo(spectator.getIdCard());
    }

    private String resolveOrderCategoryKey(OrderVO vo) {
        if (vo == null) return "other";

        Integer status = vo.getStatus();
        if (Objects.equals(status, ORDER_STATUS_PENDING_PAY)) return "1";
        if (Objects.equals(status, ORDER_STATUS_CANCELED)) return "2";
        if (Objects.equals(status, 4)) return "4";

        List<OrderVO.TicketVO> tickets = vo.getTickets();
        if (tickets == null || tickets.isEmpty()) {
            return "6";
        }

        boolean allChecked = tickets.stream()
                .allMatch(ticket -> Objects.equals(ticket.getCheckStatus(), TICKET_CHECK_STATUS_CHECKED));
        if (allChecked) {
            return "3";
        }

        if (isOrderEventOver(vo)) {
            return "ended";
        }

        return "6";
    }

    private boolean isOrderEventOver(OrderVO vo) {
        try {
            if (vo == null || vo.getEvent() == null || !StringUtils.hasText(vo.getEvent().getTime())) {
                return false;
            }
            LocalDateTime showTime = LocalDateTime.parse(vo.getEvent().getTime(), DATE_TIME_FORMATTER);
            int runningTime = vo.getEvent().getRunningTime() == null ? 120 : vo.getEvent().getRunningTime();
            return LocalDateTime.now().isAfter(showTime.plusMinutes(runningTime));
        } catch (Exception e) {
            return false;
        }
    }

    private String sceneKey(Long eventId, Long sessionId) {
        return eventId + ":" + sessionId;
    }
}
