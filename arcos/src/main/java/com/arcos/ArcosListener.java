package com.arcos;

/**
 * Callbacks from the robot's background thread. Every method has a do-nothing
 * default, so implementations override only what they need.
 *
 * <p>These fire on {@link ArcosRobot}'s cycle thread, not the Android main thread.
 * Post to a {@code Handler} before touching views.
 */
public interface ArcosListener {

    /** The handshake succeeded and the session is open. */
    default void onConnected(RobotInfo info) { }

    /**
     * A Server Information Packet arrived, roughly every 100 ms. This is the only
     * place fresh odometry, battery and sonar data appear.
     */
    default void onState(RobotState state) { }

    /** The session ended, whether by {@link ArcosRobot#disconnect} or by failure. */
    default void onDisconnected(String reason) { }

    /**
     * Something went wrong. A connect failure arrives here and then the robot goes
     * back to disconnected; a mid-session error arrives here and ends the session.
     */
    default void onError(Throwable error) { }

    /** Diagnostic text: handshake steps, dropped frames, baud switches. */
    default void onLog(String message) { }
}
