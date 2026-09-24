package de.balto.laserexcavator.block.excavator;

public enum ExcavatorWorkPhase {
    NONE(0),
    LASER(1);

    private final int id;

    ExcavatorWorkPhase(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static ExcavatorWorkPhase byId(int id) {
        for (ExcavatorWorkPhase phase : values()) {
            if (phase.id == id) return phase;
        }
        return NONE;
    }
}
