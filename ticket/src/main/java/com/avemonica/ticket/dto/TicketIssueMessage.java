package com.avemonica.ticket.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor // 反序列化需要无参构造
public class TicketIssueMessage {
    private Long orderId;
    private Long eventId;
    private Long ticketId;
    private String ticketName; // 冗余票档名称，减轻消费者查库压力
    private List<Long> spectatorIds;

    public TicketIssueMessage(Long orderId, Long eventId, Long ticketId, String ticketName, List<Long> spectatorIds) {
        this.orderId = orderId;
        this.eventId = eventId;
        this.ticketId = ticketId;
        this.ticketName = ticketName;
        this.spectatorIds = spectatorIds;
    }
}