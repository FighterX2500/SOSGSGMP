package sosgsgmp.game;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;

import settlement.trade.PBuyer;
import settlement.trade.PSeller;

/**
 * SOSGSGMP: vanilla's depot messages that {@code PBuyer} and {@code PSeller} keep private (SOS-210), so a message that
 * counts the players says it in vanilla's words and language. Each is read once, the first time a depot asks; the
 * game's texts are set when its classes load and don't change after.
 */
public final class DepotTexts {

    private DepotTexts() {
    }

    /** "You don't have enough credits to purchase this resource." */
    public static CharSequence credits() {
        return Texts.CREDITS;
    }

    /** "The current cheapest buy price exceeds your set price cap. ..." */
    public static CharSequence importPriceCap() {
        return Texts.IMPORT_PRICE_CAP;
    }

    /** "A purchase of a batch of 32 of this resource is not possible due to your treasury limit." */
    public static CharSequence treasury() {
        return Texts.TREASURY;
    }

    /** "The current price is below your price cap. ..." */
    public static CharSequence exportPriceCap() {
        return Texts.EXPORT_PRICE_CAP;
    }

    /** "There is no one willing to buy this goods. ..." */
    public static CharSequence noBuyer() {
        return Texts.NO_BUYER;
    }

    /** "Best price." in the goods' hover. */
    public static CharSequence bestPrice() {
        return Texts.BEST_PRICE;
    }

    /** Read on first use, once: a holder class, so nothing reads before the game's own classes have loaded. */
    private static final class Texts {
        static final CharSequence CREDITS = read(PBuyer.class, "¤¤probCredits");
        static final CharSequence IMPORT_PRICE_CAP = read(PBuyer.class, "¤¤probPriceCap");
        static final CharSequence TREASURY = read(PBuyer.class, "¤¤probTreasury");
        static final CharSequence EXPORT_PRICE_CAP = read(PSeller.class, "¤¤priceCapProblem");
        static final CharSequence NO_BUYER = read(PSeller.class, "¤¤NoPrice");
        static final CharSequence BEST_PRICE = read(PBuyer.class, "¤¤BestPrice");

        private static CharSequence read(Class<?> c, String name) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return (CharSequence) f.get(null);
            } catch (NoSuchFieldException | IllegalAccessException | SecurityException | InaccessibleObjectException
                    | ClassCastException e) {
                throw new IllegalStateException("SOSGSGMP: can't read " + c.getName() + "." + name + " (Songs of Syx V71)", e);
            }
        }
    }
}
