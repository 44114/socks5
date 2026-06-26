package com.socks5.data.repository

import com.socks5.data.db.ProfileDao
import com.socks5.data.model.ConnectionProfile
import com.socks5.data.preferences.AppPreferences
import kotlinx.coroutines.flow.Flow

class ProfileRepository(
    private val profileDao: ProfileDao,
    private val preferences: AppPreferences
) {
    fun getAllProfiles(): Flow<List<ConnectionProfile>> = profileDao.getAll()

    suspend fun getById(id: Long): ConnectionProfile? = profileDao.getById(id)

    suspend fun insert(profile: ConnectionProfile): Long = profileDao.insert(profile)

    suspend fun update(profile: ConnectionProfile) = profileDao.update(profile)

    suspend fun delete(profile: ConnectionProfile) {
        // If this was the last-used profile, clear it
        if (preferences.lastUsedProfileId == profile.id) {
            preferences.lastUsedProfileId = -1
        }
        profileDao.delete(profile)
    }

    suspend fun deleteById(id: Long) {
        if (preferences.lastUsedProfileId == id) {
            preferences.lastUsedProfileId = -1
        }
        profileDao.deleteById(id)
    }

    suspend fun markUsed(id: Long) {
        preferences.lastUsedProfileId = id
        profileDao.updateLastUsed(id)
    }
}
