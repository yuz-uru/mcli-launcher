package org.asuka.download;

import java.io.IOException;

/** 不可重试的 HTTP 错误（4xx，除 408/429），重试循环遇到应立即放弃当前来源 */
public class HttpPermanentException extends IOException {

	public HttpPermanentException(String message) {
		super(message);
	}
}