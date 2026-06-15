/**
 * DCQL claim path pointers used to locate claims inside credential payloads.
 */

package di.swallet.wpb.presentation.domain

/**
 * Ordered path into a credential claim tree per OpenID4VP DCQL (section 7).
 * Segments may be object keys, array indices, or wildcards for array elements.
 */
data class ClaimPath(val segments: List<ClaimPathSegment>) {
    init {
        require(segments.isNotEmpty()) { "ClaimPath must have at least one segment" }
    }

    /** Returns a dotted claim name when every segment is a key. */
    fun toDotNotation(): String = segments.joinToString(".") { it.toPathString() }

    /** Returns the final segment as a string. */
    fun leafKey(): String = segments.last().toPathString()

    companion object {
        /** Builds a path from a single claim key. */
        fun key(name: String): ClaimPath = ClaimPath(listOf(ClaimPathSegment.Key(name)))

        /** Parses a dotted claim name such as `given_name` or `address.street`. */
        fun fromDotNotation(dotted: String): ClaimPath =
            ClaimPath(dotted.split('.').filter { it.isNotBlank() }.map { ClaimPathSegment.Key(it) })

        /** Builds a path from DCQL path segments, or returns null when empty. */
        fun fromDcqlPath(path: List<ClaimPathSegment>): ClaimPath? =
            path.takeIf { it.isNotEmpty() }?.let { ClaimPath(it) }
    }
}

sealed interface ClaimPathSegment {
    /** Serializes this segment for dot notation or logging. */
    fun toPathString(): String

    /** Object property name in the claim tree. */
    data class Key(val name: String) : ClaimPathSegment {
        /** Renders the property name as a path segment. */
        override fun toPathString(): String = name
    }

    /** Zero-based array index in the claim tree. */
    data class Index(val position: Int) : ClaimPathSegment {
        /** Renders the array index as a path segment. */
        override fun toPathString(): String = position.toString()
    }

    /** Wildcard over all array elements at this position (DCQL `null` in the path). */
    data object Wildcard : ClaimPathSegment {
        /** Renders the wildcard marker used for any array element. */
        override fun toPathString(): String = "*"
    }
}
