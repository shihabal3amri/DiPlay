package com.shilapi.xcertplay

import android.content.Context
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** No OEM classes packaged, no menu setters, no simulated button presses. */
internal class Fx11DimMenuReflectionBridge(private val context: Context) : Fx11DimMenuBridge {
    private val lock = Any()
    private var ended = false
    private var connected = false
    private var epoch = 0L
    private lateinit var sink: Fx11DimMenuBridge.Listener
    private lateinit var dimType: Class<*>
    private lateinit var menuType: Class<*>
    private lateinit var callbackType: Class<*>
    private var connectType: Class<*>? = null
    private var dim: Any? = null
    private var menu: Any? = null
    private var menuCallback: Any? = null
    private var watcherAttempted = false

    override fun connect(listener: Fx11DimMenuBridge.Listener) {
        sink = listener
        dimType = Class.forName("${API}DimInteraction")
        menuType = Class.forName("${API}IDimMenuInteraction")
        callbackType = Class.forName("${API}IDimMenuInteraction\$IDimMenuInteractionCallback")
        dim = invoke(dimType.getMethod("create", Context::class.java), null, context)
            ?: error("DimInteraction unavailable")
        connectType = try { Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable") }
            catch (_: ClassNotFoundException) { null }
        val type = connectType
        if (type == null || !type.isInstance(dim)) {
            connectType = null
            connectionChanged(true)
            return
        }
        val watcherType = Class.forName("com.ecarx.xui.adaptapi.binder.IConnectable\$IConnectWatcher")
        val watcher = proxy(watcherType) { name, _ ->
            when (name) {
                "onConnected" -> connectionChanged(true)
                "onDisConnected" -> connectionChanged(false)
            }
        }
        watcherAttempted = true
        invoke(type.getMethod("registerConnectWatcher", watcherType), dim, watcher)
    }

    private fun connectionChanged(value: Boolean) = synchronized(lock) {
        if (ended) return@synchronized
        connected = value; epoch++
        sink.onConnection(value)
    }

    override fun subscribe(): Boolean {
        val token = synchronized(lock) { if (ended || !connected) return false else epoch }
        cleanupMenu()
        val currentMenu = invoke(dimType.getMethod("getDimMenuInteraction"), dim) ?: return false
        val callback = proxy(callbackType) { name, args -> synchronized(lock) {
            if (ended || !connected || epoch != token) return@synchronized
            when (name) {
                "onTabChanged" -> (args?.singleOrNull() as? Number)?.let { sink.onTab(it.toInt()) }
                "onControlCenterStateChanged" -> (args?.singleOrNull() as? Number)?.let { sink.onControlCenter(it.toInt()) }
                "onVolumeBarStatusChanged" -> (args?.singleOrNull() as? Boolean)?.let(sink::onVolumeBar)
                "onEngineStatusChanged" -> (args?.singleOrNull() as? Boolean)?.let(sink::onEngineOff)
            }
        } }
        menu = currentMenu; menuCallback = callback
        invoke(menuType.getMethod("registerDimMenuInteractionCallback", callbackType), currentMenu, callback)
        return synchronized(lock) { !ended && connected && epoch == token }
    }

    private fun cleanupMenu() {
        val previousMenu = menu
        val previousCallback = menuCallback
        menu = null; menuCallback = null
        if (previousMenu != null && previousCallback != null) {
            invoke(menuType.getMethod("unregisterDimMenuInteractionCallback", callbackType), previousMenu, previousCallback)
        }
    }

    override fun close() {
        synchronized(lock) { ended = true; connected = false; epoch++ }
        try { cleanupMenu() } finally {
            val type = connectType
            try {
                if (watcherAttempted && type != null) invoke(type.getMethod("unregisterConnectWatcher"), dim)
            } finally {
                if (type != null && dim != null) invoke(type.getMethod("disconnect"), dim)
            }
        }
    }

    private fun proxy(type: Class<*>, event: (String, Array<out Any?>?) -> Unit): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                "toString" -> "DiPlay read-only DIM menu callback"
                else -> { event(method.name, args); null }
            }
        }

    private fun invoke(method: Method, receiver: Any?, vararg args: Any?): Any? = try {
        method.invoke(receiver, *args)
    } catch (failure: InvocationTargetException) { throw failure.targetException }

    companion object { private const val API = "com.ecarx.xui.adaptapi.diminteraction." }
}
