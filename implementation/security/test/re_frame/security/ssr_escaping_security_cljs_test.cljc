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

  Reverting the `event-handler-allowlist` lower-case lookup (or the
  camelCase/kebab `event-handler-name-re`) in
  `html_helpers.cljc` makes the generated-casing test go RED - a live
  `onX=` attribute appears in `attr-string` output. Reverting
  `validate-attr-name!`'s grammar throw makes the breakout-key test go
  RED. Confirmed by temporary local revert + restore (see PR Quality
  gates)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
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
