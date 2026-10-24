// Ported from the user's carlink FX11 integration (c839f925).
package com.shilapi.xcertplay.vehicle;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Parcelable;

/** Optional data supplied by a navigation adapter. All absent values remain unknown. */
public final class NavigationDetails {
    public static final String EXTRA = "demo.navigationDetails";
    private final Bundle values;
    public NavigationDetails(Bundle values) { this.values = values == null ? new Bundle() : new Bundle(values); }
    public NavigationDetails withDayNight(int mode) {
        Bundle next=new Bundle(values);next.putInt("dayNight",mode);return new NavigationDetails(next);
    }
    public NavigationDetails withMute(int mode) {
        Bundle next=new Bundle(values);next.putInt("mute",mode);return new NavigationDetails(next);
    }
    public int seconds(int fallbackMinutes) {
        int fallback = (int)Math.min(Integer.MAX_VALUE,Math.max(0L,fallbackMinutes)*60L);
        return Math.max(0, values.getInt("remainingSeconds",fallback));
    }
    public int dayNight() { return mode("dayNight"); }
    public int mute() { return mode("mute"); }
    private int mode(String key) { int v = values.getInt(key,-1); return v == 0 || v == 1 ? v : -1; }
    public int direction() {
        double bearing = values.getDouble("bearingDegrees",Double.NaN);
        if (!Double.isFinite(bearing)) return -1;
        int sector = (int)Math.floor((((bearing % 360) + 360) % 360 + 22.5) / 45) % 8;
        return new int[]{3,4,0,5,1,7,2,6}[sector]; // N NE E SE S SW W NW
    }
    public Bundle object(String key) {
        Bundle b = values.getBundle(key); return b == null ? null : new Bundle(b);
    }
    public Bundle[] lanes() {
        Parcelable[] raw = values.getParcelableArray("lanes");
        if (raw == null) return new Bundle[0];
        java.util.ArrayList<Bundle> out = new java.util.ArrayList<>();
        for (Parcelable item : raw) {
            if (out.size() == 8) break;
            if (!(item instanceof Bundle)) continue;
            Bundle lane = (Bundle)item;
            Object bitmap = lane.get("bitmap");
            if (!(bitmap instanceof Bitmap) && lane.getInt("iconId",-1)<0) continue;
            out.add(new Bundle(lane));
        }
        return out.toArray(new Bundle[0]);
    }
    public String roadType() { return values.getString("roadType"); }
}
