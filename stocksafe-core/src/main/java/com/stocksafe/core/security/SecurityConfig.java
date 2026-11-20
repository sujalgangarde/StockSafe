package com.stocksafe.core.security;

import com.stocksafe.core.apikey.ApiKeyFilter;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity   // enables @PreAuthorize
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtService jwt, JdbcTemplate jdbc) throws Exception {
        http.csrf(c -> c.disable())   // stateless API, no cookies
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/v1/auth/**", "/actuator/health/**", "/actuator/prometheus", "/error").permitAll()
                // Webhook: checked by Razorpay signature. Admin: checked by X-Admin-Key.
                .requestMatchers("/api/v1/billing/webhook", "/api/v1/admin/**").permitAll()
                .requestMatchers("/api/v1/public/**").hasRole("API")
                .anyRequest().hasAnyRole("OWNER", "MANAGER", "STAFF"))
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(new JwtTenantFilter(jwt), UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new ApiKeyFilter(jdbc), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${stocksafe.cors.allowed-origins}") String origins) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).toList());
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setExposedHeaders(List.of("X-Correlation-Id", "Content-Disposition"));
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return src;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
