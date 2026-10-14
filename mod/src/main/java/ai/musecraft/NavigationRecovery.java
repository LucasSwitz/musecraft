package ai.musecraft;

import java.util.*;
import net.minecraft.util.math.Vec3d;

/** Per-action recovery survives planner restarts; planning is not physical progress. */
final class NavigationRecovery {
    private record Failed(NavigationStep step,long until) {}
    private final List<Failed> failed=new ArrayList<>();
    private final List<Vec3d> sections=new ArrayList<>();
    private long progressAt=System.currentTimeMillis();
    private long retryAt;
    private double closest=Double.POSITIVE_INFINITY;
    private Vec3d waypoint;
    private int attempts;
    void following(Vec3d here,Vec3d next) {
        if(!next.equals(waypoint)){waypoint=next;closest=here.distanceTo(next);return;}
        double gap=here.distanceTo(next);
        if(closest-gap>.15){closest=gap;progressAt=System.currentTimeMillis();attempts=0;}
    }
    void advanced(){progressAt=System.currentTimeMillis();closest=Double.POSITIVE_INFINITY;waypoint=null;attempts=0;}
    long stalledMs(){return System.currentTimeMillis()-progressAt;}
    boolean retryDue(){return System.currentTimeMillis()-Math.max(progressAt,retryAt)>2500;}
    boolean failed(NavigationStep step) {
        retryAt=System.currentTimeMillis();
        if(step!=null)failed.add(new Failed(step,System.currentTimeMillis()+15000));
        return ++attempts<=4;
    }
    void changed(NavigationMap map){failed.removeIf(f->map.affects(f.step));}
    boolean rejects(NavigationStep step) {
        failed.removeIf(f->f.until<System.currentTimeMillis());
        return failed.stream().anyMatch(f->f.step.to().distanceTo(step.to())<.4&&f.step.from().distanceTo(step.from())<.8);
    }
    void section(Vec3d p){sections.add(p);if(sections.size()>128)sections.removeFirst();advanced();}
    boolean visited(Vec3d p){return sections.stream().anyMatch(v->v.distanceTo(p)<4);}
    Map<String,Object> metrics(){return Map.of("attempts",attempts,"failedEdges",failed.size(),"sections",sections.size(),"stalledMs",stalledMs());}
}
