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
     * Kafka 会确保同一个消费组内，一条消息只被一个消费者实例消费。
     */
    @KafkaListener(topics = "order-ticket-issue-topic", groupId = "ticket-issue-group")
    @Transactional(rollbackFor = Exception.class)
    public void consumeTicketIssueMessage(String jsonMessage) {
        try {
            TicketIssueMessage message = objectMapper.readValue(jsonMessage, TicketIssueMessage.class);

            Long orderId = message.getOrderId();
            Long eventId = message.getEventId();
            Long sessionId = message.getSessionId();
            Long ticketId = message.getTicketId();
            String ticketName = message.getTicketName();
            List<Long> spectatorIds = message.getSpectatorIds();

            if (orderId == null) {
                throw new RuntimeException("出票失败：订单ID为空");
            }
            if (eventId == null) {
                throw new RuntimeException("出票失败：演出ID为空");
            }
            if (sessionId == null) {
                throw new RuntimeException("出票失败：场次ID为空");
            }
            if (ticketId == null) {
                throw new RuntimeException("出票失败：票档ID为空");
            }
            if (spectatorIds == null || spectatorIds.isEmpty()) {
                throw new RuntimeException("出票失败：观演人为空");
            }

            log.info(
                    "MQ 收到异步出票任务，开始处理。orderId={}, eventId={}, sessionId={}, ticketId={}, spectatorCount={}",
                    orderId,
                    eventId,
                    sessionId,
                    ticketId,
                    spectatorIds.size()
            );

            List<OrderTicket> orderTicketList = new ArrayList<>();

            for (Long specId : spectatorIds) {
                Spectator spectator = spectatorMapper.selectById(specId);
                if (spectator == null) {
                    throw new RuntimeException("出票失败：观演人不存在，spectatorId=" + specId);
                }

                OrderTicket ticketInstance = new OrderTicket();

                ticketInstance.setOrderId(orderId);
                ticketInstance.setEventId(eventId);

                // 核心新增：写入具体时间场次ID
                ticketInstance.setSessionId(sessionId);

                ticketInstance.setTicketId(ticketId);
                ticketInstance.setTicketName(ticketName);

                ticketInstance.setSpectatorId(specId);
                ticketInstance.setSpectatorName(spectator.getName());
                ticketInstance.setSpectatorIdCard(spectator.getIdCard());

                ticketInstance.setSeatInfo(null);
                ticketInstance.setQrCode(UUID.randomUUID().toString().replace("-", ""));

                // 建议保持和订单列表逻辑一致：1=未检票，2=已检票，4=未出票
                ticketInstance.setCheckStatus(1);

                orderTicketList.add(ticketInstance);
            }

            orderTicketService.saveBatch(orderTicketList);

            log.info(
                    "MQ 异步出票成功。orderId={}, eventId={}, sessionId={}, 出票数={}",
                    orderId,
                    eventId,
                    sessionId,
                    orderTicketList.size()
            );

        } catch (Exception e) {
            log.error("MQ 消费出票消息异常，消息内容={}", jsonMessage, e);
            throw new RuntimeException("处理出票失败", e);
        }
    }
}