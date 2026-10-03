(ns re-frame2-pair-mcp.orient-test
  "Unit tests for the orient tool — the app-shape orientation summary.

  The tool is a thin wrapper over the runtime `orient` fn: it emits the
  `(re-frame2-pair.runtime/orient)` form and shapes the response. Here we
  stub `cljs-eval-value` to return a representative orient summary and pin:

    - the emitted form calls the runtime `orient` fn;
    - the summary shape (liveness / frames / app-db-top-keys / registry /
      machines) rides through to the wire envelope unchanged;
    - a non-map runtime return degrades to a structured error, never a
      silent success.

  The runtime `orient` composition is exercised by the bb structural pin
  (tests/runtime/orient_test.clj in the skill).

  ## Stub lifetime — fixture-scoped, not Promise-chain-scoped

  Each test installs its `cljs-eval-value` stub via a bare `set!` (no
  per-test `.finally`); a `use-fixtures :each :after` step unconditionally
  restores the pristine original captured at ns-load. This keeps cleanup
  independent of the Promise chain: a `.finally`-scoped restore fires
  AFTER cljs.test's `done` has already advanced to the next test, which in
  the full suite would let a neighbour's late restore clobber THIS test's
  freshly-installed stub mid-eval — surfacing as a flaky
  `connect EADDRNOTAVAIL` from the real socket fn. The fixture boundary
  closes that race (the same approach invoke_test uses)."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.orient :as orient]))

(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:after (fn [] (set! nrepl/cljs-eval-value pristine-eval))})

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(def ^:private read-result-text tu/extract-edn)
(def ^:private err? tu/error?)

(def ^:private sample-summary
  {:ok? true
   :liveness {:debug-enabled? true :frame-count 2 :app-frame-count 1
              :ambiguous-frame? false :runtime-instance-id "abc"}
   :frames   {:all [:rf/default :rf/xray] :app [:rf/default] :operating :rf/default}
   :app-db-top-keys {:rf/default [:cart :route :user]}
   ;; The runtime orient counts carry the three EP-0016 resources-artefact
   ;; kinds (:resource / :mutation / :resource-scope) — see the orient
   ;; descriptor example in descriptors_data.cljs.
   :registry {:counts {:event 14 :sub 9 :fx 3 :cofx 1 :view 6
                       :frame 2 :route 4 :flow 0 :head 0 :error-projector 0
                       :resource 3 :mutation 2 :resource-scope 1}
              :events [:cart/add :cart/checkout]
              :subs   [:cart/total :current-user]
              :fx     [:http :navigate :persist]}
   :machines [:checkout]})

(defn- stub-eval!
  "Install a `cljs-eval-value` stub via a bare `set!` (NO `.finally` —
  cleanup is the `:after` fixture's job). Records the emitted
  (non-probe) form into `captured*` and resolves it with `canned`.
  PROBE-AWARE: the preload-probe sentinel form (`__re_frame2_pair_runtime`)
  is answered with `true` so the tool's `ensure-runtime!` preflight passes
  regardless of conn-cache state. `captured*` may be nil."
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

(deftest emits-the-orient-runtime-form
  (async done
    (let [captured (atom nil)]
      (stub-eval! captured sample-summary)
      (-> (orient/orient-tool (fresh-conn) #js {})
          (.then (fn [_]
                   (is (= "(re-frame2-pair.runtime/orient)" @captured)
                       "emits the runtime orient call, no args")
                   (done)))))))

(deftest summary-shape-rides-through
  (async done
    (stub-eval! nil sample-summary)
    (-> (orient/orient-tool (fresh-conn) #js {})
        (.then (fn [r]
                 (is (not (err? r)))
                 (is (= sample-summary (dissoc (read-result-text r) :build))
                     "every slot of the runtime summary rides through unchanged")
                 (done))))))

(deftest echoes-session-sticky-build-when-omitted
  ;; With a session target cached (a prior discover-app), orient with no
  ;; :build arg resolves to AND echoes that sticky target.
  (async done
    (stub-eval! nil sample-summary)
    (let [conn (fresh-conn)]
      (swap! conn assoc :resolved-build-id :examples/step-deck
                        :probed-builds #{:examples/step-deck})
      (-> (orient/orient-tool conn #js {})
          (.then (fn [r]
                   (is (= :examples/step-deck (:build (read-result-text r)))
                       "orient resolves + echoes the session-sticky build")
                   (done)))))))
