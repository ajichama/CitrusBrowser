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

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 既存 preview process/tab を起動・終了・読み替えせず、通常キーによるコピーだけを試す。 */
@SuppressWarnings("deprecation")
final class MyFiltersClipboardProbe {
    private static final String PREVIEW = "io.github.ajichama.citrusbrowser.geckopreview";
    private static final String TAG = "MyFiltersClipboardProbe";
    private final Context context;
    private final UiAutomation automation;
    private boolean openedSettings;
    private volatile ClipData original;
    // clipboard 操作は relay Activity の UI thread で行うため、待機期限と交差しても読めるよう volatile にする。
    private volatile boolean clipboardTouched;
    // Ctrl+C を送ったが、その結果を自分のコピーとして確定できていない間 true。
    private volatile boolean copyUnresolved;
    private volatile boolean copyResponseUnresolved;
    private volatile String sentinel;
    private volatile Stamp expected;
    private String stage = "START";
    private String failedStage;
    private int copiedChars;
    private boolean originalClipRead;
    private volatile String restoreStatus = "UNTOUCHED";
    private String cleanupFailedStage = "NONE";
    private String activeRootKind = "UNKNOWN";
    private String focusedRootKind = "UNKNOWN";
    private String failedActiveRootKind = "NONE";
    private String failedFocusedRootKind = "NONE";
    private int copyAttempt;
    private int editorCandidates, editorDepth, editorChildren;
    private boolean editorFocusable, editorFocused, editorEditable, editorFocusAction, editorClickAction;
    private String editorBounds = "NONE";

    MyFiltersClipboardProbe(Instrumentation instrumentation) {
        context = instrumentation.getTargetContext();
        automation = instrumentation.getUiAutomation();
    }

    String stage() { return failedStage == null ? stage : failedStage; }
    int copiedChars() { return copiedChars; }

    void appendDiagnostics(Bundle result) {
        result.putBoolean("originalClipRead", originalClipRead);
        result.putBoolean("clipboardTouched", clipboardTouched);
        result.putString("clipboardRestore", restoreStatus);
        result.putBoolean("copyResponseUnresolved", copyResponseUnresolved);
        result.putString("cleanupFailedStage", cleanupFailedStage);
        result.putString("activeRootKind", activeRootKind);
        result.putString("focusedRootKind", focusedRootKind);
        result.putString("failedActiveRootKind", failedActiveRootKind);
        result.putString("failedFocusedRootKind", failedFocusedRootKind);
        result.putInt("copyAttempt", copyAttempt);
        result.putInt("editorCandidates", editorCandidates);
        result.putInt("editorDepth", editorDepth);
        result.putInt("editorChildren", editorChildren);
        result.putBoolean("editorFocusable", editorFocusable);
        result.putBoolean("editorFocused", editorFocused);
        result.putBoolean("editorEditable", editorEditable);
        result.putBoolean("editorFocusAction", editorFocusAction);
        result.putBoolean("editorClickAction", editorClickAction);
        result.putString("editorBounds", editorBounds);
    }

