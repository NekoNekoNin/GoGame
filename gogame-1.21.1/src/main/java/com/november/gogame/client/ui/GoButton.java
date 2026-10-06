package com.november.gogame.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Custom artwork with vanilla Button's keyboard, focus, click, and narration behavior. */
public final class GoButton extends Button {
    public enum Tone { PRIMARY, SECONDARY, DANGER }
    private final Tone tone;

    public GoButton(int x, int y, int width, int height, Component label, OnPress action, Tone tone) {
        super(x, y, width, height, label, action, DEFAULT_NARRATION);
        this.tone = tone;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int base = switch (tone) {
            case PRIMARY -> 0xFF315E52;
            case SECONDARY -> 0xFF24332F;
            case DANGER -> 0xFF49332F;
        };
        int hover = GoUi.mix(base, tone == Tone.DANGER ? 0xFFE0A386 : GoUi.JADE, 0.22F);
        GoUi.button(g, Minecraft.getInstance().font, getX(), getY(), getWidth(), getHeight(),
                getMessage().getString(), active, isHoveredOrFocused(), base, hover,
                0xFF202B28, GoUi.IVORY, 0xFF72847C);
        if (isFocused()) GoUi.roundOutline(g, getX(), getY(), getWidth(), getHeight(), 4, GoUi.JADE);
    }
}
