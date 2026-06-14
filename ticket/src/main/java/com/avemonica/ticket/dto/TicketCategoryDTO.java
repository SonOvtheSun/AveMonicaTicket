package com.avemonica.ticket.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class TicketCategoryDTO {
    private Long id;
    private String name;      // 票档名称 (如: 普通票)
    private BigDecimal price; // 价格
    private Integer stock;    // 库存
}