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

package io.github.ajichama.citrusbrowser.adblock.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 各読込の待機を個別に解放し、最後に依頼された読込だけを公開する。 */
internal class AdBlockLoadGate {
    internal class Ticket {
        private val completed = CountDownLatch(1)
        fun await() = completed.await()
        fun await(timeout: Long, unit: TimeUnit) = completed.await(timeout, unit)
        fun finish() = completed.countDown()
    }

    @Volatile private var current: Ticket? = null

    @Synchronized fun begin(): Ticket = Ticket().also { current = it }

    @Synchronized fun publish(ticket: Ticket, block: () -> Unit) {
        if (current === ticket) block()
    }

    @Synchronized fun finish(ticket: Ticket) {
        ticket.finish()
        if (current === ticket) current = null
    }

    /** 最大 [timeoutMs] だけ待つ。読み込みが終わっていれば true。 */
    fun awaitAtMost(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val pending = current ?: return true
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0 || !pending.await(remaining, TimeUnit.NANOSECONDS)) return current == null
            if (current == null) return true
        }
    }

    fun await() {
        while (true) {
            val pending = current ?: return
            pending.await()
            if (current == null) return
        }
    }
}
