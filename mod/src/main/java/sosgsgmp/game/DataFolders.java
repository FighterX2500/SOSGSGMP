package sosgsgmp.game;

import init.paths.PATHS;
import sosgsgmp.campaign.DataLayout;

/** SOSGSGMP: the mod's data folders on this PC (SOS-155), from the game's own data, saves and profile folders. */
public final class DataFolders {

    private DataFolders() {
    }

    public static DataLayout layout() {
        return new DataLayout(PATHS.local().ROOT.get(), PATHS.local().save().get(), PATHS.local().PROFILE.get());
    }
}
