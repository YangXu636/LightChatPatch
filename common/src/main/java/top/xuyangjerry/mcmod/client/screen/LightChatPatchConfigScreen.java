package top.xuyangjerry.mcmod.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import top.xuyangjerry.mcmod.config.ChatHistoryView;
import top.xuyangjerry.mcmod.config.LcpConfig;
import top.xuyangjerry.mcmod.history.ChatBoxHistoryManager;
import top.xuyangjerry.mcmod.history.ChatHistoryManager;

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

        y += 8;

        addRow(controlX, y, "deleteChatBoxHistory", createDeleteButton(true));
        y += ROW_HEIGHT;
        addRow(controlX, y, "deleteChatHistory", createDeleteButton(false));
        y += ROW_HEIGHT;

        maxScroll = Math.max(0, y - (contentBottom - contentTop));

        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_DONE, button -> {
                            LcpConfig.save();
                            this.minecraft.setScreen(parent);
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
                            if (minecraft.gui != null && minecraft.gui.getChat() != null) {
                                minecraft.gui.getChat().getRecentChat().clear();
                                minecraft.gui.getChat().getRecentChat().addLast("");
                            }
                        }
                    }
                    minecraft.setScreen(this);
                },
                Component.translatable(titleKey),
                Component.translatable(confirmKey),
                CommonComponents.GUI_YES,
                CommonComponents.GUI_NO
        );
        minecraft.setScreen(confirmScreen);
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

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int controlX = this.width - PADDING - CONTROL_WIDTH;

        for (Row row : rows) {
            int y = contentTop + row.y - scrollOffset;
            row.widget.setX(controlX);
            row.widget.setY(y + 2);
            row.widget.visible = y + ROW_HEIGHT >= contentTop && y < contentBottom;
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

        guiGraphics.enableScissor(0, contentTop, this.width, contentBottom);

        int labelX = PADDING;
        for (Row row : rows) {
            int y = contentTop + row.y - scrollOffset;
            if (y + ROW_HEIGHT < contentTop || y >= contentBottom) continue;

            Component label = Component.translatable("light_chat_patch.options." + row.key);
            int color = row.key.startsWith("delete") ? 0xFFFF0000 : 0xFFFFFFFF;
            guiGraphics.drawString(this.font, label, labelX, y + 7, color);
        }

        guiGraphics.disableScissor();

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
