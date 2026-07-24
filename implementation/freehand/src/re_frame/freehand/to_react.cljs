(ns ^:no-doc re-frame.freehand.to-react
  "INTERNAL. The runtime of `v/->react` — the OUTWARD bridge, and the only
  direction of traffic Freehand offers without a wrapper.

  Freehand normally points inward: a Freehand tree mounts Freehand
  boundaries. Some React libraries reverse the arrow and want a COMPONENT
  VALUE rather than an element — AG Grid's `cellRenderer`, a drag overlay,
  a virtual list's row component, a plugin API that takes a component.
  This turns a declared view into exactly that value, and nothing more:
  the returned component mounts the descriptor the ordinary way, so events,
  subscriptions, error identity and commit fencing inside it are the same
  ones any Freehand parent would have given it.

  ## Four rules, and they are the whole contract

  1. **A DESCRIPTOR, never a function.** The argument is a `v/defview`
     value. A plain function or a hiccup vector is refused, naming a
     declared view or an explicit React wrapper as the recovery. Declared
     identity is what makes the exported component debuggable — HMR,
     errors and evidence all key on the view id.

  2. **One shallow prop rule, or one explicit mapper.** Without
     `:map-props`, every own enumerable property of the foreign props
     object becomes a props-map entry by EXACT name — `\"person-id\"` is
     `:person-id`, `\"acme/id\"` is `:acme/id` — with its value untouched.
     No camelisation and no deep walk: an AG-Grid parameter object is a
     mutable graph of nodes, handles and callbacks, and `js->clj` over one
     would be expensive and semantically false. A library that hands over
     such an object supplies a `:map-props` adapter instead, which is
     ordinary top-level code at the host edge and is testable as such.

  3. **The bridge owns three names, and the view sees none of them.**
     `frame` selects the frame (below). `children` is React's content
     slot, and it becomes the boundary's TRAILING CHILDREN — the same slot
     it is, so `<PersonCell><Badge/></PersonCell>` puts React content
     inside a Freehand view and the child walk passes each element through
     untouched. `ref` is REFUSED: Freehand has no ref protocol (Spec 004
     §Absent at the declaration and call surface retires the whole neutral
     ref/effect tier), and a `ref` that silently resolved to nothing would
     leave a foreign owner holding a handle that never fills.

  4. **A frame is SELECTED, never created.** An own `frame` prop names or
     carries an already-live frame and scopes it; anything else about it —
     nil, malformed, naming no live frame — fails loud, self-attributed to
     `v/->react` rather than to the shared provider. With NO own `frame`
     prop the exported view resolves its frame by the ordinary ambient
     chain, exactly as a view mounted anywhere else does, and a subtree
     under no frame boundary at all fails with the ordinary
     `:rf.error/no-frame-context`. Own-property PRESENCE decides which
     arm runs, not truthiness, so an explicit `frame={null}` is a stated
     target that fails rather than a silent fall-through to the ambient
     one.

  ## Identity is a guarantee the caller cannot supply

  React reconciles on component TYPE. A bridge that minted a fresh
  function on every call would remount the foreign library's subtree every
  time the exporting expression ran — the exact failure the bridge exists
  to stop people hand-rolling. So the component is cached, and cached on
  the VIEW ID rather than on the descriptor object: a hot reload mints a
  fresh descriptor for the same view, and keying on the object would make
  every reload a remount. The current descriptor lives in a slot the
  cached component reads on each render, so a reload republishes the body
  and the foreign library never notices.

  ## Host

  ClojureScript only, like `v/mount` and its siblings — a React component
  value has no meaning in a JVM structural render, and Freehand's server
  render is `v/render-static`, a structural fold with no React in it. The
  door publishes this verb under a `:cljs` reader conditional, so the
  bridge is ABSENT on the JVM rather than present-and-throwing. That
  absence is the SSR policy: nothing a server render can reach can be an
  exported component, so there is no server-renderer context path here and
  no React internal is read to make one work.

  Normative owner:
  [`spec/004-Views.md`](../../../../../spec/004-Views.md) §The outward
  React bridge."
  (:require ["react" :as react]
            [goog.object :as gobj]
            [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.react :as fh-react]
            [re-frame.freehand.shell :as shell]))

;; ---------------------------------------------------------------------------
;; The three names the bridge owns
;; ---------------------------------------------------------------------------

(def ^:private reserved-frame "frame")
(def ^:private reserved-children "children")
(def ^:private refused-ref "ref")

