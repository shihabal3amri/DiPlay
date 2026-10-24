package com.shilapi.xcertplay.ultra

import android.os.SystemClock
import java.lang.reflect.Proxy
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Read-only HIDL 2.0 adapter based on the user's working FX11 demo. No HAL setters are loaded. */
internal class Fx11VehicleSource(
    private val signals: Set<VehicleSignal> = VehicleSignal.entries.toSet(),
    private val extraBindings: List<Fx11Signals.Binding> = emptyList(),
) : UltraVehicleSource {
    private val bindings = (Fx11Signals.bindings + extraBindings).filter { it.signal in signals }
    private val stopped = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val telemetry = VehicleTelemetry(SystemClock::elapsedRealtime)
    private val monitor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fx11-ultra-poll").apply { isDaemon = true } }
    @Volatile private var active: Future<*>? = null
    @Volatile var status = "FX11 disconnected"
        private set
    private var vehicle: Any? = null
    private var valueClass: Class<*>? = null
    private var retryAfter = 0L
    private var targets = emptyList<Fx11Signals.Binding>()
    private val diagnostics = ConcurrentHashMap<Int, String>()
    private val readModes = ConcurrentHashMap<Int, String>()
    private val confirmations = ConcurrentHashMap<Int, Long>()

    /** No VIN, coordinates, device identity or arbitrary BYTES payloads are recorded. */
    fun diagnosticReport(): String {
        val now = SystemClock.elapsedRealtime()
        return status + "\n" + bindings.joinToString("\n") { binding ->
            val age = confirmations[binding.propertyId]?.let { now - it }
            "${binding.signal}@${binding.propertyId}: ${diagnostics[binding.propertyId] ?: "not polled"}; " +
                "lastAcceptedAgeMs=${age ?: "never"}; source=${readModes[binding.propertyId] ?: "not selected"}"
        }
    }

    override fun start() {
        if (stopped.get() || !started.compareAndSet(false, true)) return
        monitor.scheduleWithFixedDelay(::poll, 0, 250, TimeUnit.MILLISECONDS)
    }
    override fun snapshot() = telemetry.snapshot()
    private fun poll() {
        if (stopped.get() || SystemClock.elapsedRealtime() < retryAfter) return
        try {
            val progress = AtomicLong()
            val task = READERS.submit {
                if (stopped.get()) return@submit
                if (vehicle == null) { connect(); progress.incrementAndGet() }
                for (binding in targets) {
                    if (stopped.get() || Thread.currentThread().isInterrupted) break
                    try { read(binding) } catch (e: Exception) {
                        if (e.cause is android.os.RemoteException || e is android.os.RemoteException) throw e
                        if (!stopped.get()) telemetry.accept(binding.signal, null, binding.propertyId)
                        val reason = e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName
                        diagnostics[binding.propertyId] = "read exception=$reason"
                        status = "FX11 read failed ${binding.propertyId}: $reason"
                    } finally { progress.incrementAndGet() }
                }
            }
            active = task
            // Large native dashboards poll many more properties than ordinary CarPlay.
            // Timeout only a stalled reader, not a whole round that is still making progress.
            awaitProgress(task, progress)
        } catch (e: Exception) {
            active?.cancel(true)
            telemetry.clear()
            status = "FX11 unavailable: ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}"
            // A native Binder call may ignore interrupts. Never add unbounded replacement readers.
            if (e is TimeoutException || e is RejectedExecutionException) {
                stopped.set(true)
                monitor.shutdownNow()
            } else { vehicle = null; retryAfter = SystemClock.elapsedRealtime() + 5000 }
        } finally { active = null }
    }
    private fun connect() {
        val api = Class.forName("android.hardware.automotive.vehicle.V2_0.IVehicle")
        val candidate = api.getMethod("getService", String::class.java, Boolean::class.javaPrimitiveType)
            .invoke(null, "default", false) ?: error("No VHAL 2.0/default")
        valueClass = Class.forName("android.hardware.automotive.vehicle.V2_0.VehiclePropValue")
        val configs = candidate.javaClass.getMethod("getAllPropConfigs").invoke(candidate) as List<*>
        targets = selectTargets(configs)
        if (stopped.get()) return
        vehicle = candidate
        status = "FX11 connected; readable=${targets.size}/${bindings.size}; PA decoding=${targets.count { it.paInteger }}; " +
            "allowlisted direct=${targets.count { readModes[it.propertyId] == "allowlisted direct read" }}"
    }
    private fun selectTargets(configs: List<*>): List<Fx11Signals.Binding> {
        val advertised = configs.filterNotNull().associateBy { (field(it, "prop") as Number).toInt() }
        readModes.clear()
        val selected = bindings.filter { b ->
            val config = advertised[b.propertyId]
            val areas = config?.let { field(it, "areaConfigs") as List<*> }.orEmpty()
                .filterNotNull().map { (field(it, "areaId") as Number).toInt() }
            val readable = config?.let { (field(it, "access") as Number).toInt() and 1 != 0 } == true
            // FX11 omits some verified global, read-only feedback IDs from its catalog.
            // Only those explicit bindings may bypass absence. An advertised access/area
            // restriction always wins; all getter response checks still apply unchanged.
            val canRead = if (config == null) b.allowMissingConfig else readable && (areas.isEmpty() || 0 in areas)
            diagnostics[b.propertyId] = when {
                config == null && b.allowMissingConfig -> "config missing; allowlisted direct read; waiting for first value"
                config == null -> "config missing"
                !readable -> "config not readable"
                !canRead -> "global area 0 missing; areas=$areas"
                else -> "readable; waiting for first value"
            }
            if (canRead) readModes[b.propertyId] = if (config == null) "allowlisted direct read" else "advertised"
            canRead
        }.distinctBy { it.signal }
        val selectedIds = selected.map { it.propertyId }.toSet()
        diagnostics.replaceAll { property, diagnostic ->
            if (property !in selectedIds && readModes.containsKey(property))
                "${readModes[property]}; lower-priority source not polled" else diagnostic
        }
        return selected
    }
    private fun read(binding: Fx11Signals.Binding) {
        val target = vehicle ?: return
        val request = requireNotNull(valueClass).getConstructor().newInstance()
        request.javaClass.getField("prop").setInt(request, binding.propertyId)
        request.javaClass.getField("areaId").setInt(request, 0)
        val method = target.javaClass.methods.first { it.name == "get" && it.parameterCount == 2 }
        val callbackType = method.parameterTypes[1]
        var returned = false
        val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, m, args ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "Fx11ReadCallback"
                "onValues" -> {
                    returned = true
                    if (!stopped.get()) {
                        val code = (args?.get(0) as? Number)?.toInt()
                        val value = args?.get(1)
                        val returnedProp = value?.let { (field(it, "prop") as Number).toInt() }
                        val returnedArea = value?.let { (field(it, "areaId") as Number).toInt() }
                        val valueStatus = value?.let { (field(it, "status") as Number).toInt() }
                        val identityValid = returnedProp == binding.propertyId && returnedArea == 0
                        val scalar = if (code == 0 && value != null && identityValid && valueStatus == 0)
                            scalar(value, binding) else null
                        // ON_CHANGE timestamps describe physical changes, not getter freshness.
                        // A successful available read refreshes telemetry even for an older event.
                        // Both iPhone and native-gauge speed samples must represent a recent event.
                        val event = value?.let { (field(it, "timestamp") as? Number)?.toLong()?.div(1_000_000) }
                        val age = event?.let { SystemClock.elapsedRealtime() - it }
                        val validEvent = event != null && event > 0 && age != null && age >= 0 &&
                            ((binding.signal != VehicleSignal.SPEED_KPH && binding.signal != VehicleSignal.INDICATED_SPEED_KPH) || age <= 2000)
                        val decoded = if (validEvent) scalar?.let(binding.decode) else null
                        val reason = when {
                            code != 0 -> "result=$code"
                            !identityValid -> "identity mismatch returned=$returnedProp/$returnedArea"
                            valueStatus != 0 -> "value status=$valueStatus"
                            !validEvent -> "timestamp rejected"
                            scalar == null -> "payload unavailable or unsupported"
                            decoded == null -> "decode rejected"
                            else -> "accepted"
                        }
                        telemetry.accept(binding.signal, decoded, binding.propertyId, event)
                        if (decoded != null) confirmations[binding.propertyId] = SystemClock.elapsedRealtime()
                        diagnostics[binding.propertyId] = "$reason; result=$code status=$valueStatus eventAgeMs=$age; " +
                            (value?.let { rawDiagnostic(it, binding) } ?: "no value")
                    }
                    null
                }
                else -> null
            }
        }
        method.invoke(target, request, callback)
        if (!returned && !stopped.get()) {
            telemetry.accept(binding.signal, null, binding.propertyId)
            diagnostics[binding.propertyId] = "callback missing"
        }
    }
    private fun scalar(value: Any, binding: Fx11Signals.Binding): Number? {
        val property = binding.propertyId
        val raw = field(value, "value") ?: return null
        if (binding.paInteger) {
            if (property and 0x00ff0000 != 0x00700000) return null
            val bytes = (field(raw, "bytes") as? List<*>)?.map { it as? Byte ?: return null }?.toByteArray() ?: return null
            return Fx11DashboardSignals.paInteger(bytes)
        }
        val key = when (property and 0x00ff0000) {
            0x00200000, 0x00400000 -> "int32Values" // HIDL encodes BOOLEAN as one int32; the binding validates 0/1.
            0x00600000 -> "floatValues"
            else -> return null
        }
        val values = field(raw, key) as? List<*> ?: return null
        if (binding.allowFloatPayload && values.isEmpty()) {
            return (field(raw, "floatValues") as? List<*>)?.singleOrNull() as? Number
        }
        return values.singleOrNull() as? Number
    }
    private fun rawDiagnostic(value: Any, binding: Fx11Signals.Binding): String {
        val raw = field(value, "value") ?: return "payload missing"
        val ints = (field(raw, "int32Values") as? List<*>).orEmpty()
        val floats = (field(raw, "floatValues") as? List<*>).orEmpty()
        val bytes = (field(raw, "bytes") as? List<*>).orEmpty()
        val pa = if (binding.paInteger && bytes.size == 16) {
            val data = bytes.mapNotNull { it as? Byte }.toByteArray()
            if (data.size == 16) {
                val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                "; PA=[availability=${buffer.int},data=${buffer.int},format=${buffer.int},status=${buffer.int}]"
            } else ""
        } else ""
        return "int32=${ints.take(4)} float=${floats.take(4)} byteCount=${bytes.size}$pa"
    }
    override fun close() {
        stopped.set(true)
        active?.cancel(true)
        monitor.shutdownNow()
        telemetry.clear()
        status = "FX11 disconnected"
        vehicle = null
    }
    private fun field(obj: Any, name: String): Any? = obj.javaClass.getField(name).get(obj)
    companion object {
        /** A stuck Binder call may ignore interrupts. Never spawn unbounded replacement readers. */
        internal fun awaitProgress(task: Future<*>, progress: AtomicLong, timeoutMillis: Long = 3000) {
            var observed = progress.get()
            while (true) {
                try { task.get(timeoutMillis, TimeUnit.MILLISECONDS); return }
                catch (error: TimeoutException) {
                    val current = progress.get()
                    if (current == observed) throw error
                    observed = current
                }
            }
        }
        private val READERS = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(2),
            ThreadFactory { r -> Thread(r, "fx11-ultra-read").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    }
}
