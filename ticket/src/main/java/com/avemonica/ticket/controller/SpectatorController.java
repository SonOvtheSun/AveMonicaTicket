package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result; // 假设你的通用返回体叫 Result
import com.avemonica.ticket.entity.Spectator;
import com.avemonica.ticket.service.SpectatorService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.redisson.api.RedissonClient;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/user/spectator")

public class SpectatorController {
    @Autowired
    private SpectatorService spectatorService;

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 获取当前登录用户的所有常用观演人列表 (未删除)
     */
    @GetMapping("/list")
    public Result<List<Spectator>> getSpectatorList() {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(401, "登录已过期，请重新登录");

        List<Spectator> list = spectatorService.list(
                new LambdaQueryWrapper<Spectator>()
                        .eq(Spectator::getUserId, userId)
                        .eq(Spectator::getIsDeleted, 0) // 仅查询未软删除的
                        .orderByDesc(Spectator::getCreateTime)
        );
        return Result.success(list);
    }

    /**
     * 新增常用购票人 (上限50个拦截)
     */
    @PostMapping("/add")
    public Result<String> addSpectator(@RequestBody Spectator spectator) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(401, "登录已过期，请重新登录");

        // 🚨 1. 定义分布式锁的 Key，粒度精确到具体的 userId
        String lockKey = "lock:spectator:add:" + userId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 🚨 2. 尝试获取锁：最多等待 3 秒，拿不到就直接返回失败（防止拖垮系统）
            // 拿到锁后，Redisson 的“看门狗 (Watch Dog)”机制会自动续期，不用担心业务没执行完锁就过期了
            boolean isLocked = lock.tryLock(3, -1, TimeUnit.SECONDS);

            if (!isLocked) {
                return Result.error("系统繁忙或您点击太快，请稍后再试");
            }

            // 🚨 3. 进入安全的临界区：执行数量校验
            long count = spectatorService.count(
                    new LambdaQueryWrapper<Spectator>()
                            .eq(Spectator::getUserId, userId)
                            .eq(Spectator::getIsDeleted, 0)
            );
            if (count >= 50) {
                return Result.error("添加失败：每个账户最多只能保存 50 个常用购票人");
            }

            spectator.setUserId(userId);
            spectator.setIsDeleted(0);
            spectator.setIsDefault(0);

            try {
                spectatorService.save(spectator);
                return Result.success("添加成功");
            } catch (Exception e) {
                // 利用数据库联合唯一索引进行兜底拦截
                return Result.error("添加失败：该证件号已被您添加过，请勿重复操作");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error("请求被意外中断，请重试");
        } finally {
            // 🚨 4. 释放锁：必须放在 finally 块中，且要判断锁是否是当前线程持有的
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @PostMapping("/delete/{id}")
    public Result<String> deleteSpectator(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        Spectator spectator = spectatorService.getById(id);
        // 安全校验：只能删自己的
        if (spectator == null || !spectator.getUserId().equals(userId)) {
            return Result.error("无权操作或数据不存在");
        }
        spectator.setIsDeleted(1); // 软删除
        spectatorService.updateById(spectator);
        return Result.success("删除成功");
    }

    /**
     * 修改常用购票人
     */
    @PostMapping("/update")
    public Result<String> updateSpectator(@RequestBody Spectator spectator) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(401, "未登录");

        // 安全校验：确认要修改的数据确实是当前用户的
        Spectator exist = spectatorService.getById(spectator.getId());
        if (exist == null || !exist.getUserId().equals(userId) || exist.getIsDeleted() == 1) {
            return Result.error("无权操作或数据不存在");
        }

        try {
            spectatorService.updateById(spectator);
            return Result.success("修改成功");
        } catch (Exception e) {
            return Result.error("修改失败：该证件号已被您添加过");
        }
    }

    /**
     * 辅助方法：从 SecurityContext 中获取当前用户的 userId
     */
    private Long getCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            org.springframework.security.core.userdetails.User principal =
                    (org.springframework.security.core.userdetails.User) auth.getPrincipal();
            return Long.valueOf(principal.getUsername());
        } catch (Exception e) {
            return null;
        }
    }


}
