(ns re-frame.epoch-redact-fn-test
  "Coverage for the `:redact-fn` PROJECTION-SIDE advanced override on
  `:epoch-history` (EP-0015 §15 Epoch Redaction + open-issue 6 disposition,
  RULED, hardened).

  THE RULING (open-issue 6). `:redact-fn` is the projection-side-only
  advanced override. Storage-side mutation was REMOVED — post-EP-0010
  epoch records are causal replay material, and mutating them at rest
  corrupts the replay contract. So:

    * the in-process ring buffer (`epoch-history`) and every
      `register-epoch-listener!` listener deliver the RAW record;
    * the app `:redact-fn` runs ONCE per record at the OFF-BOX EGRESS
      boundary — inside `projected-record`, AFTER the frame/profile
      `project-egress` projection — never at storage time.

  Invariants pinned here:

    1. **Ring + listeners are RAW.** `(rf/epoch-history)` and the
       listener fan-out carry unredacted `:db-after` etc. — the
       `:redact-fn` does NOT run at storage time. Restore fidelity is
       preserved by construction.

    2. **`projected-record` applies the override.** The installed
       `:redact-fn` runs on the PROJECTED record (after the frame/profile
       projection). It sees the projected shape (frame-declared sensitive
       paths already `:rf/redacted`) and is the escape for material the
       projection cannot prove.

    3. **`:rf.epoch/sensitive?` rollup is raw-signal-derived.** Computed
       inside `build-record` from the raw record's frame-declared
       sensitive leaves; it is a trustworthy off-box-branch signal on the
       RAW ring record regardless of any projection-side redaction.

    4. **Failure isolation.** A throwing `:redact-fn` emits
       `:rf.warning/epoch-redact-fn-exception` at projection time and falls
       back to the projected (frame/profile-redacted) record — neither the
       drain nor the egress is broken; the RAW ring record is untouched.

    5. **No `:redact-fn` ⇒ projection is the plain frame/profile shape.**

  Plus the `configure!` validation surface (`fn?` / `nil` accepted, other
  shapes silently dropped) — unchanged by the storage→projection move."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.epoch :as epoch]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            [re-frame.test-support :as test-support]
            ;; Side-effect requires (mirror epoch_test.clj fixture).
            [re-frame.machines]))

;; ---- fixtures --------------------------------------------------------------
;;
;; rf2-yw1w1u — the canonical capture/restore fixture. It snapshots the
;; registrar at ns-load and restores around each test (so framework /
;; example registrations survive cross-ns runs) and fires the epoch
;; reset-hook table (`:epoch/clear-history!`,
;; `:epoch/clear-epoch-listeners!`, `:epoch/reset-config!`) so each test
;; starts from a clean epoch slate with config reset to the shipped
;; default. The `:init-fn` re-applies the suite's non-default
;; `:trace-events-keep 5` (NOT the shipped 50 = :depth, see
;; `re-frame.epoch.state/default-trace-events-keep`; Mike pair-debug
;; 2026-05-27) through the public `configure!` boundary — no test ns
;; reaches into the private `state/config` var. The `:init-fn` runs
;; AFTER the post-dispose reset hooks, so the override lands on top of
;; the freshly-reset default.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (rf/configure! {:epoch-history {:trace-events-keep 5}}))}))

;; ---- helpers ---------------------------------------------------------------

(defn- last-record [frame-id]
  (last (rf/epoch-history frame-id)))

;; EP-0025: durable app-db classification rides the commit-plane
;; classification effects — declare the `[:auth :password]` sensitive path
;; through `elision/apply-classification-effects` (`:source :effect`), the
;; same registry write a `reg-event` returning `:sensitive` performs. The
;; frame container is reg-frame'd by each deftest before this runs.
(defn- install-sensitive-schema!
  [frame-id]
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt {:sensitive [[:auth :password]]})))
  nil)

(defn- record-warnings! []
  ;; Capture every `:warning` op-type emission so the failure-
  ;; isolation invariant can assert the documented op-id + tags fire.
  (let [warnings (atom [])]
    (rf/register-listener! :trace ::warn-watcher
                           (fn [ev]
                             (when (= :warning (:op-type ev))
                               (swap! warnings conj ev))))
    warnings))

