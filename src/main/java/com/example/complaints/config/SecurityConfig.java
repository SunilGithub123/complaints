package com.example.complaints.config;

import com.example.complaints.auth.security.JwtAuthFilter;
import com.example.complaints.auth.security.PasswordResetRequiredFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security wiring for v1.
 *
 * <p>Filter chain (in order):</p>
 * <ol>
 *   <li>{@link JwtAuthFilter} — turns a {@code Bearer} access token into an {@code AuthenticatedStaff}.</li>
 *   <li>{@link PasswordResetRequiredFilter} — blocks every staff route except a small allow-list
 *       until {@code passwordResetRequired = false}.</li>
 *   <li>Default Spring Security authorization filter.</li>
 * </ol>
 *
 * <p>{@code ConsumerVerificationFilter} lands in the {@code consumer} module (Phase 3).</p>
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);   // strength 12 per TD 6
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthFilter jwtAuthFilter,
            PasswordResetRequiredFilter pwdResetFilter
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/api/v1/staff/auth/login",
                                "/api/v1/staff/auth/refresh",
                                "/api/v1/consumer/**"            // gated by ConsumerVerificationFilter (Phase 3)
                        ).permitAll()
                        .requestMatchers("/api/v1/staff/**").authenticated()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/engineer/**").hasRole("ENGINEER")
                        .requestMatchers("/api/v1/technician/**").hasRole("TECHNICIAN")
                        .anyRequest().permitAll()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(pwdResetFilter, JwtAuthFilter.class);
        return http.build();
    }
}
