package com.picsou.config;

import com.picsou.model.AppUser;
import com.picsou.repository.AppUserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final AppUserRepository userRepository;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, AppUserRepository userRepository) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {

        String token = extractAccessTokenFromCookie(request);

        if (token != null) {
            try {
                Claims claims = jwtUtil.validateAndParse(token);
                if (jwtUtil.isAccessToken(claims)) {
                    Long userId = claims.get("uid", Long.class);
                    Long tv = jwtUtil.getTokenVersion(claims);
                    if (userId != null) {
                        AppUser user = userRepository.findByIdWithMember(userId).orElse(null);
                        if (user != null && user.isActivated()
                            && tv != null && tv == user.getTokenVersion()) {
                            String role = "ROLE_" + user.getRole().name();
                            var auth = new UsernamePasswordAuthenticationToken(
                                user,
                                null,
                                List.of(new SimpleGrantedAuthority(role))
                            );
                            SecurityContextHolder.getContext().setAuthentication(auth);
                        }
                    }
                }
            } catch (JwtException ex) {
                // Invalid token — continue unauthenticated
            }
        }

        chain.doFilter(request, response);
    }

    private String extractAccessTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie cookie : request.getCookies()) {
            if ("access_token".equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
