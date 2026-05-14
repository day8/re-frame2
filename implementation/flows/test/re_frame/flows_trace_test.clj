(ns re-frame.flows-trace-test
  "JVM coverage for Spec 009 §Flow trace events / Spec 013 §Flow tracing
  — verifies the five `:rf.flow/*` lifecycle events fire with the
  documented payloads. The conformance fixture
  `flow-lifecycle-emits-traces.edn` describes the same shapes as data;
  this file exercises them against the JVM reference implementation
  directly so a regression surfaces as a unit-test failure even when the
  conformance harness is skipping the fixture (the reference harness
  skips `:flow/basic` capability fixtures until the runner wires the
  flow-body realiser through).

  Per rf2-2s1o: `:flow` op-type and `:rf.flow/*` operation vocabulary
  added for re-frame-10x v2's flow panel."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- per-test reset / trace recorder -------------------------------------

(def ^:dynamic ^:private *captured* nil)

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (when-let [li-var (resolve 're-frame.flows/last-inputs)]
    (reset! (deref li-var) {}))
  ;; Per rf2-bacs4: the error-emit listener registry is a `defonce`
  ;; atom that survives test re-runs. Clear before each test so a
  ;; listener registered by one test doesn't leak into the next.
  (error-emit/clear-error-emit-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (let [captured (atom [])]
    (binding [*captured* captured]
      (trace/register-trace-cb!
        ::flow-trace-recorder
        (fn [ev]
          ;; Filter to flow op-type only — keeps assertions tight.
          (when (= :flow (:op-type ev))
            (swap! captured conj ev))))
      (try
        (test-fn)
        (finally
          (trace/remove-trace-cb! ::flow-trace-recorder))))))

(use-fixtures :each reset-runtime)

(defn- by-op
  "Filter the captured trace events by :operation, returning the matching
  events in capture order."
  [op]
  (filterv #(= op (:operation %)) @*captured*))

;; ---------------------------------------------------------------------------
;; 1. :rf.flow/registered fires after reg-flow successfully registers
;; ---------------------------------------------------------------------------

(deftest reg-flow-emits-registered-trace
  (testing "reg-flow fires :rf.flow/registered with :flow-id, :inputs, :path, :frame"
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* (or w 0) (or h 0)))
                  :path   [:rect :area]})
    (let [evs (by-op :rf.flow/registered)]
      (is (= 1 (count evs))
          "exactly one :rf.flow/registered fired for the reg-flow call")
      (let [ev (first evs)]
        (is (= :flow (:op-type ev))                      "op-type :flow")
        (is (= :rf.flow/registered (:operation ev))      "operation :rf.flow/registered")
        (let [tags (:tags ev)]
          (is (= :area              (:flow-id tags))     ":flow-id in tags")
          (is (= [[:w] [:h]]        (:inputs tags))      ":inputs in tags")
          (is (= [:rect :area]      (:path tags))        ":path in tags")
          (is (= :rf/default        (:frame tags))       ":frame in tags"))))))

(deftest reg-flow-registered-fires-first-time-only
  (testing "Per rf2-ehxez: :rf.flow/registered fires only on first-time
            registration. On re-registration the cross-kind
            `:rf.registry/handler-replaced` trace (emitted by
            `registrar/register!` per Spec 001 §Hot-reload trace
            surface) is the hot-reload signal — both traces no longer
            double-emit on the same re-registration."
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* (or w 0) (or h 0)))
                  :path   [:rect :area]})
    (is (= 1 (count (by-op :rf.flow/registered)))
        "first-time registration fires :rf.flow/registered once")
    (reset! *captured* [])
    ;; Re-register with the SAME shape — :rf.flow/registered must NOT fire.
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* (or w 0) (or h 0)))
                  :path   [:rect :area]})
    (is (zero? (count (by-op :rf.flow/registered)))
        "re-registration does NOT fire :rf.flow/registered — hot-reload signal rides on :rf.registry/handler-replaced"))
  (testing "re-registration with a NEW :output also does not double-emit"
    (rf/reg-flow {:id     :area2
                  :inputs [[:w]]
                  :output (fn [w] w)
                  :path   [:rect :area2]})
    (reset! *captured* [])
    (rf/reg-flow {:id     :area2
                  :inputs [[:w]]
                  :output (fn [w] (* 2 w))
                  :path   [:rect :area2]})
    (is (zero? (count (by-op :rf.flow/registered)))
        "real body change still does not re-emit :rf.flow/registered — only first-time")))

