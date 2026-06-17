package com.socks5.data.db

import androidx.room.*
import com.socks5.data.model.SshKey
import kotlinx.coroutines.flow.Flow

@Dao
interface KeyDao {
    @Query("SELECT * FROM ssh_keys ORDER BY createdAt DESC")
    fun getAll(): Flow<List<SshKey>>

    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    suspend fun getById(id: Long): SshKey?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(key: SshKey): Long

    @Update
    suspend fun update(key: SshKey)

    @Delete
    suspend fun delete(key: SshKey)

    @Query("DELETE FROM ssh_keys WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM ssh_keys WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun getByFingerprint(fingerprint: String): SshKey?
}
