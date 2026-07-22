package top.xuyangjerry.mcmod.neoforge.client;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.fml.ModContainer;
import java.util.function.Supplier;
import top.xuyangjerry.mcmod.LightChatPatch;
import top.xuyangjerry.mcmod.client.ChatScreenHandler;
import top.xuyangjerry.mcmod.client.screen.LightChatPatchConfigScreen;

/**
 * NeoForge 客户端事件处理。通过 @EventBusSubscriber 自动注册。
 * 仅在客户端加载（value = Dist.CLIENT）。
 */
@EventBusSubscriber(modid = LightChatPatch.MOD_ID, value = Dist.CLIENT)
public final class NeoForgeClientEvents {

    private NeoForgeClientEvents() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        LightChatPatch.init();
        event.enqueueWork(() -> {
            net.neoforged.fml.ModList.get().getModContainerById(LightChatPatch.MOD_ID)
                    .ifPresent(container -> {
                        Supplier<IConfigScreenFactory> factorySupplier = () -> new IConfigScreenFactory() {
                            @Override
                            public Screen createScreen(ModContainer cont, Screen parent) {
                                return new LightChatPatchConfigScreen(parent);
                            }
                        };
                        container.registerExtensionPoint(IConfigScreenFactory.class, factorySupplier);
                    });
        });
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ChatScreenHandler.onClientTick();
    }

    @SubscribeEvent
    public static void onScreenInitPost(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof ChatScreen chatScreen) {
            ChatScreenHandler.onChatScreenInit(chatScreen);
        } else if (event.getScreen() instanceof ControlsScreen) {
            ChatScreenHandler.onControlsScreenInit(event.getScreen());
        }
    }

    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (event.getScreen() instanceof ChatScreen chatScreen) {
            ChatScreenHandler.onChatScreenRender(
                    chatScreen,
                    event.getGuiGraphics(),
                    (int) event.getMouseX(),
                    (int) event.getMouseY(),
                    event.getPartialTick()
            );
        }
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (event.getScreen() instanceof ChatScreen chatScreen) {
            if (ChatScreenHandler.onChatScreenKeyPress(
                    chatScreen,
                    event.getKeyCode(),
                    event.getScanCode(),
                    event.getModifiers())) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onMouseButtonPressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getScreen() instanceof ChatScreen chatScreen) {
            if (ChatScreenHandler.onChatScreenMouseClick(
                    chatScreen,
                    event.getMouseX(),
                    event.getMouseY(),
                    event.getButton())) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof ChatScreen chatScreen) {
            ChatScreenHandler.onChatScreenRemove(chatScreen);
        }
    }
}
