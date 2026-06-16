package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("tb_order")
public class Order {

    /**
     * 订单 ID，同时也是展示给用户看的订单号。
     * 规则：16 位随机数字。
     */
    @TableId(type = IdType.INPUT)
    private Long id;

    private Long eventId;

    private Long userId;

    private Long ticketId;

    private BigDecimal payPrice;

    /**
     * 订单状态：
     * 1 已创建，未支付
     * 2 已取消
     * 3 已完成订单
     * 4 申请退款中
     * 5 异常订单
     * 6 已支付但未检票
     * 7 已退票
     */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime expireTime;

    private Integer quantity;

    private Long sessionId;

    private String refundReason;

    private LocalDateTime refundApplyTime;

    private LocalDateTime refundAuditTime;

    private String refundRejectReason;

    private Long refundOperatorId;
}
