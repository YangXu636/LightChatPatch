package top.xuyangjerry.mcmod.lcp.fabric;

import net.fabricmc.api.DedicatedServerModInitializer;

/**
 * 专用服务端入口点。
 * 服务端事件注册已移至 LightChatPatch (ModInitializer)，
 * 以确保单人游戏（Integrated Server）也能正常工作。
 * 此类仅保留用于专用服务端标识，不重复注册事件。
 */
public class LightChatPatchServer implements DedicatedServerModInitializer {
	@Override
	public void onInitializeServer() {
		top.xuyangjerry.mcmod.lcp.fabric.LightChatPatch.LOGGER.info("[LCP] Dedicated server side initialized (NoneBot ready)");
	}
}
