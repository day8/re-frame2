(ns re-frame.epoch-redact-fn-projection-test
  "Per rf2-wp70d.3 — composition smoke between the build-time
  `:redact-fn` hook (rf2-wp70d.2) and the off-box egress projection
  (`projected-record` / `projected-history` — rf2-mrsck). The impl bead
  pins each surface's direct contract; this file pins the cross-surface
  contract — they MUST compose without double-walk corruption,
  consumers of the projection over a redacted record see a stable
  shape, and the `:rf.epoch/sensitive?` rollup remains a trustworthy
  signal regardless of redaction.

  Coverage matrix (per ai/findings/epoch-redaction-hook-design-2026-05-17.md
  §Bead 3):

    A. The four payload slots (`:db-before`, `:db-after`,
       `:trigger-event`, `:trace-events`) survive both passes
       (redact-fn first, projection second).

    B. Redact-fn replaces a whole slot with `:rf/redacted` (scalar
       sentinel) — `elide-wire-value` passes scalar sentinels through
       unchanged at the projection pass; double-projection is
       idempotent.

    C. Redact-fn returns a partially-redacted map (e.g. one path in
       `:db-after` sentinel'd) — the projection further redacts
       schema-declared sensitive paths against the SAME map; the
       sentinel'd leaf stays the sentinel, the schema-declared leaves
       become `:rf/redacted` too; no double-walk corruption.

    D. Composition with the `:rf.epoch/sensitive?` rollup: the rollup
       reflects RAW signals (computed pre-redaction inside
       `build-record`), so a record with every sensitive path
       sentinel'd still carries the correct rollup. The projection
       preserves the rollup verbatim.

    E. `projected-record` against a nil-slot record (halted-destroy
       path) — the redact-fn ran (per the wiring tests in
       epoch_redact_fn_test.clj), the projection still handles the
       nil-slot shape cleanly.

    F. `projected-history` over a mixed ring (some records redacted,
       some not — in practice the redact-fn is stateless and runs
       against every cascade, but the test pins the iteration shape
       and order even when some records carry sentinels and some
       don't).

  Idempotence claim being pinned by this file: `projected-record` is
  idempotent in the composition sense — applying it to an
  already-projected (or already-redacted) record returns a
  structurally-equal value, because `:rf/redacted` is `elide-wire-value`'s
  own substitution target.

  redact-fn call-count contract per epoch_redact_fn_test invariant-2:
  the redact-fn fires EXACTLY once per cascade between `build-record`
  and ring-append / listener fan-out — `projected-record` is a pure
  data transform that does NOT re-invoke the redact-fn. This file pins
  that property by counting invocations across mixed cascades and
  multiple projection calls."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.epoch :as epoch]
            [re-frame.epoch.state :as state]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.privacy :as privacy]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            ;; Side-effect requires (mirror epoch_test.clj fixture).
            [re-frame.machines]))

