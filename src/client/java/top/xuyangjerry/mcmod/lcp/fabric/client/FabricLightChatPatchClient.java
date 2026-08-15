package top.xuyangjerry.mcmod.lcp.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.xuyangjerry.mcmod.lcp.client.LightChatPatchClient;

public class FabricLightChatPatchClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("light_chat_patch");

	@Override
	public void onInitializeClient() {
		// 尽早禁用 AWT headless 模式，确保剪贴板/文件选择器等功能可用
		try {
			System.setProperty("java.awt.headless", "false");
		} catch (Throwable ignored) {
		}

		LOGGER.info("[LCP] Client entrypoint starting...");
		try {
			LightChatPatchClient.init();
			LOGGER.info("[LCP] Client initialized successfully");
		} catch (Throwable t) {
			LOGGER.error("[LCP] Failed to initialize client!", t);
			throw new RuntimeException("LCP client init failed", t);
		}
	}
}
