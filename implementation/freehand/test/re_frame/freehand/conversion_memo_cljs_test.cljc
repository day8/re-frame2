(ns re-frame.freehand.conversion-memo-cljs-test
  "The remembered projections of `re-frame.freehand.conversion`, pinned by
  what they ANSWER rather than by how they store it.

  These caches changed representation under rf2-xu6rx: on ClojureScript the
  store is now the host's own `js/Map` and on the JVM it stays a persistent
  map in an atom, because a persistent map read on every attribute of every
  element was itself 3.04% of an interpreted mount. A representation swap is
  exactly the kind of change that can be invisible in the common case and
  wrong at an edge, so what is asserted here is the contract a caller can
  actually observe — the answer, the key space, and the bound — and never
  the store. Nothing below reaches for `js/Map` or for an atom, and the file
  is `.cljc` so both hosts answer the same table.

  Each projection has an UNCACHED oracle in `re-frame.freehand.rules`, and
  every assertion compares against that rather than against a literal, so a
  cache that went stale or keyed wrongly fails here even if the expected
  spelling were ever revised."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.freehand.conversion :as conv]
            [re-frame.freehand.rules :as rules]))

;; ===========================================================================
;; The answer, on a miss and again on the hit
;; ===========================================================================

(def ^:private prop-names
  ["class" "for" "tab-index" "read-only" "data-thing" "aria-label"
   "not-a-real-attribute"])

(def ^:private event-names
  ["on-click" "on-double-click" "on-pointer-down"])

(def ^:private style-names
  ["font-size" "background-color" "-webkit-line-clamp" "-moz-appearance"
   "-ms-flex" "--brand-accent" "color"])

(deftest remembered-answers-what-the-uncached-projection-answers
  (testing "asked once (a miss) and again (a hit), against the rules oracle"
    (doseq [n prop-names]
      (let [expected (rules/react-prop-name n)]
        (is (= expected (conv/react-prop-name (keyword n))) n)
        (is (= expected (conv/react-prop-name (keyword n))) n)))

    (doseq [n event-names]
      (let [expected (rules/react-event-name n)]
        (is (= expected (conv/react-event-name (keyword n))) n)
        (is (= expected (conv/react-event-name (keyword n))) n)))

    (doseq [n style-names]
      (let [expected (rules/react-style-name n)]
        (is (= expected (conv/react-style-name n)) n)
        (is (= expected (conv/react-style-name n)) n)))

    (doseq [n prop-names]
      (let [expected (rules/custom-element-property-name n)]
        (is (= expected (conv/custom-element-property-name (keyword n))) n)
        (is (= expected (conv/custom-element-property-name (keyword n))) n)))))

;; ===========================================================================
;; The key space — a cache MISS is a speed cost, never a different answer
;; ===========================================================================
;;
;; `js/Map` compares strings by VALUE and everything else by IDENTITY, so a
;; caller that mints `(keyword "class")` afresh on each render can miss a
;; cache the persistent map would have hit. That trade is documented in
;; `conversion` §The store, and it is only ever allowed to cost time: the
;; projections are pure, so a miss recomputes the same answer. This is the
;; assertion that keeps it that way. It deliberately does NOT assert
;; anything about `identical?` — whether a given host interns a keyword is
;; the representation detail this file refuses to depend on.

(deftest a-freshly-minted-key-agrees-with-the-interned-one
  (testing "keyword-keyed projections"
    (doseq [n prop-names]
      ;; The literal path first, so the cache is warm on the interned key
      ;; before the minted one is asked.
      (let [warm   (conv/react-prop-name (keyword n))
            minted (conv/react-prop-name (keyword (str n)))]
        (is (= warm minted) n)
        (is (= (rules/react-prop-name n) minted) n))))

  (testing "a string-keyed projection, where js/Map compares by value"
    (doseq [n style-names]
      (let [warm  (conv/react-style-name n)
            built (conv/react-style-name (str n))]
        (is (= warm built) n)
        (is (= (rules/react-style-name n) built) n)))))

;; ===========================================================================
;; The bound — a cache limit, not a correctness limit
;; ===========================================================================
;;
;; Every projection stops STORING past `conversion`'s 4096-entry limit and
;; nothing is ever evicted, so a key space larger than the bound degrades to
;; what the projection always cost. That is unchanged by the representation
;; swap, and it is the property worth pinning: unbounded growth is refused,
;; and refusing it never changes an answer.
;;
;; This saturates a real, shared, module-level cache, which is why it picks
;; `custom-element-property-name` — the narrowest-traffic projection of the
;; four. All of them share one `remembered`, so the mechanism is pinned by
;; saturating any one of them, and the cost of doing it here is that later
;; tests recompute this one projection instead of reading it. Correct either
;; way, which is precisely the claim.

(def ^:private beyond-the-bound 5000)

(deftest past-the-bound-the-projection-is-still-total
  (let [early :saturation-probe-early]
    (testing "a key cached before the bound still answers"
      (is (= (rules/custom-element-property-name (name early))
             (conv/custom-element-property-name early))))

    (dotimes [i beyond-the-bound]
      (conv/custom-element-property-name (keyword (str "saturation-probe-" i))))

    (testing "the early key still answers after the cache filled"
      (is (= (rules/custom-element-property-name (name early))
             (conv/custom-element-property-name early))))

    (testing "a key first asked PAST the bound answers correctly, uncached"
      (doseq [n ["past-the-bound-one" "past-the-bound-two"]]
        (let [k (keyword n)]
          (is (= (rules/custom-element-property-name n)
                 (conv/custom-element-property-name k)) n)
          ;; And again — an uncached projection is still idempotent.
          (is (= (rules/custom-element-property-name n)
                 (conv/custom-element-property-name k)) n))))))
