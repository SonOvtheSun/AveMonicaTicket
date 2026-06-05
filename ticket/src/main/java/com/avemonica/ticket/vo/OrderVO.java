package com.avemonica.ticket.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class OrderVO {
    private String id;              // 订单ID
    private String createTime;      // 创建时间
    private Integer status;         // 订单状态 (1待支付, 2已取消, 3已完成, 6待检票)
    private BigDecimal totalAmount; // 实付款金额
    private EventVO event;          // 绑定的演出信息
    private List<TicketVO> tickets; // 绑定的电子票集合
    private String eventId;         // 🚨 新增：用于前端点击跳转
    private String payTime;         // 🚨 新增：支付时间
    private String paymentMethod;   // 🚨 新增：支付方式 (如：支付宝支付)

    @Data
    public static class EventVO {
        private String name;
        private String poster;
        private String city;
        private String venue;
        private String time;
    }

    @Data
    public static class TicketVO {
        private String id;
        private String name;
        private String seatInfo;
        private Integer checkStatus; // 1:未检票, 2:已检票, 4:后台配座中(未出票)
        private String qrCode;
    }
}