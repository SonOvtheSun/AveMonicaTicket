package com.avemonica.ticket.scheduler;

import com.avemonica.ticket.entity.UserRecommendEvent;
import com.avemonica.ticket.mapper.UserRecommendEventMapper;
import com.avemonica.ticket.service.RecommendService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 首页推荐结果表刷新任务。
 *
 * 目标：
 * 1. 不在用户请求链路中实时重算推荐表；
 * 2. 服务器闲时、小批量、不定期刷新；
 * 3. 首页接口优先读取 tb_user_recommend_event；
 * 4. 没有预计算结果时才走实时兜底推荐。
 */
@Slf4j
@Component
public class RecommendEventScheduler {

    private static final String SCENE_HOME = UserRecommendEvent.SCENE_HOME;

    /**
     * 单轮最多刷新多少个用户。
     * 建议先小一点，避免一次性扫太多用户。
     */
    private static final int BATCH_SIZE = 30;

    /**
     * 每个用户保留多少条首页推荐结果。
     */
    private static final int STORE_SIZE = 50;

    /**
     * 推荐结果最长有效期。
     * 即使用户画像没有变化，超过 12 小时也会被刷新。
     */
    private static final int STALE_HOURS = 12;

    private static final String LOCK_KEY = "recommend:event:home:refresh:lock";
    private static final long LOCK_SECONDS = 300;

    @Autowired
    private UserRecommendEventMapper userRecommendEventMapper;

    @Autowired
    private RecommendService recommendService;

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
     * 每 10 分钟触发一次，但不一定每次都执行。
     *
     * 真正执行条件：
     * 1. 处于低峰时间段；或者
     * 2. 随机命中，并且服务器 CPU 负载较低。
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000, initialDelay = 2 * 60 * 1000)
    public void refreshHomeRecommendEventsWhenIdle() {
        if (!shouldRunThisRound()) {
            log.debug("首页推荐刷新跳过：当前非闲时或随机未命中");
            return;
        }

        String lockValue = UUID.randomUUID().toString();

        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(LOCK_KEY, lockValue, LOCK_SECONDS, TimeUnit.SECONDS);

        if (!Boolean.TRUE.equals(locked)) {
            log.debug("首页推荐刷新跳过：已有实例正在执行");
            return;
        }

        try {
            doRefreshHomeRecommendEvents();
        } finally {
            releaseLock(lockValue);
        }
    }

    private void doRefreshHomeRecommendEvents() {
        LocalDateTime staleBefore = LocalDateTime.now().minusHours(STALE_HOURS);

        List<Long> userIds = userRecommendEventMapper.selectUserIdsNeedRecommendRefresh(
                SCENE_HOME,
                staleBefore,
                BATCH_SIZE
        );

        if (userIds == null || userIds.isEmpty()) {
            log.debug("首页推荐刷新完成：没有需要刷新的用户");
            return;
        }

        int success = 0;
        int fail = 0;

        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }

            try {
                recommendService.refreshUserHomeRecommendEvents(userId, STORE_SIZE);
                success++;
            } catch (Exception e) {
                fail++;
                log.warn("刷新用户首页推荐失败，userId={}", userId, e);
            }
        }

        log.info("首页推荐刷新完成，扫描用户数={}，成功={}，失败={}",
                userIds.size(), success, fail);
    }

    /**
     * 控制“不定期”和“闲时”。
     */
    private boolean shouldRunThisRound() {
        int hour = LocalDateTime.now().getHour();

        // 低峰时间段：凌晨、午间、深夜。
        boolean offPeak =
                (hour >= 1 && hour < 8)
                        || (hour >= 12 && hour < 14)
                        || (hour >= 23);

        if (offPeak) {
            return true;
        }

        // 非低峰时段：只有 20% 概率尝试执行。
        boolean randomHit = ThreadLocalRandom.current().nextInt(100) < 20;
        return randomHit && isServerCpuIdle();
    }

    /**
     * 简单 CPU 闲时判断。
     * 读取失败时保守返回 false。
     */
    private boolean isServerCpuIdle() {
        try {
            java.lang.management.OperatingSystemMXBean baseBean =
                    ManagementFactory.getOperatingSystemMXBean();

            if (baseBean instanceof com.sun.management.OperatingSystemMXBean osBean) {
                double load = osBean.getCpuLoad();

                // getCpuLoad 可能返回负数，表示暂不可用。
                if (load < 0) {
                    return false;
                }

                return load < 0.55;
            }

            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void releaseLock(String lockValue) {
        try {
            redisTemplate.execute(
                    unlockScript,
                    Collections.singletonList(LOCK_KEY),
                    lockValue
            );
        } catch (Exception e) {
            log.warn("释放首页推荐刷新锁失败", e);
        }
    }
}