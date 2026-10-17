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
                                    raw-text element).
    - `newline-eating-tags` /
      `sole-string-child` /
      `leading-newline-compensation`
                                 — the HTML elements (`<pre>` / `<listing>` /
                                    `<textarea>`) whose parser eats one
                                    leading LF, and the compensating LF
                                    react-dom/server 19.2 prefixes for a
                                    single-string body.
    - `escape-script-body-string`— escape `<` as `\\u003c` for strings
                                    dropped inside `<script>` bodies. JSON bodies
                                    only — every `<` is in a string.
    - `escape-edn-script-body`   — EDN-aware `<script>`-body escape for
                                    the hydration payload: `<` escaped
                                    only inside string
                                    literals (tokens with `<` round-trip;
                                    `</`/`<!` token breakouts fail loud).
    - `validate-attr-name!`      — HTML5-grammar gate on attribute keys.
    - `validate-tag-name!`       — HTML5 / SVG / MathML element-name gate
                                    on hiccup heads.
    - `boolean-attr-class` /
      `boolean-attrs` /
      `booleanish-attrs` /
      `overloaded-boolean-attrs`  — the react-dom 19.3.0 boolean
                                    attribute-value classes (Spec 004B
                                    §Booleans and their neighbours).
    - `dom-attr-aliases`         — React prop name → the DOM attribute
                                    name react-dom/server writes (Spec
                                    004B §Attribute names).
    - `attr-string`              — render an attribute map as
                                    ` k1=\"v1\" k2=\"v2\"`. A boolean is
                                    rendered by its attribute's class:
                                    `aria-*` / `data-*` / booleanish
                                    stringify `true` AND `false`; boolean
                                    and overloaded-boolean names keep
                                    presence semantics; `nil` → omitted."
  (:require [clojure.string :as str]
            [re-frame.error :as rf.error]
            [re-frame.ssr.hash :as rf.ssr.hash]))

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
;; Raw-text elements — <script>/<style> content is HTML RAW TEXT
;;
;; The home for the raw-text emission rule the hiccup emitter applies to an
;; ordinary inline `<script>`/`<style>`. This is the AUTHOR-CONTENT channel;
;; the stricter DATA-payload helpers (`escape-script-body-string` /
;; `escape-edn-script-body`, below) are a separate concern.
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

;; ---------------------------------------------------------------------------
;; Newline-eating elements — leading-LF compensation
;;
;; The home for the leading-LF rule the hiccup emitter (`emit`) applies: a
;; body emitted without it loses a character at parse time.
;; ---------------------------------------------------------------------------

(def newline-eating-tags
  "The HTML elements whose parser DROPS one leading LF immediately after the
  start tag — `<pre>`, `<listing>`, `<textarea>`. react-dom/server 19.2
  compensates by prefixing one extra LF so content that begins with a newline
  survives the parse round-trip."
  #{"pre" "listing" "textarea"})

(defn sole-string-child
  "The single STRING body of a hiccup element's `children`, or nil.

  React's leading-LF doctoring applies only when the element's `children` IS a
  string (`typeof children === 'string'`) — one text child and nothing else. A
  multi-child body, an element child, or an empty body is left untouched, and
  this returns nil for each. The hiccup lever for
  `leading-newline-compensation`."
  [children]
  (let [content (seq children)]
    (when (and content (nil? (next content)) (string? (first content)))
      (first content))))

(defn leading-newline-compensation
  "-> the compensating LF (`\"\\n\"`) react-dom/server 19.2 prefixes inside a
  newline-eating element (`newline-eating-tags`), or `\"\"` when none is owed.
  `tag-lc` is the LOWER-CASED tag name; `content-string` is the element's sole
  string body, or nil when it has none.

  HTML parsing eats the FIRST LF immediately after `<pre>` / `<listing>` /
  `<textarea>` (the newline-eating elements). So markup whose textarea value
  or pre text begins with LF would, without compensation, parse to a DOM
  carrying one FEWER newline than authored — a silent loss of an authored
  character relative to the client's Reagent/React rendering of the same
  `.cljc` view. React prefixes exactly one LF, but ONLY for a SINGLE
  STRING body: multiple or element children are left untouched, because the
  parser's newline-eating still applies but React does not doctor a multi-child
  body. Passing nil for `content-string` is how a caller says \"not a single
  string body\", and yields `\"\"`.

  Only a leading LF triggers it. A leading CR (`\\r`) does not — the tokenizer
  eats an LF, and `\\r\\n` normalisation happens earlier in the input stream
  pass, so react-dom/server compensates neither."
  [tag-lc content-string]
  (if (and (contains? newline-eating-tags tag-lc)
           (string? content-string)
           (str/starts-with? content-string "\n"))
    "\n"
    ""))

(defn escape-script-body-string
  "Escape a string that will be emitted raw inside a `<script>…</script>`
  element body. The HTML tokenizer treats `</script` (case-insensitive,
  followed by `>` / `/` / tab / LF / FF / CR / space) as a script-end
  tag regardless of surrounding JSON/EDN/JS context, so any inline-data
  emission that puts user-controlled content inside `<script>` is an
  XSS vector unless the closing-tag pattern is broken.

  We escape every `<` as the Unicode escape `\\u003c`. Its caller, the
  JSON-LD script body (`re-frame.ssr.head.emit`), is parsed by the
  client's `JSON.parse`, which accepts `\\u003c` as the
  six-character escape sequence for `<`, so the payload round-trips
  through the reader unchanged. Escaping `<` rather than the narrower
  `</` keeps the rule simple and covers related lookalikes (`<!--`,
  `<![CDATA[`, …) the HTML parser also treats as state switches.

  One helper serves both host arms of that caller, so there is no
  copy-paste drift.

  NOTE — this whole-string replacement is correct ONLY for JSON bodies
  (JSON-LD), where every `<` is necessarily inside a string literal and
  `\\u003c` is a valid JSON string escape. EDN bodies (the hydration
  payload) carry bare keyword/symbol TOKENS in which
  `<` is legal yet `\\u003c` is NOT a valid in-token escape — use
  `escape-edn-script-body` for those."
  [s]
  (str/replace (str s) "<" "\\u003c"))

(defn escape-edn-script-body
  "EDN-aware variant of `escape-script-body-string` for an
  already-`pr-str`'d EDN document dropped inside a `<script
  type=\"application/edn\">` body — the hydration payload (`__rf_payload`).

  Why the whole-string `<`→`\\u003c` replacement is WRONG for EDN: the
  six-character escape `\\u003c` is only meaningful to the EDN reader
  INSIDE a string literal. EDN also has bare keyword/symbol tokens whose
  grammar legally admits `<` (`:<`, `:a<b`, the symbol `a<b`). Rewriting
  every `<` in the serialized document corrupts those tokens —
  `clojure.edn/read-string` / `cljs.reader/read-string` then reject
  `:a\\u003cb` (`Invalid unicode character`) and `:\\u003c`
  (`Invalid token`), breaking hydration. The spec's contract (011
  §Hydration payload) is
  that the script body MUST round-trip through the EDN reader unchanged
  AND MUST NOT carry a literal `</script` (or `<!`) breakout.

  This encoder scans the document tracking string-literal context (an EDN
  string literal opens/closes on an unescaped `\"`; `\\` escapes the next
  char inside a string). In token position a backslash opens an EDN
  CHARACTER LITERAL (`\\\"`, `\\<`, `\\newline`, …): the char immediately
  after the backslash is the literal's payload, so a `\\\"` (what
  `(char 34)` prints as) does NOT open a string and a later real string
  literal's `</script>` is escaped, not mis-read as a token-position
  breakout:

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
  single `.cljc` definition serves every host."
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
            ;; `\newline`, `\u0041`, `\o101`). The character IMMEDIATELY
            ;; following the backslash is the literal's payload, NOT a
            ;; document delimiter — in particular a `\"` (the char literal
            ;; for double-quote, what `(char 34)` prints as) must NOT be
            ;; read as a string-opening quote. Emit the
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
                (rf.error/throw-error!
                  :rf.error/ssr-edn-script-breakout
                  're-frame.ssr.html-helpers
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
      (rf.error/throw-error!
        :rf.error/ssr-invalid-attribute-name
        're-frame.ssr.html-helpers
        (str "attribute name violates the HTML5 grammar "
             "[A-Za-z][A-Za-z0-9_:-]*; got " (pr-str s)
             ". Rename the attribute key to a letter-led name of "
             "letters/digits/underscore/colon/hyphen.")
        {:recovery :rename-the-attribute-key
         :extra    {:attribute k}}))))

