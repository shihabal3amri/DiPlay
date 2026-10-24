package com.shilapi.xcertplay;

import android.hardware.automotive.vehicle.V2_0.IVehicle;
import android.hardware.automotive.vehicle.V2_0.IVehicleCallback;
import android.hardware.automotive.vehicle.V2_0.SubscribeOptions;
import android.hardware.automotive.vehicle.V2_0.VehiclePropConfig;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.os.IHwBinder;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional OEM adapter. Only subscribe/get/unsubscribe for the one requested candidate property. */
public final class Fx11DteHidlBridge implements Fx11DteButtonBridge {
    private static final int PROPERTY = Fx11DteButtonPolicy.PROPERTY;
    private final AtomicBoolean closed = new AtomicBoolean();
    private IVehicle vehicle;
    private Listener listener;
    private boolean attemptedSubscription;
    private boolean deathLinked;
    private final IHwBinder.DeathRecipient death = cookie -> {
        if (!closed.get() && listener != null) listener.onDisconnected("VHAL service died");
    };
    private final IVehicleCallback callback = new IVehicleCallback.Stub() {
        @Override public void onPropertyEvent(ArrayList<VehiclePropValue> values) {
            if (closed.get() || values == null) return;
            for (VehiclePropValue value : values) emit(value, false);
        }
        @Override public void onPropertySet(VehiclePropValue value) { /* Not a vehicle input event. */ }
        @Override public void onPropertySetError(int error, int property, int area) { /* No setters are called. */ }
    };

    @Override public String connect(Listener sink) {
        if (closed.get()) throw new IllegalStateException("Observer closed");
        listener = sink;
        try {
            vehicle = IVehicle.getService("default", false);
            if (vehicle == null) throw new IllegalStateException("No VHAL 2.0/default");
            deathLinked = vehicle.linkToDeath(death, 0);
            VehiclePropConfig config = null;
            for (VehiclePropConfig candidate : vehicle.getAllPropConfigs()) {
                if (candidate != null && candidate.prop == PROPERTY) { config = candidate; break; }
            }
            float rate = 0;
            if (config != null) {
                if ((config.access & 1) == 0) throw new IllegalStateException("DTE property not readable");
                boolean global = config.areaConfigs.isEmpty();
                for (android.hardware.automotive.vehicle.V2_0.VehicleAreaConfig area : config.areaConfigs) {
                    if (area.areaId == 0) global = true;
                }
                if (!global) throw new IllegalStateException("DTE global area unavailable");
                if (config.changeMode == 2) {
                    if (!Float.isFinite(config.minSampleRate) || !Float.isFinite(config.maxSampleRate) ||
                            config.maxSampleRate <= 0 || config.minSampleRate < 0 || config.minSampleRate > config.maxSampleRate)
                        throw new IllegalStateException("Invalid DTE sample-rate bounds");
                    rate = Math.max(config.minSampleRate, Math.min(20f, config.maxSampleRate));
                } else if (config.changeMode != 1) {
                    throw new IllegalStateException("DTE property is not an event source");
                }
            }
            // A config omission permits only this demo-mapped property, not arbitrary ID scanning.
            final String[] baseline = {"getter unavailable"};
            try {
                VehiclePropValue request = new VehiclePropValue(); request.prop = PROPERTY; request.areaId = 0;
                vehicle.get(request, (status, value) -> {
                    baseline[0] = "getter result=" + status;
                    if (status == 0) emit(value, true); // Baseline can NEVER flip a page.
                });
            } catch (Exception error) { baseline[0] = "getter failed=" + error.getClass().getSimpleName(); }
            SubscribeOptions option = new SubscribeOptions();
            option.propId = PROPERTY; option.sampleRate = rate; option.flags = 1;
            ArrayList<SubscribeOptions> options = new ArrayList<>(); options.add(option);
            attemptedSubscription = true;
            int result = vehicle.subscribe(callback, options);
            if (result != 0) throw new IllegalStateException("DTE subscribe result=" + result);
            return "DTE candidate subscribed prop=" + PROPERTY + " rate=" + rate + " flags=1; " +
                (config == null ? "catalog omitted; explicit candidate only; " : "advertised; ") +
                baseline[0] + "; deathLinked=" + deathLinked + "; read-only; RESET unconfirmed";
        } catch (Exception error) {
            throw new IllegalStateException("DTE observer connect failed: " + error.getMessage(), error);
        }
    }

    private void emit(VehiclePropValue value, boolean initial) {
        if (closed.get() || listener == null || value == null || value.prop != PROPERTY) return;
        // PA_IntBase must be exactly 16 bytes. Never reinterpret int/float payloads as a key.
        byte[] bytes = new byte[0];
        if (value.value != null && value.value.bytes != null && value.value.bytes.size() == 16 &&
                value.value.int32Values.isEmpty() && value.value.floatValues.isEmpty() && value.value.int64Values.isEmpty()) {
            bytes = new byte[16];
            for (int i = 0; i < 16; i++) {
                Byte item = value.value.bytes.get(i);
                if (item == null) { bytes = new byte[0]; break; }
                bytes[i] = item;
            }
        }
        listener.onSample(new Fx11DteButtonSample(value.prop, value.areaId, value.status, value.timestamp, bytes, initial));
    }

    @Override public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;
        IVehicle previous = vehicle; vehicle = null;
        if (previous == null) return;
        Exception failure = null;
        try {
            if (attemptedSubscription) {
                int result = previous.unsubscribe(callback, PROPERTY);
                if (result != 0) failure = new IllegalStateException("DTE unsubscribe result=" + result);
            }
        } catch (Exception error) { failure = error; }
        finally { if (deathLinked) previous.unlinkToDeath(death); }
        if (failure != null) throw failure;
    }
}
