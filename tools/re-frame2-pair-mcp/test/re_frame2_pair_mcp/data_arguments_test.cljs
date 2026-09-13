(ns re-frame2-pair-mcp.data-arguments-test
  "rf2-fzbj.6 — EDN arguments advertised as DATA reach the runtime as the
  datum the caller sent.

  ## What was wrong

  `rf2-j2wz` / `rf2-olqo` repaired two slots — `dispatch`'s event and
  `replace-app-db`'s db — by emitting them through `eval-form/rt-quote`
  instead of the default `pr-str` arg path. The remaining EDN slots kept
  printing, and PRINTING RENDERS A VALUE AS SOURCE. So a query, a path, a
  signal, a scripted coeffect, a registrar id or an epoch-id containing a
  LIST was a function call, one containing a SYMBOL was a name lookup, and
  one shaped like the emitter's own tagged IR was spliced in as raw
  source. All three happen while the runtime call is being CONSTRUCTED —
  before the runtime validates anything, and regardless of whether
  `eval-cljs` is enabled, since the expression is embedded in a DIFFERENT
  tool's generated form.

  The consequences are not cosmetic: a read-only tool answers `:ok? true`
  ABOUT THE WRONG TARGET (`get-path [(inc 41)]` reads key 42 and reports
  success), and a replayed dispatch uses a DIFFERENT causal fact from the
  one the caller scripted, which is the exact determinism a recorded cofx
  exists to provide.

  ## How these tests read the emitted form

  Through `quoted-datum`, which asks what the emitted argument EVALUATES
  to rather than what it prints as. That distinction is the whole finding:
  `pr-str`'d source and quoted data READ BACK IDENTICALLY as EDN, so an
  assertion that reads the argument as EDN and compares it passes on the
  broken tree. Only evaluation semantics tell them apart — `(quote x)`
  yields `x` for every EDN value, a bare `(inc 41)` yields 42.

  The controls matter as much as the witnesses: ordinary scalar/map
  arguments must be unchanged, and the emitter's INTERNAL raw-source
  splices (`rt-raw` — let-bound names, synthesised predicate fns, the
  resolved-frame symbol) must stay raw source. Quoting everything would
  break the tools as surely as quoting nothing left them wrong."
  (:require [cljs.test :refer-macros [deftest is async testing use-fixtures]]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
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

;; ---------------------------------------------------------------------------
;; Form readers.
;; ---------------------------------------------------------------------------

(defn- read-form
  "Read an emitted form string back to data.

  Two INTERNAL raw-source splices use reader syntax the EDN reader has
  no tag for — the `#js {}` missing-sentinel and the `#(...)` anonymous
  fn in the elision counter — so they are rewritten to their readable
  equivalents (`{}` and a plain list) first. Both are emitter-composed
  source this suite never asserts on; neither substitution can reach a
  caller-supplied argument, which is what every assertion here reads."
  [form-str]
  (-> form-str
      (str/replace "#js {}" "{}")
      (str/replace "#(" "(")
      (cljs.reader/read-string)))

(defn- quoted-datum
  "The datum a `(quote <datum>)` form evaluates to, or `::not-quoted` for
  anything else — an unquoted list is a call and an unquoted symbol is a
  name lookup, so neither yields the datum it was printed from."
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
  "Install a `cljs-eval-value` stub recording every emitted form into
  `forms*` and answering with `canned`. Prelude evals (the preload
  sentinel probe and the raw-state signal) are answered directly so the
  preflight passes regardless of cache state."
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

;; The witness payloads. `(inc 41)` is an ordinary EDN list that
;; EVALUATES to 42, so an unquoted emission is visible as a value change
;; rather than as a crash; `js/window` is a symbol that resolves; the
;; emitter-tagged vector is the strongest witness, because unquoted it is
;; recognised as IR and its payload spliced in as raw source.
(def ^:private inert-list (list 'inc 41))
(def ^:private raw-tag :re-frame2-pair-mcp.tools.eval-form/raw)

;; ---------------------------------------------------------------------------
;; read-sub — the query vector.
;; ---------------------------------------------------------------------------

(deftest read-sub-query-list-is-not-evaluated
  (async done
    (let [forms (atom [])]
      (capture-eval! forms {:ok? true :query-v [:review/sub inert-list]
                            :frame :rf/default :value 1})
      (-> (read-sub/read-sub-tool (fresh-conn) #js {:sub "[:review/sub (inc 41)]"})
          (.then (fn [_]
                   (let [call (find-call (read-form (form-matching forms "read-sub!"))
                                         're-frame2-pair.runtime/read-sub!)]
                     (is (= [:review/sub inert-list] (quoted-datum (second call)))
                         "the query reaches read-sub! as the datum the caller sent")
                     (is (not= [:review/sub 42] (second call))
                         "and NOT as a printed expression that evaluates to 42"))
                   (done)))))))

(deftest read-sub-query-symbol-is-not-resolved
  (async done
    (let [forms (atom [])]
      (capture-eval! forms {:ok? true :value 1})
      (-> (read-sub/read-sub-tool (fresh-conn) #js {:sub "[:review/sub js/window]"})
          (.then (fn [_]
                   (let [call (find-call (read-form (form-matching forms "read-sub!"))
                                         're-frame2-pair.runtime/read-sub!)]
                     (is (= [:review/sub 'js/window] (quoted-datum (second call)))
                         "a symbol-valued query element stays a symbol"))
                   (done)))))))

(deftest read-sub-emitter-shaped-query-is-not-spliced
  (async done
    (let [forms (atom [])]
      (capture-eval! forms {:ok? true :value 1})
      (-> (read-sub/read-sub-tool
            (fresh-conn)
            #js {:sub "[:re-frame2-pair-mcp.tools.eval-form/raw \"(inc 41)\"]"})
          (.then (fn [_]
                   (let [src  (form-matching forms "read-sub!")
                         call (find-call (read-form src)
                                         're-frame2-pair.runtime/read-sub!)]
                     (is (= [raw-tag "(inc 41)"] (quoted-datum (second call)))
                         "the tagged vector rides through as the vector it is")
                     (is (not (str/includes? src "read-sub! (inc 41)"))
                         "no raw-source splice in the runtime call"))
                   (done)))))))

(deftest read-sub-ordinary-query-is-unchanged
  ;; CONTROL — scalars and maps print and quote alike, so the repair must
  ;; leave the everyday query reaching the runtime as the same datum.
  (async done
    (let [forms (atom [])]
      (capture-eval! forms {:ok? true :value 1})
      (-> (read-sub/read-sub-tool (fresh-conn) #js {:sub "[:review/sub {:user/id 42}]"})
          (.then (fn [_]
                   (let [call (find-call (read-form (form-matching forms "read-sub!"))
                                         're-frame2-pair.runtime/read-sub!)]
                     (is (= [:review/sub {:user/id 42}] (quoted-datum (second call)))))
                   (done)))))))

(deftest read-sub-quotes-with-eval-cljs-disabled
  ;; The defect is reachable with the eval gate OFF, because the
  ;; expression rides inside a DIFFERENT tool's generated form. The
  ;; repair must therefore hold there too.
  (async done
    (let [forms (atom [])]
      (eval-cljs/set-eval-allowed! false)
      (capture-eval! forms {:ok? true :value 1})
      (-> (read-sub/read-sub-tool (fresh-conn) #js {:sub "[:review/sub (inc 41)]"})
          (.then (fn [_]
                   (let [call (find-call (read-form (form-matching forms "read-sub!"))
                                         're-frame2-pair.runtime/read-sub!)]
                     (is (= [:review/sub inert-list] (quoted-datum (second call)))
                         "argument fidelity does not depend on the eval-cljs gate"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; get-path — the singular path and the plural batch.
;; ---------------------------------------------------------------------------

(deftest get-path-singular-path-is-not-evaluated
  (async done
    (let [forms (atom [])]
      (capture-eval! forms {:ok? true :exists? true :path [inert-list]
                            :value :list-key :elided-count 0})
      (-> (get-path/get-path-tool (fresh-conn) #js {:path "[(inc 41)]"})
          (.then (fn [_]
                   (let [form (read-form (form-matching forms "get-in db path"))]
                     (is (= [inert-list] (quoted-datum (let-binding form 'path)))
                         "get-in reads the path the caller asked for")
                     (is (not= [42] (let-binding form 'path))
                         "and not a path whose segment was evaluated first"))
                   (done)))))))

(deftest get-path-batch-paths-are-not-evaluated
  (async done
    (let [forms (atom [])]
      (capture-eval! forms {:ok? true :results {} :elided-count 0})
      (-> (get-path/get-path-tool (fresh-conn) #js {:paths "[[(inc 41)]]"})
          (.then (fn [_]
                   (let [form   (read-form (form-matching forms "reduce"))
                         reduce-call (find-call form 'reduce)]
                     (is (= [[inert-list]] (quoted-datum (last reduce-call)))
                         "the batch folds over the paths the caller sent"))
                   (done)))))))

(deftest get-path-keeps-internal-raw-source-raw
  ;; CONTROL — the resolved-frame handle is an INTERNAL let-bound name
  ;; (an `rt-raw` splice), not caller data. Quoting it would hand the
  ;; runtime a symbol instead of the frame it names.
  (async done
    (let [forms (atom [])]
      (capture-eval! forms {:ok? true :exists? true :path [:a] :value 1 :elided-count 0})
      (-> (get-path/get-path-tool (fresh-conn) #js {:path "[:a]"})
          (.then (fn [_]
                   (let [form (read-form (form-matching forms "get-in db path"))
                         snap (find-call form 're-frame2-pair.runtime/snapshot)]
                     (is (symbol? (second snap))
                         "the frame handed to snapshot stays a bare let-bound symbol")
                     (is (= [:a] (quoted-datum (let-binding form 'path)))
                         "while the caller's path is quoted data"))
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
    (is (= [{:sub [:review/sub inert-list]}] (quoted-datum (second call)))
        "the sampler watches the signal the caller described")
    (is (not= [{:sub [:review/sub 42]}] (second call))
        "and not one whose query element was evaluated first")))

(deftest watch-form-keeps-the-synthesised-predicate-as-source
  ;; CONTROL — `pred-source` output is source this server synthesised,
  ;; not caller data. It must NOT be quoted, or the poll would compare a
  ;; list against the sample instead of calling the fn.
  (let [src (watch-until/watch-form [{:app-db [:x]}] :rf/default
                                    (record/pred-source {:signal 0 :equals :done})
                                    "{}")]
    (is (str/includes? src "(boolean ((fn [sample]")
        "the predicate fn literal is still applied as source")))

(deftest record-signals-and-stop-bounds-are-not-evaluated
  (let [src  (#'record/start-recording-form
               [{:sub [:review/sub inert-list]}]
               {:ms 15000 :pred {:signal 0 :equals :done}}
               :rf/default
               2000
               "{:rf.egress/include-large? false :rf.egress/include-sensitive? false}")
        form (read-form src)
        call (find-call form 're-frame2-pair.runtime/start-recording!)
        opts (second call)]
    (is (= [{:sub [:review/sub inert-list]}] (quoted-datum (:signals opts)))
        "the recorder records the signal the caller described")
    (is (= 15000 (quoted-datum (get-in opts [:stop :ms])))
        "the stop bound rides as the datum the caller sent")
    (testing "the synthesised predicate stays raw source (the mixed map's point)"
      (is (str/includes? src ":pred-fn (fn [sample]")
          "the :pred-fn slot is a fn literal, not quoted data"))))

;; ---------------------------------------------------------------------------
;; handler-meta / restore-epoch / replay-epoch — the remaining EDN slots.
;; ---------------------------------------------------------------------------

(deftest handler-meta-id-is-not-evaluated
  (async done
    (let [forms (atom [])]
      (capture-eval! forms {:ok? false :reason :not-registered})
      (-> (handler-meta/handler-meta-tool
            (fresh-conn)
            #js {:kind "sub" :id "[:rf/composite (inc 41)]"})
          (.then (fn [_]
                   (let [call (find-call (read-form (form-matching forms "registrar-describe"))
                                         're-frame2-pair.runtime/registrar-describe)]
                     (is (= [:rf/composite inert-list] (quoted-datum (last call)))
                         "the composite id is looked up as the datum the caller sent"))
                   (done)))))))

(deftest restore-epoch-id-is-not-evaluated
  (async done
    (let [forms (atom [])
          prev  (writes/allow-writes-enabled?)]
      (writes/set-allow-writes! true)
      (capture-eval! forms {:ok? false :restored? false :reason :restore-rejected})
      (-> (restore-epoch/restore-epoch-tool (fresh-conn) #js {:epoch-id "(inc 41)"})
          (.then (fn [_]
                   (let [call (find-call (read-form (form-matching forms "restore-epoch"))
                                         're-frame2-pair.runtime/restore-epoch)]
                     (is (= inert-list (quoted-datum (second call)))
                         "the epoch-id reaches the runtime unevaluated"))
                   (writes/set-allow-writes! prev)
                   (done)))))))

(deftest replay-epoch-id-is-not-evaluated
  (async done
    (let [forms (atom [])
          prev  (writes/allow-writes-enabled?)]
      (writes/set-allow-writes! true)
      (capture-eval! forms {:ok? false :reason :no-such-epoch})
      (-> (replay-epoch/replay-epoch-tool (fresh-conn) #js {:epoch-id "(inc 41)"})
          (.then (fn [_]
                   (let [call (find-call (read-form (form-matching forms "replay-epoch"))
                                         're-frame2-pair.runtime/replay-epoch)]
                     (is (= inert-list (quoted-datum (second call)))
                         "the epoch-id reaches the runtime unevaluated"))
                   (writes/set-allow-writes! prev)
                   (done)))))))

(deftest ordinary-integer-epoch-id-still-rides
  ;; CONTROL — the documented everyday shape (`epoch-id "7"`) is a
  ;; number, which prints and quotes alike.
  (async done
    (let [forms (atom [])
          prev  (writes/allow-writes-enabled?)]
      (writes/set-allow-writes! true)
      (capture-eval! forms {:ok? true :restored? true :epoch-id 7})
      (-> (restore-epoch/restore-epoch-tool (fresh-conn) #js {:epoch-id "7"})
          (.then (fn [_]
                   (let [call (find-call (read-form (form-matching forms "restore-epoch"))
                                         're-frame2-pair.runtime/restore-epoch)]
                     (is (= 7 (quoted-datum (second call)))))
                   (writes/set-allow-writes! prev)
                   (done)))))))
