(ns re-frame2-pair-mcp.size-override-default-launch-test
  "The `elision false` size override is honoured on a DEFAULT launch
  (rf2-ealv5 / rf2-3x7nj.32.4).

  ## What this pins

  A `:large` declaration governs its whole subtree, so a read at or
  below it returns a `:rf.size/large-elided` marker, and the only
  structured route to the raw value is `get-path` with `elision false`.
  Until this bead every pair tool that takes `elision` forced it back to
  `true` unless the server was launched with `--allow-sensitive-reads` —
  so on a default install the route did not exist, and the echoed
  `:elision true` gave no hint the argument had been overridden. The
  launch gate protects the SENSITIVE axis; the size override is an
  `:rf.egress/include-large? true` overlay with the walker still
  running, so it can never reveal a declared-sensitive slot.

  ## Why the stub runs the real walker

  A form-level assertion (\"the form carries the overlay\") proves only
  what the tool SENDS. Here the runtime simulator lifts the egress opts
  out of the emitted form and runs them through the framework's real
  `re-frame.projection/project-egress` against a frame whose registry
  carries a real `:large` and `:sensitive` declaration. So the assertions
  are about what comes BACK: raw content under the size override, and a
  sensitive descendant still `:rf/redacted` in that same read. On the
  pre-fix tree the gate-OFF form carried no overlay, the walker marked
  the large slot, and these reds name the missing raw value.

  The pair server never `:require`s the framework (it ships a keyword,
  not a policy); this test does, because the walker is the thing under
  test and core is already on this artefact's classpath (deps.edn)."
  (:require [cljs.reader :as reader]
            [cljs.test :refer-macros [deftest is async use-fixtures]]
            [clojure.string :as str]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.projection :as rf.projection]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.get-path :as get-path]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]))

;; ---------------------------------------------------------------------------
;; The app: a doc row whose `:body` is declared `:large` and whose `:token`
;; is declared `:sensitive` — both index-free, so they govern every row.
;; ---------------------------------------------------------------------------

(def ^:private fid :rf2-ealv5/size-override)

(def ^:private body "BODY-7-LARGE-rf2-ealv5")
(def ^:private secret "SECRET-7-rf2-ealv5-do-not-leak")

(def ^:private app-db
  {:docs [{:id 7 :body body :token secret}]})

(defn- install-frame! []
  (when-not (rf.substrate.adapter/current-adapter)
    (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter))
  (when-not (rf.frame/frame fid)
    (rf.frame/upsert-frame! fid {:doc "rf2-ealv5 size-override fixture frame"}))
  (rf.frame/swap-runtime-db! fid
    (fn [rt] (rf.elision/apply-classification-effects
               rt {:sensitive [[:docs :token]]
                   :large     [[:docs :body]]}))))

(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:before (fn []
             (install-frame!)
             (raw-state/set-allow-raw-state! false)
             (raw-state/reset-runtime-signal-cache!))
   :after  (fn []
             (set! nrepl/cljs-eval-value pristine-eval)
             (raw-state/set-allow-raw-state! false)
             (raw-state/reset-runtime-signal-cache!))})

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

;; ---------------------------------------------------------------------------
;; The runtime simulator — the egress opts come OUT OF THE EMITTED FORM.
;; ---------------------------------------------------------------------------

