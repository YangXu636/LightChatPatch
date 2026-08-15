package top.xuyangjerry.mcmod.lcp.client.message;

import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import top.xuyangjerry.mcmod.lcp.client.mixin.ChatComponentAccess;

/**
 * 聊天消息定位工具类。
 *
 * 关于原版玩家名 selector 的说明：
 *   - 原版 MC 发送的玩家消息（chat.type.text），发送者名是 Component.selector()，
 *     其 Style 会自动附加 HoverEvent.SHOW_ENTITY（悬浮显示玩家实体信息：名称、类型、UUID）。
 *   - 这是判断"某段文本是否为原版玩家名"最可靠的标识：只要字符位置的 Style
 *     带有 SHOW_ENTITY 悬浮事件且 type=player，即可 100% 确定是玩家名。
 *   - mod 发送的纯文本消息（LiteralContents）不带 SHOW_ENTITY，需通过文本格式回退匹配。
 */
public final class ChatMessageLocator {

    private ChatMessageLocator() {
    }

    /**
     * 获取鼠标位置精确对应的字符 Style。
     * 通过迭代 trimmed line 的 FormattedCharSequence，按字符宽度累加定位到鼠标 X 所在字符，
     * 返回该字符的 Style。用于检测 SHOW_ENTITY 悬浮事件（原版玩家名标识）。
     *
     * @return 对应字符的 Style，定位失败返回 null
     */
    public static Style getStyleAtMouse(double mouseX, double mouseY) {
        int trimmedIndex = getTrimmedIndexAtMouse(mouseY);
        if (trimmedIndex < 0) return null;

        Minecraft mc = Minecraft.getInstance();
        ChatComponentAccess access = (ChatComponentAccess) mc.gui.hud.getChat();
        java.util.List<GuiMessage.Line> trimmed = access.lcp$getTrimmedMessages();
        if (trimmedIndex >= trimmed.size()) return null;

        double chatScale = mc.options.chatScale().get();
        if (chatScale <= 0.0) return null;

        // 将屏幕坐标转换为聊天内部（缩放前）坐标
        Font font = mc.font;
        double localX = (mouseX - 4.0 * chatScale) / chatScale;  // 去掉左侧 4px 缩进
        if (localX < 0.0) return null;

        GuiMessage.Line line = trimmed.get(trimmedIndex);
        FormattedCharSequence seq = line.content();

        // 按字符宽度累加，找到鼠标 X 落在哪个字符上
        final float[] cursorX = {0.0f};
        final Style[] hitStyle = {null};
        final double targetX = localX;

        seq.accept((index, style, codePoint) -> {
            if (hitStyle[0] != null) return true;  // 已找到，跳过
            float charWidth = font.width(String.valueOf(Character.toChars(codePoint)));
            if (targetX >= cursorX[0] && targetX < cursorX[0] + charWidth) {
                hitStyle[0] = style;
                return false;
            }
            cursorX[0] += charWidth;
            return true;
        });

        return hitStyle[0];
    }

    /**
     * 通过鼠标位置找到对应的 GuiMessage。
     * 直接使用 trimmedMessages[trimmedIndex].parent() 获取 GuiMessage，
     * 不依赖 addedTime 匹配，避免多条消息共享同一 addedTime 时的定位错误。
     */
    public static GuiMessage findMessageAtMouse(double mouseX, double mouseY) {
        int trimmedIndex = getTrimmedIndexAtMouse(mouseY);
        if (trimmedIndex < 0) return null;

        Minecraft mc = Minecraft.getInstance();
        ChatComponentAccess access = (ChatComponentAccess) mc.gui.hud.getChat();
        java.util.List<GuiMessage.Line> trimmed = access.lcp$getTrimmedMessages();
        if (trimmedIndex >= trimmed.size()) return null;

        return trimmed.get(trimmedIndex).parent();
    }

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

