package com.avemonica.ticket.service;

import com.avemonica.ticket.entity.UserRecommendProfile;

public interface RecommendProfileService {

    /**
     * 刷新某个用户的推荐画像。
     * 只基于最近 200 条行为计算，防止画像 JSON 过长。
     */
    void refreshUserProfile(Long userId);

    /**
     * 获取用户画像。
     */
    UserRecommendProfile getUserProfile(Long userId);
}