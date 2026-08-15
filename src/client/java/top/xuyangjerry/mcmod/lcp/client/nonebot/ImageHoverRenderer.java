package top.xuyangjerry.mcmod.lcp.client.nonebot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import top.xuyangjerry.mcmod.lcp.client.nonebot.ClientImageCache.ImageEntry;

public final class ImageHoverRenderer {

    private static final float MAX_WIDTH_RATIO = 0.5f;
    private static final float MAX_HEIGHT_RATIO = 0.6f;
    private static final int PADDING = 4;
    private static final int OFFSET_X = 12;
    private static final int OFFSET_Y = 12;

    private ImageHoverRenderer() {
    }

    public static boolean tryRender(GuiGraphicsExtractor g, String hoverText, int mouseX, int mouseY) {
        if (hoverText == null || hoverText.isEmpty()) return false;

        int imageId = ClientImageCache.extractImageId(hoverText);
        if (imageId < 0) return false;

        ImageEntry entry = ClientImageCache.getImage(imageId);
        if (entry == null) {
            renderPlaceholder(g, mouseX, mouseY,
                    Component.translatable("light_chat_patch.image.expired").getString());
            return true;
        }

        renderImagePopup(g, entry, mouseX, mouseY);
        return true;
    }

    private static void renderImagePopup(GuiGraphicsExtractor g, ImageEntry entry, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // 固定预览框大小（不随图片尺寸变化）
        int boxW = (int) (screenW * MAX_WIDTH_RATIO);
        int boxH = (int) (screenH * MAX_HEIGHT_RATIO);

        int x = mouseX + OFFSET_X;
        int y = mouseY + OFFSET_Y;

        if (x + boxW > screenW) {
            x = mouseX - OFFSET_X - boxW;
        }
        if (y + boxH > screenH) {
            y = mouseY - OFFSET_Y - boxH;
        }
        if (x < 0) x = Math.max(0, screenW - boxW);
        if (y < 0) y = Math.max(0, screenH - boxH);

        g.fill(x, y, x + boxW, y + boxH, argb(220, 16, 16, 16));
        drawBorder(g, x, y, boxW, boxH);

        // 计算图片在框内的缩放比例，始终显示整张图片
        int availW = boxW - PADDING * 2;
        int availH = boxH - PADDING * 2;
        int imgW = entry.width();
        int imgH = entry.height();
        if (imgW <= 0 || imgH <= 0) {
            imgW = availW;
            imgH = availH;
        }

        // 缩放到框内（保持宽高比），无论图片比框大还是小都缩放
        double ratio = Math.min((double) availW / imgW, (double) availH / imgH);
        int drawW = (int) Math.max(1, Math.round(imgW * ratio));
        int drawH = (int) Math.max(1, Math.round(imgH * ratio));

        // 居中放置
        int imgX = x + (boxW - drawW) / 2;
        int imgY = y + (boxH - drawH) / 2;

        Identifier location = entry.location();
        // 裁剪到预览框内容区，确保图片不超出框
        g.enableScissor(x + PADDING, y + PADDING, x + boxW - PADDING, y + boxH - PADDING);
        try {
            // 使用 UV 0.0-1.0 映射，确保缩小时显示整张图片而非只取左上角
            g.blit(location, imgX, imgY, imgX + drawW, imgY + drawH,
                    0.0f, 1.0f, 0.0f, 1.0f);
        } finally {
            g.disableScissor();
        }

        String sizeLabel = entry.width() + "x" + entry.height();
        int labelW = mc.font.width(sizeLabel);
        int labelX = x + boxW - labelW - PADDING;
        int labelY = y + boxH - mc.font.lineHeight - 2;
        g.text(mc.font, sizeLabel, labelX, labelY, 0xFFFFFFFF, true);
    }

    private static void renderPlaceholder(GuiGraphicsExtractor g, int mouseX, int mouseY, String text) {
        Minecraft mc = Minecraft.getInstance();
        int textW = mc.font.width(text);
        int boxW = textW + PADDING * 2;
        int boxH = mc.font.lineHeight + PADDING * 2;

        int x = mouseX + OFFSET_X;
        int y = mouseY + OFFSET_Y;
        if (x + boxW > mc.getWindow().getGuiScaledWidth()) {
            x = mouseX - OFFSET_X - boxW;
        }
        if (y + boxH > mc.getWindow().getGuiScaledHeight()) {
            y = mouseY - OFFSET_Y - boxH;
        }

        g.fill(x, y, x + boxW, y + boxH, argb(220, 16, 16, 16));
        drawBorder(g, x, y, boxW, boxH);
        g.text(mc.font, text, x + PADDING, y + PADDING, 0xFFCCCCCC, true);
    }

    private static void drawBorder(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        int color = 0xFF606060;
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private static int argb(int a, int r, int g, int b) {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
