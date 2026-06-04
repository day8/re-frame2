(ns re-frame.warn-once-clear-governance-cljs-test
  "Governance gate for the adapter/views warn-once `defonce` cache class
  (rf2-z79p8) — the class that has now bitten FOUR times:

    * rf2-4edk  — `warned-non-dom-roots` (the source-coord / non-DOM-root
                  cache) was left out of the fixture-reset chain.
    * rf2-9hoos — `seen-render-keys` (the :mount? discriminator's set).
    * rf2-qy6cl — the slim hiccup interpreter's `warned-keyword-prop`.
    * rf2-z79p8 — `warned-plain-fn-frame-pairs` (the Spec 004 plain-fn-
                  under-non-default-frame suppression set) — the 4th
                  straggler, chained by THIS change.

  Each recurrence was the SAME defect: a process-wide `defonce` warn-once
  cache whose clear-fn was published standalone but NEVER chained into the
  canonical `:adapter/clear-warn-once-caches!` hook that
  `make-reset-runtime-fixture` fires — so the standard fixture silently
  failed to re-arm it between tests, and a sibling test's first-encounter
  warning could swallow a later same-key warning.

  rf2-z79p8 ENDS the class. Every contributor now enrols through the
  single chokepoint `re-frame.late-bind/register-warn-once-clear-fn!`,
  which both chains the clear-fn AND records the cache (with `:arm` /
  `:armed?` probes where the cache atom is in scope) in the
  `warn-once-clear-registry`. This test enumerates that registry and
  proves, empirically, that firing the canonical chain ONCE wipes every
  registered cache. A future 5th cache that registers but forgets the
  chain (impossible through the chokepoint, but a bare `chain-fn!` could
  still drift) — or that escapes the chokepoint entirely — is caught:

    * the empirical assertion arms every probe-carrying cache, fires the
      chain, and asserts each is wiped → a registered-but-unchained cache
      survives the chain and FAILS;
    * the would-fail negative proof registers a SYNTHETIC unchained cache
      directly into the registry (NOT through the chokepoint) and shows
      the same assertion catches it — proving the gate has teeth.

  The companion source-enumeration assertion lives in the JVM test
  `re-frame.warn-once-clear-governance-test` (it greps every `defonce`
  warn-once cache out of source and asserts each is routed through the
  chokepoint) — that layer catches a 5th cache that never registers at
  all.

  Loading the four adapter/views producer namespaces below populates the
  registry at ns-load (each adapter's `make-react-adapter` /
  `make-ratom-adapter` runs its enrolment at require time).

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.set :as set]
            [re-frame.late-bind :as late-bind]
            ;; load-bearing requires — each populates the warn-once-clear
            ;; registry at ns-load:
            ;;   re-frame.views          → seen-render-keys
            ;;   re-frame.views.warn-once → warned-non-dom-roots
            ;;                              + warned-plain-fn-frame-pairs
            ;;   re-frame.adapter.uix     → the React-hook spine's per-
            ;;                              adapter source-coord cache
            ;;                              (make-react-adapter enrols it)
            ;;   re-frame.adapter.reagent-slim → warned-keyword-prop
            [re-frame.views]
            [re-frame.views.warn-once]
            [re-frame.adapter.uix]
            [re-frame.adapter.reagent-slim]))

;; ---------------------------------------------------------------------------
;; Enumeration — every named cache of the class is enrolled
;; ---------------------------------------------------------------------------

(def ^:private expected-labels
  "Every warn-once cache of the rf2-4edk/9hoos/qy6cl/z79p8 class that MUST
  be enrolled in the registry (and therefore chained). A 5th cache that
  forgets to enrol trips the source-enumeration JVM assertion; one that
  enrols under a new label lands here once Mike/the reviewer adds it."
  #{:views/warned-non-dom-roots
    :views/warned-plain-fn-frame-pairs
    :views/seen-render-keys
    :adapter/warned-non-dom-roots          ;; the React-hook spine's per-adapter cache
    :reagent-slim/warned-keyword-prop})

