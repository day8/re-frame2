(ns day8.re-frame2-machines-viz.chart.projection-cljs-test
  "Pure-data tests for the MachineChart projection layer: the parsed graph →
  xyflow `:nodes` / `:edges` projector and the elk.js children / edges feed.
  Fixtures run through `chart.layout/project-definition`, so the projector
  sees the parser's real shape."
  (:require #?(:clj  [clojure.test :refer [deftest is]]
               :cljs [cljs.test    :refer-macros [deftest is]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.projection :as projection]
            [day8.re-frame2-machines-viz.theme.tokens :as tokens]
            [day8.re-frame2-machines-viz.visual-constants :as vc]))

;; ---- fixtures ----------------------------------------------------------

(def idle-loading
  "A plain `:on`, an `:after` timer and a guarded `:always` in one flat machine."
  {:initial :idle
   :states  {:idle    {:on    {:start :loading}}
             :loading {:after {1000 {:target :timeout}}
                       :always {:target :ready :guard :loaded?}}
             :ready   {:final? true}
             :timeout {:final? true}}})

(def compound-machine
  {:initial :unauth
   :states  {:unauth        {:on {:login :authenticated}}
             :authenticated {:initial :browsing
                             :states  {:browsing {:on {:checkout :paying}}
                                       :paying   {:on {:done :browsing}}}
                             :on      {:logout :unauth}}}})

(def parallel-machine
  {:type    :parallel
   :regions {:audio {:initial :muted
                     :states  {:muted   {:on {:unmute :playing}}
                               :playing {:on {:mute :muted}}}}
             :video {:initial :hidden
                     :states  {:hidden  {:on {:show :shown}}
                               :shown   {:on {:hide :hidden}}}}}})

(def nested-compound-machine
  {:initial :outer
   :states  {:outer {:initial :mid
                     :states  {:mid {:initial :leaf
                                     :states  {:leaf  {:on {:go :other}}
                                               :other {}}}}}}})

(def self-loop-machine
  "Two transitions leave `:idle` under distinct triggers, one a self-loop."
  {:initial :idle
   :states  {:idle {:on {:ping :idle :go :busy}}
             :busy {:on {:done :idle}}}})

(def internal-self-machine
  {:initial :a
   :states  {:a {:on {:tick {:action :inc}}}}})

(def wildcard-machine
  {:initial :a
   :states  {:a {:on {:start :b :* :err}}
             :b {}
             :err {}}})

(def machine-level-on-machine
  {:initial :a :on {:logout :a} :states {:a {} :b {}}})

(def door-cyclic-machine
  "Cyclic, with a root `:on` fallback, a guarded exit and an internal one."
  {:initial :locked
   :on      {:door/audit {:target :locked :action :record-audit}}
   :states  {:locked   {:on {:door/insert-coin :closed}}
             :closed   {:on {:door/push :open}}
             :open     {:on {:door/close   {:target :closed :guard :may-close?}
                             :door/hold    {:action :hold-open}
                             :door/trip    {:target :alarming :action :enter-alarm}}}
             :alarming {:on {:door/reset :locked}}}})

(def shallow-history-machine
  {:initial :off
   :states  {:off    {:on {:resume [:player :hist]}}
             :player {:initial :stopped
                      :states  {:stopped {:on {:play :playing}}
                                :playing {:on {:stop :stopped}}
                                :hist    {:type :history :deep? false}}
                      :on      {:power-off :off}}}})

(def deep-history-machine
  (assoc-in shallow-history-machine [:states :player :states :hist]
            {:type :history :deep? true :default-target :playing}))

(def success-and-error-finals
  {:initial :running
   :states  {:running {:on {:ok :ok :boom :boom}}
             :ok      {:final? true}
             :boom    {:final? true :error? true}}})

(def checkout-on-done
  "A compound `:flow` whose `:on-done` advances to `:next` once `:paid` is reached."
  {:initial :flow
   :states  {:flow {:initial :collecting
                    :on-done :next
                    :states  {:collecting {:on {:submit :submitting}}
                              :submitting {:on {:ok :paid}}
                              :paid       {:final? true}}}
             :next {:on {:reset [:flow]}}}})

(def ingest-on-done
  "A parallel root whose `:on-done` is action-only."
  {:type    :parallel
   :on-done {:action :announce}
   :regions {:fetch    {:initial :loading :states {:loading {:on {:loaded :done}} :done {:final? true}}}
             :validate {:initial :checking :states {:checking {:on {:ok :done}} :done {:final? true}}}}})

(def spawn-on-error-machine
  {:initial :idle
   :states  {:idle    {:on {:go :working}}
             :working {:spawn {:machine-id :child :on-error :failed}}
             :failed  {:final? true}}})

(def ^:private gate-fork-machine
  "`:gate/check` forks from `:idle` over a guarded candidate vector (first
  guard to pass wins, else the fallback); `:gate/set` leaves the same state
  under a different trigger, so it is not part of the fork."
  {:initial :idle
   :data    {:level 0}
   :states  {:idle     {:on {:gate/set   {:action :set-level}
                             :gate/check [{:guard :gate-high? :target :high}
                                          {:guard :gate-low?  :target :low}
                                          {:target :rejected}]}}
             :low      {:on {:gate/reset :idle}}
             :high     {:on {:gate/reset :idle}}
             :rejected {:on {:gate/reset :idle}}}})

;; ---- helpers -----------------------------------------------------------

