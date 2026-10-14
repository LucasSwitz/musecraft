package ai.musecraft;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Inclusive whole-block cube, shared by resource and visibility queries. */
record BlockVolume(BlockPos min, BlockPos max) {
    static BlockVolume around(Vec3d center,int radius) {
        var block=BlockPos.ofFloored(center);
        return new BlockVolume(block.add(-radius,-radius,-radius),block.add(radius,radius,radius));
    }
    boolean contains(BlockPos pos) {
        return pos.getX()>=min.getX()&&pos.getX()<=max.getX()
            &&pos.getY()>=min.getY()&&pos.getY()<=max.getY()
            &&pos.getZ()>=min.getZ()&&pos.getZ()<=max.getZ();
    }
    boolean contains(Vec3d point) {return contains(BlockPos.ofFloored(point));}
    Box box() {return new Box(min.getX(),min.getY(),min.getZ(),max.getX()+1,max.getY()+1,max.getZ()+1);}
    Iterable<BlockPos> cells() {return BlockPos.iterate(min,max);}
}
