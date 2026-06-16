
package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.entity.Banner;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.entity.EventSession;
import com.avemonica.ticket.entity.TicketCategory;
import com.avemonica.ticket.mapper.ArtistMapper;
import com.avemonica.ticket.mapper.EventSessionMapper;
import com.avemonica.ticket.service.BannerService;
import com.avemonica.ticket.service.EventService;
import com.avemonica.ticket.service.TicketService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/event")
public class PublicEventController {

    @Autowired
    private EventService eventService;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private EventSessionMapper eventSessionMapper;

    @Autowired
    private BannerService bannerService;

    @Autowired
    private ArtistMapper artistMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    @Qualifier("eventLocalCache")
    private Cache<String, String> localCache;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String EVENT_CACHE_KEY_PREFIX = "event:detail:";
    private static final String EVENT_VIEW_KEY_PREFIX = "event:views:";
    private static final String EVENT_VIEW_DEDUP_KEY_PREFIX = "event:view:dedup:";
    private static final String EVENT_WANT_KEY_PREFIX = "event:want:";

    private static final int EVENT_STATUS_ONLINE = 1;
    private static final int EVENT_STATUS_HIDDEN = 4;
    private static final int SESSION_STATUS_HIDDEN = 4;

    /**
     * 首页随机推荐。
     *
     * 新数据结构约定：
     * 1. 票档只挂在 sessions[*].tickets 下；
     * 2. 不再返回 event.tickets 作为根级票档；
     * 3. 只有存在未来场次的演出才进入 upcoming。
     */
    @GetMapping("/upcoming")
    public Result<List<Event>> getUpcomingEvents(@RequestParam(required = false, defaultValue = "全国") String city) {
        LocalDateTime now = LocalDateTime.now();

        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Event::getStatus, EVENT_STATUS_ONLINE)
                .apply(
                        "EXISTS (" +
                                "SELECT 1 FROM tb_event_session s " +
                                "WHERE s.event_id = tb_event.id " +
                                "AND s.status <> {0} " +
                                "AND s.show_time > {1}" +
                                ")",
                        SESSION_STATUS_HIDDEN,
                        now
                );

        if (StringUtils.hasText(city) && !"全国".equals(city)) {
            wrapper.like(Event::getCity, city.replace("市", ""));
        }

        wrapper.last("ORDER BY RAND() LIMIT 10");

        List<Event> events = eventService.list(wrapper);
        for (Event event : events) {
            attachSessionsTicketsAndArtists(event, false);
        }

