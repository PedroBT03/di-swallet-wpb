package di.swallet.wpb.presentation.domain

/**
 * DCQL Claims Path Pointer (OpenID4VP §7): ordered segments into the credential claim tree.
 *
 * Segments are object keys ([ClaimPathSegment.Key]), array indices ([ClaimPathSegment.Index]),
 * or array wildcards ([ClaimPathSegment.Wildcard] for JSON `null` in the path array).
 */
data class ClaimPath(val segments: List<ClaimPathSegment>) {
    init {
        require(segments.isNotEmpty()) { "ClaimPath must have at least one segment" }
    }

    /** PID rulebook-style dotted name when the path contains only key segments. */
    fun toDotNotation(): String = segments.joinToString(".") { it.toPathString() }

    fun leafKey(): String = segments.last().toPathString()

    companion object {
        fun key(name: String): ClaimPath = ClaimPath(listOf(ClaimPathSegment.Key(name)))

        fun fromDotNotation(dotted: String): ClaimPath =
            ClaimPath(dotted.split('.').filter { it.isNotBlank() }.map { ClaimPathSegment.Key(it) })

        fun fromDcqlPath(path: List<ClaimPathSegment>): ClaimPath? =
            path.takeIf { it.isNotEmpty() }?.let { ClaimPath(it) }
    }
}

sealed interface ClaimPathSegment {
    fun toPathString(): String

    data class Key(val name: String) : ClaimPathSegment {
        override fun toPathString(): String = name
    }

    data class Index(val position: Int) : ClaimPathSegment {
        override fun toPathString(): String = position.toString()
    }

    /** DCQL `null` in a path array — wildcard over array elements at this position. */
    data object Wildcard : ClaimPathSegment {
        override fun toPathString(): String = "*"
    }
}
