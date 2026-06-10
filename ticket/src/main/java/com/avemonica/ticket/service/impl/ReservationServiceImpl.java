package com.avemonica.ticket.service.impl;
import com.avemonica.ticket.entity.EventReservation;
import com.avemonica.ticket.entity.OrderTicket;
import com.avemonica.ticket.mapper.OrderTicketMapper;
import com.avemonica.ticket.mapper.ReservationMapper;
import com.avemonica.ticket.service.OrderTicketService;
import com.avemonica.ticket.service.ReservationService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class ReservationServiceImpl extends ServiceImpl<ReservationMapper, EventReservation> implements ReservationService {
}
