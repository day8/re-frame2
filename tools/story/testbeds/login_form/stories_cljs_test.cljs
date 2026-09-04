(ns login-form.stories-cljs-test
  "Regression guard for the login-form testbed's machine-snapshot reads.

  Machine snapshots live in the frame's runtime-db partition at
  `[:rf.runtime/machines :snapshots <machine-id>]` (EP-0001). The
  login-form testbed's four projection subs (`:login/state` `:login/error`
  `:login/attempts` `:login/email`) and three `:script` checkpoints read
  the snapshot through that partition.

  This test runs each variant through `rf.story/run-variant` under the
  top-level `node-test` build (`npm run test:cljs`) and then, against the
  variant frame's live `frame-state-value` (which carries the runtime-db
  partition), computes each projection sub via `rf/compute-sub` and asserts
  it resolves the live snapshot value. `compute-sub` resolves a
  `:rf/machine`-derived sub against the frame-state value's
  `:rf.db/runtime` partition (EP-0001), so this is the faithful runtime-db
  read the views perform reactively. If a projection sub stops reading the
  runtime-db partition, these assertions go red and the CLJS gate fails
  loudly.

  Why compute-sub here rather than a `:rf.assert/sub-equals` `:script`
  checkpoint: the play-runner's `:sub-equals` evaluator computes subs
  against app-db ONLY, so a runtime-db machine projection reads nil through
  it. The variant `:script`s pin state with `:rf.assert/state-is`
  (runtime-db-aware); this CLJS test owns the `:data`-slice
  (`:error` / `:attempts` / `:email`) verification.

  The variant stories ns is required at the top — loading it fires the
  `reg-*` macros — so the side-table + the four projection subs are
  registered by the time the tests run."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [clojure.set        :as set]
            [re-frame.core      :as rf]
            [re-frame.frame     :as rf.frame]
            [re-frame.machines  :as rf.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.source-store :as rf.source-store]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story     :as rf.story]
            [re-frame.story.async      :as rf.story.async]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.test-support     :as rf.test-support]
            [login-form.events]
            [login-form.subs]
            [login-form.stories :as lf-stories]
            ;; The login EXAMPLE deck — co-loaded with this testbed under the
            ;; consolidated node-test build. Required here for the
            ;; distinct-parents regression below (rf2-tijvbd): its
            ;; `register-all!` repopulates the `:story.login/*` variants the
            ;; `before!` fixture's `clear-all!` wiped, so the disjointness
            ;; assertion sees both decks. Already on the node-test classpath
            ;; (the login-example seed test requires it too).
            [login.stories :as login-stories]))

;; ---- fixtures ------------------------------------------------------------
;;
;; Snapshot/restore the registrar around each test (same shape as the
;; counter-with-stories CLJS test): the framework-shipped events / fxs /
;; subs registered at ns-load (the machines `:rf/machine` sub, the Story
;; lifecycle machine, the login app's events / subs, the canonical
;; `:rf.assert/*` handlers) survive the snapshot; per-test registrations
;; roll back. Map-form fixture is needed for cljs.test's async bodies.
;;
;; This ns deliberately stays on this hand-rolled reset (rf.story/clear-all! +
;; register-all! + the ns-load source-store baseline restore below) rather
;; than `make-reset-runtime-fixture` — the async story tests need the
;; map-form fixture the composed Story fixture does not yet expose. See
;; `counter_with_stories/stories_cljs_test`'s fixture header for the full
;; cluster-wide rationale (rf2-y90h6h / rf2-vyzqca) and DO NOT re-attempt a
;; corpus "one-idiom" migration without addressing every point there.

(def ^:private registrar-snapshot (atom nil))

;; EP-0026 §Default Image — STABLE ns-load source-store baseline.
;;
;; Captured ONCE here at ns-load (after the `:require` chain above has fired
;; every `reg-*`, so the provenance-tagged `login-form.*` app descriptors the
;; variant frames' `:select-ns {:include ["login-form.**"]}` image selects are
;; live), NOT per-`before!`. This mirrors `re-frame.test-support/
;; make-reset-runtime-fixture`'s `source-store-baseline` (rf2-7hwnu): a per-test
;; capture is RUN-ORDER-DEPENDENT — a sibling test ns whose own fixture clears
;; the source store can leave it empty (or polluted) at the moment a per-`before!`
;; capture runs, so `login-form.**` would zero-match / under-resolve and the
;; `:login/flow` machine snapshot would read nil. Capturing the baseline once at
;; this ns's load pins exactly this ns's framework + app registrations and makes
;; each test's restore deterministic regardless of sibling run order.
(def ^:private source-store-baseline @rf.source-store/kind->id->ns->descriptor)

