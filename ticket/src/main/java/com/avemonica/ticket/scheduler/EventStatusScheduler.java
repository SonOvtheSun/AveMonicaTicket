package com.avemonica.ticket.scheduler;

import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.mapper.EventMapper;
import com.avemonica.ticket.service.ArtistHeatService;
import com.avemonica.ticket.service.EventService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 演出状态自动同步任务。
 *
 * 每天凌晨 1 点：
 * 将状态为上架中的、所有可见场次均已到期的演出，自动设置为已停售。
 */
@Slf4j
@Component
public class EventStatusScheduler {

    private static final int EVENT_STATUS_ONLINE = 1;
    private static final int EVENT_STATUS_STOPPED = 3;
    private static final int SESSION_STATUS_HIDDEN = 4;

    private static final String EVENT_CACHE_KEY_PREFIX = "event:detail:";

    @Autowired
    private EventMapper eventMapper;

    @Autowired
    private EventService eventService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    @Qualifier("eventLocalCache")
    private Cache<String, String> localCache;

    @Autowired
    private ArtistHeatService artistHeatService;

    /**
     * 每天凌晨 1 点执行。
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void stopEndedOnlineEvents() {
        LocalDateTime now = LocalDateTime.now();

        List<Event> endedEvents = eventMapper.selectList(
                new LambdaQueryWrapper<Event>()
                        .select(Event::getId)
                        .eq(Event::getStatus, EVENT_STATUS_ONLINE)
                        // 至少存在一个非隐藏场次
                        .apply(
                                "EXISTS (" +
                                        "SELECT 1 FROM tb_event_session s " +
                                        "WHERE s.event_id = tb_event.id " +
                                        "AND s.status <> {0} " +
                                        "AND s.show_time IS NOT NULL" +
                                        ")",
                                SESSION_STATUS_HIDDEN
                        )
                        // 不存在未到期的非隐藏场次：即所有可见场次都已经 show_time <= now
                        .apply(
                                "NOT EXISTS (" +
                                        "SELECT 1 FROM tb_event_session s " +
                                        "WHERE s.event_id = tb_event.id " +
                                        "AND s.status <> {0} " +
                                        "AND s.show_time IS NOT NULL " +
                                        "AND s.show_time > {1}" +
                                        ")",
                                SESSION_STATUS_HIDDEN,
                                now
                        )
        );

        if (endedEvents == null || endedEvents.isEmpty()) {
            log.info("演出状态自动同步完成：没有需要设置为已停售的演出");
            return;
        }

        List<Long> eventIds = endedEvents.stream()
                .map(Event::getId)
                .collect(Collectors.toList());

        boolean updated = eventService.update(
                new LambdaUpdateWrapper<Event>()
                        .in(Event::getId, eventIds)
                        .eq(Event::getStatus, EVENT_STATUS_ONLINE)
                        .set(Event::getStatus, EVENT_STATUS_STOPPED)
                        .set(Event::getUpdateTime, now)
        );

        if (!updated) {
            log.warn("演出状态自动同步执行失败，eventIds={}", eventIds);
            return;
        }

        for (Long eventId : eventIds) {
            evictEventDetailCache(eventId);
            artistHeatService.markEventDirty(eventId);
        }

        log.info("演出状态自动同步完成，已设置为已停售数量={}，eventIds={}", eventIds.size(), eventIds);
    }

    private void evictEventDetailCache(Long eventId) {
        if (eventId == null) {
            return;
        }

        String cacheKey = EVENT_CACHE_KEY_PREFIX + eventId;
        localCache.invalidate(cacheKey);
        redisTemplate.delete(cacheKey);
    }
}