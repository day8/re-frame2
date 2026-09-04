(ns re-frame.bench.hicasso.amp-merge-arms-cljs-test
  "THE CLIFF THE `:&` LADDER FELL OFF, AND THE PAIRS THAT DO NOT (rf2-v5oto).

  `amp_merge_clock_app`'s decomposition ladder prices the codec's
  `merge-caller` cleanly and the AUTHOR's share only as a SUM, and the
  reason is not statistical. One helper cannot omit a key for three
  fields out of four without an `assoc`, so `field-explicit` writes
  `:class` unconditionally and both of `rf2-z143r`'s rungs carry a
  `:class nil` passenger — which would be a single map entry if a map
  entry were a smooth cost. It is not:
  `cljs.core/PersistentArrayMap`'s `HASHMAP-THRESHOLD` is EIGHT, and the
  entry that would make nine promotes the whole map to a
  `PersistentHashMap`. Rungs (1) and (3) were therefore comparing two
  map REPRESENTATIONS, not two maps.

  `rf2-v5oto` repairs that with two arms whose pairs stay on one side of
  the cliff. This file is what stops the repair rotting: a rung whose
  arms drift back across the boundary reads as a number rather than as a
  fault, which is the class of defect the whole lane exists to refuse.

  ## What is checked mechanically and what is checked by reading

  Every field HELPER on the page is called here — the arms' own code,
  never a copy of it — and what the helper contributes to the element's
  attribute map is pinned exactly.

  The CALL-SITE literals are not reachable. They live inside bodies that
  read subscriptions, and `hicasso`'s `sub` refuses outside a boundary
  render (`:rf.error/hicasso-sub-outside-render`), so a test cannot
  evaluate one. Where a claim needs a call site, this file supplies the
  remainder map ITSELF and hands the SAME map to both arms of the pair —
  which is the stronger statement anyway, because it makes the two arms
  provably differ by the thing under test and by nothing else.

  The one fact held by reading is [[expanded-attr-keys]], `:expanded`'s
  own eight keys in its own order. `:expanded` is a FROZEN arm — the
  published figure is read off it — so the half of rung (1')'s equality
  that cannot change is the half this file cannot reach, and the half
  that can change is pinned below.

  ## Anti-vacuity

  [[the-old-rungs-crossed-the-cliff]] asserts the DEFECT: on a classless
  field the ladder's own helpers land a nine-entry `PersistentHashMap`
  where the frozen arms land an eight-entry `PersistentArrayMap`. Unless
  that reads true, every equality above it is an equality between two
  things that were never in danger of differing, and this file would
  pass while checking nothing."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.bench.hicasso.amp-merge-clock-app :as rf.bench.hicasso.amp-merge-clock-app]
            [re-frame.bench.hicasso.front.codec :as rf.bench.hicasso.front.codec]))

;; ---------------------------------------------------------------------------
;; The witness's own inputs
;; ---------------------------------------------------------------------------
;;
;; Plain data, handed to BOTH arms of every pair. The arms' own call sites
;; are unreachable (see the namespace docstring), and re-spelling them here
;; would be the second-authority shape this lane refuses everywhere else —
;; so nothing here claims to be a copy of one.

(def ^:private draft
  {:title "A title" :description "A description" :body "A body" :tagList "a,b"
   :busy? false})

(def ^:private errors {:description "can't be blank 0"})

(def ^:private id 0)

(def ^:private classless-remainder
  "A field with no class — 300 of the page's 400."
  {:type "text" :name "description" :placeholder "What's this article about?"
   :data-testid "editor-description"})

(def ^:private classless-remainder+nil-class
  "The same field as `rf2-z143r`'s two rungs write it: one helper cannot
  omit a key for three fields out of four, so the key is written and the
  value is nil. THE PASSENGER, spelled out."
  {:class nil
   :type "text" :name "description" :placeholder "What's this article about?"
   :data-testid "editor-description"})

(def ^:private title-remainder
  "The title field — 100 of 400, and the only one carrying a class."
  {:class "form-control-lg"
   :type "text" :name "title" :placeholder "Article Title"
   :data-testid "editor-title"})

(def ^:private expanded-attr-keys
  "`expanded-body`'s attribute keys, in the order it writes them.

  READ from the frozen arm rather than computed, because a body that
  reads subscriptions cannot be evaluated outside a boundary render. It
  is safe to hold this way and only this way: `:expanded` is frozen —
  the published `:&` figure is read off it — so the vector cannot go
  stale without the freeze breaking first, and it is `field-lean`, the
  arm that CAN move, whose keys are pinned against it below."
  [:type :name :placeholder :data-testid :value :disabled :on-blur :on-input])

;; ---------------------------------------------------------------------------
;; Reading a helper's answer
;; ---------------------------------------------------------------------------

(defn- input-of
  "The `[:input …]` vector a field helper builds: the fieldset's first
  child."
  [hiccup]
  (nth hiccup 1))

(defn- attrs-of
  "The attribute map a field helper writes into its element."
  [hiccup]
  (nth (input-of hiccup) 1))

(defn- presented
  "The map the CODEC actually meets — `merge-caller` run over the
  element's attribute map, which folds a `:&` remainder in and answers
  the map by identity when there is none. The `:&` arms and the
  spelled-key arms are therefore read through one function rather than
  two, so a comparison between them is a comparison of the same
  quantity."
  [hiccup]
  (rf.bench.hicasso.front.codec/merge-caller (attrs-of hiccup)))

;; ---------------------------------------------------------------------------
;; The cliff itself
;; ---------------------------------------------------------------------------

