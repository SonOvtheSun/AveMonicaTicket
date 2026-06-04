package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.entity.Address;
import com.avemonica.ticket.entity.Artist;
import com.avemonica.ticket.mapper.AddressMapper;
import com.avemonica.ticket.mapper.ArtistMapper;
import com.avemonica.ticket.service.AddressService;
import com.avemonica.ticket.service.ArtistService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class AddressServiceImpl extends ServiceImpl<AddressMapper, Address> implements AddressService {
}
