package com.sumitdocmind;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    SecurityFilterChain filter(HttpSecurity http, JwtFilter jwt,
                               @Value("${app.jwt-enabled:false}") boolean jwtEnabled) throws Exception {
        var auth = http.csrf(c -> c.disable())
            .cors(c -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (jwtEnabled) {
            auth.authorizeHttpRequests(a -> a
                .requestMatchers(
                    "/api/auth/**",
                    "/",
                    "/index.html",
                    "/favicon.ico",
                    "/v3/api-docs/**",
                    "/swagger-ui/**",
                    "/swagger-ui.html"
                ).permitAll()
                .anyRequest().authenticated());
        } else {
            // JWT disabled (default): all paths open, JwtFilter injects the default user.
            auth.authorizeHttpRequests(a -> a.anyRequest().permitAll());
        }

        return auth
            .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}