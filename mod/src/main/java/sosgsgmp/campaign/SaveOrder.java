package sosgsgmp.campaign;

/**
 * The host's order to save the campaign (spec §11, SOS-64): every PC, the host too, holds at {@code step}, saves its
 * own game as save {@code number} and keeps that many autosaves of the campaign ({@code keep}; 0 keeps them all). With
 * {@code exit}, the session ends on every PC once the save is done: a desync's Save and exit (spec §11, SOS-68), or
 * player {@code quitter}'s Quit to menu or Exit (SOS-79). Without {@code exit}, a {@code quitter} chose Leave and
 * freeze (spec §11 Freezing a player, SOS-194): once the save is done, that player's PC leaves, its city kept as it
 * saved it, the host freezes their human, and the others play on. {@code quitter} is {@link #NO_QUITTER} otherwise.
 */
public record SaveOrder(int number, long step, CampaignRecord.Kind kind, int keep, Campaign campaign, boolean exit, int quitter) {

    /** No player quit: a save that plays on, or a desync's Save and exit. */
    public static final int NO_QUITTER = -1;

    public SaveOrder {
        if (number < 1 || step < 0 || keep < 0)
            throw new IllegalArgumentException("SOSGSGMP: save order " + number + " at step " + step + ", keeping " + keep);
        if (quitter < NO_QUITTER)
            throw new IllegalArgumentException("SOSGSGMP: save order " + number + " with quitter " + quitter);
    }

    /** Whether player {@link #quitter} leaves frozen once this save is done, and the others play on. */
    public boolean freezes() {
        return quitter != NO_QUITTER && !exit;
    }

    /** An order no player quit with. */
    public SaveOrder(int number, long step, CampaignRecord.Kind kind, int keep, Campaign campaign, boolean exit) {
        this(number, step, kind, keep, campaign, exit, NO_QUITTER);
    }
}
