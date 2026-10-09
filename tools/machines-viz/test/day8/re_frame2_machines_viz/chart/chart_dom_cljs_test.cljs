(ns day8.re-frame2-machines-viz.chart.chart-dom-cljs-test
  "Browser-side visual pins for the xyflow `MachineChart`.

  The JVM cannot load xyflow, so the rendered-chart coverage lives here: each
  test mounts the chart through the substrate-adapter React bridge
  (`adapters.react-chart/chart-element`, the element a UIx host mounts) under
  React `act()` and reads the real DOM.

  elkjs layout is async, but xyflow renders node DOM at `{x 0 y 0}` on the
  first commit, so counts, attrs, classes and the control toggles are all
  assertable synchronously; no test awaits the elk Promise.

  The ns ends in `-dom-cljs-test`, so `:browser-test` (headless Chromium) runs
  it. Under `:node-test` there is no DOM and the mount helpers do nothing."
  (:require ["react"            :as React]
            ["react-dom/client" :as react-dom-client]
            [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-machines-viz.adapters.react-chart :as react-chart]
            [day8.re-frame2-machines-viz.chart.edges :as edges]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.chart.projection :as projection]
            [day8.re-frame2-machines-viz.theme.tokens :as tokens]
            [day8.re-frame2-machines-viz.visual-constants :as vc]))

;; ---- sample machines ----------------------------------------------------

(def ^:private idle-loading-done
  "4 states, 3 transitions, two finals."
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :done :err :failed}}
             :done    {:final? true}
             :failed  {:final? true}}})

(def ^:private tagged-machine
  {:initial :idle
   :states  {:idle    {:tags #{:initial-tag} :on {:start :loading}}
             :loading {:on {:done :idle}}}})

(def ^:private namespaced-tag-machine
  {:initial :open
   :states  {:open {:tags #{:door/open} :on {:close :shut}}
             :shut {}}})

(def ^:private machine-level-on-machine
  "A top-level `:on` fallback (`:reset` → `:a`) plus machine `:data`."
  {:initial :a
   :on      {:reset :a}
   :data    {:hits 0 :seen []}
   :states  {:a {:on {:go :b}}
             :b {:on {:go :a}}}})

(def ^:private success-and-error-finals
  {:initial :running
   :states  {:running {:on {:ok :ok :boom :boom}}
             :ok      {:final? true}
             :boom    {:final? true :error? true}}})

(def ^:private parallel-machine
  "2 regions, 2 states each."
  {:type :parallel
   :regions {:audio   {:initial :playing
                       :states {:playing {:on {:pause :paused}}
                                :paused  {:on {:play :playing}}}}
             :display {:initial :on
                       :states {:on  {:on {:dim :off}}
                                :off {:on {:lit :on}}}}}})

(def ^:private parallel-root-fallback-machine
  "A parallel root with its own `:on`, which projects the synthetic
  `:parallel-root?` anchor chip. 4 real states."
  {:type    :parallel
   :on      {:reset {:target [[:audio :playing] [:display :on]]}}
   :regions {:audio   {:initial :playing
                       :states {:playing {:on {:pause :paused}}
                                :paused  {:on {:play :playing}}}}
             :display {:initial :on
                       :states {:on  {:on {:dim :off}}
                                :off {:on {:lit :on}}}}}})

(def ^:private history-machine
  "A compound holding a `:type :history` pseudo-state. 4 real states."
  {:initial :off
   :states  {:off    {:on {:resume [:player :hist]}}
             :player {:initial :stopped
                      :states  {:stopped {:on {:play :playing}}
                                :playing {:on {:stop :stopped}}
                                :hist    {:type :history :deep? false}}
                      :on      {:power-off :off}}}})

(def ^:private context-rich-machine
  "A 3-key `:data`, so the Context band is taller than a bare title strip."
  {:initial :a
   :data    {:hits 0 :seen [] :note "x"}
   :states  {:a {:on {:go :b}}
             :b {:on {:go :a}}}})

(def ^:private compound-machine
  {:initial :unauth
   :states  {:unauth        {:on {:login :authenticated}}
             :authenticated {:initial :browsing
                             :states  {:browsing {:on {:checkout :paying}}
                                       :paying   {:on {:done :browsing}}}
                             :on      {:logout :unauth}}}})

(def ^:private compound-endpoint-machine
  "A compound `:active` that is the target, the source and the self-loop
  holder of transitions, plus a sibling transition into it."
  {:initial :idle
   :states  {:idle    {:on {:connect :active}}
             :active  {:initial :connecting
                       :on      {:disconnect :idle
                                 :send {}}
                       :states  {:connecting {:on {:done :connected}}
                                 :connected  {}}}
             :failed  {:on {:retry :active}}}})

(def ^:private guarded-door-machine
  {:initial :closed
   :states  {:closed {:on {:door/open :open}}
             :open   {:on {:door/close {:target :closed :guard :may-close?}}}}})

(def ^:private gate-fork-machine
  "`:gate/check` forks from `:idle` by a guarded candidate vector (priority
  1 `:gate-high?`, 2 `:gate-low?`, 3 the unguarded fallback). `:gate/set` is
  a separate action-only transition on the same source."
  {:initial :idle
   :data    {:level 0}
   :states  {:idle     {:on {:gate/set   {:action :set-level}
                             :gate/check [{:guard :gate-high? :target :high}
                                          {:guard :gate-low?  :target :low}
                                          {:target :rejected}]}}
             :low      {:on {:gate/reset :idle}}
             :high     {:on {:gate/reset :idle}}
             :rejected {:on {:gate/reset :idle}}}})

;; ---- DOM helpers --------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- get-act []
  (when (exists? (.-act React)) (.-act React)))

