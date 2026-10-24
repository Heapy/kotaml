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

import io.kotest.matchers.shouldBe
import it.krzeminski.snakeyaml.engine.kmp.api.LoadSettings
import it.krzeminski.snakeyaml.engine.kmp.events.Event
import it.krzeminski.snakeyaml.engine.kmp.parser.ParserImpl
import it.krzeminski.snakeyaml.engine.kmp.scanner.StreamReader

class SnakeYamlEventMarksTest :
    FlatFunSpec({
        // Exercise the dependency itself, including synthetic empty scalars and collection ends.
        val documents =
            listOf(
                "",
                "one\n---\ntwo",
                "---\n...\n---\n",
                "# comment\nitems: &items\n- one\n- {key: [two, three], empty: }\ncopy: *items\ntagged: !!str\n",
                "? [one, two]\n: value\nblock: |\n  text\nquoted: 'text'\n",
            )
        for (useMarks in listOf(true, false)) {
            test("SnakeYAML emits all event types with marks present exactly when useMarks=$useMarks") {
                val seen = mutableSetOf<Event.ID>()
                for (document in documents) {
                    val settings = LoadSettings(useMarks = useMarks, parseComments = true)
                    val parser = ParserImpl(settings, StreamReader(settings, document))
                    while (parser.hasNext()) {
                        val event = parser.next()
                        seen += event.eventId
                        (event.startMark != null) shouldBe useMarks
                        (event.endMark != null) shouldBe useMarks
                    }
                }
                seen shouldBe Event.ID.entries.toSet()
            }
        }

        test("SnakeYAML marks use zero-based lines and columns") {
            val settings = LoadSettings(useMarks = true)
            val parser = ParserImpl(settings, StreamReader(settings, "one\n---\n  two"))
            val events =
                buildList {
                    while (parser.hasNext()) add(parser.next())
                }
            val secondDocument = checkNotNull(events.filter { it.eventId == Event.ID.DocumentStart }[1].startMark)
            secondDocument.line shouldBe 1
            secondDocument.column shouldBe 0
            val secondScalar = checkNotNull(events.filter { it.eventId == Event.ID.Scalar }[1].startMark)
            secondScalar.line shouldBe 2
            secondScalar.column shouldBe 2
        }
    })