;; ---- fixtures --------------------------------------------------------------

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (when-let [li-var (resolve 're-frame.flows/last-inputs)]
    (reset! (deref li-var) {}))
  (trace/clear-trace-cbs!)
  (epoch/clear-history!)
  (epoch/clear-epoch-cbs!)
  (reset! @#'state/config {:depth 50 :trace-events-keep 5 :redact-fn nil})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers ---------------------------------------------------------------

(defn- last-record [frame-id]
  (last (rf/epoch-history frame-id)))

(defn- install-sensitive-schema!
  "Register a `[:auth :password]` sensitive schema slot against
  `frame-id` and force the elision registry population."
  [frame-id]
  (rf/reg-app-schema [:auth]
                     [:map [:password {:sensitive? true} :string]]
                     {:frame frame-id})
  (rf/populate-sensitive-from-schemas! frame-id)
  nil)

(defn- install-two-sensitive-paths-schema!
  "Register two sensitive paths against `frame-id` — `[:auth :password]`
  and `[:auth :token]`. Used by tests pinning the composition rule that
  a redact-fn substituting ONE path still leaves the schema-declared
  walker to substitute the OTHER path on the projection pass."
  [frame-id]
  (rf/reg-app-schema [:auth]
                     [:map
                      [:password {:sensitive? true} :string]
                      [:token    {:sensitive? true} :string]]
                     {:frame frame-id})
  (rf/populate-sensitive-from-schemas! frame-id)
  nil)

;; ============================================================================
;;  A. Four payload slots survive both passes
;; ============================================================================

(deftest A-all-four-payload-slots-survive-redact-then-project
  (testing "redact-fn sentinels every payload slot at build-time;
            projected-record runs over the resulting record without
            corruption. All four slots remain in the projected shape;
            scalar sentinels in non-projected leaves pass through."
    (rf/reg-frame :test/main {})
    (rf/configure :epoch-history
                  {:redact-fn (fn [r]
                                ;; Sentinel every payload slot wholesale.
                                (-> r
                                    (assoc :db-before     :rf/redacted)
                                    (assoc :db-after      :rf/redacted)
                                    (assoc :trigger-event :rf/redacted)
                                    (assoc :trace-events  :rf/redacted)))})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [r         (last-record :test/main)
          projected (epoch/projected-record r)]
      (is (= :rf/redacted (:db-before r)))
      (is (= :rf/redacted (:db-after r)))
      (is (= :rf/redacted (:trigger-event r)))
      (is (= :rf/redacted (:trace-events r)))

      (testing "projection over wholly-sentinel'd payload slots is a
                no-op walk — scalar :rf/redacted passes through
                elide-wire-value unchanged"
        (is (= :rf/redacted (:db-before projected)))
        (is (= :rf/redacted (:db-after projected)))
        (is (= :rf/redacted (:trigger-event projected)))
        (is (= :rf/redacted (:trace-events projected))))

      (testing "projection is structurally idempotent in this shape"
        (is (= projected (epoch/projected-record projected)))))))

;; ============================================================================
;;  B. Scalar :rf/redacted sentinel passes through projection unchanged
;; ============================================================================

(deftest B-scalar-sentinel-passes-through-projection
  (testing "the projection's walker treats :rf/redacted as a plain
            scalar — it does not unwrap, substitute, or warn on it.
            Calling projected-record twice produces structural
            equality (= projected1 projected2)."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/configure :epoch-history
                  {:redact-fn (fn [r]
                                (cond-> r
                                  (get-in r [:db-after :auth :password])
                                  (assoc-in [:db-after :auth :password]
                                            :rf/redacted)))})
    (rf/reg-event-db :login
                     (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [r          (last-record :test/main)
          projected  (epoch/projected-record r)
          projected2 (epoch/projected-record projected)]
      (is (= :rf/redacted (get-in r [:db-after :auth :password]))
          "redact-fn produced the sentinel at build-time")
      (is (= :rf/redacted (get-in projected [:db-after :auth :password]))
          "projection over an already-sentinel leaf leaves it as the
           sentinel (the projection's own target value)")
      (is (= projected projected2)
          "second projection pass returns a structurally-equal value —
           :rf/redacted is the projection's fixpoint"))))

(deftest B-projected-record-does-not-invoke-redact-fn
  (testing "projected-record is a pure data transform — it does NOT
            re-invoke the configured :redact-fn. Multiple projection
            calls over the same record produce no extra redact-fn
            invocations."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (let [invocations (atom 0)]
      (rf/configure :epoch-history
                    {:redact-fn (fn [r]
                                  (swap! invocations inc)
                                  (cond-> r
                                    (get-in r [:db-after :auth :password])
                                    (assoc-in [:db-after :auth :password]
                                              :rf/redacted)))})
      (rf/reg-event-db :login
                       (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))
      (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

      (is (= 1 @invocations)
          "one cascade = one redact-fn invocation (the build-time pass)")

      (let [r (last-record :test/main)]
        (dotimes [_ 5] (epoch/projected-record r))
        (is (= 1 @invocations)
            "five projection calls did NOT re-invoke the redact-fn —
             projected-record is a pure data transform")))))

;; ============================================================================
;;  C. Partial redaction + schema-declared sensitive paths compose
;; ============================================================================

(deftest C-partial-redact-then-schema-redact-no-double-walk
  (testing "redact-fn replaces ONE schema-declared sensitive path with
            the sentinel; the projection then walks the same map and
            redacts the OTHER schema-declared sensitive path against
            the SAME parent map. No double-walk corruption — both
            leaves land as :rf/redacted, the rest of the map is
            structurally unchanged."
    (rf/reg-frame :test/main {})
    (install-two-sensitive-paths-schema! :test/main)
    ;; Redact-fn substitutes only :password. The projection's
    ;; schema-driven walker should then substitute :token too.
    (rf/configure :epoch-history
                  {:redact-fn (fn [r]
                                (cond-> r
                                  (get-in r [:db-after :auth :password])
                                  (assoc-in [:db-after :auth :password]
                                            :rf/redacted)))})
    (rf/reg-event-db :login
                     (fn [db [_ pw tk]]
                       (-> db
                           (assoc-in [:auth :password] pw)
                           (assoc-in [:auth :token]    tk)
                           (assoc-in [:public :name]   "alice"))))
    (rf/dispatch-sync [:login "topsecret" "tok-xyz"] {:frame :test/main})

    (let [r         (last-record :test/main)
          projected (epoch/projected-record r)]
      (is (= :rf/redacted (get-in r [:db-after :auth :password]))
          "redact-fn sentinel'd :password at build-time")
      (is (= "tok-xyz" (get-in r [:db-after :auth :token]))
          ":token survived the redact-fn (raw at the ring boundary)")

      (is (= :rf/redacted (get-in projected [:db-after :auth :password]))
          "projection leaves the already-sentinel :password alone")
      (is (= :rf/redacted (get-in projected [:db-after :auth :token]))
          "projection substitutes :token via the schema-declared path —
           the partial redact-fn output composes with the schema-driven
           walker without conflict")
      (is (= "alice" (get-in projected [:db-after :public :name]))
          "non-sensitive sibling path passes through both walkers
           unchanged — no structural corruption from the composition")

      (testing "second projection pass is a no-op (idempotent fixpoint)"
        (is (= projected (epoch/projected-record projected)))))))

;; ============================================================================
;;  D. Rollup reflects raw signals — projection preserves it
;; ============================================================================

(deftest D-sensitive-rollup-survives-redact-then-project
  (testing "the :rf.epoch/sensitive? rollup is computed inside
            build-record from RAW signals (per invariant 1 of the
            impl bead). After the redact-fn erases the leaves and the
            projection further sentinels schema-declared paths, the
            rollup MUST still read true — both passes preserve the
            bookkeeping slot."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/configure :epoch-history
                  {:redact-fn (fn [r]
                                (cond-> r
                                  (get-in r [:db-after :auth :password])
                                  (assoc-in [:db-after :auth :password]
                                            :rf/redacted)))})
    (rf/reg-event-db :login
                     (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [r         (last-record :test/main)
          projected (epoch/projected-record r)]
      (is (true? (:rf.epoch/sensitive? r))
          "rollup is true — ran from the RAW signal pre-redact-fn")
      (is (true? (:rf.epoch/sensitive? projected))
          "projection preserves the rollup verbatim — the bookkeeping
           slot passes through both walkers"))))

(deftest D-rollup-preserved-when-redact-fn-erases-everything
  (testing "even when the redact-fn sentinels every payload slot
            wholesale (no surviving leaf for the schema-driven walker
            to find), the rollup that was computed BEFORE the redact-fn
            ran still carries the correct truth value, and the
            projection preserves it"
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/configure :epoch-history
                  {:redact-fn (fn [r]
                                (-> r
                                    (assoc :db-before    :rf/redacted)
                                    (assoc :db-after     :rf/redacted)
                                    (assoc :trace-events :rf/redacted)))})
    (rf/reg-event-db :login
                     (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [r         (last-record :test/main)
          projected (epoch/projected-record r)]
      (is (true? (:rf.epoch/sensitive? r))
          "rollup keyed on raw db-after BEFORE the redact-fn wiped it")
      (is (= :rf/redacted (:db-after r))
          "wholesale erasure landed at build-time")
      (is (true? (:rf.epoch/sensitive? projected))
          "projection preserves the truthful rollup over an erased
           payload — off-box consumers can still branch correctly"))))

(deftest D-redacted-sentinel-uses-privacy-namespace
  (testing "the sentinel value the redact-fn substitutes is the SAME
            value the projection's walker uses — privacy/redacted-sentinel.
            Pinning the identity prevents future divergence (a separate
            'epoch-redacted' sentinel would break the
            idempotency story)."
    (is (= :rf/redacted privacy/redacted-sentinel)
        "privacy/redacted-sentinel is the keyword :rf/redacted")))

;; ============================================================================
;;  rf2-dl3gx — :rf.epoch/redacted-modified-paths-count on the record
;; ============================================================================
;;
;; Per Spec-Schemas §`:rf/epoch-record` and Security.md §Epoch privacy
;; posture: the framework annotates each assembled record with an
;; integer count of schema-declared sensitive paths whose value differs
;; between :db-before and :db-after. Computed inside `build-record`
;; from RAW values BEFORE the redact-fn runs, parallel to the
;; :rf.epoch/sensitive? rollup. Closes the "redact-fn ⇒ empty diff but
;; something changed" gap by surfacing the count Causa's chip needs.
;;
;; Coverage matrix:
;;   G1. No sensitive paths declared → 0.
;;   G2. Sensitive path declared but value unchanged → 0.
;;   G3. Sensitive path declared and value changed → 1; rollup also true.
;;   G4. Multiple sensitive paths; some changed, some not → count.
;;   G5. Counter is computed BEFORE redact-fn (= reflects RAW signal
;;       even when the fn erases the leaf it keyed on).
;;   G6. Projection passes the counter through unchanged.
;;   G7. Halted record (nil :db-after) — counter handles the nil edge.

(deftest G1-no-sensitive-paths-yields-zero-count
  (testing "with no schema-declared sensitive paths registered, the
            counter is 0 (the empty-paths short-circuit). Mirrors the
            common case for apps without a privacy posture."
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})

    (let [r (last-record :test/main)]
      (is (= 0 (:rf.epoch/redacted-modified-paths-count r))
          "no declarations → 0, regardless of how much the db changed"))))

(deftest G2-sensitive-path-unchanged-yields-zero-count
  (testing "a sensitive path is declared but its value did NOT change
            across the cascade — the counter is 0 (the value-equality
            predicate filters it out). A non-sensitive sibling change
            does not lift the counter."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event-db :seed (fn [_ _]
                             {:auth   {:password "topsecret"}
                              :public {:counter 0}}))
    (rf/reg-event-db :touch-public
                     (fn [db _] (update-in db [:public :counter] inc)))
    (rf/dispatch-sync [:seed]         {:frame :test/main})
    (rf/dispatch-sync [:touch-public] {:frame :test/main})

    (let [r (last-record :test/main)]
      (is (= 0 (:rf.epoch/redacted-modified-paths-count r))
          ":auth :password value identical pre/post — counter = 0
           even though a sibling slot changed"))))

(deftest G3-sensitive-path-modified-yields-positive-count
  (testing "a sensitive path's value changed across the cascade — the
            counter is 1. The :rf.epoch/sensitive? rollup also reads
            true (both signals fire from the same schema-declared
            registry)."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event-db :login
                     (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [r (last-record :test/main)]
      (is (= 1 (:rf.epoch/redacted-modified-paths-count r))
          ":auth :password mutated nil → \"topsecret\" — count = 1")
      (is (true? (:rf.epoch/sensitive? r))
          "rollup also true — both signals key on the same registry"))))

(deftest G4-multiple-sensitive-paths-partial-modification
  (testing "two sensitive paths declared; one changes, the other does
            not — the counter is 1 (the unchanged path filters out via
            value-equality)."
    (rf/reg-frame :test/main {})
    (install-two-sensitive-paths-schema! :test/main)
    (rf/reg-event-db :seed
                     (fn [_ _]
                       {:auth {:password "pw-1" :token "tk-1"}}))
    (rf/reg-event-db :rotate-token
                     (fn [db [_ tk]] (assoc-in db [:auth :token] tk)))
    (rf/dispatch-sync [:seed]                    {:frame :test/main})
    (rf/dispatch-sync [:rotate-token "tk-fresh"] {:frame :test/main})

    (let [r (last-record :test/main)]
      (is (= 1 (:rf.epoch/redacted-modified-paths-count r))
          ":token changed (count += 1); :password unchanged (filtered)")))

  (testing "both declared paths change in the same cascade — counter
            is 2 (paths counted independently)"
    (rf/reg-frame :test/main {})
    (install-two-sensitive-paths-schema! :test/main)
    (rf/reg-event-db :login-both
                     (fn [db [_ pw tk]]
                       (-> db
                           (assoc-in [:auth :password] pw)
                           (assoc-in [:auth :token]    tk))))
    (rf/dispatch-sync [:login-both "topsecret" "tok-xyz"]
                      {:frame :test/main})

    (let [r (last-record :test/main)]
      (is (= 2 (:rf.epoch/redacted-modified-paths-count r))
          "both :password and :token changed — count = 2"))))

(deftest G5-counter-reflects-raw-signal-pre-redact-fn
  (testing "the counter is computed from RAW db values BEFORE the
            redact-fn runs (parallel to :rf.epoch/sensitive? rollup
            invariant 1 of the impl bead). Even when the redact-fn
            erases the leaves wholesale, the counter on the assembled
            record carries the truthful figure."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/configure :epoch-history
                  {:redact-fn (fn [r]
                                ;; Wipe :db-before / :db-after entirely
                                ;; — if the counter were computed after,
                                ;; it would read 0 (both sides equal
                                ;; sentinels). The framework computes
                                ;; pre-redact-fn, so the truthful 1
                                ;; survives.
                                (-> r
                                    (assoc :db-before :rf/redacted)
                                    (assoc :db-after  :rf/redacted)))})
    (rf/reg-event-db :login
                     (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [r (last-record :test/main)]
      (is (= :rf/redacted (:db-before r))
          "redact-fn ran — :db-before is the sentinel")
      (is (= :rf/redacted (:db-after  r))
          "redact-fn ran — :db-after is the sentinel")
      (is (= 1 (:rf.epoch/redacted-modified-paths-count r))
          "counter reads from RAW signal — :auth :password mutated
           BEFORE the fn erased the leaves; figure survives the
           erasure"))))

(deftest G6-projection-preserves-counter
  (testing "projected-record passes :rf.epoch/redacted-modified-paths-count
            through unchanged — the integer is structurally non-sensitive
            bookkeeping, parallel to :rf.epoch/sensitive?."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event-db :login
                     (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [r         (last-record :test/main)
          projected (epoch/projected-record r)]
      (is (= 1 (:rf.epoch/redacted-modified-paths-count r)))
      (is (= 1 (:rf.epoch/redacted-modified-paths-count projected))
          "projection preserves the counter verbatim")
      (is (= (:rf.epoch/redacted-modified-paths-count r)
             (:rf.epoch/redacted-modified-paths-count
               (epoch/projected-record projected)))
          "idempotent under a second projection pass"))))

(deftest G7-counter-handles-nil-db-edge
  (testing "halted-destroy records may carry nil :db-before or
            :db-after (rf2-v0jwt). The counter handles the nil edge:
            transitioning a sensitive path from nil to a value counts
            as a change; nil-to-nil is unchanged. `(get-in nil P)` is
            `nil` — the value-equality predicate handles both shapes
            without throwing."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    ;; Direct unit-level test against the assembly fn — covers the
    ;; nil shapes the standard dispatch path can't easily produce.
    (require '[re-frame.epoch.assembly :as assembly])
    (let [count-fn (resolve 're-frame.epoch.assembly/redacted-modified-paths-count)]
      (is (= 0 (count-fn :test/main nil nil))
          "nil → nil at every path: 0 changes")
      (is (= 1 (count-fn :test/main nil {:auth {:password "x"}}))
          "nil → {:auth {:password \"x\"}}: 1 change at the sensitive path")
      (is (= 1 (count-fn :test/main {:auth {:password "x"}} nil))
          "{:auth {:password \"x\"}} → nil: 1 change at the sensitive path")
      (is (= 0 (count-fn :test/main
                         {:auth {:password "x"}}
                         {:auth {:password "x"}}))
          "value-equal across the cascade: 0 changes"))))

;; ============================================================================
;;  E. Nil-slot records (halted-destroy / partial)
;; ============================================================================

(deftest E-projected-record-on-redacted-nil-slot-record
  (testing "a halted-destroy record carries nil :db-before / :db-after
            (rf2-v0jwt). A redact-fn that runs against this record may
            leave those nils untouched OR rewrite them. The projection
            handles both shapes without throwing and preserves the nil
            slots as nil (no fabricated values)."
    ;; Synthesise the post-redact-fn shape directly (the halted-destroy
    ;; cascade path is exercised in epoch_redact_fn_test.clj's
    ;; wiring-halted-destroy-applies-redact-fn — this test pins the
    ;; projection's behaviour over the resulting shape).
    (let [redacted-partial
          {:epoch-id        99
           :frame           :test/main
           :committed-at    0
           :outcome         :halted-destroy
           :halt-reason     {:reason :rf.epoch/halted-destroy}
           :db-before       nil
           :db-after        nil
           ;; redact-fn left trigger-event alone (a halted-destroy
           ;; with no recoverable trigger may not carry the slot at
           ;; all — pin both shapes work).
           :trace-events    []
           :sub-runs        []
           :renders         []
           :effects         []
           :rf.epoch/sensitive? false}
          projected (epoch/projected-record redacted-partial)]
      (is (some? projected) "projection over a nil-slot record returns
                             a non-nil value")
      (is (nil? (:db-before projected)) "nil :db-before stays nil")
      (is (nil? (:db-after projected))  "nil :db-after stays nil")
      (is (= :halted-destroy (:outcome projected))
          "bookkeeping passes through unchanged")
      (is (= projected (epoch/projected-record projected))
          "idempotent under a second projection pass"))))

(deftest E-projected-record-on-record-with-sentinel-db-after
  (testing "a record whose :db-after is itself the :rf/redacted scalar
            (the redact-fn wiped the whole map) — the projection MUST
            preserve the scalar (no walk, no warning, no marker)"
    (let [synthetic
          {:epoch-id      1
           :frame         :test/main
           :committed-at  0
           :event-id      :login
           :trigger-event [:login]
           :db-before     {:n 0}
           :db-after      :rf/redacted
           :outcome       :ok
           :schema-digest nil
           :rf.epoch/sensitive? true
           :trace-events  []
           :sub-runs      []
           :renders       []
           :effects       []}
          projected (epoch/projected-record synthetic)]
      (is (= :rf/redacted (:db-after projected))
          "wholly-sentinel'd payload slot stays as the scalar through
           the projection walker")
      (is (= {:n 0} (:db-before projected))
          "the other payload slot is walked normally — the per-slot
           guards do not cross-contaminate")
      (is (= projected (epoch/projected-record projected))
          "idempotent over the synthetic mixed shape"))))

;; ============================================================================
;;  F. projected-history over a mixed-sentinel ring
;; ============================================================================

(deftest F-projected-history-iterates-mixed-ring-in-order
  (testing "projected-history walks the ring in oldest-first order
            and applies projected-record to each entry. With a
            redact-fn installed mid-session, the ring carries some raw
            and some redacted records; projected-history produces the
            same iteration shape and preserves epoch-id ordering."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :login
                     (fn [db [_ pw]] (assoc-in db [:auth :password] pw)))

    ;; Cascade 1: no redact-fn installed yet — record is raw.
    (rf/dispatch-sync [:seed]              {:frame :test/main})
    (rf/dispatch-sync [:login "secret-1"]  {:frame :test/main})

    ;; Install redact-fn mid-session.
    (rf/configure :epoch-history
                  {:redact-fn (fn [r]
                                (cond-> r
                                  (get-in r [:db-after :auth :password])
                                  (assoc-in [:db-after :auth :password]
                                            :rf/redacted)))})

    ;; Cascade 2: redact-fn installed — record sentinels the leaf.
    (rf/dispatch-sync [:login "secret-2"]  {:frame :test/main})
    (rf/dispatch-sync [:login "secret-3"]  {:frame :test/main})

    (let [history (rf/epoch-history :test/main)
          ph      (epoch/projected-history :test/main)]
      (is (= (count history) (count ph))
          "one projected record per ring entry")
      (is (= (mapv :epoch-id history) (mapv :epoch-id ph))
          "ordering preserved across the projection pass")

      (testing "every projected record's password slot is nil or
                :rf/redacted — no raw secret leaks through the
                off-box egress, regardless of whether the redact-fn
                was installed at the time the record was assembled"
        (doseq [r ph]
          (let [pw (get-in r [:db-after :auth :password])]
            (is (or (nil? pw) (= :rf/redacted pw))
                (str "epoch-id " (:epoch-id r) " password slot: " pw)))))

      (testing "projected-history is idempotent — re-projecting each
                entry produces a structurally-equal vector"
        (is (= ph (mapv epoch/projected-record ph)))))))

(deftest F-projected-history-empty-with-redact-fn-installed
  (testing "with a redact-fn installed but no cascades recorded,
            projected-history is the empty vector — the redact-fn is
            inert until a record reaches build-record"
    (rf/configure :epoch-history {:redact-fn (fn [r] r)})
    (is (= [] (epoch/projected-history :rf/no-such-frame)))))

;; ============================================================================
;;  Composition fixpoint — the core idempotency claim
;; ============================================================================

(deftest fixpoint-redact-then-project-twice-equals-redact-then-project-once
  (testing "the core composition claim: (project (project (redact r)))
            = (project (redact r)). The first projection lands every
            schema-declared sensitive path on :rf/redacted; the second
            projection finds the same sentinels (already its own
            target value) and produces no further change."
    (rf/reg-frame :test/main {})
    (install-two-sensitive-paths-schema! :test/main)
    (rf/configure :epoch-history
                  {:redact-fn (fn [r]
                                ;; Half the work — sentinel :password
                                ;; only; the projection picks up :token.
                                (cond-> r
                                  (get-in r [:db-after :auth :password])
                                  (assoc-in [:db-after :auth :password]
                                            :rf/redacted)))})
    (rf/reg-event-db :login
                     (fn [db [_ pw tk]]
                       (-> db
                           (assoc-in [:auth :password] pw)
                           (assoc-in [:auth :token]    tk))))
    (rf/dispatch-sync [:login "topsecret" "tok-xyz"] {:frame :test/main})

    (let [r          (last-record :test/main)
          projected1 (epoch/projected-record r)
          projected2 (epoch/projected-record projected1)
          projected3 (epoch/projected-record projected2)]
      (is (= projected1 projected2)
          "second projection pass is a no-op (fixpoint)")
      (is (= projected2 projected3)
          "third projection pass is also a no-op — the fixpoint is
           stable, not merely a two-pass coincidence")
      (is (= :rf/redacted (get-in projected1 [:db-after :auth :password])))
      (is (= :rf/redacted (get-in projected1 [:db-after :auth :token]))))))
