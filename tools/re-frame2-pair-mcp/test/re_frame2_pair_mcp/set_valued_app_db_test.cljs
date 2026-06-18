(ns re-frame2-pair-mcp.set-valued-app-db-test
  "Regression: set-valued app-db slots survive the wire walkers (rf2-ogqfe).

  A consumer app whose app-db carries a `#{...}` set value — e.g. a
  state machine stamping `:tags #{:door/locked}` on every snapshot —
  must round-trip cleanly through every wire-shrink walker the
  `snapshot` and `trace-window` tools run. The bead reported a live
  `'me.cljs$core$IMapEntry$_key$arity$1 is not a function'` crash on a
  machine-epochs runtime, hypothesising that a walker iterates every
  collection as map-entries and calls `key`/`val` on a `PersistentHashSet`
  element.

  Investigation found every walker in the path already set-aware
  (`summary/tree-summary` and `source-uri/decorate` carry explicit
  `set?` branches; `de-dupe-eq` routes a set through its generic
  `(coll? form)` arm, not the map-entry arm; the framework's
  server-side `elide-wire-value` / `projected-record` likewise branch
  on `set?`). These tests PIN that set-safety as an enforced invariant
  across the FULL client-side pipeline — both tools, both the summary
  and the diff/dedup epoch paths — so a future refactor that drops a
  `set?` branch from any walker trips this gate instead of shipping a
  runtime crash to a consumer with set-valued state.

  The crashing symptom on the live runtime is attributed to a stale
  compiled build; no code path in the current pair-mcp + mcp-base +
  framework walkers reproduces it.

  End-to-end via a substring-matching `cljs-eval-value` stub (mirrors
  `trace_window_test`'s `with-substr-eval!`): the stub stands in for the
  nREPL runtime response so the test exercises the real client-side wire
  pipeline (`run-wire-pipeline`) that processes the runtime's reply."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.dedup :as dedup]
            [re-frame2-pair-mcp.tools.snapshot :as snapshot]
            [re-frame2-pair-mcp.tools.summary :as summary]
            [re-frame2-pair-mcp.tools.source-uri :as source-uri]
            [re-frame2-pair-mcp.tools.trace-window :as tw]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]
            [re-frame.mcp-base.diff-encode :as diff-encode]))

