package top.xuyangjerry.mcmod.client.message;

import net.minecraft.client.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

public final class MessageInfo {
    private static final String CHAT_TYPE_TEXT = "chat.type.text";
    private static final String SYSTEM_SENDER = "系统";

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
        if (component.getContents() instanceof TranslatableContents tc) {
            String key = tc.getKey();
            if (CHAT_TYPE_TEXT.equals(key)) {
                Object[] args = tc.getArgs();
                if (args.length >= 2) {
                    String sender = args[0] instanceof Component c ? c.getString() : String.valueOf(args[0]);
                    String content = args[1] instanceof Component c ? c.getString() : String.valueOf(args[1]);
                    return new MessageInfo(sender, content, true, message);
                }
            }
            // 私聊消息不可选取
            if ("commands.message.display.incoming".equals(key) ||
                "commands.message.display.outgoing".equals(key)) {
                return null;
            }
            // 其他可翻译消息（join/leave/death/achievement 等）归为「系统」
            return new MessageInfo(SYSTEM_SENDER, component.getString(), false, message);
        }
        // PlainTextContents 或其他类型（如 /tellraw）
        return new MessageInfo("?", component.getString(), false, message);
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
