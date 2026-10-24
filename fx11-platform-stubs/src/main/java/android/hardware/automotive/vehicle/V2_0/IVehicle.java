// Compile-only API signatures, NOT a HAL implementation. See platform-stubs/README.md.
package android.hardware.automotive.vehicle.V2_0;

public interface IVehicle {
    static IVehicle getService(String name, boolean retry) throws Exception { throw new UnsupportedOperationException("Compile-only signature; use the vehicle system implementation"); }
    java.util.ArrayList<VehiclePropConfig> getAllPropConfigs() throws Exception;
    interface getCallback { void onValues(int status, VehiclePropValue value); }
    void get(VehiclePropValue request, getCallback callback) throws Exception;
    int set(VehiclePropValue value) throws Exception;
    int subscribe(IVehicleCallback callback, java.util.ArrayList<SubscribeOptions> options) throws Exception;
    int unsubscribe(IVehicleCallback callback, int propId) throws Exception;
    boolean linkToDeath(android.os.IHwBinder.DeathRecipient recipient, long cookie) throws Exception;
    boolean unlinkToDeath(android.os.IHwBinder.DeathRecipient recipient) throws Exception;
}
