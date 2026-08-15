package top.xuyangjerry.mcmod.lcp.client.popup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import org.lwjgl.glfw.GLFW;

/**
 * 弹窗基类：支持拖动、关闭、八方向调整大小、滚动。
 * 弹窗之间有层级遮盖，Esc 从最上层开始关闭。
 */
public abstract class PopupWindow {

    protected int x, y, width, height;
    protected String title;
    protected boolean dragging = false;
    protected int dragOffsetX, dragOffsetY;
    protected int scrollOffset = 0;
    protected int contentHeight = 0;

    // 调整大小相关
    protected ResizeDirection resizeDirection = ResizeDirection.NONE;
    protected int resizeStartX, resizeStartY, resizeStartW, resizeStartH;
    protected int resizeStartWindowX, resizeStartWindowY;

    // 鼠标指针相关
    private static long arrowCursor = 0;
    private static long nsCursor = 0;
    private static long weCursor = 0;
    private static long nwseCursor = 0;
    private static long neswCursor = 0;
    private static boolean cursorsInitialized = false;
    private static long lastCursorShape = -1;

    // GLFW 标准光标形状常量值（LWJGL 3.4.1 Java 绑定使用 GLFW 3.4+ 新常量）
    private static final int CURSOR_ARROW = 0x00036001;
    private static final int CURSOR_NS = 0x0003600A;    // GLFW_RESIZE_NS_CURSOR
    private static final int CURSOR_WE = 0x0003600B;    // GLFW_RESIZE_WE_CURSOR
    private static final int CURSOR_NWSE = 0x00036007;  // GLFW_RESIZE_NWSE_CURSOR
    private static final int CURSOR_NESW = 0x00036008;  // GLFW_RESIZE_NESW_CURSOR

    protected static final int TITLE_BAR_HEIGHT = 14;
    protected static final int BORDER_WIDTH = 1;
    protected static final int SCROLLBAR_WIDTH = 6;
    protected static final int MIN_WIDTH = 120;
    protected static final int MIN_HEIGHT = 80;
    protected static final int CLOSE_BUTTON_SIZE = 10;
    protected static final int RESIZE_HANDLE_SIZE = 6;

    /**
     * 缩放方向枚举
     */
    protected enum ResizeDirection {
        NONE,
        TOP, BOTTOM, LEFT, RIGHT,
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    public PopupWindow(int x, int y, int width, int height, String title) {
        this.x = x;
        this.y = y;
        this.width = Math.max(width, MIN_WIDTH);
        this.height = Math.max(height, MIN_HEIGHT);
        this.title = title;
    }

    public abstract void render(GuiGraphicsExtractor g, int mouseX, int mouseY);

    /**
     * 处理鼠标点击。返回 true 如果消耗了点击（不穿透到下层）。
     */
    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (button != 0) return false;

        // 关闭按钮
        if (isCloseButtonClicked(mouseX, mouseY)) {
            return true; // 由 manager 处理关闭
        }

        // 检查缩放手柄（优先于标题栏，因为角落区域可能重叠）
        ResizeDirection dir = getResizeDirection(mouseX, mouseY);
        if (dir != ResizeDirection.NONE) {
            resizeDirection = dir;
            resizeStartX = mouseX;
            resizeStartY = mouseY;
            resizeStartW = width;
            resizeStartH = height;
            resizeStartWindowX = x;
            resizeStartWindowY = y;
            return true;
        }

        // 标题栏拖动（拖动时保持窗口大小不变）
        if (mouseY >= y && mouseY <= y + TITLE_BAR_HEIGHT
                && mouseX >= x && mouseX <= x + width) {
            dragging = true;
            dragOffsetX = mouseX - x;
            dragOffsetY = mouseY - y;
            return true;
        }

        // 滚动条
        if (mouseX >= x + width - SCROLLBAR_WIDTH - BORDER_WIDTH
                && mouseX <= x + width - BORDER_WIDTH
                && mouseY >= y + TITLE_BAR_HEIGHT && mouseY <= y + height - BORDER_WIDTH) {
            return handleScrollbarClick(mouseY);
        }

        // 内容区点击
        return handleContentClick(mouseX, mouseY);
    }

