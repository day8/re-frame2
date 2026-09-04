(ns re-frame.story.panels-e2e.share-url-state-hydration-e2e-cljs-test
  "Regression coverage for the full shell URL-hydration sequence
  (rf2-ovb1en).

  Shell mount runs TWO URL-hydration passes, in this order
  (`re-frame.story.ui.shell/hydrate-url-state!`):

      1. rf.story.ui.url-state/hydrate-from-url!  ← authoritative + VALIDATING owner of
                                         selection / substrate / chrome slots
      2. rf.story.ui.share/hydrate-from-url!     ← owns ONLY the focused-variant
                                         cell-overrides slice + drift hint

  The bug: pass 2 used to reparse the RAW `substrate=` without registry
  validation and write it unconditionally, RESURRECTING a stale value pass
  1 had already normalised to `:reagent` (`substrate=ghost-substrate` →
  `:reagent` by pass 1 → `:ghost-substrate` again by pass 2). It also wrote
  cell-overrides only when `(seq overrides)`, so an all-stale `overrides=`
  set (which `drop-stale-overrides` collapses to nil) left the UNFILTERED
  slice pass 1 had installed live as orphan args.

  This ns drives the real two-pass sequence against the live registry +
  the production `url-state` validators, with the URL parse seam stubbed
  (jsdom `window.location` is read-only under the node runner — the
  parsers themselves are covered by `url-state-cljs-test` /
  `story-share-test`). It asserts the END state after BOTH passes:

  - an unregistered `substrate=` ends as `:reagent` (pass 2 cannot revive it);
  - an all-stale `overrides=` leaves NO `[:cell-overrides variant-id]` slice
    and still records the dropped-override drift hint;
  - a valid `substrate=` + valid `overrides=` still hydrate (no regression)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.story :as rf.story]
            [re-frame.story.test-helpers.e2e-multi-frame :as rf.story.test-helpers.e2e-multi-frame]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.ui.multi-substrate :as rf.story.ui.multi-substrate]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.ui.share :as rf.story.ui.share]
            [re-frame.story.ui.url-state :as rf.story.ui.url-state]
            [re-frame.story.share :as rf.story.share]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- production validators (mirror shell's private `url-state-apply-fn`) --
;;
;; `shell/url-state-apply-fn` is a defn- closing over the live registrar +
;; substrate registry. We reconstruct the same validator map here so the
;; test exercises url-state's REAL validation path (the part that degrades a
;; stale `substrate=` to `:reagent`) rather than a no-validator stand-in.

(defn- production-apply-fn []
  (let [validators {:variant?   (fn [vid]
                                  (or (nil? vid)
                                      (rf.story.registrar/registered? :variant vid)))
                    :substrate? (fn [s]
                                  (or (nil? s)
                                      (contains? (rf.story.ui.multi-substrate/registered-substrates) s)))}]
    (fn [state parsed]
      (rf.story.ui.url-state/apply-parsed-to-state state parsed validators))))

;; ---- drive the real two-pass shell hydration sequence -------------------

