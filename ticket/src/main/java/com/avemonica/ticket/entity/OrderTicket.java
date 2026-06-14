package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("tb_order_ticket")
public class OrderTicket {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;

    private Long eventId;
    private Long ticketId;
    private String ticketName;

    private Long spectatorId;
    private String spectatorName;
    private String spectatorIdCard;
    private String seatInfo;
    private String qrCode;
    private Integer checkStatus;
    private Long sessionId;
}