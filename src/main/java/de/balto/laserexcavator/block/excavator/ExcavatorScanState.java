package de.balto.laserexcavator.block.excavator;

public enum ExcavatorScanState {
    IDLE(0),
    SCANNING(1),
    READY(2),
    EXCAVATING(3),
    STORAGE_FULL(4),
    COMPLETE(5);

    private static final ExcavatorScanState[] VALUES = values();
    private final int id;

    ExcavatorScanState(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static ExcavatorScanState byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : IDLE;
    }
}
