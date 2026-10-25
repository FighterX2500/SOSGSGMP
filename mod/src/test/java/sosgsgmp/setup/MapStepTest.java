package sosgsgmp.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sosgsgmp.setup.MapStep.PlayerState;

class MapStepTest {

    private static final MapOptions RANDOM_256 = new MapOptions(256, MapOptions.RANDOM, 50, 7);

    /** A host with joiners 1 and 2, built and through its first generation (hash 100); no results yet. */
    private static MapStep generatedHost() {
        MapStep h = MapStep.host(0, List.of(1, 2));
        int build = h.build(256);
        h.built(build);
        h.onBuildReady(1, build);
        h.onBuildReady(2, build);
        int gen = h.generate(RANDOM_256);
        h.generated(gen, 100, MapStatus.OK);
        return h;
    }

    @Test
    void confirmOnlyWhenEveryHashForTheLatestGenerationMatches() {
        MapStep h = generatedHost();
        assertFalse(h.canConfirm());
        assertTrue(h.onResult(1, 1, 100, MapStatus.OK));
        assertFalse(h.canConfirm());
        assertTrue(h.onResult(2, 1, 100, MapStatus.OK));
        assertTrue(h.canConfirm());
        assertEquals(PlayerState.MATCH, h.state(2));
        assertEquals(PlayerState.GENERATED, h.state(0));

        int gen = h.generate(new MapOptions(256, MapOptions.RANDOM, 20, 8));
        assertFalse(h.canConfirm());
        h.generated(gen, 200, MapStatus.OK);
        assertFalse(h.canConfirm());
        assertEquals(PlayerState.GENERATING, h.state(1));
        h.onResult(1, gen, 200, MapStatus.OK);
        h.onResult(2, gen, 200, MapStatus.OK);
        assertTrue(h.canConfirm());

        h.confirm();
        assertTrue(h.confirmed());
        assertEquals(200, h.confirmedHash());
        assertFalse(h.canConfirm());
    }

    @Test
    void aMismatchOrAnErrorBlocksConfirm() {
        MapStep h = generatedHost();
        h.onResult(1, 1, 100, MapStatus.OK);
        h.onResult(2, 1, 101, MapStatus.OK);
        assertFalse(h.canConfirm());
        assertEquals(PlayerState.MISMATCH, h.state(2));
        assertThrows(IllegalStateException.class, h::confirm);

        int gen = h.generate(RANDOM_256);
        h.generated(gen, 100, MapStatus.OK);
        h.onResult(1, gen, 100, MapStatus.OK);
        h.onResult(2, gen, 0, MapStatus.UNKNOWN_MAP_TYPE);
        assertFalse(h.canConfirm());
        assertEquals(PlayerState.ERROR, h.state(2));
    }

    @Test
    void aStaleReplyChangesNothing() {
        MapStep h = generatedHost();
        int gen = h.generate(RANDOM_256);
        h.generated(gen, 300, MapStatus.OK);
        assertFalse(h.onResult(1, gen - 1, 300, MapStatus.OK));
        assertEquals(PlayerState.GENERATING, h.state(1));

        int build = h.build(512);
        assertFalse(h.onBuildReady(1, build - 1));
        assertEquals(PlayerState.BUILDING, h.state(1));
        assertFalse(h.onResult(1, gen, 300, MapStatus.OK), "a result for the old build's generation");
        assertFalse(h.onResult(1, gen + 1, 300, MapStatus.OK), "a result for a generation not sent yet");
    }

    @Test
    void aRepeatedMessageChangesNothing() {
        MapStep h = generatedHost();
        assertFalse(h.onBuildReady(1, 1));
        assertTrue(h.onResult(1, 1, 100, MapStatus.OK));
        assertFalse(h.onResult(1, 1, 999, MapStatus.OK));
        assertEquals(PlayerState.MATCH, h.state(1));

        MapStep j = MapStep.joiner(1);
        assertTrue(j.onBuild(1, 256));
        assertFalse(j.onBuild(1, 512));
        assertEquals(256, j.size());
        j.built(1);
        assertTrue(j.onGenerate(1, 1, "", 50, 7));
        assertFalse(j.onGenerate(1, 1, "", 90, 8));
        assertEquals(RANDOM_256, j.pendingGeneration());
        j.generated(1, 100, MapStatus.OK);
        assertTrue(j.onConfirm(1, 100));
        assertFalse(j.onConfirm(1, 100));
    }

    @Test
    void aPlayerLeavingRemovesTheirRowAndCanUnblockConfirm() {
        MapStep h = generatedHost();
        h.onResult(1, 1, 100, MapStatus.OK);
        h.onResult(2, 1, 555, MapStatus.OK);
        assertFalse(h.canConfirm());
        assertTrue(h.remove(2));
        assertFalse(h.remove(2));
        assertNull(h.state(2));
        assertTrue(h.canConfirm());
        assertFalse(h.onResult(2, 1, 100, MapStatus.OK));
    }

