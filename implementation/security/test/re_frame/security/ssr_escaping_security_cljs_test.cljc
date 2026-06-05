(ns re-frame.security.ssr-escaping-security-cljs-test
  "Adversarial-property security tier - SSR static-markup escaping
  boundary (rf2-3cfvt, surface 1; the rf2-1uex4 class).

  ## The boundary

  `re-frame.ssr.html-helpers/attr-string` is the single emit seam that
  serialises a hiccup attribute map to wire HTML. Two MUSTs protect it:

    1. **Event-handler props never reach the wire.** Any `on*` event-
       handler attribute - across EVERY casing a case-insensitive HTML
       parser fires (`onclick` / `ONCLICK` / `OnClick` / `onLoad` /
       `on-click`) - MUST be stripped (`strip-prop?`  drop), never
       serialised as a live `onX=` attribute. rf2-1uex4 was exactly
       this: the matcher missed lowercase/uppercase canonical spellings,
       so `:onclick` rode through as a live handler.

    2. **Grammar-breakout attribute KEYS are rejected.** A key carrying
       `=`, quotes, whitespace, `<`, `>`, or `/` (the chars that escape
       attribute-name context to inject a sibling `onclick=` attribute)
       MUST throw `:rf.error/ssr-invalid-attribute-name` at
       `validate-attr-name!` - never silently serialise.

  ## Why property-style

  The pin-and-assert regime (rf2-ynjts) tested a fixed handful of casings
  and MISSED the lowercase-canonical leak. This tier sweeps the casing
  space generatively: a generator mints handler names by independently
  case-folding each character of a canonical WHATWG handler name (plus
  camelCase/kebab structural spellings), so hundreds of distinct casings
  per run probe the matcher. A curated hostile corpus pins the named
  payloads (the rf2-1uex4 lowercase set, grammar-breakout keys, the
  `<script>`-body breakout) exactly.

  ## Net property (verify-by-revert)

  Two INDEPENDENT generative properties pin the two `event-handler-name?`
  matcher arms so a revert of EITHER goes RED (each canonical handler is
  also in the allowlist, so a canonical-casing sweep alone leaves the
  regex vacuously covered — rf2-q0a81.1):

    1. Reverting the `event-handler-allowlist` lower-case lookup makes
       `no-recased-canonical-handler-serialises` go RED — the all-
       lowercase / mixed-lowercase canonical spellings the regex's
       `[A-Z]`/`-` discriminator CANNOT catch leak as live `onX=`
       attributes.
    2. Reverting (or weakening) the camelCase/kebab `event-handler-name-re`
       makes `no-custom-structural-handler-serialises` go RED — the
       NON-allowlist structural handlers (`onCustomEvent` / `on-foo-bar`,
       recased across the `on` prefix) that ONLY the regex catches leak.
       The canonical sweep never exercises this arm because every
       canonical handler is in the allowlist.

  Reverting `validate-attr-name!`'s grammar throw makes the breakout-key
  test go RED. Confirmed by temporary local revert + restore (see PR
  Quality gates)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [re-frame.ssr.html-helpers :as h]
            [re-frame.security.gen :as gen]))

;; ---------------------------------------------------------------------------
;; Canonical WHATWG event-handler names browsers actually fire. A leak of
;; ANY of these as a live attribute is an XSS vector.
;; ---------------------------------------------------------------------------

(def ^:private canonical-handlers
  ["onclick" "onload" "onerror" "onmouseover" "onmouseout" "onsubmit"
   "onfocus" "onblur" "onkeydown" "onkeyup" "onkeypress" "onchange"
   "oninput" "onwheel" "onscroll" "ondrag" "ondrop" "onpaste" "oncopy"
   "oncut" "onbeforeunload" "onunload" "onhashchange" "onpopstate"
   "onmessage" "onanimationstart" "ontransitionend" "onpointerdown"])

(defn- live-handler-attr?
  "True when `rendered` (the output of `attr-string` for ONE attr) contains
  a serialised `on*=\"...\"` event-handler attribute - i.e. the prop was NOT
  stripped. We test against the rendered string the way a browser parser
  would read it: lower-case the run up to the first `=` and check for an
  `on...` attribute name. `attr-string` always emits ` name=\"value\"` with a
  leading space."
  [rendered]
  (let [s (str/triml rendered)]
    (and (str/includes? s "=")
         (let [nm (str/lower-case (subs s 0 (str/index-of s "=")))]
           (str/starts-with? (str/trim nm) "on")))))

;; ---------------------------------------------------------------------------
;; Generator - independently case-fold each character of a canonical handler
;; name, so a single canonical name explodes into 2^len casings. Also fold in
;; the structural camelCase / kebab spellings.
;; ---------------------------------------------------------------------------

