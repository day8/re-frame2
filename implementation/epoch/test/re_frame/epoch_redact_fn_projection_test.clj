(ns re-frame.epoch-redact-fn-projection-test
  "Composition between the frame/profile projection (`projected-record` /
  `projected-history`) and the `:redact-fn` PROJECTION-SIDE advanced
  override (EP-0015 §15 Epoch Redaction + open-issue 6 disposition, RULED).

  Per the ruling `:redact-fn` is projection-side only — storage was REMOVED
  (the ring is causal replay material). `projected-record` is a TWO-STAGE
  projection:

    1. frame/profile projection — each tree slot through
       `re-frame.projection/project-egress` under
       `:rf.egress/off-box-observability` (frame-declared sensitive paths
       → `:rf/redacted`, large paths → markers);
    2. `:redact-fn` advanced override — the installed fn applied to the
       already-projected record (the escape for non-frame-declared
       material).

  This file pins the cross-surface contract: the two stages compose
  without double-walk corruption, consumers of the projection see a stable
  shape, and the `:rf.epoch/sensitive?` rollup remains a trustworthy signal
  on the raw ring record.

  Idempotence claim being pinned: `projected-record` is idempotent in the
  composition sense — applying it to an already-projected record returns a
  structurally-equal value, because `:rf/redacted` is the projection's own
  substitution target AND the override scrubs to the same sentinel.

  redact-fn call-count contract: the override fires ONCE per
  `projected-record` call (the egress override), after the frame/profile
  projection. It never runs at storage time."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.epoch :as epoch]
            [re-frame.frame :as frame]
            [re-frame.privacy :as privacy]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            ;; Side-effect requires (mirror epoch_test.clj fixture).
            [re-frame.machines]))

;; ---- fixtures --------------------------------------------------------------
;;
;; rf2-yw1w1u — canonical capture/restore fixture. Snapshots the
;; registrar at ns-load + restores around each test, fires the epoch
;; reset-hook table (history / listeners / config-to-default), and the
;; `:init-fn` re-applies the suite's non-default `:trace-events-keep 5`
;; (NOT the shipped 50 = :depth; Mike pair-debug 2026-05-27) through the
;; public `configure!` boundary — no test ns reaches into the private
;; `state/config` var.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (rf/configure! {:epoch-history {:trace-events-keep 5}}))}))

;; ---- helpers ---------------------------------------------------------------

(defn- last-record [frame-id]
  (last (rf/epoch-history frame-id)))

;; EP-0025: durable app-db classification rides the commit-plane
;; classification effects. Seed sensitive declarations through
;; `elision/apply-classification-effects` (`:source :effect`) — the same
;; registry write a `reg-event` returning `:sensitive` performs. The frame
;; container is reg-frame'd by each deftest before these run.
(defn- install-sensitive-schema!
  "Declare a `[:auth :password]` sensitive path against `frame-id`."
  [frame-id]
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt {:sensitive [[:auth :password]]})))
  nil)

(defn- install-two-sensitive-paths-schema!
  "Declare two sensitive paths against `frame-id` — `[:auth :password]`
  and `[:auth :token]`."
  [frame-id]
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt
               {:sensitive [[:auth :password] [:auth :token]]})))
  nil)

;; ============================================================================
;;  A. The override runs AFTER the frame/profile projection
;; ============================================================================

