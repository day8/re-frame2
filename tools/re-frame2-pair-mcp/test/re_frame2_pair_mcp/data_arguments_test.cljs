(ns re-frame2-pair-mcp.data-arguments-test
  "EDN arguments advertised as DATA reach the runtime as the datum the
  caller sent.

  Every caller EDN slot is emitted through `eval-form/rt-quote`, because
  printing renders a value as SOURCE: a printed list is a call and a
  printed symbol a name lookup, both evaluated while the runtime call is
  constructed — so `get-path [(inc 41)]` would read key 42 and report
  success. Printed source and quoted data read back identically as EDN, so
  these tests ask what the emitted argument EVALUATES to (`quoted-datum`):
  `(quote x)` yields `x`, a bare `(inc 41)` yields 42.

  The controls matter as much as the witnesses: the emitter's own raw
  source (synthesised predicate fns) must stay source."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.eval-cljs :as eval-cljs]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.writes :as writes]
            [re-frame2-pair-mcp.tools.record :as record]
            [re-frame2-pair-mcp.tools.watch-until :as watch-until]
            [re-frame2-pair-mcp.tools.read-sub :as read-sub]
            [re-frame2-pair-mcp.tools.get-path :as get-path]
            [re-frame2-pair-mcp.tools.handler-meta :as handler-meta]
            [re-frame2-pair-mcp.tools.restore-epoch :as restore-epoch]
            [re-frame2-pair-mcp.tools.replay-epoch :as replay-epoch]))

(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:after (fn []
            (set! nrepl/cljs-eval-value pristine-eval)
            (eval-cljs/set-eval-allowed! true))})

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(defn- read-form
  "Read an emitted form string back to data. The `#js {}` sentinel and the
  `#(...)` elision counter are emitter-composed source no caller argument
  reaches, rewritten to forms the EDN reader accepts."
  [form-str]
  (-> form-str
      (str/replace "#js {}" "{}")
      (str/replace "#(" "(")
      (cljs.reader/read-string)))

