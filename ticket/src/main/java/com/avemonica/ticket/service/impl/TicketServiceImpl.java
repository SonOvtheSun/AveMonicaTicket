package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.entity.TicketCategory;
import com.avemonica.ticket.mapper.TicketCategoryMapper;
import com.avemonica.ticket.service.TicketService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class TicketServiceImpl extends ServiceImpl<TicketCategoryMapper, TicketCategory> implements TicketService {

}