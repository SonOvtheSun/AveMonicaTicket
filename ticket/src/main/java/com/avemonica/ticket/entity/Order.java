package com.avemonica.ticket.entity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Date;

@Data
@TableName("tb_order")
public class Order {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long eventId;

    private Long userId;

    private Long ticketId;

    private BigDecimal payPrice;

    private Integer status;

    private LocalDateTime createTime;

    private Integer quantity;

    private String orderNo;

    private Long sessionId;
}