(defn- recase
  "Apply a per-character case mask `bits` (vector of 0/1) to `s`."
  [s bits]
  (apply str
         (map-indexed
           (fn [i ch]
             (if (= 1 (nth bits (mod i (count bits))))
               (str/upper-case (str ch))
               (str/lower-case (str ch))))
           s)))

(def ^:private gen-handler-casing
  "Draws a `[k v]` attribute pair whose KEY is a randomly-recased canonical
  event-handler name (a hostile attribute key controlling a handler). The
  value is an attacker JS payload string."
  (gen/gen-fmap
    (fn [[base bits]]
      [(keyword (recase base bits)) "alert(document.cookie)"])
    (fn [rng]
      (let [[base rng1] (gen/rand-nth rng canonical-handlers)
            [bits rng2] ((gen/gen-vec (count base) (gen/gen-elem [0 1])) rng1)]
        [[base bits] rng2]))))

;; ---------------------------------------------------------------------------
;; PROPERTY 1 - no recased canonical handler ever serialises as a live attr.
;; ---------------------------------------------------------------------------

(deftest no-recased-canonical-handler-serialises
  (testing "rf2-1uex4 - across 600 randomly-recased canonical handler names,
            attr-string strips every one (empty output, no live on*= attr)"
    (let [result (gen/for-all
                   gen-handler-casing 600 1
                   (fn [[k v]]
                     (let [out (h/attr-string {k v})]
                       ;; A stripped handler yields "" for a single-entry map.
                       (and (= "" out)
                            (not (live-handler-attr? out))))))]
      (is (nil? result)
          (str "a recased handler leaked as a live attribute: "
               (pr-str result))))))

;; ---------------------------------------------------------------------------
;; PROPERTY 1b - no NON-ALLOWLIST structural handler ever serialises as a live
;; attr. This independently exercises `event-handler-name-re` (rf2-q0a81.1):
;; PROPERTY 1's generator only mints recasings of CANONICAL handlers, and every
;; canonical handler is in `event-handler-allowlist`, whose lookup lower-cases
;; the candidate - so the allowlist arm alone strips all 600 draws there and a
;; revert of the regex leaves PROPERTY 1 GREEN (vacuous w.r.t. the regex). The
;; framework-shaped CUSTOM handlers below (`:onCustomEvent` / `:on-foo-bar`)
;; are NOT in the allowlist; ONLY the camelCase/kebab regex strips them, so
;; reverting/weakening `event-handler-name-re` makes THIS property go RED.
;; ---------------------------------------------------------------------------

(def ^:private structural-tail-letters
  (vec "abcdefghijklmnopqrstuvwxyz"))

(defn- recase-on-prefix
  "Recase ONLY the leading `on` (2 chars) per a 2-bit mask `[b0 b1]`; the rest
  of the name is kept verbatim so the regex's structural discriminator (the
  upper-case tail char for camelCase, the `-` for kebab) survives every draw.
  This sweeps the `[Oo][Nn]` case-insensitivity of the prefix - exactly the
  rf2-1uex4 casing axis - while keeping the name NON-canonical."
  [s [b0 b1]]
  (str (if (= 1 b0) (str/upper-case (subs s 0 1)) (str/lower-case (subs s 0 1)))
       (if (= 1 b1) (str/upper-case (subs s 1 2)) (str/lower-case (subs s 1 2)))
       (subs s 2)))

(def ^:private gen-custom-structural-handler
  "Draws a `[k v]` attribute pair whose KEY is a randomly-recased-prefix
  NON-allowlist structural event handler - either camelCase (`on` + an
  upper-case first tail char + a random lower-case tail, e.g. `onFooba`) or
  kebab (`on-` + a random tail, e.g. `on-fooba`). The random 4-6-letter tail
  makes the lower-cased name impossible to collide with a canonical allowlist
  entry, so the ONLY matcher arm that strips it is `event-handler-name-re`.
  The value is an attacker JS payload string."
  (gen/gen-fmap
    (fn [[form prefix-bits tail]]
      (let [tail-str (apply str tail)
            base     (case form
                       :camel (str "on"
                                   (str/upper-case (subs tail-str 0 1))
                                   (subs tail-str 1))
                       :kebab (str "on-" tail-str))]
        [(keyword (recase-on-prefix base prefix-bits)) "alert(document.cookie)"]))
    (fn [rng]
      (let [[form rng1] (gen/rand-nth rng [:camel :kebab])
            [pb0 rng2]  (gen/rand-nth rng1 [0 1])
            [pb1 rng3]  (gen/rand-nth rng2 [0 1])
            [tail rng4] ((gen/gen-vec (gen/gen-int 4 7)
                                      (gen/gen-elem structural-tail-letters))
                         rng3)]
        [[form [pb0 pb1] tail] rng4]))))

