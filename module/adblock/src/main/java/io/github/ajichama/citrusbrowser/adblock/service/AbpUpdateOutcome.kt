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

/** 変更なし・削除済み・再試行対象を失敗の Boolean にまとめない。 */
internal enum class AbpUpdateOutcome {
    Changed, NotModified, Skipped, RetryableFailure, PermanentFailure;

    companion object {
        fun forHttpError(code: Int): AbpUpdateOutcome =
            if (code == 408 || code == 429 || code in 500..599) RetryableFailure else PermanentFailure

        fun aggregate(results: List<AbpUpdateOutcome>): AbpUpdateOutcome = when {
            RetryableFailure in results -> RetryableFailure
            PermanentFailure in results -> PermanentFailure
            Changed in results -> Changed
            NotModified in results -> NotModified
            else -> Skipped
        }
    }
}
