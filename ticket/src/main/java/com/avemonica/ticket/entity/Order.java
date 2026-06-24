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

    /**
     * 退款状态：
     * 1申请中，2审核通过，3退款中，4退款完成，5审核拒绝，6退款失败
     */
    private Integer refundStatus;

    /**
     * 退款进度步骤：
     * 1申请退款，2后台审核，3金额退还，4退款完成
     */
    private Integer refundStep;

    /**
     * 失败/拒绝所在步骤：
     * 2审核拒绝，3退款失败
     */
    private Integer refundFailStep;

    /**
     * 退款失败或拒绝原因。
     */
    private String refundFailReason;

    /**
     * 金额退还成功时间。
     */
    private LocalDateTime refundReturnTime;

    /**
     * 退款完成时间。
     */
    private LocalDateTime refundFinishTime;

    /**
     * 用户端是否删除：
     * 0 未删除
     * 1 已删除
     */
    private Integer userDeleted;

    /**
     * 用户端删除时间。
     */
    private LocalDateTime userDeleteTime;
}
