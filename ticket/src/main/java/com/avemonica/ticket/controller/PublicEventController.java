package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.entity.TicketCategory;
import com.avemonica.ticket.service.EventService;
import com.avemonica.ticket.service.TicketService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/api/event")
public class PublicEventController {

    @Autowired
    private EventService eventService;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private com.avemonica.ticket.mapper.ArtistMapper artistMapper;

    // 🚨 引入 Redis
    @Autowired
    private StringRedisTemplate redisTemplate;

    // 🚨 引入 JVM 本地缓存
    @Autowired
    @Qualifier("eventLocalCache")
    private Cache<String, String> localCache;

    // 🚨 引入 JSON 序列化工具
    @Autowired
    private ObjectMapper objectMapper;

    // 提取公共前缀，防止 Key 冲突
    private static final String EVENT_CACHE_KEY_PREFIX = "event:detail:";

    @GetMapping("/upcoming")
    public Result<List<Event>> getUpcomingEvents() {
        // 首页列表由于带了 RAND() 随机推荐，通常可以只做 Redis 级别的缓存，或者对热门列表做定时预热。
        // 为了演示核心机制，我们将重点放在下方的【演出详情】接口
        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Event::getStatus, 1)
                .gt(Event::getShowTime, LocalDateTime.now())
                .last("ORDER BY RAND() LIMIT 10");

        List<Event> events = eventService.list(wrapper);
        for (Event event : events) {
            List<TicketCategory> tickets = ticketService.list(
                    new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, event.getId())
            );
            event.setTickets(tickets);
        }
        return Result.success(events);
    }

    /**
     * 获取演出详情：多级缓存核心战区 (Caffeine -> Redis -> MySQL)
     */
    @GetMapping("/{id}")
    public Result<Event> getEventDetail(@PathVariable Long id) {
        String cacheKey = EVENT_CACHE_KEY_PREFIX + id;
        Event event = null;

        try {
            // ==========================================
            // L1: 查找本地缓存 (Caffeine)
            // ==========================================
            String localJson = localCache.getIfPresent(cacheKey);
            if (StringUtils.hasText(localJson)) {
                log.info("🎯 命中 L1 本地缓存, EventID: {}", id);
                return Result.success(objectMapper.readValue(localJson, Event.class));
            }

            // ==========================================
            // L2: 查找分布式缓存 (Redis)
            // ==========================================
            String redisJson = redisTemplate.opsForValue().get(cacheKey);
            if (StringUtils.hasText(redisJson)) {
                log.info("🎯 命中 L2 Redis 缓存, EventID: {}", id);

                // 将数据回填到本地缓存，供下一次请求直接使用
                localCache.put(cacheKey, redisJson);

                return Result.success(objectMapper.readValue(redisJson, Event.class));
            }

            // ==========================================
            // L3: 缓存全未命中，查询数据库 (MySQL)
            // ==========================================
            log.warn("⚠️ 缓存击穿，查询 MySQL, EventID: {}", id);
            // 🚨 工业界防击穿警告：高并发下，这里应当加分布式锁 (Redisson)，防止10万人同时把 DB 压垮。
            // 简化演示，直接查库：
            event = eventService.getById(id);
            if (event == null || event.getStatus() == 4) {
                // 缓存穿透防御：如果是恶意攻击查不存在的ID，也应缓存一个空对象，避免一直打 DB
                return Result.error("该演出不存在或已下架");
            }

            // 查票档信息
            List<TicketCategory> tickets = ticketService.list(
                    new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, id)
            );
            event.setTickets(tickets);

            // 查参演艺人信息
            List<java.util.Map<String, Object>> artists = artistMapper.selectArtistMapsByEventId(id);
            event.setArtists(artists);

            // ==========================================
            // 将查询结果回填到 Redis 和本地缓存
            // ==========================================
            String finalJson = objectMapper.writeValueAsString(event);

            // Redis 存 30 分钟 (可设置随机过期时间防止雪崩)
            redisTemplate.opsForValue().set(cacheKey, finalJson, 30, TimeUnit.MINUTES);
            // Caffeine 存 5 秒
            localCache.put(cacheKey, finalJson);

            return Result.success(event);

        } catch (Exception e) {
            log.error("获取演出详情出现异常: {}", e.getMessage());
            return Result.error("系统繁忙，请稍后再试");
        }
    }

    /**
     * 动静分离策略：轻量级实时库存接口
     * 仅返回 Map<票档ID, 剩余库存>
     */
    @GetMapping("/stock/{id}")
    public Result<java.util.Map<Long, Integer>> getEventStock(@PathVariable Long id) {
        // 🚨 极轻量级查询：利用 MyBatis-Plus 仅 select 需要的两个字段，绝不去查庞大的海报和详情字段
        List<TicketCategory> tickets = ticketService.list(
                new LambdaQueryWrapper<TicketCategory>()
                        .select(TicketCategory::getId, TicketCategory::getRemainingStock)
                        .eq(TicketCategory::getEventId, id)
        );

        // 工业界进阶提示：如果你的库存已经同步到了 Redis，这里可以直接从 Redis 批量 MGET 查出库存，连 MySQL 都不用碰！

        // 将 List 转换为 Map，方便前端直接通过 ID 匹配
        java.util.Map<Long, Integer> stockMap = tickets.stream()
                .collect(java.util.stream.Collectors.toMap(TicketCategory::getId, TicketCategory::getRemainingStock));

        return Result.success(stockMap);
    }
}