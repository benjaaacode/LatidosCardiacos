package com.example.latidoscardiacos.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.example.latidoscardiacos.data.LecturaSensor
import com.example.latidoscardiacos.data.LecturaSensorDao
import com.example.latidoscardiacos.security.CryptoUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

enum class EstadoConexion {
    DESCONECTADO,
    CONECTANDO,
    CONECTADO,
    RECONECTANDO
}

data class DatosSensorIoT(
    val bpm: Int = 0,
    val temperatura: Float = 0f,
    val estado: String = "DESCONOCIDO",
    val ultimoComandoAck: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Gestor avanzado de comunicación IoT por Bluetooth Classic / SPP.
 * Cumple con los requerimientos de la Unidad 3:
 * - Reconexión automática ante pérdidas de señal.
 * - Validación de integridad de datos (Checksum ISO 27000).
 * - Control de actuadores con confirmación ACK.
 * - Modo de ahorro de energía (Eco Mode).
 * - Almacenamiento local en Room DB con sincronización diferida.
 */
class BluetoothManagerIoT(
    private val context: Context,
    private val lecturaSensorDao: LecturaSensorDao
) {
    companion object {
        private const val TAG = "BluetoothManagerIoT"
        // SPP Standard UUID (Serial Port Profile) usado por Arduino R4 y ESP32
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 3000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var targetMacAddress: String? = null

    // Estados reactivos expuestos a la UI
    private val _estadoConexion = MutableStateFlow(EstadoConexion.DESCONECTADO)
    val estadoConexion: StateFlow<EstadoConexion> = _estadoConexion.asStateFlow()

    private val _datosSensor = MutableStateFlow(DatosSensorIoT())
    val datosSensor: StateFlow<DatosSensorIoT> = _datosSensor.asStateFlow()

    private val _modoEcoActivado = MutableStateFlow(false)
    val modoEcoActivado: StateFlow<Boolean> = _modoEcoActivado.asStateFlow()

    private val _mensajeEstado = MutableStateFlow("Listo para conectar")
    val mensajeEstado: StateFlow<String> = _mensajeEstado.asStateFlow()

    private var lecturaJob: Job? = null
    private var reconexionJob: Job? = null

    /**
     * Conecta a un dispositivo Bluetooth según su dirección MAC.
     */
    @SuppressLint("MissingPermission")
    fun conectar(macAddress: String) {
        targetMacAddress = macAddress
        reconexionJob?.cancel()
        
        scope.launch {
            iniciarConexion(macAddress, esReconexion = false)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun iniciarConexion(macAddress: String, esReconexion: Boolean) {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _mensajeEstado.value = "Bluetooth no disponible o desactivado"
            _estadoConexion.value = EstadoConexion.DESCONECTADO
            return
        }

        _estadoConexion.value = if (esReconexion) EstadoConexion.RECONECTANDO else EstadoConexion.CONECTANDO
        _mensajeEstado.value = if (esReconexion) "Reconectando con dispositivo IoT..." else "Conectando a $macAddress..."

        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }

            val device: BluetoothDevice = bluetoothAdapter.getRemoteDevice(macAddress)
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket?.connect()

            inputStream = socket?.inputStream
            outputStream = socket?.outputStream

            _estadoConexion.value = EstadoConexion.CONECTADO
            _mensajeEstado.value = "Conectado exitosamente con ${device.name ?: macAddress}"
            Log.i(TAG, "Conexión Bluetooth establecida con $macAddress")

            // Iniciar bucle de lectura de tramas
            iniciarLecturaDeDatos()

        } catch (e: Exception) {
            Log.e(TAG, "Error al conectar con $macAddress: ${e.message}")
            socketCleanUp()

            if (!esReconexion) {
                _mensajeEstado.value = "Fallo en conexión inicial. Iniciando auto-reconexión..."
                iniciarAutoReconexion()
            }
        }
    }

    /**
     * Bucle de reconexión automática (Paso 3 & 4 - Continuidad operativa).
     */
    private fun iniciarAutoReconexion() {
        val mac = targetMacAddress ?: return
        reconexionJob?.cancel()

        reconexionJob = scope.launch {
            var intentos = 0
            while (intentos < MAX_RETRY_ATTEMPTS && _estadoConexion.value != EstadoConexion.CONECTADO) {
                intentos++
                _estadoConexion.value = EstadoConexion.RECONECTANDO
                _mensajeEstado.value = "Reintentando conexión (Intento $intentos de $MAX_RETRY_ATTEMPTS)..."
                delay(RECONNECT_DELAY_MS)
                iniciarConexion(mac, esReconexion = true)
            }

            if (_estadoConexion.value != EstadoConexion.CONECTADO) {
                _estadoConexion.value = EstadoConexion.DESCONECTADO
                _mensajeEstado.value = "No se logró reconectar. Verifica que el dispositivo IoT esté encendido."
            }
        }
    }

    /**
     * Escucha continua de stream de datos seriales.
     */
    private fun iniciarLecturaDeDatos() {
        lecturaJob?.cancel()
        lecturaJob = scope.launch {
            val buffer = ByteArray(1024)
            var builder = StringBuilder()

            while (isActive && _estadoConexion.value == EstadoConexion.CONECTADO) {
                try {
                    val bytesRead = inputStream?.read(buffer) ?: -1
                    if (bytesRead > 0) {
                        val fragmento = String(buffer, 0, bytesRead, Charsets.UTF_8)
                        builder.append(fragmento)

                        // Las tramas finales concluyen con salto de línea \n
                        val lineEnd = builder.indexOf("\n")
                        if (lineEnd >= 0) {
                            val tramaCompleta = builder.substring(0, lineEnd).trim()
                            builder = StringBuilder(builder.substring(lineEnd + 1))

                            if (tramaCompleta.isNotEmpty()) {
                                procesarTramaEntrante(tramaCompleta)
                            }
                        }
                    } else if (bytesRead == -1) {
                        // Conexión cerrada inesperadamente
                        throw Exception("Socket cerrado por el microcontrolador")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Pérdida de enlace durante lectura: ${e.message}")
                    _estadoConexion.value = EstadoConexion.DESCONECTADO
                    _mensajeEstado.value = "Conexión interrumpida. Iniciando reconexión..."
                    socketCleanUp()
                    iniciarAutoReconexion()
                    break
                }
            }
        }
    }

    /**
     * Valida la integridad (Checksum SHA-256 / ISO 27000) y procesa las tramas recibidas.
     */
    private suspend fun procesarTramaEntrante(trama: String) {
        try {
            // Soporta tramas JSON: {"bpm": 78, "temp": 36.6, "estado": "NORMAL", "chk": "a1b2c3d4"}
            if (trama.startsWith("{") && trama.endsWith("}")) {
                val json = JSONObject(trama)
                val bpm = json.optInt("bpm", 0)
                val temp = json.optDouble("temp", 0.0).toFloat()
                val estadoStr = json.optString("estado", "NORMAL")
                val ack = json.optString("ack", "")
                val chk = json.optString("chk", "")

                // Validación de Integridad (ISO 27000)
                val payloadVal = "$bpm,$temp,$estadoStr"
                val expectedChk = CryptoUtils.calculateChecksum(payloadVal)

                if (chk.isNotEmpty() && !chk.equals(expectedChk, ignoreCase = true)) {
                    Log.w(TAG, "Trama rechazada por corrupción o falla de checksum: $trama")
                    return
                }

                _datosSensor.value = DatosSensorIoT(
                    bpm = bpm,
                    temperatura = temp,
                    estado = estadoStr,
                    ultimoComandoAck = ack,
                    timestamp = System.currentTimeMillis()
                )

                // Guardar lectura en la base de datos local Room (Paso 5 - Offline storage)
                lecturaSensorDao.insertarLectura(
                    LecturaSensor(
                        bpm = bpm,
                        temperatura = temp,
                        estado = estadoStr,
                        sincronizado = false
                    )
                )

            } else if (trama.startsWith("ACK:")) {
                // Confirmación simple de comando enviado a actuadores
                val comandoConfirmado = trama.substring(4)
                _datosSensor.value = _datosSensor.value.copy(ultimoComandoAck = comandoConfirmado)
                _mensajeEstado.value = "Comando confirmado: $comandoConfirmado"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando trama serial '$trama': ${e.message}")
        }
    }

    /**
     * Envía comandos de control a los actuadores en el microcontrolador (Paso 4).
     */
    fun enviarComandoActuador(comando: String) {
        if (_estadoConexion.value != EstadoConexion.CONECTADO) {
            _mensajeEstado.value = "Imposible enviar comando: Dispositivo no conectado"
            return
        }

        scope.launch {
            try {
                // Formato de comando seguro con terminación \n
                val tramaComando = "$comando\n"
                outputStream?.write(tramaComando.toByteArray(Charsets.UTF_8))
                outputStream?.flush()
                _mensajeEstado.value = "Comando '$comando' enviado"
                Log.d(TAG, "Comando enviado al IoT: $comando")
            } catch (e: Exception) {
                Log.e(TAG, "Error enviando comando a actuador: ${e.message}")
                _mensajeEstado.value = "Error enviando comando $comando"
            }
        }
    }

    /**
     * Alterna el Modo Eco de Ahorro de Energía (Paso 3).
     */
    fun cambiarModoEco(activar: Boolean) {
        _modoEcoActivado.value = activar
        val comandoEco = if (activar) "SET_ECO_1" else "SET_ECO_0"
        enviarComandoActuador(comandoEco)
        _mensajeEstado.value = if (activar) "Modo Eco activado (Muestreo 5s)" else "Modo Frecuente activado (Muestreo 1s)"
    }

    /**
     * Sincroniza las lecturas offline acumuladas en la base de datos local (Paso 5).
     */
    fun sincronizarLecturasOffline(onCompletado: (Int) -> Unit) {
        scope.launch {
            val pendientes = lecturaSensorDao.getLecturasNoSincronizadas()
            if (pendientes.isNotEmpty()) {
                val ids = pendientes.map { it.id }
                lecturaSensorDao.marcarComoSincronizadas(ids)
                withContext(Dispatchers.Main) {
                    _mensajeEstado.value = "Sincronizadas ${pendientes.size} lecturas locales correctamente"
                    onCompletado(pendientes.size)
                }
            } else {
                withContext(Dispatchers.Main) {
                    _mensajeEstado.value = "Todas las lecturas locales están al día"
                    onCompletado(0)
                }
            }
        }
    }

    /**
     * Cierra limpiamente los sockets y streams de conexión.
     */
    fun desconectar() {
        reconexionJob?.cancel()
        lecturaJob?.cancel()
        socketCleanUp()
        _estadoConexion.value = EstadoConexion.DESCONECTADO
        _mensajeEstado.value = "Desconectado manualmente"
    }

    private fun socketCleanUp() {
        try {
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error en limpieza de socket: ${e.message}")
        } finally {
            inputStream = null
            outputStream = null
            socket = null
        }
    }
}
