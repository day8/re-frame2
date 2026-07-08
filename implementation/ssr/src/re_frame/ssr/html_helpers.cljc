(ns re-frame.ssr.html-helpers
  "HTML escape + attribute-serialisation helpers shared by the SSR emitter
  (`re-frame.ssr.emit`) and the head/meta emitter
  (`re-frame.ssr.head.emit`). Both callsites `:require` this ns so the
  entity-escape rules change in exactly one place.

  Escape semantics:

    - `escape-html`              — full text-node escaping: `& < > \" '`.
    - `escape-attr`              — attribute-value escaping (we always
                                    emit double-quoted values, so only
                                    `&` and `\"` matter).
    - `escape-script-body-string`— escape `<` as `\\u003c` for strings
                                    dropped inside `<script>` bodies
                                    (security audit 2026-05-14 §P1.1,
                                    rf2-7ksyr + rf2-m5u23). JSON bodies
                                    only — every `<` is in a string.
    - `escape-edn-script-body`   — EDN-aware `<script>`-body escape for
                                    the hydration payload / streaming
                                    delta: `<` escaped only inside string
                                    literals (tokens with `<` round-trip;
                                    `</`/`<!` token breakouts fail loud)
                                    (rf2-rdxxa).
    - `validate-attr-name!`      — HTML5-grammar gate on attribute keys
                                    (security audit §P2.5, rf2-vl8ir).
    - `attr-string`              — render an attribute map as
                                    ` k1=\"v1\" k2=\"v2\"`. Boolean `true`
                                    → bare attribute name (`disabled`);
                                    `false` / `nil` → omitted entirely."
  (:require [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.ssr.hash :as hash]))

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
  helper, two call sites, no copy-paste drift.

  NOTE — this whole-string replacement is correct ONLY for JSON bodies
  (JSON-LD), where every `<` is necessarily inside a string literal and
  `\\u003c` is a valid JSON string escape. EDN bodies (the hydration
  payload / streaming delta) carry bare keyword/symbol TOKENS in which
  `<` is legal yet `\\u003c` is NOT a valid in-token escape — use
  `escape-edn-script-body` for those (rf2-rdxxa)."
  [s]
  (str/replace (str s) "<" "\\u003c"))

