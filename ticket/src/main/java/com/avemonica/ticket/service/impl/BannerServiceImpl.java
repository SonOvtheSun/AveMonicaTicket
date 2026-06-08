package com.avemonica.ticket.service.impl;
import com.avemonica.ticket.entity.Banner;
import com.avemonica.ticket.mapper.BannerMapper;
import com.avemonica.ticket.service.BannerService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class BannerServiceImpl extends ServiceImpl<BannerMapper, Banner> implements BannerService {}