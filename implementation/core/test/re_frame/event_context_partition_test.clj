(ns re-frame.event-context-partition-test
  "EP-0001 — event-context partition keys, the effect-map's `:rf.db/runtime`
  key, and the runtime-effect dev diagnostic.

  Pins the event-CONTEXT contract:

    1. The `:db` coeffect means app-db (NOT the whole frame).
    2. `:rf.db/runtime` is present in the event context (per Spec 002
       §Event context threads both partitions) and reads the frame's
       runtime-db partition (`{}` on a fresh frame). The `:rf.frame/id`
       stamp and the exact coeffect key set are
       `re-frame.event-context-coeffect-keys-test`'s.
    3. The closed effect-map admits `:rf.db/runtime` (per
       Spec-Schemas §:rf/effect-map, whose closed set is SEVEN keys — `:db`,
       `:rf.db/runtime`, `:fx` and the four EP-0025 commit-plane
       classification effects): a `:rf.db/runtime` effect is NOT a shape
       error, while a foreign top-level key is (a handler's own return in
       `re-frame.fx-test`, an interceptor's in the deftests below).
    4. `:rf.warning/app-handler-runtime-effect` fires when an ORDINARY app
       handler returns a `:rf.db/runtime` effect, and DOES NOT fire for a
       framework-authority handler (`:rf/machine? true`) — reserved BY
       CONVENTION, not a security boundary. The effect is applied either
       way (the diagnostic is a warning, not a gate).

  ## Posture split

  Every diagnostic this file reads — the
  `:rf.warning/app-handler-runtime-effect` warning and the
  `:rf.error/effect-map-shape`, `:rf.error/legacy-runtime-root` and
  `:rf.error/handler-exception` errors — is read off the TRACE bus, which
  carries nothing under `-Dre-frame.debug=false`, so those assertions are
  guarded. (`:rf.error/effect-map-shape` and `:rf.error/legacy-runtime-root`
  are catalogued always-on in Spec 009 and also reach the always-on
  `:errors` stream; this file does not read that stream.)

  THE POLICING ITSELF IS NOT DEV-ONLY, and that is the whole point of the file:
  a foreign top-level effect key REFUSES the event, a non-sequential `:fx`
  REFUSES it too — both abort PRE-COMMIT, so no `:db` lands beside them and
  `do-fx` never runs to throw a raw host exception — and a legacy
  `:rf/runtime` root is REJECTED WHOLE — in every posture. A drop that
  committed the `:db` anyway would be a partial-success disguise. (A
  malformed ENTRY inside a well-shaped `:fx` vector is the one case that
  recovers per-entry — it is post-commit, on the best-effort do-fx plane.)
  Each case has an always-on assertion on exactly that: what committed, what
  did not, and that the drain survived. Read the file with the guards on and
  it still says everything that matters about production behaviour; what it
  loses is the narration.

  A negative over an empty trace ring passes vacuously, so the two deftests
  whose dev-only assertions certify that nothing was emitted —
  `framework-authority-runtime-effect-commits-silently` and
  `well-shaped-final-effects-emit-no-shape-error` — each also carry an
  always-on commit witness, which is all they have to say under the gate.

  ONE ASYMMETRY IS WORTH RECORDING RATHER THAN PAPERING OVER.
  The no-warning half of `framework-authority-runtime-effect-commits-silently`
  has NO production counterpart even in principle: the diagnostic is
  `:recovery :warned`, so the effect applies identically whether it fires or
  not (convention, not enforcement). Its always-on residue can only be that
  the write landed, which is true of the warned case too. That is the honest
  state of the contract, not a gap in the test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.events :as rf.events]
            [re-frame.frame :as rf.frame]
            [re-frame.interceptor :as rf.interceptor]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (when-let [clear-schemas! (rf.late-bind/get-fn :schemas/clear-by-frame!)]
    (clear-schemas!))
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
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
    (rf/make-frame {:id :ctx/db-is-app-db :doc "ctx"})
    (rf/reg-event :ctx/seed (fn [{:keys [db]} [_ db]] {:db db}))
    (rf/dispatch-sync [:ctx/seed {:user/id 42}] {:frame :ctx/db-is-app-db})
    (let [captured (atom nil)]
      (rf/reg-interceptor :ctx/capture-probe
        {:before (fn [ctx] (reset! captured (:coeffects ctx)) ctx)})
      (rf/reg-event :ctx/capture
        {:interceptors [:ctx/capture-probe]}
        (fn [_ _] {}))
      (rf/dispatch-sync [:ctx/capture] {:frame :ctx/db-is-app-db})
      (let [cofx @captured]
        (is (= {:user/id 42} (:db cofx))
            ":db is the plain app-db map")
        (is (= (rf/app-db-value :ctx/db-is-app-db) (:db cofx))
            ":db equals app-db-value (NOT the {:rf.db/app … :rf.db/runtime …} frame-state)")))))

