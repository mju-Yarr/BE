package com.hq.backend.common.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import com.hq.backend.auth.JwtService;

/**
 * 화이트리스트 방식 보안 설정.
 * 명시된 경로만 인증 없이 허용하고 나머지는 Bearer JWT 필수.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtService jwtService;

    private static final String[] PUBLIC_PATHS = {
            "/auth/**",
            "/actuator/health",
            "/health",
            "/error",
            // ponytail: 로컬 Swagger 확인용. 커밋하지 말 것(사용자 요청으로 로컬 전용 작업).
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**"
    };

    /**
     * Browser clients are hosted on Firebase Hosting. Keep this exact allowlist in sync with
     * deployed frontend origins; the API origin itself is not a cross-origin browser client.
     * localhost:* is allowed so developers can point a local frontend (dev server port varies
     * per run) at this API without a redeploy each time.
     */
    private static final List<String> ALLOWED_CORS_ORIGINS = List.of(
            "https://ensom-10da2.web.app",
            "https://ensom-10da2.firebaseapp.com",
            "http://localhost:*"
    );

    private static final List<String> ALLOWED_CORS_HEADERS = List.of(
            "Authorization",
            "Content-Type",
            "X-Refresh-Token",
            "X-App-Version",
            "Accept-Language",
            "Idempotency-Key"
    );

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorizedEntryPoint()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        var config = new org.springframework.web.cors.CorsConfiguration();
        config.setAllowedOriginPatterns(ALLOWED_CORS_ORIGINS);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(ALLOWED_CORS_HEADERS);
        config.setExposedHeaders(List.of("Authorization", "X-Refresh-Token", "Idempotency-Key"));
        // ENSOM uses Bearer tokens rather than browser cookies; do not permit credentialed CORS.
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"인증이 필요합니다.\",\"retryable\":false}}");
        };
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtService);
    }

    /**
     * Bearer 토큰을 파싱해서 SecurityContext에 인증 정보를 세팅하는 필터.
     * 토큰이 없거나 유효하지 않으면 SecurityContext를 비워둬서 Spring Security가 401을 반환하게 한다.
     */
    @RequiredArgsConstructor
    static class JwtAuthenticationFilter extends OncePerRequestFilter {

        private final JwtService jwtService;

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            String header = request.getHeader("Authorization");

            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                try {
                    var userId = jwtService.getUserId(token);
                    var auth = new UsernamePasswordAuthenticationToken(
                            userId, null, List.of());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } catch (Exception ignored) {
                    // 유효하지 않은 토큰 — SecurityContext 비움 → 401
                }
            }

            filterChain.doFilter(request, response);
        }
    }
}
