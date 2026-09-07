package com.eventmanager.app.data.dao

import androidx.room.*
import com.eventmanager.app.data.models.GuestForm
import kotlinx.coroutines.flow.Flow

@Dao
interface GuestFormDao {
    @Query("SELECT * FROM guest_forms ORDER BY createdAt DESC")
    fun getAllGuestForms(): Flow<List<GuestForm>>

    @Query("SELECT * FROM guest_forms WHERE formId = :formId LIMIT 1")
    suspend fun getGuestFormByFormId(formId: String): GuestForm?

    @Query("SELECT * FROM guest_forms WHERE formId = :formId AND firebaseOrgId = :orgId LIMIT 1")
    suspend fun getGuestFormByFormIdAndOrg(formId: String, orgId: String): GuestForm?

    @Query("SELECT * FROM guest_forms WHERE status = :status")
    suspend fun getGuestFormsByStatus(status: String): List<GuestForm>

    @Query("SELECT * FROM guest_forms WHERE parentFormId = :parentFormId")
    suspend fun getGuestFormsByParentFormId(parentFormId: String): List<GuestForm>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGuestForm(form: GuestForm): Long

    @Update
    suspend fun updateGuestForm(form: GuestForm)

    @Delete
    suspend fun deleteGuestForm(form: GuestForm)

    @Query("DELETE FROM guest_forms")
    suspend fun deleteAllGuestForms()

    @Query("DELETE FROM guest_forms WHERE firebaseOrgId = :orgId")
    suspend fun deleteAllForOrg(orgId: String)

    @Query("DELETE FROM guest_forms WHERE firebaseOrgId != '' AND firebaseOrgId NOT IN (:orgIds)")
    suspend fun deleteAllNotInOrgs(orgIds: List<String>)

    @Query("UPDATE guest_forms SET firebaseOrgId = :orgId WHERE firebaseOrgId = ''")
    suspend fun backfillEmptyOrgIds(orgId: String)
}
