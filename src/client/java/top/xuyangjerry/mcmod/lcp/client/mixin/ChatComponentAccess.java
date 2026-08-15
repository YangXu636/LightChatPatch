package top.xuyangjerry.mcmod.lcp.client.mixin;

import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ChatComponent.class)
public interface ChatComponentAccess {

    @Accessor("allMessages")
    List<GuiMessage> lcp$getAllMessages();

    @Accessor("trimmedMessages")
    List<GuiMessage.Line> lcp$getTrimmedMessages();

    @Accessor("chatScrollbarPos")
    int lcp$getChatScrollbarPos();

    @Accessor("chatScrollbarPos")
    void lcp$setChatScrollbarPos(int pos);
}