(defn escape-edn-script-body
  "EDN-aware variant of `escape-script-body-string` for an
  already-`pr-str`'d EDN document dropped inside a `<script
  type=\"application/edn\">` body — the hydration payload (`__rf_payload`)
  and the per-subtree streaming delta (rf2-7ksyr / rf2-rdxxa).

  Why the whole-string `<`→`\\u003c` replacement is WRONG for EDN: the
  six-character escape `\\u003c` is only meaningful to the EDN reader
  INSIDE a string literal. EDN also has bare keyword/symbol tokens whose
  grammar legally admits `<` (`:<`, `:a<b`, the symbol `a<b`). Rewriting
  every `<` in the serialized document corrupts those tokens —
  `clojure.edn/read-string` / `cljs.reader/read-string` then reject
  `:a\\u003cb` (`Invalid unicode character`) and `:\\u003c`
  (`Invalid token`), breaking hydration or silently skipping a streaming
  delta. The spec's contract (011 §Streaming SSR, §Hydration payload) is
  that the script body MUST round-trip through the EDN reader unchanged
  AND MUST NOT carry a literal `</script` (or `<!`) breakout.

  This encoder scans the document tracking string-literal context (an EDN
  string literal opens/closes on an unescaped `\"`; `\\` escapes the next
  char inside a string). In token position a backslash opens an EDN
  CHARACTER LITERAL (`\\\"`, `\\<`, `\\newline`, …): the char immediately
  after the backslash is the literal's payload, so a `\\\"` (what
  `(char 34)` prints as) does NOT open a string and a later real string
  literal's `</script>` is escaped, not mis-read as a token-position
  breakout (rf2-g15jtb):

    - INSIDE a string literal — every `<` becomes `\\u003c`. The reader
      decodes the escape back to `<` inside the string, so the value
      round-trips exactly while no literal `<` survives to start an HTML
      `</script` / `<!--` / `<![CDATA[` state switch.
    - OUTSIDE a string literal (token / structural position) — a lone `<`
      is harmless in the HTML script-data state and is left untouched so
      tokens like `:<` / `:a<b` round-trip readably. A `<` that begins a
      genuine breakout precursor — `</` (script-end) or `<!`
      (comment / CDATA opener) — has no readable in-token EDN escape, so
      it is rejected fail-loud with `:rf.error/ssr-edn-script-breakout`.
      Such a token (e.g. the symbol `a</script>b`) is an exotic,
      effectively hostile app-db value; failing closed is correct per the
      CORRECTNESS + SECURITY posture (no silent corruption, no breakout).
      (A keyword cannot carry `</` in its NAME — the first `/` is the
      namespace separator — so the realistic token-position breakout
      carrier is a symbol value or a keyword whose namespace ends in `<`
      directly abutting a `/`; both are caught here.)

  Result: the emitted `<script>` body cannot contain a literal `</script`
  / `<!` breakout, and every valid EDN payload round-trips through the
  reader — `<` in string literals, and `<` in keyword/symbol tokens that
  do not form a breakout precursor.

  Portable across CLJ/CLJS — uses only `str`/`subs`/`count`/`nth` so the
  single `.cljc` definition serves the JVM payload emitter and the shared
  streaming-delta emitter alike."
  [s]
  (let [s (str s)
        n (count s)]
    (loop [i         0
           in-string? false
           escaped?  false
           acc       (transient [])]
      (if (>= i n)
        (apply str (persistent! acc))
        (let [c (nth s i)]
          (cond
            ;; Inside a string literal.
            in-string?
            (cond
              escaped?
              (recur (inc i) true false (conj! acc c))

              (= c \\)
              (recur (inc i) true true (conj! acc c))

              (= c \")
              (recur (inc i) false false (conj! acc c))

              (= c \<)
              (recur (inc i) true false (conj! acc "\\u003c"))

              :else
              (recur (inc i) true false (conj! acc c)))

            ;; Outside any string literal — token / structural position.
            ;;
            ;; An EDN character literal opens with a backslash (`\"`, `\<`,
            ;; `\newline`, `A`, `\o101`). The character IMMEDIATELY
            ;; following the backslash is the literal's payload, NOT a
            ;; document delimiter — in particular a `\"` (the char literal
            ;; for double-quote, what `(char 34)` prints as) must NOT be
            ;; read as a string-opening quote (rf2-g15jtb). Emit the
            ;; backslash and the following payload char verbatim, then
            ;; resume normal token scanning. The payload char cannot itself
            ;; start an HTML breakout: an EDN char literal is a single
            ;; character (`\<`), and `pr-str` always separates it from the
            ;; next token with whitespace / a structural delimiter, so the
            ;; literal's `<` can never directly abut a following `/` or `!`.
            (= c \\)
            (if (< (inc i) n)
              (recur (+ i 2) false false (-> acc (conj! c) (conj! (nth s (inc i)))))
              ;; Trailing lone backslash (not reachable from valid `pr-str`
              ;; output, but keep the scanner total) — emit verbatim.
              (recur (inc i) false false (conj! acc c)))

            (= c \")
            (recur (inc i) true false (conj! acc c))

            (= c \<)
            (let [nxt (when (< (inc i) n) (nth s (inc i)))]
              (if (or (= nxt \/) (= nxt \!))
                ;; `</` or `<!` in token position is a real HTML breakout
                ;; precursor with no readable in-token EDN escape.
                (error/throw-error!
                  :rf.error/ssr-edn-script-breakout
                  'rf.ssr/html-helpers
                  (str "EDN script body carries a `<"
                       nxt "` HTML breakout precursor "
                       "in a non-string (keyword/symbol) "
                       "token, which has no readable EDN "
                       "escape; the offending token cannot "
                       "be safely emitted inside a "
                       "<script type=\"application/edn\"> "
                       "body. Restructure the offending "
                       "app-db key/value.")
                  {:recovery :restructure-the-offending-app-db-value})
                ;; Lone `<` in a token (`:<`, `:a<b`) — harmless in HTML
                ;; script-data state; leave it so the token round-trips.
                (recur (inc i) false false (conj! acc c))))

            :else
            (recur (inc i) false false (conj! acc c))))))))

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
      (error/throw-error!
        :rf.error/ssr-invalid-attribute-name
        'rf.ssr/html-helpers
        (str "attribute name violates the HTML5 grammar "
             "[A-Za-z][A-Za-z0-9_:-]*; got " (pr-str s)
             ". Rename the attribute key to a letter-led name of "
             "letters/digits/underscore/colon/hyphen.")
        {:recovery :rename-the-attribute-key
         :extra    {:attribute k}}))))

