package com.autosort;

import net.minecraft.block.BarrelBlock;
import net.minecraft.block.Block;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.state.property.Properties;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import net.minecraft.world.World;

public class ChestDetector {

    /**
     * Returns the canonical position of the chest the player is currently looking at / has open.
     * For double chests, returns the position with the smaller coordinates (canonical half).
     */
    public static BlockPos getOpenChestPos(MinecraftClient client) {
        if (client.player == null || client.world == null) return null;

        HitResult hit = client.player.raycast(5.0, 0, false);
        if (hit instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            BlockState state = client.world.getBlockState(pos);
            Block block = state.getBlock();

            if (block instanceof ChestBlock) {
                return getCanonicalChestPos(pos, state, client.world);
            }
            if (block instanceof BarrelBlock || block instanceof ShulkerBoxBlock) {
                return pos;
            }
        }
        return null;
    }

    /**
     * For double chests, returns the "smaller" position so both halves map to the same entry.
     */
    private static BlockPos getCanonicalChestPos(BlockPos pos, BlockState state, World world) {
        if (!state.contains(Properties.CHEST_TYPE)) return pos;

        ChestType type = state.get(Properties.CHEST_TYPE);
        if (type == ChestType.SINGLE) return pos;

        BlockPos otherPos = pos.offset(ChestBlock.getFacing(state));
        // Return the position with the smaller coordinates as canonical
        if (comparePosOrdered(otherPos, pos) < 0) {
            return otherPos;
        }
        return pos;
    }

    private static int comparePosOrdered(BlockPos a, BlockPos b) {
        int cmp = Integer.compare(a.getX(), b.getX());
        if (cmp != 0) return cmp;
        cmp = Integer.compare(a.getY(), b.getY());
        if (cmp != 0) return cmp;
        return Integer.compare(a.getZ(), b.getZ());
    }

    public static boolean isChestBlock(Block block) {
        return block instanceof ChestBlock
                || block instanceof BarrelBlock
                || block instanceof ShulkerBoxBlock;
    }
}
