// 本次新增按键过滤健康判断与导航调音写后异常处理。
package com.shilapi.xcertplay

/** 低频检查；未知状态清除连续故障计数，避免把 IPC 或生命周期间隙当成确定故障。 */
internal class WheelFilterHealthPolicy {
    private var nextInspection = 0L
    private var failures = 0
    fun shouldInspect(now: Long): Boolean {
        if (now < nextInspection) return false
        nextInspection = now + 5_000L
        return true
    }
    fun observed(healthy: Boolean?): Boolean {
        if (healthy != false) { failures = 0; return false }
        failures++
        return failures == 2
    }
}

/** 写入一旦尝试就接管这一按键，避免厂商写后抛错或读回滞后导致原车再调一次。 */
internal object NavigationWheelAudioAdjustment {
    fun adjust(delta: Int, eligible: () -> Boolean, minimum: () -> Int, maximum: () -> Int,
        current: () -> Int, write: (Int) -> Unit, readback: () -> Int,
        failed: (Exception) -> Unit = {}): Boolean {
        var attempted = false
        return try {
            if (!eligible()) return false
            val target = navigationWheelTarget(current(), minimum(), maximum(), delta) ?: return false
            if (!eligible()) return false
            attempted = true
            write(target)
            readback()
            true
        } catch (error: Exception) { failed(error); attempted }
    }
}
