package com.example.latidoscardiacos.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LecturaSensorDao {

    @Insert
    suspend fun insertarLectura(lectura: LecturaSensor): Long

    @Query("SELECT * FROM lecturas_sensores ORDER BY timestamp DESC LIMIT 50")
    fun getUltimasLecturas(): Flow<List<LecturaSensor>>

    @Query("SELECT * FROM lecturas_sensores WHERE sincronizado = 0")
    suspend fun getLecturasNoSincronizadas(): List<LecturaSensor>

    @Query("UPDATE lecturas_sensores SET sincronizado = 1 WHERE id IN (:ids)")
    suspend fun marcarComoSincronizadas(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM lecturas_sensores WHERE sincronizado = 0")
    fun getCantidadNoSincronizadasFlow(): Flow<Int>
}
