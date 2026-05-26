package com.reconciliation.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtConfig jwtConfig;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            if (jwtConfig.isValid(token)) {
                String type = jwtConfig.extractType(token);

                if ("merchant".equals(type)) {
                    String merchantId = jwtConfig.extractMerchantId(token);
                    request.setAttribute("merchantId", merchantId);
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(merchantId, null, List.of());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } else {
                    // admin/ops token — subject is email
                    String email = jwtConfig.extractEmail(token);
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(email, null, List.of());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
