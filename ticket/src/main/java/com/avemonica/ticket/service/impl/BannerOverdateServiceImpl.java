package com.avemonica.ticket.service.impl;
import com.avemonica.ticket.entity.Banner;
import com.avemonica.ticket.entity.BannerOverdate;
import com.avemonica.ticket.mapper.BannerMapper;
import com.avemonica.ticket.mapper.BannerOverdateMapper;
import com.avemonica.ticket.service.BannerOverdateService;
import com.avemonica.ticket.service.BannerService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class BannerOverdateServiceImpl extends ServiceImpl<BannerOverdateMapper, BannerOverdate> implements BannerOverdateService {}