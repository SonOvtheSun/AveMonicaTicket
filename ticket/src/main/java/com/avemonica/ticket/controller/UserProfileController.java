package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.entity.User;
import com.avemonica.ticket.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user/profile")
public class UserProfileController {

    @Autowired
    private UserService userService;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    /**
     * 1. 获取当前登录用户的完整个人资料
     * 对接前端：axios.get('/api/user/profile/info')
     */
    @GetMapping("/info")
    public Result<User> getUserProfile() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(401, "登录已过期，请重新登录");
        }

        User user = userService.getById(userId);
        if (user == null) {
            return Result.error("未找到该用户信息");
        }

        // 🚨 安全脱敏：绝对不能把密码哈希值返回给前端
        user.setPassword(null);
        return Result.success(user);
    }

    /**
     * 更新用户基本资料 (用户名、性别、生日、简介)
     * 对接前端：axios.post('/api/user/profile/update')
     */
    @PostMapping("/update")
    public Result<String> updateProfile(@RequestBody User updateParams) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(401, "登录已过期");
        }

        // 1. 查出当前未修改的系统用户信息
        User currentUser = userService.getById(userId);
        if (currentUser == null) {
            return Result.error("用户不存在");
        }

        // 2. 🚨 唯一性校验：如果用户修改了用户名，需要去数据库查重
        String newUsername = updateParams.getUsername();
        if (newUsername != null && !newUsername.equals(currentUser.getUsername())) {
            if (userId == 1 || "admin".equals(currentUser.getUsername())) {
                return Result.error("安全限制：管理员账号（admin）不可修改用户名");
            }

            // 复用你已有的用户名可用性检查服务
            boolean available = userService.isUsernameAvailable(newUsername);
            if (!available) {
                return Result.error("该用户名已存在，请换一个试试");
            }
        }

        // 3. 执行更新
        User user = new User();
        user.setId(userId);
        user.setUsername(newUsername); // 🚨 替换为 username
        user.setGender(updateParams.getGender());
        user.setBirthday(updateParams.getBirthday());
        user.setBio(updateParams.getBio());
        user.setAvatar(updateParams.getAvatar());

        boolean success = userService.updateById(user);
        return success ? Result.success("基本信息修改成功", null) : Result.error("修改失败");
    }

    /**
     * 3. 用户实名认证 (一经认证，数据库底层锁死不可修改)
     * 对接前端实名认证模态框
     */
    @PostMapping("/real-name-auth")
    public Result<String> realNameAuth(@RequestBody User authParams) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(401, "登录已过期");
        }

        // ① 检查当前用户是否已经实名过，防止恶意重复提交
        User currentUser = userService.getById(userId);
        if (currentUser.getRealName() != null && !currentUser.getRealName().trim().isEmpty()) {
            return Result.error("您已完成实名认证，信息已锁定，无法重复认证");
        }

        // ② 查重：检查该身份证号是否已被系统内的其他账号绑定
        long count = userService.count(
                new LambdaQueryWrapper<User>()
                        .eq(User::getIdCard, authParams.getIdCard())
                // 排除由于软删除导致的残留脏数据（如果你们用户表有软删除的话）
        );
        if (count > 0) {
            // 🚨 冲突状态码：返回 409 冲突，前端收到 409 后即可弹出“是否强制解绑原账户”的二级确认框
            return Result.error(409, "该身份信息已被其他账户绑定");
        }

        // ③ 落地保存实名信息
        User updateUser = new User();
        updateUser.setId(userId);
        updateUser.setRealName(authParams.getRealName());
        updateUser.setIdCard(authParams.getIdCard());
        if (authParams.getIdType() != null) {
            updateUser.setIdType(authParams.getIdType()); // 对应之前讨论的证件类型扩展
        }

        boolean success = userService.updateById(updateUser);
        return success ? Result.success("实名认证成功", null) : Result.error("认证失败，请稍后再试");
    }

    /**
     * 4. 修改登录密码
     */
    @PostMapping("/update-password")
    public Result<String> updatePassword(@RequestBody java.util.Map<String, String> params) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(401, "登录已过期");

        String oldPassword = params.get("oldPassword");
        String newPassword = params.get("newPassword");

        User user = userService.getById(userId);

        // 校验原密码是否正确
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            return Result.error("原密码输入错误");
        }

        // 加密并保存新密码
        User updateUser = new User();
        updateUser.setId(userId);
        updateUser.setPassword(passwordEncoder.encode(newPassword));
        userService.updateById(updateUser);

        return Result.success("密码修改成功，下次请使用新密码登录");
    }

    /**
     * 5. 绑定/更换邮箱
     */
    @PostMapping("/update-email")
    public Result<String> updateEmail(@RequestBody java.util.Map<String, String> params) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(401, "登录已过期");

        String email = params.get("email");
        // String code = params.get("code");
        // 🚨 工业界此处需校验邮箱验证码。这里为了跑通主流程，假设验证码校验已通过

        // 查重：检查邮箱是否已被别人绑定
        long count = userService.count(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
        if (count > 0) return Result.error("该邮箱已被其他账号绑定");

        User updateUser = new User();
        updateUser.setId(userId);
        updateUser.setEmail(email);
        userService.updateById(updateUser);

        return Result.success("邮箱绑定成功");
    }

    /**
     * 6. 更换手机号
     */
    @PostMapping("/update-phone")
    public Result<String> updatePhone(@RequestBody java.util.Map<String, String> params) {
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error(401, "登录已过期");

        String phone = params.get("phone");
//        String code = params.get("code");
        // 🚨 工业界此处需调用 smsService.verifyCode(phone, code) 校验短信验证码

        long count = userService.count(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (count > 0) return Result.error("该手机号已被其他账号绑定");

        User updateUser = new User();
        updateUser.setId(userId);
        updateUser.setPhone(phone);
        userService.updateById(updateUser);

        return Result.success("手机号更换成功");
    }

    /**
     * 提取公共方法：从 Security 上下文中获取经过网关/Filter 解析后的当前用户 ID
     */
    private Long getCurrentUserId() {
        try {
            String name = SecurityContextHolder.getContext().getAuthentication().getName();
            return Long.valueOf(name);
        } catch (Exception e) {
            return null;
        }
    }
}