(defn- mount-node! []
  (let [el (.createElement js/document "div")]
    ;; xyflow needs a non-zero parent box for the chart's "100%" to resolve.
    (set! (.. el -style -width) "800px")
    (set! (.. el -style -height) "600px")
    (.appendChild (.-body js/document) el)
    el))

(defn- with-mounted-element
  "Mount React `element` in a sized host node under act(), call `(f node)`,
  then unmount. Does nothing off-DOM."
  [element f]
  (let [act-fn (get-act)]
    (when (and (browser?) act-fn)
      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
      (let [node (mount-node!)
            root (react-dom-client/createRoot node)]
        (try
          (act-fn (fn [] (.render root element)))
          (f node)
          (finally
            (try (act-fn (fn [] (.unmount root))) (catch :default _ nil))
            (try (.removeChild (.-body js/document) node) (catch :default _ nil))))))))

(defn- with-mounted-chart
  "Mount `MachineChart` with `props` and call `(f chart-root host-node)`. The
  elk Promise is never awaited: returning a Promise from act() makes it async,
  which this synchronous runner cannot await."
  [props f]
  (with-mounted-element (react-chart/chart-element props)
    (fn [node] (f (.querySelector node "[data-testid=\"rf-mv-chart\"]") node))))

(defn- count-sel [^js node sel]
  (.-length (.querySelectorAll node sel)))

(defn- attrs
  "`{attr-name value}` for each of `names` on `el`."
  [^js el names]
  (into {} (map (fn [n] [n (.getAttribute el n)])) names))

(defn- state-node-el [^js node]
  (.querySelector node "[data-testid^=\"rf-mv-chart-node-\"]"))

(defn- canonical-edge-id
  "The edge id the projector mints for `from` → `to` via `event`."
  [definition from-path to-path event]
  (some (fn [e]
          (when (= [from-path to-path event] [(:from-path e) (:to-path e) (:event e)])
            (:id e)))
        (:edges (layout/project-definition definition))))

(defn- ->edge-props
  "The JS props xyflow hands a custom edge component, built from a projector
  edge map."
  [edge]
  #js {:id             (:id edge)
       :sourceX        0 :sourceY 0
       :targetX        80 :targetY 0
       :sourcePosition "right" :targetPosition "left"
       :markerEnd      (clj->js (:markerEnd edge))
       :data           (clj->js (:data edge))})

(defn- normalize-color
  "Round-trip a CSS colour through the CSSOM, so a token (hex or `rgba(...)`)
  compares equal to the browser's read-back of a `style.<prop>`."
  [css]
  (let [el (.createElement js/document "div")]
    (set! (.. el -style -color) (str css))
    (.. el -style -color)))

