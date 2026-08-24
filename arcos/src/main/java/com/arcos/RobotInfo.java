package com.arcos;

/**
 * What the robot said about itself in its reply to SYNC2, plus the conversion
 * factors chosen as a result.
 */
public final class RobotInfo {

    /** Name stored in the controller's flash, e.g. {@code "Pioneer"}. */
    public final String name;
    /** General class, e.g. {@code "Pioneer"}. */
    public final String type;
    /** Specific model, lower-cased, e.g. {@code "p3dx"}. */
    public final String subtype;
    /** Conversion factors in use for this session. */
    public final RobotParams params;
    /**
     * True when {@link #subtype} matched a model this library knows. When false,
     * {@link #params} is a P3-DX guess and the odometry scale may be wrong.
     */
    public final boolean paramsRecognised;

    RobotInfo(String name, String type, String subtype, RobotParams params,
              boolean paramsRecognised) {
        this.name = name;
        this.type = type;
        this.subtype = subtype;
        this.params = params;
        this.paramsRecognised = paramsRecognised;
    }

    @Override public String toString() {
        return "RobotInfo{name=" + name + ", type=" + type + ", subtype=" + subtype
                + (paramsRecognised ? "" : " (unrecognised, using p3dx factors)") + "}";
    }
}
