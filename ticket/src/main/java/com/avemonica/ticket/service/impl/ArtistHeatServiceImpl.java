package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.entity.Artist;
import com.avemonica.ticket.mapper.ArtistHeatMapper;
import com.avemonica.ticket.service.ArtistHeatService;
import com.avemonica.ticket.service.ArtistService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ArtistHeatServiceImpl implements ArtistHeatService {

    private static final String DIRTY_ARTIST_KEY = "artist:heat:dirty:artist";
    private static final String DIRTY_EVENT_KEY = "artist:heat:dirty:event";
    private static final String ARTIST_HEAT_RANK_KEY = "artist:heat:rank";

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ArtistHeatMapper artistHeatMapper;

    @Autowired
    private ArtistService artistService;

    @Override
    public void markArtistDirty(Long artistId) {
        if (artistId == null) return;
        redisTemplate.opsForSet().add(DIRTY_ARTIST_KEY, String.valueOf(artistId));
    }

    @Override
    public void markEventDirty(Long eventId) {
        if (eventId == null) return;
        redisTemplate.opsForSet().add(DIRTY_EVENT_KEY, String.valueOf(eventId));
    }

    /**
     * 每分钟批量刷新被标记为 dirty 的音乐人热度。
     * 前台访问不参与计算。
     */
    @Override
    @Scheduled(fixedDelay = 60_000)
    public void refreshDirtyHeat() {
        List<String> eventIdTexts = redisTemplate.opsForSet().pop(DIRTY_EVENT_KEY, 200);

        if (!CollectionUtils.isEmpty(eventIdTexts)) {
            List<Long> eventIds = eventIdTexts.stream()
                    .map(this::parseLong)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            if (!eventIds.isEmpty()) {
                List<Long> artistIds = artistHeatMapper.selectArtistIdsByEventIds(eventIds);
                if (!CollectionUtils.isEmpty(artistIds)) {
                    String[] values = artistIds.stream()
                            .filter(Objects::nonNull)
                            .map(String::valueOf)
                            .distinct()
                            .toArray(String[]::new);
                    redisTemplate.opsForSet().add(DIRTY_ARTIST_KEY, values);
                }
            }
        }

        List<String> artistIdTexts = redisTemplate.opsForSet().pop(DIRTY_ARTIST_KEY, 200);
        if (CollectionUtils.isEmpty(artistIdTexts)) {
            return;
        }

        artistIdTexts.stream()
                .map(this::parseLong)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(this::refreshArtistHeat);
    }

    @Override
    public void refreshArtistHeat(Long artistId) {
        if (artistId == null) return;

        Long heatValue = artistHeatMapper.calculateArtistHeat(artistId);
        Integer recentWeekLikeCount = artistHeatMapper.calculateRecentWeekLikeCount(artistId);
        Integer recentEventCount = artistHeatMapper.calculateRecentEventCount(artistId);

        long safeHeatValue = heatValue == null ? 0L : heatValue;
        int safeWeekLikeCount = recentWeekLikeCount == null ? 0 : recentWeekLikeCount;
        int safeRecentEventCount = recentEventCount == null ? 0 : recentEventCount;

        artistService.update(
                new LambdaUpdateWrapper<Artist>()
                        .eq(Artist::getId, artistId)
                        .set(Artist::getHeatValue, safeHeatValue)
                        .set(Artist::getRecentWeekLikeCount, safeWeekLikeCount)
                        .set(Artist::getRecentEventCount, safeRecentEventCount)
                        .set(Artist::getHeatUpdateTime, LocalDateTime.now())
        );

        redisTemplate.opsForZSet().add(ARTIST_HEAT_RANK_KEY, String.valueOf(artistId), safeHeatValue);
    }

    @Override
    public void refreshAllArtistHeat() {
        List<Artist> artists = artistService.list(
                new LambdaQueryWrapper<Artist>()
                        .select(Artist::getId)
                        .eq(Artist::getAuditStatus, 1)
        );

        for (Artist artist : artists) {
            refreshArtistHeat(artist.getId());
        }
    }

    private Long parseLong(String text) {
        try {
            return Long.valueOf(text);
        } catch (Exception e) {
            return null;
        }
    }

    @Scheduled(cron = "0 30 3 * * ?")
    public void rebuildAllArtistHeat() {
        refreshAllArtistHeat();
    }
}