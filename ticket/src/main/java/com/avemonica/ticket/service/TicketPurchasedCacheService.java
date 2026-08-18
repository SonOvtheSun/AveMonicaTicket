package com.avemonica.ticket.service;

import com.avemonica.ticket.dto.TicketIssueMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class TicketPurchasedCacheService {

    private static final String KEY_PURCHASED_SPEC =
            "event:purchased:spectators:";

    private final StringRedisTemplate redisTemplate;

    public TicketPurchasedCacheService(
            StringRedisTemplate redisTemplate
    ) {
        this.redisTemplate = redisTemplate;
    }

    public void markPurchased(
            TicketIssueMessage message
    ) {

        Long eventId = message.getEventId();
        Long sessionId = message.getSessionId();
        List<Long> spectatorIds =
                message.getSpectatorIds();

        if (eventId == null
                || sessionId == null
                || spectatorIds == null
                || spectatorIds.isEmpty()) {
            throw new RuntimeException(
                    "更新已购名单失败：出票消息参数不完整"
            );
        }

        String key =
                KEY_PURCHASED_SPEC
                        + eventId
                        + ":"
                        + sessionId;

        String[] values =
                spectatorIds.stream()
                        .map(String::valueOf)
                        .toArray(String[]::new);

        redisTemplate.opsForSet()
                .add(key, values);

        redisTemplate.expire(
                key,
                30,
                TimeUnit.DAYS
        );
    }
}