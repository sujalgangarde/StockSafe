package com.stocksafe.core.auth;

import com.stocksafe.core.auth.AuthDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse signup(@Valid @RequestBody SignupRequest r) {
        return service.signup(r);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest r) {
        return service.login(r);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest r) {
        return service.refresh(r);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest r) {
        service.logout(r);
    }
}