;; Prototype-pollution keys (rf2-dwds9 / security audit §XSS at output
;; boundaries). These three names, if they reach the underlying host's
;; `createElement`-equivalent on the client at hydration, can poison
;; `Object.prototype`. They are dropped at static-markup emission before
;; they ever land in the wire props map.
(def ^:private reserved-prop-keys
  #{"__proto__" "constructor" "prototype"})

;; `on*` event-handler-prop matcher (rf2-dwds9 / rf2-1uex4). Two layers,
;; both applied to the attribute name, together covering every casing a
;; case-insensitive HTML parser fires:
;;
;;   1. STRUCTURAL — framework-shaped custom handler spellings the
;;      re-frame hiccup adapters and react-dom/server recognise:
;;        - camelCase: `on` + an upper-case letter — `onClick`,
;;          `onMouseDown`, `onCustomEvent`. (`on[A-Z]…`)
;;        - kebab-case: `on-` prefix — `on-click`, `on-custom-event`.
;;          (`on-…`)
;;      Only the `on` PREFIX is case-folded (`[Oo][Nn]`), so `OnClick`
;;      / `ON-CLICK` strip too — but the discriminating class stays a
;;      strict `[A-Z]`. Making the WHOLE pattern case-insensitive would
;;      be a bug: `(?i)` over `[A-Z]` matches `[A-Za-z]`, so it would eat
;;      innocuous English-word keys (`one`, `once`, `online`, `only`)
;;      AND swallow grammar-breakout payloads (`onclick=alert(1) data-x`)
;;      that must instead surface at the `validate-attr-name!` gate. A
;;      structural handler is always `on` + upper-case or `on` + hyphen,
;;      which discriminates cleanly from `on` + lowercase-word.
;;
;;   2. CANONICAL ALLOWLIST — the all-lowercase canonical HTML spellings
;;      (`onclick`, `onload`, `onerror`, …) that layer 1 cannot catch
;;      (no upper-case letter, no hyphen) yet are the *exact* names a
;;      browser fires and the canonical choice of an attacker who
;;      controls an attribute KEY. HTML attribute names are
;;      case-insensitive, so we test `(str/lower-case nm)` against the
;;      WHATWG event-handler-content-attribute set. An allowlist (not a
;;      `on` + lowercase wildcard) is required so legitimate non-handler
;;      keys like `online` / `once` / `only` / `on` survive.
(def ^:private event-handler-name-re
  #"[Oo][Nn](?:[A-Z].*|-.*)")

;; WHATWG event-handler content attributes (HTML Living Standard
;; §"Event handlers on elements, Document objects, and Window objects",
;; plus the IDL `on*` attributes on Window/Document/Element), and the
;; W3C Touch Events Level 2 §8 `GlobalEventHandlers` touch attributes
;; (`ontouchstart` / `ontouchmove` / `ontouchend` / `ontouchcancel` —
;; rf2-cv165: the structural regex only catches the camelCase/kebab
;; spellings, so the lower-case canonical touch names MUST be enumerated
;; here or they ride through as live attributes on touch-capable
;; browsers — an XSS-equivalent gap of the same class as `:onclick`). All
;; lower-case; the lookup lower-cases the candidate name first so every
;; casing (`onload`, `ONLOAD`, `OnLoad`) is covered. Curated to the
;; event-handler names browsers actually fire — NOT a broad `on`-prefix —
;; so non-handler keys (`online`, `once`, `only`, `on`) are never eaten.
(def ^:private event-handler-allowlist
  #{"onabort" "onafterprint" "onanimationcancel" "onanimationend"
    "onanimationiteration" "onanimationstart" "onauxclick"
    "onbeforeinput" "onbeforematch" "onbeforeprint" "onbeforetoggle"
    "onbeforeunload" "onblur" "oncancel" "oncanplay" "oncanplaythrough"
    "onchange" "onclick" "onclose" "oncontextlost" "oncontextmenu"
    "oncontextrestored" "oncopy" "oncuechange" "oncut" "ondblclick"
    "ondrag" "ondragend" "ondragenter" "ondragleave" "ondragover"
    "ondragstart" "ondrop" "ondurationchange" "onemptied" "onended"
    "onerror" "onfocus" "onformdata" "onfullscreenchange"
    "onfullscreenerror" "ongotpointercapture" "onhashchange" "oninput"
    "oninvalid" "onkeydown" "onkeypress" "onkeyup" "onlanguagechange"
    "onload" "onloadeddata" "onloadedmetadata" "onloadstart"
    "onlostpointercapture" "onmessage" "onmessageerror" "onmousedown"
    "onmouseenter" "onmouseleave" "onmousemove" "onmouseout"
    "onmouseover" "onmouseup" "onoffline" "ononline" "onpagehide"
    "onpagereveal" "onpageshow" "onpageswap" "onpaste" "onpause"
    "onplay" "onplaying" "onpointercancel" "onpointerdown"
    "onpointerenter" "onpointerleave" "onpointermove" "onpointerout"
    "onpointerover" "onpointerrawupdate" "onpointerup" "onpopstate"
    "onprogress" "onratechange" "onrejectionhandled" "onreset"
    "onresize" "onscroll" "onscrollend" "onsecuritypolicyviolation"
    "onseeked" "onseeking" "onselect" "onselectionchange"
    "onselectstart" "onslotchange" "onstalled" "onstorage" "onsubmit"
    "onsuspend" "ontimeupdate" "ontoggle" "ontouchcancel" "ontouchend"
    "ontouchmove" "ontouchstart" "ontransitioncancel"
    "ontransitionend" "ontransitionrun" "ontransitionstart"
    "onunhandledrejection" "onunload" "onvolumechange" "onwaiting"
    "onwheel"})

(defn- event-handler-name?
  "True when attribute name `nm` denotes an `on*` event-handler prop that
  MUST be stripped at SSR static-markup emission (rf2-dwds9 / rf2-1uex4).

  Matches either the structural camelCase/kebab handler spellings
  (`event-handler-name-re`, case-insensitive) — so framework-shaped
  custom handlers like `:onCustomEvent` / `:on-custom-event` strip — OR
  the canonical all-lowercase HTML event-handler names in
  `event-handler-allowlist`, looked up against `(str/lower-case nm)` so
  every casing of a real handler (`onload` / `ONLOAD` / `OnLoad`) is
  caught while non-handler keys (`online` / `once` / `only` / `on`)
  survive."
  [nm]
  (or (some? (re-matches event-handler-name-re nm))
      (contains? event-handler-allowlist (str/lower-case nm))))

;; JSX source-coord props. React DevTools' "View source" gesture reads
;; `_jsxFileName` / `_jsxLineNumber` / `_jsxColumnNumber` off the
;; rendered React element. The framework no longer emits these — see
;; Spec 006 §Historical: JSX source-coord props (rf2-rohdn dropped the
;; rf2-fa4ly injection) — but user code, JSX-compiled third-party
;; libraries, or hand-stamped DevTools shims may still produce hiccup
;; carrying them. SSR MUST NOT serialise them as wire HTML attributes:
;; the leading underscore fails the conservative HTML5 attribute-name
;; grammar (`[A-Za-z][A-Za-z0-9_:-]*`), and they have no HTML wire
;; representation in any case. This stripper is defensive — it sits
;; upstream of the grammar gate so user-carried JSX source-coord props
;; pass through cleanly without surfacing as a grammar error.
;;
;; The matcher pins the three documented names (per
;; `@babel/plugin-transform-react-jsx-source`) rather than a broad
;; underscore-prefix, so an app's custom underscore-prefixed prop
;; (if grammar-legal) still surfaces the grammar error.
(def ^:private jsx-source-prop-names
  #{"_jsxFileName" "_jsxLineNumber" "_jsxColumnNumber"})

(defn strip-prop?
  "True when the attribute `[k v]` MUST be dropped at SSR static-markup
  emission per Spec 011 rule rf2-dwds9:

    - `on*` event-handler props (`:on-click`, `:onMouseDown`,
      `:onclick`, `:ONLOAD`, …). The client-side substrate adapters
      wire handlers at hydration; the server-rendered string MUST NOT
      carry them inline. Matched (case-insensitively) by
      `event-handler-name?` — the structural camelCase/kebab spellings
      plus the WHATWG canonical all-lowercase event-handler names.
    - function-valued prop values — a fn can only be a handler/callback;
      it has no HTML-attribute serialisation and must never reach output.
    - reserved prototype-pollution keys (`__proto__` / `constructor` /
      `prototype`), dropped before they reach the host createElement.
    - JSX source-coord props (`:_jsxFileName`, `:_jsxLineNumber`,
      `:_jsxColumnNumber`) — React DevTools internals that may appear
      in hiccup from user code, JSX-compiled libraries, or hand-stamped
      shims (the framework itself does not emit them; see Spec 006
      §Historical: JSX source-coord props). They have no HTML wire
      representation and would fail the HTML5 attribute-name grammar.

  Mirrors react-dom/server behaviour. Recognised here are exactly the
  props that are *safe to silently drop*; malformed keys (breakout chars)
  are NOT this fn's concern — they surface at the `validate-attr-name!`
  grammar gate (rf2-vl8ir)."
  [[k v]]
  (let [nm (name k)]
    (or (event-handler-name? nm)
        (fn? v)
        (contains? reserved-prop-keys (str/lower-case nm))
        (contains? jsx-source-prop-names nm))))

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
                                           ;; A numeric attribute VALUE is
                                           ;; canonicalised the same way the
                                           ;; render-tree hash serialises it
                                           ;; (rf2-0ypnnk) so a whole-valued
                                           ;; double renders `value="0"` (not
                                           ;; the JVM `value="0.0"`) and the
                                           ;; emitted HTML matches the hash
                                           ;; byte-for-byte cross-runtime.
                                           ;; Without this the hash would
                                           ;; AGREE while the server/client
                                           ;; attribute strings diverged — a
                                           ;; silent hydration inconsistency.
                                           (escape-attr
                                             (if (number? v)
                                               (hash/canonical-number v)
                                               v))
                                           "\"")))
                       attrs)]
    (if (seq rendered)
      (str " " (str/join " " rendered))
      "")))
