package com.daf360.payroll.security;

import com.daf360.payroll.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {

    private final AppProperties appProperties;
    private RSAPublicKey rsaPublicKey;

    @PostConstruct
    void init() {
        if (StringUtils.hasText(appProperties.getJwtPublicKeyPath())) {
            try {
                String pem = Files.readString(Path.of(appProperties.getJwtPublicKeyPath()));
                String keyContent = pem
                        .replace("-----BEGIN PUBLIC KEY-----", "")
                        .replace("-----END PUBLIC KEY-----", "")
                        .replaceAll("\\s", "");
                byte[] keyBytes = Base64.getDecoder().decode(keyContent);
                KeyFactory kf = KeyFactory.getInstance("RSA");
                rsaPublicKey = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(keyBytes));
                log.info("JwtService: RSA public key loaded from {}", appProperties.getJwtPublicKeyPath());
            } catch (Exception e) {
                log.warn("JwtService: could not load RSA public key — {}", e.getMessage());
            }
        }
        log.info("JwtService initialized: HMAC={}, RSA={}",
                StringUtils.hasText(appProperties.getJwtSecret()), rsaPublicKey != null);
    }

    public Claims parseToken(String token) {
        return isRs256(token) ? parseRs256(token) : parseHmac(token);
    }

    public boolean isTokenValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean isRs256(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return false;
            byte[] headerBytes = Base64.getUrlDecoder().decode(parts[0]);
            String header = new String(headerBytes, StandardCharsets.UTF_8);
            return header.contains("RS256");
        } catch (Exception e) {
            return false;
        }
    }

    private Claims parseHmac(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(
                        appProperties.getJwtSecret().getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Claims parseRs256(String token) {
        if (rsaPublicKey == null) {
            throw new JwtException("RSA public key not configured — set JWT_PUBLIC_KEY_PATH");
        }
        return Jwts.parser()
                .verifyWith(rsaPublicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
