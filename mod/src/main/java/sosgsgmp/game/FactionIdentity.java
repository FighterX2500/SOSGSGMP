package sosgsgmp.game;

import game.faction.FACTIONS;
import game.faction.FBanner;
import game.faction.Faction;
import game.faction.player.Player;
import init.sprite.BitmapSprite;
import snake2d.util.color.ColorImp;
import sosdet.game.cmd.Names;

/**
 * SOSGSGMP test tooling (SOS-78): a player faction's name, ruler name and banner as one line, and this PC's player
 * renaming and changing the banner the way the profile window does: through the engine's hooks for its inputs, its
 * banner arrows and colour sliders, and its Accept. Game thread, outside the step.
 */
public final class FactionIdentity {

    private FactionIdentity() {
    }

    /** The faction in {@code slot}'s name, ruler name and banner, for comparing PCs; empty when it is no player. */
    public static String of(int slot) {
        Faction f = FACTIONS.getByIndex(slot);
        if (!(f instanceof Player p))
            return "";
        FBanner b = p.banner();
        StringBuilder s = new StringBuilder();
        s.append(p.name).append('|').append(p.rulerName).append('|').append(b.bannerType());
        for (ColorImp c : new ColorImp[] {b.colorBG(), b.colorFG(), b.colorBorder(), b.colorPole()})
            s.append('|').append(c.red() & 0xFF).append(',').append(c.green() & 0xFF).append(',').append(c.blue() & 0xFF);
        long pixels = 0;
        for (int i = 0; i < BitmapSprite.AREA; i++)
            if (b.sprite.is(i))
                pixels = pixels * 31 + i + 1;
        return s.append('|').append(Long.toHexString(pixels)).toString();
    }

    /**
     * This PC's player types {@code name} and {@code ruler} into the profile's inputs, steps the banner's shape on with
     * the arrows, moves its background's red slider, and accepts the banner.
     */
    public static void change(String name, String ruler) {
        Names.factionName(name);
        Names.rulerName(ruler);
        FBanner b = FACTIONS.player().banner();
        b.bannerTypeSet(b.bannerType() + 1); // opens the banner's draft, as the arrows do
        ColorImp bg = b.colorBG();
        int red = ((bg.red() & 0xFF) + 64) % 128;
        Names.colour(bg, 0, red); // the slider's hook, then the slider's own set
        bg.setRed(red);
        Names.bannerAccept();
    }
}
