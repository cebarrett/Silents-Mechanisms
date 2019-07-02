package net.silentchaos512.mechanisms.block.generator;

import com.google.common.collect.ImmutableList;
import lombok.Getter;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.container.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SUpdateTileEntityPacket;
import net.minecraft.tileentity.AbstractFurnaceTileEntity;
import net.minecraft.tileentity.ITickableTileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.IIntArray;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.ForgeEventFactory;
import net.silentchaos512.lib.tile.LockableSidedInventoryTileEntity;
import net.silentchaos512.lib.tile.SyncVariable;
import net.silentchaos512.mechanisms.capability.EnergyStorageImpl;
import net.silentchaos512.mechanisms.init.ModTileEntities;
import net.silentchaos512.mechanisms.util.EnergyUtils;
import net.silentchaos512.mechanisms.util.TextUtil;

import javax.annotation.Nullable;
import java.util.List;

public class CoalGeneratorTileEntity extends LockableSidedInventoryTileEntity implements ITickableTileEntity {
    // Energy constants
    public static final int MAX_ENERGY = 100_000;
    public static final int MAX_SEND_RECEIVE = 1_000;
    public static final int ENERGY_CREATED_PER_TICK = 25;

    @Getter
    @SyncVariable(name = "BurnTime")
    private int burnTime;
    @Getter
    @SyncVariable(name = "TotalBurnTime")
    private int totalBurnTime;

    private final LazyOptional<IEnergyStorage> energy = LazyOptional.of(() ->
            new EnergyStorageImpl(MAX_ENERGY, MAX_SEND_RECEIVE, MAX_SEND_RECEIVE, 0));

    final IIntArray fields = new IIntArray() {
        @Override
        public int get(int index) {
            switch (index) {
                case 0:
                    return burnTime;
                case 1:
                    return totalBurnTime;
                case 2:
                    return getEnergyStored();
                default:
                    return 0;
            }
        }

        @Override
        public void set(int index, int value) {
            switch (index) {
                case 0:
                    burnTime = value;
                    break;
                case 1:
                    totalBurnTime = value;
                    break;
                case 2:
                    setEnergyStored(value);
                    break;
            }
        }

        @Override
        public int size() {
            return 3;
        }
    };

    public CoalGeneratorTileEntity() {
        super(ModTileEntities.coalGenerator, 1);
    }

    @Override
    public void tick() {
        if (world == null || world.isRemote) return;

        if (isBurning()) {
            // Currently burning fuel
            --burnTime;
            energy.ifPresent(e -> e.receiveEnergy(ENERGY_CREATED_PER_TICK, false));

            sendUpdate();
        } else {
            ItemStack fuel = getStackInSlot(0);
            if (getEnergyStored() < getMaxEnergyStored() && isFuel(fuel)) {
                // Not burning, and not at max energy
                burnTime = getBurnTime(fuel);
                if (isBurning()) {
                    totalBurnTime = burnTime;

                    // Consume fuel
                    if (fuel.hasContainerItem()) {
                        setInventorySlotContents(0, fuel.getContainerItem());
                    } else if (!fuel.isEmpty()) {
                        fuel.shrink(1);
                        if (fuel.isEmpty()) {
                            setInventorySlotContents(0, fuel.getContainerItem());
                        }
                    }
                }

                sendUpdate();
            }
        }

        energy.ifPresent(e -> EnergyUtils.trySendToNeighbors(world, pos, e, MAX_SEND_RECEIVE));
    }

    private void sendUpdate() {
        if (world != null) {
            BlockState oldState = world.getBlockState(pos);
            BlockState newState = oldState.with(AbstractFurnaceBlock.LIT, isBurning());
            world.notifyBlockUpdate(pos, oldState, newState, 3);
        }
    }

    public boolean isBurning() {
        return burnTime > 0;
    }

    private static boolean isFuel(ItemStack stack) {
        return AbstractFurnaceTileEntity.isFuel(stack);
    }

    private static int getBurnTime(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        int ret = stack.getBurnTime();
        return ForgeEventFactory.getItemBurnTime(stack, ret == -1 ? AbstractFurnaceTileEntity.getBurnTimes().getOrDefault(stack.getItem(), 0) : ret);
    }

    public int getEnergyStored() {
        return energy.isPresent() ? energy.orElseThrow(IllegalStateException::new).getEnergyStored() : 0;
    }

    private void setEnergyStored(int value) {
        energy.ifPresent(e -> {
            if (e instanceof EnergyStorageImpl) {
                ((EnergyStorageImpl) e).setEnergyDirectly(value);
            }
        });
    }

    public int getMaxEnergyStored() {
        return energy.isPresent() ? energy.orElseThrow(IllegalStateException::new).getMaxEnergyStored() : 0;
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return new int[]{0};
    }

    @Override
    public boolean canInsertItem(int index, ItemStack itemStackIn, @Nullable Direction direction) {
        return isFuel(itemStackIn);
    }

    @Override
    public boolean canExtractItem(int index, ItemStack stack, Direction direction) {
        return stack.getItem() == Items.BUCKET;
    }

    @Override
    protected ITextComponent getDefaultName() {
        return TextUtil.translate("container", "coal_generator");
    }

    @Override
    protected Container createMenu(int id, PlayerInventory playerInventory) {
        return new CoalGeneratorContainer(id, playerInventory, this);
    }

    @Override
    public void read(CompoundNBT tags) {
        super.read(tags);
        SyncVariable.Helper.readSyncVars(this, tags);
        setEnergyStored(tags.getInt("Energy"));
    }

    @Override
    public CompoundNBT write(CompoundNBT tags) {
        super.write(tags);
        SyncVariable.Helper.writeSyncVars(this, tags, SyncVariable.Type.WRITE);
        energy.ifPresent(e -> tags.putInt("Energy", e.getEnergyStored()));
        return tags;
    }

    @Override
    public void onDataPacket(NetworkManager net, SUpdateTileEntityPacket packet) {
        super.onDataPacket(net, packet);
        SyncVariable.Helper.readSyncVars(this, packet.getNbtCompound());
        setEnergyStored(packet.getNbtCompound().getInt("Energy"));
    }

    @Override
    public CompoundNBT getUpdateTag() {
        CompoundNBT tags = super.getUpdateTag();
        SyncVariable.Helper.writeSyncVars(this, tags, SyncVariable.Type.PACKET);
        energy.ifPresent(e -> tags.putInt("Energy", e.getEnergyStored()));
        return tags;
    }

    public List<String> getDebugText() {
        return ImmutableList.of(
                "burnTime = " + burnTime,
                "totalBurnTime = " + totalBurnTime,
                "energy = " + getEnergyStored() + " FE / " + getMaxEnergyStored() + " FE",
                "ENERGY_CREATED_PER_TICK = " + ENERGY_CREATED_PER_TICK,
                "MAX_SEND_RECEIVE = " + MAX_SEND_RECEIVE
        );
    }
}
