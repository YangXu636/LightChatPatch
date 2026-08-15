package top.xuyangjerry.mcmod.lcp.client.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChatScreen.class)
public interface ChatScreenAccess {

    @Accessor("input")
    EditBox lcp$getInput();

    @Accessor("initial")
    String lcp$getInitial();

    @Accessor("initial")
    void lcp$setInitial(String value);

    @Accessor("isDraft")
    boolean lcp$isDraft();

    @Accessor("isDraft")
    void lcp$setIsDraft(boolean value);

    @Accessor("historyPos")
    int lcp$getHistoryPos();

    @Accessor("historyPos")
    void lcp$setHistoryPos(int value);
}