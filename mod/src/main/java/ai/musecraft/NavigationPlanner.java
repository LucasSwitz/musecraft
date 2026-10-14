package ai.musecraft;

import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

/** Incremental A* with a virtual terminal shared by all acceptable endpoints. */
final class NavigationPlanner {
    private record Node(Vec3d pos,double g,double f,long order) {}
    private final PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingDouble(Node::f).thenComparingDouble(n->-n.g).thenComparingLong(Node::order));
    private final Map<Vec3d,Double> costs=new HashMap<>();
    private final Map<Vec3d,NavigationStep> parent=new HashMap<>();
    private final NavigationMap map;
    private final NavigationGoal goal;
    private final NavigationRecovery recovery;
    private final Vec3d origin;
    private final boolean flying,returnable;
    private final long started=System.nanoTime(),revision,initialHits,initialMisses;
    private Node expanding;
    private List<Vec3d> candidates;
    private int direction,index,visits,slices,passVisits;
    private long order,cpuNanos,maxSliceNanos;
    private boolean fine,done,initialized,boundary;
    private String reason;
    private Vec3d best,terminal,terminalParent,frontier;
    private NavigationStep terminalEdge;
    private double bestCost=Double.POSITIVE_INFINITY,frontierCost=Double.POSITIVE_INFINITY;
    private List<NavigationStep> result=List.of();
    private static final int[][] DIRECTIONS={{0,0},{1,0},{0,1},{-1,0},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
    NavigationPlanner(NavigationMap map,Vec3d from,NavigationGoal goal,boolean fly,NavigationRecovery recovery,boolean returnable) {
        this.returnable=returnable;this.map=map;origin=from;this.goal=goal;flying=fly;this.recovery=recovery;revision=map.revision;
        initialHits=map.hits;initialMisses=map.misses;
    }
    private void reset() {
        open.clear();costs.clear();parent.clear();expanding=null;candidates=null;
        passVisits=0;frontier=null;frontierCost=Double.POSITIVE_INFINITY;terminal=null;terminalParent=null;best=null;terminalEdge=null;bestCost=Double.POSITIVE_INFINITY;
        costs.put(origin,0.0);open.add(new Node(origin,0,goal.heuristic(origin),order++));
    }
    boolean stale(){return revision!=map.revision;}
    boolean done(){return done;}
    String reason(){return reason;}
    Vec3d resolved(){return terminal;}
    List<NavigationStep> result(){return result;}
    void step(MinecraftClient c) {
        long start=System.nanoTime(),deadline=start+2_000_000L;
        try {do {advance(c);} while(!done&&System.nanoTime()<deadline);}
        finally {long elapsed=System.nanoTime()-start;cpuNanos+=elapsed;maxSliceNanos=Math.max(maxSliceNanos,elapsed);slices++;}
    }
    private void advance(MinecraftClient c) {
        if(done)return;
        if(!goal.ready()){goal.resolveStep(c);return;}
        if(goal.failure()!=null){finish(goal.failure(),false);return;}
        if(!initialized){reset();initialized=true;return;}
        if(cpuNanos>=500_000_000L||visits>=12000){finish("search_incomplete",false);return;}
        if(expanding==null) {
            if(terminal!=null&&(open.isEmpty()||open.peek().f>=bestCost)){finish("route_found",true);return;}
            if(open.isEmpty()||passVisits>=6000||!fine&&cpuNanos>=200_000_000L&&frontier==null) {
                if(terminal!=null){finish("route_found",true);return;}
                if(!fine){fine=true;reset();return;}
                finish(boundary||passVisits>=6000?"search_incomplete":"no_route_in_region",false);return;
            }
            Node n=open.poll();if(n.g>costs.getOrDefault(n.pos,Double.MAX_VALUE))return;
            expanding=n;direction=0;candidates=null;visits++;passVisits++;
            if(goal.accepts(c,n.pos))offer(n,n.pos,null);
            // The exact anchor is also a terminal candidate, even off the graph lattice.
            Vec3d anchor=goal.anchor();
            if(n.pos.distanceTo(anchor)<=1.5&&!n.pos.equals(anchor)&&goal.accepts(c,anchor)) {
                var edge=map.step(c,n.pos,anchor);
                if(edge!=null&&!recovery.rejects(edge)&&(!returnable||map.edge(c,anchor,n.pos)))offer(n,anchor,edge);
            }
            return;
        }
        if(candidates!=null&&index<candidates.size()) {
            Vec3d p=candidates.get(index++);
            if(Math.abs(p.y-origin.y)>12){boundary=true;return;}
            if(p.equals(expanding.pos))return;
            var edge=map.step(c,expanding.pos,p);if(edge==null||recovery.rejects(edge)||returnable&&!map.edge(c,p,expanding.pos))return;
            double g=expanding.g+edge.cost();
            if(g>=costs.getOrDefault(p,Double.MAX_VALUE))return;
            costs.put(p,g);parent.put(p,edge);open.add(new Node(p,g,g+goal.heuristic(p),order++));return;
        }
        if(direction<DIRECTIONS.length) {
            int[] d=DIRECTIONS[direction++];double step=fine?.5:1;
            double gx=fine?Math.round(expanding.pos.x*2)/2.:Math.floor(expanding.pos.x)+.5;
            double gz=fine?Math.round(expanding.pos.z*2)/2.:Math.floor(expanding.pos.z)+.5;
            double x=gx+d[0]*step,z=gz+d[1]*step;
            if(Math.abs(x-origin.x)>24||Math.abs(z-origin.z)>24){boundary=true;candidates=null;return;}
            candidates=flying?List.of(new Vec3d(x,expanding.pos.y,z)):map.column(c,x,z,(int)Math.floor(expanding.pos.y));index=0;return;
        }
        if(direction++==DIRECTIONS.length) {
            candidates=new ArrayList<>();index=0;
            if(flying||NavigationTerrain.climbing(c,expanding.pos)){candidates.add(expanding.pos.add(0,.5,0));candidates.add(expanding.pos.add(0,-.5,0));}
            else if(NavigationTerrain.swimming(c,expanding.pos))candidates.add(expanding.pos.add(0,.5,0));
            return;
        }
        // Retain verified local frontiers; the action remembers prior sections.
        // Baritone's best-so-far search scores remaining distance plus a fraction
        // of path cost. Near goals need useful partial paths too (reference in docs).
        double improvement=goal.heuristic(origin)-goal.heuristic(expanding.pos);
        if(origin.distanceTo(expanding.pos)>=2&&improvement>.5&&!recovery.visited(expanding.pos)) {
            double cost=expanding.g*.25+goal.heuristic(expanding.pos);
            if(cost<frontierCost){frontierCost=cost;frontier=expanding.pos;}
            if(improvement>=12||cpuNanos>=100_000_000L&&improvement>=2) {
                best=frontier;finish("partial_route",false);return;
            }
        }
        expanding=null;
    }
    private void offer(Node n,Vec3d endpoint,NavigationStep edge) {
        double cost=n.g+(edge==null?0:edge.cost())+goal.penalty(endpoint);
        if(cost<bestCost){bestCost=cost;best=n.pos;terminalParent=n.pos;terminal=endpoint;terminalEdge=edge;}
    }
    private void finish(String why,boolean reached) {
        // A search budget limits optimality, not the validity of an already
        // discovered complete route. Preserve its path before partial fallback.
        if(terminal!=null){reached=true;best=terminalParent;why="route_found";}
        if(!reached&&why.equals("search_incomplete")&&frontier!=null) {
            best=frontier;why="partial_route";
        }
        reason=why;done=true;
        if(!reached&&!why.equals("partial_route"))return;
        LinkedList<NavigationStep> path=new LinkedList<>();
        for(Vec3d p=best;!p.equals(origin);) {var edge=parent.get(p);if(edge==null){reason="search_incomplete";return;}path.addFirst(edge);p=edge.from();}
        if(reached&&terminalEdge!=null)path.add(terminalEdge);
        if(!reached)terminal=null;
        result=path;
    }
    Map<String,Object> metrics() {
        var out=new LinkedHashMap<String,Object>();
        out.put("phase",done?reason:!goal.ready()?"resolving":"planning");out.put("expanded",visits);out.put("fine",fine);out.put("slices",slices);
        out.put("cpuMs",cpuNanos/1e6);out.put("maxSliceMs",maxSliceNanos/1e6);out.put("elapsedMs",(System.nanoTime()-started)/1e6);
        out.put("cacheHits",map.hits-initialHits);out.put("cacheMisses",map.misses-initialMisses);return out;
    }
}
