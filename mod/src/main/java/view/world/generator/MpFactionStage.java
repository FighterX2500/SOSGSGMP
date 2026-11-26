package view.world.generator;

import game.faction.FACTIONS;
import game.faction.player.PTitles;
import game.faction.player.PTitles.PTitle;
import init.race.RACES;
import init.race.Race;
import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.datatypes.DIR;
import snake2d.util.gui.GUI_BOX;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sets.ArrayList;
import sosgsgmp.setup.TitlesMode;
import util.data.GETTER;
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import util.gui.misc.GStat;
import util.gui.misc.GText;
import util.gui.table.GScrollRows;
import util.gui.table.GTextScroller;
import util.info.GFORMAT;
import util.text.Dic;
import view.ui.profile.UIProfile;

/**
 * SOSGSGMP: the factions step's race and Customize Faction screens (setup-factions-step.md §2), with every player's
 * row beside them; a hot-join's pick shows them too (SOS-198). They are built as vanilla's StagePickRace and StageVisuals build theirs, from the same parts, but
 * without their Confirm: it runs WorldViewGenerator.set(), which goes on to vanilla's one-player capital stage.
 */
final class MpFactionStage {

	private static final CharSequence RACE_TITLE = "Select species";
	private static final CharSequence CUSTOMIZE_TITLE = "Customize Faction";
	private static final CharSequence HOST_TITLES = "Every player's faction holds the titles you pick, as strong as your profile makes them.";

	private MpFactionStage() {
	}

