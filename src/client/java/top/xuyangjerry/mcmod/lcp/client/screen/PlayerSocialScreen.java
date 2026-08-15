package top.xuyangjerry.mcmod.lcp.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;

import top.xuyangjerry.mcmod.lcp.client.mute.MuteClientManager;
import top.xuyangjerry.mcmod.lcp.network.MutePayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 玩家社交界面（Player Social Screen）
 *
 * 展示在线玩家列表，每行显示玩家名、头像以及"禁言"/"解禁"按钮。
 * - 按钮文案根据当前禁言状态动态切换
 * - 自己不会出现在列表中
 * - 点击按钮发送 C2S 禁言/解禁请求
 */
public class PlayerSocialScreen extends Screen {

    private static final Component TITLE = Component.translatable("light_chat_patch.social.title");
    private static final int ENTRY_HEIGHT = 28;
    private static final int LIST_TOP = 32;
    private static final int LIST_BOTTOM_OFFSET = 40;
    private static final int FACE_SIZE = 20;
    private static final int MUTE_BTN_WIDTH = 60;

    private final Screen parent;
    private List<PlayerInfo> playerInfos = new ArrayList<>();
    private List<Button> actionButtons = new ArrayList<>();
    private int scrollOffset = 0;

    private static class PlayerInfo {
        final String name;
        final UUID uuid;
        boolean muted;

        PlayerInfo(String name, UUID uuid, boolean muted) {
            this.name = name;
            this.uuid = uuid;
            this.muted = muted;
        }
    }

    public PlayerSocialScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    @Override
    protected void init() {
        refreshPlayers();

        this.addRenderableWidget(
                Button.builder(CommonComponents.GUI_DONE, btn -> this.minecraft.gui.setScreen(parent))
                        .bounds(this.width / 2 - 100, this.height - 30, 200, 20)
                        .build()
        );
    }

    private void refreshPlayers() {
        // 清除旧按钮
        for (Button btn : actionButtons) {
            this.removeWidget(btn);
        }
        actionButtons.clear();
        playerInfos.clear();

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        String myName = mc.player.getName().getString();
        List<? extends Player> playerList = mc.level.players();

        for (Player player : playerList) {
            String name = player.getName().getString();
            if (name.equals(myName)) continue;

            UUID uuid = player.getUUID();
            MutePayload.MuteEntry muted = MuteClientManager.findByName(name);
            boolean isMuted = muted != null;

            playerInfos.add(new PlayerInfo(name, uuid, isMuted));
        }

        // 创建按钮
        int listHeight = this.height - LIST_TOP - LIST_BOTTOM_OFFSET;
        int listWidth = this.width - 40;
        int listLeft = 20;

        for (int i = 0; i < playerInfos.size(); i++) {
            int y = LIST_TOP + i * ENTRY_HEIGHT;
            if (y + ENTRY_HEIGHT < LIST_TOP || y > this.height - LIST_BOTTOM_OFFSET) continue;

            PlayerInfo info = playerInfos.get(i);
            final int index = i;
            int btnX = listLeft + listWidth - MUTE_BTN_WIDTH - 4;
            int btnY = y + (ENTRY_HEIGHT - 20) / 2;
            Button btn = Button.builder(getButtonLabel(info.muted),
                            b -> onActionClicked(index))
                    .bounds(btnX, btnY, MUTE_BTN_WIDTH, 20)
                    .build();
            this.addRenderableWidget(btn);
            actionButtons.add(btn);
        }
    }

    private Component getButtonLabel(boolean isMuted) {
        return Component.translatable(isMuted
                ? "light_chat_patch.social.unmute"
                : "light_chat_patch.social.mute");
    }

