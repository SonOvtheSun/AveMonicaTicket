package com.avemonica.ticket.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

import static java.lang.Double.parseDouble;
import static java.lang.Long.parseLong;

@Component
public class QdrantClient {

    @Value("${ai.vector.qdrant-url:http://localhost:6333}")
    private String qdrantUrl;

    @Value("${ai.vector.collection:event_rag}")
    private String collection;

    private final RestTemplate restTemplate = new RestTemplate();

    public static class SearchResult {
        private Long eventId;
        private Double score;
        private Map<String, Object> payload;

        public Long getEventId() {
            return eventId;
        }

        public void setEventId(Long eventId) {
            this.eventId = eventId;
        }

        public Double getScore() {
            return score;
        }

        public void setScore(Double score) {
            this.score = score;
        }

        public Map<String, Object> getPayload() {
            return payload;
        }

        public void setPayload(Map<String, Object> payload) {
            this.payload = payload;
        }
    }

    public List<SearchResult> searchEventVectors(List<Double> queryVector, String city, int limit) {
        if (queryVector == null || queryVector.isEmpty()) {
            throw new RuntimeException("Qdrant queryVector 不能为空");
        }

        int safeLimit = limit <= 0 ? 10 : Math.min(limit, 50);

        Map<String, Object> body = new HashMap<>();
        body.put("vector", queryVector);
        body.put("limit", safeLimit);
        body.put("with_payload", true);

        Map<String, Object> filter = buildSearchFilter(city);
        if (!filter.isEmpty()) {
            body.put("filter", filter);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> response = restTemplate.exchange(
                qdrantUrl + "/collections/" + collection + "/points/search",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );

        Map<String, Object> resBody = response.getBody();
        if (resBody == null || !(resBody.get("result") instanceof List<?> resultList)) {
            return Collections.emptyList();
        }

        List<SearchResult> results = new ArrayList<>();

        for (Object item : resultList) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }

            SearchResult result = new SearchResult();

            Object idObj = row.get("id");
            result.setEventId(parseLong(idObj));

            Object scoreObj = row.get("score");
            result.setScore(parseDouble(scoreObj));

            Object payloadObj = row.get("payload");
            if (payloadObj instanceof Map<?, ?> payloadMap) {
                Map<String, Object> payload = new HashMap<>();
                for (Map.Entry<?, ?> entry : payloadMap.entrySet()) {
                    payload.put(String.valueOf(entry.getKey()), entry.getValue());
                }

                if (result.getEventId() == null) {
                    result.setEventId(parseLong(payload.get("eventId")));
                }

                result.setPayload(payload);
            } else {
                result.setPayload(Collections.emptyMap());
            }

            if (result.getEventId() != null) {
                results.add(result);
            }
        }

        return results;
    }

    private Map<String, Object> buildSearchFilter(String city) {
        List<Map<String, Object>> must = new ArrayList<>();

        // Qdrant payload 里的 status 是索引时写入的演出状态。
        // 后面仍会回查 MySQL 二次过滤，所以这里是第一层粗过滤。
        must.add(match("status", 1));

        if (city != null && !city.trim().isEmpty()
                && !"全国".equals(city)
                && !"全部".equals(city)) {
            must.add(match("city", city.replace("市", "")));
        }

        if (must.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Object> filter = new HashMap<>();
        filter.put("must", must);
        return filter;
    }

    private Map<String, Object> match(String key, Object value) {
        Map<String, Object> match = new HashMap<>();
        match.put("value", value);

        Map<String, Object> condition = new HashMap<>();
        condition.put("key", key);
        condition.put("match", match);

        return condition;
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

    private Double parseDouble(Object value) {
        if (value == null) {
            return null;
        }

        try {
            return Double.valueOf(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * upsert 演出向量。
     *
     * Qdrant point id 必须是无符号整数或 UUID。
     * 这里统一用 eventId。
     */
    public void upsertEventVector(Long pointId, List<Double> vector, Map<String, Object> payload) {
        if (pointId == null) {
            throw new RuntimeException("Qdrant pointId 不能为空");
        }

        if (vector == null || vector.isEmpty()) {
            throw new RuntimeException("Qdrant vector 不能为空");
        }

        Map<String, Object> point = new HashMap<>();
        point.put("id", pointId);
        point.put("vector", vector);
        point.put("payload", payload == null ? Collections.emptyMap() : payload);

        Map<String, Object> body = new HashMap<>();
        body.put("points", Collections.singletonList(point));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        restTemplate.exchange(
                qdrantUrl + "/collections/" + collection + "/points?wait=true",
                HttpMethod.PUT,
                new HttpEntity<>(body, headers),
                Map.class
        );
    }

    public void deleteEventVector(Long pointId) {
        if (pointId == null) {
            return;
        }

        Map<String, Object> body = new HashMap<>();
        body.put("points", Collections.singletonList(pointId));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        restTemplate.exchange(
                qdrantUrl + "/collections/" + collection + "/points/delete?wait=true",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );
    }

    public String getCollection() {
        return collection;
    }
}