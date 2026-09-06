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
            [re-frame.ssr.emit :as rf.ssr.emit]
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
  (testing "across 600 randomly-recased canonical handler names,
            attr-string strips every one (empty output, no live on*= attr)"
    (let [result (rf.security.gen/for-all
                   gen-handler-casing 600 1
                   (fn [[k v]]
                     (let [out (rf.ssr.html-helpers/attr-string {k v})]
                       ;; A stripped handler yields "" for a single-entry map.
                       (and (= "" out)
                            (not (live-handler-attr? out))))))]
      (is (nil? result)
          (str "a recased handler leaked as a live attribute: "
               (pr-str result))))))

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
  (testing "across 600 non-allowlist structural handler names
            (camelCase / kebab, recased prefix), attr-string strips every one
            via event-handler-name-re alone"
    (let [result (rf.security.gen/for-all
                   gen-custom-structural-handler 600 5
                   (fn [[k v]]
                     (let [out (rf.ssr.html-helpers/attr-string {k v})]
                       (and (= "" out)
                            (not (live-handler-attr? out))))))]
      (is (nil? result)
          (str "a custom structural handler leaked as a live attribute: "
               (pr-str result))))))

(def ^:private hostile-handler-keys
  ;; Canonical casings plus camelCase and kebab structural spellings.
  [:onclick :ONCLICK :OnClick :onClick :oNcLiCk
   :onload :ONLOAD :OnLoad :onLoad
   :onerror :ONERROR :OnError
   :onmouseover :ONMOUSEOVER :OnMouseOver :onMouseOver
   :on-click :ON-CLICK :on-mouse-over :onCustomEvent :on-custom-event
   :onsubmit :ONSUBMIT :onfocus :onFocus :onkeydown :onKeyDown
   ;; The structural matcher cannot catch these lower-case touch names.
   :ontouchstart :ontouchmove :ontouchend :ontouchcancel
   :ONTOUCHSTART :OnTouchStart :onTouchStart
   ;; Nor the lower-case oncommand spelling (WHATWG `command` event).
   :oncommand :ONCOMMAND :OnCommand :oNcOmMaNd])

(deftest hostile-handler-corpus-all-stripped
  (testing "every hostile handler key strips to an empty attribute string"
    (doseq [k hostile-handler-keys]
      (let [out (rf.ssr.html-helpers/attr-string {k "javascript:alert(1)"})]
        (is (= "" out)
            (str "handler key " (pr-str k) " was NOT stripped - leaked: "
                 (pr-str out)))
        (is (not (live-handler-attr? out))
            (str "handler key " (pr-str k) " produced a live on*= attribute"))))))

(deftest touch-handler-payload-never-reaches-wire-html
  (testing "lower-case touch handler keys emit no live on* attribute and no
            trace of the JavaScript payload"
    (doseq [k [:ontouchstart :ontouchmove :ontouchend :ontouchcancel]]
      (let [payload "alert(document.cookie)"
            html    (rf.ssr.emit/emit-element [:div {k payload :id "ok"} "child"])]
        (is (str/includes? html "id=\"ok\"")
            (str "the benign attr should survive for key " (pr-str k)))
        (is (str/includes? html "child")
            (str "the child text should survive for key " (pr-str k)))
        (is (not (str/includes? (str/lower-case html) (name k)))
            (str "touch handler name " (pr-str k) " leaked into wire HTML: "
                 html))
        (is (not (str/includes? html payload))
            (str "touch handler JS payload reached wire HTML for key "
                 (pr-str k) ": " html))))))

(deftest oncommand-handler-stripped
  (testing "the standardized oncommand handler (WHATWG `command` event)
            strips in every HTML-equivalent casing - the all-lowercase
            spelling has no upper-case letter and no hyphen, so only the
            allowlist can catch it"
    (let [payload "globalThis.pwned++"]
      (doseq [k [:oncommand :ONCOMMAND :OnCommand :oNcOmMaNd]]
        (let [out (rf.ssr.html-helpers/attr-string {k payload})]
          (is (= "" out)
              (str "handler key " (pr-str k) " was NOT stripped - leaked: "
                   (pr-str out)))
          (is (not (str/includes? (str/lower-case out) "oncommand"))
              (str "handler name oncommand survived for key " (pr-str k)
                   ": " (pr-str out)))
          (is (not (str/includes? out payload))
              (str "JS payload survived for key " (pr-str k) ": "
                   (pr-str out)))))
      ;; Non-vacuity: the SAME payload under a benign key serialises, so the
      ;; empty results above prove handler stripping, not value rejection.
      (is (str/includes? (rf.ssr.html-helpers/attr-string {:data-x payload}) payload)
          "the payload under a benign attribute must serialise"))))

(deftest oncommand-payload-never-reaches-wire-html
  (testing "an emitted element carrying :oncommand renders its benign sibling
            attribute and child text while omitting the handler name and the
            JavaScript payload entirely"
    (let [payload "globalThis.pwned++"
          html    (rf.ssr.emit/emit-element [:div {:oncommand payload :id "ok"}
                                      "child"])]
      (is (str/includes? html "id=\"ok\"")
          "the benign sibling attr should survive")
      (is (str/includes? html "child")
          "the child text should survive")
      (is (not (str/includes? (str/lower-case html) "oncommand"))
          (str "oncommand leaked into wire HTML: " html))
      (is (not (str/includes? html payload))
          (str "oncommand JS payload reached wire HTML: " html)))))

