package org.asuka.auth;

import lombok.extern.slf4j.Slf4j;
import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.java.JavaAuthManager;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import net.raphimc.minecraftauth.msa.service.impl.DeviceCodeMsaAuthService;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 正版登录：MinecraftAuth 设备码流程（MSA -> XBL -> XSTS -> Minecraft），成功后返回会话并落盘。
 */
@Slf4j
public final class MicrosoftOAuth {
	private MicrosoftOAuth() {
	}

	public static JavaAuthManager auth() throws IOException, InterruptedException, TimeoutException {
		HttpClient httpClient = SessionManager.httpClient();

		JavaAuthManager authManager = JavaAuthManager.create(httpClient)
		                                             .login(
				                                             DeviceCodeMsaAuthService :: new,
				                                             (Consumer<MsaDeviceCode>)code -> {
					                                             log.info(
							                                             "请在浏览器中访问: {}",
							                                             code.getVerificationUri()
					                                                     );
					                                             log.info("访问代码: {}", code.getUserCode());
					                                             log.info(
							                                             "或直接访问: {}",
							                                             code.getDirectVerificationUri()
					                                                     );
					                                             log.info("等待浏览器确认中……");
				                                             }
		                                                   );

		SessionManager.save(authManager);
		log.info("登录成功");
		return authManager;
	}
}