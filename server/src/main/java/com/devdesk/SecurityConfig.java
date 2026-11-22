package com.devdesk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    UserDetailsService users(@Value("${devdesk.owner.username}") String username,
                             @Value("${devdesk.owner.password}") String password,
                             PasswordEncoder passwordEncoder) {
        if (password.length() < 16 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new IllegalStateException("OWNER_PASSWORD must be at least 16 characters and at most 72 UTF-8 bytes");
        return new InMemoryUserDetailsManager(User.withUsername(username)
            .password(passwordEncoder.encode(password)).roles("OWNER").build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http
            .authorizeHttpRequests(auth -> auth.requestMatchers("/health", "/login", "/error").permitAll()
                .anyRequest().hasRole("OWNER"))
            .formLogin(form -> form.defaultSuccessUrl("/devdesk.html", true).permitAll())
            .logout(logout -> logout.logoutSuccessUrl("/login?logout"))
            .exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), new AntPathRequestMatcher("/api/**")))
            .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")))
            .build();
    }
}
