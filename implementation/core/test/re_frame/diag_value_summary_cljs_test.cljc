(ns re-frame.diag-value-summary-cljs-test
  "rf2-uwqale / rf2-210uq — EP-0015 diagnostic value-summary gate.

  Spec 015 §Data-Classification forbids raw application values in
  framework exception messages / ex-data: a value baked into a flattened
  message or ex-data slot is captured off-box (console, error boundary,
  host log, SSR error handler, observability) BEFORE the record projector
  can classify the path, and path-based projection cannot recover a value
  that no longer sits at a path. `re-frame.error/diag-value-summary`
  produces an EP-0015-safe SUMMARY — never the raw value.

  ## rf2-210uq — the summary used to disclose what it was handed

  The primitive is reached for BECAUSE an author believes it cannot
  disclose, and PR #7204 routes malformed reserved-`:rf.server/*` fx args
  (a cookie `:value` is a session token) through it into both a thrown
  message and ex-data. It did not meet that claim. Four distinct paths
  carried input content into the output:

    1. `:head` on a STRING — any string of 24 chars or fewer rode back
       VERBATIM, and a longer one rode back as its raw first 24 chars. A
       short token was reproduced whole; a bearer token leaked its prefix.
    2. `:keys` on a MAP — every top-level key, uncapped and unsanitised.
       App/user-controlled keys carry content, and an attacker-sized key
       set made the 'bounded' summary arbitrarily large.
    3. `:head` on a KEYWORD or SYMBOL — returned `(str v)` with NO length
       bound at all, on the guess that such values are always structural.
       `(keyword some-user-string)` is not.
    4. `:head` on a NUMBER / BOOLEAN / unknown `:scalar` — a card number,
       a PIN or an arbitrary host object's `toString` is content, and the
       `:scalar` leg calls `toString` on a value the framework knows
       nothing about.

  ## The contract this gate pins

  A summary now carries SHAPE and NOTHING ELSE: a `:type` drawn from a
  closed keyword vocabulary, and — for a counted collection or string — an
  integer `:count`. That is a STRUCTURAL guarantee rather than a
  redaction-quality argument: no expression in the summary is derived from
  the input's content, so there is no prefix to bound and no key set to
  cap, and the serialized summary has a FIXED size bound whatever arrives.

  Size/cardinality are retained deliberately: 'you passed a 4000-char
  string where a keyword was expected' is the whole diagnostic value, an
  integer cannot carry a fragment of a token, and `:count` is already what
  every in-repo consumer of a summary reads alongside `:type`.

  Dual-runtime: `-cljs-test` rides `npm run test:cljs`; the `.cljc` is
  also discovered on the JVM. Pure data — no runtime state."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.error :as error]))

;; ---- the adversarial corpus ----------------------------------------------

(def ^:private sentinel
  "EXACTLY 24 characters — the historic `diag-head-limit`. A secret whose
  first 24 chars are this marker was reproduced WHOLE by the old
  `(subs s 0 24)` prefix, which is the sharpest available witness."
  "SENTINELSENTINELSENTINEL")

(def ^:private short-secret
  "16 chars — UNDER the historic limit, so the old head returned it verbatim."
  "SENTINELSENTINEL")

(def ^:private long-secret
  "A bearer-token-shaped secret whose first 24 chars ARE the sentinel."
  (str sentinel "-tail-that-was-truncated-away-0123456789"))

(defn- hostile-scalar
  "A host object outside every known Clojure shape whose `toString` answers
  the sentinel — the `:scalar` leg's input, about which the framework knows
  nothing at all."
  []
  #?(:clj  (reify Object (toString [_] sentinel))
     :cljs (let [o (js-obj)]
             (set! (.-toString o) (fn [] sentinel))
             o)))

(defn- exploding-scalar
  "A host object whose `toString` THROWS. Summarising must not throw: a
  diagnostic that explodes while describing a failure destroys the failure."
  []
  #?(:clj  (reify Object (toString [_] (throw (Exception. "boom"))))
     :cljs (let [o (js-obj)]
             (set! (.-toString o) (fn [] (throw (js/Error. "boom"))))
             o)))

