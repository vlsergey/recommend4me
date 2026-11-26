package io.github.vlsergey.recommend4me.item

/**
 * An item — a work as one source knows it — by its source and the application's own number of it in the source's
 * database ([id]): never the site's id of it, which is the source's plugin's alone and may change.
 */
data class ItemKey(val source: String, val id: Long) {
    override fun toString() = "$source/$id"

    companion object {
        /** The reverse of [toString]. */
        fun parse(value: String): ItemKey {
            val at = value.lastIndexOf('/')
            require(at > 0) { "Not an item key: $value" }
            return ItemKey(value.substring(0, at), value.substring(at + 1).toLong())
        }
    }
}
