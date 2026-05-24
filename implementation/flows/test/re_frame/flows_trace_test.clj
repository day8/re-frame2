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
  (error-emit/clear-error-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (let [captured (atom [])]
    (binding [*captured* captured]
      (trace/register-listener!
        ::flow-trace-recorder
        (fn [ev]
          ;; Filter to flow op-type only — keeps assertions tight.
          (when (= :flow (:op-type ev))
            (swap! captured conj ev))))
      (try
        (test-fn)
        (finally
          (trace/unregister-listener! ::flow-trace-recorder))))))

(use-fixtures :each reset-runtime)

(defn- by-op
  "Filter the captured trace events by :operation, returning the matching
  events in capture order."
  [op]
  (filterv #(= op (:operation %)) @*captured*))

(defn- record-all-traces
  "Capture every emitted trace event (not just `:op-type :flow`) for the
  ordered-stream tests. The `*captured*` fixture recorder is flow-only."
  [body-fn]
  (let [seen (atom [])]
    (trace/register-listener! ::all-trace-recorder (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (trace/unregister-listener! ::all-trace-recorder)))
    @seen))

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
          (is (= :rf/default   (:frame tags)))
          ;; Per rf2-qlzh4: :before carries the value at :path
          ;; immediately BEFORE this flow's write. On the first
          ;; compute the slot has never been written, so :before is
          ;; nil. The KEY must be present so consumers can rely on
          ;; uniform shape (rather than discriminating between
          ;; absent-key and explicit-nil).
          (is (contains? tags :before)
              ":before key present on every :rf.flow/computed trace")
          (is (nil? (:before tags))
              "first compute — :path has never been written; :before is nil"))))))

;; ---------------------------------------------------------------------------
;; 2b. :before slot tracks the pre-write value across drains (rf2-qlzh4)
;;
;; Per Spec 013 §Flow tracing: `:rf.flow/computed` carries `:before`
;; — the value at the flow's `:path` immediately before this drain's
;; write. Self-contained trace consumers (Causa Event Detail, 10x
;; flow panel) render the "wrote [path] <before> -> <after>" line
;; without walking the surrounding epoch's `:db-before` snapshot.
;; These tests pin the contract across the edge cases the audit
;; (ai/findings/2026-05-19-flow-trace-events-audit.md) flagged.
;; ---------------------------------------------------------------------------

(deftest computed-before-equals-prior-result-on-second-compute
  (testing "second :rf.flow/computed's :before equals the first compute's :result"
    (rf/reg-event-db :init      (fn [_ _] {:n 3}))
    (rf/reg-event-db :replace-n (fn [db [_ v]] (assoc db :n v)))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:doubled]})
    (rf/dispatch-sync [:init])           ;; first compute: 3 -> 6
    (rf/dispatch-sync [:replace-n 5])    ;; second compute: 5 -> 10
    (let [computes (by-op :rf.flow/computed)]
      (is (= 2 (count computes))
          "one compute per real input change")
      (let [[first-ev second-ev] computes]
        (is (nil? (:before (:tags first-ev)))
            "first compute :before is nil — :path slot was unwritten")
        (is (= 6 (:before (:tags second-ev)))
            ":before of the second compute equals the first compute's :result")
        (is (= 10 (:result (:tags second-ev)))
            ":result is the freshly-computed output value")))))

(deftest computed-before-with-prior-value-already-at-path
  (testing ":before carries the prior value when the path was written by a non-flow source first"
    ;; Seed [:doubled] with a value the event handler put there before
    ;; the flow ever fires. The first compute's :before MUST be that
    ;; prior value — not nil — because the slot was non-empty.
    (rf/reg-event-db :seed (fn [_ _] {:n 3 :doubled :preseeded}))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:doubled]})
    (rf/dispatch-sync [:seed])
    (let [ev (last (by-op :rf.flow/computed))
          tags (:tags ev)]
      (is (= :preseeded (:before tags))
          ":before reads the pre-existing value at :path, not nil")
      (is (= 6 (:result tags))
          ":result is the new flow output"))))

(deftest computed-before-on-nested-path
  (testing ":before reads the pre-write value at a deeply-nested :path"
    (rf/reg-event-db :init  (fn [_ _] {:w 3 :h 4 :rect {:area :initial-area}}))
    (rf/reg-event-db :grow  (fn [db _] (assoc db :w 5)))
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :output (fn [w h] (* w h))
                  :path   [:rect :area]})
    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:grow])
    (let [[first-ev second-ev] (by-op :rf.flow/computed)]
      (is (= :initial-area (:before (:tags first-ev)))
          "first compute :before reads the pre-existing nested-path value")
      (is (= 12 (:result (:tags first-ev)))
          "first compute :result is the freshly-computed value (3 * 4)")
      (is (= 12 (:before (:tags second-ev)))
          "second compute :before equals the first compute's :result")
      (is (= 20 (:result (:tags second-ev)))
          "second compute :result reflects the input change (5 * 4)"))))

