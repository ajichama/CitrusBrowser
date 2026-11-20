/*
 * Copyright (C) 2026 CitrusBrowser contributors
 * Licensed under the Apache License, Version 2.0. See the LICENSE file.
 *
 * redirect=noop-1s.mp4 で返す、約1秒の無音の MP4 を作る（他の広告ブロッカーの資源は使わない）。
 * 中身は、自作の無音 MP3（assets/adblock/noop-0.1s.mp3）の先頭フレームを39個並べた音声トラック1本。
 * 箱の構成は ISO/IEC 14496-12（ISO base media file format）と 14496-14（MP4）、MP3 の格納は objectTypeIndication 0x6B（MPEG-1 Audio）。
 *
 * 使い方: node tools/adblock/make-noop-mp4.mjs   （リポジトリのルートで実行。出力は assets/adblock/noop-1s.mp4）
 */
import fs from 'node:fs';
import path from 'node:path';

const assets = path.join('module', 'adblock', 'src', 'main', 'assets', 'adblock');
const mp3 = fs.readFileSync(path.join(assets, 'noop-0.1s.mp3'));
const FRAME_BYTES = 417; // MPEG-1 Layer III・128kbps・44.1kHz・パディングなし
const SAMPLES_PER_FRAME = 1152;
const SAMPLE_RATE = 44100;
const FRAMES = 39; // 39 × 1152 / 44100 ≒ 1.019 秒
const frame = mp3.subarray(0, FRAME_BYTES);
if (frame[0] !== 0xff || frame[1] !== 0xfb || frame[2] !== 0x90 || (frame[3] & 0xc0) !== 0xc0) {
  throw new Error('noop-0.1s.mp3 の先頭フレームが想定（MPEG-1 Layer III・128kbps・44.1kHz・モノラル）と違います');
}

const u8 = (v) => Buffer.from([v & 0xff]);
const u16 = (v) => { const b = Buffer.alloc(2); b.writeUInt16BE(v); return b; };
const u24 = (v) => Buffer.from([(v >>> 16) & 0xff, (v >>> 8) & 0xff, v & 0xff]);
const u32 = (v) => { const b = Buffer.alloc(4); b.writeUInt32BE(v >>> 0); return b; };
const zeros = (n) => Buffer.alloc(n);
const box = (type, ...parts) => { const body = Buffer.concat(parts); return Buffer.concat([u32(8 + body.length), Buffer.from(type, 'latin1'), body]); };
const fullBox = (type, version, flags, ...parts) => box(type, u8(version), u24(flags), ...parts);
const matrix = Buffer.concat([0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000].map(u32));
const descriptor = (tag, ...parts) => { const body = Buffer.concat(parts); return Buffer.concat([u8(tag), u8(body.length), body]); };

const sampleDuration = FRAMES * SAMPLES_PER_FRAME;
const movieDurationMs = Math.round(sampleDuration * 1000 / SAMPLE_RATE);
const ftyp = box('ftyp', Buffer.from('isom', 'latin1'), u32(0x200), Buffer.from('isomiso2mp41', 'latin1'));
const payload = Buffer.concat(Array.from({ length: FRAMES }, () => frame));
const mdat = box('mdat', payload);
const chunkOffset = ftyp.length + 8;

const esds = fullBox('esds', 0, 0, descriptor(0x03, u16(1), u8(0),
  descriptor(0x04, u8(0x6b), u8((0x05 << 2) | 1), u24(0), u32(128000), u32(128000)),
  descriptor(0x06, u8(0x02))));
const mp4a = box('mp4a', zeros(6), u16(1), zeros(8), u16(1), u16(16), u16(0), u16(0), u32(SAMPLE_RATE * 65536), esds);
const stbl = box('stbl',
  fullBox('stsd', 0, 0, u32(1), mp4a),
  fullBox('stts', 0, 0, u32(1), u32(FRAMES), u32(SAMPLES_PER_FRAME)),
  fullBox('stsc', 0, 0, u32(1), u32(1), u32(FRAMES), u32(1)),
  fullBox('stsz', 0, 0, u32(FRAME_BYTES), u32(FRAMES)),
  fullBox('stco', 0, 0, u32(1), u32(chunkOffset)));
const minf = box('minf',
  fullBox('smhd', 0, 0, u16(0), u16(0)),
  box('dinf', fullBox('dref', 0, 0, u32(1), fullBox('url ', 0, 1))),
  stbl);
const language = ((('u'.charCodeAt(0) - 0x60) << 10) | (('n'.charCodeAt(0) - 0x60) << 5) | ('d'.charCodeAt(0) - 0x60));
const mdia = box('mdia',
  fullBox('mdhd', 0, 0, u32(0), u32(0), u32(SAMPLE_RATE), u32(sampleDuration), u16(language), u16(0)),
  fullBox('hdlr', 0, 0, u32(0), Buffer.from('soun', 'latin1'), zeros(12), Buffer.from('SoundHandler', 'latin1'), zeros(1)),
  minf);
const trak = box('trak',
  fullBox('tkhd', 0, 3, u32(0), u32(0), u32(1), u32(0), u32(movieDurationMs), zeros(8), u16(0), u16(0), u16(0x0100), u16(0), matrix, u32(0), u32(0)),
  mdia);
const moov = box('moov',
  fullBox('mvhd', 0, 0, u32(0), u32(0), u32(1000), u32(movieDurationMs), u32(0x00010000), u16(0x0100), zeros(10), matrix, zeros(24), u32(2)),
  trak);

const out = Buffer.concat([ftyp, mdat, moov]);
fs.writeFileSync(path.join(assets, 'noop-1s.mp4'), out);
console.log(`noop-1s.mp4: ${out.length} バイト、${movieDurationMs} ミリ秒`);
