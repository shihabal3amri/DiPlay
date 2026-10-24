// Compile-only API signatures, NOT a HAL implementation. See platform-stubs/README.md.
package android.hardware.automotive.vehicle.V2_0;

public final class VehiclePropValue {
    public long timestamp;
    public int areaId, prop, status;
    public RawValue value = new RawValue();
    public static final class RawValue {
        public java.util.ArrayList<Integer> int32Values = new java.util.ArrayList<>();
        public java.util.ArrayList<Float> floatValues = new java.util.ArrayList<>();
        public java.util.ArrayList<Long> int64Values = new java.util.ArrayList<>();
        public java.util.ArrayList<Byte> bytes = new java.util.ArrayList<>();
        public String stringValue = "";
    }
}
