package de.balto.laserexcavator.block.excavator;

import de.balto.laserexcavator.debug.ExcavatorProfiler;
import de.balto.laserexcavator.config.LaserExcavatorConfig;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.function.LongSupplier;

/** Internal FE storage with per-tick external receive limit */
public final class ExcavatorEnergyStorage implements IEnergyStorage {
    private final LongSupplier gameTimeSupplier;
    private final Runnable changeListener;

    private int energy;
    private long receiveBudgetTick = Long.MIN_VALUE;
    private int receivedThisTick;
    private boolean internalChangePending;

    public ExcavatorEnergyStorage(LongSupplier gameTimeSupplier, Runnable changeListener) {
        this.gameTimeSupplier = gameTimeSupplier;
        this.changeListener = changeListener;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        long profile = ExcavatorProfiler.begin(ExcavatorProfiler.Section.EXTERNAL_FE_RECEIVE);
        try {
            if (maxReceive <= 0) return 0;

            long gameTime = gameTimeSupplier.getAsLong();
            if (gameTime != receiveBudgetTick) {
                receiveBudgetTick = gameTime;
                receivedThisTick = 0;
            }

            int rateRemaining = Math.max(0, LaserExcavatorConfig.ENERGY_MAX_RECEIVE.get() - receivedThisTick);
            int accepted = Math.min(maxReceive, Math.min(rateRemaining, remainingCapacity()));

            if (!simulate && accepted > 0) {
                energy += accepted;
                receivedThisTick += accepted;
                changeListener.run();
            }
            return accepted;
        } finally {
            ExcavatorProfiler.end(ExcavatorProfiler.Section.EXTERNAL_FE_RECEIVE, profile);
        }
    }

    @Override public int extractEnergy(int maxExtract, boolean simulate) { return 0; }
    @Override public int getEnergyStored() { return Math.min(energy, capacity()); }
    @Override public int getMaxEnergyStored() { return capacity(); }
    @Override public boolean canExtract() { return false; }
    @Override public boolean canReceive() { return true; }

    public int remainingCapacity() {
        int capacity = capacity();
        return capacity - Math.min(energy, capacity);
    }

    public int addInternal(int amount) {
        if (amount <= 0) return 0;
        int capacity = capacity(), stored = Math.min(energy, capacity);
        int accepted = Math.min(amount, capacity - stored);
        if (accepted <= 0) return 0;
        energy = stored + accepted;
        internalChangePending = true;
        return accepted;
    }

    /** Adds as many complete internal energy units as fit. */
    public int addInternalUnits(int energyPerUnit, int maxUnits) {
        if (energyPerUnit <= 0 || maxUnits <= 0) return 0;

        int capacity = capacity(), stored = Math.min(energy, capacity);
        int accepted = Math.min(maxUnits, (capacity - stored) / energyPerUnit);
        if (accepted <= 0) return 0;
        energy = stored + accepted * energyPerUnit;
        internalChangePending = true;
        return accepted;
    }

    public boolean consumeInternal(int amount) {
        if (amount <= 0) return true;
        int stored = Math.min(energy, capacity());
        if (stored < amount) return false;
        energy = stored - amount;
        internalChangePending = true;
        return true;
    }

    public boolean flushInternalChanges() {
        if (!internalChangePending) return false;
        internalChangePending = false;
        changeListener.run();
        return true;
    }

    public void loadStoredEnergy(int stored) { energy = Mth.clamp(stored, 0, capacity()); }
    private int capacity() { return Math.max(1, LaserExcavatorConfig.ENERGY_CAPACITY.get()); }
}
