package io.github.hronosin.miracle.toolchain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block entity that clients can see into. Vanilla's block entities keep what they save to
 * themselves (the server's); a Hallowed one sends it to the players nearby when their chunk
 * loads, and again whenever you call {@link #sync()}.
 *
 * <pre>{@code
 * class AltarEntity extends Hallowed {
 *     int prayers;
 *     AltarEntity(BlockPos pos, BlockState state) { super(ALTAR_ENTITY.get(), pos, state); }
 *
 *     protected void saveAdditional(ValueOutput out) { super.saveAdditional(out); out.putInt("prayers", prayers); }
 *     protected void loadAdditional(ValueInput in) { super.loadAdditional(in); prayers = in.getIntOr("prayers", 0); }
 *
 *     void pray() { prayers++; sync(); }   // saved with the world, and shown to everyone nearby
 * }
 * }</pre>
 *
 * <p>Everything {@code saveAdditional} writes is sent, so keep secrets elsewhere. Call
 * {@code setChanged()} instead of {@code sync()} for changes only the server needs to remember.
 */
public class Hallowed extends BlockEntity {

    public Hallowed(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Marks it to be saved and sends its data to the players who can see it. Server side; harmless on a client. */
    public void sync() {
        sync(this);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    /** {@link #sync()} for any block entity whose class sends an update packet (Hallowed and Reliquary do). */
    static void sync(BlockEntity be) {
        be.setChanged();
        Level level = be.getLevel();
        if (level != null && !level.isClientSide()) {
            BlockState state = be.getBlockState();
            level.sendBlockUpdated(be.getBlockPos(), state, state, 3);
        }
    }
}