(defn- hydrate-from-url-params!
  "Run the full shell URL-hydration sequence (both passes, in shell order)
  against a `{param-name → string}` URL getter map, with the parse seam
  stubbed so it runs under the node runner. `getter` keys are the wire
  param names (\"variant\", \"substrate\", \"overrides\", ...)."
  [getter]
  (let [usp (js/URLSearchParams.
              (clj->js (into {} (filter (comp some? val) getter))))]
    (with-redefs [;; pass 1 source: url-state parses window.location.search
                  rf.story.ui.url-state/parse-current-url (constantly (rf.story.share/parse-params getter))
                  ;; pass 2 source: share reads window.location.search
                  rf.story.ui.share/current-url-params    (constantly usp)]
      ;; Pass 1 — the authoritative, validating owner.
      (rf.story.ui.url-state/hydrate-from-url! rf.story.ui.state/shell-state-atom (production-apply-fn))
      ;; Pass 2 — the cell-overrides slice + drift hint owner.
      (rf.story.ui.share/hydrate-from-url!))))

;; A variant whose declared arg surface is exactly #{:label}. An
;; `overrides=` entry for any OTHER key is stale (the variant no longer
;; declares it) and must be dropped + reported by pass 2.
(defn- register-variant! []
  (rf.story/reg-story :story.sample {:doc "Parent for the share-hydration e2e."})
  (rf.story/reg-variant :story.sample/card
    {:doc      "A card variant declaring a single :label arg."
     :argtypes {:label {:type :string}}
     :args     {:label "Hello"}}))

;; Register a second substrate so a non-default valid `substrate=` round-trips.
(defn- register-substrates! []
  (rf.story.ui.multi-substrate/register-substrate! :reagent (fn [_ _ _] [:div]))
  (rf.story.ui.multi-substrate/register-substrate! :uix     (fn [_ _ _] [:div])))

;; =========================================================================
;; (1) unregistered substrate= ends :reagent after the COMPLETE sequence
;; =========================================================================

(deftest unregistered-substrate-ends-reagent-after-full-hydration
  (testing "rf2-ovb1en — `substrate=ghost-substrate` is normalised to
            :reagent by pass 1 and pass 2 (share) MUST NOT resurrect the
            raw stale value. The end state after BOTH passes is :reagent."
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories (fn [] (register-variant!) (register-substrates!))}
      (fn []
        ;; Seed a stale in-memory substrate so a no-op would leave it dirty.
        (swap! rf.story.ui.state/shell-state-atom assoc :substrate :uix)
        (hydrate-from-url-params!
          {"variant"   "story.sample/card"
           "substrate" "ghost-substrate"})
        (is (= :reagent (:substrate (rf.story.ui.state/get-state)))
            "unregistered substrate= degrades to :reagent — pass 2 cannot
             revive the stale value it once reparsed without validation")
        (is (= :story.sample/card (:selected-variant (rf.story.ui.state/get-state)))
            "the registered variant is still focused")))))

;; =========================================================================
;; (2) all-stale overrides= leaves NO slice + records the drift hint
;; =========================================================================

(deftest all-stale-overrides-clears-slice-and-records-hint
  (testing "rf2-ovb1en — an `overrides=` set whose keys the variant no
            longer declares collapses to nil under `drop-stale-overrides`;
            the end state has NO [:cell-overrides variant-id] slice (pass 1's
            unfiltered install is cleared by pass 2) AND the share-import
            drift hint records the dropped overrides."
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories (fn [] (register-variant!) (register-substrates!))}
      (fn []
        ;; `:gone` + `:also-gone` are NOT in the variant's declared #{:label}.
        (hydrate-from-url-params!
          {"variant"   "story.sample/card"
           "overrides" (pr-str {:gone 1 :also-gone 2})})
        (let [s (rf.story.ui.state/get-state)]
          (is (= :story.sample/card (:selected-variant s)))
          (is (nil? (get-in s [:cell-overrides :story.sample/card]))
              "all-stale overrides leave NO live slice — pass 2 clears the
               unfiltered slice pass 1 installed")
          (is (= 2 (get-in s [:rf.story/share-import-hint
                              :story.sample/card :dropped-count]))
              "both stale overrides are reported in the drift hint"))))))

;; =========================================================================
;; (3) valid substrate= + valid overrides= still hydrate (no regression)
;; =========================================================================

(deftest valid-substrate-and-overrides-still-hydrate
  (testing "rf2-ovb1en — a registered non-default substrate= and a declared
            overrides= entry still hydrate after the complete sequence; the
            fix narrows pass 2, it does not break the happy path."
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories (fn [] (register-variant!) (register-substrates!))}
      (fn []
        (hydrate-from-url-params!
          {"variant"   "story.sample/card"
           "substrate" "uix"
           "overrides" (pr-str {:label "Shared"})})
        (let [s (rf.story.ui.state/get-state)]
          (is (= :uix (:substrate s))
              "a registered non-default substrate is kept (pass 1 validates,
               pass 2 leaves it alone)")
          (is (= :story.sample/card (:selected-variant s)))
          (is (= {:label "Shared"} (get-in s [:cell-overrides :story.sample/card]))
              "a declared override hydrates into the focused variant's slice")
          (is (nil? (get-in s [:rf.story/share-import-hint :story.sample/card]))
              "no drift hint when nothing was dropped"))))))
