package top.xuyangjerry.mcmod.lcp.client.message;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

public final class MessageActions {

    private MessageActions() {
    }

    public static void copy(String text) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text);
    }

    public static void reply(ChatScreen screen, String sender, String originalText) {
        String reply = "@" + sender + " " + originalText;
        screen.insertText(reply, false);
    }

    public static void plusOne(ChatScreen screen, String messageText) {
        screen.handleChatInput(messageText, true);
    }
}