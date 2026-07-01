package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.entity.*;
import com.avemonica.ticket.mapper.*;
import com.avemonica.ticket.service.RecommendService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RecommendServiceImpl implements RecommendService {

    private static final int EVENT_STATUS_ONLINE = 1;
    private static final int EVENT_STATUS_HIDDEN = 4;
    private static final int SESSION_STATUS_HIDDEN = 4;

    private static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 20;

    @Autowired
    private EventMapper eventMapper;

    @Autowired
    private EventSessionMapper eventSessionMapper;

    @Autowired
    private TicketCategoryMapper ticketCategoryMapper;

    @Autowired
    private UserRecommendProfileMapper userRecommendProfileMapper;

    @Autowired
    private RecommendQueryMapper recommendQueryMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRecommendEventMapper userRecommendEventMapper;

    private static final String SCENE_HOME = "home";

    /**
     * 每个用户预计算首页推荐保留数量。
     * 首页实际展示 10 个，但结果表多存一些，方便城市筛选和后续扩展。
     */
    private static final int STORE_RECOMMEND_SIZE = 50;

    @Override
    public List<Event> recommendHomeEvents(Long userId, String city, Integer size) {
        int safeSize = normalizeSize(size);

        // 1. 登录用户优先读推荐结果表
        if (userId != null) {
            List<Event> storedEvents = loadStoredHomeRecommendEvents(userId, city, safeSize);

            if (storedEvents.size() >= safeSize) {
                return storedEvents;
            }

            // 预计算结果不足时，用实时兜底补齐
            List<Long> excludeIds = storedEvents.stream()
                    .map(Event::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            List<Event> fallbackEvents = calculateHomeRecommendEvents(
                    userId,
                    city,
                    safeSize - storedEvents.size(),
                    excludeIds
            );

            List<Event> merged = new ArrayList<>();
            merged.addAll(storedEvents);
            merged.addAll(fallbackEvents);

            return merged.stream()
                    .limit(safeSize)
                    .collect(Collectors.toList());
        }

        // 2. 未登录用户走冷启动实时推荐
        return calculateHomeRecommendEvents(null, city, safeSize, Collections.emptyList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refreshUserHomeRecommendEvents(Long userId, Integer size) {
        if (userId == null) {
            return;
        }

        int storeSize = size == null || size <= 0 ? STORE_RECOMMEND_SIZE : Math.min(size, STORE_RECOMMEND_SIZE);

        List<ScoredEvent> scoredEvents = calculateHomeScoredEvents(
                userId,
                null,
                storeSize,
                Collections.emptyList()
        );

        // 没有候选结果时，不删除旧结果，避免用户首页突然空白。
        if (scoredEvents == null || scoredEvents.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        // 先删旧结果，再写新结果。整个方法有事务保护。
        userRecommendEventMapper.delete(
                new LambdaQueryWrapper<UserRecommendEvent>()
                        .eq(UserRecommendEvent::getUserId, userId)
                        .eq(UserRecommendEvent::getScene, SCENE_HOME)
        );

        for (ScoredEvent item : scoredEvents.stream()
                .sorted(Comparator.comparing(ScoredEvent::getScore).reversed())
                .limit(storeSize)
                .collect(Collectors.toList())) {

            if (item == null || item.getEvent() == null || item.getEvent().getId() == null) {
                continue;
            }

            UserRecommendEvent record = new UserRecommendEvent();
            record.setUserId(userId);
            record.setEventId(item.getEvent().getId());
            record.setScore(BigDecimal.valueOf(item.getScore()).setScale(4, RoundingMode.HALF_UP));
            record.setReason(buildRecommendReason(item.getEvent()));
            record.setScene(SCENE_HOME);
            record.setCreateTime(now);
            record.setUpdateTime(now);

            userRecommendEventMapper.insert(record);
        }
    }

    private int normalizeSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    private String buildRecommendReason(Event event) {
        if (event == null) {
            return "根据你的近期偏好推荐";
        }

        if (StringUtils.hasText(event.getStyle())) {
            return "因为你最近关注了相关风格的演出";
        }

        if (StringUtils.hasText(event.getCity())) {
            return "结合你的城市偏好推荐";
        }

        return "近期热度较高";
    }

    /**
     * 候选集只取：
     * 1. event.status = 1；
     * 2. 至少存在未来非隐藏场次；
     * 3. 如果选择了城市，则按城市过滤。
     */
    private List<Event> loadCandidateEvents(String city, int limit, List<Long> excludeIds) {
        LocalDateTime now = LocalDateTime.now();

        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Event::getStatus, EVENT_STATUS_ONLINE)
                .apply(
                        "EXISTS (" +
                                "SELECT 1 FROM tb_event_session s " +
                                "WHERE s.event_id = tb_event.id " +
                                "AND s.status <> {0} " +
                                "AND s.show_time IS NOT NULL " +
                                "AND s.show_time > {1}" +
                                ")",
                        SESSION_STATUS_HIDDEN,
                        now
                );

        if (excludeIds != null && !excludeIds.isEmpty()) {
            wrapper.notIn(Event::getId, excludeIds);
        }

        if (StringUtils.hasText(city) && !"全国".equals(city) && !"全部".equals(city)) {
            wrapper.like(Event::getCity, city.replace("市", ""));
        }

        wrapper.last(
                "ORDER BY " +
                        "show_time IS NULL ASC, " +
                        "CASE WHEN show_time >= NOW() THEN 0 ELSE 1 END, " +
                        "ABS(TIMESTAMPDIFF(SECOND, show_time, NOW())) ASC " +
                        "LIMIT " + limit
        );

        return eventMapper.selectList(wrapper);
    }

    private List<Event> calculateHomeRecommendEvents(Long userId, String city, int size, List<Long> excludeIds) {
        List<ScoredEvent> scoredEvents = calculateHomeScoredEvents(userId, city, size, excludeIds);

        List<Event> result = scoredEvents.stream()
                .sorted(Comparator.comparing(ScoredEvent::getScore).reversed())
                .limit(size)
                .map(ScoredEvent::getEvent)
                .collect(Collectors.toList());

        attachSessionsAndTickets(result);
        return result;
    }

    private List<ScoredEvent> calculateHomeScoredEvents(Long userId, String city, int size, List<Long> excludeIds) {
        int safeSize = normalizeSize(size);
        int candidateSize = Math.max(80, safeSize * 8);

        List<Event> candidates = loadCandidateEvents(city, candidateSize, excludeIds);
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        UserRecommendProfile profile = userId == null ? null : userRecommendProfileMapper.selectById(userId);

        if (profile == null) {
            return scoreByColdStart(candidates, city);
        }

        return scoreByProfile(candidates, profile, city);
    }

    private List<ScoredEvent> scoreByColdStart(List<Event> candidates, String city) {
        List<ScoredEvent> result = new ArrayList<>();

        for (Event event : candidates) {
            double score = 0D;

            if (StringUtils.hasText(city)
                    && !"全国".equals(city)
                    && event.getCity() != null
                    && event.getCity().contains(city.replace("市", ""))) {
                score += 20;
            }

            score += normalizePopularityScore(event);
            score += normalizeTimeScore(event.getShowTime());

            ScoredEvent item = new ScoredEvent();
            item.setEvent(event);
            item.setScore(score);
            result.add(item);
        }

        return result;
    }

    private List<ScoredEvent> scoreByProfile(List<Event> candidates, UserRecommendProfile profile, String city) {
        Map<String, Integer> styleProfile = parseProfileMap(profile.getStyleProfile());
        Map<String, Integer> cityProfile = parseProfileMap(profile.getCityProfile());
        Map<String, Integer> artistProfile = parseProfileMap(profile.getArtistProfile());

        List<Long> eventIds = candidates.stream()
                .map(Event::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        Map<Long, List<Long>> eventArtistMap = loadEventArtistMap(eventIds);

        List<ScoredEvent> result = new ArrayList<>();

        for (Event event : candidates) {
            double score = 0D;

            score += matchStyleScore(event, styleProfile);
            score += matchCityScore(event, cityProfile, city);
            score += matchArtistScore(event, eventArtistMap, artistProfile);
            score += normalizePopularityScore(event);
            score += normalizeTimeScore(event.getShowTime());

            // 如果用户画像完全没命中，也给一点冷启动分，避免列表为空或排序过于随机。
            if (score <= 0) {
                score += normalizePopularityScore(event) + normalizeTimeScore(event.getShowTime());
            }

            ScoredEvent item = new ScoredEvent();
            item.setEvent(event);
            item.setScore(score);
            result.add(item);
        }

        return result;
    }

    private double matchStyleScore(Event event, Map<String, Integer> styleProfile) {
        if (event == null || !StringUtils.hasText(event.getStyle()) || styleProfile.isEmpty()) {
            return 0D;
        }

        double score = 0D;
        List<String> styles = Arrays.stream(event.getStyle().split("/"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());

        for (String style : styles) {
            Integer weight = styleProfile.get(style);
            if (weight != null && weight > 0) {
                score += 30 + Math.min(20, weight * 0.25);
            }
        }

        return score;
    }

    private double matchCityScore(Event event, Map<String, Integer> cityProfile, String currentCity) {
        if (event == null || !StringUtils.hasText(event.getCity())) {
            return 0D;
        }

        double score = 0D;

        Integer profileWeight = cityProfile.get(event.getCity());
        if (profileWeight != null && profileWeight > 0) {
            score += 20 + Math.min(15, profileWeight * 0.2);
        }

        if (StringUtils.hasText(currentCity)
                && !"全国".equals(currentCity)
                && event.getCity().contains(currentCity.replace("市", ""))) {
            score += 10;
        }

        return score;
    }

    private double matchArtistScore(
            Event event,
            Map<Long, List<Long>> eventArtistMap,
            Map<String, Integer> artistProfile
    ) {
        if (event == null || event.getId() == null || artistProfile.isEmpty()) {
            return 0D;
        }

        List<Long> artistIds = eventArtistMap.getOrDefault(event.getId(), Collections.emptyList());
        double score = 0D;

        for (Long artistId : artistIds) {
            Integer weight = artistProfile.get(String.valueOf(artistId));
            if (weight != null && weight > 0) {
                score += 40 + Math.min(25, weight * 0.25);
            }
        }

        return score;
    }

    private double normalizePopularityScore(Event event) {
        if (event == null) {
            return 0D;
        }

        int wantCount = event.getWantCount() == null ? 0 : event.getWantCount();
        int pageViews = event.getPageViews() == null ? 0 : event.getPageViews();

        double score = wantCount * 0.6 + pageViews * 0.05;
        return Math.min(score, 30D);
    }

    private double normalizeTimeScore(LocalDateTime showTime) {
        if (showTime == null) {
            return 0D;
        }

        LocalDateTime now = LocalDateTime.now();
        if (!showTime.isAfter(now)) {
            return 0D;
        }

        long days = Duration.between(now, showTime).toDays();

        if (days <= 3) return 18D;
        if (days <= 7) return 15D;
        if (days <= 30) return 10D;
        if (days <= 90) return 5D;
        return 2D;
    }

    private Map<Long, List<Long>> loadEventArtistMap(List<Long> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Map<String, Object>> rows = recommendQueryMapper.selectEventArtistPairs(eventIds);
        Map<Long, List<Long>> result = new HashMap<>();

        for (Map<String, Object> row : rows) {
            Long eventId = parseLong(row.get("eventId"));
            Long artistId = parseLong(row.get("artistId"));

            if (eventId == null || artistId == null) {
                continue;
            }

            result.computeIfAbsent(eventId, k -> new ArrayList<>()).add(artistId);
        }

        return result;
    }

    /**
     * 首页卡片兼容：
     * 1. event.sessions[*].tickets；
     * 2. event.tickets 根级兜底，避免旧 Home 卡片取不到最低票价。
     */
    private void attachSessionsAndTickets(List<Event> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        List<Long> eventIds = events.stream()
                .map(Event::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (eventIds.isEmpty()) {
            return;
        }

        List<EventSession> sessions = eventSessionMapper.selectList(
                new LambdaQueryWrapper<EventSession>()
                        .in(EventSession::getEventId, eventIds)
                        .ne(EventSession::getStatus, SESSION_STATUS_HIDDEN)
                        .orderByAsc(EventSession::getSortOrder)
                        .orderByAsc(EventSession::getShowTime)
        );

        List<Long> sessionIds = sessions.stream()
                .map(EventSession::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        List<TicketCategory> tickets = sessionIds.isEmpty()
                ? Collections.emptyList()
                : ticketCategoryMapper.selectList(
                new LambdaQueryWrapper<TicketCategory>()
                .in(TicketCategory::getSessionId, sessionIds)
        );

        Map<Long, List<EventSession>> sessionMap = sessions.stream()
                .collect(Collectors.groupingBy(EventSession::getEventId));

        Map<Long, List<TicketCategory>> ticketBySessionMap = tickets.stream()
                .collect(Collectors.groupingBy(TicketCategory::getSessionId));

        Map<Long, List<TicketCategory>> ticketByEventMap = tickets.stream()
                .collect(Collectors.groupingBy(TicketCategory::getEventId));

        for (Event event : events) {
            List<EventSession> eventSessions = sessionMap.getOrDefault(event.getId(), Collections.emptyList());

            for (EventSession session : eventSessions) {
                session.setTickets(ticketBySessionMap.getOrDefault(session.getId(), Collections.emptyList()));
            }

            event.setSessions(eventSessions);
            event.setTickets(ticketByEventMap.getOrDefault(event.getId(), Collections.emptyList()));
        }
    }

    private List<Event> loadStoredHomeRecommendEvents(Long userId, String city, int size) {
        if (userId == null || size <= 0) {
            return Collections.emptyList();
        }

        List<UserRecommendEvent> records = userRecommendEventMapper.selectList(
                new LambdaQueryWrapper<UserRecommendEvent>()
                        .eq(UserRecommendEvent::getUserId, userId)
                        .eq(UserRecommendEvent::getScene, SCENE_HOME)
                        .orderByDesc(UserRecommendEvent::getScore)
                        .last("LIMIT " + Math.max(size * 3, size))
        );

        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> eventIds = records.stream()
                .map(UserRecommendEvent::getEventId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (eventIds.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDateTime now = LocalDateTime.now();

        LambdaQueryWrapper<Event> wrapper = new LambdaQueryWrapper<Event>()
                .in(Event::getId, eventIds)
                .eq(Event::getStatus, EVENT_STATUS_ONLINE)
                .apply(
                        "EXISTS (" +
                                "SELECT 1 FROM tb_event_session s " +
                                "WHERE s.event_id = tb_event.id " +
                                "AND s.status <> {0} " +
                                "AND s.show_time IS NOT NULL " +
                                "AND s.show_time > {1}" +
                                ")",
                        SESSION_STATUS_HIDDEN,
                        now
                );

        if (StringUtils.hasText(city) && !"全国".equals(city) && !"全部".equals(city)) {
            wrapper.like(Event::getCity, city.replace("市", ""));
        }

        List<Event> events = eventMapper.selectList(wrapper);
        if (events == null || events.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, Event> eventMap = events.stream()
                .filter(event -> event.getId() != null)
                .collect(Collectors.toMap(Event::getId, event -> event, (a, b) -> a));

        List<Event> orderedEvents = new ArrayList<>();
        for (Long eventId : eventIds) {
            Event event = eventMap.get(eventId);
            if (event != null) {
                orderedEvents.add(event);
            }

            if (orderedEvents.size() >= size) {
                break;
            }
        }

        attachSessionsAndTickets(orderedEvents);
        return orderedEvents;
    }

    private Map<String, Integer> parseProfileMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }

        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Integer>>() {});
        } catch (Exception e) {
            log.warn("解析用户推荐画像失败，json={}", json, e);
            return Collections.emptyMap();
        }
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }

        try {
            return Long.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    @Data
    private static class ScoredEvent {
        private Event event;
        private Double score;
    }
}