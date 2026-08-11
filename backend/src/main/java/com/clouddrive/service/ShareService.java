package com.clouddrive.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.clouddrive.common.AppException;
import com.clouddrive.entity.Share;
import com.clouddrive.repository.ShareRepository;

/**
 * 分享业务（对齐 Go share_handler）：创建/删除/按 token 解析（Redis 缓存，TTL=min(5min,剩余)）。
 */
@Service
public class ShareService {

    private static final Duration SHARE_CACHE_TTL = Duration.ofMinutes(5);

    private final ShareRepository shareRepo;
    private final CacheService cache;
    private final FileService fileService;
    private final SecureRandom random = new SecureRandom();

    public ShareService(ShareRepository shareRepo, CacheService cache, FileService fileService) {
        this.shareRepo = shareRepo;
        this.cache = cache;
        this.fileService = fileService;
    }

    @Transactional
    public Share create(Long userId, Long fileId, Integer expireHrs) {
        var file = fileService.getFileById(fileId);
        if (!file.getOwnerId().equals(userId)) {
            throw AppException.forbidden("access denied");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        Share share = new Share();
        share.setFileId(fileId);
        share.setOwnerId(userId);
        share.setToken(HexFormat.of().formatHex(bytes));
        if (expireHrs != null && expireHrs > 0) {
            share.setExpiredAt(LocalDateTime.now().plusHours(expireHrs));
        }
        return shareRepo.save(share);
    }

    /** 解析分享：缓存命中/DB 兜底 + 到期校验（含缓存残留兜底）。 */
    public Share getShare(String token) {
        Share share = cache.get(shareKey(token), Share.class).orElse(null);
        if (share == null) {
            share = shareRepo.findByToken(token)
                    .orElseThrow(() -> AppException.notFound("share not found"));
            Duration ttl = SHARE_CACHE_TTL;
            if (share.getExpiredAt() != null) {
                Duration remaining = Duration.between(LocalDateTime.now(), share.getExpiredAt());
                if (remaining.isZero() || remaining.isNegative()) {
                    throw AppException.notFound("share expired");
                }
                if (remaining.compareTo(ttl) < 0) {
                    ttl = remaining;
                }
            }
            cache.set(shareKey(token), share, ttl);
        }
        // 兜底：缓存可能残留到期分享
        if (share.getExpiredAt() != null && share.getExpiredAt().isBefore(LocalDateTime.now())) {
            cache.del(shareKey(token));
            throw AppException.notFound("share expired");
        }
        return share;
    }

    @Transactional
    public void delete(Long shareId, Long userId) {
        Share share = shareRepo.findByIdAndOwnerId(shareId, userId)
                .orElseThrow(() -> AppException.notFound("share not found"));
        shareRepo.deleteById(shareId);
        cache.del(shareKey(share.getToken()));
    }

    private static String shareKey(String token) {
        return "share:" + token;
    }
}
