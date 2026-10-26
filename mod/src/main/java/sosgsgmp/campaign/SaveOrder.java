package sosgsgmp.campaign;

/**
 * The host's order to save the campaign (spec §11, SOS-64): every PC, the host too, holds at {@code step}, saves its
 * own game as save {@code number} and keeps that many autosaves of the campaign ({@code keep}; 0 keeps them all). With
 * {@code exit}, the session ends on every PC once the save is done: a desync's Save and exit (spec §11, SOS-68), or
 * player {@code quitter}'s Quit to menu or Exit (SOS-79); {@code quitter} is {@link #NO_QUITTER} otherwise.
 */
public record SaveOrder(int number, long step, CampaignRecord.Kind kind, int keep, Campaign campaign, boolean exit, int quitter) {

    /** No player quit: a save that plays on, or a desync's Save and exit. */
    public static final int NO_QUITTER = -1;

    public SaveOrder {
        if (number < 1 || step < 0 || keep < 0)
            throw new IllegalArgumentException("SOSGSGMP: save order " + number + " at step " + step + ", keeping " + keep);
        if (quitter < NO_QUITTER || quitter != NO_QUITTER && !exit)
            throw new IllegalArgumentException("SOSGSGMP: save order " + number + " with quitter " + quitter + (exit ? "" : ", which plays on"));
    }

    /** An order no player quit with. */
    public SaveOrder(int number, long step, CampaignRecord.Kind kind, int keep, Campaign campaign, boolean exit) {
        this(number, step, kind, keep, campaign, exit, NO_QUITTER);
    }
}