(deftest no-custom-structural-handler-serialises
  (testing "rf2-q0a81.1 - across 600 NON-allowlist structural handler names
            (camelCase / kebab, recased prefix), attr-string strips every one
            via event-handler-name-re alone - the allowlist cannot catch these.
            Reverting/weakening the regex makes this go RED."
    (let [result (gen/for-all
                   gen-custom-structural-handler 600 5
                   (fn [[k v]]
                     (let [out (h/attr-string {k v})]
                       (and (= "" out)
                            (not (live-handler-attr? out))))))]
      (is (nil? result)
          (str "a custom structural handler leaked as a live attribute "
               "(event-handler-name-re regression): " (pr-str result))))))

;; ---------------------------------------------------------------------------
;; HOSTILE CORPUS - the exact rf2-1uex4 casings + structural spellings.
;; Each MUST strip to "".
;; ---------------------------------------------------------------------------

(def ^:private hostile-handler-keys
  ;; The rf2-1uex4 named misses: all-lowercase + ALL-UPPERCASE + Title-case
  ;; canonical handlers, plus the camelCase / kebab structural spellings.
  [:onclick :ONCLICK :OnClick :onClick :oNcLiCk
   :onload :ONLOAD :OnLoad :onLoad
   :onerror :ONERROR :OnError
   :onmouseover :ONMOUSEOVER :OnMouseOver :onMouseOver
   :on-click :ON-CLICK :on-mouse-over :onCustomEvent :on-custom-event
   :onsubmit :ONSUBMIT :onfocus :onFocus :onkeydown :onKeyDown])

(deftest hostile-handler-corpus-all-stripped
  (testing "rf2-1uex4 corpus - every named hostile handler key strips to \"\""
    (doseq [k hostile-handler-keys]
      (let [out (h/attr-string {k "javascript:alert(1)"})]
        (is (= "" out)
            (str "handler key " (pr-str k) " was NOT stripped - leaked: "
                 (pr-str out)))
        (is (not (live-handler-attr? out))
            (str "handler key " (pr-str k) " produced a live on*= attribute"))))))

(deftest non-handler-on-prefix-keys-survive
  (testing "the matcher must NOT over-strip innocuous English-word keys -
            `online`/`once`/`only`/`on` are real attribute names, not handlers"
    (doseq [k [:online :once :only :on :one :ongoing]]
      (let [out (h/attr-string {k "v"})]
        (is (str/includes? out (name k))
            (str "innocuous key " (pr-str k) " was wrongly stripped"))))))

;; ---------------------------------------------------------------------------
;; PROPERTY 2 - grammar-breakout attribute KEYS throw, never serialise.
;; ---------------------------------------------------------------------------