(deftest A-override-runs-after-frame-profile-projection
  (testing "the :redact-fn override observes the ALREADY-PROJECTED record:
            a frame-declared sensitive path is :rf/redacted by the
            frame/profile stage before the override runs over it. The
            override is the escape for a NON-declared slot the projection
            could not prove."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (let [observed (atom :unset)]
      (rf/configure! {:epoch-history {:redact-fn (fn [r]
                                  (reset! observed
                                          (get-in r [:db-after :auth :password]))
                                  ;; Scrub a non-declared slot the projection
                                  ;; left raw.
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
            "the override saw :password ALREADY :rf/redacted — it ran after
             the frame/profile projection")
        (is (= :rf/redacted (get-in projected [:db-after :auth :password]))
            "frame-declared path redacted by the projection")
        (is (= :rf/redacted (get-in projected [:db-after :session :token]))
            "non-declared path scrubbed by the override")
        (is (= "topsecret" (get-in r [:db-after :auth :password]))
            "the RAW ring record is unredacted (storage-side redaction gone)")
        (is (= "tok-xyz" (get-in r [:db-after :session :token]))
            "the RAW ring record keeps the non-declared token verbatim")))))

;; ============================================================================
;;  B. Override returns a whole-slot sentinel — idempotent under re-projection
;; ============================================================================

(deftest B-whole-slot-sentinel-passes-through-re-projection
  (testing "an override that collapses :db-after to the scalar :rf/redacted
            survives a second projection pass (the frame/profile walk passes
            a scalar sentinel through unchanged, and the override re-applies
            the same collapse) — structural equality across passes."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/configure! {:epoch-history {:redact-fn (fn [r] (assoc r :db-after :rf/redacted))}})
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [r          (last-record :test/main)
          projected  (epoch/projected-record r)
          projected2 (epoch/projected-record projected)]
      (is (= {:auth {:password "topsecret"}} (:db-after r))
          "the RAW ring :db-after is unredacted")
      (is (= :rf/redacted (:db-after projected))
          "the override collapsed :db-after to the scalar sentinel")
      (is (= projected projected2)
          "second projection pass returns a structurally-equal value —
           :rf/redacted is the fixpoint of both stages"))))

;; ============================================================================
;;  C. Frame/profile projection + override compose without double-walk
;; ============================================================================

(deftest C-projection-redacts-declared-override-redacts-the-rest
  (testing "the frame/profile projection redacts the frame-declared
            sensitive path; the override redacts a SECOND, non-declared
            path against the same projected map. No double-walk corruption:
            the declared leaf stays :rf/redacted, the override's leaf
            becomes :rf/redacted, the benign sibling is unchanged."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)            ;; only :password declared
    (rf/configure! {:epoch-history {:redact-fn (fn [r]
                                ;; The override scrubs a NON-declared slot.
                                (cond-> r
                                  (get-in r [:db-after :session :token])
                                  (assoc-in [:db-after :session :token]
                                            :rf/redacted)))}})
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw tk]]
                       {:db (-> db
                           (assoc-in [:auth :password]  pw)
                           (assoc-in [:session :token]  tk)
                           (assoc-in [:public :name]    "alice"))}))
    (rf/dispatch-sync [:login "topsecret" "tok-xyz"] {:frame :test/main})

    (let [r         (last-record :test/main)
          projected (epoch/projected-record r)]
      (is (= :rf/redacted (get-in projected [:db-after :auth :password]))
          "frame/profile projection redacted the frame-declared :password")
      (is (= :rf/redacted (get-in projected [:db-after :session :token]))
          "the override redacted the non-declared :token")
      (is (= "alice" (get-in projected [:db-after :public :name]))
          "the benign sibling passes through both stages unchanged")
      (is (= "topsecret" (get-in r [:db-after :auth :password]))
          "the RAW ring record carries the verbatim password")

      (testing "second projection pass is a no-op (idempotent fixpoint)"
        (is (= projected (epoch/projected-record projected)))))))

;; ============================================================================
;;  D. Rollup is raw-signal-derived — projection (both stages) preserve it
;; ============================================================================

(deftest D-sensitive-rollup-survives-both-projection-stages
  (testing "the :rf.epoch/sensitive? rollup is computed inside build-record
            from RAW signals. After the frame/profile projection redacts
            the schema path and the override scrubs further, the rollup
            MUST still read true — both stages preserve the bookkeeping."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/configure! {:epoch-history {:redact-fn (fn [r] (assoc r :db-after :rf/redacted))}})
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [r         (last-record :test/main)
          projected (epoch/projected-record r)]
      (is (true? (:rf.epoch/sensitive? r))
          "rollup true on the raw ring record (raw-signal-derived)")
      (is (true? (:rf.epoch/sensitive? projected))
          "projection preserves the rollup verbatim across both stages"))))

(deftest D-redacted-sentinel-uses-privacy-namespace
  (testing "the sentinel the projection's walker uses is
            privacy/redacted-sentinel. Pinning the identity prevents future
            divergence (a separate sentinel would break the idempotency
            story)."
    (is (= :rf/redacted privacy/redacted-sentinel)
        "privacy/redacted-sentinel is the keyword :rf/redacted")))

;; ============================================================================
;;  rf2-dl3gx — :rf.epoch/redacted-modified-paths-count on the RAW record
;; ============================================================================
;;
;; Per Spec-Schemas §`:rf/epoch-record` and Security.md §Epoch privacy
;; posture: each assembled record carries an integer count of
;; frame-declared sensitive paths whose value differs between :db-before
;; and :db-after. Computed inside `build-record` from the RAW dbs (the
;; stored record is always raw). Closes the
;; "redact-fn ⇒ empty diff but something changed" gap for projected egress.
;;
;; Coverage matrix:
;;   G1. No sensitive paths declared → 0.
;;   G2. Sensitive path declared but value unchanged → 0.
;;   G3. Sensitive path declared and value changed → 1; rollup also true.
;;   G4. Multiple sensitive paths; some changed, some not → count.
;;   G6. Projection passes the counter through unchanged.
;;   G7. Halted record (nil :db-after) — counter handles the nil edge.

