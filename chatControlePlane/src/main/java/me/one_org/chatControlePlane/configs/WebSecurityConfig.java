package me.one_org.chatControlePlane.configs;

import me.one_org.chatControlePlane.filtures.AuthFilture;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

@Configuration
@EnableWebSecurity
public class WebSecurityConfig {

    private final AuthFilture authFilture;

    public WebSecurityConfig(AuthFilture authFilture) {
        this.authFilture = authFilture;
    }

    @Bean
    SecurityFilterChain httpSecurity(HttpSecurity req) throws Exception {
        req
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/logout").authenticated()
                        .requestMatchers("/auth/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(authFilture, UsernamePasswordAuthenticationFilter.class);
        return req.build();
    }
}
