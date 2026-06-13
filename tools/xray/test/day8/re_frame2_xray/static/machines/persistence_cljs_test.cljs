(ns day8.re-frame2-xray.static.machines.persistence-cljs-test
  "Direct slot tests for the Static Machines persistence round-trip
  (rf2-3t7rs8, finding 1).

  ## What's under test

  `panel_cljs_test.cljs` only smoke-tests the persisted slots through
  the registered subs (the default sub-mode resolves to `:topology`) —
  it never hits `load-sub-mode-by-id`, `save-sub-mode-by-id!`,
  `load-selected-id`, or `save-selected-id!` directly. The edge cases
  those four functions guard were unpinned:

    - empty / unavailable sub-mode slot
    - malformed (unparseable) EDN
    - non-map EDN
    - invalid sub-mode VALUES (normalise back to `:topology`)
    - non-keyword KEYS dropped
    - selected-id nil clear
    - selected-id namespaced-keyword round-trip

  ## Test seam

  Per the bead these are CLJS unit tests that `with-redefs` the shared
  `local-storage` seam (`get-item` / `set-item!` / `remove-item!`) over
  an in-process atom. No `js/window` / jsdom is touched — the slot
  parse + normalise logic is exercised hermetically.

  ## Adversarial posture (rf2-3t7rs8)

  The bead's finding 1 evidence flags a doc-vs-implementation tension on
  the EMPTY-slot read: the `load-sub-mode-by-id` docstring says it
  'returns `{}` when the slot is empty / unparseable', but the body
  wraps the whole read in `when-let`, so an empty slot actually returns
  `nil`. `load-empty-slot-returns-nil-not-empty-map` PINS the AS-BUILT
  behaviour (nil) and documents the asymmetry: `nil` and `{}` are
  observationally identical to every consumer (`get`/`merge`/`into`
  treat them the same, and the hydrate event seeds the slot either
  way), so the implementation is correct as written — only the
  docstring overstates. Pinning the real value keeps a future refactor
  honest about which branch fires."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.local-storage :as ls]
            [day8.re-frame2-xray.static.machines.persistence :as persistence]))

;; ---- in-process localStorage stub ---------------------------------------
;;
;; A bare atom standing in for `window.localStorage`. The shared
;; `local-storage` seam is the one place the persistence ns touches the
;; browser; redefing its three primitives over this atom lets the slot
;; round-trip run under node-test with no jsdom.

(def ^:private store (atom {}))

(defn- stub-get-item [k] (get @store k))
(defn- stub-set-item! [k v] (swap! store assoc k v) nil)
(defn- stub-remove-item! [k] (swap! store dissoc k) nil)

(use-fixtures :each
  {:before (fn [] (reset! store {}))})

(defn- with-stub-storage* [f]
  (with-redefs [ls/get-item    stub-get-item
                ls/set-item!   stub-set-item!
                ls/remove-item! stub-remove-item!]
    (f)))

;; -------------------------------------------------------------------------
;; (1) sub-mode slot — empty / unavailable
;; -------------------------------------------------------------------------

(deftest load-empty-slot-returns-nil-not-empty-map
  (testing "rf2-3t7rs8 — AS-BUILT: an empty / unavailable sub-mode slot
            reads back nil (the outer `when-let` short-circuits before
            the `{}` fallback). nil and {} are observationally identical
            to every consumer, so this pins the real branch rather than
            the docstring's overstated `{}`."
    (with-stub-storage*
      (fn []
        ;; store is empty → get-item returns nil → when-let is falsey.
        (is (nil? (persistence/load-sub-mode-by-id))
            "empty slot reads nil, not {}")))))

(deftest load-empty-string-slot-returns-nil
  (testing "rf2-3t7rs8 — a present-but-empty-string slot DOES reach the
            parse branch (the `when-let` raw is the non-nil empty
            string). `cljs.reader/read-string \"\"` returns nil (it does
            NOT throw), so the `(when (map? parsed) …)` guard fails and
            the read yields nil — not the catch-branch `{}`. Pins the
            real path: the catch `{}` only fires for genuinely
            unparseable EDN, which the next test exercises."
    (with-stub-storage*
      (fn []
        (stub-set-item! persistence/sub-mode-key "")
        (is (nil? (persistence/load-sub-mode-by-id))
            "empty string reads as nil → fails map? → nil")))))

