(ns re-frame.flows-conformance-test
  "Drives every `spec/conformance/fixtures/flow-*.edn` fixture through the
  live flows runtime and compares each `:fixture/expect` channel with what
  the runtime produced. This is the flows artefact's own conformance gate, so
  a flow regression fails here rather than in core's corpus runner.

  The channels (spec/conformance/README.md §Fixture lifecycle) are
  `:final-app-db` and the per-frame `:final-app-dbs` (submap), `:sub-values`,
  `:expect-trace-stream` (`:flow` op-type only) and `:trace-emissions`
  (order-preserving subset), `:trace-absent`, `:flow-recompute-counts`,
  `:flow-graph-topology`, `:flow-registry-after`, `:flow-last-inputs-after`,
  `:registrar-flow-slots-after` and `:error-emit-records`.

  Every fixture this runner sees is a flow fixture it is the reference gate
  for, so an unclaimed capability or spec version FAILS rather than skips: a
  skip would leave a new fixture's contract unchecked while the gate stayed
  green. The fix is to extend the claim and implement the matcher."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.conformance :as rf.conformance]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.flows :as rf.flows]
            [re-frame.flows.topo :as rf.flows.topo]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; `:ambient-frame nil`: `run-fixture` calls `make-frame` with no ambient
;; scope, so a frame's `:initial-events` cascade runs synchronously rather
;; than queueing (EP-0002). The standard reset leaves the always-on error
;; listeners alone, so they are cleared here.
(def ^:private reset-runtime-fixture
  (let [standard (rf.test-support/make-reset-runtime-fixture
                   {:adapter       rf.substrate.plain-atom/adapter
                    :ambient-frame nil})]
    (fn [test-fn]
      (standard (fn []
                  (rf.error-emit/clear-error-listeners!)
                  (test-fn))))))

(use-fixtures :each reset-runtime-fixture)

;; ---- fixture discovery ----------------------------------------------------

(def fixtures-dir
  "The corpus directory, anchored to this namespace's own source on the
  classpath rather than to the working directory: a cwd-relative path
  resolves above the repo root when the combined `implementation/deps.edn`
  alias runs from `implementation/`, and discovers nothing."
  (let [res (io/resource "re_frame/flows_conformance_test.clj")]
    (assert res "the flows test/ dir must be on the classpath for fixture discovery")
    (-> (io/file res)        ; .../flows/test/re_frame/flows_conformance_test.clj
        .getParentFile       ; .../flows/test/re_frame
        .getParentFile       ; .../flows/test
        .getParentFile       ; .../flows
        .getParentFile       ; .../implementation
        .getParentFile       ; repo root
        (io/file "spec" "conformance" "fixtures")
        .getCanonicalFile)))

(defn- read-one-form
  "Read `text` as exactly one top-level EDN form, or throw: `read-string`
  silently discards everything after the first form, so a fixture whose
  expectation block closes early would pass having verified less."
  [text fixture-name]
  (let [eof  (Object.)
        rdr  (java.io.PushbackReader. (java.io.StringReader. text))
        fail (fn [why data]
               (throw (ex-info (str "conformance fixture " fixture-name " " why ".")
                               (assoc data :fixture/file fixture-name))))
        rd   (fn []
               (try (edn/read {:eof eof} rdr)
                    (catch Exception e
                      (fail (str "is not readable EDN: " (.getMessage e))
                            {:fixture/reader-error (.getMessage e)}))))
        form (rd)]
    (when (identical? eof form)
      (fail "holds no top-level EDN form" {:fixture/forms 0}))
    (when-not (identical? eof (rd))
      (fail (str "must hold exactly ONE top-level EDN form — a plain read"
                 " returns the first and silently discards the rest")
            {:fixture/forms :more-than-one}))
    form))

(defn- load-fixture
  "Read one fixture, rewriting `::name` keywords as core's runner does, since
  `clojure.edn` has no resolver for them."
  [file]
  (read-one-form (str/replace (slurp file) #"::([a-zA-Z][a-zA-Z0-9_-]*)" ":rf.machine.timer/$1")
                 (.getName file)))

(defn- all-flow-fixtures
  "`[[filename fixture] ...]` for every `flow-*.edn`, in name order."
  []
  (->> (file-seq fixtures-dir)
       (filter #(.isFile %))
       (filter #(let [n (.getName %)]
                  (and (str/starts-with? n "flow-") (str/ends-with? n ".edn"))))
       (sort-by #(.getName %))
       (mapv (fn [f] [(.getName f) (load-fixture f)]))))

;; ---- claim ----------------------------------------------------------------

(def claimed-capabilities
  "The flow surface, plus the `:core/*` capabilities flow fixtures cross-cut:
  `:core/sub` for the fixtures reading materialised outputs through subs,
  `:core/error` for the flow-eval-exception fixture's error channels."
  #{:core/event-handler
    :core/sub
    :core/fx
    :core/trace
    :core/error
    :flow/basic
    :flow/topo
    :flow/dirty-check
    :flow/toggle
    :flow/hot-reload
    :flow/trace
    :flow/frame-scoped})

(def claimed-spec-versions #{"1.0"})

(defn- refusal
  "Why this runner refuses `fixture`, or nil when it claims it."
  [fixture]
  (let [v    (:fixture/spec-version fixture)
        caps (:fixture/capabilities fixture)]
    (cond
      (and (some? v) (not (contains? claimed-spec-versions v))) {:unclaimed-spec-version v}
      (not-every? claimed-capabilities caps)                    {:unclaimed-capabilities caps})))

;; ---- handler realisation --------------------------------------------------

(defn- adapter-helpers
  "The frame access the fx-body DSL needs, as core's runner provides it."
  []
  {:read-db!  (fn [frame-id] (rf.frame/frame-app-db-value frame-id))
   :write-db! (fn [frame-id new-db] (rf.frame/swap-frame-db! frame-id (constantly new-db)))
   :dispatch! (fn [event frame-id] (rf/dispatch event {:frame frame-id}))})

(defn- realise-event-sub-fx-handlers
  "Register the fixture's event, sub and fx handlers through the
  `re-frame.conformance` DSL interpreter. Flows are registered separately,
  after the frame exists, because destroying a frame tears its flows down."
  [fixture]
  (let [hmap        (or (:fixture/handlers fixture) {})
        event-meta  (get-in fixture [:fixture/registry :event] {})
        sub-meta    (get-in fixture [:fixture/registry :sub] {})
        fx-bodies   (:fx hmap)
        fx-registry (get-in fixture [:fixture/registry :fx] {})
        helpers     (adapter-helpers)
        reg-event   (fn [id meta h] (if (seq meta) (rf/reg-event id meta h) (rf/reg-event id h)))]
    (doseq [[id steps] (:event hmap)]
      (let [[kind handler] (rf.conformance/realise-event-handler steps)
            meta           (get event-meta id {})]
        (case kind
          ;; A `:db` handler is `(fn [db event] new-db)`; lower it to the
          ;; single cofx-in, effects-out form.
          :db (reg-event id meta (fn [{:keys [db]} ev] {:db (handler db ev)}))
          :fx (reg-event id meta handler))))
    (doseq [[id steps] (:sub hmap)]
      (let [{:keys [kind inputs body]} (rf.conformance/realise-sub steps)
            meta                       (get sub-meta id {})]
        (case kind
          :layer-1 (if (seq meta) (rf/reg-sub id meta body) (rf/reg-sub id body))
          ;; The fn-form `subs/reg-sub` takes the inputs as data; the public
          ;; `rf/reg-sub` is a JVM macro.
          :layer-2 (rf.subs/reg-sub id (assoc meta :inputs (vec inputs)) body))))
    ;; `:rf.fx/reg-flow` and `:rf.fx/clear-flow` come from `re-frame.flows`;
    ;; fixtures name them without a body.
    (doseq [id (remove #{:rf.fx/reg-flow :rf.fx/clear-flow}
                       (into #{} (concat (keys fx-bodies) (keys fx-registry))))]
      (let [handler (rf.conformance/realise-fx-handler id (get fx-bodies id [[:noop]]) helpers)]
        (rf/reg-fx id (assoc (get fx-registry id {}) :handler-fn handler) handler)))))

(defn- realise-flows!
  "Register each static flow in `:fixture/registry :flow` whose body is in
  `:fixture/flow-bodies`. Flows registered through `:rf.fx/reg-flow` reach
  the runtime through the DSL interpreter instead."
  [fixture]
  (let [flow-bodies (or (:fixture/flow-bodies fixture) {})]
    (doseq [[flow-id flow-meta] (get-in fixture [:fixture/registry :flow] {})]
      (when-let [body (get flow-bodies flow-id)]
        (rf/reg-flow flow-id flow-meta (rf.conformance/realise-flow-output-fn body))))))

;; ---- matchers -------------------------------------------------------------

(defn- submap?
  "True if every key of `expected` is in `actual` with a matching value,
  recursing into nested maps."
  [expected actual]
  (if (and (map? expected) (map? actual))
    (every? (fn [[k v]]
              (let [a (get actual k)]
                (if (and (map? v) (map? a)) (submap? v a) (= v a))))
            expected)
    (= expected actual)))

(defn- trace-matches?
  "Partial match: every key of `exp` is in `act`, one level of nested maps
  matching key by key."
  [exp act]
  (every? (fn [[k v]]
            (let [a (get act k)]
              (if (and (map? v) (map? a))
                (every? (fn [[kk vv]] (= vv (get a kk))) v)
                (= v a))))
          exp))

(defn- check-trace-stream
  "Failures of an order-preserving subset match: each expected trace must
  appear in `actual` after the previous one matched. Extras are tolerated."
  [actual expected]
  (loop [actual actual expected expected failures []]
    (if (empty? expected)
      failures
      (let [exp (first expected)
            i   (some (fn [[i a]] (when (trace-matches? exp a) i)) (map-indexed vector actual))]
        (if i
          (recur (drop (inc i) actual) (rest expected) failures)
          (recur actual (rest expected) (conj failures (str "expected trace not seen: " (pr-str exp)))))))))

(defn- check-trace-absent
  "Failures of an absence match: no pattern in `forbidden` may match any
  trace in `actual`."
  [actual forbidden]
  (vec (for [pat forbidden :when (some #(trace-matches? pat %) actual)]
         (str "forbidden trace WAS seen: " (pr-str pat)))))

(defn- recompute-counts
  "`{flow-id n}` of `:rf.flow/computed` events. A `:rf.flow/skip` is the
  absence of a recompute, so it does not count."
  [traces]
  (frequencies (for [ev traces :when (= :rf.flow/computed (:operation ev))]
                 (get-in ev [:tags :flow-id]))))

(defn- flow-graph-deps
  "`{flow-id #{dep-id ...}}` over `:rf/default`'s flows, by the runtime's own
  `topo/depends-on?` so the matcher cannot drift from the dependency rule."
  []
  (let [registry (get (rf.flows/flows-snapshot) :rf/default {})]
    (into {} (for [[id flow] registry]
               [id (into #{} (for [[other-id other] registry
                                   :when (and (not= id other-id) (rf.flows.topo/depends-on? flow other))]
                               other-id))]))))

(defn- flow-registry-ids [frame-id]
  (set (keys (get (rf.flows/flows-snapshot) frame-id))))

(defn- channels
  "Each `:fixture/expect` channel as `[observe ok?]`: `observe` reads the
  runtime given the expectation, and `ok?` compares the two.
  `:registrar-flow-slots-after` reads the whole `:flow` registrar slot, which
  `reg-flow` never writes, rather than the per-frame store or the expected
  ids, either of which would let an empty expectation match a polluted slot."
  [traces err-records]
  (let [no-failures (fn [_ failures] (empty? failures))]
    {:final-app-db               [(fn [_] (rf/app-db-value :rf/default)) submap?]
     :final-app-dbs              [#(into {} (for [[f _] %] [f (rf/app-db-value f)]))
                                  (fn [e a] (every? (fn [[f db]] (submap? db (get a f))) e))]
     :sub-values                 [#(into {} (for [[q _] %] [q (rf/subscribe-once q {:frame :rf/default})])) =]
     :expect-trace-stream        [#(check-trace-stream (filterv (comp #{:flow} :op-type) @traces) %) no-failures]
     :trace-emissions            [#(check-trace-stream @traces %) no-failures]
     :trace-absent               [#(check-trace-absent @traces %) no-failures]
     :flow-recompute-counts      [(fn [_] (recompute-counts @traces)) =]
     :flow-graph-topology        [#(select-keys (flow-graph-deps) (keys %)) =]
     ;; A bare set reads `:rf/default`; a map reads each named frame.
     :flow-registry-after        [#(if (map? %)
                                     (into {} (for [[f _] %] [f (flow-registry-ids f)]))
                                     (flow-registry-ids :rf/default))
                                  =]
     :flow-last-inputs-after     [#(into {} (for [[id _] %]
                                              [id (set (keys (get (rf.flows/last-inputs-snapshot) id)))]))
                                  =]
     :registrar-flow-slots-after [(fn [_] (rf.registrar/ids :flow)) =]
     :error-emit-records         [#(check-trace-stream @err-records %) no-failures]}))

;; ---- running a fixture ----------------------------------------------------

(defn- run-fixture
  "Run one fixture: `{:mismatches {channel {:expected … :actual …}}}`, or
  `{:error …}` when it threw."
  [fixture]
  (try
    (let [fid         (:fixture/id fixture)
          traces      (atom [])
          err-records (atom [])]
      (rf.trace.tooling/register-listener! [fid] #(swap! traces conj %))
      (rf.error-emit/register-error-listener! [::flows-conformance fid] #(swap! err-records conj %))
      ;; make-frame on an existing id does not re-fire `:initial-events`, so
      ;; the reset's bare `:rf/default` goes first. Handlers precede
      ;; make-frame, whose `:initial-events` dispatch them; flows follow it.
      (rf/destroy-frame! :rf/default)
      (realise-event-sub-fx-handlers fixture)
      (if-let [frames (seq (:fixture/frames fixture))]
        (run! rf/make-frame frames)
        (rf/make-frame (assoc (:fixture/frame-config fixture) :id :rf/default)))
      (binding [rf.frame/*current-frame* :rf/default]
        (realise-flows! fixture)
        (doseq [step (:fixture/dispatches fixture)]
          (cond
            (not (map? step))               (rf/dispatch-sync step)
            (contains? step :destroy-frame) (rf.frame/destroy-frame! (:destroy-frame step))
            :else                           (rf/dispatch-sync (:event step) (dissoc step :event)))))
      (let [channels (channels traces err-records)
            result   {:mismatches
                      (into {} (for [[k expected] (:fixture/expect fixture)
                                     :let [[observe ok?] (get channels k)]
                                     :when (and observe (some? expected))
                                     :let [actual (observe expected)]
                                     :when (not (ok? expected actual))]
                                 [k {:expected expected :actual actual}]))}]
        (rf.trace.tooling/clear-listeners!)
        (rf.error-emit/clear-error-listeners!)
        result))
    (catch Throwable e
      {:error (ex-message e) :exception e})))

(defn- run-claimed
  "Run `fixture` on a fresh runtime, or report why this runner refuses it."
  [fixture]
  (let [result (atom nil)]
    (reset-runtime-fixture #(reset! result (or (refusal fixture) (run-fixture fixture))))
    @result))

(deftest run-flows-conformance-corpus
  ;; A fixture passes only as `{:mismatches {}}`. The floor catches a
  ;; fixtures-dir fault orphaning the corpus, which would otherwise pass over
  ;; nothing; it is a minimum because the corpus grows.
  (let [results (into (sorted-map)
                      (for [[fname fixture] (all-flow-fixtures)]
                        [fname (dissoc (run-claimed fixture) :exception)]))]
    (is (<= 7 (count results)) (str "flow fixtures executed: " (count results)))
    (is (= {} (into (sorted-map) (remove (comp #{{:mismatches {}}} val) results))))))

(deftest registrar-flow-slot-pollution-fails-the-destroy-fixture
  ;; The control on the registrar-slots channel: a forbidden `:flow` registrar
  ;; row survives the destroy fixture, which must then fail on that channel
  ;; alone.
  (let [fixture (some (fn [[_ fx]] (when (= :flow/frame-destroy-teardown (:fixture/id fx)) fx))
                      (all-flow-fixtures))
        run     (fn [before]
                  (let [result (atom nil)]
                    (reset-runtime-fixture #(do (before) (reset! result (run-fixture fixture))))
                    (:mismatches @result)))]
    (is (= {} (run (fn [])))
        "the unpolluted destroy fixture passes")
    (is (= {:registrar-flow-slots-after {:expected #{} :actual #{:rf.test/forbidden-registrar-row}}}
           (run #(rf.registrar/register! :flow :rf.test/forbidden-registrar-row {:doc "pollution control"}))))))