    /**
     * 根据鼠标位置判断缩放方向。
     * 标题栏中间区域不触发缩放（仅角落可触发），避免与标题栏拖动冲突。
     */
    protected ResizeDirection getResizeDirection(int mouseX, int mouseY) {
        boolean atLeft = mouseX >= x - RESIZE_HANDLE_SIZE && mouseX <= x + RESIZE_HANDLE_SIZE;
        boolean atRight = mouseX >= x + width - RESIZE_HANDLE_SIZE && mouseX <= x + width + RESIZE_HANDLE_SIZE;
        boolean atTop = mouseY >= y - RESIZE_HANDLE_SIZE && mouseY <= y + RESIZE_HANDLE_SIZE;
        boolean atBottom = mouseY >= y + height - RESIZE_HANDLE_SIZE && mouseY <= y + height + RESIZE_HANDLE_SIZE;
        boolean inTitleBar = mouseY >= y && mouseY <= y + TITLE_BAR_HEIGHT;

        // 角落优先（即使在标题栏内，角落仍可缩放）
        if (atTop && atLeft) return ResizeDirection.TOP_LEFT;
        if (atTop && atRight) return ResizeDirection.TOP_RIGHT;
        if (atBottom && atLeft) return ResizeDirection.BOTTOM_LEFT;
        if (atBottom && atRight) return ResizeDirection.BOTTOM_RIGHT;

        // 标题栏中间区域不触发边缩放，保留给标题栏拖动
        if (inTitleBar) return ResizeDirection.NONE;

        // 边
        if (atTop) return ResizeDirection.TOP;
        if (atBottom) return ResizeDirection.BOTTOM;
        if (atLeft) return ResizeDirection.LEFT;
        if (atRight) return ResizeDirection.RIGHT;

        return ResizeDirection.NONE;
    }

    /**
     * 处理鼠标拖动（拖动窗口或调整大小）。
     */
    public void mouseDragged(int mouseX, int mouseY, int button) {
        if (dragging) {
            // 拖动标题栏：仅改变位置，保持窗口大小不变
            x = mouseX - dragOffsetX;
            y = mouseY - dragOffsetY;
            // 允许部分在游戏窗口外，但至少保留标题栏可见
            Minecraft mc = Minecraft.getInstance();
            int screenW = mc.getWindow().getGuiScaledWidth();
            int screenH = mc.getWindow().getGuiScaledHeight();
            // 至少保留 20px 的标题栏可见
            if (x > screenW - 20) x = screenW - 20;
            if (x + width < 20) x = 20 - width;
            if (y > screenH - 20) y = screenH - 20;
            if (y + TITLE_BAR_HEIGHT < 0) y = -TITLE_BAR_HEIGHT;
        } else if (resizeDirection != ResizeDirection.NONE) {
            int dx = mouseX - resizeStartX;
            int dy = mouseY - resizeStartY;

            int newX = resizeStartWindowX;
            int newY = resizeStartWindowY;
            int newW = resizeStartW;
            int newH = resizeStartH;

            // 根据方向调整
            switch (resizeDirection) {
                case LEFT -> {
                    newX = resizeStartWindowX + dx;
                    newW = resizeStartW - dx;
                }
                case RIGHT -> {
                    newW = resizeStartW + dx;
                }
                case TOP -> {
                    newY = resizeStartWindowY + dy;
                    newH = resizeStartH - dy;
                }
                case BOTTOM -> {
                    newH = resizeStartH + dy;
                }
                case TOP_LEFT -> {
                    newX = resizeStartWindowX + dx;
                    newW = resizeStartW - dx;
                    newY = resizeStartWindowY + dy;
                    newH = resizeStartH - dy;
                }
                case TOP_RIGHT -> {
                    newW = resizeStartW + dx;
                    newY = resizeStartWindowY + dy;
                    newH = resizeStartH - dy;
                }
                case BOTTOM_LEFT -> {
                    newX = resizeStartWindowX + dx;
                    newW = resizeStartW - dx;
                    newH = resizeStartH + dy;
                }
                case BOTTOM_RIGHT -> {
                    newW = resizeStartW + dx;
                    newH = resizeStartH + dy;
                }
            }

            // 最小尺寸限制：如果缩小到最小以下，调整位置使窗口不会"移动"
            if (newW < MIN_WIDTH) {
                if (resizeDirection == ResizeDirection.LEFT
                        || resizeDirection == ResizeDirection.TOP_LEFT
                        || resizeDirection == ResizeDirection.BOTTOM_LEFT) {
                    newX = resizeStartWindowX + (resizeStartW - MIN_WIDTH);
                }
                newW = MIN_WIDTH;
            }
            if (newH < MIN_HEIGHT) {
                if (resizeDirection == ResizeDirection.TOP
                        || resizeDirection == ResizeDirection.TOP_LEFT
                        || resizeDirection == ResizeDirection.TOP_RIGHT) {
                    newY = resizeStartWindowY + (resizeStartH - MIN_HEIGHT);
                }
                newH = MIN_HEIGHT;
            }

            x = newX;
            y = newY;
            width = newW;
            height = newH;
            clampScrollOffset();
            onResized();
        }
    }