(deftest computed-before-across-cascading-flows
  (testing ":before is captured against the in-drain accumulator so chained-flow :before reads its own slot, not :path overlap"
    ;; :A writes [:a-out]. :B reads [:a-out] and writes [:b-out]. The
    ;; cascade fires A then B in the SAME drain — :B's :before must
    ;; reflect the pre-drain value at [:b-out] (here nil on first
    ;; compute), NOT some intermediate state from :A's write. Each
    ;; flow's :before is independent — captured against its own :path.
    (rf/reg-event-db :init (fn [_ _] {:n 3}))
    (rf/reg-event-db :bump (fn [db _] (update db :n inc)))
    (rf/reg-flow {:id     :A
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:a-out]})
    (rf/reg-flow {:id     :B
                  :inputs [[:a-out]]
                  :output (fn [a] (str "B-saw-" a))
                  :path   [:b-out]})
    (rf/dispatch-sync [:init])
    (let [first-pass (by-op :rf.flow/computed)
          a-first    (first (filterv #(= :A (:flow-id (:tags %))) first-pass))
          b-first    (first (filterv #(= :B (:flow-id (:tags %))) first-pass))]
      (is (nil? (:before (:tags a-first)))
          ":A's first :before is nil — [:a-out] was unwritten")
      (is (= 6 (:result (:tags a-first))))
      (is (nil? (:before (:tags b-first)))
          ":B's first :before is nil — [:b-out] was unwritten — NOT 6 from :A's just-completed write at [:a-out]")
      (is (= "B-saw-6" (:result (:tags b-first)))))
    (reset! *captured* [])
    (rf/dispatch-sync [:bump])
    (let [second-pass (by-op :rf.flow/computed)
          a-second    (first (filterv #(= :A (:flow-id (:tags %))) second-pass))
          b-second    (first (filterv #(= :B (:flow-id (:tags %))) second-pass))]
      (is (= 6 (:before (:tags a-second)))
          ":A's :before on the second drain is its prior :result (6)")
      (is (= 8 (:result (:tags a-second))))
      (is (= "B-saw-6" (:before (:tags b-second)))
          ":B's :before on the second drain is its prior :result (the string B-saw-6) — NOT :A's intermediate value")
      (is (= "B-saw-8" (:result (:tags b-second)))))))

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
;; shippers registered via `register-error-listener!`) and to
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
      (rf/register-error-listener!
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
        (is (= #{:error :event :event-id :frame :time :exception :elapsed-ms
                 :source-coord}
               (set (keys r)))
            "record carries the tight rf2-bacs4 keys plus rf2-3un2g
             :source-coord (always-on parallel error-coord registry —
             the failing handler was macro-registered above)")))))

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
               the outermost-`:after` flow walk (pre-install)")
          (is (= :init       (:event-id tags)))
          (is (= [:init]     (:event tags)))
          (is (= :rf/default (:frame tags)))
          (is (some? (:exception tags)))
          ;; Per rf2-je5p8: :flow-id is stamped into :tags from the
          ;; ex-info wrapping in evaluate-flow!'s catch. This is the
          ;; ONLY per-flow attribution that survives CLJS prod
          ;; elision — `:rf.flow/failed` trace is DCE'd.
          (is (= :boom (:flow-id tags))
              ":flow-id is propagated from evaluate-flow!'s ex-info wrap (rf2-je5p8)")
          ;; Attribution is `:flow-id`-only. There is no real flow VALUE
          ;; to carry, so the cascade-level error MUST NOT claim a
          ;; `:flow` slot — the contract is the id alone (Spec 013
          ;; §Failure semantics / §Resolved decisions).
          (is (not (contains? tags :flow))
              ":flow is NOT stamped — no real flow value exists"))))))

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
      (rf/register-error-listener!
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
          (is (= :rf.error/flow-cycle (:rf.error/id d))
              "exception ex-data carries the canonical :rf.error/id discriminator (per Spec 009 §The thrown-error shape)")
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
      (rf/register-error-listener!
        :test/recorder
        (fn [record] (reset! listener-saw record)))
      (trace/register-listener!
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
          (trace/unregister-listener! ::flow-eval-trace-recorder))))))

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
  (testing ":rf.flow/computed :result rides through elide-wire-value — schema-large path is elided"
    ;; Seed the app-db with `merge` semantics so the schema-installed
    ;; elision registry survives the :init handler. A replacing handler
    ;; (e.g. `(fn [_ _] {:n 1})`) would wipe the `:rf/elision` slot
    ;; before the flow's evaluate-time registry read.
    (rf/reg-event-db :init (fn [db _] (merge db {:n 1})))
    (rf/reg-flow {:id     :payload
                  :inputs [[:n]]
                  :output (fn [_] {:bytes "BIG"})
                  :path   [:derived :blob]})
    (rf/reg-app-schema [:derived :blob] [:map {:large? true}])
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (let [ev   (last (by-op :rf.flow/computed))
          tags (:tags ev)]
      (is (some? ev) ":rf.flow/computed fired")
      (is (elision/marker? (:result tags))
          ":result is replaced by the `:rf.size/large-elided` marker")
      (let [marker (:rf.size/large-elided (:result tags))]
        (is (= [:derived :blob] (:path marker))
            "marker carries the schema-declared path")
        (is (= :schema (:reason marker))
            "marker carries :reason :schema for schema-large paths")))))

