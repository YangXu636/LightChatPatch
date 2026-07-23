package top.xuyangjerry.mcmod.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import top.xuyangjerry.mcmod.client.ChatScreenState;
import top.xuyangjerry.mcmod.client.ChatScreenHandler;
import top.xuyangjerry.mcmod.client.message.MessageInfo;
import top.xuyangjerry.mcmod.config.LcpConfig;
import top.xuyangjerry.mcmod.mixin.client.ChatComponentAccess;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class RangeActionScreen extends Screen {
    private static final Component TITLE = Component.translatable("light_chat_patch.range_action.title");
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 160;

    private final Screen parent;
    private final ChatScreenState state;
    private List<Button> buttons = new ArrayList<>();
    private int selectedButton = 0;

    public RangeActionScreen(Screen parent, ChatScreenState state) {
        super(TITLE);
        this.parent = parent;
        this.state = state;
    }

    @Override
    protected void init() {
        buttons.clear();
        int centerX = this.width / 2;
        int y = this.height / 2 - 30;

        String rangeActionKey = LcpConfig.getInstance().isRangeSelectToggle()
                ? "light_chat_patch.range_action.toggle"
                : "light_chat_patch.range_action.select_all";

        Button rangeBtn = Button.builder(Component.translatable(rangeActionKey), btn -> handleApplyRange())
                .bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.addRenderableWidget(rangeBtn);
        buttons.add(rangeBtn);
        y += 24;

        Button filterBtn = Button.builder(Component.translatable("light_chat_patch.range_action.filter_by_sender"), btn -> handleFilterBySender())
                .bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.addRenderableWidget(filterBtn);
        buttons.add(filterBtn);
        y += 24;

        Button cancelBtn = Button.builder(CommonComponents.GUI_CANCEL, btn -> this.minecraft.setScreen(parent))
                .bounds(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.addRenderableWidget(cancelBtn);
        buttons.add(cancelBtn);

        selectedButton = 0;
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 40, 0xFFFFFFFF);

        for (int i = 0; i < buttons.size(); i++) {
            Button btn = buttons.get(i);
            if (i == selectedButton && !btn.isHovered()) {
                int btnX = btn.getX();
                int btnY = btn.getY();
                int w = btn.getWidth();
                int h = btn.getHeight();
                // 细描边高亮（避免覆盖按钮颜色）
                guiGraphics.fill(btnX - 1, btnY - 1, btnX + w + 1, btnY, 0xFFFFFFFF);
                guiGraphics.fill(btnX - 1, btnY + h, btnX + w + 1, btnY + h + 1, 0xFFFFFFFF);
                guiGraphics.fill(btnX - 1, btnY, btnX, btnY + h, 0xFFFFFFFF);
                guiGraphics.fill(btnX + w, btnY, btnX + w + 1, btnY + h, 0xFFFFFFFF);
            }
        }
    }

    private void handleApplyRange() {
        ChatScreenHandler.applyRangeSelection(state);
        this.minecraft.setScreen(parent);
    }

    private void handleFilterBySender() {
        Minecraft mc = Minecraft.getInstance();
        List<GuiMessage> targetMessages;
        if (state.rangeStart != null && state.rangeEnd != null) {
            targetMessages = ChatScreenHandler.getMessagesInRange(state);
        } else {
            ChatComponentAccess access = (ChatComponentAccess) mc.gui.getChat();
            targetMessages = access.lcp$getAllMessages();
        }

        Set<String> senders = new LinkedHashSet<>();
        for (GuiMessage m : targetMessages) {
            MessageInfo mi = MessageInfo.from(m);
            if (mi != null) {
                senders.add(mi.getSender());
            }
        }

        if (!senders.isEmpty()) {
            PlayerFilterScreen filterScreen = new PlayerFilterScreen(parent, state, new ArrayList<>(senders));
            if (state.rangeStart != null && state.rangeEnd != null) {
                filterScreen.setMessageRange(targetMessages);
            }
            mc.setScreen(filterScreen);
        } else {
            mc.setScreen(parent);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == 265 || keyCode == 263) {
            selectedButton = (selectedButton - 1 + buttons.size()) % buttons.size();
            return true;
        }
        if (keyCode == 264 || keyCode == 262) {
            selectedButton = (selectedButton + 1) % buttons.size();
            return true;
        }
        if (keyCode == 32 || keyCode == 257) {
            if (selectedButton >= 0 && selectedButton < buttons.size()) {
                buttons.get(selectedButton).onPress(event);
                return true;
            }
        }
        return super.keyPressed(event);
    }
}
