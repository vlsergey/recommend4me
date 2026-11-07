package io.github.vlsergey.recommend4me.setvector

import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.vector.Vectors
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(SetDirections::class.java)

/**
 * THE DIRECTIONS OF A KIND OF SETS, FITTED per content type on a sample of its catalogue and kept: fitted again, they
 * would make every stored vector a vector of another space. They are fitted again only when the encoder of the members
 * changes or the catalogue grew; the vectors carry the id of the directions they are of.
 */
@Component
class SetDirections(private val members: SetMembers, private val workload: Workload) {

    /**
     * The directions of the kind of sets, fitted on what the catalogue has: on every member while it has no more than
     * [SAMPLE], else on that many drawn at random. Fitted again whenever that number changes — the catalogue grew — and
     * then every item's set is made again by them. None while the catalogue has fewer than two members: one has no
     * spread to find a direction in.
     */
    fun inUse(type: TypeStore, kind: SetKind, encoder: String): StoredEmbedding? {
        val have = type.sources.associateWith { s -> members.sizes(s, kind, encoder).values.sum() }
        val total = have.values.sum()
        val count = minOf(total, SAMPLE)
        val stored = type.embeddings.find(kind)?.takeIf { it.encoder == encoder }
        // Directions fitted before the number was kept were fitted on a full sample: still so while the catalogue fills
        // one
        val fittedOn = stored?.members ?: if (stored != null && total >= SAMPLE) SAMPLE else null
        val current = stored != null && fittedOn == count
        // Every source gives its share of the sample, all it has while the catalogue is within it
        val sample =
            if (current || count < 2) emptyList()
            else
                have
                    .filterValues { it > 0 }
                    .flatMap { (s, n) ->
                        members.sample(
                            s,
                            kind,
                            encoder,
                            if (total <= SAMPLE) n else (SAMPLE.toLong() * n / total).toInt(),
                        )
                    }
        return if (sample.size < 2) stored else fitted(type, kind, encoder, sample, count)
    }

    /** New directions of [kind] fitted on [sample], drawn of [count] members of the catalogue, and kept. */
    private fun fitted(
        type: TypeStore,
        kind: SetKind,
        encoder: String,
        sample: List<FloatArray>,
        count: Int,
    ): StoredEmbedding {
        val embedding =
            workload.run(Work("setDirections", type = type.id, facet = kind.key), mapOf("catalogueGrew" to 1)) {
                SetEmbedding.fit(sample, minOf(SetEmbedding.DIRECTIONS, sample.size - 1))
            }
        val id = Vectors.keyOf("${type.id}:${kind.name}:$encoder:${System.nanoTime()}")
        type.embeddings.save(kind, id, encoder, embedding, count, Instant.now())
        log.info(
            "{}: {} directions of {} fitted on {} members",
            type.id,
            embedding.directions.size,
            kind.key,
            sample.size,
        )
        return StoredEmbedding(id, encoder, embedding, count)
    }

    private companion object {
        /** The members of the catalogue held at once to fit directions on: memory, not meaning. */
        const val SAMPLE = 20_000
    }
}
