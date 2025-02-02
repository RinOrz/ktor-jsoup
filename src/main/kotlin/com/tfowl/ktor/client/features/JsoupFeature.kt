@file:Suppress("unused")

package com.tfowl.ktor.client.features

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retry
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.features.HttpClientFeature
import io.ktor.client.statement.HttpResponseContainer
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/**
 * [HttpClient] feature that deserialises response bodies into Jsoup's [Document]
 * class using a provided [Parser]
 *
 * By default [Html][ContentType.Text.Html] is deserialised using [Parser.htmlParser]
 * and [Xml][ContentType.Text.Xml] is deserialised using [Parser.xmlParser].
 *
 * Note: It will only deserialised registered content types and for receiving
 * [Document] or superclasses.
 *
 * @property parsers Registered parsers for content types
 */
class JsoupFeature internal constructor(val parsers: Map<ContentType, Parser>) {

    /**
     * [JsoupFeature] configuration that is used during installation
     */
    class Config {

        /**
         * [Parsers][Parser] that will be used for each [ContentType]
         *
         * Default registered are:
         *  - Html [ContentType.Text.Html]
         *  - Xml [ContentType.Text.Xml]·[ContentType.Application.Xml]
         */
        var parsers: MutableMap<ContentType, Parser> = mutableMapOf(
                ContentType.Text.Html to Parser.htmlParser(),
                ContentType.Text.Xml to Parser.xmlParser(),
                ContentType.Application.Xml to Parser.xmlParser()
        )
    }

    /**
     * Companion object for feature installation
     */
    companion object Feature : HttpClientFeature<Config, JsoupFeature> {
        override val key: AttributeKey<JsoupFeature> = AttributeKey("Jsoup")

        override fun prepare(block: Config.() -> Unit): JsoupFeature =
                JsoupFeature(Config().apply(block).parsers)

        override fun install(feature: JsoupFeature, scope: HttpClient) {
            scope.responsePipeline.intercept(HttpResponsePipeline.Transform) { (info, body) ->
                if (body !is ByteReadChannel)
                    return@intercept

                if (!info.type.java.isAssignableFrom(Document::class.java))
                    return@intercept

                val parser = feature.parsers
                    .filterKeys { context.response.contentType()?.match(it) == true }
                    .values.firstOrNull() ?: return@intercept

                val bodyText = body.readRemaining().readText()
                val baseUrl = context.request.url.toString()
                
                val parsedBody = flow {
                  emit(parser.parseInput(bodyText, baseUrl))
                }.retry(20) {
                  // In the case of high concurrency, some system errors may occur, so delay retrying
                  delay(5)
                  true
                }.first()
                
                proceedWith(HttpResponseContainer(info, parsedBody))
            }
        }
    }
}

/**
 * Install [JsoupFeature]
 */
@Suppress("FunctionName")
fun HttpClientConfig<*>.Jsoup(block: JsoupFeature.Config.() -> Unit = {}) {
    install(JsoupFeature, block)
}
