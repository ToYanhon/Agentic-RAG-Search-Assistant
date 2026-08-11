package com.clouddrive.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.clouddrive.common.Resp;
import com.clouddrive.dto.LoginResponse;
import com.clouddrive.dto.StorageUsage;
import com.clouddrive.dto.UserProfile;
import com.clouddrive.service.AuthService;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 认证接口（对齐 Go auth_handler）：register/login/logout 公开，其余需 JWT。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Data
    public static class RegisterReq {
        @NotBlank
        @Size(min = 3, max = 64)
        private String username;
        @NotBlank
        @Email
        private String email;
        @NotBlank
        @Size(min = 6)
        private String password;
    }

    @Data
    public static class LoginReq {
        @NotBlank
        private String username;
        @NotBlank
        private String password;
    }

    @Data
    public static class UpdateProfileReq {
        @Size(min = 3, max = 64)
        private String username;
    }

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class ChangePasswordReq {
        @NotBlank
        private String oldPassword;
        @NotBlank
        @Size(min = 6)
        private String newPassword;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Resp<Map<String, String>> register(@Valid @RequestBody RegisterReq req) {
        authService.register(req.getUsername(), req.getEmail(), req.getPassword());
        return Resp.created(Map.of("message", "register success"));
    }

    @PostMapping("/login")
    public Resp<LoginResponse> login(@Valid @RequestBody LoginReq req) {
        var result = authService.login(req.getUsername(), req.getPassword());
        return Resp.ok(new LoginResponse(result.token(), result.profile()));
    }

    @PostMapping("/logout")
    public Resp<Map<String, String>> logout(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        String token = (auth != null && auth.startsWith("Bearer ")) ? auth.substring(7) : "";
        authService.logout(token);
        return Resp.ok(Map.of("message", "logged out"));
    }

    @GetMapping("/profile")
    public Resp<UserProfile> profile(HttpServletRequest request) {
        return Resp.ok(authService.getProfile(userId(request)));
    }

    @GetMapping("/storage/usage")
    public Resp<StorageUsage> storageUsage(HttpServletRequest request) {
        UserProfile profile = authService.getProfile(userId(request));
        return Resp.ok(new StorageUsage(profile.getStorageUsed(), profile.getStorageLimit()));
    }

    @PutMapping("/profile")
    public Resp<UserProfile> updateProfile(HttpServletRequest request,
                                           @Valid @RequestBody UpdateProfileReq req) {
        return Resp.ok(authService.updateProfile(userId(request), req.getUsername()));
    }

    @PutMapping("/password")
    public Resp<Map<String, String>> changePassword(HttpServletRequest request,
                                                    @Valid @RequestBody ChangePasswordReq req) {
        authService.changePassword(userId(request), req.getOldPassword(), req.getNewPassword());
        return Resp.ok(Map.of("message", "password changed"));
    }

    private Long userId(HttpServletRequest request) {
        return (Long) request.getAttribute("user_id");
    }
}
