package com.avemonica.ticket.service;

import com.avemonica.ticket.entity.Artist;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.entity.UserFavorite;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface UserFavoriteService extends IService<UserFavorite> {

    /**
     * 切换收藏/关注状态
     * @return true: 当前为已收藏, false: 当前为未收藏
     */
    boolean toggleFavorite(Long userId, Long targetId, Integer type);

    /**
     * 获取我收藏的演出列表
     */
    List<Event> getUserFavoriteEvents(Long userId);

    /**
     * 获取我关注的音乐人列表
     */
    List<Artist> getUserFavoriteArtists(Long userId);
}