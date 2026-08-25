package com.clouddrive.auth;

/**
 * 随机 hex 生成器端口，对应 Go auth.RandomHex。
 */
public interface RandomHex {

	String generate(int bytes);

}