(deftest runtime-db-coeffect-reads-the-runtime-partition
  (testing ":rf.db/runtime is threaded into the event context and reads the frame's runtime-db partition"
    (rf/make-frame {:id :ctx/partitions :doc "ctx"})
    (let [captured (atom nil)]
      (rf/reg-interceptor :ctx/capture-probe
        {:before (fn [ctx] (reset! captured (:coeffects ctx)) ctx)})
      (rf/reg-event :ctx/capture
        {:interceptors [:ctx/capture-probe]}
        (fn [_ _] {}))
      (rf/dispatch-sync [:ctx/capture] {:frame :ctx/partitions})
      (let [cofx @captured]
        (is (= {} (:rf.db/runtime cofx))
            ":rf.db/runtime reads the real (fresh {}) runtime-db partition")
        (is (= (:rf.db/runtime (rf/frame-state-value :ctx/partitions)) (:rf.db/runtime cofx))
            ":rf.db/runtime coeffect equals runtime-db-value")))))

;; ===========================================================================
;; 3 — the effect-map admits :rf.db/runtime (inside the closed set, which is
;;     the seven keys #{:db :rf.db/runtime :fx :sensitive :large
;;     :clear-sensitive :clear-large})
;; ===========================================================================

(deftest framework-authority-runtime-effect-commits-silently
  (testing "a framework-authority handler (:rf/machine? true) returning
            :rf.db/runtime commits it with no shape error, no
            app-handler-runtime diagnostic and no legacy-root throw"
    (rf/make-frame {:id :ctx/fw-authority :doc "ctx"})
    (let [recorded (record-traces! ::fw-quiet)]
      ;; :rf/machine? marks framework-write authority (machine registrar mints
      ;; framework-authority handlers — Spec 002 §Write authority).
      (rf/reg-event :ctx/fw-emits-runtime
        {:doc "framework-authority runtime write" :rf/machine? true}
        (fn [_ _] {:rf.db/runtime {:rf.runtime/machines {:m 1}} :fx []}))
      (rf/dispatch-sync [:ctx/fw-emits-runtime] {:frame :ctx/fw-authority})
      ;; ALWAYS-ON: the commit is the production statement of the closed-set
      ;; and legacy-root claims — a policed key would have been dropped with
      ;; the event refused, and a legacy-root throw would have aborted the
      ;; write. The ABSENCE of the app-handler nudge is a dev-posture fact
      ;; only (see the ns docstring), and every negative below is vacuous
      ;; under the gate.
      (is (= {:rf.runtime/machines {:m 1}}
             (:rf.db/runtime (rf/frame-state-value :ctx/fw-authority)))
          "the framework-authority :rf.db/runtime write committed")
      (when rf.interop/debug-enabled?
        (is (empty? (error-events recorded :rf.error/effect-map-shape))
            ":rf.db/runtime is inside the closed set — no shape error")
        (is (empty? (warning-events recorded :rf.warning/app-handler-runtime-effect))
            "the framework-authority path is in-bounds — no diagnostic")
        (is (empty? (error-events recorded :rf.error/handler-exception))
            "writing the :rf.db/runtime partition is legitimate — no legacy-root throw")))))

;; ===========================================================================
;; 4 — :rf.warning/app-handler-runtime-effect diagnostic
;; ===========================================================================

