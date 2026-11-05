package org.stride.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration harness (ANDF-004 / MVP-006). With a single schema version it proves
 * the exported v1 schema creates a valid database; each future version adds a
 * `migrate(N -> N+1)` test here using `helper.runMigrationsAndValidate`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), StrideDatabase::class.java)

    @Test
    fun version1SchemaCreates() {
        helper.createDatabase("migration-test", 1).use { db ->
            db.execSQL(
                "INSERT INTO activity (id, name, sport, start_epoch_sec, local_date, time_zone, duration_sec, " +
                    "moving_time_sec, distance_m, " +
                    "elevation_gain_m, avg_hr, max_hr, avg_speed_mps, max_speed_mps, avg_cadence_spm, calories, source, has_streams) " +
                    "VALUES (1, 'Run', 'RUN', 0, '1970-01-01', NULL, 60, NULL, 100, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'GARMIN', 0)"
            )
            db.query("SELECT COUNT(*) FROM activity").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            assertEquals(1, db.version)
        }
    }
}
