package com.shilapi.xcertplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.*

/** One channel's subscriptions. Other channels/phones never inherit subscriptions or cached values. */
class VehicleCafState(private val profile: UltraVehicleProfile, private val source: UltraVehicleSource) {
    private val subscriptions = linkedSetOf<Long>()
    private val last = linkedMapOf<Long, Any?>()
    fun handle(reader: CafMessageReader): CafRcsFrame {
        val id = reader.pluginId
        val tx = reader.requireTransactionId()
        require(id == profile.pluginId)
        fun ids() = reader.valuesList().map { (it as? Number)?.toLong() ?: error("Invalid IID") }
        fun values(ids: Collection<Long>): Pair<Map<Long, Any?>, Map<Long, Long>> {
            val snapshot = source.snapshot()
            val values = linkedMapOf<Long, Any?>(); val errors = linkedMapOf<Long, Long>()
            for (iid in ids) {
                val binding = profile.bindings[iid]
                if (binding == null) errors[iid] = profile.unsupportedError
                else {
                    val value = binding.value(snapshot[binding.signal])
                    values[iid] = value
                    if (value == null) errors[iid] = profile.unavailableError
                }
            }
            return values to errors
        }
        return when (reader.command) {
            CafCommand.CONFIG_REQUEST -> CarAccessoryMessages.configResponse(id, tx, profile.config)
            CafCommand.READ_REQUEST -> values(ids()).let { CarAccessoryMessages.readResponse(id, tx, it.first, it.second) }
            CafCommand.REGISTER_REQUEST -> {
                val requested = if (reader.values() == "*") profile.bindings.keys.toList() else ids()
                subscriptions += requested.filter { it in profile.bindings }
                values(requested).let {
                    last.putAll(it.first)
                    CarAccessoryMessages.registerResponse(id, tx, it.first, it.second)
                }
            }
            CafCommand.UNREGISTER_REQUEST -> {
                ids().forEach { subscriptions.remove(it); last.remove(it) }
                CarAccessoryMessages.unregisterResponse(id, tx)
            }
            CafCommand.WRITE_REQUEST -> CarAccessoryMessages.writeResponse(id, tx,
                reader.valuesMap().keys.associateWith { profile.unsupportedError })
            CafCommand.CONTROL_REQUEST -> CarAccessoryMessages.controlResponse(id, tx, emptyMap(),
                reader.valuesMap().keys.associateWith { profile.unsupportedError })
            else -> CarAccessoryMessages.generalError(id, profile.unsupportedError, tx)
        }
    }
    fun updates(): CafRcsFrame? {
        val snapshot = source.snapshot()
        val changed = linkedMapOf<Long, Any?>()
        for (iid in subscriptions) {
            val b = profile.bindings.getValue(iid)
            val current = b.value(snapshot[b.signal])
            if (!last.containsKey(iid) || last[iid] != current) { changed[iid] = current; last[iid] = current }
        }
        return changed.takeIf { it.isNotEmpty() }?.let { CarAccessoryMessages.updateNotify(profile.pluginId, it) }
    }
    fun clear() { subscriptions.clear(); last.clear() }
}
