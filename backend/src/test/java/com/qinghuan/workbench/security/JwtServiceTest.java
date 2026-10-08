package com.qinghuan.workbench.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {
    private final MockEnvironment testEnvironment = new MockEnvironment();
    private final JwtService jwtService = new JwtService(
            "test-secret-that-is-at-least-32-bytes-long", 8, testEnvironment);

    @Test
    void generatesAndParsesUserId() {
        LoginUser user = new LoginUser(
                7L, "qinghuan", "$2y$10$hash", "清欢",
                2L, "USER", "普通用户", true, Set.of("task:self:view"));

        String token = jwtService.generate(user);

        assertEquals(7L, jwtService.parseUserId(token));
    }

    @BeforeEach
    void configureTestProfile() {
        testEnvironment.setActiveProfiles("test");
    }

    @Test
    void rejectsBlankSecretInProduction() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");

        assertThrows(IllegalStateException.class,
                () -> new JwtService("", 8, production));
    }

    @Test
    void allowsTemporarySecretOutsideProduction() {
        JwtService temporary = new JwtService("", 8, testEnvironment);
        LoginUser user = new LoginUser(
                7L, "qinghuan", "$2y$10$hash", "清欢",
                2L, "USER", "普通用户", true, Set.of("task:self:view"));

        assertEquals(7L, temporary.parseUserId(temporary.generate(user)));
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
