package de.balto.laserexcavator.block.excavator;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Mutable per-column excavation state. It owns the scan heights, current target
 * heights and dense active-column list, while world lookups and shared-column
 * coordination remain in the block entity.
 */
public final class ExcavatorColumnState {
    private int scanIndex;
    private int[] surfaceHeights = new int[0];
    private int highestSurfaceY = ExcavationScanner.NO_SURFACE;

    private int[] currentHeights = new int[0];
    private int[] activeColumns = new int[0];
    private int activeColumnCount;
    private boolean excavationInitialized;
    private boolean sharedColumnsRegistered;
    private final BitSet sharedColumns = new BitSet();

    public void beginScan(int totalColumns) {
        surfaceHeights = new int[totalColumns];
        Arrays.fill(surfaceHeights, ExcavationScanner.NO_SURFACE);
        currentHeights = new int[0];
        activeColumns = new int[0];
        activeColumnCount = 0;
        excavationInitialized = false;
        sharedColumnsRegistered = false;
        sharedColumns.clear();
        scanIndex = 0;
        highestSurfaceY = ExcavationScanner.NO_SURFACE;
    }

    public void ensureScanStorage(int totalColumns) {
        if (surfaceHeights.length == totalColumns) return;
        surfaceHeights = new int[totalColumns];
        Arrays.fill(surfaceHeights, ExcavationScanner.NO_SURFACE);
        scanIndex = 0;
        highestSurfaceY = ExcavationScanner.NO_SURFACE;
    }

    public int scanIndex() {
        return scanIndex;
    }

    public void recordScannedSurface(int surfaceY) {
        surfaceHeights[scanIndex] = surfaceY;
        if (surfaceY != ExcavationScanner.NO_SURFACE) {
            highestSurfaceY = Math.max(highestSurfaceY, surfaceY);
        }
        scanIndex++;
    }

    public int highestSurfaceY() {
        return highestSurfaceY;
    }

    /** Transfers the scan array into the mutable target-height array without cloning it. */
    public void beginExcavationState(int totalColumns) {
        if (surfaceHeights.length == totalColumns) {
            currentHeights = surfaceHeights;
            surfaceHeights = new int[0];
        } else {
            currentHeights = new int[totalColumns];
            Arrays.fill(currentHeights, ExcavationScanner.NO_SURFACE);
        }
        activeColumns = new int[totalColumns];
        activeColumnCount = 0;
    }

    public int currentHeight(int columnIndex) {
        return currentHeights[columnIndex];
    }

    public void setCurrentHeight(int columnIndex, int y) {
        currentHeights[columnIndex] = y;
    }

    public int currentHeightCount() {
        return currentHeights.length;
    }

    public int activeCount() {
        return activeColumnCount;
    }

    public int activeColumnAt(int activeSlot) {
        return activeColumns[activeSlot];
    }

    public void addActiveColumn(int columnIndex) {
        activeColumns[activeColumnCount++] = columnIndex;
    }

    public void ensureActiveColumn(int columnIndex) {
        for (int slot = 0; slot < activeColumnCount; slot++) {
            if (activeColumns[slot] == columnIndex) return;
        }
        if (activeColumnCount >= activeColumns.length) {
            activeColumns = Arrays.copyOf(activeColumns, Math.max(activeColumnCount + 1, activeColumns.length * 2 + 1));
        }
        activeColumns[activeColumnCount++] = columnIndex;
    }

    public void trimActiveColumns() {
        if (activeColumnCount < activeColumns.length) {
            activeColumns = Arrays.copyOf(activeColumns, activeColumnCount);
        }
    }

    public void replaceActiveColumns(int[] columns, int count) {
        activeColumns = count == columns.length ? columns : Arrays.copyOf(columns, count);
        activeColumnCount = count;
    }

