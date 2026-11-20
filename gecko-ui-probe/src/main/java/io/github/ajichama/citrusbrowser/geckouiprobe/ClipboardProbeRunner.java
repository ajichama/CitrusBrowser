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

import android.os.Bundle;
import android.app.Activity;
import android.app.Instrumentation;

/** 本体向けの通常 runner と混ぜず、Java だけの外部 UI probe に限定する。 */
public final class ClipboardProbeRunner extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (!getContext().getPackageName().equals(getTargetContext().getPackageName())) {
            throw new IllegalStateException("Clipboard probe は test APK 自身を target にしてください。");
        }
        start();
    }

    @Override public void onStart() {
        super.onStart();
        Bundle result = new Bundle();
        MyFiltersClipboardProbe probe = null;
        try {
            probe = new MyFiltersClipboardProbe(this);
            probe.run();
            result.putString("probeStatus", "PASS");
            probe.appendDiagnostics(result);
            result.putInt("copiedChars", probe.copiedChars());
            result.putString("stream", "\nCitrus clipboard probe: PASS（非 JUnit・本文変更なし）\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("probeStatus", "FAIL");
            if (probe != null) probe.appendDiagnostics(result);
            result.putString("stage", probe == null ? "INIT" : probe.stage());
            result.putString("errorClass", error.getClass().getName());
            // framework 例外にも clipboard 本文や URI が含まれ得るため message は出力しない。
            result.putString("stream", "\nCitrus clipboard probe: FAIL（非 JUnit・本文変更なし）\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
