package io.github.vlsergey.recommend4me.contenttype

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The content types the application knows by itself; a plugin may declare more. */
@Configuration(proxyBeanMethods = false)
class BuiltInTypes {

    @Bean
    fun games() = ContentType(
        id = "games",
        title = "Игры",
        grades = listOf("Не нравится", "Можно поиграть", "В целом понравилась", "Очень хорошая", "Хочу ещё"),
        verb = "играть",
    )

    @Bean
    fun books() = ContentType(
        id = "books",
        title = "Книги",
        grades = listOf("Не нравится", "Можно почитать", "В целом понравилась", "Очень хорошая", "Хочу ещё"),
        verb = "читать",
    )
}
