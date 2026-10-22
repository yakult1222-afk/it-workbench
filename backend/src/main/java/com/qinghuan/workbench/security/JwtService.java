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

/**
 * 负责 JWT 的签发和解析。
 *
 * <p>签名密钥通过 Spring 属性 {@code workbench.jwt.secret} 注入。该属性最终通常来自
 * 环境变量 {@code JWT_SECRET}；本地启动脚本会把 {@code backend/.env} 中配置的变量
 * 导入环境变量。生产环境必须提供至少 32 字节的稳定密钥。非生产环境未配置密钥时，
 * 会为当前进程生成临时随机密钥，因此应用重启后此前签发的令牌会失效。</p>
 */
@Slf4j
@Service
public class JwtService {
    /** 用于签发和校验 JWT 的 HMAC-SHA256 密钥。 */
    private final SecretKey key;
    /** JWT 自签发时起允许使用的时长。 */
    private final Duration expireDuration;

    /**
     * 创建 JWT 服务并初始化签名密钥。
     *
     * @param secret       Spring 属性 {@code workbench.jwt.secret}，通常由
     *                     环境变量 {@code JWT_SECRET} 提供
     * @param expireHours  JWT 有效期，单位为小时，默认 8 小时
     * @param environment  Spring 环境对象，用于判断当前是否启用了 {@code prod} Profile
     * @throws IllegalArgumentException 显式配置的密钥不足 32 字节
     * @throws IllegalStateException    生产环境未配置密钥
     */
    public JwtService(@Value("${workbench.jwt.secret:}") String secret,
                      @Value("${workbench.jwt.expire-hours:8}") long expireHours,
                      Environment environment) {
        if (secret == null || secret.isBlank()) {
            // 生产环境不能使用临时密钥，否则应用重启或多个实例之间无法互相校验令牌。
            if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
                throw new IllegalStateException("JWT_SECRET is required in production");
            }
            this.key = generateRandomKey();
            log.warn("JWT_SECRET 未配置，当前使用临时随机密钥；应用重启后已签发令牌会失效");
        } else {
            // JJWT 的 HMAC-SHA256 要求密钥至少为 256 位（32 字节）。
            if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("JWT secret must be at least 32 bytes");
            }
            this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        }
        this.expireDuration = Duration.ofHours(expireHours);
    }

    /** 生成仅用于当前应用进程的临时 256 位 HMAC 密钥。 */
    private static SecretKey generateRandomKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("HmacSHA256");
            generator.init(256);
            return generator.generateKey();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /**
     * 为登录用户签发 JWT。
     *
     * <p>用户 ID 写入标准 {@code sub} 声明，用户名写入自定义 {@code username}
     * 声明，并写入签发时间和过期时间。服务端不保存会话，令牌合法性由签名验证。</p>
     *
     * @param user 已通过认证的用户
     * @return 紧凑格式的 JWT 字符串
     */
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

    /**
     * 校验 JWT 签名和过期时间，并从中读取用户 ID。
     *
     * <p>解析过程中的签名错误、格式错误或令牌过期都会由 JJWT 抛出异常，
     * 调用方应将其转换为认证失败响应。</p>
     *
     * @param token 客户端提交的 JWT
     * @return 令牌 {@code sub} 声明中的用户 ID
     */
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