    void run() throws Throwable {
        assertTrue("self-target runner 以外では操作しません。",
                context.getPackageName().equals("io.github.ajichama.citrusbrowser.geckouiprobe"));
        int oldFlags = automation.getServiceInfo().flags;
        Throwable primary = null;
        try {
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            awaitPreview("INITIAL");
            if (!hasId("userFilters")) {
                stage = "CHECK_EXISTING_OVERLAY";
                assertFalse("既存の設定編集画面は置き換えません。先に通常 UI で MyFilters を表示してください。", hasNativeClose());
                stage = "OPEN_NATIVE_UBO_SETTINGS";
                clickLabel("UBO 設定");
                openedSettings = true;
                await("MyFilters の表示", 30000, () -> {
                    if (hasId("userFilters")) return true;
                    clickOptionalLabel("マイフィルター", "My filters");
                    return false;
                });
            }
            stage = "MYFILTERS_STATE";
            FilterState before = filterState();
            assertFalse("未適用編集があれば中止します。", before.apply);
            assertFalse("未適用編集があれば中止します。", before.revert);

            // foreground の正規 ClipboardManager API で取得する。背景読取拒否を空本文と混同しない。
            stage = "元 clipboard の取得";
            original = ClipboardProbeActivity.run(context, clipboard -> {
                boolean had = clipboard.hasPrimaryClip();
                ClipData value = clipboard.getPrimaryClip();
                if (had && value == null) throw new IllegalStateException("元 clipboard を読み取れません。");
                if (value == null && Build.VERSION.SDK_INT < 28) throw new IllegalStateException("空 clipboard を復旧できない OS では変更しません。");
                if (value != null) for (int index = 0; index < value.getItemCount(); index++) {
                    ClipData.Item item = value.getItemAt(index);
                    // 上書きで URI grant が失効すると元の形式を復旧できないため、触れる前に中止する。
                    if (item.getUri() != null || item.getIntent() != null) {
                        throw new IllegalStateException("URI または Intent を含む元 clipboard は変更しません。");
                    }
                }
                return value;
            });
            originalClipRead = true;
            awaitPreview("AFTER_ORIGINAL_CLIP_READ");
            String first = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                copyAttempt = attempt + 1;
                stage = "コピー検出用 clipboard の準備 " + (attempt + 1);
                sentinel = "CITRUS_COPY_PROBE_" + UUID.randomUUID();
                ClipboardProbeActivity.run(context, clipboard -> {
                    // 書込より前に記録し、書込直後の待機期限切れでも UNTOUCHED と誤報告しない。
                    clipboardTouched = true;
                    restoreStatus = "PENDING";
                    clipboard.setPrimaryClip(ClipData.newPlainText("Citrus clipboard probe", sentinel));
                    expected = Stamp.of(clipboard.getPrimaryClip());
                    return null;
                });
                awaitPreview("AFTER_SENTINEL_" + (attempt + 1));
                focusEditor();
                chord(KeyEvent.KEYCODE_A);
                // コピー結果を確定するまでは、clipboard 上の未知の内容が自分のコピーである可能性を残す。
                copyUnresolved = true;
                copyResponseUnresolved = true;
                chord(KeyEvent.KEYCODE_C);
                stage = "CodeMirror コピー結果の取得 " + (attempt + 1);
                Stamp copied = ClipboardProbeActivity.run(context, clipboard -> {
                    long deadline = SystemClock.elapsedRealtime() + 1500;
                    do {
                        Stamp current = Stamp.of(clipboard.getPrimaryClip());
                        if (current != null && !current.text.equals(sentinel)) return current;
                        SystemClock.sleep(40);
                    } while (SystemClock.elapsedRealtime() < deadline);
                    throw new AssertionError("コピー応答がありません。空本文とは解釈しません。");
                });
                copyResponseUnresolved = false;
                if (first == null) first = copied.text;
                else assertTrue("2 回の全文コピーが一致しません。本文は変更しません。", Objects.equals(first, copied.text));
                // 2 回目が異なる場合は expected を自分の sentinel のままにし、他の内容を復旧で上書きしない。
                // その場合 copyUnresolved は true のまま残し、後始末で他者の更新とは別の結果値にする。
                expected = copied;
                copyUnresolved = false;
                awaitPreview("AFTER_COPY_" + (attempt + 1));
                focusEditor();
            }
            stage = "VERIFY_COPY_STATE";
            assertTrue("コピーで既存状態が変わりました。", before.same(filterState()));
            assertNotNull("コピー応答がありません。", first);
            copiedChars = first.length();
            Log.i(TAG, "twoCopiesEqual=true; rawChars=" + first.length() + "; rulesChanged=false; mainProcessStartedOrFinished=false");
        } catch (Throwable error) {
            failedStage = stage;
            failedActiveRootKind = activeRootKind;
            failedFocusedRootKind = focusedRootKind;
            primary = error;
            throw error;
        } finally {
            Throwable cleanup = null;
            String cleanupPhase = "CLIPBOARD_RESTORE";
            if (clipboardTouched) try {
                ClipboardProbeActivity.run(context, clipboard -> {
                    ClipData current = clipboard.getPrimaryClip();
                    Stamp stamp = Stamp.of(current);
                    // 通知待ち失敗でも自分の固有 sentinel だけは認識して復旧する。
                    boolean ownSentinel = stamp != null && stamp.text.equals(sentinel) && "Citrus clipboard probe".equals(stamp.label);
                    if (ownSentinel || (expected != null && expected.same(stamp))) {
                        if (original == null && Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip(); else if (original != null) clipboard.setPrimaryClip(original);
                        // 応答のないコピーが復旧直後に遅れて届いた場合は、他者の更新と区別できないため上書きし直さない。
                        restoreStatus = copyUnresolved && changedAfterRestore(clipboard) ? "PROBE_COPY_LEFT_UNVERIFIED" : "RESTORED";
                    } else if (copyUnresolved) {
                        // 不一致・遅延したコピー結果が残っている可能性がある。他者の更新とも判別できないので上書きしない。
                        restoreStatus = "PROBE_COPY_LEFT_UNVERIFIED";
                        Log.i(TAG, "probeCopyMayRemain=true; 判別できないため上書きしない");
                    } else {
                        restoreStatus = "FOREIGN_CHANGE_PRESERVED";
                        Log.i(TAG, "clipboardChangedAfterProbe=true; 他の更新を上書きしない");
                    }
                    return null;
                });
                cleanupPhase = "PREVIEW_AFTER_CLIPBOARD_RESTORE";
                awaitPreview("AFTER_CLIPBOARD_RESTORE");
            } catch (Throwable error) {
                cleanup = error;
                cleanupFailedStage = cleanupPhase;
                if ("PENDING".equals(restoreStatus)) restoreStatus = "FAILED";
            }
            if (openedSettings) try { closeOwnSettings(); }
            catch (Throwable error) { if (cleanup == null) { cleanup = error; cleanupFailedStage = "OWN_SETTINGS_CLOSE"; } else cleanup.addSuppressed(error); }
            try {
                AccessibilityServiceInfo info = automation.getServiceInfo();
                info.flags = oldFlags;
                automation.setServiceInfo(info);
            } catch (Throwable error) { if (cleanup == null) { cleanup = error; cleanupFailedStage = "AUTOMATION_FLAGS_RESTORE"; } else cleanup.addSuppressed(error); }
            if (cleanup != null) { if (primary != null) primary.addSuppressed(cleanup); else throw new AssertionError("probe の復旧に失敗しました。", cleanup); }
        }
    }

