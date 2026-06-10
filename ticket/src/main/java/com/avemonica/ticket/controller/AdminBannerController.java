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
import com.avemonica.ticket.entity.User;
import com.avemonica.ticket.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Objects;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@RestController
@RequestMapping("/api/admin/banner")
public class AdminBannerController {

    @Autowired
    private UserService userService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BannerService bannerService;

    @Autowired
    private BannerOverdateService overdateService;

    private User getCurrentUser() {
        String userId = SecurityContextHolder.getContext().getAuthentication().getName();
        return userService.getById(Long.valueOf(userId));
    }

    private boolean isSuperAdmin() {
        User user = getCurrentUser();
        return user != null && user.getId() == 1L;
    }

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('banner:manage') or principal.username == '1'")
    public Result<?> getBanners(@RequestParam Integer type) {
        LocalDateTime now = LocalDateTime.now();

        if (type == 1) {
            // 即将展示：查热库，开始时间大于当前时间
            return Result.success(bannerService.list(
                    new LambdaQueryWrapper<Banner>()
                            .eq(Banner::getAuditStatus, 1)
                            .le(Banner::getStartTime, now)
                            .ge(Banner::getEndTime, now)
                            .orderByDesc(Banner::getCreateTime)
            ));
        } else if (type == 2) {
            // 展示中：查热库，当前时间处于开始与结束之间
            return Result.success(bannerService.list(
                    new LambdaQueryWrapper<Banner>()
                            .eq(Banner::getAuditStatus, 1)
                            .le(Banner::getStartTime, now)
                            .ge(Banner::getEndTime, now)
                            .orderByDesc(Banner::getCreateTime)
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
        boolean superAdmin = isSuperAdmin();

        boolean isNowExpired = dto.getEndTime().isBefore(now);
        if (!isNowExpired) {
            checkBannerDailyLimit(dto.getStartTime(), dto.getEndTime(), dto.getId());
        }

        // 1. 超管：直接走原来的保存逻辑，立即生效
        if (superAdmin) {
            saveBannerDirectly(dto, now, isNowExpired);
            return Result.success("横幅配置保存成功");
        }

        // 2. 普通管理员新增：进入新增待审核
        if (dto.getId() == null) {
            Banner banner = new Banner();
            BeanUtils.copyProperties(dto, banner);
            banner.setAuditStatus(0);
            banner.setEditAuditStatus(null);
            banner.setPendingPayload(null);
            banner.setAuditSubmitTime(now);
            banner.setCreateBy(getCurrentUser().getId());
            banner.setCreateTime(now);
            bannerService.save(banner);
            return Result.success("横幅已提交审核，审核通过后客户端才会展示");
        }

        // 3. 普通管理员编辑
        Banner oldBanner = bannerService.getById(dto.getId());
        if (oldBanner == null) {
            throw new BusinessException("横幅不存在");
        }

        if (Objects.equals(oldBanner.getAuditStatus(), 0)) {
            throw new BusinessException("该横幅正在审核中，如需修改，请先撤销审核申请");
        }

        if (Objects.equals(oldBanner.getEditAuditStatus(), 0)) {
            throw new BusinessException("该横幅修改正在审核中，如需再次修改，请先撤销审核申请");
        }

        if (Objects.equals(oldBanner.getAuditStatus(), 1)) {
            try {
                oldBanner.setPendingPayload(objectMapper.writeValueAsString(dto));
                oldBanner.setEditAuditStatus(0);
                oldBanner.setAuditSubmitTime(now);
                bannerService.updateById(oldBanner);
                return Result.success("横幅修改已提交审核，审核通过前客户端仍展示原横幅");
            } catch (Exception e) {
                throw new BusinessException("保存横幅修改审核快照失败");
            }
        }

        if (Objects.equals(oldBanner.getAuditStatus(), 2) || Objects.equals(oldBanner.getAuditStatus(), 3)) {
            Banner banner = new Banner();
            BeanUtils.copyProperties(dto, banner);
            banner.setId(dto.getId());
            banner.setAuditStatus(0);
            banner.setEditAuditStatus(null);
            banner.setPendingPayload(null);
            banner.setAuditSubmitTime(now);
            bannerService.updateById(banner);
            return Result.success("横幅已重新提交审核");
        }

        throw new BusinessException("当前横幅状态不允许修改");
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

    @GetMapping("/audit-list")
    @PreAuthorize("hasAuthority('audit:manage') or principal.username == '1'")
    public Result<List<Banner>> getPendingBanners() {
        List<Banner> list = bannerService.list(
                new LambdaQueryWrapper<Banner>()
                        .and(w -> w.eq(Banner::getAuditStatus, 0)
                                .or()
                                .eq(Banner::getEditAuditStatus, 0))
                        .orderByDesc(Banner::getAuditSubmitTime)
        );
        return Result.success(list);
    }

    @PutMapping("/audit/{id}")
    @PreAuthorize("hasAuthority('audit:manage') or principal.username == '1'")
    @Transactional(rollbackFor = Exception.class)
    public Result<String> auditBanner(@PathVariable Long id, @RequestParam Boolean isPass) {
        Banner banner = bannerService.getById(id);
        if (banner == null) {
            throw new BusinessException("横幅不存在");
        }

        // 修改审核
        if (Objects.equals(banner.getEditAuditStatus(), 0)) {
            if (isPass) {
                try {
                    BannerSaveDTO dto = objectMapper.readValue(banner.getPendingPayload(), BannerSaveDTO.class);
                    LocalDateTime now = LocalDateTime.now();
                    boolean isNowExpired = dto.getEndTime().isBefore(now);
                    saveBannerDirectly(dto, now, isNowExpired);
                    return Result.success("横幅修改审核已通过，客户端信息已同步更新");
                } catch (Exception e) {
                    throw new BusinessException("解析横幅修改审核快照失败");
                }
            } else {
                banner.setEditAuditStatus(2);
                banner.setPendingPayload(null);
                bannerService.updateById(banner);
                return Result.success("已驳回横幅修改申请，客户端继续展示原横幅");
            }
        }

        // 新增审核
        if (Objects.equals(banner.getAuditStatus(), 0)) {
            if (isPass) {
                banner.setAuditStatus(1);
            } else {
                banner.setAuditStatus(2);
            }
            bannerService.updateById(banner);
            return Result.success(isPass ? "横幅新增审核已通过" : "已驳回横幅新增申请");
        }

        throw new BusinessException("当前横幅没有待审核申请");
    }

    @PutMapping("/revoke/{id}")
    @PreAuthorize("hasAuthority('banner:manage') or principal.username == '1'")
    public Result<String> revokeBannerAudit(@PathVariable Long id) {
        Banner banner = bannerService.getById(id);
        if (banner == null) {
            throw new BusinessException("横幅不存在");
        }

        if (Objects.equals(banner.getAuditStatus(), 0)) {
            banner.setAuditStatus(3);
            bannerService.updateById(banner);
            return Result.success("已撤销横幅新增审核申请，可重新编辑后提交");
        }

        if (Objects.equals(banner.getEditAuditStatus(), 0)) {
            banner.setEditAuditStatus(null);
            banner.setPendingPayload(null);
            bannerService.updateById(banner);
            return Result.success("已撤销横幅修改审核申请，客户端信息未受影响");
        }

        throw new BusinessException("当前状态无需撤销审核");
    }

    private void saveBannerDirectly(BannerSaveDTO dto, LocalDateTime now, boolean isNowExpired) {
        Banner activeBanner = new Banner();
        BeanUtils.copyProperties(dto, activeBanner);
        if (activeBanner.getCreateTime() == null) activeBanner.setCreateTime(now);
        activeBanner.setAuditStatus(1);
        activeBanner.setEditAuditStatus(null);
        activeBanner.setPendingPayload(null);
        activeBanner.setAuditSubmitTime(now);

        BannerOverdate coldBanner = new BannerOverdate();
        BeanUtils.copyProperties(dto, coldBanner);
        if (coldBanner.getCreateTime() == null) coldBanner.setCreateTime(now);
        coldBanner.setArchiveTime(now);

        if (dto.getId() == null) {
            if (isNowExpired) {
                overdateService.save(coldBanner);
            } else {
                bannerService.save(activeBanner);
            }
        } else {
            if (dto.getIsExpiredEdit()) {
                if (isNowExpired) {
                    overdateService.updateById(coldBanner);
                } else {
                    overdateService.removeById(dto.getId());
                    bannerService.save(activeBanner);
                }
            } else {
                if (isNowExpired) {
                    bannerService.removeById(dto.getId());
                    overdateService.save(coldBanner);
                } else {
                    bannerService.updateById(activeBanner);
                }
            }
        }
    }
}