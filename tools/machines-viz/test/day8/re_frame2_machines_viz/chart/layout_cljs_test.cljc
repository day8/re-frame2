(ns day8.re-frame2-machines-viz.chart.layout-cljs-test
  "The chart's graph parse: definition → nodes, edges and stable ids. xyflow
  and elkjs own positioning, so nothing here carries coordinates."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-machines-viz.chart.layout :as layout]))

;; ---- fixtures ----------------------------------------------------------

(def idle-loading-success
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :success :err :failed}}
             :success {:final? true}
             :failed  {:final? true}}})

(def compound-machine
  {:initial :unauth
   :states  {:unauth        {:on {:login :authenticated}}
             :authenticated {:initial :browsing
                             :states  {:browsing {:on {:checkout :paying}}
                                       :paying   {:on {:done :browsing}}}
                             :on      {:logout :unauth}}}})

(def ingest
  "The Spec 005 `:ingest` shape: three regions, each with a same-named
  `:done` leaf."
  {:type    :parallel
   :regions {:fetch    {:initial :loading
                        :states  {:loading {:on {:loaded :done}}
                                  :done    {:final? true}}}
             :validate {:initial :checking
                        :states  {:checking {:on {:ok :done}}
                                  :done     {:final? true}}}
             :index    {:initial :building
                        :states  {:building {:on {:built :done}}
                                  :done     {:final? true}}}}})

(defn- states-by-path
  "`{path (f node)}` over every node except the root-container frame."
  [nodes f]
  (into {} (map (juxt :path f)) (remove :root-container? nodes)))

