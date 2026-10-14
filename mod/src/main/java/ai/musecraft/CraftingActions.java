package ai.musecraft;

import com.google.gson.*;
import java.util.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.*;
import net.minecraft.registry.Registries;
import net.minecraft.screen.*;
import net.minecraft.util.Identifier;
import net.minecraft.network.packet.c2s.play.CraftRequestC2SPacket;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;

final class CraftingActions {
    private static final Gson JSON=new Gson();
    static boolean eligible(ItemStack stack) {
        return !stack.isEmpty()&&!stack.isDamaged()&&!stack.hasEnchantments()
            &&!stack.contains(net.minecraft.component.DataComponentTypes.CUSTOM_NAME);
    }
    static JsonElement inputs(MinecraftClient c) {
        var counts=new TreeMap<String,Integer>();
        for(int i=0;i<36;i++) {
            var stack=c.player.getInventory().getStack(i);
            // Same admission as RecipeMatcher.addUnenchantedInput: a crafting
            // plan must not budget a named, enchanted or damaged possession.
            if(!eligible(stack))continue;
            counts.merge(Registries.ITEM.getId(stack.getItem()).toString(),stack.getCount(),Integer::sum);
        }
        return JSON.toJsonTree(counts);
    }
    // Forecast ordinary recipe-book choices so preparing a generic ingredient
    // cannot consume a species/material needed by a later exact recipe.
    static JsonElement selection(MinecraftClient c, JsonObject p) {
        int count=p.get("count").getAsInt();
        if(count<1||count>256)throw new IllegalArgumentException("invalid_craft_count");
        var entry=c.player.networkHandler.getRecipeManager().get(Identifier.of(p.get("recipe").getAsString()))
            .orElseThrow(()->new IllegalArgumentException("unknown_recipe"));
        if(!(entry.value() instanceof CraftingRecipe recipe)||recipe.isIgnoredInRecipeBook())
            throw new IllegalArgumentException("not_a_crafting_recipe");
        var stock=new TreeMap<String,Integer>();
        inputs(c).getAsJsonObject().entrySet().forEach(e->stock.put(e.getKey(),e.getValue().getAsInt()));
        if(p.has("reserveTable")&&p.get("reserveTable").getAsBoolean()) {
            int tables=stock.getOrDefault("minecraft:crafting_table",0);
            if(tables==0)return JSON.toJsonTree(Map.of("available",false));
            stock.put("minecraft:crafting_table",tables-1);
        }
        var output=recipe.getResult(c.world.getRegistryManager());
        int batches=(count+output.getCount()-1)/output.getCount();
        var consumed=new TreeMap<String,Integer>();
        for(int batch=0;batch<batches;batch++) {
            var matcher=new RecipeMatcher();
            stock.forEach((item,n)->{if(n>0)matcher.addInput(new ItemStack(Registries.ITEM.get(Identifier.of(item)),n),n);});
            var selected=new it.unimi.dsi.fastutil.ints.IntArrayList();
            if(!matcher.match(recipe,selected))return JSON.toJsonTree(Map.of("available",false));
            var remainders=new TreeMap<String,Integer>();
            for(int id:selected) {
                var stack=RecipeMatcher.getStackFromId(id);
                if(stack.isEmpty())continue;
                String item=Registries.ITEM.getId(stack.getItem()).toString();
                stock.merge(item,-1,Integer::sum);consumed.merge(item,1,Integer::sum);
                if(stack.getItem().hasRecipeRemainder())
                    remainders.merge(Registries.ITEM.getId(stack.getItem().getRecipeRemainder()).toString(),1,Integer::sum);
            }
            remainders.forEach((item,n)->stock.merge(item,n,Integer::sum));
            stock.merge(Registries.ITEM.getId(output.getItem()).toString(),output.getCount(),Integer::sum);
        }
        return JSON.toJsonTree(Map.of("available",true,"unlocked",c.player.getRecipeBook().contains(entry),
            "consumed",consumed,"after",stock,"crafted",batches*output.getCount()));
    }
    // One bounded read replaces per-ingredient round trips. This stays inside
    // the controller; the player sees only the final action result.
    static JsonElement graph(MinecraftClient c, JsonObject p) {
        var byOutput=new TreeMap<String,List<RecipeEntry<?>>>();
        var all=new ArrayList<>(c.player.networkHandler.getRecipeManager().values());
        if(all.size()>16384)throw new IllegalStateException("crafting_recipe_graph_limit");
        all.sort(Comparator.comparing(e->e.id().toString()));
        for(var entry:all) {
            if(!(entry.value() instanceof CraftingRecipe recipe)||recipe.isIgnoredInRecipeBook())continue;
            String item=Registries.ITEM.getId(recipe.getResult(c.world.getRegistryManager()).getItem()).toString();
            byOutput.computeIfAbsent(item,k->new ArrayList<>()).add(entry);
        }
        record Pending(String item,int depth) {}
        var queue=new ArrayDeque<Pending>();
        queue.add(new Pending(p.get("item").getAsString(),0));
        queue.add(new Pending("minecraft:crafting_table",0));
        var scheduled=new HashSet<String>();
        scheduled.add(p.get("item").getAsString());scheduled.add("minecraft:crafting_table");
        var result=new LinkedHashMap<String,Object>();
        int entries=0;
        while(!queue.isEmpty()) {
            var pending=queue.removeFirst();
            if(result.containsKey(pending.item()))continue;
            if(result.size()>=512)throw new IllegalStateException("crafting_recipe_graph_limit");
            var recipes=new ArrayList<Map<String,Object>>();
            for(var entry:byOutput.getOrDefault(pending.item(),List.of())) {
                if(recipes.size()==32)break;
                if(++entries>2048)throw new IllegalStateException("crafting_recipe_graph_limit");
                var recipe=entry.value();
                var output=recipe.getResult(c.world.getRegistryManager());
                var ingredients=new ArrayList<List<String>>();
                for(var ingredient:recipe.getIngredients())if(!ingredient.isEmpty()) {
                    var names=Arrays.stream(ingredient.getMatchingStacks()).map(stack->Registries.ITEM.getId(stack.getItem()).toString()).limit(64).toList();
                    ingredients.add(names);
                    if(pending.depth()<8)for(String name:names)if(scheduled.add(name)) {
                        if(scheduled.size()>512)throw new IllegalStateException("crafting_recipe_graph_limit");
                        queue.add(new Pending(name,pending.depth()+1));
                    }
                }
                recipes.add(Map.of("recipe",entry.id().toString(),"type","minecraft:crafting",
                    "output",Map.of("name",pending.item(),"count",output.getCount()),
                    "fits2x2",recipe.fits(2,2),"ingredients",ingredients));
            }
            result.put(pending.item(),recipes);
        }
        return JSON.toJsonTree(result);
    }
    static JsonElement recipes(MinecraftClient c, JsonObject p) {
        String item=p.get("item").getAsString();
        var matcher=new RecipeMatcher();
        for (int i=0;i<36;i++) matcher.addUnenchantedInput(c.player.getInventory().getStack(i));
        var result=new ArrayList<Map<String,Object>>();
        var all=new ArrayList<>(c.player.networkHandler.getRecipeManager().values());
        all.sort(Comparator.comparing(e->e.id().toString()));
        for (var entry:all) {
            var recipe=entry.value();
            var output=recipe.getResult(c.world.getRegistryManager());
            if (!Registries.ITEM.getId(output.getItem()).toString().equals(item) || recipe.isIgnoredInRecipeBook()) continue;
            if (!(recipe instanceof CraftingRecipe) && !(recipe instanceof AbstractCookingRecipe)) continue;
            var data=new LinkedHashMap<String,Object>();
            data.put("recipe",entry.id().toString());data.put("output",ContainerState.stack(output));
            data.put("type",Registries.RECIPE_TYPE.getId(recipe.getType()).toString());
            data.put("unlocked",c.player.getRecipeBook().contains(entry));
            data.put("fits2x2",recipe.fits(2,2));data.put("availableCrafts",matcher.countCrafts(entry,null));
            var ingredients=new ArrayList<List<String>>();
            for (var ingredient:recipe.getIngredients()) if (!ingredient.isEmpty()) ingredients.add(Arrays.stream(ingredient.getMatchingStacks())
                .map(s->Registries.ITEM.getId(s.getItem()).toString()).limit(64).toList());
            data.put("ingredients",ingredients);
            if (recipe instanceof AbstractCookingRecipe cooking) data.put("cookTicks",cooking.getCookingTime());
            result.add(data); if (result.size()==32) break;
        }
        return JSON.toJsonTree(result);
    }
    static JsonElement craft(MinecraftClient c, JsonObject p) {
        var h=ContainerState.admit(c,p);
        if (!(h instanceof CraftingScreenHandler) && !(h instanceof PlayerScreenHandler)) throw new IllegalStateException("crafting_grid_required");
        var grid=(AbstractRecipeScreenHandler<?,?>)h;
        for (var s:h.slots) if (s.inventory!=c.player.getInventory() && !s.getStack().isEmpty()) throw new IllegalStateException("crafting_grid_not_empty");
        var entry=c.player.networkHandler.getRecipeManager().get(Identifier.of(p.get("recipe").getAsString())).orElseThrow(()->new IllegalArgumentException("unknown_recipe"));
        if (!(entry.value() instanceof CraftingRecipe recipe)) throw new IllegalArgumentException("not_a_crafting_recipe");
        if (!c.player.getRecipeBook().contains(entry)) throw new IllegalStateException("recipe_not_unlocked");
        if (!recipe.fits(grid.getCraftingWidth(),grid.getCraftingHeight())) throw new IllegalStateException("crafting_table_required");
        var matcher=new RecipeMatcher();
        for (int i=0;i<36;i++) matcher.addUnenchantedInput(c.player.getInventory().getStack(i));
        if (!matcher.match(recipe,null)) throw new IllegalStateException("missing_ingredients");
        var output=recipe.getResult(c.world.getRegistryManager());
        var result=InventoryActions.begin(c,h,Map.of(grid.getCraftingResultSlotIndex(),output));
        c.player.networkHandler.sendPacket(new CraftRequestC2SPacket(h.syncId,entry,false));
        return result;
    }
    static JsonElement cooking(MinecraftClient c, JsonObject p) {
        var h=c.player.currentScreenHandler;
        RecipeType<?> type=h instanceof SmokerScreenHandler?RecipeType.SMOKING:h instanceof BlastFurnaceScreenHandler?RecipeType.BLASTING:RecipeType.SMELTING;
        if (!(h instanceof AbstractFurnaceScreenHandler)) throw new IllegalStateException("furnace_required");
        var item=new ItemStack(Registries.ITEM.get(Identifier.of(p.get("item").getAsString())));
        var fuel=Registries.ITEM.get(Identifier.of(p.get("fuel").getAsString()));
        for (var entry:c.player.networkHandler.getRecipeManager().values()) {
            if (entry.value() instanceof AbstractCookingRecipe recipe && recipe.getType()==type && recipe.getIngredients().getFirst().test(item)) {
                int burn=AbstractFurnaceBlockEntity.createFuelTimeMap().getOrDefault(fuel,0);
                if (h instanceof SmokerScreenHandler || h instanceof BlastFurnaceScreenHandler) burn/=2;
                return JSON.toJsonTree(Map.of("recipe",entry.id().toString(),"output",ContainerState.stack(recipe.getResult(c.world.getRegistryManager())),
                    "cookTicks",recipe.getCookingTime(),"fuelTicks",burn));
            }
        }
        throw new IllegalStateException("item_not_cookable_in_this_furnace");
    }
}
