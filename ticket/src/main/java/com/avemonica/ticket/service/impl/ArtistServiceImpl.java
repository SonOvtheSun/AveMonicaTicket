package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.entity.Artist;
import com.avemonica.ticket.mapper.ArtistMapper;
import com.avemonica.ticket.service.ArtistService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class ArtistServiceImpl extends ServiceImpl<ArtistMapper, Artist> implements ArtistService {
    // 基础的级联注入由 MyBatis-Plus 的 ServiceImpl 底层帮你自动打通
    // 以后如果需要写特别复杂的自定义艺人业务（比如同步第三方数据），再在这里写具体逻辑
}