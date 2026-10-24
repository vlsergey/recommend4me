package io.github.vlsergey.recommend4me.pairing

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.PAIRING
import org.jooq.DSLContext
import java.time.Instant

/** The pairings the user made of two characters of the dictionary, known before any work has them. */
class PairingRepository(private val db: DSLContext) {

    fun add(key: String, now: Instant) {
        db.insertInto(PAIRING).set(PAIRING.PAIRING_KEY, key).set(PAIRING.MADE_AT, now).onDuplicateKeyIgnore().execute()
    }

    /** Every pairing made: key to when. */
    fun all(): Map<String, Instant> = db.selectFrom(PAIRING).fetchMap({ it.pairingKey!! }, { it.madeAt!! })
}
