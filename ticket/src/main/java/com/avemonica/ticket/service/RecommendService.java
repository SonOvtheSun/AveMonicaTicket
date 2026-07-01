package com.avemonica.ticket.service;

import com.avemonica.ticket.entity.Event;

import java.util.List;

public interface RecommendService {

    /**
     * 首页为您推荐。
     *
     * 优先读 tb_user_recommend_event；
     * 如果没有预计算结果，再走实时兜底推荐。
     */
    List<Event> recommendHomeEvents(Long userId, String city, Integer size);

    /**
     * 刷新某个用户首页推荐结果表。
     *
     * 由 RecommendEventScheduler 在服务器闲时调用。
     */
    void refreshUserHomeRecommendEvents(Long userId, Integer size);
}