(deftest failed-trace-elides-inputs
  (testing ":rf.flow/failed :inputs rides through elide-wire-value"
    ;; Register the flow that will throw; the input path is schema-
    ;; declared large so the walker substitutes the marker on emit.
    (rf/reg-event-db :init (fn [db _] (merge db {:payload {:big "value"}})))
    (rf/reg-flow {:id     :boom
                  :inputs [[:payload]]
                  :output (fn [_] (throw (ex-info "boom" {})))
                  :path   [:doomed]})
    (rf/reg-app-schema [:payload] [:map {:large? true}])
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
;; 7b. Failed-flow cascade behaviour — atomicity contract (Mike 2026-05-24).
;;
;; A flow throw is a PRE-INSTALL throw: it aborts the WHOLE event. There
;; is NO partial commit. The router's `flows-after-interceptor` DISCARDS
;; the pending `:db` effect on the throw, so app-db is left UNCHANGED —
;; neither the handler's write nor any prior successful flows' writes
;; land, no `:rf.event/db-changed` is emitted, and `:fx` is skipped. This
;; deftest pins that contract: nothing the flow drain (or its handler)
;; produced survives the throw; downstream flows do not run.
;; ---------------------------------------------------------------------------

(deftest failed-cascade-aborts-event-app-db-unchanged
  (testing "when a downstream flow throws, the event aborts: app-db is unchanged (no install); the cascade halts"
    ;; :A reads [:n], writes [:a-out]. :B reads [:a-out], throws.
    ;; :C reads [:b-out]. The path-prefix dependency edges
    ;; (A.path → B.input, B.path → C.input) pin topo order A → B → C.
    ;; Seed :n via a FIRST clean drain so we can prove the SECOND
    ;; (throwing) drain installs nothing on top of it.
    (rf/reg-event-db :seed (fn [_ _] {:n 5}))
    (rf/reg-event-db :touch (fn [db _] (assoc db :touched true)))
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
    ;; First drain seeds :n. :A computes [:a-out]; :B throws → that whole
    ;; drain aborts, so even :n does not land. Re-seed cleanly is not
    ;; possible while :B throws, so capture app-db right after the
    ;; throwing drain and assert nothing landed.
    (reset! *captured* [])
    (rf/dispatch-sync [:seed])
    (let [db (rf/get-frame-db :rf/default)]
      ;; Atomicity: the handler's own write does NOT land.
      (is (not (contains? db :n))
          ":n absent — the handler's :db write was discarded (no install)")
      ;; Prior flow :A's write does NOT land (no partial commit).
      (is (not (contains? db :a-out))
          ":a-out absent — prior-flow writes are NOT committed on a flow throw")
      ;; Failing :B did not write.
      (is (not (contains? db :b-out))
          ":b-out absent — the failing flow's own write is not applied")
      ;; Downstream :C did not run.
      (is (not (contains? db :c-out))
          ":c-out absent — downstream flows do not run on the failing drain"))
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
;; 7c. Failed-flow bookkeeping pin — atomicity contract (Mike 2026-05-24).
;;
;; Atomicity extends to the dirty-check bookkeeping. `evaluate-flow!`
;; advances the global `last-inputs` atom for each flow it computes, but
;; a flow throw aborts the WHOLE event — nothing is installed. So a prior
;; flow's `last-inputs` advance MUST be rolled back: otherwise its
;; recompute would be suppressed next drain even though its output never
;; reached app-db, silently losing the write forever. `run-flows-on-db`
;; snapshots `last-inputs` before the walk and restores it on a throw, so
;; EVERY flow (prior-successful and failing alike) re-attempts cleanly on
;; the next drain — matching the all-or-nothing `:db` install.
;; ---------------------------------------------------------------------------

(deftest failed-flow-rolls-back-last-inputs-so-prior-flows-retry
  (testing "on a flow throw, the prior flow's last-inputs is rolled back — it recomputes every drain (not suppressed)"
    ;; :init writes the SAME db each drain, so absent rollback :A's
    ;; dirty-check would suppress its recompute on the 2nd+ drain. Because
    ;; the throw rolls back last-inputs, :A recomputes on EVERY drain.
    (rf/reg-event-db :init (fn [_ _] {:n 5}))
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

      ;; Second :init with IDENTICAL inputs. Absent rollback, :A's
      ;; last-inputs (advanced to [5] during the first, aborted drain)
      ;; would suppress recompute. Because the throw rolled last-inputs
      ;; back, :A re-attempts — and so does :B.
      (rf/dispatch-sync [:init])
      (is (= 2 @a-calls)
          ":A re-fired on the second drain — its last-inputs advance was rolled back by the throw")
      (is (= 2 @b-calls)
          ":B re-fired — its last-inputs was never advanced (it threw)"))))

(deftest failed-flow-app-db-unchanged-across-multiple-failing-drains
  (testing "across multiple failing drains, NOTHING lands — app-db stays unchanged (no partial commit)"
    (rf/reg-event-db :n!   (fn [db [_ v]] (assoc db :n v)))
    (rf/reg-flow {:id     :A
                  :inputs [[:n]]
                  :output (fn [n] (* 10 n))
                  :path   [:a-out]})
    (rf/reg-flow {:id     :B
                  :inputs [[:a-out]]
                  :output (fn [_] (throw (ex-info "boom" {})))
                  :path   [:b-out]})
    (rf/dispatch-sync [:n! 5])
    (let [db (rf/get-frame-db :rf/default)]
      (is (not (contains? db :n))
          ":n absent — the handler's write was discarded on the flow throw")
      (is (not (contains? db :a-out))
          ":a-out absent — prior-flow writes are NOT committed (no partial commit)")
      (is (not (contains? db :b-out))
          ":b-out absent — the failing flow's slot is never written"))

    (rf/dispatch-sync [:n! 7])
    (let [db (rf/get-frame-db :rf/default)]
      (is (not (contains? db :a-out))
          ":a-out still absent after a second failing drain — every drain aborts wholesale")
      (is (not (contains? db :b-out))
          ":b-out still absent across drains"))

    (rf/dispatch-sync [:n! 11])
    (let [db (rf/get-frame-db :rf/default)]
      (is (= {} db)
          "app-db remains the empty initial value — no failing drain ever committed anything"))))

;; ---------------------------------------------------------------------------
;; 7d. A flow throw aborts the event — atomicity contract (Mike 2026-05-24).
;;
;; A flow throw is a PRE-INSTALL throw: the event aborts wholesale. The
;; router's `flows-after-interceptor` DISCARDS the pending `:db` effect
;; (no install, app-db unchanged, no `:rf.event/db-changed`) and
;; `commit-and-flow!` skips `:fx`. So when a handler emits
;; `[:dispatch [:react-to-area-change]]` from `:fx` and the flow throws,
;; the child dispatch must NOT fire (its side effects — HTTP, navigation,
;; analytics — would escape) AND the handler's own `:db` write must NOT
;; land (no partial commit).
;;
;; Per rf2-u0zz5: the flow transform is the outermost `:after`; on a
;; throw it `dissoc`-es the pending `:db` effect and stashes
;; `:rf/flow-error` on the context, so the install is a no-op and
;; `commit-and-flow!` skips `run-fx-effects!`.
;; ---------------------------------------------------------------------------

(deftest fx-does-not-run-after-flow-throws
  (testing "when a flow's :output throws, the handler's :fx is skipped AND its :db does NOT land"
    (let [child-fired? (atom false)]
      (rf/reg-event-db :after-throw
                       (fn [db _] (reset! child-fired? true) db))
      (rf/reg-event-fx :run-with-throwing-flow
                       (fn [_ _]
                         {:db {:n 2}
                          :fx [[:dispatch [:after-throw]]]}))
      ;; Register a flow that throws. The flow runs as the outermost
      ;; :after — after the handler, BEFORE :db install and BEFORE :fx
      ;; walks (rf2-u0zz5); a throw aborts the event, so :after-throw
      ;; must NOT dispatch and the handler's :db must NOT install.
      (rf/reg-flow {:id     :boom
                    :inputs [[:n]]
                    :output (fn [_] (throw (ex-info "boom" {:why :test})))
                    :path   [:doomed]})
      (rf/dispatch-sync [:run-with-throwing-flow])
      (is (false? @child-fired?)
          "`:after-throw` did NOT dispatch — :fx was skipped because the flow threw")
      ;; Atomicity contract: the handler's :db write was DISCARDED — the
      ;; event aborted with no install (app-db unchanged).
      (is (not (contains? (rf/get-frame-db :rf/default) :n))
          ":n absent — the handler's :db did NOT land; a flow throw aborts the event with no install"))))

(deftest fx-after-successful-flow-still-runs
  (testing "Per rf2-fslx0: when flows succeed, :fx still walks normally (negative control)"
    (let [child-fired? (atom false)]
      (rf/reg-event-db :init (fn [_ _] {:n 1}))
      (rf/reg-event-db :after-ok
                       (fn [db _] (reset! child-fired? true) db))
      (rf/reg-event-fx :run-with-ok-flow
                       (fn [_ _]
                         {:db {:n 2}
                          :fx [[:dispatch [:after-ok]]]}))
      (rf/reg-flow {:id     :double
                    :inputs [[:n]]
                    :output (fn [n] (* 2 n))
                    :path   [:doubled]})
      (rf/dispatch-sync [:init])
      (reset! child-fired? false)
      (rf/dispatch-sync [:run-with-ok-flow])
      (is (true? @child-fired?)
          "`:after-ok` DID dispatch — :fx walked because the flow succeeded")
      (is (= 4 (:doubled (rf/get-frame-db :rf/default)))
          "flow output landed in app-db (sanity)"))))

(deftest fx-skip-on-flow-throw-still-emits-error-substrate
  (testing "Per rf2-fslx0 + rf2-hrt5c: when :fx is skipped on flow throw, the error substrate still fires"
    ;; Pin that the cascade-halt does NOT short-circuit the error fan-
    ;; out — ops monitors still see the failure record even though :fx
    ;; was skipped.
    (let [seen (atom [])]
      (rf/register-error-listener!
        :test/fx-skip-recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event-fx :run-with-throwing-flow
                       (fn [_ _]
                         {:db {:n 2}
                          :fx [[:dispatch [:must-not-fire]]]}))
      (rf/reg-event-db :must-not-fire (fn [db _] db))
      (rf/reg-flow {:id     :boom
                    :inputs [[:n]]
                    :output (fn [_] (throw (ex-info "boom" {})))
                    :path   [:doomed]})
      (rf/dispatch-sync [:run-with-throwing-flow])
      (is (>= (count @seen) 1)
          "error-emit substrate fired — the cascade-halt does not silence the error fan-out")
      (is (some #(= :rf.error/flow-eval-exception (:error %)) @seen)
          "at least one substrate record carries :error :rf.error/flow-eval-exception"))))

;; ---------------------------------------------------------------------------
;; 7e. An fx throw does NOT wind back app-db — the POST-commit boundary
;;     (atomicity contract, Mike 2026-05-24; rf2-q1sbo).
;;
;; `:fx` is the ONLY post-install stage. By the time it walks, the
;; deferred (flow-augmented) `:db` has ALREADY committed: an fx throw
;; surfaces an error but MUST NOT roll back app-db — the fx side effects
;; (HTTP, navigation, dispatch) may already have fired and are
;; irreversible, so unwinding the db would desync state from the world.
;; This is the mirror of `fx-does-not-run-after-flow-throws` (a PRE-commit
;; throw aborts wholesale): a POST-commit throw leaves everything
;; committed. Previously this was covered only by a machine-proxy test;
;; this pins the flow-specific case directly.
;; ---------------------------------------------------------------------------

(deftest fx-throw-does-not-wind-back-handler-db-or-flow-output
  (testing "when a sibling :fx throws AFTER commit, the handler's :db AND the
            flow's output STAY committed (app-db reflects them) — :fx is the
            post-commit stage and does not unwind the install"
    (let [other-fired? (atom false)]
      (rf/reg-fx :test/boom-fx (fn [& _] (throw (ex-info "fx boom" {:why :test}))))
      (rf/reg-fx :test/ok-fx   (fn [& _] (reset! other-fired? true) nil))
      ;; Handler writes :n; flow :double reads :n and writes :doubled. The
      ;; :fx vector has a throwing fx alongside an ok fx — the throw must
      ;; NOT discard the already-committed handler :db or flow output.
      (rf/reg-event-fx :commit-then-fx-throw
                       (fn [_ _]
                         {:db {:n 5}
                          :fx [[:test/ok-fx true]
                               [:test/boom-fx true]]}))
      (rf/reg-flow {:id     :double
                    :inputs [[:n]]
                    :output (fn [n] (* 2 n))
                    :path   [:doubled]})
      ;; An fx throw surfaces an error event but does not propagate out of
      ;; the dispatch (each fx is isolated per Spec 002 §Cascade
      ;; propagation); the drain continues. dispatch-sync returns normally.
      (rf/dispatch-sync [:commit-then-fx-throw])
      (let [db (rf/get-frame-db :rf/default)]
        (is (= 5 (:n db))
            ":n stayed committed — the handler's :db was installed before the
             post-commit :fx walk; the fx throw does NOT wind it back")
        (is (= 10 (:doubled db))
            ":doubled stayed committed — the flow output rode the same
             deferred install and survives the post-commit fx throw")))))

;; ---------------------------------------------------------------------------
;; 7f. No spurious `:rf.event/db-changed` on a no-write event — the
;;     deferred-install contract (rf2-q1sbo).
;;
;; A `reg-event-fx` handler that returns NO `:db` (only `:fx`), whose
;; flows' inputs are all unchanged (so every flow SKIPS), produces no
;; `:db` effect at all → the deferred install is a no-op → ZERO
;; `:rf.event/db-changed` must be emitted. A spurious db-changed on a
;; no-op drain would mislead off-box monitors and trigger needless sub
;; recompute.
;; ---------------------------------------------------------------------------

(deftest no-db-changed-on-no-write-event-with-stable-flows
  (testing "a reg-event-fx returning only :fx [] (no :db), with flows whose
            inputs are unchanged, emits ZERO :rf.event/db-changed"
    (rf/reg-fx :test/noop (fn [& _] nil))
    ;; Seed :n so the flow computes once on the seed drain.
    (rf/reg-event-db :seed (fn [_ _] {:n 7}))
    ;; This handler writes NO :db — only an :fx that is a noop.
    (rf/reg-event-fx :no-write
                     (fn [_ _] {:fx [[:test/noop true]]}))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :output (fn [n] (* 2 n))
                  :path   [:doubled]})
    ;; First drain seeds :n and computes the flow (outside the record
    ;; window — we only care about the no-write drain's trace stream).
    (rf/dispatch-sync [:seed])
    (let [;; Dispatch the no-write event in its own record window so we
          ;; isolate its trace stream from the seed drain.
          evs (record-all-traces
                (fn [] (rf/dispatch-sync [:no-write])))
          ops (mapv :operation evs)]
      ;; The handler returned no :db, and :n is unchanged → the flow skips,
      ;; so the pending :db effect is empty → no install → no db-changed.
      (is (not-any? #(= :rf.event/db-changed %) ops)
          "ZERO :rf.event/db-changed — the no-write handler produced no :db
           effect and the unchanged-input flow skipped (no install)")
      ;; Sanity: the flow DID skip (proves the inputs were stable, so the
      ;; absence of db-changed is the real no-op path, not a flow that
      ;; never registered).
      (is (some #(= :rf.flow/skip %) ops)
          ":rf.flow/skip fired — the flow's inputs were value-equal, so it
           contributed no write to the (empty) pending :db effect")
      ;; And the cascade still completed cleanly (run-end fired).
      (is (some #(= :rf.event/run-end %) ops)
          ":rf.event/run-end still fires — the cascade completed normally"))))

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

(deftest computed-trace-elides-large-before
  (testing ":rf.flow/computed :before rides through elide-wire-value just like :result (rf2-qlzh4)"
    ;; Seed [:derived :blob] with a non-nil value so :before is non-
    ;; nil on the FIRST flow drain we observe. Then bump :n to drive
    ;; a recompute whose :before reads the previous blob and is
    ;; therefore schema-large. The walker must substitute the marker.
    (rf/reg-event-db :init (fn [db _] (merge db {:n 1 :derived {:blob {:bytes "PRESEEDED"}}})))
    (rf/reg-event-db :bump (fn [db _] (update db :n inc)))
    (rf/reg-flow {:id     :payload
                  :inputs [[:n]]
                  :output (fn [n] {:bytes (str "blob-" n)})
                  :path   [:derived :blob]})
    (rf/reg-app-schema [:derived :blob] [:map {:large? true}])
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:bump])
    (let [computes (by-op :rf.flow/computed)
          last-ev  (last computes)
          tags     (:tags last-ev)]
      (is (some? last-ev) ":rf.flow/computed fired on the recompute")
      (is (elision/marker? (:before tags))
          ":before is replaced by the `:rf.size/large-elided` marker — the slot is schema-large")
      (is (elision/marker? (:result tags))
          ":result is similarly elided (sanity)")
      (let [marker (:rf.size/large-elided (:before tags))]
        (is (= [:derived :blob] (:path marker))
            "before-marker carries the schema-declared path")
        (is (= :schema (:reason marker))
            "before-marker carries :reason :schema")))))

;; ---------------------------------------------------------------------------
;; 8. `:sensitive?` inheritance on `:rf.flow/*` traces (Spec 013 §`:sensitive?`
;;    inheritance, 013-Flows.md:242).
;;
;; Spec 013 is normative: the runtime stamps `:sensitive? true` at the top
;; level of every `:rf.flow/*` trace event when the in-scope handler's
;; cascade is sensitive — "the flow itself does not declare `:sensitive?`
;; directly; the marker rides the cascade." Sensitivity is schema-derived
;; per rf2-hjs2d: a handler scoped (`rf/path`) over a schema slot marked
;; `{:sensitive? true}` makes the router bind `:rf/sensitive? true` into the
;; handler scope. Flows run inside that scope (`commit-and-flow!` sits inside
;; `run-handler-cascade!`'s `with-handler-scope`), so `trace/build-event`'s
;; `compute-sensitive?` hoists the stamp onto the flow trace automatically —
;; the same handler-scope inheritance every other in-cascade trace uses.
;;
;; These tests pin the contract end-to-end so a future reorder of the drain
;; (e.g. moving the flow walk outside the handler scope) cannot silently
;; strip the privacy marker — which would leak auth-handler-triggered flow
;; recompute traces past the default-drop forwarders (Sentry / Causa-MCP).
;; ---------------------------------------------------------------------------

(deftest flow-computed-trace-inherits-sensitive-from-schema-scope
  (testing "Spec 013:242 — `:rf.flow/computed` is stamped `:sensitive? true`
            when the triggering handler's cascade is schema-sensitive"
    ;; Sensitive schema slot at [:auth :token]; a path-scoped handler
    ;; writes it (drives the router's schema-derived overlap → scope
    ;; `:rf/sensitive? true`). The flow reads [:auth :token] and writes
    ;; [:auth :derived-user] — it recomputes inside the sensitive scope.
    (rf/reg-app-schema [:auth]
                       [:map
                        [:token {:sensitive? true} :string]])
    (rf/reg-flow {:id     :auth/derived-user
                  :inputs [[:auth :token]]
                  :output (fn [t] (str "user-of-" t))
                  :path   [:auth :derived-user]})
    (rf/reg-event-db :auth/signed-in
                     [(rf/path :auth)]
                     (fn [auth [_ token]] (assoc auth :token token)))
    (reset! *captured* [])
    (rf/dispatch-sync [:auth/signed-in "secret-token"])
    (let [computes (by-op :rf.flow/computed)]
      (is (= 1 (count computes))
          "the flow recomputed once on the sensitive handler's drain")
      (is (true? (:sensitive? (first computes)))
          ":rf.flow/computed carries a top-level `:sensitive? true` stamp —
           inherited from the schema-sensitive handler scope (Spec 013:242)"))))

(deftest flow-failed-trace-inherits-sensitive-from-schema-scope
  (testing "Spec 013:242 — `:rf.flow/failed` is also stamped `:sensitive?`
            when the triggering handler's cascade is schema-sensitive"
    (rf/reg-app-schema [:auth]
                       [:map
                        [:token {:sensitive? true} :string]])
    (rf/reg-flow {:id     :auth/derived-user
                  :inputs [[:auth :token]]
                  :output (fn [_] (throw (ex-info "derive boom" {})))
                  :path   [:auth :derived-user]})
    (rf/reg-event-db :auth/signed-in
                     [(rf/path :auth)]
                     (fn [auth [_ token]] (assoc auth :token token)))
    (reset! *captured* [])
    (rf/dispatch-sync [:auth/signed-in "secret-token"])
    (let [failures (by-op :rf.flow/failed)]
      (is (= 1 (count failures))
          "the flow threw once on the sensitive handler's drain")
      (is (true? (:sensitive? (first failures)))
          ":rf.flow/failed carries the top-level `:sensitive? true` stamp too"))))

(deftest flow-skip-trace-inherits-sensitive-from-schema-scope
  (testing "Spec 013:242 — `:rf.flow/skip` is stamped `:sensitive?` when the
            triggering handler's cascade is schema-sensitive"
    (rf/reg-app-schema [:auth]
                       [:map
                        [:token {:sensitive? true} :string]])
    (rf/reg-flow {:id     :auth/derived-user
                  :inputs [[:auth :token]]
                  :output (fn [t] (str "user-of-" t))
                  :path   [:auth :derived-user]})
    (rf/reg-event-db :auth/signed-in
                     [(rf/path :auth)]
                     (fn [auth [_ token]] (assoc auth :token token)))
    ;; First sign-in computes; second sign-in with the SAME token leaves
    ;; the input value-equal → `:rf.flow/skip` fires, still inside the
    ;; schema-sensitive handler scope.
    (rf/dispatch-sync [:auth/signed-in "secret-token"])
    (reset! *captured* [])
    (rf/dispatch-sync [:auth/signed-in "secret-token"])
    (let [skips (by-op :rf.flow/skip)]
      (is (= 1 (count skips))
          "the value-equal rewrite produced one `:rf.flow/skip`")
      (is (true? (:sensitive? (first skips)))
          ":rf.flow/skip carries the top-level `:sensitive? true` stamp"))))

(deftest flow-trace-NOT-sensitive-when-handler-not-sensitive
  (testing "Spec 013:242 negative — a flow recompute driven by a NON-sensitive
            handler does NOT carry the `:sensitive?` stamp (absent reads false)"
    ;; No sensitive schema slot is under the handler's focus, so the
    ;; router computes no overlap and the scope is not sensitive. The
    ;; flow trace must NOT acquire a stamp.
    (rf/reg-app-schema [:auth]
                       [:map
                        [:token {:sensitive? true} :string]])
    ;; This handler is path-scoped to :profile (disjoint from the sensitive
    ;; :auth slot) and the flow reads :profile — non-sensitive cascade.
    (rf/reg-flow {:id     :profile/derived
                  :inputs [[:profile :name]]
                  :output (fn [n] (str "hello-" n))
                  :path   [:profile :greeting]})
    (rf/reg-event-db :profile/rename
                     [(rf/path :profile)]
                     (fn [profile [_ name]] (assoc profile :name name)))
    (reset! *captured* [])
    (rf/dispatch-sync [:profile/rename "ada"])
    (let [computes (by-op :rf.flow/computed)]
      (is (= 1 (count computes))
          "the profile flow recomputed once")
      (is (not (contains? (first computes) :sensitive?))
          ":rf.flow/computed has NO `:sensitive?` key — non-sensitive cascade
           (absent, not `false`, per the top-level-stamp contract)"))))

(deftest flow-trace-sensitive-value-also-redacted-on-wire
  (testing "defence-in-depth: when a flow's input/output path is itself a
            sensitive schema slot, the wire-bearing payload is ALSO redacted
            (via `elide-wire-value`) on top of the top-level `:sensitive?`
            stamp — both privacy layers fire for a sensitive flow trace"
    ;; The flow's :path is the sensitive slot, so `:result` (and the
    ;; sensitive input) ride through the elision walker → `:rf/redacted`,
    ;; while the cascade is sensitive → top-level stamp.
    (rf/reg-app-schema [:auth]
                       [:map
                        [:token {:sensitive? true} :string]
                        [:derived-token {:sensitive? true} :string]])
    (rf/reg-flow {:id     :auth/derived-token
                  :inputs [[:auth :token]]
                  :output (fn [t] (str "derived-" t))
                  :path   [:auth :derived-token]})
    (rf/reg-event-db :auth/signed-in
                     [(rf/path :auth)]
                     (fn [auth [_ token]] (assoc auth :token token)))
    (reset! *captured* [])
    (rf/dispatch-sync [:auth/signed-in "secret-token"])
    (let [ev   (first (by-op :rf.flow/computed))
          tags (:tags ev)]
      (is (some? ev) ":rf.flow/computed fired")
      (is (true? (:sensitive? ev))
          "top-level `:sensitive?` stamp present (cascade is sensitive)")
      (is (= :rf/redacted (:result tags))
          ":result value redacted on the wire — its :path is a sensitive slot")
      (is (= :rf/redacted (first (:input-values tags)))
          "the sensitive input value is redacted on the wire too"))))

;; ---------------------------------------------------------------------------
;; 8b. Strict trace-stream ordering on a flow throw — atomicity contract
;;     (Spec 013 §Failure semantics / §Trace stream ordering on a flow
;;     throw; rf2-u0zz5, Mike 2026-05-24).
;;
;; A flow throw is a PRE-INSTALL throw: the event aborts. The router
;; DISCARDS the pending `:db` effect, so NO `:rf.event/db-changed` is
;; emitted and app-db is UNCHANGED. The throw stream carries, in order,
;; `:rf.flow/failed` → `:rf.error/flow-eval-exception` and STOPS — no
;; `:rf.event/db-changed`, no `:rf.fx/handled` (the cascade halts; the
;; :fx GAP is pinned by `fx-does-not-run-after-flow-throws` above).
;; ---------------------------------------------------------------------------

(deftest flow-throw-trace-stream-is-strictly-ordered
  (testing "rf2-u0zz5 atomicity — flow/failed → flow-eval-exception, and
            NO db-changed in the throw stream; app-db unchanged after the
            throw"
    (let [evs (record-all-traces
                (fn []
                  ;; The handler writes :n AND prior flow :ok writes :a-out,
                  ;; so a :db effect + a prior-flow write both exist when
                  ;; :boom throws — proving that EVEN THEN nothing installs.
                  (rf/reg-event-db :bump (fn [db _] (update db :n (fnil inc 0))))
                  (rf/reg-flow {:id     :ok
                                :inputs [[:n]]
                                :output (fn [n] (* 10 n))
                                :path   [:a-out]})
                  (rf/reg-flow {:id     :boom
                                :inputs [[:a-out]]
                                :output (fn [_] (throw (ex-info "boom" {})))
                                :path   [:doomed]})
                  (rf/dispatch-sync [:bump])))
          ;; Restrict to the :bump cascade. Take everything from the
          ;; :bump :run-start trace to the end.
          evs-v       (vec evs)
          bump-start  (->> (map-indexed vector evs-v)
                           (filter (fn [[_ ev]]
                                     (and (= :rf.event/run-start (:operation ev))
                                          (= [:bump] (get-in ev [:tags :rf.event/v])))))
                           (map first)
                           last)
          tail        (subvec evs-v bump-start)
          ops         (mapv :operation tail)
          ;; positions within the bump cascade
          pos         (fn [op] (.indexOf ^java.util.List ops op))
          p-failed    (pos :rf.flow/failed)
          p-error     (pos :rf.error/flow-eval-exception)]
      ;; NO db-changed in the throw stream — the event aborted before install.
      (is (not-any? #(= :rf.event/db-changed %) ops)
          "NO :rf.event/db-changed in the throw stream — the event aborted before install")
      ;; Ordered: flow failure precedes the cascade-level error.
      (is (and (<= 0 p-failed) (< p-failed p-error))
          (str "ordered: :rf.flow/failed (" p-failed ") < :rf.error/flow-eval-exception ("
               p-error ")"))
      ;; app-db is UNCHANGED — nothing the aborted drain produced landed.
      (let [db (rf/get-frame-db :rf/default)]
        (is (= {} db)
            "app-db is unchanged (empty initial value) — no install on a flow throw")
        (is (not (contains? db :n))
            "the handler's :n write did NOT land")
        (is (not (contains? db :a-out))
            "the prior flow's :a-out write did NOT land (no partial commit)")
        (is (not (contains? db :doomed))
            "the failing flow's own output is NOT written"))
      ;; No :rf.fx/handled fires after the flow-eval-exception (cascade halt).
      (let [after-error (subvec ops (inc p-error))]
        (is (not-any? #(= :rf.fx/handled %) after-error)
            "no :rf.fx/handled trace fires after the flow-eval-exception — cascade halts")))))

;; ---------------------------------------------------------------------------
;; 8c. Strict trace-stream ordering on the CLEAN (success) path — pins the
;;     Spec 009 §Canonical per-event trace sequence diagram against the impl
;;     (rf2-q1sbo follow-up to rf2-u0zz5, Mike 2026-05-24).
;;
;; The load-bearing fact the 009 diagram encodes: `:rf.event/run-end` is a
;; CASCADE-TAIL trace — the router emits it in `emit-cascade-trailers!`
;; AFTER `commit-and-flow!` has installed the deferred (flow-augmented)
;; `:db` and walked `:fx`. So a clean cascade orders:
;;
;;   :rf.flow/computed → :rf.event/db-changed → :rf.fx/handled
;;     → :rf.fx/do-fx (terminating fx-walk marker) → :rf.event/run-end (LAST)
;;
;; Pre-rf2-u0zz5 the diagram had `:rf.event/run-end` BEFORE
;; `:rf.event/db-changed`; this test conformance-checks the corrected
;; ordering so the diagram can't silently drift back.
;; ---------------------------------------------------------------------------

(deftest clean-path-trace-stream-run-end-fires-last
  (testing "rf2-q1sbo — on a clean cascade, :rf.event/run-end fires LAST:
            after the deferred :db install (:rf.event/db-changed) and the
            :fx walk (:rf.fx/handled → terminating :rf.fx/do-fx marker)"
    (let [evs (record-all-traces
                (fn []
                  ;; Handler writes :db AND a flow recomputes AND a real
                  ;; (user-registered) fx fires — so the cascade exercises
                  ;; install + flow + fx all on the success path.
                  (rf/reg-fx :test/noop (fn [& _] nil))
                  (rf/reg-event-fx :go
                                   (fn [_ _]
                                     {:db {:n 3}
                                      :fx [[:test/noop true]]}))
                  (rf/reg-flow {:id     :double
                                :inputs [[:n]]
                                :output (fn [n] (* 2 n))
                                :path   [:doubled]})
                  (rf/dispatch-sync [:go])))
          evs-v       (vec evs)
          ;; Restrict to the :go cascade — from its :run-start trace on.
          go-start    (->> (map-indexed vector evs-v)
                           (filter (fn [[_ ev]]
                                     (and (= :rf.event/run-start (:operation ev))
                                          (= [:go] (get-in ev [:tags :rf.event/v])))))
                           (map first)
                           last)
          tail        (subvec evs-v go-start)
          ops         (mapv :operation tail)
          pos         (fn [op] (.indexOf ^java.util.List ops op))
          p-computed  (pos :rf.flow/computed)
          p-db        (pos :rf.event/db-changed)
          p-handled   (pos :rf.fx/handled)
          p-do-fx     (pos :rf.fx/do-fx)
          p-run-end   (pos :rf.event/run-end)]
      ;; Sanity: every phase fired exactly once in this cascade.
      (is (= 1 (count (filterv #(= :rf.event/run-end %) ops)))
          "exactly one :rf.event/run-end in the cascade")
      (is (= 1 (count (filterv #(= :rf.event/db-changed %) ops)))
          "exactly one :rf.event/db-changed in the cascade")
      ;; The flow recomputed and the db installed — flow BEFORE install.
      (is (and (<= 0 p-computed) (< p-computed p-db))
          (str ":rf.flow/computed (" p-computed ") < :rf.event/db-changed (" p-db ")"))
      ;; The user fx ran AFTER the install (post-commit stage).
      (is (and (<= 0 p-handled) (< p-db p-handled))
          (str ":rf.event/db-changed (" p-db ") < :rf.fx/handled (" p-handled ")"))
      ;; :rf.fx/do-fx is the TERMINATING fx-walk marker — after the per-fx
      ;; :rf.fx/handled (per re-frame.fx/do-fx, which emits it last).
      (is (and (<= 0 p-do-fx) (< p-handled p-do-fx))
          (str ":rf.fx/handled (" p-handled ") < :rf.fx/do-fx terminating marker ("
               p-do-fx ")"))
      ;; THE load-bearing assertion: run-end is the LAST trace of the
      ;; cascade — after db-changed AND after the whole :fx walk.
      (is (< p-db p-run-end)
          (str ":rf.event/db-changed (" p-db ") < :rf.event/run-end (" p-run-end
               ") — run-end fires AFTER the deferred install, not before"))
      (is (= (dec (count ops)) p-run-end)
          ":rf.event/run-end is the FINAL trace of the clean cascade"))))
