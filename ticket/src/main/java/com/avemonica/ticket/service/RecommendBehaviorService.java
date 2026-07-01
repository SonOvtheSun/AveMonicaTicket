package com.avemonica.ticket.service;

import com.avemonica.ticket.entity.UserBehavior;

import java.util.List;

public interface RecommendBehaviorService {

    /**
     * 记录用户行为。
     *
     * @param userId 用户ID，未登录时可以为空
     * @param visitorId 游客ID，未登录时使用
     * @param eventId 演出ID
     * @param behaviorType 行为类型
     */
    void recordBehavior(Long userId, String visitorId, Long eventId, Integer behaviorType);

    /**
     * 查询某个用户最近的行为。
     * 后续生成用户画像时，只允许基于最近 200 条行为计算。
     */
    List<UserBehavior> listRecentUserBehaviors(Long userId, int limit);

    /**
     * 查询某个游客最近的行为。
     */
    List<UserBehavior> listRecentVisitorBehaviors(String visitorId, int limit);
}