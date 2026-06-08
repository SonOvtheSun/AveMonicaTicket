package com.avemonica.ticket.scheduler;

import com.avemonica.ticket.entity.Banner;
import com.avemonica.ticket.entity.BannerOverdate;
import com.avemonica.ticket.service.BannerOverdateService;
import com.avemonica.ticket.service.BannerService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class BannerScheduler {

    @Autowired
    private BannerService bannerService;

    @Autowired
    private BannerOverdateService overdateService;

    /**
     * 每天凌晨 1:00 准时触发冷热数据分离归档
     */
    @Scheduled(cron = "0 0 1 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void archiveExpiredBanners() {
        LocalDateTime now = LocalDateTime.now();
        log.info("⏰ 开始执行横幅过期检查与自动归档任务...");

        // 1. 从主表查询所有已过期的数据 (endTime < now)
        List<Banner> expiredList = bannerService.list(
                new LambdaQueryWrapper<Banner>().lt(Banner::getEndTime, now)
        );

        if (expiredList.isEmpty()) {
            return;
        }

        // 2. 将数据装载为归档表实体，并打上归档时间戳
        List<BannerOverdate> overdateList = expiredList.stream().map(b -> {
            BannerOverdate overdate = new BannerOverdate();
            BeanUtils.copyProperties(b, overdate);
            overdate.setArchiveTime(now);
            return overdate;
        }).collect(Collectors.toList());

        // 3. 批量写入冷库，并从热库删除 (同属一个事务，确保数据不丢失)
        overdateService.saveBatch(overdateList);

        List<Long> expiredIds = expiredList.stream().map(Banner::getId).collect(Collectors.toList());
        bannerService.removeByIds(expiredIds);

        log.info("✅ 成功将 {} 条过期横幅归档至历史记录表。", expiredList.size());
    }
}