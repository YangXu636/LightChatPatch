package top.xuyangjerry.mcmod.lcp.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import top.xuyangjerry.mcmod.lcp.config.ChatHistoryView;
import top.xuyangjerry.mcmod.lcp.config.LcpConfig;
import top.xuyangjerry.mcmod.lcp.history.ChatBoxHistoryManager;
import top.xuyangjerry.mcmod.lcp.history.ChatHistoryManager;
import top.xuyangjerry.mcmod.lcp.client.screen.BannedWordsConfigScreen;
import top.xuyangjerry.mcmod.lcp.client.screen.PlayerSocialScreen;

import java.util.ArrayList;
import java.util.List;

public class LightChatPatchConfigScreen extends Screen {
    private static final Component TITLE = Component.translatable("light_chat_patch.options.title");
    private static final int ROW_HEIGHT = 24;
    private static final int CONTROL_WIDTH = 150;
    // 问题2：颜色行控件需要更宽（模式下拉+输入模块+颜色预览）
    private static final int COLOR_CONTROL_WIDTH = 240;
    private static final int PADDING = 10;

    private final Screen parent;
    private final LcpConfig config;
    private final List<Row> rows = new ArrayList<>();
    private EditBox imageSizeNumberBox;
    private CycleButton<String> imageSizeUnitButton;
    private int imageSizeRowY = -1;
    // 问题2：颜色行控件
    private int qqColorRowY = -1;
    private CycleButton<String> qqColorModeButton; // RGB/HSL/HEX
    private CycleButton<Boolean> qqColorValueModeButton; // 百分比/整数 (true=百分比)
    private EditBox qqColorCh1Box; // R / H / HEX
    private EditBox qqColorCh2Box; // G / S (仅非HEX)
    private EditBox qqColorCh3Box; // B / L (仅非HEX)
    private Button qqColorPreviewButton; // 颜色预览，点击打开取色器
    // 是否从内部程序更新输入框，防止循环
    private boolean qqColorSyncing = false;

    private int scrollOffset = 0;
    private int maxScroll = 0;
    private int contentTop;
    private int contentBottom;

