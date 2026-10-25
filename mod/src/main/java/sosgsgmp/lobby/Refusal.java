package sosgsgmp.lobby;

/** Why a host turned a joining player away. Sent as its ordinal, so only append. */
public enum Refusal {
    WRONG_VERSION("The host runs another version of the mod."),
    FULL("The lobby is full."),
    STARTED("The game has already started."),
    NOT_IN_CAMPAIGN("You aren't a player of the campaign this lobby resumes."),
    ALREADY_JOINED("A PC with your player key is already in the lobby. If one PC's profile was copied from the other, delete"
            + " saves\\profile\\SOSGSGMP-lobby.properties on one of them: it gets a new key.");

    public final String text;

    Refusal(String text) {
        this.text = text;
    }
}
