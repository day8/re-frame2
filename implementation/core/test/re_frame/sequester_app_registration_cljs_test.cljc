(ns re-frame.sequester-app-registration-cljs-test
  "Provenance safety + true-no-op contract for
  `re-frame.test-support/sequester-app-registration!` (rf2-22vzb).

  BUNDLE CO-LOAD HYGIENE (rf2-h1vqa4) sequesters one app's `(kind, id)`
  registration from the live registrar AND the provenance source store so a
  co-loaded example app cannot fail another suite's default-image assembly.
  Auditing the documented no-op contract (PR #6056) exposed the singular helper
  removing `(kind, id)` from the live registrar UNCONDITIONALLY — even when the
  requested `[kind id provenance-ns]` source row was absent, or when the live
  registrar slot belonged to a SIBLING provenance namespace. Either way it
  returned nil yet clobbered another namespace's live registration and left a
  rf.registrar/source-store divergence behind.

  These rows pin the repaired contract, cross-platform (JVM + CLJS), against a
  snapshot/restore of both stores so the shared `:node-test` bundle is
  undisturbed:

    - correct `:route` capture / removal / reinstatement,
    - a mismatched kind is a true no-op,
    - an absent provenance row is a true no-op (the reported bug),
    - a same-id sibling provenance row cannot clobber the sibling's live
      registrar slot.

  See [Spec 008 §Built-in test-runner namespace] and the
  `sequester-app-registration!` docstring."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.registrar :as rf.registrar]
            [re-frame.source-store :as rf.source-store]
            [re-frame.test-support :as rf.test-support]))

;; ---- isolation: snapshot/restore BOTH stores ------------------------------
;;
;; The helper mutates the process-default registrar + source store directly, so
;; the fixture snapshots both atoms and restores them in a `finally`. That scopes
;; every registration these rows make to the case, leaving the shared node-test
;; bundle's ns-load registrations (sibling suites' reg-view'd components, routing
;; standards, …) exactly as they were.

(use-fixtures :each
  (fn [test-fn]
    (let [reg-snap @rf.registrar/kind->id->metadata
          src-snap @rf.source-store/kind->id->ns->descriptor]
      (try
        (test-fn)
        (finally
          (reset! rf.registrar/kind->id->metadata reg-snap)
          (reset! rf.source-store/kind->id->ns->descriptor src-snap))))))

;; ---- helpers --------------------------------------------------------------

(defn- reg-slot
  "The live registrar metadata for (kind, id), or nil."
  [kind id]
  (get-in @rf.registrar/kind->id->metadata [kind id]))

(defn- src-row
  "The source-store descriptor for the exact (kind, id, provenance-ns) slot,
  or nil."
  [kind id provenance-ns]
  (get-in @rf.source-store/kind->id->ns->descriptor [kind id provenance-ns]))

(defn- reg!
  "Register (kind, id) authored in `provenance-ns` through the real
  `rf.registrar/register!` path — registrar resolver map + provenance source store
  in lockstep, exactly as a `reg-*` macro would in dev. `provenance-ns` is a
  string; it is stamped into the descriptor's macro-captured `:ns` symbol so the
  source store keys the row under it. Returns the stored source descriptor."
  [kind id provenance-ns]
  (rf.registrar/register! kind id
                       {:ns         (symbol provenance-ns)
                        :handler-fn (fn [& _] [kind id provenance-ns])})
  (src-row kind id provenance-ns))

;; ---- 1. correct :route capture / removal / reinstatement ------------------

(deftest matching-route-row-captured-removed-and-reinstated
  (testing "a present matching (kind, id, provenance-ns) :route row is captured,
            removed from BOTH stores, returned, and reinstated exactly once by
            reinstate-app-registration! (rf2-22vzb acceptance 3)"
    (reg! :route :rf2-22vzb/not-found-route "rf2-22vzb.app-a")
    (let [captured (rf.test-support/sequester-app-registration!
                     :route :rf2-22vzb/not-found-route "rf2-22vzb.app-a")]
      (is (some? captured)
          "the captured source descriptor is returned")
      (is (= "rf2-22vzb.app-a" (get captured rf.source-store/provenance-ns-key))
          "the captured row carries app-a's provenance")
      (is (nil? (reg-slot :route :rf2-22vzb/not-found-route))
          "removed from the live registrar")
      (is (nil? (src-row :route :rf2-22vzb/not-found-route "rf2-22vzb.app-a"))
          "removed from the source store")
      ;; Reinstate — registrar + source store back in lockstep.
      (rf.test-support/reinstate-app-registration! captured)
      (is (some? (reg-slot :route :rf2-22vzb/not-found-route))
          "reinstated into the live registrar")
      (is (= captured (src-row :route :rf2-22vzb/not-found-route "rf2-22vzb.app-a"))
          "reinstated into the source store, byte-for-byte the captured row"))))