        return Result.success(events);
    }

    /**
     * C 端演出详情。
     *
     * 缓存策略：
     * 1. L1：Caffeine 本地缓存；
     * 2. L2：Redis 分布式缓存；
     * 3. L3：MySQL 回源。
     *
     * 实时数据：
     * 浏览量、想看数、是否已想看不写入缓存，每次返回前动态注入。
     */
    @GetMapping("/detail/{id}")
    public Result<Event> getEventDetail(
            @PathVariable Long id,
            @RequestParam(required = false) String viewToken
    ) {
        String cacheKey = EVENT_CACHE_KEY_PREFIX + id;

        try {
            Event event = readEventFromCache(cacheKey);

            if (event == null) {
                event = loadEventDetailFromDatabase(id);
                if (event == null) {
                    return Result.error("该演出不存在或已下架");
                }

                String json = objectMapper.writeValueAsString(event);
                redisTemplate.opsForValue().set(cacheKey, json, 30, TimeUnit.MINUTES);
                localCache.put(cacheKey, json);
            }

            refreshCollectionFields(id, event);
            injectRealtimeStats(id, viewToken, event);

            return Result.success(event);
        } catch (Exception e) {
            log.error("获取演出详情出现异常，eventId={}", id, e);
            return Result.error("系统繁忙，请稍后再试");
        }
    }

    /**
     * 同一合集下的演出列表。
     * 该接口不调用 detail 接口，避免切换列表时增加浏览量。
     */
    @GetMapping("/collection/{collectionId}/events")
    public Result<List<Event>> getCollectionEvents(@PathVariable Long collectionId) {
        if (collectionId == null) {
            return Result.error("合集ID不能为空");
        }

        List<Event> events = eventService.list(
                new LambdaQueryWrapper<Event>()
                        .select(
                                Event::getId,
                                Event::getTitle,
                                Event::getCollectionId,
                                Event::getCollectionAlias,
                                Event::getCity,
                                Event::getVenue,
                                Event::getShowTime,
                                Event::getStatus
                        )
                        .eq(Event::getCollectionId, collectionId)
                        .ne(Event::getStatus, EVENT_STATUS_HIDDEN)
                        .orderByAsc(Event::getShowTime)
        );

        return Result.success(events);
    }

    /**
     * 场次级实时库存。
     * 新模型只允许按 sessionId 查询库存。
     */
    @GetMapping("/session/stock/{sessionId}")
    public Result<Map<Long, Integer>> getSessionStock(@PathVariable Long sessionId) {
        List<TicketCategory> tickets = ticketService.list(
                new LambdaQueryWrapper<TicketCategory>()
                        .select(TicketCategory::getId, TicketCategory::getRemainingStock)
                        .eq(TicketCategory::getSessionId, sessionId)
        );

        Map<Long, Integer> stockMap = tickets.stream()
                .collect(Collectors.toMap(TicketCategory::getId, TicketCategory::getRemainingStock));

        return Result.success(stockMap);
    }

    /**
     * 当前生效的首页 Banner。
     */
    @GetMapping("/banner/active")
    public Result<List<Banner>> getActiveBanners() {
        LocalDateTime now = LocalDateTime.now();

        List<Banner> activeBanners = bannerService.list(
                new LambdaQueryWrapper<Banner>()
                        .eq(Banner::getAuditStatus, 1)
                        .le(Banner::getStartTime, now)
                        .ge(Banner::getEndTime, now)
                        .orderByDesc(Banner::getCreateTime)
        );

        return Result.success(activeBanners);
    }

    /**
     * C 端演出分页检索。
     *
     * 新数据结构约定：
     * 1. 时间筛选基于 tb_event_session.show_time；
     * 2. 票档返回在 sessions[*].tickets；
     * 3. event.showTime 只作为列表摘要排序字段，不承载购票业务。
     */
    @GetMapping("/page")
    public Result<Map<String, Object>> pageEvents(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String style,
            @RequestParam(required = false) Integer timeType,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long artistId
    ) {
        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<>();
        wrapper.ne(Event::getStatus, EVENT_STATUS_HIDDEN);

        applyArtistFilter(wrapper, artistId);
        applyCityFilter(wrapper, city);
        applyStyleFilter(wrapper, style);
        applyKeywordFilter(wrapper, keyword);
        applyTimeFilter(wrapper, timeType, startDate, endDate);

        wrapper.last(
                "ORDER BY show_time IS NULL ASC, " +
                        "CASE WHEN show_time >= NOW() THEN 0 ELSE 1 END, " +
                        "ABS(TIMESTAMPDIFF(SECOND, show_time, NOW())) ASC"
        );

        IPage<Event> pageData = eventService.page(new Page<>(current, size), wrapper);

        for (Event event : pageData.getRecords()) {
            attachSessionsTicketsAndArtists(event, true);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("records", pageData.getRecords());
        result.put("total", pageData.getTotal());
        result.put("current", pageData.getCurrent());
        result.put("size", pageData.getSize());
        result.put("pages", pageData.getPages());

        return Result.success(result);
    }

    /**
     * 切换“想看”状态。
     */
    @PostMapping("/want/{id}")
    public Result<Boolean> toggleWant(@PathVariable Long id) {
        try {
            String userId = getCurrentUserId();
            if (!StringUtils.hasText(userId)) {
                Result<Boolean> res = Result.error("请先登录");
                res.setCode(401);
                return res;
            }

            String wantKey = EVENT_WANT_KEY_PREFIX + id;
            Boolean isMember = redisTemplate.opsForSet().isMember(wantKey, userId);

            if (Boolean.TRUE.equals(isMember)) {
                redisTemplate.opsForSet().remove(wantKey, userId);
                return Result.success("操作成功", false);
            }

            redisTemplate.opsForSet().add(wantKey, userId);
            return Result.success("操作成功", true);
        } catch (Exception e) {
            log.error("切换想看状态异常，eventId={}", id, e);
            return Result.error("系统异常");
        }
    }

    /**
     * 从 L1 / L2 缓存读取演出详情。
     */
    private Event readEventFromCache(String cacheKey) throws Exception {
        String localJson = localCache.getIfPresent(cacheKey);
        if (StringUtils.hasText(localJson)) {
            log.info("命中 L1 本地缓存，cacheKey={}", cacheKey);
            return objectMapper.readValue(localJson, Event.class);
        }

        String redisJson = redisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(redisJson)) {
            log.info("命中 L2 Redis 缓存，cacheKey={}", cacheKey);
            localCache.put(cacheKey, redisJson);
            return objectMapper.readValue(redisJson, Event.class);
        }

        return null;
    }

    /**
     * 从数据库加载完整详情。
     */
    private Event loadEventDetailFromDatabase(Long eventId) {
        Event event = eventService.getById(eventId);
        if (event == null || event.getStatus() == EVENT_STATUS_HIDDEN) {
            return null;
        }

        attachSessionsTicketsAndArtists(event, true);
        return event;
    }

    /**
     * 装配场次、场次下票档、艺人信息。
     * 根级 event.tickets 不再返回，避免继续依赖旧数据结构。
     */
    private void attachSessionsTicketsAndArtists(Event event, boolean includeArtists) {
        if (event == null || event.getId() == null) {
            return;
        }

        Long eventId = event.getId();

        List<EventSession> sessions = eventSessionMapper.selectList(
                new LambdaQueryWrapper<EventSession>()
                        .eq(EventSession::getEventId, eventId)
                        .ne(EventSession::getStatus, SESSION_STATUS_HIDDEN)
                        .orderByAsc(EventSession::getSortOrder)
                        .orderByAsc(EventSession::getShowTime)
        );

        if (!sessions.isEmpty()) {
            List<Long> sessionIds = sessions.stream().map(EventSession::getId).collect(Collectors.toList());

            List<TicketCategory> tickets = ticketService.list(
                    new LambdaQueryWrapper<TicketCategory>()
                            .in(TicketCategory::getSessionId, sessionIds)
            );

            Map<Long, List<TicketCategory>> ticketMap = tickets.stream()
                    .collect(Collectors.groupingBy(TicketCategory::getSessionId));

            for (EventSession session : sessions) {
                session.setTickets(ticketMap.getOrDefault(session.getId(), new ArrayList<>()));
            }
        }

        event.setSessions(sessions);

        if (includeArtists) {
            List<Map<String, Object>> artists = artistMapper.selectArtistMapsByEventId(eventId);
            event.setArtists(artists);
        }
    }

    /**
     * 合集归属字段实时刷新。
     */
    private void refreshCollectionFields(Long eventId, Event event) {
        Event collectionInfo = eventService.getOne(
                new LambdaQueryWrapper<Event>()
                        .select(Event::getId, Event::getCollectionId, Event::getCollectionAlias)
                        .eq(Event::getId, eventId),
                false
        );

        if (collectionInfo != null) {
            event.setCollectionId(collectionInfo.getCollectionId());
            event.setCollectionAlias(collectionInfo.getCollectionAlias());
        }
    }

    /**
     * 注入浏览量、想看总数、当前用户是否已想看。
     */
    private void injectRealtimeStats(Long eventId, String viewToken, Event event) {
        String viewsKey = EVENT_VIEW_KEY_PREFIX + eventId;
        String wantKey = EVENT_WANT_KEY_PREFIX + eventId;

        Long currentViews = increasePageViews(eventId, viewToken, viewsKey);
        event.setPageViews((event.getPageViews() != null ? event.getPageViews() : 0) + currentViews.intValue());

        Long wantCount = redisTemplate.opsForSet().size(wantKey);
        if (wantCount != null && wantCount > 0) {
            event.setWantCount(wantCount.intValue());
        }

        String userId = getCurrentUserId();
        event.setHasWanted(StringUtils.hasText(userId)
                && Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(wantKey, userId)));
    }

    /**
     * 浏览量去重。
     * 前端每次进入详情页会传 viewToken，同一 token 十分钟内只计一次。
     */
    private Long increasePageViews(Long eventId, String viewToken, String viewsKey) {
        if (StringUtils.hasText(viewToken)) {
            String dedupKey = EVENT_VIEW_DEDUP_KEY_PREFIX + eventId + ":" + viewToken;
            Boolean firstView = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", 10, TimeUnit.MINUTES);

            if (Boolean.TRUE.equals(firstView)) {
                return redisTemplate.opsForValue().increment(viewsKey);
            }

            String currentValue = redisTemplate.opsForValue().get(viewsKey);
            return StringUtils.hasText(currentValue) ? Long.valueOf(currentValue) : 0L;
        }

        return redisTemplate.opsForValue().increment(viewsKey);
    }

    private String getCurrentUserId() {
        try {
            String userId = org.springframework.security.core.context.SecurityContextHolder
                    .getContext()
                    .getAuthentication()
                    .getName();

            return StringUtils.hasText(userId) && !"anonymousUser".equals(userId) ? userId : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void applyArtistFilter(LambdaQueryWrapper<Event> wrapper, Long artistId) {
        if (artistId != null) {
            wrapper.inSql(Event::getId, "SELECT event_id FROM tb_event_artist WHERE artist_id = " + artistId);
        }
    }

    private void applyCityFilter(LambdaQueryWrapper<Event> wrapper, String city) {
        if (StringUtils.hasText(city) && !"全部".equals(city) && !"全国".equals(city)) {
            wrapper.like(Event::getCity, city.replace("市", ""));
        }
    }

    private void applyStyleFilter(LambdaQueryWrapper<Event> wrapper, String style) {
        if (StringUtils.hasText(style) && !"全部".equals(style)) {
            wrapper.eq(Event::getStyle, style);
        }
    }

    private void applyKeywordFilter(LambdaQueryWrapper<Event> wrapper, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return;
        }

        wrapper.and(w -> w
                .like(Event::getTitle, keyword)
                .or()
                .apply(
                        "EXISTS (" +
                                "SELECT 1 FROM tb_event_artist ea " +
                                "INNER JOIN tb_artist a ON ea.artist_id = a.id " +
                                "WHERE ea.event_id = tb_event.id " +
                                "AND a.name LIKE CONCAT('%', {0}, '%')" +
                                ")",
                        keyword
                )
        );
    }

    private void applyTimeFilter(
            LambdaQueryWrapper<Event> wrapper,
            Integer timeType,
            String startDate,
            String endDate
    ) {
        LocalDateTime now = LocalDateTime.now();

        if (timeType != null && timeType != 0) {
            LocalDateTime start = null;
            LocalDateTime end = null;

            switch (timeType) {
                case 1:
                    start = now.with(LocalTime.MIN);
                    end = now.with(LocalTime.MAX);
                    break;
                case 2:
                    start = now;
                    end = now.plusWeeks(1);
                    break;
                case 3:
                    start = now.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(LocalTime.MIN);
                    end = start.plusDays(6).with(LocalTime.MAX);
                    break;
                case 4:
                    start = now;
                    end = now.plusMonths(1);
                    break;
                default:
                    break;
            }

            if (start != null && end != null) {
                applySessionTimeExists(wrapper, start, end);
            }
            return;
        }

        if (StringUtils.hasText(startDate) && StringUtils.hasText(endDate)) {
            LocalDateTime start = LocalDate.parse(startDate).atStartOfDay();
            LocalDateTime end = LocalDate.parse(endDate).atTime(LocalTime.MAX);
            applySessionTimeExists(wrapper, start, end);
        }
    }

    private void applySessionTimeExists(LambdaQueryWrapper<Event> wrapper, LocalDateTime start, LocalDateTime end) {
        wrapper.apply(
                "EXISTS (" +
                        "SELECT 1 FROM tb_event_session s " +
                        "WHERE s.event_id = tb_event.id " +
                        "AND s.status <> {0} " +
                        "AND s.show_time >= {1} " +
                        "AND s.show_time <= {2}" +
                        ")",
                SESSION_STATUS_HIDDEN,
                start,
                end
        );
    }
}
