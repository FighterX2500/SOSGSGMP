package sosgsgmp.game;

import java.util.function.Supplier;

import com.sun.jna.Callback;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

import sosgsgmp.net.Protocol;
import sosgsgmp.net.SteamPort;

/**
 * SOSGSGMP: Steam's newer peer-to-peer messages ({@code ISteamNetworkingMessages}, SOS-105) for {@link SteamworksPort},
 * in place of the deprecated {@code ISteamNetworking}. steamworks4j doesn't wrap them, but the {@code steam_api64.dll}
 * it loads from the game jar (Steamworks SDK 1.53) exports their flat API, and the game jar carries JNA, so this calls
 * the DLL steamworks4j already loaded, found by name. Each function is looked up once, here; a missing one fails with
 * its name. Only the first three fields of {@code SteamNetworkingMessage_t} are read (data, size, the sender's identity
 * at 16), stable since the API began; identities go through the DLL's own helpers.
 *
 * <p>The node's I/O thread only, as the port: the session callbacks run inside {@code SteamAPI_RunCallbacks} there.
 */
final class SteamMessages {

    /** {@code k_nSteamNetworkingSend_Reliable | k_nSteamNetworkingSend_AutoRestartBrokenSession}. */
    private static final int SEND_FLAGS = 8 | 32;
    private static final int CHANNEL = 0;
    /** {@code sizeof(SteamNetworkingIdentity)}. */
    private static final int IDENTITY_BYTES = 136;
    /** {@code SteamNetworkingMessage_t}: {@code m_pData}, {@code m_cbSize}, {@code m_identityPeer}. */
    private static final int MESSAGE_DATA = 0, MESSAGE_SIZE = 8, MESSAGE_PEER = 16;
    /** {@code k_EResultOK}. */
    private static final int RESULT_OK = 1;
    private static final String DLL = "steam_api64.dll";

    /** The session callbacks' shape: one pointer to a struct that begins with the remote identity. */
    private interface SessionCallback extends Callback {
        void invoke(Pointer event);
    }

    private final Pointer messages;
    private final Function send, receive, accept, closeSession, setSteamId, getSteamId, release;
    private final Memory identity = new Memory(IDENTITY_BYTES);
    private final Memory out = new Memory(Protocol.MAX_MESSAGE + 4);
    private final Memory received = new Memory(Native.POINTER_SIZE);
    // held here: the DLL keeps only the function pointers, so the callbacks must not be collected
    private final SessionCallback requested;
    private final SessionCallback failed;

    /** Binds the functions and installs the session callbacks, which tell {@code listener}. Steam must be initialized. */
    SteamMessages(Supplier<SteamPort.Listener> listener) {
        Pointer module = NativeLibrary.getInstance("kernel32").getFunction("GetModuleHandleA").invokePointer(new Object[] {DLL});
        if (module == null)
            throw new IllegalStateException("SOSGSGMP: " + DLL + " isn't loaded: Steam's messages need steamworks4j started first");
        messages = function(module, "SteamAPI_SteamNetworkingMessages_SteamAPI_v002").invokePointer(new Object[0]);
        Pointer utils = function(module, "SteamAPI_SteamNetworkingUtils_SteamAPI_v004").invokePointer(new Object[0]);
        if (messages == null || utils == null)
            throw new IllegalStateException("SOSGSGMP: Steam's networking interfaces aren't available");
        send = function(module, "SteamAPI_ISteamNetworkingMessages_SendMessageToUser");
        receive = function(module, "SteamAPI_ISteamNetworkingMessages_ReceiveMessagesOnChannel");
        accept = function(module, "SteamAPI_ISteamNetworkingMessages_AcceptSessionWithUser");
        closeSession = function(module, "SteamAPI_ISteamNetworkingMessages_CloseSessionWithUser");
        setSteamId = function(module, "SteamAPI_SteamNetworkingIdentity_SetSteamID64");
        getSteamId = function(module, "SteamAPI_SteamNetworkingIdentity_GetSteamID64");
        release = function(module, "SteamAPI_SteamNetworkingMessage_t_Release");
        requested = event -> listener.get().sessionRequested(steamId(event));
        failed = event -> listener.get().sessionFailed(steamId(event), "Steam's session failed"); // SteamNetConnectionInfo_t begins with the identity
        function(module, "SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_MessagesSessionRequest").invokeInt(new Object[] {utils, requested});
        function(module, "SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_MessagesSessionFailed").invokeInt(new Object[] {utils, failed});
        function(module, "SteamAPI_ISteamNetworkingUtils_InitRelayNetworkAccess").invokeVoid(new Object[] {utils}); // the relay's routes, ahead of the first message
    }

    private static Function function(Pointer module, String name) {
        Pointer p = NativeLibrary.getInstance("kernel32").getFunction("GetProcAddress").invokePointer(new Object[] {module, name});
        if (p == null)
            throw new IllegalStateException("SOSGSGMP: " + DLL + " has no " + name + " (the game's Steamworks SDK 1.53 does)");
        return Function.getFunction(p);
    }

    private Pointer identity(long user) {
        identity.clear(IDENTITY_BYTES);
        setSteamId.invokeVoid(new Object[] {identity, user});
        return identity;
    }

    private long steamId(Pointer identity) {
        return getSteamId.invokeLong(new Object[] {identity});
    }

    void accept(long user) {
        accept.invokeInt(new Object[] {messages, identity(user)});
    }

    void close(long user) {
        closeSession.invokeInt(new Object[] {messages, identity(user)});
    }

    /** Sends one message reliably, in order; false if Steam refused it. */
    boolean send(long user, byte[] bytes) {
        out.write(0, bytes, 0, bytes.length);
        return send.invokeInt(new Object[] {messages, identity(user), out, bytes.length, SEND_FLAGS, CHANNEL}) == RESULT_OK;
    }

    /** The next message waiting, its sender in {@code from[0]}; null when none waits. */
    byte[] read(long[] from) {
        if (receive.invokeInt(new Object[] {messages, CHANNEL, received, 1}) < 1)
            return null;
        Pointer m = received.getPointer(0);
        try {
            byte[] bytes = m.getPointer(MESSAGE_DATA).getByteArray(0, m.getInt(MESSAGE_SIZE));
            from[0] = steamId(m.share(MESSAGE_PEER));
            return bytes;
        } finally {
            release.invokeVoid(new Object[] {m});
        }
    }
}
