package com.avemonica.ticket.config;

import com.avemonica.ticket.utils.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import org.springframework.util.StringUtils;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // 1. 从 Header 中获取完整的 Authorization 字符串
        String headerToken = request.getHeader("Authorization");

        String token = null;

        // 2. 🚨 核心标准规范：检查 Header 是否存在，且必须以 "Bearer " 开头（注意后面有个空格）
        if (StringUtils.hasText(headerToken) && headerToken.startsWith("Bearer ")) {
            // 3. 截取掉前 7 个字符（即 "Bearer "），剩下的才是纯净的 JWT 字符串
            token = headerToken.substring(7);
        }

        // 4. 用截取出来的纯净 token 进行原有验证
        if (token != null && jwtUtils.validateToken(token)) {
            Long userId = jwtUtils.getUserId(token);

            // 加载用户信息和角色（权限）
            UserDetails userDetails = userDetailsService.loadUserByUsername(String.valueOf(userId));

            // 将身份和权限存入 Security 上下文
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        // 5. 放行请求（如果 token 无效或没带 Bearer，上下文就是空的，会被后面的拦截器拦下返回 403）
        filterChain.doFilter(request, response);
    }
}