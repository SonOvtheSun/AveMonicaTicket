package com.avemonica.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;

/**
 * 票档实体类
 */
@Data
@TableName("tb_ticket_category")
public class TicketCategory {

    @TableId(type = IdType.AUTO)
    private Long id;

    // 关联的演出ID
    private Long eventId;

    // 票档名称 (如: VIP区)
    private String name;

    // 价格
    private BigDecimal price;

    // 总库存
    private Integer totalStock;

    // 剩余库存
    private Integer remainingStock;

    private Long sessionId;
}