package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.dto.OrderCreateMessage;
import com.avemonica.ticket.dto.TicketIssueMessage;
import com.avemonica.ticket.entity.*;
import com.avemonica.ticket.exception.BusinessException;
import com.avemonica.ticket.mapper.EventMapper;
import com.avemonica.ticket.mapper.EventSessionMapper;
import com.avemonica.ticket.mapper.OrderSpectatorMapper;
import com.avemonica.ticket.mapper.TicketCategoryMapper;
import com.avemonica.ticket.service.ArtistHeatService;
import com.avemonica.ticket.service.OrderService;
import com.avemonica.ticket.service.RecommendBehaviorService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/order")
public class OrderController {

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

    @Autowired
    private EventSessionMapper eventSessionMapper;

    @Autowired
    private ArtistHeatService artistHeatService;

    @Autowired
    private RecommendBehaviorService recommendBehaviorService;

    /** 演出隐藏状态。隐藏演出不允许进入购票流程。 */
    private static final int EVENT_STATUS_HIDDEN = 4;

    /** 场次上架状态。只有场次状态为 1 时允许预检/下单。 */
    private static final int SESSION_STATUS_ON_SALE = 1;

    /** 订单待支付状态。 */
    private static final int ORDER_STATUS_PENDING_PAY = 1;

    /** 已支付但未检票。支付成功后应该进入该状态，而不是 3。 */
    private static final int ORDER_STATUS_PAID_UNCHECKED = 6;

    /** 单笔订单最大购票数量。前端也有限制，后端再兜底一次。 */
    private static final int MAX_TICKET_QUANTITY = 6;

    /** 分布式令牌桶容量与速率。 */
    private static final String BUCKET_CAPACITY = "1500";
    private static final String BUCKET_RATE = "1000";

    /** Redis Key 统一前缀。 */
    private static final String KEY_USER_LOCK = "order:lock:user:";
    private static final String KEY_FLOW_BUCKET = "order:flow:bucket:";
    private static final String KEY_SUBMIT_TOKEN = "order:submit:token:";
    private static final String KEY_PURCHASED_SPEC = "event:purchased:spectators:";
    private static final String KEY_SPEC_LOCK = "event:spectator:lock:";
    private static final String KEY_ORDER_RESULT = "order:result:";

    /** 黄牛风控 Key。这里按“演出:场次”维度统计，避免不同场次互相污染。 */
    private static final String KEY_SCALPER_BLOCK = "risk:scalper:block:";
    private static final String KEY_RISK_SCORE = "risk:scalper:score:";
    private static final String KEY_RISK_USER_ACTION = "risk:user:action:";
    private static final String KEY_RISK_IP_EVENT_ACTION = "risk:ip:event:action:";
    private static final String KEY_RISK_IP_EVENT_USERS = "risk:ip:event:users:";
    private static final String KEY_RISK_UA_EVENT_USERS = "risk:ua:event:users:";
    private static final String KEY_RISK_DEVICE_EVENT_USERS = "risk:device:event:users:";

    /** 演出上架中状态。 */
    private static final int EVENT_STATUS_ONLINE = 1;

    /** 演出已停售状态。 */
    private static final int EVENT_STATUS_STOPPED = 3;

    private static final int RISK_NEED_CAPTCHA_SCORE = 70;
    private static final int RISK_BLOCK_SCORE = 120;

    private DefaultRedisScript<Long> tokenBucketScript;
    private DefaultRedisScript<Long> spectatorLockScript;

    /**
     * 一票一证短锁 Lua：
     * 1. 只要一个观演人已被锁定，就整体失败；
     * 2. 全部未锁定时，批量写入 10 分钟锁。
     */
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

