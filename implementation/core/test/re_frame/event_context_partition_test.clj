(ns re-frame.event-context-partition-test
  "EP-0001 (rf2-bvwoi4) — event-context partition keys + effect-map widening
  + the runtime-effect dev diagnostic.

  Pins the event-CONTEXT contract introduced by bead 4 (the partitioned
  COMMIT is bead 5 / rf2-adwcv6):

    1. `:db` coeffect KEEPS meaning app-db (NOT the whole frame).
    2. `:rf.db/runtime` + `:rf.frame/id` are present in the event context
       (per Spec 002 §Event context threads both partitions). `:rf.db/runtime`
       reads `nil` until the physical partition lands in bead 5.
    3. The closed effect-map is widened to `#{:db :rf.db/runtime :fx}`
       (per Spec-Schemas §:rf/effect-map): a `:rf.db/runtime` effect is NOT a
       shape error, while a foreign top-level key still is.
    4. `:rf.warning/app-handler-runtime-effect` fires when an ORDINARY app
       handler returns a `:rf.db/runtime` effect, and DOES NOT fire for a
       framework-authority handler (`:rf/machine? true`) — reserved BY
       CONVENTION, not a security boundary (Mike ruling #4). The effect is
       applied either way (the diagnostic is a warning, not a gate)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.events :as events]
            [re-frame.frame :as frame]
            [re-frame.interceptor :as interceptor]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (when-let [clear-schemas! (late-bind/get-fn :schemas/clear-by-frame!)]
    (clear-schemas!))
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces! [listener-id]
  (let [a (atom [])]
    (rf/register-listener! :trace listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- warning-events [recorded operation]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= operation (:operation ev))))
           @recorded))

(defn- error-events [recorded operation]
  (filterv (fn [ev]
             (and (= :error (:op-type ev))
                  (= operation (:operation ev))))
           @recorded))

;; ===========================================================================
;; 1 + 2 — event-context partition keys
;; ===========================================================================

(deftest db-coeffect-is-app-db-not-whole-frame
  (testing ":db coeffect equals the app-db partition value, not the frame-state"
    (rf/reg-frame :ctx/db-is-app-db {:doc "ctx"})
    (rf/reg-event :ctx/seed (fn [{:keys [db]} [_ db]] {:db db}))
    (rf/dispatch-sync [:ctx/seed {:user/id 42}] {:frame :ctx/db-is-app-db})
    (let [captured (atom nil)]
      (rf/reg-interceptor* :ctx/capture-probe
        {:before (fn [ctx] (reset! captured (:coeffects ctx)) ctx)})
      (rf/reg-event :ctx/capture
        {:interceptors [:ctx/capture-probe]}
        (fn [_ _] {}))
      (rf/dispatch-sync [:ctx/capture] {:frame :ctx/db-is-app-db})
      (let [cofx @captured]
        (is (= {:user/id 42} (:db cofx))
            ":db is the plain app-db map")
        (is (= (rf/app-db-value :ctx/db-is-app-db) (:db cofx))
            ":db equals app-db-value (NOT the {:rf.db/app … :rf.db/runtime …} frame-state)")
        (is (not (contains? (:db cofx) :rf.db/app))
            ":db is NOT the frame-state projection")
        (is (not (contains? (:db cofx) :rf.db/runtime))
            ":db carries no runtime partition")))))

(deftest runtime-and-frame-id-coeffects-present
  (testing ":rf.db/runtime and :rf.frame/id are threaded into the event context"
    (rf/reg-frame :ctx/partitions {:doc "ctx"})
    (let [captured (atom nil)]
      (rf/reg-interceptor* :ctx/capture-probe
        {:before (fn [ctx] (reset! captured (:coeffects ctx)) ctx)})
      (rf/reg-event :ctx/capture
        {:interceptors [:ctx/capture-probe]}
        (fn [_ _] {}))
      (rf/dispatch-sync [:ctx/capture] {:frame :ctx/partitions})
      (let [cofx @captured]
        (is (contains? cofx :rf.db/runtime)
            ":rf.db/runtime coeffect is present (runtime-db partition)")
        (is (= {} (:rf.db/runtime cofx))
            ":rf.db/runtime reads the real (fresh {}) runtime-db partition (rf2-adwcv6, bead 5)")
        (is (= (rf/runtime-db-value :ctx/partitions) (:rf.db/runtime cofx))
            ":rf.db/runtime coeffect equals runtime-db-value")
        (is (= :ctx/partitions (:rf.frame/id cofx))
            ":rf.frame/id is the running frame's id (runtime-context spelling)")
        ;; rf2-1m6rf1 — the retired bare `:frame` coeffect is GONE. The
        ;; frame stamp travels under `:rf.frame/id` only in the event
        ;; context; `:frame` survives solely as the public dispatch/subscribe
        ;; opt + the dispatch envelope key (per Spec 002 §Event context).
        (is (not (contains? cofx :frame))
            "the bare :frame coeffect is no longer injected (one carrier, one name)")))))