;; ---- tag names -------------------------------------------------------------
;;
;; Tag-name injection gate. HTML5 / SVG / MathML element names require an
;; ASCII letter start, then letters / digits / hyphens; anything else is
;; refused.
;;
;; Fail fast (throw) rather than escape-and-emit. A tag-name
;; outside the grammar has no safe wire interpretation — escaping would
;; produce `<img&#x20;src=...>` which no browser parses as a tag, just a
;; visible glyph. The hiccup emitter (`re-frame.ssr.emit`) gates every head
;; it emits through `validate-tag-name!`.
(def ^:private tag-name-re
  ;; HTML5 §element-name + SVG element-name + MathML element-name all
  ;; share the same conservative ASCII grammar: leading letter, then
  ;; letters / digits / hyphens. Custom elements (per HTML5 §custom-
  ;; element-name) require an ASCII-lower first letter + a `-`; the
  ;; conservative grammar admits both standard elements and well-formed
  ;; custom-element names.
  ;;
  ;; XML-namespaced SVG/MathML tags carry a single colon-
  ;; separated prefix (e.g. `:svg:rect`, `:xlink:href`-style elements).
  ;; Admit one optional `prefix:` segment where the prefix follows the
  ;; same element-name grammar. A single colon only — embedded `<`, `>`,
  ;; whitespace, `=` (the tag-injection vectors) remain rejected, and a
  ;; bare/leading/trailing/double colon (`:rect`, `svg:`, `a::b`) still
  ;; throws because each segment must be a well-formed element name.
  #"(?:[A-Za-z][A-Za-z0-9-]*:)?[A-Za-z][A-Za-z0-9-]*")

(defn validate-tag-name!
  "Throw `:rf.error/invalid-tag-name` if `tag-name` does not match the
  HTML5 / SVG / MathML element-name grammar (`[A-Za-z][A-Za-z0-9-]*`,
  optionally prefixed by a single XML namespace segment `prefix:`);
  otherwise return it. `source` is the value the name was read from,
  `origin` names what that value is (`\"hiccup head\"`), and `where`
  names the emitter refusing it."
  [tag-name source origin where]
  (when-not (and (string? tag-name)
                 (re-matches tag-name-re tag-name))
    (rf.error/throw-error!
      :rf.error/invalid-tag-name
      where
      (str "tag-name " (pr-str tag-name)
           " (from " origin " " (pr-str source) ")"
           " does not match the HTML5/SVG/MathML"
           " element-name grammar"
           " ([A-Za-z][A-Za-z0-9-]*, optionally"
           " namespaced prefix:local) — DOM tag-name"
           " injection forbidden. Use a grammar-valid element name.")
      {:recovery :use-a-valid-element-name
       :extra    {:tag-name tag-name
                  :source   source}}))
  tag-name)

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

;; REACT'S TWO STRUCTURAL SLOTS. `key` and `ref` are read off a
;; props map by React itself (the JSX transform / `createElement`) and are
;; never handed to the host as DOM attributes: `key` is reconciliation
;; identity and `ref` is an instance handle, and neither has an HTML wire
;; representation. Passing both straight through would serve
;; `<div key="k">` for `[:div {:key "k"}]` and `<div ref="R">` for a string
;; `[:div {:ref "R"}]`.
;;
;; A function-valued `:ref` is dropped by the `fn?` arm below in any case,
;; which is what keeps this narrow: the REACHABLE case is `:key`, and `:key`
;; on a native tag is ordinary application markup rather than a curiosity —
;; the same `[:div {:key i}]`-inside-a-`for` idiom as the keyed fragment one
;; slot over.
;;
;; WHY DROPPING IS RIGHT RATHER THAN MERELY TIDY. The client paints no such
;; attribute, so emitting one server-side is precisely a hydration
;; divergence — the emitter's whole contract here is agreeing with
;; react-dom/server, and react-dom/server emits neither. A custom element
;; genuinely wanting a `key` attribute cannot get one out of React either,
;; so refusing it costs nothing that is reachable on the client.
;;
;; MATCHED CASE-SENSITIVELY, unlike the event-handler and prototype-pollution
;; rosters above. React extracts these two slots by exact JS property name,
;; so `:Key` really does reach the host as an ordinary unknown prop; matching
;; loosely here would make the SERVER strip a name the client paints, which
;; is the divergence this entry exists to prevent, pointed the other way.
(def ^:private structural-slot-names
  #{"key" "ref"})

;; REACT'S TWO CONTENT CHANNELS. A SEPARATE ROSTER FROM THE
;; STRUCTURAL SLOTS ABOVE, DELIBERATELY. `key` and `ref` are IDENTITY —
;; reconciliation identity and an instance handle, neither of which has
;; anything to render. `children` and `dangerouslySetInnerHTML` are the
;; two channels React takes an element's CONTENT from, so they fail the
;; same way on the wire and for a different reason, and folding them into
;; one roster entry for tidiness would lose that reason. React consumes
;; all four before the host sees them; only these two are about what the
;; element CONTAINS.
;;
;; Without this entry the attribute stream would serve:
;;
;;   [:div {:children "v"}]        => <div children="v"></div>
;;   [:div {:dangerouslySetInnerHTML {:__html "<b>x</b>"}}]
;;                                 => <div dangerouslySetInnerHTML="{:__html
;;                                    &quot;<b>x</b>&quot;}"></div>
;;   {:meta [{:children "v" :name "a"}]}  => <meta children="v" name="a">
;;
;; WHAT THE CLIENT DOES. The client adapters pass both straight through
;; to React, so both reach `createElement`, and React then renders
;; `props.children` as the element's children and
;; `dangerouslySetInnerHTML.__html` as its raw body — painting NEITHER as
;; an attribute.
;;
;; SO THE ATTRIBUTE STREAM DROPS BOTH, AND THE CONTENT IS THE CHILDREN
;; PATH'S BUSINESS. The hiccup body walker (`emit/emit-element`) renders
;; `dangerouslySetInnerHTML`'s `__html` as
;; the element's raw body (`emit/dom-element-props`), reading it under the
;; same exact name this roster drops, so the prop that leaves the attributes
;; is the prop rendered as content. `children` renders nothing: an
;; element's variadic children are its content, which is React's own
;; precedence rule. On the other surfaces this roster serves there is no
;; body to honour either prop in — `<meta>` / `<link>` are void, and the
;; Ring host shell's `:html-attrs` / `:body-attrs` are attribute bags — so
;; there dropping is the whole of it.
;;
;; NOT A THROW, THOUGH 004B SAYS "REFUSED" FOR THE TREE-SPACE GRAMMAR.
;; That refusal is a compile/walk-time gate in a grammar that HAS a
;; trusted-markup node; this fn's contract is narrower and says so above —
;; it recognises "exactly the props that are *safe to silently drop*",
;; and malformed input is `validate-attr-name!`'s job. A throw here would
;; also take the Ring host shell down on a config typo in an attribute
;; bag, and would diverge from react-dom/server harder than either drop
;; or honour: react-dom throws for neither.
;;
;; MATCHED CASE-SENSITIVELY, for the same reason as the structural slots:
;; React extracts both by exact JS property name, so `:Children` and the
;; all-lowercase `:dangerouslysetinnerhtml` really do reach the host as
;; ordinary unknown props that the client paints — stripping those would
;; be this same divergence pointed the other way.
(def ^:private content-channel-names
  #{"children" "dangerouslySetInnerHTML"})

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
    - React's two STRUCTURAL SLOTS, `:key` and `:ref` —
      reconciliation identity and an instance handle, both consumed by
      React before the host sees them and neither serialised by
      react-dom/server. Matched case-sensitively; see
      `structural-slot-names`.
    - React's two CONTENT CHANNELS, `:children` and
      `:dangerouslySetInnerHTML` — the two slots React takes
      an element's CONTENT from, likewise consumed before the host sees
      them and likewise serialised by react-dom/server as neither. A
      SEPARATE roster from the structural slots because the reason
      differs: those are identity, these are content. Left in, the
      raw-HTML one would reach the wire as the escaped EDN print of its
      `{:__html …}` map. The hiccup body walker renders its `__html` as
      the element's body instead (`emit/dom-element-props`), so dropping
      it here removes only the attribute. Matched case-sensitively; see
      `content-channel-names`, which carries the full argument.

  Mirrors react-dom/server behaviour. Recognised here are exactly the
  props that are *safe to silently drop*; malformed keys (breakout chars)
  are NOT this fn's concern — they surface at the `validate-attr-name!`
  grammar gate.

  ONE ROSTER, READ BY EVERY SSR SURFACE THAT EMITS AN ATTRIBUTE. This fn
  is called only from `attr-string` below, and `attr-string` is what the
  hiccup body emitter (`emit/emit-element`), the
  head emitter (`head.emit`'s `<meta>` / `<link>` / `<script>`) and the
  Ring host shell (`ring.shell`'s `<html>` / `<body>`) all serialise
  through. That is the whole reason the stripping lives HERE rather than
  as a `dissoc` local to one emitter: a second stripping roster is a
  drift surface, and a local `dissoc` would leave the other emitters
  divergent from it."
  [[k v]]
  (let [nm (name k)]
    (or (event-handler-name? nm)
        (fn? v)
        (contains? reserved-prop-keys (str/lower-case nm))
        (contains? jsx-source-prop-names nm)
        (contains? structural-slot-names nm)
        (contains? content-channel-names nm))))

;; ---- boolean attribute-value classes ---------------------------------------
;;
;; HTML/React attributes do NOT share one boolean rule, so a `false` value
;; cannot mean "omit" everywhere. Spec 004B §Booleans and their neighbours
;; carries the react-dom 19.2.0 table, row-by-row probed; these are its three
;; rosters and the classifier `attr-string` reads. A serialiser that
;; ignored the table would drop `aria-expanded false` / `contentEditable
;; false` entirely — markup asserting the OPPOSITE of what the author wrote,
;; with no hydration-mismatch signal to catch it (011 §What React-native
;; adoption does not catch — attribute-only mismatches).
;;
;; WHAT CHECKS THESE ROSTERS, AND WHY IT CANNOT BE A PARITY TEST. A roster
;; can be internally consistent and still miss names, with every in-repo
;; test green, as long as every test compares re-frame with re-frame.
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
;; THE EVIDENCE CARRIES SIX VALUES PER NAME, NOT TWO. `true` and
;; `false` cannot separate `:presence` from `:overloaded` — the two render
;; identically for both booleans — so a boolean-only fixture would let the
;; classes be merged with every row green, and `attr-string` would then
;; serialise a non-boolean value wrongly for every member of
;; `boolean-attrs`. The four non-booleans
;; `"yes"`, `""`, `0` and `"0"` are what tell the classes apart and pin the
;; JS-truthiness collapse the presence class runs on.

(def boolean-attrs
  "HTML boolean attributes: `true` → presence, `false`/absent → omitted.
  Tracks react-dom 19.3.0 (004B §Booleans and their neighbours); keyed by
  the hyphen-collapsed lowercase author name.

  Pinned against react-dom's measured output, not against a restatement of
  it — see the roster note above and
  `re-frame.ssr-boolean-attr-react-parity-test`."
  #{"allowfullscreen" "async" "autofocus" "autoplay" "checked" "controls"
    "credentialless"
    "default" "defer" "disabled" "disablepictureinpicture"
    "disableremoteplayback" "formnovalidate" "hidden" "inert" "ismap"
    "itemscope" "loop" "multiple" "muted" "nomodule" "novalidate" "open"
    "playsinline" "readonly" "required" "reversed" "scoped" "seamless"
    "selected"})

(def booleanish-attrs
  "`true`/`false` → `\"true\"`/`\"false\"`, never omitted. Tracks react-dom
  19.3.0.

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
  Tracks react-dom 19.3.0."
  #{"download" "capture"})

(defn presence-value-truthy?
  "Would react-dom treat this value on a `:presence` attribute as present?

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
  the same bytes for the same tree.
  Beyond strings and numbers everything is truthy — keywords, collections,
  objects — matching JS, where every object is truthy.

  `NaN` IS JS-FALSY, AND `NaN?` IS THE CHECK — NOT the self-inequality idiom
  `(not= v v)`, which is DEAD CODE on the JVM. `clojure.lang.Util/equiv` short-circuits on reference identity
  before it ever compares numerically, and a predicate parameter is ONE boxed
  object handed to both argument positions, so `(= v v)` answers `true` for
  every value a caller can supply — `NaN` included — and `(not= v v)` is
  therefore `false` for all of them. Nothing errors and no branch is skipped;
  the NaN arm simply never runs. What makes the idiom so convincing is that it
  is genuinely correct in CLJS, where `=` on numbers reaches `identical?` and
  `NaN === NaN` is false: it is right on one runtime and inert on the other,
  and the JVM is the runtime that renders. `NaN?` is `clojure.core`'s since
  1.11 and `cljs.core`'s, it is correct for a boxed argument on both, and it
  says what it tests. (`==` also discriminates `NaN` correctly on both hosts —
  `Numbers/equiv` takes no identity shortcut — but `(== v v)` has to be
  decoded before it reads as a NaN test, and this predicate exists to be
  read.)

  TOTAL over every value an attribute map can carry, `nil` and booleans
  included, rather than documented as undefined for them. `attr-string` does
  drop `nil` and dispatch booleans before it asks, but a predicate whose
  contract said \"never called with `nil`\" would answer `true` for it in any
  caller that does not, and emit an attribute react-dom omits. `null` is falsy
  in JS, so `nil` is falsy here."
  [v]
  (cond
    (nil? v)     false
    (boolean? v) v
    (string? v)  (not= "" v)
    (number? v)  (not (or (zero? v) (NaN? v)))
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

  `:presence` AND `:overloaded` ARE SEPARATE CLASSES although merging them
  would be invisible for as long as only BOOLEAN values consulted this fn —
  the two rules are identical for `true` and for `false`, and 004B's own
  tables state them with the same two words. They part on the third case,
  which is where `download=\"report.pdf\"` has to survive and
  `disabled=\"yes\"` has to collapse, so a classifier that cannot tell them
  apart cannot serialise a non-boolean value correctly for either. The split
  is derived from react-dom's measured bytes for a non-boolean value, in
  `re-frame.ssr-boolean-attr-react-parity-test`, exactly as the other three
  classes are.

  TWO NAMES ARE DELIBERATELY NOT WHAT REACT DOES, and both are recorded
  rather than left to be rediscovered:

    `value`  — react-dom stringifies a BOOLEAN `value` (`value=\"true\"`)
               because `value` shares the code branch with the booleanish
               names, not because it is one. Here it is `:ordinary`: a
               boolean `value` on a form control is an author error, not a
               state, and `value` is a 004B form-control special form whose
               meaning differs per element (`<input>` attribute,
               `<textarea>` text child, `<select>` → `selected` on an
               option). Classifying it in this shared roster would change
               that special form's handling from here, which is 004B's call
               and not this ns's.
    `ismap`  — `:presence` per 004B §Booleans and their neighbours,
               which names it explicitly and carries the
               divergence and its reason in the row itself. react-dom 19.2.0
               accepts NO boolean `ismap` in any spelling — the name is absent
               from its `possibleStandardNames` altogether, so a boolean warns
               \"Received `true` for a non-boolean attribute\" and emits
               nothing — which is why it can never appear in the evidence
               fixture. `ismap` IS a real HTML boolean attribute on `<img>`,
               so presence is the HTML-correct rendering, and 004B states
               that the grammar tracks HTML where the two part on a genuine
               HTML boolean attribute.

  Names are matched hyphen-collapsed and lowercased, so `:content-editable`
  and `:contentEditable` classify alike. Only the CLASS is decided here — the
  emitted attribute NAME is each caller's business (the hiccup body
  walker converts author names the way the hydrating Reagent-tier adapter
  does before calling `attr-string`; the head emitter and the
  Ring host shell pass theirs verbatim)."
  [attribute-name]
  (let [collapsed (str/lower-case (str/replace attribute-name "-" ""))]
    (cond
      (str/starts-with? attribute-name "aria-")        :stringify
      (str/starts-with? attribute-name "data-")        :stringify
      (contains? booleanish-attrs collapsed)           :stringify
      (contains? overloaded-boolean-attrs collapsed)   :overloaded
      (contains? boolean-attrs collapsed)              :presence
      :else                                            :ordinary)))

;; ---- React prop name → DOM attribute name ----------------------------------
;;
;; The hiccup emitter converts an author key to the React prop name the
;; hydrating Reagent-tier adapter hands React (`re-frame.ssr.emit`'s
;; `dom-attr-key`), then looks that prop name up in `dom-attr-aliases` for
;; the attribute react-dom/server writes — the serialisation half of Spec
;; 004B §Attribute names.

(def ^:private standard-names
  "react-dom 19.3.0 `possibleStandardNames`, reduced to non-identity
  entries and keyed by both the kebab and the hyphen-collapsed lowercase
  form — the author-name → React-prop-name half of the conversion table,
  kept whole so it is re-derived against a react-dom release as one table.
  `dom-attr-aliases` inverts its hyphenated entries."
  {"accent-height" "accentHeight"
   "accentheight" "accentHeight"
   "accept-charset" "acceptCharset"
   "acceptcharset" "acceptCharset"
   "accesskey" "accessKey"
   "alignment-baseline" "alignmentBaseline"
   "alignmentbaseline" "alignmentBaseline"
   "allowfullscreen" "allowFullScreen"
   "allowreorder" "allowReorder"
   "arabic-form" "arabicForm"
   "arabicform" "arabicForm"
   "attributename" "attributeName"
   "attributetype" "attributeType"
   "autocapitalize" "autoCapitalize"
   "autocomplete" "autoComplete"
   "autocorrect" "autoCorrect"
   "autofocus" "autoFocus"
   "autoplay" "autoPlay"
   "autoreverse" "autoReverse"
   "autosave" "autoSave"
   "basefrequency" "baseFrequency"
   "baseline-shift" "baselineShift"
   "baselineshift" "baselineShift"
   "baseprofile" "baseProfile"
   "calcmode" "calcMode"
   "cap-height" "capHeight"
   "capheight" "capHeight"
   "cellpadding" "cellPadding"
   "cellspacing" "cellSpacing"
   "charset" "charSet"
   "class" "className"
   "classid" "classID"
   "clip-path" "clipPath"
   "clip-rule" "clipRule"
   "clippath" "clipPath"
   "clippathunits" "clipPathUnits"
   "cliprule" "clipRule"
   "color-interpolation" "colorInterpolation"
   "color-interpolation-filters" "colorInterpolationFilters"
   "color-profile" "colorProfile"
   "color-rendering" "colorRendering"
   "colorinterpolation" "colorInterpolation"
   "colorinterpolationfilters" "colorInterpolationFilters"
   "colorprofile" "colorProfile"
   "colorrendering" "colorRendering"
   "colspan" "colSpan"
   "contenteditable" "contentEditable"
   "contentscripttype" "contentScriptType"
   "contentstyletype" "contentStyleType"
   "contextmenu" "contextMenu"
   "controlslist" "controlsList"
   "crossorigin" "crossOrigin"
   "dangerouslysetinnerhtml" "dangerouslySetInnerHTML"
   "datetime" "dateTime"
   "defaultchecked" "defaultChecked"
   "defaultvalue" "defaultValue"
   "diffuseconstant" "diffuseConstant"
   "disablepictureinpicture" "disablePictureInPicture"
   "disableremoteplayback" "disableRemotePlayback"
   "dominant-baseline" "dominantBaseline"
   "dominantbaseline" "dominantBaseline"
   "edgemode" "edgeMode"
   "enable-background" "enableBackground"
   "enablebackground" "enableBackground"
   "enctype" "encType"
   "enterkeyhint" "enterKeyHint"
   "externalresourcesrequired" "externalResourcesRequired"
   "fetchpriority" "fetchPriority"
   "fill-opacity" "fillOpacity"
   "fill-rule" "fillRule"
   "fillopacity" "fillOpacity"
   "fillrule" "fillRule"
   "filterres" "filterRes"
   "filterunits" "filterUnits"
   "flood-color" "floodColor"
   "flood-opacity" "floodOpacity"
   "floodcolor" "floodColor"
   "floodopacity" "floodOpacity"
   "font-family" "fontFamily"
   "font-size" "fontSize"
   "font-size-adjust" "fontSizeAdjust"
   "font-stretch" "fontStretch"
   "font-style" "fontStyle"
   "font-variant" "fontVariant"
   "font-weight" "fontWeight"
   "fontfamily" "fontFamily"
   "fontsize" "fontSize"
   "fontsizeadjust" "fontSizeAdjust"
   "fontstretch" "fontStretch"
   "fontstyle" "fontStyle"
   "fontvariant" "fontVariant"
   "fontweight" "fontWeight"
   "for" "htmlFor"
   "formaction" "formAction"
   "formenctype" "formEncType"
   "formmethod" "formMethod"
   "formnovalidate" "formNoValidate"
   "formtarget" "formTarget"
   "frameborder" "frameBorder"
   "glyph-name" "glyphName"
   "glyph-orientation-horizontal" "glyphOrientationHorizontal"
   "glyph-orientation-vertical" "glyphOrientationVertical"
   "glyphname" "glyphName"
   "glyphorientationhorizontal" "glyphOrientationHorizontal"
   "glyphorientationvertical" "glyphOrientationVertical"
   "glyphref" "glyphRef"
   "gradienttransform" "gradientTransform"
   "gradientunits" "gradientUnits"
   "horiz-adv-x" "horizAdvX"
   "horiz-origin-x" "horizOriginX"
   "horizadvx" "horizAdvX"
   "horizoriginx" "horizOriginX"
   "hreflang" "hrefLang"
   "http-equiv" "httpEquiv"
   "httpequiv" "httpEquiv"
   "image-rendering" "imageRendering"
   "imagerendering" "imageRendering"
   "imagesizes" "imageSizes"
   "imagesrcset" "imageSrcSet"
   "inputmode" "inputMode"
   "itemid" "itemID"
   "itemprop" "itemProp"
   "itemref" "itemRef"
   "itemscope" "itemScope"
   "itemtype" "itemType"
   "kernelmatrix" "kernelMatrix"
   "kernelunitlength" "kernelUnitLength"
   "keyparams" "keyParams"
   "keypoints" "keyPoints"
   "keysplines" "keySplines"
   "keytimes" "keyTimes"
   "keytype" "keyType"
   "lengthadjust" "lengthAdjust"
   "letter-spacing" "letterSpacing"
   "letterspacing" "letterSpacing"
   "lighting-color" "lightingColor"
   "lightingcolor" "lightingColor"
   "limitingconeangle" "limitingConeAngle"
   "marginheight" "marginHeight"
   "marginwidth" "marginWidth"
   "marker-end" "markerEnd"
   "marker-mid" "markerMid"
   "marker-start" "markerStart"
   "markerend" "markerEnd"
   "markerheight" "markerHeight"
   "markermid" "markerMid"
   "markerstart" "markerStart"
   "markerunits" "markerUnits"
   "markerwidth" "markerWidth"
   "maskcontentunits" "maskContentUnits"
   "masktype" "maskType"
   "maskunits" "maskUnits"
   "maxlength" "maxLength"
   "mediagroup" "mediaGroup"
   "minlength" "minLength"
   "nomodule" "noModule"
   "novalidate" "noValidate"
   "numoctaves" "numOctaves"
   "overline-position" "overlinePosition"
   "overline-thickness" "overlineThickness"
   "overlineposition" "overlinePosition"
   "overlinethickness" "overlineThickness"
   "paint-order" "paintOrder"
   "paintorder" "paintOrder"
   "panose-1" "panose1"
   "pathlength" "pathLength"
   "patterncontentunits" "patternContentUnits"
   "patterntransform" "patternTransform"
   "patternunits" "patternUnits"
   "playsinline" "playsInline"
   "pointer-events" "pointerEvents"
   "pointerevents" "pointerEvents"
   "pointsatx" "pointsAtX"
   "pointsaty" "pointsAtY"
   "pointsatz" "pointsAtZ"
   "popovertarget" "popoverTarget"
   "popovertargetaction" "popoverTargetAction"
   "preservealpha" "preserveAlpha"
   "preserveaspectratio" "preserveAspectRatio"
   "primitiveunits" "primitiveUnits"
   "radiogroup" "radioGroup"
   "readonly" "readOnly"
   "referrerpolicy" "referrerPolicy"
   "refx" "refX"
   "refy" "refY"
   "rendering-intent" "renderingIntent"
   "renderingintent" "renderingIntent"
   "repeatcount" "repeatCount"
   "repeatdur" "repeatDur"
   "requiredextensions" "requiredExtensions"
   "requiredfeatures" "requiredFeatures"
   "rowspan" "rowSpan"
   "shape-rendering" "shapeRendering"
   "shaperendering" "shapeRendering"
   "specularconstant" "specularConstant"
   "specularexponent" "specularExponent"
   "spellcheck" "spellCheck"
   "spreadmethod" "spreadMethod"
   "srcdoc" "srcDoc"
   "srclang" "srcLang"
   "srcset" "srcSet"
   "startoffset" "startOffset"
   "stddeviation" "stdDeviation"
   "stitchtiles" "stitchTiles"
   "stop-color" "stopColor"
   "stop-opacity" "stopOpacity"
   "stopcolor" "stopColor"
   "stopopacity" "stopOpacity"
   "strikethrough-position" "strikethroughPosition"
   "strikethrough-thickness" "strikethroughThickness"
   "strikethroughposition" "strikethroughPosition"
   "strikethroughthickness" "strikethroughThickness"
   "stroke-dasharray" "strokeDasharray"
   "stroke-dashoffset" "strokeDashoffset"
   "stroke-linecap" "strokeLinecap"
   "stroke-linejoin" "strokeLinejoin"
   "stroke-miterlimit" "strokeMiterlimit"
   "stroke-opacity" "strokeOpacity"
   "stroke-width" "strokeWidth"
   "strokedasharray" "strokeDasharray"
   "strokedashoffset" "strokeDashoffset"
   "strokelinecap" "strokeLinecap"
   "strokelinejoin" "strokeLinejoin"
   "strokemiterlimit" "strokeMiterlimit"
   "strokeopacity" "strokeOpacity"
   "strokewidth" "strokeWidth"
   "suppresscontenteditablewarning" "suppressContentEditableWarning"
   "suppresshydrationwarning" "suppressHydrationWarning"
   "surfacescale" "surfaceScale"
   "systemlanguage" "systemLanguage"
   "tabindex" "tabIndex"
   "tablevalues" "tableValues"
   "targetx" "targetX"
   "targety" "targetY"
   "text-anchor" "textAnchor"
   "text-decoration" "textDecoration"
   "text-rendering" "textRendering"
   "textanchor" "textAnchor"
   "textdecoration" "textDecoration"
   "textlength" "textLength"
   "textrendering" "textRendering"
   "transform-origin" "transformOrigin"
   "transformorigin" "transformOrigin"
   "underline-position" "underlinePosition"
   "underline-thickness" "underlineThickness"
   "underlineposition" "underlinePosition"
   "underlinethickness" "underlineThickness"
   "unicode-bidi" "unicodeBidi"
   "unicode-range" "unicodeRange"
   "unicodebidi" "unicodeBidi"
   "unicoderange" "unicodeRange"
   "units-per-em" "unitsPerEm"
   "unitsperem" "unitsPerEm"
   "usemap" "useMap"
   "v-alphabetic" "vAlphabetic"
   "v-hanging" "vHanging"
   "v-ideographic" "vIdeographic"
   "v-mathematical" "vMathematical"
   "valphabetic" "vAlphabetic"
   "vector-effect" "vectorEffect"
   "vectoreffect" "vectorEffect"
   "vert-adv-y" "vertAdvY"
   "vert-origin-x" "vertOriginX"
   "vert-origin-y" "vertOriginY"
   "vertadvy" "vertAdvY"
   "vertoriginx" "vertOriginX"
   "vertoriginy" "vertOriginY"
   "vhanging" "vHanging"
   "videographic" "vIdeographic"
   "viewbox" "viewBox"
   "viewtarget" "viewTarget"
   "vmathematical" "vMathematical"
   "word-spacing" "wordSpacing"
   "wordspacing" "wordSpacing"
   "writing-mode" "writingMode"
   "writingmode" "writingMode"
   "x-height" "xHeight"
   "xchannelselector" "xChannelSelector"
   "xheight" "xHeight"
   "xlinkactuate" "xlinkActuate"
   "xlinkarcrole" "xlinkArcrole"
   "xlinkhref" "xlinkHref"
   "xlinkrole" "xlinkRole"
   "xlinkshow" "xlinkShow"
   "xlinktitle" "xlinkTitle"
   "xlinktype" "xlinkType"
   "xmlbase" "xmlBase"
   "xmllang" "xmlLang"
   "xmlnsxlink" "xmlnsXlink"
   "xmlspace" "xmlSpace"
   "ychannelselector" "yChannelSelector"
   "zoomandpan" "zoomAndPan"})

(def dom-attr-aliases
  "React prop name -> the DOM attribute name react-dom/server writes when
  the two differ — the serialiser half of the conversion table. Transcribed
  from react-dom 19.2.0, with `maskType` carrying its 19.3.0 spelling — the
  one emitted-name move measured across the two versions. The table as a
  whole is not re-derived against 19.3.0; `standard-names` above, the OTHER
  half — author name to React prop name — is.
  Everything NOT in this map serialises as the prop name verbatim
  (react-dom 19.2.0 emits e.g. `readOnly` verbatim — HTML attribute names
  are ASCII case-insensitive).

  The `keep` below inverts only `standard-names` entries whose KEY carries a
  hyphen, which is why the `masktype -> maskType` entry in `standard-names`
  contributes nothing here: the hyphen-collapsed key is not a kebab spelling.
  `maskType` therefore needs an explicit row, and `react_dom_probe/
  attr_name_mask_family.cjs` + `re-frame.ssr-attr-name-react-parity-test`
  pin that row against the installed package rather than against this prose.

  The inversion is not quite react-dom's rule, and one row shows it:
  react-dom 19.3.0 keys its `panose-1` alias on the HYPHENATED name (an
  identity row) and has no `panose1` key, so it writes the prop `panose1`
  verbatim. The `keep` therefore skips `panose-1`. The other 89 rows are
  byte-identical against 19.3.0 by a one-off probe; no test pins the whole
  table."
  (merge (into {}
               (keep (fn [[attribute-key attribute-value]]
                       (when (and (str/includes? attribute-key "-")
                                  (not= "panose-1" attribute-key))
                         [attribute-value attribute-key])))
               standard-names)
         {"className"    "class"
          "htmlFor"      "for"
          "maskType"     "mask-type"
          "tabIndex"     "tabindex"
          "xlinkActuate" "xlink:actuate"
          "xlinkArcrole" "xlink:arcrole"
          "xlinkHref"    "xlink:href"
          "xlinkRole"    "xlink:role"
          "xlinkShow"    "xlink:show"
          "xlinkTitle"   "xlink:title"
          "xlinkType"    "xlink:type"
          "xmlBase"      "xml:base"
          "xmlLang"      "xml:lang"
          "xmlSpace"     "xml:space"
          "xmlnsXlink"   "xmlns:xlink"}))

;; ---- inline-style map serialisation ---------------------------------------
;;
;; A hiccup `:style` whose value is a MAP must serialise to a CSS declaration
;; string (`{:margin "0 1em"}` → `margin:0 1em`), NOT the EDN print of the map
;; (`{:margin &quot;0 1em&quot;}`). The server HTML must match what the client
;; React path emits from the same style object, or React 19 logs a hydration
;; attribute mismatch on first load.
;;
;; The rules mirror `react-dom/server`'s `pushStyleAttribute`.

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

;; React 19.2.0's `unitlessNumber` set, mirrored verbatim (camelCase keys),
;; then reprojected
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
  with the client React render:

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
                              (let [n (rf.ssr.hash/canonical-number v)]
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
  (`boolean-attr-class`): `aria-*` / `data-*` /
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
  `:rf.error/ssr-invalid-attribute-name`. This closes the XSS
  vector where an app splats an attacker-controlled `:custom-attrs`
  map into hiccup; an attacker who chooses a key like
  `\"onclick=alert(1) data-x\"` would otherwise inject an event-handler
  attribute by escaping the attribute-name context. Same gate covers
  the `:html-attrs` / `:body-attrs` flow through the host shell.

  Props matching `strip-prop?` — `on*` event-handler props, function-
  valued props, reserved prototype-pollution keys, JSX source-coord
  props, and React's structural slots (`:key` / `:ref`) and content
  channels (`:children` / `:dangerouslySetInnerHTML`) — are dropped at
  emit time per Spec 011 §XSS at output boundaries (the per-attribute
  prop-name filter position in the locked emitter composition order). The
  filter
  runs ahead of the attribute-key grammar gate so a stripped prop never
  reaches `validate-attr-name!`.

  THAT LIST IS A GLOSS, NOT A SECOND ROSTER. `strip-prop?` above is the
  single realisation point every SSR surface serialises through, and its
  docstring carries each class's reason and matching rule; this one names
  the classes only so a caller can see what `attr-string` will silently
  discard without scrolling back. It is downstream of `strip-prop?`,
  which is itself downstream of Spec 011's enumeration — that list is the
  contract, and what an other-language port implements (011 §XSS at
  output boundaries says so in terms). The three of them standing apart
  is a defect in whichever moved without the others, and a gloss is the
  easiest of the three to leave behind: a new class can land hundreds of
  lines above it in this same file."
  [attrs]
  ;; `keep` realises only the surviving attributes; the leading space is
  ;; added once, conditionally. A map that is non-empty but whose every
  ;; entry is stripped/omitted (e.g. `{:on-click f}`) must yield `\"\"`,
  ;; not a stray `\" \"` — so we branch on the rendered seq, not the input
  ;; map's emptiness.
  (let [rendered (keep (fn [[k v :as kv]]
                         (cond
                           (strip-prop? kv) nil
                           ;; A BOOLEAN value's rendering depends on
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

                           ;; A PRESENCE attribute collapses a
                           ;; non-boolean value too, on react-dom's JS
                           ;; truthiness. Asking the class BEFORE the value is
                           ;; what makes that work: without this arm every
                           ;; non-boolean would fall through to the ordinary
                           ;; stringify below and emit `disabled="yes"`
                           ;; where react-dom emits `disabled=""` — a DOM-level
                           ;; hydration divergence, and one the structural-tree
                           ;; serialiser next door does not have.
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
                                   ;; HTML agrees with the client React render.
                                   ;; A string `:style` value is
                                   ;; already CSS and rides the default branch
                                   ;; verbatim.
                                   (and (map? v) (= "style" (name k)))
                                   (style-map->css v)
                                   ;; A numeric attribute VALUE is canonicalised
                                   ;; cross-runtime (`canonical-number`) so a
                                   ;; whole-valued double renders `value="0"`
                                   ;; (not the JVM `value="0.0"`), as the client
                                   ;; does. Without this the server/client
                                   ;; attribute strings would diverge — an
                                   ;; attribute-only divergence React neither
                                   ;; patches nor reports.
                                   (number? v) (rf.ssr.hash/canonical-number v)
                                   :else       v)]
                             (str (validate-attr-name! k)
                                  "=\"" (escape-attr rendered-val) "\""))))
                       attrs)]
    (if (seq rendered)
      (str " " (str/join " " rendered))
      "")))