(defn- dispatch-click!
  "Dispatch a bubbling click on `el` inside act(), where React's root
  delegate catches it."
  [el]
  (let [act-fn (get-act)
        ev     (js/MouseEvent. "click" #js {:bubbles true :cancelable true})]
    (act-fn (fn [] (.dispatchEvent el ev)))))

;; ---- counts, root attrs and default chrome ------------------------------

(deftest chart-renders-states-and-default-chrome
  (testing "one node per state; the semantic counts and resolved defaults on
            the root; the named root-container frame; Controls + Background
            but no MiniMap"
    (with-mounted-chart
      {:machine-id :test/flow :definition idle-loading-done}
      (fn [root node]
        (is (= 4 (count-sel node "[data-testid^=\"rf-mv-chart-node-\"]")))
        (let [want {"data-node-count"             "4"
                    "data-edge-count"             "3"
                    "data-density"                "regular"
                    "data-theme"                  "dark"
                    "data-fired-edge-ids"         ""
                    "data-guard-blocked-edge-ids" ""}]
          (is (= want (attrs root (keys want)))))
        (is (= {"data-tags" "" "data-tag-count" "0"}
               (attrs (state-node-el node) ["data-tags" "data-tag-count"]))
            "an untagged state carries empty tag attrs")
        (is (= "false" (.getAttribute (.querySelector node "[data-root-container=\"true\"]")
                                      "data-parallel")))
        (is (re-find #"flow" (.-textContent (.querySelector
                                              node "[data-testid^=\"rf-mv-chart-root-container-title-\"]")))
            "the frame header carries the machine name")
        (is (= [true true false]
               (map #(pos? (count-sel node %))
                    [".react-flow__controls" ".react-flow__background" ".react-flow__minimap"]))
            "Controls and Background by default, no MiniMap")))))

