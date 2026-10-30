package menu;

import game.faction.FACTIONS;
import init.race.RACES;
import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.DIR;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sets.ArrayList;
import sosdet.game.deal.DealBook;
import sosdet.game.siege.AssaultTerms;
import sosgsgmp.game.PlayerSieges;
import util.data.INT;
import util.gui.misc.GButt;
import util.gui.misc.GText;
import util.gui.slider.GSliderInt;
import util.gui.table.GScrollRows;

/**
 * SOSGSGMP: the attacker's choice after its army reached another player's throne (spec §9; SOS-92 D): Sack, up to 80% of
 * the defender's money and goods and of its people, each person moved, killed or freed; or Vassalage with a yearly
 * tribute. Eliminate isn't built yet. Unanswered, half the money and goods are taken after two minutes and no one is
 * removed; the window closes then on its own.
 */
final class MpAssaultTerms extends MpWindow {

	private static final int WIDTH = 540;
	private static final int SLIDER = 112;
	private static final int COUNT = 56;
	private static final int ROW = 28;
	/** The rows shown before the races scroll. */
	private static final int ROWS = 6;
	private static final String[] FATES = {"Moved", "Killed", "Freed"};

	private final INT.IntImp sack = new INT.IntImp(0, AssaultTerms.MAX_SACK);
	private final INT.IntImp tribute = new INT.IntImp(0, 100);
	/** The people who can be removed, by race, as the window opened; the step caps the choice to the city by then. */
	private final int[] inCity;
	/** The choice: {@code AssaultTerms}'s flat counts. */
	private final int[] people;
	private final int limit;
	/** The choice was seen open: the window closes once it isn't (a window opened without one, as the GUI preview's, stays). */
	private boolean open;

	/** The window for the attacker of {@code defender}'s capital. */
	MpAssaultTerms(int defender) {
		super("Assault: the throne is yours");
		sack.set(AssaultTerms.DEFAULT_PERCENT);
		tribute.set(DealBook.DEFAULT_TRIBUTE);
		inCity = PlayerSieges.peopleInCity(defender);
		people = new int[inCity.length * AssaultTerms.FATES];
		limit = AssaultTerms.peopleLimit(inCity);
		inner.add(text("Your army reached " + FACTIONS.getByIndex(defender).name + "'s throne. Take a share of their money and"
				+ " goods, carried home by caravan, remove some of their people, and make peace; or make them your vassal."
				+ " Without a choice in two minutes, " + AssaultTerms.DEFAULT_PERCENT
				+ "% of the money and goods is taken and no one is removed."), 0, 0);
		inner.add(new GSliderInt(sack, 260, true, false), 0, inner.body().y2() + 12);
		inner.addRightC(8, percent(sack));
		if (limit > 0)
			addPeople();
		inner.add(chosenPeople(), 0, inner.body().y2() + 8);
		inner.addRightC(8, choose("Sack", AssaultTerms.SACK, sack));
		inner.add(new GSliderInt(tribute, 260, true, false), 0, inner.body().y2() + 12);
		inner.addRightC(8, percent(tribute));
		inner.addRightC(8, choose("Vassal", AssaultTerms.VASSALAGE, tribute));
	}

	private static RENDEROBJ text(String text) {
		return text(text, WIDTH);
	}

	private static RENDEROBJ text(String text, int width) {
		return new RENDEROBJ.RenderImp(width, UI.FONT().M.getHeight(text, width)) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				COLOR.WHITE100.bind();
				UI.FONT().M.render(r, text, body.x1(), body.y1(), body.width(), 1.0);
				COLOR.unbind();
			}
		};
	}

	/** One row per race in the city, with a slider per fate under the fates' names. */
	private void addPeople() {
		inner.add(text("People, up to " + limit + ". The moved arrive as prisoners. Moving lowers your mercy, killing lowers it"
				+ " twice as much, and freeing raises it."), 0, inner.body().y2() + 12);
		int y = inner.body().y2() + 8;
		for (int f = 0; f < FATES.length; f++)
			inner.add(text(FATES[f], SLIDER), 32 + f * (SLIDER + COUNT + 8), y);
		ArrayList<RENDEROBJ> rows = new ArrayList<>(inCity.length);
		for (int r = 0; r < inCity.length; r++)
			if (inCity[r] > 0)
				rows.add(race(r));
		inner.add(new GScrollRows(rows, Math.min(rows.size(), ROWS) * ROW).view(), 0, y + UI.FONT().M.height() + 4);
	}

	private GuiSection race(int race) {
		GuiSection row = new GuiSection();
		for (int f = 0; f < AssaultTerms.FATES; f++) {
			int index = race * AssaultTerms.FATES + f;
			row.addRightC(f == 0 ? 0 : 8, new GSliderInt(count(index), SLIDER, false, false));
			row.addRightC(4, countText(index));
		}
		row.addRelBody(4, DIR.W, RACES.all().get(race).appearance().icon.medium);
		return row;
	}

	/** One fate's count of one race: what the race and the limit leave for it. */
	private INT.INTE count(int index) {
		return new INT.INTE() {
			@Override
			public int min() {
				return 0;
			}

			@Override
			public int max() {
				int race = index / AssaultTerms.FATES;
				int ofRace = 0;
				for (int f = 0; f < AssaultTerms.FATES; f++)
					ofRace += people[race * AssaultTerms.FATES + f];
				int mine = people[index];
				return Math.max(mine, mine + Math.min(inCity[race] - ofRace, limit - chosen()));
			}

			@Override
			public int get() {
				return people[index];
			}

			@Override
			public void set(int t) {
				people[index] = t;
			}
		};
	}

	private int chosen() {
		int n = 0;
		for (int c : people)
			n += c;
		return n;
	}

	private RENDEROBJ countText(int index) {
		GText text = new GText(UI.FONT().M, 8);
		return new RENDEROBJ.RenderImp(COUNT, UI.FONT().M.height()) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				text.clear().add(people[index]);
				COLOR.WHITE100.bind();
				text.renderCY(r, body.x1(), body.cY());
				COLOR.unbind();
			}
		};
	}

	/** The people chosen so far, before the Sack button. */
	private RENDEROBJ chosenPeople() {
		GText text = new GText(UI.FONT().M, 32);
		return new RENDEROBJ.RenderImp(260, UI.FONT().M.height()) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				text.clear().add("People removed: ").add(chosen());
				COLOR.WHITE100.bind();
				text.renderCY(r, body.x1(), body.cY());
				COLOR.unbind();
			}
		};
	}

	/** The slider's value, in a fixed width so that "100%" fits before the button. */
	private static RENDEROBJ percent(INT.IntImp value) {
		GText text = new GText(UI.FONT().M, 8);
		return new RENDEROBJ.RenderImp(48, UI.FONT().M.height()) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				text.clear().add(value.get()).add('%');
				COLOR.WHITE100.bind();
				text.renderCY(r, body.x1(), body.cY());
				COLOR.unbind();
			}
		};
	}

	private GButt.ButtPanel choose(String name, int kind, INT.IntImp percent) {
		return new GButt.ButtPanel(name) {
			@Override
			protected void clickA() {
				int[] taken = kind == AssaultTerms.SACK && chosen() > 0 ? people.clone() : AssaultTerms.NO_PEOPLE;
				String why = PlayerSieges.termsRefusal(kind, percent.get(), taken);
				if (why != null) {
					new MpViewNotice("Assault", why, true).open();
					return;
				}
				PlayerSieges.terms(kind, percent.get(), taken);
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
