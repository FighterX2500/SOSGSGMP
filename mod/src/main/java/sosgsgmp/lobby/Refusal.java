package sosgsgmp.lobby;

/** Why a host turned a joining player away. Sent as its ordinal, so only append. */
public enum Refusal {
    WRONG_VERSION("The host runs another version of the mod."),
    FULL("The lobby is full."),
    STARTED("The game has already started.");

    public final String text;

    Refusal(String text) {
        this.text = text;
    }
}
