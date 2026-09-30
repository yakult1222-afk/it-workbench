package com.qinghuan.workbench.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {
    private final JwtService jwtService = new JwtService(
            "test-secret-that-is-at-least-32-bytes-long", 8);

    @Test
    void generatesAndParsesUserId() {
        LoginUser user = new LoginUser(
                7L, "qinghuan", "$2y$10$hash", "清欢",
                2L, "USER", "普通用户", true, Set.of("task:self:view"));

        String token = jwtService.generate(user);

        assertEquals(7L, jwtService.parseUserId(token));
    }

    @Test
    void rejectsTamperedToken() {
        LoginUser user = new LoginUser(
                7L, "qinghuan", "$2y$10$hash", "清欢",
                2L, "USER", "普通用户", true, Set.of("task:self:view"));
        String token = jwtService.generate(user);

        assertThrows(Exception.class, () -> jwtService.parseUserId(token + "x"));
    }
}