(deftest reg-flow-cycle-does-NOT-emit-registered
  (testing "when reg-flow throws cycle, no :rf.flow/registered fires for the rejected flow"
    (rf/reg-flow {:id :a :inputs [[:b]] :output identity :path [:a]})
    ;; one event so far for :a
    (is (= 1 (count (by-op :rf.flow/registered))))
    (is (thrown? Throwable
                 (rf/reg-flow {:id :b :inputs [[:a]] :output identity :path [:b]})))
    ;; Still just the one — :b's registration unwound before the trace.
    (is (= 1 (count (by-op :rf.flow/registered)))
        "only :a's register trace; :b's was rolled back")))

;; ---------------------------------------------------------------------------
;; 2. :rf.flow/computed fires when a flow recomputes
;; ---------------------------------------------------------------------------

(deftest flow-computed-fires-on-input-change
  (testing "first drain after registration emits :rf.flow/computed with :input-values, :result, :path, :frame"
    (rf/reg-event-db :init (fn [_ _] {:w 3 :h 4}))
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* w h))
                  :path   [:rect :area]})
    (rf/dispatch-sync [:init])
    (let [computes (by-op :rf.flow/computed)]
      (is (pos? (count computes))
          "first drain after init computes the flow at least once")
      (let [ev (last computes)]
        (is (= :flow (:op-type ev)))
        (let [tags (:tags ev)]
          (is (= :area         (:flow-id tags)))
          (is (= [3 4]         (:input-values tags))     ":input-values are the raw vec")
          (is (= 12            (:result tags))           ":result is the computed value")
          (is (= [:rect :area] (:path tags)))
          (is (= :rf/default   (:frame tags))))))))

;; ---------------------------------------------------------------------------
;; 3. :rf.flow/skip fires when value-equal input rewrite suppresses recompute
;; ---------------------------------------------------------------------------

(deftest flow-skip-fires-on-value-equal-rewrite
  (testing "writing :n with =-equal value emits :rf.flow/skip not :rf.flow/computed"
    (rf/reg-event-db :init       (fn [_ _] {:n 5}))
    (rf/reg-event-db :replace-n  (fn [db [_ v]] (assoc db :n v)))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:derived :doubled]})
    (rf/dispatch-sync [:init])
    ;; Reset capture so we look only at the :replace-n drain.
    (reset! *captured* [])
    (rf/dispatch-sync [:replace-n 5])
    (let [skips    (by-op :rf.flow/skip)
          computes (by-op :rf.flow/computed)]
      (is (= 1 (count skips))
          ":n was replaced with =-equal value; one :rf.flow/skip fired")
      (is (zero? (count computes))
          "the value-equal rewrite did NOT trigger a recompute trace")
      (let [tags (:tags (first skips))]
        (is (= :double             (:flow-id tags)))
        (is (= :inputs-value-equal (:reason tags))
            ":reason names the suppression cause (rf2-719e value-equal recompute suppression)")
        (is (= :rf/default         (:frame tags)))))))

(deftest flow-skip-then-computed-on-real-change
  (testing "skip fires on equal rewrite; subsequent real change fires :rf.flow/computed"
    (rf/reg-event-db :init      (fn [_ _] {:n 5}))
    (rf/reg-event-db :replace-n (fn [db [_ v]] (assoc db :n v)))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:derived :doubled]})
    (rf/dispatch-sync [:init])
    (reset! *captured* [])
    (rf/dispatch-sync [:replace-n 5])    ;; same value → skip
    (rf/dispatch-sync [:replace-n 7])    ;; new value  → compute
    (is (= 1 (count (by-op :rf.flow/skip))))
    (is (= 1 (count (by-op :rf.flow/computed))))))

