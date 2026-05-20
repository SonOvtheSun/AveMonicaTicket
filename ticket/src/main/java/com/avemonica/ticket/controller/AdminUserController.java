package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.entity.User;
import com.avemonica.ticket.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/user")
public class AdminUserController{
    @Autowired
    private UserService userService;

    @GetMapping("/list")
    @PreAuthorize("principal.username == '1'")
    public Result<IPage<User>> listUsers(@RequestParam(defaultValue = "1") int current,
                                         @RequestParam(defaultValue = "10") int size){
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(User::getId);

        IPage<User> pageData = userService.page(new Page<>(current, size), wrapper);
        pageData.getRecords().forEach(u -> u.setPassword(null));

        return Result.success(pageData);
    }

    @PutMapping("/role/{id}")
    @PreAuthorize("principal.username == '1'")
    public Result<String> updateUserRole(@PathVariable Long id, @RequestParam Integer role){
        User user = userService.getById(id);
        if (user == null) {
            return Result.error("用户不存在");
        }

        // 保护机制：防止超管把自己降级了
        if (user.getId() == 1L) {
            return Result.error("系统最高管理员权限不可被修改");
        }

        user.setRole(role);
        userService.updateById(user);

        return Result.success("角色权限更新成功");
    }

}
