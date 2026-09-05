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
    - `raw-text-tags` /
      `escape-raw-text`          — the HTML raw-text elements (`<script>`
                                    / `<style>`) whose body is emitted
                                    VERBATIM with only React's context-
                                    safe closing-sequence rewrite — NO
                                    entity escaping (the HTML parser never
                                    decodes character references inside a
                                    raw-text element). ONE shared
                                    implementation for the S5 serialiser
                                    (`re-frame.ssr.ui-tree`) and both
                                    hiccup emitters (`emit` /
                                    `streaming`), so every SSR path emits
                                    byte-identical raw-text.
    - `escape-script-body-string`— escape `<` as `\\u003c` for strings
                                    dropped inside `<script>` bodies. JSON bodies
                                    only — every `<` is in a string.
    - `escape-edn-script-body`   — EDN-aware `<script>`-body escape for
                                    the hydration payload / streaming
                                    delta: `<` escaped only inside string
                                    literals (tokens with `<` round-trip;
                                    `</`/`<!` token breakouts fail loud).
    - `validate-attr-name!`      — HTML5-grammar gate on attribute keys.
    - `boolean-attr-class` /
      `boolean-attrs` /
      `booleanish-attrs` /
      `overloaded-boolean-attrs`  — the react-dom 19.2.0 boolean
                                    attribute-value classes (Spec 004B
                                    §Booleans and their neighbours). ONE
                                    roster, read by BOTH the hiccup
                                    emitter here and the structural-tree
                                    serialiser (`re-frame.ssr.ui-tree`).
    - `attr-string`              — render an attribute map as
                                    ` k1=\"v1\" k2=\"v2\"`. A boolean is
                                    rendered by its attribute's class:
                                    `aria-*` / `data-*` / booleanish
                                    stringify `true` AND `false`; boolean
                                    and overloaded-boolean names keep
                                    presence semantics; `nil` → omitted."
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

;; ---------------------------------------------------------------------------
;; Raw-text elements — <script>/<style> content is HTML RAW TEXT (rf2-2dh3b)
;;
;; The single shared home for the raw-text emission rule (hoisted from
;; `re-frame.ssr.ui-tree` per rf2-xbvzh, ruling Option (a)). ALL three SSR
;; paths — the S5 structural serialiser and both hiccup emitters — call
;; this ONE implementation, so an ordinary inline `<script>`/`<style>`
;; with the same author content serialises to byte-identical HTML on every
;; path. This is the AUTHOR-CONTENT channel; the stricter DATA-payload
;; helpers (`escape-script-body-string` / `escape-edn-script-body`, below)
;; are a separate concern and unchanged.
;; ---------------------------------------------------------------------------