;; ---------------------------------------------------------------------------
;; 4. :rf.flow/cleared fires when clear-flow runs
;; ---------------------------------------------------------------------------

(deftest clear-flow-emits-cleared-trace
  (testing "clear-flow emits :rf.flow/cleared with :flow-id, :path, :frame"
    (rf/reg-event-db :seed (fn [_ _] {:rect {:w 3 :h 4}}))
    (rf/reg-flow {:id     :area
                  :inputs [[:rect :w] [:rect :h]]
                  :output (fn [w h] (* w h))
                  :path   [:rect :area]})
    (rf/dispatch-sync [:seed])
    (reset! *captured* [])
    (rf/clear-flow :area)
    (let [evs (by-op :rf.flow/cleared)]
      (is (= 1 (count evs)))
      (let [tags (:tags (first evs))]
        (is (= :area         (:flow-id tags)))
        (is (= [:rect :area] (:path tags)))
        (is (= :rf/default   (:frame tags)))))))

(deftest clear-flow-on-unknown-id-emits-nothing
  (testing "clear-flow on an unregistered id is a no-op and emits no trace"
    (rf/clear-flow :no-such-flow)
    (is (zero? (count (by-op :rf.flow/cleared))))))

;; ---------------------------------------------------------------------------
;; 5. :rf.flow/failed fires when the :output fn throws
;; ---------------------------------------------------------------------------

(deftest flow-failed-fires-when-output-throws
  (testing "a flow whose :output fn throws emits :rf.flow/failed; the exception propagates"
    (rf/reg-event-db :init       (fn [_ _] {:n 1}))
    (rf/reg-event-db :bump       (fn [db _] (update db :n inc)))
    (rf/reg-flow {:id     :boom
                  :inputs [[:n]]
                  :output (fn [_] (throw (ex-info "boom" {:why :test})))
                  :path   [:doomed]})
    (reset! *captured* [])
    ;; The router catches the cascade-level throw and emits
    ;; :rf.error/flow-eval-exception per Spec 009 §Error contract; our
    ;; concern is that the per-flow :rf.flow/failed fired before that.
    (rf/dispatch-sync [:init])
    (let [evs (by-op :rf.flow/failed)]
      (is (= 1 (count evs))
          ":rf.flow/failed fires once on the first drain (initial evaluation throws)")
      (let [tags (:tags (first evs))]
        (is (= :boom (:flow-id tags)))
        (is (some? (:ex tags))     ":ex carries the thrown exception")
        (is (= :rf/default (:frame tags)))
        (is (= [1] (:inputs tags)) ":inputs records what was read just before the throw")))
    ;; Driving another input change re-attempts (last-inputs was not
    ;; advanced on the failed path) — :rf.flow/failed fires again.
    (reset! *captured* [])
    (rf/dispatch-sync [:bump])
    (is (= 1 (count (by-op :rf.flow/failed)))
        "subsequent input change re-attempts and :rf.flow/failed fires again")))

;; ---------------------------------------------------------------------------
;; 5b. :rf.error/flow-eval-exception routes through the always-on
;;     error-emit substrate (rf2-hrt5c — security audit follow-up).
;;
;; Pre-fix, `run-flows!` caught flow throws and called
;; `trace/emit-error!` ONLY. In CLJS production builds, that path is
;; DCE'd by `goog.DEBUG=false` — flow failures became silent to
;; corpus-wide error listeners (Sentry / Honeybadger / Rollbar
;; shippers registered via `register-error-emit-listener!`) and to
;; the per-frame `:on-error` policy fn. The handler-exception path
;; (`emit-handler-exception!`) had ALREADY been routed through the
;; always-on substrate; flow-eval was asymmetric. This test pins the
;; symmetric routing: a flow-eval throw must surface on the listener
;; registry record in JVM dev AND survive prod elision in CLJS.
;; ---------------------------------------------------------------------------

