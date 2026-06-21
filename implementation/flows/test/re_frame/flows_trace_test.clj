(ns re-frame.flows-trace-test
  "JVM coverage for Spec 009 §Flow trace events / Spec 013 §Flow tracing
  — verifies the five `:rf.flow/*` lifecycle events fire with the
  documented payloads. The conformance fixture
  `flow-lifecycle-emits-traces.edn` describes the same shapes as data and
  is driven against the live runtime by `re-frame.flows-conformance-test`;
  this file exercises them against the JVM reference implementation
  directly so a regression surfaces as a plain unit-test failure rather
  than only through the data-driven gate.

  The `:flow` op-type and `:rf.flow/*` operation vocabulary back
  re-frame-10x v2's flow panel."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.privacy :as privacy]
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
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (flows/reset-last-inputs!)
  ;; The error-emit listener registry is a `defonce` atom that survives test
  ;; re-runs. Clear before each test so a listener registered by one test
  ;; doesn't leak into the next.
  (error-emit/clear-error-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  ;; EP-0002: reg-flow is context-required frame-local — an ambient call
  ;; under no scope raises :rf.error/no-frame-context. Pin :rf/default (an
  ;; ordinary frame) as the established scope for the body.
  (frame/ensure-default-frame!)
  (let [captured (atom [])]
    (binding [*captured*           captured
              frame/*current-frame* :rf/default]
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

;; EP-0025: durable app-db egress classification rides the commit-plane
;; classification effects (a `reg-event` returns `:sensitive` / `:large`
;; alongside `:db`, written `:source :effect` by
;; `elision/apply-classification-effects`). The durable `:sensitive` /
;; `:large {:app-db …}` frame annotation is REMOVED; schema-attached
;; `{:sensitive? true}` / `{:large? true}` slot props do not feed the frame
;; elision registry the walker reads. These helpers seed the classification
;; via the surviving effect path.

(defn- install-large!
  "Seed the frame's large app-db classification via the commit-plane effect
  path (`:source :effect`). Marker `:reason` for these paths is `:effect`."
  [frame-id & paths]
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt {:large (mapv vec paths)}))))

(defn- install-sensitive!
  "Seed the frame's sensitive app-db classification via the commit-plane
  effect path (`:source :effect`). Drives the router's per-handler overlap
  stamp + on-wire redaction, the same way schema-sensitive slots formerly
  did."
  [frame-id & paths]
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt {:sensitive (mapv vec paths)}))))

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
                  :derive (fn [w h] (* (or w 0) (or h 0)))
                  :output-path   [:rect :area]})
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
            `:rf.registry/handler-replaced` trace (emitted DIRECTLY by
            `reg-flow` under rf2-en00bk single-store, per Spec 001
            §Hot-reload trace surface) is the hot-reload signal — both
            traces no longer double-emit on the same re-registration."
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :derive (fn [w h] (* (or w 0) (or h 0)))
                  :output-path   [:rect :area]})
    (is (= 1 (count (by-op :rf.flow/registered)))
        "first-time registration fires :rf.flow/registered once")
    (reset! *captured* [])
    ;; Re-register with the SAME shape — :rf.flow/registered must NOT fire.
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :derive (fn [w h] (* (or w 0) (or h 0)))
                  :output-path   [:rect :area]})
    (is (zero? (count (by-op :rf.flow/registered)))
        "re-registration does NOT fire :rf.flow/registered — hot-reload signal rides :rf.registry/handler-replaced (emitted directly by reg-flow, rf2-en00bk)"))
  (testing "re-registration with a NEW :derive also does not double-emit"
    (rf/reg-flow {:id     :area2
                  :inputs [[:w]]
                  :derive (fn [w] w)
                  :output-path   [:rect :area2]})
    (reset! *captured* [])
    (rf/reg-flow {:id     :area2
                  :inputs [[:w]]
                  :derive (fn [w] (* 2 w))
                  :output-path   [:rect :area2]})
    (is (zero? (count (by-op :rf.flow/registered)))
        "real body change still does not re-emit :rf.flow/registered — only first-time")))

(deftest reg-flow-registered-is-per-frame-not-per-global-id
  (testing "Per rf2-mb9vq: registering the SAME flow-id against two
            different frames emits TWO `:rf.flow/registered` events, each
            tagged with its own `:frame`. Pre-fix the second frame's
            registration was suppressed because `:rf.flow/registered`
            gated on the GLOBAL registrar `:was` (flow-id-scoped), so a
            same-id/different-frame FIRST registration was misclassified
            as a replacement — a per-frame flow inventory built from
            `op-type :flow` missed it."
    (rf/reg-frame :left  {:doc "left frame"})
    (rf/reg-frame :right {:doc "right frame"})
    ;; First-time registration on :left — emits with :frame :left.
    (rf/reg-flow {:id     :shared
                  :inputs [[:n]]
                  :derive (fn [n] (* 2 (or n 0)))
                  :output-path   [:result]}
                 {:frame :left})
    ;; First-time registration of the SAME id on :right — an INDEPENDENT
    ;; definition per Spec 013 §Frame-scoping line 102; must ALSO emit.
    (rf/reg-flow {:id     :shared
                  :inputs [[:n]]
                  :derive (fn [n] (* 100 (or n 0)))
                  :output-path   [:result]}
                 {:frame :right})
    (let [evs (by-op :rf.flow/registered)]
      (is (= 2 (count evs))
          "TWO :rf.flow/registered — one per frame's first-time registration")
      (is (= #{:left :right}
             (set (map #(get-in % [:tags :frame]) evs)))
          "the two events carry distinct :frame tags (:left and :right)")
      (is (every? #(= :shared (get-in % [:tags :flow-id])) evs)
          "both events name the :shared flow-id"))))

(deftest reg-flow-same-frame-re-register-still-suppresses
  (testing "Per rf2-mb9vq: a genuine SAME-FRAME re-registration still
            suppresses `:rf.flow/registered` (its hot-reload signal rides
            `:rf.registry/handler-replaced`, emitted directly by reg-flow
            under rf2-en00bk single-store) — the per-frame gating must
            not over-fire for the replacement case."
    (rf/reg-frame :left {:doc "left frame"})
    (rf/reg-flow {:id     :shared
                  :inputs [[:n]]
                  :derive (fn [n] (* 2 (or n 0)))
                  :output-path   [:result]}
                 {:frame :left})
    (is (= 1 (count (by-op :rf.flow/registered)))
        "first-time registration on :left emitted once")
    (reset! *captured* [])
    ;; Re-register on the SAME frame with a NEW body — replacement, NOT a
    ;; first-time registration. :rf.flow/registered must NOT fire again.
    (rf/reg-flow {:id     :shared
                  :inputs [[:n]]
                  :derive (fn [n] (* 7 (or n 0)))
                  :output-path   [:result]}
                 {:frame :left})
    (is (zero? (count (by-op :rf.flow/registered)))
        "same-frame re-registration does NOT re-emit :rf.flow/registered")))

(deftest reg-flow-cycle-does-NOT-emit-registered
  (testing "when reg-flow throws cycle, no :rf.flow/registered fires for the rejected flow"
    (rf/reg-flow {:id :a :inputs [[:b]] :derive identity :output-path [:a]})
    ;; one event so far for :a
    (is (= 1 (count (by-op :rf.flow/registered))))
    (is (thrown? Throwable
                 (rf/reg-flow {:id :b :inputs [[:a]] :derive identity :output-path [:b]})))
    ;; Still just the one — :b's registration unwound before the trace.
    (is (= 1 (count (by-op :rf.flow/registered)))
        "only :a's register trace; :b's was rolled back")))

;; ---------------------------------------------------------------------------
;; 2. :rf.flow/computed fires when a flow recomputes
;; ---------------------------------------------------------------------------

(deftest flow-computed-fires-on-input-change
  (testing "first drain after registration emits :rf.flow/computed with :input-values, :result, :path, :frame"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:w 3 :h 4}}))
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :derive (fn [w h] (* w h))
                  :output-path   [:rect :area]})
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
          ;; :before carries the value at :output-path immediately BEFORE
          ;; this flow's write. On the first compute the slot has never been
          ;; written, so :before is nil. The KEY is always present so consumers
          ;; can rely on uniform shape (rather than discriminating between
          ;; absent-key and explicit-nil).
          (is (contains? tags :before)
              ":before key present on every :rf.flow/computed trace")
          (is (nil? (:before tags))
              "first compute — :output-path has never been written; :before is nil")
          ;; Per-op DURATION — the flow :derive recompute's wall-clock,
          ;; dev-only, so the Trace panel's DURATION column reads it off
          ;; :rf.flow/computed.
          (is (contains? tags :elapsed-ms)
              ":elapsed-ms present on every :rf.flow/computed trace")
          (is (number? (:elapsed-ms tags))
              ":elapsed-ms is a number")
          (is (>= (:elapsed-ms tags) 0)
              ":elapsed-ms is non-negative"))))))

;; ---------------------------------------------------------------------------
;; 2b. :before slot tracks the pre-write value across drains
;;
;; Per Spec 013 §Flow tracing: `:rf.flow/computed` carries `:before`
;; — the value at the flow's `:output-path` immediately before this drain's
;; write. Self-contained trace consumers (Xray Event Detail, 10x
;; flow panel) render the "wrote [path] <before> -> <after>" line
;; without walking the surrounding epoch's `:db-before` snapshot.
;; These tests pin the contract across the edge cases the audit
;; (ai/findings/2026-05-19-flow-trace-events-audit.md) flagged.
;; ---------------------------------------------------------------------------

(deftest computed-before-equals-prior-result-on-second-compute
  (testing "second :rf.flow/computed's :before equals the first compute's :result"
    (rf/reg-event :init      (fn [{:keys [db]} _] {:db {:n 3}}))
    (rf/reg-event :replace-n (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :derive (fn [n] (* 2 n))
                  :output-path   [:doubled]})
    (rf/dispatch-sync [:init])           ;; first compute: 3 -> 6
    (rf/dispatch-sync [:replace-n 5])    ;; second compute: 5 -> 10
    (let [computes (by-op :rf.flow/computed)]
      (is (= 2 (count computes))
          "one compute per real input change")
      (let [[first-ev second-ev] computes]
        (is (nil? (:before (:tags first-ev)))
            "first compute :before is nil — :output-path slot was unwritten")
        (is (= 6 (:before (:tags second-ev)))
            ":before of the second compute equals the first compute's :result")
        (is (= 10 (:result (:tags second-ev)))
            ":result is the freshly-computed output value")))))

(deftest computed-before-with-prior-value-already-at-path
  (testing ":before carries the prior value when the path was written by a non-flow source first"
    ;; Seed [:doubled] with a value the event handler put there before
    ;; the flow ever fires. The first compute's :before MUST be that
    ;; prior value — not nil — because the slot was non-empty.
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 3 :doubled :preseeded}}))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :derive (fn [n] (* 2 n))
                  :output-path   [:doubled]})
    (rf/dispatch-sync [:seed])
    (let [ev (last (by-op :rf.flow/computed))
          tags (:tags ev)]
      (is (= :preseeded (:before tags))
          ":before reads the pre-existing value at :output-path, not nil")
      (is (= 6 (:result tags))
          ":result is the new flow output"))))

(deftest computed-before-on-nested-path
  (testing ":before reads the pre-write value at a deeply-nested :output-path"
    (rf/reg-event :init  (fn [{:keys [db]} _] {:db {:w 3 :h 4 :rect {:area :initial-area}}}))
    (rf/reg-event :grow  (fn [{:keys [db]} _] {:db (assoc db :w 5)}))
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :derive (fn [w h] (* w h))
                  :output-path   [:rect :area]})
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
  (testing ":before is captured against the in-drain accumulator so chained-flow :before reads its own slot, not :output-path overlap"
    ;; :A writes [:a-out]. :B reads [:a-out] and writes [:b-out]. The
    ;; cascade fires A then B in the SAME drain — :B's :before must
    ;; reflect the pre-drain value at [:b-out] (here nil on first
    ;; compute), NOT some intermediate state from :A's write. Each
    ;; flow's :before is independent — captured against its own :output-path.
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 3}}))
    (rf/reg-event :bump (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-flow {:id     :A
                  :inputs [[:n]]
                  :derive (fn [n] (* 2 n))
                  :output-path   [:a-out]})
    (rf/reg-flow {:id     :B
                  :inputs [[:a-out]]
                  :derive (fn [a] (str "B-saw-" a))
                  :output-path   [:b-out]})
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
    (rf/reg-event :init       (fn [{:keys [db]} _] {:db {:n 5}}))
    (rf/reg-event :replace-n  (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :derive (fn [n] (* 2 n))
                  :output-path   [:derived :doubled]})
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
        (is (= :rf/default         (:frame tags)))
        ;; `:input-paths-unchanged` names every input db-path whose value was
        ;; `=` to the previous run — the cascade
        ;; DAG consumer reads this to render the "considered, no recompute"
        ;; branch dimmed. For a value-equal skip every input is by
        ;; definition unchanged, so the tag carries the FULL input-path
        ;; vector (the flow's `:inputs`). Pin both the key's presence and
        ;; its full-vector shape so a regression that drops it, ships a
        ;; partial vector, or renames the key surfaces here.
        (is (contains? tags :input-paths-unchanged)
            ":input-paths-unchanged key present on every :rf.flow/skip trace")
        (is (= [[:n]] (:input-paths-unchanged tags))
            ":input-paths-unchanged carries the flow's full :inputs vector (every input unchanged on a value-equal skip)")))))

(deftest flow-skip-input-paths-unchanged-names-all-inputs
  (testing "Per rf2-931pm: a multi-input flow's :rf.flow/skip carries the
            FULL :inputs vector under :input-paths-unchanged (not a single
            path, not a truncated subset)"
    ;; Two distinct input paths. On a value-equal rewrite the cascade-DAG
    ;; consumer must learn BOTH inputs were considered-and-unchanged, so
    ;; the tag must enumerate every declared input path.
    (rf/reg-event :init       (fn [{:keys [db]} _] {:db {:w 3 :h 4}}))
    (rf/reg-event :rewrite-wh (fn [{:keys [db]} [_ w h]] {:db (assoc db :w w :h h)}))
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :derive (fn [w h] (* w h))
                  :output-path   [:rect :area]})
    (rf/dispatch-sync [:init])
    (reset! *captured* [])
    ;; Rewrite both inputs with their SAME values → value-equal skip.
    (rf/dispatch-sync [:rewrite-wh 3 4])
    (let [skips (by-op :rf.flow/skip)]
      (is (= 1 (count skips)) "the value-equal rewrite produced one skip")
      (let [tags (:tags (first skips))]
        (is (= [[:w] [:h]] (:input-paths-unchanged tags))
            ":input-paths-unchanged enumerates BOTH declared input paths in order")))))

(deftest flow-skip-then-computed-on-real-change
  (testing "skip fires on equal rewrite; subsequent real change fires :rf.flow/computed"
    (rf/reg-event :init      (fn [{:keys [db]} _] {:db {:n 5}}))
    (rf/reg-event :replace-n (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :derive (fn [n] (* 2 n))
                  :output-path   [:derived :doubled]})
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
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:rect {:w 3 :h 4}}}))
    (rf/reg-flow {:id     :area
                  :inputs [[:rect :w] [:rect :h]]
                  :derive (fn [w h] (* w h))
                  :output-path   [:rect :area]})
    (rf/dispatch-sync [:seed])
    (reset! *captured* [])
    (flows/clear-flow :area)
    (let [evs (by-op :rf.flow/cleared)]
      (is (= 1 (count evs)))
      (let [tags (:tags (first evs))]
        (is (= :area         (:flow-id tags)))
        (is (= [:rect :area] (:path tags)))
        (is (= :rf/default   (:frame tags)))))))

(deftest clear-flow-on-unknown-id-emits-nothing
  (testing "clear-flow on an unregistered id is a no-op and emits no trace"
    (flows/clear-flow :no-such-flow)
    (is (zero? (count (by-op :rf.flow/cleared))))))

;; ---------------------------------------------------------------------------
;; 5. :rf.flow/failed fires when the :derive fn throws
;; ---------------------------------------------------------------------------

(deftest flow-failed-fires-when-output-throws
  (testing "a flow whose :derive fn throws emits :rf.flow/failed; the exception propagates"
    (rf/reg-event :init       (fn [{:keys [db]} _] {:db {:n 1}}))
    (rf/reg-event :bump       (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-flow {:id     :boom
                  :inputs [[:n]]
                  :derive (fn [_] (throw (ex-info "boom" {:why :test})))
                  :output-path   [:doomed]})
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
        ;; No raw Throwable `:ex` slot — the failure carries a structured,
        ;; EDN-safe exception summary instead (`:exception-message` plain
        ;; string + `:exception-data` ex-data map). No raw Throwable rides the
        ;; trace bus / epoch capture / tooling listeners by default.
        (is (not (contains? tags :ex))
            "no raw Throwable `:ex` slot — replaced by a structured summary")
        (is (= "boom" (:exception-message tags))
            ":exception-message carries the plain message string")
        (is (= {:why :test} (:exception-data tags))
            ":exception-data carries the (non-sensitive) ex-data map")
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
;;     error-emit substrate.
;;
;; A flow-eval throw routes through the always-on error-emit substrate, not
;; just `trace/emit-error!` — which is DCE'd by `goog.DEBUG=false` in CLJS
;; production builds, where it would leave flow failures silent to corpus-wide
;; error listeners (Sentry / Honeybadger / Rollbar shippers registered via
;; `register-error-listener!`). This matches the handler-exception path
;; (`emit-handler-exception!`). This test pins the routing: a flow-eval throw
;; surfaces on the listener registry record in JVM dev AND survives prod
;; elision in CLJS.
;; ---------------------------------------------------------------------------

(deftest flow-eval-exception-routes-through-error-emit-substrate
  (testing "Per rf2-hrt5c: a flow whose :derive throws fires a corpus-
            wide error-emit listener record with `:error
            :rf.error/flow-eval-exception` — fan-out runs through
            `error-emit/dispatch-on-error!`, mirroring the handler-
            exception path."
    (let [seen (atom [])]
      (rf/register-listener! :errors
        :test/flow-eval-recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 1}}))
      (rf/reg-flow {:id     :boom
                    :inputs [[:n]]
                    :derive (fn [_] (throw (ex-info "flow boom" {:why :test})))
                    :output-path   [:doomed]})
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

;; ---------------------------------------------------------------------------
;; 5b'. The flow-eval boundary re-throw carries the CANONICAL thrown-error
;;      shape.
;;
;; `evaluate-flow!`'s catch re-throws through `error/throw-error!` like every
;; other flows throw, so the rethrown ex-info carries the full canonical shape
;; (`:rf.error/id` / `:where` / `:recovery` / a human message bearing the
;; greppability token) — a tool reading `(:rf.error/id (ex-data e))` gets the
;; machine discriminator, not nil. The router surfaces the rethrown ex-info
;; ITSELF as the `:exception` slot of the cascade-level
;; `:rf.error/flow-eval-exception` record, so we inspect its ex-data there.
;; ---------------------------------------------------------------------------

(deftest flow-eval-boundary-rethrow-carries-canonical-thrown-error-shape
  (testing "the re-thrown flow-eval ex-info carries :rf.error/id + :where +
            :recovery + the :rf.flow/failed-id attribution in :extra, with a
            human message bearing the greppability token (rf2-cjr635)"
    (let [seen (atom [])]
      (rf/register-listener! :errors
        :test/flow-eval-shape-recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 1}}))
      (rf/reg-flow {:id     :boom
                    :inputs [[:n]]
                    :derive (fn [_] (throw (ex-info "flow boom" {:why :test})))
                    :output-path   [:doomed]})
      (rf/dispatch-sync [:init])
      (is (= 1 (count @seen)) "one error-emit record fired for the throw")
      (let [thrown (:exception (first @seen))
            data   (ex-data thrown)]
        (is (some? thrown) "the rethrown ex-info reached the substrate record")
        (is (= :rf.error/flow-eval-exception (:rf.error/id data))
            ":rf.error/id is the canonical machine discriminator (was nil pre-fix)")
        (is (= 'rf/run-flows-on-db (:where data))
            ":where names the user-facing surface symbol (bare canonical slot)")
        (is (= :no-recovery (:recovery data))
            ":recovery names the disposition (matches the catalogue row)")
        (is (string? (:reason data)) ":reason is a human sentence")
        (is (= :boom (:rf.flow/failed-id data))
            "the flow-attribution slot rides through (now under :extra)")
        (is (some? (:cause data))
            "the original exception is preserved under :cause for introspection")
        ;; Spec 009 §The thrown-error shape rule 4: trailing greppability token.
        (is (re-find #"\[:rf\.error/flow-eval-exception\]" (ex-message thrown))
            "the derived message carries the [:rf.error/<id>] token")
        ;; And it is a human sentence, not the bare keyword.
        (is (not= ":rf.error/flow-eval-exception" (ex-message thrown))
            "message is a human sentence, not the bare keyword")))))

;; ---------------------------------------------------------------------------
;; 5c. :rf.fx/reg-flow cycle detection routes through error-emit
;;
;; A cycle introduced through `:rf.fx/reg-flow` from a handler's `:fx` raises
;; `:rf.error/flow-cycle`; `handle-one-fx`'s reserved-fx branch catches it and
;; routes through `error-emit/dispatch-on-error!` with the `:cycle` ex-data
;; (the closing-repeat vector tools render) preserved, plus the dev-side trace
;; emit. This mirrors the handler-exception and flow-eval routings, so the
;; runtime cycle reaches corpus-wide error listeners even in CLJS production
;; (where a dev-only trace emit would be DCE'd).
;; ---------------------------------------------------------------------------

(deftest fx-reg-flow-cycle-routes-through-error-emit-substrate
  (testing "Per rf2-eb4lp: a :rf.fx/reg-flow that closes a cycle fires
            a corpus-wide error-emit listener record with `:error
            :rf.error/flow-cycle` — fan-out runs through
            `error-emit/dispatch-on-error!`, mirroring the
            handler-exception / flow-eval-exception paths."
    (let [seen (atom [])]
      (rf/register-listener! :errors
        :test/fx-reg-flow-cycle-recorder
        (fn [record] (swap! seen conj record)))
      ;; Register flow :a that depends on :b's path.
      (rf/reg-flow {:id     :a
                    :inputs [[:b-out]]
                    :derive identity
                    :output-path   [:a-out]})
      ;; Now dispatch an event whose :fx registers :b such that
      ;; :b's :inputs overlap :a's :output-path → cycle.
      (rf/reg-event :introduce-cycle
                       (fn [_ _]
                         {:fx [[:rf.fx/reg-flow
                                {:id     :b
                                 :inputs [[:a-out]]
                                 :derive identity
                                 :output-path   [:b-out]}]]}))
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
      (rf/register-listener! :errors
        :test/recorder
        (fn [record] (reset! listener-saw record)))
      (trace/register-listener!
        ::flow-eval-trace-recorder
        (fn [ev]
          (when (= :rf.error/flow-eval-exception (:operation ev))
            (reset! trace-saw ev))))
      (try
        (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 1}}))
        (rf/reg-flow {:id     :boom
                      :inputs [[:n]]
                      :derive (fn [_] (throw (ex-info "boom" {})))
                      :output-path   [:doomed]})
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
    (rf/reg-event :init      (fn [{:keys [db]} _] {:db {:n 3}}))
    (rf/reg-event :replace-n (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :derive (fn [n] (* 2 n))
                  :output-path   [:doubled]})
    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:replace-n 3])     ;; same → skip
    (rf/dispatch-sync [:replace-n 4])     ;; change → compute
    (flows/clear-flow :double)
    (is (= 1 (count (by-op :rf.flow/registered))))
    (is (pos?  (count (by-op :rf.flow/computed))))
    (is (= 1 (count (by-op :rf.flow/skip))))
    (is (= 1 (count (by-op :rf.flow/cleared))))
    (is (zero? (count (by-op :rf.flow/failed))))))

;; ---------------------------------------------------------------------------
;; 7. Wire-bearing flow trace payloads ride through `elide-wire-value`
;;    (Spec 009 §Size elision in traces / §Privacy contract for the flow
;;    trace surface).
;;
;; `:rf.flow/computed` carries `:input-values` and `:result`; `:rf.flow/failed`
;; carries `:inputs`. Per Spec 009 the wire-bearing payload of every tracer
;; surface passes through the elision walker (the single normative emission
;; site for `:rf.size/large-elided` and `:rf/redacted`), so a flow reading or
;; producing a large value is elided on the trace bus exactly as the sibling
;; tracers (event-emit, error-emit, dispatch trace) do. These tests pin the
;; routing.
;; ---------------------------------------------------------------------------

(deftest computed-trace-elides-large-result
  (testing ":rf.flow/computed :result rides through elide-wire-value — frame-large path is elided"
    ;; A plain replacing `:init` handler is safe: under the two-partition
    ;; contract a `:db` return replaces ONLY the app-db partition, so the
    ;; frame-installed elision registry — which lives in the runtime-db
    ;; partition at `[:rf.runtime/elision]` — survives untouched for the
    ;; flow's evaluate-time registry read. A replacing handler cannot reach
    ;; the runtime-db partition (see
    ;; `re-frame.events/reject-legacy-runtime-root!`).
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 1}}))
    (rf/reg-flow {:id     :payload
                  :inputs [[:n]]
                  :derive (fn [_] {:bytes "BIG"})
                  :output-path   [:derived :blob]})
    (install-large! :rf/default [:derived :blob])
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (let [ev   (last (by-op :rf.flow/computed))
          tags (:tags ev)]
      (is (some? ev) ":rf.flow/computed fired")
      (is (elision/marker? (:result tags))
          ":result is replaced by the `:rf.size/large-elided` marker")
      (let [marker (:rf.size/large-elided (:result tags))]
        (is (= [:derived :blob] (:path marker))
            "marker carries the classified path")
        (is (= :effect (:reason marker))
            "marker carries :reason :effect for commit-plane-classified large paths")))))

(deftest failed-trace-elides-inputs
  (testing ":rf.flow/failed :inputs rides through elide-wire-value"
    ;; Register the flow that will throw; the input path is frame-
    ;; declared large so the walker substitutes the marker on emit.
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:payload {:big "value"}}}))
    (rf/reg-flow {:id     :boom
                  :inputs [[:payload]]
                  :derive (fn [_] (throw (ex-info "boom" {})))
                  :output-path   [:doomed]})
    (install-large! :rf/default [:payload])
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
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 5}}))
    (rf/reg-event :touch (fn [{:keys [db]} _] {:db (assoc db :touched true)}))
    (rf/reg-flow {:id     :A
                  :inputs [[:n]]
                  :derive (fn [n] (* 2 n))
                  :output-path   [:a-out]})
    (rf/reg-flow {:id     :B
                  :inputs [[:a-out]]
                  :derive (fn [_] (throw (ex-info "boom" {:why :test})))
                  :output-path   [:b-out]})
    (rf/reg-flow {:id     :C
                  :inputs [[:b-out]]
                  :derive (fn [b] (str "C-saw-" b))
                  :output-path   [:c-out]})
    ;; First drain seeds :n. :A computes [:a-out]; :B throws → that whole
    ;; drain aborts, so even :n does not land. Re-seed cleanly is not
    ;; possible while :B throws, so capture app-db right after the
    ;; throwing drain and assert nothing landed.
    (reset! *captured* [])
    (rf/dispatch-sync [:seed])
    (let [db (rf/app-db-value :rf/default)]
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
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 5}}))
    (let [a-calls (atom 0)
          b-calls (atom 0)]
      (rf/reg-flow {:id     :A
                    :inputs [[:n]]
                    :derive (fn [n] (swap! a-calls inc) (* 2 n))
                    :output-path   [:a-out]})
      (rf/reg-flow {:id     :B
                    :inputs [[:a-out]]
                    :derive (fn [_]
                              (swap! b-calls inc)
                              (throw (ex-info "boom" {})))
                    :output-path   [:b-out]})
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


;; ---------------------------------------------------------------------------
;; EP-0025: flow input->output sensitivity PROPAGATION is removed (the
;; drain-time `refresh-flow-output-declarations!` + its rolled-back refresh, and
;; the `:rf.egress/output-sensitivity` claim). A flow's EXPLICIT output
;; classification (`:sensitive` / `:large` / `:large?`) installs at reg-flow
;; time and is covered above; there is no drain-time propagation to roll back.
;; The deleted tests asserted the removed propagation surface.
;; ---------------------------------------------------------------------------

(deftest failed-flow-app-db-unchanged-across-multiple-failing-drains
  (testing "across multiple failing drains, NOTHING lands — app-db stays unchanged (no partial commit)"
    (rf/reg-event :n!   (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
    (rf/reg-flow {:id     :A
                  :inputs [[:n]]
                  :derive (fn [n] (* 10 n))
                  :output-path   [:a-out]})
    (rf/reg-flow {:id     :B
                  :inputs [[:a-out]]
                  :derive (fn [_] (throw (ex-info "boom" {})))
                  :output-path   [:b-out]})
    (rf/dispatch-sync [:n! 5])
    (let [db (rf/app-db-value :rf/default)]
      (is (not (contains? db :n))
          ":n absent — the handler's write was discarded on the flow throw")
      (is (not (contains? db :a-out))
          ":a-out absent — prior-flow writes are NOT committed (no partial commit)")
      (is (not (contains? db :b-out))
          ":b-out absent — the failing flow's slot is never written"))

    (rf/dispatch-sync [:n! 7])
    (let [db (rf/app-db-value :rf/default)]
      (is (not (contains? db :a-out))
          ":a-out still absent after a second failing drain — every drain aborts wholesale")
      (is (not (contains? db :b-out))
          ":b-out still absent across drains"))

    (rf/dispatch-sync [:n! 11])
    (let [db (rf/app-db-value :rf/default)]
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
;; The flow transform is the outermost `:after`; on a throw it
;; `dissoc`-es the pending `:db` effect and stashes `:rf/flow-error` on the
;; context, so the install is a no-op and `commit-and-flow!` skips
;; `run-fx-effects!`.
;; ---------------------------------------------------------------------------

(deftest fx-does-not-run-after-flow-throws
  (testing "when a flow's :derive throws, the handler's :fx is skipped AND its :db does NOT land"
    (let [child-fired? (atom false)]
      (rf/reg-event :after-throw
                       (fn [{:keys [db]} _] (reset! child-fired? true) {:db db}))
      (rf/reg-event :run-with-throwing-flow
                       (fn [_ _]
                         {:db {:n 2}
                          :fx [[:dispatch [:after-throw]]]}))
      ;; Register a flow that throws. The flow runs as the outermost
      ;; :after — after the handler, BEFORE :db install and BEFORE :fx
      ;; walks; a throw aborts the event, so :after-throw must NOT dispatch
      ;; and the handler's :db must NOT install.
      (rf/reg-flow {:id     :boom
                    :inputs [[:n]]
                    :derive (fn [_] (throw (ex-info "boom" {:why :test})))
                    :output-path   [:doomed]})
      (rf/dispatch-sync [:run-with-throwing-flow])
      (is (false? @child-fired?)
          "`:after-throw` did NOT dispatch — :fx was skipped because the flow threw")
      ;; Atomicity contract: the handler's :db write was DISCARDED — the
      ;; event aborted with no install (app-db unchanged).
      (is (not (contains? (rf/app-db-value :rf/default) :n))
          ":n absent — the handler's :db did NOT land; a flow throw aborts the event with no install"))))

(deftest fx-after-successful-flow-still-runs
  (testing "Per rf2-fslx0: when flows succeed, :fx still walks normally (negative control)"
    (let [child-fired? (atom false)]
      (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 1}}))
      (rf/reg-event :after-ok
                       (fn [{:keys [db]} _] (reset! child-fired? true) {:db db}))
      (rf/reg-event :run-with-ok-flow
                       (fn [_ _]
                         {:db {:n 2}
                          :fx [[:dispatch [:after-ok]]]}))
      (rf/reg-flow {:id     :double
                    :inputs [[:n]]
                    :derive (fn [n] (* 2 n))
                    :output-path   [:doubled]})
      (rf/dispatch-sync [:init])
      (reset! child-fired? false)
      (rf/dispatch-sync [:run-with-ok-flow])
      (is (true? @child-fired?)
          "`:after-ok` DID dispatch — :fx walked because the flow succeeded")
      (is (= 4 (:doubled (rf/app-db-value :rf/default)))
          "flow output landed in app-db (sanity)"))))

(deftest fx-skip-on-flow-throw-still-emits-error-substrate
  (testing "Per rf2-fslx0 + rf2-hrt5c: when :fx is skipped on flow throw, the error substrate still fires"
    ;; Pin that the cascade-halt does NOT short-circuit the error fan-
    ;; out — ops monitors still see the failure record even though :fx
    ;; was skipped.
    (let [seen (atom [])]
      (rf/register-listener! :errors
        :test/fx-skip-recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event :run-with-throwing-flow
                       (fn [_ _]
                         {:db {:n 2}
                          :fx [[:dispatch [:must-not-fire]]]}))
      (rf/reg-event :must-not-fire (fn [{:keys [db]} _] {:db db}))
      (rf/reg-flow {:id     :boom
                    :inputs [[:n]]
                    :derive (fn [_] (throw (ex-info "boom" {})))
                    :output-path   [:doomed]})
      (rf/dispatch-sync [:run-with-throwing-flow])
      (is (>= (count @seen) 1)
          "error-emit substrate fired — the cascade-halt does not silence the error fan-out")
      (is (some #(= :rf.error/flow-eval-exception (:error %)) @seen)
          "at least one substrate record carries :error :rf.error/flow-eval-exception"))))

;; ---------------------------------------------------------------------------
;; 7e. An fx throw does NOT wind back app-db — the POST-commit boundary
;;     (atomicity contract).
;;
;; `:fx` is the ONLY post-install stage. By the time it walks, the
;; deferred (flow-augmented) `:db` has ALREADY committed: an fx throw
;; surfaces an error but MUST NOT roll back app-db — the fx side effects
;; (HTTP, navigation, dispatch) may already have fired and are
;; irreversible, so unwinding the db would desync state from the world.
;; This is the mirror of `fx-does-not-run-after-flow-throws` (a PRE-commit
;; throw aborts wholesale): a POST-commit throw leaves everything committed.
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
      (rf/reg-event :commit-then-fx-throw
                       (fn [_ _]
                         {:db {:n 5}
                          :fx [[:test/ok-fx true]
                               [:test/boom-fx true]]}))
      (rf/reg-flow {:id     :double
                    :inputs [[:n]]
                    :derive (fn [n] (* 2 n))
                    :output-path   [:doubled]})
      ;; An fx throw surfaces an error event but does not propagate out of
      ;; the dispatch (each fx is isolated per Spec 002 §Cascade
      ;; propagation); the drain continues. dispatch-sync returns normally.
      (rf/dispatch-sync [:commit-then-fx-throw])
      (let [db (rf/app-db-value :rf/default)]
        (is (= 5 (:n db))
            ":n stayed committed — the handler's :db was installed before the
             post-commit :fx walk; the fx throw does NOT wind it back")
        (is (= 10 (:doubled db))
            ":doubled stayed committed — the flow output rode the same
             deferred install and survives the post-commit fx throw")))))

;; ---------------------------------------------------------------------------
;; 7f. No spurious `:rf.event/db-changed` on a no-write event — the
;;     deferred-install contract.
;;
;; A `reg-event` handler that returns NO `:db` (only `:fx`), whose
;; flows' inputs are all unchanged (so every flow SKIPS), produces no
;; `:db` effect at all → the deferred install is a no-op → ZERO
;; `:rf.event/db-changed` must be emitted. A spurious db-changed on a
;; no-op drain would mislead off-box monitors and trigger needless sub
;; recompute.
;; ---------------------------------------------------------------------------

(deftest no-db-changed-on-no-write-event-with-stable-flows
  (testing "a reg-event returning only :fx [] (no :db), with flows whose
            inputs are unchanged, emits ZERO :rf.event/db-changed"
    (rf/reg-fx :test/noop (fn [& _] nil))
    ;; Seed :n so the flow computes once on the seed drain.
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 7}}))
    ;; This handler writes NO :db — only an :fx that is a noop.
    (rf/reg-event :no-write
                     (fn [_ _] {:fx [[:test/noop true]]}))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :derive (fn [n] (* 2 n))
                  :output-path   [:doubled]})
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
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:w 3 :h 4}}))
    (rf/reg-flow {:id     :area
                  :inputs [[:w] [:h]]
                  :derive (fn [w h] (* w h))
                  :output-path   [:rect :area]})
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
    ;; therefore frame-large. The walker must substitute the marker.
    (rf/reg-event :init (fn [{:keys [db]} _] {:db (merge db {:n 1 :derived {:blob {:bytes "PRESEEDED"}}})}))
    (rf/reg-event :bump (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/reg-flow {:id     :payload
                  :inputs [[:n]]
                  :derive (fn [n] {:bytes (str "blob-" n)})
                  :output-path   [:derived :blob]})
    (install-large! :rf/default [:derived :blob])
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (rf/dispatch-sync [:bump])
    (let [computes (by-op :rf.flow/computed)
          last-ev  (last computes)
          tags     (:tags last-ev)]
      (is (some? last-ev) ":rf.flow/computed fired on the recompute")
      (is (elision/marker? (:before tags))
          ":before is replaced by the `:rf.size/large-elided` marker — the slot is frame-large")
      (is (elision/marker? (:result tags))
          ":result is similarly elided (sanity)")
      (let [marker (:rf.size/large-elided (:before tags))]
        (is (= [:derived :blob] (:path marker))
            "before-marker carries the classified path")
        (is (= :effect (:reason marker))
            "before-marker carries :reason :effect")))))

;; ---------------------------------------------------------------------------
;; 8. `:sensitive?` inheritance on `:rf.flow/*` traces (Spec 013 §`:sensitive?`
;;    inheritance, 013-Flows.md:242).
;;
;; Spec 013 is normative: the runtime stamps `:sensitive? true` at the top
;; level of every `:rf.flow/*` trace event when the in-scope handler's
;; cascade is sensitive — "the flow itself does not declare `:sensitive?`
;; directly; the marker rides the cascade." Sensitivity is frame-
;; classification-derived (EP-0015 §8): a handler scoped (`rf/path`) over a
;; frame-owned `:sensitive {:app-db …}` slot makes the router bind
;; `:rf/sensitive? true` into the handler scope. Flows run inside that scope
;; (`commit-and-flow!` sits inside `run-handler-cascade!`'s
;; `with-handler-scope`), so `trace/build-event`'s `compute-sensitive?`
;; hoists the stamp onto the flow trace automatically — the same handler-
;; scope inheritance every other in-cascade trace uses.
;;
;; These tests pin the contract end-to-end so a future reorder of the drain
;; (e.g. moving the flow walk outside the handler scope) cannot silently
;; strip the privacy marker — which would leak auth-handler-triggered flow
;; recompute traces past the default-drop forwarders (Sentry / Xray-MCP).
;; ---------------------------------------------------------------------------

(deftest flow-computed-trace-inherits-sensitive-from-schema-scope
  (testing "Spec 013:242 — `:rf.flow/computed` is stamped `:sensitive? true`
            when the triggering handler's cascade is frame-sensitive"
    ;; Sensitive frame app-db slot at [:auth :token]; a path-scoped handler
    ;; writes it (drives the router's frame-classification overlap → scope
    ;; `:rf/sensitive? true`). The flow reads [:auth :token] and writes
    ;; [:auth :derived-user] — it recomputes inside the sensitive scope.
    (install-sensitive! :rf/default [:auth :token])
    (rf/reg-flow {:id     :auth/derived-user
                  :inputs [[:auth :token]]
                  :derive (fn [t] (str "user-of-" t))
                  :output-path   [:auth :derived-user]})
    (rf/reg-event :auth/signed-in
                     {:interceptors [[:rf.interceptor/path [:auth]]]}
                     (fn [{:keys [db]} [_ token]] {:db (assoc db :token token)}))
    (reset! *captured* [])
    (rf/dispatch-sync [:auth/signed-in "secret-token"])
    (let [computes (by-op :rf.flow/computed)]
      (is (= 1 (count computes))
          "the flow recomputed once on the sensitive handler's drain")
      (is (true? (:sensitive? (first computes)))
          ":rf.flow/computed carries a top-level `:sensitive? true` stamp —
           inherited from the frame-sensitive handler scope (Spec 013:242)"))))

(deftest flow-failed-trace-inherits-sensitive-from-schema-scope
  (testing "Spec 013:242 — `:rf.flow/failed` is also stamped `:sensitive?`
            when the triggering handler's cascade is frame-sensitive"
    (install-sensitive! :rf/default [:auth :token])
    (rf/reg-flow {:id     :auth/derived-user
                  :inputs [[:auth :token]]
                  :derive (fn [_] (throw (ex-info "derive boom" {})))
                  :output-path   [:auth :derived-user]})
    (rf/reg-event :auth/signed-in
                     {:interceptors [[:rf.interceptor/path [:auth]]]}
                     (fn [{:keys [db]} [_ token]] {:db (assoc db :token token)}))
    (reset! *captured* [])
    (rf/dispatch-sync [:auth/signed-in "secret-token"])
    (let [failures (by-op :rf.flow/failed)]
      (is (= 1 (count failures))
          "the flow threw once on the sensitive handler's drain")
      (is (true? (:sensitive? (first failures)))
          ":rf.flow/failed carries the top-level `:sensitive? true` stamp too"))))

;; ---------------------------------------------------------------------------
;; `:rf.flow/failed` must NOT deliver a raw Throwable, and a sensitive flow's
;; exception ex-data must be redacted before the trace crosses the bus / epoch
;; capture / tooling listeners. The throwing flow's ex-data carries a secret;
;; assert it is redacted while flow-id / frame attribution is preserved.
;; ---------------------------------------------------------------------------

(deftest flow-failed-emits-structured-summary-not-raw-throwable
  (testing "rf2-iqh5yf — `:rf.flow/failed` carries a structured EDN-safe
            exception summary (`:exception-message` + `:exception-data`),
            NEVER a raw Throwable under `:ex`"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 1}}))
    (rf/reg-flow {:id     :boom
                  :inputs [[:n]]
                  :derive (fn [_] (throw (ex-info "kaboom" {:code 42})))
                  :output-path   [:doomed]})
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (let [tags (:tags (last (by-op :rf.flow/failed)))]
      (is (not (contains? tags :ex))
          "no raw `:ex` Throwable slot rides the trace bus")
      (is (= "kaboom" (:exception-message tags))
          ":exception-message is the plain message string")
      (is (= {:code 42} (:exception-data tags))
          ":exception-data is the EDN-safe ex-data map")
      ;; The whole tags payload must be EDN-round-trippable — a raw Throwable
      ;; is not (it cannot be `pr-str`/`read-string`-round-tripped), so this
      ;; is the structural guard the bead asks for (no raw object reaches
      ;; epoch capture / off-box tooling).
      (is (= tags (read-string (pr-str tags)))
          "the :rf.flow/failed tags are EDN round-trippable (no raw object)"))))

(deftest flow-failed-redacts-ex-data-when-frame-sensitive
  (testing "rf2-iqh5yf — when the flow's frame declares ANY sensitive app-db
            classification, a throwing flow's ex-data (which may embed the
            secret it read / interpolated) is REDACTED to the `:rf/redacted`
            sentinel before listeners / epoch capture / tooling observe it;
            flow-id / frame attribution is preserved"
    (install-sensitive! :rf/default [:auth :token])
    (rf/reg-event :auth/signed-in
                     {:interceptors [[:rf.interceptor/path [:auth]]]}
                     (fn [{:keys [db]} [_ token]] {:db (assoc db :token token)}))
    (rf/reg-flow {:id     :auth/derived-user
                  :inputs [[:auth :token]]
                  ;; The exception ex-data smuggles the sensitive value the
                  ;; flow just read — the exact leak class the bead names.
                  :derive (fn [tok] (throw (ex-info "derive failed"
                                                    {:leaked-token tok})))
                  :output-path   [:auth :derived-user]})
    (reset! *captured* [])
    (rf/dispatch-sync [:auth/signed-in "TOP-SECRET"])
    (let [failures (by-op :rf.flow/failed)
          tags     (:tags (last failures))]
      (is (= 1 (count failures)) ":rf.flow/failed fired once")
      ;; Attribution preserved.
      (is (= :auth/derived-user (:flow-id tags)) ":flow-id attribution preserved")
      (is (= :rf/default (:frame tags))          ":frame attribution preserved")
      (is (true? (:sensitive? (last failures)))
          "the event is stamped sensitive (off-box shippers drop it)")
      ;; The ex-data is redacted wholesale — the sensitive frame handles
      ;; secrets, so the framework cannot prove the author-keyed ex-data map
      ;; is secret-free (mirrors project-machine-error-tags).
      (is (= privacy/redacted-sentinel (:exception-data tags))
          ":exception-data is redacted to the sentinel for a sensitive frame")
      ;; The raw token must NOT appear anywhere in the delivered tags.
      (is (not (contains? (set (tree-seq coll? seq tags)) "TOP-SECRET"))
          "the raw secret token does not appear anywhere in the failed-trace tags"))))

(deftest flow-failed-ex-data-rides-verbatim-when-frame-not-sensitive
  (testing "rf2-iqh5yf — a NON-sensitive frame's flow-failed ex-data rides
            verbatim (the projection is precise, not a blanket scrub —
            symmetric with every other per-registration projection)"
    (rf/reg-event :init (fn [{:keys [db]} _] {:db {:n 1}}))
    (rf/reg-flow {:id     :boom
                  :inputs [[:n]]
                  :derive (fn [_] (throw (ex-info "boom" {:detail :useful})))
                  :output-path   [:doomed]})
    (reset! *captured* [])
    (rf/dispatch-sync [:init])
    (let [tags (:tags (last (by-op :rf.flow/failed)))]
      (is (= {:detail :useful} (:exception-data tags))
          "ex-data is preserved verbatim when no frame sensitivity is declared")
      (is (not (true? (:sensitive? (last (by-op :rf.flow/failed)))))
          "no sensitive stamp on a non-sensitive frame"))))

(deftest flow-skip-trace-inherits-sensitive-from-schema-scope
  (testing "Spec 013:242 — `:rf.flow/skip` is stamped `:sensitive?` when the
            triggering handler's cascade is frame-sensitive"
    (install-sensitive! :rf/default [:auth :token])
    (rf/reg-flow {:id     :auth/derived-user
                  :inputs [[:auth :token]]
                  :derive (fn [t] (str "user-of-" t))
                  :output-path   [:auth :derived-user]})
    (rf/reg-event :auth/signed-in
                     {:interceptors [[:rf.interceptor/path [:auth]]]}
                     (fn [{:keys [db]} [_ token]] {:db (assoc db :token token)}))
    ;; First sign-in computes; second sign-in with the SAME token leaves
    ;; the input value-equal → `:rf.flow/skip` fires, still inside the
    ;; frame-sensitive handler scope.
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
    ;; No sensitive frame app-db slot is under the handler's focus, so the
    ;; router computes no overlap and the scope is not sensitive. The
    ;; flow trace must NOT acquire a stamp.
    (install-sensitive! :rf/default [:auth :token])
    ;; This handler is path-scoped to :profile (disjoint from the sensitive
    ;; :auth slot) and the flow reads :profile — non-sensitive cascade.
    (rf/reg-flow {:id     :profile/derived
                  :inputs [[:profile :name]]
                  :derive (fn [n] (str "hello-" n))
                  :output-path   [:profile :greeting]})
    (rf/reg-event :profile/rename
                     {:interceptors [[:rf.interceptor/path [:profile]]]}
                     (fn [{:keys [db]} [_ name]] {:db (assoc db :name name)}))
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
    ;; The flow's :output-path is the sensitive slot, so `:result` (and the
    ;; sensitive input) ride through the elision walker → `:rf/redacted`,
    ;; while the cascade is sensitive → top-level stamp.
    (install-sensitive! :rf/default [:auth :token] [:auth :derived-token])
    (rf/reg-flow {:id     :auth/derived-token
                  :inputs [[:auth :token]]
                  :derive (fn [t] (str "derived-" t))
                  :output-path   [:auth :derived-token]})
    (rf/reg-event :auth/signed-in
                     {:interceptors [[:rf.interceptor/path [:auth]]]}
                     (fn [{:keys [db]} [_ token]] {:db (assoc db :token token)}))
    (reset! *captured* [])
    (rf/dispatch-sync [:auth/signed-in "secret-token"])
    (let [ev   (first (by-op :rf.flow/computed))
          tags (:tags ev)]
      (is (some? ev) ":rf.flow/computed fired")
      (is (true? (:sensitive? ev))
          "top-level `:sensitive?` stamp present (cascade is sensitive)")
      (is (= :rf/redacted (:result tags))
          ":result value redacted on the wire — its :output-path is a sensitive slot")
      (is (= :rf/redacted (first (:input-values tags)))
          "the sensitive input value is redacted on the wire too"))))

;; ---------------------------------------------------------------------------
;; 8b. Strict trace-stream ordering on a flow throw — atomicity contract
;;     (Spec 013 §Failure semantics / §Trace stream ordering on a flow throw).
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
                  (rf/reg-event :bump (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
                  (rf/reg-flow {:id     :ok
                                :inputs [[:n]]
                                :derive (fn [n] (* 10 n))
                                :output-path   [:a-out]})
                  (rf/reg-flow {:id     :boom
                                :inputs [[:a-out]]
                                :derive (fn [_] (throw (ex-info "boom" {})))
                                :output-path   [:doomed]})
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
      (let [db (rf/app-db-value :rf/default)]
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
;;     Spec 009 §Canonical per-event trace sequence diagram against the impl.
;;
;; The load-bearing fact the 009 diagram encodes: `:rf.event/run-end` is a
;; CASCADE-TAIL trace — the router emits it in `emit-cascade-trailers!`
;; AFTER `commit-and-flow!` has installed the deferred (flow-augmented)
;; `:db` and walked `:fx`. So a clean cascade orders:
;;
;;   :rf.flow/computed → :rf.event/db-changed → :rf.fx/handled
;;     → :rf.fx/do-fx (terminating fx-walk marker) → :rf.event/run-end (LAST)
;;
;; This test conformance-checks that ordering so the diagram can't silently
;; drift.
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
                  (rf/reg-event :go
                                   (fn [_ _]
                                     {:db {:n 3}
                                      :fx [[:test/noop true]]}))
                  (rf/reg-flow {:id     :double
                                :inputs [[:n]]
                                :derive (fn [n] (* 2 n))
                                :output-path   [:doubled]})
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
