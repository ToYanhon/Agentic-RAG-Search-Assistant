package com.clouddrive.service;

import java.time.Duration;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.clouddrive.common.AppException;
import com.clouddrive.dto.UserProfile;
import com.clouddrive.entity.User;
import com.clouddrive.repository.UserRepository;
import com.clouddrive.security.JwtService;
import com.clouddrive.security.TokenBlacklist;

/**
 * 认证业务（对齐 Go authService）：bcrypt、HS256 JWT + jti 黑名单、profile 缓存（5min）。
 */
@Service
public class AuthService {

    private static final Duration PROFILE_CACHE_TTL = Duration.ofMinutes(5);

    private final UserRepository userRepo;
    private final JwtService jwt;
    private final TokenBlacklist blacklist;
    private final CacheService cache;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepo, JwtService jwt,
                       TokenBlacklist blacklist, CacheService cache) {
        this.userRepo = userRepo;
        this.jwt = jwt;
        this.blacklist = blacklist;
        this.cache = cache;
    }

    @Transactional
    public void register(String username, String email, String password) {
        if (userRepo.findByUsername(username).isPresent()) {
            throw AppException.usernameTaken("username already exists");
        }
        if (userRepo.findByEmail(email).isPresent()) {
            throw AppException.emailTaken("email already exists");
        }
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(encoder.encode(password));
        userRepo.save(user);
    }

    /** 登录成功返回 (JWT, profile)。 */
    public LoginResult login(String username, String password) {
        User user = userRepo.findByUsername(username)
                .orElseThrow(() -> AppException.invalidCreds("invalid username or password"));
        if (!encoder.matches(password, user.getPassword())) {
            throw AppException.invalidCreds("invalid username or password");
        }
        return new LoginResult(jwt.create(user.getId(), user.getUsername()), UserProfile.from(user));
    }

    /** 登录结果。 */
    public record LoginResult(String token, UserProfile profile) {
    }

    public void logout(String tokenStr) {
        if (tokenStr == null || tokenStr.isEmpty()) {
            throw AppException.unauthorized("unauthorized");
        }
        JwtService.TokenData td;
        try {
            td = jwt.parse(tokenStr);
        } catch (Exception e) {
            throw AppException.unauthorized("unauthorized");
        }
        if (td.jti() == null || td.jti().isEmpty()) {
            throw AppException.unauthorized("unauthorized");
        }
        blacklist.add(td.jti(), td.expiresAtSeconds());
    }

    /** 用户资料：缓存优先（DTO），未命中查库回填。 */
    public UserProfile getProfile(Long userId) {
        String key = profileKey(userId);
        var cached = cache.get(key, UserProfile.class);
        if (cached.isPresent()) {
            return cached.get();
        }
        User user = userRepo.findById(userId)
                .orElseThrow(() -> AppException.notFound("user not found"));
        UserProfile profile = UserProfile.from(user);
        cache.set(key, profile, PROFILE_CACHE_TTL);
        return profile;
    }

    @Transactional
    public UserProfile updateProfile(Long userId, String username) {
        if (username == null || username.isBlank()) {
            throw AppException.badRequest("nothing to update");
        }
        userRepo.findByUsername(username).ifPresent(existing -> {
            if (!existing.getId().equals(userId)) {
                throw AppException.usernameTaken("username already exists");
            }
        });
        userRepo.updateUsername(userId, username);
        User user = userRepo.findById(userId)
                .orElseThrow(() -> AppException.internal("user update failed"));
        cache.del(profileKey(userId));
        return UserProfile.from(user);
    }

    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> AppException.notFound("user not found"));
        if (!encoder.matches(oldPassword, user.getPassword())) {
            throw AppException.badRequest("wrong password");
        }
        userRepo.updatePassword(userId, encoder.encode(newPassword));
        cache.del(profileKey(userId));
    }

    private String profileKey(Long userId) {
        return "user_profile:" + userId;
    }
}
