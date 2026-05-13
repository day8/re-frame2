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

    - `escape-html`  — full text-node escaping: `& < > \" '`.
    - `escape-attr`  — attribute-value escaping (we always emit double-
                       quoted values, so only `&` and `\"` matter).
    - `attr-string`  — render an attribute map as ` k1=\"v1\" k2=\"v2\"`.
                       Boolean `true` → bare attribute name (`disabled`);
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
