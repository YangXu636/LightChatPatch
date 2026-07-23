package top.xuyangjerry.mcmod.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import top.xuyangjerry.mcmod.config.ChatHistoryView;
import top.xuyangjerry.mcmod.config.LcpConfig;

import java.util.ArrayList;
import java.util.List;

public class LightChatPatchConfigScreen extends Screen {
    private static final Component TITLE = Component.translatable("light_chat_patch.options.title");
    private static final int ROW_HEIGHT = 24;
    private static final int CONTROL_WIDTH = 150;
    private static final int PADDING = 10;

    private final Screen parent;
    private final LcpConfig config;
    private final List<Row> rows = new ArrayList<>();
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
        addRow(controlX, y, "chatHistoryMaxSize", createSlider(config.getChatHistoryMaxSize(), config::setChatHistoryMaxSize));
        y += ROW_HEIGHT;
        addRow(controlX, y, "chatMaxVisibleMessages", createSlider(config.getChatMaxVisibleMessages(), config::setChatMaxVisibleMessages));
        y += ROW_HEIGHT;
        addRow(controlX, y, "clickReplyToJump", createOnOff(config.isClickReplyToJump(), config::setClickReplyToJump));
        y += ROW_HEIGHT;
        addRow(controlX, y, "preserveChatHistory", createOnOff(config.isPreserveChatHistory(), config::setPreserveChatHistory));
        y += ROW_HEIGHT;
        addRow(controlX, y, "rangeSelectToggle", createOnOff(config.isRangeSelectToggle(), config::setRangeSelectToggle));

        maxScroll = Math.max(0, y - (contentBottom - contentTop));

        // 底部完成按钮
        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_DONE, button -> {
                            LcpConfig.save();
                            this.minecraft.setScreen(parent);
                        })
                        .bounds(this.width / 2 - 100, this.height - 29, 200, 20)
                        .build()
        );
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

    private AbstractWidget createSlider(int value, java.util.function.IntConsumer setter) {
        return new AbstractSliderButton(0, 0, CONTROL_WIDTH, 20, Component.empty(),
                (Math.max(10, Math.min(1000, value)) - 10) / 990.0) {
            @Override
            protected void updateMessage() {
                this.setMessage(Component.literal(String.valueOf((int) (this.value * 990.0) + 10)));
            }

            @Override
            protected void applyValue() {
                setter.accept((int) (this.value * 990.0) + 10);
            }
        };
    }

    private AbstractWidget createHistoryViewButton() {
        return CycleButton.<ChatHistoryView>builder(
                        view -> Component.translatable("light_chat_patch.options.chatHistoryView." + view.getKey()),
                        config.getChatHistoryView())
                .withValues(ChatHistoryView.values())
                .displayOnlyValue()
                .create(0, 0, CONTROL_WIDTH, 20, Component.empty(), (btn, val) -> config.setChatHistoryView(val));
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int controlX = this.width - PADDING - CONTROL_WIDTH;

        // 根据滚动偏移更新所有控件位置
        for (Row row : rows) {
            int y = contentTop + row.y - scrollOffset;
            row.widget.setX(controlX);
            row.widget.setY(y + 2);
            row.widget.visible = y + ROW_HEIGHT >= contentTop && y < contentBottom;
        }

        // super.render 处理背景、完成按钮和所有已注册控件
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

        // 裁剪内容区域并绘制标签
        guiGraphics.enableScissor(0, contentTop, this.width, contentBottom);

        int labelX = PADDING;
        for (Row row : rows) {
            int y = contentTop + row.y - scrollOffset;
            if (y + ROW_HEIGHT < contentTop || y >= contentBottom) continue;

            Component label = Component.translatable("light_chat_patch.options." + row.key);
            guiGraphics.drawString(this.font, label, labelX, y + 7, 0xFFFFFFFF);
        }

        guiGraphics.disableScissor();

        // 滚动条
        if (maxScroll > 0) {
            int contentHeight = contentBottom - contentTop;
            int totalHeight = rows.get(rows.size() - 1).y + ROW_HEIGHT;
            int scrollBarHeight = Math.max(20, (int) ((float) contentHeight * contentHeight / totalHeight));
            int scrollBarY = contentTop + (int) ((float) scrollOffset / maxScroll * (contentHeight - scrollBarHeight));
            int scrollBarX = this.width - 8;
            guiGraphics.fill(scrollBarX, scrollBarY, scrollBarX + 6, scrollBarY + scrollBarHeight, 0x80FFFFFF);
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
        this.minecraft.setScreen(parent);
    }

    private record Row(int y, String key, AbstractWidget widget) {
    }
}
