package com.shilapi.xcertplay

import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Turns the instrument panel's projection into DiPlay's display.
 *
 * <p>On `geely_fx11` the cluster keeps its own layer on top of Android's display 2 until the
 * head unit is told to project to it. The stock map does this through the DIM service
 * (`com.autolink.diminteraction`); a normal app can call the same binder directly: the
 * `DimNaviService` binder, `notifyDimProjectScreenSwitch` (transaction 9, an int). Without it a
 * `Presentation` on the cluster display stays invisible behind the factory dials.
 *
 * <p>Only the Geely device takes this route; everywhere else the cluster projection is driven by
 * the car's own cluster support. The switch is head-unit-wide and shared with the stock map, so the
 * state is read back as well ([status]) and re-asserted when the map hands it back.
 */
internal object GeelyClusterProjection {

    private const val TAG = "DiPlay-Cluster"
    private const val SERVICE = "DimNaviService"
    private const val DESCRIPTOR = "ecarx.dimprotocol.custom.service.IDimNaviService"
    private const val NOTIFY_PROJECT_SCREEN_SWITCH = 9
    private const val GET_PROJECT_SCREEN_STATUS = 10

    /**
     * Reads the head unit's projection state, or null when the answer cannot be obtained (service not
     * registered, transaction refused). The state is head-unit-wide: the stock map drives the same
     * switch, so an off reading while DiPlay's presentation is up means the map took it away and the
     * cluster is dark (see [ClusterProjectionWatchdog]).
     */
    fun status(): Boolean? {
        val binder = service() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            binder.transact(GET_PROJECT_SCREEN_STATUS, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (error: Throwable) {
            // Polled, so a failure is logged but never treated as "off": the watchdog acts on evidence.
            Log.w(TAG, "dim projection: getDimProjectScreenStatus() failed", error)
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Asks the head unit to show (or stop showing) the projected cluster. Returns whether the
     * call reached the service; a missing binder is not an error worth failing over.
     */
    fun set(on: Boolean): Boolean {
        val binder = service() ?: run {
            Log.i(TAG, "dim projection: DimNaviService is not registered")
            return false
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(if (on) 1 else 0)
            binder.transact(NOTIFY_PROJECT_SCREEN_SWITCH, data, reply, 0)
            reply.readException()
            Log.i(TAG, "dim projection: notifyDimProjectScreenSwitch($on) ok")
            true
        } catch (error: Throwable) {
            Log.w(TAG, "dim projection: notifyDimProjectScreenSwitch($on) failed", error)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** {@code android.os.ServiceManager.getService(String)} is a hidden API. */
    private fun service(): IBinder? = try {
        val serviceManager = Class.forName("android.os.ServiceManager")
        serviceManager.getMethod("getService", String::class.java).invoke(null, SERVICE) as? IBinder
    } catch (error: Throwable) {
        Log.w(TAG, "dim projection: ServiceManager.getService($SERVICE) failed", error)
        null
    }
}
