(ns re-frame.freehand.behaviors
  "REGISTERED BEHAVIORS — the one sanctioned imperative boundary over a
  single DOM node (Spec 004 §Registered behaviors and commands, F4; ruled
  by D013).

  Some integrations are not honestly described by immutable props. A chart
  renderer, a spreadsheet, a code editor, a resize observer, a measurement
  pass — each creates opaque host state, mutates it in place, listens for
  host events, and has to release what it took. Freehand answers that with
  ONE registered lifecycle over ONE node, and refuses to answer it with a
  neutral hook, ref, effect or portal surface.

  Three words carry the whole design, and each of them closes a different
  escape hatch:

  **CLOSED TIMING.** A behavior runs at one of exactly two enumerated
  moments — `:layout` (before the browser paints, for measure-then-place
  work) or `:passive` (after paint, the default). There is no \"run
  whenever\" hook, because an open lifecycle is unanalysable: the set of
  moments at which host state can move is part of the contract, not part
  of the implementation.

  **BOUNDED COMMANDS.** A behavior declares a finite roster of named
  operations, and an application reaches them through ONE effect
  (`:re-frame.freehand.host/command`) carrying data. There is no host
  handle, no instance registry, no service locator, and no queue — a
  command that finds no live connection is REFUSED, never retained for a
  future mount.

  **SEMANTIC-ID TARGETS.** A command addresses a caller-authored id
  declared at the use site — never a render position, a key path, a DOM
  selector or a host object. A behavior that could be found by querying
  the document would be a portal by another name.

  ## The shape

      (v/defbehavior autosize
        {:timing     :layout
         :connect    (fn [{:keys [node config]}] (fit! node) nil)
         :update     (fn [{:keys [node config]}] (fit! node) nil)
         :disconnect (fn [{:keys [node]}] nil)
         :commands   {:refit (fn [{:keys [node]}] (fit! node) nil)}})

      [v/behavior {:use autosize :target :composer/body :config {:max-rows 8}}
       [:textarea.composer {:value draft :on-input [:composer/typed ::v/value]}]]

  The declaration binds `autosize` to the REGISTERED ID
  `:my.ns/autosize` — a plain qualified keyword — so the use site is data
  all the way down and the implementation is registered code. The
  structural tree therefore records a behavior faithfully as a boundary
  node carrying its id, its target and its public config, and carries no
  host object, no node, no closure and no cleanup handle.

  ## The two halves

    the registry, the closed rosters, `read-opts`   COMMON — one law, two
                                                    hosts, both modes
    the connection table, the hooks, the commands   ClojureScript — the
                                                    live half

  On the JVM a behavior is an INERT MARKER: the boundary node and its
  public config are recorded, and nothing runs. A server cannot own a
  browser node, and claiming otherwise in the tree would be a lie a
  hydration would then have to unpick.

  ## Private memory

  Whatever `:connect` RETURNS is the connection's memory, held in this
  namespace's connection table and handed back to `:update`,
  `:disconnect` and every command. It never enters app-db, the structural
  tree, an event vector or a trace — the only way out is an event the
  behavior dispatches through its generation-fenced `:dispatch`.

  Normative owner:
  [`spec/004-Views.md`](../../../../../spec/004-Views.md) §Registered
  behaviors and commands."
  (:require [re-frame.error :as error]
            [re-frame.freehand.eq :as eq]
            [re-frame.fx :as fx]
            #?@(:cljs [["react" :as react]
                       [re-frame.freehand.shell :as shell]
                       [re-frame.router :as router]])))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Identity and the closed rosters
;; ---------------------------------------------------------------------------

(def behavior-view-id
  "The reserved view id of the `v/behavior` attachment boundary — the
  `:view-id` its node carries in the structural tree, on both hosts."
  :re-frame.freehand/behavior)

