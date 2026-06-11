(ns re-frame.world-inputs-test
  "EP-0010 §Causal World Inputs core slice (rf2-s9ss0t).

  Pins the `:rf.world/inputs` envelope + coeffect contract that makes the
  frame fold deterministic with respect to prior frame-state plus the
  causal token (Spec 002 §The World-Input Rule):

    - the router STAMPS `:rf.world/inputs {:time-ms ...}` when the caller
      omits it (Spec 002 §Dispatch Envelope Stamping);
    - a caller-supplied map is PRESERVED verbatim — including extra slots
      (`:uuid`, `:random`, browser/storage facts) — and the router never
      overwrites a supplied `:time-ms`;
    - a CHILD dispatch (`:fx [[:dispatch ...]]`) gets its OWN map: `:time-ms`
      is NOT inherited from the parent (each is a distinct causal token);
    - the value is visible to handler bodies as the `:rf.world/inputs`
      coeffect alongside `:db` / `:event` / `:rf.db/runtime` / `:rf.frame/id`
      (Spec 002 §Event Context And Coeffects);
    - it is FILTERED out of the user-cofx trace projection exactly like the
      other framework defaults (`fx/framework-coeffect-keys`);
    - `:dispatched-at` is RETIRED in the same change (rider b) — its
      diagnostic dispatch-time need is the trace event `:time` stamp.

  JVM-only — the stamping path is platform-agnostic (`interop/now-ms`
  realises on both hosts); no CLJS host dependency under test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.router :as router]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            ;; rf2-zq5zj2 / rf2-qwm0a — load the tooling sibling so the
            ;; late-bind hooks behind the listener API resolve (the
            ;; diagnostic-differs test registers a trace listener).
            [re-frame.trace.tooling]
            ;; rf2-zq5zj2 — side-effect require: `re-frame.epoch` publishes
            ;; the `:epoch/settle!` + `:epoch/epoch-history` +
            ;; `:epoch/clear-history!` late-bind hooks at ns-load, so each
            ;; drain-settle commits a `:rf/epoch-record` whose durable
            ;; `:committed-at` the diagnostic-differs test reads via
            ;; `rf/epoch-history`. Available on the core test classpath as the
            ;; epoch test-only dep (core/deps.edn :test alias, rf2-lt4e).
            [re-frame.epoch]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (when-let [clear-schemas! (late-bind/get-fn :schemas/clear-by-frame!)]
    (clear-schemas!))
  ;; rf2-zq5zj2 — clear the per-frame epoch rings so the diagnostic-differs
  ;; test reads only its own freshly-committed records (the `:epoch/settle!`
  ;; hook is published once `re-frame.epoch` is loaded, above).
  (when-let [clear-history! (late-bind/get-fn :epoch/clear-history!)]
    (clear-history!))
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