(deftest registry-enrols-every-named-cache-of-the-class
  (testing "the warn-once-clear governance registry enrols every named
            cache of the rf2-z79p8 class (incl. the 4th straggler
            :views/warned-plain-fn-frame-pairs)"
    (let [enrolled (set (map :label @late-bind/warn-once-clear-registry))
          missing  (set/difference expected-labels enrolled)]
      (is (empty? missing)
          (str "these warn-once caches are NOT enrolled in the governance "
               "registry (so they are NOT chained into "
               ":adapter/clear-warn-once-caches! and the standard fixture "
               "will not re-arm them — the rf2-z79p8 defect class): "
               (pr-str missing)
               ". Enrolled labels: " (pr-str enrolled))))))

;; ---------------------------------------------------------------------------
;; Empirical proof — firing the canonical chain wipes every enrolled cache
;; ---------------------------------------------------------------------------

(defn- probed-entries
  "Registry entries that carry both :arm and :armed? probes — the ones the
  empirical arm/fire/assert-empty check can drive. (Probe-less entries —
  e.g. the slim keyword-prop cache, whose atom is private behind the
  reagent2.* bundle-isolation boundary — are covered by the enrolment
  enumeration above + the source-enumeration JVM assertion + their own
  dedicated re-arm test.)"
  []
  (filter (fn [{:keys [arm armed?]}] (and (fn? arm) (fn? armed?)))
          @late-bind/warn-once-clear-registry))

(deftest canonical-chain-wipes-every-enrolled-cache
  (testing "arming every probe-carrying warn-once cache, then firing the
            canonical :adapter/clear-warn-once-caches! chain ONCE, wipes
            ALL of them — proving each enrolled cache is genuinely a member
            of the chain the standard fixture drives (rf2-z79p8)"
    (let [entries (probed-entries)
          chain   (late-bind/get-fn :adapter/clear-warn-once-caches!)]
      (is (seq entries)
          "precondition: at least one probe-carrying cache is enrolled")
      (is (some? chain)
          "precondition: the canonical :adapter/clear-warn-once-caches! chain is registered")
      ;; Arm every cache so each :armed? is true.
      (doseq [{:keys [arm]} entries] (arm))
      (doseq [{:keys [label armed?]} entries]
        (is (true? (boolean (armed?)))
            (str "precondition: " label " is armed before the chain fires")))
      ;; Fire the canonical chain ONCE.
      (chain)
      ;; Every cache must now be wiped.
      (doseq [{:keys [label armed?]} entries]
        (is (false? (boolean (armed?)))
            (str label " survived the canonical :adapter/clear-warn-once-"
                 "caches! chain — its clear-fn is enrolled in the registry "
                 "but NOT actually wired into the chain (the rf2-z79p8 "
                 "defect class). The standard make-reset-runtime-fixture "
                 "will not re-arm it between tests."))))))

;; ---------------------------------------------------------------------------
;; Re-arm of warned-plain-fn-frame-pairs — the 4th straggler (rf2-z79p8)
;; ---------------------------------------------------------------------------

(defn- plain-fn-entry []
  (some (fn [e] (when (= :views/warned-plain-fn-frame-pairs (:label e)) e))
        @late-bind/warn-once-clear-registry))

