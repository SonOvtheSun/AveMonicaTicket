package com.avemonica.ticket.service.impl;
import com.avemonica.ticket.entity.OrderTicket;
import com.avemonica.ticket.mapper.OrderTicketMapper;
import com.avemonica.ticket.service.OrderTicketService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class OrderTicketServiceImpl extends ServiceImpl<OrderTicketMapper, OrderTicket> implements OrderTicketService {}