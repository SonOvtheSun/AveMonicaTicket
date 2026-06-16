package com.avemonica.ticket.service;

import com.avemonica.ticket.entity.Order;
import com.avemonica.ticket.vo.OrderVO;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Map;

public interface OrderService extends IService<Order> {

    Order createTicketOrder(Order order, List<Long> spectatorIds);

    void cancelOrder(Long orderId, Long userId);

    List<OrderVO> getUserOrderList(Long userId, String status);

    void deleteOrder(Long orderId, Long userId);

    /**
     * 后台订单管理分页。
     */
    Map<String, Object> pageAdminOrders(Integer current,
                                        Integer size,
                                        Integer status,
                                        String searchType,
                                        String keyword);

    /**
     * 后台订单详情。
     */
    Map<String, Object> getAdminOrderDetail(Long id);

    /**
     * 后台退票审核。
     */
    void auditRefund(Map<String, Object> body);

    void applyRefund(Long orderId, Long userId, String reason);
}
