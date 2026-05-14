(ns re-frame.ssr.html-helpers
  "HTML escape + attribute-serialisation helpers shared by the SSR emitter
  (`re-frame.ssr.emit`) and the head/meta emitter
  (`re-frame.ssr.head.emit`). Per rf2-x7g10 (audit rf2-asmj1 §H1/Q2).

  Pre-split, `re-frame.ssr.emit` and `re-frame.ssr.head` each carried a
  line-for-line copy of `escape-html`, `escape-attr`, and `attr-string`.
  The duplication was a hangover from when `head` shipped under a
  different artefact; under one artefact they're local-deps. Both
  callsites now `:require` this ns so the entity-escape rules change in
  exactly one place.

  Escape semantics:

    - `escape-html`              — full text-node escaping: `& < > \" '`.
    - `escape-attr`              — attribute-value escaping (we always
                                    emit double-quoted values, so only
                                    `&` and `\"` matter).
    - `escape-script-body-string`— escape `<` as `\\u003c` for strings
                                    dropped inside `<script>` bodies
                                    (security audit 2026-05-14 §P1.1,
                                    rf2-7ksyr + rf2-m5u23).
    - `attr-string`              — render an attribute map as
                                    ` k1=\"v1\" k2=\"v2\"`. Boolean `true`
                                    → bare attribute name (`disabled`);
                                    `false` / `nil` → omitted entirely."
  (:require [clojure.string :as str]))

(defn escape-html
  "Full text-node HTML escape: `& < > \" '`. Stringifies non-strings."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn escape-attr
  "Attribute-value HTML escape. We always emit double-quoted attribute
  values, so only `&` and `\"` need escaping — `<` / `>` are legal inside
  attribute values per the HTML5 parser."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "\"" "&quot;")))

(defn escape-script-body-string
  "Escape a string that will be emitted raw inside a `<script>…</script>`
  element body. The HTML tokenizer treats `</script` (case-insensitive,
  followed by `>` / `/` / tab / LF / FF / CR / space) as a script-end
  tag regardless of surrounding JSON/EDN/JS context, so any inline-data
  emission that puts user-controlled content inside `<script>` is an
  XSS vector unless the closing-tag pattern is broken.

  We escape every `<` as the Unicode escape `\\u003c`. The two known
  callers — the hydration payload EDN (rf2-7ksyr) and JSON-LD script
  bodies (rf2-m5u23) — both parse via readers (the EDN reader; the
  client's `JSON.parse` for JSON-LD) that accept `\\u003c` as the
  six-character escape sequence for `<`, so the payload round-trips
  through the reader unchanged. Escaping `<` rather than the narrower
  `</` keeps the rule simple and covers related lookalikes (`<!--`,
  `<![CDATA[`, …) the HTML parser also treats as state switches.

  Security audit 2026-05-14 §P1.1 / §P1 (rf2-7ksyr, rf2-m5u23) — single
  helper, two call sites, no copy-paste drift."
  [s]
  (str/replace (str s) "<" "\\u003c"))

(defn attr-string
  "Render an attribute map as ` k1=\"v1\" k2=\"v2\"` (leading space when
  non-empty; empty string when the map is empty). Boolean `true` emits
  the bare attribute name (`disabled`); `false` and `nil` omit the
  attribute entirely. All other values stringify and are `escape-attr`-
  escaped."
  [attrs]
  (if (empty? attrs)
    ""
    (str " "
         (str/join " "
                   (keep (fn [[k v]]
                           (cond
                             (true? v)  (name k)
                             (false? v) nil
                             (nil? v)   nil
                             :else      (str (name k) "=\"" (escape-attr v) "\"")))
                         attrs)))))
