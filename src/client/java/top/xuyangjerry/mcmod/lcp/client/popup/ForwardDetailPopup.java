package top.xuyangjerry.mcmod.lcp.client.popup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import top.xuyangjerry.mcmod.lcp.client.ChatScreenHandler;
import top.xuyangjerry.mcmod.lcp.client.forward.ForwardMessageManager;
import top.xuyangjerry.mcmod.lcp.client.message.MessageJumpManager;
import top.xuyangjerry.mcmod.lcp.client.message.ReplyDataManager;
import top.xuyangjerry.mcmod.lcp.client.message.ReplyHoverRenderer;
import top.xuyangjerry.mcmod.lcp.network.ForwardMessagePayload;
import top.xuyangjerry.mcmod.lcp.network.ForwardMessagePayload.ForwardedMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 转发消息详情弹窗：显示完整的转发聊天记录列表，支持滚动。
 * 弹窗内的转发消息也可以点击打开新弹窗（嵌套转发）。
 */
public class ForwardDetailPopup extends PopupWindow {

    private final ForwardMessagePayload payload;
    private final List<String> renderedLines = new ArrayList<>();
    private final List<ClickableLink> clickableLinks = new ArrayList<>();
    // 每个渲染行对应的消息索引（-1 表示空行/消息间分隔行），用于多行同步高亮
    private final List<Integer> lineMessageIndex = new ArrayList<>();

    private static final int LINE_HEIGHT = 9;
    private static final int LINE_SPACING = 2;

    public ForwardDetailPopup(int x, int y, int width, int height, String title, ForwardMessagePayload payload) {
        super(x, y, width, height, title);
        this.payload = payload;
        rebuildContent();
    }

