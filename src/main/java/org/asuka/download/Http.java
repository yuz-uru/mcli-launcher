package org.asuka.download;

import org.asuka.utils.LauncherInfo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 共享 HttpClient 与简单 GET 工具 */
public final class Http {

	private static final HttpClient CLIENT = HttpClient.newBuilder()
	                                                   .followRedirects(HttpClient.Redirect.NORMAL)
	                                                   .connectTimeout(Duration.ofSeconds(15))
	                                                   .build();

	private Http() {
	}

	public static HttpClient client() {
		return CLIENT;
	}

	public static String getString(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
		                                 .timeout(Duration.ofSeconds(30))
		                                 .header("User-Agent", LauncherInfo.USER_AGENT)
		                                 .GET()
		                                 .build();
		HttpResponse<String> resp = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if(resp.statusCode() < 200 || resp.statusCode() >= 300) {
			throw new IOException("HTTP " + resp.statusCode() + ": " + url);
		}
		return resp.body();
	}
}