package sosgsgmp.session;

import sosdet.game.deal.TariffBook;

/**
 * SOSGSGMP: the Set Tariff slider's steps (spec §7; SOS-77): step 0 is 0%, each step {@link TariffBook#STEP}% more, up
 * to {@link TariffBook#MAX}%. The engine's book holds the rule; this only maps the slider onto it.
 */
public final class TariffSteps {

    /** The slider's last step: {@link TariffBook#MAX}%. */
    public static final int LAST = TariffBook.MAX / TariffBook.STEP;

    private TariffSteps() {
    }

    /** The tariff at slider step {@code step}, in percent. */
    public static int percent(int step) {
        if (step < 0 || step > LAST)
            throw new IllegalArgumentException("SOSGSGMP: tariff step " + step + ", not 0 to " + LAST);
        return step * TariffBook.STEP;
    }

    /** The slider step for a tariff of {@code percent}: the nearest step, within the range. */
    public static int step(int percent) {
        int s = Math.round(percent / (float) TariffBook.STEP);
        return Math.max(0, Math.min(LAST, s));
    }
}