(deftest the-array-map-cliff-is-where-the-ladder-says-it-is
  (testing "`PersistentArrayMap`'s own threshold, in the ClojureScript compiled here"
    (is (= 8 (.-HASHMAP-THRESHOLD PersistentArrayMap))
        "the ladder's whole diagnosis rests on this number"))

  (testing "eight entries is an array map and the NINTH promotes"
    (let [eight (reduce (fn [m i] (assoc m (keyword (str "k" i)) i)) {} (range 8))
          nine  (assoc eight :k8 8)]
      (is (= 8 (count eight)))
      (is (instance? PersistentArrayMap eight))
      (is (= 9 (count nine)))
      (is (instance? PersistentHashMap nine)
          "one more entry, and the map is a different data structure")))

  (testing "the COMPILER draws the same line: a nine-key literal is a hash map"
    (is (instance? PersistentArrayMap
                   {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8}))
    (is (instance? PersistentHashMap
                   {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 7 :h 8 :i 9}))))

;; ---------------------------------------------------------------------------
;; Rung (1') — the author's wrapper, cleanly
;; ---------------------------------------------------------------------------

(deftest rung-1-prime-writes-expandeds-eight-keys-and-no-ninth
  (let [lean (attrs-of (rf.bench.hicasso.amp-merge-clock-app/field-lean draft errors id :description false
                                       classless-remainder))]
    (testing "`field-lean` writes `:expanded`'s keys, in `:expanded`'s order"
      (is (= expanded-attr-keys (vec (keys lean)))))
    (testing "and therefore stays on `:expanded`'s side of the cliff"
      (is (= 8 (count lean)))
      (is (instance? PersistentArrayMap lean)))
    (testing "no `:class` key reaches the element at all"
      (is (not (contains? lean :class))
          "the passenger is absent, which is the whole of the repair"))
    (testing "and the codec meets that map by identity — there is no `:&` here"
      (is (identical? lean (rf.bench.hicasso.front.codec/merge-caller lean))))))

(deftest the-lg-helper-differs-from-the-plain-one-by-the-TAG-alone
  (let [plain (rf.bench.hicasso.amp-merge-clock-app/field-lean    draft errors id :description false classless-remainder)
        lg    (rf.bench.hicasso.amp-merge-clock-app/field-lean-lg draft errors id :description false classless-remainder)]
    (testing "same remainder in, same attribute map out"
      (is (= (attrs-of plain) (attrs-of lg)))
      (is (= (vec (keys (attrs-of plain))) (vec (keys (attrs-of lg))))
          "in the same order, so the codec walks them identically")
      (is (= (type (attrs-of plain)) (type (attrs-of lg)))))
    (testing "the title's extra class rides the tag, the way `:expanded` has it"
      (is (= :input.form-control (nth (input-of plain) 0)))
      (is (= :input.form-control.form-control-lg (nth (input-of lg) 0))))))

;; ---------------------------------------------------------------------------
;; Rung (3') — the author's round trip, cleanly
;; ---------------------------------------------------------------------------

(deftest rung-3-primes-two-arms-present-the-codec-the-same-map
  (doseq [[label k remainder entries representation]
          [["a classless field" :description classless-remainder 8 PersistentArrayMap]
           ["the title field"   :title       title-remainder     9 PersistentHashMap]]]
    (testing label
      (let [;; `:merged` puts `:k` and `:busy?` INTO the caller map and
            ;; its helper takes them back out; `:no-dissoc-lean` passes
            ;; them as arguments. Both are handed the SAME remainder, so
            ;; the round trip is the only thing between them.
            merged (presented (rf.bench.hicasso.amp-merge-clock-app/field draft errors id
                                         (merge {:k k :busy? false} remainder)))
            lean   (presented (rf.bench.hicasso.amp-merge-clock-app/field-no-dissoc draft errors id k false
                                                   remainder))]
        (is (= merged lean) "the same attribute map")
        (is (= (vec (keys merged)) (vec (keys lean))) "in the same order")
        (is (= (type merged) (type lean)) "and in the same REPRESENTATION")
        (is (= entries (count merged)))
        (is (instance? representation merged))
        (is (instance? representation lean))))))

;; ---------------------------------------------------------------------------
;; Anti-vacuity — the defect rf2-v5oto repairs, asserted rather than described
;; ---------------------------------------------------------------------------

(deftest the-old-rungs-crossed-the-cliff
  (testing "rung (1): `field-explicit` writes `:class` whether or not there is one"
    (let [old (attrs-of (rf.bench.hicasso.amp-merge-clock-app/field-explicit draft errors id :description false
                                            classless-remainder))]
      (is (contains? old :class))
      (is (nil? (:class old)) "the passenger, and its value is nil")
      (is (= 9 (count old)))
      (is (instance? PersistentHashMap old)
          "a hash map where `:expanded` and `field-lean` build an array map")))

  (testing "rung (3): the `:class nil` call site crosses where `:merged` does not"
    (let [merged (presented (rf.bench.hicasso.amp-merge-clock-app/field draft errors id
                                       (merge {:k :description :busy? false}
                                              classless-remainder)))
          old    (presented (rf.bench.hicasso.amp-merge-clock-app/field-no-dissoc draft errors id :description false
                                                 classless-remainder+nil-class))]
      (is (= 8 (count merged)))
      (is (instance? PersistentArrayMap merged))
      (is (= 9 (count old)))
      (is (instance? PersistentHashMap old))
      (is (not= (type merged) (type old))
          (str "rung (3)'s two arms were a map REPRESENTATION apart on 300 of "
               "the page's 400 fields, which is why it read NEGATIVE")))))