(defn- quoted-datum
  "The datum a `(quote <datum>)` form evaluates to, or `::not-quoted`."
  [form]
  (if (and (seq? form) (= 'quote (first form)) (= 2 (count form)))
    (second form)
    ::not-quoted))

(defn- find-call
  "The first sub-form whose head is `sym`, anywhere in `form`."
  [form sym]
  (first (filter #(and (seq? %) (= sym (first %)))
                 (tree-seq coll? seq form))))

(defn- let-binding
  "The value bound to `nm` by any `let` inside `form`."
  [form nm]
  (some (fn [node]
          (when (and (seq? node) (= 'let (first node)) (vector? (second node)))
            (some (fn [[k v]] (when (= nm k) v))
                  (partition 2 (second node)))))
        (tree-seq coll? seq form)))

(defn- capture-eval!
  "Stub `cljs-eval-value`: answer the preload probe and the raw-state
  signal directly, record every other form into `forms*` and answer it
  with `canned`."
  [forms* canned]
  (let [respond (fn [form]
                  (cond
                    (and (string? form) (re-find #"__re_frame2_pair_runtime" form))
                    (js/Promise.resolve true)

                    (and (string? form) (re-find #"configure-raw-state!" form))
                    (js/Promise.resolve nil)

                    :else
                    (do (swap! forms* conj form)
                        (js/Promise.resolve canned))))]
    (raw-state/reset-runtime-signal-cache!)
    (set! nrepl/cljs-eval-value
          (fn
            ([_c _b form] (respond form))
            ([_c _b form _o] (respond form))))))

(defn- form-matching
  "The single captured form containing `needle`."
  [forms* needle]
  (first (filter #(str/includes? % needle) @forms*)))

;; `(inc 41)` is ordinary EDN that EVALUATES to 42, so an unquoted emission
;; shows up as a value change rather than a crash.
(def ^:private inert-list (list 'inc 41))

(def ^:private tool-slots
  "[label tool args canned needle extract expected] — `extract` finds the
  caller's slot in the read-back form."
  [["read-sub query" read-sub/read-sub-tool #js {:sub "[:review/sub (inc 41)]"}
    {:ok? true :query-v [:review/sub inert-list] :frame :rf/default :value 1}
    "read-sub!" #(second (find-call % 're-frame2-pair.runtime/read-sub!))
    [:review/sub inert-list]]
   ["get-path path" get-path/get-path-tool #js {:path "[(inc 41)]"}
    {:ok? true :exists? true :path [inert-list] :value :list-key :elided-count 0}
    "get-in db path" #(let-binding % 'path)
    [inert-list]]
   ["get-path paths" get-path/get-path-tool #js {:paths "[[(inc 41)]]"}
    {:ok? true :results {} :elided-count 0}
    "reduce" #(last (find-call % 'reduce))
    [[inert-list]]]
   ["handler-meta id" handler-meta/handler-meta-tool #js {:kind "sub" :id "[:rf/composite (inc 41)]"}
    {:ok? false :reason :not-registered}
    "registrar-describe" #(last (find-call % 're-frame2-pair.runtime/registrar-describe))
    [:rf/composite inert-list]]
   ["restore-epoch epoch-id" restore-epoch/restore-epoch-tool #js {:epoch-id "(inc 41)"}
    {:ok? false :restored? false :reason :restore-rejected}
    "restore-epoch" #(second (find-call % 're-frame2-pair.runtime/restore-epoch))
    inert-list]
   ["replay-epoch epoch-id" replay-epoch/replay-epoch-tool #js {:epoch-id "(inc 41)"}
    {:ok? false :reason :no-such-epoch}
    "replay-epoch" #(second (find-call % 're-frame2-pair.runtime/replay-epoch))
    inert-list]])

(deftest caller-edn-reaches-the-runtime-unevaluated
  (async done
    (let [prev (writes/allow-writes-enabled?)]
      (writes/set-allow-writes! true)
      (-> (reduce
            (fn [p [label tool args canned needle extract expected]]
              (.then p (fn [_]
                         (let [forms (atom [])]
                           (capture-eval! forms canned)
                           (.then (tool (fresh-conn) args)
                                  (fn [_]
                                    (is (= expected
                                           (quoted-datum (extract (read-form (form-matching forms needle)))))
                                        label)))))))
            (js/Promise.resolve nil)
            tool-slots)
          (.catch (fn [e] (is false (str "drive rejected: " e))))
          (.finally (fn []
                      (writes/set-allow-writes! prev)
                      (done)))))))

;; ---------------------------------------------------------------------------
;; watch-until / record — the signal set and the stop bounds.
;; ---------------------------------------------------------------------------

(deftest watch-form-signals-are-not-evaluated
  (let [form (read-form
               (watch-until/watch-form [{:sub [:review/sub inert-list]}]
                                       :rf/default
                                       (record/pred-source {:signal 0 :equals :done})
                                       "{}"))
        call (find-call form 're-frame2-pair.runtime/sample-signals)]
    (is (= [{:sub [:review/sub inert-list]}] (quoted-datum (second call))))))

(deftest watch-form-keeps-the-synthesised-predicate-as-source
  ;; CONTROL — quoted, the poll would compare a list against the sample
  ;; instead of calling the fn.
  (let [src (watch-until/watch-form [{:app-db [:x]}] :rf/default
                                    (record/pred-source {:signal 0 :equals :done})
                                    "{}")]
    (is (str/includes? src "(boolean ((fn [sample]"))))

(deftest record-signals-and-stop-bounds-are-not-evaluated
  (let [src  (#'record/start-recording-form
               [{:sub [:review/sub inert-list]}]
               {:ms 15000 :pred {:signal 0 :equals :done}}
               :rf/default
               2000
               "{:rf.egress/include-large? false :rf.egress/include-sensitive? false}")
        opts (second (find-call (read-form src) 're-frame2-pair.runtime/start-recording!))]
    (is (= [{:sub [:review/sub inert-list]}] (quoted-datum (:signals opts))))
    (is (= 15000 (quoted-datum (get-in opts [:stop :ms]))))
    (is (str/includes? src ":pred-fn (fn [sample]")
        "the synthesised predicate in the same map stays raw source")))