    /**
     * 进入订单确认页前的预检查。
     * 只允许明确传入 eventId + sessionId 的新数据结构，不再兼容旧的 event 级别购票。
     */
    @PostMapping("/pre-check")
    public Result<String> preCheck(@RequestBody Map<String, Object> params, HttpServletRequest request) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(502, "登录已过期，请重新登录");
        }

        Long eventId = parseLongParam(params, "eventId");
        Long sessionId = parseLongParam(params, "sessionId");
        if (eventId == null) return Result.error("演出ID不能为空");
        if (sessionId == null) return Result.error("场次ID不能为空");

        PurchaseContext context;
        try {
            context = validateSessionForPurchase(eventId, sessionId, null, false);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }

        String sceneKey = sceneKey(context.event.getId(), context.session.getId());

        ScalperRiskResult riskResult = checkScalperRisk(userId, sceneKey, request, "preCheck", 1, null);
        if (riskResult.blocked) return Result.error(2002, riskResult.message);
        if (riskResult.needCaptcha) return Result.error(2001, riskResult.message);

        // 同一账号短时间连点限制。
        String userIdCountKey = KEY_USER_LOCK + userId;
        Long clickCount = redisTemplate.opsForValue().increment(userIdCountKey);
        if (clickCount != null && clickCount == 1) {
            redisTemplate.expire(userIdCountKey, 3, TimeUnit.SECONDS);
        }
        if (clickCount != null && clickCount > 3) {
            return Result.error(2001, "请求过于频繁，请输入验证码验证");
        }

        // 当前场次维度的全局令牌桶限流。
        String serverFlowKey = KEY_FLOW_BUCKET + sceneKey;
        Long result = redisTemplate.execute(
                tokenBucketScript,
                Collections.singletonList(serverFlowKey),
                BUCKET_CAPACITY,
                BUCKET_RATE
        );
        if (result != null && result == 0L) {
            return Result.error("当前抢票人数过多，排队拥挤，请稍后再试");
        }

        // 生成一次性下单授权 Token。Token 绑定用户 + 演出 + 场次。
        String submitToken = generateToken();
        redisTemplate.opsForValue().set(KEY_SUBMIT_TOKEN + userId + ":" + sceneKey, submitToken, 30, TimeUnit.MINUTES);

        return Result.success("恭喜进入订单确认页", submitToken);
    }

    /**
     * 创建订单入口。
     * 实际扣库存与落库由 Kafka 消费者调用 OrderService#createTicketOrder 完成。
     */
    @PostMapping("/create")
    public Result<String> createOrder(@RequestBody Map<String, Object> params, HttpServletRequest request) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(502, "登录已过期，请重新登录");
        }

        Long eventId = parseLongParam(params, "eventId");
        Long sessionId = parseLongParam(params, "sessionId");
        Long ticketId = parseLongParam(params, "ticketId");
        Integer quantity = parseIntParam(params, "quantity");
        String clientToken = (String) params.get("submitToken");

        if (eventId == null) return Result.error("演出ID不能为空");
        if (sessionId == null) return Result.error("场次ID不能为空");
        if (ticketId == null) return Result.error("票档ID不能为空");
        if (quantity == null || quantity <= 0 || quantity > MAX_TICKET_QUANTITY) {
            return Result.error("购票数量不正确");
        }
        if (!StringUtils.hasText(clientToken)) {
            return Result.error("非法请求或页面已过期，请重新从详情页进入！");
        }

        PurchaseContext context;
        try {
            context = validateSessionForPurchase(eventId, sessionId, ticketId, true);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }

        String sceneKey = sceneKey(context.event.getId(), context.session.getId());

        // 校验一次性授权 Token，防止绕过详情页和预检直接下单。
        String redisTokenKey = KEY_SUBMIT_TOKEN + userId + ":" + sceneKey;
        String serverToken = redisTemplate.opsForValue().get(redisTokenKey);
        if (serverToken == null || !serverToken.equals(clientToken)) {
            return Result.error("非法请求或页面已过期，请重新从详情页进入！");
        }

        Object rawSpectators = params.get("spectatorIds");
        if (!(rawSpectators instanceof List<?>)) {
            return Result.error("非法请求：观演人参数格式错误！");
        }

        List<Long> spectatorIds = ((List<?>) rawSpectators).stream()
                .map(id -> Long.valueOf(id.toString()))
                .toList();
        if (spectatorIds.size() != quantity) {
            return Result.error("非法请求：实名观演人数量与购票数量不符！");
        }

        ScalperRiskResult riskResult = checkScalperRisk(userId, sceneKey, request, "create", quantity, spectatorIds);
        if (riskResult.blocked) return Result.error(2002, riskResult.message);
        if (riskResult.needCaptcha) return Result.error(2001, riskResult.message);

        // 风控通过后再删除 Token，避免验证码/风险提示导致用户白白丢失令牌。
        redisTemplate.delete(redisTokenKey);

        // 已购名单按“演出:场次”隔离，允许同一观演人购买同演出不同场次。
        String purchasedSetKey = KEY_PURCHASED_SPEC + sceneKey;
        for (Long specId : spectatorIds) {
            if (Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(purchasedSetKey, String.valueOf(specId)))) {
                return Result.error("您选择的观演人中已有人购买过本场次门票，请勿重复购买！");
            }
        }

        // 待支付短锁也按“演出:场次:观演人”隔离。
        List<String> lockKeys = spectatorIds.stream()
                .map(id -> KEY_SPEC_LOCK + sceneKey + ":" + id)
                .toList();
        Long lockResult = redisTemplate.execute(spectatorLockScript, lockKeys, "600");
        if (lockResult == null || lockResult == 0L) {
            return Result.error("您选择的观演人中，有人已有本场次的待支付订单或已购票，请勿重复购买！");
        }

        String queueToken = generateToken();
        OrderCreateMessage msg = new OrderCreateMessage();
        msg.setQueueToken(queueToken);
        msg.setUserId(userId);
        msg.setEventId(eventId);
        msg.setSessionId(sessionId);
        msg.setTicketId(ticketId);
        msg.setQuantity(quantity);
        msg.setSpectatorIds(spectatorIds);

        try {
            kafkaTemplate.send("order-create-topic", objectMapper.writeValueAsString(msg));
            return Result.success("排队中", queueToken);
        } catch (Exception e) {
            redisTemplate.delete(lockKeys);
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

        if (orderId == null) {
            return Result.error("订单ID不能为空");
        }

        try {
            Order order = orderService.payOrderWithOutbox(orderId);

            /*
             * 以下属于非核心派生数据。
             * 即使后续准备继续事件化，目前也先保留。
             */
            artistHeatService.markEventDirty(order.getEventId());

            recordRecommendBehaviorQuietly(
                    order.getUserId(),
                    order.getEventId(),
                    UserBehavior.TYPE_PAY_ORDER
            );

            return Result.success("支付成功，正在为您出票");

        } catch (BusinessException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 用户申请退款。
     *
     * 请求体：
     * {
     *   "orderId": 123,
     *   "reason": "临时有事无法到场"
     * }
     */
    @PostMapping("/apply-refund")
    public Result<String> applyRefund(@RequestBody Map<String, Object> params) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(502, "登录已过期，请重新登录");
        }

        Long orderId = parseLongParam(params, "orderId");
        String reason = params.get("reason") == null ? null : String.valueOf(params.get("reason"));

        try {
            orderService.applyRefund(orderId, userId, reason);
            return Result.success("退款申请已提交，请等待管理员审核");
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    @GetMapping("/list")
    public Result<List<com.avemonica.ticket.vo.OrderVO>> getOrderList(@RequestParam(defaultValue = "all") String status) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error("登录已过期，请重新登录");
        }
        return Result.success(orderService.getUserOrderList(userId, status));
    }

    @PostMapping("/delete/{orderId}")
    public Result<String> deleteOrder(@PathVariable Long orderId) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error("登录已过期，请重新登录");
        }

        try {
            orderService.deleteOrder(orderId, userId);
            return Result.success("订单删除成功");
        } catch (Exception e) {
            return Result.error("删除失败：" + e.getMessage());
        }
    }

    private PurchaseContext validateSessionForPurchase(Long eventId, Long sessionId, Long ticketId, boolean requireTicket) {
        LocalDateTime now = LocalDateTime.now();

        Event event = eventMapper.selectById(eventId);
        if (event == null || Objects.equals(event.getStatus(), EVENT_STATUS_HIDDEN)) {
            throw new IllegalArgumentException("该演出信息不存在或已下架！");
        }

        if (Objects.equals(event.getStatus(), EVENT_STATUS_STOPPED)) {
            throw new IllegalArgumentException("该演出已停售，无法购票");
        }

        if (!Objects.equals(event.getStatus(), EVENT_STATUS_ONLINE)) {
            throw new IllegalArgumentException("该演出当前状态暂不可购票");
        }

        EventSession session = eventSessionMapper.selectById(sessionId);
        if (session == null || !Objects.equals(session.getEventId(), eventId)) {
            throw new IllegalArgumentException("场次不存在或不属于当前演出");
        }

        if (!Objects.equals(session.getStatus(), SESSION_STATUS_ON_SALE)) {
            throw new IllegalArgumentException("该场次尚未上架或已停售，无法购票！");
        }

        if (session.getShowTime() == null) {
            throw new IllegalArgumentException("该场次尚未配置演出时间，暂不可购票！");
        }

        if (!now.isBefore(session.getShowTime())) {
            throw new IllegalArgumentException("该演出已结束，无法购票");
        }

        if (session.getSaleTime() == null) {
            throw new IllegalArgumentException("该场次尚未配置开票时间，暂不可购票！");
        }

        if (now.isBefore(session.getSaleTime())) {
            throw new IllegalArgumentException("该场次尚未正式开售，请等待倒计时结束！");
        }

        TicketCategory ticket = null;
        if (requireTicket) {
            ticket = ticketCategoryMapper.selectById(ticketId);
            if (ticket == null
                    || !Objects.equals(ticket.getEventId(), eventId)
                    || !Objects.equals(ticket.getSessionId(), sessionId)) {
                throw new IllegalArgumentException("票档不存在或不属于当前场次");
            }
        }

        PurchaseContext context = new PurchaseContext();
        context.event = event;
        context.session = session;
        context.ticket = ticket;
        return context;
    }

    private Long getCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return Long.valueOf(((User) auth.getPrincipal()).getUsername());
        } catch (Exception e) {
            return null;
        }
    }

    private ScalperRiskResult checkScalperRisk(
            Long userId,
            String sceneKey,
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

        String blockKey = KEY_SCALPER_BLOCK + sceneKey + ":" + userId;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(blockKey))) {
            return ScalperRiskResult.block("操作过于频繁，已触发临时风控，请稍后再试");
        }

        int risk = 0;
        List<String> reasons = new ArrayList<>();

        Long userActionCount = incrWithExpire(KEY_RISK_USER_ACTION + sceneKey + ":" + userId + ":" + stage, 10);
        if (userActionCount != null && userActionCount > 5) {
            risk += 45;
            reasons.add("账号请求过于频繁");
        } else if (userActionCount != null && userActionCount > 3) {
            risk += 25;
            reasons.add("账号短时间多次请求");
        }

        Long ipActionCount = incrWithExpire(KEY_RISK_IP_EVENT_ACTION + sceneKey + ":" + ip + ":" + stage, 60);
        if (ipActionCount != null && ipActionCount > 80) {
            risk += 70;
            reasons.add("同一网络请求异常密集");
        } else if (ipActionCount != null && ipActionCount > 30) {
            risk += 40;
            reasons.add("同一网络多人高频请求");
        }

        String ipUsersKey = KEY_RISK_IP_EVENT_USERS + sceneKey + ":" + ip;
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

        if (StringUtils.hasText(deviceId)) {
            String deviceUsersKey = KEY_RISK_DEVICE_EVENT_USERS + sceneKey + ":" + deviceId;
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

        if (StringUtils.hasText(ua)) {
            String uaUsersKey = KEY_RISK_UA_EVENT_USERS + sceneKey + ":" + Math.abs(ua.hashCode());
            redisTemplate.opsForSet().add(uaUsersKey, String.valueOf(userId));
            redisTemplate.expire(uaUsersKey, 10, TimeUnit.MINUTES);
            Long uaUserCount = redisTemplate.opsForSet().size(uaUsersKey);
            if (uaUserCount != null && uaUserCount > 20) {
                risk += 15;
                reasons.add("浏览器特征聚集异常");
            }
        }

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

        String scoreKey = KEY_RISK_SCORE + sceneKey + ":" + userId;
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
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String header : headers) {
            String value = request.getHeader(header);
            if (StringUtils.hasText(value) && !"unknown".equalsIgnoreCase(value)) {
                return value.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private String normalizeHeader(String value) {
        if (!StringUtils.hasText(value)) return "";
        value = value.trim();
        return value.length() > 180 ? value.substring(0, 180) : value;
    }

    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String sceneKey(Long eventId, Long sessionId) {
        return eventId + ":" + sessionId;
    }

    private Long parseLongParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) return null;
        try {
            return Long.valueOf(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseIntParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value == null) return null;
        try {
            return Integer.valueOf(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static class PurchaseContext {
        private Event event;
        private EventSession session;
        private TicketCategory ticket;
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

    private void recordRecommendBehaviorQuietly(Long userId, Long eventId, Integer behaviorType) {
        if (userId == null || eventId == null || behaviorType == null) {
            return;
        }

        try {
            recommendBehaviorService.recordBehavior(userId, null, eventId, behaviorType);
        } catch (Exception e) {
            log.warn("记录支付推荐行为失败，userId={}, eventId={}, behaviorType={}",
                    userId, eventId, behaviorType, e);
        }
    }
}
