/*
 * Copyright (C) 2026 CitrusBrowser contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package io.github.ajichama.citrusbrowser.legacy.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ImportScriptTest {
    private fun script(name: String, namespace: String, code: String) = "// ==UserScript==\n// @name $name\n// @namespace $namespace\n// ==/UserScript==\n$code"
    @Test fun metadataIdentityPreservesExistingVersionAndCodeOnMerge() {
        assertEquals(ImportScript.identity(script("one", "space", "old")), ImportScript.identity(script("one", "space", "new")))
        assertNotEquals(ImportScript.identity(script("one", "space", "code")), ImportScript.identity(script("one", "other", "code")))
        assertEquals(ImportScript.identity(script("one", "space", "old")),
            ImportScript.identity(script("one", "space", "new").replace("// ==", "//==")))
    }
    @Test fun scriptsWithoutNameUseExactCodeIdentity() {
        assertEquals(ImportScript.identity("console.log(1)"), ImportScript.identity("console.log(1)"))
        assertNotEquals(ImportScript.identity("console.log(1)"), ImportScript.identity("console.log(2)"))
    }
    @Test fun unclosedOrOversizedMetadataAndCodeAreRejected() {
        listOf("// ==UserScript==\n// @name missing-end", "a".repeat(512 * 1024 + 1), "a\u0000b",
            "// ==UserScript==\n" + "// @name long\n".repeat(129) + "// ==/UserScript==")
            .forEach { assertThrows(IOException::class.java) { ImportScript.identity(it) } }
    }
}
