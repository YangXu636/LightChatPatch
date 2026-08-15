package top.xuyangjerry.mcmod.lcp.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.options.ChatOptionsScreen;
import net.minecraft.client.gui.screens.social.SocialInteractionsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import top.xuyangjerry.mcmod.lcp.client.mixin.ScreenAccess;
import top.xuyangjerry.mcmod.lcp.client.nonebot.ClientNetworking;
import top.xuyangjerry.mcmod.lcp.client.screen.LightChatPatchConfigScreen;
import top.xuyangjerry.mcmod.lcp.client.screen.PlayerSocialScreen;

public final class LightChatPatchClient {

    private LightChatPatchClient() {
    }

    public static void init() {
        try {
            ClientNetworking.registerPayloads();
            ClientNetworking.registerReceivers();
        } catch (Throwable t) {
            top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] Failed to init networking", t);
        }

        try {
            ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
                try {
                    ChatScreenHandler.onClientTick();
                } catch (Throwable t) {
                    top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] onClientTick error", t);
                }
            });
        } catch (Throwable t) {
            top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] Failed to register tick event", t);
        }

        // HUD 渲染：聊天框未打开时也绘制回复消息背景色
        // 注册在原版 CHAT 层之前，继承聊天层的渲染条件（HUD 未隐藏时渲染）
        try {
            HudElementRegistry.attachElementBefore(
                    VanillaHudElements.CHAT,
                    Identifier.fromNamespaceAndPath("light_chat_patch", "reply_backgrounds"),
                    (graphics, deltaTracker) -> {
                        try {
                            Minecraft mc = Minecraft.getInstance();
                            // 仅在未打开聊天框时绘制（聊天框打开时由 beforeExtract 处理）
                            if (mc.level != null && !(mc.gui.screen() instanceof ChatScreen)) {
                                ChatScreenHandler.drawReplyMessageBackgrounds(graphics, false);
                            }
                        } catch (Throwable t) {
                            top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] HUD reply bg error", t);
                        }
                    }
            );
        } catch (Throwable t) {
            top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] Failed to register HUD element", t);
        }

        try {
            ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
                if (screen instanceof ChatScreen chatScreen) {
                    boolean firstInit = false;
                    try {
                        firstInit = ChatScreenHandler.onChatScreenInit(chatScreen);
                    } catch (Throwable t) {
                        top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] onChatScreenInit error", t);
                    }

                    // onChatScreenInit 始终返回 true：每次 AFTER_INIT 都重新注册 per-screen 事件
                    // （Fabric 在 re-init 时会清空 per-screen 事件回调，需要重新注册，
                    //   否则从子屏幕回到 ChatScreen 或窗口最小化恢复后悬浮/菜单全部失效）
                    if (firstInit) {
                        registerChatScreenEvents(chatScreen);
                    }
                } else if (screen instanceof ChatOptionsScreen) {
                    try {
                        ChatScreenHandler.onChatOptionsScreenInit(screen);
                    } catch (Throwable t) {
                        top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] onChatOptionsScreenInit error", t);
                    }
                } else if (screen instanceof SocialInteractionsScreen socialScreen) {
                    try {
                        onSocialInteractionsScreenInit(socialScreen);
                    } catch (Throwable t) {
                        top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] onSocialInteractionsScreenInit error", t);
                    }
                }
            });
        } catch (Throwable t) {
            top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] Failed to register screen event", t);
        }
    }

    private static void registerChatScreenEvents(ChatScreen chatScreen) {
        // 回复消息背景色在 beforeExtract 中绘制，确保不遮盖命令补全（Tab补全列表等）
        try {
            ScreenEvents.beforeExtract(chatScreen).register((s, graphics, mouseX, mouseY, tickDelta) -> {
                try {
                    ChatScreenHandler.drawReplyMessageBackgrounds(graphics, true);
                } catch (Throwable t) {
                    top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] beforeExtract reply bg error", t);
                }
            });
        } catch (Throwable t) {
            top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] Failed to register beforeExtract", t);
        }

        // 使用 afterExtract 确保菜单/卡片/图片预览渲染在聊天内容之上
        try {
            ScreenEvents.afterExtract(chatScreen).register((s, graphics, mouseX, mouseY, tickDelta) -> {
                try {
                    ChatScreenHandler.onChatScreenRender(chatScreen, graphics, mouseX, mouseY, tickDelta);
                } catch (Throwable t) {
                    top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] onChatScreenRender error", t);
                }
            });
        } catch (Throwable t) {
            top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] Failed to register afterExtract", t);
        }

        // 鼠标点击（返回 false 取消原处理）
        try {
            ScreenMouseEvents.allowMouseClick(chatScreen).register((screen, context) -> {
                try {
                    return !ChatScreenHandler.onChatScreenMouseClick(chatScreen, context.x(), context.y(), context.button());
                } catch (Throwable t) {
                    top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] onChatScreenMouseClick error", t);
                    return true;
                }
            });
        } catch (Throwable t) {
            top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] Failed to register mouse click event", t);
        }

        // 鼠标拖动（弹窗拖动/调整大小，有弹窗时屏蔽聊天栏）
        try {
            ScreenMouseEvents.allowMouseDrag(chatScreen).register((screen, event, dragX, dragY) -> {
                try {
                    return !ChatScreenHandler.onChatScreenMouseDrag(chatScreen, event.x(), event.y(), event.button());
                } catch (Throwable t) {
                    top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] onChatScreenMouseDrag error", t);
                    return true;
                }
            });
        } catch (Throwable t) {
            top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] Failed to register mouse drag event", t);
        }

        // 鼠标释放（有弹窗时屏蔽聊天栏）
        try {
            ScreenMouseEvents.allowMouseRelease(chatScreen).register((screen, event) -> {
                try {
                    return !ChatScreenHandler.onChatScreenMouseRelease(chatScreen, event.x(), event.y(), event.button());
                } catch (Throwable t) {
                    top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] onChatScreenMouseRelease error", t);
                    return true;
                }
            });
        } catch (Throwable t) {
            top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] Failed to register mouse release event", t);
        }

        // 鼠标滚轮（弹窗内滚动/图片缩放，有弹窗时屏蔽聊天栏）
        try {
            ScreenMouseEvents.allowMouseScroll(chatScreen).register((screen, x, y, scrollX, scrollY) -> {
                try {
                    return !ChatScreenHandler.onChatScreenMouseScroll(chatScreen, x, y, scrollY);
                } catch (Throwable t) {
                    top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] onChatScreenMouseScroll error", t);
                    return true;
                }
            });
        } catch (Throwable t) {
            top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] Failed to register mouse scroll event", t);
        }

        // 键盘按键（返回 false 取消原处理）
        try {
            ScreenKeyboardEvents.allowKeyPress(chatScreen).register((screen, context) -> {
                try {
                    return !ChatScreenHandler.onChatScreenKeyPress(chatScreen, context.key(), context.scancode(), context.modifiers());
                } catch (Throwable t) {
                    top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] onChatScreenKeyPress error", t);
                    return true;
                }
            });
        } catch (Throwable t) {
            top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] Failed to register key press event", t);
        }

        // 屏幕移除：保存草稿 + 持久化历史
        try {
            ScreenEvents.remove(chatScreen).register(s -> {
                try {
                    ChatScreenHandler.onChatScreenRemove(chatScreen);
                } catch (Throwable t) {
                    top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] onChatScreenRemove error", t);
                }
            });
        } catch (Throwable t) {
            top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.error("[LCP] Failed to register remove event", t);
        }
    }

    /**
     * 原版社交界面（P键）初始化时调用：添加 LCP 禁言管理入口按钮。
     */
    private static void onSocialInteractionsScreenInit(SocialInteractionsScreen screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // 检查服务端是否装了 LCP mod
        if (!ClientNetworking.isServerModInstalled()) return;

        Button muteButton = Button.builder(
                        Component.translatable("light_chat_patch.social.open_mute_manager"),
                        btn -> mc.gui.setScreen(new PlayerSocialScreen(screen)))
                .bounds(screen.width / 2 - 100, screen.height - 50, 200, 20)
                .build();

        ((ScreenAccess) screen).lcp$addRenderableWidget(muteButton);
    }
}
