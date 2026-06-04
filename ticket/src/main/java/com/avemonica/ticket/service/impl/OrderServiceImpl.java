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


}
