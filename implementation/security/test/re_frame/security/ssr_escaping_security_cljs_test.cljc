(ns re-frame.security.ssr-escaping-security-cljs-test
  "SSR handler filtering, attribute grammar, and script-body escaping."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [re-frame.ssr.html-helpers :as rf.ssr.html-helpers]
            [re-frame.security.gen :as rf.security.gen]))

;; Real DOM event-handler names, each drawn in a random casing by
;; `gen-handler-casing`; `gen-unlisted-handler` below covers names on no list.
(def ^:private canonical-handlers
  ["onclick" "onload" "onerror" "onmouseover" "onmouseout" "onsubmit"
   "onfocus" "onblur" "onkeydown" "onkeyup" "onkeypress" "onchange"
   "oninput" "onwheel" "onscroll" "ondrag" "ondrop" "onpaste" "oncopy"
   "oncut" "onbeforeunload" "onunload" "onhashchange" "onpopstate"
   "onmessage" "onanimationstart" "ontransitionend" "onpointerdown"
   "ontouchstart" "ontouchmove" "ontouchend" "ontouchcancel"
   "oncommand"])

(defn- recase
  "Apply a per-character case mask."
  [s bits]
  (apply str
         (map-indexed
           (fn [i ch]
             (if (= 1 (nth bits (mod i (count bits))))
               (str/upper-case (str ch))
               (str/lower-case (str ch))))
           s)))

(def ^:private gen-handler-casing
  "Generate a recased canonical handler with a hostile value."
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
                 (fn [[k v]] (= "" (rf.ssr.html-helpers/attr-string {k v}))))]
    (is (nil? result)
        (str "a recased handler leaked as a live attribute: "
             (pr-str result)))))

;; The strip rule is react-dom's prefix test, not a roster: any grammar-valid
;; name longer than two characters that starts `on` in either case (Spec 011
;; §XSS at output boundaries). So these names are on no list — `on` in a
;; random casing, an optional `-`, then 1-8 letters each in a random case —
;; and the draws include all-lower-case vendor-style names such as `onbegin`.
(def ^:private handler-tail-letters
  (vec "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"))

(def ^:private gen-unlisted-handler
  "Generate an `on*` handler name on no list, with a hostile value."
  (rf.security.gen/gen-fmap
    (fn [[prefix dash tail]]
      [(keyword (str prefix dash (apply str tail))) "alert(document.cookie)"])
    (fn [rng]
      (let [[prefix rng1] (rf.security.gen/rand-nth rng ["on" "oN" "On" "ON"])
            [dash rng2]   (rf.security.gen/rand-nth rng1 ["" "-"])
            [tail rng3]   ((rf.security.gen/gen-vec (rf.security.gen/gen-int 1 9)
                                                    (rf.security.gen/gen-elem handler-tail-letters))
                           rng2)]
        [[prefix dash tail] rng3]))))

(deftest no-unlisted-handler-serialises
  (let [result (rf.security.gen/for-all
                 gen-unlisted-handler 600 5
                 (fn [[k v]] (= "" (rf.ssr.html-helpers/attr-string {k v}))))]
    (is (nil? result)
        (str "an unlisted on* handler leaked as a live attribute: "
             (pr-str result)))))

(def ^:private breakout-chars [\= \" \space \< \> \/ \tab \newline \'])

(def ^:private gen-breakout-key
  "Insert a grammar-breakout character into an attribute name."
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

;; EDN script escaping must preserve reader semantics. A `<` can be escaped
;; inside a string literal, but the same escape is invalid inside a keyword or
;; symbol. Safe token-position `<` characters remain literal; token-position
;; `</` and `<!` precursors fail loudly because EDN has no readable in-token
;; escape for them.

(defn- no-script-breakout?
  "Reject a literal closing script tag in any casing."
  [s]
  (not (str/includes? (str/lower-case s) "</script")))

(deftest edn-script-body-round-trips-with-no-closing-tag
  ;; Token-position `<` that starts no precursor stays literal; a string
  ;; literal's `</` / `<!` (any casing, after an escaped char too) is escaped.
  (doseq [doc [{:a<b 'sym<tail}
               {:tok<key "value with </script> inside a string"}
               {:s "x</ScRiPt\ny"}]]
    (let [escaped (rf.ssr.html-helpers/escape-edn-script-body (pr-str doc))]
      (is (= [true doc] [(no-script-breakout? escaped) (edn/read-string escaped)])
          (str (pr-str doc) " escaped to " escaped)))))

(deftest edn-token-breakout-precursor-fails-loud
  (testing "Token-position </ and <! reject without corrupting EDN."
    (doseq [edn-doc ["{:a</script>b 1}"
                     "{:tag<!-- 1}"]]
      (let [thrown (try
                     (rf.ssr.html-helpers/escape-edn-script-body edn-doc)
                     nil
                     (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                       (:rf.error/id (ex-data e))))]
        (is (= :rf.error/ssr-edn-script-breakout thrown)
            (str "token-position breakout precursor in " (pr-str edn-doc)
                 " did not fail loud; got " (pr-str thrown)))))))
