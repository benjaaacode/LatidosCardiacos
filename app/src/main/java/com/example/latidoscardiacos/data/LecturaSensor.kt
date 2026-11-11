package com.example.latidoscardiacos.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entidad Room para el almacenamiento local de lecturas de sensores (Paso 5 - Integración IoT & Sincronización).
 */
@Entity(tableName = "lecturas_sensores")
data class LecturaSensor(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bpm: Int,               // Ritmo cardíaco
    val temperatura: Float,    // Temperatura corporal o ambiental en °C
    val timestamp: Long = System.currentTimeMillis(),
    val estado: String = "NORMAL", // NORMAL, ELEVADO, CRITICO
    val sincronizado: Boolean = false // Estado de sincronización offline -> servidor/nube
)
