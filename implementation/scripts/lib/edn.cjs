'use strict';

/*
 * A small, complete EDN reader for the G-13 production-dependency boundary
 * proof (rf2-2n0cv).
 *
 * Where a balanced-brace byte scan cannot tell a real `}` from one that sits
 * inside a `; comment`, a "string", or a `#_` reader-discarded form, this
 * reader tokenizes EDN's lexical structure and returns the first real
 * (non-discarded) top-level datum as a tagged JS value:
 *
 *   nil / true / false          -> native JS null / boolean
 *   number                      -> { edn: 'number',  raw }
 *   "string"                    -> { edn: 'string',  value }
 *   :keyword                    -> { edn: 'keyword', name }  // name has no ':'
 *   symbol                      -> { edn: 'symbol',  name }
 *   \char                       -> { edn: 'char',    name }
 *   { … }                       -> { edn: 'map',     entries: [[k, v], …] }
 *   [ … ]                       -> { edn: 'vector',  items: [ … ] }
 *   ( … )                       -> { edn: 'list',    items: [ … ] }
 *   #{ … }                      -> { edn: 'set',     items: [ … ] }
 *
 * It is deliberately strict and fail-closed: anything it does not understand
 * (an unterminated string, an unbalanced collection, an unknown `#` dispatch)
 * throws EdnReadError so callers can reject rather than silently mis-read. It
 * is NOT a full Clojure reader — it covers exactly the EDN grammar a deps.edn
 * can contain — but, unlike the brace counter it replaces, it never mistakes
 * comment / string / discarded bytes for real structure.
 */

class EdnReadError extends Error {}

const EOF = Symbol('edn/eof');

// EDN treats commas as whitespace.
function isWhitespace(ch) {
  return (
    ch === ' ' ||
    ch === '\t' ||
    ch === '\n' ||
    ch === '\r' ||
    ch === '\f' ||
    ch === ','
  );
}

// Characters that terminate an unquoted atom (symbol / keyword / number).
const TERMINATORS = new Set(['(', ')', '[', ']', '{', '}', '"', ';', '\\']);

const STRING_ESCAPES = {
  n: '\n',
  t: '\t',
  r: '\r',
  f: '\f',
  b: '\b',
  '"': '"',
  '\\': '\\',
};

class Reader {
  constructor(text) {
    this.text = text;
    this.pos = 0;
    this.len = text.length;
  }

  peek() {
    return this.pos < this.len ? this.text[this.pos] : EOF;
  }

  // Skip everything EDN treats as ignorable between data: whitespace, commas,
  // `;`-to-end-of-line comments, and `#_` discard forms. Leaves `pos` on the
  // first significant character, which may be a closing delimiter or EOF.
  //
  // A discard is ignorable content, not a datum — that is the whole of its
  // meaning, and putting it HERE rather than in readForm is what fixes
  // rf2-vr11t. It used to be readForm's job: readForm consumed `#_`, read the
  // discarded datum, then looped round to read "the real next form" — but
  // inside a collection whose LAST element is a discard, the real next form is
  // the CLOSING DELIMITER, and readDatum rejects that. So an entirely ordinary
  // `#_`-commented-out final dependency
  //
  //   {:deps {org.clojure/clojure {:mvn/version "1.12.0"}
  //           #_day8/de-dupe #_{:git/url "https://example.invalid/x.git"}}}
  //
  // threw `unexpected '}'`, and every consumer of this reader — the release
  // lockstep gate, the bundle-isolation gate — fails CLOSED on a throw. Never a
  // false pass, but an un-runnable gate, reachable by normal editing.
  //
  // Skipping a discard means READING the datum it drops (that is how far it
  // reaches), so this recurses into readDatum. A `#_ #_ a b` chain drops two
  // data and falls out of the loop without a special case: the outer marker's
  // "next form" is itself preceded by a marker, which the recursive
  // skipIgnored() below consumes first.
  skipIgnored() {
    for (;;) {
      const ch = this.peek();
      if (ch === EOF) return;
      if (isWhitespace(ch)) {
        this.pos += 1;
        continue;
      }
      if (ch === ';') {
        while (this.pos < this.len && this.text[this.pos] !== '\n') this.pos += 1;
        continue;
      }
      if (ch === '#' && this.text[this.pos + 1] === '_') {
        this.pos += 2; // consume `#_`
        this.skipIgnored(); // ignorables between the marker and its datum
        const next = this.peek();
        if (next === EOF) throw new EdnReadError('#_ with no following form');
        this.readDatum(next); // read it, drop it
        continue;
      }
      return;
    }
  }

  // Read one datum, skipping any ignorable content ahead of it. Returns EOF at
  // end of input.
  readForm() {
    this.skipIgnored();
    const ch = this.peek();
    if (ch === EOF) return EOF;
    return this.readDatum(ch);
  }

  readDatum(ch) {
    if (ch === '#') {
      // `#_` never reaches here: skipIgnored() consumes discards ahead of every
      // readDatum call, including its own.
      const after = this.pos + 1 < this.len ? this.text[this.pos + 1] : EOF;
      if (after === '{') {
        this.pos += 2; // consume `#{`
        return { edn: 'set', items: this.readSeq('}') };
      }
      throw new EdnReadError(
        `unsupported reader dispatch #${after === EOF ? '<eof>' : after}`,
      );
    }
    if (ch === '(') {
      this.pos += 1;
      return { edn: 'list', items: this.readSeq(')') };
    }
    if (ch === '[') {
      this.pos += 1;
      return { edn: 'vector', items: this.readSeq(']') };
    }
    if (ch === '{') {
      this.pos += 1;
      return { edn: 'map', entries: this.readMap() };
    }
    if (ch === ')' || ch === ']' || ch === '}') {
      throw new EdnReadError(`unexpected '${ch}'`);
    }
    if (ch === '"') return this.readString();
    if (ch === '\\') return this.readChar();
    return this.readAtom();
  }

