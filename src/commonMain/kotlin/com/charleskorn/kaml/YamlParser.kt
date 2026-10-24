/*

   Copyright 2018-2023 Charles Korn.
   Copyright 2026 Ruslan Ibrahimau.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

*/

package com.charleskorn.kaml

import com.charleskorn.kaml.internal.bufferedSource
import it.krzeminski.snakeyaml.engine.kmp.api.LoadSettings
import it.krzeminski.snakeyaml.engine.kmp.api.copy
import it.krzeminski.snakeyaml.engine.kmp.events.Event
import it.krzeminski.snakeyaml.engine.kmp.exceptions.MarkedYamlEngineException
import it.krzeminski.snakeyaml.engine.kmp.exceptions.ReaderException
import it.krzeminski.snakeyaml.engine.kmp.exceptions.YamlEngineException
import it.krzeminski.snakeyaml.engine.kmp.exceptions.YamlVersionException
import it.krzeminski.snakeyaml.engine.kmp.parser.ParserImpl
import it.krzeminski.snakeyaml.engine.kmp.scanner.StreamReader
import okio.IOException
import okio.Source

/**
 * Reads one YAML document as events, translating parser failures into path diagnostics.
 *
 * After consuming the root, call [ensureEndOfStreamReached] to reject trailing documents.
 * The caller retains ownership of the source. [YamlNodeReader] handles node construction and aliases.
 */
internal class YamlParser(
    reader: Source,
    codePointLimit: Int? = null,
) {
    internal constructor(source: String) : this(source.bufferedSource())

    private val dummyFileName = "DUMMY_FILE_NAME"
    private val loadSettings =
        LoadSettings(
            label = dummyFileName,
            useMarks = true,
        ).copy {
            if (codePointLimit != null) this.codePointLimit = codePointLimit
        }
    private val streamReader = StreamReader(loadSettings, reader)
    private val events = ParserImpl(loadSettings, streamReader)

    init {
        consumeEventOfType(Event.ID.StreamStart, YamlPath.root)

        if (peekEvent(YamlPath.root).eventId == Event.ID.StreamEnd) {
            throw EmptyYamlDocumentException("The YAML document is empty.", YamlPath.root)
        }

        consumeEventOfType(Event.ID.DocumentStart, YamlPath.root)
    }

    fun ensureEndOfStreamReached() {
        consumeEventOfType(Event.ID.DocumentEnd, YamlPath.root)
        consumeEventOfType(Event.ID.StreamEnd, YamlPath.root)
    }

    fun consumeEvent(path: YamlPath): Event = checkEvent(path) { events.next() }

    fun peekEvent(path: YamlPath): Event = checkEvent(path) { events.peekEvent() }

    fun consumeEventOfType(
        type: Event.ID,
        path: YamlPath,
    ) {
        val event = consumeEvent(path)

        if (event.eventId != type) {
            val startMark =
                checkNotNull(event.startMark) {
                    "SnakeYAML event ${event.eventId} has no start mark despite useMarks = true"
                }
            throw MalformedYamlException(
                "Unexpected ${event.eventId}, expected $type",
                path.withError(Location(startMark.line + 1, startMark.column + 1)),
            )
        }
    }

    private fun checkEvent(
        path: YamlPath,
        retrieve: () -> Event,
    ): Event {
        try {
            return retrieve()
        } catch (e: YamlEngineException) {
            // The engine wraps source I/O. Preserve its identity so callers can classify/retry it.
            val sourceFailure = e.cause
            if (sourceFailure is IOException) throw sourceFailure
            throw if (e is MarkedYamlEngineException) {
                translateYamlEngineException(e, path)
            } else {
                val message =
                    when (e) {
                        is ReaderException -> "Invalid YAML code point U+${e.codePoint.toString(16).uppercase().padStart(4, '0')} at code-point offset ${e.position}: ${e.message}"
                        is YamlVersionException -> "Unsupported YAML version ${e.message}."
                        else -> e.message ?: "YAML parsing failed."
                    }
                // Unmarked failures have no exact error mark; retain the last reader cursor.
                MalformedYamlException(message, path.withError(Location(streamReader.line + 1, streamReader.column + 1)), e)
            }
        }
    }

    private fun translateYamlEngineException(
        e: MarkedYamlEngineException,
        path: YamlPath,
    ): MalformedYamlException {
        val updatedMessage = StringBuilder()

        val context = e.context
        val contextMark = e.contextMark

        if (context != null && contextMark != null) {
            val snippet = contextMark.createSnippet(4, Int.MAX_VALUE)
            updatedMessage.append(
                """
                    |$context
                    | at line ${contextMark.line + 1}, column ${contextMark.column + 1}:
                    |$snippet
                    |
                """.trimMargin(),
            )
        }

        val problemMark = e.problemMark
        if (problemMark != null) {
            val problem = translateYamlEngineExceptionMessage(e.problem)
            val snippet = problemMark.createSnippet(4, Int.MAX_VALUE)
            updatedMessage.append(
                """
                    |$problem
                    | at line ${problemMark.line + 1}, column ${problemMark.column + 1}:
                    |$snippet
                """.trimMargin(),
            )
        }

        val updatedPath =
            if (problemMark != null) {
                path.withError(Location(problemMark.line + 1, problemMark.column + 1))
            } else {
                path
            }

        return MalformedYamlException(
            message = updatedMessage.toString().ifEmpty { translateYamlEngineExceptionMessage(e.problem) },
            path = updatedPath,
            cause = e,
        )
    }

    private fun translateYamlEngineExceptionMessage(message: String): String =
        when (message) {
            "mapping values are not allowed here",
            "expected <block end>, but found '<block sequence start>'",
            "expected <block end>, but found '<block mapping start>'",
            -> {
                "$message (is the indentation level of this line or a line nearby incorrect?)"
            }

            else -> {
                message
            }
        }
}
