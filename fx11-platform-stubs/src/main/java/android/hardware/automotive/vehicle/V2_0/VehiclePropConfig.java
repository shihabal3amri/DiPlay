// Compile-only API signatures, NOT a HAL implementation. See platform-stubs/README.md.
package android.hardware.automotive.vehicle.V2_0;

public final class VehiclePropConfig {
    public int prop, access, changeMode;
    public java.util.ArrayList<VehicleAreaConfig> areaConfigs = new java.util.ArrayList<>();
    public java.util.ArrayList<Integer> configArray = new java.util.ArrayList<>();
    public String configString = "";
    public float minSampleRate, maxSampleRate;
}
