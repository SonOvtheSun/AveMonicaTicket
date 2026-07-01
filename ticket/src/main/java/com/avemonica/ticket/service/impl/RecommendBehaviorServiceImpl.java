package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.entity.UserBehavior;
import com.avemonica.ticket.mapper.UserBehaviorMapper;
import com.avemonica.ticket.service.RecommendBehaviorService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
public class RecommendBehaviorServiceImpl implements RecommendBehaviorService {

    /**
     * 单个登录用户 / 单个游客最多保留最近 200 条行为。
     */
    private static final int MAX_BEHAVIOR_COUNT = 200;

    @Autowired
    private UserBehaviorMapper userBehaviorMapper;

    @Override
    public void recordBehavior(Long userId, String visitorId, Long eventId, Integer behaviorType) {
        if (eventId == null || behaviorType == null) {
            return;
        }

        String safeVisitorId = normalizeVisitorId(visitorId);

        // 登录用户和游客ID至少要有一个，否则无法归属推荐画像。
        if (userId == null && !StringUtils.hasText(visitorId)) {
            return;
        }

        Integer weight = resolveWeight(behaviorType);
        if (weight == null) {
            return;
        }

        UserBehavior behavior = new UserBehavior();
        behavior.setUserId(userId);
        behavior.setVisitorId(StringUtils.hasText(visitorId) ? visitorId : null);
        behavior.setEventId(eventId);
        behavior.setBehaviorType(behaviorType);
        behavior.setBehaviorWeight(weight);
        behavior.setCreateTime(LocalDateTime.now());

        userBehaviorMapper.insert(behavior);

        // 核心：写入后立刻裁剪，只保留最近 200 条。
        pruneOldBehaviors(userId, safeVisitorId);
    }

    private String normalizeVisitorId(String visitorId) {
        if (!StringUtils.hasText(visitorId)) {
            return null;
        }

        String text = visitorId.trim();
        return text.length() > 100 ? text.substring(0, 100) : text;
    }

    @Override
    public List<UserBehavior> listRecentUserBehaviors(Long userId, int limit) {
        if (userId == null) {
            return Collections.emptyList();
        }

        int safeLimit = normalizeLimit(limit);

        return userBehaviorMapper.selectList(
                new LambdaQueryWrapper<UserBehavior>()
                        .eq(UserBehavior::getUserId, userId)
                        .orderByDesc(UserBehavior::getCreateTime)
                        .orderByDesc(UserBehavior::getId)
                        .last("LIMIT " + safeLimit)
        );
    }

    @Override
    public List<UserBehavior> listRecentVisitorBehaviors(String visitorId, int limit) {
        if (!StringUtils.hasText(visitorId)) {
            return Collections.emptyList();
        }

        int safeLimit = normalizeLimit(limit);

        return userBehaviorMapper.selectList(
                new LambdaQueryWrapper<UserBehavior>()
                        .isNull(UserBehavior::getUserId)
                        .eq(UserBehavior::getVisitorId, visitorId)
                        .orderByDesc(UserBehavior::getCreateTime)
                        .orderByDesc(UserBehavior::getId)
                        .last("LIMIT " + safeLimit)
        );
    }

    private void pruneOldBehaviors(Long userId, String visitorId) {
        if (userId != null) {
            userBehaviorMapper.deleteOldByUserId(userId, MAX_BEHAVIOR_COUNT);
            return;
        }

        if (StringUtils.hasText(visitorId)) {
            userBehaviorMapper.deleteOldByVisitorId(visitorId, MAX_BEHAVIOR_COUNT);
        }
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return MAX_BEHAVIOR_COUNT;
        }
        return Math.min(limit, MAX_BEHAVIOR_COUNT);
    }

    private Integer resolveWeight(Integer behaviorType) {
        switch (behaviorType) {
            case UserBehavior.TYPE_VIEW_EVENT:
                return UserBehavior.WEIGHT_VIEW_EVENT;
            case UserBehavior.TYPE_WANT_EVENT:
                return UserBehavior.WEIGHT_WANT_EVENT;
            case UserBehavior.TYPE_CANCEL_WANT_EVENT:
                return UserBehavior.WEIGHT_CANCEL_WANT_EVENT;
            case UserBehavior.TYPE_CREATE_ORDER:
                return UserBehavior.WEIGHT_CREATE_ORDER;
            case UserBehavior.TYPE_PAY_ORDER:
                return UserBehavior.WEIGHT_PAY_ORDER;
            case UserBehavior.TYPE_COMMENT:
                return UserBehavior.WEIGHT_COMMENT;
            case UserBehavior.TYPE_SHARE:
                return UserBehavior.WEIGHT_SHARE;
            case UserBehavior.TYPE_REFUND_SUCCESS:
                return UserBehavior.WEIGHT_REFUND_SUCCESS;
            default:
                return null;
        }
    }
}