(defn- edge-by-id [graph id]
  (first (filter #(= id (:id %)) (:edges graph))))

(defn- node-by-id [graph id]
  (first (filter #(= id (:id %)) (:nodes graph))))

(defn- projected-node
  "The xyflow node projected for the first parsed node of `machine` matching `pick`."
  [machine pick opts]
  (let [parsed (layout/project-definition machine)]
    (node-by-id (projection/xyflow-graph parsed {} opts)
                (:id (first (filter pick (:nodes parsed)))))))

(defn- root-children
  "The children of the root-container frame, the sole top-level elk child."
  [elk-children]
  (:children (first elk-children)))

(defn- event-node-for [graph parsed-edge-id]
  (node-by-id graph (projection/event-node-id {:id parsed-edge-id})))

(defn- inbound-edge-for [graph parsed-edge-id]
  (edge-by-id graph (str parsed-edge-id "__in")))

(defn- outbound-edge-for [graph parsed-edge-id]
  (edge-by-id graph (str parsed-edge-id "__out")))

(defn- flags
  "`{id value}` of the `:data` flag `k` over the items of `xs` that carry it."
  [xs k]
  (into {} (keep #(when (contains? (:data %) k) [(:id %) (get-in % [:data k])])) xs))

(defn- only-true
  "What `flags` reads when exactly `ids` are set: true for them, false for the rest."
  [fs ids]
  (merge (zipmap (keys fs) (repeat false)) (zipmap ids (repeat true))))

;; ---- every edge, node :type and click targets ----------------------------

(deftest every-projected-edge-is-an-arrowclosed-transition
  (let [edges (:edges (projection/xyflow-graph (layout/project-definition gate-fork-machine) {} {}))]
    (is (seq edges))
    (is (every? #(= "transition" (:type %)) edges))
    (is (every? #(= "arrowclosed" (get-in % [:markerEnd :type])) edges))
    (is (every? #(string? (get-in % [:markerEnd :color])) edges))))

(deftest xyflow-graph-node-type-dispatch
  (doseq [[machine pick expected]
          [[idle-loading             #(= [:idle] (:path %))          "state"]
           [compound-machine         #(= [:authenticated] (:path %)) "compound"]
           [parallel-machine         :region?                        "parallel-region"]
           [idle-loading             :root-container?                "root-container"]
           [machine-level-on-machine :machine-root?                  "machine-root"]
           [ingest-on-done           :parallel-root?                 "machine-root"]
           [shallow-history-machine  :history?                       "history-marker"]]]
    (is (= expected (:type (projected-node machine pick {}))) expected)))

(deftest xyflow-graph-threads-on-click-to-leaf-and-compound-only
  ;; Synthetic chips, region containers and history markers are not states a
  ;; click can select.
  (let [cb (fn [_path] :clicked)]
    (doseq [[machine pick clickable?]
            [[compound-machine         #(= [:unauth] (:path %))        true]
             [compound-machine         #(= [:authenticated] (:path %)) true]
             [parallel-machine         :region?                        false]
             [machine-level-on-machine :machine-root?                  false]
             [ingest-on-done           :parallel-root?                 false]
             [shallow-history-machine  :history?                       false]]
            :let [data (:data (projected-node machine pick {:on-state-click cb}))]]
      (if clickable?
        (is (= cb (:onClick data)))
        (is (not (contains? data :onClick)))))))

(deftest xyflow-graph-history-marker-carries-deep-flag
  (doseq [[machine deep?] [[shallow-history-machine false] [deep-history-machine true]]]
    (is (= deep? (:deep (:data (projected-node machine :history? {})))))))

(deftest project-definition-history-target-keeps-incoming-edge
  (is (some #(= (layout/node-id [:player :hist]) (:target %))
            (:edges (layout/project-definition shallow-history-machine)))))

(deftest xyflow-graph-threads-initial-final-and-error-final-flags
  (let [graph (projection/xyflow-graph (layout/project-definition success-and-error-finals) {} {})]
    (is (= {:running {:initial true  :final false :errorFinal false}
            :ok      {:initial false :final true  :errorFinal false}
            :boom    {:initial false :final true  :errorFinal true}}
           (into {} (map (fn [s] [s (select-keys (:data (node-by-id graph (layout/node-id [s])))
                                                 [:initial :final :errorFinal])]))
                 [:running :ok :boom])))))

;; ---- parentId / extent nesting -------------------------------------------
;;
;; xyflow v12 reads `parentId`; it silently ignores the pre-v12 `parentNode`,
;; which would leave a child at root-level absolute coords outside its box.

(deftest xyflow-graph-region-children-wire-parent-id
  (let [graph (projection/xyflow-graph (layout/project-definition parallel-machine) {} {})
        nest  #(select-keys (node-by-id graph %) [:parentId :extent])]
    (is (= {:parentId (layout/region-node-id :audio) :extent "parent"}
           (nest (layout/region-scoped-id :audio [:muted]))))
    (is (= {:parentId layout/root-container-id :extent "parent"}
           (nest (layout/region-node-id :audio))))))

(deftest xyflow-graph-compound-children-wire-parent-id
  (let [graph (projection/xyflow-graph (layout/project-definition compound-machine) {} {})]
    (is (= {:parentId (layout/node-id [:authenticated]) :extent "parent"}
           (select-keys (node-by-id graph (layout/node-id [:authenticated :browsing]))
                        [:parentId :extent :parentNode])))))

(deftest xyflow-graph-flat-state-nests-under-root-container
  (let [graph (projection/xyflow-graph (layout/project-definition idle-loading) {} {})]
    (is (nil? (:parentId (node-by-id graph layout/root-container-id))))
    (is (= {:parentId layout/root-container-id :extent "parent"}
           (select-keys (node-by-id graph (layout/node-id [:idle])) [:parentId :extent])))))

(deftest xyflow-graph-region-children-do-not-emit-pre-v12-parent-node
  (let [graph (projection/xyflow-graph (layout/project-definition parallel-machine) {} {})]
    (is (not-any? #(contains? % :parentNode) (:nodes graph)))))

(defn- elk-parent-of
  "`{child-id parent-id}` read off the NESTED `->elk-children` tree — ELK's
  own ancestry, independent of the xyflow projection under test."
  ([children] (elk-parent-of children nil))
  ([children parent]
   (reduce (fn [acc c]
             (cond-> (merge acc (elk-parent-of (:children c) (:id c)))
               parent (assoc (:id c) parent)))
           {}
           children)))

(defn- chain-sum
  "The absolute `{:x :y}` of `id`: its own position plus every ancestor's,
  walking `parent-of` out to the root."
  [pos-of parent-of id]
  (loop [id id acc {:x 0 :y 0}]
    (if (nil? id)
      acc
      (let [p (pos-of id)]
        (recur (parent-of id) {:x (+ (:x acc) (:x p)) :y (+ (:y acc) (:y p))})))))

(deftest xyflow-graph-parallel-absolute-positions-match-elk-ancestry
  ;; The frame sits at a non-zero origin, so a node whose xyflow `parentId`
  ;; chain dropped a parent lands short of its ELK position.
  (let [parsed    (layout/project-definition parallel-machine)
        elk-par   (elk-parent-of (projection/->elk-children parsed))
        elk-ids   (into (set (keys elk-par)) (vals elk-par))
        positions (into {}
                        (map-indexed (fn [i id]
                                       [id (if (= id layout/root-container-id)
                                             {:x 100 :y 200 :width 900 :height 700}
                                             {:x (+ 10 i) :y (+ 20 i)
                                              :width 152 :height 58})]))
                        (sort elk-ids))
        graph     (projection/xyflow-graph parsed positions {})
        by-id     (into {} (map (juxt :id identity)) (:nodes graph))
        xy-abs    #(chain-sum (comp :position by-id) (comp :parentId by-id) %)
        elk-abs   #(chain-sum positions elk-par %)
        markers   (filter #(= "initial-marker" (:type %)) (:nodes graph))]
    (is (some #(str/starts-with? % "__rf2_event_") elk-ids) "the fixture lays out event-nodes too")
    (doseq [id elk-ids]
      (is (= (elk-abs id) (xy-abs id)) id))
    (is (seq markers))
    (doseq [m markers]
      (is (= (merge-with + (:position m) (elk-abs (:parentId m))) (xy-abs (:id m)))
          (:id m)))))

(deftest xyflow-graph-nested-containers-parent-first-on-reversed-input
  ;; xyflow needs each `parentId` target earlier in the nodes array, whatever
  ;; order the input carries.
  (let [reversed (update (layout/project-definition nested-compound-machine) :nodes (comp vec reverse))
        nodes    (:nodes (projection/xyflow-graph reversed {} {}))
        index    (into {} (map-indexed (fn [i n] [(:id n) i])) nodes)]
    (doseq [n nodes :when (:parentId n)]
      (is (< (index (:parentId n)) (index (:id n))) (:id n)))))

;; ---- highlight flags -----------------------------------------------------

(deftest xyflow-graph-from-and-to-highlight-flags
  (let [from  (layout/node-id [:idle])
        to    (layout/node-id [:loading])
        graph (projection/xyflow-graph (layout/project-definition idle-loading) {}
                                       {:from-highlight-id from :to-highlight-id to})]
    (is (= [{:fromHighlight true :toHighlight false} {:fromHighlight false :toHighlight true}]
           (map #(select-keys (:data (node-by-id graph %)) [:fromHighlight :toHighlight])
                [from to])))))

(deftest xyflow-graph-sim-flag-is-active-and-sim
  (let [parsed (layout/project-definition idle-loading)
        hi     (layout/node-id [:loading])
        sim    (fn [sim? id]
                 (:sim (:data (node-by-id (projection/xyflow-graph parsed {} {:highlight-ids #{hi}
                                                                              :sim?          sim?})
                                          id))))]
    (is (= [true false false] [(sim true hi) (sim false hi) (sim true (layout/node-id [:idle]))]))))

(deftest xyflow-graph-active-is-the-highlighted-leaves-and-their-containers
  ;; A container is active when a leaf below it is; the root-container frame,
  ;; an ancestor of everything, never is.
  (let [rs   layout/region-scoped-id
        leaf (layout/node-id [:outer :mid :leaf])]
    (doseq [[machine highlight active]
            [[parallel-machine #{(rs :audio [:playing]) (rs :video [:shown])}
              #{(rs :audio [:playing]) (rs :video [:shown])
                (layout/region-node-id :audio) (layout/region-node-id :video)}]
             [parallel-machine #{(rs :audio [:playing])}
              #{(rs :audio [:playing]) (layout/region-node-id :audio)}]
             [parallel-machine #{} #{}]
             [nested-compound-machine #{leaf}
              #{leaf (layout/node-id [:outer :mid]) (layout/node-id [:outer])}]
             [idle-loading #{(layout/node-id [:loading])} #{(layout/node-id [:loading])}]]
            :let [graph (projection/xyflow-graph (layout/project-definition machine) {}
                                                 {:highlight-ids highlight})
                  fs    (flags (:nodes graph) :active)]]
      (is (= (only-true fs active) fs) (pr-str highlight)))))

(deftest xyflow-graph-focuses-the-lens-transition-only-with-both-ends
  (let [parsed (layout/project-definition idle-loading)
        from   (layout/node-id [:idle])
        id     (:id (first (filter #(= from (:source %)) (:edges parsed))))]
    (doseq [[lens focused] [[{:from-highlight-id from :to-highlight-id (layout/node-id [:loading])}
                             [(str id "__in") (str id "__out")]]
                            [{:from-highlight-id from} []]]
            :let [fs (flags (:edges (projection/xyflow-graph parsed {} lens)) :focused)]]
      (is (= (only-true fs focused) fs)))))

(deftest xyflow-graph-marker-colour-tracks-active-and-fired
  ;; The arrowhead takes its stroke's state hue, so it reads as part of the line.
  (let [parsed (layout/project-definition idle-loading)
        id     (:id (first (filter #(= :start (:event %)) (:edges parsed))))
        colour #(get-in (outbound-edge-for (projection/xyflow-graph parsed {} %) id)
                        [:markerEnd :color])]
    (doseq [opts [{:highlight-ids #{(layout/node-id [:idle])}} {:fired-edge-ids #{id}}]]
      (is (not= (colour {}) (colour opts)) (pr-str opts)))))

;; ---- threaded theme + density ---------------------------------------------

(deftest xyflow-graph-threads-palette-and-chart-onto-every-node-and-edge
  ;; xyflow invokes the renderers outside the render's dynamic scope, so they
  ;; read theme and density off `:data`.
  (let [parsed (layout/project-definition gate-fork-machine)
        light  (tokens/chart-tokens tokens/light-palette)]
    (doseq [[opts palette chart] [[{:palette light :chart vc/chart-compact} light vc/chart-compact]
                                  [{} (tokens/chart-tokens tokens/dark-palette) vc/chart-regular]]
            :let [graph (projection/xyflow-graph parsed {} opts)
                  items (concat (:nodes graph) (:edges graph))]]
      (is (seq items))
      (is (every? #(= [palette chart] ((juxt :palette :chart) (:data %))) items)))))

(deftest xyflow-graph-event-route-arrowhead-split
  ;; The source→event half is the quiet segment with the smaller head, so the
  ;; pair reads as one transition.
  (let [parsed (layout/project-definition idle-loading)
        graph  (projection/xyflow-graph parsed {} {})
        id     (:id (first (filter #(= :start (:event %)) (:edges parsed))))]
    (is (= [[(:arrow-width-quiet vc/chart-regular) true] [(:arrow-width vc/chart-regular) false]]
           (map (juxt #(get-in % [:markerEnd :width]) #(get-in % [:data :quietSegment]))
                [(inbound-edge-for graph id) (outbound-edge-for graph id)])))))

;; ---- node payload + style -------------------------------------------------

(deftest xyflow-graph-sizes-containers-from-their-measured-position
  ;; A container fills `width:100% height:100%`, so xyflow must allocate the
  ;; box ELK measured; a leaf sizes from its own DOM.
  (let [cid      (layout/node-id [:authenticated])
        browsing (layout/node-id [:authenticated :browsing])
        graph    (projection/xyflow-graph (layout/project-definition compound-machine)
                                          {cid      {:x 0  :y 0  :width 328 :height 156}
                                           browsing {:x 14 :y 34 :width 140 :height 44}}
                                          {})]
    (is (= {:width 328 :height 156} (:style (node-by-id graph cid))))
    (is (= {:position {:x 14 :y 34}} (select-keys (node-by-id graph browsing) [:position :style]))
        "a leaf keeps its parent-relative position and takes no :style")
    (is (= {:x 0 :y 0} (:position (node-by-id graph (layout/node-id [:authenticated :paying]))))
        "an unpositioned node sits at the origin until layout lands")))

(deftest xyflow-graph-region-data-carries-region-id-and-index
  (let [graph (projection/xyflow-graph (layout/project-definition parallel-machine) {} {})]
    (is (= [[:audio 0] [:video 1]]
           (map #((juxt :regionId :regionIndex) (:data (node-by-id graph (layout/region-node-id %))))
                [:audio :video])))))

(deftest xyflow-graph-threads-entry-exit-onto-node-data
  (let [graph (projection/xyflow-graph
                (layout/project-definition {:initial :a :states {:a {:entry :on-enter :exit :on-leave}}})
                {} {})]
    (is (= {:entry "on-enter" :exit "on-leave"}
           (select-keys (:data (node-by-id graph (layout/node-id [:a]))) [:entry :exit])))))

;; ---- event-node payload ----------------------------------------------------

(deftest xyflow-graph-event-node-surfaces-guard-and-action
  ;; Each piece rides its own slot; the label stays the bare event.
  (let [parsed (layout/project-definition {:initial :idle
                                           :states  {:idle    {:on {:submit {:target :loading
                                                                             :guard  :authed?
                                                                             :action :log-it}}}
                                                     :loading {}}})
        graph  (projection/xyflow-graph parsed {} {})]
    (is (= {:eventLabel "submit" :variant "on" :guard "authed?" :action "log-it"}
           (select-keys (:data (event-node-for graph (:id (first (:edges parsed)))))
                        [:eventLabel :variant :guard :action])))))

(deftest xyflow-graph-event-node-resolves-an-iso-after-delay-to-ms
  (let [parsed (layout/project-definition {:initial :a :states {:a {:after {"PT1S" :b}} :b {}}})
        graph  (projection/xyflow-graph parsed {} {})]
    (is (= {:afterMs 1000 :eventLabel "⌚ 1000ms"}
           (select-keys (:data (event-node-for graph (:id (first (:edges parsed)))))
                        [:afterMs :eventLabel])))))

(deftest xyflow-graph-event-node-eventId-is-the-fireable-event-only
  ;; The on-chart simulator sends a clicked event-node's `:eventId`; timers,
  ;; eventless and engine-raised transitions and the `:*` wildcard carry none.
  (doseq [[machine pick expected]
          [[idle-loading           #(= :start (:event %)) {:eventId :start :variant "on"
                                                           :fromPath [:idle] :toPath [:loading]}]
           [idle-loading           :after                 {:eventId nil :variant "after"}]
           [idle-loading           :always?               {:eventId nil :variant "always"}]
           [wildcard-machine       #(= :* (:event %))     {:eventId nil}]
           [spawn-on-error-machine :on-error?             {:eventId nil :variant "on-error"
                                                           :eventLabel "✗ error"}]]
          :let [parsed (layout/project-definition machine)
                data   (:data (event-node-for (projection/xyflow-graph parsed {} {})
                                              (:id (first (filter pick (:edges parsed))))))]]
    (is (= expected (select-keys data (keys expected))))))

(deftest xyflow-graph-threads-on-edge-click-onto-every-event-node
  (let [cb       (fn [_])
        graph    (projection/xyflow-graph (layout/project-definition idle-loading) {} {:on-edge-click cb})
        ev-nodes (filter #(= "rf2-event" (:type %)) (:nodes graph))]
    (is (seq ev-nodes))
    (is (every? #(= cb (:onClick (:data %))) ev-nodes))))

(deftest xyflow-graph-reenter-event-node-carries-reenter-data
  ;; `:reenter? true` restarts the state; the renderer marks it ↻.
  (doseq [[on reenter?] [[{:target :same-state :reenter? true} true]
                         [{:target :same-state}                false]]
          :let [parsed (layout/project-definition {:initial :a :states {:a {:on {:ping on}}}})]]
    (is (= reenter? (:reenter (:data (event-node-for (projection/xyflow-graph parsed {} {})
                                                     (:id (first (:edges parsed))))))))))

(deftest xyflow-graph-machine-level-event-nodes-flagged
  ;; A root `:on` fallback is ONE event-node leaving the machine-root chip, not
  ;; one per leaf it covers.
  (let [parsed (layout/project-definition machine-level-on-machine)
        graph  (projection/xyflow-graph parsed {} {})]
    (is (= [true] (map (comp :machineLevel :data)
                       (filter #(= :logout (:eventId (:data %))) (:nodes graph)))))
    (is (= layout/machine-root-id
           (:source (inbound-edge-for graph (:id (first (filter :machine-level? (:edges parsed))))))))))

;; ---- ->elk-children ---------------------------------------------------------

(deftest elk-children-flat-is-state-plus-event-nodes
  ;; One elk child per state and one per transition, inside the root-container frame.
  (let [parsed (layout/project-definition idle-loading)]
    (is (= (sort (concat (map :id (remove :root-container? (:nodes parsed)))
                         (map projection/event-node-id (:edges parsed))))
           (sort (map :id (root-children (projection/->elk-children parsed))))))))

(deftest elk-children-parallel-nests-states-and-events-under-regions
  ;; Each region holds its 2 states and 2 events, padded for its header strip
  ;; and its initial state's marker.
  (let [kids (root-children (projection/->elk-children (layout/project-definition parallel-machine)))]
    (is (= {(layout/region-node-id :audio) 4 (layout/region-node-id :video) 4}
           (into {} (map (juxt :id (comp count :children))) kids)))
    (is (every? #(= {"elk.algorithm" "layered"
                     "elk.padding"   (projection/container-elk-padding vc/chart-regular true)}
                    (:layoutOptions %))
                kids))))

(deftest elk-children-compound-keeps-its-floor-even-when-measured
  ;; Its extent comes from ELK laying out its children; feeding back its own
  ;; `100%`-of-the-box measurement would be circular.
  (let [parsed (layout/project-definition compound-machine)
        cid    (layout/node-id [:authenticated])
        kids   (root-children (projection/->elk-children parsed {cid {:width 999 :height 999}}))]
    (is (= [projection/compound-node-min-width projection/compound-node-min-height]
           ((juxt :width :height) (first (filter #(= cid (:id %)) kids)))))))

(deftest leaf-elk-size-takes-the-larger-of-measured-and-floor-per-dimension
  (let [floor-w projection/state-node-min-width
        floor-h projection/state-node-min-height]
    (doseq [[measured expected] [[nil                     {:width floor-w :height floor-h}]
                                 [{:width 300 :height 90} {:width 300 :height 90}]
                                 [{:width 320 :height 10} {:width 320 :height floor-h}]]]
      (is (= expected (projection/leaf-elk-size measured)) (pr-str measured)))))

(deftest elk-children-threads-measured-dims-to-leaves-and-events
  ;; The measure-then-relayout pass: measured leaves and event-nodes take their
  ;; real box, unmeasured ones keep their floor.
  (let [parsed              (layout/project-definition idle-loading)
        idle                (layout/node-id [:idle])
        ready               (layout/node-id [:ready])
        [start-ev other-ev] (map projection/event-node-id (:edges parsed))
        sizes               (->> (projection/->elk-children parsed {idle     {:width 260 :height 72}
                                                                    start-ev {:width 180 :height 60}})
                                 root-children
                                 (into {} (map (juxt :id (juxt :width :height)))))]
    (is (= {idle     [260 72]
            start-ev [180 60]
            ready    [projection/state-node-min-width projection/state-node-min-height]
            other-ev [projection/event-node-elk-width projection/event-node-elk-height]}
           (select-keys sizes [idle start-ev ready other-ev])))))

(deftest order-state-children-is-stable-against-shuffle
  ;; The initial state floats first and the machine-root chip sinks last; the
  ;; rest keep their input order.
  (let [top    (filter #(= layout/root-container-id (:parent-id %))
                       (:nodes (layout/project-definition door-cyclic-machine)))
        [init] (filter :initial? top)
        [root] (filter :machine-root? top)
        plain  (remove #(or (:initial? %) (:machine-root? %)) top)]
    (is (= (map :id (concat [init] plain [root]))
           (map :id (projection/order-state-children
                      (concat (take 1 plain) [init root] (drop 1 plain))))))))

(deftest elk-children-leads-with-initial-state
  ;; The parse lists the machine-root chip first; ELK must see it last.
  (let [ids (->> (projection/->elk-children (layout/project-definition door-cyclic-machine))
                 root-children
                 (remove #(str/starts-with? (:id %) "__rf2_event_"))
                 (map :id))]
    (is (= [(layout/node-id [:locked]) layout/machine-root-id] [(first ids) (last ids)]))))

;; ---- container padding ------------------------------------------------------

(deftest container-elk-padding-derives-from-density-constants
  ;; TOP clears the title strip plus a body-pad band. Reserving room for a
  ;; nested initial marker, drawn left of its state and outside ELK, widens
  ;; LEFT only.
  (doseq [{:keys [container-title-height container-body-pad] :as chart-vc}
          [vc/chart-compact vc/chart-regular vc/chart-cosy]
          :let [pad #(str "[top=" (+ container-title-height container-body-pad)
                          ",left=" % ",bottom=" container-body-pad ",right=" container-body-pad "]")]]
    (is (= (pad container-body-pad) (projection/container-elk-padding chart-vc)))
    (is (= (pad (max container-body-pad projection/initial-marker-left-extent))
           (projection/container-elk-padding chart-vc true)))))

(deftest context-band-height-grows-with-row-count
  (let [h #(projection/context-band-height % (:container-divider-width vc/chart-regular))]
    (is (= [0 0] [(h -1) (h 0)]) "no rows paint no band")
    (is (pos? (h 1)))
    (is (= (+ projection/context-band-row-height projection/context-band-row-gap)
           (- (h 2) (h 1))
           (- (h 3) (h 2)))
        "each row adds one row height and one gap")))

(deftest elk-children-non-root-containers-ignore-context-rows
  (let [walk   (fn walk [c] (cons c (mapcat walk (:children c))))
        nested (->> (projection/->elk-children (layout/project-definition nested-compound-machine)
                                               nil vc/chart-regular 4)
                    (mapcat walk)
                    (filter #(and (seq (:children %)) (not= layout/root-container-id (:id %)))))]
    (is (seq nested))
    (doseq [c nested]
      (is (= (projection/container-elk-padding vc/chart-regular true)
             (get-in c [:layoutOptions "elk.padding"]))
          (:id c)))))

;; ---- ->elk-edge / ->elk-edges -------------------------------------------------

(deftest elk-edge-splits-each-transition-at-its-event-node
  ;; The event-node carries the transition text, so both halves carry an empty label.
  (let [half  (fn [id from to] {:id id :sources [from] :targets [to] :labels [{:text ""}]})
        ev    projection/event-node-id
        start (first (filter #(= :start (:event %)) (:edges (layout/project-definition idle-loading))))
        tick  (first (:edges (layout/project-definition internal-self-machine)))]
    (is (= [(half (str (:id start) "__in") (:source start) (ev start))
            (half (str (:id start) "__out") (ev start) (:target start))]
           (projection/->elk-edge start)))
    (is (= [(half (str (:id tick) "__in") (:source tick) (ev tick))]
           (projection/->elk-edge tick))
        "an internal transition has no target, so no __out half")))

(deftest elk-edges-derives-initial-set-and-prioritises-each-region-initial
  ;; Pulls each initial state to the start of its region's flow, where the soft
  ;; model-order preference slips in a pure cycle.
  (let [rs #(layout/region-scoped-id %1 [%2])
        p  projection/initial-edge-priority-direction]
    (is (= {(rs :audio :muted) p (rs :audio :playing) nil
            (rs :video :hidden) p (rs :video :shown) nil}
           (->> (projection/->elk-edges (layout/project-definition parallel-machine))
                (filter #(str/ends-with? (:id %) "__in"))
                (into {} (map (juxt (comp first :sources)
                                    #(get-in % [:layoutOptions "elk.layered.priority.direction"])))))))))

;; ---- ELK routes and label positions ---------------------------------------------

(deftest xyflow-graph-attaches-edge-points-by-elk-edge-id
  ;; Keyed by the elk edge ids, so each half draws exactly its own segment.
  (let [parsed    (layout/project-definition idle-loading)
        id        (:id (first (filter #(= :start (:event %)) (:edges parsed))))
        in-route  [{:x 0 :y 0} {:x 0 :y 25} {:x 40 :y 25}]
        out-route [{:x 40 :y 50} {:x 80 :y 75} {:x 80 :y 100}]
        graph     (projection/xyflow-graph parsed {} {:edge-points {(str id "__in")  in-route
                                                                     (str id "__out") out-route}})]
    (is (= [in-route out-route]
           (map #(get-in % [:data :points]) [(inbound-edge-for graph id) (outbound-edge-for graph id)])))))

(deftest xyflow-graph-attaches-elk-label-position-to-edge-data
  (let [parsed (layout/project-definition idle-loading)
        id     (:id (first (filter #(= :start (:event %)) (:edges parsed))))
        graph  (projection/xyflow-graph parsed {} {:edge-labels {(str id "__in") {:x 42 :y 99}}})]
    (is (= [{:x 42 :y 99} nil]
           (map #(get-in % [:data :labelPos]) [(inbound-edge-for graph id) (outbound-edge-for graph id)])))))

;; ---- initial-state markers ------------------------------------------------------

(deftest xyflow-graph-emits-initial-marker-node-and-entry-edge
  (let [graph     (projection/xyflow-graph (layout/project-definition idle-loading) {} {})
        idle      (layout/node-id [:idle])
        marker-id (projection/initial-marker-id idle)]
    (is (= "initial-marker" (:type (node-by-id graph marker-id))))
    (is (= {:source marker-id :target idle :targetHandle "left"}
           (select-keys (edge-by-id graph (str marker-id "entry")) [:source :target :targetHandle])))))

(deftest xyflow-graph-positions-initial-marker-at-fixed-offset
  ;; A fixed offset left of (and just below) its state, wherever ELK put the state.
  (let [idle  (layout/node-id [:idle])
        graph (projection/xyflow-graph (layout/project-definition idle-loading) {idle {:x 300 :y 120}} {})]
    (is (= {:x (- 300 projection/initial-marker-x-offset) :y (+ 120 projection/initial-marker-y-offset)}
           (:position (node-by-id graph (projection/initial-marker-id idle)))))))

(deftest initial-marker-glyph-hook-flows-forward
  ;; The geometry the renderer paints: a Stately-small head, a hook flowing
  ;; dot → down-and-right into it, and a tip just outside the state's edge.
  (doseq [density vc/densities
          :let [{:keys [ah tip-x dot-x end-x]}
                (projection/initial-marker-glyph (:pseudo-radius (vc/chart-for-density density)))]]
    (is (<= 4 ah 6) density)
    (is (> end-x dot-x) density)
    (is (< 0 tip-x projection/initial-marker-x-offset) density)))

(deftest xyflow-graph-emits-compound-substate-initial-marker
  ;; It shares the compound's frame through `parentId` but takes no
  ;; `:extent "parent"`: the clamp would push a marker sitting just outside the
  ;; padding into its state.
  (let [graph (projection/xyflow-graph (layout/project-definition compound-machine) {} {})]
    (is (= {:parentId (layout/node-id [:authenticated])}
           (select-keys (node-by-id graph (projection/initial-marker-id
                                            (layout/node-id [:authenticated :browsing])))
                        [:parentId :parentNode :extent])))))

(deftest xyflow-graph-synthetic-ids-never-collide-with-real-state-ids
  ;; A bare `initial__` / `event__` prefix would mint the id of a real state
  ;; path starting `:initial` / `:event`, and xyflow silently drops a duplicate id.
  (doseq [[machine real-path synthetic-of synthetic-type]
          [[{:initial :a :states {:a {} :initial {:initial :a :states {:a {}}}}}
            [:initial :a]
            (fn [_] (projection/initial-marker-id (layout/node-id [:a])))
            "initial-marker"]
           [{:initial :a
             :states  {:a     {:on {:go :b}}
                       :b     {}
                       :event {:initial :a
                               :states  {:a {:initial :b
                                             :states  {:b {:initial :go :states {:go {}}}}}}}}}
            [:event :a :b :go]
            (fn [parsed] (projection/event-node-id (first (filter #(= :go (:event %)) (:edges parsed)))))
            "rf2-event"]]
          :let [parsed (layout/project-definition machine)
                graph  (projection/xyflow-graph parsed {} {})
                ids    (map :id (:nodes graph))]]
    (is (= (count ids) (count (distinct ids))))
    (is (= ["state" synthetic-type]
           (map #(:type (node-by-id graph %)) [(layout/node-id real-path) (synthetic-of parsed)])))))

;; ---- transitions route through their event-node ---------------------------------

(def ^:private parent-level-transition-machine
  "A compound `:active` with parent-level transitions, one internal, plus a
  transition into it and one inside it."
  {:initial :idle
   :states  {:idle    {:on {:connect :active}}
             :active  {:initial :connecting
                       :on      {:disconnect :idle
                                 :send {}}
                       :states  {:connecting {:on {:done :connected}}
                                 :connected  {}}}
             :failed  {:on {:retry :active}}}})

(deftest xyflow-graph-routes-every-transition-through-its-event-node
  ;; Compound endpoints included: the projector keeps them, and chart_dom_cljs_test's
  ;; `chart-renders-compound-node-with-handle-class-targets` and
  ;; `chart-renders-parallel-region-with-handle-class-targets` pin the container
  ;; handles xyflow needs to draw them. An internal transition's event-node
  ;; hangs with no __out half.
  (let [parsed (layout/project-definition parent-level-transition-machine)
        graph  (projection/xyflow-graph parsed {} {})
        id     layout/node-id
        route  (fn [{e :id :as edge}]
                 (let [ev   (projection/event-node-id edge)
                       ends (fn [x] (when x (mapv #(if (= ev %) :event %) ((juxt :source :target) x))))]
                   [(ends (inbound-edge-for graph e))
                    (ends (outbound-edge-for graph e))
                    (:internal (:data (event-node-for graph e)))]))]
    (is (= {:connect    [[(id [:idle]) :event] [:event (id [:active])] false]
            :disconnect [[(id [:active]) :event] [:event (id [:idle])] false]
            :send       [[(id [:active]) :event] nil true]
            :done       [[(id [:active :connecting]) :event] [:event (id [:active :connected])] false]
            :retry      [[(id [:failed]) :event] [:event (id [:active])] false]}
           (into {} (map (juxt :event route)) (:edges parsed))))))

(def ^:private multi-self-loop-machine
  {:initial :idle
   :states  {:idle {:on {:arm    {:action :arm-it}
                         :disarm {:action :disarm-it}
                         :clear  {:action :clear-it}}}}})

(deftest xyflow-graph-each-parsed-edge-yields-one-event-node
  ;; Several events on one source, or on one source/target pair, never collapse.
  (doseq [m [idle-loading compound-machine self-loop-machine wildcard-machine
             machine-level-on-machine multi-self-loop-machine
             {:initial :a :states {:a {:on {:go-fast :b :go-slow :b}} :b {}}}]
          :let [parsed (layout/project-definition m)
                graph  (projection/xyflow-graph parsed {} {})]]
    (is (= (count (:edges parsed)) (count (filter #(= "rf2-event" (:type %)) (:nodes graph))))
        (pr-str m))))

(def ^:private cross-hierarchy-machine
  {:initial :outer
   :states  {:outer  {:initial :inner
                      :states  {:inner {:on {:escape [:sibling]}}}}
             :sibling {}}})

(deftest xyflow-graph-flags-cross-hierarchy-edge
  ;; Source and target under different containers: the renderer anchors such
  ;; an edge's label at the source side.
  (doseq [[machine event cross?] [[cross-hierarchy-machine :escape true]
                                  [compound-machine :checkout false]]
          :let [parsed (layout/project-definition machine)
                id     (:id (first (filter #(= event (:event %)) (:edges parsed))))]]
    (is (= cross? (get-in (outbound-edge-for (projection/xyflow-graph parsed {} {}) id)
                          [:data :crossHierarchy])))))

;; ---- source-active, fired and guard-blocked overlays ------------------------------

(deftest xyflow-graph-from-active-is-source-active-only
  ;; An edge lights only when its SOURCE is active: push (closed → open), which
  ;; merely lands on the active `:open`, stays quiet.
  (let [parsed (layout/project-definition door-cyclic-machine)
        graph  (projection/xyflow-graph parsed {} {:highlight-ids #{(layout/node-id [:open])}})
        active (fn [e] [(get-in (inbound-edge-for graph (:id e)) [:data :active])
                        (get-in (outbound-edge-for graph (:id e)) [:data :active])])]
    (is (= {:door/insert-coin [false false] :door/push  [false false] :door/close [true true]
            :door/hold        [true nil]    :door/trip  [true true]   :door/reset [false false]
            :door/audit       [false false]}
           (into {} (map (juxt :event active)) (:edges parsed))))))

(deftest xyflow-graph-marks-fired-event-nodes-and-their-edges
  ;; Matched by edge id, so every traversed arm lights, endpoints aside.
  (let [parsed (layout/project-definition idle-loading)
        fired  [(first (filter #(= :start (:event %)) (:edges parsed)))
                (first (filter :always? (:edges parsed)))]
        graph  (projection/xyflow-graph parsed {} {:fired-edge-ids (set (map :id fired))})
        nodes  (flags (:nodes graph) :fired)
        edges  (flags (:edges graph) :fired)]
    (is (= (only-true nodes (map projection/event-node-id fired)) nodes))
    (is (= (only-true edges (for [e fired half ["__in" "__out"]] (str (:id e) half))) edges))))

(deftest xyflow-graph-marks-guard-blocked-event-node-and-its-in-half
  ;; A blocked transition is a no-op, so the pink stops at its event-node and the
  ;; __out half keeps a resting hue. Pink wins over the affordance hue the
  ;; active `:open` gives the same edge.
  (let [parsed (layout/project-definition door-cyclic-machine)
        close  (:id (first (filter #(= :may-close? (:guard %)) (:edges parsed))))
        pink   (:edge-guard-blocked (tokens/chart-tokens))
        graph  (projection/xyflow-graph parsed {} {:highlight-ids          #{(layout/node-id [:open])}
                                                    :guard-blocked-edge-ids #{close}})
        nodes  (flags (:nodes graph) :guardBlocked)
        edges  (flags (:edges graph) :guardBlocked)]
    (is (= (only-true nodes [(projection/event-node-id {:id close})]) nodes))
    (is (= (only-true edges [(str close "__in")]) edges))
    (is (= [true false]
           (map #(= pink (get-in % [:markerEnd :color]))
                [(inbound-edge-for graph close) (outbound-edge-for graph close)])))))

;; ---- completion (`:on-done`) event-nodes ------------------------------------------
;;
;; A completion carries the ✓ done chip and, when it completes a node, the
;; SCXML-style `done.state.<id>` label. Its event is engine-raised, so it is
;; never click-to-send.

(deftest xyflow-graph-compound-on-done-projects-done-event-node
  (let [parsed (layout/project-definition checkout-on-done)
        od     (first (filter :on-done? (:edges parsed)))]
    (is (= {:eventLabel "✓ done" :variant "on-done" :onDone true :eventId nil
            :doneState  (str "done.state." (layout/node-id [:flow]))}
           (select-keys (:data (event-node-for (projection/xyflow-graph parsed {} {}) (:id od)))
                        [:eventLabel :variant :onDone :eventId :doneState])))))

(deftest xyflow-graph-parallel-root-on-done-carries-the-scxml-done-state
  ;; Its done-path is the root `[]`, whose node-id is "", so a naive label would
  ;; read "done.state." where SCXML names the parallel root.
  (let [parsed (layout/project-definition ingest-on-done)
        od     (first (filter :on-done? (:edges parsed)))]
    (is (= {:onDone true :doneState (str "done.state." layout/parallel-root-done-state-id)}
           (select-keys (:data (event-node-for (projection/xyflow-graph parsed {} {}) (:id od)))
                        [:onDone :doneState])))))

(deftest xyflow-graph-spawn-on-done-beside-the-other-completions
  ;; A spawning compound's own completion, its child's completion and its
  ;; child's failure stay three edges; only the compound's names a done node.
  (let [parsed (layout/project-definition
                 {:initial :job
                  :states  {:job    {:initial :run
                                     :on-done :next
                                     :spawn   {:machine-id :child :on-done :loaded :on-error :failed}
                                     :states  {:run {:on {:finish :fin}} :fin {:final? true}}}
                            :next   {}
                            :loaded {}
                            :failed {}}})
        graph  (projection/xyflow-graph parsed {} {})]
    (is (= #{[:finish [:job :run] [:job :fin] "on" nil]
             [:rf.machine/done [:job] [:next] "on-done" (str "done.state." (layout/node-id [:job]))]
             [:rf.machine.spawn/done [:job] [:loaded] "on-done" nil]
             [:rf.machine.spawn/error [:job] [:failed] "on-error" nil]}
           (set (map (fn [e] (let [d (:data (event-node-for graph (:id e)))]
                               [(:event e) (:from e) (:to e) (:variant d) (:doneState d)]))
                     (:edges parsed)))))))

;; ---- guarded forks ----------------------------------------------------------------
;;
;; A guarded fork (2+ candidates on one source and trigger, at least one
;; guarded) is evaluated first-pass-wins in candidate order. The chart numbers
;; its branches, joins them with a dotted connector in that order, and pins ELK
;; to lay them out in it.

(defn- gate-check-event-node
  "The event-node id of the gate fork's `:gate/check` branch guarded by `guard`
  (nil: the fallback)."
  [parsed guard]
  (projection/event-node-id
    (first (filter #(and (= :gate/check (:event %)) (= guard (:guard %))) (:edges parsed)))))

(defn- connector-edges-of [graph]
  (filter #(:forkConnector (:data %)) (:edges graph)))

(deftest xyflow-graph-threads-fork-order-onto-event-nodes
  (let [parsed (layout/project-definition gate-fork-machine)
        graph  (projection/xyflow-graph parsed {} {})]
    (is (= {:gate-high? 1 :gate-low? 2 nil 3}
           (->> (:edges parsed)
                (filter #(= :gate/check (:event %)))
                (into {} (map (juxt :guard #(:forkOrder (:data (event-node-for graph (:id %))))))))))))

(deftest fork-order-badges-only-guarded-same-trigger-groups
  ;; Distinct triggers never group; a guardless group has no evaluation order to
  ;; show; a partly guarded one numbers every branch, the fallback last.
  (doseq [[on expected] [[{:go-x {:guard :gx? :target :b} :go-y {:guard :gy? :target :c}} {}]
                         [{:go [{:target :b} {:target :c}]}                              {}]
                         [{:go [{:guard :g1? :target :b} {:target :c}]}                 {[:b] 1 [:c] 2}]]
          :let [edges (:edges (layout/project-definition {:initial :a :states {:a {:on on} :b {} :c {}}}))
                order (projection/fork-order-by-edge-id edges)]]
    (is (= expected (into {} (keep #(when-let [o (order (:id %))] [(:to %) o])) edges)))))

(deftest xyflow-graph-links-fork-branches-with-a-decorative-connector
  ;; Branch 1 → 2 → 3, each link leaving its branch's right handle for the next
  ;; one's left, since the branches lay out left to right.
  (let [parsed (layout/project-definition gate-fork-machine)
        ev     #(gate-check-event-node parsed %)
        conns  (connector-edges-of (projection/xyflow-graph parsed {} {}))]
    (is (= [[(ev :gate-high?) (ev :gate-low?)] [(ev :gate-low?) (ev nil)]]
           (map (juxt :source :target) conns)))
    (is (every? #(= ["right" "left"] ((juxt :sourceHandle :targetHandle) %)) conns))))

(deftest elk-children-pin-fork-branches-in-priority-order
  ;; The `elk.position` index rides the cross axis of the resolved direction:
  ;; X for :tb (the default), Y for :lr. Other event-nodes stay unpinned.
  (let [parsed (layout/project-definition gate-fork-machine)
        ev     #(gate-check-event-node parsed %)
        set-ev (projection/event-node-id (first (filter #(= :gate/set (:event %)) (:edges parsed))))]
    (doseq [[elk [p1 p2 p3]] [[(projection/->elk-children parsed)               ["(1,0)" "(2,0)" "(3,0)"]]
                              [(projection/->elk-children parsed nil nil 0 :lr) ["(0,1)" "(0,2)" "(0,3)"]]]
            :let [opts (into {} (map (juxt :id :layoutOptions)) (root-children elk))]]
      (is (= {(ev :gate-high?) {"elk.position" p1}
              (ev :gate-low?)  {"elk.position" p2}
              (ev nil)         {"elk.position" p3}
              set-ev           nil}
             (select-keys opts [(ev :gate-high?) (ev :gate-low?) (ev nil) set-ev]))))))

(deftest elk-children-root-container-carries-semi-interactive-for-fork
  ;; ELK honours the branch pins only in a semiInteractive container, and the
  ;; gate fork's is the root-container frame, not the bare root edges_cljs_test pins.
  (is (= "true" (get-in (first (projection/->elk-children (layout/project-definition gate-fork-machine)))
                        [:layoutOptions "elk.layered.crossingMinimization.semiInteractive"]))))

(deftest no-guarded-fork-emits-no-fork-machinery
  ;; Distinct triggers on one source (`self-loop-machine`) and the `:after` /
  ;; guarded `:always` pair on one source (`idle-loading`) are not forks.
  (doseq [m [self-loop-machine idle-loading]
          :let [parsed (layout/project-definition m)
                graph  (projection/xyflow-graph parsed {} {})
                elk    (projection/->elk-children parsed)]]
    (is (every? #(nil? (:forkOrder (:data %))) (filter #(= "rf2-event" (:type %)) (:nodes graph))))
    (is (empty? (connector-edges-of graph)))
    (is (not-any? #(contains? (:layoutOptions %) "elk.position") (root-children elk)))
    (is (not (contains? (:layoutOptions (first elk))
                        "elk.layered.crossingMinimization.semiInteractive")))))

;; ---- declared `:rf.cofx/requires` ----------------------------------------------
;;
;; A named guard / action / entry / exit's declared requirements ride `:data` as
;; id strings, camelCased so the renderer reads them after xyflow's `clj->js`.

(def cofx-projection-machine
  {:initial :idle
   :guards  {:within-window? {:rf.cofx/requires [:rf/time-ms]
                              :fn (fn [_] true)}}
   :actions {:schedule-retry {:rf.cofx/requires [:payment/retry-jitter-ms]
                              :fn (fn [_] nil)}
             :stamp-started  {:rf.cofx/requires [:rf/time-ms]
                              :fn (fn [_] nil)}
             :stamp-ended    {:rf.cofx/requires [:rf/uuid]
                              :fn (fn [_] nil)}}
   :states  {:idle {:entry :stamp-started
                    :exit  :stamp-ended
                    :on    {:go {:target :busy
                                 :guard  :within-window?
                                 :action :schedule-retry}}}
             :busy {}}})

(deftest xyflow-graph-carries-declared-requires-on-event-and-state-data
  (let [parsed (layout/project-definition cofx-projection-machine)
        graph  (projection/xyflow-graph parsed {} {})]
    (is (= {:guardRequires ["rf/time-ms"] :actionRequires ["payment/retry-jitter-ms"]}
           (select-keys (:data (event-node-for graph (:id (first (:edges parsed)))))
                        [:guardRequires :actionRequires])))
    (is (= {:entryRequires ["rf/time-ms"] :exitRequires ["rf/uuid"]}
           (select-keys (:data (node-by-id graph (layout/node-id [:idle])))
                        [:entryRequires :exitRequires])))))

(def cofx-parallel-dup-machine
  "Regions `:a` and `:b` each own an `:active` with distinct lifecycle requires."
  {:type    :parallel
   :actions {:a-enter {:rf.cofx/requires [:region-a/enter-fact] :fn (fn [_] nil)}
             :a-exit  {:rf.cofx/requires [:region-a/exit-fact]  :fn (fn [_] nil)}
             :b-enter {:rf.cofx/requires [:region-b/enter-fact] :fn (fn [_] nil)}
             :b-exit  {:rf.cofx/requires [:region-b/exit-fact]  :fn (fn [_] nil)}}
   :regions {:a {:initial :active
                 :states  {:active {:entry :a-enter :exit :a-exit}}}
             :b {:initial :active
                 :states  {:active {:entry :b-enter :exit :b-exit}}}}})

(deftest lifecycle-requires-resolve-per-parallel-region
  ;; Each `:active` shows its own region's requirements, never those of the
  ;; first region holding that path.
  (let [graph (projection/xyflow-graph (layout/project-definition cofx-parallel-dup-machine) {} {})]
    (is (= {:a [["region-a/enter-fact"] ["region-a/exit-fact"]]
            :b [["region-b/enter-fact"] ["region-b/exit-fact"]]}
           (into {} (map (fn [r] [r ((juxt :entryRequires :exitRequires)
                                     (:data (node-by-id graph (layout/region-scoped-id r [:active]))))]))
                 [:a :b])))))
