package top.xuyangjerry.mcmod.mixin.client;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 纯接口 @Accessor mixin，零运行时风险。
 * 暴露 ChatScreen 的私有/受保护字段，供非 mixin 的 Java 类使用。
 */
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
