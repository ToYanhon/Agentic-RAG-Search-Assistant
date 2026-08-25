package com.clouddrive.llmconfig;

/**
 * 密钥加密端口，对应 Go llmconfig.Secret（AES-GCM，v1: 前缀）。
 */
public interface Secret {

	String encrypt(String plain);

	String decrypt(String cipher);

}