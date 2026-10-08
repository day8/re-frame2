(ns re-frame2-pair-mcp.set-valued-app-db-test
  "Set-valued app-db slots survive the wire walkers.

  A consumer app whose app-db carries a `#{...}` value — a machine
  stamping `:tags #{:door/locked}` on every snapshot — must round-trip
  through every walker `snapshot` and `trace-window` run. A walker that
  iterates a set as map-entries crashes with
  `'me.cljs$core$IMapEntry$_key$arity$1 is not a function'`; these tests
  drive the real client-side pipeline over a stubbed runtime reply."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.snapshot :as snapshot]
            [re-frame2-pair-mcp.tools.summary :as summary]
            [re-frame2-pair-mcp.tools.trace-window :as tw]
            [re-frame.mcp-base.diff-encode :as rf.mcp-base.diff-encode]))

(defn- with-substr-eval!
  "Stub `cljs-eval-value`: the preload probe answers true, the form
  matching `real-substr` answers `canned`, anything else nil."
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

;; Machine snapshots sit in the runtime-db partition with sets where the
;; framework stamps them, at cardinalities 1, 2 and 3.
(defn- machine-snapshots [door-tags]
  {:rf.db/runtime
   {:rf.runtime/machines
    {:snapshots
     {:door  {:state :locked :tags door-tags        :context {:attempts 0}}
      :alarm {:state :armed  :tags #{:alarm/armed :alarm/loud}}}}}
   :counter 5})

(def ^:private db-before (machine-snapshots #{:door/locked}))
(def ^:private db-after  (machine-snapshots #{:door/locked :door/bolted}))

(def ^:private door-tags-path
  [:rf.db/runtime :rf.runtime/machines :snapshots :door :tags])

(deftest tree-summary-handles-a-set
  (let [marker (:rf.mcp/summary (summary/tree-summary #{:door/locked :armed}))]
    (is (= {:type :set :count 2} (select-keys marker [:type :count])))
    (is (pos? (:bytes marker)))))

(deftest snapshot-tool-set-valued-app-db
  ;; Full mode ships the deep set intact; summary mode summarises the
  ;; set-carrying :machines slice.
  (async done
    (let [snap {:rf/default {:app-db    db-after
                             :sub-cache {}
                             :machines  {:door {:tags #{:door/locked}}}
                             :epochs    []
                             :traces    []}}
          run  (fn [mode]
                 (with-substr-eval! "snapshot-state"
                   {:value snap :elided-count 0 :tool-frames-excluded []}
                   #(snapshot/snapshot-tool nil (tu/args->js {:mode mode :frames #js [":rf/default"]}))))]
      (-> (run "full")
          (.then (fn [r]
                   (is (= #{:door/locked :door/bolted}
                          (get-in (tu/extract-edn r) (into [:snapshot :rf/default :app-db] door-tags-path))))
                   (run "summary")))
          (.then (fn [r]
                   (is (contains? (get-in (tu/extract-edn r) [:snapshot :rf/default :machines])
                                  :rf.mcp/summary))))
          (.catch (fn [e] (is false (str "drive rejected: " e))))
          (.then (fn [_] (done)))))))

(deftest trace-window-tool-set-valued-epochs
  ;; The stubbed reply stands in for the already-projected page, so the
  ;; client pipeline (diff encoding and dedup) is what runs.
  (async done
    (let [resp {:epochs        [{:epoch-id :e1 :committed-at 100
                                 :db-before db-before :db-after db-before}
                                {:epoch-id :e2 :committed-at 200
                                 :db-before db-before :db-after db-after}]
                :id-aged-out?  false
                :requested-id  nil
                :head-id       :e2
                :next-id       nil
                :history-count 2
                :remaining     0}]
      (-> (with-substr-eval! "epoch-history" resp
            #(tw/trace-window-tool nil (tu/args->js {:ms 60000 :epochs-mode "diff"})))
          (.then (fn [r]
                   (let [decoded (mapv rf.mcp-base.diff-encode/decode-db-after
                                       (tu/dedup-expand (:epochs (tu/extract-edn r))))]
                     (is (= #{:door/locked :door/bolted}
                            (get-in (:db-after (second decoded)) door-tags-path))))))
          (.then (fn [_] (done)))))))
