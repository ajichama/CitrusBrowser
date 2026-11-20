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
package io.github.ajichama.citrusbrowser.geckouiprobe;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * 原本 uBO の set-constant と動的 has-text を、ページ自身の通常 JS だけで測定する。
 * 任意の URL・DOM 本文・clipboard を収集せず、外部のページへ接続しない。
 */
final class ScriptletProceduralFixture implements Closeable {
    private static final String HOST = "127.0.0.2";
    private static final int MAX_HEADER = 8192;
    private static final int MAX_BODY = 1024;
    private static final int MAX_LOADS = 8;
    private static final int MAX_TICKS = 400;
    private static final Set<String> REPORT_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "initialTrue", "assignmentStayedTrue", "initialProceduralVisible", "proceduralHidden",
            "controlVisible", "normalJs", "textChanged", "ticks", "documentCounter")));

    static final class Load {
        final String token;
        final String url;
        private Load(String token, String url) { this.token = token; this.url = url; }
    }

    static final class Report {
        final boolean initialTrue;
        final boolean assignmentStayedTrue;
        final boolean initialProceduralVisible;
        final boolean proceduralHidden;
        final boolean controlVisible;
        final boolean normalJs;
        final boolean textChanged;
        final int ticks;
        final String documentNonce;

        private Report(Map<String, String> values, String token, String frame) throws IOException {
            initialTrue = flag(values, "initialTrue");
            assignmentStayedTrue = flag(values, "assignmentStayedTrue");
            initialProceduralVisible = flag(values, "initialProceduralVisible");
            proceduralHidden = flag(values, "proceduralHidden");
            controlVisible = flag(values, "controlVisible");
            normalJs = flag(values, "normalJs");
            textChanged = flag(values, "textChanged");
            ticks = number(values.get("ticks"), 1, MAX_TICKS);
            int documentCounter = number(values.get("documentCounter"), 1, 1_000_000_000);
            // 文字列の本文を受け取らず、所有 token と有界な文書識別数だけで作る。
            documentNonce = token + ":" + frame + ":" + documentCounter;
        }
    }

    /** 要求側の不正。fixture 全体の停止ではなく、その接続へ 400 を返して閉じる。 */
    private static final class BadRequest extends IOException {
        BadRequest(String message) { super(message); }
    }

    private static final class Entry {
        final Load load;
        final Map<String, Report> reports = new ConcurrentHashMap<>();
        Entry(Load load) { this.load = load; }
    }

    private final String nonce = UUID.randomUUID().toString().replace("-", "");
    private final Map<String, Entry> loads = new ConcurrentHashMap<>();
    private final Object monitor = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Socket> active = new AtomicReference<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final ServerSocket server;
    private final String origin;
    private final Thread worker;

    ScriptletProceduralFixture() throws IOException {
        server = new ServerSocket();
        try {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName(HOST), 0), 16);
            server.setSoTimeout(250);
        } catch (IOException error) {
            try { server.close(); } catch (IOException ignored) { }
            throw new IOException("専用 loopback fixture を開始できません。");
        }
        origin = "http://" + HOST + ":" + server.getLocalPort();
        worker = new Thread(this::serve, "citrus-scriptlet-fixture");
        worker.setDaemon(true);
        worker.start();
    }

    String nonce() { return nonce; }
    String propertyName() { return "citrusFixtureFlag" + nonce; }
    String selectorClass() { return "citrus-fixture-" + nonce; }
    String matchingText() { return "CITRUS_MATCH_" + nonce; }

    synchronized Load page(boolean enabledLabel) {
        if (closed.get()) throw new IllegalStateException("fixture は終了しています。");
        if (loads.size() >= MAX_LOADS) throw new IllegalStateException("fixture のページ数上限です。");
        String token = UUID.randomUUID().toString().replace("-", "");
        // ON/OFF は所有ページの区別だけに使う。文書の測定値をラベルから生成しない。
        String key = (enabledLabel ? "on" : "off") + token;
        Load load = new Load(key, origin + "/page/" + key + "/top");
        loads.put(key, new Entry(load));
        return load;
    }

    /** predicate は固定 Report の比較だけに使い、UI 操作や追加待機を行わない。 */
    Report awaitReport(Load load, String frame, long timeoutMillis, Predicate<Report> predicate)
            throws InterruptedException {
        Entry entry = loads.get(load.token);
        if (entry == null || entry.load != load || !validFrame(frame) || timeoutMillis < 1 || timeoutMillis > 120_000)
            throw new IllegalArgumentException("結果待機の引数が不正です。");
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        synchronized (monitor) {
            while (true) {
                if (failure.get() != null) throw new AssertionError("専用 fixture が停止しました。", failure.get());
                if (closed.get()) throw new AssertionError("専用 fixture は終了しています。");
                Report report = entry.reports.get(frame);
                if (report != null && predicate.test(report)) return report;
                long left = (deadline - System.nanoTime()) / 1_000_000L;
                if (left <= 0) throw new AssertionError("固定測定結果の待機期限を超えました。");
                monitor.wait(Math.min(left, 200));
            }
        }
    }

    private void serve() {
        while (!closed.get()) {
            try (Socket socket = server.accept()) {
                active.set(socket);
                if (closed.get()) break;
                try { handle(socket); }
                catch (BadRequest ignored) {
                    // 不正な要求はこの接続だけを 400 で終える。応答できなくても fixture は継続する。
                    try { respond(socket, 400, "text/plain", "fixture"); } catch (IOException alsoIgnored) { }
                } catch (IOException ignored) {
                    // ページ取消・不正な要求・読込期限切れは、この接続だけを閉じる。
                } finally { active.compareAndSet(socket, null); }
            } catch (SocketTimeoutException ignored) {
                // accept にも上限を設け、終了状態を確認する。
            } catch (IOException error) {
                if (!closed.get()) recordFailure();
            } catch (RuntimeException error) {
                // 入力や URL を含み得る例外本文を、診断結果へ流さない。
                if (!closed.get()) recordFailure();
            }
        }
    }

    private void recordFailure() {
        failure.compareAndSet(null, new IOException("専用 fixture の内部処理が停止しました。"));
        synchronized (monitor) { monitor.notifyAll(); }
    }

    private void handle(Socket socket) throws IOException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        InputStream input = socket.getInputStream();
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int tail = 0;
        boolean complete = false;
        while (header.size() < MAX_HEADER) {
            setReadTimeout(socket, deadline);
            int value = input.read();
            if (value < 0) return;
            header.write(value);
            tail = (tail << 8) | value;
            if (tail == 0x0d0a0d0a) { complete = true; break; }
        }
        if (!complete) throw new BadRequest("要求ヘッダーの上限です。");
        String[] lines = header.toString(StandardCharsets.US_ASCII.name()).split("\r\n");
        // 空行だけの要求では split 結果が空配列になるため、要求行の有無を先に確認する。
        if (lines.length == 0) throw new BadRequest("要求行がありません。");
        String[] first = lines[0].split(" ", -1);
        if (first.length != 3 || !first[2].equals("HTTP/1.1")) throw new BadRequest("要求形式が不正です。");
        String method = first[0], path = first[1];
        Map<String, String> headers = new HashMap<>();
        for (int index = 1; index < lines.length; index++) {
            int colon = lines[index].indexOf(':');
            if (colon <= 0) throw new BadRequest("要求ヘッダーが不正です。");
            String name = lines[index].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            // Cookie 等の他ヘッダーを保存・解釈しない。
            if (name.equals("host") || name.equals("origin") || name.equals("content-length") ||
                    name.equals("content-type") || name.equals("transfer-encoding")) {
                if (headers.put(name, lines[index].substring(colon + 1).trim()) != null)
                    throw new BadRequest("要求ヘッダーが重複しています。");
            }
        }
        if (!(HOST + ":" + server.getLocalPort()).equals(headers.get("host")) || headers.containsKey("transfer-encoding"))
            throw new BadRequest("専用 origin の要求ではありません。");
        String[] parts = path.split("/", -1);
        if (parts.length != 4 || !parts[0].isEmpty() || !validFrame(parts[3])) { respond(socket, 404, "text/plain", "fixture"); return; }
        Entry entry = loads.get(parts[2]);
        if (entry == null) { respond(socket, 404, "text/plain", "fixture"); return; }
        if (method.equals("GET") && parts[1].equals("page")) {
            respond(socket, 200, "text/html; charset=utf-8", html(entry.load.token, parts[3]));
            return;
        }
        if (method.equals("POST") && parts[1].equals("report") && origin.equals(headers.get("origin")) &&
                isFormMediaType(headers.get("content-type"))) {
            int length = badRequestNumber(headers.get("content-length"), 1, MAX_BODY);
            byte[] body = new byte[length];
            int offset = 0;
            while (offset < length) {
                setReadTimeout(socket, deadline);
                int count = input.read(body, offset, length - offset);
                if (count < 0) throw new IOException("固定測定結果が途中で終了しました。");
                offset += count;
            }
            Report report;
            try {
                report = new Report(fixedValues(body), entry.load.token, parts[3]);
            } catch (IOException invalid) {
                // 測定値の形式違反も要求側の不正として扱い、結果を記録せずこの接続だけを終える。
                throw new BadRequest("固定測定結果が不正です。");
            }
            entry.reports.put(parts[3], report);
            synchronized (monitor) { monitor.notifyAll(); }
            respond(socket, 200, "text/plain", "ok");
            return;
        }
        respond(socket, 404, "text/plain", "fixture");
    }

    private String html(String token, String frame) {
        String child = frame.equals("top") ? "<iframe title=\"fixture child\" src=\"/page/" + token + "/child\"></iframe>" : "";
        // 最初の読取より前にページ自身が true を書かない。false 再代入後も原本 scriptlet の効果を読む。
        String initial = "<script>window.__citrusProof={initialTrue:window['" + propertyName() + "']===true};" +
                "try{window['" + propertyName() + "']=false;}catch(e){}" +
                "window.__citrusProof.assignmentStayedTrue=window['" + propertyName() + "']===true;</script>";
        String script = String.format(Locale.ROOT, """
                <script>(()=>{
                  const state=window.__citrusProof, target=document.getElementById('target'), control=document.getElementById('control');
                  const visible=(node)=>{const css=getComputedStyle(node),r=node.getBoundingClientRect();return css.display!=='none'&&css.visibility!=='hidden'&&r.width>0&&r.height>0;};
                  const initialProceduralVisible=visible(target);
                  const documentCounter=1+Math.floor(Math.random()*1000000000);
                  let textChanged=false,ticks=0,pending=false,normalJs=false;
                  (()=>{normalJs=true;})();
                  setTimeout(()=>{target.textContent='%s';textChanged=true;},350);
                  const timer=setInterval(()=>{
                    if(ticks>=400){clearInterval(timer);return;}
                    ticks++;
                    if(pending)return;
                    const values={initialTrue:state.initialTrue,assignmentStayedTrue:state.assignmentStayedTrue,
                      initialProceduralVisible,proceduralHidden:textChanged&&!visible(target),controlVisible:visible(control),normalJs,textChanged};
                    let body=Object.keys(values).map((key)=>key+'='+(values[key]?'1':'0')).join('&');
                    body+='&ticks='+ticks+'&documentCounter='+documentCounter;
                    pending=true;
                    fetch('/report/%s/%s',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body,cache:'no-store',credentials:'omit'})
                      .catch(()=>{}).finally(()=>{pending=false;});
                  },150);
                })();</script>
                """, matchingText(), token, frame);
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>Citrus fixture</title>" + initial +
                "</head><body><div id=\"target\" class=\"" + selectorClass() + "\">neutral fixture text</div>" +
                "<div id=\"control\" class=\"" + selectorClass() + "\">non-matching control</div>" + child + script + "</body></html>";
    }

    private static Map<String, String> fixedValues(byte[] bytes) throws IOException {
        for (byte value : bytes) {
            if (value < 0x20 || value > 0x7e) throw new IOException("固定測定結果はASCIIだけです。");
        }
        String[] fields = new String(bytes, StandardCharsets.US_ASCII).split("&", -1);
        if (fields.length != REPORT_FIELDS.size()) throw new IOException("固定測定結果の項目数が不正です。");
        Map<String, String> values = new HashMap<>();
        for (String field : fields) {
            String[] pair = field.split("=", -1);
            if (pair.length != 2 || !REPORT_FIELDS.contains(pair[0]) || values.put(pair[0], pair[1]) != null)
                throw new IOException("固定測定結果の項目が不正です。");
        }
        return values;
    }

    private static boolean flag(Map<String, String> values, String key) throws IOException {
        String value = values.get(key);
        if ("1".equals(value)) return true;
        if ("0".equals(value)) return false;
        throw new IOException("固定booleanが不正です。");
    }

    private static int number(String value, int minimum, int maximum) throws IOException {
        if (value == null || value.isEmpty() || value.length() > 10 || !value.matches("[0-9]+"))
            throw new IOException("固定整数が不正です。");
        long parsed = Long.parseLong(value);
        if (parsed < minimum || parsed > maximum) throw new IOException("固定整数が範囲外です。");
        return (int) parsed;
    }

    /** `;` 以降の charset 等を除き、メディアタイプだけを大文字小文字無視で比較する。 */
    private static boolean isFormMediaType(String value) {
        if (value == null) return false;
        int semicolon = value.indexOf(';');
        String type = (semicolon < 0 ? value : value.substring(0, semicolon)).trim();
        return type.equalsIgnoreCase("application/x-www-form-urlencoded");
    }

    private static int badRequestNumber(String value, int minimum, int maximum) throws BadRequest {
        try { return number(value, minimum, maximum); }
        catch (IOException invalid) { throw new BadRequest("要求の数値が不正です。"); }
    }

    private static void setReadTimeout(Socket socket, long deadline) throws IOException {
        long left = (deadline - System.nanoTime()) / 1_000_000L;
        if (left <= 0) throw new IOException("接続の読込期限を超えました。");
        socket.setSoTimeout((int) Math.min(left, 1000));
    }

    private static boolean validFrame(String frame) { return "top".equals(frame) || "child".equals(frame); }

    private static void respond(Socket socket, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? " OK" : status == 400 ? " Bad Request" : " Not Found";
        byte[] header = ("HTTP/1.1 " + status + reason + "\r\nContent-Type: " + type +
                "\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: " + bytes.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        socket.getOutputStream().write(header);
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    @Override public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        IOException closeFailure = null;
        try { server.close(); } catch (IOException error) {
            closeFailure = new IOException("fixture の待受けを閉じられません。");
        } finally {
            Socket socket = active.getAndSet(null);
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
            synchronized (monitor) { monitor.notifyAll(); }
        }
        worker.interrupt();
        long deadline = System.nanoTime() + 4_000_000_000L;
        boolean interrupted = Thread.interrupted();
        try {
            while (worker.isAlive()) {
                long left = (deadline - System.nanoTime()) / 1_000_000L;
                if (left <= 0) throw new IOException("fixture の停止期限を超えました。");
                try { worker.join(Math.min(left, 200)); } catch (InterruptedException error) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
        if (closeFailure != null) throw closeFailure;
    }
}
