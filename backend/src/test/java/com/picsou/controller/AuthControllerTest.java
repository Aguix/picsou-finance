package com.picsou.controller;

import com.picsou.config.AuthCookieWriter;
import com.picsou.config.JwtUtil;
import com.picsou.dto.LoginRequest;
import com.picsou.model.AppUser;
import com.picsou.model.FamilyMember;
import com.picsou.model.UserRole;
import com.picsou.repository.AppUserRepository;
import com.picsou.service.MfaService;
import com.picsou.service.PersistentSessionService;
import com.picsou.service.SetupAuditService;
import io.github.bucket4j.Bucket;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

import jakarta.servlet.http.Cookie;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock AppUserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtUtil jwtUtil;
    @Mock AuthCookieWriter cookieWriter;
    @Mock MfaService mfaService;
    @Mock PersistentSessionService persistentSessionService;
    @Mock SetupAuditService auditService;

    Map<String, Bucket> loginBuckets;
    Map<String, Bucket> mfaVerifyBuckets;
    AuthController controller;
    MockHttpServletRequest httpReq;
    MockHttpServletResponse httpRes;

    @BeforeEach
    void setUp() {
        loginBuckets = new HashMap<>();
        mfaVerifyBuckets = new HashMap<>();
        controller = new AuthController(
            userRepository, passwordEncoder, jwtUtil,
            loginBuckets, mfaVerifyBuckets, cookieWriter,
            mfaService, persistentSessionService, auditService
        );
        httpReq = new MockHttpServletRequest();
        httpReq.setRemoteAddr("10.0.0.5");
        httpRes = new MockHttpServletResponse();
    }

    private AppUser user(boolean activated) {
        FamilyMember member = FamilyMember.builder()
            .id(42L).displayName("Alice").build();
        return AppUser.builder()
            .id(7L).username("alice")
            .role(UserRole.ADMIN)
            .passwordHash("$2a$12$hash")
            .activated(activated)
            .tokenVersion(3L)
            .member(member)
            .build();
    }

    // ─── login ───────────────────────────────────────────────────────────

    @Test
    void login_returns403_andSetsNoCookies_whenAccountNotActivated() {
        AppUser deactivated = user(false);
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(deactivated));
        when(passwordEncoder.matches("pw", "$2a$12$hash")).thenReturn(true);

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).isInstanceOf(ProblemDetail.class);
        assertThat(((ProblemDetail) res.getBody()).getDetail()).containsIgnoringCase("not activated");
        // No session must be established for a deactivated account.
        verify(cookieWriter, never()).setAccessAndRefresh(any(), any(), any());
        verify(mfaService, never()).isEnabled(any());
    }

    @Test
    void login_proceeds_whenActivated_noMfa() {
        AppUser active = user(true);
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("pw", "$2a$12$hash")).thenReturn(true);
        when(mfaService.isEnabled(active)).thenReturn(false);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref");

        ResponseEntity<?> res = controller.login(
            new LoginRequest("alice", "pw", false), httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc", "ref");
    }

    // ─── refresh ─────────────────────────────────────────────────────────

    @Test
    void refresh_returns401_andClearsCookies_whenAccountNotActivated() {
        AppUser deactivated = user(false);
        httpReq.setCookies(new Cookie("refresh_token", "rt"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("rt")).thenReturn(claims);
        when(jwtUtil.isRefreshToken(claims)).thenReturn(true);
        when(claims.getSubject()).thenReturn("alice");
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(deactivated));
        when(jwtUtil.getTokenVersion(claims)).thenReturn(3L); // matches user.tokenVersion

        ResponseEntity<?> res = controller.refresh(httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(cookieWriter).clearAuthCookies(httpRes);
        verify(jwtUtil, never()).generateAccessToken(any());
    }

    @Test
    void refresh_rotatesTokens_whenActivated_andTvMatches() {
        AppUser active = user(true);
        httpReq.setCookies(new Cookie("refresh_token", "rt"));
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtUtil.validateAndParse("rt")).thenReturn(claims);
        when(jwtUtil.isRefreshToken(claims)).thenReturn(true);
        when(claims.getSubject()).thenReturn("alice");
        when(userRepository.findByUsernameWithMember("alice")).thenReturn(Optional.of(active));
        when(jwtUtil.getTokenVersion(claims)).thenReturn(3L);
        when(jwtUtil.generateAccessToken(active)).thenReturn("acc2");
        when(jwtUtil.generateRefreshToken(active)).thenReturn("ref2");

        ResponseEntity<?> res = controller.refresh(httpReq, httpRes);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(cookieWriter).setAccessAndRefresh(httpRes, "acc2", "ref2");
    }
}
