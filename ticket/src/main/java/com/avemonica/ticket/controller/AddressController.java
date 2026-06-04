package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.entity.Address;
import com.avemonica.ticket.service.AddressService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/user/address")
public class AddressController {

    @Autowired
    private AddressService addressService;
    @Autowired
    private RedissonClient redissonClient;

    // 1. 获取地址列表
    @GetMapping("/list")
    public Result<List<Address>> getList() {
        Long userId = getCurrentUserId();
        List<Address> list = addressService.list(
                new LambdaQueryWrapper<Address>()
                        .eq(Address::getUserId, userId)
                        .eq(Address::getIsDeleted, 0)
        );
        return Result.success(list);
    }

    // 2. 新增地址 (带20个上限并发锁)
    @PostMapping("/add")
    public Result<String> addAddress(@RequestBody Address address) {
        Long userId = getCurrentUserId();
        String lockKey = "lock:address:add:" + userId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (!lock.tryLock(3, -1, TimeUnit.SECONDS)) {
                return Result.error("系统繁忙，请稍后再试");
            }
            long count = addressService.count(
                    new LambdaQueryWrapper<Address>().eq(Address::getUserId, userId).eq(Address::getIsDeleted, 0)
            );
            if (count >= 20) return Result.error("最多只能保存 20 个收货地址");

            // 补充基础的系统字段
            address.setUserId(userId);
            address.setIsDeleted(0);
            address.setIsDefault(0);

            // 🚨 删除了原先写死的默认省市，直接保存前端发来的完整 Address 对象（包含 district）
            addressService.save(address);
            return Result.success("添加成功");
        } catch (Exception e) {
            e.printStackTrace(); // 打印日志方便后续排查
            return Result.error("请求异常");
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    // 3. 软删除地址
    @PostMapping("/delete/{id}")
    public Result<String> deleteAddress(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        Address address = addressService.getById(id);
        if (address == null || !address.getUserId().equals(userId)) {
            return Result.error("无权操作");
        }
        address.setIsDeleted(1); // 软删除
        addressService.updateById(address);
        return Result.success("删除成功");
    }

    /**
     * 修改收货地址
     */
    @PostMapping("/update")
    public Result<String> updateAddress(@RequestBody Address address) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(401, "未登录");

        // 安全校验：确认要修改的数据确实是当前用户的
        Address exist = addressService.getById(address.getId());
        if (exist == null || !exist.getUserId().equals(userId) || exist.getIsDeleted() == 1) {
            return Result.error("无权操作或数据不存在");
        }

        addressService.updateById(address);
        return Result.success("修改成功");
    }

    private Long getCurrentUserId() {
        return Long.valueOf(((org.springframework.security.core.userdetails.User) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUsername());
    }
}