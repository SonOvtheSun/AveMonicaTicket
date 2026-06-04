package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.entity.Spectator;
import com.avemonica.ticket.mapper.SpectatorMapper;
import com.avemonica.ticket.service.SmsService;
import com.avemonica.ticket.service.SpectatorService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@Service
public class SpectatorServiceImpl extends ServiceImpl<SpectatorMapper, Spectator> implements SpectatorService {
}
