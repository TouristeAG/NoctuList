package com.eventmanager.app.data.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.SQLiteStatement
import com.eventmanager.app.data.dao.AccountTransferDao
import com.eventmanager.app.data.dao.GuestDao
import com.eventmanager.app.data.dao.GuestFormDao
import com.eventmanager.app.data.dao.JobDao
import com.eventmanager.app.data.dao.JobTypeConfigDao
import com.eventmanager.app.data.dao.SalesSheetItemDao
import com.eventmanager.app.data.dao.VenueDao
import com.eventmanager.app.data.dao.VolunteerDao
import com.eventmanager.app.data.dao.PendingRemoteWriteDao
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.Converters
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.PendingRemoteWrite
import com.eventmanager.app.data.models.SalesSheetItem
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.data.models.Volunteer

/** Minimal cursor shim for Room KMP migrations that used SupportSQLiteDatabase.query. */
private class MigrationCursor(private val statement: SQLiteStatement) {
    fun moveToFirst(): Boolean = statement.step()
    fun moveToNext(): Boolean = statement.step()
    fun close() { statement.close() }
    fun getString(index: Int): String = statement.getText(index)
    fun getInt(index: Int): Int = statement.getLong(index).toInt()
    fun getLong(index: Int): Long = statement.getLong(index)
}

private fun SQLiteConnection.query(sql: String): MigrationCursor =
    MigrationCursor(prepare(sql))

