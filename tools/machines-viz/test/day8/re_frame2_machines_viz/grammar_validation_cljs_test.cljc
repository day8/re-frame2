(ns day8.re-frame2-machines-viz.grammar-validation-cljs-test
  "The viz's own pins on `grammar/definition-defect`: the canonical
  `:rf.error/machine-*` category each structural defect earns at any depth,
  the location a defect names, the injective id codec, and the value-free
  summary. Accept/reject agreement with the engine's `validate-machine!` is
  pinned in `engine-grammar-parity-test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.grammar :as g]))

(defn- category [definition]
  (:category (g/definition-defect definition)))

(def ^:private category-rows
  "Definition → the category `definition-defect` refuses it with, or nil when
  it projects."
  [;; compound `:initial` presence, at any depth and inside a region
   [{:initial :a :states {:a {:initial :b :states {:b {:states {:c {}}}}}}}
    :rf.error/machine-compound-state-missing-initial]
   [{:type :parallel :regions {:r {:initial :a :states {:a {:states {:b {}}}}}}}
    :rf.error/machine-compound-state-missing-initial]
   ;; parallel root and region shape
   [{:type :parallel :regions {:r {:initial "x" :states {:x {}}}}} :rf.error/machine-parallel-bad-shape]
   [{:type :parallel :initial :a :regions {:r {:initial :a :states {:a {}}}}} :rf.error/machine-parallel-bad-shape]
   [{:type :parallel :regions {}} :rf.error/machine-parallel-bad-shape]
   [{:type :parallel :regions 5} :rf.error/machine-parallel-bad-shape]
   [{:type :parallel :regions {:r {:initial :a :states {:a {:type :parallel :regions {:x {:initial :y :states {:y {}}}}}}}}}
    :rf.error/machine-parallel-nested-not-supported]
   ;; flat root shape
   [42 :rf.error/machine-bad-definition]
   [{:initial "idle" :states {:idle {}}} :rf.error/machine-missing-initial]
   [{:initial :a :states {}} :rf.error/machine-missing-states]
   [{:initial :a :states 7} :rf.error/machine-missing-states]
   ;; unknown BARE keys; namespaced keys and `:meta` are the open slots
   [{:initial :a :innitial :a :states {:a {}}} :rf.error/machine-unknown-node-key]
   [{:initial :a :states {:a {:my.app/note "meta"}}} nil]
   [{:initial :a :my.tool/x 1 :states {:a {}}} nil]
   [{:initial :a :states {:a {:meta {:doc "x"}}}} nil]
   ;; transition targets: a keyword names a sibling, so a top-level state is
   ;; unreachable by keyword from a nested one; every target-bearing slot is checked
   [{:initial :o :states {:o {:initial :i :states {:i {:on {:up :top}}}} :top {}}} :rf.error/machine-unresolved-target]
   [{:initial :a :states {:a {:on {:go [:nope]}}}} :rf.error/machine-unresolved-target]
   [{:initial :a :states {:a {:on {:go []}}}} :rf.error/machine-bad-target]
   [{:initial :a :states {:a {:on {:go {:target 42}}}}} :rf.error/machine-bad-target]
   [{:initial :a :states {:a {:after {1000 {:target :nope}}}}} :rf.error/machine-unresolved-target]
   [{:initial :a :states {:a {:always [{:target :nope}]}}} :rf.error/machine-unresolved-target]
   [{:initial :a :states {:a {:initial :b :states {:b {:final? true}} :on-done {:target :nope}}}}
    :rf.error/machine-unresolved-target]
   [{:initial :a :states {:a {:spawn {:machine-id :m :on-error {:target :nope}}}}} :rf.error/machine-unresolved-target]
   [{:initial :a :states {:a {:on {:loop :same-state}}}} nil]
   ;; history pseudo-states
   [{:initial :a :states {:a {} :hist {:type :history}}} :rf.error/machine-history-misplaced]
   [{:initial :o :states {:o {:initial :s :states {:s {} :h {:type :history :on {:x :s}}}}}}
    :rf.error/machine-history-extra-keys]
   [{:initial :o :states {:o {:initial :s :states {:s {} :h1 {:type :history} :h2 {:type :history}}}}}
    :rf.error/machine-history-duplicate]
   [{:initial :o :states {:o {:initial :s :states {:s {} :h {:type :history :default-target :s}}}}} nil]
   [{:initial :o :states {:o {:initial :s :states {:s {} :h {:type :history :default-target :nope}}}}}
    :rf.error/machine-history-bad-default-target]
   ;; final states
   [{:initial :a :states {:a {:final? true :states {:b {}}}}} :rf.error/machine-final-state-compound]
   [{:initial :a :states {:a {:final? true :on {:x :b}} :b {}}} :rf.error/machine-final-state-has-transitions]
   [{:initial :a :states {:a {:output-key :foo}}} :rf.error/machine-output-key-without-final]
   [{:initial :a :states {:a {:error? true}}} :rf.error/machine-error-flag-without-final]
   [{:initial :a :states {:a {:on {:go :b}} :b {:final? true :output-key :out :error? true}}} nil]
   ;; tags, `:after` delay keys, spawn shape
   [{:initial :a :states {:a {:tags [:x]}}} :rf.error/machine-bad-tags]
   [{:initial :a :states {:a {:tags #{:ok "bad"}}}} :rf.error/machine-bad-tags]
   [{:initial :a :states {:a {:after {0 :b}} :b {}}} :rf.error/machine-bad-after-delay]
   [{:initial :a :states {:a {:spawn {}}}} :rf.error/machine-spawn-bad-shape]
   [{:initial :a :states {:a {:spawn {:definition {:initial :x :states {:x {}}}}}}} :rf.error/machine-spawn-bad-shape]
   ;; `:choice` and `:timeout` are validated on their lowered shape
   [{:initial :g :states {:g {:type :choice :choice [{:target :nope}]} :a {}}} :rf.error/machine-unresolved-target]
   [{:initial :a :states {:a {:timeout 1000 :on-timeout :nope}}} :rf.error/machine-unresolved-target]])

(deftest definition-defect-categories
  (doseq [[definition expected] category-rows]
    (is (= expected (category definition)) (pr-str definition))))

;; The defect names WHERE the fault sits (its `:path`, plus `:slot` / `:keys`
;; where they apply) and nothing it was handed: the summary's `:depth`,
;; `:slot` and `:key-count` are read off it.

(deftest defect-names-its-location-and-nothing-else
  (doseq [[definition defect]
          [[{:initial :outer :states {:outer {:states {:inner {}}}}}
            {:category :rf.error/machine-compound-state-missing-initial :path [:outer]}]
           [{:initial :idle :states {:idle {:on {:go :missing}}}}
            {:category :rf.error/machine-unresolved-target :path [:idle] :slot :on}]
           [{:initial :a
             :data    {:secret "TOP-SECRET-TOKEN"}
             :states  {:a {:on-entry (fn secret-action [_] :SECRET-RETURN)}}}
            {:category :rf.error/machine-unknown-node-key :path [:a] :keys [:on-entry]}]
           ;; a malformed root fallback clause is refused at its scope, not thrown
           [{:initial :a :states {:a {}} :on [:secret-token "leak-me-42"]}
            {:category :rf.error/machine-bad-on-clause :path []}]
           [{:type :parallel :regions {:r {:initial :a :states {:a {}} :on :retry}}}
            {:category :rf.error/machine-bad-on-clause :path [:r]}]]]
      (is (= defect (g/definition-defect definition)))))

(deftest escape-id-segment-fixed-width-and-injective
  (testing "≤ U+00FF escapes to `_XX`, above it to the self-delimiting
            `_u<4-hex>`, so distinct segments never collide (đ vs \\u0011 + \"1\")
            and no escape mints the `__` the consumers' separators rely on"
    (let [expected {"/"                   "_2f"
                    "-"                   "_2d"
                    "_"                   "_5f"
                    "?"                   "_3f"
                    (str (char 0x11))     "_11"
                    (str (char 0x11) "1") "_111"
                    "开"                  "_u5f00"
                    "始"                  "_u59cb"
                    "开始"                "_u5f00_u59cb"
                    "đ"                   "_u0111"
                    "a-b"                 "a_2db"
                    "a/b"                 "a_2fb"
                    "a_b"                 "a_5fb"}]
      (is (= expected (into {} (map (juxt identity g/escape-id-segment)) (keys expected)))))))

;; ---------------------------------------------------------------------------
;; EP-0015 — the summary is content-free BY CONSTRUCTION
;;
;; A sentinel hunt only finds the leak someone thought to plant: a `:keys`
;; leg, or a `:defect` naming the offending keys and their state-id path,
;; would pass one, because all three are KEY-position material. So the checks
;; below are a GRAMMAR, not a hunt: every summary over a corpus of hostile
;; definitions is a closed-vocabulary `:type` plus integers and booleans, and
;; serializes inside a fixed bound however large the forged definition.

(def ^:private sentinel
  "The token planted in every position a forged definition can reach."
  "hunter2-swordfish-SENTINEL")

(def ^:private sentinel-fragments
  "Every 8-character window of the sentinel: a bounded prefix of attacker
  material is a leak too."
  (into #{} (map #(subs sentinel % (+ % 8))) (range (- (count sentinel) 7))))

(defn- discloses?
  "Does `x`, serialized, reproduce any fragment of the sentinel? `pr-str`
  rather than a string walk, because a leaked KEYWORD is not a string."
  [x]
  (let [s (pr-str x)]
    (boolean (some #(str/includes? s %) sentinel-fragments))))

(def ^:private exploding-key
  "A map key whose `toString` THROWS: a summariser or validator that `str`s or
  `namespace`s caller-supplied keys would raise the key's exception in place
  of the failure it was called to describe."
  #?(:clj  (reify Object
             (toString [_] (throw (ex-info (str "toString exploded: " sentinel) {}))))
     :cljs (let [o #js {}]
             (set! (.-toString o)
                   (fn [] (throw (js/Error. (str "toString exploded: " sentinel)))))
             o)))

(def ^:private hostile-keys
  "Sentinel-bearing keys of every type a forged definition can carry, plus
  markup and control characters."
  {(str "string-key-" sentinel)                 1
   (keyword sentinel)                           2
   (keyword sentinel sentinel)                  3
   (symbol sentinel)                            4
   [sentinel]                                   5
   {sentinel sentinel}                          6
   (str "<script>alert(" sentinel ")</script>") 7
   (str \u001b "[31m" sentinel \u001b "[0m")    8
   (str "CR\r\nLF-" sentinel)                   9
   (str "NUL" \u0000 "-" sentinel)              10
   4111111111111111                             11
   true                                         12})

(def ^:private attacker-sized-definition
  "2000 sentinel-named states: a summary leg that grew with the input would
  blow the serialized bound."
  {:initial                             (keyword (str sentinel "-0"))
   (keyword (str sentinel "-root-key")) 1
   :states  (into {} (map (fn [i] [(keyword (str sentinel "-" i)) {}])) (range 2000))})

(def ^:private forged-definitions
  "Definitions a forged share fragment, an LLM response or an SCXML / Mermaid
  import can hand the grammar — one per `definition-summary` type leg. Each is
  rejected."
  [["hostile keys of every key type on the root"
    (merge hostile-keys {:initial :a :states {:a {}}})]
   ["a hostile key on a state node"
    {:initial :a :states {:a (merge hostile-keys {})}}]
   ["a key whose toString throws"
    {:initial :a :states {:a {}} exploding-key 1}]
   ["sentinel state ids with a dangling target"
    {:initial (keyword sentinel)
     :states  {(keyword sentinel) {:on {(keyword sentinel) (keyword (str sentinel "-gone"))}}}}]
   ["a sentinel-named nested compound missing :initial"
    {:initial (keyword sentinel)
     :states  {(keyword sentinel) {:states {(keyword (str sentinel "-inner")) {}}}}}]
   ["an attacker-sized 2000-state definition" attacker-sized-definition]
   ["a live :data slot beside a defect"
    {:initial :a :states {:a {:on-entry (fn [_] sentinel)}} :data {:token sentinel}}]
   ["a parallel root of sentinel-named regions"
    {:type :parallel :regions {(keyword sentinel) {:states {(keyword sentinel) {}}}}}]
   ["a sentinel string where a definition was expected"    sentinel]
   ["a sentinel keyword with no length bound"
    (keyword (apply str (repeat 20 sentinel)))]
   ["a sentinel symbol"                                    (symbol sentinel)]
   ["a vector of secrets"                                  [sentinel sentinel]]
   ["a set of secrets"                                     #{sentinel}]
   ["a list of secrets"                                    (list sentinel)]
   ["a lazy seq of secrets"                                (map identity [sentinel])]
   ["a live fn"                                            (fn [] sentinel)]
   ["a 16-digit card number"                               4111111111111111]
   ["a boolean"                                            true]
   ["nil"                                                  nil]
   ;; the `:else` leg, which only an opaque host object reaches
   ["an opaque host object naming the sentinel"
    #?(:clj (java.io.File. ^String sentinel) :cljs (js-obj "k" sentinel))]])

(def ^:private summary-keys
  "The CLOSED key set of a summary: any other slot would be derived from the
  definition's content."
  #{:type :count :parallel :state-count :region-count :defect})

(def ^:private defect-keys
  "The CLOSED key set of an embedded `:defect` — no `:path` or `:keys`, which
  are the forger's own state ids and keys."
  #{:category :slot :depth :key-count})

(def ^:private defect-slots #{:on :after :always :on-done})

(defn- non-neg-int-slots?
  "Every one of `ks` present in `m` is a non-negative integer."
  [m ks]
  (every? (fn [k] (or (not (contains? m k))
                      (let [v (get m k)] (and (integer? v) (not (neg? v))))))
          ks))

(defn- content-free-defect? [d]
  (and (map? d)
       (every? defect-keys (keys d))
       (keyword? (:category d))
       (= "rf.error" (namespace (:category d)))
       (str/starts-with? (name (:category d)) "machine-")
       (or (not (contains? d :slot)) (contains? defect-slots (:slot d)))
       (non-neg-int-slots? d [:depth :key-count])))

(defn- content-free-summary?
  "The grammar: closed key set, closed `:type` vocabulary, non-negative integer
  counts, boolean `:parallel`, and a content-free `:defect` when present."
  [s]
  (and (map? s)
       (every? summary-keys (keys s))
       (contains? g/summary-type-vocabulary (:type s))
       (non-neg-int-slots? s [:count :state-count :region-count])
       (or (not (contains? s :parallel)) (boolean? (:parallel s)))
       (or (not (contains? s :defect))   (content-free-defect? (:defect s)))))

(def ^:private summary-serialized-bound
  "Slack over the longest legal summary, orders of magnitude under the forged
  definitions."
  200)

(deftest definition-summary-is-content-free-by-construction
  (doseq [[label d] forged-definitions]
    (let [s (g/definition-summary d)]
      (is (some? (:defect s))
          (str label " — rejected, so the summary is on the path that matters"))
      (is (content-free-summary? s)
          (str label " — not a content-free summary: " (pr-str s)))
      (is (not (discloses? s))
          (str label " — no sentinel fragment may survive: " (pr-str s)))
      (is (<= (count (pr-str s)) summary-serialized-bound)
          (str label " — fixed serialized bound, got " (count (pr-str s)))))))

(deftest hostile-keys-do-not-destroy-the-failure-being-described
  (testing "a key that is not `Named` is refused as an unknown key rather than
            thrown out of the validator as a host cast exception"
    (doseq [[label d] [["a string key on the root"    {:initial :a :states {:a {}} "x" 1}]
                       ["a key whose toString throws" {:initial :a :states {:a {}} exploding-key 1}]]]
      (is (= :rf.error/machine-unknown-node-key (category d)) label))))
