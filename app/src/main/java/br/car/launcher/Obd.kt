package br.car.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Uma leitura do motor. null = o carro não informou (ex.: o HB20 costuma não informar o nível do tanque). */
class Leitura(val velocidade: Int? = null, val rpm: Int? = null, val motor: Int? = null,
              val tensao: Float? = null, val combustivel: Int? = null)

/**
 * Adaptador OBD2 Bluetooth (ELM327) na tomada de diagnóstico do HB20. Conecta pelo perfil serial (SPP),
 * lê velocidade, giro, temperatura do motor e tensão da bateria a cada ~1 s e lê os códigos de falha
 * sob pedido. Só leitura: nunca apaga falhas nem escreve na central.
 */
object Obd {
    private val SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val main = Handler(Looper.getMainLooper())
    private val usuarios = mutableSetOf<String>()
    private val fila = LinkedBlockingQueue<Pair<String, (String) -> Unit>>()
    private var trabalho: Thread? = null
    private var sock: BluetoothSocket? = null
    private var can = true

    @Volatile var conectado = false; private set
    @Volatile var leitura = Leitura(); private set
    @Volatile var status = "desligado"; private set

    /** Ouvintes na thread principal (tela e serviço de voz). */
    val ouvintes = mutableSetOf<(Leitura?) -> Unit>()

    fun configurado(c: Context) = c.prefs().getString("obd_mac", "").orEmpty().isNotBlank()

    fun permitido(c: Context) = Build.VERSION.SDK_INT < 31 ||
        c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun usar(c: Context, quem: String) {
        synchronized(usuarios) { usuarios.add(quem) }
        if (!configurado(c) || !permitido(c)) return
        synchronized(this) {
            if (trabalho?.isAlive == true) return
            val app = c.applicationContext
            trabalho = thread(name = "obd") { laco(app) }
        }
    }

    fun soltar(quem: String) { synchronized(usuarios) { usuarios.remove(quem) } }

    /** Troca de adaptador: derruba a conexão; o laço reconecta no novo. */
    fun reiniciar(c: Context) { fechar(); val quem = synchronized(usuarios) { usuarios.toList() }; quem.forEach { usar(c, it) } }

    private fun ativo() = synchronized(usuarios) { usuarios.isNotEmpty() }

    @SuppressLint("MissingPermission")
    private fun laco(c: Context) {
        var espera = 3000L
        var volta = 0
        while (ativo()) {
            val mac = c.prefs().getString("obd_mac", "").orEmpty()
            @Suppress("DEPRECATION") val ad = BluetoothAdapter.getDefaultAdapter()
            if (mac.isBlank() || ad == null || !permitido(c)) { status = "sem adaptador escolhido"; break }
            if (!ad.isEnabled) { status = "Bluetooth do tablet desligado"; publicar(null); dormir(5000); continue }
            try {
                status = "conectando…"
                val s = abrir(ad.getRemoteDevice(mac)); sock = s
                val e = s.inputStream; val o = s.outputStream
                cmd(e, o, "ATZ", 4000)
                for (x in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) cmd(e, o, x)
                cmd(e, o, "0100", 10000) // força a detecção do protocolo do carro
                can = cmd(e, o, "ATDPN").trim().trimStart('A').let { it in setOf("6", "7", "8", "9") }
                conectado = true; espera = 3000; status = "conectado"
                while (ativo()) {
                    while (true) {
                        val (q, cb) = fila.poll() ?: break
                        cb(cmd(e, o, q, 8000))
                    }
                    val l = Leitura(
                        velocidade = b1(cmd(e, o, "010D"), "0D"),
                        rpm = b2(cmd(e, o, "010C"), "0C")?.let { it / 4 },
                        motor = b1(cmd(e, o, "0105"), "05")?.let { it - 40 },
                        tensao = cmd(e, o, "ATRV").uppercase().replace("V", "").trim().toFloatOrNull(),
                        combustivel = if (volta % 15 == 0) b1(cmd(e, o, "012F"), "2F")?.let { it * 100 / 255 } else leitura.combustivel,
                    )
                    volta++
                    leitura = l; publicar(l)
                    dormir(1000)
                }
            } catch (ex: Exception) {
                status = "sem conexão com o adaptador (carro desligado ou fora de alcance)"
            }
            conectado = false; publicar(null); fechar()
            while (true) { val (_, cb) = fila.poll() ?: break; cb("") }
            if (ativo()) { dormir(espera); espera = minOf(espera * 2, 30_000L) }
        }
        fechar(); conectado = false
        if (status == "conectado") status = "desligado"
        synchronized(this) { trabalho = null }
    }

    @SuppressLint("MissingPermission")
    private fun abrir(d: BluetoothDevice): BluetoothSocket {
        try { return d.createRfcommSocketToServiceRecord(SPP).also { it.connect() } } catch (e: IOException) {}
        try { return d.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() } } catch (e: IOException) {}
        // muitos ELM327 baratos só respondem no canal 1
        val m = d.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return (m.invoke(d, 1) as BluetoothSocket).also { it.connect() }
    }

    private fun cmd(e: InputStream, o: OutputStream, c: String, limite: Long = 2500): String {
        o.write((c + "\r").toByteArray()); o.flush()
        val sb = StringBuilder()
        val fim = System.currentTimeMillis() + limite
        while (System.currentTimeMillis() < fim) {
            if (e.available() > 0) {
                val ch = e.read()
                if (ch < 0) throw IOException("fechado")
                if (ch == '>'.code) return sb.toString().replace("SEARCHING...", "").replace('\r', '\n').trim()
                sb.append(ch.toChar())
            } else Thread.sleep(10)
        }
        if (sb.isEmpty()) throw IOException("sem resposta a $c")
        return sb.toString().trim()
    }

    private fun hex(r: String) = r.lines().joinToString("") { it.substringAfter(':').replace(" ", "") }.uppercase()
    private fun b1(r: String, pid: String): Int? { val h = hex(r); val i = h.indexOf("41$pid"); return if (i < 0 || h.length < i + 6) null else h.substring(i + 4, i + 6).toIntOrNull(16) }
    private fun b2(r: String, pid: String): Int? { val h = hex(r); val i = h.indexOf("41$pid"); return if (i < 0 || h.length < i + 8) null else h.substring(i + 4, i + 8).toIntOrNull(16) }

    /** Lê os códigos de falha gravados (modo 03). null = adaptador não conectado. */
    fun falhas(cb: (List<String>?) -> Unit) {
        if (!conectado) { cb(null); return }
        fila.put("03" to { r -> val l = if (r.isBlank()) null else decodificar(r); main.post { cb(l) } })
    }

    private fun decodificar(r: String): List<String> {
        if (r.uppercase().contains("NO DATA")) return emptyList()
        var h = hex(r)
        val i = h.indexOf("43"); if (i < 0) return emptyList()
        h = h.substring(i + 2)
        if (can && h.length >= 2) h = h.substring(2) // no CAN vem a quantidade de códigos antes
        val letras = "PCBU"
        return h.chunked(4).filter { it.length == 4 && it != "0000" }.mapNotNull { g ->
            val n = g[0].digitToIntOrNull(16) ?: return@mapNotNull null
            "${letras[n shr 2]}${n and 3}${g.substring(1)}"
        }.distinct()
    }

    private fun publicar(l: Leitura?) = main.post { ouvintes.toList().forEach { it(l) } }
    private fun dormir(ms: Long) = try { Thread.sleep(ms) } catch (e: InterruptedException) {}
    private fun fechar() { try { sock?.close() } catch (e: Exception) {}; sock = null }
}
