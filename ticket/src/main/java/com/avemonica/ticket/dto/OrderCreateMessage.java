package com.avemonica.ticket.dto;

import lombok.Data;
import java.util.List;

@Data
public class OrderCreateMessage {

    /**
     * 排队令牌，用于前端轮询抢票结果
     */
    private String queueToken;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 演出ID，例如：上海站
     */
    private Long eventId;

    /**
     * 具体时间场次ID，例如：下午场 / 晚场
     */
    private Long sessionId;

    /**
     * 票档ID
     */
    private Long ticketId;

    /**
     * 购买数量
     */
    private Integer quantity;

    /**
     * 绑定的观演人集合
     */
    private List<Long> spectatorIds;
}