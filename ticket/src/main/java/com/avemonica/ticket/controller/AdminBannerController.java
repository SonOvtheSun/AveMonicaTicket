package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.dto.BannerSaveDTO;
import com.avemonica.ticket.entity.Banner;
import com.avemonica.ticket.entity.BannerOverdate;
import com.avemonica.ticket.exception.BusinessException;
import com.avemonica.ticket.service.BannerOverdateService;
import com.avemonica.ticket.service.BannerService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping("/api/admin/banner")
public class AdminBannerController {

    @Autowired
    private BannerService bannerService;

    @Autowired
    private BannerOverdateService overdateService;

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('banner:manage') or principal.username == '1'")
    public Result<?> getBanners(@RequestParam Integer type) {
        LocalDateTime now = LocalDateTime.now();

        if (type == 1) {
            // 即将展示：查热库，开始时间大于当前时间
            return Result.success(bannerService.list(
                    new LambdaQueryWrapper<Banner>().gt(Banner::getStartTime, now).orderByDesc(Banner::getCreateTime)
            ));
        } else if (type == 2) {
            // 展示中：查热库，当前时间处于开始与结束之间
            return Result.success(bannerService.list(
                    new LambdaQueryWrapper<Banner>().le(Banner::getStartTime, now).ge(Banner::getEndTime, now).orderByDesc(Banner::getCreateTime)
            ));
        } else {
            // 已过期：查冷库
            return Result.success(overdateService.list(
                    new LambdaQueryWrapper<BannerOverdate>().orderByDesc(BannerOverdate::getArchiveTime)
            ));
        }
    }

    @PostMapping("/save")
    @PreAuthorize("hasAuthority('banner:manage') or principal.username == '1'")
    @Transactional(rollbackFor = Exception.class)
    public Result<String> saveBanner(@RequestBody @Validated BannerSaveDTO dto) {
        LocalDateTime now = LocalDateTime.now();
        boolean isNowExpired = dto.getEndTime().isBefore(now); // 判读修改后的时间是否属于过期

        if (!isNowExpired) {
            checkBannerDailyLimit(dto.getStartTime(), dto.getEndTime(), dto.getId());
        }

        Banner activeBanner = new Banner();
        BeanUtils.copyProperties(dto, activeBanner);
        if (activeBanner.getCreateTime() == null) activeBanner.setCreateTime(now);

        BannerOverdate coldBanner = new BannerOverdate();
        BeanUtils.copyProperties(dto, coldBanner);
        if (coldBanner.getCreateTime() == null) coldBanner.setCreateTime(now);
        coldBanner.setArchiveTime(now);

        if (dto.getId() == null) {
            // 场景A：全新增
            if (isNowExpired) {
                overdateService.save(coldBanner); // 新增了一个过去的日期，直接打入冷库
            } else {
                bannerService.save(activeBanner);
            }
        } else {
            // 场景B：编辑现有数据
            if (dto.getIsExpiredEdit()) {
                // 原本在冷库
                if (isNowExpired) {
                    overdateService.updateById(coldBanner); // 依然过期，更新冷库
                } else {
                    overdateService.removeById(dto.getId()); // 【满血复活】从冷库删掉，加到热库
                    bannerService.save(activeBanner);
                }
            } else {
                // 原本在热库
                if (isNowExpired) {
                    bannerService.removeById(dto.getId()); // 【提前下架】改成了过去的时间，踢入冷库
                    overdateService.save(coldBanner);
                } else {
                    bannerService.updateById(activeBanner); // 依然在热库更新
                }
            }
        }
        return Result.success("横幅配置保存成功");
    }

    /**
     * 🚨 核心风控算法：横幅每日展示数量碰撞检测
     */
    private void checkBannerDailyLimit(LocalDateTime newStart, LocalDateTime newEnd, Long currentBannerId) {
        // 1. 从主表（活跃区）中，找出所有与新横幅时间【有交集】的横幅
        LambdaQueryWrapper<Banner> wrapper = new LambdaQueryWrapper<>();
        wrapper.le(Banner::getStartTime, newEnd)
                .ge(Banner::getEndTime, newStart);

        // 如果是编辑操作，需要把自己排除掉，不计入冲突计算
        if (currentBannerId != null) {
            wrapper.ne(Banner::getId, currentBannerId);
        }

        List<Banner> overlappingBanners = bannerService.list(wrapper);
        if (overlappingBanners.isEmpty()) {
            return; // 没有任何交集，绝对安全，直接放行
        }

        // 2. 将横幅的展示期拆解为“天”，逐天校验重叠数量
        LocalDate startDate = newStart.toLocalDate();
        LocalDate endDate = newEnd.toLocalDate();

        // 遍历这期间的每一天
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            LocalDateTime dayStart = date.atStartOfDay(); // 当天 00:00:00
            LocalDateTime dayEnd = date.atTime(LocalTime.MAX); // 当天 23:59:59

            // 计算在这特定的一天里，有多少张横幅同时处于展示状态
            long countOnThisDay = overlappingBanners.stream()
                    .filter(b -> b.getStartTime().compareTo(dayEnd) <= 0 && b.getEndTime().compareTo(dayStart) >= 0)
                    .count();

            // 如果这一天已经有 10 张了，再加上我们要保存的这 1 张，就会达到 11 张 -> 拦截！
            if (countOnThisDay >= 10) {
                throw new BusinessException("排期冲突：在 " + date.toString() + " 这一天已有 " + countOnThisDay + " 张横幅排期，加上本条将超过 8 张上限！请缩短时间或下架其他横幅。");
            }
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('banner:manage') or principal.username == '1'")
    public Result<String> deleteBanner(@PathVariable Long id, @RequestParam Boolean isExpired) {
        if (isExpired) {
            overdateService.removeById(id);
        } else {
            bannerService.removeById(id);
        }
        return Result.success("删除成功");
    }
}