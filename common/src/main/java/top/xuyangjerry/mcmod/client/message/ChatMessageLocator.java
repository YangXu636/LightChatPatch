package top.xuyangjerry.mcmod.client.message;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.util.Mth;
import top.xuyangjerry.mcmod.mixin.client.ChatComponentAccess;

/**
 * Utility to locate which GuiMessage is under the mouse cursor in the chat.
 *
 * Coordinate system derivation (from ChatComponent.render bytecode):
 * <ul>
 *   <li>chatScale = options.chatScale().get()</li>
 *   <li>chatBottom (local) = floor((screenHeight - 40) / chatScale)</li>
 *   <li>entryHeight = (int)(9 * (1 + chatLineSpacing))</li>
 *   <li>Line at visual index i (0 = bottom): bottom_local = chatBottom - i * entryHeight</li>
 *   <li>localMouseY = mouseY / chatScale (the pose does scale(chatScale) + translate(4,0); translate is X-only)</li>
 *   <li>lineIndex = floor((chatBottom - localMouseY) / entryHeight)</li>
 * </ul>
 */
public final class ChatMessageLocator {

    private ChatMessageLocator() {
    }

    /**
     * @return the GuiMessage under the mouse, or null if none.
     */
    public static GuiMessage findMessageAtMouse(double mouseX, double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;

        ChatComponent chat = mc.gui.getChat();
        if (chat == null) return null;

        ChatComponentAccess access = (ChatComponentAccess) chat;
        java.util.List<GuiMessage.Line> trimmed = access.lcp$getTrimmedMessages();
        java.util.List<GuiMessage> all = access.lcp$getAllMessages();
        if (trimmed.isEmpty() || all.isEmpty()) return null;

        double chatScale = mc.options.chatScale().get();
        if (chatScale <= 0.0) return null;

        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int chatBottom = Mth.floor((screenHeight - 40) / (float) chatScale);
        double chatLineSpacing = mc.options.chatLineSpacing().get();
        int entryHeight = Math.max(1, (int) (9.0 * (1.0 + chatLineSpacing)));

        double localMouseY = mouseY / chatScale;
        int visualLineIndex = Mth.floor((chatBottom - localMouseY) / entryHeight);
        if (visualLineIndex < 0) return null;

        int scrollbarPos = access.lcp$getChatScrollbarPos();
        int trimmedIndex = visualLineIndex + scrollbarPos;
        if (trimmedIndex >= trimmed.size()) return null;

        double chatHeightFocused = mc.options.chatHeightFocused().get();
        int chatHeight = Mth.floor(160.0 * chatHeightFocused + 20.0);
        int linesPerPage = chatHeight / entryHeight;
        if (visualLineIndex >= linesPerPage) return null;

        // 使用 addedTime 定位消息：多行消息的所有行共享相同的 addedTime
        int addedTime = trimmed.get(trimmedIndex).addedTime();
        for (GuiMessage msg : all) {
            if (msg.addedTime() == addedTime) {
                return msg;
            }
        }
        return null;
    }

    /**
     * @return the screen-space Y (top) of the chat line at the given visual index, or -1 if invalid.
     */
    public static int getLineScreenYTop(int visualLineIndex) {
        Minecraft mc = Minecraft.getInstance();
        double chatScale = mc.options.chatScale().get();
        if (chatScale <= 0.0) return -1;

        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int chatBottom = Mth.floor((screenHeight - 40) / (float) chatScale);
        double chatLineSpacing = mc.options.chatLineSpacing().get();
        int entryHeight = Math.max(1, (int) (9.0 * (1.0 + chatLineSpacing)));

        int lineBottomLocal = chatBottom - visualLineIndex * entryHeight;
        int lineTopLocal = lineBottomLocal - entryHeight;
        return (int) (lineTopLocal * chatScale);
    }

    /**
     * @return the screen-space Y (bottom) of the chat line at the given visual index, or -1 if invalid.
     */
    public static int getLineScreenYBottom(int visualLineIndex) {
        Minecraft mc = Minecraft.getInstance();
        double chatScale = mc.options.chatScale().get();
        if (chatScale <= 0.0) return -1;

        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int chatBottom = Mth.floor((screenHeight - 40) / (float) chatScale);
        double chatLineSpacing = mc.options.chatLineSpacing().get();
        int entryHeight = Math.max(1, (int) (9.0 * (1.0 + chatLineSpacing)));

        int lineBottomLocal = chatBottom - visualLineIndex * entryHeight;
        return (int) (lineBottomLocal * chatScale);
    }

    /**
     * @return the screen-space X of the right edge of the chat box.
     */
    public static int getChatRightEdge() {
        Minecraft mc = Minecraft.getInstance();
        double chatScale = mc.options.chatScale().get();
        // getWidth() = floor(280 * scale + 40); local chat width = ceil(getWidth() / scale)
        // right edge screen x = scale * (localWidth + 4)  (due to translate(4,0) in pose)
        int rawWidth = Mth.floor(280.0 * chatScale + 40.0);
        int localWidth = (int) Math.ceil(rawWidth / chatScale);
        return (int) (chatScale * (localWidth + 4));
    }

    /**
     * 根据消息的 addedTime 计算其在 trimmedMessages 中的起始和结束行索引。
     * 与转发选择模式使用的定位方式一致，确保多行消息范围准确。
     *
     * @return int[2] = {startIndex, endIndex}，找不到返回 null
     */
    public static int[] getMessageTrimmedRange(GuiMessage targetMessage) {
        if (targetMessage == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null || mc.gui.getChat() == null) return null;

        ChatComponent chat = mc.gui.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;
        java.util.List<GuiMessage.Line> trimmed = access.lcp$getTrimmedMessages();
        if (trimmed.isEmpty()) return null;

        int targetTime = targetMessage.addedTime();
        int rangeStart = -1;
        int rangeEnd = -1;

        for (int i = 0; i < trimmed.size(); i++) {
            if (trimmed.get(i).addedTime() == targetTime) {
                if (rangeStart < 0) rangeStart = i;
                rangeEnd = i;
            } else if (rangeStart >= 0) {
                break;
            }
        }

        if (rangeStart < 0) return null;
        return new int[]{rangeStart, rangeEnd};
    }

    /**
     * @return the visual line index under the mouse, or -1 if none.
     */
    public static int getVisualLineIndexAtMouse(double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        double chatScale = mc.options.chatScale().get();
        if (chatScale <= 0.0) return -1;

        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int chatBottom = Mth.floor((screenHeight - 40) / (float) chatScale);
        double chatLineSpacing = mc.options.chatLineSpacing().get();
        int entryHeight = Math.max(1, (int) (9.0 * (1.0 + chatLineSpacing)));

        double localMouseY = mouseY / chatScale;
        int visualLineIndex = Mth.floor((chatBottom - localMouseY) / entryHeight);
        if (visualLineIndex < 0) return -1;

        double chatHeightFocused = mc.options.chatHeightFocused().get();
        int chatHeight = Mth.floor(160.0 * chatHeightFocused + 20.0);
        int linesPerPage = chatHeight / entryHeight;
        if (visualLineIndex >= linesPerPage) return -1;

        return visualLineIndex;
    }
}
