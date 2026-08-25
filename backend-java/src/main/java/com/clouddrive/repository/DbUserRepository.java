package com.clouddrive.repository;

import com.clouddrive.auth.User;
import com.clouddrive.auth.UserRepository;
import com.clouddrive.common.Errors;
import com.clouddrive.repository.entity.UserEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 用户仓储 MySQL 实现，对应 Go db.UserRepository。
 */
@Repository
public class DbUserRepository implements UserRepository {

	private final UserJpa jpa;

	public DbUserRepository(UserJpa jpa) {
		this.jpa = jpa;
	}

	@Override
	public User findById(long id) {
		return jpa.findById(id).map(DbUserRepository::toUser).orElseThrow(() -> new Errors.NotFound("user not found"));
	}

	@Override
	public User findByUsername(String username) {
		return jpa.findByUsername(username)
			.map(DbUserRepository::toUser)
			.orElseThrow(() -> new Errors.NotFound("user not found"));
	}

	@Override
	public User findByEmail(String email) {
		return jpa.findByEmail(email)
			.map(DbUserRepository::toUser)
			.orElseThrow(() -> new Errors.NotFound("user not found"));
	}

	@Override
	public void create(String username, String email, String password) {
		UserEntity entity = new UserEntity();
		entity.setUsername(username);
		entity.setEmail(email);
		entity.setPassword(password);
		entity.setStorageUsed(0);
		entity.setStorageLimit(1073741824L);
		entity.setCreatedAt(LocalDateTime.now());
		entity.setUpdatedAt(LocalDateTime.now());
		try {
			jpa.save(entity);
		}
		catch (DataIntegrityViolationException e) {
			throw duplicate(e, username, email);
		}
	}

	@Override
	public void updateUsername(long id, String username) {
		try {
			if (jpa.updateUsername(id, username) == 0) {
				throw new Errors.NotFound("user not found");
			}
		}
		catch (DataIntegrityViolationException e) {
			throw duplicate(e, username, null);
		}
	}

	@Override
	public void updatePassword(long id, String password) {
		if (jpa.updatePassword(id, password) == 0) {
			throw new Errors.NotFound("user not found");
		}
	}

	@Override
	public Quota remaining(long ownerId) {
		List<Object[]> rows = jpa.findStorage(ownerId);
		if (rows.isEmpty()) {
			return new Quota(0, false);
		}
		Object[] row = rows.get(0);
		long used = ((Number) row[0]).longValue();
		long limit = ((Number) row[1]).longValue();
		return new Quota(limit - used, true);
	}

	private static User toUser(UserEntity e) {
		return new User(e.getId(), e.getUsername(), e.getEmail(), e.getPassword(), e.getStorageUsed(),
				e.getStorageLimit(), e.getCreatedAt());
	}

	private static RuntimeException duplicate(DataIntegrityViolationException e, String username, String email) {
		String message = e.getMostSpecificCause().getMessage();
		if (message == null) {
			message = "";
		}
		String lower = message.toLowerCase(Locale.ROOT);
		if (lower.contains("email") || (email != null && lower.contains(email.toLowerCase(Locale.ROOT)))) {
			return new Errors.EmailTaken("email already exists");
		}
		if (lower.contains("username") || (username != null && lower.contains(username.toLowerCase(Locale.ROOT)))) {
			return new Errors.UsernameTaken("username already exists");
		}
		return new Errors.DuplicateUser("duplicate user");
	}

}