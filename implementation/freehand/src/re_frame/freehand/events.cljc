(ns re-frame.freehand.events
  "Freehand's event plane — **intent is data** (EP-0036 governing law 5;
  ruled by D006 and D008).

  One user action yields exactly ONE semantic event vector or `nil`. This
  namespace owns the three mechanisms that make that true, and nothing
  else:

    1. **The closed projection trio and the one pure materializer.**
       `::v/value`, `::v/checked` and `::v/key` are the only reserved
       scalar markers. At firing time the site's host adapter reads the
       live payload, and [[materialize-event]] replaces every matching
       TOP-LEVEL argument marker before the resulting plain vector goes
       to ordinary re-frame dispatch. General `rf/dispatch` therefore
       gains no payload arity, in either direction: a projection keyword
       inside a domain event is never secretly interpreted, and no
       dispatcher can deliberately supply a payload.

    2. **The closed event grammar at an event position.** A vector, an
       options map carrying `:event` plus the closed listener options, a
       key-condition map selecting an intent by `KeyboardEvent.key` on a
       key listener, a roster callback, a bare function, or `nil`.
       [[event-plan]] is TOTAL over that roster and raises
       `:rf.error/view-bad-event` for anything else.

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
;; The closed projection trio
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §Event intent and the payload materializer. The marker and
;; the payload key are deliberately THE SAME KEYWORD: a site asks for
;; `::v/value` and the adapter supplies `::v/value`, so "did the callback
;; offer what this event asked for?" is one `contains?` and there is no
;; second vocabulary to keep in step.

(def projections
  "The CLOSED scalar projection roster — value, checked state, and key.

  These are the only reserved markers a declarative event vector may
  carry, AND the exact keys of the payload map a firing site supplies.
  Adding a fourth projection is a grammar decision, not an
  implementation detail; anything richer than a shallow scalar read is
  `v/event`'s job."
  #{:re-frame.freehand/value
    :re-frame.freehand/checked
    :re-frame.freehand/key})

;; ---------------------------------------------------------------------------
;; The one pure materializer
;; ---------------------------------------------------------------------------

(defn- payload-value
  [marker event payload]
  (if (contains? payload marker)
    (get payload marker)
    (error/throw-error!
      :rf.error/view-missing-payload
      'v/materialize-event
      (str "This event site asks for " marker " but the callback that fired it supplies "
           "no such payload" (if (seq payload)
                               (str " (it supplies " (pr-str (vec (sort (keys payload)))) ")")
                               "")
           ". Nothing is dispatched — a malformed event vector is worse than none. Ask for "
           "a projection the site's own callback carries, or convert the argument "
           "explicitly with v/event.")
      {:recovery :ask-for-a-projection-the-callback-supplies
       :extra    {:projection marker
                  :available  (vec (sort (keys payload)))
                  :event-id   (nth event 0 nil)
                  :event      (error/diag-value-summary event)}})))

