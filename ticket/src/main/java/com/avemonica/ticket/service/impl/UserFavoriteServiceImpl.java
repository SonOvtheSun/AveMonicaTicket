package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.entity.Artist;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.entity.UserFavorite;
import com.avemonica.ticket.mapper.UserFavoriteMapper;
import com.avemonica.ticket.service.ArtistService;
import com.avemonica.ticket.service.EventService;
import com.avemonica.ticket.service.UserFavoriteService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class UserFavoriteServiceImpl extends ServiceImpl<UserFavoriteMapper, UserFavorite> implements UserFavoriteService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EventService eventService;

    @Autowired
    private ArtistService artistService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggleFavorite(Long userId, Long targetId, Integer type) {
        // 1. 查询数据库中是否已存在该记录
        LambdaQueryWrapper<UserFavorite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getTargetId, targetId)
                .eq(UserFavorite::getType, type);

        UserFavorite exist = this.getOne(wrapper);
        boolean isNowFavorited;

        if (exist != null) {
            // 如果存在，说明是“取消收藏”
            this.removeById(exist.getId());
            isNowFavorited = false;
        } else {
            // 如果不存在，说明是“添加收藏”
            UserFavorite fav = new UserFavorite();
            fav.setUserId(userId);
            fav.setTargetId(targetId);
            fav.setType(type);
            fav.setCreateTime(LocalDateTime.now());
            this.save(fav);
            isNowFavorited = true;
        }

        // 🚨 2. 核心架构桥接：如果是演出(type=1)，同步更新 Redis 的 Set，保障演出详情页的秒开性能
        if (type == 1) {
            String wantKey = "event:want:" + targetId;
            if (isNowFavorited) {
                redisTemplate.opsForSet().add(wantKey, userId.toString());
            } else {
                redisTemplate.opsForSet().remove(wantKey, userId.toString());
            }
        }

        return isNowFavorited;
    }

    @Override
    public List<Event> getUserFavoriteEvents(Long userId) {
        // 1. 查出所有的目标演出 ID（按收藏时间倒序）
        List<UserFavorite> favs = this.list(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getType, 1)
                .orderByDesc(UserFavorite::getCreateTime));

        if (favs.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> eventIds = favs.stream().map(UserFavorite::getTargetId).collect(Collectors.toList());

        // 2. 批量查出演出详情
        List<Event> events = eventService.listByIds(eventIds);

        // 3. 按照用户收藏的顺序重新排序
        Map<Long, Event> eventMap = events.stream().collect(Collectors.toMap(Event::getId, e -> e));
        return eventIds.stream().map(eventMap::get).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public List<Artist> getUserFavoriteArtists(Long userId) {
        // 1. 查出所有的目标艺人 ID（按收藏时间倒序）
        List<UserFavorite> favs = this.list(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getType, 2)
                .orderByDesc(UserFavorite::getCreateTime));

        if (favs.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> artistIds = favs.stream().map(UserFavorite::getTargetId).collect(Collectors.toList());

        // 2. 批量查出艺人详情
        List<Artist> artists = artistService.listByIds(artistIds);

        // 3. 按照用户关注的顺序重新排序
        Map<Long, Artist> artistMap = artists.stream().collect(Collectors.toMap(Artist::getId, a -> a));
        return artistIds.stream().map(artistMap::get).filter(Objects::nonNull).collect(Collectors.toList());
    }
}