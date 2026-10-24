package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The fixed OEM three-screen switch. start/close never write; explicit toggle/restore may write.
 * Its value describes the OEM mode, not proof that a physical instrument rendered our window.
 */
internal class Fx11ThreeScreenController(
    private val bridgeFactory: () -> Fx11ThreeScreenBridge = { Fx11EcarxThreeScreenBridge() },
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "fx11-three-screen").apply { isDaemon = true }
    },
    private val callbacks: Executor = Executor { Handler(Looper.getMainLooper()).post(it) },
    private val deadlines: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "fx11-three-screen-deadline").apply { isDaemon = true }
    },
    private val timeoutMillis: Long = 12_000,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val mode: Fx11ProjectionMode = Fx11ProjectionMode.THREE_SCREEN,
    private val onWriteAttempt: (Int) -> Unit = {},
) : AutoCloseable {
    internal data class Result(
        val success: Boolean,
        val before: Int? = null,
        val target: Int? = null,
        val after: Int? = null,
        val writeAttempted: Boolean = false,
        val message: String,
    )

    private class Operation(val callback: (Result) -> Unit) {
        var delivered = false
        var cancelled = false
        var before: Int? = null
        var target: Int? = null
        var writeAttempted = false
        var timeout: ScheduledFuture<*>? = null
    }

    private val lock = Any()
    private var context: Context? = null
    private var closed = false
    private var dashboardReady = false
    private var active: Operation? = null
    val hasPendingRequest: Boolean get() = synchronized(lock) { active != null }
    @Volatile var status: String = "${mode.label} controller stopped; no write"
        private set

    fun start(context: Context) = synchronized(lock) {
        if (!closed) {
            this.context = context.applicationContext
            if (active == null) status = "${mode.label} ready; awaiting explicit MODE press; no write"
        }
    }

    /** Let the display owner invalidate an in-flight enable when its window disappears. No OEM call. */
    fun updateDashboardReady(ready: Boolean) = synchronized(lock) { dashboardReady = ready }

    /** Reject repeated presses while busy instead of queueing stale inversions of the same switch. */
    fun toggle(canShowDashboard: Boolean, callback: (Result) -> Unit = {}) =
        request(canShowDashboard, desiredState = null, callback)

    /** Explicit user request to close the dashboard. Unlike toggle, an already-restored OEM stays 0. */
    fun restoreOem(callback: (Result) -> Unit = {}) = request(false, desiredState = 0, callback)

    private fun request(canShowDashboard: Boolean, desiredState: Int?, callback: (Result) -> Unit) {
        val operation: Operation
        val app: Context
        synchronized(lock) {
            dashboardReady = canShowDashboard
            val unavailable = when {
                closed || context == null -> "${mode.label} controller not started; no write"
                active != null -> "${mode.label} operation still in progress; press ignored; no additional write"
                else -> null
            }
            if (unavailable != null) {
                callbacks.execute { callback(Result(false, message = unavailable)) }
                return
            }
            app = checkNotNull(context)
            operation = Operation(callback)
            active = operation
            status = "${mode.label} checking current OEM state"
            operation.timeout = deadlines.schedule({
                synchronized(lock) { operation.cancelled = true }
                complete(operation, Result(false, operation.before, operation.target,
                    writeAttempted = operation.writeAttempted,
                    message = if (operation.writeAttempted)
                        "${mode.label} timed out after one write attempt; actual state unconfirmed; no retry"
                    else "${mode.label} timed out before writing; no write"))
            }, timeoutMillis, TimeUnit.MILLISECONDS)
        }
        try {
            worker.execute { runOperation(app, operation, canShowDashboard, desiredState) }
        } catch (error: RuntimeException) {
            complete(operation, Result(false, message = "${mode.label} worker unavailable; no write: ${reason(error)}"))
            synchronized(lock) { if (active === operation) active = null }
        }
    }

    private fun valid(operation: Operation) = synchronized(lock) {
        !closed && active === operation && !operation.cancelled
    }

    private fun runOperation(app: Context, operation: Operation, canShowDashboard: Boolean, desiredState: Int?) {
        var bridge: Fx11ThreeScreenBridge? = null
        try {
            check(valid(operation)) { "Operation cancelled before connecting" }
            bridge = bridgeFactory()
            bridge.connect(app)
            check(valid(operation)) { "Operation cancelled before checking switch" }
            bridge.validate(mode)
            val before = bridge.read()
            synchronized(lock) { operation.before = before }
            check(before == 0 || before == mode.enabledValue) { "Unknown OEM state $before" }
            val target = desiredState ?: if (before == mode.enabledValue) 0 else {
                check(canShowDashboard) { "Dashboard window not ready; keeping OEM instrument" }
                mode.enabledValue
            }
            synchronized(lock) {
                check(valid(operation)) { "Operation cancelled before applying request" }
                operation.target = target
            }
            if (before == target) {
                complete(operation, Result(true, before, target, before, false,
                    "${mode.label} OEM already restored; confirmed state=0; no write"))
                return
            }
            synchronized(lock) {
                check(valid(operation)) { "Operation cancelled before writing" }
                check(target == 0 || dashboardReady) { "Dashboard window disappeared before enabling" }
                // Commit the sole write attempt under the same lock as cancellation. A Binder call
                // already committed here cannot be recalled by close or by the deadline watcher.
                onWriteAttempt(target)
                operation.writeAttempted = true
                status = "${mode.label} single write requested $before->$target"
            }
            check(bridge.write(target)) { "${mode.label} rejected write; not resent" }
            var after: Int? = null
            for (attempt in 0 until Fx11ThreeScreenPolicy.READBACK_ATTEMPTS) {
                if (!valid(operation)) return
                if (attempt > 0) pause(Fx11ThreeScreenPolicy.READBACK_DELAY_MILLIS)
                if (!valid(operation)) return
                after = bridge.read()
                check(after == 0 || after == mode.enabledValue) { "Unknown readback $after; not resent" }
                if (after == target) {
                    complete(operation, Result(true, before, target, after, true,
                        "${mode.label} OEM state confirmed $before->$after; physical display not verified"))
                    return
                }
            }
            complete(operation, Result(false, before, target, after, true,
                "${mode.label} write accepted but readback did not reach $target; not resent"))
        } catch (error: Exception) {
            completeFailure(operation, error)
        } catch (error: LinkageError) {
            completeFailure(operation, error)
        } finally {
            // A stuck Binder operation never gets replaced by another reader/writer thread.
            runCatching { bridge?.close() }
            synchronized(lock) { if (active === operation) active = null }
        }
    }

    private fun completeFailure(operation: Operation, error: Throwable) {
        complete(operation, Result(false, operation.before, operation.target,
            writeAttempted = operation.writeAttempted,
            message = "${mode.label} failed: ${reason(error)}; writeAttempted=${operation.writeAttempted}; no retry"))
    }

    private fun complete(operation: Operation, result: Result) {
        synchronized(lock) {
            if (closed || operation.delivered) return
            operation.delivered = true
            operation.timeout?.cancel(false)
            status = result.message
        }
        callbacks.execute {
            if (synchronized(lock) { !closed }) operation.callback(result)
        }
    }

    /** Does not restore or toggle the OEM mode. Callers must not imply that dismissing restores it. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            context = null
            active?.cancelled = true
            active?.timeout?.cancel(false)
            status = "${mode.label} controller closed; no automatic write or restore"
        }
        worker.shutdownNow()
        deadlines.shutdownNow()
    }

    private fun reason(error: Throwable): String {
        val actual = if (error is InvocationTargetException) error.cause ?: error else error
        return "${actual.javaClass.simpleName}: ${actual.message.orEmpty()}"
    }
}

/** Narrow bridge: no caller-controlled function IDs, areas, or arbitrary vehicle writes. */
internal interface Fx11ThreeScreenBridge : AutoCloseable {
    fun connect(context: Context)
    fun supportedStatus(): String = "unknown"
    fun zones(): IntArray? = null
    fun values(): IntArray? = null
    fun validate(mode: Fx11ProjectionMode) {
        check(mode == Fx11ProjectionMode.THREE_SCREEN) { "Wrong projection bridge" }
        Fx11ThreeScreenPolicy.validate(this)
    }
    fun read(): Int
    fun write(value: Int): Boolean
}

internal enum class Fx11ProjectionMode(val enabledValue: Int, val label: String) {
    THREE_SCREEN(1, "Three-screen"), OEM_OVERLAY(3, "OEM-overlay VHAL")
}

internal object Fx11ThreeScreenPolicy {
    const val FUNCTION = 540148992 // ECARX SETTING_FUNC_THREE_SCREEN_ANIMATION, 0x20320500.
    const val ZONE = Int.MIN_VALUE
    const val READBACK_ATTEMPTS = 8
    const val READBACK_DELAY_MILLIS = 250L

    fun validate(bridge: Fx11ThreeScreenBridge) {
        check(bridge.supportedStatus().equals("active", ignoreCase = true)) { "OEM function not active" }
        check(bridge.zones()?.contains(ZONE) == true) { "OEM global zone not supported" }
        val values = bridge.values()
        check(values?.contains(0) == true && values.contains(1)) { "OEM 0/1 values not supported" }
    }

    fun target(before: Int, canShowDashboard: Boolean): Int {
        check(before == 0 || before == 1) { "Unknown OEM state $before" }
        check(before == 1 || canShowDashboard) { "Dashboard window not ready; keeping OEM instrument" }
        return 1 - before
    }
}

/** Reflection follows the FX11 demo EcarxClient API, using the car's runtime library only. */
private class Fx11EcarxThreeScreenBridge : Fx11ThreeScreenBridge {
    private var car: Any? = null
    @Volatile private var function: Any? = null
    private var carType: Class<*>? = null
    private var functionType: Class<*>? = null
    private var connectType: Class<*>? = null
    private var watcher: Any? = null
    @Volatile private var closed = false

    override fun connect(context: Context) {
        val factory = Class.forName("com.ecarx.xui.adaptapi.car.Car")
        carType = Class.forName("com.ecarx.xui.adaptapi.car.ICar")
        functionType = Class.forName("com.ecarx.xui.adaptapi.car.base.ICarFunction")
        val instance = invoke(factory.getMethod("create", Context::class.java), null, context.applicationContext)
            ?: error("ECARX Car.create returned null")
        car = instance
        connectType = try { Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable") }
            catch (_: ClassNotFoundException) { null }
        val connect = connectType
        if (connect != null && connect.isInstance(instance)) {
            val ready = CountDownLatch(1)
            val failure = AtomicReference<Throwable>()
            val watcherType = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher")
            watcher = Proxy.newProxyInstance(watcherType.classLoader, arrayOf(watcherType)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "DiPlay FX11 three-screen watcher"
                    "onConnected" -> {
                        try {
                            if (!closed) function = invoke(checkNotNull(carType).getMethod("getICarFunction"), instance)
                        } catch (error: Exception) { failure.set(error) }
                        catch (error: LinkageError) { failure.set(error) }
                        finally { ready.countDown() }
                        null
                    }
                    "onDisConnected" -> { function = null; ready.countDown(); null }
                    else -> null
                }
            }
            invoke(connect.getMethod("registerConnectWatcher", watcherType), instance, watcher)
            check(ready.await(8, TimeUnit.SECONDS)) { "ECARX connection timed out" }
            failure.get()?.let { throw IllegalStateException("ECARX function unavailable", it) }
        } else function = invoke(checkNotNull(carType).getMethod("getICarFunction"), instance)
        requireFunction()
    }

    private fun requireFunction(): Any {
        check(!closed) { "ECARX bridge closed" }
        return checkNotNull(function) { "ECARX disconnected" }
    }

    private fun call(name: String, vararg args: Int): Any? = invoke(
        checkNotNull(functionType).getMethod(name, *Array(args.size) { Int::class.javaPrimitiveType!! }),
        requireFunction(), *args.toTypedArray())

    override fun supportedStatus() = call("isFunctionSupported", Fx11ThreeScreenPolicy.FUNCTION, Fx11ThreeScreenPolicy.ZONE)?.toString() ?: "unknown"
    override fun zones() = call("getSupportedFunctionZones", Fx11ThreeScreenPolicy.FUNCTION) as? IntArray
    override fun values() = call("getSupportedFunctionValue", Fx11ThreeScreenPolicy.FUNCTION, Fx11ThreeScreenPolicy.ZONE) as? IntArray
    override fun read() = (call("getFunctionValue", Fx11ThreeScreenPolicy.FUNCTION, Fx11ThreeScreenPolicy.ZONE) as Number).toInt()
    override fun write(value: Int): Boolean {
        require(value == 0 || value == 1)
        return call("setFunctionValue", Fx11ThreeScreenPolicy.FUNCTION, Fx11ThreeScreenPolicy.ZONE, value) == true
    }

    override fun close() {
        closed = true
        function = null
        val instance = car
        car = null
        watcher = null
        connectType?.takeIf { instance != null && it.isInstance(instance) }?.let {
            invoke(it.getMethod("disconnect"), instance)
        }
    }

    private fun invoke(method: Method, receiver: Any?, vararg args: Any?): Any? = try {
        method.invoke(receiver, *args)
    } catch (error: InvocationTargetException) {
        throw error.cause ?: error
    }
}
