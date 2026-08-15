package top.xuyangjerry.mcmod.lcp.client.popup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.Identifier;
import top.xuyangjerry.mcmod.lcp.client.nonebot.ClientImageCache;
import top.xuyangjerry.mcmod.lcp.client.nonebot.ClientImageCache.ImageEntry;

/**
 * 图片详情弹窗：显示原图，支持鼠标滚轮缩放和拖拽平移。
 * 图片渲染被裁剪在弹窗内容区内，超出部分不显示。
 */
public class ImageDetailPopup extends PopupWindow {

    private final int imageId;
    private float zoom = 1.0f;
    private int imageWidth = 0;
    private int imageHeight = 0;

    // 平移偏移（相对于图片中心）
    private int panX = 0;
    private int panY = 0;
    private boolean panning = false;
    private int panStartX, panStartY, panStartPanX, panStartPanY;

    public ImageDetailPopup(int x, int y, int width, int height, int imageId) {
        super(x, y, width, height, "图片 #" + imageId);
        this.imageId = imageId;

        ImageEntry entry = ClientImageCache.getImage(imageId);
        if (entry != null) {
            this.imageWidth = entry.width();
            this.imageHeight = entry.height();
            // 初始缩放：计算最大的完整图片缩放比例
            fitToWindow();
        }
    }

    /**
     * 计算并应用最大的完整图片缩放比例。
     */
    private void fitToWindow() {
        if (imageWidth <= 0 || imageHeight <= 0) return;
        int availW = width - 6 - SCROLLBAR_WIDTH;
        int availH = height - TITLE_BAR_HEIGHT - 4;
        float scaleX = (float) availW / imageWidth;
        float scaleY = (float) availH / imageHeight;
        zoom = Math.min(scaleX, scaleY);
        if (zoom > 1.0f) zoom = 1.0f; // 不放大超过原图
        panX = 0;
        panY = 0;
    }

    @Override
    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        renderFrame(g);

        ImageEntry entry = ClientImageCache.getImage(imageId);
        if (entry == null) {
            Font font = Minecraft.getInstance().font;
            g.text(font, "图片加载中...", getContentLeft(), getContentTop(), 0xFFAAAAAA);
            return;
        }

        int drawW = (int) (imageWidth * zoom);
        int drawH = (int) (imageHeight * zoom);

        // 内容区域
        int contentTop = getContentTop();
        int contentLeft = getContentLeft();
        int availW = width - 6 - SCROLLBAR_WIDTH;
        int availH = height - TITLE_BAR_HEIGHT - 4;

        // 图片居中 + 平移偏移
        int drawX = x + 2 + (availW - drawW) / 2 + panX;
        int drawY = contentTop + (availH - drawH) / 2 + panY;

        // 裁剪到内容区
        g.enableScissor(x + BORDER_WIDTH, contentTop,
                x + width - BORDER_WIDTH - SCROLLBAR_WIDTH, y + height - BORDER_WIDTH);

        Identifier location = entry.location();
        // 使用带 UV 坐标的 blit 重载，避免图片平铺
        g.blit(location, drawX, drawY, drawX + drawW, drawY + drawH,
                0.0f, 1.0f, 0.0f, 1.0f);

        g.disableScissor();

        // 显示缩放比例和尺寸
        Font font = Minecraft.getInstance().font;
        String zoomText = String.format("%d%% (%dx%d)", (int) (zoom * 100), imageWidth, imageHeight);
        g.text(font, zoomText, x + 4, y + height - 12, 0xFF888888);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollAmount) {
        if (contains(mouseX, mouseY)) {
            float factor = scrollAmount > 0 ? 1.15f : 1f / 1.15f;
            float newZoom = zoom * factor;
            if (newZoom < 0.05f) newZoom = 0.05f;
            if (newZoom > 10.0f) newZoom = 10.0f;
            zoom = newZoom;
            clampPan(); // 缩放后重新限制平移范围
            return true;
        }
        return false;
    }

    @Override
    public void mouseDragged(int mouseX, int mouseY, int button) {
        if (dragging || resizeDirection != ResizeDirection.NONE) {
            super.mouseDragged(mouseX, mouseY, button);
            if (resizeDirection != ResizeDirection.NONE) {
                clampPan(); // 窗口大小变化后重新限制平移范围
            }
        } else if (panning) {
            panX = panStartPanX + (mouseX - panStartX);
            panY = panStartPanY + (mouseY - panStartY);
            clampPan(); // 拖拽时实时限制平移范围
        }
    }

    @Override
    protected void onResized() {
        clampPan();
    }

    /**
     * 限制图片平移范围：
     * - 图片小于内容区：图片中心与内容区中心对齐（panX=panY=0）
     * - 图片大于内容区：图片外框不得进入内容区（最多边界重合）
     *   即 panX ∈ [-overflowX/2, overflowX/2]，panY 同理
     */
    private void clampPan() {
        if (imageWidth <= 0 || imageHeight <= 0) return;
        int drawW = (int) (imageWidth * zoom);
        int drawH = (int) (imageHeight * zoom);
        int availW = width - 6 - SCROLLBAR_WIDTH;
        int availH = height - TITLE_BAR_HEIGHT - 4;

        int overflowX = drawW - availW;
        int overflowY = drawH - availH;

        if (overflowX <= 0) {
            // 图片小于内容区：固定居中，不能平移
            panX = 0;
        } else {
            // 图片大于内容区：最多边界重合
            panX = Math.max(-overflowX / 2, Math.min(panX, overflowX / 2));
        }

        if (overflowY <= 0) {
            panY = 0;
        } else {
            panY = Math.max(-overflowY / 2, Math.min(panY, overflowY / 2));
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int button) {
        super.mouseReleased(mouseX, mouseY, button);
        panning = false;
    }

    @Override
    protected boolean handleContentClick(int mouseX, int mouseY) {
        // 内容区点击：开始拖拽平移图片
        panning = true;
        panStartX = mouseX;
        panStartY = mouseY;
        panStartPanX = panX;
        panStartPanY = panY;
        return true;
    }
}
