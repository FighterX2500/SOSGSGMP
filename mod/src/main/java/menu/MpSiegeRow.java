package menu;

import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.gui.GuiSection;
import sosgsgmp.game.PlayerSieges;
import util.data.GETTER;
import util.gui.misc.GButt;
import util.gui.misc.GText;
import world.map.regions.Region;

/**
 * SOSGSGMP: the region panel's siege row (spec §9, owner 2026-10-29; SOS-92), under the military row of this PC's
 * player's own region (hook M9 in {@code view/world/ui/region/Play.java}). Shown only on their capital while it is
 * besieged: the days to the assault and Sally out. The panel is built before a session begins, so the row keeps its
 * height in every game and draws nothing when it doesn't apply.
 */
public final class MpSiegeRow extends GuiSection {

	private final GETTER<Region> region;
	private final GText status = new GText(UI.FONT().M, 64);

	public MpSiegeRow(GETTER<Region> region, int width) {
		this.region = region;
		GButt.ButtPanel sally = MpSiegeNotice.sallyButton(() -> {
		});
		body().setWidth(width);
		body().setHeight(sally.body().height());
		add(sally, width - sally.body().width() - 16, 0);
		addCentredY(status, 8);
		status.warnify();
	}

	@Override
	public void render(SPRITE_RENDERER r, float ds) {
		visableSet(PlayerSieges.besiegedCapital(region.get()) && PlayerSieges.beforeAssault());
		if (!visableIs())
			return;
		int days = PlayerSieges.assaultInDays();
		status.clear().add("Besieged: the assault can start in ").add(days).add(days == 1 ? " day." : " days.");
		super.render(r, ds);
	}
}
