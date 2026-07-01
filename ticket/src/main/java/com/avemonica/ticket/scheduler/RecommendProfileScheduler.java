package com.avemonica.ticket.scheduler;

import com.avemonica.ticket.mapper.UserBehaviorMapper;
import com.avemonica.ticket.service.RecommendProfileService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 用户推荐画像定时刷新任务。
 *
 * 设计原则：
 * 1. 不在用户请求链路中同步刷新画像；
 * 2. 定时批量刷新最近活跃用户；
 * 3. 单个用户画像只基于最近 200 条行为生成；
 * 4. 使用 Redis 分布式锁，避免多实例重复刷新。
 */
@Slf4j
@Component
public class RecommendProfileScheduler {

    /**
     * 每次只扫描最近 30 分钟有行为的用户。
     * 如果你的流量很小，可以改成 60。
     */
    private static final int ACTIVE_LOOKBACK_MINUTES = 30;

    /**
     * 单次最多刷新 200 个用户，避免定时任务一次跑太久。
     */
    private static final int BATCH_SIZE = 200;

    /**
     * 分布式锁时间。
     * 当前任务 5 分钟跑一次，锁 4 分钟比较合适。
     */
    private static final long LOCK_SECONDS = 240;

    private static final String LOCK_KEY = "recommend:profile:refresh:lock";

    @Autowired
    private UserBehaviorMapper userBehaviorMapper;

    @Autowired
    private RecommendProfileService recommendProfileService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private DefaultRedisScript<Long> unlockScript;

    @PostConstruct
    public void init() {
        unlockScript = new DefaultRedisScript<>();
        unlockScript.setResultType(Long.class);
        unlockScript.setScriptText("""
                if redis.call('GET', KEYS[1]) == ARGV[1] then
                    return redis.call('DEL', KEYS[1])
                else
                    return 0
                end
                """);
    }

    /**
     * 每 5 分钟刷新一次最近活跃用户画像。
     *
     * initialDelay：
     * 项目启动 60 秒后再开始，避免启动阶段数据库、Redis、缓存尚未完全就绪。
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 60 * 1000)
    public void refreshRecentActiveUserProfiles() {
        String lockValue = UUID.randomUUID().toString();

        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, lockValue, LOCK_SECONDS, TimeUnit.SECONDS);

        if (!Boolean.TRUE.equals(locked)) {
            log.debug("推荐画像刷新任务跳过：已有实例正在执行");
            return;
        }

        try {
            doRefreshRecentActiveUserProfiles();
        } finally {
            releaseLock(lockValue);
        }
    }

    private void doRefreshRecentActiveUserProfiles() {
        LocalDateTime since = LocalDateTime.now().minusMinutes(ACTIVE_LOOKBACK_MINUTES);

        List<Long> userIds = userBehaviorMapper.selectUserIdsNeedProfileRefresh(since, BATCH_SIZE);
        if (userIds == null || userIds.isEmpty()) {
            log.debug("推荐画像刷新任务完成：没有需要刷新的用户");
            return;
        }

        int successCount = 0;
        int failCount = 0;

        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }

            try {
                recommendProfileService.refreshUserProfile(userId);
                successCount++;
            } catch (Exception e) {
                failCount++;
                log.warn("刷新用户推荐画像失败，userId={}", userId, e);
            }
        }

        log.info("推荐画像刷新任务完成，扫描用户数={}，成功={}，失败={}",
                userIds.size(), successCount, failCount);
    }

    private void releaseLock(String lockValue) {
        try {
            redisTemplate.execute(
                    unlockScript,
                    Collections.singletonList(LOCK_KEY),
                    lockValue
            );
        } catch (Exception e) {
            log.warn("释放推荐画像刷新任务锁失败", e);
        }
    }
}