(defn materialize-event
  "The ONE pure event materializer: replace the reserved projection
  markers in `event` with the live scalars in `payload`, and return a
  plain event vector ready for ordinary re-frame dispatch.

      (materialize-event [:account/email-edited ::v/value]
                         {::v/value \"mike@example.com\"})
      ;; => [:account/email-edited \"mike@example.com\"]

  Its semantics are deliberately small, and every path — a literal
  vector, a forwarded `(conj on-change ::v/value)`, an options map's
  `:event`, a `v/event` body's result, interpreted, compiled, production
  and test — runs through exactly this function:

  - **position zero may not be a marker.** An event id is a name, not a
    projection;
  - **only TOP-LEVEL argument positions are replaced.** A marker nested
    inside a map, a vector or any other value is ordinary application
    data and is left alone;
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
    (when (vector? id)
      (bad-event!
        'v/materialize-event
        (str "A Freehand event site yields exactly one event vector, and this one is a vector "
             "of event vectors. One user action is one semantic event: give the intent a name "
             "and let its re-frame handler return the effects the step needs.")
        :yield-one-event-vector-or-nil
        {:event (error/diag-value-summary event)})))
  (if (some projections event)
    (into [(nth event 0)]
          (map (fn [x] (if (contains? projections x) (payload-value x event payload) x)))
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

  The exact-key condition map for `:on-key-down` / `:on-key-up` is a
  SEPARATE closed form with its own slice; it is not a variant of this
  one, and mixing the two in one map is an error."
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

(declare map-plan)

(defn event-plan
  "Classify the value at an event position into its closed **site plan**,
  or `nil` when the position is empty. TOTAL over the roster:

    a vector        → `:event-vector`   — declarative intent
    an options map  → `:event-options`  — intent plus listener options
    a key map       → `:key-map`        — exact-`KeyboardEvent.key` branches,
                                          legal only on a key listener
    a roster callback → its role        — `:event` / `:handler` /
                                          `:render-fn` / `:raw-fn`
    a bare function → `:bare-fn`        — legal at a native `:on-*` site,
                                          where the site's own committed
                                          adapter owns its lifetime
    `nil`           → `nil`             — an empty position

  A MAP is the one shape two closed forms share, so it is classified by
  [[map-plan]] — options first (`:event`), then the string-keyed
  key-condition form — and `:rf.error/view-bad-event` for anything else,
  naming the roster. The plan is a plain map, so both emitters and the
  structural host read one shape."
  [v]
  (cond
    (nil? v)      nil
    (vector? v)   {:role :event-vector :event v}
    (callback? v) {:role (callback-role v) :f (callback-fn v)}
    (map? v)      (map-plan v)
    (fn? v)       {:role :bare-fn :f v}
    :else
    (bad-event!
      'v/event-site
      (str "An event position takes an event vector, an options map carrying :event, a "
           "key-condition map (on a key listener), one of the declared callback forms "
           "(v/event, v/handler, v/render-fn, v/raw-fn), a plain function, or nil; got a "
           (value-tag v) ".")
      :use-one-of-the-declared-event-forms
      {:legal-forms [:event-vector :event-options :key-map :v-event :v-handler :v-render-fn
                     :v-raw-fn :bare-fn :nil]
       :value       (error/diag-value-summary v)})))

;; ---------------------------------------------------------------------------
;; The closed key-condition event map
;; ---------------------------------------------------------------------------
;;
;; Spec 004 §Event intent and the payload materializer, ruled by D007. A
;; SEPARATE closed form from the options map, legal only on `:on-key-down` /
;; `:on-key-up`. Its keys are exact `KeyboardEvent.key` strings and each value
;; is an existing DISPATCHING event form — a vector, an options map carrying
;; `:event`, `v/event`, or `nil`. Selection is one level and by exact equality:
;; a missing key is a no-op, an in-flight IME composition or a chord modifier
;; matches nothing, and each selected branch runs its OWN pre-dispatch mechanics
;; before its intent dispatches. Everything richer — modifier chords, ordering,
;; wildcards, platform aliases, state predicates — stays `v/event`'s job.
;;
;; The whole form ships SUBJECT TO its delete-before-release pilot gate (D007):
;; if the F5 component/library pilots (rf2-drpa3.44) show no repeated real use,
;; it is DELETED before release rather than kept for symmetry, at the F6e donor
;; deletion (rf2-drpa3.57). This bead (rf2-drpa3.23) carries that obligation.

(def key-slots
  "The two React event slots a key-condition map is legal on — the slots the
  emitter writes for `:on-key-down` and `:on-key-up`."
  #{"onKeyDown" "onKeyUp"})

(defn- branch-plan
  "Classify one key branch's value into its dispatching site plan, or `nil`. A
  branch names exactly ONE intent, so only the dispatching roster — an event
  vector, an options map carrying `:event`, or `v/event` — and `nil` are legal;
  a `v/handler`, a bare function, a render/raw callback, and a NESTED key map
  are refused, which is precisely what keeps the form one level deep."
  [k v]
  (let [plan (event-plan v)]
    (if (or (nil? plan)
            (contains? #{:event-vector :event-options :event} (:role plan)))
      plan
      (bad-event!
        'v/event-site
        (str "The key-condition branch " (pr-str k) " is a " (name (:role plan))
             "; a key branch names ONE intent — an event vector, an options map "
             "carrying :event, v/event, or nil. A callback that is not itself an "
             "intent, and a nested key map, are outside the one-level exact-key form.")
        :name-one-intent-per-key-branch
        {:key k :role (:role plan)}))))

(defn- key-map-plan
  "Normalize a validated key-condition map into its `:key-map` site plan — the
  exact-key branches, each already a classified dispatching plan."
  [m]
  {:role     :key-map
   :branches (into {} (map (fn [[k v]] [k (branch-plan k v)])) m)})

(defn- map-plan
  "Classify a MAP at an event position — the one shape two closed forms share.
  An OPTIONS map carries the keyword listener roster (`:event` …); a
  KEY-CONDITION map carries exact-`KeyboardEvent.key` string branches. They are
  SEPARATE forms: a map that mixes the two, or an empty map that is neither, is
  a typed authoring error rather than a silently degenerate site. Options are
  decided first, so a `:event` map is never read as a key map."
  [m]
  (cond
    (empty? m)
    (bad-event!
      'v/event-site
      (str "An empty map is neither an options map — which states its intent under "
           ":event — nor a key-condition map, which names at least one exact "
           "KeyboardEvent.key branch. Write {:event [:my-event …]} or {\"Enter\" [:accept] …}.")
      :state-an-event-or-a-key-branch
      {})

    (not-any? string? (keys m))
    (options-plan m)

    (every? string? (keys m))
    (key-map-plan m)

    :else
    (bad-event!
      'v/event-site
      (str "A key-condition map's keys are exact KeyboardEvent.key strings, but this map "
           "also carries the listener-option key(s) "
           (pr-str (vec (sort (remove string? (keys m)))))
           ". The exact-key form and the options map are SEPARATE closed forms — state "
           "per-key intents as {\"Enter\" [:accept] …} and whole-listener options in an "
           "options map, never both in one map.")
      :do-not-mix-key-branches-and-listener-options
      {:string-keys (vec (sort (filter string? (keys m))))
       :option-keys (vec (sort (remove string? (keys m))))})))

(defn select-branch
  "Pick the branch a key-condition `plan` fires for the selection `facts`
  `{:key :composing? :chord?}`, or `nil` for no branch. A chord modifier
  (Ctrl/Alt/Meta) or an in-flight IME composition matches nothing; otherwise the
  branch is the one whose key EXACTLY equals `:key`, and an absent key — like an
  explicit `nil` branch — dispatches nothing. One level, exact equality, no
  wildcard, ordering or modifier syntax: everything else is `v/event`'s job."
  [plan {:keys [key composing? chord?]}]
  (when-not (or composing? chord?)
    (get (:branches plan) key)))

(defn key-facts
  "Read the branch-selection facts off a live key event (browser) or the
  structural payload map (JVM): the key name, whether an IME composition is in
  flight, and whether a CHORD modifier — Ctrl, Alt or Meta — is held. Shift is
  deliberately NOT a chord: it is already reflected in `KeyboardEvent.key`
  (\"?\" is not \"/\"), so excluding it would make every shifted key
  unmatchable. This is the one host-shaped seam of the key-condition form,
  exactly parallel to [[native-payload]]."
  [x]
  #?(:cljs {:key        (unchecked-get x "key")
            :composing? (boolean (unchecked-get x "isComposing"))
            :chord?     (boolean (or (unchecked-get x "ctrlKey")
                                     (unchecked-get x "altKey")
                                     (unchecked-get x "metaKey")))}
     :clj  {:key        (:re-frame.freehand/key x)
            :composing? (boolean (:composing? x))
            :chord?     (boolean (:chord? x))}))

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
   (defn native-payload
     "Read the closed scalar trio off a live native / React synthetic
     event. A key is present only when the event actually carries it, so
     asking a click for `::v/key` is a typed error rather than a silent
     `nil` reaching a handler.

     No DOM node, synthetic event or other host object leaves this
     function: three normalized scalars go in the payload map and
     nothing else."
     [e]
     (let [target  (unchecked-get e "target")
           value   (when (some? target) (unchecked-get target "value"))
           checked (when (some? target) (unchecked-get target "checked"))
           k       (unchecked-get e "key")]
       (cond-> {}
         (some? value)   (assoc :re-frame.freehand/value value)
         (some? checked) (assoc :re-frame.freehand/checked checked)
         (some? k)       (assoc :re-frame.freehand/key k)))))

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
       (do
         (when (and (= :key-map (:role plan))
                    (some? element)
                    (not (contains? key-slots (:slot element))))
           (bad-event!
             'v/event-site
             (str "A key-condition map selects an intent by KeyboardEvent.key, so it is legal "
                  "only on :on-key-down and :on-key-up — this one is on a " (pr-str (:slot element))
                  " site. Put an ordinary click or input intent in a vector or an options map.")
             :put-key-condition-maps-on-key-listeners
             {:slot (:slot element)}))
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
           proxy))))))

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

(defn- payload
  [plan args]
  (when-some [pf (:payload plan)]
    (apply pf args)))

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
      :key-map
      ;; Select one branch by exact key off the live event (a chord modifier or
      ;; an in-flight composition matches nothing), then run THAT branch's own
      ;; pre-dispatch mechanics and fire it. A missing key dispatches nothing.
      ;; The branch inherits the SITE's payload extractor, so a branch intent may
      ;; carry `::v/key` (or any projection) like any other event.
      (when-some [branch (select-branch plan (key-facts (first args)))]
        (apply-mechanics! branch args)
        (fire! (assoc branch :payload (:payload plan)) state args))

      (:event-vector :event-options)
      (dispatch (materialize-event (:event plan) (payload plan args)))

      :event
      (let [result (apply (:f plan) args)]
        ;; Exactly one event vector or nil. `nil` dispatches nothing;
        ;; anything that is not a vector is rejected by the materializer,
        ;; which is the one place that law lives. `nil` also needs no door
        ;; flush — no state moved, so nothing has to round-trip.
        (when (some? result)
          (dispatch (materialize-event result (payload plan args)))))

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
