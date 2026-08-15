package top.xuyangjerry.mcmod.lcp.client.mixin;

import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(OptionsSubScreen.class)
public interface OptionsSubScreenAccess {
    @Accessor("layout")
    HeaderAndFooterLayout lcp$getLayout();

    @Accessor("list")
    OptionsList lcp$getList();
}