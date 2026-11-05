package org.stride.core.garmin

internal object Fixtures {
    fun text(name: String): String =
        requireNotNull(javaClass.classLoader.getResource("fixtures/$name")) { "missing fixture $name" }.readText()

    /** A syntactically valid JWT with the given `exp` claim (signature is irrelevant to the client). */
    fun jwt(exp: Long): String {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
        val payload = enc.encodeToString("""{"sub":"fixture","exp":$exp}""".toByteArray())
        return "$header.$payload.c2ln"
    }
}
