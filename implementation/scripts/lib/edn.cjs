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

  // Skip whitespace, commas, and `;`-to-end-of-line comments.
  skipSpace() {
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
      return;
    }
  }

  // Read one datum. Returns EOF at end of input. Transparently consumes any
  // number of leading `#_` discard forms (each drops the following datum).
  readForm() {
    for (;;) {
      this.skipSpace();
      const ch = this.peek();
      if (ch === EOF) return EOF;
      if (ch === '#') {
        const after = this.pos + 1 < this.len ? this.text[this.pos + 1] : EOF;
        if (after === '_') {
          this.pos += 2; // consume `#_`
          const discarded = this.readForm();
          if (discarded === EOF) throw new EdnReadError('#_ with no following form');
          continue; // now read the real next form
        }
        if (after === '{') {
          this.pos += 2; // consume `#{`
          return { edn: 'set', items: this.readSeq('}') };
        }
        throw new EdnReadError(
          `unsupported reader dispatch #${after === EOF ? '<eof>' : after}`,
        );
      }
      return this.readDatum(ch);
    }
  }

  readDatum(ch) {
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

  readSeq(close) {
    const items = [];
    for (;;) {
      this.skipSpace();
      const ch = this.peek();
      if (ch === EOF) {
        throw new EdnReadError(`unterminated collection, expected '${close}'`);
      }
      if (ch === close) {
        this.pos += 1;
        return items;
      }
      const form = this.readForm();
      if (form === EOF) {
        throw new EdnReadError(`unterminated collection, expected '${close}'`);
      }
      items.push(form);
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
