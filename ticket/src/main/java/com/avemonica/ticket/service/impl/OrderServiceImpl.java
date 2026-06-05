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
    private ObjectMapper objectMapper;

    @Autowired
    private OrderSpectatorMapper orderSpectatorMapper;

    @Autowired
    private EventMapper eventMapper; // 假设你已有 EventMapper
    @Autowired
    private OrderTicketMapper orderTicketMapper; // 存储真实电子票的 Mapper (对应 tb_order_ticket)

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Order createTicketOrder(Order order, List<Long> spectatorIds) {
        TicketCategory ticket = ticketMapper.selectById(order.getTicketId());
        if (ticket == null) {
            throw new RuntimeException("该票档不存在或已下架");
        }

        int updateRows = ticketMapper.deductStock(order.getTicketId(), order.getQuantity());
        if(updateRows == 0){
            throw new RuntimeException("手慢了，该票档库存不足！");
        }


        BigDecimal payPrice = ticket.getPrice().multiply(new BigDecimal(order.getQuantity()));
        order.setPayPrice(payPrice);
        order.setStatus(1);
        order.setOrderNo(IdWorker.getIdStr());
        order.setCreateTime(LocalDateTime.now());
        this.save(order);

        // ==========================================
        // 3. 🚨 核心改造：废弃原有的 Redis 暂存观演人逻辑
        // 立即将订单与观演人的绑定关系落入 tb_order_spectator 表
        // ==========================================
        for (Long specId : spectatorIds) {
            OrderSpectator os = new OrderSpectator();
            os.setOrderId(order.getId());
            os.setEventId(order.getEventId());
            os.setSpectatorId(specId);
            os.setDeleteToken(0L); // 0 代表当前正处于活跃状态（待支付/已支付）

            // 🚨 触发点：如果 Redis 预检漏过了并发请求，这里的 insert 会触发
            // uk_event_spectator 唯一索引冲突，直接抛出 DuplicateKeyException 异常，
            // 阻断本次写库，并将异常抛给上一层的 Kafka 消费者进行“出票失败”的善后处理。
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
            redisLockKeys.add("event:spectator:lock:" + order.getEventId() + ":" + os.getSpectatorId());
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

        if (status != null && !"all".equals(status)) {
            wrapper.eq(Order::getStatus, Integer.parseInt(status));
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
            OrderVO.EventVO eventVO = new OrderVO.EventVO();
            if (event != null) {
                eventVO.setName(event.getTitle());
                eventVO.setPoster(event.getPosterUrl());
                eventVO.setCity(event.getCity()); // 假设你的 Event 实体有 city 字段
                eventVO.setVenue(event.getVenue());
                eventVO.setTime(event.getShowTime() != null ? event.getShowTime().format(formatter) : "时间待定");
            }
            vo.setEvent(eventVO);
            vo.setEventId(order.getEventId().toString());

            if (order.getStatus() == 3 || order.getStatus() == 6) {
                // 假设你有 payTime 字段，如果没有可以用 updateTime 代替展示
                vo.setPaymentMethod("支付宝支付"); // 这里可以根据实际业务取值
            }

            // 2.2 组装电子票信息
            List<OrderVO.TicketVO> ticketVOs = new ArrayList<>();
            TicketCategory category = ticketMapper.selectById(order.getTicketId());
            String categoryName = category != null ? category.getName() : "未知票档";

            if (order.getStatus() == 1 || order.getStatus() == 2) {
                // 状态A：待支付或已取消 -> 根本还没出票
                for (int i = 0; i < order.getQuantity(); i++) {
                    OrderVO.TicketVO tVO = new OrderVO.TicketVO();
                    tVO.setId("T_PENDING_" + order.getId() + "_" + i);
                    tVO.setName(categoryName);
                    tVO.setCheckStatus(4); // 🚨 触发前端需求11：后台配座中(未出票)
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
                        tVO.setCheckStatus(4); // 🚨 同样展示后台配座中
                        ticketVOs.add(tVO);
                    }
                } else {
                    // 状态D：完美出票，下发座位号和二维码
                    for (OrderTicket rt : realTickets) {
                        OrderVO.TicketVO tVO = new OrderVO.TicketVO();
                        tVO.setId(rt.getId().toString());
                        tVO.setName(rt.getTicketName());
                        tVO.setSeatInfo(rt.getSeatInfo());
                        tVO.setCheckStatus(rt.getCheckStatus());
                        tVO.setQrCode(rt.getQrCode()); // 生成的 uuid 核销码
                        ticketVOs.add(tVO);
                    }
                }
            }
            vo.setTickets(ticketVOs);
            voList.add(vo);
        }
        return voList;
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
