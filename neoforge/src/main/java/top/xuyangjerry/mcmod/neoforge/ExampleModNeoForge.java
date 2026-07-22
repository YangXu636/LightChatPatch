package top.xuyangjerry.mcmod.neoforge;

import net.neoforged.fml.common.Mod;
import top.xuyangjerry.mcmod.LightChatPatch;

/**
 * NeoForge 模组入口点。
 * 实际初始化由 NeoForgeClientEvents 在客户端事件中处理。
 */
@Mod(LightChatPatch.MOD_ID)
public final class ExampleModNeoForge {
    public ExampleModNeoForge() {
        // 不在此处初始化配置，等待客户端事件触发
        // LightChatPatch.init() 由 NeoForgeClientEvents 的 FMLClientSetupEvent 调用
    }
}
