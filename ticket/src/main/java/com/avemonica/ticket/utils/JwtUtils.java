package com.avemonica.ticket.utils;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import java.security.Key;
import java.util.Date;

@Component
public class JwtUtils {
    // 实际生产环境应放在配置文件中
    private static final String SECRET = "AveMonicaTicketSecretKeyForJWTSecurity2026";
    private static final long EXPIRATION = 86400000; // 24小时有效
    private static final Key KEY = Keys.hmacShaKeyFor(SECRET.getBytes());

    // 生成 Token
    public String createToken(Long userId) {
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION))
                .signWith(KEY)
                .compact();
    }

    // 解析 Token
    public Long getUserId(String token) {
        String subject = Jwts.parserBuilder().setSigningKey(KEY).build()
                .parseClaimsJws(token).getBody().getSubject();
        return Long.valueOf(subject); // 将解析出来的字符串 ID 转回 Long 返回
    }

    // 校验 Token
    public boolean validateToken(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(KEY).build().parseClaimsJws(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}