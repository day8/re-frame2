(ns re-frame.partitioned-commit-test
  "EP-0001 (rf2-adwcv6) — the partitioned COMMIT: the physical heart of the
  two-partition frame. Pins the bead-5 contract:

    1. ONE physical frame-state container; app-db / runtime-db are PROJECTION
       REACTIONS over it (decision #3). `frame-state-container` is the single
       writable cell; `app-db-container` / `runtime-db-container` are
       read-only projections.
    2. An ordinary `:db` effect is scoped to the app-db partition — it
       replaces ONLY app-db; runtime-db is untouched (the footgun is gone).
    3. A reserved `:rf.db/runtime` effect commits to the runtime-db partition
       (whole-value replacement). Operation-style runtime writes go through
       the helpers / mutators (decision #5 — both shapes supported).
    4. An app+runtime cascade installs BOTH partitions as ONE atomic
       frame-state transition (Spec 006 §Commit boundary).
    5. Projection-equality invalidation, NO dirty flags (decision #7): a
       runtime-only commit leaves the app-db projection `=` and does not
       invalidate app subs; an app-only commit leaves the runtime-db
       projection `=` and does not invalidate framework subs.
    6. `:rf.event/db-changed` stays APP-DB-ONLY (decision #6); a new
       `:rf.event/frame-state-changed` fires partition-tagged for EITHER
       partition.
    7. `replace-frame-state!` (rf2-t3lftq — API-shrink #3 consolidated the
       former `replace-app-db!` / `reset-app-db!` / `replace-runtime-db!` /
       `replace-frame-state!` family into this ONE partial-map surface) is
       a real partition write (the bead-3 :not-yet-implemented throws are
       gone).
    8. Atomicity (rf2-uhk9ko): an app-db schema rejection discards the
       WHOLE candidate transition (both partitions) BEFORE install —
       preserving the pre-commit-transactional / post-commit-best-effort
       fx asymmetry (Mike-ruled, unchanged)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            ;; Load the flows artefact so the rejection-survivor tests below
            ;; can register REAL `reg-flow` outputs (which install
            ;; `:source :flow` elision marks at registration time) and
            ;; exercise the classification × schema-rejection contract
            ;; (rf2-uhk9ko; formerly the source-aware rollback restore).
            ;; Requiring it publishes the `:flows/*` late-bind hooks the
            ;; drain consults.
            [re-frame.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            ;; Load the schemas artefact so the
            ;; `:schemas/validate-app-schema!` late-bind hook is published —
            ;; the schema-rejection tests below guard on it and are
            ;; otherwise skipped when this ns runs in isolation (`-n`).
            [re-frame.schemas]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]
            ;; rf2-szbzei — the partition-injection mutators
            ;; (replace-runtime-db! / replace-frame-state!) are now
            ;; epoch-backed Tool-Pair writes that delegate to the epoch
            ;; artefact's late-bind hooks; load the namespace so the hooks
            ;; are published (otherwise the :on-absent :throw wrapper raises
            ;; :rf.error/epoch-artefact-missing).
            [re-frame.epoch]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (when-let [clear-schemas! (rf.late-bind/get-fn :schemas/clear-by-frame!)]
    (clear-schemas!))
  (rf.trace/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces! [listener-id]
  (let [a (atom [])]
    (rf/register-listener! :trace listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- events-of [recorded operation]
  (filterv #(= operation (:operation %)) @recorded))

;; A framework-authority handler (machine registrar would mint this) can
;; emit `:rf.db/runtime` without firing the dev diagnostic. We use the same
;; `:rf/machine? true` marker the diagnostic keys on.
(defn- reg-fw-runtime-handler! [id f]
  (rf/reg-event id {:doc "framework-authority" :rf/machine? true} f))

;; ===========================================================================
;; 1 — one physical container + two projection reactions (decision #3)
;; ===========================================================================

(deftest one-physical-container-two-projections
  (testing "a frame holds one frame-state container; app-db / runtime-db are projections over it"
    (rf/make-frame {:id :pc/shape :doc "shape"})
    (let [fs   (rf.frame/frame-state-container :pc/shape)
          app  (rf.frame/app-db-container :pc/shape)
          rt   (rf.frame/runtime-db-container :pc/shape)]
      (is (some? fs) "the physical frame-state container exists")
      (is (some? app) "the app-db projection exists")
      (is (some? rt) "the runtime-db projection exists")
      (is (= {:rf.db/app {} :rf.db/runtime {}}
             (rf.substrate.adapter/read-container fs))
          "the physical container holds the coherent frame-state value (both partitions)")
      (is (= {} (rf.substrate.adapter/read-container app))
          "the app-db projection derefs the :rf.db/app slice")
      (is (= {} (rf.substrate.adapter/read-container rt))
          "the runtime-db projection derefs the :rf.db/runtime slice"))))

(deftest projections-are-read-only
  (testing "writing the app-db / runtime-db projection throws derived-container-replaced"
    (rf/make-frame {:id :pc/ro :doc "ro"})
    (doseq [container [(rf.frame/app-db-container :pc/ro)
                       (rf.frame/runtime-db-container :pc/ro)]]
      (let [e (try (rf.substrate.adapter/replace-container! container {:x 1}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "a projection reaction rejects replace-container!")
        (is (= :rf.error/derived-container-replaced (:rf.error/id (ex-data e)))
            "the rejection carries the canonical derived-container-replaced id")))))

;; ===========================================================================
;; 2 — ordinary :db scoped to the app-db partition (the footgun is gone)
;; ===========================================================================

(deftest ordinary-db-effect-scoped-to-app-db
  (testing "an ordinary :db effect replaces ONLY app-db; runtime-db is untouched"
    (rf/make-frame {:id :pc/scope :doc "scope"})
    ;; Seed a runtime-db partition (framework-authority write).
    (reg-fw-runtime-handler! :pc/seed-rt
      (fn [_ _] {:rf.db/runtime {:rf.runtime/machines {:door {:state :open}}}}))
    (rf/dispatch-sync [:pc/seed-rt] {:frame :pc/scope})
    (is (= {:rf.runtime/machines {:door {:state :open}}}
           (:rf.db/runtime (rf/frame-state-value :pc/scope))))
    ;; A fresh-map app handler — the classic footgun shape — must NOT drop
    ;; runtime-db, because :db is scoped to the app-db partition.
    (rf/reg-event :pc/fresh (fn [{:keys [db]} _] {:db {:session :anonymous}}))
    (rf/dispatch-sync [:pc/fresh] {:frame :pc/scope})
    (is (= {:session :anonymous} (rf/app-db-value :pc/scope))
        "app-db replaced wholesale")
    (is (= {:rf.runtime/machines {:door {:state :open}}}
           (:rf.db/runtime (rf/frame-state-value :pc/scope)))
        "runtime-db SURVIVES a fresh-map :db return — the partition footgun is structurally gone")))

;; ===========================================================================
;; 3 — runtime-db commit (both write shapes — decision #5)
;; ===========================================================================

(deftest runtime-db-effect-whole-value-commit
  (testing "a framework :rf.db/runtime effect commits the runtime-db partition (whole-value)"
    (rf/make-frame {:id :pc/rtfx :doc "rtfx"})
    (rf/reg-event :pc/seed-app (fn [{:keys [db]} _] {:db {:app :data}}))
    (rf/dispatch-sync [:pc/seed-app] {:frame :pc/rtfx})
    (reg-fw-runtime-handler! :pc/write-rt
      (fn [_ _] {:rf.db/runtime {:rf.runtime/routing {:current {:route-id :home}}}}))
    (rf/dispatch-sync [:pc/write-rt] {:frame :pc/rtfx})
    (is (= {:rf.runtime/routing {:current {:route-id :home}}}
           (:rf.db/runtime (rf/frame-state-value :pc/rtfx)))
        "the :rf.db/runtime effect installed the runtime-db partition")
    (is (= {:app :data} (rf/app-db-value :pc/rtfx))
        "app-db is untouched by a runtime-only effect")))

(deftest runtime-db-operation-style-write
  (testing "operation-style runtime writes (via the mutator) coexist with whole-value (decision #5)"
    (rf/make-frame {:id :pc/rtop :doc "rtop"})
    ;; whole-value seed
    (rf/replace-frame-state! :pc/rtop {:rf.db/runtime {:rf.runtime/machines {:a 1}}})
    (is (= {:rf.runtime/machines {:a 1}} (:rf.db/runtime (rf/frame-state-value :pc/rtop))))
    ;; operation-style update over the partition (read-modify-write)
    (rf/replace-frame-state! :pc/rtop {:rf.db/runtime (assoc (:rf.db/runtime (rf/frame-state-value :pc/rtop))
                                   :rf.runtime/routing {:current {:route-id :x}})})
    (is (= {:rf.runtime/machines {:a 1}
            :rf.runtime/routing {:current {:route-id :x}}}
           (:rf.db/runtime (rf/frame-state-value :pc/rtop)))
        "an operation-style write merges into the existing runtime-db partition")))

;; ===========================================================================
;; 4 — atomic cross-partition commit (Spec 006 §Commit boundary)
;; ===========================================================================

(deftest atomic-cross-partition-commit
  (testing "an app+runtime cascade installs both partitions as ONE coherent transition"
    (rf/make-frame {:id :pc/both :doc "both"})
    (reg-fw-runtime-handler! :pc/app-and-rt
      (fn [{:keys [db]} _]
        {:db (assoc db :page :account)
         :rf.db/runtime {:rf.runtime/routing {:current {:route-id :account}}}}))
    (rf/dispatch-sync [:pc/app-and-rt] {:frame :pc/both})
    (is (= {:page :account} (rf/app-db-value :pc/both))
        "app-db partition committed")
    (is (= {:rf.runtime/routing {:current {:route-id :account}}}
           (:rf.db/runtime (rf/frame-state-value :pc/both)))
        "runtime-db partition committed")
    ;; The physical container holds the coherent both-partition value — there
    ;; is no window where one partition is committed and the other is not.
    (is (= {:rf.db/app {:page :account}
            :rf.db/runtime {:rf.runtime/routing {:current {:route-id :account}}}}
           (rf.substrate.adapter/read-container (rf.frame/frame-state-container :pc/both)))
        "both partitions are present in the single physical frame-state value")))

;; ===========================================================================
;; 5 — projection-equality invalidation: NO dirty flags (decision #7)
;; ===========================================================================

(deftest runtime-only-commit-does-not-invalidate-app-subs
  (testing "a runtime-only commit leaves app-db `=` and does not recompute app subs"
    ;; Use :rf/default + with-frame so subscribe resolves a derefable reaction
    ;; (the {:frame …} subscribe opt has a separate JVM resolution path).
    (rf/reg-event :pc/seed (fn [{:keys [db]} _] {:db {:n 1}}))
    (rf/dispatch-sync [:pc/seed])
    (let [runs (atom 0)]
      (rf/reg-sub :pc/app-sub (fn [db _] (swap! runs inc) (:n db)))
      ;; prime the sub
      (is (= 1 (rf/with-frame :rf/default @(rf/subscribe [:pc/app-sub]))))
      (let [after-prime @runs]
        ;; runtime-only commit — app-db (:rf.db/app) is value-identical
        (reg-fw-runtime-handler! :pc/touch-rt
          (fn [_ _] {:rf.db/runtime {:rf.runtime/machines {:m 1}}}))
        (rf/dispatch-sync [:pc/touch-rt])
        ;; force a deref to give the sub the chance to recompute
        (is (= 1 (rf/with-frame :rf/default @(rf/subscribe [:pc/app-sub]))))
        (is (= after-prime @runs)
            "the app sub did NOT recompute — the app-db projection stayed `=` on a runtime-only commit")
        (is (= {:rf.runtime/machines {:m 1}} (:rf.db/runtime (rf/frame-state-value :rf/default)))
            "the runtime-only commit DID land in runtime-db")))))

(deftest app-only-commit-does-not-invalidate-runtime-projection
  (testing "an app-only commit leaves runtime-db `=` (the runtime-db projection does not change)"
    (rf/make-frame {:id :pc/inval-rt :doc "inval-rt"})
    (reg-fw-runtime-handler! :pc/seed-rt2
      (fn [_ _] {:rf.db/runtime {:rf.runtime/routing {:current {:route-id :home}}}}))
    (rf/dispatch-sync [:pc/seed-rt2] {:frame :pc/inval-rt})
    (let [rt-before (:rf.db/runtime (rf/frame-state-value :pc/inval-rt))]
      ;; app-only commit
      (rf/reg-event :pc/app-write (fn [{:keys [db]} _] {:db (assoc db :touched? true)}))
      (rf/dispatch-sync [:pc/app-write] {:frame :pc/inval-rt})
      (is (true? (:touched? (rf/app-db-value :pc/inval-rt))))
      (is (identical? rt-before (:rf.db/runtime (rf/frame-state-value :pc/inval-rt)))
          "runtime-db is reference-identical across an app-only commit — no spurious runtime change"))))

;; ===========================================================================
;; 6 — change traces: db-changed app-db-only + partition-tagged frame-state-changed
;; ===========================================================================

(deftest app-only-commit-trace-shape
  (testing "an app-only commit emits db-changed AND frame-state-changed #{:app-db}"
    (rf/make-frame {:id :pc/tr-app :doc "tr-app"})
    (let [recorded (record-traces! ::tr-app)]
      (rf/reg-event :pc/app-only (fn [{:keys [db]} _] {:db (assoc db :k 1)}))
      (rf/dispatch-sync [:pc/app-only] {:frame :pc/tr-app})
      (let [dbc (events-of recorded :rf.event/db-changed)
            fsc (events-of recorded :rf.event/frame-state-changed)]
        (is (= 1 (count dbc)) "db-changed fires for an app-db change")
        (is (= 1 (count fsc)) "frame-state-changed fires too")
        (is (= #{:app-db} (:rf.event/partitions (:tags (first fsc))))
            "frame-state-changed is tagged #{:app-db}")))))

(deftest runtime-only-commit-trace-shape
  (testing "a runtime-only commit emits frame-state-changed #{:runtime-db} and NO db-changed"
    (rf/make-frame {:id :pc/tr-rt :doc "tr-rt"})
    (let [recorded (record-traces! ::tr-rt)]
      (reg-fw-runtime-handler! :pc/rt-only
        (fn [_ _] {:rf.db/runtime {:rf.runtime/machines {:m 1}}}))
      (rf/dispatch-sync [:pc/rt-only] {:frame :pc/tr-rt})
      (let [dbc (events-of recorded :rf.event/db-changed)
            fsc (events-of recorded :rf.event/frame-state-changed)]
        (is (empty? dbc)
            ":rf.event/db-changed is APP-DB-ONLY (Mike ruling #6) — NOT emitted for a runtime-only commit")
        (is (= 1 (count fsc)) "frame-state-changed fires for the runtime change")
        (is (= #{:runtime-db} (:rf.event/partitions (:tags (first fsc))))
            "frame-state-changed is tagged #{:runtime-db}")))))

(deftest both-partition-commit-trace-shape
  (testing "a both-partition commit emits db-changed + frame-state-changed #{:app-db :runtime-db}"
    (rf/make-frame {:id :pc/tr-both :doc "tr-both"})
    (let [recorded (record-traces! ::tr-both)]
      (reg-fw-runtime-handler! :pc/both-tr
        (fn [{:keys [db]} _]
          {:db (assoc db :k 1)
           :rf.db/runtime {:rf.runtime/routing {:current {:route-id :x}}}}))
      (rf/dispatch-sync [:pc/both-tr] {:frame :pc/tr-both})
      (let [dbc (events-of recorded :rf.event/db-changed)
            fsc (events-of recorded :rf.event/frame-state-changed)]
        (is (= 1 (count dbc)) "db-changed fires (app-db changed)")
        (is (= 1 (count fsc)) "frame-state-changed fires once")
        (is (= #{:app-db :runtime-db} (:rf.event/partitions (:tags (first fsc))))
            "frame-state-changed names both touched partitions")))))

(deftest no-op-partition-commit-emits-no-change-trace
  (testing "a :db effect equal to the current app-db emits neither change trace"
    (rf/make-frame {:id :pc/tr-noop :doc "tr-noop"})
    (rf/reg-event :pc/seed (fn [{:keys [db]} _] {:db {:k 1}}))
    (rf/dispatch-sync [:pc/seed] {:frame :pc/tr-noop})
    (let [recorded (record-traces! ::tr-noop)]
      ;; return the SAME app-db value
      (rf/reg-event :pc/same (fn [{:keys [db]} _] {:db db}))
      (rf/dispatch-sync [:pc/same] {:frame :pc/tr-noop})
      (is (empty? (events-of recorded :rf.event/db-changed))
          "a value-equal app-db commit is not reported as a change")
      (is (empty? (events-of recorded :rf.event/frame-state-changed))
          "neither is frame-state-changed — change derives from projection equality, no dirty flags"))))

;; ===========================================================================
;; 7 — the mutators are real (bead-3 throws are gone)
;; ===========================================================================

(deftest replace-app-db-leaves-runtime-untouched
  (testing "replace-app-db! writes only app-db; runtime-db survives"
    (rf/make-frame {:id :pc/m-app :doc "m-app"})
    (rf/replace-frame-state! :pc/m-app {:rf.db/runtime {:rf.runtime/machines {:m 1}}})
    ;; The frame-level helper exercises the internal partition write directly
    ;; (the public `rf/replace-frame-state!` epoch-delegate's full contract —
    ;; return value, synthetic epoch, failure modes, reject-bad-keys — is
    ;; exercised in the epoch artefact's own suite; here we only need the
    ;; partition-isolation effect of the low-level `re-frame.frame` writer).
    (rf.frame/replace-app-db! :pc/m-app {:k 1})
    (is (= {:k 1} (rf/app-db-value :pc/m-app)))
    (is (= {:rf.runtime/machines {:m 1}} (:rf.db/runtime (rf/frame-state-value :pc/m-app)))
        "rf.frame/replace-app-db! never silently replaces runtime-db (Mike ruling #10)")))

(deftest replace-frame-state-is-atomic-both-partitions
  (testing "replace-frame-state! installs both partitions in one write"
    (rf/make-frame {:id :pc/m-fs :doc "m-fs"})
    (rf/replace-frame-state! :pc/m-fs {:rf.db/app {:a 1}
                                       :rf.db/runtime {:rf.runtime/routing {:r 1}}})
    (is (= {:rf.db/app {:a 1} :rf.db/runtime {:rf.runtime/routing {:r 1}}}
           (rf/frame-state-value :pc/m-fs))
        "both partitions installed coherently")))

(deftest replace-frame-state-replaces-the-whole-frame-state
  (testing "replace-frame-state! wholesale-replaces BOTH partitions of an
            existing frame-state (the full-frame install surface, Mike
            ruling #10 — NOT an app-db-only reset)"
    (rf/make-frame {:id :pc/m-full :doc "m-full"})
    ;; seed a coherent pre-existing frame-state in both partitions
    (rf/replace-frame-state! :pc/m-full {:rf.db/app {:a :old}
                                         :rf.db/runtime {:rf.runtime/machines {:m :old}}})
    (is (= {:rf.db/app {:a :old} :rf.db/runtime {:rf.runtime/machines {:m :old}}}
           (rf/frame-state-value :pc/m-full)))
    ;; a full-frame replace swaps the WHOLE frame-state — both app-db AND
    ;; runtime-db are replaced wholesale, unlike an app-only partial map
    ;; (`{:rf.db/app v}`, the former replace-app-db!) which touches app-db only.
    (rf/replace-frame-state! :pc/m-full {:rf.db/app {:a :new}
                                         :rf.db/runtime {:rf.runtime/routing {:r :new}}})
    (is (= {:a :new} (rf/app-db-value :pc/m-full))
        "app-db partition fully replaced")
    (is (= {:rf.runtime/routing {:r :new}} (:rf.db/runtime (rf/frame-state-value :pc/m-full)))
        "runtime-db partition fully replaced — the old machines slice is gone")
    (is (= {:rf.db/app {:a :new} :rf.db/runtime {:rf.runtime/routing {:r :new}}}
           (rf/frame-state-value :pc/m-full))
        "the whole frame-state is the newly-installed value")))

(deftest mutators-return-changed-partition-set
  (testing "the frame-level commit helpers report which partition(s) changed"
    (rf/make-frame {:id :pc/m-ret :doc "m-ret"})
    (is (= #{:rf.db/app} (rf.frame/replace-app-db! :pc/m-ret {:k 1}))
        "an app-db write reports #{:rf.db/app}")
    (is (= #{} (rf.frame/replace-app-db! :pc/m-ret {:k 1}))
        "a value-equal write reports no change (projection-equality)")
    (is (= #{:rf.db/runtime} (rf.frame/replace-runtime-db! :pc/m-ret {:rf.runtime/machines {}}))
        "a runtime-db write reports #{:rf.db/runtime}")
    (is (nil? (rf.frame/replace-app-db! :pc/no-such {:k 1}))
        "an unknown frame returns nil")))

;; ===========================================================================
;; 8 — atomicity: app-db schema rejection discards the whole candidate
;; ===========================================================================

(deftest schema-rejection-discards-both-partitions
  (testing "an app-db schema rejection discards the WHOLE candidate before
            install — BOTH partitions keep the pre-handler state (rf2-uhk9ko)"
    ;; Only run when the schemas artefact is on the classpath (optional).
    (when (rf.late-bind/get-fn :schemas/validate-app-schema!)
      (rf/make-frame {:id :pc/rb :doc "rb"})
      ;; seed a coherent pre-handler frame-state
      (rf/replace-frame-state! :pc/rb {:rf.db/app {:n 0}
                                       :rf.db/runtime {:rf.runtime/machines {:m :pre}}})
      ;; app schema (root path) demanding :n be a non-negative int — scoped to
      ;; the frame via with-frame (reg-app-schema is current-frame-scoped).
      (rf/with-frame :pc/rb
        (rf/reg-app-schema [] [:map [:n [:int {:min 0}]]]))
      (reg-fw-runtime-handler! :pc/bad
        (fn [_ _]
          {:db {:n -5}                                   ;; violates the schema
           :rf.db/runtime {:rf.runtime/machines {:m :post}}}))
      (rf/dispatch-sync [:pc/bad] {:frame :pc/rb})
      (is (= {:n 0} (rf/app-db-value :pc/rb))
          "app-db keeps the pre-handler value — the candidate never installed")
      (is (= {:rf.runtime/machines {:m :pre}} (:rf.db/runtime (rf/frame-state-value :pc/rb)))
          "runtime-db keeps the pre-handler value too — the whole candidate
           (both partitions) was discarded coherently"))))

;; EP-0025 classification × schema rejection — rf2-uhk9ko (supersedes the
;; rf2-5lo1fk / rf2-fwejwc / rf2-o6rsi2 / rf2-3pglag / rf2-yzsims
;; SOURCE-AWARE rollback-restore overlay, deleted with the rollback arm).
;;
;; Under validate-before-install the transactional story is structural:
;;
;;   - an in-band `:source :effect` mark (a `:sensitive` / `:large`
;;     commit-plane classification effect the rejected handler returned)
;;     rides the CANDIDATE runtime-db — a rejected candidate never installs,
;;     so the mark simply never lands (the rf2-5lo1fk unwind, for free);
;;   - an out-of-band `:source :flow` / `:source :machine` / `:source :route`
;;     mark — written at `reg-flow` registration time, or lowered by a
;;     subsystem directly into the LIVE registry during the event — is NOT
;;     part of the candidate and stands untouched (the rf2-3pglag /
;;     rf2-yzsims survivors, also for free);
;;   - a reentrant `reg-flow` output-path MOVE during a rejected event is a
;;     durable re-registration: its registry reconcile (old-path claim
;;     dropped, new-path claim added) STANDS — see the move test below for
;;     the stale-old-path-value story.

(deftest schema-rejection-never-installs-in-band-effect-keeps-flow-mark
  (testing "a schema rejection discards the rejected event's in-band
            :source :effect classification with the candidate (it never
            installs — the rf2-5lo1fk unwind, now structural) while a real
            :source :flow mark (installed at reg-flow time, pre-existing the
            rejected event) stands untouched (rf2-3pglag)"
    (when (and (rf.late-bind/get-fn :schemas/validate-app-schema!)
               (rf.late-bind/get-fn :flows/run-flows-on-db))
      (rf/make-frame {:id :pc/rb-srcaware :doc "rb-srcaware"})
      ;; Seed a coherent pre-handler app-db that PASSES the schema below, so the
      ;; rollback target is `{:n 0}` (the rejected `{:n -5}` unwinds to it).
      (rf/replace-frame-state! :pc/rb-srcaware {:rf.db/app {:n 0}
                                               :rf.db/runtime {}})
      ;; A REAL flow whose output is classified sensitive. `reg-flow` installs
      ;; the `{:source :flow}` mark at REGISTRATION time, so it is present in
      ;; `runtime-before` (the chain-start snapshot) and must survive a rollback.
      (rf/reg-flow :creds {:frame :pc/rb-srcaware :inputs [[:n]] :output-path [:derived :creds] :sensitive [[:secret]]} (fn [n] {:secret n}))
      (is (= #{{:source :flow :flow-id :creds}}
             (get (rf.elision/sensitive-declarations :pc/rb-srcaware)
                  [:derived :creds :secret]))
          "precondition: the :source :flow owner is installed at reg-flow time")
      ;; app schema demanding :n stay a non-negative int — the bad handler
      ;; violates it, forcing the post-commit rollback.
      (rf/with-frame :pc/rb-srcaware
        (rf/reg-app-schema [] [:map [:n [:int {:min 0}]]]))
      ;; The rejected handler returns BOTH an invalid :db AND an in-band
      ;; commit-plane :sensitive classification effect (`:source :effect`). The
      ;; schema rejects the candidate → NOTHING installs; the in-band
      ;; classification never lands.
      (rf/reg-event :pc/bad-with-effect
        {:doc "schema-violating handler returning an in-band classification effect"}
        (fn [_ _]
          {:db        {:n -5}                            ;; violates the schema → reject
           :sensitive [[:another-secret]]}))             ;; in-band :source :effect mark
      (rf/dispatch-sync [:pc/bad-with-effect] {:frame :pc/rb-srcaware})
      (is (= {:n 0} (rf/app-db-value :pc/rb-srcaware))
          "app-db keeps the pre-handler value (the schema rejection held)")
      ;; rf2-5lo1fk (now structural): the in-band :source :effect
      ;; classification rode the rejected candidate and never installed.
      (is (not (contains? (rf.elision/sensitive-declarations :pc/rb-srcaware)
                          [:another-secret]))
          "the rejected event's in-band :source :effect classification never
           installed (rf2-5lo1fk, structural under rf2-uhk9ko)")
      ;; rf2-3pglag: the pre-existing :source :flow mark stands untouched.
      (is (= #{{:source :flow :flow-id :creds}}
             (get (rf.elision/sensitive-declarations :pc/rb-srcaware)
                  [:derived :creds :secret]))
          "the pre-existing :source :flow owner stands after the rejection (rf2-3pglag)"))))

;; rf2-yzsims — positive survivor coverage for genuine out-of-band sources
;; LOWERED DURING the rejected event, plus the reentrant reg-flow
;; output-path MOVE case (durable re-registration).

(deftest schema-rejection-keeps-subsystem-mark-lowered-during-event
  (testing "a :source :machine declaration lowered DURING the rejected event
            (the only legitimate during-event out-of-band write under the
            post-EP-0025 model) stands after the rejection — it wrote the
            LIVE registry directly, not the discarded candidate (rf2-yzsims)"
    (when (rf.late-bind/get-fn :schemas/validate-app-schema!)
      (rf/make-frame {:id :pc/rb-subsys :doc "rb-subsys"})
      (rf/replace-frame-state! :pc/rb-subsys {:rf.db/app {:n 0}
                                              :rf.db/runtime {}})
      (is (= {} (rf.elision/sensitive-declarations :pc/rb-subsys))
          "precondition: no subsystem mark before dispatch")
      (rf/with-frame :pc/rb-subsys
        (rf/reg-app-schema [] [:map [:n [:int {:min 0}]]]))
      ;; An :after interceptor models a subsystem (machine actor spawn / route
      ;; activation) lowering a declaration into the LIVE registry DURING the
      ;; event — out-of-band (`:source :machine`). The live registry is not
      ;; part of the discarded candidate, so the mark stands structurally.
      (rf/reg-interceptor :pc/subsystem-mark-writer
        {:after (fn [ctx]
                  (rf.elision/swap-elision-slot! :pc/rb-subsys
                    (fn [reg]
                      (rf.elision/add-claims (or reg {}) :sensitive-declarations
                                          {:source :machine :machine-id :door}
                                          [[:actor :token]])))
                  ctx)})
      (rf/reg-event :pc/bad-subsys
        {:doc "schema-violating handler; the interceptor lowers a subsystem mark"
         :interceptors [:pc/subsystem-mark-writer]}
        (fn [_ _]
          {:db {:n -5}}))                                ;; violates the schema → reject
      (rf/dispatch-sync [:pc/bad-subsys] {:frame :pc/rb-subsys})
      (is (= {:n 0} (rf/app-db-value :pc/rb-subsys))
          "app-db keeps the pre-handler value")
      (is (= #{{:source :machine :machine-id :door}}
             (get (rf.elision/sensitive-declarations :pc/rb-subsys) [:actor :token]))
          "the during-event out-of-band :source :machine owner stands after
           the rejection (rf2-yzsims)"))))

(deftest schema-rejection-keeps-flow-move-reconcile
  (testing "a reentrant reg-flow output-path MOVE inside a schema-rejected
            event is a DURABLE re-registration: its live-registry reconcile
            (old-path claim dropped, new-path claim added) stands — the move
            is out-of-band, not part of the discarded candidate (rf2-uhk9ko;
            supersedes the rf2-o6rsi2 old-path-mark restore, which existed
            only because the retired rollback arm re-installed the
            pre-handler db while the live registry had moved on; with no
            install there is no restored-value/moved-mark divergence to
            patch — the re-recorded abandoned-path vacation clears any stale
            old-path VALUE on the next clean commit, per rf2-1b8yxb)"
    (when (and (rf.late-bind/get-fn :schemas/validate-app-schema!)
               (rf.late-bind/get-fn :flows/run-flows-on-db))
      (rf/make-frame {:id :pc/rb-move :doc "rb-move"})
      ;; Seed a coherent pre-handler app-db that PASSES the schema below.
      (rf/replace-frame-state! :pc/rb-move {:rf.db/app {:n 0}
                                            :rf.db/runtime {}})
      ;; A real flow whose sensitive output sits at the OLD path. The mark is
      ;; installed at reg-flow time, so it is in the chain-start snapshot.
      (rf/reg-flow :mover {:frame :pc/rb-move :inputs [[:n]] :output-path [:old :creds] :sensitive [[:secret]]} (fn [n] {:secret n}))
      (is (= #{{:source :flow :flow-id :mover}}
             (get (rf.elision/sensitive-declarations :pc/rb-move)
                  [:old :creds :secret]))
          "precondition: the OLD-path :source :flow owner is installed")
      (rf/with-frame :pc/rb-move
        (rf/reg-app-schema [] [:map [:n [:int {:min 0}]]]))
      ;; An :after interceptor MOVES the flow's output-path mid-cascade (a
      ;; reentrant reg-flow), so the LIVE registry drops the OLD-path mark and
      ;; installs a NEW-path one — then the candidate validation rejects.
      (rf/reg-interceptor :pc/flow-mover
        {:after (fn [ctx]
                  (rf/reg-flow :mover {:frame :pc/rb-move :inputs [[:n]] :output-path [:new :creds] :sensitive [[:secret]]} (fn [n] {:secret n}))
                  ctx)})
      (rf/reg-event :pc/bad-move
        {:doc "schema-violating handler; the interceptor moves the flow output-path"
         :interceptors [:pc/flow-mover]}
        (fn [_ _]
          {:db {:n -5}}))                                ;; violates the schema → reject
      (rf/dispatch-sync [:pc/bad-move] {:frame :pc/rb-move})
      (is (= {:n 0} (rf/app-db-value :pc/rb-move))
          "app-db keeps the pre-handler value")
      ;; The move's registry reconcile is DURABLE (a re-registration is not
      ;; part of the rejected candidate): the OLD-path claim stays dropped —
      ;; the same posture as a reg-flow move outside any dispatch, whose
      ;; queued abandoned-path vacation clears the old-path value next drain.
      (is (not (contains? (rf.elision/sensitive-declarations :pc/rb-move)
                          [:old :creds :secret]))
          "the OLD-path :source :flow claim stays dropped — the reentrant
           move's reconcile is durable (out-of-band re-registration)")
      ;; The NEW-path mark — lowered out-of-band during the event — stands
      ;; (the flow now declares it; harmless over the retained db).
      (is (= #{{:source :flow :flow-id :mover}}
             (get (rf.elision/sensitive-declarations :pc/rb-move)
                  [:new :creds :secret]))
          "the NEW-path out-of-band :source :flow owner stands (during-event
           subsystem write)"))))

;; rf2-wfy2kq (P1 DATA-CORRUPTION, regression pin) — a schema rejection under
;; a `[:rf.interceptor/path p]` handler must leave the FULL app-db intact.
;;
;; History: the retired install-then-rollback arm read its restore target
;; `db-before` from `[:coeffects :db]`, which a path interceptor's `:before`
;; overwrites with the FOCUSED slice — so a rejection under a path handler
;; restored the SLICE as the whole app-db, destroying every key outside `p`.
;; rf2-wfy2kq fixed the restore source; rf2-uhk9ko then made the whole
;; corruption class STRUCTURAL: a rejected candidate never installs, so
;; there is no restore write to get wrong — the container simply keeps the
;; full pre-handler value. This test keeps the corruption path exercised
;; (path-focused handler + sibling canary + root-schema rejection) so a
;; future regression that reintroduces ANY rejection-path write is caught.
(deftest schema-rejection-preserves-full-app-db-under-path-interceptor
  (testing "a schema rejection under a [:rf.interceptor/path …] handler
            leaves the FULL prior app-db intact — every key outside the
            focused path survives (rf2-wfy2kq pin; structural under
            rf2-uhk9ko's validate-before-install)"
    (when (rf.late-bind/get-fn :schemas/validate-app-schema!)
      (rf/make-frame {:id :pc/rb-path :doc "rb-path"})
      ;; Seed a coherent pre-handler app-db with state BOTH inside AND OUTSIDE
      ;; the path the handler will focus. `:keep` is the canary — it lives
      ;; outside `[:slice]`, so a slice-as-whole-db rollback would destroy it.
      (rf/replace-frame-state! :pc/rb-path
                               {:rf.db/app    {:keep  :must-survive
                                               :slice {:n 0}}
                                :rf.db/runtime {:rf.runtime/machines {:m :pre}}})
      ;; Root-path app schema: demands :keep stay present AND :slice.n a
      ;; non-negative int. The path handler's bad write makes :slice.n -5; the
      ;; path :after splices that back into the FULL db, so the schema (which
      ;; validates the full CANDIDATE app-db) rejects it → candidate discarded.
      (rf/with-frame :pc/rb-path
        (rf/reg-app-schema [] [:map
                               [:keep  :keyword]
                               [:slice [:map [:n [:int {:min 0}]]]]]))
      ;; A PATH-FOCUSED handler: it sees ONLY `{:n 0}` (the [:slice] slice) and
      ;; returns `{:n -5}`. This is the idiomatic interceptor that triggers the
      ;; corruption — its `:before` overwrites [:coeffects :db] with the slice.
      (rf/reg-event :pc/path-bad
        {:doc          "path-focused schema-violating handler → rejection"
         :interceptors [[:rf.interceptor/path [:slice]]]}
        (fn [{:keys [db]} _]
          ;; precondition (inside the chain): the handler sees the SLICE only.
          (is (= {:n 0} db)
              "the path handler is focused on the [:slice] sub-db")
          {:db {:n -5}}))                                ;; violates the schema
      (rf/dispatch-sync [:pc/path-bad] {:frame :pc/rb-path})
      ;; THE REGRESSION ASSERTION: the FULL prior app-db is intact — no
      ;; rejection-path write occurred, so the path slice can no longer
      ;; clobber the whole partition (the historical rf2-wfy2kq corruption).
      (is (= {:keep :must-survive :slice {:n 0}}
             (rf/app-db-value :pc/rb-path))
          "the FULL prior app-db is intact after the rejection — sibling
           state outside the focused path SURVIVES")
      (is (= :must-survive (:keep (rf/app-db-value :pc/rb-path)))
          "the out-of-path canary key SURVIVED the schema rejection")
      (is (= {:rf.runtime/machines {:m :pre}} (:rf.db/runtime (rf/frame-state-value :pc/rb-path)))
          "runtime-db is intact too — the whole candidate was discarded"))))
