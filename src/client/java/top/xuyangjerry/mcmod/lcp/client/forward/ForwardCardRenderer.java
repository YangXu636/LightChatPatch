package top.xuyangjerry.mcmod.lcp.client.forward;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import top.xuyangjerry.mcmod.lcp.network.ForwardMessagePayload;

import java.util.List;

/**
 * 合并转发消息卡片渲染器（QQ 风格）。
 * 当鼠标悬停在包含 [Forward #N] 标签的消息上时，显示合并转发卡片。
 *
 * 卡片布局：
 * ┌───────────────────────────────┐
 * │  ── [转发的聊天记录] ──        │  ← 标题栏
 * │  <Steve> 你们明天打副本吗？    │  ← 消息预览（最多5条）
 * │  <Alex> 打啊，几点？           │
 * │  <Steve> 晚上8点              │
 * │  ... 共 5 条消息              │  ← 底部统计
 * └───────────────────────────────┘
 */
public final class ForwardCardRenderer {

    private static final int CARD_MAX_WIDTH = 280;
    private static final int CARD_PADDING = 10;
    private static final int PREVIEW_MAX_LINES = 5;
    private static final int TITLE_PADDING = 6;
    private static final int LINE_SPACING = 2;
    private static final int BORDER_COLOR = 0xFF505050;
    private static final int BG_COLOR = 0xF01A1A1A;
    private static final int TITLE_COLOR = 0xFFAAAAAA;
    private static final int SENDER_COLOR = 0xFF66CCFF;
    private static final int CONTENT_COLOR = 0xFFCCCCCC;
    private static final int FOOTER_COLOR = 0xFF888888;
    private static final int OFFSET_X = 12;
    private static final int OFFSET_Y = 12;

    private ForwardCardRenderer() {
    }

    private static long lastLogTime = 0;
    private static String lastDebugText = "";

    public static boolean tryRender(GuiGraphicsExtractor g, String hoverText, int mouseX, int mouseY) {
        String forwardId = ForwardMessageManager.extractId(hoverText);
        if (forwardId == null) {
            return false;
        }

        // 调试：每次hover到不同文本时输出一次
        if (!hoverText.equals(lastDebugText)) {
            lastDebugText = hoverText;
            top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER
                    .debug("[ForwardCardRenderer] tryRender: extractedId={}, hoverText={}",
                            forwardId, hoverText.length() > 80 ? hoverText.substring(0, 80) + "..." : hoverText);
        }

        ForwardMessagePayload payload = ForwardMessageManager.getInstance().getForwardMessage(forwardId);
        if (payload == null) {
            // 限制日志频率，每秒最多输出一次
            long now = System.currentTimeMillis();
            if (now - lastLogTime > 1000) {
                top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER
                        .warn("[LCP] Forward card render failed: payload not found for id={}", forwardId);
                lastLogTime = now;
            }
            return false;
        }

        renderCard(g, payload, mouseX, mouseY);
        return true;
    }

    private static void renderCard(GuiGraphicsExtractor g, ForwardMessagePayload payload, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        var font = mc.font;

        List<ForwardMessagePayload.ForwardedMessage> messages = payload.messages();
        int total = messages.size();

        String titleText = Component.translatable("light_chat_patch.forward.card_title",
                payload.forwarder()).getString();
        String footerText = Component.translatable("light_chat_patch.forward.card_footer", total).getString();

        int contentMaxWidth = CARD_MAX_WIDTH - CARD_PADDING * 2;

        // 计算卡片高度
        int titleHeight = font.lineHeight + TITLE_PADDING * 2;
        int previewCount = Math.min(PREVIEW_MAX_LINES, total);
        int previewHeight = previewCount * (font.lineHeight + LINE_SPACING);
        int footerHeight = font.lineHeight + 4;
        int cardHeight = CARD_PADDING + titleHeight + previewHeight + footerHeight + CARD_PADDING;
        int cardWidth = CARD_MAX_WIDTH;

        // 定位卡片
        int x = mouseX + OFFSET_X;
        int y = mouseY + OFFSET_Y;
        if (x + cardWidth > screenW) {
            x = mouseX - OFFSET_X - cardWidth;
        }
        if (y + cardHeight > screenH) {
            y = mouseY - OFFSET_Y - cardHeight;
        }
        if (x < 0) x = Math.max(0, screenW - cardWidth);
        if (y < 0) y = Math.max(0, screenH - cardHeight);

        // 背景和边框
        g.fill(x, y, x + cardWidth, y + cardHeight, BG_COLOR);
        drawBorder(g, x, y, cardWidth, cardHeight);

        int contentX = x + CARD_PADDING;
        int contentWidth = cardWidth - CARD_PADDING * 2;
        int curY = y + CARD_PADDING;

        // 标题：  ── [XXX 转发的聊天记录] ──
        int titleW = font.width(titleText);
        int lineW = (contentWidth - titleW) / 2 - 4;
        if (lineW > 0) {
            int lineY = curY + TITLE_PADDING + font.lineHeight / 2;
            g.fill(contentX, lineY, contentX + lineW, lineY + 1, BORDER_COLOR);
            g.fill(contentX + contentWidth - lineW, lineY, contentX + contentWidth, lineY + 1, BORDER_COLOR);
        }
        g.centeredText(font, titleText, x + cardWidth / 2, curY + TITLE_PADDING, TITLE_COLOR);
        curY += titleHeight;

        // 消息预览
        // QQ来源（forwarder以"["开头）的转发消息，子消息前缀应为 [sender] 而非 sender
        boolean fromQq = payload.forwarder() != null && payload.forwarder().startsWith("[");
        for (int i = 0; i < previewCount; i++) {
            ForwardMessagePayload.ForwardedMessage msg = messages.get(i);
            String rawSender = msg.sender();
            String sender = fromQq ? ("[" + rawSender + "] ") : (rawSender + " ");
            int senderW = font.width(sender);
            g.text(font, sender, contentX, curY, SENDER_COLOR);
            String content = truncate(msg.content(), contentWidth - senderW, font);
            g.text(font, content, contentX + senderW, curY, CONTENT_COLOR);
            curY += font.lineHeight + LINE_SPACING;
        }

        // 底部统计
        curY += 2;
        g.text(font, footerText, contentX, curY, FOOTER_COLOR);
    }

    private static String truncate(String text, int maxWidth, net.minecraft.client.gui.Font font) {
        if (text == null || text.isEmpty()) return "";
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "...";
        int ellipsisW = font.width(ellipsis);
        if (ellipsisW >= maxWidth) return ellipsis;
        int available = maxWidth - ellipsisW;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            sb.append(text.charAt(i));
            if (font.width(sb.toString()) > available) {
                sb.deleteCharAt(sb.length() - 1);
                break;
            }
        }
        return sb + ellipsis;
    }

    private static void drawBorder(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + 1, BORDER_COLOR);
        g.fill(x, y + h - 1, x + w, y + h, BORDER_COLOR);
        g.fill(x, y, x + 1, y + h, BORDER_COLOR);
        g.fill(x + w - 1, y, x + w, y + h, BORDER_COLOR);
    }
}