(defn- before! []
  (reset! registrar-snapshot (rf.test-support/snapshot-registrar))
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  (rf.frame/ensure-default-frame!)
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  ;; Re-fire the Story registrations so each test starts with a freshly
  ;; resolved registry (the four projection subs re-register here too).
  (rf.story/clear-all!)
  ;; Restore the STABLE ns-load source-store baseline so the `login-form.*` app
  ;; descriptors are present for the variant frames' image `:select-ns` (see the
  ;; baseline capture above). `rf.story/clear-all!` wiped the source store; we fold
  ;; the ns-load baseline back so each test's default/scoped projection sees
  ;; exactly this ns's registrations, run-order-independent. The resolved-
  ;; generation cache is keyed on the source-store generation, which `clear-all!`
  ;; bumped, so a stale generation never leaks.
  (reset! rf.source-store/kind->id->ns->descriptor source-store-baseline)
  ;; EP-0026 §Framework Standard Registrations: `rf.story/clear-all!` wipes the
  ;; WHOLE registrar (incl. the framework `:rf/machine` sub the projection subs
  ;; chain off via `:<- [:rf/machine :login/flow]`). Re-install the machine
  ;; runtime — both the regular registrar (so a no-generation
  ;; `rf/compute-sub [:login/state] frame-state` resolves `:rf/machine`) AND the
  ;; image standard registry (so the app-image-scoped variant frame resolves the
  ;; machine runtime through its sealed generation). Mirrors how the
  ;; `make-reset-runtime-fixture` reset re-installs it via the
  ;; `:machines/install-runtime!` hook; this custom fixture calls it directly.
  (rf.machines/install-machine-runtime!)
  (lf-stories/register-all!))

(defn- after! []
  (when-let [snap @registrar-snapshot]
    (rf.test-support/restore-registrar! snap)
    (reset! registrar-snapshot nil))
  (reset! rf.frame/frames {}))

(use-fixtures :each {:before before! :after after!})

;; ---- registrations: the four projection subs landed ----------------------

(deftest projection-subs-registered
  (testing "the four machine-snapshot projection subs registered against
            the registrar (their migration off the dead app-db path
            keeps them registered as ordinary `reg-sub`s)"
    (doseq [sub-id [:login/state :login/error :login/attempts :login/email]]
      (is (some? (rf.registrar/handler :sub sub-id)) (str sub-id " registered")))))

;; ---- each variant runs; the migrated subs resolve the snapshot -----------
;;
;; The regression guard. After `run-variant`, the variant frame (keyed by
;; the variant-id) still holds its committed state, so we compute each
;; migrated projection sub via `rf/compute-sub` against the frame's
;; `frame-state-value` — the same two-partition value `subscribe` resolves
;; reactively. `compute-sub` extracts the `:rf.db/runtime` partition for a
;; `:rf/machine`-derived sub, so a green assertion PROVES the sub reads the
;; live runtime-db snapshot. Reverting the subs to the dead app-db
;; `:rf/runtime` path makes them resolve nil → these go red.

(defn- run-then
  "Run `variant-id` and, on settle, invoke `(check result frame-state)`
  with the variant frame's live frame-state value, then tear the variant
  down and complete the async test."
  [done variant-id check]
  (-> (rf.story/run-variant variant-id)
      (rf.story.async/then
        (fn [result]
          (is (rf.story/assertions-passing? result)
              (str variant-id " play assertions (state-is etc): "
                   (pr-str (:assertions result))))
          (check result (rf/frame-state-value variant-id))
          (rf.story/destroy-variant! variant-id)
          (done)))))

(defn- sub-value
  "Compute migrated projection sub `query-v` against the variant frame's
  `frame-state` value (resolves the `:rf.db/runtime` partition for the
  `:rf/machine`-derived projection subs)."
  [query-v frame-state]
  (rf/compute-sub query-v frame-state))

