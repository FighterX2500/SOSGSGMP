package sosgsgmp.session;

import sosgsgmp.setup.MapOptions;
import sosgsgmp.setup.MapStatus;
import sosgsgmp.setup.MapStep;
import sosgsgmp.setup.MapWorld;

/** Runs the latest generation on this PC: the one way host and joining PCs generate (setup-map-step.md §3, step 3). */
final class LocalMap {

    private LocalMap() {}

    /** Generates {@code options}, generation {@link MapStep#genId()}, and records this PC's result in the step. */
    static void generate(MapStep step, MapWorld world, MapOptions options) {
        if (!world.hasMapType(options.mapType())) {
            step.generated(step.genId(), 0, MapStatus.UNKNOWN_MAP_TYPE);
            return;
        }
        step.generated(step.genId(), world.generate(options), MapStatus.OK);
    }
}
