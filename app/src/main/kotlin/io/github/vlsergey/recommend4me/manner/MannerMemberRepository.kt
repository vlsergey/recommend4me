package io.github.vlsergey.recommend4me.manner

import io.github.vlsergey.recommend4me.database.WholeKeys
import io.github.vlsergey.recommend4me.database.model.tables.references.MANNER_MEMBER
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext

/** A normalized author's or series' group of works a manner is kept of: its kind and key. */
data class GroupRef(val kind: MannerGroup, val key: String)

/**
 * THE ITEMS EVERY GROUP'S MANNER WAS LAST MADE OF ([MannerGroups]), in the model database of the content type: of an
 * author the items naming them, of a series the items of its works. Read by the items, to find the groups they were of
 * as their authors or series change; by the groups, for the items of a group one of them left.
 */
class MannerMemberRepository(private val db: DSLContext) {

    /** Of the [items], the groups each was of as their manners were last made. */
    fun groupsOf(items: Collection<ItemKey>): Map<ItemKey, Set<GroupRef>> {
        val out = HashMap<ItemKey, MutableSet<GroupRef>>()
        val m = MANNER_MEMBER
        items.groupBy({ it.source }, { it.id }).forEach { (source, ids) ->
            ids.distinct().chunked(BATCH).forEach { chunk ->
                db.select(m.ITEM_ID, m.KIND, m.GROUP_KEY)
                    .from(m)
                    .where(WholeKeys.of(m.SOURCE, m.ITEM_ID, source, chunk))
                    .fetch {
                        out.getOrPut(ItemKey(source, it.value1()!!)) { HashSet() } +=
                            GroupRef(MannerGroup.valueOf(it.value2()!!), it.value3()!!)
                    }
            }
        }
        return out
    }

    /** The items every one of the [groups] was last made of. */
    fun itemsOf(groups: Collection<GroupRef>): Map<GroupRef, List<ItemKey>> {
        val out = HashMap<GroupRef, MutableList<ItemKey>>()
        val m = MANNER_MEMBER
        groups.groupBy({ it.kind }, { it.key }).forEach { (kind, keys) ->
            keys.distinct().chunked(BATCH).forEach { chunk ->
                db.select(m.GROUP_KEY, m.SOURCE, m.ITEM_ID)
                    .from(m)
                    .where(WholeKeys.of(m.KIND, m.GROUP_KEY, kind.name, chunk))
                    .fetch {
                        out.getOrPut(GroupRef(kind, it.value1()!!)) { ArrayList() } +=
                            ItemKey(it.value2()!!, it.value3()!!)
                    }
            }
        }
        return out
    }

    /** The [group] made of the [items] now, in place of those it was made of. */
    fun replace(group: GroupRef, items: Collection<ItemKey>) {
        val m = MANNER_MEMBER
        db.transaction { c ->
            val tx = c.dsl()
            tx.deleteFrom(m).where(m.KIND.eq(group.kind.name), m.GROUP_KEY.eq(group.key)).execute()
            items.distinct().chunked(BATCH).forEach { chunk ->
                tx.batch(
                        chunk.map { item ->
                            tx.insertInto(m)
                                .set(m.KIND, group.kind.name)
                                .set(m.GROUP_KEY, group.key)
                                .set(m.SOURCE, item.source)
                                .set(m.ITEM_ID, item.id)
                        }
                    )
                    .execute()
            }
        }
    }

    /** The item [item], removed, of no group. */
    fun forget(item: ItemKey) {
        val m = MANNER_MEMBER
        db.deleteFrom(m).where(m.SOURCE.eq(item.source), m.ITEM_ID.eq(item.id)).execute()
    }

    private companion object {
        /** The keys of one statement: the length of a query's list, not a meaning. */
        const val BATCH = 500
    }
}
