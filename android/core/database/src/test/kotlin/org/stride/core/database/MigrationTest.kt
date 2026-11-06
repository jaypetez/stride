package org.stride.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration harness (ANDF-004 / MVP-006). With a single schema version it proves
 * the exported v1 schema creates a valid database; each future version adds a
 * `migrate{N-1}To{N}` test here using `helper.runMigrationsAndValidate` and
 * raises [HIGHEST_TESTED_VERSION]. [everyVersionBumpShipsATestedMigration] fails
 * the build when `@Database(version = …)` is bumped without that (AUD-038).
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

    @Test
    fun everyVersionBumpShipsATestedMigration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, StrideDatabase::class.java).allowMainThreadQueries().build()
        val current = try {
            db.openHelper.readableDatabase.version
        } finally {
            db.close()
        }
        assertTrue(
            current <= HIGHEST_TESTED_VERSION,
            "StrideDatabase is at version $current but MigrationTest only covers up to $HIGHEST_TESTED_VERSION: " +
                "add a migrate${current - 1}To$current test (helper.runMigrationsAndValidate) and raise HIGHEST_TESTED_VERSION"
        )
        val testNames = javaClass.methods.filter { it.isAnnotationPresent(Test::class.java) }.map { it.name }.toSet()
        val assets = context.assets.list(StrideDatabase::class.java.name).orEmpty().toSet()
        for (version in 1..current) {
            assertTrue(
                assets.contains("$version.json"),
                "exported schema $version.json is missing from core/database/schemas"
            )
            if (version > 1) {
                assertTrue("migrate${version - 1}To$version" in testNames, "no migrate${version - 1}To$version test")
            }
        }
    }

    private companion object {
        /** Raise together with `@Database(version)` and a new `migrate{N-1}To{N}` test. */
        const val HIGHEST_TESTED_VERSION = 1
    }
}
