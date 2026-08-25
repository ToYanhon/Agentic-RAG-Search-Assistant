package com.clouddrive.common;

/**
 * 领域错误类型集合，对应 Go 各包的 error 变量。 控制器使用 instanceof 按端点映射为响应（对应 Go 的 write*Error）。
 */
public final class Errors {

	private Errors() {
	}

	public static class NotFound extends DomainException {

		public NotFound(String message) {
			super(message);
		}

	}

	public static class AccessDenied extends DomainException {

		public AccessDenied(String message) {
			super(message);
		}

	}

	public static class StorageExceeded extends DomainException {

		public StorageExceeded(String message) {
			super(message);
		}

	}

	public static class NotTextFile extends DomainException {

		public NotTextFile(String message) {
			super(message);
		}

	}

	public static class NameRequired extends DomainException {

		public NameRequired(String message) {
			super(message);
		}

	}

	public static class FolderCycle extends DomainException {

		public FolderCycle(String message) {
			super(message);
		}

	}

	public static class UsernameTaken extends DomainException {

		public UsernameTaken(String message) {
			super(message);
		}

	}

	public static class EmailTaken extends DomainException {

		public EmailTaken(String message) {
			super(message);
		}

	}

	public static class DuplicateUser extends DomainException {

		public DuplicateUser(String message) {
			super(message);
		}

	}

	public static class InvalidCredentials extends DomainException {

		public InvalidCredentials(String message) {
			super(message);
		}

	}

	public static class WrongPassword extends DomainException {

		public WrongPassword(String message) {
			super(message);
		}

	}

	public static class ProviderRequired extends DomainException {

		public ProviderRequired(String message) {
			super(message);
		}

	}

	public static class ProviderTooLong extends DomainException {

		public ProviderTooLong(String message) {
			super(message);
		}

	}

	public static class LlmConfigNotFound extends DomainException {

		public LlmConfigNotFound(String message) {
			super(message);
		}

	}

	public static class ShareNotFound extends DomainException {

		public ShareNotFound(String message) {
			super(message);
		}

	}

	public static class UploadNotFound extends DomainException {

		public UploadNotFound(String message) {
			super(message);
		}

	}

	public static class Incomplete extends DomainException {

		public Incomplete(String message) {
			super(message);
		}

	}

	public static class SizeMismatch extends DomainException {

		public SizeMismatch(String message) {
			super(message);
		}

	}

	public static class PartTooSmall extends DomainException {

		public PartTooSmall(String message) {
			super(message);
		}

	}

	public static class InvalidParts extends DomainException {

		public InvalidParts(String message) {
			super(message);
		}

	}

	public static class AgentBusy extends DomainException {

		public AgentBusy(String message) {
			super(message);
		}

	}

	public static class TokenInvalid extends DomainException {

		public TokenInvalid(String message) {
			super(message);
		}

	}

	public static class AgentUnavailable extends DomainException {

		public AgentUnavailable(String message) {
			super(message);
		}

	}

}