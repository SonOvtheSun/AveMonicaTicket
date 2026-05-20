package com.avemonica.ticket.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import jakarta.servlet.DispatcherType;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

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
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // 无状态
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 👇 核心修改点：在这里追加 random-nickname 和 check-nickname
                        .requestMatchers(
                                "/api/user/login",
                                "/api/user/register",
                                "/api/user/random-username", // 放行获取随机昵称接口
                                "/api/user/check-username",
                                "/api/user/send-code",
                                "/api/user/login-sms",
                                "/error",
                                "/uploads/**",
                                "/api/event/upcoming",
                                "/api/event/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                ).addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

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