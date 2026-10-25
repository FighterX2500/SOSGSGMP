package view.world.generator;

import game.faction.FACTIONS;
import init.race.RACES;
import init.race.Race;
import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.datatypes.DIR;
import snake2d.util.gui.GUI_BOX;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import util.data.GETTER;
import util.gui.misc.GButt;
import util.gui.misc.GStat;
import util.gui.misc.GText;
import util.gui.table.GTextScroller;
import view.ui.profile.UIProfile;

/**
 * SOSGSGMP: the factions step's race and Customize Faction screens (setup-factions-step.md §2), with every player's
 * row beside them. They are built as vanilla's StagePickRace and StageVisuals build theirs, from the same parts, but
 * without their Confirm: it runs WorldViewGenerator.set(), which goes on to vanilla's one-player capital stage.
 */
final class MpFactionStage {

	private static final CharSequence RACE_TITLE = "Select species";
	private static final CharSequence CUSTOMIZE_TITLE = "Customize Faction";

	private MpFactionStage() {
	}

	/** The race screen: vanilla's race buttons, description, challenge, pros and cons. */
	static void race(MpFactions f) {
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
				customize(f);
			}
		});
	}

	/** The Customize Faction screen: vanilla's faction name, ruler name and banner, with Save and Load Default. */
	static void customize(MpFactions f) {
		GuiSection s = new GuiSection();
		s.add(UIProfile.section(false));
		show(f, s, CUSTOMIZE_TITLE, new GButt.ButtPanel("back") {
			@Override
			protected void clickA() {
				race(f);
			}
		}, new GButt.ButtPanel("location") {
			@Override
			protected void clickA() {
				MpLocationStage.place(f);
			}
		});
	}

	/** Shows {@code content} with the players' side to its right, in the generator's centred panel. */
	private static void show(MpFactions f, GuiSection content, CharSequence title, GButt.ButtPanel back, GButt.ButtPanel next) {
		f.g.reset();
		content.addRelBody(32, DIR.E, f.side(back, next));
		content.pad(8, 8);
		f.g.dummy.add(content, title);
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
