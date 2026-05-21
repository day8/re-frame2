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
    - `validate-attr-name!`      — HTML5-grammar gate on attribute keys
                                    (security audit §P2.5, rf2-vl8ir).
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

;; HTML5 attribute-name grammar (rf2-vl8ir / security audit §P2.5).
;; The spec's full production permits almost anything except whitespace,
;; `=`, quotes, `<`, `>`, `/`. We use a deliberately narrower form here:
;; first char is an ASCII letter, subsequent chars are letters / digits
;; / underscore / hyphen / colon. The broader grammar is unsafe in
;; practice — an attacker who controls an attribute KEY (rare-but-real
;; when an app splats user-supplied `:custom-attrs` maps into hiccup)
;; can sneak in `=`, quotes, or whitespace and break out of attribute
;; context to add event-handler attributes like `onclick`. The
;; conservative grammar rejects every such payload up front.
(def ^:private attr-name-grammar
  #"[A-Za-z][A-Za-z0-9_:-]*")

(defn validate-attr-name!
  "Throw `:rf.error/ssr-invalid-attribute-name` if `k`'s name string
  violates the conservative HTML5 attribute-name grammar
  `[A-Za-z][A-Za-z0-9_:-]*` (see `attr-name-grammar`).

  Returns `(name k)` on success so the caller can chain the validated
  string straight into the emit path."
  [k]
  (let [s (name k)]
    (if (re-matches attr-name-grammar s)
      s
      (throw (ex-info ":rf.error/ssr-invalid-attribute-name"
                      {:rf.error/id :rf.error/ssr-invalid-attribute-name
                       :where     'rf.ssr/html-helpers
                       :reason    (str "attribute name violates HTML5 grammar "
                                       "[A-Za-z][A-Za-z0-9_:-]*; got "
                                       (pr-str s))
                       :attribute k
                       :recovery  :no-recovery})))))

;; Prototype-pollution keys (rf2-dwds9 / security audit §XSS at output
;; boundaries). These three names, if they reach the underlying host's
;; `createElement`-equivalent on the client at hydration, can poison
;; `Object.prototype`. They are dropped at static-markup emission before
;; they ever land in the wire props map.
(def ^:private reserved-prop-keys
  #{"__proto__" "constructor" "prototype"})

;; `on*` event-handler-prop matcher (rf2-dwds9). Matches the two canonical
;; handler-prop spellings re-frame hiccup and react-dom/server recognise:
;;
;;   - camelCase: `on` followed by an UPPERCASE letter — `onClick`,
;;     `onMouseDown`, `onLoad`. (`on[A-Z]…`)
;;   - kebab-case: `on-` prefix — `on-click`, `on-mouse-down`. (`on-…`)
;;
;; Deliberately NOT a bare `(starts-with? "on")`: that ate innocuous
;; English-word attribute names like `one`, `once`, `online`, `only`.
;; A handler is always `on` + uppercase or `on` + hyphen; ordinary words
;; are `on` + lowercase with no hyphen, so this discriminates cleanly.
(def ^:private event-handler-name-re
  #"on(?:[A-Z].*|-.*)")

(defn strip-prop?
  "True when the attribute `[k v]` MUST be dropped at SSR static-markup
  emission per Spec 011 rule rf2-dwds9:

    - `on*` event-handler props (`:on-click`, `:onMouseDown`, …). The
      client-side substrate adapters wire handlers at hydration; the
      server-rendered string MUST NOT carry them inline. Matched against
      `event-handler-name-re` (camelCase `on[A-Z]` or kebab `on-`).
    - function-valued prop values — a fn can only be a handler/callback;
      it has no HTML-attribute serialisation and must never reach output.
    - reserved prototype-pollution keys (`__proto__` / `constructor` /
      `prototype`), dropped before they reach the host createElement.

  Mirrors react-dom/server behaviour. Recognised here are exactly the
  props that are *safe to silently drop*; malformed keys (breakout chars)
  are NOT this fn's concern — they surface at the `validate-attr-name!`
  grammar gate (rf2-vl8ir)."
  [[k v]]
  (let [nm (name k)]
    (or (some? (re-matches event-handler-name-re nm))
        (fn? v)
        (contains? reserved-prop-keys (str/lower-case nm)))))

(defn attr-string
  "Render an attribute map as ` k1=\"v1\" k2=\"v2\"` (leading space when
  non-empty; empty string when the map is empty). Boolean `true` emits
  the bare attribute name (`disabled`); `false` and `nil` omit the
  attribute entirely. All other values stringify and are `escape-attr`-
  escaped.

  Attribute KEYS are gated through `validate-attr-name!` (HTML5 grammar
  `[A-Za-z][A-Za-z0-9_:-]*`) — a key violating the grammar throws
  `:rf.error/ssr-invalid-attribute-name`. This closes the rf2-vl8ir XSS
  vector where an app splats an attacker-controlled `:custom-attrs`
  map into hiccup; an attacker who chooses a key like
  `\"onclick=alert(1) data-x\"` would otherwise inject an event-handler
  attribute by escaping the attribute-name context. Same gate covers
  the `:html-attrs` / `:body-attrs` flow through the host shell
  (security audit §P3.1).

  Props matching `strip-prop?` — `on*` event-handler props, function-
  valued props, and reserved prototype-pollution keys — are dropped at
  emit time per Spec 011 rule rf2-dwds9 (the per-attribute prop-name
  filter position in the locked emitter composition order). The filter
  runs ahead of the attribute-key grammar gate so a stripped prop never
  reaches `validate-attr-name!`."
  [attrs]
  ;; `keep` realises only the surviving attributes; the leading space is
  ;; added once, conditionally. A map that is non-empty but whose every
  ;; entry is stripped/omitted (e.g. `{:on-click f}`) must yield `\"\"`,
  ;; not a stray `\" \"` — so we branch on the rendered seq, not the input
  ;; map's emptiness.
  (let [rendered (keep (fn [[k v :as kv]]
                         (cond
                           (strip-prop? kv) nil
                           (true? v)  (validate-attr-name! k)
                           (false? v) nil
                           (nil? v)   nil
                           :else      (str (validate-attr-name! k)
                                           "=\""
                                           (escape-attr v)
                                           "\"")))
                       attrs)]
    (if (seq rendered)
      (str " " (str/join " " rendered))
      "")))
