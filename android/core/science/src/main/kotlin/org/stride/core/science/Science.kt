package org.stride.core.science

/**
 * Kotlin port of `packages/core/src/science` (ADR 0010). Every public function
 * mirrors a TypeScript function and is parity-tested against the goldens in
 * `contracts/science-golden/`.
 */
object Science {
    /** Bumped whenever the port changes in a way that requires recomputing stored metrics. */
    const val ENGINE_VERSION = 1
}
