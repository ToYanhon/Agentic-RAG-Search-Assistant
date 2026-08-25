package com.clouddrive.auth;

/**
 * JWT 签发与解析端口，对应 Go auth.TokenService。
 */
public interface TokenService {

	String create(long userId, String username);

	Claims parse(String raw);

}