  // Elements up to `close`. Because skipIgnored() now runs BEFORE the
  // closing-delimiter test on every pass, a discard sitting last in the
  // collection is gone by the time we look for `}` — the ordering rf2-vr11t
  // turned on.
  readSeq(close) {
    const items = [];
    for (;;) {
      this.skipIgnored();
      const ch = this.peek();
      if (ch === EOF) {
        throw new EdnReadError(`unterminated collection, expected '${close}'`);
      }
      if (ch === close) {
        this.pos += 1;
        return items;
      }
      items.push(this.readDatum(ch));
    }
  }

  readMap() {
    const items = this.readSeq('}');
    if (items.length % 2 !== 0) {
      throw new EdnReadError('map literal has an odd number of forms');
    }
    const entries = [];
    for (let i = 0; i < items.length; i += 2) entries.push([items[i], items[i + 1]]);
    return entries;
  }

  readString() {
    this.pos += 1; // opening quote
    let value = '';
    for (;;) {
      if (this.pos >= this.len) throw new EdnReadError('unterminated string');
      const ch = this.text[this.pos];
      this.pos += 1;
      if (ch === '"') return { edn: 'string', value };
      if (ch === '\\') {
        if (this.pos >= this.len) throw new EdnReadError('unterminated string escape');
        const esc = this.text[this.pos];
        this.pos += 1;
        value += Object.prototype.hasOwnProperty.call(STRING_ESCAPES, esc)
          ? STRING_ESCAPES[esc]
          : esc;
        continue;
      }
      value += ch;
    }
  }

  readChar() {
    this.pos += 1; // backslash
    if (this.pos >= this.len) throw new EdnReadError('unterminated character literal');
    let name = this.text[this.pos];
    this.pos += 1;
    // Named characters (\newline, \space, …) run on as alphanumerics.
    while (this.pos < this.len && /[A-Za-z0-9]/.test(this.text[this.pos])) {
      name += this.text[this.pos];
      this.pos += 1;
    }
    return { edn: 'char', name };
  }

  readAtom() {
    let token = '';
    for (;;) {
      const ch = this.peek();
      if (ch === EOF || isWhitespace(ch) || TERMINATORS.has(ch)) break;
      token += ch;
      this.pos += 1;
    }
    if (token.length === 0) {
      throw new EdnReadError(`unreadable token near '${String(this.peek())}'`);
    }
    return classifyAtom(token);
  }
}

function classifyAtom(token) {
  if (token === 'nil') return null;
  if (token === 'true') return true;
  if (token === 'false') return false;
  if (token[0] === ':') return { edn: 'keyword', name: token.slice(1) };
  // A leading digit, or a sign followed by a digit, makes it a number literal.
  // We never inspect a number's value here, so keep the raw text rather than
  // wrestle with ratios / N / M suffixes / radix forms.
  if (/^[+-]?\d/.test(token)) return { edn: 'number', raw: token };
  return { edn: 'symbol', name: token };
}

// Read the first real (non-discarded) top-level datum. Throws EdnReadError on
// malformed input or when there is no datum at all.
function readEdn(text) {
  const reader = new Reader(text);
  const form = reader.readForm();
  if (form === EOF) throw new EdnReadError('no EDN form found');
  return form;
}

function isMap(form) {
  return form !== null && typeof form === 'object' && form.edn === 'map';
}

function isSymbol(form, name) {
  return (
    form !== null &&
    typeof form === 'object' &&
    form.edn === 'symbol' &&
    (name === undefined || form.name === name)
  );
}

function isKeyword(form, name) {
  return (
    form !== null &&
    typeof form === 'object' &&
    form.edn === 'keyword' &&
    (name === undefined || form.name === name)
  );
}

// Value bound to keyword key `name` in an EDN map form, or undefined.
function mapGetKeyword(mapForm, name) {
  for (const [k, v] of mapForm.entries) {
    if (isKeyword(k, name)) return v;
  }
  return undefined;
}

// True iff the EDN map form has a symbol key named `name`.
function mapHasSymbolKey(mapForm, name) {
  return mapForm.entries.some(([k]) => isSymbol(k, name));
}

// Parse `uiDepsEdn` and return the real, top-level production `:deps` map
// form — reading EDN structure, never text slices. Fail-closed on unreadable
// input, a non-map top-level form, a missing `:deps`, or a non-map `:deps`
// value, routing every rejection through the injected gate `fail`.
function productionDepsMap(uiDepsEdn, fail) {
  let top;
  try {
    top = readEdn(uiDepsEdn);
  } catch (err) {
    fail(`ui/deps.edn is not readable EDN (${err.message})`);
  }
  if (!isMap(top)) fail('ui/deps.edn top-level form is not a map');
  const deps = mapGetKeyword(top, 'deps');
  if (deps === undefined) fail('ui/deps.edn has no production :deps map');
  if (!isMap(deps)) fail('ui/deps.edn production :deps is not a map');
  return deps;
}

module.exports = {
  EdnReadError,
  readEdn,
  isMap,
  isSymbol,
  isKeyword,
  mapGetKeyword,
  mapHasSymbolKey,
  productionDepsMap,
};
