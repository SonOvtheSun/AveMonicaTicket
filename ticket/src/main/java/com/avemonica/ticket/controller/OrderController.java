package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.dto.OrderCreateMessage;
import com.avemonica.ticket.dto.TicketIssueMessage;
import com.avemonica.ticket.entity.Order;
import com.avemonica.ticket.entity.OrderSpectator;
import com.avemonica.ticket.entity.TicketCategory;
import com.avemonica.ticket.mapper.OrderSpectatorMapper;
import com.avemonica.ticket.mapper.TicketCategoryMapper;
import com.avemonica.ticket.service.OrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/order")
public class OrderController {

    // ==========================================
    // 依赖注入区
    // ==========================================
    @Autowired
    private OrderService orderService;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TicketCategoryMapper ticketCategoryMapper;
    @Autowired
    private OrderSpectatorMapper orderSpectatorMapper;

    // ==========================================
    // 常量与配置区 (消除硬编码)
    // ==========================================
    private static final String BUCKET_CAPACITY = "1500";
    private static final String BUCKET_RATE = "1000";

    // Redis Key 统一前缀管理
    private static final String KEY_USER_LOCK = "order:lock:user:";
    private static final String KEY_FLOW_BUCKET = "order:flow:bucket:";
    private static final String KEY_SUBMIT_TOKEN = "order:submit:token:";
    private static final String KEY_PURCHASED_SPEC = "event:purchased:spectators:";
    private static final String KEY_SPEC_LOCK = "event:spectator:lock:";
    private static final String KEY_ORDER_RESULT = "order:result:";

    private DefaultRedisScript<Long> tokenBucketScript;
    private DefaultRedisScript<Long> spectatorLockScript;

    private static final String SPECTATOR_LOCK_LUA =
            "for i, key in ipairs(KEYS) do \n" +
                    "   if redis.call('EXISTS', key) == 1 then return 0 end \n" +
                    "end \n" +
                    "for i, key in ipairs(KEYS) do \n" +
                    "   redis.call('SET', key, '1', 'EX', ARGV[1]) \n" +
                    "end \n" +
                    "return 1 \n";

    @PostConstruct
    public void init() {
        tokenBucketScript = new DefaultRedisScript<>();
        tokenBucketScript.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        tokenBucketScript.setResultType(Long.class);

        spectatorLockScript = new DefaultRedisScript<>(SPECTATOR_LOCK_LUA, Long.class);
    }

    // ==========================================
    // 接口区
    // ==========================================

    @PostMapping("/pre-check")
    public Result<String> preCheck(@RequestBody Map<String, Object> params, HttpServletRequest request) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(502, "登录已过期，请重新登录");

        Long eventId = Long.valueOf(params.get("eventId").toString());

        // 1. 同一账户防刷防连点
        String userIdCountKey = KEY_USER_LOCK + userId;
        Long clickCount = redisTemplate.opsForValue().increment(userIdCountKey);
        if (clickCount != null && clickCount == 1) {
            redisTemplate.expire(userIdCountKey, 3, TimeUnit.SECONDS);
        }
        if (clickCount != null && clickCount > 3) {
            return Result.error(2001, "请求过于频繁，请输入验证码验证");
        }

        // 2. 全局限流 (分布式令牌桶)
        String serverFlowKey = KEY_FLOW_BUCKET + eventId;
        Long result = redisTemplate.execute(tokenBucketScript, Collections.singletonList(serverFlowKey), BUCKET_CAPACITY, BUCKET_RATE);
        if (result != null && result == 0L) {
            return Result.error("当前抢票人数过多，排队拥挤，请稍后再试");
        }

        // 3. 生成一次性下单授权 Token
        String submitToken = generateToken();
        redisTemplate.opsForValue().set(KEY_SUBMIT_TOKEN + userId + ":" + eventId, submitToken, 30, TimeUnit.MINUTES);