    private void rebuildContent() {
        renderedLines.clear();
        clickableLinks.clear();
        lineMessageIndex.clear();

        if (payload == null || payload.messages() == null) return;

        Minecraft mc = Minecraft.getInstance();
        int maxWidth = getContentWidth() - 4;

        for (int msgIdx = 0; msgIdx < payload.messages().size(); msgIdx++) {
            ForwardedMessage msg = payload.messages().get(msgIdx);
            // sender 已包含尖括号（如 "<Player>"），直接拼接
            String displayText = msg.sender() + " " + msg.content();
            int lineY = renderedLines.size() * (LINE_HEIGHT + LINE_SPACING);

            // 检测是否是转发消息（包含 [Forward #FW*]）
            String forwardId = ForwardMessageManager.extractId(msg.content());
            if (forwardId != null) {
                clickableLinks.add(new ClickableLink(lineY, 0, forwardId, LinkType.FORWARD));
            }

            // 检测是否是图片消息（包含 [Image #*]）
            if (msg.content().contains("[Image #")) {
                int hashIdx = msg.content().indexOf("#");
                int endIdx = msg.content().indexOf("]", hashIdx);
                if (hashIdx > 0 && endIdx > hashIdx) {
                    try {
                        String imgIdStr = msg.content().substring(hashIdx + 1, endIdx);
                        int imgId = Integer.parseInt(imgIdStr);
                        clickableLinks.add(new ClickableLink(lineY, 0, String.valueOf(imgId), LinkType.IMAGE));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            // 检测是否是回复消息（包含 [Reply #uuid]）
            String replyUuid = ReplyDataManager.extractUuid(msg.content());
            if (replyUuid != null) {
                clickableLinks.add(new ClickableLink(lineY, 0, replyUuid, LinkType.REPLY));
            }

            // 按 font 宽度拆分多行
            List<String> wrapped = wrapText(mc, displayText, maxWidth);

            // 更新可点击链接的 height 为实际行数
            int linkHeight = wrapped.size() * (LINE_HEIGHT + LINE_SPACING);
            for (ClickableLink link : clickableLinks) {
                if (link.lineY == lineY) {
                    link.height = linkHeight;
                }
            }

            for (String line : wrapped) {
                renderedLines.add(line);
                lineMessageIndex.add(msgIdx);
            }

            // 消息间空行
            renderedLines.add("");
            lineMessageIndex.add(-1);
        }

        contentHeight = renderedLines.size() * (LINE_HEIGHT + LINE_SPACING);
        clampScrollOffset();
    }

    @Override
    protected void onResized() {
        // 窗口大小变化时重新计算文本换行和高亮索引
        rebuildContent();
    }

    private List<String> wrapText(Minecraft mc, String text, int maxWidth) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            result.add("");
            return result;
        }

        // 按已有换行符拆分
        String[] parts = text.split("\n");
        for (String part : parts) {
            if (part.isEmpty()) {
                result.add("");
                continue;
            }

            // 按像素宽度包裹
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                String test = current.toString() + c;
                if (mc.font.width(test) > maxWidth && current.length() > 0) {
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

    @Override
    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        renderFrame(g);

        Font font = Minecraft.getInstance().font;
        int contentTop = getContentTop();
        int contentLeft = getContentLeft();
        int contentW = getContentWidth();

        enableScissor(g);

        // 先计算当前 hover 的消息索引，用于多行同步高亮
        int hoverMsgIdx = -1;
        if (mouseX >= contentLeft && mouseX <= contentLeft + contentW
                && mouseY >= contentTop && mouseY <= y + height - BORDER_WIDTH) {
            int relativeY = (mouseY - contentTop) + scrollOffset;
            int lineIdx = relativeY / (LINE_HEIGHT + LINE_SPACING);
            if (lineIdx >= 0 && lineIdx < lineMessageIndex.size()) {
                hoverMsgIdx = lineMessageIndex.get(lineIdx);
            }
        }

        int lineY = contentTop - scrollOffset;

        for (int i = 0; i < renderedLines.size(); i++) {
            if (lineY + LINE_HEIGHT < getContentTop()) {
                lineY += LINE_HEIGHT + LINE_SPACING;
                continue;
            }
            if (lineY > y + height - BORDER_WIDTH) break;

            String line = renderedLines.get(i);

            // 同一消息的所有行一起高亮
            int msgIdx = lineMessageIndex.get(i);
            boolean isHover = msgIdx >= 0 && msgIdx == hoverMsgIdx;
            int color = isHover ? 0xFFFFFFAA : 0xFFCCCCCC;

            if (!line.isEmpty()) {
                g.text(font, line, contentLeft, lineY, color);
            }

            lineY += LINE_HEIGHT + LINE_SPACING;
        }

        disableScissor(g);
        renderScrollbar(g);

        // 悬浮预览：鼠标悬停在含 [Reply #uuid] 的消息上时显示被回复内容卡片
        // 必须在 disableScissor 之后调用，否则卡片会被裁剪到弹窗内
        if (hoverMsgIdx >= 0) {
            ForwardedMessage hoverMsg = payload.messages().get(hoverMsgIdx);
            ReplyHoverRenderer.tryRender(g, hoverMsg.content(), mouseX, mouseY);
        }
    }

    @Override
    protected boolean handleContentClick(int mouseX, int mouseY) {
        int contentTop = getContentTop();
        int contentLeft = getContentLeft();
        int contentW = getContentWidth();

        if (mouseX < contentLeft || mouseX > contentLeft + contentW) return false;

        int relativeY = (mouseY - contentTop) + scrollOffset;
        int lineIdx = relativeY / (LINE_HEIGHT + LINE_SPACING);
        if (lineIdx < 0 || lineIdx >= renderedLines.size()) return false;

        // 查找该行是否属于某个可点击链接
        int lineYInContent = lineIdx * (LINE_HEIGHT + LINE_SPACING);
        for (ClickableLink link : clickableLinks) {
            if (lineYInContent >= link.lineY && lineYInContent < link.lineY + link.height) {
                switch (link.type) {
                    case FORWARD -> {
                        // 打开嵌套转发弹窗
                        ForwardMessagePayload nestedPayload = ForwardMessageManager.getInstance().getForwardMessage(link.id);
                        if (nestedPayload != null) {
                            PopupWindowManager.getInstance().openForwardDetail(link.id, nestedPayload, mouseX, mouseY);
                        }
                    }
                    case IMAGE -> {
                        // 打开图片弹窗
                        try {
                            int imgId = Integer.parseInt(link.id);
                            PopupWindowManager.getInstance().openImageDetail(imgId, mouseX, mouseY);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    case REPLY -> {
                        // 跳转到聊天栏中的原始消息，并高亮3秒
                        boolean jumped = MessageJumpManager.getInstance().jumpToMessageById(link.id);
                        if (jumped) {
                            ChatScreenHandler.requestHighlight(link.id);
                        } else {
                            // 跳转失败提示
                            Minecraft mc = Minecraft.getInstance();
                            if (mc.gui != null && mc.gui.hud.getChat() != null) {
                                mc.gui.hud.getChat().addClientSystemMessage(
                                        Component.literal("§7原始消息不在当前聊天范围内"));
                            }
                        }
                    }
                }
                return true;
            }
        }

        return true; // 消耗点击但不执行操作
    }

    /** 链接类型枚举 */
    private enum LinkType {
        FORWARD, IMAGE, REPLY
    }

    private static class ClickableLink {
        int lineY;
        int height;
        final String id;
        final LinkType type;

        ClickableLink(int lineY, int height, String id, LinkType type) {
            this.lineY = lineY;
            this.height = height;
            this.id = id;
            this.type = type;
        }
    }
}
