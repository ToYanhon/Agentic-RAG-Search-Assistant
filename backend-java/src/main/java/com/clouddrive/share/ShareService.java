package com.clouddrive.share;

import com.clouddrive.auth.RandomHex;
import com.clouddrive.common.Errors;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 公开分享用例，对应 Go share.Service。
 */
@org.springframework.stereotype.Service
public class ShareService {

	private static final Duration CACHE_TTL = Duration.ofMinutes(5);

	private final Repository shares;

	private final FileAccess files;

	private final Clock clock;

	private final RandomHex tokens;

	private final Cache cache;

	public ShareService(Repository shares, FileAccess files, Clock clock, RandomHex tokens, Cache cache) {
		this.shares = shares;
		this.files = files;
		this.clock = clock;
		this.tokens = tokens;
		this.cache = cache;
	}

	public Record create(long ownerId, long fileId, Integer expireHours) {
		com.clouddrive.file.Record file = files.find(fileId);
		if (file.ownerId() != ownerId) {
			throw new Errors.AccessDenied("access denied");
		}
		String token = tokens.generate(32);
		LocalDateTime expiresAt = null;
		if (expireHours != null && expireHours > 0) {
			expiresAt = clock.now().plusHours(expireHours);
		}
		return shares.create(ownerId, fileId, token, expiresAt);
	}

	public void revoke(long ownerId, long shareId) {
		Record share = shares.findOwned(shareId, ownerId);
		shares.delete(shareId);
		cache.delete(cacheKey(share.token()));
	}

	public com.clouddrive.file.Record access(String token) {
		Record share = validShare(token);
		return files.find(share.fileId());
	}

	public com.clouddrive.file.Download download(String token) {
		com.clouddrive.file.Record file = access(token);
		return new com.clouddrive.file.Download(file, files.get(file.objectKey()));
	}

	public com.clouddrive.file.Download downloadRange(String token, long offset, long length) {
		com.clouddrive.file.Record file = access(token);
		return new com.clouddrive.file.Download(file, files.getRange(file.objectKey(), offset, length));
	}

	private Record validShare(String token) {
		Record share = cache.get(cacheKey(token));
		boolean hit = share != null;
		if (!hit) {
			share = shares.findByToken(token);
			if (expired(share, clock.now())) {
				throw new Errors.ShareNotFound("share not found");
			}
			try {
				cache.set(cacheKey(token), share, ttl(share, clock.now()));
			}
			catch (RuntimeException ignored) {
				// 缓存故障不影响公开分享的 MySQL 权威读取
			}
		}
		if (expired(share, clock.now())) {
			cache.delete(cacheKey(token));
			throw new Errors.ShareNotFound("share not found");
		}
		return share;
	}

	private static String cacheKey(String token) {
		return "share:" + token;
	}

	private static boolean expired(Record share, LocalDateTime now) {
		return share.expiresAt() != null && !share.expiresAt().isAfter(now);
	}

	private static Duration ttl(Record share, LocalDateTime now) {
		if (share.expiresAt() == null) {
			return CACHE_TTL;
		}
		Duration remaining = Duration.between(now, share.expiresAt());
		if (remaining.compareTo(CACHE_TTL) < 0) {
			return remaining;
		}
		return CACHE_TTL;
	}

}