(def ^:private adversarial-corpus
  "Hostile input, not tidy input: secrets at and around the old head
  boundary, sentinel-bearing map keys of every key type, keys carrying
  markup and control characters, a 2000-key map, nesting, and host objects
  whose `toString` answers or throws."
  (delay
    [short-secret
     long-secret
     sentinel
     (str/join "" (repeat 500 sentinel))
     ;; sentinel-bearing DYNAMIC map keys, one per key type
     {sentinel 1}
     {(keyword sentinel) 1}
     {(symbol sentinel) 1}
     {[sentinel] 1}
     ;; keys chosen to look like markup / control characters
     {(str "<script>alert('" sentinel "')</script>") 1
      (str "\u001b[31m" sentinel "\u001b[0m")        2
      (str "line1\nline2\r\n" sentinel)              3
      (str sentinel "\u0000")                  4}
     ;; a VERY large map — the old `:keys` leg grew without bound
     (into {} (map (fn [i] [(str sentinel "-key-" i) i])) (range 2000))
     ;; nesting: neither outer keys nor inner content may surface
     {:outer {:inner {(keyword sentinel) long-secret}}}
     [long-secret {sentinel 1} #{sentinel}]
     #{sentinel long-secret}
     (list sentinel long-secret)
     ;; scalars that ARE their content
     4111111111111111
     -12345678901234567890
     3.14159
     true
     false
     (keyword sentinel)
     (symbol sentinel)
     (hostile-scalar)
     ;; degenerate shapes
     nil
     {}
     []
     ""
     (fn [] nil)]))

;; ---- the closed output contract ------------------------------------------

(def ^:private summary-types
  "The closed `:type` vocabulary. Spec 009's scroll-strategy row and Spec
  012 §Custom scroll strategies both name it as a stable axis."
  #{:map :vector :seq :set :string :keyword :symbol :number :boolean :nil
    :fn :scalar})