(deftest flow-eval-exception-routes-through-error-emit-substrate
  (testing "Per rf2-hrt5c: a flow whose :output throws fires a corpus-
            wide error-emit listener record with `:error
            :rf.error/flow-eval-exception` — fan-out runs through
            `error-emit/dispatch-on-error!`, mirroring the handler-
            exception path."
    (let [seen (atom [])]
      (rf/register-error-emit-listener!
        :test/flow-eval-recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event-db :init (fn [_ _] {:n 1}))
      (rf/reg-flow {:id     :boom
                    :inputs [[:n]]
                    :output (fn [_] (throw (ex-info "flow boom" {:why :test})))
                    :path   [:doomed]})
      (rf/dispatch-sync [:init])
      (is (= 1 (count @seen))
          "exactly one substrate record fired for one flow-eval throw")
      (let [r (first @seen)]
        (is (= :rf.error/flow-eval-exception (:error r))
            ":error names the flow-eval path")
        (is (= [:init]        (:event r))
            ":event is the in-flight dispatch envelope")
        (is (= :init          (:event-id r))
            ":event-id is the dispatched event id")
        (is (= :rf/default    (:frame r))
            ":frame is the draining frame")
        (is (some? (:exception r))
            ":exception is the thrown Throwable / ex-info")
        (is (number? (:time r))
            ":time is wall-clock millis")
        (is (integer? (:elapsed-ms r))
            ":elapsed-ms is integer (rf2-ph8pa contract — no float
             leak from CLJS performance.now())")
        (is (not (neg? (:elapsed-ms r)))
            ":elapsed-ms is non-negative")
        (is (= #{:error :event :event-id :frame :time :exception :elapsed-ms}
               (set (keys r)))
            "record carries ONLY the tight rf2-bacs4 keys")))))

