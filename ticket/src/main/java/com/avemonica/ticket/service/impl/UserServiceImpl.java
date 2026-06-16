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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /**
     * 用户 ID 规则：9 位随机数字。
     * 范围：100000000 ~ 999999999。
     *
     * 这样仍然可以继续使用 Long / BIGINT 类型，不需要把全项目用户 ID 改成 String。
     */
    private static final long USER_ID_MIN = 100000000L;
    private static final long USER_ID_RANGE = 900000000L;
    private static final int USER_ID_MAX_RETRY = 50;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtils jwtUtils; // 👇 1. 注入我们写好的 JWT 工具类

    @Autowired
    private SmsService smsService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public void register(UserRegisterDTO dto) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getPhone, dto.getPhone());
        if (this.count(queryWrapper) > 0) {
            throw new BusinessException("该手机号已注册");
        }

        if (!isUsernameAvailable(dto.getUsername())) {
            throw new BusinessException("用户名已存在");
        }

        boolean smsVerifiedByTicket = false;

        if (dto.getRegisterTicket() != null && !dto.getRegisterTicket().trim().isEmpty()) {
            String ticketKey = "sms:register:ticket:" + dto.getRegisterTicket();
            String ticketPhone = redisTemplate.opsForValue().get(ticketKey);

            if (ticketPhone == null || !ticketPhone.equals(dto.getPhone())) {
                throw new BusinessException("注册凭证无效或已过期，请重新获取验证码");
            }

            smsVerifiedByTicket = true;
            redisTemplate.delete(ticketKey);
        }

        if (!smsVerifiedByTicket) {
            if (!smsService.verifyCode(dto.getPhone(), dto.getCode())) {
                throw new BusinessException("验证码错误或已过期");
            }
        }

        User user = new User();

        // 新用户 ID 使用 9 位随机数字，保持 Long / BIGINT 类型不变。
        user.setId(generateUniqueUserId());

        user.setPhone(dto.getPhone());
        user.setUsername(dto.getUsername());
        user.setAvatar("/uploads/avatar/default.png");
        user.setPassword(passwordEncoder.encode(dto.getPassword()));

        this.save(user);
    }

    /**
     * 生成唯一用户 ID：9 位随机数字。
     *
     * 由于随机数字存在极低概率碰撞，所以生成后必须查库确认。
     */
    private Long generateUniqueUserId() {
        for (int i = 0; i < USER_ID_MAX_RETRY; i++) {
            Long userId = nextNineDigitUserId();

            long count = this.count(new LambdaQueryWrapper<User>().eq(User::getId, userId));
            if (count == 0) {
                return userId;
            }
        }

        throw new BusinessException("用户ID生成失败，请稍后重试");
    }

    /**
     * 生成 100000000 ~ 999999999 之间的 9 位数字。
     */
    private Long nextNineDigitUserId() {
        return USER_ID_MIN + SECURE_RANDOM.nextLong(USER_ID_RANGE);
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

        // 3. 登录成功，生成 Token
        String token = jwtUtils.createToken(user.getId());

        // 🚨 核心互踢逻辑：将最新 Token 存入 Redis，实现单设备覆盖 (设置 7 天过期，需与你 JWT 真实有效期一致)
        String redisKey = "user:token:" + user.getId();
        redisTemplate.opsForValue().set(redisKey, token, 7, java.util.concurrent.TimeUnit.DAYS);

        return token;
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