    @Test
    void nothingIsAcceptedAfterTheConfirm() {
        MapStep h = generatedHost();
        h.onResult(1, 1, 100, MapStatus.OK);
        h.onResult(2, 1, 100, MapStatus.OK);
        h.confirm();
        assertFalse(h.canGenerate());
        assertThrows(IllegalStateException.class, () -> h.generate(RANDOM_256));
        assertThrows(IllegalStateException.class, () -> h.build(256));
        assertFalse(h.onBuildReady(1, 2));

        MapStep j = MapStep.joiner(1);
        j.onBuild(1, 256);
        j.built(1);
        j.onGenerate(1, 1, "", 50, 7);
        j.generated(1, 100, MapStatus.OK);
        j.onConfirm(1, 100);
        assertFalse(j.onBuild(2, 512));
        assertFalse(j.onGenerate(2, 1, "", 50, 7));
        assertEquals(0, j.pendingBuild());
        assertNull(j.pendingGeneration());
        assertEquals(1, j.genId());
    }

    @Test
    void aNewBuildResetsEveryResult() {
        MapStep h = generatedHost();
        h.onResult(1, 1, 100, MapStatus.OK);
        h.onResult(2, 1, 100, MapStatus.OK);
        assertTrue(h.canConfirm());

        int build = h.build(512);
        assertEquals(2, build);
        assertEquals(build, h.pendingBuild());
        assertFalse(h.canConfirm());
        assertFalse(h.canGenerate());
        assertNull(h.options());
        assertNull(h.status());
        assertEquals(PlayerState.BUILDING, h.state(0));
        assertEquals(PlayerState.BUILDING, h.state(1));

        h.built(build);
        assertEquals(0, h.pendingBuild());
        assertTrue(h.canGenerate());
        assertEquals(PlayerState.READY, h.state(0));
        h.onBuildReady(1, build);
        assertEquals(PlayerState.READY, h.state(1));
        assertThrows(IllegalArgumentException.class, () -> h.generate(RANDOM_256), "options for the old size");
    }

    @Test
    void presetsOnlyAtVanillasSize() {
        MapStep h = MapStep.host(0, List.of(1));
        h.built(h.build(512));
        assertThrows(IllegalArgumentException.class, () -> h.generate(new MapOptions(512, "Continent", 50, 1)));
        h.generate(new MapOptions(512, MapOptions.RANDOM, 50, 1));
    }

    @Test
    void theHostGeneratesOnlyOnceItsGameIsBuilt() {
        MapStep h = MapStep.host(0, List.of(1));
        assertFalse(h.canGenerate());
        int build = h.build(256);
        assertFalse(h.canGenerate());
        h.built(build);
        assertTrue(h.canGenerate(), "joiners still building get the generation after their build");
        assertThrows(IllegalStateException.class, () -> h.built(build));
    }

    @Test
    void aJoiningPcFollowsTheHost() {
        MapStep j = MapStep.joiner(1);
        assertEquals(0, j.pendingBuild());
        assertTrue(j.onBuild(1, 384));
        assertEquals(1, j.pendingBuild());
        assertEquals(PlayerState.BUILDING, j.state(1));
        assertNull(j.state(0), "a joining PC knows only its own progress");
        j.built(1);
        assertEquals(PlayerState.READY, j.state(1));

        assertTrue(j.onGenerate(1, 1, "", 30, 9));
        assertEquals(new MapOptions(384, "", 30, 9), j.pendingGeneration());
        assertEquals(PlayerState.GENERATING, j.state(1));
        j.generated(1, 42, MapStatus.OK);
        assertNull(j.pendingGeneration());
        assertEquals(MapStatus.OK, j.status());
        assertEquals(42, j.hash());
        assertEquals(PlayerState.GENERATED, j.state(1));

        assertFalse(j.onConfirm(0, 42), "stale");
        assertTrue(j.onConfirm(1, 42));
        assertTrue(j.confirmed());
    }

    @Test
    void aGenerationForAnotherBuildIsAWrongBuild() {
        MapStep j = MapStep.joiner(1);
        j.onBuild(2, 256);
        j.built(2);
        assertTrue(j.onGenerate(1, 1, "", 50, 7));
        assertNull(j.pendingGeneration());
        assertEquals(MapStatus.WRONG_BUILD, j.status());
        assertEquals(PlayerState.ERROR, j.state(1));
        assertThrows(IllegalStateException.class, () -> j.generated(1, 5, MapStatus.OK));
    }

    @Test
    void eachSideCallsOnlyItsOwnMethods() {
        MapStep h = MapStep.host(0, List.of(1));
        MapStep j = MapStep.joiner(1);
        assertThrows(IllegalStateException.class, () -> h.onBuild(1, 256));
        assertThrows(IllegalStateException.class, () -> j.build(256));
        assertThrows(IllegalStateException.class, () -> j.onResult(0, 1, 1, MapStatus.OK));
        assertThrows(IllegalArgumentException.class, () -> MapStep.host(0, List.of(0)));
    }
}
