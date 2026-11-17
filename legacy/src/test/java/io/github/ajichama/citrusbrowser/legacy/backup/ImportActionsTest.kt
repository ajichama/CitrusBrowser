/*
 * Copyright (C) 2026 CitrusBrowser contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.ajichama.citrusbrowser.legacy.backup

import org.junit.Assert.*
import org.junit.Test

class ImportActionsTest {
    @Test fun startActivityNumericSpellingsAndNestedActionsCannotBypassUnresolvedReferenceRejection() {
        for (number in listOf("50005", "50005.0", "5.0005e4")) {
            val action = "[$number,{\"0\":\"#Intent;component=fixture/fixture.Activity;l.id=1;end\",\"1\":\"title #Intent; fragment\"}]"
            var callbacks = 0
            val raw = "[[${io.github.ajichama.citrusbrowser.legacy.action.SingleAction.CUSTOM_ACTION},{\"0\":[$action],\"1\":\"nested\"}]]"
            assertNull(ImportActions.validate("app_action1_hbtn/0.dat", raw.toByteArray()) { callbacks++; null })
            assertEquals(1, callbacks)
        }
    }
    @Test fun knownToolbarActionsAreValidatedAndUnknownIdsKeepTheCurrentFileUntouched() {
        val supported = "[[[1020,null]],[],[],[],[],[]]".toByteArray()
        assertNotNull(ImportActions.validate("app_action1_btn/1.dat", supported))
        assertNull(ImportActions.validate("app_action1_btn/1.dat", "[[[999999,null]],[],[],[],[],[]]".toByteArray()))
    }
    @Test fun nestedUnknownCustomActionsAreAlsoExcludedAndMalformedJsonIsRejected() {
        assertNull(ImportActions.validate("app_action1_btn/1.dat", """[[[100000,{"0":[[999999,null]],"1":"custom"}]],[],[],[],[],[]]""".toByteArray()))
        assertThrows(Exception::class.java) { ImportActions.validate("app_action1_btn/1.dat", "[[[1020,".toByteArray()) }
    }
}
