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
  (:require [re-frame.error :as error])
  #?(:cljs (:require-macros [re-frame.freehand.descriptor :refer [def-view-descriptor]])))

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
   (defmacro ^:no-doc def-view-descriptor
     "Define `ViewDescriptor` and its COMPLETE host call protocol, every
     arity routed to [[direct-call!]].

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
     []
     (let [cljs?  (some? (:ns &env))
           invoke (if cljs? '-invoke 'invoke)
           this   '_this
           body   (list `direct-call! 'entry)
           args   (fn [n] (map #(symbol (str "_a" %)) (range n)))
           meth   (fn [as] (list invoke (vec (cons this as)) body))]
       (concat
         (list 'deftype 'ViewDescriptor '[entry]
               'Object
               (list 'toString [this]
                     (list 'str "#re-frame.freehand/view " (list :view-id 'entry)))
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

(def-view-descriptor)

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

(defn host-descriptor?
  "True when `x` is a **declared host descriptor** — the third and last
  legal vector head, naming a foreign component behind a qualified host
  boundary.

  The marker is the reserved `:re-frame.freehand/host` key. The authoring
  surface that mints host descriptors (value-in / callback-out, the
  structural and SSR policy) is owned by the host-boundary sections of
  Spec 004 and lands with its own slice; what is fixed here is the tag
  [[classify-head]] recognises, so the classification is total today."
  [x]
  (and (map? x) (true? (:re-frame.freehand/host x))))

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
  reported as absence, never as `:any`. The descriptor's host `mount` and
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

(defn manifest
  "A COMPILED declaration's manifest — what its analysis makes statically
  knowable about it, as plain data — or `nil` for an interpreted one,
  which has no analysis to report. Re-exported on the door as
  `v/manifest`.

      {:view-id       :app.people/people-list
       :grammar       :re-frame.freehand/v1
       :subscriptions [{:sid … :query [:person 7] :path [0]}]
       :events        [] :slots [] :html-sites [] :frame-ops []
       :capabilities  #{:sub}
       :reactive?     true
       :view-cell     :present
       :crossings     [{:view-id :re-frame.freehand/markup
                        :lowering :interpreted :path [1]}]}

  The `:subscriptions` / `:events` / `:slots` / `:html-sites` /
  `:frame-ops` rosters are the view's finite lexical sites; `:capabilities`
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
      ;; => {:key panel-id
      ;;     :props {:title \"Details\" :children [[details {:id panel-id}]]}}

  The laws it enforces, identically in both execution modes:

  - **one map, no positional args** — `props` is a single map, `{}` when
    the view needs nothing. A non-map props slot is `:rf.error/view-bad-props`;
  - **`:children` is reserved** — trailing children arrive under it, and
    a caller-authored `:children` inside the props map is rejected. The
    key is ABSENT when the call supplied no children, so a childless call
    yields the smallest props map that can compare equal;
  - **`:key` is stripped** — it selects sibling identity for
    reconciliation and the view body never sees it. It is returned
    separately, and it is not part of the props map's equality;
  - **the declared children policy holds** — children against a `:none`
    view, or none against a `:required` view, is
    `:rf.error/view-children-policy`."
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
      {:key   (:key props)
       :props (cond-> (dissoc props :key)
                children (assoc :children children))})))

