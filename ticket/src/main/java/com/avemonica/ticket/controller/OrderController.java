package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.dto.OrderCreateMessage;
import com.avemonica.ticket.dto.TicketIssueMessage;
import com.avemonica.ticket.entity.Order;
import com.avemonica.ticket.entity.TicketCategory;
import com.avemonica.ticket.mapper.TicketCategoryMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.userdetails.User;
import com.avemonica.ticket.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.neo4j.Neo4jProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import jakarta.annotation.PostConstruct;
import java.util.Collections;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/order")
public class OrderController {
    @Autowired
    private OrderService orderService;

    @Autowired
    private StringRedisTemplate redisTemplate; // 用于处理高并发频次限制和限流

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TicketCategoryMapper ticketCategoryMapper;

    private static final int MAX_ORDERS_AT_ONE_TIME = 100;

    private static final int MAX_CLICK_PER_FIVE_SECOND = 100;

    private DefaultRedisScript<Long> tokenBucketScript;

    private static final String BUCKET_CAPACITY = "150";
    // 定义每秒放入的令牌数 (恒定处理速率 QPS)
    private static final String BUCKET_RATE = "100";

    @Autowired
    private com.avemonica.ticket.mapper.OrderSpectatorMapper orderSpectatorMapper;

    // 🚨 1. 声明观演人锁定 Lua 脚本
    private DefaultRedisScript<Long> spectatorLockScript;

    // 🚨 2. Lua 脚本：原子性检查多把锁，若都不存在，则全部加锁
    private static final String SPECTATOR_LOCK_LUA =
            "for i, key in ipairs(KEYS) do \n" +
                    "   if redis.call('EXISTS', key) == 1 then \n" +
                    "       return 0 \n" + // 只要有一个人被锁了，立刻返回 0 (失败)
                    "   end \n" +
                    "end \n" +
                    "for i, key in ipairs(KEYS) do \n" +
                    "   redis.call('SET', key, '1', 'EX', ARGV[1]) \n" + // 全部加锁，并设置过期时间
                    "end \n" +
                    "return 1 \n"; // 成功

    @PostConstruct
    public void init() {
        tokenBucketScript = new DefaultRedisScript<>();
        tokenBucketScript.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        tokenBucketScript.setResultType(Long.class);

        // 🚨 3. 初始化观演人锁脚本
        spectatorLockScript = new DefaultRedisScript<>(SPECTATOR_LOCK_LUA, Long.class);
    }


    @PostMapping("/pre-check")
    public Result<String> preCheck(@RequestBody Map<String, Object> params, HttpServletRequest request) {
        // ==========================================
        // 1. 获取用户信息 (保持不变)
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
        // 2. 逻辑点 3：同一账户防刷防连点
        // ==========================================
        // 注意：单用户的防刷机制，使用原有的固定窗口计数器其实足够了，
        // 因为它的目的是"防作弊"，而不是"平滑流量"。
        String userIdCountKey = "order:lock:user:" + userId;
        Long clickCount = redisTemplate.opsForValue().increment(userIdCountKey);

        if(clickCount != null && clickCount == 1) {
            redisTemplate.expire(userIdCountKey, 3,  TimeUnit.SECONDS);
        }

        if(clickCount != null && clickCount > 3){
            return Result.error(2001, "请求过于频繁，请输入验证码验证");
        }

        // ==========================================
        // 3. 🚨 逻辑点 4：服务器全局限流 (升级为分布式令牌桶)
        // ==========================================
        String serverFlowKey = "order:flow:bucket:" + eventId;

        // 执行 Lua 脚本，传入活动限流 Key、桶容量(允许瞬时突发)、生成速率(稳定QPS)
        Long result = redisTemplate.execute(
                tokenBucketScript,
                Collections.singletonList(serverFlowKey),
                BUCKET_CAPACITY,
                BUCKET_RATE
        );

        if (result != null && result == 0L) {
            // 返回 0 代表桶空了，没有拿到令牌，执行随机降级策略
            return Result.error("当前抢票人数过多，排队拥挤，请稍后再试");
        }

        return Result.success("恭喜进入订单确认页");
    }

