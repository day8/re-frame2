(ns re-frame.freehand.pilot-react-interop-react
  "The F5i pilot's REACT half — the only way a third-party React component
  reaches the page from Freehand in this tree, and what it costs.

  `pilot-react-interop` explains why: the qualified host leaf and the
  explicit React wrapper are both refused by the emitters today, and
  `v/->react` does not exist. The one shape that IS reachable — a registered
  behavior — hands its implementation a DOM NODE, not a React parent. So an
  adopter who needs `<ReactFlow>`, `<VegaLite>`, `<AgGridReact>` on the page
  right now has exactly one move available:

      open a SECOND React root inside the node an `{:opaque true}` behavior
      owns, and render the foreign component into it.

  That works. This namespace does it twice — once against a purpose-built
  component that exercises the whole React ABI (props, children, a forwarded
  ref, an imperative handle, mount/unmount effects, a listener it must
  release), and once against `@xyflow/react`, a real third-party React
  component already in this repo's dependency tree.

  ## The costs, stated up front

  The nested root is a SEPARATE React tree. It does not inherit React context
  from the Freehand tree above it, its events do not bubble through the
  Freehand tree's React handlers, it does not participate in the outer tree's
  Suspense boundaries or transitions, and it has no server rendering at all.
  It is also ONE-WAY: the foreign component's children are React's, so
  Freehand content cannot be interleaved into them. `Radix`-shaped compound
  components and `flexRender`-shaped cell APIs need exactly that
  interleaving, which is why they are reported blocked rather than
  worked-around.

  Everything here is counted. `live-roots`, `live-probe-mounts` and
  `live-probe-listeners` are read by the mounted suite AFTER teardown, and
  the claim is an exact zero measured against a control mount."
  (:require ["@xyflow/react" :as xyflow]
            ["react" :as react]
            ["react-dom/client" :as rdc]
            [re-frame.core :as rf]
            [re-frame.freehand :as v]))

;; ===========================================================================
;; The ledger — what the React half is holding right now
;; ===========================================================================

(def ^:private ledger
  (atom {:roots 0 :probe-mounts 0 :probe-listeners 0
         :roots-opened 0 :roots-closed 0}))

(defn ledger-snapshot [] @ledger)
(defn reset-ledger!
  []
  (reset! ledger {:roots 0 :probe-mounts 0 :probe-listeners 0
                  :roots-opened 0 :roots-closed 0})
  nil)

(defn live-roots [] (:roots @ledger))
(defn live-probe-mounts [] (:probe-mounts @ledger))
(defn live-probe-listeners [] (:probe-listeners @ledger))

;; ===========================================================================
;; `acme-widget` — a third-party React component, ABI-complete
;; ===========================================================================
;;
;; Written the way a real React library writes one, because the pilot's
;; question is whether the boundary can carry a real ABI and not whether it
;; can carry a `<div>`:
;;
;;   * value props in, including a callback prop the host invokes;
;;   * children React owns;
;;   * `forwardRef` + `useImperativeHandle` — the imperative handle a caller
;;     is expected to hold and call methods on;
;;   * a mount effect that installs a real `window` listener, and a cleanup
;;     that must release it.

