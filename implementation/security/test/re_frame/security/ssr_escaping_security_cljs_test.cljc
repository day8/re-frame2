(ns re-frame.security.ssr-escaping-security-cljs-test
  "Adversarial tests for SSR static-markup escaping.

  Event-handler attributes are removed for every HTML-equivalent casing, and
  attribute names containing grammar-breakout characters are rejected. Separate
  generated corpora exercise canonical handlers caught by the allowlist and
  non-canonical camelCase or kebab handlers caught only by the structural
  matcher.

  Script-body tests ensure HTML closing tags are neutralised. EDN script bodies
  additionally preserve reader round trips: string-literal breakouts can be
  escaped, while breakout precursors in token positions must fail loudly."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [re-frame.ssr.html-helpers :as rf.ssr.html-helpers]
            [re-frame.security.gen :as rf.security.gen]))

(def ^:private canonical-handlers
  ["onclick" "onload" "onerror" "onmouseover" "onmouseout" "onsubmit"
   "onfocus" "onblur" "onkeydown" "onkeyup" "onkeypress" "onchange"
   "oninput" "onwheel" "onscroll" "ondrag" "ondrop" "onpaste" "oncopy"
   "oncut" "onbeforeunload" "onunload" "onhashchange" "onpopstate"
   "onmessage" "onanimationstart" "ontransitionend" "onpointerdown"
   ;; Lower-case touch names rely on the allowlist, not the structural matcher.
   "ontouchstart" "ontouchmove" "ontouchend" "ontouchcancel"
   ;; The lower-case oncommand spelling (WHATWG `command` event) likewise
   ;; relies on the allowlist alone.
   "oncommand"])

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
  (rf.security.gen/gen-fmap
    (fn [[base bits]]
      [(keyword (recase base bits)) "alert(document.cookie)"])
    (fn [rng]
      (let [[base rng1] (rf.security.gen/rand-nth rng canonical-handlers)
            [bits rng2] ((rf.security.gen/gen-vec (count base) (rf.security.gen/gen-elem [0 1])) rng1)]
        [[base bits] rng2]))))

(deftest no-recased-canonical-handler-serialises
  ;; A stripped handler yields "" for a single-entry map.
  (let [result (rf.security.gen/for-all
                 gen-handler-casing 600 1
                 (fn [[k v]]
                   (let [out (rf.ssr.html-helpers/attr-string {k v})]
                     (and (= "" out)
                          (not (live-handler-attr? out))))))]
    (is (nil? result)
        (str "a recased handler leaked as a live attribute: "
             (pr-str result)))))

;; Canonical names exercise the allowlist. These generated custom names are not
;; in it, so they independently exercise the camelCase/kebab structural matcher.
(def ^:private structural-tail-letters
  (vec "abcdefghijklmnopqrstuvwxyz"))

(defn- recase-on-prefix
  "Recase ONLY the leading `on` (2 chars) per a 2-bit mask `[b0 b1]`; the rest
  of the name is kept verbatim so the regex's structural discriminator (the
  upper-case tail char for camelCase, the `-` for kebab) survives every draw.
  This sweeps the `[Oo][Nn]` case-insensitivity while keeping the name
  non-canonical."
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
  (rf.security.gen/gen-fmap
    (fn [[form prefix-bits tail]]
      (let [tail-str (apply str tail)
            base     (case form
                       :camel (str "on"
                                   (str/upper-case (subs tail-str 0 1))
                                   (subs tail-str 1))
                       :kebab (str "on-" tail-str))]
        [(keyword (recase-on-prefix base prefix-bits)) "alert(document.cookie)"]))
    (fn [rng]
      (let [[form rng1] (rf.security.gen/rand-nth rng [:camel :kebab])
            [pb0 rng2]  (rf.security.gen/rand-nth rng1 [0 1])
            [pb1 rng3]  (rf.security.gen/rand-nth rng2 [0 1])
            [tail rng4] ((rf.security.gen/gen-vec (rf.security.gen/gen-int 4 7)
                                      (rf.security.gen/gen-elem structural-tail-letters))
                         rng3)]
        [[form [pb0 pb1] tail] rng4]))))

(deftest no-custom-structural-handler-serialises
  (let [result (rf.security.gen/for-all
                 gen-custom-structural-handler 600 5
                 (fn [[k v]]
                   (let [out (rf.ssr.html-helpers/attr-string {k v})]
                     (and (= "" out)
                          (not (live-handler-attr? out))))))]
    (is (nil? result)
        (str "a custom structural handler leaked as a live attribute: "
             (pr-str result)))))