	/** The race screen: vanilla's race buttons, description, challenge, pros and cons. */
	static void race(MpFactionScreens f) {
		GuiSection s = new GuiSection();
		int i = 0;
		for (Race r : RACES.all())
			if (r.playable)
				s.addGrid(raceButton(r), i++, 6, 4, 4);
		s.addRelBody(16, DIR.N, new GText(UI.FONT().M, StagePickRace.¤¤desc).setMaxWidth(600));
		GETTER<CharSequence> desc = () -> FACTIONS.player().race().info.desc_long;
		s.addRelBody(16, DIR.S, new GTextScroller(UI.FONT().M, desc, 650, 150));
		s.addRelBody(16, DIR.S, new GStat() {
			@Override
			public void update(GText text) {
				text.add(FACTIONS.player().race().info.initialChallenge);
			}
		}.increase().hv("Initial Challenge"));
		s.addRelBody(16, DIR.S, new RENDEROBJ.RenderImp(650, 180) {
			private final GText t = new GText(UI.FONT().M, 64);

			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				Race rr = FACTIONS.player().race();
				t.setMaxWidth(280);
				t.setMultipleLines(true);
				int y = body.y1();
				for (String p : rr.info.pros) {
					t.clear().add('+').s().add(p).adjustWidth();
					t.normalify2();
					t.render(r, body.x1() + 16, y);
					y += t.height();
				}
				y = body.y1();
				for (String c : rr.info.cons) {
					t.clear().add('-').s().add(c).adjustWidth();
					t.errorify();
					t.render(r, body.x1() + 325, y);
					y += t.height();
				}
			}
		});
		show(f, s, RACE_TITLE, null, new GButt.ButtPanel("next") {
			@Override
			protected void clickA() {
				if (f.titles() != null)
					titles(f);
				else
					customize(f);
			}
		});
	}

	/**
	 * The titles screen (spec §5 Titles), after the race when this player picks titles: vanilla's title buttons, up to 5
	 * of this PC's profile's unlocked titles. In Host-Titles mode the host's are every player's.
	 */
	static void titles(MpFactionScreens f) {
		GuiSection s = new GuiSection();
		PTitles titles = FACTIONS.player().titles;
		s.addRelBody(4, DIR.S, new GText(UI.FONT().M, StagePickTitles.¤¤spent));
		if (f.titles() == TitlesMode.HOST)
			s.addRelBody(4, DIR.S, new GText(UI.FONT().M, HOST_TITLES).setMaxWidth(600));
		s.addRelBody(8, DIR.S, new GStat(UI.FONT().M) {
			@Override
			public void update(GText text) {
				GFORMAT.iofkInv(text, titles.selected(), TitlesMode.MAX_TITLES);
			}
		}.r(DIR.N));
		ArrayList<RENDEROBJ> rows = new ArrayList<>(titles.all().size());
		for (PTitle t : titles.all())
			rows.add(new TitleButton(t));
		s.addRelBody(8, DIR.S, new GScrollRows(rows, rows.get(0).body().height() * 6).view());
		GButt.ButtPanel back = new GButt.ButtPanel("back") {
			@Override
			protected void clickA() {
				race(f);
			}
		};
		show(f, s, StagePickTitles.¤¤title, back, new GButt.ButtPanel("next") {
			@Override
			protected void clickA() {
				customize(f);
			}
		});
	}

	/**
	 * The Customize Faction screen: vanilla's faction name, ruler name and banner, with Save and Load Default. A living
	 * world's other player (SOS-187) picks no location here: Ready is on this screen, and they settle on the built world.
	 */
	static void customize(MpFactionScreens f) {
		GuiSection s = new GuiSection();
		s.add(UIProfile.section(false));
		GButt.ButtPanel back = new GButt.ButtPanel("back") {
			@Override
			protected void clickA() {
				if (f.titles() != null)
					titles(f);
				else
					race(f);
			}
		};
		show(f, s, CUSTOMIZE_TITLE, back, f.afterCustomize());
	}

	/** Shows {@code content} with the players' side to its right, in the generator's centred panel. */
	private static void show(MpFactionScreens f, GuiSection content, CharSequence title, GButt.ButtPanel back, GButt.ButtPanel next) {
		GuiSection all = new GuiSection();
		all.add(f.locking(content));
		all.addRelBody(32, DIR.E, f.side(back, next));
		all.pad(8, 8);
		f.showCentred(all, title);
	}

	/** One title, as vanilla's title screen shows it: greyed while locked, selected while held; at most 5 held. */
	private static final class TitleButton extends GButt.ButtPanel {

		private final PTitle title;

		TitleButton(PTitle title) {
			super(title.name);
			icon(title.icon.scaled(2));
			body.setDim(600, title.icon.height() * 2 + 8);
			this.title = title;
		}

		@Override
		protected void renAction() {
			activeSet(title.unlocked());
			selectedSet(title.selected());
		}

		@Override
		public void hoverInfoGet(GUI_BOX text) {
			GBox b = (GBox) text;
			if (!title.unlocked()) {
				b.error(Dic.¤¤Unavailable);
				return;
			}
			b.title(title.name);
			b.text(title.desc);
			b.sep();
			b.textLL(PTitles.¤¤racesUnlocked);
			b.NL();
			for (Race r : RACES.playable())
				if (title.race(r))
					b.add(r.appearance().iconBig);
			b.NL();
			GText t = b.text();
			t.add(PTitles.¤¤racesUnlockedD);
			t.insert(0, 50.0 / RACES.playable().size(), 1);
			b.text(t);
			b.NL(4);
			b.textLL(PTitles.¤¤currentBoost);
			b.NL();
			title.boosters.hover(text, title.boosterValue(), null, -1);
			b.NL();
		}

		@Override
		protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
			super.render(r, ds, isActive, isSelected, isHovered);
			if (!title.unlocked())
				return;
			int x1 = body.x1() + 120;
			int y1 = body.y2() - 25;
			for (Race ra : RACES.playable())
				if (title.race(ra)) {
					ra.appearance().icon.render(r, x1, y1);
					x1 += 26;
				}
		}

		@Override
		protected void clickA() {
			if (title.selected())
				title.select(false);
			else if (title.unlocked() && FACTIONS.player().titles.selected() < TitlesMode.MAX_TITLES)
				title.select(true);
		}
	}

	private static CLICKABLE raceButton(Race r) {
		GButt.ButtPanel b = new GButt.ButtPanel(r.appearance().iconBig.huge) {
			@Override
			protected void clickA() {
				FACTIONS.player().setRace(r);
			}

			@Override
			protected void renAction() {
				selectedSet(FACTIONS.player().race() == r);
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				text.title(r.info.name);
			}
		};
		b.pad(10, 10);
		return b;
	}
}
