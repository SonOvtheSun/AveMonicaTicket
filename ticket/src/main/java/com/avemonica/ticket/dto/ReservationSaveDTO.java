package com.avemonica.ticket.dto;

import lombok.Data;
import java.util.List;

@Data
public class ReservationSaveDTO {
    private Long eventId;
    private Long ticketId;
    private List<Long> spectatorIds; // 接收前端的数组
    private Long sessionId;
}