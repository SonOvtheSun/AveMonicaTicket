package com.avemonica.ticket.mq;

import com.avemonica.ticket.dto.TicketIssueMessage;
import com.avemonica.ticket.entity.OrderTicket;
import com.avemonica.ticket.entity.Spectator;
import com.avemonica.ticket.mapper.SpectatorMapper;
import com.avemonica.ticket.service.OrderTicketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Slf4j
@Component
public class TicketIssueConsumer {

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SpectatorMapper spectatorMapper;
    @Autowired
    private OrderTicketService orderTicketService;

    /**
     * 监听出票 Topic。
     * groupId 用于标识消费组，Kafka 会确保同一个组内一条消息只被消费一次。
     */
    @KafkaListener(topics = "order-ticket-issue-topic", groupId = "ticket-issue-group")
    @Transactional(rollbackFor = Exception.class)
    public void consumeTicketIssueMessage(String jsonMessage) {
        try {
            // 1. 反序列化消息
            TicketIssueMessage message = objectMapper.readValue(jsonMessage, TicketIssueMessage.class);
            Long orderId = message.getOrderId();
            List<Long> spectatorIds = message.getSpectatorIds();

            log.info("MQ 收到异步出票任务，开始处理。订单 ID: {}", orderId);

            List<OrderTicket> orderTicketList = new ArrayList<>();

            // 2. 循环处理观演人，生成票务快照
            for (Long specId : spectatorIds) {
                Spectator spectator = spectatorMapper.selectById(specId);
                if (spectator == null) {
                    log.error("观演人不存在，跳过。specId: {}", specId);
                    continue; // 实际业务中这里可能需要标记订单异常
                }

                OrderTicket ticketInstance = new OrderTicket();
                ticketInstance.setOrderId(orderId);
                ticketInstance.setEventId(message.getEventId());

                ticketInstance.setSpectatorId(specId);
                ticketInstance.setTicketId(message.getTicketId());
                ticketInstance.setTicketName(message.getTicketName());
                ticketInstance.setSpectatorName(spectator.getName());
                ticketInstance.setSpectatorIdCard(spectator.getIdCard());
                ticketInstance.setQrCode(UUID.randomUUID().toString().replace("-", ""));
                ticketInstance.setCheckStatus(0);

                orderTicketList.add(ticketInstance);
            }

            // 3. 批量落库
            if (!orderTicketList.isEmpty()) {
                orderTicketService.saveBatch(orderTicketList);
                log.info("MQ 异步出票成功！订单 ID: {}, 出票数: {}", orderId, orderTicketList.size());
            }

        } catch (Exception e) {
            log.error("MQ 消费出票消息异常, 消息内容: {}", jsonMessage, e);
            // 注意：如果抛出异常，根据 Kafka 配置，可能会触发死信队列(DLQ)或不断重试
            throw new RuntimeException("处理出票失败", e);
        }
    }
}