(deftest idle-variant-state-sub-resolves
  (testing ":story.login-form/idle — `:login/state` resolves :idle off the
            runtime-db snapshot (and the `:rf.assert/state-is` checkpoint
            passes)"
    (async done
      (run-then done :story.login-form/idle
        (fn [_ fs]
          (is (= :idle (sub-value [:login/state] fs))
              ":login/state read the live runtime-db snapshot, not nil"))))))

(deftest submitting-variant-state-sub-resolves
  (testing ":story.login-form/submitting — `:login/state` resolves :submitting"
    (async done
      (run-then done :story.login-form/submitting
        (fn [_ fs]
          (is (= :submitting (sub-value [:login/state] fs))))))))

(deftest error-variant-error-sub-resolves
  (testing ":story.login-form/error — `:login/error` resolves the error message
            off the runtime-db snapshot (NOT nil from the dead app-db path)"
    (async done
      (run-then done :story.login-form/error
        (fn [_ fs]
          (is (= :error (sub-value [:login/state] fs)))
          (is (= "Invalid credentials." (sub-value [:login/error] fs))
              ":login/error returned the live message, not nil"))))))

(deftest submitting-retry-variant-attempts-sub-resolves
  (testing ":story.login-form/submitting-retry — `:login/attempts` resolves the
            incremented counter off the runtime-db snapshot"
    (async done
      (run-then done :story.login-form/submitting-retry
        (fn [_ fs]
          (is (= :submitting-retry (sub-value [:login/state] fs)))
          (is (= 1 (sub-value [:login/attempts] fs))
              ":login/attempts returned the live count, not the 0 default"))))))

(deftest authenticated-variant-email-sub-resolves
  (testing ":story.login-form/authenticated — `:login/email` resolves the
            submitted handle off the runtime-db snapshot"
    (async done
      (run-then done :story.login-form/authenticated
        (fn [_ fs]
          (is (= :authenticated (sub-value [:login/state] fs)))
          (is (= "ada@example.com" (sub-value [:login/email] fs))
              ":login/email returned the live email, not nil"))))))

;; ---- rf2-tijvbd: the two login decks own DISTINCT parents ----------------
;;
;; Regression guard for the parent-id collision (rf2-tijvbd). The login
;; EXAMPLE deck (examples/core/login) owns the canonical `:story.login`; this
;; testbed owns `:story.login-form`. Under the consolidated node-test build
;; BOTH decks' `(register-all!)` fire at ns-load, so a shared parent id made
;; `rf.story/variants-of` return the UNION of both (they collided on
;; `:story.login/submitting`). Co-load both decks here — the `before!` fixture
;; already fired this testbed's `register-all!`; fire the example's too — and
;; assert each `variants-of` returns ONLY its own deck's variants, with no
;; shared variant id. Membership is `(= (namespace vid) (name story-id))`
;; (rf.story/registrar), so the distinct `"story.login"` / `"story.login-form"`
;; namespaces keep the two sets disjoint. This goes RED if either deck is ever
;; re-pointed back at the other's parent id.

(def ^:private example-variant-ids
  "The login EXAMPLE's seven canonical variants (examples/core/login/stories.cljs)."
  #{:story.login/empty
    :story.login/filled
    :story.login/submitting
    :story.login/invalid-credentials
    :story.login/auth-error
    :story.login/locked-out
    :story.login/success})

(def ^:private testbed-variant-ids
  "This testbed's five variants."
  #{:story.login-form/idle
    :story.login-form/submitting
    :story.login-form/error
    :story.login-form/submitting-retry
    :story.login-form/authenticated})

(deftest login-decks-register-under-distinct-parents
  (testing "rf.story/variants-of returns ONLY each deck's own variants — the
            example and testbed login decks no longer share a parent story id
            or any variant id (rf2-tijvbd)"
    (login-stories/register-all!)   ;; example → :story.login/*
    (lf-stories/register-all!)      ;; testbed → :story.login-form/* (idempotent; fixture fired it)
    (is (= testbed-variant-ids (rf.story/variants-of :story.login-form))
        "variants-of :story.login-form is exactly this testbed's five")
    (is (= example-variant-ids (rf.story/variants-of :story.login))
        "variants-of :story.login is exactly the example's seven — no testbed variants leak in")
    (is (empty? (set/intersection (rf.story/variants-of :story.login)
                                  (rf.story/variants-of :story.login-form)))
        "no variant id is shared between the two decks")))
