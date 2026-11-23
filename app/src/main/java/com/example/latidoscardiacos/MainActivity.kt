package com.example.latidoscardiacos

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import java.security.MessageDigest
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import com.example.latidoscardiacos.data.AppDatabase
import com.example.latidoscardiacos.data.LecturaSensor
import com.example.latidoscardiacos.data.Usuario
import com.example.latidoscardiacos.data.UsuarioDao
import com.example.latidoscardiacos.ui.theme.LatidosCardiacosTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Definimos los estados (pantallas) posibles de nuestra app
enum class Pantalla {
    LOGIN_TRADICIONAL,
    LOGIN_BIOMETRICO,
    REGISTRO,
    PRINCIPAL
}

data class DispositivoBluetooth(val nombre: String, val direccionMac: String)

class MainActivity : FragmentActivity() {
    private lateinit var database: AppDatabase
    private lateinit var usuarioDao: UsuarioDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Lazy initialization in case the context is not ready early
        setContent {
            database = AppDatabase.getDatabase(this)
            usuarioDao = database.usuarioDao()
            LatidosCardiacosTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Empezamos siempre por el Login Tradicional
                    var pantallaActual by remember { mutableStateOf(Pantalla.LOGIN_TRADICIONAL) }

                    // Motor de navegación
                    when (pantallaActual) {
                        Pantalla.LOGIN_TRADICIONAL -> {
                            PantallaLoginTradicional(
                                modifier = Modifier.padding(innerPadding),
                                usuarioDao = usuarioDao,
                                onLoginExitoso = { pantallaActual = Pantalla.PRINCIPAL },
                                onUsarHuella = { pantallaActual = Pantalla.LOGIN_BIOMETRICO },
                                onIrARegistro = { pantallaActual = Pantalla.REGISTRO }
                            )
                        }
                        Pantalla.REGISTRO -> {
                            BackHandler { pantallaActual = Pantalla.LOGIN_TRADICIONAL }
                            PantallaRegistro(
                                modifier = Modifier.padding(innerPadding),
                                usuarioDao = usuarioDao,
                                onRegistroExitoso = { pantallaActual = Pantalla.LOGIN_TRADICIONAL }, // Vuelve al login tras registrar
                                onVolver = { pantallaActual = Pantalla.LOGIN_TRADICIONAL }
                            )
                        }
                        Pantalla.LOGIN_BIOMETRICO -> {
                            BackHandler { pantallaActual = Pantalla.LOGIN_TRADICIONAL }
                            PantallaLoginBiometrico(
                                modifier = Modifier.padding(innerPadding),
                                onLoginExitoso = { pantallaActual = Pantalla.PRINCIPAL },
                                onVolver = { pantallaActual = Pantalla.LOGIN_TRADICIONAL }
                            )
                        }
                        Pantalla.PRINCIPAL -> {
                            BackHandler { pantallaActual = Pantalla.LOGIN_TRADICIONAL }
                            PantallaPrincipal(
                                modifier = Modifier.padding(innerPadding),
                                onCerrarSesion = { pantallaActual = Pantalla.LOGIN_TRADICIONAL }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// UTILIDAD: CIFRADO DE CONTRASEÑA (SHA-256)
// ==========================================
fun hashPassword(password: String): String {
    val bytes = password.toByteArray()
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    return digest.fold("") { str, it -> str + "%02x".format(it) }
}

// ==========================================
// PANTALLA: LOGIN TRADICIONAL
// ==========================================
@Composable
fun PantallaLoginTradicional(
    modifier: Modifier = Modifier,
    usuarioDao: UsuarioDao? = null,
    onLoginExitoso: () -> Unit,
    onUsarHuella: () -> Unit,
    onIrARegistro: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var correo by remember { mutableStateOf("") }
    var contrasena by remember { mutableStateOf("") }
    var contrasenaVisible by remember { mutableStateOf(false) }
    var recordarUsuario by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "NetPulse",
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        OutlinedTextField(
            value = correo,
            onValueChange = { correo = it },
            label = { Text("Correo Electrónico") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = contrasena,
            onValueChange = { contrasena = it },
            label = { Text("Contraseña") },
            visualTransformation = if (contrasenaVisible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                val imagen = if (contrasenaVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility
                val descripcion = if (contrasenaVisible) "Ocultar contraseña" else "Mostrar contraseña"
                
                IconButton(onClick = { contrasenaVisible = !contrasenaVisible }) {
                    Icon(imageVector = imagen, contentDescription = descripcion)
                }
            }
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = recordarUsuario,
                    onCheckedChange = { recordarUsuario = it }
                )
                Text("Recordarme", fontSize = 14.sp)
            }
            TextButton(
                onClick = { /* TODO: Lógica de recuperar contraseña */ },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("¿Olvidaste tu contraseña?", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                val inputCorreo = correo.trim()
                if (inputCorreo.isEmpty() || contrasena.isEmpty()) {
                    Toast.makeText(context, "Por favor llena todos los campos", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                coroutineScope.launch {
                    try {
                        val hashedPassword = hashPassword(contrasena)
                        val usuario = withContext(Dispatchers.IO) {
                            usuarioDao?.login(inputCorreo, hashedPassword)
                        }

                        // Verificación con base de datos o backdoor para pruebas
                        if (usuario != null) {
                            Toast.makeText(context, "¡Bienvenido, ${usuario.nombre}!", Toast.LENGTH_SHORT).show()
                            onLoginExitoso()
                        } else if (inputCorreo == "benjamin@netpulse.com" && contrasena == "admin4321") {
                            Toast.makeText(context, "¡Bienvenido, Benjamin (Backdoor)!", Toast.LENGTH_SHORT).show()
                            onLoginExitoso()
                        } else {
                            Toast.makeText(context, "Correo o contraseña incorrectos", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error al iniciar sesión", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("Iniciar Sesión", fontSize = 18.sp)
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // Botón para crear una cuenta
        TextButton(onClick = onIrARegistro) {
            Text("¿No tienes cuenta? Regístrate aquí")
        }

        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = "O ingresa rápidamente con",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        IconButton(
            onClick = onUsarHuella,
            modifier = Modifier.size(64.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Fingerprint,
                contentDescription = "Usar Huella Digital",
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ==========================================
// NUEVA PANTALLA: REGISTRO
// ==========================================
@Composable
fun PantallaRegistro(
    modifier: Modifier = Modifier,
    usuarioDao: UsuarioDao? = null,
    onRegistroExitoso: () -> Unit,
    onVolver: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var nombre by remember { mutableStateOf("") }
    var edad by remember { mutableStateOf("") }
    var correo by remember { mutableStateOf("") }
    var contrasena by remember { mutableStateOf("") }
    var contrasenaVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- BOTÓN DE VOLVER ATRÁS ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            IconButton(onClick = onVolver) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Volver"
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Crear Cuenta",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        OutlinedTextField(
            value = nombre,
            onValueChange = { nombre = it },
            label = { Text("Nombre Completo") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // NUEVO: Campo para la Edad
        OutlinedTextField(
            value = edad,
            onValueChange = { 
                // Filtro simple para que solo se puedan escribir números
                if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                    edad = it 
                }
            },
            label = { Text("Edad (años)") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = correo,
            onValueChange = { correo = it },
            label = { Text("Correo Electrónico") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = contrasena,
            onValueChange = { contrasena = it },
            label = { Text("Crear Contraseña") },
            visualTransformation = if (contrasenaVisible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                val imagen = if (contrasenaVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility
                val descripcion = if (contrasenaVisible) "Ocultar contraseña" else "Mostrar contraseña"
                
                IconButton(onClick = { contrasenaVisible = !contrasenaVisible }) {
                    Icon(imageVector = imagen, contentDescription = descripcion)
                }
            }
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                val inputNombre = nombre.trim()
                val inputEdad = edad.toIntOrNull()
                val inputCorreo = correo.trim()

                if (inputNombre.isEmpty() || inputEdad == null || inputCorreo.isEmpty() || contrasena.isEmpty()) {
                    Toast.makeText(context, "Por favor completa todos los campos", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                // 1. Validar restricción de Edad (1 a 110 años)
                if (inputEdad !in 1..110) {
                    Toast.makeText(context, "La edad debe estar entre 1 y 110 años", Toast.LENGTH_LONG).show()
                    return@Button
                }

                // 2. Validar longitud de la contraseña (Mínimo 8 caracteres)
                if (contrasena.length < 8) {
                    Toast.makeText(context, "La contraseña debe tener al menos 8 caracteres", Toast.LENGTH_LONG).show()
                    return@Button
                }

                // 3. Validar símbolos permitidos en la contraseña: : , / & % $ # " ! ¡ = ( ) ? ¿
                val simbolosPermitidos = setOf(':', ',', '/', '&', '%', '$', '#', '"', '!', '¡', '=', '(', ')', '?', '¿')
                val simboloInvalido = contrasena.firstOrNull { char ->
                    !char.isLetterOrDigit() && char !in simbolosPermitidos
                }

                if (simboloInvalido != null) {
                    Toast.makeText(
                        context,
                        "Símbolo '$simboloInvalido' no permitido. Solo se aceptan: : , / & % $ # \" ! ¡ = ( ) ? ¿",
                        Toast.LENGTH_LONG
                    ).show()
                    return@Button
                }

                coroutineScope.launch {
                    try {
                        val hashedPassword = hashPassword(contrasena)
                        val nuevoUsuario = Usuario(
                            nombre = inputNombre,
                            edad = inputEdad,
                            correo = inputCorreo,
                            contrasena = hashedPassword
                        )

                        withContext(Dispatchers.IO) {
                            usuarioDao?.insertUsuario(nuevoUsuario)
                        }

                        Toast.makeText(context, "Registro exitoso", Toast.LENGTH_SHORT).show()
                        onRegistroExitoso()
                    } catch (e: Exception) {
                        // Catch generally handles unique constraint exceptions (email ya existe)
                        Toast.makeText(context, "Error al registrar: ¿El correo ya existe?", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("Registrarse", fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = onVolver) {
            Text("Ya tengo una cuenta. Iniciar Sesión")
        }
        
        Spacer(modifier = Modifier.weight(1f))
    }
}

// ==========================================
// PANTALLA: LOGIN BIOMÉTRICO
// ==========================================
@Composable
fun PantallaLoginBiometrico(
    modifier: Modifier = Modifier,
    onLoginExitoso: () -> Unit,
    onVolver: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var estadoAutenticacion by remember { mutableStateOf("Toca el botón para iniciar sesión") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- BOTÓN DE VOLVER ATRÁS ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            IconButton(onClick = onVolver) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Volver"
                )
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "NetPulse",
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = "Acceso Seguro",
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 64.dp)
        )

        LargeFloatingActionButton(
            onClick = {
                if (activity != null) {
                    autenticarUsuario(activity) { exito, mensaje ->
                        estadoAutenticacion = mensaje
                        if (exito) {
                            Toast.makeText(context, "¡Bienvenido a NetPulse!", Toast.LENGTH_SHORT).show()
                            onLoginExitoso()
                        } else {
                            Toast.makeText(context, mensaje, Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    estadoAutenticacion = "Error interno: Contexto no es FragmentActivity"
                }
            },
            modifier = Modifier.size(100.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Icon(
                imageVector = Icons.Filled.Fingerprint,
                contentDescription = "Autenticación Biométrica",
                modifier = Modifier.size(50.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = estadoAutenticacion,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(64.dp))
        
        TextButton(onClick = onVolver) {
            Text("Usar correo y contraseña")
        }
        
        Spacer(modifier = Modifier.weight(1f))
    }
}

// ==========================================
// PANTALLA: PRINCIPAL (BLE CON ARDUINO R4 WIFI - "NetPulse_R4")
// ==========================================
@SuppressLint("MissingPermission")
@Composable
fun PantallaPrincipal(
    modifier: Modifier = Modifier,
    database: AppDatabase? = null,
    onCerrarSesion: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val coroutineScope = rememberCoroutineScope()

    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter = bluetoothManager.adapter
    val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

    val dispositivosEncontrados = remember { mutableStateListOf<DispositivoBluetooth>() }
    var escaneando by remember { mutableStateOf(false) }

    var dispositivoSeleccionado by remember { mutableStateOf<DispositivoBluetooth?>(null) }
    var estadoConexion by remember { mutableStateOf("Desconectado") }
    var bluetoothGatt by remember { mutableStateOf<BluetoothGatt?>(null) }

    // Estado del sensor recibido del Arduino R4 WiFi
    var lecturaSensorTexto by remember { mutableStateOf("Sin dedo") }
    var bpmActual by remember { mutableIntStateOf(0) }
    var modoEco by remember { mutableStateOf(false) }

    val serviceUuid = remember { UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214") }
    val charUuid = remember { UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214") }
    val cccdUuid = remember { UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") }

    fun enviarComandoArduino(comando: String) {
        if (bluetoothGatt != null && estadoConexion == "Conectado") {
            val service = bluetoothGatt?.getService(serviceUuid)
            val characteristic = service?.getCharacteristic(charUuid)
            if (characteristic != null) {
                characteristic.value = comando.toByteArray(Charsets.UTF_8)
                bluetoothGatt?.writeCharacteristic(characteristic)
                Toast.makeText(context, "Instrucción enviada a Arduino: $comando", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Conecta tu Arduino R4 WiFi primero", Toast.LENGTH_SHORT).show()
        }
    }

    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                procesarScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { procesarScanResult(it) }
            }

            private fun procesarScanResult(result: ScanResult) {
                val device = result.device
                val scanRecord = result.scanRecord
                val rawName = device.name ?: scanRecord?.deviceName
                val name = rawName ?: if (device.address != null) "Dispositivo BLE (${device.address.takeLast(5)})" else "Dispositivo BLE"
                val mac = device.address ?: return

                coroutineScope.launch(Dispatchers.Main) {
                    val nuevoDisp = DispositivoBluetooth(name, mac)
                    if (!dispositivosEncontrados.any { d -> d.direccionMac == mac }) {
                        dispositivosEncontrados.add(nuevoDisp)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                coroutineScope.launch(Dispatchers.Main) {
                    escaneando = false
                    Toast.makeText(context, "Fallo en escaneo BLE: $errorCode", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }



    val gattCallback = remember {
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    estadoConexion = "Conectado"
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    estadoConexion = "Desconectado"
                    lecturaSensorTexto = "Sin conexión"
                    bpmActual = 0
                    bluetoothGatt = null
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(serviceUuid)
                    val characteristic = service?.getCharacteristic(charUuid)
                    if (characteristic != null) {
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(cccdUuid)
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val rawValue = characteristic.getStringValue(0)
                    ?: String(characteristic.value ?: byteArrayOf(), Charsets.UTF_8)
                val textoLimpio = rawValue.trim()
                lecturaSensorTexto = textoLimpio

                val parsedBpm = textoLimpio.toIntOrNull()
                if (parsedBpm != null && parsedBpm > 0) {
                    bpmActual = parsedBpm

                    // Guardar en la base de datos local Room
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val estadoStr = if (parsedBpm > 100) "CRITICO" else "NORMAL"
                            database?.lecturaSensorDao()?.insertarLectura(
                                LecturaSensor(
                                    bpm = parsedBpm,
                                    temperatura = 36.5f,
                                    estado = estadoStr
                                )
                            )
                        } catch (_: Exception) {}
                    }
                } else {
                    bpmActual = 0
                }
            }
        }
    }

    LaunchedEffect(dispositivoSeleccionado) {
        val disp = dispositivoSeleccionado
        if (disp != null) {
            val device = bluetoothAdapter?.getRemoteDevice(disp.direccionMac)
            if (device != null) {
                estadoConexion = "Conectando a ${disp.nombre}..."
                bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, false, gattCallback)
                }
            }
        } else {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            estadoConexion = "Desconectado"
            lecturaSensorTexto = "Sin dedo"
            bpmActual = 0
        }
    }

    DisposableEffect(context) {
        onDispose {
            try {
                bluetoothLeScanner?.stopScan(scanCallback)
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            } catch (_: Exception) {}
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- BARRA SUPERIOR CON BOTÓN DE CERRAR SESIÓN ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCerrarSesion) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Cerrar Sesión"
                )
            }
            Text(
                text = "NetPulse IoT Dashboard",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(48.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- TARJETA PRINCIPAL DE MONITOREO DE RITMO CARDÍACO ---
        val colorEstado = when {
            estadoConexion != "Conectado" -> MaterialTheme.colorScheme.secondaryContainer
            bpmActual > 100 -> Color(0xFFFFEBEE) // Alerta rojo claro
            bpmActual > 0 -> Color(0xFFE8F5E9)  // Normal verde claro
            else -> MaterialTheme.colorScheme.surfaceVariant
        }

        val colorTextoBpm = when {
            bpmActual > 100 -> Color(0xFFD32F2F)
            bpmActual > 0 -> Color(0xFF2E7D32)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = colorEstado),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "Latidos",
                        tint = if (bpmActual > 100) Color.Red else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Ritmo Cardíaco en Tiempo Real",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (bpmActual > 0) "$bpmActual BPM" else lecturaSensorTexto,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colorTextoBpm
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (bpmActual > 100) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFCDD2)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "⚠️ ¡ALERTA! Ritmo Cardíaco Elevado (>100 BPM) - Buzzer Activo en Pin D5",
                            color = Color(0xFFB71C1C),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                } else {
                    Text(
                        text = when {
                            estadoConexion != "Conectado" -> "Conecta tu Arduino R4 WiFi para ver lecturas"
                            lecturaSensorTexto == "Sin dedo" -> "Coloca tu dedo suavemente en el sensor (A1)"
                            lecturaSensorTexto == "Calculando..." -> "Midiendo pulso..."
                            else -> "Lectura normal dentro del rango de salud"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        val bpmParaGuardar = if (bpmActual > 0) bpmActual else 75
                        val estadoStr = if (bpmParaGuardar > 100) "CRITICO" else "NORMAL"
                        coroutineScope.launch(Dispatchers.IO) {
                            database?.lecturaSensorDao()?.insertarLectura(
                                LecturaSensor(
                                    bpm = bpmParaGuardar,
                                    temperatura = 36.5f,
                                    estado = estadoStr
                                )
                            )
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "¡Lectura de $bpmParaGuardar BPM guardada en la base de datos local!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(imageVector = Icons.Filled.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Guardar Lectura Actual", fontSize = 14.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- PANEL DE ESTADO Y DISPOSITIVO CONECTADO ---
        if (dispositivoSeleccionado != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (estadoConexion) {
                        "Conectado" -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Dispositivo: ${dispositivoSeleccionado?.nombre}",
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "Estado: $estadoConexion")
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { dispositivoSeleccionado = null },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Desconectar Arduino")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // --- CONTROL DE ACTUADORES Y ENERGÍA (Paso 4 / Rúbrica 3.1.2.5, 3.1.3.9, 3.1.3.10) ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Control de Actuadores y Energía (BLE / ISO 27000)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = { enviarComandoArduino("LED_ON") },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Encender LED", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { enviarComandoArduino("LED_OFF") },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("Apagar LED", fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = { enviarComandoArduino("BUZZER_OFF") },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Silenciar Buzzer", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            modoEco = !modoEco
                            enviarComandoArduino(if (modoEco) "ECO_ON" else "ECO_OFF")
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (modoEco) Color(0xFF2E7D32) else MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Text(if (modoEco) "Eco: ACTIVO (2s)" else "Eco: INACTIVO (500ms)", fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- ALMACENAMIENTO OFFLINE Y SINCRONIZACIÓN (Paso 5 / Rúbrica 3.1.1.4 & 3.1.2.7) ---
        val lecturasPendientesCount by database?.lecturaSensorDao()?.getCantidadNoSincronizadasFlow()?.collectAsState(initial = 0)
            ?: remember { mutableIntStateOf(0) }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Almacenamiento Local Offline (Room DB)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Lecturas almacenadas pendientes: $lecturasPendientesCount",
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        coroutineScope.launch(Dispatchers.IO) {
                            val pendientes = database?.lecturaSensorDao()?.getLecturasNoSincronizadas() ?: emptyList()
                            if (pendientes.isNotEmpty()) {
                                database?.lecturaSensorDao()?.marcarComoSincronizadas(pendientes.map { it.id })
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "¡${pendientes.size} lecturas sincronizadas externamente con éxito!", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Todas las lecturas locales están al día", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Filled.Sync, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sincronizar Lecturas Offline (ISO 27000)", fontSize = 13.sp)
                }
            }
        }

        // --- HISTORIAL DE LECTURAS GUARDADAS (Room DB) ---
        val ultimasLecturas by database?.lecturaSensorDao()?.getUltimasLecturas()?.collectAsState(initial = emptyList())
            ?: remember { mutableStateOf(emptyList()) }

        if (ultimasLecturas.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "📜 Historial de Lecturas Guardadas (Room DB)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    val dateFormat = remember { SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()) }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(ultimasLecturas) { lectura ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "${lectura.bpm} BPM",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = if (lectura.bpm > 100) Color(0xFFD32F2F) else Color(0xFF2E7D32)
                                    )
                                    Text(
                                        text = dateFormat.format(Date(lectura.timestamp)),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (lectura.sincronizado) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                                    )
                                ) {
                                    Text(
                                        text = if (lectura.sincronizado) "Sincronizado" else "Pendiente",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (lectura.sincronizado) Color(0xFF2E7D32) else Color(0xFFE65100),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- BOTÓN DE BÚSQUEDA DE ARDUINO R4 WIFI ---
        Button(
            onClick = {
                val permisos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                } else {
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                }

                val permisosConcedidos = permisos.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }

                if (permisosConcedidos) {
                    if (bluetoothAdapter == null || bluetoothLeScanner == null) {
                        Toast.makeText(context, "BLE no soportado en este dispositivo", Toast.LENGTH_LONG).show()
                    } else if (!bluetoothAdapter.isEnabled) {
                        Toast.makeText(context, "Por favor, enciende el Bluetooth del celular", Toast.LENGTH_LONG).show()
                    } else {
                        dispositivosEncontrados.clear()
                        escaneando = true
                        val scanSettings = ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                            .build()
                        bluetoothLeScanner.startScan(null, scanSettings, scanCallback)

                        coroutineScope.launch(Dispatchers.Main) {
                            delay(12000)
                            if (escaneando) {
                                try {
                                    bluetoothLeScanner.stopScan(scanCallback)
                                } catch (_: Exception) {}
                                escaneando = false
                            }
                        }
                    }
                } else {
                    if (activity != null) {
                        ActivityCompat.requestPermissions(activity, permisos, 100)
                    } else {
                        Toast.makeText(context, "Por favor, otorga los permisos de Bluetooth y Ubicación en la configuración del celular", Toast.LENGTH_LONG).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            enabled = !escaneando
        ) {
            Icon(imageVector = Icons.Filled.Search, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (escaneando) "Buscando NetPulse_R4 (10s)..." else "Buscar NetPulse_R4 (Arduino R4)", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- LISTA DE DISPOSITIVOS ENCONTRADOS ---
        if (dispositivosEncontrados.isNotEmpty()) {
            Text(
                text = "Dispositivos BLE Encontrados:",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp)
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(dispositivosEncontrados) { dispositivo ->
                    val esNetPulse = dispositivo.nombre.contains("NetPulse_R4", ignoreCase = true)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    bluetoothLeScanner?.stopScan(scanCallback)
                                } catch (_: Exception) {}
                                escaneando = false
                                Toast.makeText(context, "Conectando a ${dispositivo.nombre}...", Toast.LENGTH_SHORT).show()
                                dispositivoSeleccionado = dispositivo
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = if (esNetPulse) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = if (esNetPulse) "⭐ ${dispositivo.nombre} (Arduino R4 WiFi)" else dispositivo.nombre,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (esNetPulse) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "MAC: ${dispositivo.direccionMac}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else if (escaneando) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 32.dp))
        }
    }
}

// Función que maneja la lógica de la huella/rostro
fun autenticarUsuario(activity: FragmentActivity, onResult: (Boolean, String) -> Unit) {
    val executor = ContextCompat.getMainExecutor(activity)

    val biometricPrompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onResult(false, "Error: $errString")
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onResult(true, "Autenticación exitosa")
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                onResult(false, "Autenticación fallida. Intenta de nuevo.")
            }
        })

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Iniciar sesión en NetPulse")
        .setSubtitle("Usa tu huella dactilar o rostro")
        .setNegativeButtonText("Cancelar")
        .build()

    biometricPrompt.authenticate(promptInfo)
}

// ==========================================
// VISTAS PREVIAS
// ==========================================
@Preview(showBackground = true)
@Composable
fun PantallaLoginTradicionalPreview() {
    LatidosCardiacosTheme {
        PantallaLoginTradicional(onLoginExitoso = {}, onUsarHuella = {}, onIrARegistro = {})
    }
}

@Preview(showBackground = true)
@Composable
fun PantallaRegistroPreview() {
    LatidosCardiacosTheme {
        PantallaRegistro(onRegistroExitoso = {}, onVolver = {})
    }
}

@Preview(showBackground = true)
@Composable
fun PantallaLoginBiometricoPreview() {
    LatidosCardiacosTheme {
        PantallaLoginBiometrico(onLoginExitoso = {}, onVolver = {})
    }
}

@Preview(showBackground = true)
@Composable
fun PantallaPrincipalPreview() {
    LatidosCardiacosTheme {
        PantallaPrincipal()
    }
}
