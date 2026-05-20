package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.exception.BusinessException;
import com.avemonica.ticket.service.SmsService;
import com.avemonica.ticket.utils.JwtUtils;
import com.avemonica.ticket.utils.RandomUtil;
import com.avemonica.ticket.common.Result;
import com.avemonica.ticket.dto.UserLoginDTO;
import com.avemonica.ticket.dto.UserRegisterDTO;
import com.avemonica.ticket.entity.User;
import com.avemonica.ticket.mapper.UserMapper;
import com.avemonica.ticket.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtils jwtUtils; // 👇 1. 注入我们写好的 JWT 工具类

    @Autowired
    private SmsService smsService;

    public UserServiceImpl(SmsService smsService) {
        this.smsService = smsService;
    }

    @Override
    public void register(UserRegisterDTO dto) {
        // 1. 检查手机号是否已被注册
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getPhone, dto.getPhone());
        if (this.count(queryWrapper) > 0) {
            throw new BusinessException("该手机号已注册");
        }

        if (!isUsernameAvailable(dto.getUsername())) {
            throw new BusinessException("用户名已存在");
        }

        if (!smsService.verifyCode(dto.getPhone(), dto.getCode())) {
            throw new BusinessException("验证码错误或已过期");
        }

        // 2. 构建新用户对象
        User user = new User();
        user.setPhone(dto.getPhone());
        user.setUsername(dto.getUsername());
        String defaultAvatar = "/uploads/avatar/default.png";
        user.setAvatar(defaultAvatar);
        // 3. 密码加密存储 (绝对不要存明文！)
        String encodedPassword = passwordEncoder.encode(dto.getPassword());
        user.setPassword(encodedPassword);

        // 4. 保存到数据库
        this.save(user);
    }

    @Override
    public String login(UserLoginDTO dto) {
        // 1. 根据手机号查询用户
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.nested(i -> i.eq(User::getPhone, dto.getAccount())
                .or()
                .eq(User::getUsername, dto.getAccount()));
        User user = this.getOne(queryWrapper);

        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            // 👇 遇到错误，直接抛出！
            throw new BusinessException("账号或密码错误");
        }

        // 3. 登录成功，生成 Token (这里暂时用 UUID 模拟，后续可换成真实的 JWT)

        // TODO: 可将 token 存入 Redis，设置过期时间

        return jwtUtils.createToken(user.getId());
    }

    @Override
    public boolean isUsernameAvailable(String username) {
        return this.count(new LambdaQueryWrapper<User>().eq(User::getUsername, username)) == 0;
    }

    @Override
    public String generateUniqueUsername() {
        String username;
        int retry = 0;
        do {
            username = RandomUtil.generateAmUsername();
            retry++;
            // 理论上 10 位字母数字组合碰撞率极低，但仍需数据库校验
        } while (!isUsernameAvailable(username) && retry < 10);
        return username;
    }

    @Override
    public boolean checkUserExistsByPhone(String phone) {
        // 使用 MyBatis-Plus 查询数据库中是否存在该手机号
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getPhone, phone);
        // 如果查出来的数量大于 0，说明用户存在
        return this.count(queryWrapper) > 0;
    }
}