    /** 復旧直後の時刻印だけを短時間比較する。本文やハッシュは読まず、記録もしない。 */
    private static boolean changedAfterRestore(ClipboardManager clipboard) {
        long restored = timestamp(clipboard.getPrimaryClip());
        long deadline = SystemClock.elapsedRealtime() + 500;
        do {
            SystemClock.sleep(50);
            if (timestamp(clipboard.getPrimaryClip()) != restored) return true;
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    private static long timestamp(ClipData clip) { return clip == null ? -1L : clip.getDescription().getTimestamp(); }

    private void focusEditor() throws Exception {
        final boolean[] clicked = { false };
        await("CodeMirror 文書の焦点", 10000, () -> withRoot(root -> {
            AccessibilityNodeInfo pane = find(root, node -> idIs(node, "userFilters"));
            if (pane == null) return false;
            try {
                // relay 往復後は blur しているので、focused/Editable だけを候補条件にしない。
                // 実機の検索 EditText を含む toolbar branch を除外し、文書側の最深 focusable View を選ぶ。
                AccessibilityNodeInfo editor = documentEditor(pane);
                if (editor == null) return false;
                try {
                    if (!editor.isFocused()) {
                        editorFocusAction = editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                        editor.refresh();
                        if (!editor.isFocused() && !clicked[0]) {
                            clicked[0] = true;
                            editorClickAction = editor.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        }
                    }
                    editor.refresh();
                    rememberEditor(editor);
                    AccessibilityNodeInfo focused = pane.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if (focused == null) return false;
                    try {
                        // 検索欄や URL のコピーを成功扱いしない。選んだ文書 node 自身への焦点を必要とする。
                        return editor.isFocused() && editor.equals(focused);
                    } finally { focused.recycle(); }
                }
                finally { editor.recycle(); }
            } finally { pane.recycle(); }
        })) ;
    }

    private AccessibilityNodeInfo documentEditor(AccessibilityNodeInfo pane) {
        AccessibilityNodeInfo[] best = { null };
        int[] deepest = { -1 };
        editorCandidates = 0;
        for (int index = 0; index < pane.getChildCount(); index++) {
            AccessibilityNodeInfo branch = pane.getChild(index);
            if (branch == null) continue;
            try {
                AccessibilityNodeInfo search = find(branch, node -> "android.widget.EditText".equals(String.valueOf(node.getClassName())));
                if (search != null) { search.recycle(); continue; }
                chooseEditor(branch, 1, best, deepest);
            } finally { branch.recycle(); }
        }
        editorDepth = deepest[0];
        return best[0];
    }

    private void chooseEditor(AccessibilityNodeInfo node, int depth, AccessibilityNodeInfo[] best, int[] deepest) {
        if ("android.view.View".equals(String.valueOf(node.getClassName())) && node.getChildCount() > 0
                && (node.isFocusable() || node.isEditable() || node.isFocused())) {
            editorCandidates++;
            if (depth > deepest[0]) {
                if (best[0] != null) best[0].recycle();
                best[0] = AccessibilityNodeInfo.obtain(node);
                deepest[0] = depth;
            }
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try { chooseEditor(child, depth + 1, best, deepest); } finally { child.recycle(); }
        }
    }

    private void rememberEditor(AccessibilityNodeInfo editor) {
        editorFocusable = editor.isFocusable();
        editorFocused = editor.isFocused();
        editorEditable = editor.isEditable();
        editorChildren = editor.getChildCount();
        Rect bounds = new Rect();
        editor.getBoundsInScreen(bounds);
        editorBounds = bounds.toShortString();
    }

    private static final class FilterState {
        boolean apply, revert;
        final List<Boolean> checked = new ArrayList<>();
        boolean same(FilterState other) { return other != null && apply == other.apply && revert == other.revert && checked.equals(other.checked); }
    }
    private FilterState filterState() {
        final FilterState value = new FilterState();
        assertTrue("MyFilters を読み取れません。", withRoot(root -> {
            AccessibilityNodeInfo apply = find(root, node -> idIs(node, "userFiltersApply"));
            AccessibilityNodeInfo revert = find(root, node -> idIs(node, "userFiltersRevert"));
            if (apply == null || revert == null) { if (apply != null) apply.recycle(); if (revert != null) revert.recycle(); return false; }
            try { value.apply = apply.isEnabled(); value.revert = revert.isEnabled(); }
            finally { apply.recycle(); revert.recycle(); }
            visit(root, node -> { if ("android.widget.CheckBox".equals(String.valueOf(node.getClassName()))) value.checked.add(node.isChecked()); });
            return value.checked.size() == 2;
        }));
        return value;
    }

    private static final class Stamp {
        final long time; final String label, text, html;
        Stamp(ClipData clip) { time = clip.getDescription().getTimestamp(); label = String.valueOf(clip.getDescription().getLabel()); text = clip.getItemAt(0).getText().toString(); html = clip.getItemAt(0).getHtmlText(); }
        static Stamp of(ClipData clip) { return clip != null && clip.getItemCount() == 1 && clip.getItemAt(0).getText() != null
                && clip.getItemAt(0).getUri() == null && clip.getItemAt(0).getIntent() == null ? new Stamp(clip) : null; }
        boolean same(Stamp other) { return other != null && time == other.time && Objects.equals(label, other.label) && Objects.equals(text, other.text) && Objects.equals(html, other.html); }
    }

    private void chord(int code) {
        long down = SystemClock.uptimeMillis();
        int ctrl = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        inject(down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, ctrl);
        try { inject(down, KeyEvent.ACTION_DOWN, code, ctrl); inject(down, KeyEvent.ACTION_UP, code, ctrl); }
        finally { inject(down, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0); }
    }
    private void inject(long down, int action, int code, int meta) {
        assertTrue("キーを注入できません。", automation.injectInputEvent(new KeyEvent(down, SystemClock.uptimeMillis(), action,
                code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), true));
    }
    private boolean hasId(String id) { return withRoot(root -> { AccessibilityNodeInfo node = find(root, n -> idIs(n, id)); if (node == null) return false; node.recycle(); return true; }); }
    private boolean hasNativeClose() { return withRoot(root -> { AccessibilityNodeInfo node = find(root, n -> "android.widget.Button".equals(String.valueOf(n.getClassName())) && "閉じる".equals(String.valueOf(n.getText()))); if (node == null) return false; node.recycle(); return true; }); }
    private void closeOwnSettings() {
        assertTrue("自分が開いた設定画面を閉じられません。", withRoot(root -> {
            AccessibilityNodeInfo node = find(root, n -> "android.widget.Button".equals(String.valueOf(n.getClassName())) && "閉じる".equals(String.valueOf(n.getText())));
            if (node == null) return false;
            try { return node.performAction(AccessibilityNodeInfo.ACTION_CLICK); } finally { node.recycle(); }
        }));
    }
    private void clickLabel(String label) { assertTrue("必要な UI 操作を特定できません。", clickOptionalLabel(label)); }
    private boolean clickOptionalLabel(String... labels) {
        return withRoot(root -> {
            AccessibilityNodeInfo node = find(root, n -> {
                if (!n.isClickable() || !n.isEnabled()) return false;
                for (String label : labels) if ((n.getText() != null && n.getText().toString().contains(label)) || (n.getContentDescription() != null && n.getContentDescription().toString().contains(label))) return true;
                return false;
            });
            if (node == null) return false;
            try { return node.performAction(AccessibilityNodeInfo.ACTION_CLICK); } finally { node.recycle(); }
        });
    }
    private void awaitPreview(String phase) throws Exception { await("PREVIEW_" + phase, 10000, () -> withRoot(root -> true)); }
    private interface Condition { boolean get() throws Exception; }
    private void await(String label, long timeout, Condition condition) throws Exception {
        stage = label;
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do { if (condition.get()) return; SystemClock.sleep(100); } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError(label + " が期限内に確認できません。本文変更は行いません。");
    }
    private interface NodeCheck { boolean test(AccessibilityNodeInfo node); }
    private boolean withRoot(NodeCheck action) {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        List<AccessibilityWindowInfo> windows = automation.getWindows();
        AccessibilityNodeInfo direct = automation.getRootInActiveWindow();
        if (direct != null) roots.add(direct);
        activeRootKind = kind(direct);
        focusedRootKind = "NONE";
        AccessibilityNodeInfo focusedPreview = null;
        AccessibilityNodeInfo activePreview = null;
        boolean foreignFocused = false;
        try {
            if (windows != null) for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) roots.add(root);
                boolean own = "PREVIEW".equals(kind(root));
                if (window.isFocused()) {
                    focusedRootKind = kind(root);
                    if (own) focusedPreview = root; else foreignFocused = true;
                }
                if (own && window.isActive()) activePreview = root;
            }
            // transient な active root に依存せず input focus を優先し、別 UID の焦点中は操作しない。
            AccessibilityNodeInfo selected = foreignFocused ? null : focusedPreview;
            if (selected == null && !foreignFocused) selected = "PREVIEW".equals(kind(direct)) ? direct : activePreview;
            return selected != null && action.test(selected);
        } finally {
            for (AccessibilityNodeInfo root : roots) root.recycle();
            if (windows != null) for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }
    private static String kind(AccessibilityNodeInfo root) {
        if (root == null) return "NONE";
        String value = String.valueOf(root.getPackageName());
        if (PREVIEW.equals(value)) return "PREVIEW";
        if ("io.github.ajichama.citrusbrowser.geckouiprobe".equals(value)) return "PROBE";
        return "OTHER";
    }
    private static boolean idIs(AccessibilityNodeInfo node, String id) { String value = node.getViewIdResourceName(); return id.equals(value) || (value != null && value.endsWith(":id/" + id)); }
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, NodeCheck check) {
        if (check.test(node)) return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child == null) continue;
            try { AccessibilityNodeInfo found = find(child, check); if (found != null) return found; } finally { child.recycle(); } }
        return null;
    }
    private static void assertTrue(String message, boolean condition) { if (!condition) throw new AssertionError(message); }
    private static void assertFalse(String message, boolean condition) { assertTrue(message, !condition); }
    private static void assertNotNull(String message, Object value) { assertTrue(message, value != null); }

    private interface NodeVisit { void accept(AccessibilityNodeInfo node); }
    private static void visit(AccessibilityNodeInfo node, NodeVisit action) {
        action.accept(node);
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child == null) continue;
            try { visit(child, action); } finally { child.recycle(); } }
    }
}
