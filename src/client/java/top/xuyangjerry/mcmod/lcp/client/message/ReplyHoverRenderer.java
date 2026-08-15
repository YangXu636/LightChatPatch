package top.xuyangjerry.mcmod.lcp.client.message;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import top.xuyangjerry.mcmod.lcp.network.ReplyMessagePayload;

import java.util.ArrayList;
import java.util.List;

/**
 * 回复消息悬浮渲染器。
 * 当鼠标悬停在包含 [Reply #uuid] 标签的消息上时，显示被回复消息的完整悬浮预览。
 *
 * 预览布局：
 * ┌───────────────────────────┐
 *  ↩ <Steve>                  │  ← 被回复者（带回复图标）
 *  完整内容（自动换行）        │  ← 被回复内容（完整显示，多行换行）
 * └───────────────────────────┘
 */
public final class ReplyHoverRenderer {

    private static final int CARD_MAX_WIDTH = 260;
    private static final int CARD_PADDING = 8;
    private static final int LINE_SPACING = 2;
    private static final int BORDER_COLOR = 0xFF505050;
    private static final int BG_COLOR = 0xF01A1A1A;
    private static final int SENDER_COLOR = 0xFF66CCFF;
    private static final int CONTENT_COLOR = 0xFFCCCCCC;
    private static final int ICON_COLOR = 0xFFAAAAAA;
    private static final int OFFSET_X = 12;
    private static final int OFFSET_Y = 12;
    private static final String REPLY_ICON = "\u21A9"; // ↩

    private ReplyHoverRenderer() {
    }

    public static boolean tryRender(GuiGraphicsExtractor g, String hoverText, int mouseX, int mouseY) {
        String uuid = ReplyDataManager.extractUuid(hoverText);
        if (uuid == null) {
            return false;
        }

        ReplyMessagePayload payload = ReplyDataManager.getInstance().getReplyData(uuid);
        if (payload == null) {
            return false;
        }

        renderPreview(g, payload, mouseX, mouseY);
        return true;
    }

    private static void renderPreview(GuiGraphicsExtractor g, ReplyMessagePayload payload,
                                       int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        Font font = mc.font;

        String senderLine = REPLY_ICON + " <" + payload.originalSender() + ">";
        String content = payload.originalContent();
        if (content == null) content = "";

        int contentMaxWidth = CARD_MAX_WIDTH - CARD_PADDING * 2;

        // 完整内容按宽度换行（不截断）
        List<String> contentLines = wrapText(content, contentMaxWidth, font);

        int senderW = font.width(senderLine);
        int maxLineWidth = senderW;
        for (String line : contentLines) {
            maxLineWidth = Math.max(maxLineWidth, font.width(line));
        }
        int cardWidth = Math.min(CARD_MAX_WIDTH, maxLineWidth + CARD_PADDING * 2);
        int cardHeight = CARD_PADDING + font.lineHeight + LINE_SPACING
                + contentLines.size() * (font.lineHeight + LINE_SPACING) + CARD_PADDING;

        // 定位卡片
        int x = mouseX + OFFSET_X;
        int y = mouseY + OFFSET_Y;
        if (x + cardWidth > screenW) {
            x = mouseX - OFFSET_X - cardWidth;
        }
        if (y + cardHeight > screenH) {
            y = mouseY - OFFSET_Y - cardHeight;
        }
        if (x < 0) x = 0;
        if (y < 0) y = 0;

        // 背景和边框
        g.fill(x, y, x + cardWidth, y + cardHeight, BG_COLOR);
        drawBorder(g, x, y, cardWidth, cardHeight);

        int contentX = x + CARD_PADDING;
        int curY = y + CARD_PADDING;

        // 被回复者
        g.text(font, senderLine, contentX, curY, SENDER_COLOR);
        curY += font.lineHeight + LINE_SPACING;

        // 被回复内容（完整多行）
        for (String line : contentLines) {
            g.text(font, line, contentX, curY, CONTENT_COLOR);
            curY += font.lineHeight + LINE_SPACING;
        }
    }

    /**
     * 按字体宽度换行文本，保留显式换行符。
     */
    private static List<String> wrapText(String text, int maxWidth, Font font) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            result.add("");
            return result;
        }

        String[] parts = text.split("\n", -1);
        for (String part : parts) {
            if (part.isEmpty()) {
                result.add("");
                continue;
            }

            StringBuilder current = new StringBuilder();
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                String test = current.toString() + c;
                if (font.width(test) > maxWidth && current.length() > 0) {
                    result.add(current.toString());
                    current = new StringBuilder();
                    current.append(c);
                } else {
                    current.append(c);
                }
            }
            if (current.length() > 0) {
                result.add(current.toString());
            }
        }
        return result;
    }

    private static void drawBorder(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + 1, BORDER_COLOR);
        g.fill(x, y + h - 1, x + w, y + h, BORDER_COLOR);
        g.fill(x, y, x + 1, y + h, BORDER_COLOR);
        g.fill(x + w - 1, y, x + w, y + h, BORDER_COLOR);
    }
}
