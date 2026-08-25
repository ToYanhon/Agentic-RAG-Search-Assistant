package com.clouddrive.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用配置，对应 Go 后端 internal/config。环境变量 CD_<SECTION>_<FIELD> 经 Spring relaxed binding
 * 映射到本类（如 CD_UPLOAD_DIRECT_MAX_BYTES -> cd.upload-direct-max-bytes）。
 */
@ConfigurationProperties(prefix = "cd")
public class AppProperties {

	private int serverPort = 8080;

	private String mysqlDsn = "jdbc:mysql://localhost:3306/cloud_drive?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";

	private String redisAddr = "localhost:6379";

	private String redisPassword = "";

	private String jwtSecret = "change-me-in-production";

	private int jwtExpireHours = 72;

	private String minioEndpoint = "localhost:9100";

	private String minioAccessKey = "minioadmin";

	private String minioSecretKey = "minioadmin";

	private String minioBucket = "cloud-drive";

	private long uploadDirectMaxBytes = 52_428_800L;

	private long uploadChunkMaxBytes = 10_485_760L;

	private long uploadFileMaxBytes = 10_737_418_240L;

	private String agentBaseUrl = "http://127.0.0.1:8000";

	private int agentResponseHeaderTimeoutSec = 60;

	private int agentMaxConcurrent = 20;

	private String llmEncryptionKey = "dev-only-llm-encryption-key-change-me-32b";

	public int getServerPort() {
		return serverPort;
	}

	public void setServerPort(int serverPort) {
		this.serverPort = serverPort;
	}

	public String getMysqlDsn() {
		return mysqlDsn;
	}

	public void setMysqlDsn(String mysqlDsn) {
		this.mysqlDsn = mysqlDsn;
	}

	public String getRedisAddr() {
		return redisAddr;
	}

	public void setRedisAddr(String redisAddr) {
		this.redisAddr = redisAddr;
	}

	public String getRedisPassword() {
		return redisPassword;
	}

	public void setRedisPassword(String redisPassword) {
		this.redisPassword = redisPassword;
	}

	public String getJwtSecret() {
		return jwtSecret;
	}

	public void setJwtSecret(String jwtSecret) {
		this.jwtSecret = jwtSecret;
	}

	public int getJwtExpireHours() {
		return jwtExpireHours;
	}

	public void setJwtExpireHours(int jwtExpireHours) {
		this.jwtExpireHours = jwtExpireHours;
	}

	public String getMinioEndpoint() {
		return minioEndpoint;
	}

	public void setMinioEndpoint(String minioEndpoint) {
		this.minioEndpoint = minioEndpoint;
	}

	public String getMinioAccessKey() {
		return minioAccessKey;
	}

	public void setMinioAccessKey(String minioAccessKey) {
		this.minioAccessKey = minioAccessKey;
	}

	public String getMinioSecretKey() {
		return minioSecretKey;
	}

	public void setMinioSecretKey(String minioSecretKey) {
		this.minioSecretKey = minioSecretKey;
	}

	public String getMinioBucket() {
		return minioBucket;
	}

	public void setMinioBucket(String minioBucket) {
		this.minioBucket = minioBucket;
	}

	public long getUploadDirectMaxBytes() {
		return uploadDirectMaxBytes;
	}

	public void setUploadDirectMaxBytes(long uploadDirectMaxBytes) {
		this.uploadDirectMaxBytes = uploadDirectMaxBytes;
	}

	public long getUploadChunkMaxBytes() {
		return uploadChunkMaxBytes;
	}

	public void setUploadChunkMaxBytes(long uploadChunkMaxBytes) {
		this.uploadChunkMaxBytes = uploadChunkMaxBytes;
	}

	public long getUploadFileMaxBytes() {
		return uploadFileMaxBytes;
	}

	public void setUploadFileMaxBytes(long uploadFileMaxBytes) {
		this.uploadFileMaxBytes = uploadFileMaxBytes;
	}

	public String getAgentBaseUrl() {
		return agentBaseUrl;
	}

	public void setAgentBaseUrl(String agentBaseUrl) {
		this.agentBaseUrl = agentBaseUrl;
	}

	public int getAgentResponseHeaderTimeoutSec() {
		return agentResponseHeaderTimeoutSec;
	}

	public void setAgentResponseHeaderTimeoutSec(int agentResponseHeaderTimeoutSec) {
		this.agentResponseHeaderTimeoutSec = agentResponseHeaderTimeoutSec;
	}

	public int getAgentMaxConcurrent() {
		return agentMaxConcurrent;
	}

	public void setAgentMaxConcurrent(int agentMaxConcurrent) {
		this.agentMaxConcurrent = agentMaxConcurrent;
	}

	public String getLlmEncryptionKey() {
		return llmEncryptionKey;
	}

	public void setLlmEncryptionKey(String llmEncryptionKey) {
		this.llmEncryptionKey = llmEncryptionKey;
	}

}