(deftest flow-eval-exception-fires-per-frame-on-error-policy
  (testing "Per rf2-hrt5c: a flow whose :output throws ALSO fires the
            per-frame `:on-error` policy fn through the substrate.
            The structured error-event carries `:operation
            :rf.error/flow-eval-exception` and `:where :flow-eval`
            so policy fns can discriminate the flow-eval path from
            the handler-exception path."
    (let [policy-saw (atom nil)]
      (rf/reg-frame :rf/default
                    {:on-error (fn [ev] (reset! policy-saw ev) nil)})
      (rf/reg-event-db :init (fn [_ _] {:n 1}))
      (rf/reg-flow {:id     :boom
                    :inputs [[:n]]
                    :output (fn [_] (throw (ex-info "flow boom" {})))
                    :path   [:doomed]})
      (rf/dispatch-sync [:init])
      (let [ev @policy-saw]
        (is (some? ev) ":on-error policy fired for the flow-eval throw")
        (is (= :rf.error/flow-eval-exception (:operation ev))
            ":operation names the flow-eval-exception path")
        (is (= :error (:op-type ev)))
        (is (= :no-recovery (:recovery ev)))
        (let [tags (:tags ev)]
          (is (= :flow-eval (:where tags))
              ":where :flow-eval distinguishes from :handler-exception")
          (is (nil? (:handler-id tags))
              ":handler-id nil — no handler ran; the throw came from
               the post-commit flow walk")
          (is (= :init       (:event-id tags)))
          (is (= [:init]     (:event tags)))
          (is (= :rf/default (:frame tags)))
          (is (some? (:exception tags)))
          ;; Per rf2-je5p8: :flow-id is stamped into :tags from the
          ;; ex-info wrapping in evaluate-flow!'s catch. This is the
          ;; ONLY per-flow attribution that survives CLJS prod
          ;; elision — `:rf.flow/failed` trace is DCE'd.
          (is (= :boom (:flow-id tags))
              ":flow-id is propagated from evaluate-flow!'s ex-info wrap (rf2-je5p8)"))))))

;; ---------------------------------------------------------------------------
;; 5c. :rf.fx/reg-flow cycle detection routes through error-emit (rf2-eb4lp)
;;
;; Pre-fix, a cycle introduced through `:rf.fx/reg-flow` from a handler's
;; `:fx` raised `:rf.error/flow-cycle` synchronously inside the reserved-
;; fx body, the throw bubbled uncaught up the drain stack, and the drain
;; emergency-release re-threw. The typed `:rf.error/flow-cycle` ex-data
;; (carrying the `:cycle` closing-repeat vector tools render) never
;; reached the error-emit substrate. In CLJS production the runtime
;; cycle was silently lost.
;;
;; Post-fix: `handle-one-fx`'s reserved-fx branch catches
;; `:rf.error/flow-cycle` and routes through `error-emit/dispatch-on-
;; error!` with the `:cycle` ex-data preserved, plus the dev-side trace
;; emit. Mirrors the rf2-hrt5c handler-exception and rf2-fslx0 flow-eval
;; routings.
;; ---------------------------------------------------------------------------

(deftest fx-reg-flow-cycle-routes-through-error-emit-substrate
  (testing "Per rf2-eb4lp: a :rf.fx/reg-flow that closes a cycle fires
            a corpus-wide error-emit listener record with `:error
            :rf.error/flow-cycle` — fan-out runs through
            `error-emit/dispatch-on-error!`, mirroring the
            handler-exception / flow-eval-exception paths."
    (let [seen (atom [])]
      (rf/register-error-emit-listener!
        :test/fx-reg-flow-cycle-recorder
        (fn [record] (swap! seen conj record)))
      ;; Register flow :a that depends on :b's path.
      (rf/reg-flow {:id     :a
                    :inputs [[:b-out]]
                    :output identity
                    :path   [:a-out]})
      ;; Now dispatch an event whose :fx registers :b such that
      ;; :b's :inputs overlap :a's :path → cycle.
      (rf/reg-event-fx :introduce-cycle
                       (fn [_ _]
                         {:fx [[:rf.fx/reg-flow
                                {:id     :b
                                 :inputs [[:a-out]]
                                 :output identity
                                 :path   [:b-out]}]]}))
      (rf/dispatch-sync [:introduce-cycle])
      (is (= 1 (count @seen))
          "exactly one substrate record fired for one :rf.fx/reg-flow cycle")
      (let [r (first @seen)]
        (is (= :rf.error/flow-cycle (:error r))
            ":error names the typed flow-cycle path (NOT a generic fx-handler-exception)")
        (is (some? (:exception r))
            ":exception is the thrown ex-info carrying the cycle data")
        (let [d (ex-data (:exception r))]
          (is (= :rf.error/flow-cycle (:error d))
              "exception ex-data carries :error :rf.error/flow-cycle")
          (is (vector? (:cycle d))
              "exception ex-data carries :cycle — the closing-repeat chain tools render"))))))

(deftest flow-eval-exception-trace-and-substrate-fire-together
  (testing "Per rf2-hrt5c: the trace path is NOT replaced by the
            substrate routing — both fire from one normative
            emission site so dev-time `:rf.error/flow-eval-exception`
            trace consumers (re-frame-10x, conformance recorders)
            are unaffected by the substrate addition."
    (let [trace-saw    (atom nil)
          listener-saw (atom nil)]
      (rf/register-error-emit-listener!
        :test/recorder
        (fn [record] (reset! listener-saw record)))
      (trace/register-trace-cb!
        ::flow-eval-trace-recorder
        (fn [ev]
          (when (= :rf.error/flow-eval-exception (:operation ev))
            (reset! trace-saw ev))))
      (try
        (rf/reg-event-db :init (fn [_ _] {:n 1}))
        (rf/reg-flow {:id     :boom
                      :inputs [[:n]]
                      :output (fn [_] (throw (ex-info "boom" {})))
                      :path   [:doomed]})
        (rf/dispatch-sync [:init])
        (is (some? @trace-saw)
            "trace bus saw `:rf.error/flow-eval-exception` — dev path intact")
        (is (some? @listener-saw)
            "corpus-wide listener saw the record — always-on substrate path fired")
        (is (= :rf.error/flow-eval-exception (:error @listener-saw)))
        (finally
          (trace/remove-trace-cb! ::flow-eval-trace-recorder))))))

;; ---------------------------------------------------------------------------
;; 6. End-to-end sample: all five events fire across a typical lifecycle
;; ---------------------------------------------------------------------------

(deftest typical-lifecycle-fires-all-five-events
  (testing "register → first compute → skip on equal rewrite → real recompute → clear"
    (rf/reg-event-db :init      (fn [_ _] {:n 3}))
    (rf/reg-event-db :replace-n (fn [db [_ v]] (assoc db :n v)))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:doubled]})
    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:replace-n 3])     ;; same → skip
    (rf/dispatch-sync [:replace-n 4])     ;; change → compute
    (rf/clear-flow :double)
    (is (= 1 (count (by-op :rf.flow/registered))))
    (is (pos?  (count (by-op :rf.flow/computed))))
    (is (= 1 (count (by-op :rf.flow/skip))))
    (is (= 1 (count (by-op :rf.flow/cleared))))
    (is (zero? (count (by-op :rf.flow/failed))))))

