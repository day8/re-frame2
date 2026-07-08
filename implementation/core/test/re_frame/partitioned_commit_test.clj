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
    8. Atomicity: an app-db schema rollback unwinds the WHOLE transition
       (both partitions) — preserving the pre-commit-transactional /
       post-commit-best-effort fx asymmetry (Mike-ruled, unchanged)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            ;; Load the flows artefact so the rollback-survivor tests below can
            ;; register REAL `reg-flow` outputs (which install `:source :flow`
            ;; elision marks at registration time) and exercise the
            ;; source-aware rollback restore (rf2-5lo1fk / rf2-o6rsi2 /
            ;; rf2-yzsims). Requiring it publishes the `:flows/*` late-bind
            ;; hooks the drain + rollback paths consult.
            [re-frame.flows]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            ;; Load the schemas artefact so the
            ;; `:schemas/validate-app-schema!` late-bind hook is published —
            ;; the post-commit schema-rollback tests below guard on it and are
            ;; otherwise skipped when this ns runs in isolation (`-n`).
            [re-frame.schemas]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            ;; rf2-szbzei — the partition-injection mutators
            ;; (replace-runtime-db! / replace-frame-state!) are now
            ;; epoch-backed Tool-Pair writes that delegate to the epoch
            ;; artefact's late-bind hooks; load the namespace so the hooks
            ;; are published (otherwise the :on-absent :throw wrapper raises
            ;; :rf.error/epoch-artefact-missing).
            [re-frame.epoch]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (when-let [clear-schemas! (late-bind/get-fn :schemas/clear-by-frame!)]
    (clear-schemas!))
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/reg-frame :rf/default {})
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
    (rf/reg-frame :pc/shape {:doc "shape"})
    (let [fs   (frame/frame-state-container :pc/shape)
          app  (frame/app-db-container :pc/shape)
          rt   (frame/runtime-db-container :pc/shape)]
      (is (some? fs) "the physical frame-state container exists")
      (is (some? app) "the app-db projection exists")
      (is (some? rt) "the runtime-db projection exists")
      (is (= {:rf.db/app {} :rf.db/runtime {}}
             (adapter/read-container fs))
          "the physical container holds the coherent frame-state value (both partitions)")
      (is (= {} (adapter/read-container app))
          "the app-db projection derefs the :rf.db/app slice")
      (is (= {} (adapter/read-container rt))
          "the runtime-db projection derefs the :rf.db/runtime slice"))))