@Database(
    entities = [
        Guest::class,
        Volunteer::class,
        Job::class,
        JobTypeConfig::class,
        VenueEntity::class,
        SalesSheetItem::class,
        AccountTransfer::class,
        PendingRemoteWrite::class,
        GuestForm::class,
    ],
    version = 51,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class EventManagerDatabase : RoomDatabase() {
    abstract fun guestDao(): GuestDao
    abstract fun volunteerDao(): VolunteerDao
    abstract fun jobDao(): JobDao
    abstract fun jobTypeConfigDao(): JobTypeConfigDao
    abstract fun venueDao(): VenueDao
    abstract fun salesSheetItemDao(): SalesSheetItemDao
    abstract fun accountTransferDao(): AccountTransferDao
    abstract fun pendingRemoteWriteDao(): PendingRemoteWriteDao
    abstract fun guestFormDao(): GuestFormDao

    companion object {
        @Volatile
        private var INSTANCE: EventManagerDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                // Add sheetsId column to all tables
                connection.execSQL("ALTER TABLE guests ADD COLUMN sheetsId TEXT")
                connection.execSQL("ALTER TABLE volunteers ADD COLUMN sheetsId TEXT")
                connection.execSQL("ALTER TABLE jobs ADD COLUMN sheetsId TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                // Create job_type_configs table
                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS job_type_configs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        isShiftJob INTEGER NOT NULL DEFAULT 1,
                        isOrionJob INTEGER NOT NULL DEFAULT 0,
                        requiresShiftTime INTEGER NOT NULL DEFAULT 1,
                        description TEXT NOT NULL DEFAULT '',
                        lastModified INTEGER NOT NULL
                    )
                """)
                
                // Insert default job type configurations
                val defaultJobTypes = listOf(
                    "BAR" to (true to false),
                    "SECURITY" to (true to false),
                    "CLEANING" to (true to false),
                    "SETUP" to (true to false),
                    "SOUND_TECH" to (true to false),
                    "LIGHTING" to (true to false),
                    "ENTRANCE" to (true to false),
                    "CLOAKROOM" to (true to false),
                    "COORDINATION" to (false to true),
                    "COMMITTEE" to (false to true),
                    "COMMISSION_PRESIDENCY" to (false to true),
                    "MEETING" to (true to false),
                    "OTHER" to (true to false)
                )
                
                val currentTime = System.currentTimeMillis()
                defaultJobTypes.forEach { (name, config) ->
                    val (isShiftJob, isOrionJob) = config
                    connection.execSQL("""
                        INSERT INTO job_type_configs (name, isActive, isShiftJob, isOrionJob, requiresShiftTime, description, lastModified)
                        VALUES ('$name', 1, ${if (isShiftJob) 1 else 0}, ${if (isOrionJob) 1 else 0}, 1, '', $currentTime)
                    """)
                }
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    // Add jobTypeName column to jobs table with default value
                    connection.execSQL("ALTER TABLE jobs ADD COLUMN jobTypeName TEXT NOT NULL DEFAULT 'OTHER'")
                } catch (e: Exception) {
                    // Column might already exist, ignore the error
                    println("jobTypeName column might already exist: ${e.message}")
                }
                
                try {
                    // Update existing jobs to set jobTypeName based on jobType enum
                    connection.execSQL("UPDATE jobs SET jobTypeName = CASE jobType " +
                        "WHEN 'BAR' THEN 'BAR' " +
                        "WHEN 'SECURITY' THEN 'SECURITY' " +
                        "WHEN 'CLEANING' THEN 'CLEANING' " +
                        "WHEN 'SETUP' THEN 'SETUP' " +
                        "WHEN 'SOUND_TECH' THEN 'SOUND_TECH' " +
                        "WHEN 'LIGHTING' THEN 'LIGHTING' " +
                        "WHEN 'ENTRANCE' THEN 'ENTRANCE' " +
                        "WHEN 'CLOAKROOM' THEN 'CLOAKROOM' " +
                        "WHEN 'COORDINATION' THEN 'COORDINATION' " +
                        "WHEN 'COMMITTEE' THEN 'COMMITTEE' " +
                        "WHEN 'COMMISSION_PRESIDENCY' THEN 'COMMISSION_PRESIDENCY' " +
                        "WHEN 'MEETING' THEN 'MEETING' " +
                        "ELSE 'OTHER' END")
                } catch (e: Exception) {
                    println("Failed to update jobTypeName values: ${e.message}")
                }
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    // Add volunteerId column to guests table
                    connection.execSQL("ALTER TABLE guests ADD COLUMN volunteerId INTEGER")
                } catch (e: Exception) {
                    // Column might already exist, ignore the error
                    println("volunteerId column might already exist: ${e.message}")
                }
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    // Fix jobTypeName column to be NOT NULL
                    // First, update any NULL values to 'OTHER'
                    connection.execSQL("UPDATE jobs SET jobTypeName = 'OTHER' WHERE jobTypeName IS NULL")
                    
                    // SQLite doesn't support ALTER COLUMN, so we need to recreate the table
                    // Create new jobs table with correct schema
                    connection.execSQL("""
                        CREATE TABLE jobs_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            sheetsId TEXT,
                            volunteerId INTEGER NOT NULL,
                            jobType TEXT NOT NULL,
                            jobTypeName TEXT NOT NULL,
                            venue TEXT NOT NULL,
                            date INTEGER NOT NULL,
                            shiftTime TEXT NOT NULL,
                            notes TEXT NOT NULL,
                            lastModified INTEGER NOT NULL
                        )
                    """)
                    
                    // Copy data from old table to new table
                    connection.execSQL("""
                        INSERT INTO jobs_new (id, sheetsId, volunteerId, jobType, jobTypeName, venue, date, shiftTime, notes, lastModified)
                        SELECT id, sheetsId, volunteerId, jobType, 
                               COALESCE(jobTypeName, 'OTHER') as jobTypeName, 
                               venue, date, shiftTime, notes, lastModified
                        FROM jobs
                    """)
                    
                    // Drop old table
                    connection.execSQL("DROP TABLE jobs")
                    
                    // Rename new table
                    connection.execSQL("ALTER TABLE jobs_new RENAME TO jobs")
                    
                } catch (e: Exception) {
                    println("Failed to fix jobTypeName column: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    // Add sheetsId column to job_type_configs table
                    connection.execSQL("ALTER TABLE job_type_configs ADD COLUMN sheetsId TEXT")
                    println("Successfully added sheetsId column to job_type_configs table")
                } catch (e: Exception) {
                    // Column might already exist, ignore the error
                    println("sheetsId column might already exist in job_type_configs: ${e.message}")
                }
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    // Add lastShiftDate column to volunteers table
                    connection.execSQL("ALTER TABLE volunteers ADD COLUMN lastShiftDate INTEGER")
                    println("Successfully added lastShiftDate column to volunteers table")
                } catch (e: Exception) {
                    // Column might already exist, ignore the error
                    println("lastShiftDate column might already exist in volunteers: ${e.message}")
                }
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    // Create new volunteers table with nullable currentRank
                    connection.execSQL("""
                        CREATE TABLE volunteers_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            sheetsId TEXT,
                            name TEXT NOT NULL,
                            lastNameAbbreviation TEXT NOT NULL,
                            email TEXT NOT NULL,
                            phoneNumber TEXT NOT NULL,
                            dateOfBirth TEXT NOT NULL,
                            currentRank TEXT,
                            isActive INTEGER NOT NULL,
                            lastShiftDate INTEGER,
                            lastModified INTEGER NOT NULL
                        )
                    """)
                    
                    // Copy data from old table to new table
                    connection.execSQL("""
                        INSERT INTO volunteers_new (id, sheetsId, name, lastNameAbbreviation, email, phoneNumber, dateOfBirth, currentRank, isActive, lastShiftDate, lastModified)
                        SELECT id, sheetsId, name, lastNameAbbreviation, email, phoneNumber, dateOfBirth, currentRank, isActive, lastShiftDate, lastModified
                        FROM volunteers
                    """)
                    
                    // Drop old table and rename new table
                    connection.execSQL("DROP TABLE volunteers")
                    connection.execSQL("ALTER TABLE volunteers_new RENAME TO volunteers")
                    
                    println("Successfully migrated volunteers table to support nullable currentRank")
                } catch (e: Exception) {
                    println("Migration 8_9 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    // Add lastNameAbbreviation column to guests table
                    connection.execSQL("ALTER TABLE guests ADD COLUMN lastNameAbbreviation TEXT NOT NULL DEFAULT ''")
                    println("Successfully added lastNameAbbreviation column to guests table")
                } catch (e: Exception) {
                    println("Migration 9_10 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    // Add benefitSystemType column to job_type_configs table
                    connection.execSQL("ALTER TABLE job_type_configs ADD COLUMN benefitSystemType TEXT NOT NULL DEFAULT 'STELLAR'")
                    
                    // Add manualRewards column to job_type_configs table
                    connection.execSQL("ALTER TABLE job_type_configs ADD COLUMN manualRewards TEXT")
                    
                    println("Successfully added benefitSystemType and manualRewards columns to job_type_configs table")
                } catch (e: Exception) {
                    println("Migration 10_11 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    // Add gender column to volunteers table
                    connection.execSQL("ALTER TABLE volunteers ADD COLUMN gender TEXT")
                    
                    println("Successfully added gender column to volunteers table")
                } catch (e: Exception) {
                    println("Migration 11_12 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    // Create venues table
                    connection.execSQL("""
                        CREATE TABLE IF NOT EXISTS venues (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            sheetsId TEXT,
                            name TEXT NOT NULL,
                            description TEXT NOT NULL DEFAULT '',
                            isActive INTEGER NOT NULL DEFAULT 1,
                            lastModified INTEGER NOT NULL
                        )
                    """)
                    
                    // Insert default venues
                    val currentTime = System.currentTimeMillis()
                    val defaultVenues = listOf(
                        "GROOVE" to "Main venue for events",
                        "LE_TERREAU" to "Secondary venue for events"
                    )
                    
                    defaultVenues.forEach { (name, description) ->
                        connection.execSQL("""
                            INSERT INTO venues (name, description, isActive, lastModified)
                            VALUES ('$name', '$description', 1, $currentTime)
                        """)
                    }
                    
                    println("Successfully created venues table with default venues")
                } catch (e: Exception) {
                    println("Migration 12_13 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    // Check if venues table exists and what its current schema is
                    val cursor = connection.query("SELECT sql FROM sqlite_master WHERE type='table' AND name='venues'")
                    val tableExists = cursor.moveToFirst()
                    cursor.close()
                    
                    if (tableExists) {
                        // Table exists, check if it needs schema updates
                        try {
                            // Try to query for sheetsId column to see if it exists
                            val testCursor = connection.query("SELECT sheetsId FROM venues LIMIT 1")
                            testCursor.close()
                            println("venues table already has correct schema")
                        } catch (e: Exception) {
                            // sheetsId column doesn't exist, need to add it
                            println("Adding sheetsId column to venues table")
                            connection.execSQL("ALTER TABLE venues ADD COLUMN sheetsId TEXT")
                        }
                        
                        // Check if isDiscovered column exists and remove it if it does
                        try {
                            val testCursor = connection.query("SELECT isDiscovered FROM venues LIMIT 1")
                            testCursor.close()
                            // Column exists, we need to recreate the table without it
                            println("Removing isDiscovered column from venues table")
                            
                            // Create new table with correct schema
                            connection.execSQL("""
                                CREATE TABLE venues_new (
                                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    sheetsId TEXT,
                                    name TEXT NOT NULL,
                                    description TEXT NOT NULL DEFAULT '',
                                    isActive INTEGER NOT NULL DEFAULT 1,
                                    lastModified INTEGER NOT NULL
                                )
                            """)
                            
                            // Copy data from old table to new table
                            connection.execSQL("""
                                INSERT INTO venues_new (id, sheetsId, name, description, isActive, lastModified)
                                SELECT id, sheetsId, name, description, isActive, lastModified FROM venues
                            """)
                            
                            // Drop old table and rename new one
                            connection.execSQL("DROP TABLE venues")
                            connection.execSQL("ALTER TABLE venues_new RENAME TO venues")
                            
                        } catch (e: Exception) {
                            // isDiscovered column doesn't exist, that's fine
                            println("isDiscovered column doesn't exist, schema is correct")
                        }
                    } else {
                        // Table doesn't exist, create it
                        println("Creating venues table")
                        connection.execSQL("""
                            CREATE TABLE venues (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                sheetsId TEXT,
                                name TEXT NOT NULL,
                                description TEXT NOT NULL DEFAULT '',
                                isActive INTEGER NOT NULL DEFAULT 1,
                                lastModified INTEGER NOT NULL
                            )
                        """)
                    }
                    
                    // Check if venues table is empty and add default venues if needed
                    val countCursor = connection.query("SELECT COUNT(*) FROM venues")
                    val count = if (countCursor.moveToFirst()) countCursor.getInt(0) else 0
                    countCursor.close()
                    
                    if (count == 0) {
                        val currentTime = System.currentTimeMillis()
                        val defaultVenues = listOf(
                            "GROOVE" to "Main venue for events",
                            "LE_TERREAU" to "Secondary venue for events"
                        )
                        
                        defaultVenues.forEach { (name, description) ->
                            connection.execSQL("""
                                INSERT INTO venues (name, description, isActive, lastModified)
                                VALUES ('$name', '$description', 1, $currentTime)
                            """)
                        }
                        println("Added default venues to empty venues table")
                    }
                    
                    println("Migration 13_14 completed successfully")
                } catch (e: Exception) {
                    println("Migration 13_14 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    // Migrate guests table: rename old table, create new one, migrate data, drop old
                    connection.execSQL("ALTER TABLE guests RENAME TO guests_old")
                    connection.execSQL("""
                        CREATE TABLE guests (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            invitations INTEGER NOT NULL,
                            venueName TEXT NOT NULL DEFAULT 'OTHER',
                            notes TEXT NOT NULL,
                            isVolunteerBenefit INTEGER NOT NULL,
                            lastModified INTEGER NOT NULL,
                            sheetsId TEXT,
                            volunteerId INTEGER,
                            lastNameAbbreviation TEXT NOT NULL
                        )
                    """)
                    connection.execSQL("""
                        INSERT INTO guests (id, name, invitations, venueName, notes, isVolunteerBenefit, lastModified, sheetsId, volunteerId, lastNameAbbreviation)
                        SELECT id, name, invitations, 
                            CASE venue
                                WHEN 'GROOVE' THEN 'GROOVE'
                                WHEN 'LE_TERREAU' THEN 'LE_TERREAU'
                                WHEN 'BOTH' THEN 'BOTH'
                                ELSE 'OTHER'
                            END as venueName,
                            notes, isVolunteerBenefit, lastModified, sheetsId, volunteerId, lastNameAbbreviation
                        FROM guests_old
                    """)
                    connection.execSQL("DROP TABLE guests_old")
                    
                    // Migrate jobs table: rename old table, create new one, migrate data, drop old
                    connection.execSQL("ALTER TABLE jobs RENAME TO jobs_old")
                    connection.execSQL("""
                        CREATE TABLE jobs (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            volunteerId INTEGER NOT NULL,
                            date INTEGER NOT NULL,
                            shiftTime TEXT NOT NULL,
                            jobType TEXT NOT NULL,
                            jobTypeName TEXT NOT NULL,
                            venueName TEXT NOT NULL DEFAULT 'OTHER',
                            notes TEXT NOT NULL,
                            lastModified INTEGER NOT NULL,
                            sheetsId TEXT
                        )
                    """)
                    connection.execSQL("""
                        INSERT INTO jobs (id, volunteerId, date, shiftTime, jobType, jobTypeName, venueName, notes, lastModified, sheetsId)
                        SELECT id, volunteerId, date, shiftTime, jobType, jobTypeName,
                            CASE venue
                                WHEN 'GROOVE' THEN 'GROOVE'
                                WHEN 'LE_TERREAU' THEN 'LE_TERREAU'
                                WHEN 'BOTH' THEN 'BOTH'
                                ELSE 'OTHER'
                            END as venueName,
                            notes, lastModified, sheetsId
                        FROM jobs_old
                    """)
                    connection.execSQL("DROP TABLE jobs_old")
                    println("Successfully converted venue enum to venueName string in both guests and jobs tables")
                } catch (e: Exception) {
                    println("Migration 14_15 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    // This migration handles cleanup for databases that had incomplete v15 migration
                    // Check if guests table still has the old venue column
                    val guestsCursor = connection.query("PRAGMA table_info(guests)")
                    var hasOldVenueColumn = false
                    while (guestsCursor.moveToNext()) {
                        val columnName = guestsCursor.getString(1)
                        if (columnName == "venue") {
                            hasOldVenueColumn = true
                            break
                        }
                    }
                    guestsCursor.close()

                    if (hasOldVenueColumn) {
                        // Recreate guests table without old venue column
                        connection.execSQL("ALTER TABLE guests RENAME TO guests_old")
                        connection.execSQL("""
                            CREATE TABLE guests (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                name TEXT NOT NULL,
                                invitations INTEGER NOT NULL,
                                venueName TEXT NOT NULL DEFAULT 'OTHER',
                                notes TEXT NOT NULL,
                                isVolunteerBenefit INTEGER NOT NULL,
                                lastModified INTEGER NOT NULL,
                                sheetsId TEXT,
                                volunteerId INTEGER,
                                lastNameAbbreviation TEXT NOT NULL
                            )
                        """)
                        connection.execSQL("""
                            INSERT INTO guests (id, name, invitations, venueName, notes, isVolunteerBenefit, lastModified, sheetsId, volunteerId, lastNameAbbreviation)
                            SELECT id, name, invitations, 
                                CASE WHEN venueName IS NOT NULL AND venueName != '' THEN venueName
                                     ELSE CASE venue
                                        WHEN 'GROOVE' THEN 'GROOVE'
                                        WHEN 'LE_TERREAU' THEN 'LE_TERREAU'
                                        WHEN 'BOTH' THEN 'BOTH'
                                        ELSE 'OTHER'
                                     END
                                END as venueName,
                                notes, isVolunteerBenefit, lastModified, sheetsId, volunteerId, lastNameAbbreviation
                            FROM guests_old
                        """)
                        connection.execSQL("DROP TABLE guests_old")
                        
                        // Recreate jobs table without old venue column
                        connection.execSQL("ALTER TABLE jobs RENAME TO jobs_old")
                        connection.execSQL("""
                            CREATE TABLE jobs (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                volunteerId INTEGER NOT NULL,
                                date INTEGER NOT NULL,
                                shiftTime TEXT NOT NULL,
                                jobType TEXT NOT NULL,
                                jobTypeName TEXT NOT NULL,
                                venueName TEXT NOT NULL DEFAULT 'OTHER',
                                notes TEXT NOT NULL,
                                lastModified INTEGER NOT NULL,
                                sheetsId TEXT
                            )
                        """)
                        connection.execSQL("""
                            INSERT INTO jobs (id, volunteerId, date, shiftTime, jobType, jobTypeName, venueName, notes, lastModified, sheetsId)
                            SELECT id, volunteerId, date, shiftTime, jobType, jobTypeName,
                                CASE WHEN venueName IS NOT NULL AND venueName != '' THEN venueName
                                     ELSE CASE venue
                                        WHEN 'GROOVE' THEN 'GROOVE'
                                        WHEN 'LE_TERREAU' THEN 'LE_TERREAU'
                                        WHEN 'BOTH' THEN 'BOTH'
                                        ELSE 'OTHER'
                                     END
                                END as venueName,
                                notes, lastModified, sheetsId
                            FROM jobs_old
                        """)
                        connection.execSQL("DROP TABLE jobs_old")
                        println("Migration 15_16: Cleaned up old venue column from guests and jobs tables")
                    } else {
                        println("Migration 15_16: Tables already in correct state, skipping cleanup")
                    }
                } catch (e: Exception) {
                    println("Migration 15_16 warning: ${e.message}")
                    // Don't throw - this is a cleanup migration
                }
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    // Create people_counter table
                    connection.execSQL("""
                        CREATE TABLE IF NOT EXISTS people_counter (
                            id INTEGER PRIMARY KEY NOT NULL,
                            count INTEGER NOT NULL DEFAULT 0,
                            lastModified INTEGER NOT NULL
                        )
                    """)
                    // Insert default counter if doesn't exist
                    connection.execSQL("""
                        INSERT OR IGNORE INTO people_counter (id, count, lastModified)
                        VALUES (1, 0, ${System.currentTimeMillis()})
                    """)
                    println("Successfully created people_counter table")
                } catch (e: Exception) {
                    println("Migration 16_17 failed: ${e.message}")
                    throw e
                }
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    // Add indices for guests table
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_guests_sheetsId ON guests(sheetsId)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_guests_volunteerId ON guests(volunteerId)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_guests_venueName ON guests(venueName)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_guests_lastModified ON guests(lastModified)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_guests_isVolunteerBenefit ON guests(isVolunteerBenefit)")
                    
                    // Add indices for volunteers table
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_volunteers_sheetsId ON volunteers(sheetsId)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_volunteers_isActive ON volunteers(isActive)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_volunteers_currentRank ON volunteers(currentRank)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_volunteers_lastModified ON volunteers(lastModified)")
                    
                    // Add indices for jobs table
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_volunteerId ON jobs(volunteerId)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_date ON jobs(date)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_venueName ON jobs(venueName)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_jobTypeName ON jobs(jobTypeName)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_sheetsId ON jobs(sheetsId)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_lastModified ON jobs(lastModified)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_volunteerId_date ON jobs(volunteerId, date)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_date_shiftTime ON jobs(date, shiftTime)")
                    
                    // Add indices for job_type_configs table
                    connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_job_type_configs_name ON job_type_configs(name)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_job_type_configs_sheetsId ON job_type_configs(sheetsId)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_job_type_configs_isActive ON job_type_configs(isActive)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_job_type_configs_lastModified ON job_type_configs(lastModified)")
                    
                    // Add indices for venues table
                    connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_venues_name ON venues(name)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_venues_sheetsId ON venues(sheetsId)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_venues_isActive ON venues(isActive)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_venues_lastModified ON venues(lastModified)")
                    
                    println("Successfully added database indices in migration 17_18")
                } catch (e: Exception) {
                    println("Migration 17_18 failed: ${e.message}")
                    throw e
                }
            }
        }

        /**
         * MIGRATION 18→19: Convert Volunteer IDs from auto-incrementing Long to NanoID (String)
         * 
         * This migration:
         * 1. Creates new volunteers table with String primary key
         * 2. Generates NanoIDs for existing volunteers and maps old Long IDs to new NanoIDs
         * 3. Creates new jobs table with String volunteerId
         * 4. Creates new guests table with String volunteerId
         * 5. Migrates data while preserving relationships using the ID mapping
         * 6. Drops old tables and recreates indices
         */
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 18→19: Converting volunteer IDs from Long to NanoID (String)")
                    
                    // Step 1: Create a mapping table to store old Long ID → new NanoID mapping
                    connection.execSQL("""
                        CREATE TABLE IF NOT EXISTS volunteer_id_mapping (
                            old_id INTEGER PRIMARY KEY,
                            new_id TEXT NOT NULL
                        )
                    """)
                    
                    // Step 2: Read all existing volunteers and generate NanoIDs for each
                    val cursor = connection.query("SELECT id FROM volunteers")
                    val idMappings = mutableMapOf<Long, String>()
                    
                    while (cursor.moveToNext()) {
                        val oldId = cursor.getLong(0)
                        // Generate a NanoID for each volunteer
                        val newId = generateNanoId()
                        idMappings[oldId] = newId
                        connection.execSQL("INSERT INTO volunteer_id_mapping (old_id, new_id) VALUES ($oldId, '$newId')")
                    }
                    cursor.close()
                    
                    println("Generated ${idMappings.size} NanoIDs for existing volunteers")
                    
                    // Step 3: Create new volunteers table with TEXT primary key
                    connection.execSQL("""
                        CREATE TABLE volunteers_new (
                            id TEXT PRIMARY KEY NOT NULL,
                            sheetsId TEXT,
                            name TEXT NOT NULL,
                            lastNameAbbreviation TEXT NOT NULL,
                            email TEXT NOT NULL,
                            phoneNumber TEXT NOT NULL,
                            dateOfBirth TEXT NOT NULL,
                            gender TEXT,
                            currentRank TEXT,
                            isActive INTEGER NOT NULL,
                            lastShiftDate INTEGER,
                            lastModified INTEGER NOT NULL
                        )
                    """)
                    
                    // Step 4: Migrate volunteers data with new NanoIDs
                    connection.execSQL("""
                        INSERT INTO volunteers_new (id, sheetsId, name, lastNameAbbreviation, email, phoneNumber, dateOfBirth, gender, currentRank, isActive, lastShiftDate, lastModified)
                        SELECT m.new_id, v.sheetsId, v.name, v.lastNameAbbreviation, v.email, v.phoneNumber, v.dateOfBirth, v.gender, v.currentRank, v.isActive, v.lastShiftDate, v.lastModified
                        FROM volunteers v
                        INNER JOIN volunteer_id_mapping m ON v.id = m.old_id
                    """)
                    
                    // Step 5: Create new jobs table with TEXT volunteerId
                    connection.execSQL("""
                        CREATE TABLE jobs_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            sheetsId TEXT,
                            volunteerId TEXT NOT NULL,
                            jobType TEXT NOT NULL,
                            jobTypeName TEXT NOT NULL,
                            venueName TEXT NOT NULL,
                            date INTEGER NOT NULL,
                            shiftTime TEXT NOT NULL,
                            notes TEXT NOT NULL,
                            lastModified INTEGER NOT NULL
                        )
                    """)
                    
                    // Step 6: Migrate jobs data with mapped volunteerId
                    connection.execSQL("""
                        INSERT INTO jobs_new (id, sheetsId, volunteerId, jobType, jobTypeName, venueName, date, shiftTime, notes, lastModified)
                        SELECT j.id, j.sheetsId, COALESCE(m.new_id, CAST(j.volunteerId AS TEXT)), j.jobType, j.jobTypeName, j.venueName, j.date, j.shiftTime, j.notes, j.lastModified
                        FROM jobs j
                        LEFT JOIN volunteer_id_mapping m ON j.volunteerId = m.old_id
                    """)
                    
                    // Step 7: Create new guests table with TEXT volunteerId
                    connection.execSQL("""
                        CREATE TABLE guests_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            sheetsId TEXT,
                            name TEXT NOT NULL,
                            lastNameAbbreviation TEXT NOT NULL,
                            invitations INTEGER NOT NULL,
                            venueName TEXT NOT NULL,
                            notes TEXT NOT NULL,
                            isVolunteerBenefit INTEGER NOT NULL,
                            volunteerId TEXT,
                            lastModified INTEGER NOT NULL
                        )
                    """)
                    
                    // Step 8: Migrate guests data with mapped volunteerId
                    connection.execSQL("""
                        INSERT INTO guests_new (id, sheetsId, name, lastNameAbbreviation, invitations, venueName, notes, isVolunteerBenefit, volunteerId, lastModified)
                        SELECT g.id, g.sheetsId, g.name, g.lastNameAbbreviation, g.invitations, g.venueName, g.notes, g.isVolunteerBenefit, 
                               CASE WHEN g.volunteerId IS NOT NULL THEN m.new_id ELSE NULL END, g.lastModified
                        FROM guests g
                        LEFT JOIN volunteer_id_mapping m ON g.volunteerId = m.old_id
                    """)
                    
                    // Step 9: Drop old tables
                    connection.execSQL("DROP TABLE volunteers")
                    connection.execSQL("DROP TABLE jobs")
                    connection.execSQL("DROP TABLE guests")
                    connection.execSQL("DROP TABLE volunteer_id_mapping")
                    
                    // Step 10: Rename new tables
                    connection.execSQL("ALTER TABLE volunteers_new RENAME TO volunteers")
                    connection.execSQL("ALTER TABLE jobs_new RENAME TO jobs")
                    connection.execSQL("ALTER TABLE guests_new RENAME TO guests")
                    
                    // Step 11: Recreate all indices for volunteers table
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_volunteers_sheetsId ON volunteers(sheetsId)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_volunteers_isActive ON volunteers(isActive)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_volunteers_currentRank ON volunteers(currentRank)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_volunteers_lastModified ON volunteers(lastModified)")
                    
                    // Step 12: Recreate all indices for jobs table
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_volunteerId ON jobs(volunteerId)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_date ON jobs(date)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_venueName ON jobs(venueName)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_jobTypeName ON jobs(jobTypeName)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_sheetsId ON jobs(sheetsId)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_lastModified ON jobs(lastModified)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_volunteerId_date ON jobs(volunteerId, date)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_date_shiftTime ON jobs(date, shiftTime)")
                    
                    // Step 13: Recreate all indices for guests table
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_guests_sheetsId ON guests(sheetsId)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_guests_volunteerId ON guests(volunteerId)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_guests_venueName ON guests(venueName)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_guests_lastModified ON guests(lastModified)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_guests_isVolunteerBenefit ON guests(isVolunteerBenefit)")
                    
                    println("Migration 18→19 completed successfully: Volunteer IDs converted to NanoIDs")
                    
                } catch (e: Exception) {
                    println("Migration 18→19 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
            
            /**
             * Generate a NanoID-like string for database migration.
             * Uses a simple implementation since we can't use external libraries in SQLite migration.
             */
            private fun generateNanoId(): String {
                val alphabet = "_-0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                val random = java.security.SecureRandom()
                val sb = StringBuilder(21)
                for (i in 0 until 21) {
                    sb.append(alphabet[random.nextInt(alphabet.length)])
                }
                return sb.toString()
            }
        }

        /**
         * This migration adds two new columns to store guest contact information:
         * - email: Guest's email address (optional)
         * - phoneNumber: Guest's phone number (optional)
         */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 19→20: Adding email and phoneNumber to guests table")
                    
                    // Add email column with empty default
                    connection.execSQL("ALTER TABLE guests ADD COLUMN email TEXT NOT NULL DEFAULT ''")
                    println("Added email column to guests table")
                    
                    // Add phoneNumber column with empty default
                    connection.execSQL("ALTER TABLE guests ADD COLUMN phoneNumber TEXT NOT NULL DEFAULT ''")
                    println("Added phoneNumber column to guests table")
                    
                    println("Migration 19→20 completed successfully")
                } catch (e: Exception) {
                    println("Migration 19→20 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        /**
         * MIGRATION 20→21: Add benefitUsed column to jobs table.
         * Tracks whether an after-midnight shift's free entry benefit has been redeemed.
         * null = not applicable, 0 (false) = not yet used, 1 (true) = used.
         */
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 20→21: Adding benefitUsed column to jobs table")
                    
                    // Add nullable benefitUsed column (Room stores Boolean? as nullable INTEGER)
                    connection.execSQL("ALTER TABLE jobs ADD COLUMN benefitUsed INTEGER")
                    
                    // Set benefitUsed = 0 (false / not used) for existing after-midnight shifts
                    connection.execSQL("UPDATE jobs SET benefitUsed = 0 WHERE shiftTime = 'AFTER_MIDNIGHT'")
                    
                    println("Migration 20→21 completed successfully")
                } catch (e: Exception) {
                    println("Migration 20→21 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        /**
         * MIGRATION 21→22: Add temporary guest metadata columns to guests table.
         */
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 21→22: Adding temporary guest columns to guests table")
                    connection.execSQL("ALTER TABLE guests ADD COLUMN isTemporaryGuest INTEGER NOT NULL DEFAULT 0")
                    connection.execSQL("ALTER TABLE guests ADD COLUMN temporaryArtistName TEXT NOT NULL DEFAULT ''")
                    connection.execSQL("ALTER TABLE guests ADD COLUMN temporaryEventDate INTEGER")
                    connection.execSQL("ALTER TABLE guests ADD COLUMN temporaryContactPhone TEXT NOT NULL DEFAULT ''")
                    println("Migration 21→22 completed successfully")
                } catch (e: Exception) {
                    println("Migration 21→22 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        /**
         * MIGRATION 22→23: Add NFC UID columns to guests and volunteers.
         */
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 22→23: Adding NFC UID columns")
                    connection.execSQL("ALTER TABLE guests ADD COLUMN nfcCardUid TEXT NOT NULL DEFAULT ''")
                    connection.execSQL("ALTER TABLE volunteers ADD COLUMN nfcCardUid TEXT NOT NULL DEFAULT ''")
                    println("Migration 22→23 completed successfully")
                } catch (e: Exception) {
                    println("Migration 22→23 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        /**
         * MIGRATION 23→24: Add NanoID column to guests table.
         * Each guest (permanent and temporary) gets a globally-unique NanoID for
         * conflict-free synchronisation across devices and Google Sheets.
         * Existing rows receive an empty string as a placeholder; the application
         * will generate proper NanoIDs on next load and push them to Google Sheets.
         */
        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 23→24: Adding nanoId column to guests table")
                    connection.execSQL("ALTER TABLE guests ADD COLUMN nanoId TEXT NOT NULL DEFAULT ''")
                    println("Migration 23→24 completed successfully")
                } catch (e: Exception) {
                    println("Migration 23→24 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        /**
         * MIGRATION 24→25: Replace jobs.benefitUsed (boolean) with benefitFutureEntriesRemaining (int?).
         * Legacy: null → null, false (0) → 1 remaining, true (1) → 0 remaining.
         */
        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 24→25: benefitFutureEntriesRemaining on jobs")
                    connection.execSQL("""
                        CREATE TABLE IF NOT EXISTS jobs_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            sheetsId TEXT,
                            volunteerId TEXT NOT NULL,
                            jobType TEXT NOT NULL,
                            jobTypeName TEXT NOT NULL,
                            venueName TEXT NOT NULL,
                            date INTEGER NOT NULL,
                            shiftTime TEXT NOT NULL,
                            benefitFutureEntriesRemaining INTEGER,
                            notes TEXT NOT NULL,
                            lastModified INTEGER NOT NULL
                        )
                    """.trimIndent())
                    connection.execSQL("""
                        INSERT INTO jobs_new (
                            id, sheetsId, volunteerId, jobType, jobTypeName, venueName,
                            date, shiftTime, benefitFutureEntriesRemaining, notes, lastModified
                        )
                        SELECT
                            id, sheetsId, volunteerId, jobType, jobTypeName, venueName,
                            date, shiftTime,
                            CASE
                                WHEN benefitUsed IS NULL THEN NULL
                                WHEN benefitUsed = 0 THEN 1
                                ELSE 0
                            END,
                            notes, lastModified
                        FROM jobs
                    """.trimIndent())
                    connection.execSQL("DROP TABLE jobs")
                    connection.execSQL("ALTER TABLE jobs_new RENAME TO jobs")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_volunteerId ON jobs(volunteerId)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_date ON jobs(date)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_venueName ON jobs(venueName)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_jobTypeName ON jobs(jobTypeName)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_sheetsId ON jobs(sheetsId)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_lastModified ON jobs(lastModified)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_volunteerId_date ON jobs(volunteerId, date)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_jobs_date_shiftTime ON jobs(date, shiftTime)")
                    println("Migration 24→25 completed successfully")
                } catch (e: Exception) {
                    println("Migration 24→25 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        /**
         * MIGRATION 25→26: Add isAdmin column to guests and volunteers tables.
         * Defaults to false (0) for all existing rows.
         */
        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 25→26: Adding isAdmin column to guests and volunteers tables")
                    connection.execSQL("ALTER TABLE guests ADD COLUMN isAdmin INTEGER NOT NULL DEFAULT 0")
                    connection.execSQL("ALTER TABLE volunteers ADD COLUMN isAdmin INTEGER NOT NULL DEFAULT 0")
                    println("Migration 25→26 completed successfully")
                } catch (e: Exception) {
                    println("Migration 25→26 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        /**
         * MIGRATION 26→27: Add benefitFutureEntryInvites column to jobs table.
         * Existing rows with remaining entries default to 1 invite (friend).
         */
        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 26→27: Adding benefitFutureEntryInvites column to jobs table")
                    connection.execSQL("ALTER TABLE jobs ADD COLUMN benefitFutureEntryInvites INTEGER DEFAULT NULL")
                    connection.execSQL("UPDATE jobs SET benefitFutureEntryInvites = 1 WHERE benefitFutureEntriesRemaining IS NOT NULL AND benefitFutureEntriesRemaining > 0")
                    println("Migration 26→27 completed successfully")
                } catch (e: Exception) {
                    println("Migration 26→27 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        /**
         * MIGRATION 27→28: Add novaJobType column to job_type_configs table.
         * Defaults to DEFAULT_SHIFT for all existing rows.
         */
        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 27→28: Adding novaJobType column to job_type_configs table")
                    connection.execSQL("ALTER TABLE job_type_configs ADD COLUMN novaJobType TEXT NOT NULL DEFAULT 'DEFAULT_SHIFT'")
                    println("Migration 27→28 completed successfully")
                } catch (e: Exception) {
                    println("Migration 27→28 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        /**
         * MIGRATION 28→29: Per-venue people counter (Google Sheets E–G); remove legacy single counter table.
         */
        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 28→29: Venue people counter columns + drop people_counter")
                    connection.execSQL("ALTER TABLE venues ADD COLUMN peopleCounterCount INTEGER NOT NULL DEFAULT 0")
                    connection.execSQL("ALTER TABLE venues ADD COLUMN peopleCounterWriterDeviceId TEXT NOT NULL DEFAULT ''")
                    connection.execSQL("ALTER TABLE venues ADD COLUMN peopleCounterLastModified INTEGER NOT NULL DEFAULT 0")
                    connection.execSQL("DROP TABLE IF EXISTS people_counter")
                    println("Migration 28→29 completed successfully")
                } catch (e: Exception) {
                    println("Migration 28→29 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        /**
         * MIGRATION 29→30: Add announcement columns to venues table (Google Sheets H–K).
         */
        private val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 29→30: Adding announcement columns to venues table")
                    connection.execSQL("ALTER TABLE venues ADD COLUMN announcementTitle TEXT NOT NULL DEFAULT ''")
                    connection.execSQL("ALTER TABLE venues ADD COLUMN announcementMessage TEXT NOT NULL DEFAULT ''")
                    connection.execSQL("ALTER TABLE venues ADD COLUMN announcementSentAt INTEGER NOT NULL DEFAULT 0")
                    connection.execSQL("ALTER TABLE venues ADD COLUMN announcementSenderDeviceId TEXT NOT NULL DEFAULT ''")
                    println("Migration 29→30 completed successfully")
                } catch (e: Exception) {
                    println("Migration 29→30 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        /**
         * MIGRATION 30→31: Create sales_sheet_items table for Lightspeed sales items.
         */
        private val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 30→31: Creating sales_sheet_items table")
                    connection.execSQL("""
                        CREATE TABLE IF NOT EXISTS sales_sheet_items (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            sheetsId TEXT,
                            name TEXT NOT NULL,
                            price REAL NOT NULL,
                            discountPercent INTEGER NOT NULL DEFAULT 0,
                            requiredRank TEXT NOT NULL DEFAULT 'NOVA',
                            isActive INTEGER NOT NULL DEFAULT 1,
                            lastModified INTEGER NOT NULL
                        )
                    """)
                    connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sales_sheet_items_name ON sales_sheet_items(name)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_sales_sheet_items_sheetsId ON sales_sheet_items(sheetsId)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_sales_sheet_items_requiredRank ON sales_sheet_items(requiredRank)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_sales_sheet_items_isActive ON sales_sheet_items(isActive)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_sales_sheet_items_lastModified ON sales_sheet_items(lastModified)")
                    println("Migration 30→31 completed successfully")
                } catch (e: Exception) {
                    println("Migration 30→31 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        /**
         * MIGRATION 31→32:
         * - Replace sales discount from percentage to boolean flag (hasDiscount)
         * - Allow null requiredRank (no rank required)
         */
        private val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 31→32: Updating sales_sheet_items schema")
                    connection.execSQL("""
                        CREATE TABLE IF NOT EXISTS sales_sheet_items_new (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            sheetsId TEXT,
                            name TEXT NOT NULL,
                            price REAL NOT NULL,
                            hasDiscount INTEGER NOT NULL DEFAULT 0,
                            requiredRank TEXT,
                            isActive INTEGER NOT NULL DEFAULT 1,
                            lastModified INTEGER NOT NULL
                        )
                    """)
                    connection.execSQL("""
                        INSERT INTO sales_sheet_items_new (id, sheetsId, name, price, hasDiscount, requiredRank, isActive, lastModified)
                        SELECT
                            id,
                            sheetsId,
                            name,
                            price,
                            CASE WHEN discountPercent > 0 THEN 1 ELSE 0 END,
                            CASE WHEN requiredRank = '' THEN NULL ELSE requiredRank END,
                            isActive,
                            lastModified
                        FROM sales_sheet_items
                    """)
                    connection.execSQL("DROP TABLE sales_sheet_items")
                    connection.execSQL("ALTER TABLE sales_sheet_items_new RENAME TO sales_sheet_items")
                    connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sales_sheet_items_name ON sales_sheet_items(name)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_sales_sheet_items_sheetsId ON sales_sheet_items(sheetsId)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_sales_sheet_items_requiredRank ON sales_sheet_items(requiredRank)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_sales_sheet_items_isActive ON sales_sheet_items(isActive)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_sales_sheet_items_lastModified ON sales_sheet_items(lastModified)")
                    println("Migration 31→32 completed successfully")
                } catch (e: Exception) {
                    println("Migration 31→32 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        /**
         * MIGRATION 32→33: Account transfers ledger, job type account credit, sales item categories/emoji.
         */
        private val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 32→33: account_transfers + schema updates")
                    connection.execSQL("""
                        CREATE TABLE IF NOT EXISTS account_transfers (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            sheetsId TEXT,
                            transferId TEXT NOT NULL,
                            holderType TEXT NOT NULL,
                            holderId TEXT NOT NULL,
                            holderName TEXT NOT NULL,
                            amount REAL NOT NULL,
                            currencyCode TEXT NOT NULL,
                            type TEXT NOT NULL,
                            sourceReference TEXT NOT NULL,
                            jobReferenceKey TEXT NOT NULL DEFAULT '',
                            jobTypeName TEXT NOT NULL DEFAULT '',
                            jobDate INTEGER,
                            description TEXT NOT NULL DEFAULT '',
                            creditAmountPaid REAL,
                            posItemsJson TEXT NOT NULL DEFAULT '',
                            createdAt INTEGER NOT NULL,
                            lastModified INTEGER NOT NULL
                        )
                    """.trimIndent())
                    connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_account_transfers_transferId ON account_transfers(transferId)")
                    connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_account_transfers_sourceReference ON account_transfers(sourceReference)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_account_transfers_sheetsId ON account_transfers(sheetsId)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_account_transfers_holderType_holderId ON account_transfers(holderType, holderId)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_account_transfers_lastModified ON account_transfers(lastModified)")
                    connection.execSQL("CREATE INDEX IF NOT EXISTS index_account_transfers_createdAt ON account_transfers(createdAt)")
                    connection.execSQL("ALTER TABLE job_type_configs ADD COLUMN accountCreditChf REAL")
                    connection.execSQL("ALTER TABLE sales_sheet_items ADD COLUMN categories TEXT NOT NULL DEFAULT ''")
                    connection.execSQL("ALTER TABLE sales_sheet_items ADD COLUMN emoji TEXT NOT NULL DEFAULT ''")
                    println("Migration 32→33 completed successfully")
                } catch (e: Exception) {
                    println("Migration 32→33 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        private val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 33→34: MEETING novaJobType fix")
                    connection.execSQL(
                        "UPDATE job_type_configs SET novaJobType = 'MEETING' WHERE UPPER(name) = 'MEETING' AND novaJobType = 'DEFAULT_SHIFT'"
                    )
                    println("Migration 33→34 completed successfully")
                } catch (e: Exception) {
                    println("Migration 33→34 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        private val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 34→35: POS cash paid + bar discount audit columns")
                    connection.execSQL("ALTER TABLE account_transfers ADD COLUMN cashAmountPaid REAL")
                    connection.execSQL("ALTER TABLE account_transfers ADD COLUMN posBarDiscountPercent INTEGER")
                    println("Migration 34→35 completed successfully")
                } catch (e: Exception) {
                    println("Migration 34→35 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        private val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 35→36: POS venue on transfers and sales items")
                    connection.execSQL(
                        "ALTER TABLE account_transfers ADD COLUMN posVenueName TEXT NOT NULL DEFAULT ''"
                    )
                    connection.execSQL(
                        "ALTER TABLE sales_sheet_items ADD COLUMN availableVenues TEXT NOT NULL DEFAULT ''"
                    )
                    println("Migration 35→36 completed successfully")
                } catch (e: Exception) {
                    println("Migration 35→36 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        private val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 36→37: jobNanoId, transfer syncState, pending_remote_writes")
                    connection.execSQL(
                        "ALTER TABLE jobs ADD COLUMN jobNanoId TEXT NOT NULL DEFAULT ''"
                    )
                    // Backfill empty jobNanoId values with a simple unique surrogate
                    connection.execSQL(
                        """
                        UPDATE jobs SET jobNanoId =
                            'job_' || id || '_' || volunteerId || '_' || date
                        WHERE jobNanoId = '' OR jobNanoId IS NULL
                        """.trimIndent()
                    )
                    connection.execSQL(
                        "ALTER TABLE account_transfers ADD COLUMN syncState TEXT NOT NULL DEFAULT 'CONFIRMED'"
                    )
                    connection.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS pending_remote_writes (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            collection TEXT NOT NULL,
                            documentId TEXT NOT NULL,
                            payloadJson TEXT NOT NULL,
                            operation TEXT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            attempts INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    connection.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_pending_remote_writes_createdAt ON pending_remote_writes(createdAt)"
                    )
                    connection.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_pending_remote_writes_collection_documentId ON pending_remote_writes(collection, documentId)"
                    )
                    println("Migration 36→37 completed successfully")
                } catch (e: Exception) {
                    println("Migration 36→37 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        private val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 37→38: orgId on pending_remote_writes")
                    connection.execSQL(
                        "ALTER TABLE pending_remote_writes ADD COLUMN orgId TEXT NOT NULL DEFAULT ''"
                    )
                    println("Migration 37→38 completed successfully")
                } catch (e: Exception) {
                    println("Migration 37→38 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        private val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 38→39: firebaseOrgId on entity tables")
                    val tables = listOf(
                        "guests",
                        "volunteers",
                        "jobs",
                        "job_type_configs",
                        "venues",
                        "sales_sheet_items",
                        "account_transfers",
                    )
                    tables.forEach { table ->
                        connection.execSQL(
                            "ALTER TABLE $table ADD COLUMN firebaseOrgId TEXT NOT NULL DEFAULT ''",
                        )
                    }
                    connection.execSQL("DROP INDEX IF EXISTS index_job_type_configs_name")
                    connection.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_job_type_configs_firebaseOrgId_name " +
                            "ON job_type_configs(firebaseOrgId, name)",
                    )
                    connection.execSQL("DROP INDEX IF EXISTS index_venues_name")
                    connection.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_venues_firebaseOrgId_name " +
                            "ON venues(firebaseOrgId, name)",
                    )
                    connection.execSQL("DROP INDEX IF EXISTS index_sales_sheet_items_name")
                    connection.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_sales_sheet_items_firebaseOrgId_name " +
                            "ON sales_sheet_items(firebaseOrgId, name)",
                    )
                    println("Migration 38→39 completed successfully")
                } catch (e: Exception) {
                    println("Migration 38→39 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        private val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 39→40: nfcCardUidHash + local crypto prep")
                    connection.execSQL(
                        "ALTER TABLE guests ADD COLUMN nfcCardUidHash TEXT NOT NULL DEFAULT ''",
                    )
                    connection.execSQL(
                        "ALTER TABLE volunteers ADD COLUMN nfcCardUidHash TEXT NOT NULL DEFAULT ''",
                    )
                    println("Migration 39→40 completed successfully")
                } catch (e: Exception) {
                    println("Migration 39→40 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        private val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 40→41: Firebase people counter writer account email")
                    connection.execSQL(
                        "ALTER TABLE venues ADD COLUMN peopleCounterWriterAccountEmail TEXT NOT NULL DEFAULT ''",
                    )
                    println("Migration 40→41 completed successfully")
                } catch (e: Exception) {
                    println("Migration 40→41 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        private val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 41→42: Firebase profile photo path/url")
                    connection.execSQL(
                        "ALTER TABLE guests ADD COLUMN profilePhotoPath TEXT NOT NULL DEFAULT ''",
                    )
                    connection.execSQL(
                        "ALTER TABLE guests ADD COLUMN profilePhotoUrl TEXT NOT NULL DEFAULT ''",
                    )
                    connection.execSQL(
                        "ALTER TABLE volunteers ADD COLUMN profilePhotoPath TEXT NOT NULL DEFAULT ''",
                    )
                    connection.execSQL(
                        "ALTER TABLE volunteers ADD COLUMN profilePhotoUrl TEXT NOT NULL DEFAULT ''",
                    )
                    println("Migration 41→42 completed successfully")
                } catch (e: Exception) {
                    println("Migration 41→42 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        private val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 42→43: permanent guest bar discount percent")
                    connection.execSQL(
                        "ALTER TABLE guests ADD COLUMN barDiscountPercent INTEGER NOT NULL DEFAULT 0",
                    )
                    println("Migration 42→43 completed successfully")
                } catch (e: Exception) {
                    println("Migration 42→43 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        private val MIGRATION_43_44 = object : Migration(43, 44) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 43→44: POS product sub-category")
                    connection.execSQL(
                        "ALTER TABLE sales_sheet_items ADD COLUMN subcategory TEXT NOT NULL DEFAULT ''",
                    )
                    println("Migration 43→44 completed successfully")
                } catch (e: Exception) {
                    println("Migration 43→44 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        private val MIGRATION_44_45 = object : Migration(44, 45) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 44→45: deposit (consigne) products")
                    connection.execSQL(
                        "ALTER TABLE sales_sheet_items ADD COLUMN isDeposit INTEGER NOT NULL DEFAULT 0",
                    )
                    println("Migration 44→45 completed successfully")
                } catch (e: Exception) {
                    println("Migration 44→45 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        private val MIGRATION_45_46 = object : Migration(45, 46) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 45→46: temporary guest venue, access, contact mail and entry validation")
                    connection.execSQL(
                        "ALTER TABLE guests ADD COLUMN temporaryVenueName TEXT NOT NULL DEFAULT ''",
                    )
                    connection.execSQL(
                        "ALTER TABLE guests ADD COLUMN temporaryContactEmail TEXT NOT NULL DEFAULT ''",
                    )
                    connection.execSQL(
                        "ALTER TABLE guests ADD COLUMN temporaryAccessIds TEXT NOT NULL DEFAULT ''",
                    )
                    connection.execSQL(
                        "ALTER TABLE guests ADD COLUMN temporaryEntryValidatedAt INTEGER NOT NULL DEFAULT 0",
                    )
                    connection.execSQL(
                        "ALTER TABLE guests ADD COLUMN temporaryEntryValidatedBy TEXT NOT NULL DEFAULT ''",
                    )
                    println("Migration 45→46 completed successfully")
                } catch (e: Exception) {
                    println("Migration 45→46 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        private val MIGRATION_46_47 = object : Migration(46, 47) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 46→47: artist guest list forms")
                    connection.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS guest_forms (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            formId TEXT NOT NULL,
                            firebaseOrgId TEXT NOT NULL DEFAULT '',
                            venueName TEXT NOT NULL DEFAULT '',
                            eventName TEXT NOT NULL DEFAULT '',
                            eventDateMillis INTEGER NOT NULL DEFAULT 0,
                            artistName TEXT NOT NULL DEFAULT '',
                            offeredAccessIds TEXT NOT NULL DEFAULT '',
                            maxGuests INTEGER NOT NULL DEFAULT 10,
                            expiryMode TEXT NOT NULL DEFAULT 'AFTER_RESPONSE',
                            expiresAtMillis INTEGER NOT NULL DEFAULT 0,
                            showInstitutionLogo INTEGER NOT NULL DEFAULT 1,
                            institutionLogoDataUri TEXT NOT NULL DEFAULT '',
                            guestLogoDataUri TEXT NOT NULL DEFAULT '',
                            prefillEmail TEXT NOT NULL DEFAULT '',
                            prefillPhone TEXT NOT NULL DEFAULT '',
                            status TEXT NOT NULL DEFAULT 'OPEN',
                            submissionJson TEXT NOT NULL DEFAULT '',
                            submittedAt INTEGER NOT NULL DEFAULT 0,
                            reviewedAt INTEGER NOT NULL DEFAULT 0,
                            reviewedBy TEXT NOT NULL DEFAULT '',
                            createdAt INTEGER NOT NULL DEFAULT 0,
                            lastModified INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent(),
                    )
                    connection.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS index_guest_forms_firebaseOrgId_formId " +
                            "ON guest_forms (firebaseOrgId, formId)",
                    )
                    connection.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_guest_forms_status ON guest_forms (status)",
                    )
                    connection.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_guest_forms_lastModified ON guest_forms (lastModified)",
                    )
                    println("Migration 46→47 completed successfully")
                } catch (e: Exception) {
                    println("Migration 46→47 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        private val MIGRATION_47_48 = object : Migration(47, 48) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 47→48: guest form logo shape/invert")
                    connection.execSQL(
                        "ALTER TABLE guest_forms ADD COLUMN institutionLogoShape TEXT NOT NULL DEFAULT 'ROUNDED'",
                    )
                    connection.execSQL(
                        "ALTER TABLE guest_forms ADD COLUMN institutionLogoInvert INTEGER NOT NULL DEFAULT 0",
                    )
                    connection.execSQL(
                        "ALTER TABLE guest_forms ADD COLUMN guestLogoShape TEXT NOT NULL DEFAULT 'ROUNDED'",
                    )
                    connection.execSQL(
                        "ALTER TABLE guest_forms ADD COLUMN guestLogoInvert INTEGER NOT NULL DEFAULT 0",
                    )
                    println("Migration 47→48 completed successfully")
                } catch (e: Exception) {
                    println("Migration 47→48 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        private val MIGRATION_48_49 = object : Migration(48, 49) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 48→49: multi-response guest forms")
                    connection.execSQL(
                        "ALTER TABLE guest_forms ADD COLUMN allowMultipleResponses INTEGER NOT NULL DEFAULT 0",
                    )
                    connection.execSQL(
                        "ALTER TABLE guest_forms ADD COLUMN parentFormId TEXT NOT NULL DEFAULT ''",
                    )
                    connection.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_guest_forms_parentFormId ON guest_forms (parentFormId)",
                    )
                    println("Migration 48→49 completed successfully")
                } catch (e: Exception) {
                    println("Migration 48→49 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        private val MIGRATION_49_50 = object : Migration(49, 50) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 49→50: guest form ask email/phone toggles")
                    connection.execSQL(
                        "ALTER TABLE guest_forms ADD COLUMN askEmail INTEGER NOT NULL DEFAULT 1",
                    )
                    connection.execSQL(
                        "ALTER TABLE guest_forms ADD COLUMN askPhone INTEGER NOT NULL DEFAULT 1",
                    )
                    println("Migration 49→50 completed successfully")
                } catch (e: Exception) {
                    println("Migration 49→50 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        private val MIGRATION_50_51 = object : Migration(50, 51) {
            override fun migrate(connection: SQLiteConnection) {
                try {
                    println("Starting migration 50→51: guest form field label overrides")
                    connection.execSQL(
                        "ALTER TABLE guest_forms ADD COLUMN fieldLabelsJson TEXT NOT NULL DEFAULT ''",
                    )
                    println("Migration 50→51 completed successfully")
                } catch (e: Exception) {
                    println("Migration 50→51 failed: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
            MIGRATION_23_24,
            MIGRATION_24_25,
            MIGRATION_25_26,
            MIGRATION_26_27,
            MIGRATION_27_28,
            MIGRATION_28_29,
            MIGRATION_29_30,
            MIGRATION_30_31,
            MIGRATION_31_32,
            MIGRATION_32_33,
            MIGRATION_33_34,
            MIGRATION_34_35,
            MIGRATION_35_36,
            MIGRATION_36_37,
            MIGRATION_37_38,
            MIGRATION_38_39,
            MIGRATION_39_40,
            MIGRATION_40_41,
            MIGRATION_41_42,
            MIGRATION_42_43,
            MIGRATION_43_44,
            MIGRATION_44_45,
            MIGRATION_45_46,
            MIGRATION_46_47,
            MIGRATION_47_48,
            MIGRATION_48_49,
            MIGRATION_49_50,
            MIGRATION_50_51
        )
    }
}

