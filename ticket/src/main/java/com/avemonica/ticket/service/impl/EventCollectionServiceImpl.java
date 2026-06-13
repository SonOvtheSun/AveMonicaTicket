package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.entity.EventArtist;
import com.avemonica.ticket.entity.EventCollection;
import com.avemonica.ticket.mapper.EventArtistMapper;
import com.avemonica.ticket.mapper.EventCollectionMapper;
import com.avemonica.ticket.service.EventArtistService;
import com.avemonica.ticket.service.EventCollectionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class EventCollectionServiceImpl extends ServiceImpl<EventCollectionMapper, EventCollection> implements EventCollectionService {
}