(deftest app-handler-runtime-effect-warns
  (testing "an ORDINARY app handler returning :rf.db/runtime fires the dev diagnostic"
    (rf/make-frame {:id :ctx/app-runtime :doc "ctx"})
    (let [recorded (record-traces! ::app-warn)]
      (rf/reg-event :ctx/app-emits-runtime
        (fn [_ _] {:rf.db/runtime {:rf.runtime/routing {}}}))
      (rf/dispatch-sync [:ctx/app-emits-runtime] {:frame :ctx/app-runtime})
      ;; ALWAYS-ON: `:recovery :warned` means the write is NOT gated — the
      ;; app handler's runtime effect applies in production, where no
      ;; diagnostic exists to nudge anyone. That is the load-bearing half of
      ;; the convention-not-enforcement contract.
      (is (= {:rf.runtime/routing {}}
             (:rf.db/runtime (rf/frame-state-value :ctx/app-runtime)))
          "the effect applied anyway — the diagnostic is a warning, not a gate")
      (when rf.interop/debug-enabled?
        (let [warns (warning-events recorded :rf.warning/app-handler-runtime-effect)]
          (is (= 1 (count warns))
              "exactly one :rf.warning/app-handler-runtime-effect for the non-framework writer")
          (let [t (:tags (first warns))]
            (is (= :ctx/app-emits-runtime (:rf.trace/event-id t)))
            (is (= [:ctx/app-emits-runtime] (:rf.event/v t)))
            (is (= :ctx/app-runtime (:frame t))
                ":frame tag is the running frame (read from the :rf.frame/id coeffect)")
            (is (re-find #"rf\.db/runtime" (:reason t))))
          (is (= :warned (:recovery (first warns)))
              "recovery is :warned — convention, not enforcement"))))))

;; The framework-authority (:rf/machine? true) counterpart, which does NOT
;; warn, is `framework-authority-runtime-effect-commits-silently` above; an
;; ordinary handler that returns no :rf.db/runtime stays silent in
;; `well-shaped-final-effects-emit-no-shape-error` below.

;; ===========================================================================
;; EP-0001 — legacy :rf/runtime root is a HARD ERROR
;; ===========================================================================
;;
;; Per Conventions §The legacy :rf/runtime root — hard error in final form:
;; `:rf/runtime` is not an app-db root key. A handler whose `:db`
;; effect carries a top-level `:rf/runtime` key THROWS
;; `:rf.error/legacy-runtime-root`. Framework runtime state lives in the
;; runtime-db partition (`:rf.db/runtime`), never under an app-db root.

(deftest reject-legacy-runtime-root-throws-on-stray-key
  (testing "the guard fn throws :rf.error/legacy-runtime-root when app-db carries :rf/runtime"
    (let [thrown (try
                   (rf.events/reject-legacy-runtime-root!
                     {:user/id 1 :rf/runtime {:rf.runtime/machines {}}}
                     [:some/event])
                   ::no-throw
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (= :rf.error/legacy-runtime-root (:rf.error/id (ex-data thrown)))
          "ex-data carries :rf.error/id :rf.error/legacy-runtime-root")
      (is (= :some/event (:event-id (ex-data thrown)))
          "ex-data names the offending event-id")
      (is (= :rf/runtime (:offending-key (ex-data thrown)))
          "ex-data names :rf/runtime as the offending key"))))

(deftest db-handler-returning-legacy-runtime-root-surfaces-hard-error
  (testing "a reg-event handler whose {:db ...} return carries a :rf/runtime root surfaces :rf.error/legacy-runtime-root"
    (rf/make-frame {:id :ctx/legacy-db :doc "ctx"})
    (let [recorded (record-traces! ::legacy-db)]
      (rf/reg-event :ctx/writes-legacy-root
        (fn [{:keys [db]} _] {:db (assoc db :rf/runtime {:rf.runtime/machines {:m 1}})}))
      (rf/dispatch-sync [:ctx/writes-legacy-root] {:frame :ctx/legacy-db})
      ;; The throw is captured by the interceptor machinery and surfaced as
      ;; :rf.error/handler-exception carrying the original ex-info.
      (let [errs (error-events recorded :rf.error/handler-exception)
            ex   (some-> errs first :tags :exception)]
        ;; ALWAYS-ON: the REJECTION is production behaviour —
        ;; `rf.events/reject-legacy-runtime-root!` is an ungated `throw`. Only the
        ;; trace that reports it is dev-only.
        (is (not (contains? (rf/app-db-value :ctx/legacy-db) :rf/runtime))
            "the legacy :rf/runtime root never lands in app-db (hard-error rejects the write)")
        (when rf.interop/debug-enabled?
          (is (= 1 (count errs))
              "exactly one handler-exception trace for the legacy-root write")
          (is (= :rf.error/legacy-runtime-root (:rf.error/id (ex-data ex)))
              "the captured exception is :rf.error/legacy-runtime-root"))))))

;; A legitimate `:rf.db/runtime` write is NOT this error:
;; `framework-authority-runtime-effect-commits-silently` above.

;; ===========================================================================
;; FINAL-effects boundary shape policing
;; ===========================================================================
;;
;; The router consumes the FINAL `(:effects final-ctx)` AFTER the whole chain
;; ran, so an effect can arrive malformed by a route the handler-return site
;; never saw: an `:after`-interceptor mutation of the effect-map after the
;; handler already returned. `commit-fx-effects` does not police at all — it
;; projects the returned map verbatim — so the boundary below is the ONE check,
;; and it decides identically whichever route the key arrived by:
;;   - a foreign top-level effect key REFUSES the event
;;     (`:rf.error/effect-map-shape`, recovery `:fix-effect`);
;;   - a non-sequential whole `:fx` REFUSES it the same way, so `fx/do-fx`
;;     never sees a value it would throw on;
;;   - a legacy `:rf/runtime` root inserted into the final `[:effects :db]`
;;     is REJECTED (`:rf.error/legacy-runtime-root`).
;; All run BEFORE any commit (no partial commit) and in-band (the drain is
;; not aborted — downstream queued events keep draining).

(defn- after-icpt
  "A user `:after` interceptor (id `id`) applying `f` to the context."
  [id f]
  (rf.interceptor/->interceptor*
    :id     id
    :after  (fn [ctx] (f ctx))))

;; ---- :after interceptor mutating the final effects ------------------------

(deftest after-interceptor-malformed-fx-refuses-the-event-not-thrown
  (testing "an :after interceptor replacing [:effects :fx] with a non-sequential value refuses the event in-band, not a raw host throw after the :db commit"
    (rf/make-frame {:id :ctx/after-bad-fx :doc "ctx"})
    (let [recorded (record-traces! ::after-bad-fx)
          ;; The :after runs AFTER the handler-wrapper's :before, so this value
          ;; exists only in the FINAL effects map — the one boundary that checks.
          bad-fx   (after-icpt ::bad-fx
                               (fn [ctx]
                                 (rf.interceptor/assoc-effect ctx :fx :oops)))]
      (rf/reg-interceptor ::bad-fx bad-fx)
      (rf/reg-event :ctx/writes-db
        {:interceptors [::bad-fx]}
        (fn [{:keys [db]} _] {:db (assoc db :committed? true)
                              :fx []}))
      ;; A downstream event proves the drain was not abandoned by a raw throw.
      (rf/reg-event :ctx/downstream (fn [{:keys [db]} _] {:db (assoc db :downstream? true)}))
      (rf/dispatch-sync [:ctx/writes-db] {:frame :ctx/after-bad-fx})
      (rf/dispatch-sync [:ctx/downstream] {:frame :ctx/after-bad-fx})
      ;; ALWAYS-ON: "refused in-band, not thrown" is
      ;; entirely a production claim — nothing committed and the drain survived.
      ;; That is what this deftest is for; the shape error merely narrates it.
      ;; The in-band arm is load-bearing: a THROW here would escape into
      ;; `drain-emergency-release!` and take the downstream event with it.
      (let [db (rf/app-db-value :ctx/after-bad-fx)]
        (is (nil? (:committed? db))
            "no partial commit — the :db write did NOT land beside the refused :fx")
        (is (true? (:downstream? db))
            "the downstream event still drained — the refusal did not abandon the queue"))
      (when rf.interop/debug-enabled?
        (let [errs (error-events recorded :rf.error/effect-map-shape)]
          (is (= 1 (count errs))
              "exactly one shape error for the non-sequential :fx value")
          (is (= :fx (:offending-key (:tags (first errs)))))
          (is (= :fix-effect (:recovery (first errs)))))))))

(deftest after-interceptor-legacy-runtime-root-is-rejected
  (testing "an :after interceptor inserting :rf/runtime into [:effects :db] is rejected at the final boundary — never lands in app-db, drain survives"
    (rf/make-frame {:id :ctx/after-legacy :doc "ctx"})
    (let [recorded (record-traces! ::after-legacy)
          ;; Insert the legacy :rf/runtime root into the FINAL :db effect,
          ;; AFTER the in-chain `reject-legacy-runtime-root!` :before guard ran.
          legacy   (after-icpt ::legacy
                               (fn [ctx]
                                 (let [db (rf.interceptor/get-effect ctx :db)]
                                   (rf.interceptor/assoc-effect
                                     ctx :db (assoc db :rf/runtime {:rf.runtime/machines {}})))))]
      (rf/reg-interceptor ::legacy legacy)
      (rf/reg-event :ctx/clean-db
        {:interceptors [::legacy]}
        (fn [{:keys [db]} _] {:db (assoc db :user/id 7)}))
      (rf/reg-event :ctx/after-legacy-downstream (fn [{:keys [db]} _] {:db (assoc db :downstream? true)}))
      (rf/dispatch-sync [:ctx/clean-db] {:frame :ctx/after-legacy})
      (rf/dispatch-sync [:ctx/after-legacy-downstream] {:frame :ctx/after-legacy})
      ;; ALWAYS-ON: whole-effect rejection, no partial commit, and
      ;; a surviving drain — all production behaviour at the final boundary.
      ;; The `(not (contains? db :user/id))` negative is NOT vacuous here: the
      ;; sibling assertion proves the frame's app-db exists and carries
      ;; `:downstream?`, so an absent `:user/id` is a rejection, not an absence
      ;; of everything.
      (let [db (rf/app-db-value :ctx/after-legacy)]
        (is (not (contains? db :rf/runtime))
            "the legacy :rf/runtime root never lands in app-db — the whole :db effect is rejected (no commit)")
        (is (not (contains? db :user/id))
            "no partial commit — the rejected :db effect is dropped entirely")
        (is (true? (:downstream? db))
            "the drain survived the in-band rejection — downstream event still ran"))
      (when rf.interop/debug-enabled?
        (let [errs (error-events recorded :rf.error/legacy-runtime-root)]
          (is (= 1 (count errs))
              "exactly one legacy-runtime-root error at the final boundary")
          (is (= :rf/runtime (:offending-key (:tags (first errs))))))))))

;; ---- full-context (interceptor) final-effects policing --------------------
;;
;; EP-0018: full-context work is an INTERCEPTOR `:before` on a `reg-event`
;; registration, with a `context -> context` shape. These pin that effects a
;; full-context interceptor writes onto the context are governed by the final
;; boundary exactly as a handler-returned effects map would be.

(deftest full-context-interceptor-foreign-key-refuses-the-event
  (testing "a full-context interceptor whose context carries a foreign top-level effect key refuses the event at the boundary"
    (rf/make-frame {:id :ctx/ctx-foreign :doc "ctx"})
    (let [recorded (record-traces! ::ctx-foreign)]
      (rf/reg-interceptor :ctx/ctx-foreign-writes-probe
        {:before
         (fn [ctx]
           (-> ctx
               (rf.interceptor/assoc-effect :db (assoc (rf.interceptor/get-coeffect ctx :db) :ok? true))
               (rf.interceptor/assoc-effect :dispatch [:legacy/event])))})
      (rf/reg-event :ctx/ctx-foreign-writes
        {:interceptors [:ctx/ctx-foreign-writes-probe]}
        (fn [_ _] {}))
      (rf/dispatch-sync [:ctx/ctx-foreign-writes] {:frame :ctx/ctx-foreign})
      ;; ALWAYS-ON.
      (is (nil? (:ok? (rf/app-db-value :ctx/ctx-foreign)))
          "no partial commit — the legal :db did NOT land beside the foreign key")
      (when rf.interop/debug-enabled?
        (let [errs (error-events recorded :rf.error/effect-map-shape)]
          (is (= 1 (count errs))
              "the full-context interceptor's foreign :dispatch key is policed")
          (is (= :dispatch (:offending-key (:tags (first errs))))))))))

;; ---- the well-shaped hot path stays clean ---------------------------------

(deftest well-shaped-final-effects-emit-no-shape-error
  (testing "a clean reg-event {:db :fx} return ({:db .. :fx [..]}) emits NO :rf.error/effect-map-shape from the final boundary (no double-policing), and — carrying no :rf.db/runtime — no app-handler-runtime diagnostic"
    (rf/make-frame {:id :ctx/clean-final :doc "ctx"})
    (let [recorded (record-traces! ::clean-final)]
      (let [fx-ran (atom 0)]
        (rf/reg-fx :ctx/noop-fx (fn [_ _] (swap! fx-ran inc)))
        (rf/reg-event :ctx/clean
          (fn [{:keys [db]} _] {:db (assoc db :n 1)
                                :fx [[:ctx/noop-fx {}]]}))
        (rf/dispatch-sync [:ctx/clean] {:frame :ctx/clean-final})
        ;; ALWAYS-ON: "passed the final boundary untouched" means BOTH
        ;; effects survived it — the `:db` committed and the `:fx` ran. That
        ;; is the production statement of "no spurious policing"; the
        ;; empty-error-stream negative below is vacuous under the gate.
        (is (= 1 (:n (rf/app-db-value :ctx/clean-final)))
            "the :db effect committed normally")
        (is (= 1 @fx-ran)
            "the well-shaped :fx ran — nothing was dropped at the boundary")
        (when rf.interop/debug-enabled?
          (is (empty? (error-events recorded :rf.error/effect-map-shape))
              "well-shaped effects pass the final boundary untouched — no spurious / double shape error")
          (is (empty? (warning-events recorded :rf.warning/app-handler-runtime-effect))
              "no :rf.db/runtime effect ⇒ no app-handler-runtime diagnostic"))))))