;; ============================================================================
;;  configure! validation surface (Spec API.md §Configure keys —
;;  :redact-fn row) — UNCHANGED by the storage→projection move
;; ============================================================================

(deftest configure-accepts-fn
  (testing "(rf/configure! {:epoch-history {:redact-fn f}}) — fn? accepted
            and the slot lands in current-config"
    (let [f (fn [r] r)]
      (rf/configure! {:epoch-history {:redact-fn f}})
      (is (identical? f (:redact-fn (epoch/current-config)))
          ":redact-fn lands by identity — no wrapping"))))

(deftest configure-accepts-nil-to-clear
  (testing "(rf/configure! {:epoch-history {:redact-fn nil}}) clears a
            previously-installed fn"
    (rf/configure! {:epoch-history {:redact-fn (fn [r] r)}})
    (is (some? (:redact-fn (epoch/current-config))))

    (rf/configure! {:epoch-history {:redact-fn nil}})
    (is (nil? (:redact-fn (epoch/current-config)))
        "explicit nil clears the slot")))

(deftest configure-rejects-non-fn
  (testing "(rf/configure! {:epoch-history {:redact-fn <bad>}}) silently
            drops other shapes; a previously-installed fn survives"
    (let [f (fn [r] r)]
      (rf/configure! {:epoch-history {:redact-fn f}})
      (rf/configure! {:epoch-history {:redact-fn "not-a-fn"}})
      (is (identical? f (:redact-fn (epoch/current-config)))
          "string silently dropped — prior fn survives")

      (rf/configure! {:epoch-history {:redact-fn 42}})
      (is (identical? f (:redact-fn (epoch/current-config)))
          "number silently dropped")

      (rf/configure! {:epoch-history {:redact-fn {:k :v}}})
      (is (identical? f (:redact-fn (epoch/current-config)))
          "map silently dropped")

      (rf/configure! {:epoch-history {:redact-fn [:a :b]}})
      (is (identical? f (:redact-fn (epoch/current-config)))
          "vector silently dropped"))))

(deftest configure-absent-slot-preserves-existing-fn
  (testing "a configure call that does NOT mention :redact-fn leaves
            the previously-installed fn intact (the configure semantics
            are 'merge present keys' — absence is a no-op)"
    (let [f (fn [r] r)]
      (rf/configure! {:epoch-history {:redact-fn f}})
      (rf/configure! {:epoch-history {:depth 17}})
      (is (identical? f (:redact-fn (epoch/current-config)))
          ":redact-fn slot preserved across a :depth-only update")
      (is (= 17 (:depth (epoch/current-config)))
          "the :depth update was applied"))))

(deftest configure-partial-update-mixed-validity
  (testing "a configure call carrying a valid :redact-fn and an
            invalid :depth applies the fn and drops the depth"
    (rf/configure! {:epoch-history {:depth 9}})
    (let [f (fn [r] r)]
      (rf/configure! {:epoch-history {:depth nil :redact-fn f}})
      (is (identical? f (:redact-fn (epoch/current-config))))
      (is (= 9 (:depth (epoch/current-config)))
          ":depth nil dropped; prior 9 survives"))))

;; ============================================================================
;;  Invariant 1 — ring + listeners are RAW (no storage-side redaction)
;; ============================================================================

