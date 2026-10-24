// Ported from the user's carlink FX11 integration (c839f925).
package com.shilapi.xcertplay.vehicle;

import java.util.Locale;

/**
 * The small, stable data contract needed by a cluster TBT renderer.
 *
 * <p>The fields intentionally follow common automotive guide data (road names,
 * maneuver, distances, speed and lane state) instead of carrying a map bitmap.
 * A vehicle HMI can consume the same object and choose its own renderer.</p>
 */
public final class NavigationInfo {
    public enum Maneuver {
        STRAIGHT("直行"),
        LEFT("左转"),
        RIGHT("右转"),
        U_TURN("掉头"),
        ROUNDABOUT("环岛"),
        ARRIVED("到达");

        private final String label;

        Maneuver(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public final String routeId;
    public final String currentRoadName;
    public final String nextRoadName;
    public final String destination;
    public final Maneuver maneuver;
    public final int distanceMeters;
    public final int routeRemainingMeters;
    public final int routeRemainingMinutes;
    public final int etaMinutes;
    public final int currentSpeedKmh;
    public final int speedLimitKmh;
    public final int laneCount;
    public final int recommendedLane;
    public final boolean rerouting;
    public final boolean trafficLight;
    public final double latitude;
    public final double longitude;
    /** Original AMap newIcon value, when the source supplied one. */
    public final int sourceTurnIconId;
    /** AMap roundabout exit number, used by the OEM TBT mapping. */
    public final int roundaboutExit;
    public final NavigationDetails details;

    public NavigationInfo(String routeId,
                           String currentRoadName,
                           String nextRoadName,
                           String destination,
                           Maneuver maneuver,
                           int distanceMeters,
                           int routeRemainingMeters,
                           int routeRemainingMinutes,
                           int etaMinutes,
                           int currentSpeedKmh,
                           int speedLimitKmh,
                           int laneCount,
                           int recommendedLane,
                           boolean rerouting,
                           boolean trafficLight,
                           double latitude,
                           double longitude) {
        this(routeId, currentRoadName, nextRoadName, destination, maneuver,
                distanceMeters, routeRemainingMeters, routeRemainingMinutes,
                etaMinutes, currentSpeedKmh, speedLimitKmh, laneCount,
                recommendedLane, rerouting, trafficLight, latitude, longitude,
                -1, 0);
    }

    public NavigationInfo(String routeId,
                           String currentRoadName,
                           String nextRoadName,
                           String destination,
                           Maneuver maneuver,
                           int distanceMeters,
                           int routeRemainingMeters,
                           int routeRemainingMinutes,
                           int etaMinutes,
                           int currentSpeedKmh,
                           int speedLimitKmh,
                           int laneCount,
                           int recommendedLane,
                           boolean rerouting,
                           boolean trafficLight,
                           double latitude,
                           double longitude,
                           int sourceTurnIconId,
                           int roundaboutExit) {
        this.details = new NavigationDetails(null);
        this.routeId = valueOrFallback(routeId, "demo-route");
        this.currentRoadName = valueOrFallback(currentRoadName, "");
        this.nextRoadName = valueOrFallback(nextRoadName, "");
        this.destination = valueOrFallback(destination, "");
        this.maneuver = maneuver == null ? Maneuver.STRAIGHT : maneuver;
        this.distanceMeters = Math.max(0, distanceMeters);
        this.routeRemainingMeters = Math.max(0, routeRemainingMeters);
        this.routeRemainingMinutes = Math.max(0, routeRemainingMinutes);
        this.etaMinutes = Math.max(0, etaMinutes);
        this.currentSpeedKmh = Math.max(-1, currentSpeedKmh);
        this.speedLimitKmh = Math.max(0, speedLimitKmh);
        this.laneCount = Math.max(0, Math.min(8, laneCount));
        this.recommendedLane = this.laneCount == 0
                ? 0 : Math.max(1, Math.min(this.laneCount, recommendedLane));
        this.rerouting = rerouting;
        this.trafficLight = trafficLight;
        this.latitude = latitude;
        this.longitude = longitude;
        this.sourceTurnIconId = sourceTurnIconId;
        this.roundaboutExit = Math.max(0, roundaboutExit);
    }

    private NavigationInfo(NavigationInfo original, NavigationDetails details) {
        this.routeId = original.routeId;
        this.currentRoadName = original.currentRoadName;
        this.nextRoadName = original.nextRoadName;
        this.destination = original.destination;
        this.maneuver = original.maneuver;
        this.distanceMeters = original.distanceMeters;
        this.routeRemainingMeters = original.routeRemainingMeters;
        this.etaMinutes = original.etaMinutes;
        this.currentSpeedKmh = original.currentSpeedKmh;
        this.speedLimitKmh = original.speedLimitKmh;
        this.laneCount = original.laneCount;
        this.recommendedLane = original.recommendedLane;
        this.rerouting = original.rerouting;
        this.trafficLight = original.trafficLight;
        this.latitude = original.latitude;
        this.longitude = original.longitude;
        this.sourceTurnIconId = original.sourceTurnIconId;
        this.roundaboutExit = original.roundaboutExit;
        this.details = details;
        this.routeRemainingMinutes = (int)Math.min(Integer.MAX_VALUE,(details.seconds(original.routeRemainingMinutes)+59L)/60);
    }
    public NavigationInfo withDetails(NavigationDetails details) { return new NavigationInfo(this,details); }
    public int remainingSeconds() { return details.seconds(routeRemainingMinutes); }

    public String distanceLabel() {
        if (distanceMeters < 1000) return distanceMeters + " 米";
        return String.format(Locale.CHINA, "%.1f 公里", distanceMeters / 1000f);
    }

    public String routeRemainingLabel() {
        if (routeRemainingMeters < 1000) return routeRemainingMeters + " 米";
        return String.format(Locale.CHINA, "%.1f 公里", routeRemainingMeters / 1000f);
    }

    /** Keeps the current route labels while emitting the terminal TBT state. */
    public NavigationInfo asArrived() {
        return new NavigationInfo(routeId, currentRoadName, nextRoadName, destination,
                Maneuver.ARRIVED, 0, 0, 0, 0, currentSpeedKmh, speedLimitKmh,
                0, 0, false, false, latitude, longitude);
    }

    private static String valueOrFallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
