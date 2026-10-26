package menu;

import java.util.LinkedList;

import game.faction.FACTIONS;
import game.faction.Faction;
import game.faction.diplomacy.DIP;
import init.constant.C;
import init.sprite.UI.Icon;
import init.sprite.UI.UI;
import init.trade.TR;
import init.trade.TRADABLE;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.DIR;
import snake2d.util.gui.GUI_BOX;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import sosgsgmp.game.PlayerDeals;
import util.colors.GCOLOR;
import util.data.GETTER.GETTER_IMP;
import util.gui.common.UIPickerArmy;
import util.gui.common.UIPickerRegion;
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import util.gui.misc.GStat;
import util.gui.misc.GText;
import util.gui.table.GScrollRows;
import util.info.GFORMAT;
import util.text.Dic;
import view.interrupter.ISidePanel;
import view.main.VIEW;
import world.WORLD;
import world.army.AD;
import world.entity.army.WArmy;
import world.map.regions.Region;
import world.region.RD;

/**
 * SOSGSGMP: another player's faction window, beside vanilla's faction list as an NPC's is (spec §6; SOS-76). Vanilla's
 * window works only on an NPC, so this one is built from the same parts for a player: the banner header, which says
 * "Player" where vanilla shows the stance, and the tabs Realm, Goods, Boosts and Diplomacy. There is no Court: a player
 * has no court. A new window is made at each open; vanilla's list clears the old one.
 */
final class MpPlayerWindow extends ISidePanel {

	/** Beside vanilla's faction list, whose rows are {@code C.SG*264} wide, in the side panels' width. */
	private static final int WIDTH = C.MIN_WIDTH - C.SG * 264 - 48;
	private static final int TAB_WIDTH = 140;
	private static final String PRICE_D = "What one item costs you: their lowest export price, as they last reported it, and the toll.";
	private static final String NO_BOOSTS = "Another player's boosts come from their city, which runs on their PC: not shown yet.";

	private final CLICKABLE.ClickSwitch sw;
	private final MpPlayerDiplomacy dip;

	MpPlayerWindow(Faction f) {
		GETTER_IMP<Faction> g = new GETTER_IMP<>(f);
		section = new GuiSection() {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				if (PlayerDeals.otherPlayer(f)) // a player removed since stays as an empty panel, as vanilla's does
					super.render(r, ds);
			}
		};
		section.body().setWidth(WIDTH).setHeight(1);
		section.addRelBody(0, DIR.S, header(f));

