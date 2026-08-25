package com.clouddrive.config;

import com.clouddrive.adapter.security.AesGcmCipher;
import com.clouddrive.adapter.security.BcryptHasher;
import com.clouddrive.adapter.security.CryptoRandom;
import com.clouddrive.adapter.security.Hs256JwtService;
import com.clouddrive.auth.PasswordHasher;
import com.clouddrive.auth.RandomHex;
import com.clouddrive.auth.TokenService;
import com.clouddrive.llmconfig.Secret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {

	@Bean
	public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
		return new TransactionTemplate(transactionManager);
	}

	@Bean
	public RandomHex randomHex() {
		return new CryptoRandom();
	}

	@Bean
	public PasswordHasher passwordHasher() {
		return new BcryptHasher();
	}

	@Bean
	public TokenService tokenService(AppProperties properties, RandomHex randomHex) {
		return new Hs256JwtService(properties.getJwtSecret(), Duration.ofHours(properties.getJwtExpireHours()),
				randomHex);
	}

	@Bean
	public Secret llmSecret(AppProperties properties) {
		return new AesGcmCipher(properties.getLlmEncryptionKey());
	}

}