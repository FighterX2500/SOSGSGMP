package sosgsgmp.campaign;

/**
 * The host's order to save the campaign (spec §11, SOS-64): every PC, the host too, holds at {@code step}, saves its
 * own game as save {@code number} and keeps that many autosaves of the campaign ({@code keep}; 0 keeps them all).
 */
public record SaveOrder(int number, long step, CampaignRecord.Kind kind, int keep, Campaign campaign) {

    public SaveOrder {
        if (number < 1 || step < 0 || keep < 0)
            throw new IllegalArgumentException("SOSGSGMP: save order " + number + " at step " + step + ", keeping " + keep);
    }
}
