package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.entity.Order;
import org.springframework.security.core.userdetails.User;
import com.avemonica.ticket.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.neo4j.Neo4jProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/order")
public class OrderController {
    @Autowired
    private OrderService orderService;

    @Autowired
    private StringRedisTemplate redisTemplate; // 用于处理高并发频次限制和限流

    private static final int MAX_ORDERS_AT_ONE_TIME = 100;

    private static final int MAX_CLICK_PER_FIVE_SECOND = 100;

    @PostMapping("/pre-check")
    public Result<String> preCheck(@RequestBody Map<String, Object> params, HttpServletRequest request) {
        // ==========================================
        // 1. 动态获取当前登录用户 ID (替换掉 123L)
        // ==========================================
        Long userId = null;
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String userIdStr = ((User) auth.getPrincipal()).getUsername();
            userId = Long.valueOf(userIdStr);
        } catch (Exception e) {
            return Result.error(502,"登录已过期，请重新登录");
        }
        Long eventId = Long.valueOf(params.get("eventId").toString());

        // ==========================================
        // 2. 逻辑点 3：同一账户点击次数过多 (升级为滑动计数器)
        // ==========================================
        String userIdCountKey = "order:lock:user:" + userId;
        Long clickCount = redisTemplate.opsForValue().increment(userIdCountKey);

        if(clickCount != null && clickCount == 1) {
            redisTemplate.expire(userIdCountKey, 3,  TimeUnit.SECONDS);
        }

        if(clickCount != null && clickCount > 3){
            // 5秒内点击超过 3 次，判定为频繁点击或脚本刷单
            return Result.error(2001, "请求过于频繁，请输入验证码验证");
        }

        // ==========================================
        // 3. 逻辑点 4：服务器限流 (限制接口 QPS)
        // ==========================================
        String serverFlowKey = "order:flow:count:" + eventId;
        Long currentFlow = redisTemplate.opsForValue().increment(serverFlowKey);

        if (currentFlow != null && currentFlow == 1) {
            // 初始化全局计数器过期时间为 1 秒 (意味着限制的是每秒的 QPS)
            redisTemplate.expire(serverFlowKey, 1, TimeUnit.SECONDS);
        }

        if (currentFlow != null && currentFlow > MAX_ORDERS_AT_ONE_TIME) {
            // 超过最大允许进单量，执行随机降级策略
            if (Math.random() > 0.3) { // 70% 概率随机拒绝，防止后端全盘崩溃
                return Result.error("当前抢票人数过多，排队拥挤，请稍后再试");
            }
        }

        return Result.success("恭喜进入订单确认页");
    }
}