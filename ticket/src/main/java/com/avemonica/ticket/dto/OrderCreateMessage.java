package com.avemonica.ticket.dto;
import lombok.Data;
import java.util.List;

@Data
public class OrderCreateMessage {
    private String queueToken;     // 排队令牌
    private Long userId;           // 用户ID
    private Long eventId;          // 演出ID
    private Long ticketId;         // 票档ID
    private Integer quantity;      // 购买数量
    private List<Long> spectatorIds; // 绑定的观演人集合
}