package top.xuyangjerry.mcmod.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import top.xuyangjerry.mcmod.LightChatPatch;
import top.xuyangjerry.mcmod.client.ChatScreenHandler;

public final class ExampleModFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        LightChatPatch.init();

        // 客户端 tick：检测进入世界，提前加载历史发送记录
        ClientTickEvents.END_CLIENT_TICK.register(client -> ChatScreenHandler.onClientTick());

        // 全局 AFTER_INIT：处理 ChatScreen 和 KeyBindsScreen 初始化
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof ChatScreen chatScreen) {
                boolean firstInit = ChatScreenHandler.onChatScreenInit(chatScreen);

                // 仅在首次初始化时注册 per-screen 事件（避免 re-init 时重复注册）
                if (firstInit) {
                    registerChatScreenEvents(chatScreen);
                }
            } else if (screen instanceof ControlsScreen) {
                ChatScreenHandler.onControlsScreenInit(screen);
            }
        });
    }

    private static void registerChatScreenEvents(ChatScreen chatScreen) {
        // 渲染后绘制悬停按钮 / 右键菜单
        ScreenEvents.afterRender(chatScreen).register((screen, graphics, mouseX, mouseY, partialTick) -> {
            ChatScreenHandler.onChatScreenRender(chatScreen, graphics, mouseX, mouseY, partialTick);
        });

        // 鼠标点击（返回 false 取消原处理）
        // MouseButtonEvent 签名: boolean allowMouseClick(Screen, MouseButtonEvent)
        ScreenMouseEvents.allowMouseClick(chatScreen).register((screen, context) -> {
            return !ChatScreenHandler.onChatScreenMouseClick(chatScreen, context.x(), context.y(), context.button());
        });

        // 键盘按键（返回 false 取消原处理）
        // KeyEvent 签名: boolean allowKeyPress(Screen, KeyEvent)
        ScreenKeyboardEvents.allowKeyPress(chatScreen).register((screen, context) -> {
            return !ChatScreenHandler.onChatScreenKeyPress(chatScreen, context.key(), context.scancode(), context.modifiers());
        });

        // 屏幕移除：保存草稿 + 持久化历史
        ScreenEvents.remove(chatScreen).register(screen -> {
            ChatScreenHandler.onChatScreenRemove(chatScreen);
        });
    }
}
