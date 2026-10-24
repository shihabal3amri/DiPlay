package com.shilapi.xcertplay.ultra

import android.content.Context
import android.os.SystemClock
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Read-only ECARX exterior-light selector. The selector is separate from actual lamp feedback:
 * AUTO does not imply that a low beam is lit, and AHBC is not an AUTO-low-beam confirmation.
 */
internal class Fx11ExteriorLightSource(
    context: Context,
    private val bridgeFactory: () -> Fx11ExteriorLightBridge = { Fx11EcarxExteriorLightBridge() },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val worker: ExecutorService = READERS,
    private val pollMillis: Long = 500,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) : UltraVehicleSource {
    private val app = context.applicationContext
    private val lock = Any()
    private val telemetry = VehicleTelemetry(clock)
    private var started = false
    private var closed = false
    private var connectionEpoch = 0L
    private var activeSession: Any? = null
    private var task: FutureTask<Unit>? = null
    private var lastAcceptedMillis: Long? = null
    private var connectionAttempts = 0L
    @Volatile private var lastRaw: Int? = null
    @Volatile var status = "ECARX exterior lights stopped; read-only"
        private set

    override fun start() = synchronized(lock) {
        if (started || closed) return@synchronized
        started = true
        status = "ECARX exterior lights connecting; read-only"
        val readTask = FutureTask { readLoop() }
        task = readTask
        try {
            worker.execute(readTask)
        } catch (error: RuntimeException) {
            telemetry.clear()
            status = "ECARX exterior lights unavailable: ${reason(error)}"
        }
    }

    override fun snapshot(): Map<VehicleSignal, VehicleReading> = telemetry.snapshot()

    fun diagnosticReport(): String = synchronized(lock) {
        val age = lastAcceptedMillis?.let { clock() - it }
        val current = telemetry.snapshot()[VehicleSignal.AUTO_HEADLIGHTS]?.value ?: "unknown"
        "$status\nAUTO_HEADLIGHTS@${Fx11ExteriorLightPolicy.FUNCTION}: " +
            "zone=${Fx11ExteriorLightPolicy.ZONE}; raw=${lastRaw ?: "unknown"}; " +
            "value=$current; lastAcceptedAgeMs=${age ?: "never"}; " +
            "connectionAttempts=$connectionAttempts; source=ECARX selector"
    }

    private fun running(): Boolean = synchronized(lock) { !closed }

    private fun readLoop() {
        var bridge: Fx11ExteriorLightBridge? = null
        var consecutiveFailures = 0
        try {
            while (running()) {
                var nextReadDelay = pollMillis
                try {
                    if (bridge == null) {
                        synchronized(lock) { connectionAttempts++ }
                        val candidate = bridgeFactory()
                        bridge = candidate
                        val session = Any()
                        synchronized(lock) { activeSession = session }
                        candidate.connect(app) {
                            synchronized(lock) {
                                if (!closed && activeSession === session) {
                                    connectionEpoch++
                                    telemetry.clear()
                                    lastRaw = null
                                    status = "ECARX exterior lights disconnected; value unknown"
                                }
                            }
                        }
                    }
                    if (!running()) break
                    val epoch = synchronized(lock) { connectionEpoch }
                    val reader = checkNotNull(bridge)
                    if (!reader.supportedStatus().equals("active", ignoreCase = true)) {
                        throw CapabilityUnavailable("Exterior-light function not active")
                    }
                    if (reader.zones()?.contains(Fx11ExteriorLightPolicy.ZONE) != true) {
                        throw CapabilityUnavailable("Exterior-light global zone not supported")
                    }
                    val supportedValues = reader.values()
                    if (supportedValues == null || supportedValues.isEmpty()) {
                        throw CapabilityUnavailable("Exterior-light supported values unavailable")
                    }
                    val raw = reader.read()
                    val decoded = if (supportedValues.contains(raw)) Fx11ExteriorLightPolicy.decode(raw) else null
                    synchronized(lock) {
                        // A disconnect or close during a Binder call must never publish its late reply.
                        if (!closed && epoch == connectionEpoch) {
                            consecutiveFailures = 0
                            lastRaw = raw
                            telemetry.accept(VehicleSignal.AUTO_HEADLIGHTS, decoded, Fx11ExteriorLightPolicy.FUNCTION)
                            if (decoded != null) lastAcceptedMillis = clock()
                            status = if (decoded == null) "ECARX exterior-light selector unknown; raw=$raw"
                                else "ECARX exterior-light selector read; raw=$raw; AUTO=$decoded"
                        }
                    }
                } catch (error: CapabilityUnavailable) {
                    // Unsupported capabilities are not a disconnected Binder. Keep its connection
                    // and recheck slowly, instead of recreating Car and its watchers every 500 ms.
                    nextReadDelay = CAPABILITY_RECHECK_MILLIS
                    synchronized(lock) {
                        telemetry.clear()
                        lastRaw = null
                        if (!closed) status = "ECARX exterior lights unavailable: ${error.message}; " +
                            "value unknown; connection retained; capability recheck in ${nextReadDelay}ms"
                    }
                } catch (error: Exception) {
                    consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(RECONNECT_DELAYS.size)
                    nextReadDelay = RECONNECT_DELAYS[consecutiveFailures - 1]
                    unavailable(error, nextReadDelay)
                    runCatching { bridge?.close() }
                    bridge = null
                } catch (error: LinkageError) {
                    consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(RECONNECT_DELAYS.size)
                    nextReadDelay = RECONNECT_DELAYS[consecutiveFailures - 1]
                    unavailable(error, nextReadDelay)
                    runCatching { bridge?.close() }
                    bridge = null
                }
                if (running()) pause(nextReadDelay)
            }
        } catch (error: InterruptedException) {
            if (running()) unavailable(error)
            Thread.currentThread().interrupt()
        } finally {
            // Cleanup stays on the same background reader, even if OEM disconnect blocks.
            runCatching { bridge?.close() }
            telemetry.clear()
        }
    }

    private fun unavailable(error: Throwable, reconnectDelay: Long? = null) = synchronized(lock) {
        activeSession = null
        telemetry.clear()
        lastRaw = null
        if (!closed) status = "ECARX exterior lights unavailable: ${reason(error)}; value unknown" +
            (reconnectDelay?.let { "; reconnect in ${it}ms" } ?: "")
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        connectionEpoch++
        activeSession = null
        telemetry.clear()
        lastRaw = null
        status = "ECARX exterior lights closed; read-only"
        task?.let {
            it.cancel(true)
            // Remove cancelled queued owners, without starting any replacement reader thread.
            (worker as? ThreadPoolExecutor)?.remove(it)
        }
        task = null
    }

    private fun reason(error: Throwable): String {
        val actual = if (error is InvocationTargetException) error.cause ?: error else error
        return "${actual.javaClass.simpleName}: ${actual.message.orEmpty()}"
    }

    companion object {
        private const val CAPABILITY_RECHECK_MILLIS = 30_000L
        private val RECONNECT_DELAYS = longArrayOf(2_000, 5_000, 15_000, 30_000)
        // One process-wide reader and one queued owner bound resource use even when Binder ignores
        // interruption. A source that cannot acquire it stays unknown, never creating extra threads.
        private val READERS = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "fx11-exterior-light-read").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
    }

    private class CapabilityUnavailable(message: String) : Exception(message)
}