;; ---------------------------------------------------------------------------
;; 7. Wire-bearing flow trace payloads ride through `elide-wire-value`
;;    (rf2-vkqkk — pins Spec 009 §Size elision in traces / §Privacy contract
;;    for the flow trace surface).
;;
;; `:rf.flow/computed` carries `:input-values` and `:result`; `:rf.flow/failed`
;; carries `:inputs`. Per Spec 009 the wire-bearing payload of every tracer
;; surface MUST pass through the elision walker (the single normative emission
;; site for `:rf.size/large-elided` and `:rf/redacted`). Pre-fix, the flow
;; tracer bypassed the walker — a flow reading or producing a large value
;; surfaced raw on the trace bus while sibling tracers (event-emit, error-
;; emit, dispatch trace) honoured the contract. These tests pin the routing.
;; ---------------------------------------------------------------------------

(deftest computed-trace-elides-large-result
  (testing ":rf.flow/computed :result rides through elide-wire-value — declared-large path is elided"
    ;; Seed the app-db with `merge` semantics so the elision declarations
    ;; written by `declare-large-path!` survive the :init handler — a
    ;; replacing handler (e.g. `(fn [_ _] {:n 1})`) would wipe the
    ;; `:rf/elision` registry slot under the same key, masking the
    ;; declaration before the flow's evaluate-time registry read.
    (rf/reg-event-db :init (fn [db _] (merge db {:n 1})))
    (rf/reg-flow {:id     :payload
                  :inputs [[:n]]
                  :output (fn [_] {:bytes "BIG"})
                  :path   [:derived :blob]})
    ;; Declare the flow's :path as a large-elision candidate. The walker
    ;; consults `[:rf/elision :declarations <path>]` per frame.
    (elision/declare-large-path! [:derived :blob])
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (let [ev   (last (by-op :rf.flow/computed))
          tags (:tags ev)]
      (is (some? ev) ":rf.flow/computed fired")
      (is (elision/marker? (:result tags))
          ":result is replaced by the `:rf.size/large-elided` marker")
      (let [marker (:rf.size/large-elided (:result tags))]
        (is (= [:derived :blob] (:path marker))
            "marker carries the declared path")
        (is (= :declared (:reason marker))
            "marker carries :reason :declared for declared-large paths")))))

(deftest failed-trace-elides-inputs
  (testing ":rf.flow/failed :inputs rides through elide-wire-value"
    ;; Register the flow that will throw; the input path is declared
    ;; large so the walker substitutes the marker on emit.
    (rf/reg-event-db :init (fn [db _] (merge db {:payload {:big "value"}})))
    (rf/reg-flow {:id     :boom
                  :inputs [[:payload]]
                  :output (fn [_] (throw (ex-info "boom" {})))
                  :path   [:doomed]})
    (elision/declare-large-path! [:payload])
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (let [ev   (last (by-op :rf.flow/failed))
          tags (:tags ev)
          [first-input] (:inputs tags)]
      (is (some? ev) ":rf.flow/failed fired")
      (is (vector? (:inputs tags))
          ":inputs vector preserves the per-input slot shape")
      (is (elision/marker? first-input)
          "the elided input-value is substituted with the wire marker"))))

