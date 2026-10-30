package sosgsgmp.session;

import java.util.ArrayDeque;

import sosgsgmp.net.Message;

/**
 * Who paused the game (spec §4, SOS-110). A paused PC runs no steps, so a pause can't travel in the lockstep: like a chat
 * line it goes through the host, whose order every PC hears. It changes nothing in the simulation: a PC that stops
 * running steps is what stops the game. Plain Java; the sessions feed it, the game side takes each pause and acts on it.
 */
public final class Pauses {

    public static final int NOBODY = -1;

    private final ArrayDeque<Message.Pause> heard = new ArrayDeque<>();
    private int pausedBy = NOBODY;

    void heard(Message.Pause pause) {
        heard.add(pause);
        pausedBy = pause.paused() ? pause.playerId() : NOBODY;
    }

    /** The next pause or unpause in the host's order, or null; each is handed out once. */
    public Message.Pause take() {
        return heard.poll();
    }

    /** The player who paused the game, or {@link #NOBODY} while it runs. */
    public int pausedBy() {
        return pausedBy;
    }
}
