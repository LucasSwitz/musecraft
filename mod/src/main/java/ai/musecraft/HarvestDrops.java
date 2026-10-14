package ai.musecraft;

import java.util.*;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;

/** Vanilla source hints, never a promise about server loot tables or yield. */
final class HarvestDrops {
    private static final Map<String,String> DROPS=new HashMap<>();
    static {
        for(String ore:List.of("coal","diamond","emerald","redstone","lapis")) {
            String item=ore.equals("lapis")?"lapis_lazuli":ore;
            DROPS.put(ore+"_ore",item);DROPS.put("deepslate_"+ore+"_ore",item);
        }
        for(String ore:List.of("iron","copper","gold")) {
            DROPS.put(ore+"_ore","raw_"+ore);DROPS.put("deepslate_"+ore+"_ore","raw_"+ore);
        }
        DROPS.putAll(Map.of("stone","cobblestone","deepslate","cobbled_deepslate",
            "nether_quartz_ore","quartz","nether_gold_ore","gold_nugget","clay","clay_ball",
            "glowstone","glowstone_dust","melon","melon_slice"));
        for(String soil:List.of("grass_block","podzol","mycelium","farmland","dirt_path"))DROPS.put(soil,"dirt");
    }
    static boolean silk(ItemStack stack) {
        return stack.getEnchantments().getEnchantmentEntries().stream()
            .anyMatch(e->e.getKey().matchesKey(Enchantments.SILK_TOUCH)&&e.getIntValue()>0);
    }
    private static boolean silkOnly(BlockState state) {
        return state.isIn(BlockTags.LEAVES)||state.isOf(Blocks.GLASS)
            ||state.getBlock() instanceof StainedGlassBlock||state.getBlock() instanceof PaneBlock
            ||state.isOf(Blocks.ICE)||state.isOf(Blocks.PACKED_ICE)||state.isOf(Blocks.BLUE_ICE);
    }
    static boolean yields(BlockState state,String item,boolean silk) {
        String name=Registries.BLOCK.getId(state.getBlock()).toString();
        if(!name.startsWith("minecraft:"))return false;
        // Stateful crops and multi-block plants use the farming action. Do not
        // infer a harvest from asItem() for blocks whose normal loot is empty.
        if(state.getBlock() instanceof CropBlock||state.isOf(Blocks.BEDROCK)
            ||state.isOf(Blocks.SPAWNER)||state.isOf(Blocks.BUDDING_AMETHYST)
            ||state.isOf(Blocks.NETHER_PORTAL)||state.isOf(Blocks.END_PORTAL)
            ||state.isOf(Blocks.FIRE)||state.isOf(Blocks.SNOW))return false;
        String drop=DROPS.get(name.substring(10));
        if(drop!=null&&!silk)return item.equals("minecraft:"+drop);
        if(silkOnly(state)&&!silk)return false;
        if(state.isOf(Blocks.GRAVEL)&&!silk&&item.equals("minecraft:flint"))return true;
        Item own=state.getBlock().asItem();
        return own!=Items.AIR&&Registries.ITEM.getId(own).toString().equals(item);
    }
    static boolean potential(BlockState state,String item) {return yields(state,item,false)||yields(state,item,true);}
    static int tool(MinecraftClient c,BlockState block,String item) {
        int best=-1;double score=-1;
        for(int i=0;i<36;i++) {
            var stack=c.player.getInventory().getStack(i);
            if(block.isToolRequired()&&!stack.isSuitableFor(block))continue;
            if(!yields(block,item,silk(stack)))continue;
            double speed=stack.getMiningSpeedMultiplier(block);
            if(speed>score){best=i;score=speed;}
        }
        return best;
    }
}
