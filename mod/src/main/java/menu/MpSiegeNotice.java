package menu;

import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.GUI_BOX;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosgsgmp.game.PlayerSieges;
import util.gui.misc.GBox;
import util.gui.misc.GButt;

/**
 * SOSGSGMP: the siege of this PC's player's capital has begun (spec §9, owner 2026-10-29; SOS-92): a reminder that they
 * can sally out at any time before the assault, with Sally out at hand. A window, not vanilla's full-screen prompt:
 * that one takes the game's time while it shows.
 */
final class MpSiegeNotice extends MpWindow {

	private static final int WIDTH = 380;

	MpSiegeNotice(int assaultInDays) {
		super("Your capital is besieged");
		String text = "Your capital is besieged. You can sally out against the besiegers at any time before the assault: "
				+ "Sally out here, on your capital's region panel, or in the multiplayer panel. The assault can start in " + assaultInDays
				+ (assaultInDays == 1 ? " day." : " days.");
		inner.add(new RENDEROBJ.RenderImp(WIDTH, UI.FONT().M.getHeight(text, WIDTH)) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, text, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		}, 0, 0);
		inner.addDown(8, sallyButton(this::close));
		inner.addRightC(8, new GButt.ButtPanel("Later") {
			@Override
			protected void clickA() {
				close();
			}
		});
	}

	/** Sally out, greyed with the engine's reason while it can't be done; {@code after} runs once it is sent. */
	static GButt.ButtPanel sallyButton(Runnable after) {
		return new GButt.ButtPanel("Sally out") {
			@Override
			protected void renAction() {
				activeSet(PlayerSieges.sallyRefusal() == null);
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				GBox b = (GBox) text;
				b.text("Fight the besiegers now: the siege asks you, as at its start.");
				String why = PlayerSieges.sallyRefusal();
				if (why != null)
					b.error(why);
			}

			@Override
			protected void clickA() {
				if (PlayerSieges.sallyRefusal() != null)
					return;
				PlayerSieges.sallyOut();
				after.run();
			}
		};
	}

	@Override
	void frame() {
	}
}
