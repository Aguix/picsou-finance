package com.picsou.config;

import com.picsou.model.AppUser;
import com.picsou.model.UserRole;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    private JwtUtil jwt;
    private AppUser user;

    @BeforeEach
    void setUp() {
        // 32+ char secret to satisfy the HS256 length check.
        jwt = new JwtUtil("0123456789abcdef0123456789abcdef-test", 15, 7, 5);
        user = AppUser.builder()
            .id(42L)
            .username("alice")
            .passwordHash("h")
            .role(UserRole.ADMIN)
            .build();
    }

    // ─── token type isolation ─────────────────────────────────────────────

    @Test
    void accessToken_isOnlyRecognizedAsAccess() {
        Claims claims = jwt.validateAndParse(jwt.generateAccessToken(user));
        assertThat(jwt.isAccessToken(claims)).isTrue();
        assertThat(jwt.isRefreshToken(claims)).isFalse();
        assertThat(jwt.isMfaChallengeToken(claims)).isFalse();
    }

    @Test
    void refreshToken_isOnlyRecognizedAsRefresh() {
        Claims claims = jwt.validateAndParse(jwt.generateRefreshToken(user));
        assertThat(jwt.isRefreshToken(claims)).isTrue();
        assertThat(jwt.isAccessToken(claims)).isFalse();
        assertThat(jwt.isMfaChallengeToken(claims)).isFalse();
    }

    @Test
    void mfaChallengeToken_isOnlyRecognizedAsMfaChallenge() {
        Claims claims = jwt.validateAndParse(jwt.generateMfaChallengeToken(user, false));
        assertThat(jwt.isMfaChallengeToken(claims)).isTrue();
        assertThat(jwt.isAccessToken(claims)).isFalse();
        assertThat(jwt.isRefreshToken(claims)).isFalse();
    }

    // ─── mfa_challenge specifics ──────────────────────────────────────────

    @Test
    void mfaChallengeToken_carriesUserIdAndRole() {
        Claims claims = jwt.validateAndParse(jwt.generateMfaChallengeToken(user, true));
        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(claims.get("uid", Long.class)).isEqualTo(42L);
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
    }

    @Test
    void mfaChallengeToken_persistsRememberMeFlag() {
        Claims yes = jwt.validateAndParse(jwt.generateMfaChallengeToken(user, true));
        Claims no  = jwt.validateAndParse(jwt.generateMfaChallengeToken(user, false));

        assertThat(jwt.getRememberMeClaim(yes)).isTrue();
        assertThat(jwt.getRememberMeClaim(no)).isFalse();
    }

    @Test
    void rememberMeClaim_isFalseOnTokensWithoutTheClaim() {
        // Access/refresh tokens never set remember_me — accessor must not blow up
        // and must return false (defensive default).
        Claims access = jwt.validateAndParse(jwt.generateAccessToken(user));
        assertThat(jwt.getRememberMeClaim(access)).isFalse();
    }

    // ─── expiry exposure ──────────────────────────────────────────────────

    @Test
    void mfaChallengeExpirySeconds_matchesConstructor() {
        // 5 minutes → 300 seconds.
        assertThat(jwt.getMfaChallengeExpirySeconds()).isEqualTo(300L);
    }
}
