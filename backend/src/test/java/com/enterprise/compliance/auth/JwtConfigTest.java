package com.enterprise.compliance.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import static org.junit.jupiter.api.Assertions.*;

class JwtConfigTest {
    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
    private final JwtConfig config = new JwtConfig();
    private final javax.crypto.SecretKey key = config.jwtKey(Base64.getEncoder().encodeToString(new byte[32]));
    private final JwtEncoder encoder = config.jwtEncoder(key);
    private final JwtDecoder decoder = config.jwtDecoder(key, Clock.fixed(NOW, ZoneOffset.UTC), "test-issuer");

    private String token(Instant issuedAt, Instant expiresAt) {
        var claims = JwtClaimsSet.builder().issuer("test-issuer").subject("1").id("test-session");
        if (issuedAt != null) claims.issuedAt(issuedAt);
        if (expiresAt != null) claims.expiresAt(expiresAt);
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }

    @Test
    void acceptsValidClaimTimes() {
        assertEquals("1", decoder.decode(token(NOW.minusSeconds(60), NOW.plusSeconds(60))).getSubject());
    }

    @Test
    void rejectsMissingClaimTimes() {
        assertThrows(JwtValidationException.class, () -> decoder.decode(token(null, NOW.plusSeconds(60))));
        assertThrows(JwtValidationException.class, () -> decoder.decode(token(NOW.minusSeconds(60), null)));
    }

    @Test
    void rejectsFutureIssueTimeAndExpiredToken() {
        assertThrows(JwtValidationException.class, () -> decoder.decode(token(NOW.plusSeconds(10), NOW.plusSeconds(60))));
        assertThrows(JwtValidationException.class, () -> decoder.decode(token(NOW.minusSeconds(60), NOW.minusSeconds(10))));
    }
}
