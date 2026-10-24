// Compile-only API signatures, NOT a HAL implementation. See platform-stubs/README.md.
package android.hardware.automotive.vehicle.V2_0;

public interface IVehicleCallback {
    void onPropertyEvent(java.util.ArrayList<VehiclePropValue> values);
    void onPropertySet(VehiclePropValue value);
    void onPropertySetError(int errorCode, int propId, int areaId);
    abstract class Stub implements IVehicleCallback {
        public Stub() { throw new UnsupportedOperationException("Compile-only signature"); }
    }
}