(deftest projections-are-read-only
  (testing "writing the app-db / runtime-db projection throws derived-container-replaced"
    (rf/reg-frame :pc/ro {:doc "ro"})
    (doseq [container [(frame/app-db-container :pc/ro)
                       (frame/runtime-db-container :pc/ro)]]
      (let [e (try (adapter/replace-container! container {:x 1}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "a projection reaction rejects replace-container!")
        (is (= :rf.error/derived-container-replaced (:rf.error/id (ex-data e)))
            "the rejection carries the canonical derived-container-replaced id")))))

;; ===========================================================================
;; 2 — ordinary :db scoped to the app-db partition (the footgun is gone)
;; ===========================================================================

(deftest ordinary-db-effect-scoped-to-app-db
  (testing "an ordinary :db effect replaces ONLY app-db; runtime-db is untouched"
    (rf/reg-frame :pc/scope {:doc "scope"})
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
    (rf/reg-frame :pc/rtfx {:doc "rtfx"})
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
    (rf/reg-frame :pc/rtop {:doc "rtop"})
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
    (rf/reg-frame :pc/both {:doc "both"})
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
           (adapter/read-container (frame/frame-state-container :pc/both)))
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
    (rf/reg-frame :pc/inval-rt {:doc "inval-rt"})
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
    (rf/reg-frame :pc/tr-app {:doc "tr-app"})
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
    (rf/reg-frame :pc/tr-rt {:doc "tr-rt"})
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
    (rf/reg-frame :pc/tr-both {:doc "tr-both"})
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
    (rf/reg-frame :pc/tr-noop {:doc "tr-noop"})
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
    (rf/reg-frame :pc/m-app {:doc "m-app"})
    (rf/replace-frame-state! :pc/m-app {:rf.db/runtime {:rf.runtime/machines {:m 1}}})
    ;; The frame-level helper exercises the internal partition write directly
    ;; (the public `rf/replace-frame-state!` epoch-delegate's full contract —
    ;; return value, synthetic epoch, failure modes, reject-bad-keys — is
    ;; exercised in the epoch artefact's own suite; here we only need the
    ;; partition-isolation effect of the low-level `re-frame.frame` writer).
    (frame/replace-app-db! :pc/m-app {:k 1})
    (is (= {:k 1} (rf/app-db-value :pc/m-app)))
    (is (= {:rf.runtime/machines {:m 1}} (:rf.db/runtime (rf/frame-state-value :pc/m-app)))
        "frame/replace-app-db! never silently replaces runtime-db (Mike ruling #10)")))

(deftest replace-frame-state-is-atomic-both-partitions
  (testing "replace-frame-state! installs both partitions in one write"
    (rf/reg-frame :pc/m-fs {:doc "m-fs"})
    (rf/replace-frame-state! :pc/m-fs {:rf.db/app {:a 1}
                                       :rf.db/runtime {:rf.runtime/routing {:r 1}}})
    (is (= {:rf.db/app {:a 1} :rf.db/runtime {:rf.runtime/routing {:r 1}}}
           (rf/frame-state-value :pc/m-fs))
        "both partitions installed coherently")))

(deftest replace-frame-state-replaces-the-whole-frame-state
  (testing "replace-frame-state! wholesale-replaces BOTH partitions of an
            existing frame-state (the full-frame install surface, Mike
            ruling #10 — NOT an app-db-only reset)"
    (rf/reg-frame :pc/m-full {:doc "m-full"})
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
    (rf/reg-frame :pc/m-ret {:doc "m-ret"})
    (is (= #{:rf.db/app} (frame/replace-app-db! :pc/m-ret {:k 1}))
        "an app-db write reports #{:rf.db/app}")
    (is (= #{} (frame/replace-app-db! :pc/m-ret {:k 1}))
        "a value-equal write reports no change (projection-equality)")
    (is (= #{:rf.db/runtime} (frame/replace-runtime-db! :pc/m-ret {:rf.runtime/machines {}}))
        "a runtime-db write reports #{:rf.db/runtime}")
    (is (nil? (frame/replace-app-db! :pc/no-such {:k 1}))
        "an unknown frame returns nil")))

;; ===========================================================================
;; 8 — atomicity: app-db schema rollback unwinds the whole transition
;; ===========================================================================

(deftest schema-rollback-unwinds-both-partitions
  (testing "an app-db schema rejection rolls back BOTH partitions to the pre-handler state"
    ;; Only run when the schemas artefact is on the classpath (optional).
    (when (late-bind/get-fn :schemas/validate-app-schema!)
      (rf/reg-frame :pc/rb {:doc "rb"})
      ;; seed a coherent pre-handler frame-state
      (rf/replace-frame-state! :pc/rb {:rf.db/app {:n 0}
                                       :rf.db/runtime {:rf.runtime/machines {:m :pre}}})
      ;; app schema (root path) demanding :n be a non-negative int — scoped to
      ;; the frame via with-frame (reg-app-schema is current-frame-scoped).
      (rf/with-frame :pc/rb
        (rf/reg-app-schema [] {:schema [:map [:n [:int {:min 0}]]]}))
      (reg-fw-runtime-handler! :pc/bad
        (fn [_ _]
          {:db {:n -5}                                   ;; violates the schema
           :rf.db/runtime {:rf.runtime/machines {:m :post}}}))
      (rf/dispatch-sync [:pc/bad] {:frame :pc/rb})
      (is (= {:n 0} (rf/app-db-value :pc/rb))
          "app-db rolled back to the pre-handler value")
      (is (= {:rf.runtime/machines {:m :pre}} (:rf.db/runtime (rf/frame-state-value :pc/rb)))
          "runtime-db ALSO rolled back — the whole transition unwinds coherently"))))

;; EP-0025 SOURCE-AWARE rollback restore — rf2-5lo1fk / rf2-fwejwc /
;; rf2-o6rsi2 / rf2-3pglag / rf2-yzsims.
;;
;; The post-commit schema-rollback arm restores the per-frame elision registry
;; SOURCE-AWARE, not by carrying the whole LIVE registry forward. Per Spec 015
;; §84 a classification `walks back atomically with the frame on a revert`, but
;; only the IN-BAND classification the rejected event produced:
;;
;;   - an in-band `:source :effect` mark (a `:sensitive` / `:large`
;;     commit-plane classification effect the rejected handler returned) MUST
;;     UNWIND with the rejected `:db` (rf2-5lo1fk);
;;   - an out-of-band `:source :flow` / `:source :machine` / `:source :route`
;;     mark — written at `reg-flow` registration time, or lowered by a subsystem
;;     during the event — MAY legitimately SURVIVE the rollback;
;;   - a `:source :flow` mark a mid-drain reentrant `reg-flow` output-path MOVE
;;     dropped from the LIVE registry is RESTORED to its pre-cascade old path
;;     (rf2-o6rsi2) — a privacy fail-open the old carry-live behaviour caused.
;;
;; The base is the PRE-CASCADE registry (the `[:rf.runtime/elision]` sub-tree of
;; the chain-start `runtime-before`), overlaid with the LIVE out-of-band
;; entries. (rf2-3pglag note: the old version of the next test FAKED a flow with
;; a `:source :effect` write and asserted it SURVIVED — which pinned the
;; rf2-5lo1fk bug. It is rewritten below: a real `:source :effect`
;; classification UNWINDS; a real `:source :flow` mark SURVIVES.)

(deftest schema-rollback-unwinds-in-band-effect-keeps-flow-mark
  (testing "a post-commit schema rollback UNWINDS the rejected event's in-band
            :source :effect classification (rf2-5lo1fk) while a real
            :source :flow mark (installed at reg-flow time, pre-existing the
            rejected event) SURVIVES (rf2-3pglag)"
    (when (and (late-bind/get-fn :schemas/validate-app-schema!)
               (late-bind/get-fn :flows/run-flows-on-db))
      (rf/reg-frame :pc/rb-srcaware {:doc "rb-srcaware"})
      ;; Seed a coherent pre-handler app-db that PASSES the schema below, so the
      ;; rollback target is `{:n 0}` (the rejected `{:n -5}` unwinds to it).
      (rf/replace-frame-state! :pc/rb-srcaware {:rf.db/app {:n 0}
                                               :rf.db/runtime {}})
      ;; A REAL flow whose output is classified sensitive. `reg-flow` installs
      ;; the `{:source :flow}` mark at REGISTRATION time, so it is present in
      ;; `runtime-before` (the chain-start snapshot) and must survive a rollback.
      (rf/reg-flow :creds {:frame :pc/rb-srcaware :inputs [[:n]] :output-path [:derived :creds] :sensitive [[:secret]]} (fn [n] {:secret n}))
      (is (= {:source :flow :flow-id :creds}
             (get (elision/sensitive-declarations :pc/rb-srcaware)
                  [:derived :creds :secret]))
          "precondition: the :source :flow mark is installed at reg-flow time")
      ;; app schema demanding :n stay a non-negative int — the bad handler
      ;; violates it, forcing the post-commit rollback.
      (rf/with-frame :pc/rb-srcaware
        (rf/reg-app-schema [] {:schema [:map [:n [:int {:min 0}]]]}))
      ;; The rejected handler returns BOTH an invalid :db AND an in-band
      ;; commit-plane :sensitive classification effect (`:source :effect`). The
      ;; schema rejects :db → the whole transition rolls back; the in-band
      ;; classification MUST unwind with it.
      (rf/reg-event :pc/bad-with-effect
        {:doc "schema-violating handler returning an in-band classification effect"}
        (fn [_ _]
          {:db        {:n -5}                            ;; violates the schema → rollback
           :sensitive [[:another-secret]]}))             ;; in-band :source :effect mark
      (rf/dispatch-sync [:pc/bad-with-effect] {:frame :pc/rb-srcaware})
      (is (= {:n 0} (rf/app-db-value :pc/rb-srcaware))
          "app-db rolled back to the pre-handler value (the schema rejection held)")
      ;; rf2-5lo1fk: the in-band :source :effect classification UNWINDS with the
      ;; rejected db (before the fix the source-blind carry-live PRESERVED it).
      (is (not (contains? (elision/sensitive-declarations :pc/rb-srcaware)
                          [:another-secret]))
          "the rejected event's in-band :source :effect classification UNWINDS on rollback (rf2-5lo1fk)")
      ;; rf2-3pglag: the pre-existing :source :flow mark SURVIVES the rollback.
      (is (= {:source :flow :flow-id :creds}
             (get (elision/sensitive-declarations :pc/rb-srcaware)
                  [:derived :creds :secret]))
          "the pre-existing :source :flow mark SURVIVES the rollback (rf2-3pglag)"))))

;; rf2-yzsims — positive rollback-survivor coverage for genuine out-of-band
;; sources LOWERED DURING the rejected event, plus the rf2-o6rsi2 reentrant
;; reg-flow output-path MOVE case (old-path mark restored).

(deftest schema-rollback-keeps-subsystem-mark-lowered-during-event
  (testing "a :source :machine declaration lowered DURING the rejected event
            (the only legitimate during-event out-of-band write under the
            post-EP-0025 model) SURVIVES the rollback while the rejected :db
            unwinds (rf2-yzsims)"
    (when (late-bind/get-fn :schemas/validate-app-schema!)
      (rf/reg-frame :pc/rb-subsys {:doc "rb-subsys"})
      (rf/replace-frame-state! :pc/rb-subsys {:rf.db/app {:n 0}
                                              :rf.db/runtime {}})
      (is (= {} (elision/sensitive-declarations :pc/rb-subsys))
          "precondition: no subsystem mark before dispatch")
      (rf/with-frame :pc/rb-subsys
        (rf/reg-app-schema [] {:schema [:map [:n [:int {:min 0}]]]}))
      ;; An :after interceptor models a subsystem (machine actor spawn / route
      ;; activation) lowering a declaration into the LIVE registry DURING the
      ;; event — out-of-band (`:source :machine`), after `runtime-before` was
      ;; captured. This is the load-bearing positive case the source-aware
      ;; restore preserves.
      (rf/reg-interceptor :pc/subsystem-mark-writer
        {:after (fn [ctx]
                  (elision/swap-elision-slot! :pc/rb-subsys
                    (fn [reg]
                      (assoc-in reg [:sensitive-declarations [:actor :token]]
                                {:source :machine :machine-id :door})))
                  ctx)})
      (rf/reg-event :pc/bad-subsys
        {:doc "schema-violating handler; the interceptor lowers a subsystem mark"
         :interceptors [:pc/subsystem-mark-writer]}
        (fn [_ _]
          {:db {:n -5}}))                                ;; violates the schema → rollback
      (rf/dispatch-sync [:pc/bad-subsys] {:frame :pc/rb-subsys})
      (is (= {:n 0} (rf/app-db-value :pc/rb-subsys))
          "app-db rolled back to the pre-handler value")
      (is (= {:source :machine :machine-id :door}
             (get (elision/sensitive-declarations :pc/rb-subsys) [:actor :token]))
          "the during-event out-of-band :source :machine mark SURVIVES the rollback (rf2-yzsims)"))))

(deftest schema-rollback-restores-moved-flow-old-path-mark
  (testing "a reentrant reg-flow output-path MOVE inside a schema-rejected
            event drops the OLD-path :source :flow mark from the LIVE registry;
            the source-aware rollback RESTORES it from the pre-cascade snapshot
            (rf2-o6rsi2) — a privacy fail-open the old carry-live caused"
    (when (and (late-bind/get-fn :schemas/validate-app-schema!)
               (late-bind/get-fn :flows/run-flows-on-db))
      (rf/reg-frame :pc/rb-move {:doc "rb-move"})
      ;; Seed a coherent pre-handler app-db that PASSES the schema below.
      (rf/replace-frame-state! :pc/rb-move {:rf.db/app {:n 0}
                                            :rf.db/runtime {}})
      ;; A real flow whose sensitive output sits at the OLD path. The mark is
      ;; installed at reg-flow time, so it is in the chain-start snapshot.
      (rf/reg-flow :mover {:frame :pc/rb-move :inputs [[:n]] :output-path [:old :creds] :sensitive [[:secret]]} (fn [n] {:secret n}))
      (is (= {:source :flow :flow-id :mover}
             (get (elision/sensitive-declarations :pc/rb-move)
                  [:old :creds :secret]))
          "precondition: the OLD-path :source :flow mark is installed")
      (rf/with-frame :pc/rb-move
        (rf/reg-app-schema [] {:schema [:map [:n [:int {:min 0}]]]}))
      ;; An :after interceptor MOVES the flow's output-path mid-cascade (a
      ;; reentrant reg-flow), so the LIVE registry drops the OLD-path mark and
      ;; installs a NEW-path one — BEFORE the post-commit schema rejection.
      (rf/reg-interceptor :pc/flow-mover
        {:after (fn [ctx]
                  (rf/reg-flow :mover {:frame :pc/rb-move :inputs [[:n]] :output-path [:new :creds] :sensitive [[:secret]]} (fn [n] {:secret n}))
                  ctx)})
      (rf/reg-event :pc/bad-move
        {:doc "schema-violating handler; the interceptor moves the flow output-path"
         :interceptors [:pc/flow-mover]}
        (fn [_ _]
          {:db {:n -5}}))                                ;; violates the schema → rollback
      (rf/dispatch-sync [:pc/bad-move] {:frame :pc/rb-move})
      (is (= {:n 0} (rf/app-db-value :pc/rb-move))
          "app-db rolled back to the pre-handler value")
      ;; rf2-o6rsi2: the OLD-path mark is restored from the pre-cascade snapshot
      ;; (the live registry had dropped it; the rolled-back db still holds the
      ;; old output value, so dropping the mark would leak it RAW).
      (is (= {:source :flow :flow-id :mover}
             (get (elision/sensitive-declarations :pc/rb-move)
                  [:old :creds :secret]))
          "the dropped OLD-path :source :flow mark is RESTORED on rollback (rf2-o6rsi2)")
      ;; The NEW-path mark — lowered out-of-band during the event — legitimately
      ;; survives too (the flow now declares it; harmless over the rolled-back db).
      (is (= {:source :flow :flow-id :mover}
             (get (elision/sensitive-declarations :pc/rb-move)
                  [:new :creds :secret]))
          "the NEW-path out-of-band :source :flow mark survives (during-event subsystem write)"))))

;; rf2-wfy2kq (P1 DATA-CORRUPTION) — the post-commit schema rollback must
;; restore the FULL prior app-db, not a path-focused SLICE.
;;
;; The mechanism the corruption rode on: a `[:rf.interceptor/path p]` handler's
;; `:before` overwrites `[:coeffects :db]` with the FOCUSED slice (the handler
;; sees only the slice), and its `:after` rewrites only `[:effects :db]` — it
;; NEVER restores `[:coeffects :db]`. The old rollback read `db-before` from
;; `(get-in final-ctx [:coeffects :db])`, so under an idiomatic path handler
;; `db-before` was the SLICE. On a post-commit schema rejection the rollback
;; then installed that slice as the WHOLE app-db, DESTROYING every key outside
;; `p`. The existing rollback tests above don't catch it because they use no
;; path interceptor, so `[:coeffects :db]` coincidentally equals the full db.
;;
;; This test exercises the REAL corruption path: a handler focused on `[:slice]`
;; with sibling state (`:keep`) living OUTSIDE the path, a root-path app schema
;; that rejects the spliced-back full db, and an assertion that the rollback
;; restored the WHOLE prior app-db — `:keep` MUST survive.
;;
;; Before the fix (`db-before` from `[:coeffects :db]`):
;;   rollback installs `{:n 0}` (the slice) as the whole app-db → `:keep` gone.
;; After the fix (`db-before` from `frame/*run-frame-state-before*`'s app
;; partition):
;;   rollback installs `{:keep :must-survive :slice {:n 0}}` (the full db).
(deftest schema-rollback-restores-full-app-db-not-path-slice
  (testing "a post-commit schema rollback under a [:rf.interceptor/path …]
            handler restores the FULL prior app-db, NOT the path slice — every
            key outside the focused path survives (rf2-wfy2kq)"
    (when (late-bind/get-fn :schemas/validate-app-schema!)
      (rf/reg-frame :pc/rb-path {:doc "rb-path"})
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
      ;; validates the full committed app-db) rejects it → post-commit rollback.
      (rf/with-frame :pc/rb-path
        (rf/reg-app-schema [] {:schema [:map
                                        [:keep  :keyword]
                                        [:slice [:map [:n [:int {:min 0}]]]]]}))
      ;; A PATH-FOCUSED handler: it sees ONLY `{:n 0}` (the [:slice] slice) and
      ;; returns `{:n -5}`. This is the idiomatic interceptor that triggers the
      ;; corruption — its `:before` overwrites [:coeffects :db] with the slice.
      (rf/reg-event :pc/path-bad
        {:doc          "path-focused schema-violating handler → rollback"
         :interceptors [[:rf.interceptor/path [:slice]]]}
        (fn [{:keys [db]} _]
          ;; precondition (inside the chain): the handler sees the SLICE only.
          (is (= {:n 0} db)
              "the path handler is focused on the [:slice] sub-db")
          {:db {:n -5}}))                                ;; violates the schema
      (rf/dispatch-sync [:pc/path-bad] {:frame :pc/rb-path})
      ;; THE REGRESSION ASSERTION: the rollback restored the FULL prior app-db,
      ;; not the path slice. Before the fix this was `{:n 0}` (the slice clobbered
      ;; the whole partition) and `:keep` was destroyed.
      (is (= {:keep :must-survive :slice {:n 0}}
             (rf/app-db-value :pc/rb-path))
          "the FULL prior app-db is restored on rollback — sibling state outside
           the focused path SURVIVES (NOT corrupted to the path slice)")
      (is (= :must-survive (:keep (rf/app-db-value :pc/rb-path)))
          "the out-of-path canary key SURVIVED the schema-rollback")
      (is (= {:rf.runtime/machines {:m :pre}} (:rf.db/runtime (rf/frame-state-value :pc/rb-path)))
          "runtime-db ALSO rolled back coherently — the whole transition unwinds"))))
