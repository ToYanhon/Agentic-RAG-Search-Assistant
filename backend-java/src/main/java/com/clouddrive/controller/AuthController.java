package com.clouddrive.controller;

import com.clouddrive.auth.AuthService;
import com.clouddrive.auth.Profile;
import com.clouddrive.common.ApiException;
import com.clouddrive.common.Envelope;
import com.clouddrive.common.ErrorCode;
import com.clouddrive.common.Errors;
import com.clouddrive.web.UserContext;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证控制器，对应 Go httpapi/auth.go。
 */
@RestController
public class AuthController {

	private final AuthService service;

	public AuthController(AuthService service) {
		this.service = service;
	}

	@GetMapping("/health")
	public Map<String, String> health() {
		return Map.of("status", "ok");
	}

	public record RegisterRequest(String username, String email, String password) {
	}

	@PostMapping("/api/v1/auth/register")
	public ResponseEntity<Envelope<Map<String, String>>> register(@RequestBody RegisterRequest request) {
		String username = request.username() == null ? "" : request.username();
		String email = request.email() == null ? "" : request.email();
		String password = request.password() == null ? "" : request.password();
		if (username.trim().length() < 3 || username.length() > 64 || !validEmail(email)
				|| password.trim().length() < 6) {
			throw ApiException.badRequest("validation failed");
		}
		service.register(username, email, password);
		return ResponseEntity.status(HttpStatus.CREATED).body(Envelope.created(Map.of("message", "register success")));
	}

	public record LoginRequest(String username, String password) {
	}

	@PostMapping("/api/v1/auth/login")
	public Envelope<Map<String, Object>> login(@RequestBody LoginRequest request) {
		String username = request.username() == null ? "" : request.username();
		String password = request.password() == null ? "" : request.password();
		if (username.trim().isEmpty() || password.trim().isEmpty()) {
			throw ApiException.badRequest("validation failed");
		}
		AuthService.LoginResult result = service.login(username, password);
		return Envelope.ok(Map.of("token", result.token(), "user", (Object) result.user()));
	}

	@PostMapping("/api/v1/auth/logout")
	public Envelope<Map<String, String>> logout(@org.springframework.web.bind.annotation.RequestHeader(
			value = "Authorization", required = false) String authorization) {
		String raw = bearer(authorization);
		try {
			service.logout(raw);
		}
		catch (Errors.TokenInvalid e) {
			throw new ApiException(401, ErrorCode.UNAUTHORIZED, "unauthorized");
		}
		return Envelope.ok(Map.of("message", "logged out"));
	}

	@GetMapping("/api/v1/auth/profile")
	public Envelope<Profile> profile() {
		return Envelope.ok(service.profile(UserContext.userId()));
	}

	public record UpdateProfileRequest(String username) {
	}

	@PutMapping("/api/v1/auth/profile")
	public Envelope<Profile> updateProfile(@RequestBody UpdateProfileRequest request) {
		String username = request.username() == null ? "" : request.username();
		if (username.trim().length() < 3 || username.length() > 64) {
			throw ApiException.badRequest("nothing to update");
		}
		return Envelope.ok(service.updateUsername(UserContext.userId(), username));
	}

	@GetMapping("/api/v1/auth/storage/usage")
	public Envelope<Map<String, Long>> storageUsage() {
		Profile profile = service.profile(UserContext.userId());
		return Envelope.ok(Map.of("storage_used", profile.storageUsed(), "storage_limit", profile.storageLimit()));
	}

	public record PasswordRequest(String oldPassword, String newPassword) {
	}

	@PutMapping("/api/v1/auth/password")
	public Envelope<Map<String, String>> changePassword(@RequestBody PasswordRequest request) {
		String oldPassword = request.oldPassword() == null ? "" : request.oldPassword();
		String newPassword = request.newPassword() == null ? "" : request.newPassword();
		if (oldPassword.trim().isEmpty() || newPassword.trim().length() < 6) {
			throw ApiException.badRequest("validation failed");
		}
		service.changePassword(UserContext.userId(), oldPassword, newPassword);
		return Envelope.ok(Map.of("message", "password changed"));
	}

	private static String bearer(String authorization) {
		if (authorization == null || !authorization.startsWith("Bearer ")) {
			return "";
		}
		return authorization.substring("Bearer ".length());
	}

	private static boolean validEmail(String value) {
		if (value == null) {
			return false;
		}
		try {
			InternetAddress address = new InternetAddress(value);
			address.validate();
			return address.getAddress().equals(value) && value.contains("@");
		}
		catch (AddressException e) {
			return false;
		}
	}

}