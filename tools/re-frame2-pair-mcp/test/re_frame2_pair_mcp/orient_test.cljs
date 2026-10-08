(ns re-frame2-pair-mcp.orient-test
  "Unit tests for the orient tool — the first-contact app-shape summary.
  The tool emits `(re-frame2-pair.runtime/orient)`, caps the runtime's id
  lists to examples with a continuation per list, and echoes the resolved
  `:build`. The blank-eval degrade is pinned by the conformance corpus."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.cache :as cache]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.descriptors :as descriptors]
            [re-frame2-pair-mcp.tools.orient :as orient]
            [re-frame2-pair-mcp.tools.registry :as registry]))

;; Stubs are installed by bare `set!` and restored by the fixture, so a late
;; restore cannot clobber a neighbouring test's stub.
(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:before (fn [] (cache/clear!))
   :after  (fn [] (set! nrepl/cljs-eval-value pristine-eval) (cache/clear!))})

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(def ^:private read-result-text tu/extract-edn)

(def ^:private sample-summary
  {:ok? true
   :liveness {:debug-enabled? true :frame-count 2 :app-frame-count 1
              :ambiguous-frame? false :runtime-instance-id "abc"}
   :frames   {:all [:rf/default :rf/xray] :app [:rf/default] :operating :rf/default}
   :app-db-top-keys {:rf/default [:cart :route :user]}
   :registry {:counts {:event 14 :sub 9 :fx 3 :cofx 1 :view 6
                       :frame 2 :route 4 :flow 0 :head 0 :error-projector 0
                       :resource 3 :mutation 2 :resource-scope 1}
              :events [:cart/add :cart/checkout]
              :subs   [:cart/total :current-user]
              :fx     [:http :navigate :persist]}
   :machines [:checkout]})

(defn- stub-eval!
  "Answer the preload probe directly; record every other form into
  `captured*` (may be nil) and answer it with `canned`."
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

(deftest summary-shape-rides-through
  ;; Lists within the example limit ride through unchanged.
  (async done
    (let [captured (atom nil)]
      (stub-eval! captured sample-summary)
      (-> (orient/orient-tool (fresh-conn) #js {})
          (.then (fn [r]
                   (is (= "(re-frame2-pair.runtime/orient)" @captured))
                   (is (= (assoc sample-summary :build :app) (read-result-text r)))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; A real registry orients under the DEFAULT cap: shipped whole, the sorted
;; id vectors of a several-hundred-handler app overflow the budget before
;; the frame / count summary the first read exists for.
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

(deftest a-registry-of-several-hundred-ids-orients-under-the-default-cap
  (async done
    (is (> (quot (count (pr-str big-summary)) 4) 5000)
        "control: shipped whole, this summary's EDN alone is over the default 5000-token cap")
    (stub-eval! nil big-summary)
    (-> (tools/invoke (fresh-conn) "orient" #js {} nil)
        (.then (fn [r]
                 ;; Every kind keeps its true count; each capped list carries its
                 ;; first 20 sorted ids and names the call that returns the rest
                 ;; of THAT registry; every other slot is untouched.
                 (let [capped (reduce (fn [s p] (update-in s p #(vec (take 20 %))))
                                      big-summary
                                      [[:registry :events] [:registry :subs] [:registry :fx] [:machines]])]
                   (is (= (assoc capped :truncated
                                 [{:slot  [:registry :events] :shown 20 :total 439
                                   :next  {:tool "list-handlers" :args {:kind "event" :frame ":rf/default"}}}
                                  {:slot  [:registry :subs] :shown 20 :total 285
                                   :next  {:tool "list-handlers" :args {:kind "sub" :frame ":rf/default"}}}
                                  {:slot  [:registry :fx] :shown 20 :total 78
                                   :next  {:tool "list-handlers" :args {:kind "fx" :frame ":rf/default"}}}
                                  {:slot  [:machines] :shown 20 :total 31
                                   :next  {:tool "list-handlers" :args {:kind "machine"}}}])
                          (select-keys (read-result-text r) (conj (keys big-summary) :truncated)))))
                 (done))))))

(deftest a-process-basis-continuation-names-no-frame
  ;; With no single operating frame the registry is the process-wide
  ;; registrar, which `list-handlers` reads when given no `frame`.
  (is (= {:tool "list-handlers" :args {:kind "event"}}
         (-> big-summary
             (update :registry dissoc :frame)
             (assoc-in [:registry :basis] :process)
             orient/bound-summary
             :truncated first :next))))

(deftest orient-overflow-hint-names-only-accepted-arguments
  ;; orient has no narrowing argument, so an overflow under a lowered budget
  ;; must point at arguments the schemas really accept.
  (async done
    (stub-eval! nil sample-summary)
    (-> (tools/invoke (fresh-conn) "orient" #js {"max-tokens" 20} nil)
        (.then (fn [r]
                 (let [hint  (str (get-in (read-result-text r) [:rf.mcp/overflow :hint]))
                       props (fn [tool]
                               (->> registry/tool-descriptors
                                    (filter #(= tool (:name %)))
                                    first
                                    descriptors/with-budget-knob
                                    :inputSchema :properties keys (map name) set))]
                   (doseq [[tool arg] [["list-handlers" "kind"] ["list-handlers" "frame"]
                                       ["snapshot" "path"] ["orient" "max-tokens"]]]
                     (is (re-find (re-pattern (str "`" arg "`")) hint)
                         (str "the hint names `" arg "`"))
                     (is (contains? (props tool) arg)
                         (str tool " accepts `" arg "`, so the advice can be followed"))))
                 (done))))))
