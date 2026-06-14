package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.entity.Banner;
import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.entity.EventSession;
import com.avemonica.ticket.entity.TicketCategory;
import com.avemonica.ticket.mapper.EventSessionMapper;
import com.avemonica.ticket.service.EventService;
import com.avemonica.ticket.service.TicketService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
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
    private com.avemonica.ticket.service.BannerService bannerService;

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
    public Result<List<Event>> getUpcomingEvents(@RequestParam(required = false, defaultValue = "全国") String city) {
        // 首页列表由于带了 RAND() 随机推荐，通常可以只做 Redis 级别的缓存，或者对热门列表做定时预热。
        // 为了演示核心机制，我们将重点放在下方的【演出详情】接口
        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Event::getStatus, 1)
                .gt(Event::getShowTime, LocalDateTime.now())
                .last("ORDER BY RAND() LIMIT 10");

        // 🚨 核心新增：根据前端传来的城市在数据库层面进行精准检索
        if (StringUtils.hasText(city) && !"全国".equals(city)) {
            // 使用 like 进行模糊匹配，兼容前端传 "北京" 数据库存 "北京市" 的情况
            wrapper.like(Event::getCity, city.replace("市", ""));
        }

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
    @GetMapping("/detail/{id}")
    public Result<Event> getEventDetail(
            @PathVariable Long id,
            @RequestParam(required = false) String viewToken
    ) {
        String cacheKey = EVENT_CACHE_KEY_PREFIX + id;
        Event event = null;

        try {
            // ==========================================
            // L1: 查找本地缓存 (Caffeine)
            // ==========================================
            String localJson = localCache.getIfPresent(cacheKey);
            if (StringUtils.hasText(localJson)) {
                log.info("🎯 命中 L1 本地缓存, EventID: {}", id);
                event = objectMapper.readValue(localJson, Event.class);
            }

            // ==========================================
            // L2: 查找分布式缓存 (Redis)
            // ==========================================
            if (event == null) {
                String redisJson = redisTemplate.opsForValue().get(cacheKey);
                if (StringUtils.hasText(redisJson)) {
                    log.info("🎯 命中 L2 Redis 缓存, EventID: {}", id);
                    localCache.put(cacheKey, redisJson); // 回填 L1
                    event = objectMapper.readValue(redisJson, Event.class);
                }
            }

            // ==========================================
            // L3: 缓存全未命中，查询数据库 (MySQL)
            // ==========================================
            if (event == null) {
                log.warn("⚠️ 缓存击穿，查询 MySQL, EventID: {}", id);
                event = eventService.getById(id);
                if (event == null || event.getStatus() == 4) {
                    return Result.error("该演出不存在或已下架");
                }

                // 查票档和艺人信息
                attachSessionsAndTickets(event);

                List<java.util.Map<String, Object>> artists = artistMapper.selectArtistMapsByEventId(id);
                event.setArtists(artists);

                // 回填缓存
                String finalJson = objectMapper.writeValueAsString(event);
                redisTemplate.opsForValue().set(cacheKey, finalJson, 30, TimeUnit.MINUTES);
                localCache.put(cacheKey, finalJson);
            }

            // ==========================================
            // 缓存外挂：合集归属字段需要保持实时，避免合集调整后详情页仍读到旧缓存
            // ==========================================
            Event collectionInfo = eventService.getOne(
                    new LambdaQueryWrapper<Event>()
                            .select(Event::getId, Event::getCollectionId, Event::getCollectionAlias)
                            .eq(Event::getId, id),
                    false
            );
            if (collectionInfo != null) {
                event.setCollectionId(collectionInfo.getCollectionId());
                event.setCollectionAlias(collectionInfo.getCollectionAlias());
            }

            // ==========================================
            // 🚨 终极绝招：缓存外挂！动态注入实时的浏览量与想看数据
            // ==========================================
            String viewsKey = "event:views:" + id;
            String wantKey = "event:want:" + id;

// 1. 浏览量实时统计：同一次页面加载只统计一次
            Long currentViews;

            if (StringUtils.hasText(viewToken)) {
                String viewDedupKey = "event:view:dedup:" + id + ":" + viewToken;

                Boolean firstView = redisTemplate.opsForValue()
                        .setIfAbsent(viewDedupKey, "1", 10, TimeUnit.MINUTES);

                if (Boolean.TRUE.equals(firstView)) {
                    currentViews = redisTemplate.opsForValue().increment(viewsKey);
                } else {
                    String viewText = redisTemplate.opsForValue().get(viewsKey);
                    currentViews = StringUtils.hasText(viewText) ? Long.valueOf(viewText) : 0L;
                }
            } else {
                // 兼容旧请求：没有 viewToken 时仍然统计
                currentViews = redisTemplate.opsForValue().increment(viewsKey);
            }

            event.setPageViews((event.getPageViews() != null ? event.getPageViews() : 0) + currentViews.intValue());
            // 2. 拉取实时想看总数
            Long wantCount = redisTemplate.opsForSet().size(wantKey);
            if (wantCount != null && wantCount > 0) {
                event.setWantCount(wantCount.intValue());
            }

            // 3. 判断当前登录用户是否已点过“想看”
            event.setHasWanted(false);
            try {
                String userIdStr = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
                if (StringUtils.hasText(userIdStr) && !"anonymousUser".equals(userIdStr)) {
                    Boolean isMember = redisTemplate.opsForSet().isMember(wantKey, userIdStr);
                    event.setHasWanted(Boolean.TRUE.equals(isMember));
                }
            } catch (Exception e) {
                // 游客访问，不抛异常，hasWanted 保持为 false
            }

            return Result.success(event);

        } catch (Exception e) {
            log.error("获取演出详情出现异常: {}", e.getMessage());
            return Result.error("系统繁忙，请稍后再试");
        }
    }

    /**
     * 获取同一合集下的所有非隐藏演出，供演出详情页做场次切换。
     * 注意：这里不复用 /detail/{id}，避免切换列表增加浏览量。
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
                                Event::getSaleTime,
                                Event::getStatus
                        )
                        .eq(Event::getCollectionId, collectionId)
                        .ne(Event::getStatus, 4)
                        .orderByAsc(Event::getShowTime)
        );

        return Result.success(events);
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

    @GetMapping("/session/stock/{sessionId}")
    public Result<Map<Long, Integer>> getSessionStock(@PathVariable Long sessionId) {
        List<TicketCategory> tickets = ticketService.list(
                new LambdaQueryWrapper<TicketCategory>()
                        .select(TicketCategory::getId, TicketCategory::getRemainingStock)
                        .eq(TicketCategory::getSessionId, sessionId)
        );

        Map<Long, Integer> stockMap = tickets.stream()
                .collect(Collectors.toMap(
                        TicketCategory::getId,
                        TicketCategory::getRemainingStock
                ));

        return Result.success(stockMap);
    }

    // 🚨 在 PublicEventController.java 的末尾追加这个方法
    @GetMapping("/banner/active")
    public Result<List<Banner>> getActiveBanners() {
        LocalDateTime now = LocalDateTime.now();

        // 惰性过滤：由于有定时器兜底，主表数据量极小。
        // 查询条件：开始时间 <= 当前时间，且结束时间 >= 当前时间
        List<Banner> activeBanners = bannerService.list(
                new LambdaQueryWrapper<Banner>()
                        .eq(Banner::getAuditStatus, 1)
                        .le(Banner::getStartTime, now)
                        .ge(Banner::getEndTime, now)
                        .orderByDesc(Banner::getCreateTime)
        );

        return Result.success(activeBanners);
    }

    @GetMapping("/page")
    public Result<Map<String, Object>> pageEvents(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String style,
            @RequestParam(required = false) Integer timeType, // 1:今天, 2:最近一周, 3:下周, 4:最近一月
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long artistId
    ) {
        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<>();
        // C 端不展示隐藏状态；音乐人主页需要包含已结束演出，因此不能只查未来演出。
        wrapper.ne(Event::getStatus, 4);

        if (artistId != null) {
            // 跨表查询：找出 tb_event_artist 表中包含该 artistId 的演出
            wrapper.inSql(Event::getId, "SELECT event_id FROM tb_event_artist WHERE artist_id = " + artistId);
        }

        // 1. 城市筛选
        if (StringUtils.hasText(city) && !"全部".equals(city) && !"全国".equals(city)) {
            wrapper.like(Event::getCity, city.replace("市", ""));
        }

        // 2. 风格筛选
        if (StringUtils.hasText(style) && !"全部".equals(style)) {
            wrapper.eq(Event::getStyle, style);
        }

        // 3. 关键词模糊搜索：支持按演出名称、艺人名称搜索演出
        if (StringUtils.hasText(keyword)) {
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

        // 4. 时间范围筛选逻辑
        LocalDateTime now = LocalDateTime.now();
        if (timeType != null && timeType != 0) {
            switch (timeType) {
                case 1: // 今天
                    wrapper.ge(Event::getShowTime, now.with(LocalTime.MIN))
                            .le(Event::getShowTime, now.with(LocalTime.MAX));
                    break;
                case 2: // 最近一周内
                    wrapper.ge(Event::getShowTime, now)
                            .le(Event::getShowTime, now.plusWeeks(1));
                    break;
                case 3: // 下周内
                    LocalDateTime nextMonday = now.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(LocalTime.MIN);
                    LocalDateTime nextSunday = nextMonday.plusDays(6).with(LocalTime.MAX);
                    wrapper.ge(Event::getShowTime, nextMonday).le(Event::getShowTime, nextSunday);
                    break;
                case 4: // 最近一个月
                    wrapper.ge(Event::getShowTime, now)
                            .le(Event::getShowTime, now.plusMonths(1));
                    break;
            }
        } else if (StringUtils.hasText(startDate) && StringUtils.hasText(endDate)) {
            // 自定义日期选择器
            wrapper.ge(Event::getShowTime, startDate + " 00:00:00")
                    .le(Event::getShowTime, endDate + " 23:59:59");
        }

        // 默认“全部”不再过滤已结束演出：C 端演出页和音乐人主页都展示全部非隐藏演出。
        // 排序规则：未开始演出优先；同组内按与当前时间的接近程度排序。
        // 即：最近即将开始的演出在最前，其次才是最近刚结束的演出。
        wrapper.last("ORDER BY CASE WHEN show_time >= NOW() THEN 0 ELSE 1 END, ABS(TIMESTAMPDIFF(SECOND, show_time, NOW())) ASC");

        IPage<Event> pageData = eventService.page(new Page<>(current, size), wrapper);

        // 5. 装配票档 (用于前端计算“起步价”)
        for (Event event : pageData.getRecords()) {
            List<TicketCategory> tickets = ticketService.list(
                    new LambdaQueryWrapper<TicketCategory>().eq(TicketCategory::getEventId, event.getId())
            );
            event.setTickets(tickets);

            // 顺便装配一下艺人信息 (用于显示)
            List<java.util.Map<String, Object>> artists = artistMapper.selectArtistMapsByEventId(event.getId());
            event.setArtists(artists);
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
     * 切换“想看”状态接口
     */
    @PostMapping("/want/{id}")
    public Result<Boolean> toggleWant(@PathVariable Long id) {
        try {
            // 必须登录才能点想看
            String userIdStr = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName();
            if (!StringUtils.hasText(userIdStr) || "anonymousUser".equals(userIdStr)) {
                Result<Boolean> res = Result.error("请先登录");
                res.setCode(401);
                return res;
            }

            String wantKey = "event:want:" + id;

            // 判断当前用户是否在 Set 中
            Boolean isMember = redisTemplate.opsForSet().isMember(wantKey, userIdStr);

            if (Boolean.TRUE.equals(isMember)) {
                // 如果已经在里面，说明是取消想看 -> 从 Set 中移除
                redisTemplate.opsForSet().remove(wantKey, userIdStr);
                return Result.success("操作成功", false); // 返回 false 表示当前未想看
            } else {
                // 如果不在里面，说明是点击想看 -> 加入 Set
                redisTemplate.opsForSet().add(wantKey, userIdStr);
                return Result.success("操作成功", true); // 返回 true 表示当前已想看
            }
        } catch (Exception e) {
            log.error("切换想看状态异常", e);
            return Result.error("系统异常");
        }
    }

    private void attachSessionsAndTickets(Event event) {
        if (event == null || event.getId() == null) {
            return;
        }

        Long eventId = event.getId();

        List<EventSession> sessions = eventSessionMapper.selectList(
                new LambdaQueryWrapper<EventSession>()
                        .eq(EventSession::getEventId, eventId)
                        .ne(EventSession::getStatus, 4)
                        .orderByAsc(EventSession::getSortOrder)
                        .orderByAsc(EventSession::getShowTime)
        );

        List<TicketCategory> tickets = ticketService.list(
                new LambdaQueryWrapper<TicketCategory>()
                        .eq(TicketCategory::getEventId, eventId)
        );

        Map<Long, List<TicketCategory>> ticketMapBySessionId = tickets.stream()
                .filter(t -> t.getSessionId() != null)
                .collect(Collectors.groupingBy(TicketCategory::getSessionId));

        for (EventSession session : sessions) {
            session.setTickets(ticketMapBySessionId.getOrDefault(session.getId(), new ArrayList<>()));
        }

        event.setSessions(sessions);

        // 兼容旧前端：event.tickets 仍然返回全部票档
        event.setTickets(tickets);
    }

}