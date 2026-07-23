package top.xuyangjerry.mcmod.client.screen;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ForwardTargetScreen extends Screen {
    private static final Component TITLE = Component.translatable("light_chat_patch.forward.title");
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 200;
    private static final int ENTRY_HEIGHT = 24;
    private static final int LIST_TOP = 60;
    private static final int LIST_BOTTOM_OFFSET = 40;
    private static final int FACE_SIZE = 16;

    private final Screen parent;
    private final List<String[]> messages;

    private List<? extends Player> players = List.of();
    private final Set<String> selectedTargets = new LinkedHashSet<>();
    private List<PlayerEntry> entries = new ArrayList<>();
    private int listHeight;
    private int contentHeight;
    private int scrollOffset = 0;
    private int selectedIndex = 0;
    private Button confirmButton;

    public ForwardTargetScreen(Screen parent, List<String[]> messages) {
        super(TITLE);
        this.parent = parent;
        this.messages = messages;
    }

    public ForwardTargetScreen(Screen parent, String senderName, String messageContent) {
        this(parent, java.util.Collections.singletonList(new String[]{senderName, messageContent}));
    }

    @Override
    protected void init() {
        this.players = new ArrayList<>();
        if (this.minecraft != null && this.minecraft.level != null) {
            this.players = new ArrayList<>(this.minecraft.level.players());
        }

        for (PlayerEntry entry : this.entries) {
            this.removeWidget(entry);
        }
        this.entries.clear();

        this.listHeight = this.height - LIST_TOP - LIST_BOTTOM_OFFSET;
        this.contentHeight = this.players.size() * ENTRY_HEIGHT;
        this.scrollOffset = 0;

        int listX = this.width / 2 - BUTTON_WIDTH / 2 - 24;
        int listW = BUTTON_WIDTH + 24;

        for (int i = 0; i < this.players.size(); i++) {
            Player player = this.players.get(i);
            PlayerEntry entry = new PlayerEntry(player, i, listX, 0, listW, ENTRY_HEIGHT);
            this.entries.add(entry);
            this.addWidget(entry);
        }

        // 确认转发按钮
        this.confirmButton = Button.builder(
                        Component.translatable("light_chat_patch.forward.confirm"),
                        btn -> handleConfirm())
                .bounds(this.width / 2 - BUTTON_WIDTH - 2, this.height - 30, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.addRenderableWidget(this.confirmButton);

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, btn -> this.minecraft.setScreen(parent))
                .bounds(this.width / 2 + 2, this.height - 30, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 40, 0xFFFFFFFF);

        if (this.players.isEmpty()) {
            Component empty = Component.translatable("light_chat_patch.forward.no_players");
            guiGraphics.drawCenteredString(this.font, empty, this.width / 2, LIST_TOP + listHeight / 2, 0xFFAAAAAA);
            return;
        }

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

        // 更新确认按钮文字
        this.confirmButton.setMessage(Component.translatable("light_chat_patch.forward.confirm_count", selectedTargets.size()));
        this.confirmButton.active = !selectedTargets.isEmpty();
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

    private void toggleTarget(String name) {
        if (selectedTargets.contains(name)) {
            selectedTargets.remove(name);
        } else {
            selectedTargets.add(name);
        }
    }

    private void handleConfirm() {
        if (this.minecraft != null && this.minecraft.player != null) {
            for (String targetName : selectedTargets) {
                for (String[] msg : messages) {
                    String forwardText = msg[0] + " " + msg[1];
                    new ChatScreen("", false).handleChatInput("/msg " + targetName + " " + forwardText, true);
                }
            }
        }
        this.minecraft.setScreen(parent);
    }

    private class PlayerEntry extends AbstractWidget {
        private final Player player;
        private final int index;

        PlayerEntry(Player player, int index, int x, int y, int width, int height) {
            super(x, y, width, height, player.getName());
            this.player = player;
            this.index = index;
            this.visible = true;
            this.active = true;
        }

        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            String name = this.player.getName().getString();
            boolean isSelected = selectedTargets.contains(name);
            boolean hovered = mouseX >= this.getX() && mouseX < this.getX() + this.width
                    && mouseY >= this.getY() && mouseY < this.getY() + this.height;
            boolean keyboardSelected = selectedIndex == index;

            int bgColor;
            if (keyboardSelected) {
                bgColor = isSelected ? 0xA00088CC : 0xA00066CC;
            } else if (isSelected) {
                bgColor = hovered ? 0x800088CC : 0x500088CC;
            } else {
                bgColor = hovered ? 0x80FFFFFF : 0x80000000;
            }
            guiGraphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, bgColor);

            if (this.player instanceof AbstractClientPlayer clientPlayer) {
                PlayerSkin skin = clientPlayer.getSkin();
                PlayerFaceRenderer.draw(guiGraphics, skin, this.getX() + 4, this.getY() + 4, FACE_SIZE);
            }

            guiGraphics.drawString(font, this.player.getName(), this.getX() + 24, this.getY() + (this.height - 8) / 2, 0xFFFFFFFF);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean handled) {
            if (this.getY() + this.height <= LIST_TOP || this.getY() >= LIST_TOP + ForwardTargetScreen.this.listHeight) {
                return false;
            }
            return super.mouseClicked(event, handled);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean handled) {
            toggleTarget(this.player.getName().getString());
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
            if (selectedIndex >= 0 && selectedIndex < players.size()) {
                toggleTarget(players.get(selectedIndex).getName().getString());
                return true;
            }
        }
        return super.keyPressed(event);
    }

    private void navigate(int direction) {
        if (players.isEmpty()) return;
        selectedIndex += direction;
        selectedIndex = Math.max(0, Math.min(selectedIndex, players.size() - 1));

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
