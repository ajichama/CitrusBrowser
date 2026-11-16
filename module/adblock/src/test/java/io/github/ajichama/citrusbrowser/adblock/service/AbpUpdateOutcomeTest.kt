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

package io.github.ajichama.citrusbrowser.adblock.service

import org.junit.Assert.*
import org.junit.Test

class AbpUpdateOutcomeTest {
    @Test
    fun onlyTemporaryHttpFailuresAreRetried() {
        for (code in listOf(408, 429, 500, 503, 599)) assertEquals(AbpUpdateOutcome.RetryableFailure, AbpUpdateOutcome.forHttpError(code))
        for (code in listOf(400, 401, 403, 404, 410)) assertEquals(AbpUpdateOutcome.PermanentFailure, AbpUpdateOutcome.forHttpError(code))
    }

    @Test
    fun bulkChangesDoNotHideFailuresAndUnchangedSubscriptionsAreSuccessful() {
        val changed = listOf(AbpUpdateOutcome.Changed, AbpUpdateOutcome.Skipped)
        assertEquals(AbpUpdateOutcome.Changed, AbpUpdateOutcome.aggregate(changed))
        assertEquals(AbpUpdateOutcome.PermanentFailure, AbpUpdateOutcome.aggregate(changed + AbpUpdateOutcome.PermanentFailure))
        assertEquals(AbpUpdateOutcome.RetryableFailure, AbpUpdateOutcome.aggregate(changed + listOf(AbpUpdateOutcome.PermanentFailure, AbpUpdateOutcome.RetryableFailure)))
        assertEquals(AbpUpdateOutcome.NotModified, AbpUpdateOutcome.aggregate(listOf(AbpUpdateOutcome.NotModified, AbpUpdateOutcome.Skipped)))
        assertEquals(AbpUpdateOutcome.Skipped, AbpUpdateOutcome.aggregate(emptyList()))
    }
}