;; -------------------------------------------------------------------------
;; (2) sub-mode slot — malformed / non-map EDN
;; -------------------------------------------------------------------------

(deftest load-malformed-edn-returns-empty-map
  (testing "rf2-3t7rs8 — unparseable EDN falls into the catch and
            returns {} rather than crashing the render"
    (with-stub-storage*
      (fn []
        (stub-set-item! persistence/sub-mode-key "{:a/b :topology")  ;; unbalanced
        (is (= {} (persistence/load-sub-mode-by-id))
            "unbalanced map literal → catch → {}")))))

(deftest load-non-map-edn-returns-nil
  (testing "rf2-3t7rs8 — parseable BUT non-map EDN (a vector / scalar)
            fails the `(when (map? parsed) …)` guard, so the read yields
            nil — the value is neither a usable map nor a crash."
    (with-stub-storage*
      (fn []
        (stub-set-item! persistence/sub-mode-key "[:m/a :topology]")
        (is (nil? (persistence/load-sub-mode-by-id))
            "vector EDN parses but fails map? → nil")
        (stub-set-item! persistence/sub-mode-key ":just-a-keyword")
        (is (nil? (persistence/load-sub-mode-by-id))
            "scalar EDN parses but fails map? → nil")))))

;; -------------------------------------------------------------------------
;; (3) sub-mode slot — value normalisation + key filtering
;; -------------------------------------------------------------------------

(deftest load-normalises-invalid-sub-mode-values
  (testing "rf2-3t7rs8 — every value normalises through
            helpers/normalise-sub-mode; an out-of-enum value falls back
            to :topology rather than riding through corrupted"
    (with-stub-storage*
      (fn []
        (persistence/save-sub-mode-by-id!
          {:m/a :bogus          ;; not in the 4-mode enum → :topology
           :m/b :sim            ;; valid → preserved
           :m/c "instances"})   ;; string form of a valid mode → :instances
        (is (= {:m/a :topology
                :m/b :sim
                :m/c :instances}
               (persistence/load-sub-mode-by-id))
            "invalid → :topology; valid kw preserved; valid string coerced")))))

(deftest load-drops-non-keyword-keys
  (testing "rf2-3t7rs8 — the `(when (keyword? k) …)` keep-guard drops
            entries whose key is not a keyword (a corrupted slot could
            carry string / numeric keys)"
    (with-stub-storage*
      (fn []
        ;; Hand-write a map with mixed key types (can't go through
        ;; save! which only round-trips what the panel produces).
        (stub-set-item! persistence/sub-mode-key
                        (pr-str {:m/a :sim, "m/b" :topology, 42 :instances}))
        (is (= {:m/a :sim} (persistence/load-sub-mode-by-id))
            "string + numeric keys dropped; only the keyword key survives")))))

(deftest load-namespaced-keyword-keys-round-trip
  (testing "rf2-3t7rs8 — namespaced machine-id keys survive the
            pr-str → read-string round-trip intact"
    (with-stub-storage*
      (fn []
        (persistence/save-sub-mode-by-id! {:foo.bar/login :instances})
        (is (= {:foo.bar/login :instances}
               (persistence/load-sub-mode-by-id))
            "fully-qualified ns keyword key preserved")))))

;; -------------------------------------------------------------------------
;; (4) sub-mode slot — save! clears on empty / nil
;; -------------------------------------------------------------------------

(deftest save-empty-or-nil-clears-the-slot
  (testing "rf2-3t7rs8 — save-sub-mode-by-id! with nil OR an empty map
            removes the slot (so a cleared selection doesn't leave a
            `{}` husk in storage)"
    (with-stub-storage*
      (fn []
        (persistence/save-sub-mode-by-id! {:m/a :sim})
        (is (contains? @store persistence/sub-mode-key)
            "non-empty map writes the slot")
        (persistence/save-sub-mode-by-id! {})
        (is (not (contains? @store persistence/sub-mode-key))
            "empty map removes the slot")
        (persistence/save-sub-mode-by-id! {:m/a :sim})
        (persistence/save-sub-mode-by-id! nil)
        (is (not (contains? @store persistence/sub-mode-key))
            "nil also removes the slot")))))