(deftest plain-fn-cache-re-arms-via-the-chain
  (testing "the warned-plain-fn-frame-pairs cache (Spec 004 plain-fn-under-
            non-default-frame suppression set) re-arms when the chained
            :adapter/clear-warn-once-caches! hook fires — the rf2-z79p8 fix.
            Before this change the cache was NOT chained, so the standard
            fixture left a sibling test's pair in the set and a later same-
            pair warning was silently swallowed. This drives the REAL cache
            through the REAL chain via its registry probes (the production
            seam), mirroring the chained-clear re-arm in
            template_keyword_prop_warn_once_clear_cljs_test (rf2-qy6cl) and
            assert-chained-clear-warn-once-empties-cache (rf2-e54wc)."
    (let [{:keys [arm armed?]} (plain-fn-entry)
          chain (late-bind/get-fn :adapter/clear-warn-once-caches!)]
      (is (and (fn? arm) (fn? armed?))
          "the plain-fn cache is enrolled with arm/armed? probes")
      ;; Phase 1 — a pair is recorded (the production code does this the
      ;; first time a plain fn warns; once recorded the SAME pair is
      ;; suppressed). Simulate via the production arm seam.
      (arm)
      (is (true? (boolean (armed?)))
          "phase 1: the pair is in the suppression set (a same-pair warning would now be swallowed)")
      ;; The chained fixture-reset hook fires (what make-reset-runtime-
      ;; fixture does between every test).
      (chain)
      ;; Phase 2 — the cache is empty again: the same pair would warn
      ;; AFRESH, no longer swallowed. This is the re-arm the 4th straggler
      ;; was missing before rf2-z79p8.
      (is (false? (boolean (armed?)))
          (str "phase 2: AFTER the chained :adapter/clear-warn-once-caches! "
               "hook fires, the warned-plain-fn-frame-pairs cache MUST be "
               "empty so the same pair warns again (it was silently "
               "swallowed before rf2-z79p8 chained this cache)")))))

;; ---------------------------------------------------------------------------
;; Negative proof — the gate has teeth (a registered-but-unchained cache
;; is caught)
;; ---------------------------------------------------------------------------

(deftest governance-gate-catches-an-unchained-cache
  (testing "PROOF the gate would FAIL for a 5th cache that registers but is
            NOT chained: inject a SYNTHETIC entry straight into the registry
            (bypassing register-warn-once-clear-fn!, so it never reaches the
            chain), arm it, fire the canonical chain, and confirm the same
            arm/fire/assert-empty logic the gate uses flags it as surviving
            (rf2-z79p8). The synthetic entry is removed afterwards so the
            real gate stays green."
    (let [survivor (atom #{})
          synthetic {:label    :rf-test/unchained-synthetic-cache
                     ;; NOTE: this clear-fn is NEVER chained — the entry is
                     ;; conj'd directly, NOT via register-warn-once-clear-fn!.
                     :clear-fn (fn [] (reset! survivor #{}) nil)
                     :arm      (fn [] (swap! survivor conj ::sentinel))
                     :armed?   (fn [] (contains? @survivor ::sentinel))}]
      (try
        (swap! late-bind/warn-once-clear-registry conj synthetic)
        (let [{:keys [arm armed?]} synthetic
              chain (late-bind/get-fn :adapter/clear-warn-once-caches!)]
          (arm)
          (is (true? (boolean (armed?))) "synthetic cache armed")
          (chain)
          ;; The chain does NOT clear it (it was never wired in) — exactly
          ;; what the empirical gate flags as a failure for a real cache.
          (is (true? (boolean (armed?)))
              "the synthetic UNCHAINED cache survives the canonical chain —
               this is the failure the empirical gate raises for any real
               cache that registers but forgets the chain. The gate has
               teeth.")
          ;; And confirm that, by contrast, a properly-enrolled cache IS
          ;; cleared by the same chain fire (so the gate distinguishes the
          ;; two — it isn't vacuously green).
          (let [{real-arm :arm real-armed? :armed?} (plain-fn-entry)]
            (real-arm)
            ;; fire again so both synthetic and the real cache see the chain
            (chain)
            (is (false? (boolean (real-armed?)))
                "by contrast, a properly-enrolled cache IS wiped by the chain")))
        (finally
          ;; Drop the synthetic entry so the real gate above stays clean.
          (swap! late-bind/warn-once-clear-registry
                 (fn [reg] (vec (remove #(= :rf-test/unchained-synthetic-cache (:label %)) reg)))))))))
