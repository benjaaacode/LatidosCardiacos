package com.example.latidoscardiacos

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import com.example.latidoscardiacos.data.AppDatabase
import com.example.latidoscardiacos.data.Usuario
import com.example.latidoscardiacos.data.UsuarioDao
import com.example.latidoscardiacos.ui.theme.LatidosCardiacosTheme
import kotlinx.coroutines.Dispatchers
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
                            PantallaRegistro(
                                modifier = Modifier.padding(innerPadding),
                                usuarioDao = usuarioDao,
                                onRegistroExitoso = { pantallaActual = Pantalla.LOGIN_TRADICIONAL }, // Vuelve al login tras registrar
                                onVolver = { pantallaActual = Pantalla.LOGIN_TRADICIONAL }
                            )
                        }
                        Pantalla.LOGIN_BIOMETRICO -> {
                            PantallaLoginBiometrico(
                                modifier = Modifier.padding(innerPadding),
                                onLoginExitoso = { pantallaActual = Pantalla.PRINCIPAL },
                                onVolver = { pantallaActual = Pantalla.LOGIN_TRADICIONAL }
                            )
                        }
                        Pantalla.PRINCIPAL -> {
                            PantallaPrincipal(modifier = Modifier.padding(innerPadding))
                        }
                    }
                }
            }
        }
    }
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
                        val usuario = withContext(Dispatchers.IO) {
                            usuarioDao?.login(inputCorreo, contrasena)
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
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
                    Toast.makeText(context, "Por favor completa todos los campos correctamente", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                coroutineScope.launch {
                    try {
                        val nuevoUsuario = Usuario(
                            nombre = inputNombre,
                            edad = inputEdad,
                            correo = inputCorreo,
                            contrasena = contrasena
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
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
    }
}

// ==========================================
// PANTALLA: PRINCIPAL (BLUETOOTH)
// ==========================================
@SuppressLint("MissingPermission")
@Composable
fun PantallaPrincipal(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    
    // Configuración real de Bluetooth
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter = bluetoothManager.adapter
    
    val dispositivosEncontrados = remember { mutableStateListOf<DispositivoBluetooth>() }
    var escaneando by remember { mutableStateOf(false) }

    // Este bloque gestiona los eventos de búsqueda de Bluetooth
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        // Un dispositivo fue encontrado
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        device?.let {
                            val nombre = it.name ?: "Dispositivo Desconocido"
                            val mac = it.address
                            val nuevoDisp = DispositivoBluetooth(nombre, mac)
                            
                            // Evitamos agregar duplicados a la lista
                            if (!dispositivosEncontrados.any { d -> d.direccionMac == mac }) {
                                dispositivosEncontrados.add(nuevoDisp)
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                        escaneando = true
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        escaneando = false
                    }
                }
            }
        }
        
        // Registramos qué eventos queremos escuchar
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(receiver, filter)

        // Limpieza cuando el usuario sale de la pantalla
        onDispose {
            context.unregisterReceiver(receiver)
            if (escaneando && bluetoothAdapter != null) {
                bluetoothAdapter.cancelDiscovery()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (escaneando) Icons.AutoMirrored.Filled.BluetoothSearching else Icons.Filled.Bluetooth,
            contentDescription = "Bluetooth",
            modifier = Modifier.size(60.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Dispositivos Bluetooth",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        // Botón para escanear
        Button(
            onClick = {
                val permisos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                }
                
                val permisosConcedidos = permisos.all { 
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }

                if (permisosConcedidos) {
                    // LÓGICA REAL DE BÚSQUEDA BLUETOOTH
                    if (bluetoothAdapter == null) {
                        Toast.makeText(context, "Este dispositivo no soporta Bluetooth", Toast.LENGTH_LONG).show()
                    } else if (!bluetoothAdapter.isEnabled) {
                        Toast.makeText(context, "Por favor, enciende el Bluetooth del celular primero", Toast.LENGTH_LONG).show()
                    } else {
                        dispositivosEncontrados.clear()
                        // IMPORTANTE: Cancelar descubrimiento antes de iniciar uno nuevo
                        if (bluetoothAdapter.isDiscovering) {
                            bluetoothAdapter.cancelDiscovery()
                        }
                        // Forzamos un pequeño retraso visual para que Android alcance a cancelar y reiniciar
                        escaneando = true
                        val inicioExitoso = bluetoothAdapter.startDiscovery()
                        if (!inicioExitoso) {
                           Toast.makeText(context, "Fallo al iniciar el escáner. Verifica tu Ubicación (GPS)", Toast.LENGTH_LONG).show()
                           escaneando = false
                        }
                    }
                } else {
                    if (activity != null) {
                        ActivityCompat.requestPermissions(activity, permisos, 100)
                        Toast.makeText(context, "Acepta los permisos de Bluetooth y Ubicación Cercana", Toast.LENGTH_LONG).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            enabled = !escaneando // Desactiva el botón mientras ya está buscando
        ) {
            Text(if (escaneando) "Buscando (Tarda unos 12s)..." else "Buscar Dispositivos", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (dispositivosEncontrados.isNotEmpty()) {
            Text(
                text = "Dispositivos Disponibles:",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp)
            )
            
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(dispositivosEncontrados) { dispositivo ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                Toast.makeText(context, "Conectando a ${dispositivo.nombre}...", Toast.LENGTH_SHORT).show()
                                if (bluetoothAdapter?.isDiscovering == true) {
                                    bluetoothAdapter.cancelDiscovery() // Detiene la búsqueda al conectar
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = dispositivo.nombre,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
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