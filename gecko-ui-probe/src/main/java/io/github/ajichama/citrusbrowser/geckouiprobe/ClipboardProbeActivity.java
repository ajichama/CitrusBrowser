/*
 * Copyright (C) 2026 CitrusBrowser contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ajichama.citrusbrowser.geckouiprobe;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import java.util.concurrent.CancellationException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 背景 clipboard 権限を追加せず、test UID の一時 focus 中だけ操作して元の画面へ戻る。 */
public final class ClipboardProbeActivity extends Activity {
    interface Action<T> { T run(ClipboardManager clipboard) throws Exception; }
    private static final AtomicReference<Request<?>> PENDING = new AtomicReference<>();
    private Request<?> request;
    private boolean performed;

    private static final int NOT_STARTED = 0, STARTED = 1, ABANDONED = 2;

    private static final class Request<T> {
        volatile ClipboardManager clipboard;
        volatile ClipboardProbeActivity owner;
        // 待機期限と UI thread 上の開始が交差しても、書込の有無を呼出側が確定できるようにする。
        final AtomicInteger state = new AtomicInteger(NOT_STARTED);
        final FutureTask<T> task;
        Request(Action<T> action) {
            task = new FutureTask<>(() -> {
                if (!state.compareAndSet(NOT_STARTED, STARTED)) throw new CancellationException("clipboard 操作は放棄済みです。");
                return action.run(clipboard);
            });
        }
    }

    static <T> T run(Context context, Action<T> action) throws Exception {
        Request<T> request = new Request<>(action);
        if (!PENDING.compareAndSet(null, request)) throw new IllegalStateException("clipboard 操作が重複しています。");
        try {
            context.startActivity(new Intent(context, ClipboardProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            try {
                return request.task.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                // 未開始なら以後実行させない。開始済みなら clipboard 書込の途中で後始末へ進まないよう完了まで待つ。
                if (request.state.compareAndSet(NOT_STARTED, ABANDONED)) throw timeout;
                return request.task.get(5, TimeUnit.SECONDS);
            }
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError("clipboard 操作が失敗しました。", cause);
        } finally {
            request.task.cancel(false);
            PENDING.compareAndSet(request, null);
            // focus 待ちで timeout した場合も、自分が作った relay window だけを片付ける。
            ClipboardProbeActivity owner = request.owner;
            if (owner != null) owner.runOnUiThread(() -> { if (!owner.isDestroyed()) owner.finish(); });
        }
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        request = PENDING.getAndSet(null);
        if (request == null) { finish(); return; }
        request.owner = this;
        View focus = new View(this);
        focus.setFocusableInTouchMode(true);
        setContentView(focus);
        focus.requestFocus();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused || request == null || performed) return;
        performed = true;
        try {
            request.clipboard = getSystemService(ClipboardManager.class);
            request.task.run();
        } finally {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    @Override protected void onDestroy() {
        if (request != null) { request.task.cancel(false); request.owner = null; }
        super.onDestroy();
    }
}