(defn- own?
  "True when the foreign JS `obj` OWNS `k` as an own property, read through
  `Object.prototype.hasOwnProperty.call` so a null-prototype object — or one
  carrying a property literally named `hasOwnProperty` — cannot break the
  check.

  Unlike a truthiness test it distinguishes an ABSENT `k` from an explicitly
  supplied `k={null}` / `k={undefined}`, which is the exactness the reserved
  `frame` prop turns on: a stated target that names nothing fails loud, and
  only an OMITTED prop resolves ambiently."
  [obj k]
  (.call (.. js/Object -prototype -hasOwnProperty) obj k))

;; ---------------------------------------------------------------------------
;; Refusals
;; ---------------------------------------------------------------------------

(defn- malformed!
  [reason extra]
  (error/throw-error!
    :rf.error/ui-tree-malformed
    'v/->react
    reason
    extra))

(defn- require-descriptor!
  [view]
  (when-not (descriptor/view? view)
    (malformed!
      (str "(v/->react view) exports a DECLARED view and nothing else, but received "
           (pr-str view) ". Pass the view value a `v/defview` bound — (def PersonCell "
           "(v/->react person-cell)) — not its id keyword, not a rendered form, and "
           "not a plain function. A foreign protocol that needs hooks, refs or a "
           "React lifecycle is a wrapper you write, not a view you export.")
      {:recovery :export-a-declared-view-or-write-a-wrapper
       :extra    {:value (error/diag-value-summary view)}}))
  view)

