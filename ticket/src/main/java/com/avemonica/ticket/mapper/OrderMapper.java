package com.avemonica.ticket.mapper;

import com.avemonica.ticket.entity.Order;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 订单 Mapper。
 *
 * 后台订单管理的聚合查询放在 Mapper XML 里，
 * 不在 OrderServiceImpl 里手动 build 多表 Map。
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    Long countAdminOrders(@Param("query") Map<String, Object> query);

    List<Map<String, Object>> selectAdminOrderPage(@Param("query") Map<String, Object> query);

    Map<String, Object> selectAdminOrderDetail(@Param("id") Long id);

    List<Map<String, Object>> selectAdminOrderSpectators(@Param("orderIds") List<Long> orderIds);

    List<Map<String, Object>> selectAdminOrderTickets(@Param("orderIds") List<Long> orderIds);
}
