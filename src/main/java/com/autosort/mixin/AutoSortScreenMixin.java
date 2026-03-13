package com.autosort.mixin;

import com.autosort.ChestSortClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes chest screens invisible during auto-sort/organize runs,
 * allowing the player to AFK and chat while sorting happens.
 */
@Mixin(HandledScreen.class)
public class AutoSortScreenMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void chestsort$suppressRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if ((Object) this instanceof GenericContainerScreen && ChestSortClient.isAutoOperating()) {
            ci.cancel();
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void chestsort$handleKeys(KeyInput input, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof GenericContainerScreen) || !ChestSortClient.isAutoOperating()) {
            return;
        }

        int keyCode = input.key();

        // T = open chat
        if (keyCode == GLFW.GLFW_KEY_T) {
            MinecraftClient.getInstance().setScreen(new ChatScreen("", false));
            cir.setReturnValue(true);
            return;
        }

        // / = open chat with slash
        if (keyCode == GLFW.GLFW_KEY_SLASH) {
            MinecraftClient.getInstance().setScreen(new ChatScreen("/", false));
            cir.setReturnValue(true);
            return;
        }

        // K or Escape = cancel sort/organize run
        if (keyCode == GLFW.GLFW_KEY_K || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            ChestSortClient.cancelAllRuns();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.closeHandledScreen();
                client.player.sendMessage(Text.literal("[ChestSort] Cancelled."), true);
            }
            cir.setReturnValue(true);
            return;
        }

        // Block all other keys
        cir.setReturnValue(false);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void chestsort$suppressMouseClick(Click click, boolean handled, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof GenericContainerScreen && ChestSortClient.isAutoOperating()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void chestsort$suppressMouseDrag(Click click, double deltaX, double deltaY, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof GenericContainerScreen && ChestSortClient.isAutoOperating()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void chestsort$suppressMouseScroll(double mouseX, double mouseY, double horizontalAmount, double verticalAmount, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof GenericContainerScreen && ChestSortClient.isAutoOperating()) {
            cir.setReturnValue(false);
        }
    }
}
