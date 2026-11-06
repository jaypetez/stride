package stride

/**
 * Per-module Kover line-coverage floors (AUD-038), enforced by each module's
 * `koverVerify` in `./gradlew -p android check`, next to the 60% aggregate rule
 * in the root build. Tiers: `:core:*` 80%, `:feature:*` 50%, `:app` 60%.
 *
 * [RATCHETS] hold modules that are still below their tier at a floor just under
 * today's coverage. Raise a ratchet as tests land and delete it once the module
 * reaches its tier; never lower one silently.
 */
internal object CoverageFloors {
    private const val CORE = 80
    private const val FEATURE = 50
    private const val APP = 60

    /** Below-tier modules: floor = a little under the coverage measured on 2026-11-05. */
    private val RATCHETS = mapOf(
        ":core:data" to 75,
        ":core:datastore" to 55,
        ":core:database" to 40,
        ":core:ui" to 40,
        // Dispatchers, clock and DI glue: exercised through other modules' tests.
        ":core:common" to 20
    )

    /**
     * No floor: test fixtures (`core:testing`, `core:data-testing`) and modules
     * with no logic of their own whose code is covered by other modules' tests
     * (`core:model` data classes, `core:navigation` route keys).
     */
    private val EXEMPT = setOf(":core:testing", ":core:data-testing", ":core:model", ":core:navigation")

    fun forPath(path: String): Int? = when {
        path in EXEMPT -> null
        path in RATCHETS -> RATCHETS.getValue(path)
        path.startsWith(":core:") -> CORE
        path.startsWith(":feature:") -> FEATURE
        path == ":app" -> APP
        else -> null
    }
}