(def ^:private breakout-chars [\= \" \space \< \> \/ \tab \newline \'])

(def ^:private gen-breakout-key
  "Draws a hostile attribute key that splices a breakout char into an
  otherwise-plausible name, attempting to escape attribute-name context to
  inject a sibling `onclick=` attribute."
  (gen/gen-fmap
    (fn [[prefix ch suffix]]
      (str prefix ch suffix))
    (fn [rng]
      (let [[prefix rng1] (gen/rand-nth rng ["data-x" "class" "title" "id" "x"])
            [ch rng2]     (gen/rand-nth rng1 breakout-chars)
            [suffix rng3] (gen/rand-nth rng2 ["onclick=alert(1)" "\"onload=x"
                                               "><script>" "/>" "=\"y\" onmouseover=z"])]
        [[prefix ch suffix] rng3]))))

(defn- throws-invalid-attr-name?
  [attrs]
  (try
    (h/attr-string attrs)
    false
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
      (= :rf.error/ssr-invalid-attribute-name
         (:rf.error/id (ex-data e))))))

(deftest grammar-breakout-keys-throw
  (testing "rf2-vl8ir - across 400 generated grammar-breakout keys,
            attr-string throws :rf.error/ssr-invalid-attribute-name; never
            silently serialises a key that escapes attribute-name context"
    (let [result (gen/for-all
                   gen-breakout-key 400 7
                   (fn [k]
                     ;; The key carries a non-handler prefix so strip-prop?
                     ;; does not pre-empt the grammar gate - the breakout
                     ;; char must trigger the throw. Use the RAW STRING as
                     ;; the attr key (attr-string calls `(name k)`); routing
                     ;; through `(keyword k)` would mangle the breakout `/`
                     ;; into a keyword-namespace separator and hide it.
                     (throws-invalid-attr-name? {k "v"})))]
      (is (nil? result)
          (str "a grammar-breakout key did NOT throw (would serialise): "
               (pr-str result))))))

(deftest hostile-breakout-key-corpus
  (testing "named breakout keys throw the grammar error"
    (doseq [k ["onclick=alert(1) data-x"
               "x=\"\" onload=alert(1) y"
               "title><script>"
               "id=x onmouseover=y"
               "class=\"a\" onclick"]]
      (is (throws-invalid-attr-name? {k "v"})
          (str "breakout key " (pr-str k) " did not throw")))))

;; ---------------------------------------------------------------------------
;; PROPERTY 3 - <script>-body breakout: escape-script-body-string must break
;; every casing of the </script closing-tag the HTML tokenizer recognises.
;; ---------------------------------------------------------------------------

(def ^:private gen-script-breakout
  "Draws a hostile string embedding a randomly-recased `</script...>`
  payload that would prematurely close a <script> body."
  (gen/gen-fmap
    (fn [[bits tail]]
      (str "{\"k\":\"" (recase "</script" bits) tail "\"}"))
    (fn [rng]
      (let [[bits rng1] ((gen/gen-vec (count "</script") (gen/gen-elem [0 1])) rng)
            [tail rng2] (gen/rand-nth rng1 [">" " >" "/" "\t" "\n"])]
        [[bits tail] rng2]))))

(deftest script-body-breakout-neutralised
  (testing "rf2-7ksyr/rf2-m5u23 - escape-script-body-string escapes EVERY `<`
            so no `</script` (any casing) survives in a script body; sweep
            500 recased closing-tag payloads"
    (let [result (gen/for-all
                   gen-script-breakout 500 3
                   (fn [payload]
                     (let [escaped (h/escape-script-body-string payload)]
                       ;; No literal `<` may remain - `</script` in any casing
                       ;; is impossible once every `<` becomes <.
                       (and (not (str/includes? escaped "<"))
                            (str/includes? escaped "\\u003c")))))]
      (is (nil? result)
          (str "a script-body `<` survived escaping (closing-tag breakout): "
               (pr-str result))))))

(deftest text-node-escape-neutralises-tag-injection
  (testing "escape-html neutralises every angle bracket + quote so a hostile
            text node cannot open a tag or break an attribute"
    (doseq [payload ["<img src=x onerror=alert(1)>"
                     "</title><script>alert(1)</script>"
                     "\"><svg onload=alert(1)>"
                     "<!--<script>--><script>x</script>"]]
      (let [out (h/escape-html payload)]
        (is (not (str/includes? out "<")) (str "raw < survived: " (pr-str out)))
        (is (not (str/includes? out ">")) (str "raw > survived: " (pr-str out)))
        (is (not (str/includes? out "\"")) (str "raw \" survived: " (pr-str out)))))))

;; ---------------------------------------------------------------------------
;; PROPERTY 4 - EDN script-body breakout: escape-edn-script-body must break
;; every `</script` (any casing) inside an EDN <script type="application/edn">
;; body WITHOUT corrupting the document's readability. The naive whole-string
;; `<`->`<` replacement (escape-script-body-string) is XSS-safe but
;; CORRUPTS the EDN: `<` is only a valid reader escape INSIDE a string
;; literal, so a keyword/symbol token carrying `<` (`:<`, `:a<b`) becomes the
;; unreadable token `:<` / `:a<b` and the EDN reader throws -
;; breaking hydration / silently dropping a streaming delta (rf2-rdxxa).
;;
;; The fix's two MUSTs:
;;   (a) no literal `</script` (any casing) survives in the emitted body;
;;   (b) every VALID EDN payload round-trips through the reader unchanged -
;;       `<` in string literals AND `<` in keyword/symbol tokens that do not
;;       form a `</` / `<!` breakout precursor.
;;   (c) a `</` / `<!` breakout precursor in a TOKEN position (no readable
;;       in-token escape) fails loud with :rf.error/ssr-edn-script-breakout
;;       rather than corrupting the document.
;;
;; verify-by-revert: restoring the callers to escape-script-body-string makes
;; the token round-trip assertions go RED (the reader throws on `:a<b`).
;; ---------------------------------------------------------------------------

(defn- no-script-breakout?
  "True when `s` carries no literal `</script` closing-tag (any casing) -
  the HTML tokenizer's script-data-end pattern."
  [s]
  (not (str/includes? (str/lower-case s) "</script")))

(def ^:private gen-edn-string-breakout
  "Draws a hostile EDN document `{:k \"<recased </script…>>\"}` where the
  STRING VALUE embeds a randomly-recased closing-tag breakout. The breakout
  lives inside an EDN string literal, so the escape must neutralise it AND
  round-trip."
  (gen/gen-fmap
    (fn [[bits tail]]
      (pr-str {:k (str "x" (recase "</script" bits) tail "y")}))
    (fn [rng]
      (let [[bits rng1] ((gen/gen-vec (count "</script") (gen/gen-elem [0 1])) rng)
            [tail rng2] (gen/rand-nth rng1 [">" " >" "/" "\t" "\n"])]
        [[bits tail] rng2]))))

(deftest edn-script-string-breakout-neutralised-and-round-trips
  (testing "rf2-rdxxa - escape-edn-script-body neutralises EVERY recased
            `</script` in an EDN string literal so none survives in the body,
            AND the reader recovers the original document verbatim; sweep 500
            recased closing-tag payloads"
    (let [result (gen/for-all
                   gen-edn-string-breakout 500 11
                   (fn [edn-doc]
                     (let [escaped (h/escape-edn-script-body edn-doc)]
                       (and (no-script-breakout? escaped)
                            ;; round-trips: reading the escaped body yields the
                            ;; same value as reading the original EDN doc.
                            (= (edn/read-string edn-doc)
                               (edn/read-string escaped))))))]
      (is (nil? result)
          (str "an EDN string-literal `</script` breakout survived or the "
               "document failed to round-trip: " (pr-str result))))))

(def ^:private edn-token-with-angle-corpus
  ;; VALID EDN documents carrying `<` in keyword/symbol TOKENS that are NOT
  ;; breakout precursors (no `</` or `<!`). These are exactly what the naive
  ;; whole-string escape corrupted - they MUST round-trip unchanged.
  [{:a<b 1}
   {:k :<}
   {:< :>}
   {:less< "ok"}
   {:k 'sym<tail}
   {:vec [:< :a<b "<starts-with-angle"]}
   {:nested {:deep<key {:and<more "v"}}}
   ;; `<` adjacent to a quote-bearing string value plus a token key
   {:tok<key "value with </script> inside a string"}])

(deftest edn-tokens-with-angle-round-trip
  (testing "rf2-rdxxa - keyword/symbol tokens containing a non-breakout `<`
            round-trip through the EDN reader unchanged (the regression the
            whole-string `<`->`\\u003c` escape broke), and the body carries no
            `</script` breakout"
    (doseq [doc edn-token-with-angle-corpus]
      (let [edn-doc (pr-str doc)
            escaped (h/escape-edn-script-body edn-doc)]
        (is (no-script-breakout? escaped)
            (str "doc " (pr-str doc) " left a </script breakout: " escaped))
        (is (= doc (edn/read-string escaped))
            (str "doc " (pr-str doc) " did NOT round-trip; escaped body: "
                 escaped))))))

(deftest edn-token-breakout-precursor-fails-loud
  (testing "rf2-rdxxa - a `</` or `<!` breakout precursor in a TOKEN position
            has no readable in-token EDN escape, so it fails loud with
            :rf.error/ssr-edn-script-breakout rather than corrupting the doc"
    (doseq [edn-doc ["{:a</script>b 1}"
                     "{:k :</x}"
                     "{:tag<!-- 1}"
                     "{:k </script}"]]
      (let [thrown (try
                     (h/escape-edn-script-body edn-doc)
                     nil
                     (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                       (:rf.error/id (ex-data e))))]
        (is (= :rf.error/ssr-edn-script-breakout thrown)
            (str "token-position breakout precursor in " (pr-str edn-doc)
                 " did not fail loud; got " (pr-str thrown)))))))

(deftest edn-string-with-breakout-precursor-is-escaped-not-rejected
  (testing "rf2-rdxxa - a `</` / `<!` breakout INSIDE a string literal is
            escaped (it has the `\\u003c` reader escape) and round-trips - it
            must NOT trip the token-position fail-loud path"
    (doseq [doc [{:html "<!-- comment --></script>"}
                 {:s "a</b><![CDATA[x]]>"}
                 {:s "</SCRIPT/></script >"}]]
      (let [edn-doc (pr-str doc)
            escaped (h/escape-edn-script-body edn-doc)]
        (is (no-script-breakout? escaped)
            (str "string-literal breakout survived for " (pr-str doc)))
        (is (= doc (edn/read-string escaped))
            (str "string-literal breakout doc did not round-trip: "
                 (pr-str doc)))))))
