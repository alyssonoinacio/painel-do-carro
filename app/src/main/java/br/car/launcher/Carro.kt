package br.car.launcher

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Som do carro: o tablet manda o áudio pelo Bluetooth (A2DP) do rádio do HB20. */
object SomCarro {
    /** Nome do rádio quando o áudio sai pelo Bluetooth; null quando sai pelo próprio tablet. */
    fun conectado(c: Context): String? {
        val a = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 23) {
            return a.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                ?.let { it.productName?.toString()?.ifBlank { null } ?: "Bluetooth" }
        }
        @Suppress("DEPRECATION") return if (a.isBluetoothA2dpOn) "Bluetooth" else null
    }
}