(defn- edges-with [edges pred ks]
  (map #(select-keys % ks) (filter pred edges)))

(defn- event= [ev] #(= ev (:event %)))

;; ---- nodes -------------------------------------------------------------

(deftest project-definition-empty-for-nil
  (testing "nil is the empty-state placeholder, not a definition error"
    (is (= {:nodes [] :edges [] :initial-path nil} (layout/project-definition nil)))))

(deftest project-definition-extracts-flat-machine-nodes
  (let [{:keys [nodes initial-path]} (layout/project-definition idle-loading-success)]
    (is (= [:idle] initial-path))
    (is (= {[:idle]    [true false]
            [:loading] [false false]
            [:success] [false true]
            [:failed]  [false true]}
           (states-by-path nodes (juxt :initial? :final?)))
        "every state, with boolean :initial? / :final? flags")))

(deftest project-definition-nests-compound-substates
  (testing "substates carry their compound's node-id as :parent-id (xyflow v12
            `parentId`) and the compound's :initial child is flagged; top-level
            states nest under the root-container frame"
    (let [{:keys [nodes]} (layout/project-definition compound-machine)
          auth (layout/node-id [:authenticated])]
      (is (= {[:unauth]                  [layout/root-container-id true  false]
              [:authenticated]           [layout/root-container-id false true]
              [:authenticated :browsing] [auth                     true  false]
              [:authenticated :paying]   [auth                     false false]}
             (states-by-path nodes (juxt :parent-id :initial? :compound?)))))))

(deftest project-definition-threads-entry-exit-onto-nodes
  (testing ":entry / :exit surface as name strings, and are absent when undeclared"
    (let [{:keys [nodes]} (layout/project-definition
                            {:initial :a
                             :states  {:a {:entry :on-enter :exit :on-leave}
                                       :b {}}})]
      (is (= {[:a] {:entry "on-enter" :exit "on-leave"}
              [:b] {}}
             (states-by-path nodes #(select-keys % [:entry :exit])))))))

;; ---- edges -------------------------------------------------------------

(deftest project-definition-extracts-edges
  (testing "each edge carries xyflow string ids beside the substrate paths and
            the xstate label"
    (let [{:keys [edges]} (layout/project-definition idle-loading-success)
          id layout/node-id]
      (is (= #{{:from-path [:idle] :to-path [:loading] :event :start
                :source (id [:idle]) :target (id [:loading]) :event-label "start"}
               {:from-path [:loading] :to-path [:success] :event :ok
                :source (id [:loading]) :target (id [:success]) :event-label "ok"}
               {:from-path [:loading] :to-path [:failed] :event :err
                :source (id [:loading]) :target (id [:failed]) :event-label "err"}}
             (set (map #(select-keys % [:from-path :to-path :event :source :target :event-label])
                       edges)))))))

(deftest project-definition-emits-event-label-with-guard-and-action
  (let [{:keys [edges]} (layout/project-definition
                          {:initial :idle
                           :states  {:idle    {:on {:submit [{:target :loading :guard :authed? :action :log-it}
                                                             {:target :failed :guard :anon?}]}}
                                     :loading {}
                                     :failed  {}}})]
    (is (= #{"submit [authed?] / log-it" "submit [anon?]"} (set (map :event-label edges))))))

(deftest project-definition-edge-ids-distinct-for-identical-candidates
  (testing "byte-identical candidates get distinct ids via the per-key ordinal"
    (let [{:keys [edges]} (layout/project-definition
                            {:initial :a
                             :states  {:a {:on {:go [{:target :b} {:target :b}]}}
                                       :b {}}})
          ids (map :id (filter (event= :go) edges))]
      (is (= 2 (count ids) (count (set ids)))))))

(deftest project-definition-self-transitions-chart-as-self-loops
  (testing "Spec 005 §Self-transitions: `:target :same-state` resolves to the
            source itself (no phantom :same-state node), and an omitted :target
            is an internal action-only edge that self-anchors rather than drops"
    (let [{:keys [edges]} (layout/project-definition
                            {:initial :a
                             :states  {:a {:on {:ping {:target :same-state}
                                                :tick {:action :inc}}}}})
          a  (layout/node-id [:a])
          ks [:from :to :source :target :internal? :action]]
      (is (= [{:from [:a] :to [:a] :source a :target a :action nil}]
             (edges-with edges (event= :ping) ks)))
      (is (= [{:from [:a] :to [:a] :source a :target a :internal? true :action :inc}]
             (edges-with edges (event= :tick) ks))))))

(deftest project-definition-wildcard-event-label
  (testing "the `:*` arm (Spec 005 §Wildcard) is charted as an 'otherwise' arm"
    (let [{:keys [edges]} (layout/project-definition {:initial :a :states {:a {:on {:* :b}} :b {}}})]
      (is (= ["* (any)"] (map :event-label (filter (event= :*) edges)))))))

(deftest project-definition-nil-forbidden-transition-matches-empty-map
  (testing "Spec 005 §Forbidden transitions: `{:logout nil}` and `{:logout {}}`
            both block fall-through, so both chart the same internal blocking chip"
    (let [logout (fn [spec]
                   (->> (layout/project-definition {:initial :a :states {:a {:on {:logout spec}}}})
                        :edges (filter (event= :logout)) first))]
      (is (true? (:internal? (logout {}))))
      (is (= (dissoc (logout {}) :id) (dissoc (logout nil) :id))))))

;; ---- machine-level (top-level) :on fallback ----------------------------

(deftest project-definition-machine-level-on-fallback
  (testing "a top-level :on charts ONE edge from the synthetic MACHINE-ROOT
            node, not one back-edge per leaf"
    (let [{:keys [nodes edges]} (layout/project-definition
                                  {:initial :a :on {:logout :a} :states {:a {} :b {}}})]
      (is (= [{:machine-level? true :from [] :to [:a] :source layout/machine-root-id}]
             (edges-with edges (event= :logout) [:machine-level? :from :to :source])))
      (is (= [{:id layout/machine-root-id :path []}]
             (map #(select-keys % [:id :path]) (filter :machine-root? nodes)))))))

(deftest project-definition-machine-level-on-targetless-action-only
  (testing "a targetless top-level :on self-anchors on the MACHINE-ROOT chip as
            an internal affordance (XState v5 targetless semantics), not dropped"
    (let [{:keys [edges]} (layout/project-definition
                            {:initial :a :on {:ping {:action :log-ping}} :states {:a {} :b {}}})]
      (is (= [{:machine-level? true :internal? true :from [] :to-path []
               :source layout/machine-root-id :target layout/machine-root-id :action :log-ping}]
             (edges-with edges (event= :ping)
                         [:machine-level? :internal? :from :to-path :source :target :action]))))))

(deftest project-definition-machine-root-same-state-drops-no-phantom-edge
  (testing "a top-level `:same-state` has no concrete state to target, so it is
            dropped, and no MACHINE-ROOT chip is minted for it"
    (let [{:keys [nodes edges]} (layout/project-definition
                                  {:initial :a :states {:a {} :b {}} :on {:noop {:target :same-state}}})]
      (is (empty? edges))
      (is (not-any? :machine-root? nodes)))))

;; ---- node ids ----------------------------------------------------------

(deftest node-id-is-injective-over-hyphen-ns-underscore
  (testing "distinct paths mint distinct ids, and no state id can collide with a
            region container's: a `[^a-zA-Z0-9_]`-collapse would merge
            `:a/b` / `:a-b` / `:a_b` (a React key collision drops a node)"
    (is (apply distinct?
               (concat (map layout/node-id [[:a/b] [:a-b] [:a_b] [:logged-in] [:logged_in]
                                            [:authenticated] [:authenticated :browsing]
                                            [:r1] [:region__foo]])
                       [(layout/region-node-id :r1) (layout/region-node-id :foo)])))))

(deftest region-node-id-is-prefixed-and-distinct
  (testing "region ids are `region__`-prefixed, with segments injectively
            escaped (the `/` ns separator → `_2f`)"
    (is (= "region__r1" (layout/region-node-id :r1)))
    (is (= "region__auth_2fmain" (layout/region-node-id :auth/main)))))

(deftest region-scoped-id-is-injective-across-regions
  (testing "the same state path in two regions mints distinct ids, composed from
            the region container id + the in-region node-id"
    (is (= (str (layout/region-node-id :fetch) "__" (layout/node-id [:done]))
           (layout/region-scoped-id :fetch [:done])))
    (is (not= (layout/region-scoped-id :fetch [:done])
              (layout/region-scoped-id :validate [:done])))
    (is (not= (layout/region-scoped-id :fetch [:done])
              (layout/node-id [:done])))))

;; ---- parallel regions --------------------------------------------------

(deftest project-definition-projects-each-region-as-a-container
  (testing "each region is a compound container under the root frame carrying
            its ordinal; its states nest under it with region-scoped ids, and
            its edges stay inside it. No synthetic root is minted without a
            root :on / :on-done."
    (let [{:keys [nodes edges parallel?]}
          (layout/project-definition
            {:type    :parallel
             :regions {:r1 {:initial :a :states {:a {:on {:go :b}} :b {}}}
                       :r2 {:initial :x :states {:x {:on {:go :y}} :y {}}}}})
          r1 (layout/region-node-id :r1)
          r2 (layout/region-node-id :r2)
          s  layout/region-scoped-id]
      (is (true? parallel?))
      (is (= {r1               [layout/root-container-id :r1 0   true]
              r2               [layout/root-container-id :r2 1   true]
              (s :r1 [:a])     [r1                       :r1 nil false]
              (s :r1 [:b])     [r1                       :r1 nil false]
              (s :r2 [:x])     [r2                       :r2 nil false]
              (s :r2 [:y])     [r2                       :r2 nil false]}
             (into {} (map (juxt :id (juxt :parent-id :region :region-index :compound?)))
                   (remove :root-container? nodes))))
      (is (= #{[(s :r1 [:a]) (s :r1 [:b])] [(s :r2 [:x]) (s :r2 [:y])]}
             (set (map (juxt :source :target) edges)))))))

(deftest project-definition-parallel-same-name-region-states-distinct-nodes
  (testing "three same-named `:done` leaves stay three distinct nodes (one id
            would make xyflow drop two), and each region's edge lands on its own"
    (let [{:keys [nodes edges]} (layout/project-definition ingest)
          s layout/region-scoped-id]
      (is (= #{(s :fetch [:done]) (s :validate [:done]) (s :index [:done])}
             (set (map :id (filter #(= [:done] (:path %)) nodes)))))
      (is (apply distinct? (map :id nodes)))
      (is (= #{[(s :fetch [:loading]) (s :fetch [:done])]
               [(s :validate [:checking]) (s :validate [:done])]
               [(s :index [:building]) (s :index [:done])]}
             (set (map (juxt :source :target) edges))))
      (is (apply distinct? (map :id edges))))))

(deftest project-definition-region-top-level-on-anchors-to-the-region-container
  (testing "a region is a compound state, not a machine: its own top-level :on
            sources from the region container (never a region-scoped
            MACHINE-ROOT with the degenerate id `region__fetch__`), and a
            targetless one self-anchors on that container"
    (let [fetch (layout/region-node-id :fetch)]
      (doseq [[label on expected]
              [["targeted"   {:abort :loading}         {:target (layout/region-scoped-id :fetch [:loading])}]
               ["targetless" {:abort {:action :log}}   {:target fetch :internal? true}]]]
        (let [{:keys [nodes edges]} (layout/project-definition (assoc-in ingest [:regions :fetch :on] on))
              ids (set (map :id nodes))]
          (is (not-any? :machine-root? nodes) label)
          (is (every? #(and (ids (:source %)) (ids (:target %))) edges)
              (str label ": every edge endpoint is a real node"))
          (is (= (merge {:source fetch} expected)
                 (select-keys (first (filter :machine-level? edges)) [:source :target :internal?]))
              label))))))

;; ---- :on-done (XState onDone) completion edges --------------------------

(def checkout-on-done
  "Spec 005 §The done-state signal: when `:flow` reaches its final `:paid`,
  `:flow`'s `:on-done` advances to the SIBLING `:next`."
  {:initial :flow
   :states  {:flow {:initial :collecting
                    :on-done :next
                    :states  {:collecting {:on {:submit :submitting}}
                              :submitting {:on {:ok :paid}}
                              :paid       {:final? true}}}
             :next {:on {:reset [:flow]}}}})

(deftest project-definition-compound-on-done-is-sibling-edge
  (testing "ONE completion edge from the compound to its sibling target, with
            the reserved done event and the compound as the done-state node"
    (is (= [{:from [:flow] :to [:next] :event :rf.machine/done :done-path [:flow]
             :event-label "✓ done"}]
           (edges-with (:edges (layout/project-definition checkout-on-done)) :on-done?
                       [:from :to :event :done-path :event-label :internal?])))))

(deftest project-definition-compound-on-done-guarded-candidate-vector
  (let [{:keys [edges]} (layout/project-definition
                          {:initial :flow
                           :states  {:flow {:initial :work
                                            :on-done [{:target :ok-next :guard :ok?}
                                                      {:target :err-next :guard :err?}]
                                            :states  {:work {:on {:finish :done}}
                                                      :done {:final? true}}}
                                     :ok-next  {}
                                     :err-next {}}})]
    (is (= #{{:from [:flow] :to [:ok-next] :guard :ok?}
             {:from [:flow] :to [:err-next] :guard :err?}}
           (set (edges-with edges :on-done? [:from :to :guard]))))))

(deftest project-definition-parallel-root-on-done-is-terminal-affordance
  (testing "a parallel root's :on-done is action-only, so it charts as a
            terminal affordance self-anchored on a synthetic parallel-root node"
    (let [{:keys [nodes edges]} (layout/project-definition
                                  (assoc ingest :on-done {:action :announce}))
          roots (filter :parallel-root? nodes)
          root  (:id (first roots))]
      (is (= 1 (count roots)))
      (is (= [{:parallel-root? true :internal? true :source root :target root
               :action :announce :event-label "✓ done / announce"}]
             (edges-with edges :on-done?
                         [:parallel-root? :internal? :source :target :action :event-label]))))))

(deftest project-definition-region-on-done
  (testing "a region's own :on-done (Spec 005 §Parallel :on-done) sources from
            the region container: a target lands on the region's own state, an
            action-only one self-anchors on the container"
    (let [a-rid (layout/region-node-id :a)]
      (doseq [[label on-done expected]
              [["targeted"    :a1            {:target (layout/region-scoped-id :a [:a1])
                                              :event :rf.machine/done :done-path [:a]
                                              :event-label "✓ done"}]
               ["action-only" {:action :log} {:target a-rid :internal? true :action :log}]]]
        (let [{:keys [edges]} (layout/project-definition
                                {:type    :parallel
                                 :regions {:a {:initial :a1
                                               :on-done on-done
                                               :states  {:a1 {:on {:go :a2}}
                                                         :a2 {:final? true}}}
                                           :b {:initial :b1 :states {:b1 {}}}}})]
          (is (= [(merge {:source a-rid} expected)]
                 (edges-with edges :on-done? (into [:source :internal?] (keys expected))))
              label))))))

;; ---- root parallel :on / :after -----------------------------------------
;;
;; A parallel machine's own :on / :after is the ancestor fallback for its
;; regions (Spec 005 §Root parallel `:on`): each region-qualified target
;; charts as an edge from the MACHINE-ROOT chip into the region-scoped node.

(def two-region
  {:a {:initial :one :states {:one {} :two {}}}
   :b {:initial :one :states {:one {} :two {}}}})

(deftest project-definition-parallel-root-on-single-region-target
  (let [{:keys [nodes edges]} (layout/project-definition
                                {:type :parallel :on {:one {:target [:a :two]}} :regions two-region})]
    (is (= [{:machine-level? true :from-path [] :to-path [:a :two] :event :one
             :source layout/machine-root-id :target (layout/region-scoped-id :a [:two])}]
           (edges-with edges :parallel-root-on?
                       [:machine-level? :from-path :to-path :event :source :target :internal?])))
    (is (= [layout/machine-root-id] (map :id (filter :machine-root? nodes)))
        "the MACHINE-ROOT chip anchors it")))

(deftest project-definition-parallel-root-on-multi-region-target
  (testing "one edge per region-qualified target; the untargeted region :c gets none"
    (let [{:keys [edges]} (layout/project-definition
                            {:type    :parallel
                             :on      {:advance {:target [[:a :x] [:b :y]] :action :bump}}
                             :regions {:a {:initial :one :states {:one {} :x {}}}
                                       :b {:initial :one :states {:one {} :y {}}}
                                       :c {:initial :one :states {:one {}}}}})]
      (is (= #{{:to-path [:a :x] :source layout/machine-root-id
                :target (layout/region-scoped-id :a [:x]) :action :bump}
               {:to-path [:b :y] :source layout/machine-root-id
                :target (layout/region-scoped-id :b [:y]) :action :bump}}
             (set (edges-with edges :parallel-root-on? [:to-path :source :target :action])))))))

(deftest project-definition-parallel-root-on-targetless-action-only
  (let [{:keys [edges]} (layout/project-definition
                          {:type :parallel :on {:ping {:action :log-ping}} :regions two-region})]
    (is (= [{:internal? true :to-path [] :source layout/machine-root-id
             :target layout/machine-root-id :action :log-ping}]
           (edges-with edges :parallel-root-on? [:internal? :to-path :source :target :action]))
        "self-anchored on the MACHINE-ROOT chip; moves no region")))

(deftest project-definition-parallel-root-after-single-region-target
  (testing "a root :after rides the root :on path and carries its delay"
    (let [{:keys [edges]} (layout/project-definition
                            {:type :parallel :after {500 {:target [:a :two]}} :regions two-region})]
      (is (= [{:machine-level? true :parallel-root-on? true :after 500 :from-path []
               :to-path [:a :two] :source layout/machine-root-id
               :target (layout/region-scoped-id :a [:two])}]
             (edges-with edges :parallel-root-after?
                         [:machine-level? :parallel-root-on? :after :from-path :to-path
                          :source :target :internal?]))))))

(deftest project-definition-parallel-root-on-and-after-coexist
  (testing "a root :on and a root :after to the same target are distinct edges"
    (let [{:keys [edges]} (layout/project-definition
                            {:type    :parallel
                             :on      {:go {:target [:a :two]}}
                             :after   {1000 {:target [:a :two]}}
                             :regions two-region})
          root-edges (filter :parallel-root-on? edges)]
      (is (= [false true] (sort (map (comp boolean :parallel-root-after?) root-edges))))
      (is (apply distinct? (map :id root-edges))))))

;; ---- definition errors --------------------------------------------------

(deftest project-definition-error-flag-needs-final
  (testing ":error? is meaningful only on a :final? state, so the machine is
            rejected before graph construction with the canonical defect"
    (let [result (layout/project-definition {:initial :a
                                             :states  {:a {:error? true :on {:go :b}}
                                                       :b {}}})]
      (is (= [[] [] :rf.error/machine-error-flag-without-final]
             ((juxt :nodes :edges #(get-in % [:definition-error :defect :category])) result))))))

(deftest project-definition-definition-error-is-content-free-and-rejection-only
  (testing "the :definition-error carries cardinality, never material read off the
            definition: a key COUNT and a defect DEPTH, no :keys and no :path"
    (let [{:keys [definition-error]} (layout/project-definition
                                       {:initial :outer :states {:outer {:states {:inner {}}}}})]
      (is (nil? (:keys definition-error)))
      (is (nil? (get-in definition-error [:defect :path])))
      (is (integer? (:count definition-error)))
      (is (integer? (get-in definition-error [:defect :depth]))))))

;; ---- highlight ids -----------------------------------------------------

(deftest highlight-id-resolves-each-state-arm
  (testing "the single-active resolver: a keyword or a path to its leaf id; nil
            and a region-map (not single-active) to nil"
    (doseq [[state expected]
            [[:authing                       (layout/node-id [:authing])]
             [[:authenticated :browsing]     (layout/node-id [:authenticated :browsing])]
             [nil                            nil]
             [{:data :loading :form :neutral} nil]]]
      (is (= expected (layout/highlight-id state)) (pr-str state)))))

(deftest highlight-ids-resolves-each-state-arm
  (testing "every `:state` arm resolves to a SET of active-leaf ids; a region-map
            to one region-scoped leaf per region (the ids the parse mints), a
            compound region value to its deepest leaf, nil to the empty set"
    (doseq [[state expected]
            [[:authing
              #{(layout/node-id [:authing])}]
             [[:authenticated :browsing]
              #{(layout/node-id [:authenticated :browsing])}]
             [{:data :loading :form :neutral :mode :active}
              #{(layout/region-scoped-id :data [:loading])
                (layout/region-scoped-id :form [:neutral])
                (layout/region-scoped-id :mode [:active])}]
             [{:auth [:authenticated :dashboard] :lifecycle :idle}
              #{(layout/region-scoped-id :auth [:authenticated :dashboard])
                (layout/region-scoped-id :lifecycle [:idle])}]
             [nil #{}]]]
      (is (= expected (layout/highlight-ids state)) (pr-str state)))))

;; ---- labels -------------------------------------------------------------

(deftest edge-label-renders-each-segment-form
  (testing "`event [guard] / action`, each bracket only when its segment is
            present; `:after` and `:always` substitute their glyphs; a
            namespaced event keeps its namespace"
    (doseq [[edge expected]
            [[{:event :submit}                                                     "submit"]
             [{:event :submit :guard :authed? :action :log-it}                     "submit [authed?] / log-it"]
             [{:event :after-1500 :after 1500 :guard :timeout? :action :cleanup}   "⌚ 1500ms [timeout?] / cleanup"]
             [{:event :always :always? true :guard :ready?}                        "∞ [ready?]"]
             [{:event :auth/submit}                                                "auth/submit"]]]
      (is (= expected (layout/edge-label edge)) (pr-str edge)))))

(deftest event-line-omits-the-action-segment
  (testing "the visible line is event + guard; the action renders as a pill"
    (is (= "submit [authed?]" (layout/event-line {:event :submit :guard :authed? :action :log-it})))))

(deftest event-segment-after-iso-duration-renders-milliseconds
  (is (= "⌚ 1000ms" (layout/event-segment {:after "PT1S"}))))

(deftest name-of-renders-each-arm
  (testing "a fn renders its :name meta or \"fn\", never `#object[Function]`"
    (doseq [[value expected]
            [[:auth/admin?                               "auth/admin?"]
             [(with-meta (fn [_] true) {:name 'do-thing}) "do-thing"]
             [(fn [_] true)                              "fn"]
             ["raw"                                      "raw"]]]
      (is (= expected (layout/name-of value)) (pr-str expected)))))

;; ---- consumer-attachment requirements -----------------------------------
;;
;; Spec 005 §Consumer attachment: a named guard / action declares
;; `:rf.cofx/requires` on its registry entry; the chart surfaces the declared
;; ids, never the `:fn`.

(def cofx-machine
  {:initial :idle
   :guards  {:within-window? {:rf.cofx/requires [:rf/time-ms] :fn (fn [_] true)}
             :always-ok?     (fn [_] true)}
   :actions {:schedule-retry {:rf.cofx/requires [:payment/retry-jitter-ms] :fn (fn [_] nil)}
             :stamp-started  {:rf.cofx/requires [:rf/time-ms] :fn (fn [_] nil)}
             :stamp-ended    {:rf.cofx/requires [[:ui/local-theme "theme"]] :fn (fn [_] nil)}
             :log-it         (fn [_] nil)}
   :states  {:idle {:entry :stamp-started
                    :exit  :stamp-ended
                    :on    {:go   {:target :busy :guard :within-window? :action :schedule-retry}
                            :noop {:target :busy :guard :always-ok? :action :log-it}}}
             :busy {}}})

(deftest cofx-requires-on-edges-and-nodes
  (testing "declared ids surface as display strings (an `[id arg]` pair as
            `id(arg)`); a bare-fn guard / action declares nothing"
    (let [{:keys [edges nodes]} (layout/project-definition cofx-machine)]
      (is (= {:go   [["rf/time-ms"] ["payment/retry-jitter-ms"]]
              :noop [nil nil]}
             (into {} (map (juxt :event (juxt :guard-requires :action-requires))) edges)))
      (is (= [["rf/time-ms"] ["ui/local-theme(\"theme\")"]]
             ((juxt :entry-requires :exit-requires)
              (first (filter #(= [:idle] (:path %)) nodes))))))))

(deftest cofx-requires-machine-scoped-across-parallel-regions
  (testing "a region guard resolves against the machine-level :guards (XState v5 scoping)"
    (let [{:keys [edges]} (layout/project-definition
                            {:type    :parallel
                             :guards  {:ready? {:rf.cofx/requires [:rf/time-ms] :fn (fn [_] true)}}
                             :regions {:a {:initial :one
                                           :states  {:one {:on {:go {:target :two :guard :ready?}}}
                                                     :two {}}}
                                       :b {:initial :x :states {:x {}}}}})]
      (is (= [["rf/time-ms"]] (map :guard-requires (filter (event= :go) edges)))))))

(deftest raw-node-at-resolves-each-node-in-its-own-region
  (testing "two regions sharing an in-region path each resolve to their OWN raw
            node; a flat or compound node resolves in the top-level :states; a
            synthetic empty-path node resolves to nil"
    (let [par  {:type    :parallel
                :regions {:audio {:initial :active :states {:active {:entry :enter-a} :off {}}}
                          :video {:initial :active :states {:active {:entry :enter-b} :off {}}}}}
          flat {:initial :idle
                :states  {:idle {:entry :log}
                          :busy {:initial :step1 :states {:step1 {:exit :cleanup}}}}}]
      (doseq [[definition node expected]
              [[par  {:path [:active] :region :audio} {:entry :enter-a}]
               [par  {:path [:active] :region :video} {:entry :enter-b}]
               [flat {:path [:idle]}                  {:entry :log}]
               [flat {:path [:busy :step1]}           {:exit :cleanup}]
               [flat {:path []}                       nil]]]
        (is (= expected (layout/raw-node-at definition node)) (pr-str node))))))
