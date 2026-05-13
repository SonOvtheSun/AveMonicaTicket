package com.avemonica.ticket.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // 1. 配置密码加密器（使用业界最安全的 BCrypt 算法）
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 2. 配置保安的拦截规则
// 在你的 SecurityConfig 类中，修改 filterChain 方法
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 1. 禁用 CSRF
                .csrf(csrf -> csrf.disable())
                // 2. 开启 CORS（跨域支持）
                .cors(org.springframework.security.config.Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        // 3. 显式放行所有的 OPTIONS 预检请求
                        .requestMatchers(org.springframework.web.bind.annotation.RequestMethod.OPTIONS.name()).permitAll()
                        // 4. 放行注册和登录
                        .requestMatchers("/api/user/login", "/api/user/register", "/error").permitAll()
                        // 5. 其他请求才需要登录
                        .anyRequest().authenticated()
                );

        return http.build();
    }

    @Bean
    public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        org.springframework.web.cors.CorsConfiguration configuration = new org.springframework.web.cors.CorsConfiguration();
        // 允许来自前端 Vite 的地址
        configuration.setAllowedOrigins(java.util.Arrays.asList("http://localhost:5173"));
        // 允许所有的请求方法 (GET, POST, OPTIONS 等)
        configuration.setAllowedMethods(java.util.Arrays.asList("*"));
        // 允许所有的请求头
        configuration.setAllowedHeaders(java.util.Arrays.asList("*"));
        // 允许携带 Cookie 或 Token
        configuration.setAllowCredentials(true);

        org.springframework.web.cors.UrlBasedCorsConfigurationSource source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        // 对所有接口生效
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}