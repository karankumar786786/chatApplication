package me.one_org.chatControlePlane.filtures;

import com.nimbusds.jose.JOSEException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.one_org.chatControlePlane.dtos.DecodedTokenPayload;
import me.one_org.chatControlePlane.enums.TokenType;
import me.one_org.chatControlePlane.services.BlacklistService;
import me.one_org.chatControlePlane.utils.JsonWebToken;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class AuthFilture extends OncePerRequestFilter {
    private final JsonWebToken jsonWebToken;
    private final BlacklistService blacklistService;

    public AuthFilture(JsonWebToken jsonWebToken, BlacklistService blacklistService) {
        this.jsonWebToken = jsonWebToken;
        this.blacklistService = blacklistService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                DecodedTokenPayload verify = jsonWebToken.verify(token, TokenType.ACCESS_TOKEN);
                
                if (!blacklistService.isBlacklisted(verify.jwtId())) {
                    SecurityContext context = SecurityContextHolder.getContext();
                    if (context.getAuthentication() == null) {
                        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_USER");
                        UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(verify, null, authorities);
                        authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        context.setAuthentication(authenticationToken);
                    }
                }
            } catch (JOSEException e) {
                // Not authenticated, continue filter chain
            }
        }
        filterChain.doFilter(request, response);
    }
}

