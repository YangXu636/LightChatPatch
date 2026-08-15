package top.xuyangjerry.mcmod.lcp.client.popup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import top.xuyangjerry.mcmod.lcp.network.ForwardMessagePayload;
import top.xuyangjerry.mcmod.lcp.network.ReplyMessagePayload;

import java.util.ArrayList;
import java.util.List;

/**
 * 弹窗管理器：维护弹窗栈，统一处理渲染顺序、点击穿透、Esc 关闭。
 * 后打开的弹窗在上面，Esc 从最上层开始关闭。
 */
public class PopupWindowManager {

    private static PopupWindowManager instance;

    private final List<PopupWindow> windows = new ArrayList<>();

    private PopupWindowManager() {
    }

    public static PopupWindowManager getInstance() {
        if (instance == null) {
            instance = new PopupWindowManager();
        }
        return instance;
    }

    /**
     * 打开通转发详情弹窗。根据消息数量自适配窗口大小。
     */
    public void openForwardDetail(String forwardId, ForwardMessagePayload payload, int mouseX, int mouseY) {
        String title = "转发消息 " + forwardId;

        // 根据消息数量自适配窗口大小
        int msgCount = (payload != null && payload.messages() != null) ? payload.messages().size() : 0;
        // 每条消息约 2 行（内容+空行），每行高度 11px（LINE_HEIGHT=9 + LINE_SPACING=2）
        int estimatedHeight = msgCount * 2 * 11 + 40; // 40 = 标题栏 + 边框 + padding
        int h = Math.max(120, Math.min(estimatedHeight, 400));
        int w = 300;

        int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int x = clampPosition(mouseX, w, screenW);
        int y = clampPosition(mouseY, h, screenH);

        ForwardDetailPopup popup = new ForwardDetailPopup(x, y, w, h, title, payload);
        windows.add(popup);
    }

    /**
     * 打开图片详情弹窗。
     */
    public void openImageDetail(int imageId, int mouseX, int mouseY) {
        String title = "图片 #" + imageId;
        int w = 320;
        int h = 240;
        int x = clampPosition(mouseX, w, Minecraft.getInstance().getWindow().getGuiScaledWidth());
        int y = clampPosition(mouseY, h, Minecraft.getInstance().getWindow().getGuiScaledHeight());

        ImageDetailPopup popup = new ImageDetailPopup(x, y, w, h, imageId);
        windows.add(popup);
    }

    /**
     * 打开回复详情弹窗。
     */
    public void openReplyDetail(ReplyMessagePayload payload, int mouseX, int mouseY) {
        int w = 260;
        int h = 140;
        int x = clampPosition(mouseX, w, Minecraft.getInstance().getWindow().getGuiScaledWidth());
        int y = clampPosition(mouseY, h, Minecraft.getInstance().getWindow().getGuiScaledHeight());

        ReplyDetailPopup popup = new ReplyDetailPopup(x, y, w, h, payload);
        windows.add(popup);
    }

    private int clampPosition(int pos, int size, int max) {
        if (pos + size > max) pos = max - size - 4;
        if (pos < 4) pos = 4;
        return pos;
    }

    /**
     * 渲染所有弹窗（从底到顶）。
     * 注意：不在此处调用任何 GLFW 函数，避免窗口最小化/恢复时上下文异常导致回调失效。
     * 光标样式更新改在 mouseClicked/mouseDragged 中进行。
     */
    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        for (PopupWindow window : windows) {
            window.render(g, mouseX, mouseY);
        }
    }

    /**
     * 处理鼠标点击。返回 true 如果消耗了点击（不穿透到聊天框）。
     * 从最上层弹窗开始检测，遮挡区域不穿透到下层弹窗。
     * 只要鼠标在弹窗内（包括空白区域），始终消耗点击事件。
     */
    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        // 从最上层（最后添加）开始检测
        for (int i = windows.size() - 1; i >= 0; i--) {
            PopupWindow window = windows.get(i);
            if (window.contains(mouseX, mouseY)) {
                // 将此弹窗置顶
                if (i < windows.size() - 1) {
                    windows.remove(i);
                    windows.add(window);
                }

                // 检查关闭按钮
                if (window.isCloseButtonClicked(mouseX, mouseY)) {
                    windows.remove(window);
                    PopupWindow.restoreDefaultCursor();
                    return true;
                }

                // 鼠标在弹窗内：始终消耗点击，不穿透到下层弹窗或聊天框
                window.mouseClicked(mouseX, mouseY, button);
                window.updateCursor(mouseX, mouseY);
                return true;
            }
        }
        // 鼠标不在任何弹窗内，恢复默认光标
        PopupWindow.restoreDefaultCursor();
        return false;
    }

    /**
     * 处理鼠标拖动。
     */
    public void mouseDragged(int mouseX, int mouseY, int button) {
        // 只有最上层的弹窗可以拖动
        if (!windows.isEmpty()) {
            PopupWindow top = windows.get(windows.size() - 1);
            top.mouseDragged(mouseX, mouseY, button);
            top.updateCursor(mouseX, mouseY);
        }
    }

    /**
     * 处理鼠标释放。
     */
    public void mouseReleased(int mouseX, int mouseY, int button) {
        if (!windows.isEmpty()) {
            PopupWindow top = windows.get(windows.size() - 1);
            top.mouseReleased(mouseX, mouseY, button);
            top.updateCursor(mouseX, mouseY);
        }
    }

    /**
     * 处理鼠标滚轮。返回 true 如果消耗了滚动。
     */
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollAmount) {
        // 从最上层开始检测
        for (int i = windows.size() - 1; i >= 0; i--) {
            PopupWindow window = windows.get(i);
            if (window.contains(mouseX, mouseY)) {
                return window.mouseScrolled(mouseX, mouseY, scrollAmount);
            }
        }
        return false;
    }

    /**
     * Esc 键关闭最上层弹窗。返回 true 如果有弹窗被关闭。
     */
    public boolean closeTop() {
        if (!windows.isEmpty()) {
            windows.remove(windows.size() - 1);
            return true;
        }
        return false;
    }

    /**
     * 关闭所有弹窗。
     */
    public void closeAll() {
        windows.clear();
    }

    /**
     * 是否有弹窗打开。
     */
    public boolean hasWindows() {
        return !windows.isEmpty();
    }

    /**
     * 最上层弹窗是否正在交互（拖拽/缩放）。
     */
    public boolean isInteracting() {
        return !windows.isEmpty() && windows.get(windows.size() - 1).isInteracting();
    }

    /**
     * 获取弹窗数量。
     */
    public int getWindowCount() {
        return windows.size();
    }
}
