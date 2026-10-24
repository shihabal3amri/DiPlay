package com.shilapi.xcertplay.media

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MicrophoneInputDeviceTest {
    @Test fun usesStableIdentityAndAppliesOnlyTheSelectedInput() {
        val manager = mock(AudioManager::class.java)
        val device = mock(AudioDeviceInfo::class.java)
        val recorder = mock(AudioRecord::class.java)
        `when`(device.isSource).thenReturn(true)
        `when`(device.type).thenReturn(15)
        `when`(device.address).thenReturn("input")
        `when`(device.productName).thenReturn("test microphone")
        `when`(manager.getDevices(AudioManager.GET_DEVICES_INPUTS)).thenReturn(arrayOf(device))
        `when`(recorder.setPreferredDevice(device)).thenReturn(true)
        MicrophoneInput.applyDevice(recorder, manager, "15|input|test microphone")
        verify(recorder).setPreferredDevice(device)
        `when`(device.id).thenReturn(123)
        assertEquals("15|input|test microphone", MicrophoneInput.key(device))
        `when`(recorder.setPreferredDevice(device)).thenReturn(false)
        try {
            MicrophoneInput.applyDevice(recorder, manager, MicrophoneInput.key(device))
            fail("Rejected input must not be reported as selected")
        } catch (_: IllegalStateException) { }
    }

    @Test fun automaticInputLeavesSystemRoutingUntouched() {
        val recorder = mock(AudioRecord::class.java)
        MicrophoneInput.applyDevice(recorder, null, "")
        verifyNoInteractions(recorder)
    }
}
