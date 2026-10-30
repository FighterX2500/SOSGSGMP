package menu;

import java.util.Locale;

import game.faction.FACTIONS;
import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosdet.Assault;
import sosdet.game.siege.AssaultTerms;
import util.gui.misc.GButt;

/**
 * SOSGSGMP: how the assault on a player's capital ended, for the attacker and the defender (spec §9; SOS-92 C):
 * information only. After a taken throne the attacker chooses in {@link MpAssaultTerms} (step D).
 */
final class MpAssaultOutcome extends MpWindow {

	private static final int WIDTH = 380;

	/** The window for the assault's attacker ({@code asAttacker}) or defender; {@code men} the attacker committed. */
	MpAssaultOutcome(int attacker, int defender, int outcome, int men, boolean asAttacker) {
		super(title(outcome, asAttacker));
		String text = text(FACTIONS.getByIndex(asAttacker ? defender : attacker).name, outcome, men, asAttacker);
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

	private static String title(int outcome, boolean attacker) {
		if (outcome == Assault.RETREAT)
			return "Assault: withdrawal";
		return (outcome == Assault.HELD) != attacker ? "Assault: victory" : "Assault: defeat";
	}

	/** Every other player's notice of an NPC army invading a player's capital (SOS-92 F). */
	static String invasionText(CharSequence defender, CharSequence invader) {
		return defender + "'s capital is invaded by " + invader + ".";
	}

	/** Every other player's notice of an assault's outcome. */
	static String othersText(CharSequence attacker, CharSequence defender, int outcome) {
		String how = outcome == Assault.HELD ? "held" : outcome == Assault.TAKEN ? "fell" : "held: the attackers withdrew";
		return attacker + " stormed " + defender + "'s capital. It " + how + ".";
	}

	/** The defender's and every other player's notice of what the attacker took (SOS-92 D). */
	static String termsText(CharSequence attacker, CharSequence defender, int kind, int percent, int credits) {
		return kind == AssaultTerms.SACK
				? attacker + " sacked " + defender + "'s capital: " + percent + "% of its money (" + String.format(Locale.ENGLISH, "%,d", credits) + ") and goods, and made peace."
				: defender + " is now " + attacker + "'s vassal, with a tribute of " + percent + "%.";
	}

	private static String text(CharSequence them, int outcome, int men, boolean attacker) {
		switch (outcome) {
		case Assault.HELD:
			return attacker ? "The assault on " + them + "'s capital failed. Your " + men + " men are lost."
					: "Your capital held against " + them + ". Their " + men + " men are lost.";
		case Assault.TAKEN:
			return attacker ? "Your army reached " + them + "'s throne."
					: them + "'s army reached your throne. They choose what they take: a share of your money and goods, or you as their vassal.";
		default:
			return attacker ? "After five days your army withdrew from " + them + "'s capital. The siege goes on, with a new wait."
					: them + "'s army withdrew after five days. The siege goes on, with a new wait.";
		}
	}

	@Override
	void frame() {
	}
}
