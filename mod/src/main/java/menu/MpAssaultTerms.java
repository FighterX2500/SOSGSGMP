package menu;

import game.faction.FACTIONS;
import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosdet.game.deal.DealBook;
import sosdet.game.siege.AssaultTerms;
import sosgsgmp.game.PlayerSieges;
import util.data.INT;
import util.gui.misc.GButt;
import util.gui.misc.GStat;
import util.gui.misc.GText;
import util.gui.slider.GSliderInt;

/**
 * SOSGSGMP: the attacker's choice after its army reached another player's throne (spec §9; SOS-92 D): Sack, up to 80% of
 * the defender's money and goods, or Vassalage with a yearly tribute. Eliminate isn't built yet. Unanswered, half the
 * money and goods are taken after two minutes; the window closes then on its own.
 */
final class MpAssaultTerms extends MpWindow {

	private static final int WIDTH = 400;

	private final INT.IntImp sack = new INT.IntImp(0, AssaultTerms.MAX_SACK);
	private final INT.IntImp tribute = new INT.IntImp(0, 100);
	/** The choice was seen open: the window closes once it isn't (a window opened without one, as the GUI preview's, stays). */
	private boolean open;

	/** The window for the attacker of {@code defender}'s capital. */
	MpAssaultTerms(int defender) {
		super("Assault: the throne is yours");
		sack.set(AssaultTerms.DEFAULT_PERCENT);
		tribute.set(DealBook.DEFAULT_TRIBUTE);
		String text = "Your army reached " + FACTIONS.getByIndex(defender).name + "'s throne. Take a share of their money and"
				+ " goods, carried home by caravan, and make peace; or make them your vassal. Without a choice in two minutes, "
				+ AssaultTerms.DEFAULT_PERCENT + "% is taken.";
		inner.add(new RENDEROBJ.RenderImp(WIDTH, UI.FONT().M.getHeight(text, WIDTH)) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, text, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		}, 0, 0);
		inner.add(new GSliderInt(sack, 260, true, false), 0, inner.body().y2() + 12);
		inner.addRightC(8, percent(sack));
		inner.addRightC(8, choose("Sack", AssaultTerms.SACK, sack));
		inner.add(new GSliderInt(tribute, 260, true, false), 0, inner.body().y2() + 12);
		inner.addRightC(8, percent(tribute));
		inner.addRightC(8, choose("Vassal", AssaultTerms.VASSALAGE, tribute));
	}

	private static GStat percent(INT.IntImp value) {
		return new GStat() {
			@Override
			public void update(GText text) {
				text.add(value.get()).add('%');
			}
		};
	}

	private GButt.ButtPanel choose(String name, int kind, INT.IntImp percent) {
		return new GButt.ButtPanel(name) {
			@Override
			protected void clickA() {
				String why = PlayerSieges.termsRefusal(kind, percent.get());
				if (why != null) {
					new MpViewNotice("Assault", why, true).open();
					return;
				}
				PlayerSieges.terms(kind, percent.get());
				close();
			}
		};
	}

	@Override
	void frame() {
		if (PlayerSieges.taken() != null)
			open = true;
		else if (open)
			close(); // the choice applied, or its time ran out
	}
}