    /**
     * 子类可重写此方法，在窗口大小变化时重新计算内容布局。
     */
    protected void onResized() {
    }

    /**
     * 处理鼠标释放。
     */
    public void mouseReleased(int mouseX, int mouseY, int button) {
        dragging = false;
        resizeDirection = ResizeDirection.NONE;
    }

    /**
     * 处理鼠标滚轮。返回 true 如果消耗了滚动。
     */
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollAmount) {
        if (contains(mouseX, mouseY)) {
            scrollOffset -= (int) (scrollAmount * 12);
            clampScrollOffset();
            return true;
        }
        return false;
    }

    /**
     * 判断点是否在弹窗内（用于点击穿透判断）。
     */
    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    /**
     * 判断是否点击了关闭按钮。
     */
    public boolean isCloseButtonClicked(int mouseX, int mouseY) {
        int closeX = x + width - CLOSE_BUTTON_SIZE - 2;
        int closeY = y + 2;
        return mouseX >= closeX && mouseX <= closeX + CLOSE_BUTTON_SIZE
                && mouseY >= closeY && mouseY <= closeY + CLOSE_BUTTON_SIZE;
    }

    public String getTitle() {
        return title;
    }

    /**
     * 是否正在交互（拖拽或缩放），用于光标限制等。
     */
    public boolean isInteracting() {
        return dragging || resizeDirection != ResizeDirection.NONE;
    }

    /**
     * 更新鼠标指针样式。应在渲染时调用。
     */
    public void updateCursor(int mouseX, int mouseY) {
        long shape = getCursorShape(mouseX, mouseY);
        setCursor(shape);
    }

    protected long getCursorShape(int mouseX, int mouseY) {
        if (dragging) {
            return CURSOR_ARROW;
        }
        ResizeDirection dir = resizeDirection != ResizeDirection.NONE ? resizeDirection : getResizeDirection(mouseX, mouseY);
        return switch (dir) {
            case TOP, BOTTOM -> (long) CURSOR_NS;
            case LEFT, RIGHT -> (long) CURSOR_WE;
            case TOP_LEFT, BOTTOM_RIGHT -> (long) CURSOR_NWSE;
            case TOP_RIGHT, BOTTOM_LEFT -> (long) CURSOR_NESW;
            default -> (long) CURSOR_ARROW;
        };
    }

    /**
     * 设置鼠标指针（缓存指针句柄，避免重复创建）。
     */
    protected static void setCursor(long shape) {
        if (shape == lastCursorShape) return;
        lastCursorShape = shape;

        ensureCursorsInitialized();
        long cursor = switch ((int) shape) {
            case CURSOR_NS -> nsCursor;
            case CURSOR_WE -> weCursor;
            case CURSOR_NWSE -> nwseCursor;
            case CURSOR_NESW -> neswCursor;
            default -> arrowCursor;
        };
        long window = GLFW.glfwGetCurrentContext();
        GLFW.glfwSetCursor(window, cursor);
    }

    private static void ensureCursorsInitialized() {
        if (cursorsInitialized) return;
        arrowCursor = GLFW.glfwCreateStandardCursor(CURSOR_ARROW);
        nsCursor = GLFW.glfwCreateStandardCursor(CURSOR_NS);
        weCursor = GLFW.glfwCreateStandardCursor(CURSOR_WE);
        nwseCursor = GLFW.glfwCreateStandardCursor(CURSOR_NWSE);
        neswCursor = GLFW.glfwCreateStandardCursor(CURSOR_NESW);
        cursorsInitialized = true;
    }

    /**
     * 恢复默认鼠标指针。
     */
    public static void restoreDefaultCursor() {
        setCursor(CURSOR_ARROW);
    }

    protected void renderFrame(GuiGraphicsExtractor g) {
        // 背景
        g.fill(x, y, x + width, y + height, 0xF0202020);
        // 边框
        int border = 0xFF606060;
        g.fill(x, y, x + width, y + 1, border);
        g.fill(x, y + height - 1, x + width, y + height, border);
        g.fill(x, y, x + 1, y + height, border);
        g.fill(x + width - 1, y, x + width, y + height, border);

        // 标题栏背景
        g.fill(x + 1, y + 1, x + width - 1, y + TITLE_BAR_HEIGHT, 0xE0333333);

        Font font = Minecraft.getInstance().font;
        // 标题文字
        g.text(font, title, x + 4, y + 3, 0xFFFFFFFF);

        // 关闭按钮
        int closeX = x + width - CLOSE_BUTTON_SIZE - 2;
        int closeY = y + 2;
        g.fill(closeX, closeY, closeX + CLOSE_BUTTON_SIZE, closeY + CLOSE_BUTTON_SIZE, 0xE0AA3333);
        g.text(font, "x", closeX + 2, closeY + 1, 0xFFFFFFFF);

        // 调整大小手柄（四角和四边）
        renderResizeHandles(g);
    }

    /**
     * 渲染缩放手柄的视觉提示。
     */
    protected void renderResizeHandles(GuiGraphicsExtractor g) {
        int handleColor = 0xFF505050;
        int sz = RESIZE_HANDLE_SIZE;

        // 四角
        // 左上
        g.fill(x, y, x + sz, y + 1, handleColor);
        g.fill(x, y, x + 1, y + sz, handleColor);
        // 右上
        g.fill(x + width - sz, y, x + width, y + 1, handleColor);
        g.fill(x + width - 1, y, x + width, y + sz, handleColor);
        // 左下
        g.fill(x, y + height - 1, x + sz, y + height, handleColor);
        g.fill(x, y + height - sz, x + 1, y + height, handleColor);
        // 右下
        g.fill(x + width - sz, y + height - 1, x + width, y + height, handleColor);
        g.fill(x + width - 1, y + height - sz, x + width, y + height, handleColor);

        // 四边中间标记
        int midX = x + width / 2;
        int midY = y + height / 2;
        // 上边
        g.fill(midX - sz / 2, y, midX + sz / 2, y + 1, handleColor);
        // 下边
        g.fill(midX - sz / 2, y + height - 1, midX + sz / 2, y + height, handleColor);
        // 左边
        g.fill(x, midY - sz / 2, x + 1, midY + sz / 2, handleColor);
        // 右边
        g.fill(x + width - 1, midY - sz / 2, x + width, midY + sz / 2, handleColor);
    }

    protected void renderScrollbar(GuiGraphicsExtractor g) {
        if (contentHeight <= 0) return;
        int visibleHeight = height - TITLE_BAR_HEIGHT - 2;
        if (contentHeight <= visibleHeight) return;

        int scrollbarX = x + width - SCROLLBAR_WIDTH - BORDER_WIDTH;
        int scrollbarY = y + TITLE_BAR_HEIGHT + 1;
        int scrollbarH = visibleHeight - 2;

        // 滚动条背景
        g.fill(scrollbarX, scrollbarY, scrollbarX + SCROLLBAR_WIDTH, scrollbarY + scrollbarH, 0x40404040);

        // 滚动条滑块
        int thumbH = Math.max(10, scrollbarH * scrollbarH / contentHeight);
        int maxScroll = contentHeight - visibleHeight;
        int thumbY = scrollbarY + (maxScroll > 0 ? scrollOffset * (scrollbarH - thumbH) / maxScroll : 0);
        g.fill(scrollbarX, thumbY, scrollbarX + SCROLLBAR_WIDTH, thumbY + thumbH, 0xFF808080);
    }

    protected int getContentTop() {
        return y + TITLE_BAR_HEIGHT + 2;
    }

    protected int getContentHeight() {
        return height - TITLE_BAR_HEIGHT - 4;
    }

    protected int getContentLeft() {
        return x + BORDER_WIDTH + 2;
    }

    protected int getContentWidth() {
        return width - BORDER_WIDTH * 2 - 4 - SCROLLBAR_WIDTH;
    }

    protected void clampScrollOffset() {
        int maxScroll = Math.max(0, contentHeight - getContentHeight());
        if (scrollOffset < 0) scrollOffset = 0;
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
    }

    protected boolean handleScrollbarClick(int mouseY) {
        return true; // 简化：点击滚动条区域仅聚焦，实际滚动用滚轮
    }

    protected abstract boolean handleContentClick(int mouseX, int mouseY);

    /**
     * 启用裁剪区域，使内容不溢出弹窗边界。
     */
    protected void enableScissor(GuiGraphicsExtractor g) {
        g.enableScissor(x + BORDER_WIDTH, getContentTop(), x + width - BORDER_WIDTH - SCROLLBAR_WIDTH, y + height - BORDER_WIDTH);
    }

    protected void disableScissor(GuiGraphicsExtractor g) {
        g.disableScissor();
    }
}
