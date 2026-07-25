(ns ^:no-doc re-frame.freehand.descriptor
  "INTERNAL. The descriptor value and the host-neutral laws every
  Freehand emitter reads — the substrate's foundational layer, beneath
  the public door.

  ## Why this is not `re-frame.freehand`

  `re-frame.freehand` is the ONE public door (D001). An emitter — the
  structural tree, the React tree, the compiled analyzer — needs the
  descriptor type, the head classifier and the call normalizer, so if
  those lived in the door every emitter would have to require it, and
  the door could never require an emitter back. That cycle is a real
  constraint, not a style preference: `v/mount` and `t/render` are
  re-exports of emitter surfaces, and a door that cannot require its
  emitters cannot publish them.

  Splitting the machinery down here dissolves the cycle and closes the
  public boundary in the same move. `re-frame.freehand` re-exports the
  vars that really are public; the descriptor CONSTRUCTOR is not among
  them. A raw constructor on the supported surface would mint a value
  that passes `view?` and classifies as `:view` while carrying no
  view-id, no source and no lowering — bypassing the one-declaration
  rule the whole design rests on. `v/defview` is the only way to create
  an internal mounted boundary; that is now enforced by where the
  constructor lives, not merely stated.

  Nothing here renders. Everything here is **common** — host-neutral by
  construction, identical on the JVM and in ClojureScript, because both
  emitters and the compiled analyzer consume exactly these values.

  Normative owner: [`spec/004-Views.md`](../../../../../spec/004-Views.md)."
  (:require [re-frame.error :as error]
            [re-frame.freehand.events :as events]
            [re-frame.freehand.props-schema :as props-schema]
            [re-frame.interop :as interop])
  #?(:cljs (:require-macros [re-frame.freehand.descriptor :refer [def-descriptor-type]])))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The descriptor value
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §A declared view cannot be called. `deftype`, deliberately,
;; and deliberately NOT `defrecord` or a plain map:
;;
;;   - a plain map answers `(the-view props)` as a LOOKUP — returning
;;     `nil` and rendering nothing, silently. The law is that a direct
;;     call cannot SUCCEED, and a lookup succeeds;
;;   - a `defrecord` would answer some arities as lookups too, and its
;;     `IFn`-ness differs across hosts;
;;   - a `deftype` implements exactly the protocols it names, so the
;;     throwing call protocol below is the ONLY thing a direct call can
;;     reach, on either host.
;;
;; One field, `entry`, holding the private declaration map. Later slices
;; add private entries (the host mount entry, the structural tree entry)
;; without changing the type, and `describe` keeps projecting only the
;; public ABI slots.

(defn- direct-call!
  "The one didactic throw a direct call reaches. Takes the declaration
  `entry` directly — the call protocol below has the field in hand, so
  no accessor and no type hint are involved on the failure path."
  [entry]
  (let [view-id (:view-id entry)
        sym     (if view-id (name view-id) "the-view")]
    (error/throw-error!
      :rf.error/view-called-directly
      'v/defview
      (str "Declared view " view-id " was called as a function. A declared view is "
           "mounted, never invoked, so the call cannot succeed. Three recoveries: "
           "MOUNT it — write [" sym " {…}], because square brackets mount a declared "
           "boundary; INLINE it — if the body should run inside the caller with no "
           "boundary of its own, declare it with plain defn and keep the parentheses; "
           "or EXTRACT the shared work into a plain defn helper that the view mounts "
           "and the caller calls.")
      {:recovery :mount-it-inline-it-or-extract-a-helper
       :extra    {:view-id view-id}})))

#?(:clj
   (defmacro ^:no-doc def-descriptor-type
     "Define one descriptor `deftype` — `tname`, one `entry` field — and its
     COMPLETE host call protocol, every arity routed to `call-fn`.

     Parameterized over the two descriptor kinds because the argument
     below is about DESCRIPTORS, not about views: a declared view and a
     declared host are both mounted-never-invoked boundary values, and
     both owe the same answer to a direct call. `print-tag` is the reader
     tag the value prints under and `id-key` the entry slot naming it.

     RULED (Mike, 2026-07-22, rf2-llxsw — Option B). The descriptor
     implements the call protocol for exactly one reason: so that calling
     a declared view explains itself. `(my-view {…})` is normal, legal,
     idiomatic Reagent and is trained muscle memory in every v1 codebase
     being migrated; without an interception point the JVM answers it
     with a raw `ClassCastException`, because `(x arg)` compiles to a
     checkcast to `IFn` before any framework code runs. So the type
     implements `IFn` and throws. `(ifn? a-view)` is therefore TRUE, and
     nothing depends on it being false: head classification uses
     [[view?]], and tooling has [[view?]] / [[describe]], which are
     strictly more precise.

     The roster is the host call protocol's OWN roster, not the two or
     three arities a realistic mistake uses. A skipped arity would not
     fall through to nothing — it would fall through to the host's
     `AbstractMethodError` (JVM) or `Invalid arity` (CLJS), which is the
     poor first-encounter message this ruling exists to remove,
     reintroduced at a different arity. Generating the roster keeps that
     completeness from costing forty lines of noise.

     The remaining ceiling is the HOST's, not a Freehand asymmetry. The
     JVM's `IFn` declares a trailing `Object[]` arity plus `applyTo`, so
     every JVM call is didactic. ClojureScript's `IFn` declares
     twenty-one `-invoke` arities and REFUSES a variadic one (`Bad method
     signature in protocol implementation`), so a CLJS call carrying more
     than twenty arguments answers with the host's own `Invalid arity` —
     exactly as `cljs.core`'s own `IFn` types do. The law that could have
     been threatened is that a call must never SUCCEED, and it is not: at
     every arity, on both hosts, the call raises."
     [tname call-fn print-tag id-key]
     (let [cljs?  (some? (:ns &env))
           invoke (if cljs? '-invoke 'invoke)
           this   '_this
           body   (list call-fn 'entry)
           args   (fn [n] (map #(symbol (str "_a" %)) (range n)))
           meth   (fn [as] (list invoke (vec (cons this as)) body))]
       (concat
         (list 'deftype tname '[entry]
               'Object
               (list 'toString [this]
                     (list 'str print-tag (list id-key 'entry)))
               (if cljs? 'IFn 'clojure.lang.IFn))
         (map (comp meth args) (range 0 21))
         ;; Both hosts close the roster with the twenty-fixed-plus-rest
         ;; arity their call protocol declares last; on the JVM that
         ;; trailing slot is an `Object[]`.
         [(meth (concat (args 20)
                        [(if cljs? '_rest (with-meta '_rest {:tag 'objects}))]))]
         ;; The JVM's `IFn` additionally declares `applyTo`, which is what
         ;; `apply` reaches — and how a Reagent tree calls a component.
         (when-not cljs?
           [(list 'applyTo [this '_args] body)])))))

(def-descriptor-type ViewDescriptor re-frame.freehand.descriptor/direct-call!
  "#re-frame.freehand/view " :view-id)

#?(:clj
   (defmethod print-method ViewDescriptor [d ^java.io.Writer w]
     (.write w (str d)))
   :cljs
   (extend-protocol IPrintWithWriter
     ViewDescriptor
     (-pr-writer [d writer _opts]
       (-write writer (str d)))))

(defn view?
  "True when `x` is a view declared with `v/defview` — the ONE value a
  Freehand runtime classifies as an internal view boundary.

  Total and host-neutral: the same value answers the same way on the JVM
  and in ClojureScript, in both execution modes."
  [x]
  (instance? ViewDescriptor x))

;; ---------------------------------------------------------------------------
;; The host descriptor — the third and last legal vector head
;; ---------------------------------------------------------------------------
;;
;; D022. `v/defhost` is the SOLE public inward React host declaration, and
;; this is the one value it mints. It is a `deftype` for exactly the reason
;; the view descriptor is: the marker used to be a reserved key on a plain
;; MAP, and a map answers `(date-picker {:selected d})` as a LOOKUP —
;; returning nil, rendering nothing, saying nothing. That is the same silent
;; failure `direct-call!` exists to remove, at the boundary an adopter
;; arriving from React is MOST likely to call by habit.
;;
;; One field, `entry`, holding the private declaration map:
;;
;;   {:host-id   :app.booking/date-picker
;;    :component <the React component, or nil on a JVM expansion>
;;    :callbacks {:onChange :event}       ; finite, name -> D008 role
;;    :children  :optional                ; the closed children policy
;;    :ssr       :client-only             ; or {:fallback <markup>}
;;    :map-props <fn>                     ; optional, whole-ordinary-props
;;    :props     <inert schema>           ; optional evidence
;;    :source    {:ns … :file … :line …}}

(defn- direct-host-call!
  "The didactic throw a direct call on a host descriptor reaches. Same law
  and same error id as [[direct-call!]] — a declared boundary is mounted,
  never invoked — with the recoveries a HOST boundary actually has."
  [entry]
  (let [host-id (:host-id entry)
        sym     (if host-id (name host-id) "the-host")]
    (error/throw-error!
      :rf.error/view-called-directly
      'v/defhost
      (str "Declared host " host-id " was called as a function. A declared host is "
           "mounted, never invoked, so the call cannot succeed. MOUNT it — write ["
           sym " {…}], because square brackets mount a declared boundary. If what "
           "you want is the React component itself, call the component you "
           "registered rather than the Freehand descriptor that declares it.")
      {:recovery :mount-it
       :extra    {:view-id host-id}})))

(def-descriptor-type HostDescriptor re-frame.freehand.descriptor/direct-host-call!
  "#re-frame.freehand/host " :host-id)

#?(:clj
   (defmethod print-method HostDescriptor [d ^java.io.Writer w]
     (.write w (str d)))
   :cljs
   (extend-protocol IPrintWithWriter
     HostDescriptor
     (-pr-writer [d writer _opts]
       (-write writer (str d)))))

(defn host-descriptor?
  "True when `x` is a **declared host descriptor** — the third and last
  legal vector head, naming a foreign React component behind a qualified
  host boundary.

  Nominal, exactly like [[view?]]: the answer is the TYPE, so no map an
  application can write is a host boundary and the classification stays
  total rather than duck-typed. `v/defhost` is the only way to get one."
  [x]
  (instance? HostDescriptor x))

(defn declare-host
  "Build a [[HostDescriptor]] from a declaration `entry` map. The expansion
  target of `v/defhost` — not an authoring form, and deliberately not on
  the public surface: a raw constructor would mint a head that classifies
  as `:host` while carrying no host-id, no source, no declared callback
  positions and no SSR policy, which is the one-declaration rule with a
  hole in it."
  [entry]
  (->HostDescriptor entry))

(defn host-entry
  "A host descriptor's PRIVATE declaration map. An emitter's vocabulary:
  the structural walk reads the id, the children policy and the SSR
  policy; the React walk additionally reads the component, the declared
  callback positions and the ordinary-props adapter. Nothing public
  projects it — the descriptor's public evidence is what the structural
  tree records."
  [host]
  (.-entry ^HostDescriptor host))

(def host-callback-roles
  "The closed roster of D008 roles a `:callbacks` position may declare.

  `:event` and `:handler` ONLY. `:render-fn` and `:raw-fn` are real roster
  members ([[re-frame.freehand.events/callback-roles]]) and are
  deliberately outside a declared host position: a render-fn may run
  during an uncommitted foreign render and a raw-fn's identity is the
  caller's, so neither can carry the committed-site laws a declared
  position promises."
  #{:event :handler})

(def host-ssr-policies
  "The closed roster of SSR policy SPELLINGS a declaration may choose.

  `:client-only` is explicit no-server content; `{:fallback <markup>}` is a
  portable stand-in the JVM renders in its place. There is no third
  answer and no default — Freehand never executes the registered React
  component on the JVM, so a declaration that did not say would be a
  declaration whose server behaviour nobody chose."
  #{:client-only :fallback})

(defn host-ssr-spelling
  "Which of [[host-ssr-policies]] `ssr` is, or nil when it is neither."
  [ssr]
  (cond
    (= :client-only ssr)                        :client-only
    (and (map? ssr) (= #{:fallback} (set (keys ssr)))) :fallback))

(defn declare-view
  "Build a [[ViewDescriptor]] from a declaration `entry` map. The
  expansion target of `v/defview` — not an authoring form. `entry` is
  private: read a descriptor through [[describe]]."
  [entry]
  (->ViewDescriptor entry))

(defn error-boundary?
  "True when `view` is the framework's `v/error-boundary` boundary — a
  declared boundary whose PRIVATE entry carries the reserved
  `:error-boundary` marker.

  Like [[structural-body]], the marker is deliberately NOT projected by
  [[describe]]: an emitter routes an error boundary to its containment
  realisation (a React class boundary in the browser, a structural
  `contain` on the JVM) rather than walking a render body, and the presence
  of the entry — not a flag on the public projection — is what decides
  that. It classifies as `:view` like any other internal boundary, so a
  `[v/error-boundary {…} child]` head is mounted, never called."
  [view]
  (true? (:error-boundary (.-entry ^ViewDescriptor view))))

(defn behavior?
  "True when `view` is the framework's `v/behavior` attachment boundary — a
  declared boundary whose PRIVATE entry carries the reserved `:behavior`
  marker.

  Like [[error-boundary?]] the marker is deliberately NOT projected by
  [[describe]]: a browser emitter routes a behavior to its lifecycle
  realisation (a React component owning the node's ref and the connection's
  timing arms) rather than walking an ordinary body, and the presence of the
  entry — not a flag on the public projection — is what decides that. It
  classifies as `:view` like any other internal boundary, so
  `[v/behavior {…} node]` is mounted, never called."
  [view]
  (true? (:behavior (.-entry ^ViewDescriptor view))))

(defn describe
  "The descriptor's public **inspection / registry projection** — a plain
  map, distinct from the runtime value (per the Freehand descriptor ABI):

      {:re-frame.freehand/view true
       :view-id                :app.todo/todo-row
       :source                 {:ns … :file … :line …}
       :lowering               :interpreted   ; or :compiled
       :children-policy        :optional      ; or :none, :required
       :props-schema           <schema>}      ; absent when none declared

  `:props-schema` is **absent** when no schema was declared — absence is
  reported as absence, never as `:any` — and a declared one is projected
  as AUTHORED, inert and unevaluated
  ([[re-frame.freehand.props-schema/inert-form]]), so a schema behind a
  reference or an expression reaches a catalogue as that reference or
  expression and closes nothing anywhere. The descriptor's host `mount` and
  structural `tree` entries are private and are deliberately NOT
  projected: browser heads resolve through the mount entry and structural
  heads through the tree entry, and neither shape is a contract an
  application may depend on.

  This is what tools, registries and catalogues read. It is inspection
  data, never a dispatch surface — every view is mounted the same way."
  [view]
  (let [entry (.-entry ^ViewDescriptor view)]
    (cond-> {:re-frame.freehand/view true
             :view-id                (:view-id entry)
             :source                 (:source entry)
             :lowering               (:lowering entry)
             :children-policy        (:children-policy entry)}
      (contains? entry :props-schema)
      (assoc :props-schema (:props-schema entry)))))

(defn render-body
  "The declared view's PRIVATE render body — the one-argument fn
  `v/defview` built from the declaration's parameter vector and body.

  Deliberately NOT projected by [[describe]], and deliberately not on the
  public surface: an emitter runs a declared view by calling this, and
  nothing else does. A view is mounted, and mounting is what an emitter
  performs; an application that reached the body directly would be
  calling a view, which is the one thing the declaration boundary exists
  to prevent."
  [view]
  (:render (.-entry ^ViewDescriptor view)))

(defn structural-body
  "A COMPILED view's private structural realisation — the one-argument fn
  the `:re-frame.freehand/v1` lowering built, from the props map to its
  structural node — or `nil` for an interpreted declaration.

  It is the presence of this entry, not a flag, that decides how a
  boundary renders: a structural renderer calls it if it is there and
  walks [[render-body]] if it is not. A compiled declaration therefore
  cannot be mounted 'as interpreted' by mistake, and the two never both
  exist for one declaration."
  [view]
  (:structural (.-entry ^ViewDescriptor view)))

(defn react-body
  "A COMPILED view's private BROWSER realisation — the one-argument fn the
  `:re-frame.freehand/v1` React lowering built, from the props map to the
  React element it renders — or `nil` when the declaration has none.

  It sits BESIDE [[structural-body]] rather than replacing it, because
  the two answer different hosts and a compiled declaration owes both an
  answer: the JVM (and a browser test rendering structurally) runs the
  structural body, and a real React mount runs this one. Only a
  ClojureScript expansion can build it — there is no React to emit calls
  into on the JVM — so a compiled descriptor produced by a JVM expansion
  carries `nil` here, which is exactly what the browser emitter's refusal
  reports.

  Private for the same reason [[render-body]] is: an emitter runs a
  declared view by calling it, and nothing else does."
  [view]
  (:react (.-entry ^ViewDescriptor view)))

(defn manifest
  "A COMPILED declaration's manifest — what its analysis makes statically
  knowable about it, as plain data — or `nil` for an interpreted one,
  which has no analysis to report. Re-exported on the door as
  `v/manifest`.

      {:view-id       :app.people/people-list
       :grammar       :re-frame.freehand/v1
       :subscriptions [{:sid … :query [:person 7] :path [0]}]
       :events        [] :slots [] :html-sites []
       :capabilities  #{:sub}
       :reactive?     true
       :view-cell     :present
       :crossings     [{:view-id :re-frame.freehand/markup
                        :lowering :interpreted :path [1]}]}

  The `:subscriptions` / `:events` / `:slots` / `:html-sites`
  rosters are the view's finite lexical sites; `:capabilities`
  is their union with the structural capability bits; `:reactive?` /
  `:view-cell` carry the **capability-elision** verdict — a view with no
  reactive site omits the ViewCell shell (`:view-cell :elided`). `:crossings`
  is the roster of internal-view boundaries the body mounts, one entry per
  lexical site, each MARKED with the mode it crosses into — a compiled view
  that mounts an interpreted child is the ordinary case, so the manifest
  says where the compiled tier stops.

  Nil for an interpreted declaration is the honest answer, not an
  omission: the interpreted mode has no finite grammar and no analysis
  step, so there is nothing it could claim."
  [view]
  (:manifest (.-entry ^ViewDescriptor view)))

;; ---------------------------------------------------------------------------
;; Reserved interpreted intrinsic heads
;; ---------------------------------------------------------------------------
;;
;; A compiled `(v/presence …)` is a SEQ form the analyzer recognises at
;; compile time. An INTERPRETED `(v/presence …)` is an ordinary function call
;; that runs during the body's evaluation; it returns a plain hiccup vector
;; whose head is this reserved keyword, which each interpreted walk intercepts
;; BEFORE `classify-head` — exactly as the fragment head `:<>` is intercepted.
;; The two mechanisms never meet: compile sees the seq, the walk sees the
;; vector, and the runtime they lower to is one and the same.

(def presence-tag
  "The reserved head of the vector `v/presence` returns in interpreted mode.
  Namespaced, so it can never collide with an author's element keyword, and
  intercepted by both interpreted walks before head classification."
  :re-frame.freehand/presence)

(def client-only-tag
  "The reserved head of the vector `v/client-only` returns in interpreted
  mode. Namespaced and intercepted exactly as [[presence-tag]] is.

  A vector — not a host value — because BOTH interpreted walks must be able
  to read it. The structural walk answers one tree on the JVM and in
  ClojureScript, so a value that only the React walk could interpret would
  make `v/client-only` unrenderable in the very cross-host structural test
  the substrate's parity rests on."
  :re-frame.freehand/client-only)

;; ---------------------------------------------------------------------------
;; Vector-head classification — total
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §Vector-head classification. One rule, three legal answers, no
;; fourth case and no heuristic arm — the same order and the same outcome
;; in the interpreter, the compiled analyzer, and the JVM structural host.

(def ^:private legal-head-forms
  "The closed roster of legal vector heads, in classification order. Rides
  in the `:rf.error/view-bad-head` ex-data so a tool renders the three
  recoveries without parsing the message."
  [:declared-view :element-keyword :host-descriptor])

(defn- value-tag
  "A short, HOST-NEUTRAL tag for a value's shape — `\"fn\"`, `\"string\"`,
  `\"nil\"`, `\"vector\"` … — for the human sentence of a diagnostic.

  Deliberately NOT `error/type-of-value`, whose fallback arm stringifies
  the host class: a function renders `class user$fn__586` on the JVM and
  `function` in ClojureScript, so a message built on it would differ
  across hosts for exactly the head shape this diagnostic exists to
  catch. `diag-value-summary`'s `:type` is one vocabulary on both hosts,
  and it carries the value's SHAPE only, never the value itself (Spec 015
  §Data-Classification)."
  [v]
  (name (:type (error/diag-value-summary v))))

(defn- bad-head-reason
  "The human sentence for `:rf.error/view-bad-head`. Names all three legal
  forms, and — when the offending head is callable — adds D002's
  plain-function recovery, which is the mistake this arm actually catches
  in the field."
  [head]
  (str "A Freehand vector head must be one of exactly three things: a view declared "
       "with v/defview, a keyword naming a DOM or custom element, or a declared host "
       "descriptor; got a " (value-tag head) "."
       (when (ifn? head)
         (str " A plain function is never an internal vector head - declare it with "
              "v/defview to mount it as a boundary, or call it with parentheses as an "
              "inline helper."))
       " Mount a declared view as [the-view props], write an element as [:div ...], and "
       "cross to a foreign component through a declared host descriptor."))

(defn classify-head
  "Classify a vector `head`. **Total** — it returns exactly one of

    `:view`     the head is a view declared with `v/defview` — an internal
                boundary to mount;
    `:element`  the head is a keyword — a DOM or custom element;
    `:host`     the head is a declared host descriptor — a foreign
                boundary;

  and raises `:rf.error/view-bad-head` for anything else, naming those
  three legal forms. There is no fourth case: no bare-function heads, no
  string heads, no duck-typed component detection. This one rule is what
  keeps head resolution uniform across interpreted code, compiled code,
  the structural host, hot reload, catalogues, tools and generated edits."
  [head]
  (cond
    (view? head)            :view
    (keyword? head)         :element
    (host-descriptor? head) :host
    :else
    (error/throw-error!
      :rf.error/view-bad-head
      'v/classify-head
      (bad-head-reason head)
      {:recovery :use-a-declared-view-an-element-keyword-or-a-host-descriptor
       :extra    {:legal-heads legal-head-forms
                  :head        (error/diag-value-summary head)}})))

;; ---------------------------------------------------------------------------
;; Props, children and `:key`
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §Props, children, and `:key`. Every internal view receives ONE
;; props map. `:key` selects sibling identity and is stripped before the
;; body sees its props; trailing children arrive under the reserved
;; `:children` key. Both emitters share this normalizer, so both modes
;; deliver the same props map for the same call — the parity is by
;; construction, not by convention.

(def children-policies
  "The closed children-policy roster a declaration may choose from.

  `:optional` (the default) accepts any number of trailing children;
  `:none` declares a view that accepts none; `:required` declares a view
  that is meaningless without them."
  #{:none :optional :required})

(defn- bad-props-error!
  [view-id reason recovery extra]
  (error/throw-error!
    :rf.error/view-bad-props
    'v/normalize-call
    reason
    {:recovery recovery
     :extra    (assoc extra :view-id view-id)}))

(defn normalize-call
  "Normalize an internal boundary call into its `:key` and its one props
  map. `view` is the descriptor at the head; `args` is the rest of the
  call vector — the props map followed by any trailing children.

      [panel {:key panel-id :title \"Details\"} [details {:id panel-id}]]
      ;; => {:key    panel-id
      ;;     :keyed? true
      ;;     :props  {:title \"Details\" :children [[details {:id panel-id}]]}}

  The laws it enforces, identically in both execution modes:

  - **one map, no positional args** — `props` is a single map, `{}` when
    the view needs nothing. A non-map props slot is `:rf.error/view-bad-props`;
  - **`:children` is reserved** — trailing children arrive under it, and
    a caller-authored `:children` inside the props map is rejected. The
    key is ABSENT when the call supplied no children, so a childless call
    yields the smallest props map that can compare equal;
  - **`:key` is stripped** — it selects sibling identity for
    reconciliation and the view body never sees it. It is returned
    separately, and it is not part of the props map's equality.
    `:keyed?` reports whether the call supplied one AT ALL: React
    string-coerces a supplied key, so an explicit `nil` is the legal
    identity `\"null\"` and a different authored fact from no key —
    a distinction `:key` alone cannot carry, since both read `nil`;
  - **the declared children policy holds** — children against a `:none`
    view, or none against a `:required` view, is
    `:rf.error/view-children-policy`;
  - **a declared props schema closes the map** — an undeclared prop is
    `:rf.error/view-bad-props`. The roster and the sentence come from
    [[re-frame.freehand.props-schema]], the same pair the compiled
    analyzer applies to a literal call site, so promotion changes when a
    breach is reported and never which props are legal. DEV-gated: the
    schema is a compile-time and tooling fact, and production carries no
    validation pass (D011)."
  [view args]
  (let [entry            (.-entry ^ViewDescriptor view)
        view-id          (:view-id entry)
        [props & children] args]
    (when-not (map? props)
      (bad-props-error!
        view-id
        (str "A Freehand boundary call takes exactly one props map: write "
             "[the-view {...} & children]. " view-id " was called with a "
             (value-tag props) " in the props slot; pass {} when the view "
             "needs no props.")
        :supply-one-props-map
        {:props (error/diag-value-summary props)}))
    (when (contains? props :children)
      (bad-props-error!
        view-id
        (str ":children is reserved — it is how trailing children arrive, so a props "
             "map may not carry it. Pass the children as trailing forms of the call "
             "instead: [the-view {…} child-1 child-2].")
        :pass-children-as-trailing-forms
        {}))
    ;; The props-schema decision, on the props the call actually delivered.
    ;; `closing-keys` answers nil for every view without a closing schema —
    ;; the overwhelming majority — so the ordinary boundary pays one map
    ;; lookup and a nil check. The whole arm sits under `debug-enabled?`
    ;; because a schema is a compile-time and tooling fact: production
    ;; renders the same tree either way, and the CLJS branch folds away.
    (when interop/debug-enabled?
      (when-let [closing (props-schema/closing-keys (:props-schema entry))]
        (let [bad (props-schema/undeclared closing (keys props))]
          (when (seq bad)
            (bad-props-error!
              view-id
              (props-schema/violation-message view-id closing bad)
              :match-the-declared-props-schema
              {:undeclared bad :declared (vec closing)})))))
    (let [policy   (:children-policy entry)
          children (when (seq children) (vec children))]
      (when (or (and (= :none policy) children)
                (and (= :required policy) (nil? children)))
        (error/throw-error!
          :rf.error/view-children-policy
          'v/normalize-call
          (if children
            (str view-id " declares :children-policy :none and accepts no children; "
                 "the call supplied " (count children) ". Pass the content as a prop, "
                 "or relax the view's declared policy.")
            (str view-id " declares :children-policy :required and the call supplied "
                 "no children. Supply them as trailing forms of the call, or relax the "
                 "view's declared policy."))
          {:recovery :match-the-declared-children-policy
           :extra    {:view-id         view-id
                      :children-policy policy
                      :children-count  (count children)}}))
      {:key    (:key props)
       :keyed? (contains? props :key)
       :props  (cond-> (dissoc props :key)
                 children (assoc :children children))})))

;; ---------------------------------------------------------------------------
;; The host call — three disjoint planes
;; ---------------------------------------------------------------------------
;;
;; D022 §Ordinary props: "Ordinary props, declared callbacks, and children
;; are disjoint planes." That is enforced HERE, once, so the structural walk
;; and the React walk split one call the same way — the same parity
;; [[normalize-call]] gives an internal boundary.

(defn- bad-host-props-error!
  [host-id reason recovery extra]
  (error/throw-error!
    :rf.error/view-bad-props
    'v/defhost
    reason
    {:recovery recovery
     :extra    (assoc extra :view-id host-id)}))

(defn- host-callback-value
  "Validate one declared callback position's supplied `value` against its
  declared `role`, and answer it. `nil` is a legal empty position — a
  declaration names the positions a host HAS, not the ones a call must
  fill."
  [host-id k role value]
  (cond
    (nil? value) nil

    (not (events/callback? value))
    (bad-host-props-error!
      host-id
      (str k " is a declared " role " position on " host-id ", so it takes a (v/"
           (name role) " [args …] …) carrier"
           (if (vector? value)
             ;; The offending value rides the ex-data as a bounded SHAPE
             ;; summary and is deliberately NOT printed here (Spec 015
             ;; §Data-Classification): an event vector at a foreign position
             ;; carries whatever the caller put in it.
             (str " — not a bare event vector. Freehand does not silently convert one at "
                  "a foreign position: the host may itself want a vector there, and "
                  "guessing would confuse a host value with re-frame intent. Wrap the "
                  "intent in a carrier — (v/event [& args] the-vector).")
             (str "; the call supplied a " (name (:type (error/diag-value-summary value)))
                  ".")))
      :supply-the-declared-callback-carrier
      {:prop k :role role :value (error/diag-value-summary value)})

    (not= role (events/callback-role value))
    (bad-host-props-error!
      host-id
      (str k " is declared " role " on " host-id " and the call supplied a v/"
           (name (events/callback-role value)) ". The two roles are different "
           "contracts — a v/event names ONE event vector or nil, a v/handler is "
           "imperative work whose return is ignored — so the position and the "
           "carrier have to agree.")
      :supply-the-declared-callback-carrier
      {:prop k :role role :supplied (events/callback-role value)})

    :else value))

(defn normalize-host-call
  "Normalize a HOST boundary call into its `:key` and its three disjoint
  planes. `host` is the descriptor at the head; `args` is the rest of the
  call vector — the props map followed by any trailing children.

      [date-picker {:selected d :onChange (v/event [x] [:picked x])} child]
      ;; => {:key nil :keyed? false
      ;;     :props     {:selected d}
      ;;     :callbacks {:onChange <Callback :event>}
      ;;     :children  [child]}

  The laws, identically on both hosts:

  - **one map, no positional args**, and a caller-authored `:children`
    inside it is rejected — the same two laws [[normalize-call]] enforces
    for an internal boundary, because they are the boundary CALL ABI and
    not a per-kind rule;
  - **`:key` is stripped** and returned separately, with `:keyed?`
    reporting presence;
  - **declared positions leave the ordinary plane.** A key the declaration
    named in `:callbacks` is removed from `:props` and validated against
    its role. A `v/event` carrier at a `:handler` position, or a bare event
    vector anywhere, is refused rather than coerced;
  - **an ordinary slot is DATA.** A function or an undeclared roster
    carrier in an ordinary prop is refused, naming the recovery — declare
    the position. Silently forwarding it would hand a foreign API a
    callback with none of D008's identity, retirement or committed-body
    claims, indistinguishable at the call site from one that has them;
  - **the declared children policy holds**, reported exactly as it is for
    an internal boundary."
  [host args]
  (let [entry     (host-entry host)
        host-id   (:host-id entry)
        declared  (:callbacks entry)
        [props & children] args]
    (when-not (map? props)
      (bad-host-props-error!
        host-id
        (str "A Freehand boundary call takes exactly one props map: write "
             "[the-host {...} & children]. " host-id " was called with a "
             (value-tag props) " in the props slot; pass {} when the host "
             "needs no props.")
        :supply-one-props-map
        {:props (error/diag-value-summary props)}))
    (when (contains? props :children)
      (bad-host-props-error!
        host-id
        (str ":children is reserved — it is how trailing children arrive, so a props "
             "map may not carry it. Pass the children as trailing forms of the call "
             "instead: [the-host {…} child-1 child-2].")
        :pass-children-as-trailing-forms
        {}))
    (let [policy   (:children entry)
          children (when (seq children) (vec children))]
      (when (or (and (= :none policy) children)
                (and (= :required policy) (nil? children)))
        (error/throw-error!
          :rf.error/view-children-policy
          'v/defhost
          (if children
            (str host-id " declares :children :none and accepts no children; the call "
                 "supplied " (count children) ". A host's children cross into the "
                 "registered React component's tree, so a host that does not accept "
                 "them has nowhere to put them. Pass the content as a prop, or relax "
                 "the declared policy.")
            (str host-id " declares :children :required and the call supplied none. "
                 "Supply them as trailing forms of the call, or relax the declared "
                 "policy."))
          {:recovery :match-the-declared-children-policy
           :extra    {:view-id         host-id
                      :children-policy policy
                      :children-count  (count children)}}))
      (let [ordinary  (apply dissoc props :key (keys declared))
            callbacks (reduce-kv
                        (fn [m k role]
                          (if-let [v (host-callback-value host-id k role (get props k))]
                            (assoc m k v)
                            m))
                        {} declared)]
        (reduce-kv
          (fn [_ k v]
            ;; `fn?`, deliberately NOT `ifn?`: a keyword, a map, a set and a
            ;; vector are all `ifn?` and are all ordinary prop DATA, so `ifn?`
            ;; would refuse `{:variant :compact}`. `fn?` answers for the value
            ;; that really is a function — the same predicate the structural
            ;; tree's opaque-marker arm asks.
            (when (or (events/callback? v) (fn? v))
              (bad-host-props-error!
                host-id
                (str "The ordinary prop " k " on " host-id " carries a "
                     (if (events/callback? v)
                       (str "v/" (name (events/callback-role v)) " carrier")
                       "function")
                     ", and an ordinary prop is DATA. A callback reaches a host only "
                     "at a position the declaration named: add " k " to the host's "
                     ":callbacks map with the role it plays, so the site is finite, "
                     "checkable, and carries D008's committed identity.")
                :declare-the-callback-position
                {:prop k :value (error/diag-value-summary v)}))
            nil)
          nil ordinary)
        {:key       (:key props)
         :keyed?    (contains? props :key)
         :props     ordinary
         :callbacks callbacks
         :children  children}))))

