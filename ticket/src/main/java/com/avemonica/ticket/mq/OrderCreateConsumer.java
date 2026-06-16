package com.avemonica.ticket.mq;

import com.avemonica.ticket.dto.OrderCreateMessage;
import com.avemonica.ticket.entity.Order;
import com.avemonica.ticket.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class OrderCreateConsumer {

    @Autowired
    private OrderService orderService;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @KafkaListener(topics = "order-create-topic", groupId = "order-create-group")
    public void consumeCreateOrder(String jsonMsg) {
        try {
            OrderCreateMessage msg = objectMapper.readValue(jsonMsg, OrderCreateMessage.class);
            String queueToken = msg.getQueueToken();

            if (msg.getUserId() == null
                    || msg.getEventId() == null
                    || msg.getSessionId() == null
                    || msg.getTicketId() == null
                    || msg.getQuantity() == null
                    || msg.getSpectatorIds() == null
                    || msg.getSpectatorIds().isEmpty()) {
                redisTemplate.opsForValue().set("order:result:" + queueToken, "FAIL:订单参数缺失，请重新下单", 10, TimeUnit.MINUTES);
                log.warn("创建订单消息参数缺失，msg={}", jsonMsg);
                return;
            }

            Order order = new Order();
            order.setUserId(msg.getUserId());
            order.setEventId(msg.getEventId());
            order.setSessionId(msg.getSessionId());
            order.setTicketId(msg.getTicketId());
            order.setQuantity(msg.getQuantity());

            try {
                // 1. 调用 Service 真实落库 (tb_order 和 tb_order_spectator)
                Order saveOrder = orderService.createTicketOrder(order, msg.getSpectatorIds());

                // 2. 落库成功！把真实的 orderId 写入 Redis 通知前端
                redisTemplate.opsForValue().set("order:result:" + queueToken, saveOrder.getId().toString(), 10, TimeUnit.MINUTES);

            } catch (DuplicateKeyException e) {
                // 🚨 终极防线触发：数据库抛出唯一索引报错，证明发生了复购或并发穿透
                releaseLocks(msg);
                redisTemplate.opsForValue().set("order:result:" + queueToken, "FAIL:系统检测到该观演人已有本场演出门票", 10, TimeUnit.MINUTES);
                log.warn("触发 MySQL 唯一索引拦截, UserID: {}", msg.getUserId());
            } catch (Exception e) {
                // 库存不足或其他业务异常
                releaseLocks(msg);
                redisTemplate.opsForValue().set("order:result:" + queueToken, "FAIL:" + e.getMessage(), 10, TimeUnit.MINUTES);
            }

        } catch (Exception e) {
            log.error("消费创建订单消息失败", e);
        }
    }

    private void releaseLocks(OrderCreateMessage msg) {
        List<String> lockKeys = msg.getSpectatorIds().stream()
                .map(id -> "event:spectator:lock:" + msg.getEventId() + ":" + msg.getSessionId() + ":" + id)
                .collect(Collectors.toList());
        redisTemplate.delete(lockKeys);
    }
}