(def acme-widget
  (react/forwardRef
    (fn [^js props ref]
      (let [label   (.-label props)
            tick    (.-tick props)
            on-ping (.-onPing props)]
        (react/useImperativeHandle
          ref
          (fn []
            #js {:ping (fn [] (when on-ping (on-ping (str "ping:" label))) true)})
          #js [label on-ping])
        (react/useEffect
          (fn []
            (let [listener (fn [_] nil)]
              (.addEventListener js/window "resize" listener)
              (swap! ledger (fn [l] (-> l
                                        (update :probe-mounts inc)
                                        (update :probe-listeners inc))))
              (fn []
                (.removeEventListener js/window "resize" listener)
                (swap! ledger (fn [l] (-> l
                                          (update :probe-mounts dec)
                                          (update :probe-listeners dec))))
                nil)))
          #js [])
        (react/createElement
          "div"
          #js {:className "acme-widget" :data-label label :data-tick (str tick)}
          (react/createElement "span" #js {:className "acme-widget-label"} label)
          (.-children props))))))

;; ===========================================================================
;; The nested-root plumbing — the SAME two functions both behaviors use
;; ===========================================================================
;;
;; Two functions, and that is genuinely the whole workaround. It is small,
;; which is the honest half of the report: what it is not is FREE, and the
;; costs live in the namespace docstring rather than in the code.

(defn- open-root!
  [node element]
  (let [root (rdc/createRoot node)]
    (swap! ledger (fn [l] (-> l (update :roots inc) (update :roots-opened inc))))
    (.render root element)
    root))

(defn- close-root!
  [root]
  (.unmount root)
  (swap! ledger (fn [l] (-> l (update :roots dec) (update :roots-closed inc))))
  nil)

;; ===========================================================================
;; INTEGRATION 2a — the ABI-complete component through a nested root
;; ===========================================================================
;;
;; `:config` is DATA, so the behavior — not the use site — decides which
;; component is rendered and how the data becomes props. That constraint
;; reads as a limitation and is not one: a chart's props ARE data, and
;; forcing the mapping into one named place is where you want it. What it
;; DOES forbid is a generic `[v/behavior {:use react-host :config {:component
;; SomeComponent}}]`, so every foreign component needs its own registration.

(defn- widget-element
  [config handle on-ping]
  (react/createElement
    acme-widget
    #js {:label  (str (:label config))
         :tick   (:tick config)
         :ref    handle
         :onPing on-ping}
    (react/createElement "em" nil "react children")))

(v/defbehavior acme-widget-host
  "Mount `acme-widget` into a nested React root inside the node this
  behavior owns."
  {:opaque true
   :timing :passive
   :connect
   (fn [{:keys [node config dispatch]}]
     (let [handle  (react/createRef)
           on-ping (fn [s] (dispatch [:acme/pinged s]))]
       {:root    (open-root! node (widget-element config handle on-ping))
        :handle  handle
        :on-ping on-ping}))
   :update
   (fn [{:keys [config memory]}]
     (when memory
       (.render (:root memory)
                (widget-element config (:handle memory) (:on-ping memory))))
     memory)
   :disconnect
   (fn [{:keys [memory]}]
     (when memory (close-root! (:root memory)))
     nil)
   :commands
   {;; The imperative handle a React library expects a caller to hold is
    ;; reached through the bounded command channel — which is the RIGHT
    ;; place for it. The handle never leaves the behavior's private memory,
    ;; so no application value carries a live host object.
    :ping (fn [{:keys [memory]}]
            (when-let [h (some-> memory :handle .-current)]
              (.ping h))
            memory)}})

;; ===========================================================================
;; INTEGRATION 2b — `@xyflow/react`, a REAL third-party component
;; ===========================================================================
;;
;; React Flow is a genuine third-party React component library, already a
;; devDependency of `implementation/package.json` (the Xray machine chart
;; consumes it). Nothing about it was adapted for this pilot: the behavior
;; hands it the props its own documentation names, in a container with real
;; dimensions, and reads the DOM back.

(def ^:private ReactFlow (.-ReactFlow xyflow))

(defn- flow-element
  [{:keys [nodes edges]}]
  (react/createElement
    "div"
    #js {:style #js {:width "320px" :height "200px"}}
    (react/createElement
      ReactFlow
      #js {:nodes (clj->js (or nodes []))
           :edges (clj->js (or edges []))
           :fitView true
           ;; Deliberately inert: the pilot is measuring the boundary, not
           ;; React Flow's interaction model, and a pan/zoom listener would
           ;; add noise to the retained-listener count.
           :nodesDraggable false
           :panOnDrag      false
           :zoomOnScroll   false
           :proOptions     #js {:hideAttribution true}})))

(v/defbehavior react-flow-host
  "A React-Vega-class chart component — a real `@xyflow/react` graph —
  reached through a nested React root."
  {:opaque true
   :timing :passive
   :connect    (fn [{:keys [node config]}]
                 {:root (open-root! node (flow-element config))})
   :update     (fn [{:keys [config memory]}]
                 (when memory (.render (:root memory) (flow-element config)))
                 memory)
   :disconnect (fn [{:keys [memory]}]
                 (when memory (close-root! (:root memory)))
                 nil)})

;; ===========================================================================
;; The use sites
;; ===========================================================================

(rf/reg-event :acme/pinged
  (fn [{:keys [db]} [_ s]] {:db (assoc-in db [:acme :last-ping] s)}))

(rf/reg-sub :acme/last-ping (fn [db _] (get-in db [:acme :last-ping])))

(v/defview widget-page
  [{:keys [label tick]}]
  [:section.widget-page
   [v/behavior {:use    acme-widget-host
                :target :acme/widget
                :config {:label (or label "alpha") :tick (or tick 0)}}
    [:div.widget-host]]])

(v/defview flow-page
  [{:keys [nodes edges]}]
  [:section.flow-page
   [v/behavior {:use    react-flow-host
                :target :acme/flow
                :config {:nodes nodes :edges edges}}
    [:div.flow-host]]])

(v/defview control-widget-page
  "The CONTROL: identical markup, no behavior, no nested root."
  [_]
  [:section.widget-page
   [:div.widget-host]])
