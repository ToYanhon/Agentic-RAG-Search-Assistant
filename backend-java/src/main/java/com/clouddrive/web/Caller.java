package com.clouddrive.web;

/**
 * 调用方身份，对应 Go httpapi.Caller。
 */
public enum Caller {

	USER("user"), AGENT("agent");

	private final String value;

	Caller(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

}
