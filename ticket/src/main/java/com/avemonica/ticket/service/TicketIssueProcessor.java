package com.avemonica.ticket.service;

import com.avemonica.ticket.dto.TicketIssueMessage;
import com.avemonica.ticket.entity.OrderTicket;
import com.avemonica.ticket.entity.Spectator;
import com.avemonica.ticket.mapper.InboxEventMapper;
import com.avemonica.ticket.mapper.SpectatorMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TicketIssueProcessor {

    public static final String CONSUMER_GROUP = "ticket-issue-group";

    private final InboxEventMapper inboxEventMapper;
    private final SpectatorMapper spectatorMapper;
    private final OrderTicketService orderTicketService;

    public TicketIssueProcessor(
            InboxEventMapper inboxEventMapper,
            SpectatorMapper spectatorMapper,
            OrderTicketService orderTicketService
    ) {
        this.inboxEventMapper = inboxEventMapper;
        this.spectatorMapper = spectatorMapper;
        this.orderTicketService = orderTicketService;
    }

    /**
     * 返回：
     * true  = 本次真正执行了出票
     * false = 该Outbox事件以前已经处理过
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean processOnce(
            String outboxEventId,
            String eventType,
            TicketIssueMessage message
    ) {
        validateMessage(outboxEventId, message);

        /*
         * 第一步先抢占 Inbox。
         *
         * 这一条 INSERT 和下面的出票属于同一个 MySQL 事务。
         */
        int inserted = inboxEventMapper.insertIgnore(
                CONSUMER_GROUP,
                outboxEventId,
                eventType,
                String.valueOf(message.getOrderId())
        );

        /*
         * inserted = 0：
         * 说明以前已经成功消费过该 Outbox Event。
         *
         * 绝对不能再次生成电子票。
         */
        if (inserted == 0) {
            return false;
        }

        List<OrderTicket> tickets = new ArrayList<>();

        for (Long spectatorId : message.getSpectatorIds()) {

            Spectator spectator = spectatorMapper.selectById(spectatorId);

            if (spectator == null) {
                throw new RuntimeException(
                        "出票失败：观演人不存在，spectatorId=" + spectatorId
                );
            }

            OrderTicket ticket = new OrderTicket();

            ticket.setOrderId(message.getOrderId());
            ticket.setEventId(message.getEventId());

            // 如果当前 OrderTicket 已经有 sessionId 字段
            ticket.setSessionId(message.getSessionId());

            ticket.setTicketId(message.getTicketId());
            ticket.setTicketName(message.getTicketName());

            ticket.setSpectatorId(spectatorId);
            ticket.setSpectatorName(spectator.getName());
            ticket.setSpectatorIdCard(spectator.getIdCard());

            ticket.setSeatInfo(null);

            ticket.setQrCode(
                    UUID.randomUUID()
                            .toString()
                            .replace("-", "")
            );

            // 1 = 未检票
            ticket.setCheckStatus(1);

            tickets.add(ticket);
        }

        if (tickets.isEmpty()) {
            throw new RuntimeException("出票失败：没有生成任何电子票");
        }

        boolean success = orderTicketService.saveBatch(tickets);

        if (!success) {
            throw new RuntimeException("电子票批量落库失败");
        }

        return true;
    }

    private void validateMessage(
            String outboxEventId,
            TicketIssueMessage message
    ) {
        if (!StringUtils.hasText(outboxEventId)) {
            throw new RuntimeException("出票失败：Outbox Event ID为空");
        }

        if (message == null) {
            throw new RuntimeException("出票消息为空");
        }

        if (message.getOrderId() == null) {
            throw new RuntimeException("出票失败：订单ID为空");
        }

        if (message.getEventId() == null) {
            throw new RuntimeException("出票失败：演出ID为空");
        }

        if (message.getSessionId() == null) {
            throw new RuntimeException("出票失败：场次ID为空");
        }

        if (message.getTicketId() == null) {
            throw new RuntimeException("出票失败：票档ID为空");
        }

        if (message.getSpectatorIds() == null
                || message.getSpectatorIds().isEmpty()) {
            throw new RuntimeException("出票失败：观演人为空");
        }
    }
}