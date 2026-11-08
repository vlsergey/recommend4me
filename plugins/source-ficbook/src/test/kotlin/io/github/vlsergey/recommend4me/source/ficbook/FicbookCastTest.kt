package io.github.vlsergey.recommend4me.source.ficbook

import io.github.vlsergey.recommend4me.source.FacetValue
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/** The line "Пэйринг и персонажи" split by the site's habit: the entries by commas, a pairing's names by "/". */
class FicbookCastTest {
    @Test
    fun `a pairing by its slash, a character alone, the names as written`() {
        val cast =
            Ficbook()
                .cast(
                    listOf(
                        FacetValue("авелин/брайан", "Брайан/Авелин"),
                        FacetValue("фрида", "Фрида"),
                        FacetValue("бабушка рико", "Бабушка Рико"),
                        FacetValue("брайан", "Брайан"),
                    )
                )
        assertEquals(listOf("Брайан", "Авелин", "Фрида", "Бабушка Рико"), cast.characters)
        assertEquals(listOf(listOf("Брайан", "Авелин")), cast.pairings)
    }
}
