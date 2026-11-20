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

/** 通常は CSS の再照合へ任せ、実際に競合する inline !important だけを補正する。 */
internal val COSMETIC_STYLE_SCRIPT = """
    const marker='style[data-citrus-cosmetic]';
    const previous=document.querySelector(marker);
    if(previous && typeof previous.citrusDispose==='function') previous.citrusDispose();
    if(previous) previous.remove();
    const style=document.createElement('style');
    style.setAttribute('data-citrus-cosmetic','');
    (document.head || document.documentElement).appendChild(style);
    const selectors=[];
    const syntax=document.createDocumentFragment();
    b.forEach(a=>{try{
        // CSS ルールの注入を防ぎ、不正なセレクター一件を他から分離する。
        syntax.querySelector(a);
        if(style.sheet) style.sheet.insertRule(a+'{display:none!important;}',style.sheet.cssRules.length);
        selectors.push(a);
    }catch(e){}});
    if(!selectors.length){style.remove();return;}
    const forced=new Map(), pending=new Set();
    let timer=0, disposed=false;
    let walker=null, first=null, fullScan=true;
    let forcedCursor=null, checkForced=false, turn=0;
    function matches(node){return selectors.some(a=>{try{return node.matches(a)}catch(e){return false}})}
    function restore(node,saved){
        if(node.style.getPropertyValue('display')==='none' && node.style.getPropertyPriority('display')==='important'){
            if(saved.value) node.style.setProperty('display',saved.value,saved.priority);
            else node.style.removeProperty('display');
        }
        forced.delete(node);
    }
    function reconcile(node){
        if(!node.style) return;
        const saved=forced.get(node);
        if(!document.documentElement.contains(node) || !matches(node)){
            if(saved) restore(node,saved);
            return;
        }
        // 作者 stylesheet の important は CSS に任せ、inline の競合と CSP 時だけ扱う。
        const value=node.style.getPropertyValue('display');
        const priority=node.style.getPropertyPriority('display');
        if(saved && !(value==='none' && priority==='important')) forced.delete(node);
        if((priority==='important' && value!=='none') || !style.sheet){
            if(!saved && forced.size>=512) return;
            if(value==='none' && priority==='important') return;
            forced.set(node,{value:value,priority:priority});
            node.style.setProperty('display','none','important');
        }
    }
    function schedule(){if(!timer && !disposed) timer=setTimeout(flush,50)}
    function queue(node){
        if(!node || node.nodeType!==1 || !node.style) return;
        if(pending.size<512) pending.add(node);
        else fullScan=true;
        schedule();
    }
    function nextScan(){
        if(!walker){
            if(!fullScan) return null;
            fullScan=false;
            first=document.documentElement;
            walker=document.createTreeWalker(first,NodeFilter.SHOW_ELEMENT);
        }
        const node=first || walker.nextNode();
        first=null;
        if(!node) walker=null;
        return node;
    }
    function nextForced(){
        if(!forcedCursor){
            if(!checkForced) return null;
            checkForced=false;
            forcedCursor=forced.keys();
        }
        const entry=forcedCursor.next();
        if(entry.done){forcedCursor=null;return null;}
        return entry.value;
    }
    function hasWork(){return pending.size || walker || fullScan || forcedCursor || checkForced}
    function flush(){
        timer=0;
        if(disposed) return;
        // 新規候補・TreeWalker・既存補正の再確認を合計64要素に含め、どの経路も飢餓を避ける。
        let count=0, empty=0;
        while(count<64 && empty<3){
            let node=null;
            const source=turn++%3;
            if(source===0){
                const entry=pending.values().next();
                if(!entry.done){node=entry.value;pending.delete(node);}
            }else if(source===1) node=nextScan();
            else node=nextForced();
            if(!node){empty++;continue;}
            empty=0;
            count++;
            if(node.style && (forced.has(node) || !style.sheet || node.style.getPropertyPriority('display')==='important')) reconcile(node);
        }
        if(hasWork()) schedule();
    }
    // 全 DOM の配列を作らず、初回も再走査も同じ有界バッチで最後まで継続する。
    schedule();
    const observer=new MutationObserver(records=>{
        // :has、兄弟結合、:empty 等は変更要素以外にも作用する。走査中は巻き戻さず次の巡回を予約する。
        fullScan=true;
        let count=0;
        for(const record of records){
            if(++count>128) break;
            if(record.type==='attributes') queue(record.target);
            else if(record.type==='characterData') queue(record.target.parentElement);
            else {
                queue(record.target);
                let added=0;
                for(const node of record.addedNodes){if(++added>128) break;queue(node)}
            }
        }
        if(forced.size) checkForced=true;
        if(hasWork()) schedule();
    });
    observer.observe(document.documentElement,{subtree:true,childList:true,attributes:true,characterData:true});
    style.citrusDispose=function(){
        disposed=true;
        observer.disconnect();
        clearTimeout(timer);
        pending.clear();
        walker=null;
        forcedCursor=null;
        forced.forEach((saved,node)=>restore(node,saved));
    };
""".trimIndent()