(def command-fx-id
  "The ONE effect an application reaches a live behavior through. It is
  reserved (Conventions §Reserved fx-ids) and its args are data:

      {:fx [[:re-frame.freehand.host/command
             {:target :invoice/sheet :op :export :args {:filename \"x.xlsx\"}}]]}

  The keyword names `re-frame.freehand.host`, the sanctioned host-facing
  sibling of the public door; there is no second namespace to require,
  because a command is data an ordinary event handler returns."
  :re-frame.freehand.host/command)

(def timings
  "The CLOSED set of moments a behavior may run at.

  `:layout` runs before the browser paints — the only honest home for
  measure-then-place work, which is visibly wrong for one frame if it
  runs after. `:passive` (the default) runs after paint and is what
  everything else wants. There is no third value and no escape to an
  arbitrary schedule: the set of moments host state can move at is part
  of the contract."
  #{:passive :layout})

(def definition-keys
  "The CLOSED roster of `v/defbehavior` definition keys. An unknown key is
  refused at registration — an accepted-and-ignored key is a declaration
  that means something other than it says, and a one-character typo would
  otherwise produce a behavior that silently never runs."
  #{:timing :opaque :connect :update :disconnect :commands})

(def option-keys
  "The CLOSED roster of `[v/behavior {…} node]` option keys.

  `:use` is the registered behavior id (required). `:target` is the
  caller-authored semantic id a command addresses (optional — a behavior
  nothing commands needs none). `:config` is the public configuration,
  data through and through."
  #{:use :target :config})

;; ---------------------------------------------------------------------------
;; Diagnostics
;; ---------------------------------------------------------------------------

(defn- shape [x] (error/diag-value-summary x))
(defn- type-name [x] (name (:type (shape x))))

(defn- bad-args!
  [where reason recovery extra]
  (error/throw-error!
    :rf.error/behavior-bad-args where reason
    {:recovery recovery :extra extra}))

(defn- refused!
  [reason recovery extra]
  (error/throw-error!
    :rf.error/behavior-command-refused
    're-frame.freehand.host/command
    reason
    {:recovery recovery :extra extra}))

;; ---------------------------------------------------------------------------
;; Configuration is DATA — the rule that keeps a use site inspectable
;; ---------------------------------------------------------------------------
;;
;; D013's settled constraint: configuration contains Clojure values and event
;; intents, never callbacks, nodes, refs, cleanup functions, or preconstructed
;; host instances. The structural tree records a behavior's config verbatim, so
;; on the JVM a non-data value is refused where the boundary records its props
;; — but a browser render records nothing, and a rule that only one host
;; enforces is a rule that lets a use site work in development and fail in a
;; server render. So the config is checked HERE, at the behavior's own door,
;; and both hosts answer identically.

(defn- edn-scalar?
  [x]
  (or (string? x) (keyword? x) (number? x) (boolean? x) (nil? x) (symbol? x)
      (uuid? x)
      (instance? #?(:clj java.util.Date :cljs js/Date) x)))

(defn- non-data
  "`[path value]` for the FIRST value inside `x` that is not data, or nil
  when `x` is data all the way down. Read-only and short-circuiting: the
  ordinary case (a small map of scalars) costs one predicate per value and
  allocates nothing."
  [x]
  (cond
    (edn-scalar? x) nil

    (or (map? x) (vector? x))
    (reduce-kv (fn [_ k v]
                 (if-let [found (or (non-data k)
                                    (when-let [[p bad] (non-data v)]
                                      [(into [k] p) bad]))]
                   (reduced found)
                   nil))
               nil x)

    (coll? x)
    (reduce (fn [_ v] (when-let [found (non-data v)] (reduced found))) nil x)

    :else [[] x]))

;; ---------------------------------------------------------------------------
;; The registry — COMMON
;; ---------------------------------------------------------------------------
;;
;; One entry per registered behavior id, on BOTH hosts. The JVM needs it too:
;; the structural render refuses an unregistered id and enforces node opacity,
;; so a use site that is wrong is wrong in a structural test rather than only
;; in a browser.

(defonce ^:private registry (atom {}))

(defn definition
  "The registered definition for behavior `id`, or nil."
  [id]
  (get @registry id))

(defn registered-ids
  "Every registered behavior id — a tooling and test read."
  []
  (set (keys @registry)))

(defn- check-lifecycle!
  [id k f]
  (when (and (some? f) (not (ifn? f)))
    (bad-args!
      'v/defbehavior
      (str "The " k " entry of behavior " id " is a " (type-name f)
           ". A lifecycle entry is a function of one context map.")
      :fix-the-declaration
      {:behavior id :entry k :value (shape f)})))

(defn register!
  "Register `definition` under behavior `id` and RETURN the id — the
  expansion target of `v/defbehavior`, not an authoring form.

  Returning the id is the whole point of the shape: the var a declaration
  binds holds a plain qualified keyword, so a use site carries data and a
  tool reading the structural tree can resolve the behavior without
  holding the implementation."
  [id definition]
  (when-not (qualified-keyword? id)
    (bad-args!
      'v/defbehavior
      (str "A behavior id is a qualified keyword; got " (pr-str id) ".")
      :fix-the-declaration
      {:behavior (shape id)}))
  (when-not (map? definition)
    (bad-args!
      'v/defbehavior
      (str "A behavior definition is a map of the closed roster "
           (pr-str (vec (sort definition-keys))) "; " id " declares a "
           (type-name definition) ".")
      :fix-the-declaration
      {:behavior id :value (shape definition)}))
  (let [unknown (vec (sort (remove definition-keys (keys definition))))
        timing  (get definition :timing :passive)
        cmds    (get definition :commands)]
    (when (seq unknown)
      (bad-args!
        'v/defbehavior
        (str "Behavior definition keys are the closed roster "
             (pr-str (vec (sort definition-keys))) "; " id " declares "
             (pr-str unknown) ". A key outside the roster is refused rather than "
             "ignored — a behavior that silently never runs is the worst outcome "
             "available.")
        :fix-the-declaration
        {:behavior id :unknown-keys unknown}))
    (when-not (contains? timings timing)
      (bad-args!
        'v/defbehavior
        (str ":timing is the CLOSED set " (pr-str (vec (sort timings)))
             " — `:layout` for work that must finish before the browser paints, "
             "`:passive` (the default) for everything else; " id " declares "
             (pr-str timing) ".")
        :fix-the-declaration
        {:behavior id :timing (shape timing)}))
    (doseq [k [:connect :update :disconnect]]
      (check-lifecycle! id k (get definition k)))
    (when (some? cmds)
      (when-not (map? cmds)
        (bad-args!
          'v/defbehavior
          (str ":commands is a map of operation keyword to function; " id
               " declares a " (type-name cmds) ".")
          :fix-the-declaration
          {:behavior id :value (shape cmds)}))
      (doseq [[op f] cmds]
        (when-not (keyword? op)
          (bad-args!
            'v/defbehavior
            (str "A command operation is named by a keyword; " id " declares "
                 (pr-str op) ".")
            :fix-the-declaration
            {:behavior id :op (shape op)}))
        (check-lifecycle! id op f)))
    (when-not (some #(some? (get definition %)) [:connect :update :disconnect :commands])
      (bad-args!
        'v/defbehavior
        (str "Behavior " id " declares no lifecycle and no commands, so attaching "
             "it would do nothing at all. Declare at least one of :connect, "
             ":update, :disconnect or :commands.")
        :fix-the-declaration
        {:behavior id}))
    (swap! registry assoc id (assoc definition :timing timing))
    id))

;; ---------------------------------------------------------------------------
;; The use site — COMMON
;; ---------------------------------------------------------------------------

(defn- element-form?
  "Is `child` a markup form denoting ONE host element? The browser arm of
  the one-node rule: a behavior's child is walked into a React element and
  given the behavior's ref, and only a host element can take one.

  `:<>` (the fragment head, `re-frame.freehand.tree/fragment-tag`) and the
  reserved presence head are keywords too, and both denote a group rather
  than a node — they are named here rather than reached for through the
  walk, which would put the structural emitter underneath this namespace."
  [child]
  (let [head (first child)]
    (and (keyword? head)
         (not= :<> head)
         (not (qualified-keyword? head)))))

(defn- element-node?
  "Is `child` a structural node denoting ONE element? The JVM arm of the
  same rule — by the time a behavior's render body runs on the structural
  host its children are already-walked nodes, and an element node is the
  one variant that carries `:tag`."
  [child]
  (and (map? child) (contains? child :tag)))

(defn- childless?
  "Does the decorated child carry no children of its own? Asked only of an
  OPAQUE behavior, which owns the node's descendants."
  [child]
  (if (map? child)
    (not (contains? child :children))
    ;; a markup form: `[:div]`, `[:div {…}]` — anything longer is content
    (let [rest* (next child)]
      (or (nil? rest*)
          (and (map? (first rest*)) (nil? (next rest*)))))))

(defn read-opts
  "Validate one `[v/behavior {…} node]` call and answer
  `{:use :target :config :child}`.

  The whole closed grammar lives here, so the two execution modes and the
  two hosts agree about one declaration by construction rather than by
  agreement. Every arm raises `:rf.error/behavior-bad-args`."
  [props]
  (let [unknown (vec (sort (remove option-keys (remove #{:children} (keys props)))))
        {:keys [use target config children]} props]
    (when (seq unknown)
      (bad-args!
        'v/behavior
        (str "The [v/behavior {…} node] options are the closed roster "
             (pr-str (vec (sort option-keys))) "; this call carries "
             (pr-str unknown) ".")
        :use-the-closed-behavior-options
        {:unknown-options unknown}))
    (when-not (qualified-keyword? use)
      (bad-args!
        'v/behavior
        (str ":use names the REGISTERED behavior — the qualified keyword a "
             "(v/defbehavior …) declaration binds its var to; got "
             (if (nil? use) "nothing" (str "a " (type-name use))) ".")
        :name-a-registered-behavior
        {:use (shape use)}))
    (when (nil? (definition use))
      (bad-args!
        'v/behavior
        (str "No behavior is registered under " use ". A behavior is registered by "
             "(v/defbehavior …) when its namespace loads, so an unregistered id is "
             "either a misspelling or a namespace this one does not require.")
        :require-the-declaring-namespace
        {:use use :registered (vec (sort (registered-ids)))}))
    (when (and (some? config) (not (map? config)))
      (bad-args!
        'v/behavior
        (str ":config is the behavior's PUBLIC configuration and is a map; got a "
             (type-name config) ".")
        :supply-a-config-map
        {:config (shape config)}))
    (when-let [[path bad] (and (some? config) (non-data config))]
      (bad-args!
        'v/behavior
        (str ":config carries a " (type-name bad)
             (if (seq path) (str " at " (pr-str path)) "")
             ". A behavior's configuration is DATA through and through — it is "
             "recorded verbatim in the structural tree and read by tools, so a "
             "callback, a node, a ref or a preconstructed host instance has no "
             "place in it. Pass an event intent for something the host should "
             "report, and let :connect build what the host needs.")
        :keep-the-config-data
        {:path path :value (shape bad)}))
    (when-not (= 1 (count children))
      (bad-args!
        'v/behavior
        (str "A behavior owns exactly ONE node, so [v/behavior {…} node] takes "
             "exactly one child; this call supplied " (count children)
             ". Wrap the region in a single element, or attach a behavior to each "
             "node that needs one.")
        :decorate-exactly-one-element
        {:children-count (count children)}))
    (let [child (first children)
          ok?   (if (vector? child) (element-form? child) (element-node? child))]
      (when-not ok?
        (bad-args!
          'v/behavior
          (str "A behavior's child is ONE element — the node it owns. A declared "
               "view, a fragment, a presence boundary or text denotes a group or a "
               "value rather than a node, and there is nothing for the behavior to "
               "attach to. Put the element the behavior owns directly under it.")
          :decorate-exactly-one-element
          {:child (shape child)}))
      (when (and (:opaque (definition use)) (not (childless? child)))
        (bad-args!
          'v/behavior
          (str "Behavior " use " is declared {:opaque true} — it owns the node's "
               "descendants — so the node it decorates carries no Freehand children. "
               "Children declared here would be rendered and then overwritten by the "
               "host the moment it connects.")
          :leave-an-opaque-behaviors-node-empty
          {:use use}))
      {:use use :target target :config config :child child})))

;; ---------------------------------------------------------------------------
;; The live half
;; ---------------------------------------------------------------------------

#?(:cljs
   (do

(def ^:private empty-deps #js [])

(defonce ^:private connections
  ;; generation -> {:behavior :target :node :config :memory :frame}
  ;;
  ;; ONE table, keyed by the monotonic CONNECTION GENERATION rather than by
  ;; target. That is what makes the fences exact: a release names the exact
  ;; connection it opened, so a teardown arriving after a replacement
  ;; connected cannot clear the replacement's slot, and two live connections
  ;; claiming one target are VISIBLE (two entries) rather than silently
  ;; collapsed to whichever wrote last.
  (atom {}))

(defonce ^:private generation-counter (atom 0))

(defn connection-count
  "How many behavior connections are live. Zero outside a mount — a test
  asserts the exact integer after teardown rather than trusting a cleanup
  path."
  []
  (count @connections))

(defn target-ids
  "The semantic ids live connections currently claim — a tooling and test
  read. Never a handle: it answers ids, never nodes or memory."
  []
  (into #{} (keep :target) (vals @connections)))

(defn ^:no-doc reset-connections!
  "Drop every connection record WITHOUT running `:disconnect` — a
  test-isolation seam, mirroring `re-frame.freehand.react/reset-boundaries!`.
  Never a production path: a real teardown runs the lifecycle."
  []
  (reset! connections {})
  nil)

(defn- claims-for
  "The live `[generation record]` claims on `target`, in generation order.
  A target is unique among live connections, so this answers zero or one
  pair in every well-formed use — and a longer answer is exactly the
  ambiguity a command must refuse rather than resolve."
  [target]
  (into [] (sort-by key (filter (fn [[_ r]] (= target (:target r))) @connections))))

(defn- advise!
  [reason]
  (when ^boolean js/goog.DEBUG (js/console.warn (str "behavior: " reason)))
  nil)

(defn- dispatcher
  "The generation-fenced outward dispatch a behavior context carries.

  It resolves the connection AT FIRING TIME, so a callback the host kept
  after a disconnect is inert rather than firing into a successor — the
  same law the event plane's retired proxies hold. The frame is the one
  the connection committed under, so an outward intent lands where the
  view that declared it lives."
  [generation]
  (fn dispatch-from-behavior [event]
    (if-let [rec (get @connections generation)]
      (do (router/dispatch! event (cond-> {:source :ui}
                                    (:frame rec) (assoc :frame (:frame rec))))
          true)
      (do (advise!
            (str "an outward dispatch reached a DISCONNECTED generation and was "
                 "dropped. A behavior's context is fenced to the connection that "
                 "minted it, so a host callback that outlives its node is inert. "
                 "Release the host listener in :disconnect."))
          false))))

(defn- context
  [generation rec extra]
  (merge {:node       (:node rec)
          :config     (:config rec)
          :memory     (:memory rec)
          :behavior   (:behavior rec)
          :target     (:target rec)
          :generation generation
          :dispatch   (dispatcher generation)}
         extra))

(defn- remember!
  "Publish `memory` onto connection `generation` — but ONLY while that
  generation is still the installed one. Re-checking currency at the
  narrowest boundary is the same discipline the reactive cell publishes
  under: a lifecycle callback may have torn its own connection down, and a
  write that did not look would resurrect it."
  [generation memory]
  (swap! connections
         (fn [m] (if (contains? m generation)
                   (assoc-in m [generation :memory] memory)
                   m)))
  nil)

(defn- connect!
  "Open one connection and return its generation.

  The record is INSTALLED BEFORE `:connect` runs, so a host callback wired
  during connect can already dispatch through its own fenced context; the
  memory `:connect` returns is written back through [[remember!]], which
  refuses to resurrect a connection the callback itself tore down."
  [{:keys [use target config]} node frame-id]
  (let [generation (swap! generation-counter inc)
        rec        {:behavior use :target target :node node
                    :config config :memory nil :frame frame-id}]
    (swap! connections assoc generation rec)
    (when (and target (< 1 (count (claims-for target))))
      (advise!
        (str "two live connections claim the target " (pr-str target)
             ". A command target is unique among live connections, so a command "
             "addressed to it is REFUSED rather than delivered to whichever "
             "mounted last. Give each occurrence its own semantic id.")))
    (when-let [f (:connect (definition use))]
      (remember! generation (f (context generation rec nil))))
    generation))

(defn- reconcile!
  "Run `:update` when the committed config has MOVED by `rf=`, and not
  otherwise. Equal config is not movement, so a re-render that changes
  nothing touches no host state."
  [generation config]
  (when-let [rec (get @connections generation)]
    (when-not (eq/rf= config (:config rec))
      (let [f (:update (definition (:behavior rec)))]
        (swap! connections
               (fn [m] (if (contains? m generation)
                         (assoc-in m [generation :config] config)
                         m)))
        (when f
          (remember! generation
                     (f (context generation (assoc rec :config config)
                                 {:prev-config (:config rec)})))))))
  nil)

(defn- disconnect!
  "Close connection `generation` exactly once.

  The record is CLAIMED and REMOVED before `:disconnect` runs. That order
  is the contract: a disconnected generation is inert, so the context the
  teardown receives cannot dispatch into the application, and a
  `:disconnect` that throws still leaves the table clean — which is what
  lets a test assert the absence rather than trust the path."
  [generation]
  (let [claimed (volatile! nil)]
    (swap! connections (fn [m]
                         (when-let [rec (get m generation)] (vreset! claimed rec))
                         (dissoc m generation)))
    (when-let [rec @claimed]
      (when-let [f (:disconnect (definition (:behavior rec)))]
        (f (context generation rec nil)))))
  nil)

;; ---------------------------------------------------------------------------
;; The hooks — commit, and nothing between commits
;; ---------------------------------------------------------------------------
;;
;; A behavior connects from a REF and an EFFECT, and React runs both only for
;; a render it COMMITTED. A candidate the host abandons — a suspended
;; transition, a superseded render — runs neither, so its desired lifecycle
;; never reaches the host; building elements performs no host work at all.
;; That is the same law `re-frame.freehand.cell` states for the reactive
;; bundle, realised in the one React position that has the real node in hand.
;;
;; Both timing arms are installed on every render and each is guarded by the
;; behavior's declared timing, so the hook COUNT and ORDER are fixed while the
;; moment the work runs at is the declaration's. Each arm owns its own
;; generation ref: React runs every layout cleanup and effect before any
;; passive one, so a declaration whose timing changed would otherwise have the
;; passive arm's cleanup release the layout arm's fresh connection.

(defn- use-arm!
  "One timing arm — connect/disconnect keyed on the connection's identity,
  and a config reconcile on every commit."
  [use-effect active? node-ref opts frame-id]
  (let [state (react/useRef nil)]
    (use-effect
      (fn []
        (when active?
          (set! (.-current state) (connect! opts (.-current node-ref) frame-id)))
        (fn []
          (when-some [generation (.-current state)]
            (set! (.-current state) nil)
            (disconnect! generation))))
      #js [active? (:use opts) (:target opts) frame-id])
    (use-effect
      (fn []
        (when active?
          (when-some [generation (.-current state)]
            (reconcile! generation (:config opts))))
        js/undefined))))

(defn use-attachment!
  "Install one behavior's lifecycle for this render and answer the ref
  callback the decorated element must carry.

  The callback is created ONCE (`useCallback` over empty deps) — a fresh
  closure per render would make React detach and re-attach the node on
  every commit, which is a lifecycle event the author never declared. The
  node it stores is read at connect time and then REMEMBERED on the
  connection, so a teardown acts on the node it connected to rather than
  on whatever the ref currently holds."
  [opts]
  (let [frame-id (shell/frame-context-frame)
        node-ref (react/useRef nil)
        set-node (react/useCallback
                   (fn [n] (set! (.-current node-ref) n))
                   empty-deps)
        layout?  (= :layout (:timing (definition (:use opts))))]
    (use-arm! react/useLayoutEffect layout?       node-ref opts frame-id)
    (use-arm! react/useEffect       (not layout?) node-ref opts frame-id)
    set-node))

(defn attach
  "Give the decorated element the behavior's ref, and answer it.

  `cloneElement` rather than a wrapper node, deliberately: a behavior owns
  a node the AUTHOR declared, so inserting one of the substrate's own would
  change the document the stylesheet and the host library both address."
  [element ref]
  (react/cloneElement element #js {:ref ref}))

   ))

;; ---------------------------------------------------------------------------
;; The command channel
;; ---------------------------------------------------------------------------
;;
;; D013's bounded half. A command is produced by an ordinary re-frame handler
;; as an effect VALUE, resolves against the live target index, and runs the
;; operation the behavior registered — synchronously, once, against the
;; currently committed connection. It is never queued for a future mount and
;; never replayed after a reconnect: a target that is absent is a REFUSAL, and
;; a future mount is driven by state and config, not by an old imperative
;; request.

(defn command!
  "Perform one `:re-frame.freehand.host/command` effect. Browser-only — a
  command crosses into a live host object, and the JVM has none."
  [args]
  #?(:clj
     (refused!
       (str "The behavior command channel is a browser capability: a command "
            "reaches a live host object, and a structural render owns none. A "
            "structural test asserts the command's DATA — the effect an event "
            "handler returned — and the mounted tier proves the host action.")
       :assert-the-command-data-on-the-structural-host
       {:args (shape args)})
     :cljs
     (do
       (when-not (map? args)
         (refused!
           (str "A behavior command is a map {:target … :op … :args …}; got a "
                (type-name args) ".")
           :supply-a-command-map
           {:args (shape args)}))
       (let [{:keys [target op]} args]
         (when (nil? target)
           (refused!
             (str "A behavior command names its :target — the caller-authored "
                  "semantic id declared at the use site. There is no default "
                  "target and none is derived from render position: an address "
                  "the substrate invented would move when a view was renamed, "
                  "sorted or extracted.")
             :name-the-use-sites-target
             {:args (shape args)}))
         (when-not (keyword? op)
           (refused!
             (str "A behavior command names its :op — one of the operations the "
                  "behavior registered; got "
                  (if (nil? op) "nothing" (str "a " (type-name op))) ".")
             :name-a-registered-operation
             {:op (shape op)}))
         (let [claims (claims-for target)]
           (case (count claims)
             0 (refused!
                 (str "No live behavior connection claims the target "
                      (pr-str target) ". A command is never retained for a "
                      "target that is absent — a future mount is driven by state "
                      "and config, or by a fresh event, and a queued imperative "
                      "request would arrive at a node the application has since "
                      "changed its mind about.")
                 :connect-the-target-or-drive-the-host-through-config
                 {:target target :live (vec (sort-by str (target-ids)))})
             1 (let [[generation rec] (first claims)
                     f                (get (:commands (definition (:behavior rec))) op)]
                 (when (nil? f)
                   (refused!
                     (str "Behavior " (:behavior rec) " registers no " op
                          " operation. The command roster is finite and declared "
                          "with the behavior; an unregistered operation is refused "
                          "rather than reaching a host that has no answer for it.")
                     :name-a-registered-operation
                     {:target target :behavior (:behavior rec) :op op
                      :operations (vec (sort (keys (:commands (definition (:behavior rec))))))}))
                 (remember! generation
                            (f (context generation rec {:op op :args (:args args)})))
                 nil)
             (refused!
               (str (count claims) " live behavior connections claim the target "
                    (pr-str target) ". A command target is unique among live "
                    "connections, so the command is refused rather than delivered "
                    "to whichever mounted last. Give each occurrence its own "
                    "semantic id.")
               :give-each-occurrence-its-own-target
               {:target target :claims (count claims)})))))))

(fx/reg-fx
  command-fx-id
  {:doc       (str "Perform one bounded operation on the live behavior connection "
                   "claiming :target. Refused — never queued — when no live "
                   "connection claims it.")
   :platforms #{:client}}
  (fn [_ctx args] (command! args)))
