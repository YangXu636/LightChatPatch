package top.xuyangjerry.mcmod.mixin.client;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 纯接口 @Invoker mixin，暴露 Screen 的受保护方法。
 * addRenderableWidget 在 Screen 中直接声明（非继承），不存在 refMap 解析问题。
 */
@Mixin(Screen.class)
public interface ScreenAccess {

    @Invoker("addRenderableWidget")
    <T extends GuiEventListener & Renderable> T lcp$addRenderableWidget(T widget);
}
