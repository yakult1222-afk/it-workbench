package com.qinghuan.workbench.security;

import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;

@Slf4j
@Service
public class JwtService {
    private final SecretKey key;
    private final Duration expireDuration;

    public JwtService(@Value("${workbench.jwt.secret:}") String secret,
                      @Value("${workbench.jwt.expire-hours:8}") long expireHours,
                      Environment environment) {
        if (secret == null || secret.isBlank()) {
            if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
                throw new IllegalStateException("JWT_SECRET is required in production");
            }
            this.key = generateRandomKey();
            log.warn("JWT_SECRET 未配置，当前使用临时随机密钥；应用重启后已签发令牌会失效");
        } else {
            if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("JWT secret must be at least 32 bytes");
            }
            this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        }
        this.expireDuration = Duration.ofHours(expireHours);
    }

    private static SecretKey generateRandomKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("HmacSHA256");
            generator.init(256);
            return generator.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    public String generate(LoginUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("username", user.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expireDuration)))
                .signWith(key)
                .compact();
    }

    public Long parseUserId(String token) {
        String subject = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
        return Long.valueOf(subject);
    }
}
