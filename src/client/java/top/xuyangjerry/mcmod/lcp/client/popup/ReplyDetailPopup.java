package top.xuyangjerry.mcmod.lcp.client.popup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import top.xuyangjerry.mcmod.lcp.client.message.MessageJumpManager;
import top.xuyangjerry.mcmod.lcp.network.ReplyMessagePayload;

/**
 * 回复消息详情弹窗：显示被回复消息的完整内容和回复内容。
 * 支持点击跳转到聊天栏中的原始消息。
 */
public class ReplyDetailPopup extends PopupWindow {

    private final ReplyMessagePayload payload;

    private static final int LINE_HEIGHT = 9;
    private static final int LINE_SPACING = 2;

    public ReplyDetailPopup(int x, int y, int width, int height, ReplyMessagePayload payload) {
        super(x, y, width, height, "回复详情");
        this.payload = payload;
        contentHeight = 80; // 固定内容高度
    }

    @Override
    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        renderFrame(g);

        Font font = Minecraft.getInstance().font;
        int contentTop = getContentTop();
        int contentLeft = getContentLeft();
        int contentW = getContentWidth();

        int curY = contentTop;

        // 被回复者
        String senderLine = "\u21A9 <" + payload.originalSender() + ">";
        g.text(font, senderLine, contentLeft, curY, 0xFF66CCFF);
        curY += LINE_HEIGHT + LINE_SPACING;

        // 分隔线
        g.fill(contentLeft, curY, contentLeft + contentW, curY + 1, 0xFF404040);
        curY += 3;

        // 被回复内容
        String originalContent = payload.originalContent();
        int contentMaxWidth = contentW - 4;
        java.util.List<String> wrappedLines = wrapText(font, originalContent, contentMaxWidth);
        for (String line : wrappedLines) {
            g.text(font, line, contentLeft, curY, 0xFFCCCCCC);
            curY += LINE_HEIGHT + LINE_SPACING;
        }

        curY += 4;
        g.fill(contentLeft, curY, contentLeft + contentW, curY + 1, 0xFF404040);
        curY += 3;

        // 回复者
        String replySenderLine = "<" + payload.sender() + ">";
        g.text(font, replySenderLine, contentLeft, curY, 0xFF66CCFF);
        curY += LINE_HEIGHT + LINE_SPACING;

        // 回复内容
        String replyContent = payload.replyContent();
        java.util.List<String> replyLines = wrapText(font, replyContent, contentMaxWidth);
        for (String line : replyLines) {
            g.text(font, line, contentLeft, curY, 0xFFFFFFFF);
            curY += LINE_HEIGHT + LINE_SPACING;
        }
    }

    @Override
    protected boolean handleContentClick(int mouseX, int mouseY) {
        // 点击弹窗内容区：尝试跳转到原始消息
        String uuid = payload.originalMessageUuid();
        if (uuid != null && !uuid.isEmpty()) {
            MessageJumpManager.getInstance().jumpToMessageById(uuid);
        }
        return true;
    }

    private java.util.List<String> wrapText(Font font, String text, int maxWidth) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) {
            result.add("");
            return result;
        }

        String[] parts = text.split("\n");
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
}