    public void removeActiveColumnByColumnIndex(int columnIndex) {
        // Exhaustion happens at most once per column, so a tiny linear lookup here is
        // cheaper overall than retaining a full reverse-lookup int[] for the entire
        // excavation. Normal target selection remains O(1) through activeColumns.
        for (int slot = 0; slot < activeColumnCount; slot++) {
            if (activeColumns[slot] == columnIndex) {
                removeActiveColumnAt(slot);
                return;
            }
        }
    }

    public void removeActiveColumnAt(int activeSlot) {
        if (activeSlot < 0 || activeSlot >= activeColumnCount) return;
        int lastSlot = --activeColumnCount;
        if (activeSlot != lastSlot) activeColumns[activeSlot] = activeColumns[lastSlot];
    }

    public boolean isExcavationInitialized() {
        return excavationInitialized;
    }

    public void markExcavationInitialized() {
        excavationInitialized = true;
        sharedColumnsRegistered = false;
        sharedColumns.clear();
    }

    public boolean areSharedColumnsRegistered() {
        return sharedColumnsRegistered;
    }

    public void setSharedColumnsRegistered(boolean registered) {
        sharedColumnsRegistered = registered;
    }

    public boolean isSharedColumn(int columnIndex) {
        return sharedColumns.get(columnIndex);
    }

    public void setSharedColumn(int columnIndex, boolean shared) {
        sharedColumns.set(columnIndex, shared);
    }

    public int nextSharedColumn(int fromIndex) {
        return sharedColumns.nextSetBit(fromIndex);
    }

    public void clearSharedColumns() {
        sharedColumns.clear();
    }

    public void finishExcavation() {
        activeColumnCount = 0;
        currentHeights = new int[0];
        activeColumns = new int[0];
        surfaceHeights = new int[0];
        excavationInitialized = false;
        sharedColumnsRegistered = false;
        sharedColumns.clear();
    }

    public void resetAll() {
        scanIndex = 0;
        surfaceHeights = new int[0];
        highestSurfaceY = ExcavationScanner.NO_SURFACE;
        currentHeights = new int[0];
        activeColumns = new int[0];
        activeColumnCount = 0;
        excavationInitialized = false;
        sharedColumnsRegistered = false;
        sharedColumns.clear();
    }

    public int[] surfaceHeightsForSave() {
        return surfaceHeights;
    }

    public int[] currentHeightsForSave() {
        return currentHeights;
    }

    public int[] activeColumnsForSave() {
        return Arrays.copyOf(activeColumns, activeColumnCount);
    }

    public void loadSummary(int scanIndex, int highestSurfaceY, int activeColumnCount) {
        this.scanIndex = Math.max(0, scanIndex);
        this.highestSurfaceY = highestSurfaceY;
        this.activeColumnCount = Math.max(0, activeColumnCount);
        this.sharedColumnsRegistered = false;
        sharedColumns.clear();
    }

    public void loadExcavationState(
            int[] surfaceHeights,
            int[] currentHeights,
            int[] activeColumns,
            boolean excavationInitialized
    ) {
        this.surfaceHeights = surfaceHeights;
        this.currentHeights = currentHeights;
        this.activeColumns = activeColumns;
        this.activeColumnCount = Math.min(activeColumnCount, activeColumns.length);
        this.excavationInitialized = excavationInitialized;
        this.sharedColumnsRegistered = false;
        sharedColumns.clear();
    }

    public void loadClientSyncState(boolean excavationInitialized) {
        surfaceHeights = new int[0];
        currentHeights = new int[0];
        activeColumns = new int[0];
        this.excavationInitialized = excavationInitialized;
        sharedColumnsRegistered = false;
        sharedColumns.clear();
    }

    public void clampScanIndex(int totalColumns) {
        scanIndex = Math.max(0, Math.min(scanIndex, totalColumns));
    }

    public boolean hasExpectedSurfaceCount(int totalColumns) {
        return surfaceHeights.length == totalColumns;
    }

    public boolean hasExpectedCurrentCount(int totalColumns) {
        return currentHeights.length == totalColumns;
    }
}