(deftest non-handler-on-prefix-keys-survive
  (testing "the matcher must NOT over-strip innocuous English-word keys -
            `online`/`once`/`only`/`on` are real attribute names, not handlers"
    (doseq [k [:online :once :only :on :one :ongoing]]
      (let [out (rf.ssr.html-helpers/attr-string {k "v"})]
        (is (str/includes? out (name k))
            (str "innocuous key " (pr-str k) " was wrongly stripped"))))))

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
  (testing "across 400 generated grammar-breakout keys,
            attr-string throws :rf.error/ssr-invalid-attribute-name; never
            silently serialises a key that escapes attribute-name context"
    (let [result (rf.security.gen/for-all
                   gen-breakout-key 400 7
                   (fn [k]
                      ;; A raw string preserves `/`; keyword conversion would
                      ;; reinterpret it as a namespace separator.
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

(def ^:private gen-script-breakout
  "Draws a hostile string embedding a randomly-recased `</script...>`
  payload that would prematurely close a <script> body."
  (rf.security.gen/gen-fmap
    (fn [[bits tail]]
      (str "{\"k\":\"" (recase "</script" bits) tail "\"}"))
    (fn [rng]
      (let [[bits rng1] ((rf.security.gen/gen-vec (count "</script") (rf.security.gen/gen-elem [0 1])) rng)
            [tail rng2] (rf.security.gen/rand-nth rng1 [">" " >" "/" "\t" "\n"])]
        [[bits tail] rng2]))))

(deftest script-body-breakout-neutralised
  (testing "escape-script-body-string escapes every `<`
            so no `</script` (any casing) survives in a script body; sweep
            500 recased closing-tag payloads"
    (let [result (rf.security.gen/for-all
                   gen-script-breakout 500 3
                   (fn [payload]
                     (let [escaped (rf.ssr.html-helpers/escape-script-body-string payload)]
                        ;; Without a literal `<`, no closing tag can begin.
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
      (let [out (rf.ssr.html-helpers/escape-html payload)]
        (is (not (str/includes? out "<")) (str "raw < survived: " (pr-str out)))
        (is (not (str/includes? out ">")) (str "raw > survived: " (pr-str out)))
        (is (not (str/includes? out "\"")) (str "raw \" survived: " (pr-str out)))))))

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

(def ^:private gen-edn-string-breakout
  "Draws a hostile EDN document `{:k \"<recased </script…>>\"}` where the
  STRING VALUE embeds a randomly-recased closing-tag breakout. The breakout
  lives inside an EDN string literal, so the escape must neutralise it AND
  round-trip."
  (rf.security.gen/gen-fmap
    (fn [[bits tail]]
      (pr-str {:k (str "x" (recase "</script" bits) tail "y")}))
    (fn [rng]
      (let [[bits rng1] ((rf.security.gen/gen-vec (count "</script") (rf.security.gen/gen-elem [0 1])) rng)
            [tail rng2] (rf.security.gen/rand-nth rng1 [">" " >" "/" "\t" "\n"])]
        [[bits tail] rng2]))))

(deftest edn-script-string-breakout-neutralised-and-round-trips
  (testing "escape-edn-script-body neutralises every recased
            `</script` in an EDN string literal so none survives in the body,
            AND the reader recovers the original document verbatim; sweep 500
            recased closing-tag payloads"
    (let [result (rf.security.gen/for-all
                   gen-edn-string-breakout 500 11
                   (fn [edn-doc]
                     (let [escaped (rf.ssr.html-helpers/escape-edn-script-body edn-doc)]
                       (and (no-script-breakout? escaped)
                            ;; Reading the escaped body must recover the same value.
                            (= (edn/read-string edn-doc)
                               (edn/read-string escaped))))))]
      (is (nil? result)
          (str "an EDN string-literal `</script` breakout survived or the "
               "document failed to round-trip: " (pr-str result))))))

(def ^:private edn-token-with-angle-corpus
  ;; Valid token-position `<` characters that are not breakout precursors.
  [{:a<b 1}
   {:k :<}
   {:< :>}
   {:less< "ok"}
   {:k 'sym<tail}
   {:vec [:< :a<b "<starts-with-angle"]}
   {:nested {:deep<key {:and<more "v"}}}
   {:tok<key "value with </script> inside a string"}])

(deftest edn-tokens-with-angle-round-trip
  (testing "keyword and symbol tokens containing a non-breakout `<` round-trip
            through the EDN reader unchanged, and the body carries no closing-tag
            breakout"
    (doseq [doc edn-token-with-angle-corpus]
      (let [edn-doc (pr-str doc)
            escaped (rf.ssr.html-helpers/escape-edn-script-body edn-doc)]
        (is (no-script-breakout? escaped)
            (str "doc " (pr-str doc) " left a </script breakout: " escaped))
        (is (= doc (edn/read-string escaped))
            (str "doc " (pr-str doc) " did NOT round-trip; escaped body: "
                 escaped))))))

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

(deftest edn-string-with-breakout-precursor-is-escaped-not-rejected
  (testing "a `</` or `<!` breakout inside a string literal is
            escaped (it has the `\\u003c` reader escape) and round-trips - it
            must NOT trip the token-position fail-loud path"
    (doseq [doc [{:html "<!-- comment --></script>"}
                 {:s "a</b><![CDATA[x]]>"}
                 {:s "</SCRIPT/></script >"}]]
      (let [edn-doc (pr-str doc)
            escaped (rf.ssr.html-helpers/escape-edn-script-body edn-doc)]
        (is (no-script-breakout? escaped)
            (str "string-literal breakout survived for " (pr-str doc)))
        (is (= doc (edn/read-string escaped))
            (str "string-literal breakout doc did not round-trip: "
                 (pr-str doc)))))))