		int tabH = new GButt.ButtPanel(Dic.¤¤Realm).body().height();
		int hi = HEIGHT - section.body().height() - tabH - 8 - 16 - 24;
		CLICKABLE realm = realm(g, hi);
		sw = new CLICKABLE.ClickSwitch(realm);
		sw.setD(DIR.N);
		dip = new MpPlayerDiplomacy(f, WIDTH, hi);
		GuiSection tabs = new GuiSection();
		tabs.addRightC(0, tab(Dic.¤¤Realm, realm));
		tabs.addRightC(0, tab(Dic.¤¤goods, goods(f, hi)));
		tabs.addRightC(0, new GButt.ButtPanel(Dic.¤¤Boosts) {
			@Override
			protected void renAction() {
				activeSet(false);
			}

			@Override
			public void hoverInfoGet(GUI_BOX text) {
				((GBox) text).error(NO_BOOSTS);
			}
		}.setDim(TAB_WIDTH, tabH));
		tabs.addRightC(0, tab(Dic.¤¤Diplomacy, dip));
		section.addRelBody(8, DIR.S, tabs);
		section.addRelBody(0, DIR.S, new RENDEROBJ.RenderImp(WIDTH - 128, 16) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				GCOLOR.UI().border(r, body().x1(), body().x2(), body().y1() + 5, body().y1() + 8);
			}
		});
		section.addRelBody(0, DIR.S, sw);
		section.body().setWidth(WIDTH).setHeight(HEIGHT);
	}

	/** Shows the Diplomacy tab, where another player's proposal is answered. */
	void diplomacy() {
		sw.set(dip);
	}

	private CLICKABLE tab(CharSequence name, CLICKABLE content) {
		GButt.ButtPanel b = new GButt.ButtPanel(name) {
			@Override
			protected void clickA() {
				sw.set(content);
			}

			@Override
			protected void renAction() {
				selectedSet(sw.current() == content);
			}
		};
		b.body.setWidth(TAB_WIDTH);
		return b;
	}

	/** Vanilla's banner header: the name between the two banners, "Player" below it, then the player's figures. */
	private static GuiSection header(Faction f) {
		GuiSection s = new GuiSection();
		s.body().setWidth(WIDTH);
		s.addRelBody(6, DIR.S, new GStat(new GText(UI.FONT().H1, 32)) {
			@Override
			public void update(GText text) {
				text.add(f.name);
				text.lablify();
			}
		}.r(DIR.N));
		s.addRelBody(4, DIR.S, new GStat() {
			@Override
			public void update(GText text) {
				text.add(MpPlayers.PLAYER);
			}

			@Override
			public void hoverInfoGet(GBox b) {
				DIP.get(FACTIONS.player(), f).hover(b);
			}
		}.r(DIR.N));
		for (int side = 0; side < 2; side++) {
			RENDEROBJ banner = new RENDEROBJ.RenderImp(Icon.L * 2) {
				@Override
				public void render(SPRITE_RENDERER r, float ds) {
					f.banner().HUGE.render(r, body());
				}
			};
			if (side == 0)
				banner.body().moveX1(s.body().x1()).moveCY(s.body().cY());
			else
				banner.body().moveX2(s.body().x2()).moveCY(s.body().cY());
			s.add(banner);
		}
		GuiSection facts = new GuiSection();
		facts.addRightC(0, new GStat() {
			@Override
			public void update(GText text) {
				GFORMAT.i(text, RD.DIST().distance(f));
			}
		}.hh(UI.icons().s.wheel).hoverTitleSet(Dic.¤¤Distance));
		facts.addRightC(48, new GStat() {
			@Override
			public void update(GText text) {
				GFORMAT.i(text, (int) FACTIONS.WORTH().faction(f));
			}
		}.hh(UI.icons().s.money).hoverTitleSet(Dic.¤¤NetWorth));
		facts.addRightC(48, new GStat() {
			@Override
			public void update(GText text) {
				GFORMAT.i(text, RD.RACES().population.faction().get(f));
			}
		}.hh(UI.icons().s.human).hoverTitleSet(Dic.¤¤Population));
		s.addRelBody(8, DIR.S, facts);
		return s;
	}

	/** Vanilla's Realm tab: soldiers, conscripts and the armies on the left; area and the regions on the right. */
	private static CLICKABLE realm(GETTER_IMP<Faction> f, int height) {
		GuiSection all = new GuiSection();
		GuiSection s = new GuiSection();
		s.addRelBody(8, DIR.S, new GStat() {
			@Override
			public void update(GText text) {
				GFORMAT.i(text, AD.men(null).faction(f.get()));
			}
		}.hh(Dic.¤¤Soldiers));
		s.addRelBody(8, DIR.S, new GStat() {
			@Override
			public void update(GText text) {
				GFORMAT.i(text, AD.conscripts().total(null).get(f.get()));
			}
		}.hh(Dic.¤¤Conscripts));
		s.addRelBody(8, DIR.S, new UIPickerArmy(f, height - s.body().height() - 16) {
			@Override
			protected boolean canBePicked(WArmy a) {
				return !WORLD.FOW().is(a.ctx(), a.cty());
			}

			@Override
			protected void pick(WArmy a) {
				if (!WORLD.FOW().is(a.ctx(), a.cty())) {
					VIEW.world().activate();
					VIEW.UI().manager.close();
					VIEW.world().window.centererTile.set(a.ctx(), a.cty());
				}
			}

			@Override
			public void hover(GUI_BOX text, WArmy a) {
				if (!WORLD.FOW().is(a.ctx(), a.cty()))
					super.hover(text, a);
			}
		});
		all.add(s);
		all.addRelBody(16, DIR.E, new RENDEROBJ.RenderImp(1, height) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				GCOLOR.UI().border().render(r, body);
			}
		});
		s = new GuiSection();
		s.addRelBody(8, DIR.S, new GStat() {
			@Override
			public void update(GText text) {
				int am = 0;
				for (Region r : f.get().realm().all())
					am += r.info.area();
				GFORMAT.i(text, am);
			}
		}.hh(Dic.¤¤Area));
		s.addRelBody(8, DIR.S, new UIPickerRegion(f, height - 16) {
			@Override
			protected void toggle(Region reg) {
				VIEW.world().activate();
				VIEW.UI().manager.close();
				VIEW.world().window.centererTile.set(reg.cx(), reg.cy());
				VIEW.world().UI.regions.open(reg);
			}
		});
		all.addRelBody(16, DIR.E, s);
		return all;
	}

	/**
	 * The Goods tab: what the player last reported having of each good, and what one costs from them (SOS-77). Vanilla's
	 * Rate, Purchasing Power and traded bar describe an NPC's own market, so they're gone.
	 */
	private static CLICKABLE goods(Faction f, int height) {
		int slot = f.index();
		int rowW = WIDTH - 64;
		LinkedList<RENDEROBJ> rows = new LinkedList<>();
		rows.add(new RENDEROBJ.RenderImp(rowW, UI.FONT().H2.height() + 8) {
			@Override
			public void render(SPRITE_RENDERER r, float ds) {
				GCOLOR.T().H1.bind();
				UI.FONT().H2.render(r, Dic.¤¤Resource, body.x1() + 40, body.y1());
				UI.FONT().H2.render(r, Dic.¤¤Stored, body.x1() + rowW / 2, body.y1());
				UI.FONT().H2.render(r, Dic.¤¤Price, body.x1() + rowW * 3 / 4, body.y1());
				COLOR.unbind();
			}
		});
		for (TRADABLE t : TR.ALL()) {
			rows.add(new GuiSection() {
				private final GText amount = new GText(UI.FONT().M, 16);
				private final GText price = new GText(UI.FONT().M, 16);

				{
					body().setDim(rowW, Icon.M + 8);
				}

				@Override
				public void render(SPRITE_RENDERER r, float ds) {
					t.icon().render(r, body().x1() + 8, body().y1() + 4);
					COLOR.WHITE100.bind();
					UI.FONT().M.render(r, t.name, body().x1() + 40, body().y1() + 4);
					GFORMAT.i(amount.clear(), PlayerDeals.stock(slot, t.index()));
					amount.render(r, body().x1() + rowW / 2, body().y1() + 4);
					int sells = PlayerDeals.price(slot, t.index(), true);
					if (sells > 0) {
						GFORMAT.i(price.clear(), sells + toll(f));
						price.render(r, body().x1() + rowW * 3 / 4, body().y1() + 4);
					}
					COLOR.unbind();
				}

				@Override
				public void hoverInfoGet(GUI_BOX text) {
					GBox b = (GBox) text;
					b.title(t.name);
					b.textLL(Dic.¤¤buyPrice);
					b.tab(6);
					b.add(GFORMAT.i(b.text(), PlayerDeals.price(slot, t.index(), true) + toll(f)));
					b.NL();
					b.text(PRICE_D);
					b.NL(4);
					b.textLL(Dic.¤¤sellPrice);
					b.tab(6);
					b.add(GFORMAT.i(b.text(), PlayerDeals.price(slot, t.index(), false)));
					b.NL();
					b.textLL("Their tariff on you");
					b.tab(6);
					b.add(b.text().add(PlayerDeals.tariff(slot, FACTIONS.player().index())).add('%'));
					b.NL();
				}
			});
		}
		GuiSection s = new GuiSection();
		s.add(new GScrollRows(rows, height).view());
		return s;
	}

	private static int toll(Faction f) {
		return (int) Math.round(MpPlayers.toll(f));
	}
}
