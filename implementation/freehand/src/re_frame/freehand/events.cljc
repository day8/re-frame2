(ns re-frame.freehand.events
  "Freehand's event plane — **intent is data** (EP-0036 governing law 5;
  ruled by D006 and D008).

  One user action yields exactly ONE semantic event vector or `nil`. This
  namespace owns the three mechanisms that make that true, and nothing
  else:

    1. **The scalar projection door and the one pure materializer.** The
       named roster — `::v/value`, `::v/checked`, `::v/key`,
       `::v/scroll-top`, `::v/new-state` — plus the general
       `[::v/read <path>]` door are the reserved scalar markers; the named
       members are SUGAR for a `::v/read` of one host path, so there is
       one reader and one law. At firing time the site's host adapter
       reads the live payload, and [[materialize-event]] replaces every
       matching TOP-LEVEL argument marker — proving each read lands on a
       shallow scalar — before the resulting plain vector goes to ordinary
       re-frame dispatch. General `rf/dispatch` therefore gains no payload
       arity, in either direction: a projection marker inside a domain
       event is never secretly interpreted, and no dispatcher can
       deliberately supply a payload.

    2. **The closed event grammar at an event position.** A vector, an
       options map carrying `:event` plus the closed listener options, a
       roster callback, a bare function, or `nil`. [[event-plan]] is
       TOTAL over that roster and raises `:rf.error/view-bad-event` for
       anything else.

    3. **Per-site committed slots.** A render builds an ownership-free
       candidate table; only the SELECTED render commits it. Each site
       owns one stable proxy that reads the exact committed body and
       dispatch target when it is invoked. A later commit may replace
       that body without changing the proxy's identity — which is what
       stops a re-render from churning callback identity through React —
       and retirement makes the exact proxy inert.

       A proxy is bound to the site INCARNATION that minted it, never to
       `(owner, site-key)` alone: it dispatches only while it is the
       proxy the committed descriptor carries. So a candidate that is
       never selected publishes nothing even after a DIFFERENT candidate
       commits its key, and a removed site's proxy stays inert when that
       key is later re-used — the two lifetime transitions a key-only
       lookup would silently allow.

  Everything here is **common**: the same values, the same laws, and the
  same diagnostics on the JVM and in ClojureScript. Exactly two things
  are host-shaped, and both are named seams rather than hidden branches
  — reading the scalar payload off a live callback argument
  ([[native-payload]] vs [[payload-map]]) and running the selected
  browser mechanics before dispatch. The frame-bound dispatchers are
  injected at [[commit!]] — the ordinary batched one and the synchronous
  controlled-input one — so this namespace neither creates nor observes
  frames, and it schedules nothing. Which lane a site takes is the door
  verdict recorded on its committed plan; what the door IS belongs to
  [[re-frame.freehand.controlled]], and what the synchronous lane DOES
  belongs to [[re-frame.freehand.cell]].

  INTERNAL. The public door is `re-frame.freehand`, which re-exports the
  authoring surface — the roster forms, the projection roster, and the
  one pure materializer. The site machinery below is consumed by the
  emitters, not by applications.

  Normative owner: [`spec/004-Views.md`](../../../../../spec/004-Views.md)
  §Event intent and the payload materializer, §Callback roles and
  identity."
  (:require [re-frame.error :as error]
            [re-frame.freehand.controlled :as controlled]
            [re-frame.interop :as interop]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Diagnostics
;; ---------------------------------------------------------------------------

(defn ^:no-doc value-tag
  "A short, HOST-NEUTRAL tag for a value's shape — for the human sentence
  of a diagnostic. `diag-value-summary`'s `:type` is one vocabulary on
  both hosts and carries the value's SHAPE only, never the value itself
  (Spec 015 §Data-Classification)."
  [v]
  (name (:type (error/diag-value-summary v))))

(defn- bad-event!
  [where reason recovery extra]
  (error/throw-error! :rf.error/view-bad-event where reason
                      {:recovery recovery :extra extra}))

;; ---------------------------------------------------------------------------
;; The scalar projection door — named sugar over one shallow-scalar read
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §Event intent and the payload materializer. A projected read
;; is one SHALLOW SCALAR off the live event or its target, kept as DATA:
;; assertable by equality, printable, comparable, host-neutral. Two
;; spellings, one mechanism and one law:
;;
;;   * the NAMED roster — `::v/value`, `::v/checked`, `::v/key`,
;;     `::v/scroll-top`, `::v/new-state` — the reads common enough to
;;     have earned a name; and
;;   * the GENERAL door `[::v/read <path>]`, which reads any single
;;     shallow scalar off the event: a property keyword (`[::v/read
;;     :key]` is `event.key`), or a vector of keywords walked as a chain
;;     from the event (`[::v/read [:target :scrollTop]]` is
;;     `event.target.scrollTop`).
;;
;; The named markers are SUGAR: each is a `::v/read` of the one host path
;; it has always read (see [[sugar-paths]]), so there is ONE reader and
;; one law rather than a table per member. The marker and the payload
;; key are deliberately the SAME value — a site asks for `::v/value` (or
;; `[::v/read [:target :value]]`) and the adapter supplies exactly that
;; key — so "did the callback offer what this event asked for?" is one
;; lookup with no second vocabulary to keep in step.
;;
;; What generalising does NOT do is move the line. A read is admitted
;; only when the path RESOLVES to a scalar in the accepted-identity
;; domain — a string, number, boolean or keyword — which is precisely
;; what keeps the projected intent equality-assertable, printable and
;; host-neutral; a read landing on a host object, a collection or nothing
;; is a typed error naming `v/event` as the recovery. Anything richer
;; than a shallow scalar read — multiple reads, computation, a live host
;; object, a side effect — was never a projection and remains `v/event`'s
;; opaque job. So the door is open to more PATHS, never to more than a
;; scalar's worth of intent.

(def ^:private read-marker
  "The head of the general shallow-scalar door, `[::v/read <path>]`."
  :re-frame.freehand/read)

(defn- read-path?
  "True when `path` is a legal `::v/read` path — one property keyword, or
  a non-empty vector of property keywords read as a chain from the event."
  [path]
  (or (keyword? path)
      (and (vector? path) (seq path) (every? keyword? path))))

(defn- read-marker?
  "True when `x` is a `[::v/read …]` form — the general-door HEAD, checked
  WITHOUT asserting the whole shape, so a malformed door is detected here
  and diagnosed at materialization rather than mistaken for plain data."
  [x]
  (and (vector? x) (= read-marker (nth x 0 nil))))

(defn- read-marker-path [x] (nth x 1 nil))

(defn- read-scalar?
  "The accepted-identity domain a projected read must land in — a string,
  number, boolean or keyword. Anything else is not a projection, and the
  door refuses it in favour of `v/event`."
  [v]
  (or (string? v) (number? v) (boolean? v) (keyword? v)))

(def sugar-paths
  "Each NAMED roster marker as the `::v/read` path it is sugar for — the
  three form-control reads and the two demonstrated-need members, spelled
  as the host paths the one reader walks. `::v/value`, `::v/checked` and
  `::v/scroll-top` read the event TARGET; `::v/key` and `::v/new-state`
  read the event itself. This is the whole of what closedness bought that
  the general door does not: a short list of pre-named paths, not a
  private read mechanism the door lacks."
  {:re-frame.freehand/value      [:target :value]
   :re-frame.freehand/checked    [:target :checked]
   :re-frame.freehand/key        [:key]
   :re-frame.freehand/scroll-top [:target :scrollTop]
   :re-frame.freehand/new-state  [:newState]})

(def projections
  "The NAMED scalar projection markers — value, checked state, key, scroll
  offset, and a top-layer element's new state.

  These are the reserved keywords a declarative event vector may carry,
  AND the exact keys of the payload map a firing site supplies for them.
  Each is SUGAR for a `[::v/read <path>]` of the one host property it
  names (see [[sugar-paths]]); the general door reads any other shallow
  scalar the same way, so the NEXT read no longer needs a new name — it
  needs a path.

  What stays closed is the PROPERTY every projection keeps, not the set
  of members: a read is admitted only when it resolves to a shallow
  scalar (string, number, boolean, keyword), which is what keeps the
  projected intent assertable by equality, printable and host-neutral.
  Anything richer is [[materialize-event]]'s `v/event` job, not a
  projection."
  (set (keys sugar-paths)))

;; ---------------------------------------------------------------------------
;; The one pure materializer
;; ---------------------------------------------------------------------------

(defn- available-markers
  "The markers `payload` supplies, in a stable printable order.

  A payload's keys are HETEROGENEOUS by construction — a named marker is a
  keyword and a general door is a vector — and those do not compare, so
  `sort` throws a raw host comparison error on the very payload a missing
  projection is most likely to be diagnosed from. Ordering by the printed
  form is total over every marker shape the door admits, and it is the form
  the diagnostic shows anyway."
  [payload]
  (vec (sort-by pr-str (keys payload))))

(defn- payload-value
  [marker event payload]
  (if (contains? payload marker)
    (get payload marker)
    (let [available (available-markers payload)]
      (error/throw-error!
        :rf.error/view-missing-payload
        'v/materialize-event
        (str "This event site asks for " (pr-str marker) " but the callback that fired it "
             "supplies no such payload" (if (seq payload)
                                          (str " (it supplies " (pr-str available) ")")
                                          "")
             ". Nothing is dispatched — a malformed event vector is worse than none. Ask for "
             "a projection the site's own callback carries, or convert the argument "
             "explicitly with v/event.")
        {:recovery :ask-for-a-projection-the-callback-supplies
         :extra    {:projection marker
                    :available  available
                    :event-id   (nth event 0 nil)
                    :event      (error/diag-value-summary event)}}))))

(defn- bad-read-shape!
  [marker]
  (bad-event!
    'v/materialize-event
    (str "A general projection door is [::v/read <path>], where <path> is ONE property "
         "keyword read off the event (" (pr-str [read-marker :key]) ") or a non-empty vector "
         "of keywords read as a chain from it (" (pr-str [read-marker [:target :scrollTop]])
         "). This one is " (pr-str marker) ".")
    :write-a-read-door-as-a-keyword-or-keyword-path
    {:marker (error/diag-value-summary marker)}))

(defn- read-non-scalar!
  [marker v]
  (bad-event!
    'v/materialize-event
    (str "The projection door " (pr-str marker) " read a " (value-tag v) ", and a projection "
         "must resolve to a SHALLOW SCALAR — a string, number, boolean or keyword. A read that "
         "lands on a host object or a collection is not intent data; do that work in a v/event "
         "body, where a richer read belongs and the intent is opaque by design.")
    :read-a-shallow-scalar-or-use-v-event
    {:projection marker :value (error/diag-value-summary v)}))

(defn- read-projection
  "The substituted value for ONE marker — the payload the callback supplied
  under it, proved to be a shallow scalar.

  ONE function for both spellings, deliberately. `::v/value` IS
  `[::v/read [:target :value]]` (see [[sugar-paths]]), so a named marker
  that accepted a host object while its own expansion refused one would
  make the roster a second law wearing the door's name — and the whole
  point of naming the common paths was that they read through the same
  door, not beside it."
  [marker event payload]
  (let [v (payload-value marker event payload)]
    (if (read-scalar? v) v (read-non-scalar! marker v))))

(defn- materialize-arg
  "Replace ONE top-level argument. A named roster keyword and a
  `[::v/read <path>]` door are both substituted from `payload` — keyed by
  the marker itself, so the lookup is one law — and both must land on a
  shallow scalar; a door additionally proves its own shape first, since a
  malformed one has no path to read. Any other value is ordinary
  application data and is left exactly as it is."
  [x event payload]
  (cond
    (contains? projections x)
    (read-projection x event payload)

    (read-marker? x)
    (do
      (when-not (and (= 2 (count x)) (read-path? (read-marker-path x)))
        (bad-read-shape! x))
      (read-projection x event payload))

    :else x))

(defn materialize-event
  "The ONE pure event materializer: replace the reserved projection
  markers in `event` with the live scalars in `payload`, and return a
  plain event vector ready for ordinary re-frame dispatch.

      (materialize-event [:account/email-edited ::v/value]
                         {::v/value \"mike@example.com\"})
      ;; => [:account/email-edited \"mike@example.com\"]

      (materialize-event [:table/scrolled [::v/read [:target :scrollTop]]]
                         {[::v/read [:target :scrollTop]] 3200})
      ;; => [:table/scrolled 3200]

  Its semantics are deliberately small, and every path — a literal
  vector, a forwarded `(conj on-change ::v/value)`, an options map's
  `:event`, a `v/event` body's result, interpreted, compiled, production
  and test — runs through exactly this function:

  - **position zero may not be a marker.** An event id is a name, not a
    projection or a read door;
  - **only TOP-LEVEL argument positions are replaced.** A named marker or
    a `[::v/read <path>]` door in an argument position is substituted;
    the SAME form nested inside a map or a deeper vector is ordinary
    application data and is left alone, as is every non-marker value;
  - **a projection must read a shallow scalar** — a named marker and a
    `[::v/read <path>]` door alike, since the named ones are sugar over the
    door. A read that lands on a host object, a collection or nothing is a
    typed error naming `v/event`; that scalar law is what keeps a projected
    read equality-assertable and host-neutral;
  - **every occurrence is replaced**, not merely the first;
  - **a requested but unavailable payload is a typed error** and nothing
    is dispatched, rather than a malformed event reaching a handler;
  - **the result is a plain vector.** When the event carries no marker it
    is returned unchanged — identical, not merely equal, so an
    unprojected site allocates nothing.

  A site produces exactly one event vector or `nil`; `nil` is handled by
  the caller (nothing is dispatched) and never reaches here, so a
  non-vector — including a vector OF event vectors, the multi-intent
  mistake — is `:rf.error/view-bad-event`."
  [event payload]
  (when-not (vector? event)
    (bad-event!
      'v/materialize-event
      (str "A Freehand event site yields exactly one event vector or nil; this one yielded a "
           (value-tag event) ". Multi-step work is one semantic event whose re-frame handler "
           "returns the effects it needs — that keeps one inspectable causal unit instead of a "
           "miniature dispatcher in the view.")
      :yield-one-event-vector-or-nil
      {:event (error/diag-value-summary event)}))
  (let [id (nth event 0 nil)]
    (when (contains? projections id)
      (bad-event!
        'v/materialize-event
        (str "Position zero of an event vector is its event ID, and " id " is a reserved "
             "projection marker. A projection fills an ARGUMENT position: write "
             "[:my-event " id "].")
        :name-the-event-id-at-position-zero
        {:projection id}))
    (when (read-marker? id)
      (bad-event!
        'v/materialize-event
        (str "Position zero of an event vector is its event ID, and " (pr-str id) " is a "
             "projection READ door. A door fills an ARGUMENT position: write "
             "[:my-event " (pr-str id) "].")
        :name-the-event-id-at-position-zero
        {:projection id}))
    (when (vector? id)
      (bad-event!
        'v/materialize-event
        (str "A Freehand event site yields exactly one event vector, and this one is a vector "
             "of event vectors. One user action is one semantic event: give the intent a name "
             "and let its re-frame handler return the effects the step needs.")
        :yield-one-event-vector-or-nil
        {:event (error/diag-value-summary event)})))
  (if (or (some projections event) (some read-marker? event))
    (into [(nth event 0)]
          (map (fn [x] (materialize-arg x event payload)))
          (subvec event 1))
    event))

;; ---------------------------------------------------------------------------
;; The callback roster
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §Callback roles and identity (D008). Four declared forms, one
;; carrier. A `deftype` for the same reason the view descriptor is one:
;; a roster value is not `IFn` on either host, so it can never be
;; mistaken for the bare function it wraps, and a foreign API handed one
;; by accident fails at the call rather than silently doing the wrong
;; thing.

(deftype Callback [role f arity]
  Object
  (toString [_] (str "#re-frame.freehand/callback " role)))

#?(:clj
   (defmethod print-method Callback [c ^java.io.Writer w]
     (.write w (str c)))
   :cljs
   (extend-protocol IPrintWithWriter
     Callback
     (-pr-writer [c writer _opts]
       (-write writer (str c)))))

(def callback-roles
  "The CLOSED roster of declared callback forms.

  `:event` converts a foreign callback's arguments into one event vector
  or `nil`; `:handler` is explicit imperative foreign work whose return
  is ignored; `:render-fn` is a pure function a foreign owner invokes
  during ITS render; `:raw-fn` is the expert seam for an API where the
  authored function's identity is itself protocol data. There is no
  `v/dispatcher`: `v/event` is the one conversion seam, and appending a
  raw callback argument to an intent vector is exactly how host objects
  get into event data."
  #{:event :handler :render-fn :raw-fn})

(defn callback?
  "True when `x` is a declared roster callback — `v/event`, `v/handler`,
  `v/render-fn`, or `v/raw-fn`."
  [x]
  (instance? Callback x))

(defn callback-role
  "The roster role of a declared callback — one of [[callback-roles]]."
  [x]
  (.-role ^Callback x))

(defn callback-fn
  "The function a declared callback carries. For `v/raw-fn` this is
  EXACTLY the supplied function: Freehand promises no stabilization
  there, so the identity an API receives is the identity it was given."
  [x]
  (.-f ^Callback x))

(defn callback-arity
  "The DECLARED parameter count of a body-taking roster callback — the
  length of the vector the author wrote — or `nil` for `v/raw-fn`, whose
  function arrived from elsewhere and declares nothing here.

  Recorded because one roster role has an arity CONTRACT rather than a
  convention: a `v/render-fn` is invoked by `v/slot` with a fixed number
  of arguments, and the two hosts disagree about what a mismatch does —
  JavaScript silently drops surplus arguments and passes `undefined` for
  missing ones, the JVM throws a raw `ArityException`. Carrying the
  declared count lets [[check-slot-arity!]] answer host-identically,
  before the call, with a diagnostic instead of either."
  [x]
  (.-arity ^Callback x))

(defn ^:no-doc callback
  "Build a roster callback. The expansion target of `v/event`,
  `v/handler` and `v/render-fn`, and the body of `v/raw-fn`.

  `arity` is the declared parameter count, present for the body-taking
  forms and absent for `v/raw-fn`."
  ([role f] (->Callback role f nil))
  ([role f arity] (->Callback role f arity)))

(defn raw-fn
  "The expert seam: hand `f` to a foreign API with EXACTLY its supplied
  identity, when that identity is itself protocol data (a listener a
  library removes by identity, a memo key it compares).

  Freehand promises no stabilization here — that is the whole point.
  Every other roster form gets a site-owned stable proxy; this one
  deliberately does not, so re-render churn is the author's to manage."
  [f]
  (callback :raw-fn f))

#?(:clj
   (defn ^:no-doc expand-callback
     "Build the expansion for one of the body-taking roster macros.
     `where` is the authoring spelling used in diagnostics."
     [role where params body]
     (when-not (vector? params)
       (bad-event!
         where
         (str where " is spelled (" where " [args …] body …) — a parameter vector naming the "
              "arguments the invoker supplies, then a body. This declaration has a "
              (value-tag params) " where the parameter vector belongs.")
         :fix-the-callback-declaration
         {:params (error/diag-value-summary params)}))
     ;; A `:render-fn` carries a host-independent FIXED-arity contract with
     ;; `v/slot`: the compiled analyzer already refuses variadic `&`
     ;; (`analyze-render-fn` → `:rf.ui.compile/bad-render-fn`), so the
     ;; interpreted authoring path must reach the SAME verdict rather than
     ;; recording `(count params)`. For `[x & xs]` that count is 3 — an arity
     ;; the generated function does not accept, and one `check-slot-arity!`
     ;; would then enforce against a valid one-argument `v/slot` call. The
     ;; refusal is role-specific: `v/event` / `v/handler` have no slot-arity
     ;; contract, so their variadic forms are untouched.
     (when (and (= role :render-fn) (some #{'&} params))
       (bad-event!
         where
         (str where " parameters are a FIXED arg list — variadic & is not "
              "permitted, because a v/slot invokes a render-fn with a fixed "
              "number of arguments and the declaration must say exactly how "
              "many. Name the parameters the slot supplies; a render-fn is not "
              "a variadic function.")
         :fix-the-render-fn-parameter-vector
         {:params (error/diag-value-summary params)}))
     `(callback ~role (fn ~params ~@body) ~(count params))))

;; ---------------------------------------------------------------------------
;; Render slots — the `v/render-fn` value and the `v/slot` invocation
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §Render slots. A render slot is ONE grammar with two front ends,
;; exactly like presence: `(v/render-fn [args…] template)` is a seq form the
;; compiled analyzer recognises and lowers, and an ordinary macro call an
;; interpreted body expands — and both produce a `:render-fn` roster callback.
;; `(v/slot render-fn-value arg…)` invokes it, and the vocabulary that gates
;; the invocation lives HERE rather than in either walk, because the value
;; being gated is a member of the roster this namespace owns.
;;
;; What DOES differ between the modes is what the invoked body answers, and
;; that difference is D010 rather than an inconsistency: an interpreted
;; render-fn answers MARKUP, which its (interpreted) caller walks; a compiled
;; one answers a NODE, which its caller splices. A compiled render-fn is
;; therefore usable from an interpreted slot — a node is a child value
;; anywhere — while an interpreted one handed to a COMPILED slot lands on the
;; markup-in-a-compiled-body refusal, which is the ladder D010 already states.

(defn render-fn?
  "Is `x` a `v/render-fn` value — a roster callback in the `:render-fn`
  role?"
  [x]
  (and (callback? x) (= :render-fn (callback-role x))))

(defn invalid-slot!
  "The didactic refusal for a `v/slot` value that is neither `nil`, a
  `v/render-fn`, nor (interpreted only) an ordinary function."
  [x]
  (error/throw-error!
    :rf.error/ui-tree-malformed 're-frame.freehand/slot
    (str "(v/slot render-fn-value arg…) received " (value-tag x)
         " — a slot invokes parameterized content supplied by the CALLER, and "
         "the roster of things that can be one is closed: a (v/render-fn "
         "[args…] template) value, nil (renders nothing), or — in an "
         "INTERPRETED body only — an ordinary pure function of the same "
         "arguments. Declare the content with v/render-fn.")
    {:recovery :no-recovery :extra {:value (error/diag-value-summary x)}}))

(defn check-slot-arity!
  "Enforce the host-independent `v/slot` <-> `v/render-fn` arity contract
  BEFORE invocation: a slot passing `argc` arguments to a render-fn that
  declared a different fixed parameter count is a didactic
  `:rf.error/ui-tree-malformed` on BOTH hosts — neither host's native
  behaviour is a diagnostic (JavaScript silently drops surplus arguments,
  the JVM throws a raw `ArityException`)."
  [rf argc]
  (let [arity (callback-arity rf)]
    (when (and (some? arity) (not= arity argc))
      (error/throw-error!
        :rf.error/ui-tree-malformed 're-frame.freehand/slot
        (str "(v/slot render-fn arg…) passed " argc " argument"
             (when (not= 1 argc) "s") " to a v/render-fn that declares " arity
             " parameter" (when (not= 1 arity) "s")
             " — a render-fn is a FIXED-arity callback, so a slot passes "
             "exactly its declared parameters. Match the slot's argument "
             "count to the render-fn's parameter vector.")
        {:recovery :no-recovery :extra {:expected arity :actual argc}}))))

(defn slot-ready?
  "Gate a `v/slot` value on the COMPILED path: `nil` renders nothing
  (false), a `v/render-fn` renders (true), anything else — a bare
  function included — is [[invalid-slot!]].

  The bare-fn refusal is the runtime half of a deliberate asymmetry. An
  interpreted body may pass an ordinary pure function as parameterized
  content, because an interpreted slot has nothing to prove about it; a
  compiled one may not, because the compiled tier's whole claim is that it
  can SEE the content it lowers, and a function value it cannot see is
  exactly the claim it declines to make."
  [x]
  (cond
    (nil? x)       false
    (render-fn? x) true
    :else          (invalid-slot! x)))

(defn invoke-slot
  "The INTERPRETED `v/slot` invocation: `nil` renders nothing, a
  `v/render-fn` renders with `args`, and an ordinary function renders with
  them too — the interpreted-only widening [[slot-ready?]] refuses. Answers
  what the invoked body answered, which the interpreted walk then treats as
  the ordinary child it is."
  [x args]
  (cond
    (nil? x)       nil
    (render-fn? x) (do (check-slot-arity! x (count args))
                       (apply (callback-fn x) args))
    (fn? x)        (apply x args)
    :else          (invalid-slot! x)))

;; ---------------------------------------------------------------------------
;; The closed event grammar
;; ---------------------------------------------------------------------------

(def event-options
  "The CLOSED options-map roster: the required `:event` vector plus the
  five shallow listener options.

  `:prevent-default` and `:stop-propagation` are browser mechanics run
  BEFORE dispatch; `:once` retires the site's intent after one firing;
  `:passive` and `:capture` are native listener-attachment facts the
  emitter reads. The roster is closed — an unknown key is a typo, and a
  typo that silently does nothing is the failure mode this rejects.

  A MAP at an event position is this form and no other, so the roster is
  the whole grammar of a map there."
  #{:event :prevent-default :stop-propagation :once :passive :capture})

(defn- options-plan
  [m]
  (let [unknown (remove event-options (keys m))
        event   (:event m)]
    (when (seq unknown)
      (bad-event!
        'v/event-site
        (str "An event options map carries a vector :event plus the closed listener options "
             (pr-str (vec (sort event-options))) "; this one also carries "
             (pr-str (vec (sort unknown))) ".")
        :use-the-closed-listener-options
        {:unknown-keys (vec (sort unknown))
         :legal-keys   (vec (sort event-options))}))
    (when-not (vector? event)
      (bad-event!
        'v/event-site
        (str "An event options map states its intent under :event, as an event vector; this "
             "one supplies " (if (contains? m :event) (str "a " (value-tag event)) "none") ". "
             "Write {:event [:my-event …] :prevent-default true}.")
        :supply-a-vector-event
        {:event (error/diag-value-summary event)}))
    (cond-> {:role :event-options :event event}
      (:prevent-default m)  (assoc :prevent-default true)
      (:stop-propagation m) (assoc :stop-propagation true)
      (:once m)             (assoc :once true)
      (:passive m)          (assoc :passive true)
      (:capture m)          (assoc :capture true))))

(defn event-plan
  "Classify the value at an event position into its closed **site plan**,
  or `nil` when the position is empty. TOTAL over the roster:

    a vector        → `:event-vector`   — declarative intent
    an options map  → `:event-options`  — intent plus listener options
    a roster callback → its role        — `:event` / `:handler` /
                                          `:render-fn` / `:raw-fn`
    a bare function → `:bare-fn`        — legal at a native `:on-*` site,
                                          where the site's own committed
                                          adapter owns its lifetime
    `nil`           → `nil`             — an empty position

  A MAP is the options map and nothing else, so it is classified by
  [[options-plan]] against the closed [[event-options]] roster, and
  `:rf.error/view-bad-event` for anything else, naming the roster. The
  plan is a plain map, so both emitters and the structural host read one
  shape."
  [v]
  (cond
    (nil? v)      nil
    (vector? v)   {:role :event-vector :event v}
    (callback? v) {:role (callback-role v) :f (callback-fn v)}
    (map? v)      (options-plan v)
    (fn? v)       {:role :bare-fn :f v}
    :else
    (bad-event!
      'v/event-site
      (str "An event position takes an event vector, an options map carrying :event, one "
           "of the declared callback forms (v/event, v/handler, v/render-fn, v/raw-fn), a "
           "plain function, or nil; got a " (value-tag v) ".")
      :use-one-of-the-declared-event-forms
      {:legal-forms [:event-vector :event-options :v-event :v-handler :v-render-fn
                     :v-raw-fn :bare-fn :nil]
       :value       (error/diag-value-summary v)})))

;; ---------------------------------------------------------------------------
;; Reading the live payload — the one host-shaped seam
;; ---------------------------------------------------------------------------

(defn payload-map
  "The extractor for a callback handed its payload map DIRECTLY. It is
  the JVM structural host's default — that host fires no native event —
  and it is what a cross-host fixture names explicitly, so one body of
  test code proves the materializer identically on both hosts."
  [payload]
  payload)

#?(:cljs
   (defn read-scalar-at
     "Read the scalar `path` names off live event `e` — one property
     keyword read off the event, or a vector of keywords walked as a chain
     from it. Returns nil when any step is absent, which is how a member is
     present in the payload only when the event actually carries it. The
     ONE host read the named roster and the general `[::v/read <path>]`
     door share; no DOM node or synthetic event leaves it, only the leaf
     scalar."
     [e path]
     (reduce (fn [o k] (when (some? o) (unchecked-get o (name k))))
             e
             (if (keyword? path) [path] path))))

#?(:cljs
   (defn native-payload
     "Read the NAMED scalar roster off a live native / React synthetic
     event — each marker resolved from [[sugar-paths]] by [[read-scalar-at]],
     the same reader the general door uses. A member is present only when
     the event actually carries the property it reads, so asking a click
     for `::v/key` is a typed error rather than a silent `nil` reaching a
     handler.

     `::v/key` and `::v/new-state` read the EVENT and are carried by the
     event kinds that have them; `::v/value`, `::v/checked` and
     `::v/scroll-top` read the TARGET and follow its shape — a `<div>` has
     no `value`, so `::v/value` is absent there, while every element has a
     scroll offset and so carries `::v/scroll-top` truthfully at 0.
     Presence tracks the host, and the host is what a projection is a read
     of.

     A general `[::v/read <path>]` door's own read is added at firing time
     from the event vector, which native-payload never sees; see
     [[read-projections]]."
     [e]
     (reduce-kv (fn [m marker path]
                  (let [v (read-scalar-at e path)]
                    (if (some? v) (assoc m marker v) m)))
                {}
                sugar-paths)))

(def default-payload
  "The payload extractor a site takes when it names none: the host's own
  reading of the callback's first argument. In ClojureScript that is
  [[native-payload]] over a live event; on the JVM it is [[payload-map]],
  because the structural host has no native event to read and a
  structural test supplies the literal payload it wants materialized.

  A qualified host leaf names its own extractor instead — a foreign
  component's callback argument is not a DOM event, and guessing at one
  is how host objects end up inside intent vectors."
  #?(:cljs native-payload :clj payload-map))

;; ---------------------------------------------------------------------------
;; Event sites — candidate, commit, and the stable proxy
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §Callback roles and identity. A render builds a candidate
;; table it OWNS and nothing else can see; only the selected render's
;; candidate is committed. That is the atomic-selection law expressed
;; structurally rather than defended by a flag: an abandoned render is
;; abandoned by dropping its candidate, so it cannot retarget a live
;; proxy even in principle.
;;
;; The candidate is threaded explicitly rather than read from an ambient
;; slot. A thread-local or module-global "current render" is the shape
;; that makes concurrent and abandoned renders unsafe, and the emitter
;; walking the tree already holds the candidate.

(deftype EventOwner [id state])

(deftype RenderCandidate [owner sites])

(defn owner
  "Create the commit-owned event state for ONE mounted boundary
  occurrence. `id` names the view for diagnostics."
  [id]
  (->EventOwner id (volatile! {:lifecycle :new :sites nil :dispatch nil :fired #{}})))

(defn candidate
  "A fresh, ownership-free site table for ONE render of `owner`. It
  publishes nothing until [[commit!]]; dropping it is how an abandoned
  render publishes nothing."
  [owner]
  (->RenderCandidate owner (volatile! {})))

(defn- committed-proxy
  [^EventOwner owner site-key]
  (:proxy (get (:sites @(.-state owner)) site-key)))

(declare invoke!)

(defn- mint-proxy
  [owner site-key]
  ;; Variadic: a DOM `:on-*` invoker passes one native event, a foreign
  ;; invoker passes whatever its protocol says. The proxy closes over the
  ;; OWNER, the site key and ITSELF — never over a body — which is
  ;; precisely why a later commit changes what it does without changing
  ;; what it is, while a DIFFERENT incarnation of the same key cannot
  ;; borrow its doorway.
  ;;
  ;; The proxy IS its own incarnation token: minting is the only way to
  ;; make one, `site` hands the committed incarnation back across an
  ;; uninterrupted re-render, and `invoke!` proceeds only when the proxy
  ;; being called is the proxy the committed descriptor carries. No
  ;; second identity vocabulary, and nothing public to hold.
  (fn freehand-event-proxy [& args]
    (invoke! owner site-key freehand-event-proxy args)))

(defn site
  "RENDER time: record `value` as `site-key`'s intent in `candidate`, and
  return the site's STABLE proxy.

  Identity is owned by the site, not by the value: an unchanged site
  keeps the exact proxy it had across every re-render, so a foreign
  consumer — React reconciliation included — sees no churn. Two sites
  carrying EQUAL values get two distinct proxies, so their lifetimes,
  `:once` state and diagnostics stay independent.

  The proxy returned here is a CANDIDATE-local incarnation until this
  candidate is the one committed. An uninterrupted site is handed back
  the incarnation already committed for its key, which is exactly why
  identity survives re-render; a key with no committed incarnation — a
  first render, or a key a later render re-adds — is minted fresh, and
  the previous incarnation is retired for good.

  `payload-fn` is the site's payload extractor, [[default-payload]] when
  unnamed. Returns `nil` for an empty position, and for `v/render-fn` /
  `v/raw-fn` returns the authored function itself — those two roles are
  deliberately outside the committed-proxy scheme, because a render
  callback may run during an uncommitted foreign render and a raw
  function's identity is the caller's to own.

  `element` carries the CONTROLLED-INPUT door's element half — `{:tag
  :input :controlled? true :slot \"onInput\"}`, the facts only the walk
  has, because only the walk has seen every prop of the element. The
  SITE half — the callback's role and its listener options — is the plan
  classified right here, so the two halves meet at
  [[re-frame.freehand.controlled/door?]] once and no caller can supply a
  partial verdict. `nil` means no door, which is what every non-element
  site passes.

  The verdict rides the committed plan, so a re-commit changes it exactly
  as it changes the body: an element that stops being controlled stops
  taking the synchronous lane, and not one callback identity moves."
  ([candidate site-key value]
   (site candidate site-key value default-payload nil))
  ([candidate site-key value payload-fn]
   (site candidate site-key value payload-fn nil))
  ([^RenderCandidate candidate site-key value payload-fn element]
   (let [plan (event-plan value)]
     (case (:role plan)
       nil        nil
       :render-fn (:f plan)
       :raw-fn    (:f plan)
       (let [owner (.-owner candidate)
             sites (.-sites candidate)
             proxy (or (committed-proxy owner site-key)
                       (:proxy (get @sites site-key))
                       (mint-proxy owner site-key))
             door? (and (some? element)
                        (controlled/door? (assoc element
                                                 :role     (:role plan)
                                                 :capture? (:capture plan)
                                                 :passive? (:passive plan))))]
         (vswap! sites assoc site-key
                 (assoc plan :proxy proxy :payload payload-fn :door door?))
         proxy)))))

(defn commit!
  "COMMIT time: publish `candidate`'s exact site table to its owner,
  targeted at `dispatch` — a one-argument fn taking the materialized
  event vector, supplied by the frame-bound caller.

  This is the join point for the selected render's atomic bundle: the
  table becomes live in one write, every proxy keeps its identity, and
  `:once` state is retained only for sites this render still carries.
  Retargeting is exactly a re-commit with a different `dispatch`, so a
  frame change reaches every site without touching one callback
  identity.

  `door-dispatch` is the SYNCHRONOUS dispatcher a controlled-input site
  fires through — the same materialized event vector, delivered so that
  state has round-tripped before the native listener returns. It is a
  second committed target rather than a flag the dispatcher reads,
  because the two lanes are different scheduling contracts and a site
  belongs to exactly one of them: `re-frame.freehand.cell` commits both
  bound to the SAME frame, so a retarget moves them together and neither
  can outlive the other. The two-argument arity commits no door — the
  honest answer for a host that has no synchronous lane, and for a test
  that is proving ordinary dispatch."
  ([candidate dispatch] (commit! candidate dispatch nil))
  ([^RenderCandidate candidate dispatch door-dispatch]
   (let [^EventOwner owner (.-owner candidate)
         sites             @(.-sites candidate)]
     (vswap! (.-state owner)
             (fn [{:keys [fired]}]
               {:lifecycle     :connected
                :sites         sites
                :dispatch      dispatch
                :door-dispatch door-dispatch
                :fired         (into #{} (filter #(contains? sites %)) fired)}))
     owner)))

(defn retire!
  "Retire every proxy `owner` published. The proxies stay callable — a
  foreign listener may already hold one — but they are INERT: a retired
  proxy dispatches nothing and emits development evidence instead of
  firing into whatever owns the node now."
  [^EventOwner owner]
  (vswap! (.-state owner) assoc
          :lifecycle     :retired
          :sites         nil
          :dispatch      nil
          :door-dispatch nil
          :fired         #{})
  nil)

(defn lifecycle
  "`owner`'s lifecycle — `:new` before its first commit, `:connected`
  once a render has committed, `:retired` after [[retire!]]."
  [^EventOwner owner]
  (:lifecycle @(.-state owner)))

(defn ^:no-doc committed-sites
  "The COMMITTED site table — `{site-key plan}` — as an inspection seam
  for tools and tests, never a publication. It hands back the EXACT plans
  firing reads, including each site's proxy and its controlled-input door
  verdict, so an assertion pins what the runtime will do rather than a
  copy of it. Mirrors [[re-frame.freehand.cell/dependencies]]."
  [^EventOwner owner]
  (:sites @(.-state owner)))

;; ---------------------------------------------------------------------------
;; Firing
;; ---------------------------------------------------------------------------

#?(:cljs
   (defn- apply-mechanics!
     "Run the selected browser mechanics on the live event, before
     dispatch. Unguarded on purpose: `:prevent-default` at a site whose
     callback argument is not an event is an authoring mistake, and the
     host's own `preventDefault is not a function` names it precisely."
     [plan args]
     (let [e (first args)]
       (when (:prevent-default plan)  (.preventDefault ^js e))
       (when (:stop-propagation plan) (.stopPropagation ^js e))))
   :clj
   (defn- apply-mechanics!
     "The structural host fires no native event, so there are no browser
     mechanics to run. The options themselves still normalize and still
     ride the site plan, so the structural and browser hosts read one
     shape."
     [_plan _args]
     nil))

(defn- retired-evidence!
  [^EventOwner owner site-key state]
  (when interop/debug-enabled?
    (trace/emit!
      :warning :rf.warning/view-retired-callback
      {:view-id  (.-id owner)
       :site-id  site-key
       :state    state
       :reason   (str "a Freehand callback fired after its site incarnation was retired — "
                      "the view unmounted, the node was replaced, the render that published "
                      "it was never selected, or the site was removed and its key re-used by "
                      "a later render; the callback is inert and dispatches nothing")
       :recovery :warned-and-continued}))
  nil)

#?(:cljs
   (defn- read-projections
     "Add the GENERAL door's reads to `base` — for each `[::v/read <path>]`
     in `event`, the scalar that path names off the live event `e`, keyed by
     the door itself so [[materialize-event]] substitutes it exactly as it
     does a named member. The named roster is already in `base` from
     [[native-payload]]; a malformed door is left unread here and diagnosed
     at materialization, and a read that lands on nothing is simply absent,
     which the materializer reports as a missing payload like any other
     unmet projection.

     `event` is the vector ABOUT TO BE MATERIALIZED — a declarative site's
     committed vector, or the one a `v/event` body just returned. Both are
     scanned against the same live callback argument, which is what makes
     the two spellings one door."
     [base event e]
     (if (vector? event)
       (reduce (fn [m x]
                 (if (and (read-marker? x) (read-path? (read-marker-path x)))
                   (let [v (read-scalar-at e (read-marker-path x))]
                     (if (some? v) (assoc m x v) m))
                   m))
               base
               (rest event))
       base))
   :clj
   (defn- read-projections
     "The structural host supplies its payload map directly — a general
     door's scalar is a key the test wrote, exactly like a named member —
     so there is no live event to read and `base` is already complete."
     [base _event _e]
     base))

(defn- payload
  "The live payload for the event vector `event` about to be materialized —
  the site's own extractor over `args`, plus the general door's reads taken
  off the live callback argument.

  `event` is passed rather than read off the plan because the two roles hold
  their vector in different places: a declarative site's vector IS
  `(:event plan)`, while a `v/event` site's vector is what its body just
  RETURNED, and a plan for that role carries no `:event` at all. Scanning
  the plan for both meant a door in a returned vector was scanned for in
  `nil` and reported as a missing payload — the public contract says every
  path runs through one materializer, and this is the argument that makes it
  true."
  [plan event args]
  (when-some [pf (:payload plan)]
    (read-projections (apply pf args) event (first args))))

(defn- lane
  "The dispatcher this site fires through — the SYNCHRONOUS one when the
  site is inside the controlled-input door and a door dispatcher is
  committed, the ordinary batched one otherwise.

  The fallback is not a silent downgrade of a promise: a door verdict
  reaches a site only from a walk that also committed through
  `re-frame.freehand.cell`, which always publishes both lanes together.
  A host with no synchronous lane (the structural JVM host, a test
  committing ordinary dispatch) simply has no door to fall out of."
  [plan {:keys [dispatch door-dispatch]}]
  (if (and (:door plan) (some? door-dispatch)) door-dispatch dispatch))

(defn- fire!
  [plan state args]
  (let [dispatch (lane plan state)]
    (case (:role plan)
      (:event-vector :event-options)
      (dispatch (materialize-event (:event plan) (payload plan (:event plan) args)))

      :event
      (let [result (apply (:f plan) args)]
        ;; Exactly one event vector or nil. `nil` dispatches nothing;
        ;; anything that is not a vector is rejected by the materializer,
        ;; which is the one place that law lives. `nil` also needs no door
        ;; flush — no state moved, so nothing has to round-trip.
        ;;
        ;; The payload is read for THIS result: a body returning
        ;; `[:table/scrolled [::v/read [:target :scrollTop]]]` gets its door
        ;; read off the live callback argument, exactly as the same vector
        ;; written declaratively would.
        (when (some? result)
          (dispatch (materialize-event result (payload plan result args)))))

      (:handler :bare-fn)
      (apply (:f plan) args)))
  nil)

(defn- invoke!
  [^EventOwner owner site-key proxy args]
  ;; The site key alone does not authorize a dispatch: `proxy` must be the
  ;; exact incarnation the COMMITTED descriptor carries. Resolving by
  ;; `(owner, site-key)` alone would let a never-selected candidate's
  ;; proxy fire the body a different candidate committed, and would let a
  ;; retired proxy come back to life the moment its key was re-used.
  (let [{:keys [sites] :as state} @(.-state owner)
        plan (get sites site-key)]
    (if (and (some? plan) (identical? proxy (:proxy plan)))
      (when-not (and (:once plan) (contains? (:fired state) site-key))
        (when (:once plan)
          (vswap! (.-state owner) update :fired conj site-key))
        (apply-mechanics! plan args)
        (fire! plan state args))
      (retired-evidence! owner site-key (:lifecycle state))))
  nil)