(defn- require-opts!
  "Validate the CLOSED option roster and answer the `:map-props` adapter, or
  nil. One key, and the roster is closed for the reason D014 gives: the
  moment a bridge grows prop schemas, child conversion and callback maps it
  has become a second React component model, and a short wrapper is clearer
  than any of it."
  [opts]
  (when (some? opts)
    (when-not (map? opts)
      (malformed!
        (str "(v/->react view opts) takes an options MAP, but received "
             (pr-str opts) ".")
        {:recovery :supply-an-options-map
         :extra    {:value (error/diag-value-summary opts)}}))
    (let [unknown (remove #{:map-props} (keys opts))]
      (when (seq unknown)
        (malformed!
          (str "(v/->react view opts) has one option, :map-props. "
               (pr-str (vec unknown)) " is not it. The bridge deliberately grows no "
               "further options — prop schemas, child conversion, ref forwarding and "
               "callback maps belong in an explicit React wrapper, where they are "
               "visibly React's protocol rather than the substrate's vocabulary.")
          {:recovery :use-map-props-or-write-a-wrapper
           :extra    {:unknown (vec unknown)}})))
    (when-let [f (:map-props opts)]
      (when-not (fn? f)
        (malformed!
          (str ":map-props is the ONE adapter from the foreign props object to the "
               "view's props map, so it must be a function of one argument; received "
               (pr-str f) ".")
          {:recovery :supply-a-one-argument-function
           :extra    {:value (error/diag-value-summary f)}}))))
  (:map-props opts))

(defn- refuse-ref!
  [js-props view-id]
  (when (own? js-props refused-ref)
    (malformed!
      (str "A React `ref` reached " view-id " through (v/->react …), and a Freehand "
           "view has no ref protocol to give it — the neutral ref / effect tier is a "
           "stated absence, not an omission. An imperative handle is React's protocol: "
           "write an explicit React wrapper that owns the ref, or own the node with a "
           "registered behavior and address it by its semantic id.")
      {:recovery :write-a-wrapper-or-use-a-behavior
       :extra    {:view-id view-id}})))

(defn- require-props-map!
  "THE props-map law, applied to whichever arm produced the map: it is a map,
  and it does not carry the reserved `:frame`.

  In the shallow arm `:frame` cannot arrive — the bridge consumed the foreign
  property — so the check is free. In the `:map-props` arm the adapter is
  authoring the props map itself, and a `:frame` key there is a genuine
  collision with the name the bridge reserves: the view would receive a prop
  the bridge is simultaneously reading as a frame target, and only one of the
  two readings can be the one that happens."
  [props view-id]
  (when-not (map? props)
    (malformed!
      (str ":map-props must return the ONE props map " view-id " is mounted with, "
           "but it returned " (pr-str props) ".")
      {:recovery :return-one-props-map
       :extra    {:view-id view-id :value (error/diag-value-summary props)}}))
  (when (contains? props :frame)
    (malformed!
      (str ":frame is RESERVED by (v/->react …) — it selects the frame the exported "
           "subtree runs in — so the props map " view-id " is mounted with may not "
           "carry it. Rename the view's prop, or select the frame with the `frame` "
           "prop on the exported component.")
      {:recovery :rename-the-prop
       :extra    {:view-id view-id}}))
  props)

;; ---------------------------------------------------------------------------
;; The two conversions
;; ---------------------------------------------------------------------------

(defn- shallow-props
  "THE default conversion: one shallow pass over the foreign object's own
  enumerable properties, each key read as the props-map key of the same exact
  name and each value carried across untouched. The three names the bridge
  owns are excluded — `frame` is the frame target, `children` is the
  boundary's trailing children, and `ref` was already refused."
  [js-props]
  (persistent!
    (reduce (fn [m k]
              (if (or (= reserved-frame k) (= reserved-children k))
                m
                (assoc! m (keyword k) (gobj/get js-props k))))
            (transient {})
            (js/Object.keys js-props))))

(defn- trailing-children
  "React's `children` prop as the boundary's trailing children, in order.
  React hands over one child directly and several as an array; both collapse
  here, and each element rides the ordinary child walk, which already passes a
  finished React element straight through."
  [js-props]
  (if (own? js-props reserved-children)
    (let [kids (gobj/get js-props reserved-children)]
      (cond
        (nil? kids)   []
        (array? kids) (vec (array-seq kids))
        :else         [kids]))
    []))

;; ---------------------------------------------------------------------------
;; Frame selection
;; ---------------------------------------------------------------------------

(defn- scope
  "Scope `element` to the frame the own `frame` prop names, or answer it
  unchanged when the prop is absent.

  SCOPE-ONLY: nothing is created, refreshed or destroyed. Frame creation
  belongs to the host application's boot, and a bridge that quietly minted one
  would split an application into two runtimes that agree about nothing. Every
  typed failure names `v/->react`, not the shared provider, so the diagnostic
  lands at the surface the caller actually used."
  [js-props element]
  (if-not (own? js-props reserved-frame)
    element
    (let [target (gobj/get js-props reserved-frame)
          id     (frame/require-frame-provider-target! target 'v/->react)]
      (when (nil? (frame/frame id))
        (error/throw-error!
          :rf.error/frame-provider-frame-absent
          'v/->react
          (str "(v/->react …) received a `frame` prop naming " (pr-str id)
               ", but no live frame is registered under it — it was never created, or "
               "it has already been destroyed. The bridge SCOPES a live frame and "
               "creates none: create it in the host application's boot, or render the "
               "exported component under a frame boundary that already scopes one and "
               "omit the prop.")
          {:recovery :ensure-or-create-the-frame
           :extra    {:frame id}}))
      (shell/provide-frame id element))))

;; ---------------------------------------------------------------------------
;; The exported component, and its identity
;; ---------------------------------------------------------------------------

(defonce ^:private exports
  ;; `[view-id map-props] -> {:slot <volatile holding the CURRENT descriptor>
  ;;                          :component <the stable React component>}`
  ;;
  ;; Keyed by the view id and the adapter's identity — the two facts that
  ;; decide what the exported component DOES. Deliberately not by the
  ;; descriptor object: a reload mints a fresh one for the same view, and a
  ;; fresh key would mint a fresh component type, which is React's instruction
  ;; to unmount the foreign subtree and build a new one. `defonce` so the cache
  ;; survives a namespace reload for the same reason.
  (atom {}))

(defn- exported-component
  [slot map-props view-id]
  (let [component
        (fn freehand->react [js-props]
          (let [view  @slot
                _     (refuse-ref! js-props view-id)
                props (require-props-map!
                        (if map-props (map-props js-props) (shallow-props js-props))
                        view-id)
                kids  (trailing-children js-props)]
            (scope js-props (fh-react/mount-view view (into [props] kids)))))]
    (gobj/set component "displayName" (str "v/->react(" view-id ")"))
    component))

(defn ->react
  "Runtime of `(v/->react view)` / `(v/->react view {:map-props f})` — see the
  namespace docstring. Answers the stable React component."
  ([view] (->react view nil))
  ([view opts]
   (require-descriptor! view)
   (let [map-props (require-opts! opts)
         view-id   (:view-id (descriptor/describe view))
         k         [view-id map-props]]
     (if-let [entry (get @exports k)]
       ;; The reload seam. Republish the CURRENT descriptor into the slot the
       ;; live component already reads, and hand back the component React has
       ;; been reconciling on — so a redefined body reaches the next render
       ;; without the foreign library seeing a type change.
       (do (vreset! (:slot entry) view)
           (:component entry))
       (let [slot      (volatile! view)
             component (exported-component slot map-props view-id)]
         (swap! exports assoc k {:slot slot :component component})
         component)))))

(defn ^:no-doc export-cache
  "The export cache projected as `{[view-id map-props] revision-free-entry}` —
  a RETENTION seam for tests, never application API."
  []
  (into {} (map (fn [[k v]] [k (:component v)])) @exports))

(defn ^:no-doc reset-exports!
  "Drop every cached export — a test-isolation seam only, mirroring
  [[re-frame.freehand.react/reset-boundaries!]]."
  []
  (reset! exports {})
  nil)
