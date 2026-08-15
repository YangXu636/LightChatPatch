package top.xuyangjerry.mcmod.lcp.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 简化版颜色选择器：包含：
 * - 模式切换：RGB / HSL / HEX
 * - RGB模式：输入框3个（R/H G/S B/L）+ 百分比/整数切换
 * - HEX模式：一个宽输入框（宽度 = 3输入框 + 2间隙）
 * - 色图：Saturation×Hue 二维点击选择，宽度与HEX输入框保持一致
 * - 亮度滑块（V）：在色图右侧
 * - 颜色预览框：右上角正方形
 * - OK/Cancel 按钮
 */
public class ColorPickerScreen extends Screen {
    private static final int PICKER_W = 180;
    private static final int PICKER_H = 140;
    private static final int VSLIDER_W = 14;
    private static final int GAP = 4;

    private final Screen parent;
    private final Component description;
    private final int initialColor;
    private final Consumer<Integer> onConfirm;

    // 当前颜色在HSV空间：h in [0,360), s in [0,1], v in [0,1]
    private float h = 120f;
    private float s = 0.66f;
    private float v = 1f;

    private enum Mode { RGB, HSL, HEX }
    private Mode mode = Mode.RGB;
    private enum ValueMode { PERCENT, INT }
    private ValueMode valueMode = ValueMode.INT;

    private CycleButton<Mode> modeButton;
    private CycleButton<ValueMode> valueModeButton;
    private EditBox ch1Box; // R / H / HEX
    private EditBox ch2Box; // G / S (仅非HEX)
    private EditBox ch3Box; // B / L (仅非HEX)

    // 是否正在从HSV同步到输入框（防止响应循环）
    private boolean syncingFromHsv = false;
    // 鼠标按下中（色图拖拽）
    private boolean draggingPicker = false;
    private boolean draggingV = false;

    public ColorPickerScreen(Screen parent, Component description, int initialColorARGB, Consumer<Integer> onConfirm) {
        super(Component.translatable("light_chat_patch.color_picker.title"));
        this.parent = parent;
        this.description = description;
        this.initialColor = 0xFF000000 | initialColorARGB;
        this.onConfirm = onConfirm;
        float[] hsv = rgbToHsv((initialColor >> 16) & 0xFF, (initialColor >> 8) & 0xFF, initialColor & 0xFF);
        this.h = hsv[0]; this.s = hsv[1]; this.v = hsv[2];
    }

