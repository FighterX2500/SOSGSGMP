package sosgsgmp.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import sosdet.game.deal.DealBook;
import sosgsgmp.session.PlayerTerms.Kind;
import sosgsgmp.session.PlayerTerms.Term;

/** SOS-76: vanilla's terms between players, one chosen at a time, and the deal as the engine's terms. */
class PlayerTermsTest {

    @Test
    void everyTermIsListedAndTheUnbuiltOnesSayWhy() {
        assertEquals(9, Term.values().length, "vanilla's nine terms stay, greyed out or not");
        for (Term t : Term.values()) {
            boolean notYet = PlayerTerms.kind(t) == Kind.NOT_YET;
            assertEquals(notYet, PlayerTerms.notYet(t) != null, t + ": a reason exactly when greyed out");
            assertEquals(notYet || t == Term.WAR, PlayerTerms.treaty(t) == DealBook.NO_TREATY,
                    t + ": a treaty exactly when it works, but War, which is the engine's own command");
        }
        assertEquals(Kind.AT_ONCE, PlayerTerms.kind(Term.WAR), "SOS-83: battles between players are fought, so War is offered");
        assertEquals(Kind.AT_ONCE, PlayerTerms.kind(Term.CANCEL_ALL), "the owner: no accept or decline");
        assertEquals(Kind.PROPOSE, PlayerTerms.kind(Term.PACT));
    }

    @Test
    void oneTermAtATimeAndAGreyedOutOneCantBeChosen() {
        PlayerTerms p = new PlayerTerms(4);
        p.toggle(Term.TRADE);
        p.toggle(Term.ALLY);
        assertEquals(Term.ALLY, p.chosen());
        p.toggle(Term.ALLY);
        assertNull(p.chosen());
        assertThrows(IllegalArgumentException.class, () -> p.toggle(Term.UNITE));
        assertNull(p.chosen());
    }

    @Test
    void theDealBecomesTheEnginesTermsWithEachSidesGoodsAsPairs() {
        PlayerTerms p = new PlayerTerms(5);
        p.toggle(Term.PACT);
        p.credits(true, 100);
        p.credits(false, 7);
        p.goods(true, 1, 20);
        p.goods(true, 4, 3);
        p.goods(false, 2, 9);
        assertEquals(new DealBook.Terms(DealBook.PACT, 100, 7, new int[] {1, 20, 4, 3}, new int[] {2, 9}), p.terms());
        p.clear();
        assertTrue(p.terms().empty());
        assertFalse(p.hasGoodsOrMoney());
    }

    @Test
    void warAppliesAtOnceAndTakesNoMoneyOrGoods() {
        PlayerTerms p = new PlayerTerms(3);
        p.toggle(Term.WAR);
        assertTrue(p.atOnce());
        assertNull(p.refusal());
        p.credits(false, 10);
        assertTrue(p.refusal().startsWith("Declare War"), p.refusal());
    }

    @Test
    void cancelAllAppliesAtOnceAndTakesNoMoneyOrGoods() {
        PlayerTerms p = new PlayerTerms(3);
        p.toggle(Term.CANCEL_ALL);
        assertTrue(p.atOnce());
        assertNull(p.refusal());
        p.goods(false, 0, 5);
        assertNotNull(p.refusal());
        p.goods(false, 0, 0);
        p.credits(true, 1);
        assertNotNull(p.refusal());
        p.credits(true, 0);
        p.toggleRegion(true, 12);
        assertNotNull(p.refusal(), "nor regions");
    }

    @Test
    void regionsAndTheTributeReachTheEnginesTerms() {
        PlayerTerms p = new PlayerTerms(3);
        p.toggle(Term.VASSAL);
        assertTrue(p.vassalage());
        p.tribute(25);
        p.toggleRegion(true, 40);
        p.toggleRegion(true, 12);
        p.toggleRegion(false, 7);
        p.toggleRegion(true, 40); // taken out again
        assertEquals(new DealBook.Terms(DealBook.VASSAL, 0, 0, new int[0], new int[0], new int[] {12}, new int[] {7}, 25), p.terms());
        assertTrue(p.hasGoodsOrMoney(), "a region counts");
        assertThrows(IllegalArgumentException.class, () -> p.tribute(101));
        p.clear();
        assertTrue(p.terms().empty());
        assertEquals(DealBook.DEFAULT_TRIBUTE, p.tribute());
        p.toggle(Term.PROTECTOR);
        assertEquals(DealBook.PROTECTOR, p.terms().treaty, "the proposer becomes the overlord");
    }
}
