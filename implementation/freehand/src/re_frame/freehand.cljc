(ns re-frame.freehand
  "The Freehand view substrate — the single public door of the re-frame2
  view layer (EP-0036; ruled by D001, conventionally aliased `v`).

  Freehand is one re-frame-native substrate with two execution modes over
  one semantic model: an interpreted paved path, and a compiled hot tier
  selected by `{:compiled true}` on the same declaration. A declared view
  var is a non-`IFn` descriptor in both modes.

  F1b (the paved-path spine's first public surface) lands the three
  host-neutral laws every later slice reads:

    1. `defview` binds a var to a small non-`IFn` **descriptor value**.
       A declared view is mounted, never invoked — `(the-view {})` cannot
       silently succeed the way a plain map would answer a lookup.
    2. `classify-head` is **total** over vector heads: a descriptor is an
       internal boundary, a keyword is a DOM / custom element, a declared
       host descriptor is a foreign boundary, and anything else raises
       `:rf.error/view-bad-head` naming those three legal forms.
    3. `normalize-call` fixes the one-props-map contract: `:key` is
       stripped before the view sees its props, and trailing children
       arrive under the reserved `:children` key.

  Everything here is **common** — host-neutral by construction, identical
  on the JVM and in ClojureScript, because both emitters (F1c) and the
  compiled analyzer (F3) consume exactly these values. Nothing in this
  namespace renders: the interpreted React and JVM trees, `mount`, `sub`,
  event intent and the compiled tier land with their own slices.

  Normative owner: [`spec/004-Views.md`](../../../../spec/004-Views.md)."
  (:require [re-frame.error :as error]
            [re-frame.freehand.events :as events]
            [re-frame.freehand.route-link-seam :as route-link-seam]
            [re-frame.interop :as interop]
            #?(:clj [re-frame.source-coords :as source-coords]))
  #?(:cljs (:require-macros [re-frame.freehand :refer [defview event handler render-fn]])))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The descriptor value
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §The descriptor and `v/defview`. `deftype`, deliberately, and
;; deliberately NOT `defrecord` or a plain map:
;;
;;   - a plain map is `IFn`, so `(the-view props)` would answer as a
;;     lookup — returning `nil` and rendering nothing, silently. The whole
;;     point of the ruling (D002) is that a direct call cannot succeed;
;;   - a `defrecord` is `IFn` in ClojureScript (records emit `-invoke`
;;     arities) but NOT on the JVM. That asymmetry is exactly the
;;     cross-host divergence Freehand exists to avoid;
;;   - a `deftype` implements neither `IFn` nor `Fn` on either host, so
;;     `(ifn? d)` is `false` and `(fn? d)` is `false` in both runtimes and
;;     a direct call raises at the host call site.
;;
;; One field, `entry`, holding the private declaration map. Later slices
;; add private entries (the host mount entry, the structural tree entry)
;; without changing the type, and `describe` keeps projecting only the
;; public ABI slots.

(deftype ViewDescriptor [entry]
  Object
  (toString [_] (str "#re-frame.freehand/view " (:view-id entry))))

#?(:clj
   (defmethod print-method ViewDescriptor [d ^java.io.Writer w]
     (.write w (str d)))
   :cljs
   (extend-protocol IPrintWithWriter
     ViewDescriptor
     (-pr-writer [d writer _opts]
       (-write writer (str d)))))

(defn view?
  "True when `x` is a view declared with [[defview]] — the ONE value a
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

(defn ^:no-doc declare-view
  "Build a [[ViewDescriptor]] from a declaration `entry` map. The
  expansion target of [[defview]] — not an authoring form. `entry` is
  private: read a descriptor through [[describe]]."
  [entry]
  (->ViewDescriptor entry))

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

(defn ^:no-doc render-body
  "The declared view's PRIVATE render body — the one-argument fn
  [[defview]] built from the declaration's parameter vector and body.

  Deliberately NOT projected by [[describe]]: an emitter runs a declared
  view by calling this, and nothing else does. A view is mounted, and
  mounting is what an emitter performs; an application that reached the
  body directly would be calling a view, which is the one thing the
  non-`IFn` descriptor exists to prevent."
  [view]
  (:render (.-entry ^ViewDescriptor view)))

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

    `:view`     the head is a view declared with [[defview]] — an internal
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

;; ---------------------------------------------------------------------------
;; The declaration form
;; ---------------------------------------------------------------------------

#?(:clj
   (defn ^:no-doc parse-defview-args
     "Parse `(defview name docstring? opts? [props] body+)` into
     `{:docstring :opts :params :body}`, or nil when the shape is not one
     of the four legal spellings. Plain CLJ so both the macro and its
     tests can reach it."
     [more]
     (let [[a b c] more]
       (cond
         (vector? a)                       {:docstring nil :opts nil :params a :body (next more)}
         (and (map? a) (vector? b))        {:docstring nil :opts a   :params b :body (nnext more)}
         (and (string? a) (vector? b))     {:docstring a   :opts nil :params b :body (nnext more)}
         (and (string? a) (map? b) (vector? c))
         {:docstring a :opts b :params c :body (nthnext more 3)}
         :else nil))))

#?(:clj
   (defn ^:no-doc expand-defview
     "Build the expansion form for a `defview` call. `form-meta` is
     `(meta &form)`, `file` is `*file*`, `ns-sym` is the consuming
     namespace's symbol — all captured at the call site so the emitted
     form holds literals rather than reading `*ns*` / `*file*` at runtime
     (CLJS binds neither)."
     [form-meta file ns-sym sym more]
     (let [{:keys [docstring opts params body]} (or (parse-defview-args more)
                                                    (error/throw-error!
                                                      :rf.error/defview-bad-args
                                                      'v/defview
                                                      (str "v/defview is spelled (v/defview name docstring? opts? "
                                                           "[props] body …) — a name, an optional docstring, an "
                                                           "optional options map, then exactly one parameter vector "
                                                           "and a body. " sym "'s declaration does not match.")
                                                      {:recovery :fix-the-declaration
                                                       :extra    {:view sym}}))
           policy (get opts :children-policy :optional)]
       (when-not (= 1 (count params))
         (error/throw-error!
           :rf.error/defview-bad-args
           'v/defview
           (str "A Freehand view takes exactly one argument — its props map. " sym
                " declares " (count params) ". There are no positional view arguments: "
                "destructure the props map instead.")
           {:recovery :fix-the-declaration
            :extra    {:view sym :params (count params)}}))
       (when-not (contains? children-policies policy)
         (error/throw-error!
           :rf.error/defview-bad-args
           'v/defview
           (str ":children-policy is one of " (pr-str (sort children-policies)) "; " sym
                " declares " (pr-str policy) ".")
           {:recovery :fix-the-declaration
            :extra    {:view sym :children-policy policy}}))
       `(def ~(cond-> (vary-meta sym assoc :re-frame.freehand/view true)
                docstring (vary-meta assoc :doc docstring))
          (declare-view
            {:view-id         ~(keyword (str ns-sym) (str sym))
             :source          (if interop/debug-enabled?
                                ~(source-coords/coords-form form-meta file ns-sym)
                                ~(source-coords/prod-coords-form form-meta file ns-sym))
             :lowering        :interpreted
             :children-policy ~policy
             :render          (fn ~(symbol (str sym "-render")) ~params ~@body)})))))

#?(:clj
   (defmacro defview
     "Declare a Freehand view — the ONE declaration form, and the only way
     to create an internal mounted boundary.

         (v/defview panel
           \"Optional docstring.\"
           {:children-policy :optional}       ; optional options map
           [{:keys [title children]}]         ; exactly one param — the props map
           [:section.panel [:h2 title] children])

     The var holds a small **non-`IFn` descriptor value**, not a callable
     function: `panel` is mounted as `[panel {…}]` and is never invoked.
     `(panel {})` raises at the host call site — the descriptor implements
     no call protocol on either host, so the mistake cannot quietly return
     `nil` the way a map-shaped descriptor would.

     A plain `defn` is the other half of the convention: helpers are
     direct-called with parentheses and run inside the boundary that
     called them, owning no subscriptions, no occurrence, no memoization
     and no error containment of their own. Changing brackets to
     parentheses changes runtime ownership, not spelling.

     Options (all optional):

       `:children-policy`  `:optional` (default), `:none`, or `:required`

     Per [Spec 004 §The descriptor and `v/defview`](../../../../spec/004-Views.md)."
     [sym & more]
     (expand-defview (meta &form) *file* (ns-name *ns*) sym more)))

;; ---------------------------------------------------------------------------
;; Event intent and the callback roster
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §Event intent and the payload materializer, §Callback roles
;; and identity (D006, D008). The mechanics — the site table, the
;; committed proxy, the host payload seams — are
;; `re-frame.freehand.events`, an INTERNAL namespace. What crosses to the
;; public door is the authoring surface plus the one pure materializer
;; every mode, host and test path shares.

(def ^{:doc "The CLOSED scalar projection roster — `::v/value`,
  `::v/checked` and `::v/key`.

  These are the only reserved markers a declarative event vector may
  carry, and the exact keys of the payload map a firing site supplies.
  A marker in a top-level argument position is replaced at firing time
  from the live callback payload; a marker nested inside another value
  is ordinary application data. Adding a fourth projection is a grammar
  decision — anything richer than a shallow scalar read is [[event]]'s
  job.

  Per [Spec 004 §Event intent and the payload materializer](../../../../spec/004-Views.md)."}
  projections events/projections)

(def ^{:doc "The ONE pure event materializer: replace the reserved
  projection markers in an event vector with the live scalars in a
  payload map, and return a plain vector ready for ordinary re-frame
  dispatch.

      (v/materialize-event [:account/email-edited ::v/value]
                           {::v/value \"mike@example.com\"})
      ;; => [:account/email-edited \"mike@example.com\"]

  Every path runs through exactly this function — a literal vector, a
  forwarded `(conj on-change ::v/value)`, an options map's `:event`, an
  [[event]] body's result, interpreted, compiled, production and test.
  That is why general `rf/dispatch` needs no payload arity: projection
  is a Freehand event-site concern, and a projection keyword inside an
  ordinary domain event is never secretly interpreted.

  It is exposed so a structural test can supply a literal payload and
  assert the exact dispatched vector without a browser, running the
  production semantics rather than a test-only convention.

  Per [Spec 004 §Event intent and the payload materializer](../../../../spec/004-Views.md)."
       :arglists '([event payload])}
  materialize-event events/materialize-event)

(def ^{:doc "The expert callback seam: hand a foreign API a function with
  EXACTLY its supplied identity, for the case where that identity is
  itself protocol data — a listener the library removes by identity, a
  memo key it compares.

  Every other roster form gets a site-owned stable proxy; this one
  deliberately does not, so re-render churn is the author's to manage.
  Reach for it only when a wrapper or [[event]] genuinely cannot express
  the protocol.

  Per [Spec 004 §Callback roles and identity](../../../../spec/004-Views.md)."
       :arglists '([f])}
  raw-fn events/raw-fn)

#?(:clj
   (defmacro event
     "Declare a callback that converts an invoker's arguments into ONE
     event vector or `nil` — the explicit conversion seam at a foreign
     boundary.

         [date-picker {:on-change (v/event [date]
                                    [:booking/departure-changed (iso-date date)])}]

     The body runs synchronously with the live callback arguments and
     NAMES its outcome: an event vector dispatches (through the same
     materializer every other event form uses, so `::v/value` and
     friends still fill), `nil` dispatches nothing, and anything else is
     a loud diagnostic. It may not `v/sub`, use hooks, refs or effects —
     decisions that depend on changing application state belong in the
     receiving re-frame event handler, which sees the committed frame.

     Its identity is stable per site: an unchanged site keeps the exact
     callback across re-renders while body changes publish atomically at
     commit, so a foreign consumer sees no churn and never a stale body.

     Per [Spec 004 §Callback roles and identity](../../../../spec/004-Views.md)."
     [params & body]
     (events/expand-callback :event 'v/event params body)))

#?(:clj
   (defmacro handler
     "Declare an explicit IMPERATIVE foreign callback — work that is not
     application intent and produces no event.

         [canvas-host {:measure (v/handler [node] (measure! node))}]

     The body's return is ignored; it is neither a render nor a place to
     store state. Like [[event]] it is stable per site and reads the
     exact committed body when invoked, and it is retired with its site,
     so a foreign listener that outlives its view is inert rather than
     firing into a successor.

     Per [Spec 004 §Callback roles and identity](../../../../spec/004-Views.md)."
     [params & body]
     (events/expand-callback :handler 'v/handler params body)))

#?(:clj
   (defmacro render-fn
     "Declare a PURE callback a foreign owner invokes during ITS render.

         [virtual-list {:render-item (v/render-fn [{:keys [id]}]
                                       [result-row {:id id}])}]

     It may return Freehand content, and it may NOT `v/sub`, dispatch,
     use hooks or touch refs — it can run during an uncommitted
     candidate render, which is exactly why it is excluded from the
     committed-proxy scheme. Freehand makes no identity promise here: a
     lowering may reuse it when the descriptor and its captures compare
     equal, and an API that treats callback identity as protocol data
     uses [[raw-fn]] or a wrapper instead.

     Per [Spec 004 §Callback roles and identity](../../../../spec/004-Views.md)."
     [params & body]
     (events/expand-callback :render-fn 'v/render-fn params body)))

;; ---------------------------------------------------------------------------
;; `route-link` — a framework view, declared the ordinary way
;; ---------------------------------------------------------------------------
;;
;; A framework-supplied view is not a privileged one. `route-link` is
;; declared with the same `defview` an application uses, holds the same
;; non-`IFn` descriptor, takes the same one props map, and will be lowered
;; by the same emitters — there is no route-link intrinsic to teach, to
;; special-case in the analyzer, or to keep in step with the paved path.
;;
;; The body is one call into `route-link-seam`, which owns the anchor and
;; consumes the two late-bound `:routing/*` hooks. Spec 012 keeps href and
;; click semantics; Freehand contributes the descriptor and nothing else.

(defview route-link
  "(v/route-link {:to :route-id …html-attrs} & children) — a navigation
  anchor.

  Renders a REAL `<a href=…>` carrying the route's strategy-encoded href,
  so copy-link, open-in-new-tab, keyboard activation and no-JavaScript
  navigation all work; on a plain in-app left click it dispatches the
  routing cascade to the frame that rendered it instead of letting the
  browser reload the page.

      [v/route-link {:to :article :params {:slug slug} :class \"title\"}
       title]

  `:to` is required (a registered route id). `:params` / `:query` /
  `:fragment` feed both the href and the dispatch payload. Every OTHER
  key — `:class`, `:title`, `:id`, `:aria-label`, `:target`, `:download`,
  `:on-click`, and any further HTML attribute — passes through to the
  `<a>`.

  A caller `:on-click` runs FIRST and may veto. If it prevents the
  default — or the anchor is native (`:target` other than `_self`, or
  `:download`), or the click is a modifier / middle / auxiliary-button
  click — the framework does NOT intercept and the browser's own `href`
  behaviour stands. That deferral is the whole reason to use this view
  rather than hand-rolling one.

  Rendering it without `day8/re-frame2-routing` on the classpath fails
  loud with `:rf.error/routing-artefact-missing` rather than emitting a
  dead link; a plain `[:a]` stays available for intentional
  browser-native navigation. JVM and SSR render the handler-free
  path-form shell, and the hydrated client re-encodes through the frame's
  URL strategy.

  The behavioural contract and its conformance rows live in
  [Spec 012 §The Freehand route-link descriptor](../../../../spec/012-Routing.md)
  — routing owns the law, this view supplies the descriptor."
  [props]
  (route-link-seam/anchor props))
