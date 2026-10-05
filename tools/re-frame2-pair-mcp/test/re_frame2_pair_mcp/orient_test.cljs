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
            [re-frame2-pair-mcp.cache :as cache]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.descriptors :as descriptors]
            [re-frame2-pair-mcp.tools.orient :as orient]
            [re-frame2-pair-mcp.tools.registry :as registry]))

(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:before (fn [] (cache/clear!))
   :after  (fn [] (set! nrepl/cljs-eval-value pristine-eval) (cache/clear!))})

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

;; ---------------------------------------------------------------------------
;; A real registry orients under the DEFAULT cap.
;;
;; The runtime composes the whole sorted id vector of every navigable kind.
;; On an app with hundreds of registrations those vectors alone are several
;; times the default budget, so the mandatory first read overflowed before
;; it returned the frame / count summary it exists for. The first-contact
;; result carries counts for every kind and a capped run of example ids,
;; with a `:truncated` entry naming the exact call that returns the rest.
;; ---------------------------------------------------------------------------

(defn- many-ids
  "`n` sorted ids shaped like a real app's registrations."
  [kind n]
  (vec (sort (for [i (range n)]
               (keyword (str "app.feature-" (quot i 12) "." kind)
                        (str "handle-the-registered-thing-" i))))))

(def ^:private big-summary
  (assoc sample-summary
         :registry {:basis  :frame
                    :frame  :rf/default
                    :counts {:event 439 :sub 285 :fx 78 :cofx 6 :view 120
                             :frame 2 :route 31 :flow 0 :head 0 :error-projector 0
                             :resource 3 :mutation 2 :resource-scope 1}
                    :events (many-ids "events" 439)
                    :subs   (many-ids "subs" 285)
                    :fx     (many-ids "fx" 78)}
         :machines (many-ids "machines" 31)))

(defn- overflow? [edn] (contains? edn :rf.mcp/overflow))

(deftest a-registry-of-several-hundred-ids-orients-under-the-default-cap
  (async done
    (is (> (quot (count (pr-str big-summary)) 4) 5000)
        "control: shipped whole, this summary's EDN alone is over the default 5000-token cap")
    (stub-eval! nil big-summary)
    (-> (tools/invoke (fresh-conn) "orient" #js {} nil)
        (.then (fn [r]
                 (let [edn (read-result-text r)]
                   (is (not (err? r)))
                   (is (not (overflow? edn))
                       "the first-contact read fits the default cap without max-tokens")
                   (is (= (get-in big-summary [:registry :counts]) (get-in edn [:registry :counts]))
                       "every kind keeps its true count")
                   (doseq [path [[:registry :events] [:registry :subs] [:registry :fx] [:machines]]]
                     (is (= (take 20 (get-in big-summary path)) (get-in edn path))
                         (str path " carries the first 20 sorted ids as examples")))
                   (is (= [{:slot  [:registry :events] :shown 20 :total 439
                            :next  {:tool "list-handlers" :args {:kind "event" :frame ":rf/default"}}}
                           {:slot  [:registry :subs] :shown 20 :total 285
                            :next  {:tool "list-handlers" :args {:kind "sub" :frame ":rf/default"}}}
                           {:slot  [:registry :fx] :shown 20 :total 78
                            :next  {:tool "list-handlers" :args {:kind "fx" :frame ":rf/default"}}}
                           {:slot  [:machines] :shown 20 :total 31
                            :next  {:tool "list-handlers" :args {:kind "machine"}}}]
                          (:truncated edn))
                       "each capped list names its total and the call that returns the rest of THAT registry")
                   (is (= (:liveness sample-summary) (:liveness edn)) "liveness rides through")
                   (is (= (:frames sample-summary) (:frames edn))
                       "frame lists and the operating frame are untouched")
                   (is (= {:rf/default [:cart :route :user]} (:app-db-top-keys edn))
                       "the reserved :rf/xray frame stays out of :app-db-top-keys")
                   (is (= :frame (get-in edn [:registry :basis])) "the registry keeps its basis")
                   (is (= :rf/default (get-in edn [:registry :frame]))))
                 (done))))))

(deftest a-process-basis-continuation-names-no-frame
  ;; With no single operating frame the registry is the process-wide
  ;; registrar, which `list-handlers` reads when it is given no `frame`.
  (async done
    (stub-eval! nil (-> big-summary
                        (update :registry dissoc :frame)
                        (assoc-in [:registry :basis] :process)))
    (-> (orient/orient-tool (fresh-conn) #js {})
        (.then (fn [r]
                 (let [edn (read-result-text r)]
                   (is (= {:tool "list-handlers" :args {:kind "event"}}
                          (:next (first (:truncated edn))))
                       "the process registrar's continuation is list-handlers without a frame"))
                 (done))))))

(deftest orient-overflow-hint-names-only-accepted-arguments
  ;; An overflow still happens under a budget the caller lowered. Its hint
  ;; must name arguments the schemas really accept — orient itself has no
  ;; narrowing argument, so "re-call with narrower args" was advice the
  ;; agent could not follow.
  (async done
    (stub-eval! nil sample-summary)
    (-> (tools/invoke (fresh-conn) "orient" #js {"max-tokens" 20} nil)
        (.then (fn [r]
                 (let [hint  (get-in (read-result-text r) [:rf.mcp/overflow :hint])
                       props (fn [tool]
                               (->> registry/tool-descriptors
                                    (filter #(= tool (:name %)))
                                    first
                                    descriptors/with-budget-knob
                                    :inputSchema :properties keys (map name) set))]
                   (is (string? hint) "a lowered budget overflows with a hint")
                   (is (not (re-find #"narrower args" (str hint)))
                       "no advice to narrow arguments orient does not have")
                   (doseq [[tool arg] [["list-handlers" "kind"] ["list-handlers" "frame"]
                                       ["snapshot" "path"] ["orient" "max-tokens"]]]
                     (is (re-find (re-pattern (str "`" arg "`")) (str hint))
                         (str "the hint names `" arg "`"))
                     (is (contains? (props tool) arg)
                         (str tool " accepts `" arg "`, so the advice can be followed"))))
                 (done))))))