;; ---- 2. mismatched kind is a true no-op -----------------------------------

(deftest mismatched-kind-is-true-no-op
  (testing "sequestering under a KIND the id was never registered under returns
            nil and leaves the real (kind, id) registration untouched — no slot
            is fabricated or clobbered (rf2-22vzb acceptance 4)"
    (reg! :route :rf2-22vzb/mismatch-probe "rf2-22vzb.app-a")
    (let [reg-before (reg-slot :route :rf2-22vzb/mismatch-probe)
          src-before (src-row :route :rf2-22vzb/mismatch-probe "rf2-22vzb.app-a")
          returned   (rf.test-support/sequester-app-registration!
                       :sub :rf2-22vzb/mismatch-probe "rf2-22vzb.app-a")]
      (is (nil? returned)
          "no :sub source row for the id → returns nil")
      (is (= reg-before (reg-slot :route :rf2-22vzb/mismatch-probe))
          "the real :route registrar slot is untouched")
      (is (= src-before (src-row :route :rf2-22vzb/mismatch-probe "rf2-22vzb.app-a"))
          "the real :route source row is untouched")
      (is (nil? (reg-slot :sub :rf2-22vzb/mismatch-probe))
          "no :sub slot was fabricated"))))

;; ---- 3. absent provenance row is a true no-op (the reported bug) ----------

(deftest absent-provenance-row-is-true-no-op
  (testing "sequestering a (kind, id) under a provenance-ns that never registered
            it changes NEITHER store and returns nil — the reported clobber where
            `missing.app` wiped `app.b`'s live registration while leaving its
            source row behind (rf2-22vzb acceptance 1)"
    (reg! :route :rf2-22vzb/audit-same "rf2-22vzb.app-b")
    (let [reg-before (reg-slot :route :rf2-22vzb/audit-same)
          src-before (src-row :route :rf2-22vzb/audit-same "rf2-22vzb.app-b")
          returned   (rf.test-support/sequester-app-registration!
                       :route :rf2-22vzb/audit-same "rf2-22vzb.missing-app")]
      (is (nil? returned)
          "absent provenance row → returns nil")
      (is (some? reg-before)
          "precondition: app-b's registration is live before the no-op call")
      (is (= reg-before (reg-slot :route :rf2-22vzb/audit-same))
          "the live registrar still holds app-b's registration — NOT clobbered")
      (is (= src-before (src-row :route :rf2-22vzb/audit-same "rf2-22vzb.app-b"))
          "app-b's source-store row survives — no rf.registrar/source divergence"))))

;; ---- 4. same-id sibling provenance cannot clobber the sibling -------------

(deftest sibling-provenance-registration-not-clobbered
  (testing "when two namespaces register the SAME (kind, id), sequestering one
            provenance-ns forgets ONLY its own source row and never removes the
            live registrar slot owned by the SIBLING — registrar and source-store
            authority remain coherent (rf2-22vzb acceptance 2)"
    ;; app-a registers first, app-b second → the registrar's single
    ;; (kind, id) resolver slot is app-b's (last writer); the source store keeps
    ;; BOTH provenance rows.
    (reg! :route :rf2-22vzb/shared-id "rf2-22vzb.app-a")
    (reg! :route :rf2-22vzb/shared-id "rf2-22vzb.app-b")
    (is (some? (src-row :route :rf2-22vzb/shared-id "rf2-22vzb.app-a"))
        "precondition: app-a's source row present")
    (is (some? (src-row :route :rf2-22vzb/shared-id "rf2-22vzb.app-b"))
        "precondition: app-b's source row present (sibling)")
    (let [captured (rf.test-support/sequester-app-registration!
                     :route :rf2-22vzb/shared-id "rf2-22vzb.app-a")]
      (is (some? captured)
          "app-a's own present row is captured + returned")
      (is (= "rf2-22vzb.app-a" (get captured rf.source-store/provenance-ns-key))
          "the captured row is app-a's, not the sibling's")
      (is (some? (reg-slot :route :rf2-22vzb/shared-id))
          "app-b's live registrar slot is NOT clobbered by app-a's sequester")
      (is (nil? (src-row :route :rf2-22vzb/shared-id "rf2-22vzb.app-a"))
          "app-a's own source row is forgotten")
      (is (some? (src-row :route :rf2-22vzb/shared-id "rf2-22vzb.app-b"))
          "app-b's source row survives — rf.registrar/source authority coherent"))))
