// Direct test of multi-frame decode using the same approach as
// nrepl.cljs/decode-all-frames.
const bencode = require('bencode');

const buf = Buffer.from('d3:foo3:bared3:baz3:quxe', 'utf8');
console.log('buf len =', buf.length);

function decodeAll(b) {
  const frames = [];
  let cur = b;
  while (cur.length > 0) {
    let f;
    try { f = bencode.decode(cur, 'utf8'); } catch (e) { return [frames, cur]; }
    const consumed = bencode.decode.bytes || bencode.decode.position || 0;
    if (!f) return [frames, cur];
    frames.push(f);
    if (consumed === 0) return [frames, Buffer.alloc(0)];
    cur = cur.slice(consumed);
  }
  return [frames, cur];
}

const [frames, rest] = decodeAll(buf);
console.log('frames:', JSON.stringify(frames));
console.log('rest len:', rest.length);
