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
  future mount. It resolves in the FRAME its event ran in, so a command
  reaches nothing a sibling frame owns.

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

  **`:connect` establishes it, and NOTHING ELSE writes it.** `:update`,
  a command and `:disconnect` RECEIVE the memory and their return values
  are ignored, because the overwhelmingly common shape in the libraries
  this boundary exists for is a mutator that returns nothing at all —
  `map.setOptions(…)`, `workbook.setValue(…)`, `chart.update(spec)`,
  `addEventListener` — and a lifecycle entry whose return replaced the
  memory would have the FIRST such call erase the very instance
  `:disconnect` has to release (rf2-wj1ao). So the memory is the
  connection's, established once with it. An adapter whose host state
  genuinely EVOLVES returns a mutable cell — an atom, a volatile, a JS
  object — from `:connect` and mutates it in place, which is the honest
  shape for a boundary whose whole subject is imperative host state.

  ## The tool plane

  Two read-only projections, and nothing else: [[active-connections]] —
  which behaviors are connected, under which frame, claiming which
  semantic ids, on which public config — and [[command-log]], the bounded
  window of command traffic and its outcomes. Both answer VALUES. Neither
  answers a node, a memory, or anything a caller could reach one through,
  and neither adds an application event to carry them.

  BOTH CROSS THE PUBLIC DOOR, as `v/active-connections` and
  `v/command-log` at the `tooling` tier. A tool projection a tool cannot
  reach without depending on an unsupported namespace is not a tool
  projection, and `re-frame.freehand` is the one public door
  (Conventions §Freehand). What does NOT cross is the pair of summaries
  and the window bound — [[connection-count]], [[target-ids]] and
  [[command-log-limit]] are each derivable from a projection in one
  form, so they stay implementation/test helpers under `^:no-doc`.

  Normative owner:
  [`spec/004-Views.md`](../../../../../spec/004-Views.md) §Registered
  behaviors and commands."
  (:require [re-frame.error :as error]
            [re-frame.freehand.eq :as eq]
            [re-frame.freehand.events :as events]
            [re-frame.fx :as fx]
            #?@(:cljs [["react" :as react]
                       [re-frame.freehand.refs :as refs]
                       [re-frame.freehand.shell :as shell]
                       [re-frame.performance :as performance]
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
  ;; A roster callback carrier is `ifn?` — it implements the host call
  ;; protocol in order to THROW (rf2-yn5nj) — so `ifn?` alone would let one
  ;; register here and defer the refusal to the connect that calls it, with
  ;; a message about foreign props that has nothing to do with a lifecycle
  ;; slot. It is named, so the refusal stays where it was: at registration.
  (when (and (some? f) (or (events/callback? f) (not (ifn? f))))
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

(defn- opaque-child!
  "Raise the opaque-child law for behavior `use`: it is declared
  `{:opaque true}`, so it owns its node's descendants and the node it decorates
  carries no Freehand children — children declared there would render and then
  be overwritten the moment the host connects.

  The three paths reach ONE diagnostic once each has decided, in the child
  representation it actually holds, that the decorated node is non-empty: the
  JVM structural render and the interpreted browser walk through
  [[read-opts]]'s [[childless?]] over a form or a structural node, and the
  compiled browser path through [[element-childless?]] over the already-built
  React element. One raise, so the modes agree rather than merely coincide."
  [use]
  (bad-args!
    'v/behavior
    (str "Behavior " use " is declared {:opaque true} — it owns the node's "
         "descendants — so the node it decorates carries no Freehand children. "
         "Children declared here would be rendered and then overwritten by the "
         "host the moment it connects.")
    :leave-an-opaque-behaviors-node-empty
    {:use use}))

(defn check-attach-opts!
  "Validate the closed option roster and the `:use`/`:config` values of one
  `[v/behavior {…} node]` call, and answer `{:use :target :config}` — every
  arm `read-opts` runs EXCEPT the child.

  The child is validated where each mode can prove it: the compiled analyzer
  proves the one-element law at build, and [[read-opts]] (the JVM structural
  render and the interpreted browser walk) checks the child SHAPE it was
  handed. Both reach THIS for the use and the config, so a compiled attachment
  and its interpreted twin refuse an unregistered id or a non-data config with
  the one diagnostic rather than two. Every arm raises
  `:rf.error/behavior-bad-args`."
  [props]
  (let [unknown (vec (sort (remove option-keys (remove #{:children} (keys props)))))
        {:keys [use target config]} props]
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
    {:use use :target target :config config}))

(defn read-opts
  "Validate one `[v/behavior {…} node]` call and answer
  `{:use :target :config :child}`.

  The whole closed grammar lives here, so the two execution modes and the
  two hosts agree about one declaration by construction rather than by
  agreement. Every arm raises `:rf.error/behavior-bad-args`."
  [props]
  (let [{:keys [use target config]} (check-attach-opts! props)
        children (:children props)]
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
        (opaque-child! use))
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

;; ---------------------------------------------------------------------------
;; The tool plane — read-only projections, never a handle
;; ---------------------------------------------------------------------------
;;
;; D013's eighth point of command semantics: the test/tool plane sees the live
;; connections and the command traffic, and sees NEITHER private instance nor
;; any application event invented to carry them. The reads below are the whole
;; of that plane, and what they OMIT is the point — no node, no memory, no
;; closure and no route back to one. A projection that answered with a host
;; object would be the instance registry this contract exists to refuse,
;; reached through the inspection door instead of the front one.
;;
;; They are reads, not a stream: a tool asks, it is not called back, and
;; nothing here dispatches. Lifecycle facts are tool evidence, not domain
;; events.

(defn active-connections
  "Every live behavior connection as DATA, oldest first —
  `{:generation :behavior :frame :target :config}`, with `:frame`,
  `:target` and `:config` present only where there is one.

  This is what a tool renders and a test asserts: which behaviors are
  connected, under which frame, claiming which semantic ids, running on
  which public config. The private memory and the host node are absent by
  CONSTRUCTION rather than by redaction — the projection is built from the
  record's public half, so there is no path from an inspection read to a
  live host object."
  []
  (into []
        (map (fn [[generation rec]]
               (cond-> {:generation generation :behavior (:behavior rec)}
                 (some? (:frame rec))  (assoc :frame (:frame rec))
                 (some? (:target rec)) (assoc :target (:target rec))
                 (some? (:config rec)) (assoc :config (:config rec)))))
        (sort-by key @connections)))

(defn ^:no-doc connection-count
  "How many behavior connections are live — the scalar summary of
  [[active-connections]]. Zero outside a mount, so a test asserts the exact
  integer after teardown rather than trusting a cleanup path.

  IMPLEMENTATION / TEST HELPER, deliberately: it does NOT cross the public
  door with [[active-connections]], because `(count (v/active-connections))`
  is the same answer and a supported surface earns its place by being
  something a reader cannot compute for itself."
  []
  (count @connections))

(defn ^:no-doc target-ids
  "The semantic ids live connections currently claim, across every frame —
  the set summary of [[active-connections]]. Never a handle: it answers
  ids, never nodes or memory. A command resolves per-frame, so two frames
  claiming one id appear here once; [[active-connections]] is the read that
  tells them apart.

  IMPLEMENTATION / TEST HELPER for the same reason [[connection-count]] is:
  `(into #{} (keep :target) (v/active-connections))` is the same answer, and
  the projection is strictly more informative because it tells two frames
  apart."
  []
  (into #{} (keep :target) (vals @connections)))

(def ^:no-doc command-log-limit
  "The command-traffic projection is a BOUNDED window — the most recent
  rows and nothing older. A tool wants the last few commands and what
  became of them; an unbounded log would be a retention leak dressed up as
  evidence, growing for as long as a session lives.

  The BOUND is public contract; this NUMBER is not. It stays an
  implementation/test constant so the window can be retuned without a
  published API change — a tool reads the rows it was handed and never
  needs to know how many more there might have been. Retention
  configuration is an explicit non-goal."
  64)

(defonce ^:private command-traffic
  ;; The rows [[command-log]] answers, oldest first. Values only — an entry
  ;; is built from what the command NAMED and what the channel DECIDED, so
  ;; the log retains no connection, no node and no memory, and a torn-down
  ;; generation is not kept alive by having once been commanded.
  (atom []))

(defn- record-command!
  "Append one traffic row, dropping whatever falls out of the window."
  [entry]
  (swap! command-traffic
         (fn [log]
           (let [log' (conj log entry)
                 over (- (count log') command-log-limit)]
             (if (pos? over) (into [] (subvec log' over)) log'))))
  nil)

(defn command-log
  "The command-traffic projection — every command the channel handled,
  oldest first, bounded to the most recent [[command-log-limit]] rows:
  `{:frame :target :op :behavior :generation :outcome}` with `:outcome`
  either `:delivered` or `:refused`.

  A row records what was ASKED and what the channel DECIDED — never what
  the host made of it. The operation's return value is the connection's
  private memory and has no representation here, and a delivered row is
  written before the operation runs, so a command that crashes its host is
  in the log rather than missing from it.

  `:behavior` and `:generation` appear when the channel RESOLVED a
  connection — every delivered row, and the refusal of an operation a
  found behavior does not register. A refusal that resolved nothing has no
  honest value for either and carries neither. `:target` and `:op` appear
  only when the command named them, which a malformed one does not."
  []
  @command-traffic)

(defn ^:no-doc reset-connections!
  "Drop every connection record and every command-traffic row WITHOUT
  running `:disconnect` — the live half's test-isolation seam, mirroring
  `re-frame.freehand.react/reset-boundaries!`. Never a production path: a
  real teardown runs the lifecycle, and no application clears a tool read."
  []
  (reset! connections {})
  (reset! command-traffic [])
  nil)

(defn- claims-for
  "The live `[generation record]` claims on `target` WITHIN `frame`, in
  generation order.

  The frame is half the address. A connection is committed under the frame
  its view was mounted in, and a command resolves in the frame the effect
  was produced in, so a claim in another frame is not a claim a command can
  see — frame isolation is the same law that stops a subscription reaching
  into a sibling frame, and a host command is no different. Two frames
  legitimately mounting the same library's target are therefore two
  addresses rather than one ambiguity.

  A target is unique among live connections IN ONE FRAME, so this answers
  zero or one pair in every well-formed use — and a longer answer is
  exactly the ambiguity a command must refuse rather than resolve. A
  connection committed under no frame boundary at all carries `nil`, and is
  addressable only from a command issued under no frame."
  [frame target]
  (into [] (sort-by key (filter (fn [[_ r]] (and (= target (:target r))
                                                 (= frame (:frame r))))
                                @connections))))

(defn- targets-in
  "The semantic ids live connections in `frame` claim — what an absent-target
  refusal can honestly offer as the alternatives it CAN see. Listing the
  whole process would name ids the command could never have reached."
  [frame]
  (into #{} (comp (filter #(= frame (:frame %))) (keep :target)) (vals @connections)))

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
  write that did not look would resurrect it.

  ONE call site, and that is the law rather than an accident:
  [[connect!]] publishes what `:connect` answered and nothing else ever
  writes memory again (§Private memory). `:update`, a command and
  `:disconnect` are handed the memory through [[context]] and their
  returns are discarded — a void-returning host mutator is the ordinary
  case, not the exception, and a second writer here would let one erase
  the instance the teardown needs (rf2-wj1ao)."
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
    (when (and target (< 1 (count (claims-for frame-id target))))
      (advise!
        (str "two live connections in frame " (pr-str frame-id) " claim the target "
             (pr-str target) ". A command target is unique among the live "
             "connections of ONE frame, so a command addressed to it is REFUSED "
             "rather than delivered to whichever mounted last. Give each "
             "occurrence its own semantic id.")))
    (when-let [f (:connect (definition use))]
      (remember! generation (f (context generation rec nil))))
    generation))

(defn- reconcile!
  "Run `:update` when the committed config has MOVED by `rf=`, and not
  otherwise. Equal config is not movement, so a re-render that changes
  nothing touches no host state.

  `:update` is called for its EFFECT on the host. Its return is discarded
  and the memory is left exactly as `:connect` established it
  (§Private memory) — the ordinary `(.setOptions map (clj->js config))`
  answers `undefined`, and writing that back would erase the instance
  `:disconnect` must release."
  [generation config]
  (when-let [rec (get @connections generation)]
    (when-not (eq/rf= config (:config rec))
      (let [f (:update (definition (:behavior rec)))]
        (swap! connections
               (fn [m] (if (contains? m generation)
                         (assoc-in m [generation :config] config)
                         m)))
        (when f
          (f (context generation (assoc rec :config config)
                      {:prev-config (:config rec)}))))))
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

(defn- release!
  "Release whatever connection this arm holds, naming its exact generation."
  [state]
  (when-some [generation (.-current state)]
    (set! (.-current state) nil)
    (disconnect! generation))
  nil)

(defn- reconcile-arm!
  "Bring this arm's connection up to date with the committed use site:
  connect when it holds none, RE-OPEN when the connection's identity moved,
  and otherwise let the config diff decide whether the host is touched at
  all.

  The identity comparison lives here, in Clojure, rather than in a React
  deps array. A deps array is compared with `Object.is`, and a ClojureScript
  keyword rebuilt by each render is a different object — so a `:use` or a
  `:target` in a dep slot would make every commit a teardown and a fresh
  connection, which is precisely the churn `:update` exists to avoid."
  [state node opts frame-id]
  (let [generation (.-current state)
        rec        (when generation (get @connections generation))]
    (cond
      (nil? rec)
      (set! (.-current state) (connect! opts node frame-id))

      (or (not= (:use opts) (:behavior rec))
          (not= (:target opts) (:target rec))
          (not= frame-id (:frame rec))
          (not (identical? node (:node rec))))
      (do (release! state)
          (set! (.-current state) (connect! opts node frame-id)))

      :else
      (reconcile! generation (:config opts))))
  nil)

(defn- use-arm!
  "One timing arm.

  TWO effects, and the split is the contract. The first owns RELEASE and
  nothing else — its dep array holds one boolean, so it re-runs only when
  this arm stops being the one that owns the behavior, and React runs its
  cleanup on unmount whatever the deps say. The second runs on EVERY commit
  and decides, in Clojure, whether that commit is a connection, a
  re-connection or a config reconcile.

  Each arm keeps its OWN generation. React runs every layout cleanup and
  effect before any passive one, so two arms sharing one slot would let the
  passive arm's cleanup release the layout arm's fresh connection the moment
  a declaration's timing changed."
  [use-effect active? node-ref opts frame-id]
  (let [state (react/useRef nil)]
    (use-effect
      (fn [] (fn [] (release! state)))
      #js [active?])
    (use-effect
      (fn []
        (when active?
          (reconcile-arm! state (.-current node-ref) opts frame-id))
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
  change the document the stylesheet and the host library both address.

  The ref is CHAINED onto whatever the element already carries rather than
  written over it. An element may declare a top-layer desired state and
  carry a behavior at once — the two are orthogonal in intent, and Spec
  004 says so — and a bare write would silently disable whichever
  mechanism wrote first. The behavior is a decoration applied OVER the
  element, so it takes the node after the element's own intrinsics and
  releases before them ([[re-frame.freehand.refs]])."
  [element ref]
  (react/cloneElement element #js {:ref (refs/chain (refs/element-ref element) ref)}))

;; ---------------------------------------------------------------------------
;; The COMPILED-tier realisation (rf2-drpa3.127)
;; ---------------------------------------------------------------------------
;;
;; A compiled body resolved the decorated element at build time, so the browser
;; needs no walk and no candidate of its own here: the element's event sites
;; were already committed under the compiled view's candidate, and its
;; one-element shape was proven by the compiled analyzer. What is left is
;; exactly the lifecycle PLUS the one child-dependent law the build could not
;; settle — and that is the interpreted boundary's own
;; `use-attachment!`/`attach`/`check-attach-opts!` plus the opaque-child guard,
;; reached identically, so a compiled attachment connects, updates, commands,
;; tears down AND refuses the same as its interpreted twin (D013). The
;; difference from `behavior-component` is the difference between the two modes
;; and nothing else: the interpreted boundary WALKS its child from markup, this
;; one is HANDED it already built — exactly the split `presence-boundary` has
;; across `presence-node` and `emit-presence`.

(defn- element-childless?
  "Does the already-built React element carry no Freehand children of its own?
  The browser arm of the opaque-child law for a COMPILED attachment.

  A compiled body resolved the decorated element at build, so there is no markup
  form to walk here as [[childless?]] does — React holds the element's
  descendants under `props.children`, undefined for the childless
  `createElement(tag, props)` the emitter produces
  ([[re-frame.freehand.compiled-react/el]]) and present otherwise. This is
  [[childless?]]'s counterpart on the one child representation the compiled
  browser path actually has: an element, not a form or a structural node."
  [element]
  (let [props (.-props element)]
    (or (nil? props)
        (nil? (unchecked-get props "children")))))

(defn ^:no-doc behavior-el
  [js-props]
  (let [opts     (unchecked-get js-props "opts")
        child    (unchecked-get js-props "child")
        _        (check-attach-opts! opts)
        ;; The child-dependent OPAQUE law, enforced at runtime for both a
        ;; literal and a runtime-selected :use. `check-attach-opts!` proved the
        ;; use and the config and `analyze-behavior` proved the one-element
        ;; shape at build, but neither can prove an {:opaque true} behavior's
        ;; element carries no children — the opacity is the REGISTERED
        ;; definition's, resolvable only now. So the compiled browser path runs
        ;; the same guard the other hosts run through `read-opts`, BEFORE
        ;; `use-attachment!` installs a lifecycle arm: no connection opens and no
        ;; host overwrites already-declared children (D013, FH-BEHAVIOR-002).
        _        (when (and (:opaque (definition (:use opts)))
                            (not (element-childless? child)))
                   (opaque-child! (:use opts)))
        set-node (use-attachment! opts)]
    (attach child set-node)))

;; The SAME spelling the interpreted twin publishes
;; (`re-frame.freehand.react/behavior-component`) — one construct cannot show
;; two names in DevTools depending on which tier lowered it.
(set! (.-displayName behavior-el) (performance/entry-id behavior-view-id))

(defn ^:no-doc compiled-attachment
  "The value a compiled body hands React for one `[v/behavior {…} node]`: the
  boundary element wrapping the already-built child. `key` is the React key when
  the attachment is a keyed list row, and nil in the ordinary case."
  [key opts child]
  (react/createElement behavior-el
                       (if (nil? key)
                         #js {"opts" opts "child" child}
                         #js {"key" key "opts" opts "child" child})))

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
;;
;; It resolves IN ONE FRAME — the frame the effect was produced in, which the
;; binary fx-handler contract supplies in its context. That is not a filter
;; applied to a global answer: the frame is half the address, so a target live
;; only in a sibling frame is as absent as one nothing ever mounted.

(defn command!
  "Perform one `:re-frame.freehand.host/command` effect, resolving `args`'
  `:target` among the live connections of `frame` — the frame the
  originating event ran in.

  The CAPABILITY is browser-only — a command crosses into a live host
  object, and the JVM has none — but the effect is not gated off the
  structural host. It runs there and REFUSES, on the same diagnostic id,
  because a command that silently evaporates is the one outcome a bounded
  channel cannot afford."
  [frame args]
  #?(:clj
     (refused!
       (str "The behavior command channel is a browser capability: a command "
            "reaches a live host object, and a structural render owns none. A "
            "structural test asserts the command's DATA — the effect an event "
            "handler returned — and the mounted tier proves the host action.")
       :assert-the-command-data-on-the-structural-host
       {:frame frame :args (shape args)})
     :cljs
     (let [{:keys [target op]} (when (map? args) args)
           ;; The traffic row's known half, built once from what the command
           ;; NAMED. Every exit below completes it with the outcome, so the
           ;; log records the refusals as faithfully as the deliveries — a
           ;; projection that only saw the successes would be evidence for
           ;; the one case nobody debugs.
           row     (cond-> {:frame frame}
                     (some? target) (assoc :target target)
                     (some? op)     (assoc :op op))
           refuse! (fn refuse!
                     ([reason recovery extra]
                      (refuse! row reason recovery extra))
                     ([row* reason recovery extra]
                      (record-command! (assoc row* :outcome :refused))
                      (refused! reason recovery extra)))]
       (when-not (map? args)
         (refuse!
           (str "A behavior command is a map {:target … :op … :args …}; got a "
                (type-name args) ".")
           :supply-a-command-map
           {:frame frame :args (shape args)}))
       (when (nil? target)
         (refuse!
           (str "A behavior command names its :target — the caller-authored "
                "semantic id declared at the use site. There is no default "
                "target and none is derived from render position: an address "
                "the substrate invented would move when a view was renamed, "
                "sorted or extracted.")
           :name-the-use-sites-target
           {:frame frame :args (shape args)}))
       (when-not (keyword? op)
         (refuse!
           (str "A behavior command names its :op — one of the operations the "
                "behavior registered; got "
                (if (nil? op) "nothing" (str "a " (type-name op))) ".")
           :name-a-registered-operation
           {:frame frame :op (shape op)}))
       (let [claims (claims-for frame target)]
         (case (count claims)
           0 (refuse!
               (str "No live behavior connection in frame " (pr-str frame)
                    " claims the target " (pr-str target) ". A command resolves "
                    "in the frame its event ran in — a connection another frame "
                    "committed is another frame's to command — and it is never "
                    "retained for a target that is absent: a future mount is "
                    "driven by state and config, or by a fresh event, and a "
                    "queued imperative request would arrive at a node the "
                    "application has since changed its mind about.")
               :connect-the-target-or-drive-the-host-through-config
               {:frame frame :target target
                :live (vec (sort-by str (targets-in frame)))})
           1 (let [[generation rec] (first claims)
                   f                (get (:commands (definition (:behavior rec))) op)]
               (when (nil? f)
                 ;; The channel DID resolve a connection here and then refused
                 ;; the operation, so the row names it: a tool reading the
                 ;; traffic should not have to guess which behavior was asked
                 ;; for something it does not register.
                 (refuse!
                   (assoc row :behavior (:behavior rec) :generation generation)
                   (str "Behavior " (:behavior rec) " registers no " op
                        " operation. The command roster is finite and declared "
                        "with the behavior; an unregistered operation is refused "
                        "rather than reaching a host that has no answer for it.")
                   :name-a-registered-operation
                   {:frame frame :target target :behavior (:behavior rec) :op op
                    :operations (vec (sort (keys (:commands (definition (:behavior rec))))))}))
               ;; Recorded BEFORE the operation runs: the channel delivered it,
               ;; and a command that goes on to crash its host belongs in the
               ;; traffic a tool reads rather than missing from it.
               (record-command! (assoc row :outcome    :delivered
                                           :behavior   (:behavior rec)
                                           :generation generation))
               ;; Run for its EFFECT on the host, and DISCARD the return.
               ;; Spec 004 §The bounded command channel: a command returns no
               ;; handle, and it does not return the memory either — `:connect`
               ;; established that and nothing else writes it (§Private
               ;; memory). An `:export` calling `workbook.save(…)` answers
               ;; `undefined`, and a channel that wrote that back would have
               ;; one export erase the workbook the teardown must dispose
               ;; (rf2-wj1ao).
               (f (context generation rec {:op op :args (:args args)}))
               nil)
           (refuse!
             (str (count claims) " live behavior connections in frame "
                  (pr-str frame) " claim the target " (pr-str target)
                  ". A command target is unique among the live connections of "
                  "ONE frame, so the command is refused rather than delivered "
                  "to whichever mounted last. Give each occurrence its own "
                  "semantic id.")
             :give-each-occurrence-its-own-target
             {:frame frame :target target :claims (count claims)}))))))

;; The channel declares NO `:platforms`, deliberately — it is universal, and
;; the JVM arm of [[command!]] above is the reason.
;;
;; `:platforms #{:client}` would be the reflex for a browser-only capability,
;; and it is the wrong answer here. The generic gate SKIPS a barred effect and
;; emits a `:rf.fx/skipped-on-platform` warning trace, so a command reaching
;; the structural host through the documented `{:fx [[…]]}` path would perform
;; no host work AND say nothing an application can observe — the one outcome
;; Spec 004 §The structural marker rules out. Declaring the gate would also
;; make the JVM refusal unreachable except by calling this namespace's own
;; `command!`, which is not a door an application has.
;;
;; So the effect runs on every platform and REFUSES itself where it cannot be
;; honoured, with the diagnostic that names the fix (assert the command's DATA
;; structurally; prove the host action on the mounted tier). Spec 011 asks a
;; browser-only effect to declare the gate; it does not ask an effect to
;; declare it in place of a better diagnostic, and a typed refusal is strictly
;; more visible than a skip.
(fx/reg-fx
  command-fx-id
  {:doc (str "Perform one bounded operation on the live behavior connection "
             "claiming :target IN THE EFFECT'S OWN FRAME. Refused — never "
             "queued — when that frame holds no live connection claiming it, "
             "and refused on the structural host, which owns no live "
             "connection at all.")}
  (fn [ctx args] (command! (:frame ctx) args)))