;; ---------------------------------------------------------------------------
;; 7b. Failed-flow cascade behaviour (rf2-wyt97).
;;
;; The pre-rf2-wyt97 `evaluate-flow!` docstring claimed `[db false]` was
;; returned on failure and downstream flows still walked. The impl
;; actually `(throw e)`s after emitting `:rf.flow/failed`, which exited
;; `run-flows!`'s loop — and pre-fix, the early exit bypassed
;; `replace-container!`, so prior successful flows' dirty writes were
;; SILENTLY DROPPED even though their `:rf.flow/computed` traces had
;; already claimed the write. This deftest pins the corrected contract
;; (per Spec 013 §Failure semantics): prior writes are flushed before
;; the throw propagates; downstream flows do not run.
;; ---------------------------------------------------------------------------

(deftest failed-cascade-preserves-prior-flow-writes
  (testing "when a downstream flow throws, prior flow writes are flushed; the cascade halts"
    ;; :A reads [:n], writes [:a-out]. :B reads [:a-out], throws.
    ;; :C reads [:b-out]. The path-prefix dependency edges
    ;; (A.path → B.input, B.path → C.input) pin topo order A → B → C.
    (rf/reg-event-db :init (fn [_ _] {:n 5}))
    (rf/reg-flow {:id     :A
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:a-out]})
    (rf/reg-flow {:id     :B
                  :inputs [[:a-out]]
                  :output (fn [_] (throw (ex-info "boom" {:why :test})))
                  :path   [:b-out]})
    (rf/reg-flow {:id     :C
                  :inputs [[:b-out]]
                  :output (fn [b] (str "C-saw-" b))
                  :path   [:c-out]})
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (let [db (rf/get-frame-db :rf/default)]
      ;; Rule 1: :A's write IS in app-db.
      (is (= 10 (:a-out db))
          ":A's output (5 * 2 = 10) is in app-db — prior-flow writes preserved")
      ;; Rule 2: failing :B did not write.
      (is (not (contains? db :b-out))
          ":B's :path absent — the failing flow's own write is not applied")
      ;; Rule 3: downstream :C did not run.
      (is (not (contains? db :c-out))
          ":C's :path absent — downstream flows do not run on the failing drain"))
    ;; Trace stream pin: :A's :rf.flow/computed fired; :B's
    ;; :rf.flow/failed fired; :C emitted no drain trace.
    (let [drain-evs (filterv #(#{:rf.flow/computed :rf.flow/failed
                                 :rf.flow/skip}
                                (:operation %))
                             @*captured*)
          per-flow  (group-by #(-> % :tags :flow-id) drain-evs)]
      (is (= [:rf.flow/computed]
             (mapv :operation (get per-flow :A)))
          ":A emitted one :rf.flow/computed trace")
      (is (= [:rf.flow/failed]
             (mapv :operation (get per-flow :B)))
          ":B emitted one :rf.flow/failed trace")
      (is (empty? (get per-flow :C))
          ":C emitted no drain trace — did not run after :B threw"))))

;; ---------------------------------------------------------------------------
;; 7c. Failed-flow contract pin (rf2-hrqvg).
;;
;; Companion to 7b: explicitly pin the per-flow `last-inputs` non-advance
;; on failure and the prior-flow `last-inputs` advance on success. The
;; failed-cascade test above asserts the OBSERVABLE outcome (which
;; :path slots survive); this asserts the dirty-check bookkeeping that
;; makes retry-on-next-drain work correctly.
;;
;; This is the contract decision documented in the cluster's PR body
;; (decision-flagged bead rf2-hrqvg): preserve prior writes AND halt
;; the cascade — the strongest no-silent-loss guarantee compatible
;; with surfacing failures as cascade-level errors.
;; ---------------------------------------------------------------------------