(def ^:private build-envelope
  "The private envelope builder — the dispatch envelope is not exposed to
  user handlers, so stamping/preservation is asserted directly against it."
  #'router/build-envelope)

(defn- capture-coeffects
  "Dispatch `[:capture]` on `frame-id` (threading `opts`) and return the
  coeffects map the handler saw."
  ([frame-id] (capture-coeffects frame-id nil))
  ([frame-id opts]
   (let [captured (atom nil)]
     (rf/reg-event-ctx :capture
       (fn [ctx] (reset! captured (:coeffects ctx)) ctx))
     (if opts
       (rf/dispatch-sync [:capture] (merge {:frame frame-id} opts))
       (rf/dispatch-sync [:capture] {:frame frame-id}))
     @captured)))

;; ===========================================================================
;; Envelope stamping
;; ===========================================================================

(deftest stamps-time-ms-when-absent
  (testing "the router stamps :rf.world/inputs {:time-ms ...} when the caller omits it"
    (rf/reg-frame :wi/stamp {:doc "ctx"})
    (let [env   (build-envelope [:noop] {:frame :wi/stamp})
          world (:rf.world/inputs env)]
      (is (map? world) ":rf.world/inputs is present on the envelope")
      (is (number? (:time-ms world)) ":time-ms is a stamped epoch-ms number")
      (is (= #{:time-ms} (set (keys world)))
          "only the framework-required :time-ms is stamped — no other keys invented"))))

(deftest preserves-caller-supplied-time-ms
  (testing "a caller-supplied :time-ms is preserved verbatim — the router does NOT overwrite it"
    (rf/reg-frame :wi/supplied {:doc "ctx"})
    (let [env (build-envelope [:noop]
                              {:frame :wi/supplied
                               :rf.world/inputs {:time-ms 1781078400123}})]
      (is (= 1781078400123 (get-in env [:rf.world/inputs :time-ms]))
          "the exact supplied :time-ms rides through (replay / SSR / fixtures)"))))

(deftest preserves-caller-supplied-extra-keys-and-fills-time-ms
  (testing "extra world-input slots ride through; a missing :time-ms is filled, supplied keys untouched"
    (rf/reg-frame :wi/extra {:doc "ctx"})
    (let [uuid  #uuid "018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d"
          env   (build-envelope [:noop]
                                {:frame :wi/extra
                                 :rf.world/inputs
                                 {:uuid   {:todo/id uuid}
                                  :random {:todo/color :green}}})
          world (:rf.world/inputs env)]
      (is (= {:todo/id uuid} (:uuid world)) "supplied :uuid slot preserved")
      (is (= {:todo/color :green} (:random world)) "supplied :random slot preserved")
      (is (number? (:time-ms world))
          "the framework-required :time-ms is filled in alongside the supplied keys"))))

;; ===========================================================================
;; Child dispatch gets its OWN world-input map (no :time-ms inheritance)
;; ===========================================================================

(deftest child-dispatch-gets-fresh-time-ms
  (testing "a :fx [[:dispatch ...]] child gets its OWN :rf.world/inputs — :time-ms NOT inherited"
    (rf/reg-frame :wi/cascade {:doc "ctx"})
    (let [envelopes (atom [])]
      ;; A user fx-handler receives the parent dispatch envelope under
      ;; (:envelope m); each handler in the cascade fires its own capture,
      ;; so we read both the parent's and child's stamped world map.
      (rf/reg-fx :wi/capture-env
        (fn [m _args] (swap! envelopes conj (:envelope m))))
      (rf/reg-event-fx :wi/parent
        (fn [_ _]
          {:fx [[:wi/capture-env]
                [:dispatch [:wi/child]]]}))
      (rf/reg-event-fx :wi/child
        (fn [_ _]
          {:fx [[:wi/capture-env]]}))

      ;; Parent supplies an explicit :time-ms; the child must NOT inherit it.
      (rf/dispatch-sync [:wi/parent]
                        {:frame :wi/cascade
                         :rf.world/inputs {:time-ms 1781078400000}})

      (let [[parent-env child-env] @envelopes
            parent-t (get-in parent-env [:rf.world/inputs :time-ms])
            child-t  (get-in child-env  [:rf.world/inputs :time-ms])]
        (is (= [:wi/parent] (:event parent-env)) "first capture is the parent")
        (is (= [:wi/child]  (:event child-env))  "second capture is the child")
        (is (= 1781078400000 parent-t) "parent carries the supplied :time-ms")
        (is (number? child-t) "child has its own stamped :time-ms")
        (is (not= 1781078400000 child-t)
            "child did NOT inherit the parent's :time-ms — distinct causal token (EP-0010)")))))

;; ===========================================================================
;; Coeffect visibility + trace projection filtering
;; ===========================================================================

(deftest world-inputs-visible-as-coeffect
  (testing "handlers read :rf.world/inputs from the coeffect map"
    (rf/reg-frame :wi/cofx {:doc "ctx"})
    (let [cofx (capture-coeffects :wi/cofx
                                  {:rf.world/inputs {:time-ms 1781078400456}})]
      (is (contains? cofx :rf.world/inputs)
          ":rf.world/inputs is a framework coeffect in the initial context")
      (is (= 1781078400456 (get-in cofx [:rf.world/inputs :time-ms]))
          "the supplied :time-ms is what the handler reads"))))

(deftest world-inputs-filtered-from-user-cofx-projection
  (testing "fx/user-injected-coeffects strips :rf.world/inputs like the other framework defaults"
    (is (contains? fx/framework-coeffect-keys :rf.world/inputs)
        ":rf.world/inputs is in the framework-coeffect-keys filter set")
    (let [cofx {:db {} :event [:e] :rf.db/runtime {} :rf.frame/id :f
                :rf.world/inputs {:time-ms 1781078400789}
                :my/cofx 1}]
      (is (= {:my/cofx 1} (fx/user-injected-coeffects cofx))
          ":rf.world/inputs does NOT appear in the user-cofx trace projection"))))

;; ===========================================================================
;; :dispatched-at is retired
;; ===========================================================================

(deftest dispatched-at-is-gone
  (testing "EP-0010 rider b: :dispatched-at is retired from the envelope (no coexistence)"
    (rf/reg-frame :wi/no-dispatched-at {:doc "ctx"})
    (testing "absent even with the dev gate ON (it is not merely prod-elided — it is gone)"
      (with-redefs [interop/debug-enabled? true]
        (is (not (contains? (build-envelope [:noop] {:frame :wi/no-dispatched-at})
                            :dispatched-at))
            "no :dispatched-at key on the envelope")))
    (testing "the durable causal-time fact is (:time-ms (:rf.world/inputs env)) instead"
      (let [env (build-envelope [:noop] {:frame :wi/no-dispatched-at})]
        (is (number? (get-in env [:rf.world/inputs :time-ms]))
            ":time-ms is the replacement for the retired :dispatched-at")))))

;; ===========================================================================
;; rf2-sppf0m: a handler READS :uuid / :random from the :rf.world/inputs
;; coeffect and WRITES the supplied values into a durable app-db entity.
;;
;; This is the EP-0010 §Validation/Conformance bullet — "random/UUID values
;; supplied by fixtures become durable ids exactly as supplied" — executed
;; against an actual durable WRITE, not merely the envelope pass-through that
;; `preserves-caller-supplied-extra-keys-and-fills-time-ms` (above) pins. The
;; EP §Examples "Correct Generated Values From The Token" shows the shape: a
;; `:todo/create` handler reads `(get-in inputs [:uuid :todo/id])` +
;; `(get-in inputs [:random :todo/color])` and folds them into app-db so the
;; replay log explains every durable value. The deferred `:rf.world/uuid` /
;; `:rf.world/random` framework cofx (EP disposition 2 / rider a) are NOT
;; involved here — the test scripts the slots directly, exactly as a fixture /
;; replay / SSR-hydration dispatch would, and the handler reads them straight
;; from the coeffect map.
;; ===========================================================================

(deftest supplied-uuid-random-become-durable-ids-exactly
  (testing "a handler reads :uuid / :random from the :rf.world/inputs coeffect
            and the supplied values land in app-db EXACTLY as supplied"
    (rf/reg-frame :wi/todos {:doc "ctx"})
    ;; The durable handler: reads the causal token's scripted id + colour from
    ;; the world-input coeffect (NOT an ambient `random-uuid` / `rand-nth`) and
    ;; folds them into a durable app-db entity. `reg-event-fx` so we can read
    ;; the `:rf.world/inputs` framework coeffect off the cofx map.
    (rf/reg-event-fx :todo/create
      (fn [{:keys [db rf.world/inputs]} [_ text]]
        (let [id    (get-in inputs [:uuid :todo/id])
              color (get-in inputs [:random :todo/color])]
          {:db (assoc-in db [:todos id]
                         {:todo/id id :todo/color color :todo/text text})})))
    (let [id    #uuid "018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d"
          color :green]
      (rf/dispatch-sync [:todo/create "buy milk"]
                        {:frame :wi/todos
                         :rf.world/inputs {:uuid   {:todo/id id}
                                           :random {:todo/color color}}})
      (let [entity (get-in (rf/app-db-value :wi/todos) [:todos id])]
        (is (some? entity)
            "the entity is keyed in app-db under the EXACT supplied uuid")
        (is (= id (:todo/id entity))
            "the durable :todo/id equals the supplied uuid exactly — the
             replay log explains the durable id (EP-0010 §Conformance)")
        (is (= color (:todo/color entity))
            "the durable :todo/color equals the supplied :random value exactly")
        (is (= "buy milk" (:todo/text entity))
            "the event arg rides through alongside the world-input ids")))))

(deftest supplied-uuid-replay-stable-where-ambient-would-diverge
  (testing "re-running the SAME causal token reproduces the SAME durable id
            (replay-stable), where an ambient random-uuid / rand-nth would have
            diverged run-to-run (EP-0010 §Restore, Replay, And Hydration)"
    (rf/reg-frame :wi/replay {:doc "ctx"})
    (rf/reg-event-fx :todo/create-from-token
      (fn [{:keys [db rf.world/inputs]} _]
        (let [id    (get-in inputs [:uuid :todo/id])
              color (get-in inputs [:random :todo/color])]
          {:db (assoc db :entity {:todo/id id :todo/color color})})))
    ;; The scripted causal token — the SAME map supplied on both runs, exactly
    ;; as a replay / restore would re-feed it.
    (let [token {:uuid   {:todo/id #uuid "018ff2b4-9bbd-7a0a-a4df-cf2a91cbe86d"}
                 :random {:todo/color :blue}}
          run!  (fn []
                  (rf/dispatch-sync [:todo/create-from-token]
                                    {:frame :wi/replay :rf.world/inputs token})
                  (:entity (rf/app-db-value :wi/replay)))
          first-entity  (run!)
          second-entity (run!)]
      (is (= first-entity second-entity)
          "two runs of the same token produce IDENTICAL durable entities —
           replay-stable")
      (is (= (get-in token [:uuid :todo/id]) (:todo/id second-entity))
          "the reproduced durable id is the token's id, not a fresh draw")
      ;; Contrast: an ambient generator (random-uuid / rand-nth) folded into a
      ;; durable write would have produced two DIFFERENT ids across the two
      ;; runs. Pin that this is the failure mode the token-read design avoids —
      ;; two independent ambient draws are (with overwhelming probability)
      ;; distinct, so a handler that read ambient instead of the token would
      ;; NOT be replay-stable.
      (let [ambient-1 (random-uuid)
            ambient-2 (random-uuid)]
        (is (not= ambient-1 ambient-2)
            "two ambient random-uuid draws diverge — the very property a
             durable write must NOT depend on; reading the token avoids it")))))

;; ===========================================================================
;; rf2-zq5zj2: ambient DIAGNOSTIC timestamps may differ without changing
;; durable state.
;;
;; This is the positive half of the EP-0008 / EP-0010 causal-vs-diagnostic
;; split (§Validation/Conformance bullet: "ambient diagnostic timestamps may
;; differ without changing durable state"). The runtime records BOTH a durable
;; CAUSAL time (the token's `:rf.world/inputs` `:time-ms` → the epoch record's
;; `:committed-at`, read ONCE at the causal boundary from `epoch-now-ms`) AND
;; ambient DIAGNOSTIC times (the trace event `:time`, stamped from the elapsed
;; `interop/now-ms` at every emit). The bullet asserts the ambient ones are
;; free to vary run-to-run while the durable projection stays EQUAL.
;;
;; We run the SAME scripted token (same supplied `:time-ms`) twice under two
;; DIFFERENT ambient clocks and assert:
;;   (a) the durable :committed-at is EQUAL across both runs (it folds the
;;       supplied token time, never the ambient clock), AND
;;   (b) a diagnostic trace `:time` (captured via a trace listener) DIFFERS
;;       across the two runs (it legitimately reads the ambient clock).
;; That EXECUTES the invariant instead of documenting it (the prose at the
;; top of this ns + the inverse-only `:committed-at` clock tests in
;; epoch_test.clj are the prior coverage). `rf/epoch-history` is available on
;; the core test classpath as the epoch test-only dep.
;; ===========================================================================

(deftest ambient-diagnostic-time-differs-while-durable-committed-at-holds
  (testing "same causal token under two different ambient clocks → durable
            :committed-at EQUAL while the diagnostic trace :time DIFFERS"
    (rf/reg-frame :wi/split {:doc "ctx"})
    (rf/reg-event-db :wi/note (fn [db _] (update db :n (fnil inc 0))))
    (let [token-time   1781078400123     ; the supplied causal token :time-ms
          ;; capture the diagnostic trace :time of THIS frame's :wi/note event
          ;; per run — the trace `:time` is stamped from the ambient
          ;; `interop/now-ms` (re-frame.trace/build-event), the diagnostic
          ;; surface the bullet says is free to vary.
          trace-times  (atom [])
          run!         (fn [ambient-clock]
                         (let [seen (atom nil)]
                           (rf/register-listener! ::split-probe
                             (fn [ev]
                               ;; Capture the diagnostic :time of THIS frame's
                               ;; :wi/note dispatched event — a deterministic
                               ;; single emit per run. The event vector rides
                               ;; under (:rf.event/v :tags) as [event-id args]
                               ;; (re-frame.marks §project-event-tags).
                               (when (and (nil? @seen)
                                          (= :rf.event (:op-type ev))
                                          (= :rf.event/dispatched (:operation ev))
                                          (= :wi/note (first (get-in ev [:tags :rf.event/v]))))
                                 (reset! seen (:time ev)))))
                           ;; Pin BOTH host clocks to the SAME per-run ambient
                           ;; value so the trace :time is deterministic within
                           ;; the run yet DIFFERENT between the two runs, while
                           ;; the SUPPLIED token :time-ms rides through
                           ;; unchanged (the router only fills :time-ms when
                           ;; absent — see preserves-caller-supplied-time-ms).
                           (with-redefs [interop/now-ms       (constantly ambient-clock)
                                         interop/epoch-now-ms (constantly ambient-clock)]
                             (rf/dispatch-sync [:wi/note]
                                               {:frame :wi/split
                                                :rf.world/inputs {:time-ms token-time}}))
                           (rf/unregister-listener! ::split-probe)
                           (swap! trace-times conj @seen)
                           ;; the durable :committed-at of the just-settled epoch
                           (:committed-at (last (rf/epoch-history :wi/split)))))
          committed-1  (run! 1000)
          committed-2  (run! 9999999)]
      ;; (a) DURABLE side — equal across the two ambient clocks.
      (is (= token-time committed-1)
          "run 1: durable :committed-at folds the supplied token :time-ms")
      (is (= token-time committed-2)
          "run 2: durable :committed-at folds the SAME supplied token :time-ms")
      (is (= committed-1 committed-2)
          "durable :committed-at is EQUAL across runs — wall-clock drift
           between the two commits did not change durable state")
      ;; (b) DIAGNOSTIC side — differs across the two ambient clocks.
      (let [[t1 t2] @trace-times]
        (is (= 1000 t1)
            "run 1: the diagnostic trace :time read the ambient clock (1000)")
        (is (= 9999999 t2)
            "run 2: the diagnostic trace :time read the DIFFERENT ambient clock")
        (is (not= t1 t2)
            "the ambient diagnostic trace :time DIFFERS run-to-run — free to
             vary, exactly as the EP-0010 causal/diagnostic split permits,
             while the durable :committed-at above held equal")))))