    @Override
    protected void init() {
        int baseX = (this.width - (PICKER_W + GAP + VSLIDER_W + 80 + GAP * 2)) / 2;
        if (baseX < 10) baseX = 10;
        int topY = 40;

        // 颜色预览：正方形，高度20（与按钮高度一致）
        int previewSize = 40;

        // 模式按钮
        modeButton = CycleButton.<Mode>builder(m -> Component.literal(m.name()), mode)
                .withValues(Mode.values())
                .displayOnlyValue()
                .create(0, 0, 80, 20, Component.empty(), (btn, val) -> {
                    mode = val;
                    updateBoxVisibilities();
                    syncHsvToBoxes();
                });
        this.addRenderableWidget(modeButton);

        // 数值模式（仅RGB/HSL使用，HEX时隐藏）
        valueModeButton = CycleButton.<ValueMode>builder(
                        m -> Component.translatable(m == ValueMode.PERCENT
                                ? "light_chat_patch.color_picker.valueMode.percent"
                                : "light_chat_patch.color_picker.valueMode.int"),
                        valueMode)
                .withValues(ValueMode.values())
                .displayOnlyValue()
                .create(0, 0, 80, 20, Component.empty(), (btn, val) -> {
                    valueMode = val;
                    syncHsvToBoxes();
                });
        this.addRenderableWidget(valueModeButton);

        // 3个通道输入框
        int chW = (PICKER_W - GAP * 2) / 3;
        ch1Box = new EditBox(this.font, 0, 0, chW, 20, Component.empty());
        ch2Box = new EditBox(this.font, 0, 0, chW, 20, Component.empty());
        ch3Box = new EditBox(this.font, 0, 0, chW, 20, Component.empty());
        ch1Box.setMaxLength(8);
        ch2Box.setMaxLength(8);
        ch3Box.setMaxLength(8);
        ch1Box.setResponder(s -> onBoxEdited());
        ch2Box.setResponder(s -> onBoxEdited());
        ch3Box.setResponder(s -> onBoxEdited());
        this.addRenderableWidget(ch1Box);
        this.addRenderableWidget(ch2Box);
        this.addRenderableWidget(ch3Box);

        // Done / Cancel
        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_DONE, b -> confirmAndClose())
                        .bounds(this.width / 2 - 104, this.height - 29, 100, 20).build());
        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_CANCEL, b -> this.minecraft.gui.setScreen(parent))
                        .bounds(this.width / 2 + 4, this.height - 29, 100, 20).build());

        updateBoxVisibilities();
        syncHsvToBoxes();
    }

    private void updateBoxVisibilities() {
        boolean notHex = mode != Mode.HEX;
        valueModeButton.visible = notHex;
        ch2Box.setVisible(notHex);
        ch3Box.setVisible(notHex);
        // HEX模式下 ch1 加宽到 PICKER_W
        if (mode == Mode.HEX) {
            ch1Box.setWidth(PICKER_W);
            ch1Box.setMaxLength(7); // #RRGGBB
        } else {
            int chW = (PICKER_W - GAP * 2) / 3;
            ch1Box.setWidth(chW);
            ch1Box.setMaxLength(8);
        }
    }

    private void onBoxEdited() {
        if (syncingFromHsv) return;
        if (mode == Mode.HEX) {
            String t = ch1Box.getValue().trim();
            if (t.isEmpty()) return;
            if (!t.startsWith("#")) t = "#" + t;
            if (t.length() != 7) return;
            try {
                int hex = Integer.parseInt(t.substring(1), 16);
                int r = (hex >> 16) & 0xFF, g = (hex >> 8) & 0xFF, b = hex & 0xFF;
                float[] hsv = rgbToHsv(r, g, b);
                h = hsv[0]; s = hsv[1]; v = hsv[2];
            } catch (NumberFormatException ignored) {}
        } else {
            try {
                double v1 = parseChannel(ch1Box.getValue());
                double v2 = parseChannel(ch2Box.getValue());
                double v3 = parseChannel(ch3Box.getValue());
                if (Double.isNaN(v1) || Double.isNaN(v2) || Double.isNaN(v3)) return;
                int r, g, b;
                if (mode == Mode.RGB) {
                    r = clamp255(toInt255(v1));
                    g = clamp255(toInt255(v2));
                    b = clamp255(toInt255(v3));
                } else {
                    // HSL
                    double hh = valueMode == ValueMode.PERCENT ? v1 * 3.6 : (v1 % 360 + 360) % 360;
                    double ss = clamp01(valueMode == ValueMode.PERCENT ? v1 / 100.0 : v2 / 255.0);
                    double ll = clamp01(valueMode == ValueMode.PERCENT ? v3 / 100.0 : v3 / 255.0);
                    if (ch1Box != null) {
                        // H 用 ch1 原始值
                        try { hh = valueMode == ValueMode.PERCENT
                                ? clamp01(parseChannel(ch1Box.getValue())) * 360
                                : ((parseChannel(ch1Box.getValue()) % 360) + 360) % 360; } catch (Exception ignored) {}
                        ss = clamp01(valueMode == ValueMode.PERCENT
                                ? parseChannel(ch2Box.getValue()) / 100.0
                                : parseChannel(ch2Box.getValue()) / 255.0);
                        ll = clamp01(valueMode == ValueMode.PERCENT
                                ? parseChannel(ch3Box.getValue()) / 100.0
                                : parseChannel(ch3Box.getValue()) / 255.0);
                    }
                    int[] rgb = hslToRgb(hh / 360.0, ss, ll);
                    r = rgb[0]; g = rgb[1]; b = rgb[2];
                }
                float[] hsv = rgbToHsv(r, g, b);
                h = hsv[0]; s = hsv[1]; v = hsv[2];
            } catch (Exception ignored) {}
        }
    }

    private double parseChannel(String s) {
        if (s == null || s.isEmpty()) return Double.NaN;
        try {
            if (s.endsWith("%")) return Double.parseDouble(s.substring(0, s.length() - 1));
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
    private int toInt255(double v) {
        return valueMode == ValueMode.PERCENT ? (int) Math.round(clamp01(v / 100.0) * 255) : (int) Math.round(v);
    }

    private void syncHsvToBoxes() {
        syncingFromHsv = true;
        try {
            int[] rgb = hsvToRgb(h, s, v);
            int r = rgb[0], g = rgb[1], b = rgb[2];
            if (mode == Mode.HEX) {
                ch1Box.setValue("#" + String.format("%02X%02X%02X", r, g, b));
            } else if (mode == Mode.RGB) {
                if (valueMode == ValueMode.PERCENT) {
                    ch1Box.setValue(String.format("%.0f%%", r * 100.0 / 255.0));
                    ch2Box.setValue(String.format("%.0f%%", g * 100.0 / 255.0));
                    ch3Box.setValue(String.format("%.0f%%", b * 100.0 / 255.0));
                } else {
                    ch1Box.setValue(String.valueOf(r));
                    ch2Box.setValue(String.valueOf(g));
                    ch3Box.setValue(String.valueOf(b));
                }
            } else {
                double[] hsl = rgbToHsl(r, g, b);
                if (valueMode == ValueMode.PERCENT) {
                    ch1Box.setValue(String.format("%.0f%%", hsl[0] * 100));
                    ch2Box.setValue(String.format("%.0f%%", hsl[1] * 100));
                    ch3Box.setValue(String.format("%.0f%%", hsl[2] * 100));
                } else {
                    ch1Box.setValue(String.valueOf((int) Math.round(hsl[0] * 360)));
                    ch2Box.setValue(String.valueOf((int) Math.round(hsl[1] * 255)));
                    ch3Box.setValue(String.valueOf((int) Math.round(hsl[2] * 255)));
                }
            }
        } finally {
            syncingFromHsv = false;
        }
    }

    private void confirmAndClose() {
        int[] rgb = hsvToRgb(h, s, v);
        int argb = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
        onConfirm.accept(argb);
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean handled) {
        double mx = event.x();
        double my = event.y();
        int button = event.button();
        int[] geom = getPickerGeometry();
        int px = geom[0], py = geom[1], pw = geom[2], ph = geom[3];
        int vx = geom[4], vy = geom[5], vw = geom[6], vh = geom[7];
        if (mx >= px && mx < px + pw && my >= py && my < py + ph) {
            draggingPicker = true;
            pickAt(mx, my, px, py, pw, ph);
            return true;
        }
        if (mx >= vx && mx < vx + vw && my >= vy && my < vy + vh) {
            draggingV = true;
            vAt(my, vy, vh);
            return true;
        }
        // 点击颜色预览框：重置初始色（快捷操作）
        int[] pg = getPreviewGeometry();
        if (mx >= pg[0] && mx < pg[0] + pg[2] && my >= pg[1] && my < pg[1] + pg[3]) {
            int[] rgb = hsvToRgb(h, s, v);
            int hex = 0xFF000000 | (rgb[0]<<16) | (rgb[1]<<8) | rgb[2];
            minecraft.keyboardHandler.setClipboard(String.format("#%06X", hex & 0xFFFFFF));
            return true;
        }
        return super.mouseClicked(event, handled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        double mx = event.x();
        double my = event.y();
        int[] geom = getPickerGeometry();
        int px = geom[0], py = geom[1], pw = geom[2], ph = geom[3];
        int vx = geom[4], vy = geom[5], vw = geom[6], vh = geom[7];
        if (draggingPicker) {
            pickAt(mx, my, px, py, pw, ph);
            return true;
        }
        if (draggingV) {
            vAt(my, vy, vh);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingPicker = false;
        draggingV = false;
        return super.mouseReleased(event);
    }

    private void pickAt(double mx, double my, int px, int py, int pw, int ph) {
        double x = Math.max(0, Math.min(1, (mx - px) / (pw - 1)));
        double y = Math.max(0, Math.min(1, (my - py) / (ph - 1)));
        h = (float) (x * 360);
        s = (float) (1 - y);
        syncHsvToBoxes();
    }
    private void vAt(double my, int vy, int vh) {
        v = (float) (1 - Math.max(0, Math.min(1, (my - vy) / (vh - 1))));
        syncHsvToBoxes();
    }

    // 布局：色图 + V滑块 + 预览 + 控件
    private int[] getPickerGeometry() {
        int totalH = PICKER_H;
        int totalW = PICKER_W + GAP + VSLIDER_W;
        int x = (this.width - totalW) / 2;
        int y = 80;
        return new int[]{x, y, PICKER_W, totalH, x + PICKER_W + GAP, y, VSLIDER_W, totalH};
    }
    private int[] getPreviewGeometry() {
        int size = 40;
        // 放在色图右侧V滑块的右侧
        int[] g = getPickerGeometry();
        int x = g[4] + g[6] + GAP;
        int y = g[1];
        return new int[]{x, y, size, size};
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        // 标题：描述
        g.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        if (description != null) {
            g.centeredText(this.font, description, this.width / 2, 26, 0xFFCCCCCC);
        }

        // 控件定位：模式按钮 + 数值模式 + 3个输入框
        int[] geom = getPickerGeometry();
        int baseX = geom[0];
        int boxY = geom[1] + geom[3] + GAP;

        modeButton.setX(baseX);
        modeButton.setY(boxY);
        modeButton.visible = true;

        int chW;
        if (mode == Mode.HEX) {
            valueModeButton.setX(baseX);
            valueModeButton.setY(boxY + ROW_H);
            valueModeButton.visible = false;
            ch1Box.setPosition(baseX, boxY + ROW_H);
            ch2Box.setVisible(false);
            ch3Box.setVisible(false);
        } else {
            valueModeButton.setX(baseX + PICKER_W - 80);
            valueModeButton.setY(boxY);
            valueModeButton.visible = true;
            chW = (PICKER_W - GAP * 2) / 3;
            int row2 = boxY + ROW_H;
            ch1Box.setPosition(baseX, row2);
            ch1Box.setWidth(chW);
            ch2Box.setPosition(baseX + chW + GAP, row2);
            ch2Box.setWidth(chW);
            ch2Box.setVisible(true);
            ch3Box.setPosition(baseX + (chW + GAP) * 2, row2);
            ch3Box.setWidth(chW);
            ch3Box.setVisible(true);
        }

        // 绘制色图（Hue×Saturation）：逐像素绘制太慢，改为 180×140 的 20×15 粗网格
        drawHueSatMap(g, geom[0], geom[1], geom[2], geom[3]);

        // 绘制十字指针
        int px = geom[0] + (int) Math.round((h / 360f) * (geom[2] - 1));
        int py = geom[1] + (int) Math.round((1 - s) * (geom[3] - 1));
        g.outline(px - 2, py - 2, 5, 5, 0xFFFFFFFF);
        g.outline(px - 3, py - 3, 7, 7, 0xAA000000);

        // 绘制V滑块：从白到纯Hue色到黑？这里从 v=1 顶色到底色(v=0)为黑
        drawVSlider(g, geom[4], geom[5], geom[6], geom[7]);
        int vy = geom[5] + (int) Math.round((1 - v) * (geom[7] - 1));
        g.fill(geom[4] - 2, vy - 1, geom[4] + geom[6] + 2, vy + 2, 0xFFFFFFFF);
        g.fill(geom[4] - 2, vy, geom[4] + geom[6] + 2, vy + 1, 0xFF000000);

        // 颜色预览
        int[] pg = getPreviewGeometry();
        int[] rgb = hsvToRgb(h, s, v);
        int cur = 0xFF000000 | (rgb[0]<<16) | (rgb[1]<<8) | rgb[2];
        g.fill(pg[0], pg[1], pg[0] + pg[2], pg[1] + pg[3], cur);
        g.outline(pg[0], pg[1], pg[2], pg[3], 0xFFFFFFFF);
        g.outline(pg[0] - 1, pg[1] - 1, pg[2] + 2, pg[3] + 2, 0x80000000);
        // 初始色小方块（在预览下方）
        g.fill(pg[0], pg[1] + pg[3] + GAP, pg[0] + pg[2]/2, pg[1] + pg[3] + GAP + 10, initialColor);
        g.fill(pg[0] + pg[2]/2, pg[1] + pg[3] + GAP, pg[0] + pg[2], pg[1] + pg[3] + GAP + 10, cur);

        // 说明文字：在控件下
        int hintY = boxY + ROW_H * 2 + 2;
        Component hint = switch (mode) {
            case RGB -> Component.translatable("light_chat_patch.color_picker.hint.rgb");
            case HSL -> Component.translatable("light_chat_patch.color_picker.hint.hsl");
            case HEX -> Component.translatable("light_chat_patch.color_picker.hint.hex");
        };
        g.centeredText(this.font, hint, this.width/2, hintY, 0xFFAAAAAA);
    }

    private static final int ROW_H = 24;

    private void drawHueSatMap(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        // 使用 24×20 网格减少绘制数，插值颜色
        int cols = 24, rows = 20;
        int cw = (w + cols - 1) / cols;
        int rh = (h + rows - 1) / rows;
        for (int j = 0; j < rows; j++) {
            float ss = 1f - (j + 0.5f) / rows;
            for (int i = 0; i < cols; i++) {
                float hh = (i + 0.5f) / cols * 360f;
                int[] rgb = hsvToRgb(hh, ss, this.v);
                int c = 0xFF000000 | (rgb[0]<<16) | (rgb[1]<<8) | rgb[2];
                int cx = x + i * cw;
                int cy = y + j * rh;
                int cw1 = Math.min(cw, x + w - cx);
                int rh1 = Math.min(rh, y + h - cy);
                g.fill(cx, cy, cx + cw1, cy + rh1, c);
            }
        }
    }

    private void drawVSlider(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        int rows = 32;
        int rh = (h + rows - 1) / rows;
        for (int j = 0; j < rows; j++) {
            float vv = 1f - (j + 0.5f) / rows;
            int[] rgb = hsvToRgb(this.h, 1f, vv);
            int c = 0xFF000000 | (rgb[0]<<16) | (rgb[1]<<8) | rgb[2];
            int cy = y + j * rh;
            int rh1 = Math.min(rh, y + h - cy);
            g.fill(x, cy, x + w, cy + rh1, c);
        }
    }

    // ---- 颜色转换工具 ----
    private static float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float d = max - min;
        float hh = 0;
        if (d > 0) {
            if (max == rf) hh = ((gf - bf) / d) % 6;
            else if (max == gf) hh = (bf - rf) / d + 2;
            else hh = (rf - gf) / d + 4;
            hh *= 60;
            if (hh < 0) hh += 360;
        }
        float s = max == 0 ? 0 : d / max;
        return new float[]{hh, s, max};
    }

    private static int[] hsvToRgb(float hh, float ss, float vv) {
        if (ss == 0) {
            int v = (int) Math.round(clamp01(vv) * 255);
            return new int[]{v, v, v};
        }
        double hhN = ((hh % 360) + 360) % 360;
        hhN /= 60.0;
        int i = (int) hhN;
        double f = hhN - i;
        double v = clamp01(vv);
        double p = v * (1 - ss);
        double q = v * (1 - ss * f);
        double t = v * (1 - ss * (1 - f));
        double r, gg, b;
        switch (i) {
            case 0 -> { r = v; gg = t; b = p; }
            case 1 -> { r = q; gg = v; b = p; }
            case 2 -> { r = p; gg = v; b = t; }
            case 3 -> { r = p; gg = q; b = v; }
            case 4 -> { r = t; gg = p; b = v; }
            default -> { r = v; gg = p; b = q; }
        }
        return new int[]{(int) Math.round(r*255), (int) Math.round(gg*255), (int) Math.round(b*255)};
    }

    private static double[] rgbToHsl(int r, int g, int b) {
        double rf = r/255.0, gf = g/255.0, bf = b/255.0;
        double max = Math.max(rf, Math.max(gf, bf));
        double min = Math.min(rf, Math.min(gf, bf));
        double l = (max + min) / 2.0;
        double hh = 0, s = 0;
        if (max != min) {
            double d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            if (max == rf) hh = (gf - bf) / d + (gf < bf ? 6 : 0);
            else if (max == gf) hh = (bf - rf) / d + 2;
            else hh = (rf - gf) / d + 4;
            hh /= 6;
        }
        return new double[]{hh, s, l};
    }

    private static int[] hslToRgb(double h, double s, double l) {
        double r, g, b;
        if (s == 0) { r = g = b = l; }
        else {
            double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
            double p = 2 * l - q;
            r = hue2rgb(p, q, h + 1/3.0);
            g = hue2rgb(p, q, h);
            b = hue2rgb(p, q, h - 1/3.0);
        }
        return new int[]{clamp255((int) Math.round(r*255)), clamp255((int) Math.round(g*255)), clamp255((int) Math.round(b*255))};
    }
    private static double hue2rgb(double p, double q, double t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1/6.0) return p + (q - p) * 6 * t;
        if (t < 1/2.0) return q;
        if (t < 2/3.0) return p + (q - p) * (2/3.0 - t) * 6;
        return p;
    }

    private static float clamp01(double x) { return (float) Math.max(0, Math.min(1, x)); }
    private static int clamp255(int x) { return Math.max(0, Math.min(255, x)); }
}