(deftest G1-no-sensitive-paths-yields-zero-count
  (testing "with no frame-declared sensitive paths registered, the
            counter is 0 (the empty-paths short-circuit)."
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/dispatch-sync [:seed] {:frame :test/main})
    (rf/dispatch-sync [:inc]  {:frame :test/main})

    (let [r (last-record :test/main)]
      (is (= 0 (:rf.epoch/redacted-modified-paths-count r))
          "no declarations → 0, regardless of how much the db changed"))))

(deftest G2-sensitive-path-unchanged-yields-zero-count
  (testing "a sensitive path is declared but its value did NOT change
            across the cascade — the counter is 0."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :seed (fn [{:keys [db]} _]
                             {:db {:auth   {:password "topsecret"}
                              :public {:counter 0}}}))
    (rf/reg-event :touch-public
                     (fn [{:keys [db]} _] {:db (update-in db [:public :counter] inc)}))
    (rf/dispatch-sync [:seed]         {:frame :test/main})
    (rf/dispatch-sync [:touch-public] {:frame :test/main})

    (let [r (last-record :test/main)]
      (is (= 0 (:rf.epoch/redacted-modified-paths-count r))
          ":auth :password value identical pre/post — counter = 0"))))

(deftest G3-sensitive-path-modified-yields-positive-count
  (testing "a sensitive path's value changed across the cascade — the
            counter is 1. The :rf.epoch/sensitive? rollup also reads true."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
    (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})

    (let [r (last-record :test/main)]
      (is (= 1 (:rf.epoch/redacted-modified-paths-count r))
          ":auth :password mutated nil → \"topsecret\" — count = 1")
      (is (true? (:rf.epoch/sensitive? r))
          "rollup also true — both signals key on the same registry"))))

(deftest G4-multiple-sensitive-paths-partial-modification
  (testing "two sensitive paths declared; one changes, the other does
            not — the counter is 1."
    (rf/reg-frame :test/main {})
    (install-two-sensitive-paths-schema! :test/main)
    (rf/reg-event :seed
                     (fn [{:keys [db]} _]
                       {:db {:auth {:password "pw-1" :token "tk-1"}}}))
    (rf/reg-event :rotate-token
                     (fn [{:keys [db]} [_ tk]] {:db (assoc-in db [:auth :token] tk)}))
    (rf/dispatch-sync [:seed]                    {:frame :test/main})
    (rf/dispatch-sync [:rotate-token "tk-fresh"] {:frame :test/main})

    (let [r (last-record :test/main)]
      (is (= 1 (:rf.epoch/redacted-modified-paths-count r))
          ":token changed (count += 1); :password unchanged (filtered)")))

  (testing "both declared paths change in the same cascade — counter is 2"
    (rf/reg-frame :test/main {})
    (install-two-sensitive-paths-schema! :test/main)
    (rf/reg-event :login-both
                     (fn [{:keys [db]} [_ pw tk]]
                       {:db (-> db
                           (assoc-in [:auth :password] pw)
                           (assoc-in [:auth :token]    tk))}))
    (rf/dispatch-sync [:login-both "topsecret" "tok-xyz"]
                      {:frame :test/main})

    (let [r (last-record :test/main)]
      (is (= 2 (:rf.epoch/redacted-modified-paths-count r))
          "both :password and :token changed — count = 2"))))

