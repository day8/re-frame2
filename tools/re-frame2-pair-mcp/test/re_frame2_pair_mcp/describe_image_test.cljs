(ns re-frame2-pair-mcp.describe-image-test
  "Unit tests for the describe-image tool (rf2-srobm0) — the EP-0023
  Use-Case 7 read over a frame's resolved image generation.

  The tool is a thin wrapper over the runtime `describe-image` fn: it emits
  the `(re-frame2-pair.runtime/describe-image <opts>)` form (threading the
  optional `:frame` / `:include-ns?` opts) and shapes the response. Here we
  stub `cljs-eval-value` to return a representative generation summary and
  pin:

    - the emitted form calls the runtime describe-image fn with the right opts;
    - the summary shape (frame / images / kinds / requires / counts /
      registrations) rides through to the wire envelope unchanged;
    - a non-map runtime return degrades to a structured error;
    - the descriptor is registered with the optional :frame / :include-ns args.

  The runtime `describe-image` composition (the generation projection) is
  exercised by the runtime preload tests.

  ## Stub lifetime — fixture-scoped (rf2-wb06a)

  Each test installs its `cljs-eval-value` stub via a bare `set!`; a
  `use-fixtures :each :after` step restores the pristine original. See
  orient_test for the race this closes."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [clojure.string :as str]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.describe-image :as di]))

(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:after (fn [] (set! nrepl/cljs-eval-value pristine-eval))})

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(def ^:private args-js tu/args->js)
(def ^:private read-result-text tu/extract-edn)
(def ^:private err? tu/error?)

(defn- find-descriptor [name]
  (some #(when (= name (:name %)) %) tools/tool-descriptors))

(def ^:private sample-generation
  {:ok?      true
   :frame    :main
   :images   [:app/img]
   :kinds    [:event :sub]
   :requires []
   :counts   {:event 12 :sub 8}})

(def ^:private sample-with-ns
  (assoc sample-generation
         :registrations {[:event :user/login] {:source :registered :ns "my.app.user"}
                         [:sub   :current-user] {:source :registered :ns "my.app.subs"}}))

(defn- stub-eval!
  "Install a `cljs-eval-value` stub via a bare `set!` (cleanup is the
  `:after` fixture's job). Records the emitted (non-probe) form into
  `captured*` (may be nil) and resolves it with `canned`. Probe-aware."
  [captured* canned]
  (let [respond (fn [form]
                  (if (and (string? form) (re-find #"__re_frame2_pair_runtime" form))
                    (js/Promise.resolve true)
                    (do (when captured* (reset! captured* form))
                        (js/Promise.resolve canned))))]
    (set! nrepl/cljs-eval-value
          (fn
            ([_c _b form] (respond form))
            ([_c _b form _o] (respond form))))))

;; ---------------------------------------------------------------------------
;; Descriptor wire-up.
;; ---------------------------------------------------------------------------

(deftest describe-image-descriptor-present
  (let [d (find-descriptor "describe-image")]
    (is (some? d) "descriptor exists")
    (is (string? (:description d)))
    (is (integer? (:typicalTokens d)))
    (is (pos? (:typicalTokens d)))
    (let [{:keys [required properties]} (:inputSchema d)]
      (is (= [] (vec required)) "no required args (frame defaults to operating)")
      (is (contains? properties :frame))
      (is (contains? properties :include-ns)))))

(deftest describe-image-surfaces-on-tools-list
  (let [arr   (tools/tool-descriptors-js)
        names (set (for [i (range (alength arr))] (j/get (aget arr i) :name)))]
    (is (contains? names "describe-image"))))

;; ---------------------------------------------------------------------------
;; Form composition.
;; ---------------------------------------------------------------------------

(deftest emits-the-describe-image-runtime-form-no-frame
  (async done
    (let [captured (atom nil)]
      (stub-eval! captured sample-generation)
      (-> (di/describe-image-tool (fresh-conn) (args-js {}))
          (.then (fn [_]
                   (is (str/includes? @captured "re-frame2-pair.runtime/describe-image")
                       "emits the runtime describe-image call")
                   (is (not (str/includes? @captured ":frame"))
                       "no :frame threaded when omitted (operating frame)")
                   (is (not (str/includes? @captured ":include-ns?"))
                       "no :include-ns? threaded when omitted")
                   (done)))))))

(deftest emits-frame-and-include-ns-opts
  (async done
    (let [captured (atom nil)]
      (stub-eval! captured sample-with-ns)
      (-> (di/describe-image-tool (fresh-conn) (args-js {:frame ":main" :include-ns "true"}))
          (.then (fn [_]
                   (is (str/includes? @captured ":frame :main")
                       "the frame-id is threaded into the opts map")
                   (is (str/includes? @captured ":include-ns? true")
                       "include-ns coerces to the runtime's :include-ns? opt")
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Response shape.
;; ---------------------------------------------------------------------------

(deftest generation-summary-rides-through
  (async done
    (stub-eval! nil sample-generation)
    (-> (di/describe-image-tool (fresh-conn) (args-js {:frame ":main"}))
        (.then (fn [r]
                 (is (not (err? r)))
                 (let [edn (read-result-text r)]
                   (is (true? (:ok? edn)))
                   (is (= :main (:frame edn)))
                   (is (= [:app/img] (:images edn)))
                   (is (= [:event :sub] (:kinds edn)))
                   (is (= [] (:requires edn)) "the missing-capability discriminator rides through")
                   (is (= {:event 12 :sub 8} (:counts edn))
                       "per-kind selected-registration counts ride through"))
                 (done))))))

(deftest include-ns-surfaces-per-registration-coordinates
  (async done
    (stub-eval! nil sample-with-ns)
    (-> (di/describe-image-tool (fresh-conn) (args-js {:frame ":main" :include-ns "true"}))
        (.then (fn [r]
                 (let [edn (read-result-text r)]
                   (is (= {:source :registered :ns "my.app.user"}
                          (get-in edn [:registrations [:event :user/login]]))
                       "each selected (kind, id) carries its provenance coordinate"))
                 (done))))))

(deftest ambiguous-frame-rides-through-as-error
  (async done
    ;; The runtime returns the :ambiguous-frame refusal (a map with :ok?
    ;; false); the tool surfaces it. wire/ok-text wraps maps; an :ok? false
    ;; map still rides as the structured envelope.
    (stub-eval! nil {:ok? false :reason :ambiguous-frame :operation :describe-image})
    (-> (di/describe-image-tool (fresh-conn) (args-js {}))
        (.then (fn [r]
                 (let [edn (read-result-text r)]
                   (is (false? (:ok? edn)))
                   (is (= :ambiguous-frame (:reason edn))))
                 (done))))))

(deftest non-map-return-degrades-to-error
  (async done
    (stub-eval! nil 42)
    (-> (di/describe-image-tool (fresh-conn) (args-js {}))
        (.then (fn [r]
                 (is (err? r))
                 (is (= :unexpected-shape (:reason (read-result-text r))))
                 (done))))))