        return Result.success("恭喜进入订单确认页");
    }

    @PostMapping("/create")
    public Result<String> createOrder(@RequestBody Map<String, Object> params) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(502, "登录已过期，请重新登录");

        Long eventId = Long.valueOf(params.get("eventId").toString());
        Long ticketId = Long.valueOf(params.get("ticketId").toString());
        int quantity = Integer.parseInt(params.get("quantity").toString());
        String clientToken = (String) params.get("submitToken");

        // 1. 防越权 API 绕过校验
        String redisTokenKey = KEY_SUBMIT_TOKEN + userId + ":" + eventId;
        String serverToken = redisTemplate.opsForValue().get(redisTokenKey);
        if (serverToken == null || !serverToken.equals(clientToken)) {
            return Result.error("非法请求或页面已过期，请重新从详情页进入！");
        }
        redisTemplate.delete(redisTokenKey);

        // 2. 观演人参数校验
        Object rawSpectators = params.get("spectatorIds");
        if (!(rawSpectators instanceof List)) return Result.error("非法请求：观演人参数格式错误！");

        List<Long> spectatorIds = ((List<?>) rawSpectators).stream()
                .map(id -> Long.valueOf(id.toString()))
                .toList();

        if (spectatorIds.size() != quantity) return Result.error("非法请求：实名观演人数量与购票数量不符！");

        // 3. 内存极速预检：历史已购永久名单
        String purchasedSetKey = KEY_PURCHASED_SPEC + eventId;
        for (Long specId : spectatorIds) {
            if (Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(purchasedSetKey, String.valueOf(specId)))) {
                return Result.error("您选择的观演人中已有人购买过本场演出门票，请勿重复购买！");
            }
        }

        // 4. 高并发一票一证防御 (锁定 10 分钟)
        List<String> lockKeys = spectatorIds.stream()
                .map(id -> KEY_SPEC_LOCK + eventId + ":" + id)
                .toList();

        Long lockResult = redisTemplate.execute(spectatorLockScript, lockKeys, "600");
        if (lockResult == null || lockResult == 0L) {
            return Result.error("您选择的观演人中，有人已有本场演出的待支付订单或已购票，请勿重复购买！");
        }

        // 5. 预检通过，发送至 Kafka 处理队列
        String queueToken = generateToken();
        OrderCreateMessage msg = new OrderCreateMessage();
        msg.setQueueToken(queueToken);
        msg.setUserId(userId);
        msg.setEventId(eventId);
        msg.setTicketId(ticketId);
        msg.setQuantity(quantity);
        msg.setSpectatorIds(spectatorIds);

        try {
            kafkaTemplate.send("order-create-topic", objectMapper.writeValueAsString(msg));
            return Result.success("排队中", queueToken);
        } catch (Exception e) {
            redisTemplate.delete(lockKeys); // 补偿机制
            return Result.error("系统繁忙，请稍后再试");
        }
    }

    @PostMapping("/cancel/{orderId}")
    public Result<String> cancelOrder(@PathVariable Long orderId) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(502, "登录已过期，请重新登录");

        try {
            orderService.cancelOrder(orderId, userId);
            return Result.success("订单已取消，观演人购票资格已释放");
        } catch (Exception e) {
            return Result.error("取消失败：" + e.getMessage());
        }
    }

    @GetMapping("/result/{queueToken}")
    public Result<String> getOrderResult(@PathVariable String queueToken) {
        String result = redisTemplate.opsForValue().get(KEY_ORDER_RESULT + queueToken);
        return Result.success(result);
    }

    @PostMapping("/pay")
    public Result<String> payOrder(@RequestBody Map<String, Long> params) {
        Long orderId = params.get("orderId");
        Order order = orderService.getById(orderId);

        if (order == null) return Result.error("订单不存在");
        if (order.getStatus() != 1) return Result.error("订单当前状态无法支付");

        order.setStatus(3);
        orderService.updateById(order);

        try {
            List<OrderSpectator> osList = orderSpectatorMapper.selectList(
                    new LambdaQueryWrapper<OrderSpectator>().eq(OrderSpectator::getOrderId, orderId)
            );

            List<Long> spectatorIds = osList.stream().map(OrderSpectator::getSpectatorId).toList();

            if (!spectatorIds.isEmpty()) {
                TicketCategory ticket = ticketCategoryMapper.selectById(order.getTicketId());
                String ticketName = (ticket != null) ? ticket.getName() : "未知票档";

                // 发送出票消息
                TicketIssueMessage message = new TicketIssueMessage(orderId, order.getEventId(), order.getTicketId(), ticketName, spectatorIds);
                kafkaTemplate.send("order-ticket-issue-topic", objectMapper.writeValueAsString(message));

                // 加入 Redis 永久已购名单
                String purchasedSetKey = KEY_PURCHASED_SPEC + order.getEventId();
                String[] specIdArray = spectatorIds.stream().map(String::valueOf).toArray(String[]::new);
                redisTemplate.opsForSet().add(purchasedSetKey, specIdArray);
                redisTemplate.expire(purchasedSetKey, 30, TimeUnit.DAYS);
            }
        } catch (Exception e) {
            e.printStackTrace();
            // TODO: 记录报警或抛入死信队列
        }

        return Result.success("支付成功，正在为您出票");
    }

    /**
     * 获取我的订单列表
     */
    @GetMapping("/list")
    public Result<List<com.avemonica.ticket.vo.OrderVO>> getOrderList(@RequestParam(defaultValue = "all") String status) {
        Long userId = null;
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            userId = Long.valueOf(((User) auth.getPrincipal()).getUsername());
        } catch (Exception e) {
            return Result.error("登录已过期，请重新登录");
        }

        List<com.avemonica.ticket.vo.OrderVO> list = orderService.getUserOrderList(userId, status);
        return Result.success(list);
    }

    /**
     * 删除订单 (前端点垃圾桶图标触发)
     */
    @PostMapping("/delete/{orderId}")
    public Result<String> deleteOrder(@PathVariable Long orderId) {
        Long userId = null;
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            userId = Long.valueOf(((User) auth.getPrincipal()).getUsername());
        } catch (Exception e) {
            return Result.error("登录已过期，请重新登录");
        }

        try {
            orderService.deleteOrder(orderId, userId);
            return Result.success("订单删除成功");
        } catch (Exception e) {
            return Result.error("删除失败：" + e.getMessage());
        }
    }

    // ==========================================
    // 私有辅助方法区
    // ==========================================

    /**
     * 统一获取当前登录用户的 ID
     */
    private Long getCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return Long.valueOf(((User) auth.getPrincipal()).getUsername());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 统一生成无横线的 UUID Token
     */
    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}