package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.entity.Artist;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.entity.UserFavorite;
import com.avemonica.ticket.mapper.UserFavoriteMapper;
import com.avemonica.ticket.service.ArtistService;
import com.avemonica.ticket.service.EventService;
import com.avemonica.ticket.service.UserFavoriteService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
        if (userId == null || targetId == null || type == null) {
            throw new RuntimeException("参数不能为空");
        }

        LambdaQueryWrapper<UserFavorite> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserFavorite::getUserId, userId)
                .eq(UserFavorite::getTargetId, targetId)
                .eq(UserFavorite::getType, type);

        UserFavorite exist = this.getOne(wrapper);
        boolean isNowFavorited;

        if (exist != null) {
            // 取消收藏/关注
            this.removeById(exist.getId());
            isNowFavorited = false;
        } else {
            // 添加收藏/关注
            UserFavorite fav = new UserFavorite();
            fav.setUserId(userId);
            fav.setTargetId(targetId);
            fav.setType(type);
            fav.setCreateTime(LocalDateTime.now());
            this.save(fav);
            isNowFavorited = true;
        }

        // type=1：演出想看，同步 Redis
        if (Objects.equals(type, 1)) {
            String wantKey = "event:want:" + targetId;
            if (isNowFavorited) {
                redisTemplate.opsForSet().add(wantKey, userId.toString());
            } else {
                redisTemplate.opsForSet().remove(wantKey, userId.toString());
            }
        }

        // type=2：音乐人关注，同步 tb_artist.like_count
        if (Objects.equals(type, 2)) {
            if (isNowFavorited) {
                artistService.update(
                        new LambdaUpdateWrapper<Artist>()
                                .eq(Artist::getId, targetId)
                                .setSql("like_count = like_count + 1")
                );
            } else {
                artistService.update(
                        new LambdaUpdateWrapper<Artist>()
                                .eq(Artist::getId, targetId)
                                .setSql("like_count = GREATEST(like_count - 1, 0)")
                );
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