package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.service.SmsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@Service
public class SmsServiceImpl implements SmsService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    // Redis Key 前缀
    private static final String CODE_PREFIX = "sms:code:";

    @Override
    public Result<String> sendCode(String phone) {
        String key = CODE_PREFIX + phone;

        // 1. 防刷校验：检查是否在 60 秒内发送过
        Long expireTime = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (expireTime != null && expireTime > (5 * 60 - 60)) {
            // 假设总过期时间是5分钟(300秒)，如果剩余时间大于240秒，说明发出去还不到60秒
            return Result.error("发送太频繁，请稍后再试");
        }

        // 2. 生成 4 位随机验证码
        String code = String.format("%04d", new Random().nextInt(10000));

        // 3. 存入 Redis，设置 5 分钟有效
        redisTemplate.opsForValue().set(key, code, 5, TimeUnit.MINUTES);

        // 4. 调用第三方短信 API 发送 (这里模拟打印到控制台)
        System.out.println("=====================================");
        System.out.println("【Ave Monica Ticket】向手机号 " + phone + " 发送验证码: " + code);
        System.out.println("验证码 5 分钟内有效。");
        System.out.println("=====================================");

        return Result.success("验证码已发送", null);
    }

    @Override
    public boolean verifyCode(String phone, String code) {
        if (checkCodeOnly(phone, code)) {
            // 验证成功后，删除验证码，防止重复使用
            consumeCode(phone);
            return true;
        }
        return false;
    }

    @Override
    public boolean checkCodeOnly(String phone, String code) {
        String key = CODE_PREFIX + phone;
        String redisCode = redisTemplate.opsForValue().get(key);
        return redisCode != null && redisCode.equals(code);
    }

    @Override
    public void consumeCode(String phone) {
        String key = CODE_PREFIX + phone;
        redisTemplate.delete(key);
    }
}
