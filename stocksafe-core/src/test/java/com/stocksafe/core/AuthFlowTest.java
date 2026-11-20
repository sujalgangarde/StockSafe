package com.stocksafe.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

@SuppressWarnings({"rawtypes", "unchecked"})
class AuthFlowTest extends AbstractIntegrationTest {

    @Test
    void signup_login_me_and_refresh_rotation() {
        String email = email();
        Map s = signupRaw(email, "Flow Store").getBody();

        assertThat(signupRaw(email, "Again").getStatusCode().value()).isEqualTo(409);

        assertThat(rest.postForEntity("/api/v1/auth/login",
                Map.of("email", email, "password", "wrong-pass"), Map.class).getStatusCode().value()).isEqualTo(401);
        ResponseEntity<Map> login = rest.postForEntity("/api/v1/auth/login",
                Map.of("email", email, "password", "Passw0rd!"), Map.class);
        assertThat(login.getStatusCode().value()).isEqualTo(200);

        Map me = rest.exchange("/api/v1/me", HttpMethod.GET,
                new HttpEntity<>(auth(token(s))), Map.class).getBody();
        assertThat(me.get("plan")).isEqualTo("free");
        assertThat(me.get("role")).isEqualTo("OWNER");

        String refresh = (String) s.get("refreshToken");
        assertThat(rest.postForEntity("/api/v1/auth/refresh", Map.of("refreshToken", refresh), Map.class)
                .getStatusCode().value()).isEqualTo(200);
        assertThat(rest.postForEntity("/api/v1/auth/refresh", Map.of("refreshToken", refresh), Map.class)
                .getStatusCode().value()).isEqualTo(401);
    }
}
