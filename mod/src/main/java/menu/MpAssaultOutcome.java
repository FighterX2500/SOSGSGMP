package menu;

import game.faction.FACTIONS;
import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosdet.Assault;
import util.gui.misc.GButt;

/**
 * SOSGSGMP: how the assault on a player's capital ended, for the attacker and the defender (spec §9; SOS-92 C):
 * information only. Sack, Eliminate and Vassalage after a taken throne are step D; until then the siege goes on.
 */
final class MpAssaultOutcome extends MpWindow {

	private static final int WIDTH = 380;

	/** The window for the player in {@code slot}, the assault's attacker or defender. */
	MpAssaultOutcome(Assault a, int slot) {
		super(title(a, slot == a.attacker));
		String text = text(a, slot == a.attacker);
		inner.add(new RENDEROBJ.RenderImp(WIDTH, UI.FONT().M.getHeight(text, WIDTH)) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, text, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		}, 0, 0);
		inner.addDown(8, new GButt.ButtPanel("OK") {
			@Override
			protected void clickA() {
				close();
			}
		});
	}

	private static String title(Assault a, boolean attacker) {
		if (a.outcome() == Assault.RETREAT)
			return "Assault: withdrawal";
		return (a.outcome() == Assault.HELD) != attacker ? "Assault: victory" : "Assault: defeat";
	}

	private static String text(Assault a, boolean attacker) {
		CharSequence them = FACTIONS.getByIndex(attacker ? a.defender : a.attacker).name;
		switch (a.outcome()) {
		case Assault.HELD:
			return attacker ? "The assault on " + them + "'s capital failed. Your " + a.men + " men are lost."
					: "Your capital held against " + them + ". Their " + a.men + " men are lost.";
		case Assault.TAKEN:
			return attacker ? "Your army reached " + them + "'s throne. Sack, Eliminate and Vassalage aren't built yet: the siege goes on."
					: them + "'s army reached your throne. What they take isn't built yet: the siege goes on.";
		default:
			return attacker ? "After five days your army withdrew from " + them + "'s capital. The siege goes on, with a new wait."
					: them + "'s army withdrew after five days. The siege goes on, with a new wait.";
		}
	}

	@Override
	void frame() {
	}
}