;; ===========================================================================
;; 3 — effect-map widening (closed set #{:db :rf.db/runtime :fx})
;; ===========================================================================

(deftest runtime-db-effect-is-not-a-shape-error
  (testing "a framework-authority handler returning :rf.db/runtime emits no :rf.error/effect-map-shape"
    (rf/reg-frame :ctx/runtime-fx {:doc "ctx"})
    (let [recorded (record-traces! ::no-shape-err)]
      ;; :rf/machine? marks framework-write authority (machine registrar mints
      ;; framework-authority handlers — Spec 002 §Write authority).
      (rf/reg-event :ctx/fw-runtime
        {:doc "framework-authority runtime write" :rf/machine? true}
        (fn [_ _] {:rf.db/runtime {:rf.runtime/machines {}} :fx []}))
      (rf/dispatch-sync [:ctx/fw-runtime] {:frame :ctx/runtime-fx})
      (is (empty? (error-events recorded :rf.error/effect-map-shape))
          ":rf.db/runtime is inside the widened closed set — no shape error"))))

(deftest foreign-top-level-key-still-a-shape-error
  (testing "a foreign top-level key (legacy :http) is still policed after the widening"
    (rf/reg-frame :ctx/foreign-fx {:doc "ctx"})
    (let [recorded (record-traces! ::foreign-err)]
      (rf/reg-event :ctx/foreign
        (fn [_ _] {:db {:ok? true} :http {:url "/api"}}))
      (rf/dispatch-sync [:ctx/foreign] {:frame :ctx/foreign-fx})
      (let [errs (error-events recorded :rf.error/effect-map-shape)]
        (is (= 1 (count errs))
            "exactly one shape error for the foreign :http key")
        (is (= :http (:offending-key (:tags (first errs))))
            "the offending key is :http")
        (is (= :logged-and-skipped (:recovery (first errs))))
        (is (true? (:ok? (rf/app-db-value :ctx/foreign-fx)))
            "the legal :db still committed; only :http was dropped")))))

;; ===========================================================================
;; 4 — :rf.warning/app-handler-runtime-effect diagnostic
;; ===========================================================================

(deftest app-handler-runtime-effect-warns
  (testing "an ORDINARY app handler returning :rf.db/runtime fires the dev diagnostic"
    (rf/reg-frame :ctx/app-runtime {:doc "ctx"})
    (let [recorded (record-traces! ::app-warn)]
      (rf/reg-event :ctx/app-emits-runtime
        (fn [_ _] {:rf.db/runtime {:rf.runtime/routing {}}}))
      (rf/dispatch-sync [:ctx/app-emits-runtime] {:frame :ctx/app-runtime})
      (let [warns (warning-events recorded :rf.warning/app-handler-runtime-effect)]
        (is (= 1 (count warns))
            "exactly one :rf.warning/app-handler-runtime-effect for the non-framework writer")
        (let [t (:tags (first warns))]
          (is (= :ctx/app-emits-runtime (:rf.trace/event-id t)))
          (is (= [:ctx/app-emits-runtime] (:rf.event/v t)))
          (is (= :ctx/app-runtime (:frame t))
              ":frame tag is the running frame (read from the :rf.frame/id coeffect)")
          (is (string? (:reason t)))
          (is (re-find #"rf\.db/runtime" (:reason t))))
        (is (= :warned (:recovery (first warns)))
            "recovery is :warned — convention, not enforcement")))))

(deftest framework-authority-runtime-effect-does-not-warn
  (testing "a framework-authority handler (:rf/machine? true) does NOT fire the diagnostic"
    (rf/reg-frame :ctx/fw-authority {:doc "ctx"})
    (let [recorded (record-traces! ::fw-quiet)]
      (rf/reg-event :ctx/fw-emits-runtime
        {:doc "framework-authority" :rf/machine? true}
        (fn [_ _] {:rf.db/runtime {:rf.runtime/machines {}}}))
      (rf/dispatch-sync [:ctx/fw-emits-runtime] {:frame :ctx/fw-authority})
      (is (empty? (warning-events recorded :rf.warning/app-handler-runtime-effect))
          "the framework-authority path is in-bounds — no diagnostic"))))

(deftest plain-db-fx-handler-does-not-warn
  (testing "an ordinary handler that does NOT return :rf.db/runtime stays silent"
    (rf/reg-frame :ctx/plain {:doc "ctx"})
    (let [recorded (record-traces! ::plain-quiet)]
      (rf/reg-event :ctx/plain-db
        (fn [{:keys [db]} _] {:db (assoc db :touched? true) :fx []}))
      (rf/dispatch-sync [:ctx/plain-db] {:frame :ctx/plain})
      (is (empty? (warning-events recorded :rf.warning/app-handler-runtime-effect))
          "no :rf.db/runtime effect ⇒ no diagnostic")
      (is (true? (:touched? (rf/app-db-value :ctx/plain)))
          "the :db effect committed normally"))))

;; ===========================================================================
;; EP-0001 (rf2-tfepxu, decision #8) — legacy :rf/runtime root is a HARD ERROR
;; ===========================================================================
;;
;; Per Conventions §The legacy :rf/runtime root — hard error in final form:
;; the retired app-db root key `:rf/runtime` is gone. A handler whose `:db`
;; effect carries a top-level `:rf/runtime` key THROWS
;; `:rf.error/legacy-runtime-root`. Framework runtime state lives in the
;; runtime-db partition (`:rf.db/runtime`), never under an app-db root.

(deftest reject-legacy-runtime-root-throws-on-stray-key
  (testing "the guard fn throws :rf.error/legacy-runtime-root when app-db carries :rf/runtime"
    (let [thrown (try
                   (events/reject-legacy-runtime-root!
                     {:user/id 1 :rf/runtime {:rf.runtime/machines {}}}
                     [:some/event])
                   ::no-throw
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo thrown)
          "reject-legacy-runtime-root! throws on a stray :rf/runtime root")
      (is (= :rf.error/legacy-runtime-root (:rf.error/id (ex-data thrown)))
          "ex-data carries :rf.error/id :rf.error/legacy-runtime-root")
      (is (= :some/event (:event-id (ex-data thrown)))
          "ex-data names the offending event-id")
      (is (= :rf/runtime (:offending-key (ex-data thrown)))
          "ex-data names :rf/runtime as the offending key"))))

(deftest reject-legacy-runtime-root-is-a-noop-for-clean-app-db
  (testing "the guard fn returns the value unchanged (no throw) when :rf/runtime is absent"
    (let [clean {:user/id 1 :cart {:items []}}]
      (is (= clean (events/reject-legacy-runtime-root! clean [:ok/event]))
          "a clean app-db passes through untouched")
      (is (= nil (events/reject-legacy-runtime-root! nil [:ok/event]))
          "nil (no :db effect) is a no-op"))))

(deftest db-handler-returning-legacy-runtime-root-surfaces-hard-error
  (testing "a reg-event handler whose {:db ...} return carries a :rf/runtime root surfaces :rf.error/legacy-runtime-root"
    (rf/reg-frame :ctx/legacy-db {:doc "ctx"})
    (let [recorded (record-traces! ::legacy-db)]
      (rf/reg-event :ctx/writes-legacy-root
        (fn [{:keys [db]} _] {:db (assoc db :rf/runtime {:rf.runtime/machines {:m 1}})}))
      (rf/dispatch-sync [:ctx/writes-legacy-root] {:frame :ctx/legacy-db})
      ;; The throw is captured by the interceptor machinery and surfaced as
      ;; :rf.error/handler-exception carrying the original ex-info.
      (let [errs (error-events recorded :rf.error/handler-exception)
            ex   (some-> errs first :tags :exception)]
        (is (= 1 (count errs))
            "exactly one handler-exception trace for the legacy-root write")
        (is (= :rf.error/legacy-runtime-root (:rf.error/id (ex-data ex)))
            "the captured exception is :rf.error/legacy-runtime-root")
        (is (not (contains? (rf/app-db-value :ctx/legacy-db) :rf/runtime))
            "the legacy :rf/runtime root never lands in app-db (hard-error rejects the write)")))))

(deftest fx-handler-returning-legacy-runtime-root-surfaces-hard-error
  (testing "a reg-event handler whose {:db :fx} return carries :rf/runtime in its :db effect surfaces the hard error"
    (rf/reg-frame :ctx/legacy-fx {:doc "ctx"})
    (let [recorded (record-traces! ::legacy-fx)]
      (rf/reg-event :ctx/fx-writes-legacy-root
        (fn [{:keys [db]} _] {:db (assoc db :rf/runtime {:rf.runtime/routing {}})}))
      (rf/dispatch-sync [:ctx/fx-writes-legacy-root] {:frame :ctx/legacy-fx})
      (let [errs (error-events recorded :rf.error/handler-exception)
            ex   (some-> errs first :tags :exception)]
        (is (= :rf.error/legacy-runtime-root (:rf.error/id (ex-data ex)))
            "the :fx-path :db effect with a legacy root is rejected too")
        (is (not (contains? (rf/app-db-value :ctx/legacy-fx) :rf/runtime))
            "the legacy root never commits")))))

(deftest legitimate-runtime-db-effect-is-not-a-legacy-root-error
  (testing "a framework :rf.db/runtime effect (the NEW partition) is NOT the legacy-root hard error"
    (rf/reg-frame :ctx/new-runtime {:doc "ctx"})
    (let [recorded (record-traces! ::new-runtime)]
      (rf/reg-event :ctx/fw-runtime
        {:doc "framework-authority" :rf/machine? true}
        (fn [_ _] {:rf.db/runtime {:rf.runtime/machines {:m 1}}}))
      (rf/dispatch-sync [:ctx/fw-runtime] {:frame :ctx/new-runtime})
      (is (empty? (error-events recorded :rf.error/handler-exception))
          "writing the :rf.db/runtime partition is legitimate — no legacy-root throw")
      (is (= {:rf.runtime/machines {:m 1}} (rf/runtime-db-value :ctx/new-runtime))
          "the runtime-db partition committed normally"))))

;; ===========================================================================
;; rf2-u1kdvg — FINAL-effects boundary shape policing
;; ===========================================================================
;;
;; `commit-fx-effects` polices a `reg-event` HANDLER RETURN during the
;; chain's `:before` pass — BEFORE the `:after` interceptors run. The router
;; consumes the FINAL `(:effects final-ctx)` AFTER the whole chain ran, so an
;; effect can arrive malformed by a route the per-handler-return checks never
;; saw: an `:after`-interceptor mutation of the effect-map after the handler
;; return was already validated. `events/police-final-effects!` + the router's final
;; legacy-root check close that gap:
;;   - a foreign top-level effect key is DROPPED (not silently ignored at the
;;     partition commit) with `:rf.error/effect-map-shape`;
;;   - a non-sequential whole `:fx` is DROPPED before `fx/do-fx` can throw a
;;     raw host exception AFTER the :db commit;
;;   - a legacy `:rf/runtime` root inserted into the final `[:effects :db]`
;;     is REJECTED (`:rf.error/legacy-runtime-root`) before commit.
;; All run BEFORE any commit (no partial commit) and in-band (the drain is
;; not aborted — downstream queued events keep draining).

(defn- after-icpt
  "A user `:after` interceptor (id `id`) applying `f` to the context."
  [id f]
  (interceptor/->interceptor*
    :id     id
    :after  (fn [ctx] (f ctx))))

;; ---- :after interceptor mutating the final effects ------------------------

(deftest after-interceptor-malformed-fx-is-policed-not-thrown
  (testing "an :after interceptor replacing [:effects :fx] with a non-sequential value is policed (dropped), not a raw host throw after the :db commit"
    (rf/reg-frame :ctx/after-bad-fx {:doc "ctx"})
    (let [recorded (record-traces! ::after-bad-fx)
          ;; The :after runs AFTER the handler-wrapper's :before checks, so
          ;; `commit-fx-effects`/`fx-value-ok?` never saw this value.
          bad-fx   (after-icpt ::bad-fx
                               (fn [ctx]
                                 (interceptor/assoc-effect ctx :fx :oops)))]
      (rf/reg-interceptor* ::bad-fx bad-fx)
      (rf/reg-event :ctx/writes-db
        {:interceptors [::bad-fx]}
        (fn [{:keys [db]} _] {:db (assoc db :committed? true)
                              :fx []}))
      ;; A downstream event proves the drain was not abandoned by a raw throw.
      (rf/reg-event :ctx/downstream (fn [{:keys [db]} _] {:db (assoc db :downstream? true)}))
      (rf/dispatch-sync [:ctx/writes-db] {:frame :ctx/after-bad-fx})
      (rf/dispatch-sync [:ctx/downstream] {:frame :ctx/after-bad-fx})
      (let [errs (error-events recorded :rf.error/effect-map-shape)]
        (is (= 1 (count errs))
            "exactly one shape error for the non-sequential :fx value")
        (is (= :fx (:offending-key (:tags (first errs)))))
        (is (= :logged-and-skipped (:recovery (first errs)))))
      (let [db (rf/app-db-value :ctx/after-bad-fx)]
        (is (true? (:committed? db))
            "the :db effect still committed — the bad :fx was dropped, no host throw aborted the event")
        (is (true? (:downstream? db))
            "the downstream event still drained — the malformed :fx did not abandon the queue")))))

(deftest after-interceptor-foreign-effect-key-is-policed
  (testing "an :after interceptor inserting a foreign top-level effect key is policed (dropped) — not silently ignored at the partition commit"
    (rf/reg-frame :ctx/after-foreign {:doc "ctx"})
    (let [recorded (record-traces! ::after-foreign)
          foreign  (after-icpt ::foreign
                               (fn [ctx]
                                 (interceptor/assoc-effect ctx :http {:url "/api"})))]
      (rf/reg-interceptor* ::foreign foreign)
      (rf/reg-event :ctx/writes-db2
        {:interceptors [::foreign]}
        (fn [{:keys [db]} _] {:db (assoc db :ok? true)}))
      (rf/dispatch-sync [:ctx/writes-db2] {:frame :ctx/after-foreign})
      (let [errs (error-events recorded :rf.error/effect-map-shape)]
        (is (= 1 (count errs))
            "exactly one shape error for the foreign :http key")
        (is (= :http (:offending-key (:tags (first errs)))))
        (is (= :logged-and-skipped (:recovery (first errs)))))
      (is (true? (:ok? (rf/app-db-value :ctx/after-foreign)))
          "the legal :db still committed; only the foreign :http key was dropped"))))

(deftest after-interceptor-legacy-runtime-root-is-rejected
  (testing "an :after interceptor inserting :rf/runtime into [:effects :db] is rejected at the final boundary — never lands in app-db, drain survives"
    (rf/reg-frame :ctx/after-legacy {:doc "ctx"})
    (let [recorded (record-traces! ::after-legacy)
          ;; Insert the retired :rf/runtime root into the FINAL :db effect,
          ;; AFTER the in-chain `reject-legacy-runtime-root!` :before guard ran.
          legacy   (after-icpt ::legacy
                               (fn [ctx]
                                 (let [db (interceptor/get-effect ctx :db)]
                                   (interceptor/assoc-effect
                                     ctx :db (assoc db :rf/runtime {:rf.runtime/machines {}})))))]
      (rf/reg-interceptor* ::legacy legacy)
      (rf/reg-event :ctx/clean-db
        {:interceptors [::legacy]}
        (fn [{:keys [db]} _] {:db (assoc db :user/id 7)}))
      (rf/reg-event :ctx/after-legacy-downstream (fn [{:keys [db]} _] {:db (assoc db :downstream? true)}))
      (rf/dispatch-sync [:ctx/clean-db] {:frame :ctx/after-legacy})
      (rf/dispatch-sync [:ctx/after-legacy-downstream] {:frame :ctx/after-legacy})
      (let [errs (error-events recorded :rf.error/legacy-runtime-root)]
        (is (= 1 (count errs))
            "exactly one legacy-runtime-root error at the final boundary")
        (is (= :rf/runtime (:offending-key (:tags (first errs))))))
      (let [db (rf/app-db-value :ctx/after-legacy)]
        (is (not (contains? db :rf/runtime))
            "the legacy :rf/runtime root never lands in app-db — the whole :db effect is rejected (no commit)")
        (is (not (contains? db :user/id))
            "no partial commit — the rejected :db effect is dropped entirely")
        (is (true? (:downstream? db))
            "the drain survived the in-band rejection — downstream event still ran")))))

;; ---- full-context (interceptor) final-effects policing --------------------
;;
;; EP-0018 (rf2-xhfxcs.14): full-context work is now an INTERCEPTOR `:before`
;; on a `reg-event` registration (the removed `reg-event-ctx` form's
;; `context -> context` shape transplants verbatim). These pin that effects a
;; full-context interceptor writes onto the context are policed at the final
;; boundary exactly as a handler-returned effects map would be.

(deftest full-context-interceptor-malformed-fx-is-policed
  (testing "a full-context interceptor whose context carries a non-sequential :fx is policed at the boundary"
    (rf/reg-frame :ctx/ctx-bad-fx {:doc "ctx"})
    (let [recorded (record-traces! ::ctx-bad-fx)]
      (rf/reg-interceptor* :ctx/ctx-writes-probe
        {:before
         (fn [ctx]
           (-> ctx
               (interceptor/assoc-effect :db (assoc (interceptor/get-coeffect ctx :db) :committed? true))
               (interceptor/assoc-effect :fx {:dispatch [:nope]})))})
      (rf/reg-event :ctx/ctx-writes
        {:interceptors [:ctx/ctx-writes-probe]}
        (fn [_ _] {}))
      (rf/dispatch-sync [:ctx/ctx-writes] {:frame :ctx/ctx-bad-fx})
      (let [errs (error-events recorded :rf.error/effect-map-shape)]
        (is (= 1 (count errs))
            "the full-context interceptor's malformed :fx is policed — the reg-event handler return is not the only path that gets shape policing")
        (is (= :fx (:offending-key (:tags (first errs))))))
      (is (true? (:committed? (rf/app-db-value :ctx/ctx-bad-fx)))
          "the :db effect still committed; only the bad :fx was dropped"))))

(deftest full-context-interceptor-foreign-key-is-policed
  (testing "a full-context interceptor whose context carries a foreign top-level effect key is policed at the boundary"
    (rf/reg-frame :ctx/ctx-foreign {:doc "ctx"})
    (let [recorded (record-traces! ::ctx-foreign)]
      (rf/reg-interceptor* :ctx/ctx-foreign-writes-probe
        {:before
         (fn [ctx]
           (-> ctx
               (interceptor/assoc-effect :db (assoc (interceptor/get-coeffect ctx :db) :ok? true))
               (interceptor/assoc-effect :dispatch [:legacy/event])))})
      (rf/reg-event :ctx/ctx-foreign-writes
        {:interceptors [:ctx/ctx-foreign-writes-probe]}
        (fn [_ _] {}))
      (rf/dispatch-sync [:ctx/ctx-foreign-writes] {:frame :ctx/ctx-foreign})
      (let [errs (error-events recorded :rf.error/effect-map-shape)]
        (is (= 1 (count errs))
            "the full-context interceptor's foreign :dispatch key is policed")
        (is (= :dispatch (:offending-key (:tags (first errs))))))
      (is (true? (:ok? (rf/app-db-value :ctx/ctx-foreign)))
          "the legal :db still committed; the foreign top-level key was dropped"))))

;; ---- the well-shaped hot path stays clean ---------------------------------

(deftest well-shaped-final-effects-emit-no-shape-error
  (testing "a clean reg-event {:db :fx} return ({:db .. :fx [..]}) emits NO :rf.error/effect-map-shape from the final boundary (no double-policing)"
    (rf/reg-frame :ctx/clean-final {:doc "ctx"})
    (let [recorded (record-traces! ::clean-final)]
      (rf/reg-fx :ctx/noop-fx (fn [_ _]))
      (rf/reg-event :ctx/clean
        (fn [{:keys [db]} _] {:db (assoc db :n 1)
                              :fx [[:ctx/noop-fx {}]]}))
      (rf/dispatch-sync [:ctx/clean] {:frame :ctx/clean-final})
      (is (empty? (error-events recorded :rf.error/effect-map-shape))
          "well-shaped effects pass the final boundary untouched — no spurious / double shape error")
      (is (= 1 (:n (rf/app-db-value :ctx/clean-final)))
          "the :db effect committed normally"))))
