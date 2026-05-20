package com.avemonica.ticket.controller;

import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.dto.UserLoginDTO;
import com.avemonica.ticket.dto.UserRegisterDTO;
import com.avemonica.ticket.entity.SysPermission;
import com.avemonica.ticket.entity.User;
import com.avemonica.ticket.mapper.SysPermissionMapper;
import com.avemonica.ticket.mapper.UserMapper;
import com.avemonica.ticket.service.UserService;
import com.avemonica.ticket.utils.JwtUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.avemonica.ticket.dto.SendCodeDTO;
import com.avemonica.ticket.service.SmsService;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/user")
public class UserController {
    @Autowired
    private UserService userService;

    @Autowired
    private SmsService smsService;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private SysPermissionMapper sysPermissionMapper;

    @PostMapping("/register")
    public Result<String> register(@RequestBody UserRegisterDTO dto) {
        userService.register(dto);
        // 代码能走到这里，说明上面没抛异常，绝对是成功的
        return Result.success("注册成功", null);
    }

    @PostMapping("/login")
    public Result<String> login(@Validated @RequestBody UserLoginDTO dto) {
        String token = userService.login(dto);
        // 统一包装返回
        return Result.success("登录成功", token);
    }

    @GetMapping("/check-username")
    public Result<Boolean> checkNickname(@RequestParam String username) {
        boolean available = userService.isUsernameAvailable(username);
        return Result.success(available ? "该昵称可用" : "该昵称已存在", available);
    }

    @GetMapping("/random-username")
    public Result<String> getRandomNickname() {
        return Result.success("生成成功", userService.generateUniqueUsername());
    }

    @PostMapping("/send-code")
    public Result<String> sendCode(@Validated @RequestBody SendCodeDTO dto) {
        return smsService.sendCode(dto.getPhone());
    }

    // 2. 修改：免密登录/校验接口 (前端输入手机号和验证码点击登录时调用)
    // 之前可能只有密码登录，现在我们需要处理免密登录的逻辑
    @PostMapping("/login-sms")
    public Result<String> loginBySms(@RequestParam String phone, @RequestParam String code) {
        // 第一步：校验验证码
        boolean isValid = smsService.checkCodeOnly(phone, code);
        if (!isValid) {
            return Result.error("验证码错误或已过期");
        }

        // 第二步：检查用户是否存在
        boolean userExists = userService.checkUserExistsByPhone(phone); // 你需要在 UserService 里加这个方法

        User user = userService.getOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (userExists) {
            // 用户存在，执行登录逻辑，颁发 Token
            String token = jwtUtils.createToken(user.getId());
            smsService.consumeCode(phone);
            return Result.success("登录成功", token);
        } else {
            // 重点：用户不存在，返回特殊状态码，告诉前端去弹出“设置密码昵称”的框
            // 这里用 code = 201 模拟“需要补全信息”
            Result<String> res = Result.success("验证成功，请完善注册信息", null);
            res.setCode(201);
            return res;
        }
    }

    @GetMapping("/info")
    public Result<User> getUserInfo() {
        // 1. 从 Spring Security 的“保安亭”里直接拿当前访问者的用户名
        String userId = SecurityContextHolder.getContext().getAuthentication().getName();

        // 2. 去数据库查这个用户的详细信息（包括 role 字段）
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getId, Long.valueOf(userId));
        User user = userService.getOne(queryWrapper);

        if (user != null) {
            if(user.getRole() != null && user.getRole() == 1){
                user.setPermissions(List.of("*:*"));
            }else{
                List<SysPermission> permList = sysPermissionMapper.selectList(
                        new LambdaQueryWrapper<SysPermission>().eq(SysPermission::getRole, user.getRole())
                );
                List<String> permCodes = permList.stream()
                        .map(SysPermission::getPermissionCode)
                        .collect(Collectors.toList());
                user.setPermissions(permCodes);
            }

            // 3. 🚨 极其重要的安全意识：数据脱敏！绝对不能把加密的密码下发给前端
            user.setPassword(null);
            return Result.success("获取用户信息成功", user);
        }
        return Result.error("未找到用户信息");
    }

    /**
     * 专属管理员测试接口
     * 利用 @PreAuthorize 注解，轻松实现接口级别的权限控制
     */
    @PostMapping("/admin/test")
    @PreAuthorize("principal.username == '1'") // 只有在 UserDetailsServiceImpl 里被赋予了 ROLE_ADMIN 的人才能进
    public Result<String> adminTest() {
        return Result.success("尊贵的管理员，欢迎进入 Ave Monica 票务核心控制台！", null);
    }
}
