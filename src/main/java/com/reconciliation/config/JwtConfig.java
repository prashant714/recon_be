package com.reconciliation.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import java.security.Key;
import java.util.Date;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class JwtConfig {

    @Value("${app.jwt.secret:secret-key-must-be-at-least-32-chars-long-for-hs256}")
    private String jwtSecret;

    @Value("${app.jwt.expiry-hours:24}")
    private int expiryHours;

    private Key getKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    /** Admin/ops token — subject is email, type=admin. */
    public String generateToken(String email) {
        return Jwts.builder()
                .setSubject(email)
                .claim("type", "admin")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + (long) expiryHours * 3600 * 1000))
                .signWith(getKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /** Merchant token — subject is merchantId, type=merchant. */
    public String generateMerchantToken(String merchantId) {
        return Jwts.builder()
                .setSubject(merchantId)
                .claim("type", "merchant")
                .claim("merchantId", merchantId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + (long) expiryHours * 3600 * 1000))
                .signWith(getKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractEmail(String token) {
        return parseClaims(token).getSubject();
    }

    public String extractMerchantId(String token) {
        Claims claims = parseClaims(token);
        return claims.get("merchantId", String.class);
    }

    public String extractType(String token) {
        Claims claims = parseClaims(token);
        return claims.get("type", String.class);
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .setSigningKey(getKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