(deftest failed-flow-contract-last-inputs-bookkeeping
  (testing "failing flow's last-inputs is NOT advanced; prior flow's last-inputs IS advanced"
    (rf/reg-event-db :init (fn [_ _] {:n 5}))
    (rf/reg-event-db :bump (fn [db _] (update db :n inc)))
    (let [a-calls (atom 0)
          b-calls (atom 0)]
      (rf/reg-flow {:id     :A
                    :inputs [[:n]]
                    :output (fn [n] (swap! a-calls inc) (* 2 n))
                    :path   [:a-out]})
      (rf/reg-flow {:id     :B
                    :inputs [[:a-out]]
                    :output (fn [_]
                              (swap! b-calls inc)
                              (throw (ex-info "boom" {})))
                    :path   [:b-out]})
      (rf/dispatch-sync [:init])
      (is (= 1 @a-calls) ":A computed once on the first drain")
      (is (= 1 @b-calls) ":B threw once on the first drain")

      ;; Bump :n — :A's input changed (5 → 6).
      ;; - :A's last-inputs WAS advanced to [5] on the first drain, so a
      ;;   new dirty-check at [6] triggers recompute. (@a-calls should
      ;;   reach 2.)
      ;; - :A's new output (12) is different from the prior (10) it
      ;;   wrote on the first drain, so :B's input [:a-out] is now [12].
      ;;   :B's last-inputs was NOT advanced (still nil/empty from the
      ;;   failure), so :B retries. (@b-calls should reach 2.)
      (rf/dispatch-sync [:bump])
      (is (= 2 @a-calls)
          ":A re-fired because its last-inputs advanced and inputs changed (prior write succeeded)")
      (is (= 2 @b-calls)
          ":B re-fired because its last-inputs was NOT advanced on the prior failure"))))

(deftest failed-flow-contract-app-db-shape-after-multiple-failing-drains
  (testing "across multiple failing drains, prior flow's writes keep landing; failing flow's slot stays absent"
    (rf/reg-event-db :init (fn [_ _] {:n 5}))
    (rf/reg-event-db :n!   (fn [db [_ v]] (assoc db :n v)))
    (rf/reg-flow {:id     :A
                  :inputs [[:n]]
                  :output (fn [n] (* 10 n))
                  :path   [:a-out]})
    (rf/reg-flow {:id     :B
                  :inputs [[:a-out]]
                  :output (fn [_] (throw (ex-info "boom" {})))
                  :path   [:b-out]})
    (rf/dispatch-sync [:init])
    (is (= 50 (:a-out (rf/get-frame-db :rf/default))))
    (is (not (contains? (rf/get-frame-db :rf/default) :b-out)))

    (rf/dispatch-sync [:n! 7])
    (is (= 70 (:a-out (rf/get-frame-db :rf/default)))
        ":A's write landed again after the second failing drain (5 → 7 → 70)")
    (is (not (contains? (rf/get-frame-db :rf/default) :b-out))
        ":b-out still absent across drains — failing flow's slot remains vacated")

    (rf/dispatch-sync [:n! 11])
    (is (= 110 (:a-out (rf/get-frame-db :rf/default)))
        ":A's write landed a third time (7 → 11 → 110)")
    (is (not (contains? (rf/get-frame-db :rf/default) :b-out)))))

(deftest computed-trace-elision-no-op-when-no-declaration
  (testing "absent any declaration, :input-values and :result pass through unchanged"
    ;; Belt-and-braces against an over-eager rewrite — the walker must be
    ;; a no-op on plain values not nominated for elision.
    (rf/reg-event-db :init (fn [_ _] {:w 3 :h 4}))
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* w h))
                  :path   [:rect :area]})
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (let [tags (:tags (last (by-op :rf.flow/computed)))]
      (is (= [3 4] (:input-values tags))
          ":input-values pass through unmodified")
      (is (= 12 (:result tags))
          ":result passes through unmodified"))))