/** Fixed IDs and getters only; callers cannot select another vehicle function or perform writes. */
internal interface Fx11ExteriorLightBridge : AutoCloseable {
    fun connect(context: Context, onDisconnected: () -> Unit)
    fun supportedStatus(): String
    fun zones(): IntArray?
    fun values(): IntArray?
    fun read(): Int
}

internal object Fx11ExteriorLightPolicy {
    const val FUNCTION = 537136640
    const val ZONE = Int.MIN_VALUE
    const val OFF = 0
    const val POSITION = 537136641
    const val LOW_BEAM = 537136642
    const val AUTO = 537136643
    const val AHBC = 537136644

    fun decode(raw: Int): Boolean? = when (raw) {
        AUTO -> true
        OFF, POSITION, LOW_BEAM -> false
        else -> null
    }
}

/** Uses the installed ECARX runtime, matching the FX11 demo's read-only function getter. */
private class Fx11EcarxExteriorLightBridge : Fx11ExteriorLightBridge {
    private var car: Any? = null
    private var functionType: Class<*>? = null
    private var connectType: Class<*>? = null
    private var watcher: Any? = null
    @Volatile private var function: Any? = null
    @Volatile private var connected = false
    @Volatile private var closed = false

    override fun connect(context: Context, onDisconnected: () -> Unit) {
        val factory = Class.forName("com.ecarx.xui.adaptapi.car.Car")
        val carType = Class.forName("com.ecarx.xui.adaptapi.car.ICar")
        functionType = Class.forName("com.ecarx.xui.adaptapi.car.base.ICarFunction")
        val instance = invoke(factory.getMethod("create", Context::class.java), null, context.applicationContext)
            ?: error("ECARX Car.create returned null")
        car = instance
        connectType = try { Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable") }
            catch (_: ClassNotFoundException) { null }
        val connect = connectType
        if (connect != null && connect.isInstance(instance)) {
            val ready = CountDownLatch(1)
            val watcherType = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher")
            watcher = Proxy.newProxyInstance(watcherType.classLoader, arrayOf(watcherType)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "DiPlay FX11 read-only exterior-light watcher"
                    "onConnected" -> { if (!closed) connected = true; ready.countDown(); null }
                    "onDisConnected" -> {
                        connected = false
                        function = null
                        onDisconnected()
                        ready.countDown()
                        null
                    }
                    else -> null
                }
            }
            invoke(connect.getMethod("registerConnectWatcher", watcherType), instance, watcher)
            check(ready.await(8, TimeUnit.SECONDS)) { "ECARX connection timed out" }
        } else connected = true
        check(connected && !closed) { "ECARX disconnected" }
        // Do not issue Binder getters from a connect callback, which can run on the main thread.
        function = invoke(carType.getMethod("getICarFunction"), instance)
        requireFunction()
    }

    private fun requireFunction(): Any {
        check(!closed && connected) { "ECARX disconnected" }
        return checkNotNull(function) { "ECARX function unavailable" }
    }

    private fun getter(name: String, vararg args: Int): Any? = invoke(
        checkNotNull(functionType).getMethod(name, *Array(args.size) { Int::class.javaPrimitiveType!! }),
        requireFunction(), *args.toTypedArray())

    override fun supportedStatus() = getter("isFunctionSupported", Fx11ExteriorLightPolicy.FUNCTION,
        Fx11ExteriorLightPolicy.ZONE)?.toString() ?: "unknown"
    override fun zones() = getter("getSupportedFunctionZones", Fx11ExteriorLightPolicy.FUNCTION) as? IntArray
    override fun values() = getter("getSupportedFunctionValue", Fx11ExteriorLightPolicy.FUNCTION,
        Fx11ExteriorLightPolicy.ZONE) as? IntArray
    override fun read() = (getter("getFunctionValue", Fx11ExteriorLightPolicy.FUNCTION,
        Fx11ExteriorLightPolicy.ZONE) as? Number)?.toInt() ?: error("Exterior-light value unavailable")

    override fun close() {
        closed = true
        connected = false
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