(deftest invariant-1-ring-record-is-raw-even-with-redact-fn-installed
  (testing "with a :redact-fn installed, the in-process ring record is
            still RAW — storage-side redaction was REMOVED (EP-0015 §15 +
            open-issue 6). The ring is causal replay material; restore
            fidelity is preserved by construction."
    (rf/reg-frame :test/main {})
    ;; A :redact-fn that WOULD wipe :db-after if it ran at storage time.
    (rf/configure! {:epoch-history {:redact-fn (fn [r] (assoc r :db-after :rf/redacted))}})
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [r (last-record :test/main)]
      (is (= {:auth {:password "topsecret"}} (:db-after r))
          ":db-after on the ring is RAW — the :redact-fn did NOT run at
           storage time (it is projection-side only)"))))

(deftest invariant-1-listener-receives-raw-record
  (testing "the listener fan-out delivers the RAW record — the
            :redact-fn does NOT run before fan-out (it is applied only at
            the off-box egress boundary, inside projected-record)."
    (rf/reg-frame :test/main {})
    (let [seen (atom nil)]
      (rf/configure! {:epoch-history {:redact-fn (fn [r] (assoc r :db-after :rf/redacted))}})
      (rf/register-listener! :epoch ::watch (fn [r] (reset! seen r)))
      (rf/reg-event :login
                       (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
      (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

      (is (= {:auth {:password "topsecret"}} (:db-after @seen))
          "the listener received the RAW :db-after — listeners fan out
           the raw record; an off-box forwarder projects at its egress")
      (is (= @seen (last-record :test/main))
          "ring and listener hold the SAME raw record — single source of
           truth, no storage-side per-consumer divergence"))))

(deftest invariant-1-redact-fn-not-invoked-at-storage-time
  (testing "the :redact-fn fires ZERO times across a settle — it is not
            a storage-side hook. (It fires only when projected-record is
            called; see invariant 2.)"
    (rf/reg-frame :test/main {})
    (let [invocations (atom 0)]
      (rf/configure! {:epoch-history {:redact-fn (fn [r] (swap! invocations inc) r)}})
      (rf/register-listener! :epoch ::watch (fn [_] nil))
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
      (rf/dispatch-sync [:seed] {:frame :test/main})

      (is (= 0 @invocations)
          "no storage-side invocation — the redact-fn is projection-side
           only (EP-0015 §15 + open-issue 6)"))))

;; ============================================================================
;;  Invariant 2 — projected-record applies the :redact-fn override
;; ============================================================================

(deftest invariant-2-projected-record-applies-redact-fn
  (testing "projected-record applies the installed :redact-fn to the
            PROJECTED record (the advanced override). The override runs
            ONCE per projection call, after the frame/profile projection."
    (rf/reg-frame :test/main {})
    (let [invocations (atom 0)]
      (rf/configure! {:epoch-history {:redact-fn (fn [r]
                                  (swap! invocations inc)
                                  (assoc r :rf/test-tag :redacted))}})
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
      (rf/dispatch-sync [:seed] {:frame :test/main})

      (let [r         (last-record :test/main)
            projected (epoch/projected-record r)]
        (is (= 1 @invocations)
            "one projection call = one :redact-fn invocation")
        (is (= :redacted (:rf/test-tag projected))
            "the projected record carries the override's tag")
        (is (not (contains? r :rf/test-tag))
            "the RAW ring record is untouched by the override")))))

(deftest invariant-2-override-sees-projected-shape
  (testing "the :redact-fn runs AFTER the frame/profile projection, so it
            observes the projected record — a frame-declared sensitive
            path is already :rf/redacted when the override runs. The
            override is the escape for material the projection cannot
            prove (a non-frame-declared slot)."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (let [observed (atom nil)]
      ;; The override records what it SEES at [:db-after :auth :password]
      ;; and scrubs a NON-frame-declared slot the projection left raw.
      (rf/configure! {:epoch-history {:redact-fn (fn [r]
                                  (reset! observed
                                          (get-in r [:db-after :auth :password]))
                                  (cond-> r
                                    (get-in r [:db-after :session :token])
                                    (assoc-in [:db-after :session :token]
                                              :rf/redacted)))}})
      (rf/reg-event :login
                       (fn [{:keys [db]} [_ pw tok]]
                         {:db (-> db
                             (assoc-in [:auth :password] pw)
                             (assoc-in [:session :token] tok))}))
      (rf/dispatch-sync [:login "topsecret" "tok-xyz"] {:frame :test/main})

      (let [r         (last-record :test/main)
            projected (epoch/projected-record r)]
        (is (= :rf/redacted @observed)
            "the override observed the frame-declared :password ALREADY
             :rf/redacted by the frame/profile projection (it ran AFTER)")
        (is (= :rf/redacted (get-in projected [:db-after :session :token]))
            "the override scrubbed the NON-frame-declared slot the
             projection could not prove — its purpose as the advanced escape")
        (is (= :rf/redacted (get-in projected [:db-after :auth :password]))
            "the frame-declared path stays redacted (projection did it)")
        (is (= "tok-xyz" (get-in r [:db-after :session :token]))
            "the RAW ring record keeps the non-declared token verbatim")))))

(deftest invariant-2-no-double-invoke-across-projection-calls
  (testing "projected-record over the same record N times invokes the
            :redact-fn N times (once per egress) — it is the egress
            override, applied per projection call, not cached."
    (rf/reg-frame :test/main {})
    (let [invocations (atom 0)]
      (rf/configure! {:epoch-history {:redact-fn (fn [r] (swap! invocations inc) r)}})
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
      (rf/dispatch-sync [:seed] {:frame :test/main})

      (let [r (last-record :test/main)]
        (dotimes [_ 5] (epoch/projected-record r))
        (is (= 5 @invocations)
            "five projection calls = five override invocations (one per
             egress); the override is not memoised")))))

;; ============================================================================
;;  Invariant 3 — :rf.epoch/sensitive? rollup is raw-signal-derived
;; ============================================================================

(deftest invariant-3-rollup-on-raw-ring-record
  (testing "the :rf.epoch/sensitive? rollup on the RAW ring record reflects
            the frame-declared sensitive leaves the cascade touched — a
            trustworthy off-box-branch signal independent of any
            projection-side redaction."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [r         (last-record :test/main)
          projected (epoch/projected-record r)]
      (is (true? (:rf.epoch/sensitive? r))
          "rollup is true on the raw ring record (raw-signal-derived)")
      (is (true? (:rf.epoch/sensitive? projected))
          "the projection preserves the rollup verbatim — bookkeeping
           passes through both the frame/profile walk and any override"))))

;; ============================================================================
;;  Invariant 4 — failure isolation at PROJECTION time
;; ============================================================================

(deftest invariant-4-throwing-redact-fn-warns-and-falls-back-at-projection
  (testing "a throwing :redact-fn emits :rf.warning/epoch-redact-fn-exception
            at PROJECTION time and falls back to the projected
            (frame/profile-redacted) record — neither the drain nor the
            egress breaks, and the RAW ring record is untouched."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))

    (let [warnings (record-warnings!)]
      (rf/configure! {:epoch-history {:redact-fn (fn [_] (throw (ex-info "boom" {:why :test})))}})
      ;; Settle does NOT invoke the redact-fn (storage-side) — no warning yet.
      (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})
      (is (empty? @warnings)
          "settle fired no redact-fn-exception warning — the fn is not a
           storage-side hook")

      (let [r         (last-record :test/main)
            projected (epoch/projected-record r)]
        ;; The projection ran the (throwing) override → warning + fallback.
        (is (= :rf/redacted (get-in projected [:db-after :auth :password]))
            "fallback is the PROJECTED record — the frame/profile projection
             still redacted the frame-declared path; the throwing override
             only forfeited its own additional scrub")
        (is (= "topsecret" (get-in r [:db-after :auth :password]))
            "the RAW ring record is untouched by the throwing override")

        (let [warn (->> @warnings
                        (filter #(= :rf.warning/epoch-redact-fn-exception
                                    (:operation %)))
                        first)]
          (is (some? warn)
              ":rf.warning/epoch-redact-fn-exception emitted at projection")
          (is (= :test/main (get-in warn [:tags :frame]))
              ":frame tag carries the offending frame-id")
          (is (string? (get-in warn [:tags :ex-msg]))
              ":ex-msg tag carries the exception message string")
          (is (= (:epoch-id r) (get-in warn [:tags :rf.epoch/id]))
              ":rf.epoch/id tag (canonical, rf2-ifdsar) matches the projected record's bare :epoch-id field"))))))

(deftest invariant-4-throwing-redact-fn-stays-registered
  (testing "a throwing :redact-fn stays registered — each projection call
            re-attempts (the registration is not removed on first throw;
            the framework's posture is 'log and continue')."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})

    (let [call-count (atom 0)]
      (rf/configure! {:epoch-history {:redact-fn (fn [_]
                                  (swap! call-count inc)
                                  (throw (ex-info "boom" {})))}})
      (let [r (last-record :test/main)]
        (epoch/projected-record r)
        (epoch/projected-record r)
        (epoch/projected-record r))
      (is (= 3 @call-count)
          "redact-fn re-invoked on every projection despite throwing"))))

;; ============================================================================
;;  Invariant 5 — no :redact-fn ⇒ projection is the plain frame/profile shape
;; ============================================================================

(deftest invariant-5-no-redact-fn-projection-is-plain-frame-profile
  (testing "without a :redact-fn installed, projected-record is exactly the
            frame/profile projection — no override stage. A frame-declared
            sensitive path is redacted by the projection; everything else
            passes through."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]]
                       {:db (-> db
                           (assoc-in [:auth :password] pw)
                           (assoc-in [:public :name] "alice"))}))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [r         (last-record :test/main)
          projected (epoch/projected-record r)]
      (is (= :rf/redacted (get-in projected [:db-after :auth :password]))
          "frame-declared sensitive path redacted by the frame/profile walk")
      (is (= "alice" (get-in projected [:db-after :public :name]))
          "non-sensitive sibling passes through the projection unchanged")
      (is (= "topsecret" (get-in r [:db-after :auth :password]))
          "the RAW ring record is unredacted (no storage-side scrub)"))))

(deftest no-redact-fn-installed-ring-is-raw
  (testing "without :redact-fn installed, every recorded ring record is
            the raw shape (the default-nil posture — apps opt in to the
            advanced override explicitly)."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (let [r (last-record :test/main)]
      (is (= {:n 0} (:db-after r))
          "no redact-fn — ring record is the raw shape")
      (is (not (contains? r :rf/test-tag))
          "no redact-fn — no synthetic slots added"))))

;; ============================================================================
;;  Per-site wirings — settle! / replace-app-db! / halted-destroy store RAW
;; ============================================================================

(deftest wiring-settle-stores-raw-record
  (testing "the settle! drain-settle commit path stores the RAW record —
            the :redact-fn does not run at this seam (it runs
            projection-side, in projected-record)."
    (rf/reg-frame :test/main {})
    (rf/configure! {:epoch-history {:redact-fn (fn [r] (assoc r :rf/test-tag :redacted))}})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (is (not (contains? (last-record :test/main) :rf/test-tag))
        "settle! path: ring record is RAW (override not applied at storage)")
    (is (= :redacted (:rf/test-tag (epoch/projected-record
                                     (last-record :test/main))))
        "the override IS applied when the record is projected for egress")))

(deftest wiring-replace-app-db-stores-raw-record
  (testing "the perform-replace-app-db! synthetic-record commit path stores
            the RAW synthetic record (pair-tool injection / story tools land
            the synthetic :rf.epoch/db-replaced record through this seam);
            the override applies only on projection."
    (rf/reg-frame :test/main {})
    (let [synthetic (atom nil)]
      (rf/register-listener! :epoch ::watch (fn [r] (reset! synthetic r)))
      (rf/configure! {:epoch-history {:redact-fn (fn [r] (assoc r :rf/test-tag :redacted))}})
      (rf/replace-frame-state! :test/main {:rf.db/app {:injected :state}})
      (is (not (contains? @synthetic :rf/test-tag))
          "replace-app-db! path: listener received the RAW synthetic record")
      (is (not (contains? (last-record :test/main) :rf/test-tag))
          "replace-app-db! path: ring received the RAW synthetic record")
      (is (= :redacted (:rf/test-tag (epoch/projected-record @synthetic)))
          "the override applies when the synthetic record is projected"))))

(deftest wiring-halted-destroy-stores-raw-record
  (testing "on-frame-destroyed!'s :halted-destroy partial-record commit
            stores the RAW partial record — devtools observing the
            halted-cascade record see the raw shape (the same posture as an
            :ok cascade record); a forwarder projects at its egress.

            Per the rf2-ee38b correctness review: this drives a REAL
            mid-drain `destroy-frame!` (the handler destroys its own
            frame, matching the live wiring) and asserts UNCONDITIONALLY
            that exactly one :halted-destroy record reached the listener."
    (rf/reg-frame :test/main {})
    (let [halted-records (atom [])]
      (rf/register-listener! :epoch ::watch
                             (fn [r]
                               (when (= :halted-destroy (:outcome r))
                                 (swap! halted-records conj r))))
      (rf/configure! {:epoch-history {:redact-fn (fn [r] (assoc r :rf/test-tag :redacted))}})
      (rf/reg-event :destroy-self
                       (fn [_ _]
                         (frame/destroy-frame! :test/main)
                         {}))
      (try (rf/dispatch-sync [:destroy-self] {:frame :test/main})
           (catch Throwable _ nil))
      (is (= 1 (count @halted-records))
          "the live mid-drain destroy fires exactly one :halted-destroy
           record to listeners")
      (let [halted (first @halted-records)]
        (is (= :halted-destroy (:outcome halted)))
        (is (not (contains? halted :rf/test-tag))
            "halted-destroy path: listener received the RAW partial record
             (storage-side redaction was removed)")
        (is (= :redacted (:rf/test-tag (epoch/projected-record halted)))
            "the override applies when the halted record is projected")))))

;; ============================================================================
;;  Default no-op posture
;; ============================================================================

(deftest nil-redact-fn-installed-projection-is-plain
  (testing "an explicitly-installed nil :redact-fn is equivalent to 'no
            redact-fn' — the projection is the plain frame/profile shape,
            no override stage."
    (rf/reg-frame :test/main {})
    (rf/configure! {:epoch-history {:redact-fn nil}})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (let [r (last-record :test/main)]
      (is (= {:n 0} (:db-after r)))
      (is (= {:n 0} (:db-after (epoch/projected-record r)))
          "no override — projection of a non-sensitive db is the plain
           frame/profile shape"))))

;; ============================================================================
;;  Back-fill stores RAW; projection redacts (EP-0015 §15 / open-issue 6)
;; ============================================================================
;;
;; A post-settle render / sub-run / unmount (React commit / deref /
;; teardown time, AFTER settle committed the epoch) is back-filled into the
;; already-committed record. Per the ruling the back-fill stores the RAW
;; delta — the ring is causal replay material. Redaction (frame/profile +
;; the :redact-fn override) happens at projection time. These tests pin:
;; (a) a back-filled value is RAW in the ring + listener record; (b) the
;; projection of that record redacts via the frame/profile projection (a
;; frame-declared sensitive value) and via the override (a non-declared
;; value); (c) a benign value passes through.
;;
;; POST-SETTLE-EMIT TECHNIQUE (mirrors epoch_attribution_test): emit the
;; trace the substrate's way — op carrying its `:frame` tag, fired OUTSIDE
;; any cascade (empty in-flight buffer, no `*handler-scope*`) — so the
;; runtime's back-fill (`record-render!` / `record-sub-run!` /
;; `record-unmount!`) routes it into the causing cascade's committed record.

(defn- emit-sub-run! [frame-id sub-id prev-value value]
  (trace/emit! :rf.sub :rf.sub/run
               {:rf.sub/id             sub-id
                :rf.sub/query-v        [sub-id]
                :frame                 frame-id
                :rf.sub/value-changed? (not= prev-value value)
                :rf.sub/prev-value     prev-value
                :rf.sub/value          value
                :rf.sub/cascade?       false
                :rf.sub/cause-sub      nil}))

(deftest back-fill-sub-run-is-raw-in-ring-and-listener
  (testing "EP-0015 §15 — a post-settle SUB-RUN back-fill stores the RAW
            value in BOTH the re-notified listener record AND the ring. The
            ring is causal replay material; no storage-side redaction runs."
    (rf/reg-frame :test/main {})
    ;; A :redact-fn that WOULD scrub the value if it ran at storage time.
    (rf/configure! {:epoch-history {:redact-fn (fn [r] (assoc r :rf/scrubbed true))}})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (let [seen (atom [])]
      (rf/register-listener! :epoch ::watch (fn [r] (swap! seen conj r)))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/dispatch-sync [:inc]  {:frame :test/main})
      (let [settle-fanouts (count @seen)]
        (emit-sub-run! :test/main :secret nil "topsecret")
        (is (= (inc settle-fanouts) (count @seen))
            "the back-fill re-notified exactly once")
        (let [renotified (last @seen)
              sub-run    (->> (:sub-runs renotified)
                              (filter #(= :secret (:sub-id %))) first)]
          (is (some? sub-run) "the back-filled :secret sub-run is present")
          (is (= "topsecret" (:value sub-run))
              "the re-notified :sub-runs row's value is RAW — no
               storage-side redaction (the ring is causal replay material)")
          (is (not (contains? renotified :rf/scrubbed))
              "the :redact-fn did NOT run at the back-fill seam")
          (let [ring (last-record :test/main)]
            (is (= renotified ring)
                "ring and listener hold the SAME raw shape")
            (is (= "topsecret"
                   (:value (->> (:sub-runs ring)
                                (filter #(= :secret (:sub-id %))) first)))
                "the ring's back-filled :sub-runs row is RAW")))))))

(deftest back-fill-value-redacted-on-projection
  (testing "EP-0015 §15 — the back-filled RAW value is REDACTED when the
            record is projected for off-box egress: the frame/profile
            projection redacts a frame-declared sub-value, and the
            :redact-fn override can scrub a non-declared one."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    ;; The override scrubs the :secret sub-run's :value (a non-frame-
    ;; declared, non-app-db-rooted slot the projection cannot prove).
    (rf/configure! {:epoch-history {:redact-fn (fn [r]
                                (cond-> r
                                  (vector? (:sub-runs r))
                                  (update :sub-runs
                                          (fn [rows]
                                            (mapv (fn [row]
                                                    (if (= :secret (:sub-id row))
                                                      (assoc row :value :rf/redacted)
                                                      row))
                                                  rows)))))}})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})
    (emit-sub-run! :test/main :secret nil "topsecret")

    (let [r         (last-record :test/main)
          projected (epoch/projected-record r)
          raw-row   (->> (:sub-runs r)
                         (filter #(= :secret (:sub-id %))) first)
          proj-row  (->> (:sub-runs projected)
                         (filter #(= :secret (:sub-id %))) first)]
      (is (= "topsecret" (:value raw-row))
          "the RAW ring row carries the verbatim value")
      (is (= :rf/redacted (:value proj-row))
          "the projected row's value is redacted by the :redact-fn override
           at the egress boundary"))))

(deftest back-fill-no-redact-fn-projection-passes-benign-value
  (testing "EP-0015 §15 — with NO :redact-fn installed, a benign back-filled
            value passes through both the raw ring and the frame/profile
            projection unchanged (the attribution behaviour is intact;
            redaction is opt-in via the override or schema classification)."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))

    (let [seen (atom [])]
      (rf/register-listener! :epoch ::watch (fn [r] (swap! seen conj r)))
      (rf/dispatch-sync [:seed] {:frame :test/main})
      (rf/dispatch-sync [:inc]  {:frame :test/main})
      (emit-sub-run! :test/main :counter 0 42)
      (let [renotified (last @seen)
            sub-run    (->> (:sub-runs renotified)
                            (filter #(= :counter (:sub-id %))) first)]
        (is (some? sub-run) "the benign back-filled sub-run is present")
        (is (= 42 (:value sub-run))
            "the non-sensitive value is RAW in the ring/listener")
        (is (= true (:value-changed? sub-run))
            "the benign sub-run's value-change attribution survives intact")
        (is (= 42 (:value (->> (:sub-runs (epoch/projected-record renotified))
                               (filter #(= :counter (:sub-id %))) first)))
            "the benign value passes the projection unchanged (no
             over-redaction)")))))

;; ============================================================================
;;  Restore / replay under redaction — disposition 6's explicit
;;  "verify with a restore/replay test" instruction (rf2-t55hxg.9)
;; ============================================================================
;;
;; Disposition 6 (epoch redaction) is IMPLEMENTED CORRECTLY — the `:redact-fn`
;; never mutates storage, so the in-process ring carries the RAW
;; record (invariant 1, above) and the restore path reads that raw ring,
;; never the projected copy. The replay-faithful-under-redaction property
;; therefore holds BY CONSTRUCTION. But the disposition's explicit instruction
;; is "verify with a restore/replay test", and the rf2-kkfd3g tail-1 review
;; found the property was pinned only COMPOSITIONALLY — `invariant-1-ring-
;; record-is-raw-even-with-redact-fn-installed` stops at the ring READ (never
;; calls restore-epoch!), and `epoch_test.clj`'s genuine restore tests run with
;; NO `:redact-fn` installed. This test closes that gap: it COMBINES a live
;; projection-side `:redact-fn` install with a `restore-epoch!` call and asserts
;; the restored frame-state equals the RAW un-redacted value — the literal
;; combined assertion the disposition asks for.

(deftest restore-replays-raw-value-under-redact-fn
  (testing "disposition 6 — a wiping projection-side :redact-fn installed, a
            sensitive event dispatched, then restore-epoch!: the RESTORED
            frame-state equals the RAW un-redacted value. Redaction is a
            projection-side EGRESS concern; the durable ring the restore reads
            is replay-faithful, untouched by the override."
    (rf/reg-frame :test/main {})
    ;; Frame-owned sensitive classification (EP-0015 §8) AND a wiping
    ;; projection-side :redact-fn — BOTH redaction routes active. Neither may
    ;; corrupt the stored ring the restore replays from.
    (install-sensitive-schema! :test/main)
    (rf/configure! {:epoch-history {:redact-fn (fn [r] (assoc r :db-after :rf/redacted))}})
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
    (rf/reg-event :logout
                     (fn [{:keys [db]} _] {:db (assoc-in db [:auth :password] nil)}))

    ;; (a) Dispatch a sensitive event — the password lands at the
    ;; frame-sensitive [:auth :password] path.
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})
    (let [login-epoch (:epoch-id (last-record :test/main))]
      ;; (b) Advance past it so restore is a genuine rewind, not a no-op.
      (rf/dispatch-sync [:logout] {:frame :test/main})
      (is (nil? (get-in (rf/app-db-value :test/main) [:auth :password]))
          "after logout the live password is cleared")

      ;; (c) Restore to the sensitive epoch.
      (is (true? (rf/restore-epoch! :test/main login-epoch))
          "restore to the sensitive login epoch succeeded")

      ;; (d) The RESTORED frame-state equals the RAW un-redacted value — the
      ;; replay is faithful DESPITE the wiping :redact-fn AND the sensitive
      ;; classification. The override is egress-only; the ring is raw.
      (is (= "topsecret" (get-in (rf/app-db-value :test/main) [:auth :password]))
          "restore replayed the RAW password — the projection-side :redact-fn
           did NOT corrupt the durable ring (disposition 6: replay-faithful
           under redaction)")

      ;; (e) Cross-check the mechanism: the SAME ring record projects REDACTED
      ;; at egress (the :redact-fn DOES run there) — proving the two surfaces
      ;; diverge exactly as the disposition requires: egress redacts, the
      ;; replay-source ring stays raw.
      (let [login-record (->> (rf/epoch-history :test/main)
                              (filter #(= login-epoch (:epoch-id %)))
                              first)]
        (is (= "topsecret" (get-in login-record [:db-after :auth :password]))
            "the durable ring record is RAW (the replay source)")
        (is (= :rf/redacted (:db-after (epoch/projected-record login-record)))
            "the SAME record projects REDACTED at egress — egress redacts,
             storage stays raw")))))
