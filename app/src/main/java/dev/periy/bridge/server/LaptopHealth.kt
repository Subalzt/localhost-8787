package dev.periy.bridge.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable data class HealthCpu(val use: Double = 0.0, val name: String = "", val cores: Int = 0)
@Serializable data class HealthProc(val name: String = "", val cpu: Double = 0.0, val mem: Long = 0)
@Serializable data class HealthMem(val used: Long = 0, val total: Long = 0)
@Serializable data class HealthGpu(
    val name: String = "", val use: Double? = null, val memUsed: Double? = null, val memTotal: Double? = null,
    val temp: Double? = null, val power: Double? = null,
)
@Serializable data class HealthTemp(val name: String = "", val c: Double = 0.0)
@Serializable data class HealthBattery(val percent: Int = 0, val plugged: Boolean = false, val charging: Boolean = false, val minutes: Int = -1)
@Serializable data class HealthDisk(val name: String = "", val used: Long = 0, val total: Long = 0)

/** How a laptop is doing, as its helper measured it just now (blazeit-pc.bat Health(), blazeit-helper.py health()). */
@Serializable
data class LaptopHealthDto(
    val name: String = "", val os: String = "", val uptime: Long = 0,
    val cpu: HealthCpu? = null, val top: List<HealthProc> = emptyList(), val mem: HealthMem? = null,
    val gpus: List<HealthGpu> = emptyList(), val temps: List<HealthTemp> = emptyList(),
    val battery: HealthBattery? = null, val disks: List<HealthDisk> = emptyList(), val error: String = "",
)

/**
 * A laptop's health, from the phone or a page: the phone asks its helper through the event stream
 * it keeps open ("health ID") and the helper posts a snapshot back to `/api/laptop/health/answer`,
 * the way the laptop's files work (LaptopFiles). Nothing on the laptop listens.
 */
object LaptopHealth {
    private val waits = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** The helper's JSON as it sent it, or null when it did not answer in time. */
    suspend fun raw(laptopId: String): String? {
        if (laptopId !in Control.online()) return null
        val id = UUID.randomUUID().toString()
        val wait = CompletableDeferred<String>()
        waits[id] = wait
        EventBus.emitTo(laptopId, "health", id)
        return try { withTimeoutOrNull(12_000) { wait.await() } } finally { waits.remove(id) }
    }

    suspend fun ask(laptopId: String): LaptopHealthDto {
        val r = raw(laptopId) ?: return LaptopHealthDto(error = if (laptopId in Control.online()) "The laptop did not answer" else "The laptop helper is not running")
        return runCatching { json.decodeFromString(LaptopHealthDto.serializer(), r) }.getOrElse { LaptopHealthDto(error = "The laptop's answer made no sense") }
    }

    fun answer(id: String, body: String) { waits.remove(id)?.complete(body) }
}
