package com.avemonica.ticket.service.impl;

import com.avemonica.ticket.entity.User;
import com.avemonica.ticket.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    @Autowired
    private UserMapper userMapper;


    @Override
    public UserDetails loadUserByUsername(String userIdStr) throws UsernameNotFoundException {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getId, Long.valueOf(userIdStr)));
        if (user == null) throw new UsernameNotFoundException("用户不存在");

        // 根据 role 字段赋予权限标识
        // 2. ⚡️ 动态获取该用户的所有权限字符 (如: ["admin:dashboard", "event:add"])
        List<String> permissions = userMapper.selectPermissionsByUserId(user.getId());

        // 3. 将权限字符串列表转换为 Spring Security 认识的 GrantedAuthority 对象
        List<SimpleGrantedAuthority> authorities = permissions.stream()
                .map(SimpleGrantedAuthority::new) // 直接传入 perm_key
                .toList();

        return new org.springframework.security.core.userdetails.User(
                user.getId().toString(),
                user.getPassword(),
                authorities
        );
    }
}