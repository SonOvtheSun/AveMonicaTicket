package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.dto.OrderCreateMessage;
import com.avemonica.ticket.dto.TicketIssueMessage;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.entity.Order;
import com.avemonica.ticket.entity.OrderSpectator;
import com.avemonica.ticket.entity.TicketCategory;
import com.avemonica.ticket.mapper.EventMapper;
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
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
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
    @Autowired
    private EventMapper eventMapper;

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
    private static final String KEY_EVENT_STATUS = "event:status:";

    // 黄牛风控 Key
    private static final String KEY_SCALPER_BLOCK = "risk:scalper:block:";
    private static final String KEY_RISK_SCORE = "risk:scalper:score:";
    private static final String KEY_RISK_USER_ACTION = "risk:user:action:";
    private static final String KEY_RISK_IP_EVENT_ACTION = "risk:ip:event:action:";
    private static final String KEY_RISK_IP_EVENT_USERS = "risk:ip:event:users:";
    private static final String KEY_RISK_UA_EVENT_USERS = "risk:ua:event:users:";
    private static final String KEY_RISK_DEVICE_EVENT_USERS = "risk:device:event:users:";

    private static final int RISK_NEED_CAPTCHA_SCORE = 70;
    private static final int RISK_BLOCK_SCORE = 120;

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

        // 🚨 核心拦截 1：直接获取 Event 实体，以便同时获取状态和开票时间
        Event event = eventMapper.selectById(eventId);
        if (event == null) {
            return Result.error("该演出信息不存在！");
        }

        // 🚨 核心拦截 2：状态拦截 (1-上架/预售/在售，3-停售，4-隐藏)
        if (event.getStatus() != 1) {
            return Result.error("该演出尚未上架或已停售，无法购票！");
        }

        // 🚨 核心拦截 3：基于时间的惰性风控计算 (状态为1，但时间还没到)
        if (event.getSaleTime() != null && LocalDateTime.now().isBefore(event.getSaleTime())) {
            return Result.error("该演出尚未正式开售，请等待倒计时结束！");
        }

        // 0. 黄牛风险预检：同 IP / 同设备 / 同 UA / 同账号异常请求会被要求验证码或临时拦截
        ScalperRiskResult riskResult = checkScalperRisk(userId, eventId, request, "preCheck", 1, null);
        if (riskResult.blocked) {
            return Result.error(2002, riskResult.message);
        }
        if (riskResult.needCaptcha) {
            return Result.error(2001, riskResult.message);
        }

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

        // 🚨 核心修复：前端 EventDetail.jsx 中是通过 res.data.data 来获取 submitToken 的
        // 所以必须把 submitToken 放到 success 的数据载荷中返回
        return Result.success("恭喜进入订单确认页", submitToken);
    }

    @PostMapping("/create")
    public Result<String> createOrder(@RequestBody Map<String, Object> params, HttpServletRequest request) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(502, "登录已过期，请重新登录");

        Long eventId = Long.valueOf(params.get("eventId").toString());
        Long ticketId = Long.valueOf(params.get("ticketId").toString());
        int quantity = Integer.parseInt(params.get("quantity").toString());
        String clientToken = (String) params.get("submitToken");


        // ==========================================
        // 🚨 核心校验：基于新的 Integer 状态码进行全面拦截
        // 状态码：1-预售中；2-在售；3-停售；4-隐藏；-1-不存在
        // ==========================================
        Event event = eventMapper.selectById(eventId);
        if (event == null || event.getStatus() == 4) {
            return Result.error("该演出信息不存在或已下架！");
        }
        if (event.getStatus() != 1) {
            return Result.error("该演出已停售，无法购票！");
        }
        if (event.getSaleTime() != null && LocalDateTime.now().isBefore(event.getSaleTime())) {
            return Result.error("该演出正处于预售/预约阶段，暂未开放正式购票！");
        }

        // 1. 防越权 API 绕过校验
        String redisTokenKey = KEY_SUBMIT_TOKEN + userId + ":" + eventId;
        String serverToken = redisTemplate.opsForValue().get(redisTokenKey);
        if (serverToken == null || !serverToken.equals(clientToken)) {
            return Result.error("非法请求或页面已过期，请重新从详情页进入！");
        }

        // 2. 观演人参数校验
        Object rawSpectators = params.get("spectatorIds");
        if (!(rawSpectators instanceof List)) return Result.error("非法请求：观演人参数格式错误！");

        List<Long> spectatorIds = ((List<?>) rawSpectators).stream()
                .map(id -> Long.valueOf(id.toString()))
                .toList();

        if (spectatorIds.size() != quantity) return Result.error("非法请求：实名观演人数量与购票数量不符！");

        // 2.1 黄牛风险复检：下单阶段结合购票数量和观演人去重再次评估
        ScalperRiskResult riskResult = checkScalperRisk(userId, eventId, request, "create", quantity, spectatorIds);
        if (riskResult.blocked) {
            return Result.error(2002, riskResult.message);
        }
        if (riskResult.needCaptcha) {
            return Result.error(2001, riskResult.message);
        }

        // Token 通过且风控通过后再删除，避免验证码/风险提示导致用户白白丢失令牌
        redisTemplate.delete(redisTokenKey);

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
     * 获取演出状态（带数据库兜底及回写机制）
     * 返回值：1-预售中；2-在售；3-停售；4-隐藏；-1-演出不存在
     */
    private Integer getEventStatusWithFallback(Long eventId) {
        String redisKey = KEY_EVENT_STATUS + eventId;

        // 1. 尝试从 Redis 获取 (Redis 里存的是字符串)
        String statusStr = redisTemplate.opsForValue().get(redisKey);
        if (statusStr != null) {
            return Integer.valueOf(statusStr);
        }

        // 2. Redis 未查到，从数据库兜底调取
        Event event = eventMapper.selectById(eventId);
        if (event != null && event.getStatus() != null) {
            Integer status = event.getStatus();

            // 3. 顺手回写 Redis，将 int 转为 String 存入，设置10分钟过期
            redisTemplate.opsForValue().set(
                    redisKey,
                    String.valueOf(status),
                    10,
                    java.util.concurrent.TimeUnit.MINUTES
            );

            return status;
        }

        // 4. 如果数据库也完全没有这条记录，返回 -1
        return -1;
    }


    /**
     * 黄牛风险检测：不直接替代实名/库存/令牌校验，而是在这些校验之前增加风险评分。
     * 设计目标：
     * 1. 正常用户偶发刷新不受影响；
     * 2. 同账号、同 IP、同设备、同 UA 在短时间内高频请求时触发验证码；
     * 3. 极端异常流量临时封禁，保护后端队列和库存服务。
     */
    private ScalperRiskResult checkScalperRisk(
            Long userId,
            Long eventId,
            HttpServletRequest request,
            String stage,
            Integer quantity,
            List<Long> spectatorIds
    ) {
        String ip = getClientIp(request);
        String ua = normalizeHeader(request.getHeader("User-Agent"));
        String deviceId = normalizeHeader(request.getHeader("X-Device-Id"));
        if (!StringUtils.hasText(deviceId)) {
            deviceId = normalizeHeader(request.getHeader("X-Fingerprint"));
        }

        String blockKey = KEY_SCALPER_BLOCK + eventId + ":" + userId;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(blockKey))) {
            return ScalperRiskResult.block("操作过于频繁，已触发临时风控，请稍后再试");
        }

        int risk = 0;
        List<String> reasons = new ArrayList<>();

        // 1. 单账号短时间高频动作：同一用户 10 秒内连续 preCheck/create
        Long userActionCount = incrWithExpire(KEY_RISK_USER_ACTION + eventId + ":" + userId + ":" + stage, 10);
        if (userActionCount != null && userActionCount > 5) {
            risk += 45;
            reasons.add("账号请求过于频繁");
        } else if (userActionCount != null && userActionCount > 3) {
            risk += 25;
            reasons.add("账号短时间多次请求");
        }

        // 2. 同一 IP 对同一演出的请求洪峰
        Long ipActionCount = incrWithExpire(KEY_RISK_IP_EVENT_ACTION + eventId + ":" + ip + ":" + stage, 60);
        if (ipActionCount != null && ipActionCount > 80) {
            risk += 70;
            reasons.add("同一网络请求异常密集");
        } else if (ipActionCount != null && ipActionCount > 30) {
            risk += 40;
            reasons.add("同一网络多人高频请求");
        }

        // 3. 同 IP 短时间内出现大量不同账号：典型工作室/脚本池特征
        String ipUsersKey = KEY_RISK_IP_EVENT_USERS + eventId + ":" + ip;
        redisTemplate.opsForSet().add(ipUsersKey, String.valueOf(userId));
        redisTemplate.expire(ipUsersKey, 10, TimeUnit.MINUTES);
        Long ipUserCount = redisTemplate.opsForSet().size(ipUsersKey);
        if (ipUserCount != null && ipUserCount > 8) {
            risk += 50;
            reasons.add("同一网络下账号数量异常");
        } else if (ipUserCount != null && ipUserCount > 4) {
            risk += 25;
            reasons.add("同一网络下多账号请求");
        }

        // 4. 同一设备指纹绑定多个账号
        if (StringUtils.hasText(deviceId)) {
            String deviceUsersKey = KEY_RISK_DEVICE_EVENT_USERS + eventId + ":" + deviceId;
            redisTemplate.opsForSet().add(deviceUsersKey, String.valueOf(userId));
            redisTemplate.expire(deviceUsersKey, 30, TimeUnit.MINUTES);
            Long deviceUserCount = redisTemplate.opsForSet().size(deviceUsersKey);
            if (deviceUserCount != null && deviceUserCount > 3) {
                risk += 60;
                reasons.add("同一设备切换多个账号");
            } else if (deviceUserCount != null && deviceUserCount > 1) {
                risk += 25;
                reasons.add("同一设备存在多账号行为");
            }
        }

        // 5. 同一 UA 对同一演出聚集过多账号，作为弱信号，只加少量分
        if (StringUtils.hasText(ua)) {
            String uaUsersKey = KEY_RISK_UA_EVENT_USERS + eventId + ":" + Math.abs(ua.hashCode());
            redisTemplate.opsForSet().add(uaUsersKey, String.valueOf(userId));
            redisTemplate.expire(uaUsersKey, 10, TimeUnit.MINUTES);
            Long uaUserCount = redisTemplate.opsForSet().size(uaUsersKey);
            if (uaUserCount != null && uaUserCount > 20) {
                risk += 15;
                reasons.add("浏览器特征聚集异常");
            }
        }

        // 6. 下单数量和观演人参数异常
        if (quantity != null) {
            if (quantity >= 5) {
                risk += 30;
                reasons.add("单次购票数量偏高");
            } else if (quantity >= 3) {
                risk += 12;
                reasons.add("单次购票数量较高");
            }
        }

        if (spectatorIds != null && !spectatorIds.isEmpty()) {
            long distinctCount = spectatorIds.stream().distinct().count();
            if (distinctCount != spectatorIds.size()) {
                return ScalperRiskResult.block("观演人信息重复，请检查后重新提交");
            }
        }

        // 7. 累计风险分：避免脚本分散到多个阶段规避检测
        String scoreKey = KEY_RISK_SCORE + eventId + ":" + userId;
        Long totalRisk = redisTemplate.opsForValue().increment(scoreKey, risk);
        redisTemplate.expire(scoreKey, 30, TimeUnit.MINUTES);

        int finalRisk = totalRisk == null ? risk : totalRisk.intValue();

        if (finalRisk >= RISK_BLOCK_SCORE) {
            redisTemplate.opsForValue().set(blockKey, "1", 10, TimeUnit.MINUTES);
            return ScalperRiskResult.block("检测到异常抢票行为，已触发临时风控，请稍后再试");
        }

        if (finalRisk >= RISK_NEED_CAPTCHA_SCORE) {
            String reasonText = reasons.isEmpty() ? "请求行为异常" : String.join("、", reasons);
            return ScalperRiskResult.captcha("检测到" + reasonText + "，请完成验证码验证后再继续");
        }

        return ScalperRiskResult.pass();
    }

    private Long incrWithExpire(String key, long seconds) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, seconds, TimeUnit.SECONDS);
        }
        return count;
    }

    private String getClientIp(HttpServletRequest request) {
        String[] headers = {
                "X-Forwarded-For",
                "X-Real-IP",
                "Proxy-Client-IP",
                "WL-Proxy-Client-IP"
        };

        for (String header : headers) {
            String value = request.getHeader(header);
            if (StringUtils.hasText(value) && !"unknown".equalsIgnoreCase(value)) {
                return value.split(",")[0].trim();
            }
        }

        return request.getRemoteAddr();
    }

    private String normalizeHeader(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        value = value.trim();
        return value.length() > 180 ? value.substring(0, 180) : value;
    }

    private static class ScalperRiskResult {
        private final boolean blocked;
        private final boolean needCaptcha;
        private final String message;

        private ScalperRiskResult(boolean blocked, boolean needCaptcha, String message) {
            this.blocked = blocked;
            this.needCaptcha = needCaptcha;
            this.message = message;
        }

        private static ScalperRiskResult pass() {
            return new ScalperRiskResult(false, false, "");
        }

        private static ScalperRiskResult captcha(String message) {
            return new ScalperRiskResult(false, true, message);
        }

        private static ScalperRiskResult block(String message) {
            return new ScalperRiskResult(true, false, message);
        }
    }

    /**
     * 统一生成无横线的 UUID Token
     */
    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}