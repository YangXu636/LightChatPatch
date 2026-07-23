package top.xuyangjerry.mcmod.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import top.xuyangjerry.mcmod.client.ChatScreenState;
import top.xuyangjerry.mcmod.client.message.MessageInfo;
import top.xuyangjerry.mcmod.config.LcpConfig;
import top.xuyangjerry.mcmod.mixin.client.ChatComponentAccess;

import java.util.ArrayList;
import java.util.List;

public class PlayerFilterScreen extends Screen {
    private static final Component TITLE = Component.translatable("light_chat_patch.filter.title");
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 200;
    private static final int ENTRY_HEIGHT = 24;
    private static final int LIST_TOP = 60;
    private static final int LIST_BOTTOM_OFFSET = 40;
    private static final int FACE_SIZE = 16;

    private final Screen parent;
    private final ChatScreenState state;
    private final List<String> senders;
    private List<GuiMessage> messageRange;

    private List<PlayerEntry> entries = new ArrayList<>();
    private int listHeight;
    private int contentHeight;
    private int scrollOffset = 0;
    private int selectedIndex = 0;

    public PlayerFilterScreen(Screen parent, ChatScreenState state, List<String> senders) {
        super(TITLE);
        this.parent = parent;
        this.state = state;
        this.senders = senders;
    }

    public void setMessageRange(List<GuiMessage> range) {
        this.messageRange = range;
    }

    @Override
    protected void init() {
        for (PlayerEntry entry : this.entries) {
            this.removeWidget(entry);
        }
        this.entries.clear();

        this.listHeight = this.height - LIST_TOP - LIST_BOTTOM_OFFSET;
        this.contentHeight = this.senders.size() * ENTRY_HEIGHT;
        this.scrollOffset = 0;

        int listX = this.width / 2 - BUTTON_WIDTH / 2 - 24;
        int listW = BUTTON_WIDTH + 24;

        for (int i = 0; i < this.senders.size(); i++) {
            String sender = this.senders.get(i);
            PlayerEntry entry = new PlayerEntry(sender, i, listX, 0, listW, ENTRY_HEIGHT);
            this.entries.add(entry);
            this.addWidget(entry);
        }

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, btn -> this.minecraft.setScreen(parent))
                .bounds(this.width / 2 - BUTTON_WIDTH / 2, this.height - 30, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 40, 0xFFFFFFFF);

        int listX = this.width / 2 - BUTTON_WIDTH / 2 - 24;
        int listW = BUTTON_WIDTH + 24;

        guiGraphics.enableScissor(listX, LIST_TOP, listX + listW, LIST_TOP + this.listHeight);

        for (int i = 0; i < this.entries.size(); i++) {
            PlayerEntry entry = this.entries.get(i);
            int y = LIST_TOP + i * ENTRY_HEIGHT - this.scrollOffset;
            entry.setY(y);
            boolean inView = y + ENTRY_HEIGHT > LIST_TOP && y < LIST_TOP + this.listHeight;
            if (inView) {
                entry.render(guiGraphics, mouseX, mouseY, partialTick);
            }
        }

        guiGraphics.disableScissor();

