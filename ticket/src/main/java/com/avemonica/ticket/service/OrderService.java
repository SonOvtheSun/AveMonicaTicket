package com.avemonica.ticket.service;

import com.avemonica.ticket.dto.EventAddDTO;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.entity.Order;
import com.avemonica.ticket.vo.OrderVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface OrderService extends IService<Order>{
    Order createTicketOrder(Order order, List<Long> spectatorIds);
    void cancelOrder(Long orderId, Long userId);
    List<OrderVO> getUserOrderList(Long userId, String status);
    void deleteOrder(Long orderId, Long userId);
}