(deftest G6-projection-preserves-counter
  (testing "projected-record passes :rf.epoch/redacted-modified-paths-count
            through unchanged — the integer is structurally non-sensitive
            bookkeeping, parallel to :rf.epoch/sensitive?."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
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
  (testing "halted-destroy records may carry nil :db-before or :db-after
            (rf2-v0jwt). The counter handles the nil edge."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
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

(deftest E-projected-record-on-nil-slot-record
  (testing "a halted-destroy record carries nil :db-before / :db-after
            (rf2-v0jwt). The projection (frame/profile + identity override)
            handles the nil-slot shape without throwing and preserves the
            nil slots as nil (no fabricated values)."
    (let [partial
          {:epoch-id        99
           :frame           :test/main
           :committed-at    0
           :outcome         :halted-destroy
           :halt-reason     {:reason :rf.epoch/halted-destroy}
           :db-before       nil
           :db-after        nil
           :trace-events    []
           :sub-runs        []
           :renders         []
           :effects         []
           :rf.epoch/sensitive? false}
          projected (epoch/projected-record partial)]
      (is (some? projected) "projection over a nil-slot record returns
                             a non-nil value")
      (is (nil? (:db-before projected)) "nil :db-before stays nil")
      (is (nil? (:db-after projected))  "nil :db-after stays nil")
      (is (= :halted-destroy (:outcome projected))
          "bookkeeping passes through unchanged")
      (is (= projected (epoch/projected-record projected))
          "idempotent under a second projection pass"))))

(deftest E-projected-record-on-record-with-sentinel-db-after
  (testing "a record whose :db-after is itself the :rf/redacted scalar —
            the projection MUST preserve the scalar (no walk, no warning,
            no marker)"
    ;; EP-0015 issue 1 (rf2-t55hxg.18) — `projected-record` is an EGRESS
    ;; projection: each payload slot is walked against the record's frame
    ;; (`:test/main`). The frame must RESOLVE to a live frame for its
    ;; (empty) classification policy to be consulted; an unresolvable frame
    ;; FAILS CLOSED and redacts the whole slot. The frame is registered with
    ;; an empty (no-declaration) policy here exactly as every sibling test
    ;; in this file does, so the public `{:n 0}` slot walks through verbatim.
    (rf/reg-frame :test/main {})
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
;;  F. projected-history over a ring (raw stored; projected at egress)
;; ============================================================================

(deftest F-projected-history-iterates-ring-in-order
  (testing "projected-history walks the ring in oldest-first order and
            applies projected-record (frame/profile + override) to each
            entry. The ring carries RAW records throughout; the projection
            redacts at egress. Ordering and shape are preserved."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))

    (rf/dispatch-sync [:seed]              {:frame :test/main})
    (rf/dispatch-sync [:login "secret-1"]  {:frame :test/main})
    ;; Install an override mid-session — it only affects the PROJECTION,
    ;; never the already-raw ring.
    (rf/configure! {:epoch-history {:redact-fn (fn [r]
                                (cond-> r
                                  (get-in r [:db-after :session :token])
                                  (assoc-in [:db-after :session :token]
                                            :rf/redacted)))}})
    (rf/dispatch-sync [:login "secret-2"]  {:frame :test/main})
    (rf/dispatch-sync [:login "secret-3"]  {:frame :test/main})

    (let [history (rf/epoch-history :test/main)
          ph      (epoch/projected-history :test/main)]
      (is (= (count history) (count ph))
          "one projected record per ring entry")
      (is (= (mapv :epoch-id history) (mapv :epoch-id ph))
          "ordering preserved across the projection pass")

      (testing "every projected record's password slot is nil or
                :rf/redacted — the frame-declared path is redacted by the
                frame/profile projection on every record, regardless of when
                the override was installed"
        (doseq [r ph]
          (let [pw (get-in r [:db-after :auth :password])]
            (is (or (nil? pw) (= :rf/redacted pw))
                (str "epoch-id " (:epoch-id r) " password slot: " pw)))))

      (testing "the RAW ring still carries the verbatim secrets (storage
                is never redacted)"
        (doseq [r history]
          (let [pw (get-in r [:db-after :auth :password])]
            (is (or (nil? pw) (string? pw))
                "ring password slot is the raw string (or absent)"))))

      (testing "projected-history is idempotent — re-projecting each
                entry produces a structurally-equal vector"
        (is (= ph (mapv epoch/projected-record ph)))))))

(deftest F-projected-history-empty-with-redact-fn-installed
  (testing "with a redact-fn installed but no cascades recorded,
            projected-history is the empty vector — the override is inert
            until a record exists to project"
    (rf/configure! {:epoch-history {:redact-fn (fn [r] r)}})
    (is (= [] (epoch/projected-history :rf/no-such-frame)))))

;; ============================================================================
;;  Composition fixpoint — the core idempotency claim
;; ============================================================================

(deftest fixpoint-project-twice-equals-project-once
  (testing "the core composition claim: (project (project r)) = (project r).
            The first projection lands frame-declared sensitive paths on
            :rf/redacted (frame/profile) and the override's leaf on
            :rf/redacted; the second projection finds the same sentinels and
            produces no further change."
    (rf/reg-frame :test/main {})
    (install-sensitive-schema! :test/main)          ;; :password declared
    (rf/configure! {:epoch-history {:redact-fn (fn [r]
                                ;; The override scrubs a non-declared slot.
                                (cond-> r
                                  (get-in r [:db-after :session :token])
                                  (assoc-in [:db-after :session :token]
                                            :rf/redacted)))}})
    (rf/reg-event :login
                     (fn [{:keys [db]} [_ pw tk]]
                       {:db (-> db
                           (assoc-in [:auth :password] pw)
                           (assoc-in [:session :token] tk))}))
    (rf/dispatch-sync [:login "topsecret" "tok-xyz"] {:frame :test/main})

    (let [r          (last-record :test/main)
          projected1 (epoch/projected-record r)
          projected2 (epoch/projected-record projected1)
          projected3 (epoch/projected-record projected2)]
      (is (= projected1 projected2)
          "second projection pass is a no-op (fixpoint)")
      (is (= projected2 projected3)
          "third projection pass is also a no-op — the fixpoint is stable")
      (is (= :rf/redacted (get-in projected1 [:db-after :auth :password])))
      (is (= :rf/redacted (get-in projected1 [:db-after :session :token]))))))
