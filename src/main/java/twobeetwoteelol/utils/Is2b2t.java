package twobeetwoteelol.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.text.Text;
import twobeetwoteelol.mixin.TabAccessor;

public final class Is2b2t {

    public static boolean is2b2t(MinecraftClient mc) {
        return "2BUILDERS2TOOLS".equals(firstTabLine(mc));
    }

    public static String firstTabLine(MinecraftClient mc) {
        if (mc == null || mc.inGameHud == null) {
            return "";
        }

        PlayerListHud playerListHud = mc.inGameHud.getPlayerListHud();
        if (playerListHud == null) {
            return "";
        }

        Text header = ((TabAccessor) playerListHud).twobeetwoteelol$getHeader();
        if (header == null) {
            return "";
        }

        String raw = header.getString();
        if (raw == null || raw.isBlank()) {
            return "";
        }

        return raw.split("\\R", 2)[0].trim();
    }
}
