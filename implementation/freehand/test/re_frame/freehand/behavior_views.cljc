(ns re-frame.freehand.behavior-views
  "The declarations the `FH-BEHAVIOR-*` suites render.

  Three registered behaviors and the use sites that exercise the closed
  grammar around them. The behaviors record their lifecycle into plain
  atoms rather than touching a host, so the SAME declarations run on the
  JVM (where nothing connects and the atoms stay empty — which is itself
  the inert-marker claim) and in a real browser (where the atoms are the
  lifecycle transcript the mounted suites assert)."
  (:require [re-frame.freehand :as v]))

;; ---------------------------------------------------------------------------
;; The lifecycle transcript
;; ---------------------------------------------------------------------------

(def transcript
  "Every lifecycle call, in order — `{:behavior :op :target :config
  :memory}`. A plain vector, so a suite asserts an exact SEQUENCE rather
  than a count, and reads back the private memory the context carried
  without the memory ever having been visible in the tree."
  (atom []))

(defn reset-transcript! [] (reset! transcript []) nil)

(defn ops
  "Just the operations, in order — the shape most cases pin."
  []
  (mapv :op @transcript))

(defn- record!
  [op {:keys [behavior target config memory]}]
  (swap! transcript conj {:behavior behavior :op op :target target
                          :config config :memory memory})
  nil)

(def dispatches
  "Every outward dispatch a behavior's fenced context ACCEPTED, plus the
  boolean the context answered. `[event accepted?]`."
  (atom []))

(defn reset-dispatches! [] (reset! dispatches []) nil)

;; ---------------------------------------------------------------------------
;; The behaviors
;; ---------------------------------------------------------------------------

(v/defbehavior probe
  "The ordinary case: passive timing, the full lifecycle, and a small
  command roster. Its memory is a plain counter, so a suite can prove the
  memory survives an update and reaches a command without the memory ever
  being visible in the tree."
  {:timing     :passive
   :connect    (fn [ctx] (record! :connect ctx) {:updates 0})
   :update     (fn [{:keys [memory] :as ctx}]
                 (record! :update ctx)
                 (update memory :updates inc))
   :disconnect (fn [ctx] (record! :disconnect ctx) nil)
   :commands   {:mark     (fn [{:keys [node args memory] :as ctx}]
                            (record! :mark ctx)
                            #?(:cljs (when node
                                       (.setAttribute node "data-mark"
                                                      (str (:label args)))))
                            memory)
                :announce (fn [{:keys [dispatch args memory]}]
                            (swap! dispatches conj
                                   [(:event args) (dispatch (:event args))])
                            memory)}})

(v/defbehavior measure
  "The `:layout` arm — work that must finish before the browser paints."
  {:timing     :layout
   :connect    (fn [{:keys [node] :as ctx}]
                 (record! :connect ctx)
                 #?(:cljs (when node (.setAttribute node "data-measured" "layout")))
                 nil)
   :disconnect (fn [ctx] (record! :disconnect ctx) nil)})

(v/defbehavior canvas
  "An OPAQUE behavior — it owns the node's descendants, so Freehand
  children on that node are an error."
  {:opaque  true
   :connect (fn [ctx] (record! :connect ctx) nil)})

;; ---------------------------------------------------------------------------
;; Accepted use sites
;; ---------------------------------------------------------------------------

(v/defview plain
  "The ordinary attachment: an id, a semantic target, and public config."
  [{:keys [label]}]
  [:section.host
   [v/behavior {:use probe :target :probe/one :config {:label (or label "a")}}
    [:div.node "content"]]])

(v/defview no-target
  "A behavior nothing commands needs no target."
  [_]
  [v/behavior {:use probe :config {:label "quiet"}}
   [:div.node]])

(v/defview layout-timed
  "The `:layout` arm at a use site."
  [_]
  [v/behavior {:use measure :target :probe/measured}
   [:div.node]])

(v/defview opaque-host
  "An opaque behavior over an empty node."
  [_]
  [v/behavior {:use canvas :target :probe/canvas}
   [:div.node]])

(v/defview pair
  "Two occurrences under DISTINCT semantic ids — the ordinary multi-instance
  case, and the decoy arm of the command law."
  [_]
  [:section.host
   [v/behavior {:use probe :target :probe/one :config {:label "one"}}
    [:div.node {:data-id "one"}]]
   [v/behavior {:use probe :target :probe/two :config {:label "two"}}
    [:div.node {:data-id "two"}]]])

(v/defview twins
  "Two occurrences claiming ONE semantic id — the ambiguity a command
  refuses rather than resolves."
  [_]
  [:section.host
   [v/behavior {:use probe :target :probe/same :config {:label "one"}}
    [:div.node {:data-id "one"}]]
   [v/behavior {:use probe :target :probe/same :config {:label "two"}}
    [:div.node {:data-id "two"}]]])

(v/defview control-mount
  "The CONTROL: the same markup with no behavior at all. A cleanup
  assertion measured against this cannot mistake React's own bookkeeping
  for the substrate's."
  [_]
  [:section.host
   [:div.node "content"]])

;; ---------------------------------------------------------------------------
;; Refused use sites — one view per arm of the closed grammar
;; ---------------------------------------------------------------------------

(v/defview unknown-option
  [_]
  [v/behavior {:use probe :on-connect [:nope]} [:div.node]])

(v/defview missing-use
  [_]
  [v/behavior {:target :probe/one} [:div.node]])

(v/defview unregistered-use
  [_]
  [v/behavior {:use :nowhere/absent} [:div.node]])

(v/defview config-not-map
  [_]
  [v/behavior {:use probe :config 3} [:div.node]])

(v/defview config-carries-a-callback
  [_]
  [v/behavior {:use probe :config {:limits {:on-done (fn [] nil)}}} [:div.node]])

(v/defview two-children
  [_]
  [v/behavior {:use probe} [:div.node] [:div.other]])

(v/defview view-child
  [_]
  [v/behavior {:use probe} [control-mount {}]])

(v/defview fragment-child
  [_]
  [v/behavior {:use probe} [:<> [:div.node]]])

(v/defview text-child
  [_]
  [v/behavior {:use probe} "just text"])

(v/defview opaque-with-children
  [_]
  [v/behavior {:use canvas} [:div.node "content the host would overwrite"]])
