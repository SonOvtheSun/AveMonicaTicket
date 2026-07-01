package com.avemonica.ticket.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Component
public class QdrantClient {

    @Value("${ai.vector.qdrant-url:http://localhost:6333}")
    private String qdrantUrl;

    @Value("${ai.vector.collection:event_rag}")
    private String collection;

    private final RestTemplate restTemplate = new RestTemplate();

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