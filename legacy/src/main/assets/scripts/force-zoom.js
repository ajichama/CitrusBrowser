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

// ページのズーム禁止を無視する（#17）。文書開始時に注入し、メインのフレームだけで動く。
// viewport の user-scalable と、上限より小さい maximum-scale を取り除く（既定の上限は5倍）。
// ページが後から viewport を足したり書き換えたりしても、MutationObserver で追いかける。
(function () {
    'use strict';
    if (window.top !== window) return;

    var MAX_SCALE = 5;
    var PAIR = /([a-zA-Z-]+)\s*=\s*([^,;\s]+)/g;

    function fix(meta) {
        if ((meta.getAttribute('name') || '').toLowerCase() !== 'viewport') return;
        var content = meta.getAttribute('content');
        if (!content) return;
        var kept = [];
        var changed = false;
        var match;
        PAIR.lastIndex = 0;
        while ((match = PAIR.exec(content)) !== null) {
            var key = match[1].toLowerCase();
            if (key === 'user-scalable') {
                changed = true;
                continue;
            }
            if (key === 'maximum-scale' && !(parseFloat(match[2]) >= MAX_SCALE)) {
                changed = true;
                continue;
            }
            kept.push(key + '=' + match[2]);
        }
        // 変わらないときは書き込まない（自分の書き込みで監視が繰り返さないように）。
        if (changed) meta.setAttribute('content', kept.join(', '));
    }

    function fixWithin(node) {
        if (node.nodeType !== 1) return;
        if (node.nodeName === 'META') {
            fix(node);
        } else if (node.nodeName === 'HEAD' || node.nodeName === 'HTML') {
            var metas = node.getElementsByTagName('meta');
            for (var i = 0; i < metas.length; i++) fix(metas[i]);
        }
    }

    new MutationObserver(function (records) {
        for (var i = 0; i < records.length; i++) {
            var record = records[i];
            if (record.type === 'attributes') {
                if (record.target.nodeName === 'META') fix(record.target);
                continue;
            }
            var added = record.addedNodes;
            for (var j = 0; j < added.length; j++) fixWithin(added[j]);
        }
    }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['name', 'content'] });

    if (document.documentElement) fixWithin(document.documentElement);
})();