(def raw-text-tags
  "The HTML RAW-TEXT elements whose text children react-dom/server 19.2 emits
  WITHOUT entity escaping. React special-cases EXACTLY these two: `:title` and
  `:textarea` are escapable RCDATA (escaped normally, so NOT here)."
  #{"script" "style"})

(defn escape-raw-text
  "Serialise the text content of a raw-text element (`<script>` / `<style>`)
  the way react-dom/server 19.2 does — the raw-text half of the conversion
  table's escaping row (Spec 004B §Children, text, and escaping; the blanket
  `escape-html` row does NOT hold inside raw-text elements).

  The HTML parser does not decode character references inside raw-text
  elements, so routing script/style text through `escape-html` CORRUPTS it: a
  valid `a & b < c` becomes the literal DOM text `a &amp; b &lt; c`, and CSS/JS
  containing `<`/`&` stops meaning what it says. React therefore emits the text
  VERBATIM and only rewrites an embedded closing-tag sequence to a
  CONTEXT-SAFE spelling so the raw-text parser cannot terminate the element
  early (byte-parity with React's `scriptRegex`/`styleRegex` + replacers):

    - `<script>`: `(<|</)script` (case-insensitive) -> the `s`/`S` becomes the
      JavaScript unicode escape `\\u0073` / `\\u0053`. The JS engine reads it
      back as `s`/`S`; the HTML parser no longer sees `</script`. The same
      escape is a valid JSON string escape, so a JSON data island written as
      ordinary `<script>` string content round-trips through `JSON.parse`.
    - `<style>`:  `(<|</)style`  -> the `s`/`S` becomes the CSS escape
      `\\73 ` / `\\53 ` (the trailing space terminates the hex escape). CSS
      reads it back as `s`/`S`.

  NOT a sanitiser, and NO XSS hole beyond React's own. Raw-text emission is
  the same trusted content react-dom/server itself emits raw; the closing-
  sequence rewrite is the same breakout guard React applies — aimed at the
  DATA (an attacker-supplied `</script>` payload can no longer terminate the
  element and pivot into HTML parsing). Residual JS/CSS-language-context
  safety of interpolated data is author-owned, exactly as in React. The
  DATA-payload channels (JSON-LD head, `__rf_payload`/EDN wire) keep their
  stricter data-aware escapes (`escape-script-body-string` /
  `escape-edn-script-body`)."
  [tag-lc s]
  (case tag-lc
    "script" (str/replace s #"(</?)([sS])([cC][rR][iI][pP][tT])"
                          (fn [[_ prefix s-char suffix]]
                            (str prefix (if (= s-char "s") "\\u0073" "\\u0053") suffix)))
    "style"  (str/replace s #"(</?)([sS])([tT][yY][lL][eE])"
                          (fn [[_ prefix s-char suffix]]
                            (str prefix (if (= s-char "s") "\\73 " "\\53 ") suffix)))
    s))

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

;; Conservative HTML5 attribute-name grammar.
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

;; Prototype-pollution keys. These three names, if they reach the host's
;; `createElement`-equivalent on the client at hydration, can poison
;; `Object.prototype`. They are dropped at static-markup emission before
;; they ever land in the wire props map.
(def ^:private reserved-prop-keys
  #{"__proto__" "constructor" "prototype"})

;; `on*` event-handler-prop matcher. Two layers,
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
;; (`ontouchstart` / `ontouchmove` / `ontouchend` / `ontouchcancel`).
;; The structural regex only catches the camelCase/kebab
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
    "onchange" "onclick" "onclose" "oncommand" "oncontextlost"
    "oncontextmenu" "oncontextrestored" "oncopy" "oncuechange" "oncut"
    "ondblclick"
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
  must be stripped at SSR static-markup emission.

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
;; rendered React element. The framework does not emit them, but user code,
;; JSX-compiled third-party
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
  emission:

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

;; ---- boolean attribute-value classes (rf2-r9kf) ---------------------------
;;
;; HTML/React attributes do NOT share one boolean rule, so a `false` value
;; cannot mean "omit" everywhere. Spec 004B §Booleans and their neighbours
;; carries the react-dom 19.2.0 table, row-by-row probed; these are its three
;; rosters and the classifier both SSR serialisers read. The structural-tree
;; serialiser (`re-frame.ssr.ui-tree`) already followed the table; the hiccup
;; emitter did not, and dropped `aria-expanded false` / `contentEditable
;; false` entirely — markup asserting the OPPOSITE of what the author wrote,
;; with no hydration-mismatch signal to catch it (011 §What React-native
;; adoption does not catch — attribute-only mismatches).
;;
;; ONE roster, read by both — a second copy is a drift surface, and the two
;; emitters' PIPELINES stay separate (004B) because only the rosters and the
;; name→class function are shared; each emitter keeps its own attribute-name
;; handling and its own escape.
;;
;; WHAT CHECKS THESE ROSTERS, AND WHY IT CANNOT BE A PARITY TEST (the
;; rf2-r9kf audit's finding). Sharing one roster between two serialisers
;; removed the drift between them and, with it, the only check that had been
;; watching: two consumers of one table agree with each other whatever the
;; table says. The first version of these rosters was internally consistent
;; and still missing eight names — four presence, four stringifying — and
;; every in-repo test stayed green, because every test compared re-frame with
;; re-frame.
;;
;; So the rosters are pinned against something OUTSIDE them:
;; `re-frame.ssr-boolean-attr-react-parity-test` reads
;; `react_dom_probe/boolean_attr_classes.edn` — react-dom's own markup for
;; every boolean-valued attribute React accepts, measured from the installed
;; package by `react_dom_probe/boolean_attr_classes.cjs` — derives the class
;; from those bytes, and fails when `boolean-attr-class` disagrees IN EITHER
;; DIRECTION. Adding a name here without React's evidence reds that test just
;; as omitting one does. Two names are deliberate, documented exceptions;
;; the test names them and says why.
;;
;; THE EVIDENCE CARRIES SIX VALUES PER NAME, NOT TWO (rf2-u82a). `true` and
;; `false` cannot separate `:presence` from `:overloaded` — the two render
;; identically for both booleans — so a boolean-only fixture let the classes
;; be merged with every row green, and `attr-string` serialised a non-boolean
;; value wrongly for every member of `boolean-attrs`. The four non-booleans
;; `"yes"`, `""`, `0` and `"0"` are what tell the classes apart and pin the
;; JS-truthiness collapse the presence class runs on.

(def boolean-attrs
  "HTML boolean attributes: `true` → presence, `false`/absent → omitted.
  Tracks react-dom 19.2.0 (004B §Booleans and their neighbours); keyed by
  the hyphen-collapsed lowercase author name.

  Pinned against react-dom's measured output, not against a restatement of
  it — see the roster note above and
  `re-frame.ssr-boolean-attr-react-parity-test`."
  #{"allowfullscreen" "async" "autofocus" "autoplay" "checked" "controls"
    "default" "defer" "disabled" "disablepictureinpicture"
    "disableremoteplayback" "formnovalidate" "hidden" "inert" "ismap"
    "itemscope" "loop" "multiple" "muted" "nomodule" "novalidate" "open"
    "playsinline" "readonly" "required" "reversed" "scoped" "seamless"
    "selected"})

(def booleanish-attrs
  "`true`/`false` → `\"true\"`/`\"false\"`, never omitted. Tracks react-dom
  19.2.0.

  Beyond the three HTML names, react-dom stringifies four SVG attributes the
  same way — `autoReverse`, `externalResourcesRequired`, `focusable`,
  `preserveAlpha` — whose SVG value space is literally the STRINGS
  `\"true\"`/`\"false\"`, so an omitted `false` is a different document rather
  than a tidier one: `focusable=\"false\"` says the element is not focusable,
  and no attribute says nothing at all. That is the same shape as the ARIA
  case, and it is why these four cannot ride the presence class."
  #{"contenteditable" "draggable" "spellcheck"
    "autoreverse" "externalresourcesrequired" "focusable" "preservealpha"})

(def overloaded-boolean-attrs
  "`true` → bare presence, `false` → omitted, any other value stringifies.
  Tracks react-dom 19.2.0."
  #{"download" "capture"})

(defn presence-value-truthy?
  "Would react-dom treat this value on a `:presence` attribute as present?
  (rf2-u82a.)

  react-dom's pure-boolean branch is a plain JAVASCRIPT truthiness test —
  `if (value && …) push(name, '=\"\"')` — so a presence attribute COLLAPSES
  every truthy value onto the bare name and writes nothing at all for a falsy
  one. `{:disabled \"yes\"}` renders `disabled` and `{:disabled \"\"}` renders
  nothing.

  CLOJURE DISAGREES WITH JAVASCRIPT ABOUT EXACTLY TWO OF THE VALUES THAT CAN
  APPEAR HERE, which is the whole reason this is a named predicate rather than
  a `when`: `\"\"` and the NUMBER `0` are logically TRUE in Clojure and CLJS
  and falsy in JS, so `(when v …)` emits a bare attribute where react-dom
  emits none — a server/client divergence at the DOM level, not merely in
  bytes, and Spec 011 records that React does not guarantee to patch an
  attribute-only hydration mismatch. The trap in the other direction is the
  STRING `\"0\"`, which is a non-empty string and therefore truthy; only the
  number is not.

  Spelled out rather than reached for through a host truthiness cast, because
  this is `.cljc`: the JVM has no JS coercion, and the two runtimes must emit
  the same bytes for the same tree (the render-tree hash compares them).
  Beyond strings and numbers everything is truthy — keywords, collections,
  objects — matching JS, where every object is truthy. `NaN` is JS-falsy and
  is tested as `(not= v v)`, the one NaN check that reads the same on both
  runtimes.

  TOTAL over every value an attribute map can carry, `nil` and booleans
  included, rather than documented as undefined for them. `attr-string` does
  drop `nil` and dispatch booleans before it asks — but `re-frame.ssr.ui-tree`
  routes both straight here, and a predicate whose contract said \"never
  called with `nil`\" would have answered `true` for one and emitted an
  attribute react-dom omits. `null` is falsy in JS, so `nil` is falsy here."
  [v]
  (cond
    (nil? v)     false
    (boolean? v) v
    (string? v)  (not= "" v)
    (number? v)  (not (or (zero? v) (not= v v)))
    :else        true))

(defn boolean-attr-class
  "Author attribute NAME (a string, no namespace) → the class that decides
  how a value for it serialises:

    `:stringify`  — `aria-*`, `data-*`, and the booleanish family
                    (`contentEditable` / `draggable` / `spellCheck` plus the
                    four SVG names in `booleanish-attrs`):
                    `true`/`false` → `\"true\"`/`\"false\"`, NEVER omitted;
                    any other value stringifies.
    `:presence`   — the true HTML boolean attributes (`disabled`, `checked`,
                    …): `true` → bare name, `false` → omitted. `=\"false\"` is
                    still TRUTHY to a browser here, so omission is the only
                    correct rendering of `false`. A NON-boolean value is
                    collapsed the same way, on `presence-value-truthy?`.
    `:overloaded` — `download` and `capture`: the two booleans exactly as
                    `:presence`, but a non-boolean value is KEPT
                    (`download=\"report.pdf\"`).
    `:ordinary`   — everything else: a boolean never reaches markup at all
                    (react-dom drops it rather than inventing a bare
                    attribute); any other value stringifies.

  `:presence` AND `:overloaded` WERE ONE CLASS UNTIL rf2-u82a, and the merge
  was invisible for as long as only BOOLEAN values consulted this fn — the two
  rules are identical for `true` and for `false`, and 004B's own tables state
  them with the same two words. They part on the third case, which is where
  `download=\"report.pdf\"` has to survive and `disabled=\"yes\"` has to
  collapse, so a classifier that cannot tell them apart cannot serialise a
  non-boolean value correctly for either. The split is now derived from
  react-dom's measured bytes for a non-boolean value, in
  `re-frame.ssr-boolean-attr-react-parity-test`, exactly as the other three
  classes are.

  TWO NAMES ARE DELIBERATELY NOT WHAT REACT DOES, and both are recorded
  rather than left to be rediscovered (rf2-r9kf, second pass):

    `value`  — react-dom stringifies a BOOLEAN `value` (`value=\"true\"`)
               because `value` shares the code branch with the booleanish
               names, not because it is one. Here it stays `:ordinary`: a
               boolean `value` on a form control is an author error, not a
               state, and `value` is a 004B form-control special form whose
               meaning already differs per element (`<input>` attribute,
               `<textarea>` text child, `<select>` → `selected` on an
               option). Classifying it in this shared roster would change
               `re-frame.ssr.ui-tree`'s handling of that special form from
               here, which is 004B's call and not this ns's.
    `ismap`  — kept `:presence` on 004B §Booleans and their neighbours,
               which names it explicitly and, since rf2-u6zw, carries the
               divergence and its reason in the row itself. react-dom 19.2.0
               accepts NO boolean `ismap` in any spelling — the name is absent
               from its `possibleStandardNames` altogether, so a boolean warns
               \"Received `true` for a non-boolean attribute\" and emits
               nothing — which is why it can never appear in the evidence
               fixture. `ismap` IS a real HTML boolean attribute on `<img>`,
               so presence is the HTML-correct rendering, and 004B has ruled
               that the grammar tracks HTML where the two part on a genuine
               HTML boolean attribute. The class here is unchanged by that
               ruling; only the spec's stated provenance for it was wrong.

  Names are matched hyphen-collapsed and lowercased, so `:content-editable`
  and `:contentEditable` classify alike. Only the CLASS is decided here — the
  emitted attribute NAME is each caller's business (the hiccup emitter writes
  author names verbatim; `re-frame.ssr.ui-tree` maps them through the React
  prop vocabulary)."
  [attribute-name]
  (let [collapsed (str/lower-case (str/replace attribute-name "-" ""))]
    (cond
      (str/starts-with? attribute-name "aria-")        :stringify
      (str/starts-with? attribute-name "data-")        :stringify
      (contains? booleanish-attrs collapsed)           :stringify
      (contains? overloaded-boolean-attrs collapsed)   :overloaded
      (contains? boolean-attrs collapsed)              :presence
      :else                                            :ordinary)))

;; ---- inline-style map serialisation (rf2-l6h6a) ---------------------------
;;
;; A hiccup `:style` whose value is a MAP must serialise to a CSS declaration
;; string (`{:margin "0 1em"}` → `margin:0 1em`), NOT the EDN print of the map
;; (`{:margin &quot;0 1em&quot;}`). The server HTML must match what the client
;; React path emits from the same style object, or React 19 logs a hydration
;; attribute mismatch on first load (rf2-l6h6a — surfaced by the generated SSR
;; scaffold's counter-value span).
;;
;; The rules mirror `react-dom/server`'s `pushStyleAttribute` — the same
;; contract the reagent-slim static-markup emitter pins in its IMPL-SPEC §8.3.
;; It is duplicated here by intent: bundle isolation forbids `re-frame.ssr`
;; requiring the adapter, exactly as `void-elements` is duplicated in
;; `emit.cljc`.

(defn- style-name->css
  "camelCase style-property name → kebab CSS name, matching React's
  `pushStyleAttribute` conversion: insert `-` before each upper-case char,
  lower-case, then `^ms- → -ms-` (so `msFlex` → `-ms-flex`). An already-kebab
  name (`margin-top`) has no upper-case chars and is returned unchanged."
  [raw]
  (-> raw
      (str/replace #"([A-Z])" "-$1")
      (str/lower-case)
      (str/replace #"^ms-" "-ms-")))

;; React 19.2.0's `unitlessNumber` set, mirrored verbatim from the authoritative
;; `reagent2.dom.server/unitless-style-props` (camelCase keys), then reprojected
;; ONCE to the kebab CSS names `style-name->css` produces so a numeric value can
;; be classified without re-deriving the camelCase form. Deriving the kebab set
;; from the camelCase source (rather than hand-transcribing) preserves React's
;; own quirks — e.g. the capital-K typo `WebKitBoxFlexGroup` reprojects to
;; `-web-kit-box-flex-group`, which a real `:-webkit-box-flex-group`/
;; `:WebkitBoxFlexGroup` prop never matches, so it gets `px` exactly as React
;; does (byte-parity with `react-dom/server`).
(def ^:private unitless-style-props-camel
  #{"animationIterationCount" "aspectRatio" "borderImageOutset"
    "borderImageSlice" "borderImageWidth" "boxFlex" "boxFlexGroup"
    "boxOrdinalGroup" "columnCount" "columns" "flex" "flexGrow"
    "flexPositive" "flexShrink" "flexNegative" "flexOrder" "gridArea"
    "gridRow" "gridRowEnd" "gridRowSpan" "gridRowStart" "gridColumn"
    "gridColumnEnd" "gridColumnSpan" "gridColumnStart" "fontWeight"
    "lineClamp" "lineHeight" "opacity" "order" "orphans" "scale"
    "tabSize" "widows" "zIndex" "zoom" "fillOpacity" "floodOpacity"
    "stopOpacity" "strokeDasharray" "strokeDashoffset" "strokeMiterlimit"
    "strokeOpacity" "strokeWidth" "MozAnimationIterationCount" "MozBoxFlex"
    "MozBoxFlexGroup" "MozLineClamp" "msAnimationIterationCount" "msFlex"
    "msZoom" "msFlexGrow" "msFlexNegative" "msFlexOrder" "msFlexPositive"
    "msFlexShrink" "msGridColumn" "msGridColumnSpan" "msGridRow"
    "msGridRowSpan" "WebkitAnimationIterationCount" "WebkitBoxFlex"
    "WebKitBoxFlexGroup" "WebkitBoxOrdinalGroup" "WebkitColumnCount"
    "WebkitColumns" "WebkitFlex" "WebkitFlexGrow" "WebkitFlexPositive"
    "WebkitFlexShrink" "WebkitLineClamp"})

(def ^:private unitless-style-css-names
  (into #{} (map style-name->css) unitless-style-props-camel))

(defn- style-token->str
  "Serialise a scalar style value to its string form: keyword/symbol → name
  (so `:red` / `:flex` render bare), everything else `str`-ed."
  [v]
  (if (or (keyword? v) (symbol? v)) (name v) (str v)))

(defn style-map->css
  "Serialise a hiccup `:style` MAP to an HTML inline-style declaration string,
  matching `react-dom/server`'s `pushStyleAttribute` so the server HTML agrees
  with the client React render (rf2-l6h6a):

    - camelCase property names → kebab CSS names (`:marginTop` → `margin-top`);
    - a NUMERIC value gets a `px` suffix unless it is `0` or the property is
      unitless (`:flex-grow`, `:z-index`, `:opacity`, …); numbers are
      canonicalised cross-runtime (`hash/canonical-number`) so a JVM `1.0`
      and a CLJS `1` emit the same `1px`;
    - an entry whose value is nil / boolean / \"\" is omitted entirely (no
      empty `prop:` declaration);
    - CSS custom properties (`--foo`) pass through name + value verbatim (no
      `px` logic)."
  [m]
  (->> m
       (keep (fn [[k v]]
               (when (and (some? v) (not (boolean? v)) (not= "" v))
                 (let [raw (name k)]
                   (if (str/starts-with? raw "--")
                     ;; CSS custom property: name + value verbatim.
                     (str raw ":" (style-token->str v))
                     (let [css-name (style-name->css raw)]
                       (str css-name ":"
                            (if (number? v)
                              (let [n (hash/canonical-number v)]
                                (if (or (zero? v)
                                        (contains? unitless-style-css-names css-name))
                                  n
                                  (str n "px")))
                              (style-token->str v)))))))))
       (str/join ";")))

(defn attr-string
  "Render an attribute map as ` k1=\"v1\" k2=\"v2\"` (leading space when
  non-empty; empty string when the map is empty). A `nil` value omits the
  attribute entirely. All non-boolean values stringify and are
  `escape-attr`-escaped.

  A value is rendered by the attribute's CLASS, not by the value alone
  (`boolean-attr-class`, rf2-r9kf / rf2-u82a): `aria-*` / `data-*` /
  booleanish names stringify BOTH `true` and `false`
  (`aria-expanded=\"false\"`); true boolean and overloaded-boolean names keep
  presence semantics for a boolean (`true` → bare `disabled`, `false` →
  omitted); a boolean on any other attribute is dropped rather than emitted
  as a bare name.

  A NON-BOOLEAN value asks the class too, and the two presence classes part
  there: a `:presence` name COLLAPSES it on react-dom's JS truthiness
  (`{:disabled \"yes\"}` → bare `disabled`, `{:disabled \"\"}` and
  `{:disabled 0}` → nothing — see `presence-value-truthy?`), while an
  `:overloaded` name keeps it (`{:download \"report.pdf\"}` →
  `download=\"report.pdf\"`). Everything else stringifies.

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
                           ;; rf2-r9kf — a BOOLEAN value's rendering depends on
                           ;; the attribute NAME, not on the value alone. See
                           ;; `boolean-attr-class` and 004B §Booleans and their
                           ;; neighbours.
                           (boolean? v)
                           (case (boolean-attr-class (name k))
                             :stringify  (str (validate-attr-name! k)
                                              "=\"" (escape-attr v) "\"")
                             (:presence
                              :overloaded) (when (true? v) (validate-attr-name! k))
                             :ordinary   nil)
                           (nil? v)   nil

                           ;; rf2-u82a — a PRESENCE attribute collapses a
                           ;; non-boolean value too, on react-dom's JS
                           ;; truthiness. Asking the class BEFORE the value is
                           ;; the whole repair: this branch used to be reached
                           ;; only after `(boolean? v)` failed, so every
                           ;; non-boolean fell through to the ordinary
                           ;; stringify below and emitted `disabled="yes"`
                           ;; where react-dom emits `disabled=""` — a DOM-level
                           ;; hydration divergence, and one the structural-tree
                           ;; serialiser next door already got right.
                           ;; `:overloaded` deliberately does NOT come here:
                           ;; keeping the value is what it is for
                           ;; (`download="report.pdf"`).
                           (= :presence (boolean-attr-class (name k)))
                           (when (presence-value-truthy? v)
                             (validate-attr-name! k))

                           :else
                           (let [rendered-val
                                 (cond
                                   ;; A map-valued `:style` serialises to a CSS
                                   ;; declaration string, matching react-dom/
                                   ;; server's `pushStyleAttribute` so the server
                                   ;; HTML agrees with the client React render
                                   ;; (rf2-l6h6a). A string `:style` value is
                                   ;; already CSS and rides the default branch
                                   ;; verbatim.
                                   (and (map? v) (= "style" (name k)))
                                   (style-map->css v)
                                   ;; A numeric attribute VALUE is canonicalised
                                   ;; the same way the render-tree hash serialises
                                   ;; it (rf2-0ypnnk) so a whole-valued double
                                   ;; renders `value="0"` (not the JVM
                                   ;; `value="0.0"`) and the emitted HTML matches
                                   ;; the hash byte-for-byte cross-runtime.
                                   ;; Without this the hash would AGREE while the
                                   ;; server/client attribute strings diverged — a
                                   ;; silent hydration inconsistency.
                                   (number? v) (hash/canonical-number v)
                                   :else       v)]
                             (str (validate-attr-name! k)
                                  "=\"" (escape-attr rendered-val) "\""))))
                       attrs)]
    (if (seq rendered)
      (str " " (str/join " " rendered))
      "")))