    @PostMapping("/create")
    public Result<String> createOrder(@RequestBody Map<String, Object> params){
        Long userId = null;
        try{
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            User principal = (User) auth.getPrincipal();
            userId = Long.valueOf(principal.getUsername());
        } catch (Exception e) {
            return Result.error("登陆已过期，请重新登录");
        }

        Long eventId = Long.valueOf(params.get("eventId").toString());
        Long ticketId = Long.valueOf(params.get("ticketId").toString());
        int quantity = Integer.parseInt(params.get("quantity").toString());

        Object rawSpectators = params.get("spectatorIds");
        if (!(rawSpectators instanceof List)) {
            return Result.error("非法请求：观演人参数格式错误！");
        }

        // 🚨 安全转换：先转为未定类型的 List，再把每一个元素转成 String 最后转成 Long
        List<Long> spectatorIds = ((java.util.List<?>) rawSpectators).stream()
                .map(id -> Long.valueOf(id.toString()))
                .toList(); // 如果你的 JDK 版本低于 16，请换成 .collect(Collectors.toList())

        if (spectatorIds.size() != quantity) {
            return Result.error("非法请求：实名观演人数量与购票数量不符！");
        }

        // ==========================================
        // 1. 内存极速预检：历史已购永久名单 (Set)
        // ==========================================
        String purchasedSetKey = "event:purchased:spectators:" + eventId;
        for (Long specId : spectatorIds) {
            Boolean hasPurchased = redisTemplate.opsForSet().isMember(purchasedSetKey, String.valueOf(specId));
            if (Boolean.TRUE.equals(hasPurchased)) {
                return Result.error("您选择的观演人中已有人购买过本场演出门票，请勿重复购买！");
            }
        }

        // ==========================================
        // 🚨 4. 核心：高并发一票一证防御 (锁定 10 分钟)
        // ==========================================
        // 组装所有需要锁定的 Redis Key
        List<String> lockKeys = spectatorIds.stream()
                .map(id -> "event:spectator:lock:" + eventId + ":" + id)
                .toList(); // 如果 JDK 版本较低，请换成 .collect(Collectors.toList())

        // 执行 Lua 脚本，传入 Keys 和过期时间 (10分钟 = 600秒，与订单未支付自动取消的时间保持一致)
        Long lockResult = redisTemplate.execute(spectatorLockScript, lockKeys, "600");

        if (lockResult == null || lockResult == 0L) {
            // 只要没拿到锁，说明此人 10 分钟内下过这场的单（还在待支付状态），或者刚刚用其他设备抢过了
            return Result.error("您选择的观演人中，有人已有本场演出的待支付订单或已购票，请勿重复购买！");
        }

        // ==========================================
        // 3. 预检通过，生成排队 Token，发送给 Kafka
        // ==========================================
        String queueToken = java.util.UUID.randomUUID().toString().replace("-", "");

        OrderCreateMessage msg = new OrderCreateMessage();
        msg.setQueueToken(queueToken);
        msg.setUserId(userId);
        msg.setEventId(eventId);
        msg.setTicketId(ticketId);
        msg.setQuantity(quantity);
        msg.setSpectatorIds(spectatorIds);

        try {
            // 将下单任务丢给 MQ，绝不在此处查询/写入 MySQL
            kafkaTemplate.send("order-create-topic", objectMapper.writeValueAsString(msg));

            // 返回给前端排队标记
            return Result.success("排队中", queueToken);
        } catch (Exception e) {
            redisTemplate.delete(lockKeys); // 补偿：MQ 发送失败，释放短锁
            return Result.error("系统繁忙，请稍后再试");
        }
    }

    /**
     * 主动取消订单
     */
    @PostMapping("/cancel/{orderId}")
    public Result<String> cancelOrder(@PathVariable Long orderId) {
        Long userId = null;
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            userId = Long.valueOf(((User) auth.getPrincipal()).getUsername());
        } catch (Exception e) {
            return Result.error("登录已过期，请重新登录");
        }

        try {
            orderService.cancelOrder(orderId, userId);
            return Result.success("订单已取消，观演人购票资格已释放");
        } catch (Exception e) {
            return Result.error("取消失败：" + e.getMessage());
        }
    }

    /**
     * 供前端每秒轮询排队结果的接口
     */
    @GetMapping("/result/{queueToken}")
    public Result<String> getOrderResult(@PathVariable String queueToken) {
        String result = redisTemplate.opsForValue().get("order:result:" + queueToken);
        // 如果为空，前端会继续轮询；如果有值，前端直接取用
        return Result.success(result);
    }



    @PostMapping("/pay")
    public Result<String> payOrder(@RequestBody Map<String, Long> params) {
        Long orderId = params.get("orderId");
        Order order = orderService.getById(orderId);

        if (order == null) return Result.error("订单不存在");
        if (order.getStatus() != 1) return Result.error("订单当前状态无法支付");

        // 支付成功，状态修改为 3：已支付
        order.setStatus(3);
        orderService.updateById(order);

        try {
            // ==========================================
            // 🚨 核心修复 1：不再去 Redis 找，直接从独立关系表中查出观演人
            // ==========================================
            List<com.avemonica.ticket.entity.OrderSpectator> osList = orderSpectatorMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.avemonica.ticket.entity.OrderSpectator>()
                            .eq(com.avemonica.ticket.entity.OrderSpectator::getOrderId, orderId)
            );

            // 提取出观演人的 ID 集合
            List<Long> spectatorIds = osList.stream()
                    .map(com.avemonica.ticket.entity.OrderSpectator::getSpectatorId)
                    .toList(); // JDK 16 以下用 .collect(Collectors.toList())

            if (!spectatorIds.isEmpty()) {
                // 查询票档名称 (利用缓存或直接查表)
                TicketCategory ticket = ticketCategoryMapper.selectById(order.getTicketId());
                String ticketName = (ticket != null) ? ticket.getName() : "未知票档";

                // ==========================================
                // 🚨 核心修复 2：发送出票消息给 Kafka
                // ==========================================
                TicketIssueMessage message = new TicketIssueMessage(
                        orderId,
                        order.getEventId(),
                        order.getTicketId(),
                        ticketName,
                        spectatorIds
                );
                kafkaTemplate.send("order-ticket-issue-topic", objectMapper.writeValueAsString(message));

                // ==========================================
                // 🚨 核心修复 3：支付彻底成功，将他们加入 Redis 永久“已购名单”
                // ==========================================
                String purchasedSetKey = "event:purchased:spectators:" + order.getEventId();
                String[] specIdArray = spectatorIds.stream().map(String::valueOf).toArray(String[]::new);

                redisTemplate.opsForSet().add(purchasedSetKey, specIdArray);
                redisTemplate.expire(purchasedSetKey, 30, TimeUnit.DAYS); // 演出结束后自动清理内存
            }

        } catch (Exception e) {
            e.printStackTrace();
            // 工业界这里会引入重试机制、死信队列或记录报警，防止付了钱却没出票
        }

        return Result.success("支付成功，正在为您出票");
    }
}