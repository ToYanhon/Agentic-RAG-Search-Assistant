package com.clouddrive.auth;

import com.clouddrive.common.ApiException;
import com.clouddrive.common.Errors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

	private UserRepository users;

	private TokenService tokens;

	private Blacklist blacklist;

	private ProfileCache cache;

	private PasswordHasher passwords;

	private AuthService service;

	@BeforeEach
	void setUp() {
		users = mock(UserRepository.class);
		tokens = mock(TokenService.class);
		blacklist = mock(Blacklist.class);
		cache = mock(ProfileCache.class);
		passwords = mock(PasswordHasher.class);
		service = new AuthService(users, tokens, blacklist, cache, passwords);
	}

	private User user() {
		return new User(1, "alice", "alice@example.com", "hash", 0, 1_073_741_824L,
				java.time.LocalDateTime.of(2026, 8, 16, 12, 0, 0));
	}

	@Test
	void registerSuccess() {
		when(users.findByUsername("alice")).thenThrow(new Errors.NotFound("user not found"));
		when(users.findByEmail("alice@example.com")).thenThrow(new Errors.NotFound("user not found"));
		when(passwords.hash("secret1")).thenReturn("hash");
		service.register("alice", "alice@example.com", "secret1");
		verify(users).create("alice", "alice@example.com", "hash");
	}

	@Test
	void registerUsernameTaken() {
		when(users.findByUsername("alice")).thenReturn(user());
		assertThrows(Errors.UsernameTaken.class, () -> service.register("alice", "b@b.com", "secret1"));
	}

	@Test
	void registerEmailTaken() {
		when(users.findByUsername("bob")).thenThrow(new Errors.NotFound("user not found"));
		when(users.findByEmail("alice@example.com")).thenReturn(user());
		assertThrows(Errors.EmailTaken.class, () -> service.register("bob", "alice@example.com", "secret1"));
	}

	@Test
	void loginSuccess() {
		when(users.findByUsername("alice")).thenReturn(user());
		when(passwords.matches("secret1", "hash")).thenReturn(true);
		when(tokens.create(1, "alice")).thenReturn("jwt-token");
		AuthService.LoginResult result = service.login("alice", "secret1");
		assertEquals("jwt-token", result.token());
		assertEquals(1, result.user().id());
	}

	@Test
	void loginInvalidCredentials() {
		when(users.findByUsername("alice")).thenReturn(user());
		when(passwords.matches("wrong", "hash")).thenReturn(false);
		assertThrows(Errors.InvalidCredentials.class, () -> service.login("alice", "wrong"));
	}

	@Test
	void loginUnknownUser() {
		when(users.findByUsername("nobody")).thenThrow(new Errors.NotFound("user not found"));
		assertThrows(Errors.InvalidCredentials.class, () -> service.login("nobody", "x"));
	}

	@Test
	void logoutBlacklistsJti() {
		Claims claims = new Claims(1, "alice", "jti-123", Instant.now().plusSeconds(3600));
		when(tokens.parse("jwt-token")).thenReturn(claims);
		service.logout("jwt-token");
		ArgumentCaptor<Duration> captor = ArgumentCaptor.forClass(Duration.class);
		verify(blacklist).add(eq("jti-123"), captor.capture());
		assertTrue(captor.getValue().getSeconds() > 0);
	}

	@Test
	void logoutRejectsMalformed() {
		when(tokens.parse("bad")).thenThrow(new Errors.TokenInvalid("unauthorized"));
		assertThrows(Errors.TokenInvalid.class, () -> service.logout("bad"));
	}

	@Test
	void authenticateRejectsRevoked() {
		Claims claims = new Claims(1, "alice", "jti-123", Instant.now().plusSeconds(3600));
		when(tokens.parse("jwt-token")).thenReturn(claims);
		when(blacklist.contains("jti-123")).thenReturn(true);
		assertThrows(Errors.TokenInvalid.class, () -> service.authenticate("jwt-token"));
	}

	@Test
	void profileUsesCacheThenDB() {
		Profile cached = new Profile(1, "alice", "alice@example.com", 0, 1_073_741_824L, "2026-08-16T12:00:00Z");
		when(cache.get(1)).thenReturn(cached);
		assertEquals(cached, service.profile(1));
		verify(users, never()).findById(1);
	}

	@Test
	void profileFetchesAndCachesOnMiss() {
		when(cache.get(1)).thenReturn(null);
		when(users.findById(1)).thenReturn(user());
		Profile profile = service.profile(1);
		assertEquals("alice", profile.username());
		verify(cache).set(any(Profile.class), any(Duration.class));
	}

	@Test
	void updateUsernameChecksConflict() {
		when(users.findByUsername("bob")).thenReturn(new User(2, "bob", "b@b.com", "h", 0, 0, null));
		assertThrows(Errors.UsernameTaken.class, () -> service.updateUsername(1, "bob"));
	}

	@Test
	void updateUsernameBlank() {
		assertThrows(ApiException.class, () -> service.updateUsername(1, "   "));
	}

	@Test
	void changePasswordVerifiesOld() {
		when(users.findById(1)).thenReturn(user());
		when(passwords.matches("old", "hash")).thenReturn(false);
		assertThrows(Errors.WrongPassword.class, () -> service.changePassword(1, "old", "newpass1"));
	}

	@Test
	void changePasswordSuccess() {
		when(users.findById(1)).thenReturn(user());
		when(passwords.matches("old", "hash")).thenReturn(true);
		when(passwords.hash("newpass1")).thenReturn("new-hash");
		service.changePassword(1, "old", "newpass1");
		verify(users).updatePassword(1, "new-hash");
	}

}