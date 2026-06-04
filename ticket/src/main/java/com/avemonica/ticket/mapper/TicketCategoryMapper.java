package com.avemonica.ticket.mapper;

import com.avemonica.ticket.entity.TicketCategory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TicketCategoryMapper extends BaseMapper<TicketCategory> {
    /**
     * 🚨 核心防超卖 SQL：
     * 只有在 remaining_stock >= 购买数量 的前提下，才会执行扣减。
     * 如果库存不足，数据库会拒绝更新，返回受影响行数为 0。
     */
    @Update("UPDATE tb_ticket_category SET remaining_stock = remaining_stock - #{quantity} " +
            "WHERE id = #{ticketId} AND remaining_stock >= #{quantity}")
    int deductStock(@Param("ticketId") Long ticketId, @Param("quantity") Integer quantity);

    @Update("UPDATE tb_ticket_category SET remaining_stock = remaining_stock + #{quantity} WHERE id = #{id}")
    void addStock(Long ticketId, Integer quantity);
}