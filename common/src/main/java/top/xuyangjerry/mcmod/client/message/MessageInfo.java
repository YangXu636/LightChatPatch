package top.xuyangjerry.mcmod.client.message;

import net.minecraft.client.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

public final class MessageInfo {
    private static final String CHAT_TYPE_TEXT = "chat.type.text";

    private final String sender;
    private final String content;
    private final boolean isPlayerMessage;
    private final GuiMessage message;

    private MessageInfo(String sender, String content, boolean isPlayerMessage, GuiMessage message) {
        this.sender = sender;
        this.content = content;
        this.isPlayerMessage = isPlayerMessage;
        this.message = message;
    }

    public static MessageInfo from(GuiMessage message) {
        Component component = message.content();
        if (!(component.getContents() instanceof TranslatableContents tc)) {
            return null;
        }
        if (!CHAT_TYPE_TEXT.equals(tc.getKey())) {
            return null;
        }
        Object[] args = tc.getArgs();
        if (args.length < 2) {
            return null;
        }
        String sender = args[0] instanceof Component c ? c.getString() : String.valueOf(args[0]);
        String content = args[1] instanceof Component c ? c.getString() : String.valueOf(args[1]);
        return new MessageInfo(sender, content, true, message);
    }

    public GuiMessage getMessage() {
        return message;
    }

    public String getSender() {
        return sender;
    }

    public String getContent() {
        return content;
    }

    public boolean isPlayerMessage() {
        return isPlayerMessage;
    }
}