(def ^:private breakout-chars [\= \" \space \< \> \/ \tab \newline \'])

(def ^:private gen-breakout-key
  "Draws a hostile attribute key that splices a breakout char into an
  otherwise-plausible name, attempting to escape attribute-name context to
  inject a sibling `onclick=` attribute."
  (rf.security.gen/gen-fmap
    (fn [[prefix ch suffix]]
      (str prefix ch suffix))
    (fn [rng]
      (let [[prefix rng1] (rf.security.gen/rand-nth rng ["data-x" "class" "title" "id" "x"])
            [ch rng2]     (rf.security.gen/rand-nth rng1 breakout-chars)
            [suffix rng3] (rf.security.gen/rand-nth rng2 ["onclick=alert(1)" "\"onload=x"
                                               "><script>" "/>" "=\"y\" onmouseover=z"])]
        [[prefix ch suffix] rng3]))))

(defn- throws-invalid-attr-name?
  [attrs]
  (try
    (rf.ssr.html-helpers/attr-string attrs)
    false
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
      (= :rf.error/ssr-invalid-attribute-name
         (:rf.error/id (ex-data e))))))

(deftest grammar-breakout-keys-throw
  (let [result (rf.security.gen/for-all
                 gen-breakout-key 400 7
                 ;; A raw string preserves `/`; keyword conversion would
                 ;; reinterpret it as a namespace separator.
                 (fn [k] (throws-invalid-attr-name? {k "v"})))]
    (is (nil? result)
        (str "a grammar-breakout key did NOT throw (would serialise): "
             (pr-str result)))))

(deftest script-body-breakout-neutralised
  ;; Every `<` is escaped, so no closing tag of any casing can begin.
  (is (= "{\"k\":\"\\u003c/ScRiPt >\"}"
         (rf.ssr.html-helpers/escape-script-body-string "{\"k\":\"</ScRiPt >\"}"))))

(deftest text-node-escape-neutralises-tag-injection
  (is (not (re-find #"[<>\"]" (rf.ssr.html-helpers/escape-html "\"><svg onload=alert(1)>")))
      "no raw angle bracket or quote survives to open a tag or break an attribute"))

;; EDN script escaping must preserve reader semantics. A `<` can be escaped
;; inside a string literal, but the same escape is invalid inside a keyword or
;; symbol. Safe token-position `<` characters remain literal; token-position
;; `</` and `<!` precursors fail loudly because EDN has no readable in-token
;; escape for them.

(defn- no-script-breakout?
  "True when `s` carries no literal `</script` closing-tag (any casing) -
  the HTML tokenizer's script-data-end pattern."
  [s]
  (not (str/includes? (str/lower-case s) "</script")))

(deftest edn-script-body-round-trips-with-no-closing-tag
  ;; Token-position `<` that starts no precursor stays literal; a string
  ;; literal's `</` / `<!` (any casing, after an escaped char too) is escaped.
  (doseq [doc [{:a<b 'sym<tail}
               {:tok<key "value with </script> inside a string"}
               {:html "<!-- comment --></script>"}
               {:s "x</ScRiPt\ny"}]]
    (let [escaped (rf.ssr.html-helpers/escape-edn-script-body (pr-str doc))]
      (is (= [true doc] [(no-script-breakout? escaped) (edn/read-string escaped)])
          (str (pr-str doc) " escaped to " escaped)))))

(deftest edn-token-breakout-precursor-fails-loud
  (testing "a `</` or `<!` breakout precursor in a token position
            has no readable in-token EDN escape, so it fails loud with
            :rf.error/ssr-edn-script-breakout rather than corrupting the doc"
    (doseq [edn-doc ["{:a</script>b 1}"
                     "{:k :</x}"
                     "{:tag<!-- 1}"
                     "{:k </script}"]]
      (let [thrown (try
                     (rf.ssr.html-helpers/escape-edn-script-body edn-doc)
                     nil
                     (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                       (:rf.error/id (ex-data e))))]
        (is (= :rf.error/ssr-edn-script-breakout thrown)
            (str "token-position breakout precursor in " (pr-str edn-doc)
                 " did not fail loud; got " (pr-str thrown)))))))
