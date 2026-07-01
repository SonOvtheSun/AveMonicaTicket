package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.entity.Event;
import com.avemonica.ticket.entity.UserBehavior;
import com.avemonica.ticket.entity.UserRecommendProfile;
import com.avemonica.ticket.mapper.EventMapper;
import com.avemonica.ticket.mapper.RecommendQueryMapper;
import com.avemonica.ticket.mapper.UserRecommendProfileMapper;
import com.avemonica.ticket.service.RecommendBehaviorService;
import com.avemonica.ticket.service.RecommendProfileService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RecommendProfileServiceImpl implements RecommendProfileService {

    /**
     * 用户画像只基于最近 200 条行为。
     */
    private static final int MAX_BEHAVIOR_COUNT = 200;

    /**
     * 控制 JSON 长度：最多保留 20 个风格偏好。
     */
    private static final int MAX_STYLE_PROFILE_SIZE = 20;

    /**
     * 控制 JSON 长度：最多保留 20 个城市偏好。
     */
    private static final int MAX_CITY_PROFILE_SIZE = 20;

    /**
     * 控制 JSON 长度：最多保留 50 个艺人偏好。
     */
    private static final int MAX_ARTIST_PROFILE_SIZE = 50;

    @Autowired
    private RecommendBehaviorService recommendBehaviorService;

    @Autowired
    private UserRecommendProfileMapper userRecommendProfileMapper;

    @Autowired
    private EventMapper eventMapper;

    @Autowired
    private RecommendQueryMapper recommendQueryMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void refreshUserProfile(Long userId) {
        if (userId == null) {
            return;
        }

        List<UserBehavior> behaviors = recommendBehaviorService.listRecentUserBehaviors(userId, MAX_BEHAVIOR_COUNT);
        if (behaviors == null || behaviors.isEmpty()) {
            return;
        }

        List<Long> eventIds = behaviors.stream()
                .map(UserBehavior::getEventId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (eventIds.isEmpty()) {
            return;
        }

        Map<Long, Event> eventMap = loadEventMap(eventIds);
        Map<Long, List<Long>> eventArtistMap = loadEventArtistMap(eventIds);
        Map<Long, BigDecimal> eventMinPriceMap = loadEventMinPriceMap(eventIds);

        Map<String, Integer> styleScoreMap = new HashMap<>();
        Map<String, Integer> cityScoreMap = new HashMap<>();
        Map<String, Integer> artistScoreMap = new HashMap<>();
        List<BigDecimal> priceSamples = new ArrayList<>();

        for (UserBehavior behavior : behaviors) {
            if (behavior == null || behavior.getEventId() == null) {
                continue;
            }

            Event event = eventMap.get(behavior.getEventId());
            if (event == null) {
                continue;
            }

            int weight = behavior.getBehaviorWeight() == null ? 0 : behavior.getBehaviorWeight();

            // 退款成功、取消想看是负反馈，允许降低偏好权重。
            if (StringUtils.hasText(event.getStyle())) {
                addScore(styleScoreMap, event.getStyle(), weight);
            }

            if (StringUtils.hasText(event.getCity())) {
                addScore(cityScoreMap, event.getCity(), weight);
            }

            List<Long> artistIds = eventArtistMap.getOrDefault(behavior.getEventId(), Collections.emptyList());
            for (Long artistId : artistIds) {
                if (artistId != null) {
                    addScore(artistScoreMap, String.valueOf(artistId), weight);
                }
            }

            BigDecimal minPrice = eventMinPriceMap.get(behavior.getEventId());
            if (minPrice != null && minPrice.compareTo(BigDecimal.ZERO) > 0 && weight > 0) {
                priceSamples.add(minPrice);
            }
        }

        Map<String, Integer> styleProfile = topPositiveScoreMap(styleScoreMap, MAX_STYLE_PROFILE_SIZE);
        Map<String, Integer> cityProfile = topPositiveScoreMap(cityScoreMap, MAX_CITY_PROFILE_SIZE);
        Map<String, Integer> artistProfile = topPositiveScoreMap(artistScoreMap, MAX_ARTIST_PROFILE_SIZE);

        UserRecommendProfile profile = new UserRecommendProfile();
        profile.setUserId(userId);
        profile.setStyleProfile(toJson(styleProfile));
        profile.setCityProfile(toJson(cityProfile));
        profile.setArtistProfile(toJson(artistProfile));
        profile.setPriceMin(resolvePriceMin(priceSamples));
        profile.setPriceMax(resolvePriceMax(priceSamples));
        profile.setUpdateTime(LocalDateTime.now());

        upsertProfile(profile);
    }

    @Override
    public UserRecommendProfile getUserProfile(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRecommendProfileMapper.selectById(userId);
    }

    private Map<Long, Event> loadEventMap(List<Long> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Event> events = eventMapper.selectList(
                new LambdaQueryWrapper<Event>()
                        .select(
                                Event::getId,
                                Event::getStyle,
                                Event::getCity
                        )
                        .in(Event::getId, eventIds)
        );

        return events.stream()
                .filter(event -> event.getId() != null)
                .collect(Collectors.toMap(Event::getId, event -> event, (a, b) -> a));
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

    private Map<Long, BigDecimal> loadEventMinPriceMap(List<Long> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Map<String, Object>> rows = recommendQueryMapper.selectEventMinPrices(eventIds);
        Map<Long, BigDecimal> result = new HashMap<>();

        for (Map<String, Object> row : rows) {
            Long eventId = parseLong(row.get("eventId"));
            BigDecimal minPrice = parseBigDecimal(row.get("minPrice"));

            if (eventId != null && minPrice != null) {
                result.put(eventId, minPrice);
            }
        }

        return result;
    }

    private void addScore(Map<String, Integer> map, String key, int delta) {
        if (!StringUtils.hasText(key) || delta == 0) {
            return;
        }

        String normalizedKey = key.trim();
        map.put(normalizedKey, map.getOrDefault(normalizedKey, 0) + delta);
    }

    /**
     * 只保留正向偏好，并限制数量，防止 JSON 过长。
     */
    private Map<String, Integer> topPositiveScoreMap(Map<String, Integer> source, int limit) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }

        return source.entrySet()
                .stream()
                .filter(entry -> StringUtils.hasText(entry.getKey()))
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    private BigDecimal resolvePriceMin(List<BigDecimal> prices) {
        if (prices == null || prices.isEmpty()) {
            return null;
        }

        return prices.stream()
                .filter(Objects::nonNull)
                .min(BigDecimal::compareTo)
                .orElse(null);
    }

    private BigDecimal resolvePriceMax(List<BigDecimal> prices) {
        if (prices == null || prices.isEmpty()) {
            return null;
        }

        return prices.stream()
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(null);
    }

    private String toJson(Map<String, Integer> map) {
        try {
            if (map == null || map.isEmpty()) {
                return "{}";
            }
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("推荐画像 JSON 序列化失败", e);
            return "{}";
        }
    }

    private void upsertProfile(UserRecommendProfile profile) {
        if (profile == null || profile.getUserId() == null) {
            return;
        }

        UserRecommendProfile exists = userRecommendProfileMapper.selectById(profile.getUserId());
        if (exists == null) {
            userRecommendProfileMapper.insert(profile);
        } else {
            userRecommendProfileMapper.updateById(profile);
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

    private BigDecimal parseBigDecimal(Object value) {
        if (value == null) {
            return null;
        }

        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }
}