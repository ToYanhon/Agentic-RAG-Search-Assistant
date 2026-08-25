package com.clouddrive.file;

/**
 * 对象 key 生成端口，对应 Go file.KeyGenerator。
 */
public interface KeyGenerator {

	String newKey(long ownerId);

}