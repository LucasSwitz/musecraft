package ai.musecraft;

import baritone.api.Settings;
import java.util.*;

/** Restore native global settings when the owning gameplay action releases control. */
final class BaritoneSettingsScope implements AutoCloseable {
    private final List<Runnable> restore=new ArrayList<>();
    <T> void set(Settings.Setting<T> setting,T value){T before=setting.value;restore.add(()->setting.value=before);setting.value=value;}
    public void close(){for(int i=restore.size()-1;i>=0;i--)restore.get(i).run();restore.clear();}
}