    public LightChatPatchConfigScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
        this.config = LcpConfig.getInstance();
    }

    @Override
    protected void init() {
        this.contentTop = 35;
        this.contentBottom = this.height - 35;

        rows.clear();

        int controlX = this.width - PADDING - CONTROL_WIDTH;
        int y = 0;

        addRow(controlX, y, "enableHoverButtons", createOnOff(config.isEnableHoverButtons(), config::setEnableHoverButtons));
        y += ROW_HEIGHT;
        addRow(controlX, y, "enableRightClickMenu", createOnOff(config.isEnableRightClickMenu(), config::setEnableRightClickMenu));
        y += ROW_HEIGHT;
        addRow(controlX, y, "saveDraftOnClose", createOnOff(config.isSaveDraftOnClose(), config::setSaveDraftOnClose));
        y += ROW_HEIGHT;
        addRow(controlX, y, "plusOneSelf", createOnOff(config.isPlusOneSelf(), config::setPlusOneSelf));
        y += ROW_HEIGHT;
        addRow(controlX, y, "chatHistoryView", createHistoryViewButton());
        y += ROW_HEIGHT;
        addRow(controlX, y, "draftHistoryView", createDraftHistoryViewButton());
        y += ROW_HEIGHT;
        addRow(controlX, y, "chatHistoryMaxSize", createSlider(config.getChatHistoryMaxSize(), 10, 1000, config::setChatHistoryMaxSize));
        y += ROW_HEIGHT;
        addRow(controlX, y, "chatMaxVisibleMessages", createSlider(config.getChatMaxVisibleMessages(), 10, 1000, config::setChatMaxVisibleMessages));
        y += ROW_HEIGHT;
        addRow(controlX, y, "chatMaxLength", createChatMaxLengthButton());
        y += ROW_HEIGHT;
        addRow(controlX, y, "clickReplyToJump", createOnOff(config.isClickReplyToJump(), config::setClickReplyToJump));
        y += ROW_HEIGHT;
        addRow(controlX, y, "replyTruncateThreshold", createSlider(config.getReplyTruncateThreshold(), 10, 256, config::setReplyTruncateThreshold));
        y += ROW_HEIGHT;
        addRow(controlX, y, "replyTruncateHead", createSlider(config.getReplyTruncateHead(), 3, 100, config::setReplyTruncateHead));
        y += ROW_HEIGHT;
        addRow(controlX, y, "replyTruncateTail", createSlider(config.getReplyTruncateTail(), 3, 100, config::setReplyTruncateTail));
        y += ROW_HEIGHT;
        addRow(controlX, y, "preserveChatHistory", createOnOff(config.isPreserveChatHistory(), config::setPreserveChatHistory));
        y += ROW_HEIGHT;
        addRow(controlX, y, "rangeSelectToggle", createRangeSelectToggle());
        y += ROW_HEIGHT;
        addImageSizeRow(controlX, y);
        y += ROW_HEIGHT;
        addQqColorRow(controlX, y);
        y += ROW_HEIGHT;
        addRow(controlX, y, "bannedWordsConfig", createBannedWordsButton());
        y += ROW_HEIGHT;
        addRow(controlX, y, "playerSocialScreen", createPlayerSocialButton());
        y += ROW_HEIGHT;

        y += 8;

        addRow(controlX, y, "deleteChatBoxHistory", createDeleteButton(true));
        y += ROW_HEIGHT;
        addRow(controlX, y, "deleteChatHistory", createDeleteButton(false));
        y += ROW_HEIGHT;

        maxScroll = Math.max(0, y - (contentBottom - contentTop));

        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_DONE, button -> {
                            LcpConfig.save();
                            this.minecraft.gui.setScreen(parent);
                        })
                        .bounds(this.width / 2 - 100, this.height - 29, 200, 20)
                        .build()
        );
    }

    private Button createDeleteButton(boolean deleteChatBox) {
        String key = deleteChatBox ? "light_chat_patch.options.deleteChatBoxHistory" : "light_chat_patch.options.deleteChatHistory";
        return Button.builder(Component.translatable(key).withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(0xFF5555)), btn -> showConfirmDialog(deleteChatBox))
                .bounds(0, 0, CONTROL_WIDTH, 20)
                .build();
    }

    private void showConfirmDialog(boolean deleteChatBox) {
        String titleKey = deleteChatBox
                ? "light_chat_patch.options.deleteChatBoxHistory"
                : "light_chat_patch.options.deleteChatHistory";
        String confirmKey = deleteChatBox
                ? "light_chat_patch.options.deleteChatBoxHistory.confirm"
                : "light_chat_patch.options.deleteChatHistory.confirm";

        ConfirmScreen confirmScreen = new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        if (deleteChatBox) {
                            ChatBoxHistoryManager.deleteChatBoxHistory();
                        } else {
                            ChatHistoryManager.deleteChatHistory();
                            if (minecraft.gui != null && minecraft.gui.hud.getChat() != null) {
                                minecraft.gui.hud.getChat().getRecentChat().clear();
                                minecraft.gui.hud.getChat().getRecentChat().addLast("");
                            }
                        }
                    }
                    minecraft.gui.setScreen(this);
                },
                Component.translatable(titleKey),
                Component.translatable(confirmKey),
                CommonComponents.GUI_YES,
                CommonComponents.GUI_NO
        );
        minecraft.gui.setScreen(confirmScreen);
    }

    private void addRow(int controlX, int y, String key, AbstractWidget widget) {
        rows.add(new Row(y, key, widget));
        this.addRenderableWidget(widget);
    }

    private AbstractWidget createOnOff(boolean value, java.util.function.Consumer<Boolean> setter) {
        return CycleButton.onOffBuilder(value)
                .displayOnlyValue()
                .create(0, 0, CONTROL_WIDTH, 20, Component.empty(), (btn, val) -> setter.accept(val));
    }

    private AbstractWidget createSlider(int value, int min, int max, java.util.function.IntConsumer setter) {
        int range = max - min;
        AbstractSliderButton slider = new AbstractSliderButton(0, 0, CONTROL_WIDTH, 20, Component.empty(),
                (Math.max(min, Math.min(max, value)) - min) / (double) range) {
            @Override
            protected void updateMessage() {
                this.setMessage(Component.literal(String.valueOf((int) Math.round(this.value * range) + min)));
            }

            @Override
            protected void applyValue() {
                setter.accept((int) Math.round(this.value * range) + min);
            }
        };
        slider.setMessage(Component.literal(String.valueOf(value)));
        return slider;
    }

    private static final Integer[] CHAT_MAX_LENGTH_PRESETS = {256, 512, 1024, 2048, 4096, 8192, 16384, 32767};

    private AbstractWidget createChatMaxLengthButton() {
        return CycleButton.<Integer>builder(
                        val -> Component.literal(String.valueOf(val)),
                        config.getChatMaxLength())
                .withValues(CHAT_MAX_LENGTH_PRESETS)
                .displayOnlyValue()
                .create(0, 0, CONTROL_WIDTH, 20, Component.empty(), (btn, val) -> config.setChatMaxLength(val));
    }

    private AbstractWidget createHistoryViewButton() {
        return CycleButton.<ChatHistoryView>builder(
                        view -> Component.translatable("light_chat_patch.options.chatHistoryView." + view.getKey()),
                        config.getChatHistoryView())
                .withValues(ChatHistoryView.values())
                .displayOnlyValue()
                .create(0, 0, CONTROL_WIDTH, 20, Component.empty(), (btn, val) -> config.setChatHistoryView(val));
    }

    private AbstractWidget createDraftHistoryViewButton() {
        return CycleButton.<ChatHistoryView>builder(
                        view -> Component.translatable("light_chat_patch.options.chatHistoryView." + view.getKey()),
                        config.getDraftHistoryView())
                .withValues(ChatHistoryView.values())
                .displayOnlyValue()
                .create(0, 0, CONTROL_WIDTH, 20, Component.empty(), (btn, val) -> config.setDraftHistoryView(val));
    }

    private AbstractWidget createRangeSelectToggle() {
        return CycleButton.<Boolean>builder(
                        val -> Component.translatable("light_chat_patch.options.rangeSelectToggle." + (val ? "on" : "off")),
                        config.isRangeSelectToggle())
                .withValues(Boolean.TRUE, Boolean.FALSE)
                .displayOnlyValue()
                .create(0, 0, CONTROL_WIDTH, 20, Component.empty(), (btn, val) -> config.setRangeSelectToggle(val));
    }

    private AbstractWidget createBannedWordsButton() {
        return Button.builder(Component.translatable("light_chat_patch.options.bannedWordsConfig"),
                        btn -> this.minecraft.gui.setScreen(new BannedWordsConfigScreen(this)))
                .bounds(0, 0, CONTROL_WIDTH, 20)
                .build();
    }

    private AbstractWidget createPlayerSocialButton() {
        return Button.builder(Component.translatable("light_chat_patch.options.playerSocialScreen"),
                        btn -> this.minecraft.gui.setScreen(new PlayerSocialScreen(this)))
                .bounds(0, 0, CONTROL_WIDTH, 20)
                .build();
    }

    private static final String[] IMAGE_SIZE_UNITS = {"B", "KB", "MB", "GB", "TB"};

    /**
     * 图片最大大小行：左侧数字输入框 + 右侧单位下拉框。
     * 用一个空的占位 widget 注册到 Row（保证行布局和标签正常），
     * 数字框和单位下拉框单独 addRenderableWidget 并在 extractRenderState 中定位。
     */
    private void addImageSizeRow(int controlX, int y) {
        // 占位 widget（不可见），仅用于让 Row 占位以显示标签
        AbstractWidget placeholder = Button.builder(Component.empty(), b -> {})
                .bounds(0, 0, 0, 0).build();
        placeholder.visible = false;
        addRow(controlX, y, "maxImageSize", placeholder);
        imageSizeRowY = y;

        // 数字输入框
        EditBox numberBox = new EditBox(this.font, 0, 0, 90, 20, Component.empty());
        numberBox.setValue(String.valueOf(config.getMaxImageSize()));
        numberBox.setMaxLength(10);
        numberBox.setResponder(s -> {
            if (s.isEmpty()) return;
            try {
                int v = Integer.parseInt(s);
                config.setMaxImageSize(v);
            } catch (NumberFormatException ignored) {
                // 非数字输入：恢复为当前配置值
                numberBox.setValue(String.valueOf(config.getMaxImageSize()));
            }
        });
        this.addRenderableWidget(numberBox);
        imageSizeNumberBox = numberBox;

        // 单位下拉框
        CycleButton<String> unitButton = CycleButton.<String>builder(
                        unit -> Component.literal(unit),
                        config.getMaxImageSizeUnit())
                .withValues(IMAGE_SIZE_UNITS)
                .displayOnlyValue()
                .create(0, 0, 52, 20, Component.empty(), (btn, val) -> config.setMaxImageSizeUnit(val));
        this.addRenderableWidget(unitButton);
        imageSizeUnitButton = unitButton;
    }

    /** 问题2：QQ消息颜色配置行。布局：[模式下拉(RGB/HSL/HEX)] [输入模块] [颜色预览方块] */
    private void addQqColorRow(int controlX, int y) {
        // Row占位（用于标签显示），实际控件单独addRenderableWidget
        AbstractWidget placeholder = Button.builder(Component.empty(), b -> {}).bounds(0,0,0,0).build();
        placeholder.visible = false;
        addRow(controlX, y, "qqMessageColor", placeholder);
        qqColorRowY = y;

        // 模式下拉 RGB/HSL/HEX
        String[] modes = {"RGB", "HSL", "HEX"};
        qqColorModeButton = CycleButton.<String>builder(
                        m -> Component.literal(m),
                        modes[0])
                .withValues(modes)
                .displayOnlyValue()
                .create(0, 0, 56, 20, Component.empty(),
                        (btn, val) -> onQqColorModeChanged());
        this.addRenderableWidget(qqColorModeButton);

        // 数值模式：百分比/整数（true=百分比,false=整数）。只在RGB/HSL下可见。
        qqColorValueModeButton = CycleButton.<Boolean>builder(
                        percent -> Component.translatable(Boolean.TRUE.equals(percent)
                                ? "light_chat_patch.options.qqMessageColor.valueMode.percent"
                                : "light_chat_patch.options.qqMessageColor.valueMode.int"),
                        false)
                .withValues(Boolean.FALSE, Boolean.TRUE)
                .displayOnlyValue()
                .create(0, 0, 48, 20, Component.empty(),
                        (btn, val) -> syncQqColorFromConfigToBoxes());
        this.addRenderableWidget(qqColorValueModeButton);

        // 通道输入框
        int chW = (COLOR_CONTROL_WIDTH - 80 - 4 - 24 - 8) / 3; // 模式56+间隙4 = 60, 预览24
        int chWClamped = Math.max(28, Math.min(48, chW));
        qqColorCh1Box = new EditBox(this.font, 0, 0, chWClamped, 20, Component.empty());
        qqColorCh2Box = new EditBox(this.font, 0, 0, chWClamped, 20, Component.empty());
        qqColorCh3Box = new EditBox(this.font, 0, 0, chWClamped, 20, Component.empty());
        qqColorCh1Box.setMaxLength(10);
        qqColorCh2Box.setMaxLength(10);
        qqColorCh3Box.setMaxLength(10);
        qqColorCh1Box.setResponder(s -> onQqColorChannelEdited());
        qqColorCh2Box.setResponder(s -> onQqColorChannelEdited());
        qqColorCh3Box.setResponder(s -> onQqColorChannelEdited());
        this.addRenderableWidget(qqColorCh1Box);
        this.addRenderableWidget(qqColorCh2Box);
        this.addRenderableWidget(qqColorCh3Box);

        // 颜色预览方块（点击打开取色器）
        qqColorPreviewButton = Button.builder(Component.empty(), b -> openQqColorPicker())
                .bounds(0, 0, 20, 20).build();
        this.addRenderableWidget(qqColorPreviewButton);

        // 初始状态：RGB整数模式
        onQqColorModeChanged();
        syncQqColorFromConfigToBoxes();
    }

    private void openQqColorPicker() {
        ColorPickerScreen picker = new ColorPickerScreen(this,
                Component.translatable("light_chat_patch.options.qqMessageColor"),
                config.getQqMessageColor(),
                color -> {
                    config.setQqMessageColor(color);
                    syncQqColorFromConfigToBoxes();
                });
        this.minecraft.gui.setScreen(picker);
    }

    private void onQqColorModeChanged() {
        String mode = qqColorModeButton.getValue();
        boolean notHex = !"HEX".equals(mode);
        qqColorValueModeButton.visible = notHex;
        qqColorCh2Box.setVisible(notHex);
        qqColorCh3Box.setVisible(notHex);
        // HEX模式 ch1 宽度=3个通道+2个间隙
        int chW = qqColorCh1Box.getWidth();
        if ("HEX".equals(mode)) {
            int total3 = chW * 3 + 4 * 2;
            qqColorCh1Box.setWidth(Math.max(total3, 120));
            qqColorCh1Box.setMaxLength(8);
        } else {
            int chWNew = (COLOR_CONTROL_WIDTH - 80 - 4 - 24 - 8) / 3;
            chWNew = Math.max(28, Math.min(48, chWNew));
            qqColorCh1Box.setWidth(chWNew);
            qqColorCh2Box.setWidth(chWNew);
            qqColorCh3Box.setWidth(chWNew);
            qqColorCh1Box.setMaxLength(10);
        }
        syncQqColorFromConfigToBoxes();
    }

    private void syncQqColorFromConfigToBoxes() {
        qqColorSyncing = true;
        try {
            int c = config.getQqMessageColor();
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            String mode = qqColorModeButton.getValue();
            boolean percent = Boolean.TRUE.equals(qqColorValueModeButton.getValue());
            if ("HEX".equals(mode)) {
                qqColorCh1Box.setValue("#" + String.format("%02X%02X%02X", r, g, b));
            } else if ("RGB".equals(mode)) {
                if (percent) {
                    qqColorCh1Box.setValue(String.format("%.0f%%", r * 100f / 255f));
                    qqColorCh2Box.setValue(String.format("%.0f%%", g * 100f / 255f));
                    qqColorCh3Box.setValue(String.format("%.0f%%", b * 100f / 255f));
                } else {
                    qqColorCh1Box.setValue(String.valueOf(r));
                    qqColorCh2Box.setValue(String.valueOf(g));
                    qqColorCh3Box.setValue(String.valueOf(b));
                }
            } else {
                // HSL
                double[] hsl = colorRgbToHsl(r, g, b);
                if (percent) {
                    qqColorCh1Box.setValue(String.format("%.0f%%", hsl[0] * 100));
                    qqColorCh2Box.setValue(String.format("%.0f%%", hsl[1] * 100));
                    qqColorCh3Box.setValue(String.format("%.0f%%", hsl[2] * 100));
                } else {
                    qqColorCh1Box.setValue(String.valueOf((int) Math.round(hsl[0] * 360)));
                    qqColorCh2Box.setValue(String.valueOf((int) Math.round(hsl[1] * 255)));
                    qqColorCh3Box.setValue(String.valueOf((int) Math.round(hsl[2] * 255)));
                }
            }
        } finally {
            qqColorSyncing = false;
        }
    }

    private void onQqColorChannelEdited() {
        if (qqColorSyncing) return;
        String mode = qqColorModeButton.getValue();
        boolean percent = Boolean.TRUE.equals(qqColorValueModeButton.getValue());
        int newColor;
        try {
            if ("HEX".equals(mode)) {
                String t = qqColorCh1Box.getValue().trim();
                if (t.isEmpty()) return;
                if (!t.startsWith("#")) t = "#" + t;
                if (t.length() != 7) return;
                newColor = 0xFF000000 | Integer.parseInt(t.substring(1), 16);
            } else if ("RGB".equals(mode)) {
                int r = parseChannelInt(qqColorCh1Box.getValue(), percent);
                int g = parseChannelInt(qqColorCh2Box.getValue(), percent);
                int b = parseChannelInt(qqColorCh3Box.getValue(), percent);
                if (r < 0 || g < 0 || b < 0) return;
                newColor = 0xFF000000 | (r << 16) | (g << 8) | b;
            } else {
                // HSL
                double h, s, l;
                String hs = qqColorCh1Box.getValue().trim();
                String ss = qqColorCh2Box.getValue().trim();
                String ls = qqColorCh3Box.getValue().trim();
                if (hs.isEmpty() || ss.isEmpty() || ls.isEmpty()) return;
                if (percent) {
                    h = (parseDoublePercent(hs) / 100.0);
                    s = parseDoublePercent(ss) / 100.0;
                    l = parseDoublePercent(ls) / 100.0;
                } else {
                    double rawH = Double.parseDouble(stripPercent(hs));
                    h = ((rawH % 360) + 360) % 360 / 360.0;
                    s = Math.max(0, Math.min(255, Double.parseDouble(stripPercent(ss)))) / 255.0;
                    l = Math.max(0, Math.min(255, Double.parseDouble(stripPercent(ls)))) / 255.0;
                }
                int[] rgb = colorHslToRgb(h, s, l);
                newColor = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
            }
        } catch (NumberFormatException e) {
            return;
        }
        config.setQqMessageColor(newColor);
    }

    private static int parseChannelInt(String s, boolean percent) {
        try {
            if (s == null || s.isEmpty()) return -1;
            if (percent) {
                double p = parseDoublePercent(s);
                return (int) Math.round(Math.max(0, Math.min(100, p)) * 255 / 100);
            }
            double v = Double.parseDouble(stripPercent(s));
            return Math.max(0, Math.min(255, (int) Math.round(v)));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
    private static double parseDoublePercent(String s) {
        String t = stripPercent(s);
        double v = Double.parseDouble(t);
        return Math.max(0, Math.min(100, v));
    }
    private static String stripPercent(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.endsWith("%")) s = s.substring(0, s.length() - 1);
        return s;
    }

    // RGB <-> HSL 工具
    private static double[] colorRgbToHsl(int r, int g, int b) {
        double rf = r/255.0, gf = g/255.0, bf = b/255.0;
        double max = Math.max(rf, Math.max(gf, bf));
        double min = Math.min(rf, Math.min(gf, bf));
        double h = 0, s, l = (max + min) / 2.0;
        if (max == min) { h = 0; s = 0; }
        else {
            double d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            if (max == rf) h = ((gf - bf) / d + (gf < bf ? 6 : 0)) / 6;
            else if (max == gf) h = ((bf - rf) / d + 2) / 6;
            else h = ((rf - gf) / d + 4) / 6;
        }
        return new double[]{h, s, l};
    }
    private static int[] colorHslToRgb(double h, double s, double l) {
        double r, g, b;
        if (s == 0) { r = g = b = l; }
        else {
            double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
            double p = 2 * l - q;
            r = colorHue2rgb(p, q, h + 1/3.0);
            g = colorHue2rgb(p, q, h);
            b = colorHue2rgb(p, q, h - 1/3.0);
        }
        return new int[]{
                Math.max(0, Math.min(255, (int) Math.round(r * 255))),
                Math.max(0, Math.min(255, (int) Math.round(g * 255))),
                Math.max(0, Math.min(255, (int) Math.round(b * 255)))};
    }
    private static double colorHue2rgb(double p, double q, double t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1/6.0) return p + (q - p) * 6 * t;
        if (t < 1/2.0) return q;
        if (t < 2/3.0) return p + (q - p) * (2/3.0 - t) * 6;
        return p;
    }
    private void layoutQqColorRow(int controlX) {
        if (qqColorRowY < 0 || qqColorModeButton == null) return;
        int y = contentTop + qqColorRowY - scrollOffset;
        boolean visible = y + ROW_HEIGHT >= contentTop && y < contentBottom;
        int widgetY = y + 2;

        // 先确定COLOR_CONTROL_WIDTH的起始X：默认左对齐controlX。如果宽度不够，则向左扩展
        int startX = this.width - PADDING - COLOR_CONTROL_WIDTH;

        // 1. 模式下拉（RGB/HSL/HEX）
        int x = startX;
        qqColorModeButton.setX(x);
        qqColorModeButton.setY(widgetY);
        qqColorModeButton.visible = visible;
        x += qqColorModeButton.getWidth() + 4;

        String mode = qqColorModeButton.getValue();
        boolean notHex = !"HEX".equals(mode);

        // 2. 非HEX：数值模式按钮
        if (notHex && qqColorValueModeButton != null) {
            qqColorValueModeButton.setX(x);
            qqColorValueModeButton.setY(widgetY);
            qqColorValueModeButton.visible = visible;
            x += qqColorValueModeButton.getWidth() + 4;
        }

        // 3. 通道输入框：非HEX=3个窄框，HEX=1个宽框（宽度=3框+2间隙）
        int previewSize = 20;
        int gap4 = 4;
        int remaining = (startX + COLOR_CONTROL_WIDTH - previewSize) - x - gap4;
        if (notHex) {
            int chW = Math.max(28, (remaining - gap4 * 2) / 3);
            qqColorCh1Box.setPosition(x, widgetY); qqColorCh1Box.setWidth(chW);
            qqColorCh1Box.setVisible(visible);
            x += chW + gap4;
            qqColorCh2Box.setPosition(x, widgetY); qqColorCh2Box.setWidth(chW);
            qqColorCh2Box.setVisible(visible);
            x += chW + gap4;
            qqColorCh3Box.setPosition(x, widgetY); qqColorCh3Box.setWidth(chW);
            qqColorCh3Box.setVisible(visible);
        } else {
            qqColorCh1Box.setPosition(x, widgetY);
            qqColorCh1Box.setWidth(remaining);
            qqColorCh1Box.setVisible(visible);
        }

        // 4. 颜色预览方块（按钮）
        if (qqColorPreviewButton != null) {
            int px = startX + COLOR_CONTROL_WIDTH - previewSize;
            qqColorPreviewButton.setPosition(px, widgetY);
            qqColorPreviewButton.setSize(previewSize, previewSize);
            qqColorPreviewButton.visible = visible;
        }
    }

    /** 在scissor内部，先调用label绘制前填充颜色预览方块（因为按钮Widget本身是系统主题绘制，覆盖我们填色） */
    private void paintQqColorPreview(GuiGraphicsExtractor g) {
        if (qqColorRowY < 0 || qqColorPreviewButton == null || !qqColorPreviewButton.visible) return;
        int x = qqColorPreviewButton.getX();
        int y = qqColorPreviewButton.getY();
        int w = qqColorPreviewButton.getWidth();
        int h = qqColorPreviewButton.getHeight();
        int color = 0xFF000000 | config.getQqMessageColor();
        g.fill(x, y, x + w, y + h, color);
        g.outline(x, y, w, h, 0xFFAAAAAA);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int controlX = this.width - PADDING - CONTROL_WIDTH;

        for (Row row : rows) {
            int y = contentTop + row.y - scrollOffset;
            row.widget.setX(controlX);
            row.widget.setY(y + 2);
            row.widget.visible = y + ROW_HEIGHT >= contentTop && y < contentBottom;
        }

        // 定位图片大小行的数字框和单位下拉框
        if (imageSizeRowY >= 0 && imageSizeNumberBox != null && imageSizeUnitButton != null) {
            int y = contentTop + imageSizeRowY - scrollOffset;
            boolean visible = y + ROW_HEIGHT >= contentTop && y < contentBottom;
            // 数字框占左侧 90px，单位下拉框占右侧 52px，中间留 4px 间隔
            int numberX = controlX;
            int unitX = controlX + 90 + 4;
            int widgetY = y + 2;
            imageSizeNumberBox.setPosition(numberX, widgetY);
            imageSizeNumberBox.setVisible(visible);
            imageSizeUnitButton.setPosition(unitX, widgetY);
            imageSizeUnitButton.visible = visible;
        }

        // 问题2：定位QQ颜色行控件
        layoutQqColorRow(controlX);

        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

        g.enableScissor(0, contentTop, this.width, contentBottom);

        int labelX = PADDING;
        for (Row row : rows) {
            int y = contentTop + row.y - scrollOffset;
            if (y + ROW_HEIGHT < contentTop || y >= contentBottom) continue;

            Component label = Component.translatable("light_chat_patch.options." + row.key);
            int color = row.key.startsWith("delete") ? 0xFFFF0000 : 0xFFFFFFFF;
            g.text(this.font, label, labelX, y + 7, color);
        }

        // 问题2：颜色预览方块最后填色（在Widget之上，因为Widget系统按钮会覆盖背景）
        paintQqColorPreview(g);

        g.disableScissor();

        if (maxScroll > 0) {
            int contentHeight = contentBottom - contentTop;
            int totalHeight = rows.get(rows.size() - 1).y + ROW_HEIGHT;
            int scrollBarHeight = Math.max(20, (int) ((float) contentHeight * contentHeight / totalHeight));
            int scrollBarY = contentTop + (int) ((float) scrollOffset / maxScroll * (contentHeight - scrollBarHeight));
            int scrollBarX = this.width - 8;
            g.fill(scrollBarX, scrollBarY, scrollBarX + 6, scrollBarY + scrollBarHeight, 0x80FFFFFF);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0 && mouseY >= contentTop && mouseY <= contentBottom) {
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - (int) (scrollY * 12)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        LcpConfig.save();
        this.minecraft.gui.setScreen(parent);
    }

    private record Row(int y, String key, AbstractWidget widget) {
    }
}