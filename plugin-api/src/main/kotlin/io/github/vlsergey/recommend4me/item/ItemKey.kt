package io.github.vlsergey.recommend4me.item

/** An item — a work as one source knows it — by its source and the source's own id of it. */
data class ItemKey(val source: String, val id: String) {
    override fun toString() = "$source/$id"

    companion object {
        /** The reverse of [toString]: the source's id may itself hold slashes. */
        fun parse(value: String): ItemKey {
            val at = value.indexOf('/')
            require(at > 0) { "Not an item key: $value" }
            return ItemKey(value.substring(0, at), value.substring(at + 1))
        }
    }
}