    private void onActionClicked(int index) {
        if (index < 0 || index >= playerInfos.size()) return;
        PlayerInfo info = playerInfos.get(index);
        String uuidStr = info.uuid != null ? info.uuid.toString() : null;
        if (info.muted) {
            MuteClientManager.sendUnmuteRequest(info.name, uuidStr);
        } else {
            MuteClientManager.sendMuteRequest(info.name, uuidStr, "");
        }
        // 切换本地状态
        info.muted = !info.muted;
        // 更新按钮文案
        if (index < actionButtons.size()) {
            actionButtons.get(index).setMessage(getButtonLabel(info.muted));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

        int listHeight = this.height - LIST_TOP - LIST_BOTTOM_OFFSET;
        int listWidth = this.width - 40;
        int listLeft = 20;
        int contentHeight = playerInfos.size() * ENTRY_HEIGHT;
        int maxScroll = Math.max(0, contentHeight - listHeight);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        g.enableScissor(listLeft, LIST_TOP, listLeft + listWidth, this.height - LIST_BOTTOM_OFFSET);

        for (int i = 0; i < playerInfos.size(); i++) {
            int y = LIST_TOP + i * ENTRY_HEIGHT - scrollOffset;
            if (y + ENTRY_HEIGHT < LIST_TOP || y > this.height - LIST_BOTTOM_OFFSET) continue;

            PlayerInfo info = playerInfos.get(i);
            int x = listLeft;
            int w = listWidth;
            int h = ENTRY_HEIGHT;

            // 背景
            int bgColor = (i % 2 == 0) ? 0xFF1a1a1a : 0xFF222222;
            g.fill(x, y, x + w, y + h, bgColor);

            // 头像
            int faceX = x + 4;
            int faceY = y + (h - FACE_SIZE) / 2;
            Player clientPlayer = findPlayerByName(info.name);
            if (clientPlayer instanceof AbstractClientPlayer acp) {
                PlayerSkin skin = acp.getSkin();
                PlayerFaceExtractor.extractRenderState(g, skin, faceX, faceY, FACE_SIZE);
            } else {
                g.fill(faceX, faceY, faceX + FACE_SIZE, faceY + FACE_SIZE, 0xFF555555);
            }

            // 名字
            int nameColor = info.muted ? 0xFFFF5555 : 0xFFFFFFFF;
            g.text(minecraft.font, Component.literal(info.name)
                            .withStyle(Style.EMPTY.withColor(nameColor)),
                    faceX + FACE_SIZE + 4, y + (h - 9) / 2, nameColor);

            // 禁言状态标记
            if (info.muted) {
                String label = " [已禁言]";
                g.text(minecraft.font, Component.literal(label)
                                .withStyle(Style.EMPTY.withColor(0xFFFF5555)),
                        faceX + FACE_SIZE + 4 + minecraft.font.width(info.name),
                        y + (h - 9) / 2, 0xFFFF5555);
            }
        }

        g.disableScissor();

        // 滚动条
        if (contentHeight > listHeight) {
            int scrollBarH = Math.max(20, (int) ((float) listHeight * listHeight / contentHeight));
            int scrollBarY = LIST_TOP + (int) ((float) scrollOffset / maxScroll * (listHeight - scrollBarH));
            int scrollBarX = listLeft + listWidth + 2;
            g.fill(scrollBarX, scrollBarY, scrollBarX + 4, scrollBarY + scrollBarH, 0x80FFFFFF);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int listHeight = this.height - LIST_TOP - LIST_BOTTOM_OFFSET;
        int listLeft = 20;
        int listWidth = this.width - 40;
        if (mouseX >= listLeft && mouseX <= listLeft + listWidth
                && mouseY >= LIST_TOP && mouseY <= this.height - LIST_BOTTOM_OFFSET) {
            int contentHeight = playerInfos.size() * ENTRY_HEIGHT;
            int maxScroll = Math.max(0, contentHeight - listHeight);
            scrollOffset = Math.max(0, Math.min(scrollOffset - (int) (scrollY * 12), maxScroll));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean handled) {
        if (super.mouseClicked(event, handled)) return true;

        double mouseX = event.x();
        double mouseY = event.y();
        int listLeft = 20;
        int listWidth = this.width - 40;
        int listHeight = this.height - LIST_TOP - LIST_BOTTOM_OFFSET;

        if (mouseX >= listLeft && mouseX <= listLeft + listWidth
                && mouseY >= LIST_TOP && mouseY <= this.height - LIST_BOTTOM_OFFSET) {
            int clickedRow = (int) ((mouseY - LIST_TOP + scrollOffset) / ENTRY_HEIGHT);
            if (clickedRow >= 0 && clickedRow < playerInfos.size()) {
                onActionClicked(clickedRow);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    private static Player findPlayerByName(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        for (Player p : mc.level.players()) {
            if (p.getName().getString().equals(name)) return p;
        }
        return null;
    }
}