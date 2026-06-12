package com.avemonica.ticket.scheduler;

import com.avemonica.ticket.service.EventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
@EnableScheduling
public class EventDataSyncTask {

    public static final String EVENT_VIEWS_KEY = "event:views:";

    // 记录某演出想看用户的 Key (Set 类型: event:want:1，内部存 userId)
    public static final String EVENT_WANT_KEY = "event:want:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EventService eventService;

    // 每天凌晨 3 点执行一次同步
    @Scheduled(cron = "0 0 3 * * ?")
    public void syncRedisToMySQL() {
        // 1. 同步浏览量
        Set<String> viewsKeys = redisTemplate.keys(EVENT_VIEWS_KEY + "*");
        if (viewsKeys != null) {
            for (String key : viewsKeys) {
                Long eventId = Long.valueOf(key.split(":")[2]);
                String viewsStr = redisTemplate.opsForValue().get(key);
                if (viewsStr != null) {
                    // 把 Redis 里的增量加到 DB 里，然后删掉 Redis 里的增量记录重新计数
                    eventService.update()
                            .setSql("page_views = page_views + " + viewsStr)
                            .eq("id", eventId)
                            .update();
                    redisTemplate.delete(key);
                }
            }
        }

        // 2. 同步想看人数（想看人数是绝对值，直接覆盖即可，不用删除 Redis）
        Set<String> wantKeys = redisTemplate.keys(EVENT_WANT_KEY + "*");
        if (wantKeys != null) {
            for (String key : wantKeys) {
                Long eventId = Long.valueOf(key.split(":")[2]);
                Long wantCount = redisTemplate.opsForSet().size(key);
                if (wantCount != null) {
                    eventService.update()
                            .set("want_count", wantCount.intValue())
                            .eq("id", eventId)
                            .update();
                }
            }
        }
        System.out.println("====== Redis 浏览量与想看数据同步 MySQL 完毕 ======");
    }
}