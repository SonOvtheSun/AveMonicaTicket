package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.dto.TicketIssueMessage;
import com.avemonica.ticket.entity.*;
import com.avemonica.ticket.exception.BusinessException;
import com.avemonica.ticket.mapper.*;
import com.avemonica.ticket.service.*;
import com.avemonica.ticket.vo.OrderVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements OrderService {

    @Autowired
    private TicketCategoryMapper ticketMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EventSessionMapper eventSessionMapper;

    @Autowired
    private OrderSpectatorMapper orderSpectatorMapper;

    @Autowired
    private SpectatorMapper spectatorMapper;

    @Autowired
    private EventMapper eventMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private OrderTicketMapper orderTicketMapper;

    @Autowired
    private ArtistHeatService artistHeatService;

    @Autowired
    private RecommendBehaviorService recommendBehaviorService;

    @Autowired
    private OutboxEventWriter outboxEventWriter;

    @Autowired
    private TicketIssueFailureMapper ticketIssueFailureMapper;

    @Autowired
    private TicketIssueProcessor ticketIssueProcessor;

    @Autowired
    private TicketPurchasedCacheService ticketPurchasedCacheService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 订单状态：
     * 1 已创建，未支付
     * 2 已取消
     * 3 已完成订单
     * 4 申请退款中
     * 5 异常订单
     * 6 已支付但未检票
     * 7 已退票
     */
    private static final int ORDER_STATUS_PENDING_PAY = 1;
    private static final int ORDER_STATUS_CANCELED = 2;
    private static final int ORDER_STATUS_COMPLETED = 3;
    private static final int ORDER_STATUS_REFUND_APPLYING = 4;
    private static final int ORDER_STATUS_EXCEPTION = 5;
    private static final int ORDER_STATUS_PAID_UNCHECKED = 6;
    private static final int ORDER_STATUS_REFUNDED = 7;

    private static final int EVENT_STATUS_ONLINE = 1;
    private static final int EVENT_STATUS_STOPPED = 3;
    private static final int EVENT_STATUS_HIDDEN = 4;

    private static final int SESSION_STATUS_ON_SALE = 1;

    private static final int TICKET_CHECK_STATUS_PENDING_ISSUE = 4;
    private static final int TICKET_CHECK_STATUS_CHECKED = 2;

    private static final String KEY_SPEC_LOCK = "event:spectator:lock:";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 订单ID规则：16 位随机数字。
     * 首位不允许为 0，避免展示或导出时被误认为不足 16 位。
     */
    private static final int ORDER_ID_LENGTH = 16;
    private static final int ORDER_ID_MAX_RETRY = 50;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Kafka 消费者调用的真正建单逻辑。
     * 新模型下订单必须绑定 eventId + sessionId + ticketId，不再兼容 event 级别票档。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Order createTicketOrder(Order order, List<Long> spectatorIds) {
        validateCreateOrderArgs(order, spectatorIds);

        TicketCategory ticket = ticketMapper.selectById(order.getTicketId());
        if (ticket == null) {
            throw new RuntimeException("该票档不存在或已下架");
        }

        // 防止用户用 A 场次的 ticketId 购买 B 场次。
        if (!Objects.equals(ticket.getEventId(), order.getEventId())
                || !Objects.equals(ticket.getSessionId(), order.getSessionId())) {
            throw new RuntimeException("票档不属于当前演出场次");
        }

        EventSession session = eventSessionMapper.selectById(order.getSessionId());
        if (session == null || !Objects.equals(session.getEventId(), order.getEventId())) {
            throw new RuntimeException("演出场次不存在或不属于当前演出");
        }

        Event event = eventMapper.selectById(order.getEventId());
        validatePurchasableEventAndSession(event, session);

// 所有归属校验通过后再扣库存，避免错误扣减。
        int updateRows = ticketMapper.deductStock(order.getTicketId(), order.getQuantity());
        if (updateRows == 0) {
            throw new RuntimeException("手慢了，该票档库存不足！");
        }

        BigDecimal payPrice = ticket.getPrice().multiply(BigDecimal.valueOf(order.getQuantity()));

        /*
         * 订单主键 ID 直接作为给用户展示的订单号。
         * 因此不再单独生成 orderNo，也不再写 tb_order.order_no。
         */
        Long orderId = generateUniqueOrderId();
        order.setId(orderId);

        order.setPayPrice(payPrice);
        order.setStatus(ORDER_STATUS_PENDING_PAY);
        order.setCreateTime(LocalDateTime.now());
        order.setExpireTime(order.getCreateTime().plusMinutes(10));
        this.save(order);

        // 一票一证关系立即落库，且必须写入 sessionId。
        for (Long specId : spectatorIds) {
            OrderSpectator os = new OrderSpectator();
            os.setOrderId(order.getId());
            os.setEventId(order.getEventId());
            os.setSessionId(order.getSessionId());
            os.setSpectatorId(specId);
            os.setDeleteToken(0L);
            orderSpectatorMapper.insert(os);
        }

        recordRecommendBehaviorQuietly(
                order.getUserId(),
                order.getEventId(),
                UserBehavior.TYPE_CREATE_ORDER
        );

        return order;
    }

    @Override
    public void compensateTicketIssue(Long orderId) {

        if (orderId == null) {
            throw new BusinessException(
                    "订单ID不能为空"
            );
        }

        /*
         * 1. 查询订单
         */
        Order order = this.getById(orderId);

        if (order == null) {
            throw new BusinessException(
                    "订单不存在"
            );
        }

        /*
         * 只有 status=5 的异常订单
         * 才允许人工补偿。
         */
        if (!Objects.equals(
                order.getStatus(),
                ORDER_STATUS_EXCEPTION
        )) {
            throw new BusinessException(
                    "只有异常订单才能执行人工补偿"
            );
        }

        /*
         * 2. 找到这笔订单待处理的出票失败记录
         */
        TicketIssueFailure failure =
                ticketIssueFailureMapper.selectOne(
                        new LambdaQueryWrapper<TicketIssueFailure>()
                                .eq(
                                        TicketIssueFailure::getOrderId,
                                        orderId
                                )
                                .eq(
                                        TicketIssueFailure::getStatus,
                                        0
                                )
                                .orderByDesc(
                                        TicketIssueFailure::getFailTime
                                )
                                .last("LIMIT 1")
                );

        if (failure == null) {
            throw new BusinessException(
                    "没有找到待处理的出票失败记录"
            );
        }

        /*
         * 3. 解析原始出票消息
         */
        TicketIssueMessage message;

        try {

            message = objectMapper.readValue(
                    failure.getPayload(),
                    TicketIssueMessage.class
            );

        } catch (Exception e) {

            throw new BusinessException(
                    "出票失败消息无法解析"
            );
        }

        /*
         * 防止错误的失败记录补偿到别的订单。
         */
        if (!Objects.equals(
                message.getOrderId(),
                orderId
        )) {
            throw new BusinessException(
                    "出票失败消息与当前订单不一致"
            );
        }

        /*
         * 4. 重新执行出票。
         *
         * 这里一定使用原来的 outboxEventId。
         *
         * 如果原事件其实已经成功处理过，
         * Inbox 会识别出来，
         * 不会再生成第二套电子票。
         */
        ticketIssueProcessor.processOnce(
                failure.getOutboxEventId(),
                failure.getEventType(),
                message
        );

        /*
         * 5. 修复Redis已购名单。
         *
         * Redis Set本身是幂等的，
         * 重复执行不会产生重复成员。
         */
        ticketPurchasedCacheService.markPurchased(
                message
        );

        /*
         * 6. 前面全部成功后：
         *
         * failure:
         * 0 → 1
         *
         * order:
         * 5 → 6
         *
         * 这两个数据库修改必须是同一事务。
         */
        Long operatorId = getCurrentAdminId();

        if (operatorId == null) {
            throw new BusinessException(
                    "无法获取当前管理员信息"
            );
        }

        TransactionTemplate transactionTemplate =
                new TransactionTemplate(
                        transactionManager
                );

        transactionTemplate.executeWithoutResult(status -> {

            int failureRows =
                    ticketIssueFailureMapper.update(
                            null,
                            new LambdaUpdateWrapper<TicketIssueFailure>()
                                    .eq(
                                            TicketIssueFailure::getId,
                                            failure.getId()
                                    )
                                    .eq(
                                            TicketIssueFailure::getStatus,
                                            0
                                    )
                                    .set(
                                            TicketIssueFailure::getStatus,
                                            1
                                    )
                                    .set(
                                            TicketIssueFailure::getRepairTime,
                                            LocalDateTime.now()
                                    )
                                    .set(
                                            TicketIssueFailure::getRepairOperatorId,
                                            operatorId
                                    )
                    );

            if (failureRows == 0) {
                throw new BusinessException(
                        "该出票异常已经处理，请勿重复操作"
                );
            }

            boolean orderUpdated =
                    this.lambdaUpdate()
                            .eq(
                                    Order::getId,
                                    orderId
                            )
                            .eq(
                                    Order::getStatus,
                                    ORDER_STATUS_EXCEPTION
                            )
                            .set(
                                    Order::getStatus,
                                    ORDER_STATUS_PAID_UNCHECKED
                            )
                            .update();

            if (!orderUpdated) {
                throw new BusinessException(
                        "订单状态已发生变化，无法完成补偿"
                );
            }
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordTicketIssueFailure(
            TicketIssueFailure failure
    ) {

        if (failure == null) {
            throw new BusinessException(
                    "出票失败记录不能为空"
            );
        }

        if (!StringUtils.hasText(
                failure.getOutboxEventId()
        )) {
            throw new BusinessException(
                    "Outbox Event ID不能为空"
            );
        }

        if (failure.getOrderId() == null) {
            throw new BusinessException(
                    "订单ID不能为空"
            );
        }

        /*
         * DLT也可能发生重复消费。
         *
         * 所以失败记录本身也必须幂等。
         */
        ticketIssueFailureMapper.upsert(
                failure
        );

        /*
         * 再读数据库状态。
         *
         * 如果已经人工补偿完成(status=1)，
         * 重复DLT不能把订单重新打回异常状态。
         */
        TicketIssueFailure stored =
                ticketIssueFailureMapper.selectOne(
                        new LambdaQueryWrapper<TicketIssueFailure>()
                                .eq(
                                        TicketIssueFailure::getOutboxEventId,
                                        failure.getOutboxEventId()
                                )
                                .last("LIMIT 1")
                );

        if (stored == null) {
            throw new BusinessException(
                    "出票失败记录保存失败"
            );
        }

        if (!Objects.equals(
                stored.getStatus(),
                0
        )) {
            return;
        }

        /*
         * 只允许：
         *
         * 6 已支付未检票
         * →
         * 5 异常订单
         *
         * 不能覆盖退款、已退票等状态。
         */
        this.lambdaUpdate()
                .eq(
                        Order::getId,
                        failure.getOrderId()
                )
                .eq(
                        Order::getStatus,
                        ORDER_STATUS_PAID_UNCHECKED
                )
                .set(
                        Order::getStatus,
                        ORDER_STATUS_EXCEPTION
                )
                .update();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId, Long userId) {
        Order order = this.getById(orderId);
        if (order == null || !Objects.equals(order.getUserId(), userId)) {
            throw new RuntimeException("订单不存在或无权操作");
        }
        if (!Objects.equals(order.getStatus(), ORDER_STATUS_PENDING_PAY)) {
            throw new RuntimeException("只能取消待支付的订单");
        }

        order.setStatus(ORDER_STATUS_CANCELED);
        this.updateById(order);
        markOrderEventDirty(order);

        ticketMapper.addStock(order.getTicketId(), order.getQuantity());

        List<OrderSpectator> osList = orderSpectatorMapper.selectList(
                new LambdaQueryWrapper<OrderSpectator>()
                        .eq(OrderSpectator::getOrderId, orderId)
                        .eq(OrderSpectator::getDeleteToken, 0L)
        );

        List<String> redisLockKeys = new ArrayList<>();
        for (OrderSpectator os : osList) {
            // 释放 MySQL 唯一索引：活跃关系 deleteToken=0，取消后改为自身主键。
            os.setDeleteToken(os.getId());
            orderSpectatorMapper.updateById(os);

            redisLockKeys.add(KEY_SPEC_LOCK + sceneKey(order.getEventId(), order.getSessionId()) + ":" + os.getSpectatorId());
        }

        if (!redisLockKeys.isEmpty()) {
            redisTemplate.delete(redisLockKeys);
        }
    }

    @Override
    public List<OrderVO> getUserOrderList(Long userId, String status) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, userId)
                .and(w -> w.eq(Order::getUserDeleted, 0).or().isNull(Order::getUserDeleted))
                .orderByDesc(Order::getCreateTime);

        // 待检票/已完成基于电子票 checkStatus 动态归类，不能只看订单主表 status。
        boolean dynamicTicketStatusFilter = "3".equals(status) || "6".equals(status);
        if (StringUtils.hasText(status) && !"all".equals(status)) {
            if (dynamicTicketStatusFilter) {
                wrapper.in(Order::getStatus, ORDER_STATUS_COMPLETED, ORDER_STATUS_PAID_UNCHECKED);
            } else {
                wrapper.eq(Order::getStatus, Integer.parseInt(status));
            }
        }

        List<Order> orders = this.list(wrapper);
        List<OrderVO> voList = new ArrayList<>();

        for (Order order : orders) {
            OrderVO vo = buildOrderVO(order);

            if (dynamicTicketStatusFilter && !status.equals(resolveOrderCategoryKey(vo))) {
                continue;
            }

            voList.add(vo);
        }

        return voList;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteOrder(Long orderId, Long userId) {
        Order order = this.getById(orderId);
        if (order == null || !Objects.equals(order.getUserId(), userId)) {
            throw new RuntimeException("订单不存在或无权操作");
        }

        if (Objects.equals(order.getUserDeleted(), 1)) {
            throw new RuntimeException("订单已删除");
        }

        if (!Objects.equals(order.getStatus(), ORDER_STATUS_CANCELED)
                && !Objects.equals(order.getStatus(), ORDER_STATUS_COMPLETED)
                && !Objects.equals(order.getStatus(), ORDER_STATUS_REFUNDED)) {
            throw new RuntimeException("只有已取消、已完成、已退票订单允许删除");
        }

        Order update = new Order();
        update.setId(orderId);
        update.setUserDeleted(1);
        update.setUserDeleteTime(LocalDateTime.now());

        this.updateById(update);
    }


    /**
     * 生成唯一订单主键 ID：16 位随机数字。
     *
     * 订单 ID 同时作为用户看到的订单号，所以只需要维护一个字段。
     */
    private Long generateUniqueOrderId() {
        for (int i = 0; i < ORDER_ID_MAX_RETRY; i++) {
            Long orderId = Long.valueOf(randomSixteenDigitNumber());

            long count = this.count(
                    new LambdaQueryWrapper<Order>()
                            .eq(Order::getId, orderId)
            );

            if (count == 0) {
                return orderId;
            }
        }

        throw new RuntimeException("订单ID生成失败，请稍后重试");
    }

    /**
     * 生成固定 16 位数字字符串。
     * 首位范围 1~9，其余 15 位范围 0~9。
     */
    private String randomSixteenDigitNumber() {
        StringBuilder builder = new StringBuilder(ORDER_ID_LENGTH);

        builder.append(SECURE_RANDOM.nextInt(9) + 1);

        for (int i = 1; i < ORDER_ID_LENGTH; i++) {
            builder.append(SECURE_RANDOM.nextInt(10));
        }

        return builder.toString();
    }


    private void validateCreateOrderArgs(Order order, List<Long> spectatorIds) {
        if (order == null) throw new RuntimeException("订单参数不能为空");
        if (order.getUserId() == null) throw new RuntimeException("缺少用户信息");
        if (order.getEventId() == null) throw new RuntimeException("缺少演出信息");
        if (order.getSessionId() == null) throw new RuntimeException("缺少演出场次信息");
        if (order.getTicketId() == null) throw new RuntimeException("缺少票档信息");
        if (order.getQuantity() == null || order.getQuantity() <= 0) throw new RuntimeException("购票数量不正确");
        if (spectatorIds == null || spectatorIds.size() != order.getQuantity()) {
            throw new RuntimeException("实名观演人数量与购票数量不一致");
        }
    }

    private OrderVO buildOrderVO(Order order) {
        OrderVO vo = new OrderVO();
        vo.setId(String.valueOf(order.getId()));
        vo.setCreateTime(order.getCreateTime() != null ? order.getCreateTime().format(DATE_TIME_FORMATTER) : "");
        vo.setStatus(order.getStatus());
        vo.setTotalAmount(order.getPayPrice());
        vo.setEventId(String.valueOf(order.getEventId()));

        Event event = eventMapper.selectById(order.getEventId());
        EventSession session = eventSessionMapper.selectById(order.getSessionId());
        vo.setEvent(buildEventVO(event, session));

        if (Objects.equals(order.getStatus(), ORDER_STATUS_COMPLETED)
                || Objects.equals(order.getStatus(), ORDER_STATUS_PAID_UNCHECKED)) {
            vo.setPaymentMethod("支付宝支付");
        }

        List<OrderSpectator> orderSpectators = orderSpectatorMapper.selectList(
                new LambdaQueryWrapper<OrderSpectator>()
                        .eq(OrderSpectator::getOrderId, order.getId())
                        .eq(OrderSpectator::getDeleteToken, 0L)
                        .orderByAsc(OrderSpectator::getId)
        );

        List<Long> spectatorIds = orderSpectators.stream()
                .map(OrderSpectator::getSpectatorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        Map<Long, Spectator> spectatorMap = buildSpectatorMap(spectatorIds);
        TicketCategory category = ticketMapper.selectById(order.getTicketId());
        String categoryName = category != null ? category.getName() : "未知票档";

        vo.setTickets(buildTicketVOs(order, orderSpectators, spectatorMap, categoryName));
        return vo;
    }

    private OrderVO.EventVO buildEventVO(Event event, EventSession session) {
        OrderVO.EventVO eventVO = new OrderVO.EventVO();
        if (event == null) {
            return eventVO;
        }

        eventVO.setName(event.getTitle());
        eventVO.setPoster(event.getPosterUrl());
        eventVO.setCity(event.getCity());
        eventVO.setVenue(event.getVenue());
        eventVO.setRunningTime(event.getRunningTime());
        eventVO.setAllowRefund(event.getAllowRefund());

        // 新模型只使用订单绑定的场次时间；如果缺失，明确提示数据异常，不回退到 event.showTime。
        eventVO.setTime(session != null && session.getShowTime() != null
                ? session.getShowTime().format(DATE_TIME_FORMATTER)
                : "场次信息缺失");

        return eventVO;
    }

    private List<OrderVO.TicketVO> buildTicketVOs(
            Order order,
            List<OrderSpectator> orderSpectators,
            Map<Long, Spectator> spectatorMap,
            String categoryName
    ) {
        List<OrderVO.TicketVO> ticketVOs = new ArrayList<>();

        if (Objects.equals(order.getStatus(), ORDER_STATUS_PENDING_PAY)
                || Objects.equals(order.getStatus(), ORDER_STATUS_CANCELED)) {
            for (int i = 0; i < order.getQuantity(); i++) {
                OrderVO.TicketVO ticketVO = createPendingTicketVO(order, i, categoryName);
                fillTicketSpectatorInfo(ticketVO, getSpectatorByIndex(i, orderSpectators, spectatorMap));
                ticketVOs.add(ticketVO);
            }
            return ticketVOs;
        }

        List<OrderTicket> realTickets = orderTicketMapper.selectList(
                new LambdaQueryWrapper<OrderTicket>()
                        .eq(OrderTicket::getOrderId, order.getId())
                        .orderByAsc(OrderTicket::getId)
        );

        if (realTickets.isEmpty()) {
            for (int i = 0; i < order.getQuantity(); i++) {
                OrderVO.TicketVO ticketVO = createQueueTicketVO(order, i, categoryName);
                fillTicketSpectatorInfo(ticketVO, getSpectatorByIndex(i, orderSpectators, spectatorMap));
                ticketVOs.add(ticketVO);
            }
            return ticketVOs;
        }

        for (OrderTicket realTicket : realTickets) {
            OrderVO.TicketVO ticketVO = new OrderVO.TicketVO();
            ticketVO.setId(String.valueOf(realTicket.getId()));
            ticketVO.setName(realTicket.getTicketName());
            ticketVO.setSeatInfo(realTicket.getSeatInfo());
            ticketVO.setCheckStatus(realTicket.getCheckStatus());
            ticketVO.setQrCode(realTicket.getQrCode());

            Spectator spectator = realTicket.getSpectatorId() == null
                    ? null
                    : spectatorMap.get(realTicket.getSpectatorId());
            fillTicketSpectatorInfo(ticketVO, spectator);

            ticketVOs.add(ticketVO);
        }

        return ticketVOs;
    }

    private OrderVO.TicketVO createPendingTicketVO(Order order, int index, String categoryName) {
        OrderVO.TicketVO ticketVO = new OrderVO.TicketVO();
        ticketVO.setId("T_PENDING_" + order.getId() + "_" + index);
        ticketVO.setName(categoryName);
        ticketVO.setCheckStatus(TICKET_CHECK_STATUS_PENDING_ISSUE);
        return ticketVO;
    }

    private OrderVO.TicketVO createQueueTicketVO(Order order, int index, String categoryName) {
        OrderVO.TicketVO ticketVO = new OrderVO.TicketVO();
        ticketVO.setId("T_QUEUE_" + order.getId() + "_" + index);
        ticketVO.setName(categoryName);
        ticketVO.setCheckStatus(TICKET_CHECK_STATUS_PENDING_ISSUE);
        return ticketVO;
    }

    private Map<Long, Spectator> buildSpectatorMap(List<Long> spectatorIds) {
        if (spectatorIds == null || spectatorIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Spectator> spectators = spectatorMapper.selectBatchIds(spectatorIds);
        Map<Long, Spectator> spectatorMap = new HashMap<>();
        for (Spectator spectator : spectators) {
            spectatorMap.put(spectator.getId(), spectator);
        }
        return spectatorMap;
    }

    private Spectator getSpectatorByIndex(int index, List<OrderSpectator> orderSpectators, Map<Long, Spectator> spectatorMap) {
        if (orderSpectators == null || index < 0 || index >= orderSpectators.size()) {
            return null;
        }
        Long spectatorId = orderSpectators.get(index).getSpectatorId();
        return spectatorId == null ? null : spectatorMap.get(spectatorId);
    }

    private void fillTicketSpectatorInfo(OrderVO.TicketVO ticketVO, Spectator spectator) {
        if (ticketVO == null || spectator == null) {
            return;
        }
        ticketVO.setSpectatorId(String.valueOf(spectator.getId()));
        ticketVO.setViewerName(spectator.getName());
        ticketVO.setIdCardNo(spectator.getIdCard());
    }

    private String resolveOrderCategoryKey(OrderVO vo) {
        if (vo == null) return "other";

        Integer status = vo.getStatus();
        if (Objects.equals(status, ORDER_STATUS_PENDING_PAY)) return "1";
        if (Objects.equals(status, ORDER_STATUS_CANCELED)) return "2";
        if (Objects.equals(status, 4)) return "4";

        List<OrderVO.TicketVO> tickets = vo.getTickets();
        if (tickets == null || tickets.isEmpty()) {
            return "6";
        }

        boolean allChecked = tickets.stream()
                .allMatch(ticket -> Objects.equals(ticket.getCheckStatus(), TICKET_CHECK_STATUS_CHECKED));
        if (allChecked) {
            return "3";
        }

        if (isOrderEventOver(vo)) {
            return "ended";
        }

        return "6";
    }

    private boolean isOrderEventOver(OrderVO vo) {
        try {
            if (vo == null || vo.getEvent() == null || !StringUtils.hasText(vo.getEvent().getTime())) {
                return false;
            }
            LocalDateTime showTime = LocalDateTime.parse(vo.getEvent().getTime(), DATE_TIME_FORMATTER);
            int runningTime = vo.getEvent().getRunningTime() == null ? 120 : vo.getEvent().getRunningTime();
            return LocalDateTime.now().isAfter(showTime.plusMinutes(runningTime));
        } catch (Exception e) {
            return false;
        }
    }


    /**
     * 后台订单管理分页。
     *
     * 查询数据交给 OrderMapper 的自定义 SQL。
     * Service 只负责分页参数、组装 tickets/spectators 列表和退票审核业务。
     */
    @Override
    public Map<String, Object> pageAdminOrders(Integer current,
                                               Integer size,
                                               Integer status,
                                               String searchType,
                                               String keyword) {
        int safeCurrent = current == null || current < 1 ? 1 : current;
        int safeSize = size == null || size < 1 ? 10 : size;
        int offset = (safeCurrent - 1) * safeSize;

        Map<String, Object> params = buildAdminOrderQueryParams(status, searchType, keyword);
        params.put("offset", offset);
        params.put("size", safeSize);

        Long total = baseMapper.countAdminOrders(params);
        List<Map<String, Object>> records = total == null || total == 0
                ? Collections.emptyList()
                : baseMapper.selectAdminOrderPage(params);

        fillAdminOrderChildren(records);

        Map<String, Object> result = new HashMap<>();
        result.put("records", records);
        result.put("total", total == null ? 0 : total);
        result.put("current", safeCurrent);
        result.put("size", safeSize);
        result.put("pages", total == null || total == 0 ? 0 : (long) Math.ceil(total * 1.0 / safeSize));

        return result;
    }

    /**
     * 后台订单详情。
     */
    @Override
    public Map<String, Object> getAdminOrderDetail(
            Long id
    ) {

        if (id == null) {
            throw new BusinessException(
                    "订单ID不能为空"
            );
        }

        Map<String, Object> detail =
                baseMapper.selectAdminOrderDetail(id);

        if (detail == null
                || detail.isEmpty()) {
            throw new BusinessException(
                    "订单不存在"
            );
        }

        fillAdminOrderChildren(
                Collections.singletonList(detail)
        );

        /*
         * 查询最近一次出票失败信息。
         */
        TicketIssueFailure failure =
                ticketIssueFailureMapper.selectOne(
                        new LambdaQueryWrapper<TicketIssueFailure>()
                                .eq(
                                        TicketIssueFailure::getOrderId,
                                        id
                                )
                                .orderByDesc(
                                        TicketIssueFailure::getFailTime
                                )
                                .last("LIMIT 1")
                );

        if (failure != null) {

            detail.put(
                    "ticketIssueError",
                    failure.getErrorMessage()
            );

            detail.put(
                    "ticketIssueFailTime",
                    failure.getFailTime()
            );

            detail.put(
                    "ticketIssueFailureStatus",
                    failure.getStatus()
            );

            Long status =
                    parseLong(
                            detail.get("status")
                    );

            detail.put(
                    "canCompensate",
                    Objects.equals(
                            status,
                            5L
                    )
                            && Objects.equals(
                            failure.getStatus(),
                            0
                    )
            );
        }

        return detail;
    }

    /**
     * 后台退票审核。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void auditRefund(Map<String, Object> body) {
        Long orderId = parseLong(body.get("orderId"));
        Boolean approve = parseBoolean(body.get("approve"));
        String rejectReason = body.get("rejectReason") == null ? null : String.valueOf(body.get("rejectReason"));

        if (orderId == null) {
            throw new BusinessException("订单ID不能为空");
        }
        if (approve == null) {
            throw new BusinessException("审核结果不能为空");
        }

        Order order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }

        if (!Objects.equals(order.getStatus(), ORDER_STATUS_REFUND_APPLYING)) {
            throw new BusinessException("只有申请退款中的订单才能审核");
        }

        Long operatorId = getCurrentAdminId();

        if (approve) {
            Order refunding = new Order();
            refunding.setId(orderId);
            refunding.setRefundAuditTime(LocalDateTime.now());
            refunding.setRefundOperatorId(operatorId);
            refunding.setRefundStatus(3);
            refunding.setRefundStep(3);
            this.updateById(refunding);

// 第二步：调用钱包/支付退款
            boolean walletRefundSuccess = refundToWallet(order);

            if (!walletRefundSuccess) {
                Order fail = new Order();
                fail.setId(orderId);
                fail.setRefundStatus(6);
                fail.setRefundStep(3);
                fail.setRefundFailStep(3);
                fail.setRefundFailReason("钱包退款失败，请人工处理");
                this.updateById(fail);
                throw new BusinessException("钱包退款失败，订单状态未修改");
            }

// 第三步：退款成功，订单变为已退票
            Order success = new Order();
            success.setId(orderId);
            success.setStatus(ORDER_STATUS_REFUNDED);
            success.setRefundStatus(4);
            success.setRefundStep(4);
            success.setRefundReturnTime(LocalDateTime.now());
            success.setRefundFinishTime(LocalDateTime.now());
            this.updateById(success);
            markOrderEventDirty(order);

            recordRecommendBehaviorQuietly(
                    order.getUserId(),
                    order.getEventId(),
                    UserBehavior.TYPE_REFUND_SUCCESS
            );

            return;
        }

        if (!StringUtils.hasText(rejectReason)) {
            throw new BusinessException("拒绝退票时必须填写原因");
        }

        Order update = new Order();
        update.setId(orderId);

// 退回到已支付未检票
        update.setStatus(ORDER_STATUS_PAID_UNCHECKED);

// 审核信息
        update.setRefundAuditTime(LocalDateTime.now());
        update.setRefundRejectReason(rejectReason);
        update.setRefundOperatorId(operatorId);

// 退款流程：卡在第 2 步“后台审核”，并标红
        update.setRefundStatus(5);
        update.setRefundStep(2);
        update.setRefundFailStep(2);
        update.setRefundFailReason(rejectReason);

        this.updateById(update);
        markOrderEventDirty(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Order payOrderWithOutbox(Long orderId) {

        Order order = this.getById(orderId);

        if (order == null) {
            throw new BusinessException("订单不存在");
        }

        if (!Objects.equals(order.getStatus(), ORDER_STATUS_PENDING_PAY)) {
            throw new BusinessException("订单当前状态无法支付");
        }

        if (order.getSessionId() == null) {
            throw new BusinessException("订单缺少场次信息，无法支付");
        }

        /*
         * 1. 查询当前订单有效观演人
         */
        List<OrderSpectator> osList = orderSpectatorMapper.selectList(
                new LambdaQueryWrapper<OrderSpectator>()
                        .eq(OrderSpectator::getOrderId, orderId)
                        .eq(OrderSpectator::getDeleteToken, 0L)
        );

        List<Long> spectatorIds = osList.stream()
                .map(OrderSpectator::getSpectatorId)
                .filter(Objects::nonNull)
                .toList();

        if (spectatorIds.isEmpty()) {
            throw new BusinessException("订单没有有效观演人，无法出票");
        }

        /*
         * 2. 查询票档
         */
        TicketCategory ticket = ticketMapper.selectById(order.getTicketId());

        if (ticket == null) {
            throw new BusinessException("订单对应票档不存在");
        }

        /*
         * 3. 修改订单状态
         */
        order.setStatus(ORDER_STATUS_PAID_UNCHECKED);

        if (!this.updateById(order)) {
            throw new BusinessException("更新订单支付状态失败");
        }

        /*
         * 4. 构造原来的 Kafka 消息
         *
         * 注意：
         * 这里只构造消息，不再调用 KafkaTemplate。
         */
        TicketIssueMessage message = new TicketIssueMessage(
                order.getId(),
                order.getEventId(),
                order.getSessionId(),
                order.getTicketId(),
                ticket.getName(),
                spectatorIds
        );

        /*
         * 5. 写入 Outbox
         *
         * 因为当前方法有 @Transactional，
         * 所以 order UPDATE 和 outbox INSERT 属于同一个 MySQL 事务。
         */
        outboxEventWriter.write(
                "ORDER",
                String.valueOf(order.getId()),
                "TICKET_ISSUE_REQUESTED",
                "order-ticket-issue-topic",
                message
        );

        return order;
    }

    /**
     * 管理员强制退款。
     *
     * 不依赖用户是否提交退款申请。
     * 适用于后台人工处理异常订单、特殊售后等场景。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void forceRefund(Map<String, Object> body) {
        Long orderId = parseLong(body.get("orderId"));
        if (orderId == null) {
            throw new BusinessException("订单ID不能为空");
        }

        Order order = this.getById(orderId);
        if (order == null) {
            throw new BusinessException("订单不存在");
        }

        Integer status = order.getStatus();

        if (Objects.equals(status, ORDER_STATUS_PENDING_PAY)) {
            throw new BusinessException("未支付订单不能退款，请取消订单");
        }

        if (Objects.equals(status, ORDER_STATUS_CANCELED)) {
            throw new BusinessException("已取消订单不能退款");
        }

        if (Objects.equals(status, ORDER_STATUS_REFUNDED)) {
            throw new BusinessException("订单已退票，请勿重复退款");
        }

        Long operatorId = getCurrentAdminId();
        LocalDateTime now = LocalDateTime.now();

        /*
         * 第一步：如果用户没有申请过退款，后台强制创建一条退款流程。
         */
        Order start = new Order();
        start.setId(orderId);
        start.setStatus(ORDER_STATUS_REFUND_APPLYING);
        start.setRefundReason(
                StringUtils.hasText(order.getRefundReason())
                        ? order.getRefundReason()
                        : "管理员强制退款"
        );
        start.setRefundApplyTime(order.getRefundApplyTime() != null ? order.getRefundApplyTime() : now);
        start.setRefundAuditTime(now);
        start.setRefundOperatorId(operatorId);
        start.setRefundStatus(3);
        start.setRefundStep(3);
        start.setRefundFailStep(null);
        start.setRefundFailReason(null);
        start.setRefundRejectReason(null);

        this.updateById(start);

        /*
         * 第二步：调用真实退款接口。
         */
        boolean walletRefundSuccess = refundToWallet(order);

        if (!walletRefundSuccess) {
            Order fail = new Order();
            fail.setId(orderId);
            fail.setRefundStatus(6);
            fail.setRefundStep(3);
            fail.setRefundFailStep(3);
            fail.setRefundFailReason("强制退款失败，请人工处理");
            this.updateById(fail);

            throw new BusinessException("强制退款失败，订单状态未修改为已退票");
        }

        /*
         * 第三步：退款成功。
         */
        Order success = new Order();
        success.setId(orderId);
        success.setStatus(ORDER_STATUS_REFUNDED);
        success.setRefundStatus(4);
        success.setRefundStep(4);
        success.setRefundReturnTime(now);
        success.setRefundFinishTime(now);

        this.updateById(success);
        markOrderEventDirty(order);

        recordRecommendBehaviorQuietly(
                order.getUserId(),
                order.getEventId(),
                UserBehavior.TYPE_REFUND_SUCCESS
        );
    }

    /**
     * 用户申请退票。
     *
     * 规则：
     * 1. 只能申请自己的订单；
     * 2. 订单必须是 6：已支付但未检票；
     * 3. 演出必须 allowRefund = 1；
     * 4. 电子票不能已经检票；
     * 5. 申请后订单状态变为 4：申请退款中。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyRefund(Long orderId, Long userId, String reason) {
        if (orderId == null) {
            throw new BusinessException("订单ID不能为空");
        }
        if (userId == null) {
            throw new BusinessException("用户未登录");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException("请填写退款理由");
        }

        Order order = this.getById(orderId);
        if (order == null || !Objects.equals(order.getUserId(), userId)) {
            throw new BusinessException("订单不存在或无权操作");
        }

        if (!Objects.equals(order.getStatus(), ORDER_STATUS_PAID_UNCHECKED)) {
            throw new BusinessException("只有已支付且未检票的订单才能申请退款");
        }

        Event event = eventMapper.selectById(order.getEventId());
        if (event == null) {
            throw new BusinessException("演出不存在");
        }

        if (!Objects.equals(event.getAllowRefund(), 1)) {
            throw new BusinessException("该演出不支持退票");
        }

        List<OrderTicket> tickets = orderTicketMapper.selectList(
                new LambdaQueryWrapper<OrderTicket>()
                        .eq(OrderTicket::getOrderId, orderId)
        );

        boolean hasCheckedTicket = tickets.stream()
                .anyMatch(ticket -> Objects.equals(ticket.getCheckStatus(), TICKET_CHECK_STATUS_CHECKED));

        if (hasCheckedTicket) {
            throw new BusinessException("订单中已有门票完成检票，无法申请退款");
        }

        Order update = new Order();
        update.setId(orderId);

// 主订单状态：申请退款中
        update.setStatus(ORDER_STATUS_REFUND_APPLYING);

// 退款申请信息
        update.setRefundReason(reason.trim());
        update.setRefundApplyTime(LocalDateTime.now());

// 退款进度初始化：走到第 1 步“申请退款”
        update.setRefundStatus(1);
        update.setRefundStep(1);

// 清理上一轮退款残留
        update.setRefundAuditTime(null);
        update.setRefundRejectReason(null);
        update.setRefundOperatorId(null);
        update.setRefundFailStep(null);
        update.setRefundFailReason(null);
        update.setRefundReturnTime(null);
        update.setRefundFinishTime(null);

        this.updateById(update);
        markOrderEventDirty(order);
    }

    /**
     * 后台订单查询参数。
     *
     * 订单ID、用户ID、演出ID都是完整匹配；
     * 演出名称是模糊匹配。
     */
    private Map<String, Object> buildAdminOrderQueryParams(Integer status, String searchType, String keyword) {
        Map<String, Object> params = new HashMap<>();

        if (status != null && status != 0) {
            params.put("status", status);
        }

        if (!StringUtils.hasText(keyword)) {
            return params;
        }

        String trimmed = keyword.trim();

        if ("orderId".equals(searchType)) {
            Long orderId = parseLong(trimmed);
            if (orderId == null) {
                throw new BusinessException("订单ID必须完整输入数字");
            }
            params.put("orderId", orderId);
            return params;
        }

        if ("userId".equals(searchType)) {
            Long userId = parseLong(trimmed);
            if (userId == null) {
                throw new BusinessException("用户ID必须完整输入数字");
            }
            params.put("userId", userId);
            return params;
        }

        if ("eventId".equals(searchType)) {
            Long eventId = parseLong(trimmed);
            if (eventId == null) {
                throw new BusinessException("演出ID必须完整输入数字");
            }
            params.put("eventId", eventId);
            return params;
        }

        if ("eventName".equals(searchType)) {
            params.put("eventName", trimmed);
        }

        return params;
    }

    /**
     * 批量补充订单的观演人和电子票列表。
     *
     * 主订单聚合信息由 OrderMapper 一条 SQL 查出；
     * 子列表仍由 OrderMapper 统一查询，避免 ServiceImpl 里手动调一堆 Mapper。
     */
    private void fillAdminOrderChildren(List<Map<String, Object>> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }

        List<Long> orderIds = orders.stream()
                .map(item -> parseLong(item.get("id")))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (orderIds.isEmpty()) {
            return;
        }

        List<Map<String, Object>> spectators = baseMapper.selectAdminOrderSpectators(orderIds);
        List<Map<String, Object>> tickets = baseMapper.selectAdminOrderTickets(orderIds);

        Map<Long, List<Map<String, Object>>> spectatorMap = spectators.stream()
                .collect(Collectors.groupingBy(item -> parseLong(item.get("orderId"))));

        Map<Long, List<Map<String, Object>>> ticketMap = tickets.stream()
                .collect(Collectors.groupingBy(item -> parseLong(item.get("orderId"))));

        for (Map<String, Object> order : orders) {
            Long orderId = parseLong(order.get("id"));
            order.put("spectators", spectatorMap.getOrDefault(orderId, Collections.emptyList()));
            order.put("tickets", ticketMap.getOrDefault(orderId, Collections.emptyList()));
        }
    }

    private String getOrderStatusText(Integer status) {
        if (Objects.equals(status, ORDER_STATUS_PENDING_PAY)) return "未支付";
        if (Objects.equals(status, ORDER_STATUS_CANCELED)) return "已取消";
        if (Objects.equals(status, ORDER_STATUS_COMPLETED)) return "已完成订单";
        if (Objects.equals(status, ORDER_STATUS_REFUND_APPLYING)) return "申请退款中";
        if (Objects.equals(status, ORDER_STATUS_EXCEPTION)) return "异常订单";
        if (Objects.equals(status, ORDER_STATUS_PAID_UNCHECKED)) return "未检票";
        if (Objects.equals(status, ORDER_STATUS_REFUNDED)) return "已退票";
        return "未知状态";
    }

    /**
     * 钱包退款入口。
     *
     * 当前返回 true 是为了先跑通订单管理流程。
     * 接入真实支付/钱包后必须改为：
     * 1. 调用退款接口；
     * 2. 查询退款状态；
     * 3. 确认退款成功后才返回 true。
     */
    private boolean refundToWallet(Order order) {
        return true;
    }

    private Long getCurrentAdminId() {
        try {
            return Long.valueOf(SecurityContextHolder.getContext().getAuthentication().getName());
        } catch (Exception e) {
            return null;
        }
    }

    private Long parseLong(Object value) {
        if (value == null) return null;
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Boolean parseBoolean(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.valueOf(String.valueOf(value));
    }

    private String sceneKey(Long eventId, Long sessionId) {
        return eventId + ":" + sessionId;
    }

    private void markOrderEventDirty(Order order) {
        if (order != null && order.getEventId() != null) {
            artistHeatService.markEventDirty(order.getEventId());
        }
    }

    private void recordRecommendBehaviorQuietly(Long userId, Long eventId, Integer behaviorType) {
        if (userId == null || eventId == null || behaviorType == null) {
            return;
        }

        try {
            recommendBehaviorService.recordBehavior(userId, null, eventId, behaviorType);
        } catch (Exception e) {
            log.warn("记录订单推荐行为失败，userId={}, eventId={}, behaviorType={}",
                    userId, eventId, behaviorType, e);
        }
    }

    private void validatePurchasableEventAndSession(Event event, EventSession session) {
        LocalDateTime now = LocalDateTime.now();

        if (event == null || Objects.equals(event.getStatus(), EVENT_STATUS_HIDDEN)) {
            throw new RuntimeException("该演出信息不存在或已下架");
        }

        if (Objects.equals(event.getStatus(), EVENT_STATUS_STOPPED)) {
            throw new RuntimeException("该演出已停售，无法创建订单");
        }

        if (!Objects.equals(event.getStatus(), EVENT_STATUS_ONLINE)) {
            throw new RuntimeException("该演出当前状态暂不可购票");
        }

        if (session == null || !Objects.equals(session.getStatus(), SESSION_STATUS_ON_SALE)) {
            throw new RuntimeException("该场次尚未上架或已停售，无法创建订单");
        }

        if (session.getShowTime() == null) {
            throw new RuntimeException("该场次尚未配置演出时间，暂不可购票");
        }

        if (!now.isBefore(session.getShowTime())) {
            throw new RuntimeException("该演出已结束，无法创建订单");
        }

        if (session.getSaleTime() == null) {
            throw new RuntimeException("该场次尚未配置开票时间，暂不可购票");
        }

        if (now.isBefore(session.getSaleTime())) {
            throw new RuntimeException("该场次尚未正式开售，无法创建订单");
        }
    }
}