(deftest chart-honours-chrome-theme-and-density-props
  (testing ":show-controls? false drops Controls, :show-minimap? true adds the
            MiniMap, and :theme and :density each surface on the root"
    (with-mounted-chart
      {:machine-id :test/flow :definition idle-loading-done
       :show-controls? false :show-minimap? true :theme :light :density :compact}
      (fn [root node]
        (is (= [false true]
               (map #(pos? (count-sel node %)) [".react-flow__controls" ".react-flow__minimap"])))
        (is (= {"data-theme" "light" "data-density" "compact"}
               (attrs root ["data-theme" "data-density"])))))))

(deftest chart-node-count-excludes-synthetic-anchors-and-history
  (testing "data-node-count and the aria-label count only real states, never
            the machine-root or parallel-root anchor chip nor a history
            pseudo-state"
    (doseq [[label definition n] [["machine-root anchor"  machine-level-on-machine       2]
                                  ["parallel-root anchor" parallel-root-fallback-machine 4]
                                  ["history pseudo-state" history-machine                4]]]
      (with-mounted-chart
        {:machine-id :test/counts :definition definition}
        (fn [root _node]
          (is (= (str n) (.getAttribute root "data-node-count"))
              (str label ": data-node-count excludes it"))
          (is (str/includes? (.getAttribute root "aria-label") (str "with " n " states"))
              (str label ": the aria-label excludes it")))))))

(deftest chart-data-edge-count-projected-accounts-for-every-parsed-edge
  (testing "data-edge-count-projected is exactly one `__in` half per parsed
            edge, one `__out` half per non-internal edge and one entry edge per
            initial marker, so an edge dropped at the projector moves it"
    (with-mounted-chart
      {:machine-id :test/compound :definition compound-endpoint-machine}
      (fn [root _node]
        (let [{:keys [edges nodes]} (layout/project-definition compound-endpoint-machine)]
          (is (= (str (+ (count edges)
                         (count (remove :internal? edges))
                         (count (filter :initial? nodes))))
                 (.getAttribute root "data-edge-count-projected"))))))))

(deftest chart-renders-no-definition-placeholder
  (with-mounted-element
    (react-chart/chart-element {:machine-id :test/flow :definition nil})
    (fn [node]
      (is (some? (.querySelector node "[data-testid=\"rf-mv-chart-no-definition\"]"))))))

;; ---- state nodes ----------------------------------------------------------

(deftest chart-error-final-rings-distinct-from-success
  (testing "every final paints the double-border ring; an `:error?` final's
            ring carries the error hue (data-error-final), a success final's
            does not"
    (with-mounted-chart
      {:machine-id :test/flow :definition success-and-error-finals}
      (fn [_root node]
        (is (= ["false" "true"]
               (sort (map #(.getAttribute % "data-error-final")
                          (array-seq (.querySelectorAll
                                       node "[data-testid^=\"rf-mv-chart-final-ring-\"]"))))))))))

(deftest chart-tags-surface-as-visible-pills-below-state-name
  (testing "a declared tag renders as a visible pill and surfaces on the
            state-node's data-tags / title / data-tag-count attrs"
    (with-mounted-chart
      {:machine-id :test/tags :definition tagged-machine}
      (fn [_root node]
        (is (some? (.querySelector node "[data-testid=\"rf-mv-chart-state-tag-initial-tag\"]")))
        (is (= {"data-tags" "initial-tag" "title" "initial-tag" "data-tag-count" "1"}
               (attrs (state-node-el node) ["data-tags" "title" "data-tag-count"])))))))

(deftest chart-namespaced-tag-preserves-declared-identity
  (testing "a namespaced tag keeps `door/open` on its data-tag and visible
            label; only the testid collapses it, since a `/` breaks selectors"
    (with-mounted-chart
      {:machine-id :test/door :definition namespaced-tag-machine}
      (fn [_root node]
        (let [pill (.querySelector node "[data-testid=\"rf-mv-chart-state-tag-open\"]")]
          (is (= "door/open" (.getAttribute pill "data-tag")))
          (is (re-find #"door/open" (.-textContent pill))))))))

;; ---- machine root and Context band --------------------------------------

(deftest chart-machine-level-on-renders-single-root-sourced-chip
  (testing "a top-level `:on` fallback renders the machine-root chip and
            exactly ONE event chip, not one per state"
    (with-mounted-chart
      {:machine-id :test/ml :definition machine-level-on-machine}
      (fn [_root node]
        (is (pos? (count-sel node "[data-machine-root=\"true\"]")))
        (is (= 1 (count-sel node "[data-testid^=\"rf-mv-chart-event-\"][data-machine-level=\"true\"]")))))))

(deftest chart-context-band-renders-keys-and-provenance-badge
  (testing "the root-container frame paints the `:context-band` keys, badged
            `inferred from :data` by default and `declared` when the host
            passes `:context-band-inferred? false`"
    (let [props {:machine-id :test/ctx :definition machine-level-on-machine
                 :context-band {:hits "number" :seen "vector"}}
          badge (fn [node kind]
                  (.querySelector node (str "[data-testid^=\"rf-mv-chart-root-container-context-"
                                            kind "-\"]")))]
      (with-mounted-chart props
        (fn [_root node]
          (is (= "2" (.getAttribute (.querySelector
                                      node "[data-testid^=\"rf-mv-chart-root-container-context-\"]")
                                    "data-key-count")))
          (is (= "inferred from :data" (.-textContent (badge node "inferred"))))))
      (with-mounted-chart (assoc props :context-band-inferred? false)
        (fn [_root node]
          (is (nil? (badge node "inferred")))
          (is (= "declared" (.-textContent (badge node "declared")))))))))

(deftest chart-context-band-fits-within-reserved-elk-top-padding
  (testing "the rendered root-container header (title strip + a 3-row Context
            band) fits inside the top padding the frame reserves for ELK
            (`data-reserved-top`), so ELK lays the first child below the band"
    (with-mounted-chart
      {:machine-id :test/ctx :definition context-rich-machine
       :context-band {:hits "number" :seen "vector" :note "string"}}
      (fn [_root node]
        (let [header   (.-offsetHeight (.querySelector
                                         node "[data-testid^=\"rf-mv-chart-root-container-header-\"]"))
              reserved (js/parseInt (.getAttribute (.querySelector node "[data-root-container=\"true\"]")
                                                   "data-reserved-top")
                                    10)
              plain    (+ (:container-title-height vc/chart-regular)
                          (:container-body-pad vc/chart-regular))]
          (is (> header plain) "the band makes the header outgrow a title + body-pad reservation")
          (is (<= header reserved) "the header fits within the reserved ELK top padding"))))))

;; ---- parallel regions and compounds -------------------------------------
;;
;; xyflow v12 adopts a child through `parentId` (the pre-v12 `parentNode` is
;; silently ignored) and marks the adopting wrapper with the CSS class
;; `parent`. Without adoption the children render at the root and escape their
;; container. The class lands on the first commit.

(deftest chart-renders-parallel-region-containers
  (testing "one region container per region, holding its states, adopted via
            `parentId`; only a region with an active leaf carries data-active"
    (with-mounted-chart
      {:machine-id :test/parallel :definition parallel-machine
       :current-state {:audio :paused}}
      (fn [root node]
        (is (= "2" (.getAttribute root "data-region-count")))
        (is (= 2 (count-sel node "[data-testid^=\"rf-mv-chart-region-\"]")))
        (is (= 4 (count-sel node "[data-testid^=\"rf-mv-chart-node-\"]"))
            "region containers are not counted as states")
        (doseq [region-id (map layout/region-node-id [:audio :display])]
          (is (.. (.querySelector node (str ".react-flow__node[data-id=\"" region-id "\"]"))
                  -classList (contains "parent"))
              (str region-id " adopted its states")))
        (is (= ["true" "false"]
               (map #(.getAttribute (.querySelector
                                      node (str "[data-testid^=\"rf-mv-chart-region-\"][data-region-id=\""
                                                % "\"]"))
                                    "data-active")
                    ["audio" "display"])))))))

(deftest chart-parallel-current-state-highlights-every-active-region
  (testing "a region-map :current-state surfaces every active region leaf on
            data-highlight-ids at once"
    (with-mounted-chart
      {:machine-id    :test/parallel
       :definition    parallel-machine
       :current-state {:audio :paused :display :off}}
      (fn [root _node]
        (is (= #{(layout/region-scoped-id :audio [:paused])
                 (layout/region-scoped-id :display [:off])}
               (set (str/split (.getAttribute root "data-highlight-ids") #"\s+"))))))))

(deftest chart-compound-container-gets-xyflow-parent-class
  (with-mounted-chart
    {:machine-id :test/compound :definition compound-machine}
    (fn [_root node]
      (is (.. (.querySelector node (str ".react-flow__node[data-id=\""
                                        (layout/node-id [:authenticated]) "\"]"))
              -classList (contains "parent"))
          "the compound adopted its substates"))))

;; xyflow silently drops an edge whose endpoint node has no Handle children,
;; so compound and region containers carry invisible `.source` / `.target`
;; Handles.

(deftest chart-renders-compound-node-with-handle-class-targets
  (with-mounted-chart
    {:machine-id :test/compound :definition compound-endpoint-machine}
    (fn [_root node]
      (let [el (.querySelector node (str "[data-testid=\"rf-mv-chart-compound-"
                                         (layout/node-id [:active]) "\"]"))]
        (is (pos? (count-sel el ".source")))
        (is (pos? (count-sel el ".target")))))))

(deftest chart-renders-parallel-region-with-handle-class-targets
  (with-mounted-chart
    {:machine-id :test/parallel :definition parallel-machine}
    (fn [_root node]
      (let [el (.querySelector node (str "[data-testid=\"rf-mv-chart-region-"
                                         (layout/region-node-id :audio) "\"]"))]
        (is (pos? (count-sel el ".source")))
        (is (pos? (count-sel el ".target")))))))

;; ---- event nodes ----------------------------------------------------------

(deftest chart-fired-and-guard-blocked-edges-mark-their-event-node-and-root
  (testing "an edge id in `:fired-edge-ids` / `:guard-blocked-edge-ids` marks
            its event-node, and no other, and surfaces on the chart root"
    (doseq [[prop node-attr root-attr definition from to event]
            [[:fired-edge-ids "data-fired" "data-fired-edge-ids"
              idle-loading-done [:idle] [:loading] :start]
             [:guard-blocked-edge-ids "data-guard-blocked" "data-guard-blocked-edge-ids"
              guarded-door-machine [:open] [:closed] :door/close]]]
      (let [edge-id (canonical-edge-id definition from to event)
            ev-id   (projection/event-node-id {:id edge-id})]
        (with-mounted-chart
          {:machine-id :test/marks :definition definition prop #{edge-id}}
          (fn [root node]
            ;; `[data-node-id]` keeps the sweep to outer event-nodes: the inner
            ;; spans share the testid prefix, and a blocked node's own guard
            ;; chip is itself marked.
            (let [marked (.querySelector node (str "[data-testid=\"rf-mv-chart-event-" ev-id "\"]"))
                  others (remove #(= % marked)
                                 (array-seq (.querySelectorAll
                                              node "[data-testid^=\"rf-mv-chart-event-\"][data-node-id]")))]
              (is (= [edge-id "true"]
                     [(.getAttribute root root-attr) (some-> marked (.getAttribute node-attr))])
                  (name prop))
              (is (every? #(contains? #{"false" nil} (.getAttribute % node-attr)) others)
                  (str (name prop) ": no other event-node is marked")))))))))

(deftest chart-guarded-fork-branches-render-priority-badges-1-2-3
  (testing "the branches of the guarded `:gate/check` fork paint numbered
            priority badges 1, 2, 3, and no other event-node paints one"
    (with-mounted-chart
      {:machine-id :test/gate :definition gate-fork-machine}
      (fn [_root node]
        (is (= ["1" "2" "3"]
               (sort (map #(.getAttribute % "data-fork-order")
                          (array-seq (.querySelectorAll
                                       node "[data-testid^=\"rf-mv-chart-event-fork-badge-\"]"))))))))))

(deftest chart-action-bearing-event-renders-enclosed-action-chip
  (testing "the action-only `:gate/set` is the one event-node painting the
            action chip, named by data-action and enclosed by a fill and a
            solid 1px border so it reads as a contained annotation"
    (with-mounted-chart
      {:machine-id :test/gate :definition gate-fork-machine}
      (fn [_root node]
        (let [chips (.querySelectorAll node "[data-testid^=\"rf-mv-chart-event-action-\"]")
              chip  (aget chips 0)
              ct    (tokens/chart-tokens)]
          (is (= 1 (.-length chips)))
          (is (= ["set-level"
                  (normalize-color (:container-header-bg ct))
                  (normalize-color (:state-border ct))
                  "1px"
                  "solid"]
                 [(.getAttribute chip "data-action")
                  (normalize-color (.. chip -style -backgroundColor))
                  (normalize-color (.. chip -style -borderColor))
                  (.. chip -style -borderWidth)
                  (.. chip -style -borderStyle)])))))))

(deftest chart-fork-connector-renders-decorative
  (testing "the projector's fork-connector edge renders as a decorative
            dotted order chain — 1·3 dash, round caps, the neutral
            :pseudo-marker stroke, no arrowhead and no label — not as a
            transition edge"
    ;; `transition-edge` builds its element eagerly, so it needs the DOM guard.
    (when (browser?)
      (let [ct   (tokens/chart-tokens)
            edge (first (projection/fork-connector-edges
                          (:edges (layout/project-definition gate-fork-machine))
                          ct vc/chart-regular))]
        (with-mounted-element
          (edges/transition-edge (->edge-props edge))
          (fn [node]
            ;; The connector id carries `/` and `?`, which no CSS id selector
            ;; accepts, so match the path on its `.id` property.
            (let [path (some #(when (= (:id edge) (.-id %)) %)
                             (array-seq (.querySelectorAll node "path")))]
              (is (some? path) "the connector renders a BaseEdge <path>")
              (when path
                ;; The browser canonicalises the dash list ("1 3" → "1, 3").
                (is (= [["1" "3"] "round" (normalize-color (:pseudo-marker ct))]
                       [(vec (remove str/blank? (str/split (.. path -style -strokeDasharray) #"[,\s]+")))
                        (.. path -style -strokeLinecap)
                        (normalize-color (.. path -style -stroke))]))
                (is (contains? #{nil "" "none"} (.getAttribute path "marker-end"))
                    "no arrowhead"))
              (is (zero? (count-sel node "[data-testid^=\"rf-mv-chart-edge-\"]"))
                  "no edge label"))))))))

;; ---- :on-state-click ------------------------------------------------------

(deftest chart-on-state-click-fires-for-compound-title-strip-and-leaves
  (testing "a compound's TITLE STRIP is clickable while its body passes
            clicks through to the nested leaves (pointer-events auto / none);
            the title strip, a top-level leaf and a nested leaf each report
            their own path"
    (let [clicks (atom [])]
      (with-mounted-chart
        {:machine-id     :test/compound
         :definition     compound-machine
         :on-state-click (fn [path] (swap! clicks conj (js->clj path)))}
        (fn [_root node]
          (let [by-testid #(.querySelector node (str "[data-testid=\"rf-mv-chart-" % "\"]"))
                authed    (layout/node-id [:authenticated])
                title-el  (by-testid (str "compound-title-" authed))
                body-el   (by-testid (str "compound-" authed))
                unauth-el (by-testid (str "node-" (layout/node-id [:unauth])))
                nested-el (by-testid (str "node-" (layout/node-id [:authenticated :browsing])))]
            (is (= ["auto" "none" "pointer" "pointer"]
                   [(.. title-el -style -pointerEvents)
                    (.. body-el -style -pointerEvents)
                    (.. title-el -style -cursor)
                    (.. unauth-el -style -cursor)]))
            (doseq [el [title-el unauth-el nested-el]]
              (dispatch-click! el))
            (is (= [["authenticated"] ["unauth"] ["authenticated" "browsing"]] @clicks))))))))