(defn- content-free?
  "TRUE when a summary can carry nothing derived from its input's content:
  every key is `:type` or `:count`, `:type` is a member of the closed
  vocabulary, and `:count` — when present — is a non-negative integer.

  This is the whole redaction argument. Rather than hunting a sentinel
  through a bounded prefix, it enumerates what the output is ALLOWED to
  contain and refuses everything else, so a fifth leak added later fails
  here without anyone thinking to write a sentinel for it."
  [s]
  (and (map? s)
       (every? #{:type :count} (keys s))
       (contains? summary-types (:type s))
       (or (not (contains? s :count))
           (and (integer? (:count s)) (not (neg? (:count s)))))))

(def ^:private max-summary-chars
  "A FIXED bound on the serialized summary, independent of the input's
  size. `{:type :boolean, :count 4294967296}` is the widest realistic
  shape; 64 leaves room without letting anything input-sized through."
  64)

;; ---- rf2-210uq witnesses: nothing given comes back -----------------------

(deftest a-short-secret-is-not-reproduced
  (testing "a secret UNDER the historic 24-char head limit was returned
            verbatim as `:head`; the summary must disclose none of it"
    (let [s (error/diag-value-summary short-secret)]
      (is (= :string (:type s)))
      (is (= (count short-secret) (:count s)) "length is shape, and stays")
      (is (not (str/includes? (pr-str s) "SENTINEL"))
          "the short secret rode back WHOLE in the pre-fix `:head`"))))

(deftest a-long-secrets-raw-prefix-is-not-reproduced
  (testing "a bearer-token-shaped secret whose first 24 chars are the
            sentinel: the pre-fix `(subs s 0 24)` head reproduced the
            sentinel EXACTLY, which is the leak the old test accepted"
    (let [s (error/diag-value-summary long-secret)]
      (is (= :string (:type s)))
      (is (= (count long-secret) (:count s)))
      (is (not (str/includes? (pr-str s) "SENTINEL"))
          "no raw prefix of the secret survives into the summary")
      (is (not (str/includes? (pr-str s) "tail-that-was-truncated"))))))

(deftest sentinel-bearing-map-keys-are-not-reproduced
  (testing "map KEYS are app/user-controlled — a dynamic key of ANY key type
            carries content and must not ride into the summary"
    (doseq [m [{sentinel "v"}
               {(keyword sentinel) "v"}
               {(symbol sentinel) "v"}
               {[sentinel] "v"}
               {:token "secret" :pdf "%PDF-1.4 huge blob" :n 7}]]
      (let [s       (error/diag-value-summary m)
            printed (pr-str s)]
        (is (= :map (:type s)))
        (is (= (count m) (:count s)) "cardinality is shape, and stays")
        (is (not (str/includes? printed "SENTINEL"))
            (str "key content leaked for " (pr-str (keys m))))
        (is (not (str/includes? printed "secret")))
        (is (not (str/includes? printed "%PDF")))))))

(deftest markup-and-control-character-keys-are-not-reproduced
  (testing "keys chosen to break whatever reads the diagnostic downstream —
            markup, ANSI escapes, newlines, NUL — reach no output at all"
    (let [m       {(str "<script>alert('" sentinel "')</script>") 1
                   (str "\u001b[31m" sentinel "\u001b[0m")        2
                   (str "line1\nline2\r\n" sentinel)              3
                   (str sentinel "\u0000")                  4}
          printed (pr-str (error/diag-value-summary m))]
      (is (= "{:type :map, :count 4}" printed)
          "the summary is the shape and nothing else")
      (doseq [fragment ["SENTINEL" "<script>" "\u001b" "\n" "\u0000"]]
        (is (not (str/includes? printed fragment))
            (str "hostile key fragment reached the output: " (pr-str fragment)))))))

(deftest a-very-large-map-summarises-to-a-fixed-size
  (testing "the pre-fix `:keys` leg grew with the key set, so an
            attacker-sized map inflated the 'bounded' summary without limit"
    (let [m       (into {} (map (fn [i] [(str sentinel "-key-" i) i])) (range 2000))
          printed (pr-str (error/diag-value-summary m))]
      (is (= 2000 (:count (error/diag-value-summary m))))
      (is (not (str/includes? printed "SENTINEL")))
      (is (<= (count printed) max-summary-chars)
          (str "summary grew with the input: " (count printed) " chars")))))

(deftest keyword-and-symbol-heads-are-not-reproduced
  (testing "the pre-fix `:head` returned keywords/symbols with NO length
            bound at all, on the guess that they are always structural. A
            keyword built from user input is not (rf2-210uq leak 3)"
    (doseq [v [(keyword sentinel)
               (symbol sentinel)
               (keyword (str/join "" (repeat 200 sentinel)))]]
      (let [printed (pr-str (error/diag-value-summary v))]
        (is (not (str/includes? printed "SENTINEL"))
            "an unbounded structural head reproduced user content")
        (is (<= (count printed) max-summary-chars))))))

(deftest scalar-values-are-not-reproduced
  (testing "a number, a boolean and an unknown host object are CONTENT — a
            card number and a `toString` the framework knows nothing about
            (rf2-210uq leak 4)"
    (is (= {:type :number} (error/diag-value-summary 4111111111111111)))
    (is (= {:type :boolean} (error/diag-value-summary true)))
    (is (= {:type :boolean} (error/diag-value-summary false)))
    (let [printed (pr-str (error/diag-value-summary (hostile-scalar)))]
      (is (not (str/includes? printed "SENTINEL"))
          "the :scalar leg called toString on an unknown host value"))))

(deftest nested-content-is-not-reproduced
  (testing "no leg recurses, so nesting cannot smuggle content out either"
    (doseq [v [{:outer {:inner {(keyword sentinel) long-secret}}}
               [long-secret {sentinel 1} #{sentinel}]
               #{sentinel long-secret}
               (list sentinel long-secret)]]
      (is (not (str/includes? (pr-str (error/diag-value-summary v)) "SENTINEL"))
          (str "nested content leaked from " (:type (error/diag-value-summary v)))))))

;; ---- the capstone: the OUTPUT GRAMMAR forbids content --------------------

(deftest every-summary-is-content-free-and-fixed-size
  (testing "across the whole adversarial corpus, every summary is drawn
            from the closed output grammar and fits the fixed bound — the
            structural proof that there is no fifth leak"
    (doseq [v @adversarial-corpus]
      (let [s       (error/diag-value-summary v)
            printed (pr-str s)]
        (is (content-free? s)
            (str "summary escaped the closed output grammar: " printed))
        (is (<= (count printed) max-summary-chars)
            (str "summary exceeded the fixed bound: " printed))
        (is (not (str/includes? printed "SENTINEL"))
            (str "sentinel reached the output: " printed))))))

(deftest summarising-never-throws
  (testing "a value whose `toString` throws must still summarise — a
            diagnostic that explodes while describing a failure destroys
            the failure it was called to describe"
    (is (= {:type :scalar} (error/diag-value-summary (exploding-scalar))))
    (is (= {:type :map :count 2}
           (error/diag-value-summary {(exploding-scalar) :v :ok 1})))))

;; ---- the diagnostic VALUE that is preserved ------------------------------

(deftest shape-diagnostics-survive-the-redaction
  (testing "a summary that says nothing is safe and useless — type and
            size still answer 'what did I actually get?'"
    (is (= {:type :string :count 42}
           (error/diag-value-summary "super-secret-bearer-token-value-1234567890")))
    (is (= {:type :map :count 3}    (error/diag-value-summary {:a 1 :b 2 :c 3})))
    (is (= {:type :vector :count 3}
           (error/diag-value-summary [:div {:on-click (fn [] nil)} "child text"])))
    (is (= {:type :set :count 3}    (error/diag-value-summary #{1 2 3})))
    (is (= {:type :keyword}         (error/diag-value-summary :ws.app/request)))
    (is (= {:type :symbol}          (error/diag-value-summary 'reagent2.template/as-element)))
    (is (= {:type :number}          (error/diag-value-summary 42)))
    (is (= {:type :nil}             (error/diag-value-summary nil)))
    (is (= {:type :fn}              (error/diag-value-summary (fn [] nil))))
    ;; A lazy seq is caught by the `seq?` arm BEFORE the `seqable?` arm and
    ;; carries NO count — an unbounded/lazy seq must not be realised on the
    ;; failure path.
    (is (= {:type :seq}             (error/diag-value-summary (map inc [1 2 3]))))
    (is (= {:type :seq}             (error/diag-value-summary '(1 2 3))))))

(deftest empty-collections-are-distinguishable
  (testing "count 0 still separates an empty collection from a missing one"
    (is (= {:type :map :count 0}    (error/diag-value-summary {})))
    (is (= {:type :vector :count 0} (error/diag-value-summary [])))
    (is (= {:type :string :count 0} (error/diag-value-summary "")))))