(defn- egress-opts-of
  "The `{:rf.egress/profile ...}` opts map the get-path form hands the
  door, read back as data. Throws when the form carries none, so a form
  that stopped naming a profile cannot silently read as an empty map."
  [form]
  (if-let [m (re-find #"\{:rf\.egress/profile[^{}]*\}" form)]
    (reader/read-string m)
    (throw (ex-info "get-path form names no :rf.egress/profile" {:form form}))))

(defn- project [opts path]
  (rf.projection/project-egress (get-in app-db path)
                                (merge {:path path :frame fid} opts)))

(defn- marker? [v] (and (map? v) (contains? v :rf.size/large-elided)))

(defn- count-markers [v]
  (count (filter marker? (tree-seq coll? seq v))))

(defn- runtime-answer [form {:keys [path paths]}]
  (let [opts (egress-opts-of form)]
    (if paths
      (let [results (into {}
                          (map (fn [p] [p {:exists? true :value (project opts p)}]))
                          paths)]
        {:ok? true :results results :elided-count (count-markers results)})
      (let [v (project opts path)]
        {:ok? true :exists? true :path path :value v :elided-count (count-markers v)}))))

(defn- stub-runtime! [captured* request]
  (let [respond (fn [form]
                  (cond
                    (re-find #"__re_frame2_pair_runtime" form) (js/Promise.resolve true)
                    (re-find #"configure-raw-state!" form)     (js/Promise.resolve nil)
                    :else (do (reset! captured* form)
                              (js/Promise.resolve (runtime-answer form request)))))]
    (set! nrepl/cljs-eval-value
          (fn
            ([_c _b form] (respond form))
            ([_c _b form _o] (respond form))))))

(defn- fail!
  "A throw inside the chain is a RED, never a silent hang."
  [e]
  (is (nil? e) (str "the get-path round trip threw: " (or (ex-message e) e))))

(defn- call-get-path!
  "Invoke the real `get-path-tool`; resolves to `[result-js edn form]`."
  [args request]
  (let [captured (atom nil)]
    (stub-runtime! captured request)
    (-> (get-path/get-path-tool (fresh-conn)
                                (tu/args->js (assoc args :frame (str fid))))
        (.then (fn [r] [r (tu/extract-edn r) @captured])))))

;; ---------------------------------------------------------------------------
;; The witnesses — RED on the pre-fix tree (gate OFF forced `elision true`).
;; ---------------------------------------------------------------------------

(deftest default-launch-elision-false-reads-raw-and-still-redacts-sensitive
  (async done
    (is (false? (raw-state/raw-state-allowed?))
        "precondition: a DEFAULT launch — no --allow-sensitive-reads")
    (-> (call-get-path! {:path "[:docs 0]" :elision false} {:path [:docs 0]})
        (.then
          (fn [[r edn form]]
            (is (not (tu/error? r)))
            (is (= false (:elision edn))
                "the echo reports the HONOURED size choice, not a silent override")
            (is (str/includes? form ":rf.egress/include-large? true")
                "the form carries the size overlay on a default launch")
            (is (str/includes? form ":rf.egress/profile :rf.egress/off-box-tool")
                "and stays on the off-box-tool boundary")
            (is (not (str/includes? form ":rf.egress/local-raw"))
                "a size override never selects the full-raw boundary")
            (let [v (:value edn)]
              (is (= body (:body v))
                  "the declared-:large :body comes back RAW under elision false")
              (is (= :rf/redacted (:token v))
                  "the declared-:sensitive :token in the SAME read still redacts")
              (is (= 7 (:id v))))
            (is (not (str/includes? (tu/extract-text r) secret))
                "the secret appears nowhere in the reply")))
        (.catch fail!)
        (.then (fn [_] (done))))))

(deftest default-launch-elision-false-reads-below-a-large-declaration
  ;; A read AT/BELOW the declaration: without the override it is a marker
  ;; (rf2-ealv5 option (a)); with it, the raw value.
  (async done
    (-> (call-get-path! {:path "[:docs 0 :body]"} {:path [:docs 0 :body]})
        (.then (fn [[_ edn _]]
                 (is (marker? (:value edn))
                     "control: by default a read below the declaration returns a marker")
                 (is (= [:docs 0 :body]
                        (get-in edn [:value :rf.size/large-elided :path])))
                 (call-get-path! {:path "[:docs 0 :body]" :elision false}
                                 {:path [:docs 0 :body]})))
        (.then (fn [[_ edn _]]
                 (is (= body (:value edn))
                     "re-called on the marker's :path with elision false, the raw value")
                 (is (= false (:elision edn)))))
        (.catch fail!)
        (.then (fn [_] (done))))))

(deftest default-launch-batch-paths-honour-elision-false
  (async done
    (-> (call-get-path! {:paths "[[:docs 0 :body] [:docs 0 :token]]" :elision false}
                        {:paths [[:docs 0 :body] [:docs 0 :token]]})
        (.then (fn [[_ edn form]]
                 (is (str/includes? form ":rf.egress/include-large? true"))
                 (is (= false (:elision edn)))
                 (is (= body (get-in edn [:results [:docs 0 :body] :value]))
                     "the batch form fetches the large value raw too")
                 (is (= :rf/redacted (get-in edn [:results [:docs 0 :token] :value]))
                     "and still redacts the sensitive one")))
        (.catch fail!)
        (.then (fn [_] (done))))))

;; ---------------------------------------------------------------------------
;; Controls — the sensitive axis stays gated, and the instrument can SEE a
;; secret when it is genuinely allowed.
;; ---------------------------------------------------------------------------

(deftest default-launch-include-sensitive-is-still-dropped
  (async done
    (-> (call-get-path! {:path "[:docs 0]" :elision false :include-sensitive true}
                        {:path [:docs 0]})
        (.then (fn [[r edn form]]
                 (is (not (str/includes? form ":rf.egress/local-raw"))
                     "gate OFF: include-sensitive true never names local-raw")
                 (is (= :rf/redacted (get-in edn [:value :token])))
                 (is (not (str/includes? (tu/extract-text r) secret)))))
        (.catch fail!)
        (.then (fn [_] (done))))))

(deftest gate-on-full-raw-control-shows-the-secret
  (async done
    (raw-state/set-allow-raw-state! true)
    (-> (call-get-path! {:path "[:docs 0]" :elision false :include-sensitive true}
                        {:path [:docs 0]})
        (.then (fn [[_ edn form]]
                 (is (str/includes? form ":rf.egress/profile :rf.egress/local-raw"))
                 (is (= secret (get-in edn [:value :token]))
                     "positive control: the simulator returns a secret when allowed, so the redactions above are real")))
        (.catch fail!)
        (.then (fn [_] (done))))))
