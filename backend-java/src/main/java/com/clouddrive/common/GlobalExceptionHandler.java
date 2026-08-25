package com.clouddrive.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常映射，对应 Go httpapi 各 write*Error 的默认分支。未知异常不泄漏内部细节。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<Envelope<Void>> api(ApiException e) {
		return ResponseEntity.status(e.getStatus()).body(Envelope.error(e.getStatus(), e.getCode(), e.getMessage()));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<Envelope<Void>> unreadable(HttpMessageNotReadableException e) {
		return badRequest("invalid request body");
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<Envelope<Void>> missingParam(MissingServletRequestParameterException e) {
		return badRequest("validation failed");
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<Envelope<Void>> typeMismatch(MethodArgumentTypeMismatchException e) {
		return badRequest("invalid id");
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<Envelope<Void>> uploadTooLarge(MaxUploadSizeExceededException e) {
		return ResponseEntity.status(413)
			.body(Envelope.error(413, ErrorCode.FILE_TOO_LARGE,
					"file too large, use multipart upload for files over 50MB"));
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<Envelope<Void>> noResource(NoResourceFoundException e) {
		return ResponseEntity.status(404).body(Envelope.error(404, ErrorCode.NOT_FOUND, "resource not found"));
	}

	@ExceptionHandler(Errors.NotFound.class)
	public ResponseEntity<Envelope<Void>> notFound(Errors.NotFound e) {
		return ResponseEntity.status(404).body(Envelope.error(404, ErrorCode.NOT_FOUND, "resource not found"));
	}

	@ExceptionHandler(Errors.AccessDenied.class)
	public ResponseEntity<Envelope<Void>> accessDenied(Errors.AccessDenied e) {
		return ResponseEntity.status(403).body(Envelope.error(403, ErrorCode.FORBIDDEN, "access denied"));
	}

	@ExceptionHandler(Errors.StorageExceeded.class)
	public ResponseEntity<Envelope<Void>> storageExceeded(Errors.StorageExceeded e) {
		return ResponseEntity.status(413)
			.body(Envelope.error(413, ErrorCode.STORAGE_EXCEEDED, "storage limit exceeded"));
	}

	@ExceptionHandler(Errors.NotTextFile.class)
	public ResponseEntity<Envelope<Void>> notTextFile(Errors.NotTextFile e) {
		return ResponseEntity.status(400).body(Envelope.error(400, ErrorCode.BAD_REQUEST, "not a text file"));
	}

	@ExceptionHandler(Errors.NameRequired.class)
	public ResponseEntity<Envelope<Void>> nameRequired(Errors.NameRequired e) {
		return ResponseEntity.status(400).body(Envelope.error(400, ErrorCode.BAD_REQUEST, "name required"));
	}

	@ExceptionHandler(Errors.FolderCycle.class)
	public ResponseEntity<Envelope<Void>> folderCycle(Errors.FolderCycle e) {
		return ResponseEntity.status(422).body(Envelope.error(422, ErrorCode.FOLDER_CYCLE, e.getMessage()));
	}

	@ExceptionHandler(Errors.UsernameTaken.class)
	public ResponseEntity<Envelope<Void>> usernameTaken(Errors.UsernameTaken e) {
		return ResponseEntity.status(409)
			.body(Envelope.error(409, ErrorCode.USERNAME_TAKEN, "username already exists"));
	}

	@ExceptionHandler(Errors.EmailTaken.class)
	public ResponseEntity<Envelope<Void>> emailTaken(Errors.EmailTaken e) {
		return ResponseEntity.status(409).body(Envelope.error(409, ErrorCode.EMAIL_TAKEN, "email already exists"));
	}

	@ExceptionHandler(Errors.DuplicateUser.class)
	public ResponseEntity<Envelope<Void>> duplicateUser(Errors.DuplicateUser e) {
		return ResponseEntity.status(409).body(Envelope.error(409, ErrorCode.CONFLICT, "user already exists"));
	}

	@ExceptionHandler(Errors.InvalidCredentials.class)
	public ResponseEntity<Envelope<Void>> invalidCredentials(Errors.InvalidCredentials e) {
		return ResponseEntity.status(401)
			.body(Envelope.error(401, ErrorCode.INVALID_CREDENTIALS, "invalid username or password"));
	}

	@ExceptionHandler(Errors.WrongPassword.class)
	public ResponseEntity<Envelope<Void>> wrongPassword(Errors.WrongPassword e) {
		return ResponseEntity.status(400).body(Envelope.error(400, ErrorCode.BAD_REQUEST, "wrong password"));
	}

	@ExceptionHandler(Errors.ProviderRequired.class)
	public ResponseEntity<Envelope<Void>> providerRequired(Errors.ProviderRequired e) {
		return ResponseEntity.status(400).body(Envelope.error(400, ErrorCode.BAD_REQUEST, "provider required"));
	}

	@ExceptionHandler(Errors.ProviderTooLong.class)
	public ResponseEntity<Envelope<Void>> providerTooLong(Errors.ProviderTooLong e) {
		return ResponseEntity.status(400).body(Envelope.error(400, ErrorCode.BAD_REQUEST, "validation failed"));
	}

	@ExceptionHandler(Errors.LlmConfigNotFound.class)
	public ResponseEntity<Envelope<Void>> llmConfigNotFound(Errors.LlmConfigNotFound e) {
		return ResponseEntity.status(500).body(Envelope.error(500, ErrorCode.INTERNAL, "internal error"));
	}

	@ExceptionHandler(Errors.UploadNotFound.class)
	public ResponseEntity<Envelope<Void>> uploadNotFound(Errors.UploadNotFound e) {
		return ResponseEntity.status(404).body(Envelope.error(404, ErrorCode.NOT_FOUND, "resource not found"));
	}

	@ExceptionHandler(Errors.Incomplete.class)
	public ResponseEntity<Envelope<Void>> incomplete(Errors.Incomplete e) {
		return ResponseEntity.status(400).body(Envelope.error(400, ErrorCode.BAD_REQUEST, e.getMessage()));
	}

	@ExceptionHandler(Errors.SizeMismatch.class)
	public ResponseEntity<Envelope<Void>> sizeMismatch(Errors.SizeMismatch e) {
		return ResponseEntity.status(400).body(Envelope.error(400, ErrorCode.BAD_REQUEST, e.getMessage()));
	}

	@ExceptionHandler(Errors.PartTooSmall.class)
	public ResponseEntity<Envelope<Void>> partTooSmall(Errors.PartTooSmall e) {
		return ResponseEntity.status(400).body(Envelope.error(400, ErrorCode.BAD_REQUEST, e.getMessage()));
	}

	@ExceptionHandler(Errors.InvalidParts.class)
	public ResponseEntity<Envelope<Void>> invalidParts(Errors.InvalidParts e) {
		return ResponseEntity.status(400).body(Envelope.error(400, ErrorCode.BAD_REQUEST, e.getMessage()));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<Envelope<Void>> fallback(Exception e) {
		log.error("unhandled exception", e);
		return ResponseEntity.status(500).body(Envelope.error(500, ErrorCode.INTERNAL, "internal server error"));
	}

	private ResponseEntity<Envelope<Void>> badRequest(String message) {
		return ResponseEntity.status(400).body(Envelope.error(400, ErrorCode.BAD_REQUEST, message));
	}

}
