package dev.anima.client.mixin;

import dev.anima.client.ui.AnimaDropdownWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Installs Anima's custom sleek dark dropdown UI in the Escape (Pause) Menu.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void anima$addCustomDropdown(CallbackInfo ci) {
        this.addRenderableWidget(new AnimaDropdownWidget(12, this.height - 34, 186, 24));
    }
}