    public static int getChatRightEdge() {
        Minecraft mc = Minecraft.getInstance();
        double chatScale = mc.options.chatScale().get();
        int rawWidth = Mth.floor(280.0 * chatScale + 40.0);
        int localWidth = (int) Math.ceil(rawWidth / chatScale);
        return (int) (chatScale * (localWidth + 4));
    }

    /**
     * 通过 parent 引用匹配消息在 trimmedMessages 中的范围，
     * 不依赖 addedTime，避免多条消息共享同一 addedTime 时的范围错误。
     */
    public static int[] getMessageTrimmedRange(GuiMessage targetMessage) {
        if (targetMessage == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null || mc.gui.hud.getChat() == null) return null;

        ChatComponent chat = mc.gui.hud.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;
        java.util.List<GuiMessage.Line> trimmed = access.lcp$getTrimmedMessages();
        if (trimmed.isEmpty()) return null;

        int rangeStart = -1;
        int rangeEnd = -1;

        for (int i = 0; i < trimmed.size(); i++) {
            if (trimmed.get(i).parent() == targetMessage) {
                if (rangeStart < 0) rangeStart = i;
                rangeEnd = i;
            } else if (rangeStart >= 0) {
                break;
            }
        }

        if (rangeStart < 0) return null;
        return new int[]{rangeStart, rangeEnd};
    }

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

    /**
     * 直接从 trimmedMessages 获取鼠标位置对应的完整消息文本。
     * 使用 parent 引用收集同一消息的所有行，不依赖 addedTime。
     */
    public static String getMessageTextAtMouse(double mouseX, double mouseY) {
        int trimmedIndex = getTrimmedIndexAtMouse(mouseY);
        if (trimmedIndex < 0) return null;

        Minecraft mc = Minecraft.getInstance();
        ChatComponentAccess access = (ChatComponentAccess) mc.gui.hud.getChat();
        java.util.List<GuiMessage.Line> trimmed = access.lcp$getTrimmedMessages();
        if (trimmedIndex >= trimmed.size()) return null;

        GuiMessage target = trimmed.get(trimmedIndex).parent();
        StringBuilder sb = new StringBuilder();
        for (GuiMessage.Line line : trimmed) {
            if (line.parent() == target) {
                line.content().accept((index, style, codePoint) -> {
                    sb.appendCodePoint(codePoint);
                    return true;
                });
            }
        }
        return sb.toString();
    }

    /**
     * 计算鼠标位置对应的 trimmedMessages 索引。
     * 内部共用方法，供 findMessageAtMouse 和 getMessageTextAtMouse 使用。
     */
    private static int getTrimmedIndexAtMouse(double mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return -1;
        if (mc.gui == null || mc.gui.hud.getChat() == null) return -1;

        ChatComponent chat = mc.gui.hud.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;
        java.util.List<GuiMessage.Line> trimmed = access.lcp$getTrimmedMessages();
        if (trimmed.isEmpty()) return -1;

        double chatScale = mc.options.chatScale().get();
        if (chatScale <= 0.0) return -1;

        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int chatBottom = Mth.floor((screenHeight - 40) / (float) chatScale);
        double chatLineSpacing = mc.options.chatLineSpacing().get();
        int entryHeight = Math.max(1, (int) (9.0 * (1.0 + chatLineSpacing)));

        double localMouseY = mouseY / chatScale;
        int visualLineIndex = Mth.floor((chatBottom - localMouseY) / entryHeight);
        if (visualLineIndex < 0) return -1;

        int scrollbarPos = access.lcp$getChatScrollbarPos();
        int trimmedIndex = visualLineIndex + scrollbarPos;
        if (trimmedIndex >= trimmed.size()) return -1;

        double chatHeightFocused = mc.options.chatHeightFocused().get();
        int chatHeight = Mth.floor(160.0 * chatHeightFocused + 20.0);
        int linesPerPage = chatHeight / entryHeight;
        if (visualLineIndex >= linesPerPage) return -1;

        return trimmedIndex;
    }
}
