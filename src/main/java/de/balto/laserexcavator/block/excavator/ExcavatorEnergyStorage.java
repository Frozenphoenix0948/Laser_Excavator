package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.config.LaserExcavatorConfig;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.function.LongSupplier;

/**
 * Internal FE storage for the excavator. External systems may insert power but
 * cannot extract it, and the configured receive rate is enforced per game tick.
 */
public final class ExcavatorEnergyStorage implements IEnergyStorage {
    private final LongSupplier gameTimeSupplier;
    private final Runnable changeListener;

    private int energy;
    private long receiveBudgetTick = Long.MIN_VALUE;
    private int receivedThisTick;

    public ExcavatorEnergyStorage(LongSupplier gameTimeSupplier, Runnable changeListener) {
        this.gameTimeSupplier = gameTimeSupplier;
        this.changeListener = changeListener;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        if (maxReceive <= 0) return 0;

        long gameTime = gameTimeSupplier.getAsLong();
        if (gameTime != receiveBudgetTick) {
            receiveBudgetTick = gameTime;
            receivedThisTick = 0;
        }

        int rateRemaining = Math.max(0, LaserExcavatorConfig.ENERGY_MAX_RECEIVE.get() - receivedThisTick);
        int capacityRemaining = Math.max(0, getMaxEnergyStored() - energy);
        int accepted = Math.min(maxReceive, Math.min(rateRemaining, capacityRemaining));

        if (!simulate && accepted > 0) {
            energy += accepted;
            receivedThisTick += accepted;
            changeListener.run();
        }
        return accepted;
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        return 0;
    }

    @Override
    public int getEnergyStored() {
        int capacity = getMaxEnergyStored();
        if (energy > capacity) energy = capacity;
        return energy;
    }

    @Override
    public int getMaxEnergyStored() {
        return Math.max(1, LaserExcavatorConfig.ENERGY_CAPACITY.get());
    }

    @Override
    public boolean canExtract() {
        return false;
    }

    @Override
    public boolean canReceive() {
        return true;
    }

    /** Adds internally generated FE without applying the external cable receive-rate limit. */
    public int addInternal(int amount) {
        if (amount <= 0) return 0;
        int accepted = Math.min(amount, Math.max(0, getMaxEnergyStored() - energy));
        if (accepted > 0) {
            energy += accepted;
            changeListener.run();
        }
        return accepted;
    }

    /** Consumes power internally without exposing extraction through the capability. */
    public boolean consumeInternal(int amount) {
        if (amount <= 0) return true;
        if (getEnergyStored() < amount) return false;
        energy -= amount;
        changeListener.run();
        return true;
    }

    /** Restores persisted energy without applying the external receive-rate limit. */
    public void loadStoredEnergy(int stored) {
        energy = Mth.clamp(stored, 0, getMaxEnergyStored());
    }
}