        if (this.contentHeight > this.listHeight) {
            int scrollbarX = listX + listW - 6;
            int trackHeight = this.listHeight;
            int thumbHeight = Math.max(20, trackHeight * this.listHeight / this.contentHeight);
            int maxScroll = Math.max(1, this.contentHeight - this.listHeight);
            int thumbY = LIST_TOP + this.scrollOffset * (trackHeight - thumbHeight) / maxScroll;
            guiGraphics.fill(scrollbarX, LIST_TOP, scrollbarX + 4, LIST_TOP + trackHeight, 0x80000000);
            guiGraphics.fill(scrollbarX, thumbY, scrollbarX + 4, thumbY + thumbHeight, 0xFFAAAAAA);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int listX = this.width / 2 - BUTTON_WIDTH / 2 - 24;
        int listW = BUTTON_WIDTH + 24;
        if (mouseX >= listX && mouseX < listX + listW && mouseY >= LIST_TOP && mouseY < LIST_TOP + this.listHeight) {
            this.scrollOffset -= (int) (scrollY * ENTRY_HEIGHT);
            this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, Math.max(0, this.contentHeight - this.listHeight)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int countSelectedBySender(String sender) {
        int count = 0;
        for (GuiMessage msg : getTargetMessages()) {
            MessageInfo info = MessageInfo.from(msg);
            if (info != null && sender.equals(info.getSender()) && state.selectedMessages.contains(msg)) {
                count++;
            }
        }
        return count;
    }

    private int countTotalBySender(String sender) {
        int count = 0;
        for (GuiMessage msg : getTargetMessages()) {
            MessageInfo info = MessageInfo.from(msg);
            if (info != null && sender.equals(info.getSender())) {
                count++;
            }
        }
        return count;
    }

    private List<GuiMessage> getTargetMessages() {
        if (messageRange != null && !messageRange.isEmpty()) {
            return messageRange;
        }
        Minecraft mc = Minecraft.getInstance();
        ChatComponent chat = mc.gui.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;
        return access.lcp$getAllMessages();
    }

    private void toggleSender(String sender) {
        List<GuiMessage> targetMessages = getTargetMessages();

        if (LcpConfig.getInstance().isRangeSelectToggle()) {
            // 反转模式：对范围内该发送者的消息 toggle
            for (GuiMessage msg : targetMessages) {
                MessageInfo info = MessageInfo.from(msg);
                if (info != null && sender.equals(info.getSender())) {
                    if (state.selectedMessages.contains(msg)) {
                        state.selectedMessages.remove(msg);
                    } else {
                        state.selectedMessages.add(msg);
                    }
                }
            }
        } else {
            // 全选模式：如果范围内该发送者的消息已全部选中，则全部取消；否则全部选中
            boolean allSelected = true;
            for (GuiMessage msg : targetMessages) {
                MessageInfo info = MessageInfo.from(msg);
                if (info != null && sender.equals(info.getSender())) {
                    if (!state.selectedMessages.contains(msg)) {
                        allSelected = false;
                        break;
                    }
                }
            }
            for (GuiMessage msg : targetMessages) {
                MessageInfo info = MessageInfo.from(msg);
                if (info != null && sender.equals(info.getSender())) {
                    if (allSelected) {
                        state.selectedMessages.remove(msg);
                    } else {
                        state.selectedMessages.add(msg);
                    }
                }
            }
        }
    }

    private class PlayerEntry extends AbstractWidget {
        private final String sender;
        private final int index;

        PlayerEntry(String sender, int index, int x, int y, int width, int height) {
            super(x, y, width, height, Component.literal(sender));
            this.sender = sender;
            this.index = index;
        }

        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            boolean hovered = mouseX >= this.getX() && mouseX < this.getX() + this.width
                    && mouseY >= this.getY() && mouseY < this.getY() + this.height;
            boolean keyboardSelected = selectedIndex == index;

            int selected = countSelectedBySender(sender);
            int total = countTotalBySender(sender);
            boolean hasSelected = selected > 0;

            int bgColor;
            if (keyboardSelected) {
                bgColor = hasSelected ? 0xA00088CC : 0xA00066CC;
            } else if (hasSelected) {
                bgColor = hovered ? 0x800088CC : 0x500088CC;
            } else {
                bgColor = hovered ? 0x80FFFFFF : 0x80000000;
            }
            guiGraphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, bgColor);

            // 尝试绘制玩家头像
            Player player = null;
            if (Minecraft.getInstance().level != null) {
                for (Player p : Minecraft.getInstance().level.players()) {
                    if (p.getName().getString().equals(sender)) {
                        player = p;
                        break;
                    }
                }
            }
            if (player instanceof net.minecraft.client.player.AbstractClientPlayer clientPlayer) {
                net.minecraft.world.entity.player.PlayerSkin skin = clientPlayer.getSkin();
                net.minecraft.client.gui.components.PlayerFaceRenderer.draw(guiGraphics, skin, this.getX() + 4, this.getY() + 4, FACE_SIZE);
            }

            String display = sender + " (" + selected + "/" + total + ")";
            guiGraphics.drawString(font, display, this.getX() + 24, this.getY() + (this.height - 8) / 2, 0xFFFFFFFF);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean handled) {
            if (this.getY() + this.height <= LIST_TOP || this.getY() >= LIST_TOP + PlayerFilterScreen.this.listHeight) {
                return false;
            }
            return super.mouseClicked(event, handled);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean handled) {
            toggleSender(sender);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            this.defaultButtonNarrationText(narrationElementOutput);
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
            navigate(-1);
            return true;
        }
        if (keyCode == 264 || keyCode == 262) {
            navigate(1);
            return true;
        }
        if (keyCode == 32 || keyCode == 257) {
            if (selectedIndex >= 0 && selectedIndex < entries.size()) {
                toggleSender(senders.get(selectedIndex));
                return true;
            }
        }
        return super.keyPressed(event);
    }

    private void navigate(int direction) {
        if (entries.isEmpty()) return;
        selectedIndex += direction;
        selectedIndex = Math.max(0, Math.min(selectedIndex, entries.size() - 1));

        int visibleTop = scrollOffset / ENTRY_HEIGHT;
        int visibleBottom = visibleTop + listHeight / ENTRY_HEIGHT;

        if (selectedIndex < visibleTop) {
            scrollOffset = selectedIndex * ENTRY_HEIGHT;
        } else if (selectedIndex >= visibleBottom) {
            scrollOffset = (selectedIndex - listHeight / ENTRY_HEIGHT + 1) * ENTRY_HEIGHT;
        }
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, contentHeight - listHeight)));
    }
}