(deftest sub-mode-round-trip-survives-storage
  (testing "rf2-3t7rs8 — a valid {machine-id sub-mode} map written via
            save! reads back identical via load"
    (with-stub-storage*
      (fn []
        (let [by-id {:m/a :topology :m/b :sim :m/c :instances :m/d :cascade}]
          (persistence/save-sub-mode-by-id! by-id)
          (is (= by-id (persistence/load-sub-mode-by-id))
              "all four valid modes round-trip"))))))

;; -------------------------------------------------------------------------
;; (5) selected-id slot — save / load / clear
;; -------------------------------------------------------------------------

(deftest selected-id-round-trips-bare-keyword
  (testing "rf2-3t7rs8 — a bare (un-namespaced) machine-id keyword
            round-trips: save! stores the `name`-only string, load
            re-keywords it"
    (with-stub-storage*
      (fn []
        (persistence/save-selected-id! :login)
        (is (= "login" (get @store persistence/selection-key))
            "stored as bare name string (no leading colon)")
        (is (= :login (persistence/load-selected-id))
            "load re-keywords the stored name")))))

(deftest selected-id-round-trips-namespaced-keyword
  (testing "rf2-3t7rs8 — a namespaced machine-id keyword round-trips via
            the `ns/name` string form (the slot drops the leading colon
            but keeps the namespace)"
    (with-stub-storage*
      (fn []
        (persistence/save-selected-id! :foo.bar/login)
        (is (= "foo.bar/login" (get @store persistence/selection-key))
            "stored as ns/name (leading colon stripped)")
        (is (= :foo.bar/login (persistence/load-selected-id))
            "load reconstructs the namespaced keyword")))))

(deftest selected-id-nil-clears-the-slot
  (testing "rf2-3t7rs8 — save-selected-id! nil removes the slot; a
            subsequent load reads nil"
    (with-stub-storage*
      (fn []
        (persistence/save-selected-id! :login)
        (is (contains? @store persistence/selection-key))
        (persistence/save-selected-id! nil)
        (is (not (contains? @store persistence/selection-key))
            "nil clears the selection slot")
        (is (nil? (persistence/load-selected-id))
            "load reads nil from the cleared slot")))))

(deftest selected-id-non-keyword-is-a-no-op
  (testing "rf2-3t7rs8 — save-selected-id! with a non-nil, non-keyword
            value neither writes nor crashes (the inner `when keyword?`
            guard). Pins that a corrupted caller can't poison the slot."
    (with-stub-storage*
      (fn []
        (persistence/save-selected-id! "not-a-keyword")
        (is (not (contains? @store persistence/selection-key))
            "string value is not persisted")
        (persistence/save-selected-id! 42)
        (is (not (contains? @store persistence/selection-key))
            "numeric value is not persisted")))))

(deftest load-selected-id-empty-slot-reads-nil
  (testing "rf2-3t7rs8 — an absent selection slot reads back nil (the
            `when-let` short-circuits)"
    (with-stub-storage*
      (fn []
        (is (nil? (persistence/load-selected-id))
            "empty selection slot → nil")))))

;; -------------------------------------------------------------------------
;; (6) clear! drops BOTH slots
;; -------------------------------------------------------------------------

(deftest clear-drops-both-slots
  (testing "rf2-3t7rs8 — clear! removes the selection AND sub-mode slots
            in one call (the per-test reset hook used by the panel tests)"
    (with-stub-storage*
      (fn []
        (persistence/save-selected-id! :login)
        (persistence/save-sub-mode-by-id! {:login :sim})
        (is (= 2 (count @store)) "both slots present")
        (persistence/clear!)
        (is (empty? @store) "clear! drops both slots")))))
