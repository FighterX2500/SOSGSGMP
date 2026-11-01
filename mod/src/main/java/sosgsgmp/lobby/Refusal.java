package sosgsgmp.lobby;

/** Why a host turned a joining player away. Sent as its ordinal, so only append. */
public enum Refusal {
    WRONG_VERSION("The host runs another version of the mod."),
    FULL("The lobby is full."),
    STARTED("The game has already started."),
    NOT_IN_CAMPAIGN("You aren't a player of the campaign this lobby resumes."),
    ALREADY_JOINED("A PC with your player key is already in the lobby. If one PC's profile was copied from the other, delete"
            + " saves\\profile\\SOSGSGMP-lobby.properties on one of them: it gets a new key."),
    REMOVED("The host removed you from this campaign, so you can't play in it again."),
    TOO_SLOW("Your PC couldn't keep up with the game, so the host dropped it. You can come back to watch at the next resume."),
    WRONG_GAME("Your Songs of Syx isn't the host's build. Everyone needs the same build of the game."),
    WRONG_ENGINE("Your SOSDET isn't the host's build. Everyone needs the same one: let Steam finish updating, then restart the game."),
    WRONG_MOD("Your SOS GSG MP isn't the host's build. Everyone needs the same one: let Steam finish updating, then restart the game.");

    public final String text;

    Refusal(String text) {
        this.text = text;
    }
}