;; ---------------------------------------------------------------------------
;; Stub harness — `cljs-eval-value` scripted by form-keyword substring
;; (mirrors `trace_window_test`'s `with-substr-eval!`). The probe form
;; (`__re_frame2_pair_runtime`) must resolve true so `ensure-runtime!`
;; proceeds; the real tool form (matched by `real-substr`) resolves to
;; `canned`; everything else (the `configure-raw-state!` boot signal,
;; whose failures are swallowed) falls through to nil.
;; ---------------------------------------------------------------------------

(defn- with-substr-eval!
  [real-substr canned body-fn]
  (let [orig nrepl/cljs-eval-value
        match (fn [form-str]
                (cond
                  (re-find #"__re_frame2_pair_runtime" form-str) true
                  (re-find (re-pattern real-substr) form-str)    canned
                  :else                                          nil))
        stub (fn
               ([_conn _build-id form-str]        (js/Promise.resolve (match form-str)))
               ([_conn _build-id form-str _opts]  (js/Promise.resolve (match form-str))))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

;; ---------------------------------------------------------------------------
;; Fixtures — a machine-epochs-shaped frame-state value with sets at the
;; spots the framework actually stamps them (`:tags` under each machine
;; snapshot, per Spec 005). Machine snapshots are runtime-db state, so the
;; snapshot subtree sits in the `:rf.db/runtime` partition under
;; `:rf.runtime/machines` (per EP-0001); the `:counter` is an ordinary
;; app-db key alongside it. Sets of varying cardinality (1, 2, 3) so the
;; test covers the odd-element and even-element cases.
;; ---------------------------------------------------------------------------

(defn- machine-snapshots [door-tags]
  {:rf.db/runtime
   {:rf.runtime/machines
    {:snapshots
     {:door  {:state :locked :tags door-tags        :context {:attempts 0}}
      :alarm {:state :armed  :tags #{:alarm/armed :alarm/loud}}}}}
   :counter 5})

(def ^:private db-before (machine-snapshots #{:door/locked}))
(def ^:private db-after  (machine-snapshots #{:door/locked :door/bolted}))

;; ---------------------------------------------------------------------------
;; Unit: each wire walker handles a set value without an IMapEntry crash.
;; ---------------------------------------------------------------------------

(deftest tree-summary-handles-a-set
  (testing "summary/tree-summary classifies a set rather than iterating
            it as map-entries"
    (let [marker (:rf.mcp/summary (summary/tree-summary #{:door/locked :armed}))]
      (is (= :set (:type marker)))
      (is (= 2 (:count marker)))
      (is (pos? (:bytes marker))))))

(deftest source-uri-decorate-recurses-sets
  (testing "source-uri/decorate walks INTO a set and preserves it"
    (is (= #{:a :b :c} (source-uri/decorate #{:a :b :c} :vscode)))
    (is (= {:tags #{:door/locked}}
           (source-uri/decorate {:tags #{:door/locked}} :vscode)))))

(deftest dedup-round-trips-repeated-sets
  (testing "de-dupe-eq pools a repeated set and expand restores it exactly"
    (let [s #{:door/locked}
          payload (vec (repeat 5 {:tags s :mirror s}))
          wrapped (dedup/dedup-value payload true)
          restored (tu/dedup-expand wrapped)]
      (is (= payload restored)))))

;; ---------------------------------------------------------------------------
;; Pipeline: a set deep in the epoch diff/dedup path round-trips.
;; ---------------------------------------------------------------------------

(deftest epoch-vector-diff-dedup-preserves-deep-set
  (testing "trace-window's :epoch-vector pipeline (diff-encode -> dedup)
            preserves a set deep in :db-after"
    (let [epochs   [{:epoch-id :e1 :db-before db-before :db-after db-before}
                    {:epoch-id :e2 :db-before db-before :db-after db-after}]
          out      (wp/run-wire-pipeline epochs
                                         {:kind :epoch-vector :incl? true
                                          :mode :diff :dedup? true})
          restored (tu/dedup-expand (:value out))
          decoded  (mapv diff-encode/decode-db-after restored)]
      (is (= #{:door/locked :door/bolted}
             (get-in (:db-after (second decoded))
                     [:rf.db/runtime :rf.runtime/machines :snapshots :door :tags]))
          "the modified :tags set survives diff-encode + dedup + decode"))))

;; ---------------------------------------------------------------------------
;; End-to-end: snapshot-tool with a set-valued app-db, full + summary.
;; ---------------------------------------------------------------------------

(defn- snapshot-response
  "The `{:value <per-frame-snap> :elided-count N :tool-frames-excluded
  []}` envelope the snapshot eval form returns (rf2-e35a5)."
  [snap]
  {:value snap :elided-count 0 :tool-frames-excluded []})

(deftest snapshot-tool-full-mode-set-valued-app-db
  (testing "snapshot full-mode ships a set-valued app-db without crashing"
    (async done
      (let [snap {:rf/default {:app-db    db-after
                               :sub-cache {}
                               :machines  {:door {:tags #{:door/locked}}}
                               :epochs    []
                               :traces    []}}]
        (-> (with-substr-eval! "snapshot-state" (snapshot-response snap)
              (fn []
                (-> (snapshot/snapshot-tool nil (tu/args->js {:mode "full"
                                                              :frames #js [":rf/default"]}))
                    (.then (fn [result]
                             (is (not (tu/error? result))
                                 "no :isError — the set value didn't crash the pipeline")
                             (let [edn (tu/extract-edn result)]
                               (is (true? (:ok? edn)))
                               (is (= #{:door/locked :door/bolted}
                                      (get-in (:snapshot edn)
                                              [:rf/default :app-db
                                               :rf.db/runtime :rf.runtime/machines :snapshots :door :tags]))
                                   "the deep :tags set rides through full-mode intact")))))))
            (.then (fn [_] (done))))))))

(deftest snapshot-tool-summary-mode-set-valued-app-db
  (testing "snapshot summary-mode summarises a set-valued app-db without crashing"
    (async done
      (let [snap {:rf/default {:app-db    db-after
                               :sub-cache {}
                               :machines  {:door {:tags #{:door/locked}}}
                               :epochs    []
                               :traces    []}}]
        (-> (with-substr-eval! "snapshot-state" (snapshot-response snap)
              (fn []
                (-> (snapshot/snapshot-tool nil (tu/args->js {:mode "summary"
                                                              :frames #js [":rf/default"]}))
                    (.then (fn [result]
                             (is (not (tu/error? result)))
                             (let [edn (tu/extract-edn result)]
                               (is (true? (:ok? edn)))
                               ;; :machines slice (a map carrying a set) summarises to a marker.
                               (is (contains? (get-in (:snapshot edn) [:rf/default :machines])
                                              :rf.mcp/summary))))))))
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; End-to-end: trace-window-tool with sets inside the epoch records.
;; ---------------------------------------------------------------------------

(deftest trace-window-tool-set-valued-epochs
  (testing "trace-window ships epochs whose db carries sets without crashing"
    (async done
      (let [epochs [{:epoch-id :e1 :committed-at 100
                     :db-before db-before :db-after db-before}
                    {:epoch-id :e2 :committed-at 200
                     :db-before db-before :db-after db-after}]
            resp   {:epochs        epochs
                    :id-aged-out?  false
                    :requested-id  nil
                    :head-id       :e2
                    :next-id       nil
                    :history-count 2
                    :remaining     0}]
        ;; gate is OFF by default -> include-sensitive forced false;
        ;; the stubbed runtime response already represents the
        ;; (projected) page, so the client pipeline is what we test.
        (-> (with-substr-eval! "epoch-history" resp
              (fn []
                (-> (tw/trace-window-tool nil (tu/args->js {:ms 60000 :epochs-mode "diff"}))
                    (.then (fn [result]
                             (is (not (tu/error? result))
                                 "no :isError — set-valued epochs didn't crash the pipeline")
                             (let [edn      (tu/extract-edn result)
                                   restored (tu/dedup-expand (:epochs edn))
                                   decoded  (mapv diff-encode/decode-db-after restored)]
                               (is (true? (:ok? edn)))
                               (is (= 2 (:count edn)))
                               (is (= #{:door/locked :door/bolted}
                                      (get-in (:db-after (second decoded))
                                              [:rf.db/runtime :rf.runtime/machines :snapshots :door :tags]))
                                   "the modified :tags set survives the trace-window wire path")))))))
            (.then (fn [_] (done))))))))
