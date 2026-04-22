package com.picsou.controller;

import com.picsou.config.JwtUtil;
import com.picsou.config.RateLimitConfig;
import com.picsou.dto.ActivationRequest;
import com.picsou.dto.LoginRequest;
import com.picsou.model.AppUser;
import com.picsou.repository.AppUserRepository;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final Map<String, Bucket> loginBuckets;

    @Value("${app.secure-cookies:true}")
    private boolean secureCookies;

    public AuthController(
        AppUserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtUtil jwtUtil,
        @org.springframework.beans.factory.annotation.Qualifier("loginBuckets") Map<String, Bucket> loginBuckets
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
        this.loginBuckets = loginBuckets;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(
        @Valid @RequestBody LoginRequest req,
        HttpServletRequest httpReq,
        HttpServletResponse httpRes
    ) {
        String ip = getClientIp(httpReq);
        Bucket bucket = loginBuckets.computeIfAbsent(ip, k -> RateLimitConfig.createLoginBucket());

        if (!bucket.tryConsume(1)) {
            ProblemDetail detail = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            detail.setDetail("Too many login attempts. Try again in 15 minutes.");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(detail);
        }

        AppUser user = userRepository.findByUsernameWithMember(req.username())
            .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        String accessToken = jwtUtil.generateAccessToken(user);
        String refreshToken = jwtUtil.generateRefreshToken(user);

        setTokenCookies(httpRes, accessToken, refreshToken);

        return ResponseEntity.ok(Map.of(
            "username", user.getUsername(),
            "role", user.getRole().name(),
            "memberId", user.getMember().getId(),
            "displayName", user.getMember().getDisplayName()
        ));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest httpReq, HttpServletResponse httpRes) {
        String refreshToken = extractCookie(httpReq, "refresh_token");

        if (refreshToken == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "No refresh token"));
        }

        try {
            var claims = jwtUtil.validateAndParse(refreshToken);
            if (!jwtUtil.isRefreshToken(claims)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            String username = claims.getSubject();
            AppUser user = userRepository.findByUsernameWithMember(username)
                .orElseThrow(() -> new BadCredentialsException("User not found"));

            String newAccess = jwtUtil.generateAccessToken(user);
            String newRefresh = jwtUtil.generateRefreshToken(user); // rotation

            setTokenCookies(httpRes, newAccess, newRefresh);
            return ResponseEntity.ok(Map.of(
                "username", user.getUsername(),
                "role", user.getRole().name(),
                "memberId", user.getMember().getId(),
                "displayName", user.getMember().getDisplayName()
            ));

        } catch (Exception ex) {
            clearTokenCookies(httpRes);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse httpRes) {
        clearTokenCookies(httpRes);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/username")
    public ResponseEntity<?> changeUsername(
        @AuthenticationPrincipal AppUser user,
        @Valid @RequestBody ChangeUsernameRequest req,
        HttpServletResponse httpRes
    ) {
        String newUsername = req.newUsername().trim();
        if (newUsername.equals(user.getUsername())) {
            return ResponseEntity.ok(Map.of("username", user.getUsername()));
        }
        if (userRepository.existsByUsername(newUsername)) {
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            problem.setDetail("Username already taken");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }
        user.setUsername(newUsername);
        userRepository.save(user);
        String newAccess = jwtUtil.generateAccessToken(user);
        String newRefresh = jwtUtil.generateRefreshToken(user);
        setTokenCookies(httpRes, newAccess, newRefresh);
        return ResponseEntity.ok(Map.of("username", newUsername));
    }

    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(
        @AuthenticationPrincipal AppUser user,
        @Valid @RequestBody ChangePasswordRequest req
    ) {

        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Current password is incorrect");
        }

        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "Password updated successfully"));
    }

    @PostMapping("/activate/{token}")
    public ResponseEntity<?> activate(
        @PathVariable String token,
        @Valid @RequestBody ActivationRequest req
    ) {
        AppUser user = userRepository.findByActivationToken(token)
            .orElseThrow(() -> new BadCredentialsException("Invalid activation token"));

        if (user.getActivationTokenExpires() != null &&
            user.getActivationTokenExpires().isBefore(Instant.now())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "Activation token has expired"));
        }

        if (!req.acknowledgedWarning()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "You must acknowledge the data access warning"));
        }

        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setActivationToken(null);
        user.setActivationTokenExpires(null);
        user.setActivated(true);
        user.setAcknowledgedWarning(true);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("message", "Account activated successfully"));
    }

    // ─── Cookie helpers ───────────────────────────────────────────────────────

    private void setTokenCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        addCookie(response, "access_token", accessToken, (int) jwtUtil.getAccessExpirySeconds());
        addCookie(response, "refresh_token", refreshToken, (int) jwtUtil.getRefreshExpirySeconds());
    }

    private void clearTokenCookies(HttpServletResponse response) {
        addCookie(response, "access_token", "", 0);
        addCookie(response, "refresh_token", "", 0);
    }

    private void addCookie(HttpServletResponse response, String name, String value, int maxAge) {
        String cookieHeader = String.format(
            "%s=%s; Max-Age=%d; Path=/; HttpOnly; SameSite=Lax%s",
            name, value, maxAge, secureCookies ? "; Secure" : ""
        );
        response.addHeader("Set-Cookie", cookieHeader);
    }

    private String extractCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (var cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private String getClientIp(HttpServletRequest request) {
        // Never trust X-Forwarded-For from the client — it is user-controllable and
        // would allow rate-limit bypass by spoofing IPs. Use only the TCP-level remote
        // address, which is the nginx container's internal IP in production (the only
        // valid entry point on the picsou-net Docker bridge network).
        return request.getRemoteAddr();
    }

    record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank @Size(min = 8, max = 128) String newPassword
    ) {}

    record ChangeUsernameRequest(
        @NotBlank @Size(min = 3, max = 50)
        @jakarta.validation.constraints.Pattern(regexp = "[a-zA-Z0-9._-]+", message = "Username may only contain letters, digits, dots, underscores and hyphens")
        String newUsername
    ) {}
}
