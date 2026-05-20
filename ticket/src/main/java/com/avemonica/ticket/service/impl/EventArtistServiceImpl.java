package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.entity.EventArtist;
import com.avemonica.ticket.mapper.EventArtistMapper;
import com.avemonica.ticket.service.EventArtistService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class EventArtistServiceImpl extends ServiceImpl<EventArtistMapper, EventArtist> implements EventArtistService {
}