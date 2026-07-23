(ns re-frame.freehand.errors
  "The error-boundary LAW — resettable containment, the once-per-generation
  safe intent, and the private frame error egress (EP-0036 slice F4e; ruled
  by D019).

  A render failure is inevitable: a nil assumption, malformed Hiccup, a
  foreign component throwing, stale data violating a contract. The atomic
  shell ([[re-frame.freehand.cell]]) already guarantees that a candidate
  which throws OWNS NOTHING and publishes nothing — no dependencies, no
  event sites, no evidence. What it does not decide is what the user sees
  next, or how the failure reaches production telemetry. This namespace is
  that decision, and it is COMMON: the same value shapes and the same laws
  on the JVM and in ClojureScript, so a structural test and a browser
  boundary contain the same failure the same way.

  ## Two deliberately different channels

  D019 settles that a caught render failure has two audiences that must not
  share a representation:

  - the **safe public summary** — bounded, serialisable failure data an
    application may inspect, put in app-db, or dispatch. It carries a stable
    diagnostic id, the failing view id, the phase, a fingerprint, and D020's
    evidence vocabulary — and NOTHING host-shaped: no exception object, no
    props, no app-db, no event payloads. It is what an author's `:on-error`
    event receives, appended exactly once per failure generation after the
    fallback commits.
  - the **private frame error egress** — the SAME safe summary PLUS the
    opaque exception (available only during the promotion call) and a capped
    host/component stack, promoted at most once per failure generation onto
    re-frame's existing always-on error axis
    ([[re-frame.error-emit/dispatch-error-record!]]) and the frame-owned
    observability sink. An off-box shipper (Sentry, Datadog) reads the real
    host detail there; it can never return a host object into the view data
    plane, and it carries NO automatic app-db or event-history capture
    (EP-0036 / D019 reject option E — that is opt-in application telemetry,
    never the boundary default).

  ## The state machine

  One [[boundary]] per mounted error-boundary OCCURRENCE, `deftype` over an
  atom, exactly like a [[re-frame.freehand.cell/cell]]. It is a small
  three-move machine:

    - `:clear`  — no captured failure; the child renders.
    - [[capture!]] a fresh failure FROM `:clear` advances the generation,
      builds the safe summary, and enters `:failed`. A repeated capture WHILE
      `:failed` (React re-invoking, HMR re-rendering the same failure) is a
      no-op — it publishes nothing new, which is what makes reporting
      once-per-generation rather than once-per-render.
    - [[reset-to!]] with a `:reset-key` that changed by `rf=` returns to
      `:clear` and re-mounts the child. There is no boundary ref and no
      imperative `reset!` handle — a retry button dispatches an ordinary
      event that moves the caller-owned reset value.

  [[take-report!]] is the once-per-generation gate the host calls AFTER the
  fallback commits: it yields the captured failure exactly once per fresh
  capture and nil forever after, so the safe intent and the egress record
  each fire exactly once for a captured generation, no matter how many times
  a StrictMode / HMR host re-renders the fallback.

  This namespace is host-neutral and drives nothing itself: the React glue
  ([[re-frame.freehand.error-react]]) wires a class boundary's
  `getDerivedStateFromError` / `componentDidCatch` onto it, and the JVM
  structural host wraps a child render in [[contain]]. Both drive exactly
  this law.

  Normative owner: [`spec/004-Views.md`](../../../../../spec/004-Views.md)
  §Error boundaries and error egress; the egress category and its channel are
  [`spec/009-Instrumentation.md`](../../../../../spec/009-Instrumentation.md)
  §Error event catalogue."
  (:require [re-frame.error :as error]
            [re-frame.error-emit :as error-emit]
            [re-frame.freehand.eq :as eq]
            [re-frame.router :as router]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The `v/error-boundary` call grammar
;; ---------------------------------------------------------------------------

(def boundary-view-id
  "The framework boundary's own declared id — the value carried as
  `:boundary-view-id` on every summary this boundary reports."
  :re-frame.freehand/error-boundary)

(def opt-keys
  "The CLOSED option roster of `v/error-boundary`, identical to the
  compiled tier's analyzer roster (`re-frame.freehand.compiler.analyze`):
  `:fallback` (required — what renders when a throw is caught below the
  boundary), `:reset-key` (the caller-owned retry value), and `:on-error`
  (the optional safe-intent event prefix)."
  #{:fallback :reset-key :on-error})

(defn read-opts
  "Split a normalized `v/error-boundary` props map into `{:fallback
  :reset-key :on-error :children}` and VALIDATE it against the closed
  grammar, throwing `:rf.error/error-boundary-bad-args` on a violation — the
  interpreted-tier counterpart of the compiled analyzer's
  `:rf.ui.compile/bad-error-boundary`.

  The rules mirror the analyzer: the option roster is CLOSED, `:fallback` is
  REQUIRED (a boundary with no fallback would render nothing on failure —
  worse than a loud reject), `:on-error`, when present, is a literal
  event vector `[:domain/event …]` so the safe summary can be appended and
  dispatched, and the boundary guards EXACTLY ONE child.

  The child count is checked HERE and not left to the descriptor's
  `:children-policy :required`, which admits one or MORE. The boundary
  guards one region, so a second declared child has nowhere to go: without
  this rule the interpreted mount kept `(first children)` and discarded the
  rest — a subtree missing from the page with nothing anywhere to say so —
  while the compiled analyzer refused the identical declaration. Two modes
  disagreeing about one declaration is the failure this rule closes."
  [props]
  (let [{:keys [reset-key on-error]} props
        fallback (:fallback props)
        children (:children props)
        unknown  (vec (sort (remove (conj opt-keys :children) (keys props))))]
    (when (seq unknown)
      (error/throw-error!
        :rf.error/error-boundary-bad-args
        'v/error-boundary
        (str "v/error-boundary options are the closed roster " (pr-str (vec (sort opt-keys)))
             "; the call declares " (pr-str unknown) ". An option key is never discarded — "
             "a one-character typo would otherwise produce a boundary that quietly means "
             "something other than it says.")
        {:recovery :use-the-closed-error-boundary-options
         :extra    {:unknown-options unknown}}))
    (when-not (contains? props :fallback)
      (error/throw-error!
        :rf.error/error-boundary-bad-args
        'v/error-boundary
        (str "v/error-boundary needs a :fallback — the view or markup that renders "
             "when a throw is caught below the boundary. A boundary with no fallback "
             "would show nothing at all on failure, which is the one outcome worse than "
             "the failure itself.")
        {:recovery :supply-a-fallback}))
    (when (and (some? on-error)
               (not (and (vector? on-error) (keyword? (first on-error)))))
      (error/throw-error!
        :rf.error/error-boundary-bad-args
        'v/error-boundary
        (str ":on-error is one event prefix — a vector whose first element is the event "
             "id keyword, e.g. [:telemetry/ui-render-failed]. The safe failure summary is "
             "appended to it and dispatched exactly once after the fallback commits.")
        {:recovery :supply-an-event-prefix-vector
         :extra    {:on-error (error/diag-value-summary on-error)}}))
    (when-not (= 1 (count children))
      (error/throw-error!
        :rf.error/error-boundary-bad-args
        'v/error-boundary
        (str "v/error-boundary guards exactly ONE child; the call declares "
             (count children) ". Wrap the siblings in a fragment — "
             "[v/error-boundary {…} [:<> child-1 child-2]] — so the boundary "
             "guards one region and nothing is dropped. Keeping the first child "
             "and discarding the rest would leave a subtree off the page with "
             "nothing on screen or in the console to say so.")
        {:recovery :guard-exactly-one-child
         :extra    {:children-count (count children)}}))
    {:fallback  fallback
     :reset-key reset-key
     :on-error  on-error
     :children  children}))

;; ---------------------------------------------------------------------------
;; The stable vocabulary
;; ---------------------------------------------------------------------------

(def render-failed-diagnostic-id
  "The STABLE diagnostic id every render-failure summary carries — the
  public concept, not an analyzer internal (D019 §Safe public summary). It
  is deliberately distinct from [[egress-category]]: the diagnostic id names
  WHAT failed for an application reading the summary, while the always-on
  category names the OBSERVABILITY axis the private record rides."
  :re-frame.freehand/render-failed)

(def egress-category
  "The always-on `:rf.error/*` category the private frame egress record
  rides (Spec 009 §Error event catalogue — channel `always-on`). Promoted so
  an off-box shipper sees a contained render failure under CLJS `:advanced` +
  `goog.DEBUG=false`, where the dev trace surface is elided."
  :rf.error/view-render-failed)

(def phases
  "The closed, finite phase vocabulary a render failure is attributed to
  (D019 §Safe public summary). A FINITE set of stable values, never an
  analyzer-internal string: `:render` (a Freehand child body threw),
  `:normalize` (Hiccup normalization or common prop/event validation threw),
  and `:foreign-render` (a descendant foreign component threw where a host
  boundary applies)."
  #{:render :normalize :foreign-render})

(def safe-summary-keys
  "The CLOSED key set of a [[safe-summary]] — the bounded public envelope.
  Asserted exactly by the conformance suite: no key here is host-shaped, and
  the raw exception, props, app-db, and event payloads are absent BY
  CONSTRUCTION (D019 §Safe public summary)."
  #{:diagnostic-id :view-id :boundary-view-id :phase :frame-id :fingerprint :evidence})

(def egress-record-keys
  "The CLOSED top-level key set of an [[egress-record]] — the private frame
  egress record. Asserted exactly by the conformance suite: it carries the
  opaque `:exception` and a capped `:component-stack` an off-box shipper
  needs, and it carries NO `:app-db`, `:db`, `:event`, or `:event-history`
  key — the automatic-capture that EP-0036 / D019 reject (option E)."
  #{:error :frame :time :summary :exception :component-stack :recovery})

;; ---------------------------------------------------------------------------
;; Which declared view threw — the attribution relay
;; ---------------------------------------------------------------------------

(def unknown-view-id
  "The `:view-id` a summary carries when no occurrence observed which
  declared view threw — a foreign component's failure, or a host that
  drives a boundary without the occurrence seam. Truthful ignorance, and
  deliberately NOT the boundary's own id: a record naming the CATCHER as
  the thrower reads like an identification and sends the reader to the
  wrong file."
  :re-frame.freehand/unknown-view)

(def ^:private failing-views
  "Which declared view threw WHICH failure — the attribution relay, keyed by
  the THROWN VALUE'S IDENTITY and held WEAKLY.

  The thrown value is the only thing thrower and catcher demonstrably share:
  the occurrence seam has it in hand as it rethrows, and the boundary has
  the same object at the catch (React hands it to `componentDidCatch`
  verbatim). Keying on it is what makes attribution FAILURE-LOCAL — one
  note per throw, readable only by the catch that caught THAT throw.

  A single slot cannot express that. Two failures are routinely in flight at
  once: React finishes rendering every failed subtree of a commit before it
  runs any `componentDidCatch`, and the structural host renders on whatever
  thread asked it to. With one slot the first note wins and the second
  thrower is lost — one report names a view that did not fail this failure
  while the other goes out as [[unknown-view-id]] for a thrower that was
  plainly observed — and a note nothing ever collected waits to be read by
  the next unrelated boundary.

  Weak keys make the lifetime the throw's own: a note lives exactly as long
  as something still holds the exception it describes, so nothing has to be
  cleared and nothing accumulates."
  #?(:clj  (java.util.Collections/synchronizedMap (java.util.WeakHashMap.))
     :cljs (js/WeakMap.)))

(defn- attributable?
  "Whether `thrown` can carry a note. The JVM catch clause yields a
  Throwable; ClojureScript's `:default` catches whatever was thrown, and a
  relay key must be an object — `(Object x)` answers `x` itself exactly when
  `x` already is one, which `instance?` cannot say across realms. A thrown
  primitive is unattributable, and the report says so rather than guessing."
  [thrown]
  #?(:clj  (some? thrown)
     :cljs (and (some? thrown) (identical? thrown (js/Object thrown)))))

(defn note-failing-view!
  "Record `view-id` as the declared view whose render threw `thrown` —
  called by the OCCURRENCE seam of each host (the React function component
  the interpreted emitter builds, the structural walk's mount) as that throw
  passes through it, and by nothing else.

  The FIRST writer for a given throw wins, because the innermost occurrence
  is the one that threw: the throw unwinds outward through every enclosing
  occurrence, and each of those is a bystander that would otherwise
  overwrite the culprit with itself. First-writer-wins is scoped to the ONE
  throw — a concurrent or interleaved failure is a different key and neither
  claims nor clears the other's note."
  [thrown view-id]
  (when (attributable? thrown)
    #?(:clj  (.putIfAbsent ^java.util.Map failing-views thrown view-id)
       :cljs (when-not (.has ^js failing-views thrown)
               (.set ^js failing-views thrown view-id))))
  nil)

(defn- noted-failing-view
  "The view noted for `thrown`, or nil when no occurrence noted one. Reading
  is not consuming: the note belongs to the throw, so a host that catches
  the same throw twice (StrictMode, an HMR re-render) reads the same
  answer."
  [thrown]
  (when (attributable? thrown)
    #?(:clj  (.get ^java.util.Map failing-views thrown)
       :cljs (.get ^js failing-views thrown))))

(defn attributed-failure
  "Answer `caught` — a host's raw material for one failure — carrying the
  identity of the declared view that actually threw, resolved from the
  occurrence seam's note on `caught`'s own `:exception`
  ([[note-failing-view!]]).

  This is the whole value of a failure report. A record that names the
  boundary rather than the failing descendant sends a reader to the wrong
  file, and because the [[fingerprint]] is derived from the view id and the
  phase, it also collapses every failure under one boundary onto a SINGLE
  correlation token — two unrelated bugs indistinguishable because they
  share a catcher. A record that named some OTHER failure's thrower is worse
  again: it is a confident identification of an innocent view.

  When nothing was noted for this throw the record says so —
  [[unknown-view-id]], evidence marked incomplete, and a `:loss` naming why
  — rather than substituting an identity it does not have."
  [{:keys [exception] :as caught}]
  (if-let [failing (noted-failing-view exception)]
    (assoc caught :view-id failing)
    (assoc caught
           :view-id   unknown-view-id
           :complete? false
           :loss      {:reason :failing-view-unobserved})))

;; ---------------------------------------------------------------------------
;; The safe public summary
;; ---------------------------------------------------------------------------

(defn fingerprint
  "A STABLE, host-neutral fingerprint for a render failure — a bounded
  correlation token, never a promise of cross-session replay identity (D019
  §Safe public summary). Derived from the failing view id and the phase, so
  the SAME failure fingerprints identically on repeated captures and across
  the JVM and ClojureScript; two different failing views or phases
  fingerprint apart. It carries no value and no host object — only the two
  stable coordinates of WHERE the failure is."
  [view-id phase]
  (str "fh-render-failed:" view-id "|" (name phase)))

(defn- evidence
  "The bounded occurrence evidence, in D020's common vocabulary
  (scope / basis / completeness / loss). `complete?` is the build's honest
  statement — development retains full evidence, a production build may cap
  or eliminate it — and `:loss` is present exactly when evidence is
  incomplete, naming WHY. `basis` is `:observation`: the boundary reports
  what it observed at the caught failure, never a reconstruction."
  [{:keys [committed-generation complete? loss]}]
  (cond-> {:scope     {:committed-generation committed-generation}
           :basis     :observation
           :complete? (boolean complete?)}
    (not complete?) (assoc :loss (or loss {:reason :production-policy}))))

(defn safe-summary
  "Build the SAFE PUBLIC SUMMARY for a caught render failure — the bounded,
  serialisable envelope an application receives. Its key set is exactly
  [[safe-summary-keys]]: a stable [[render-failed-diagnostic-id]], the
  failing declared `:view-id`, the boundary's own `:boundary-view-id`, the
  finite `:phase`, the resolved `:frame-id`, a stable [[fingerprint]], and
  D020 `:evidence`.

  It NEVER carries the raw exception, `ex-data`, props, app-db, or event
  payloads — every one of those is a data-classification hazard, and the
  envelope is what may reach an event vector and a serializable trace. The
  opaque exception belongs on the private egress path alone."
  [{:keys [view-id boundary-view-id phase frame-id] :as failure}]
  {:diagnostic-id render-failed-diagnostic-id
   :view-id       view-id
   :boundary-view-id boundary-view-id
   :phase         phase
   :frame-id      frame-id
   :fingerprint   (fingerprint view-id phase)
   :evidence      (evidence failure)})

(defn egress-record
  "Build the PRIVATE FRAME EGRESS record for a captured failure — the union
  record promoted onto the always-on error axis via
  [[re-frame.error-emit/dispatch-error-record!]]. Its top-level key set is
  exactly [[egress-record-keys]]:

    {:error           :rf.error/view-render-failed   ; the always-on category
     :frame           <frame-id>                     ; the owning frame, or nil
     :time            <millis>                        ; the emit instant
     :summary         <the safe public summary>
     :exception       <the opaque host exception>    ; off-box shippers need it
     :component-stack <string or nil>                ; capped host stack
     :recovery        :contained}                     ; the boundary contained it

  The record carries the host `:exception` and `:component-stack` an off-box
  shipper genuinely needs, and it carries NOTHING that would leak the
  application data plane: no app-db slice, no dispatched-event history. Loss
  of those is the DELIBERATE default (D019 rejects option E) — an application
  that wants a redacted snapshot obtains it through its own `:on-error`
  handler and an allow-list it owns."
  [{:keys [frame-id exception component-stack] :as failure} time]
  {:error           egress-category
   :frame           frame-id
   :time            time
   :summary         (safe-summary failure)
   :exception       exception
   :component-stack component-stack
   :recovery        :contained})

;; ---------------------------------------------------------------------------
;; The boundary — one per mounted occurrence
;; ---------------------------------------------------------------------------

(deftype Boundary [state])

(defn- st [^Boundary b] (.-state b))

(defn boundary
  "Mint the state for ONE mounted error-boundary occurrence. `view-id` is
  the boundary's own declared id (for diagnostics and the summary);
  `reset-key` is the caller-owned reset value the boundary was first mounted
  with — a later [[reset-to!]] whose value differs by `rf=` clears a captured
  failure and re-mounts the child.

  A fresh boundary is `:clear` at generation 0 and has captured nothing."
  [view-id reset-key]
  (->Boundary
    (atom {:view-id    view-id
           :reset-key  reset-key
           :status     :clear
           :generation 0
           :failure    nil
           :fired?     false})))

(defn boundary?
  "True when `x` is a [[boundary]]."
  [x]
  (instance? Boundary x))

(defn view-id    "The boundary's own declared id."                 [^Boundary b] (:view-id @(st b)))
(defn reset-key  "The caller-owned reset value the boundary holds." [^Boundary b] (:reset-key @(st b)))
(defn status     "`:clear` or `:failed`."                          [^Boundary b] (:status @(st b)))
(defn generation "The failure generation — incremented per fresh capture." [^Boundary b] (:generation @(st b)))
(defn failed?    "True when the boundary is showing its fallback."  [^Boundary b] (= :failed (:status @(st b))))

(defn failure
  "The captured failure record — the safe summary plus the opaque exception
  and component stack retained for the private egress — or nil while
  `:clear`. INTERNAL to the boundary and its host driver; an application sees
  only the [[safe-summary]] the host hands its `:on-error` event."
  [^Boundary b]
  (:failure @(st b)))

;; ---------------------------------------------------------------------------
;; Capture — fresh failures advance the generation; repeats publish nothing
;; ---------------------------------------------------------------------------

(defn capture!
  "Record a caught render failure on `b` and return the boundary's captured
  failure record. `caught` is the host's raw material for one failure:

    {:exception       <the thrown host value>
     :phase           <one of [[phases]]>
     :view-id         <the failing declared view id>
     :frame-id        <the resolved frame id, or nil>
     :component-stack <a capped host stack string, or nil>
     :complete?       <whether evidence is full — the build's honest word>
     :loss            <why evidence is incomplete, when it is>}

  A capture FROM `:clear` is a FRESH failure: it advances the generation,
  builds the summary, retains the opaque exception and stack for the egress,
  and enters `:failed`. A capture WHILE already `:failed` is a NO-OP that
  returns the existing failure unchanged — a StrictMode double-invoke, an HMR
  re-render, or a repeated parent render of the same failure publishes
  NOTHING new, which is precisely what makes the safe intent and the egress
  fire once per GENERATION rather than once per render.

  The failure record's `:summary` is exactly [[safe-summary]]; the record
  additionally retains `:exception` and `:component-stack` for
  [[egress-record]] — the two live on the boundary together but leave it by
  two different doors."
  [^Boundary b {:keys [exception phase view-id frame-id component-stack complete? loss]
                :or   {complete? true}}]
  (let [s @(st b)]
    (if (= :failed (:status s))
      (:failure s)
      (let [gen     (inc (:generation s))
            failure {:view-id          view-id
                     :boundary-view-id (:view-id s)
                     :phase            phase
                     :frame-id         frame-id
                     :exception        exception
                     :component-stack  component-stack
                     :committed-generation gen
                     :complete?        complete?
                     :loss             loss}
            summary (safe-summary failure)
            record  (assoc failure :summary summary)]
        (swap! (st b) assoc
               :status     :failed
               :generation gen
               :failure    record
               :fired?     false)
        record))))

;; ---------------------------------------------------------------------------
;; Reset — a changed reset-key clears the failure and re-mounts the child
;; ---------------------------------------------------------------------------

(defn reset-to!
  "Reconcile `b` against the caller's current `new-reset-key`. When it
  differs from the boundary's stored value by `rf=`, the boundary CLEARS the
  captured failure, returns to `:clear`, and adopts the new value — so the
  next render re-mounts the child and retries it. Returns true when a reset
  happened, false when the key was unchanged.

  A reset does NOT advance the generation; the NEXT fresh [[capture!]] does.
  There is no boundary ref and no imperative reset handle: the reset value is
  caller-owned, moved by an ordinary re-frame event, so recovery is data, not
  a side channel (D019 §Boundary semantics)."
  [^Boundary b new-reset-key]
  (if (eq/rf= (:reset-key @(st b)) new-reset-key)
    ;; Unchanged key: a `:failed` boundary stays on its fallback, a `:clear`
    ;; one stays clear. Nothing to reconcile.
    false
    (do (swap! (st b) assoc
               :reset-key new-reset-key
               :status    :clear
               :failure   nil
               :fired?    false)
        true)))

;; ---------------------------------------------------------------------------
;; The once-per-generation gate
;; ---------------------------------------------------------------------------

(defn take-report!
  "The ONCE-PER-GENERATION gate the host calls AFTER the fallback commits.
  Returns the captured failure record exactly once per fresh [[capture!]] and
  nil on every subsequent call for that same generation — so the safe intent
  and the private egress each fire exactly once for a captured failure, no
  matter how many times a StrictMode / HMR host re-renders the fallback.

  A fresh capture clears the fired flag, so a NEW failure generation (after a
  [[reset-to!]] and another throw) reports again. Nil while `:clear`."
  [^Boundary b]
  (let [s @(st b)]
    (when (and (= :failed (:status s)) (not (:fired? s)) (some? (:failure s)))
      (swap! (st b) assoc :fired? true)
      (:failure s))))

;; ---------------------------------------------------------------------------
;; Promotion — fire the safe intent and the private egress, once
;; ---------------------------------------------------------------------------

(defn- dispatch-safe-intent!
  "Append the safe summary to the author's `:on-error` event prefix and
  dispatch it into the boundary's frame — the ONE data intent a captured
  failure produces (D019 §Boundary semantics 5). A boundary under a frame
  dispatches into it; one under no frame in scope dispatches ambiently. No
  `:on-error` means no intent: lifecycle and render errors are not domain
  events, so an app receives one only when the author explicitly asked."
  [on-error summary frame-id]
  (when (some? on-error)
    (let [event (conj (vec on-error) summary)]
      (if (some? frame-id)
        (router/dispatch! event {:frame frame-id :source :ui})
        (router/dispatch! event {:source :ui})))))

(defn- promote-egress!
  "Promote the private frame egress record onto the always-on error axis
  (Spec 009 surface #4) and the frame-owned observability sink. The record
  carries the opaque exception and the capped host stack; observer/sink code
  owns redaction, source-map processing, and transport. A sink failure is
  isolated inside `dispatch-error-record!` and never replaces the fallback."
  [failure time]
  (error-emit/dispatch-error-record! (egress-record failure time)))

(defn promote!
  "Fire the two failure channels for `b`, at most ONCE per captured failure
  generation: the author's safe `:on-error` intent, and the private frame
  egress record. The host calls this after the fallback commits; the
  once-per-generation guard is [[take-report!]], so repeated calls within one
  generation are no-ops.

  `opts` carries the promotion context: `:on-error` (the author's event
  prefix, or nil), `:frame-id` (the boundary's committed frame), and `:time`
  (the emit instant in millis). Returns the safe summary that was promoted,
  or nil when there was nothing fresh to report."
  [^Boundary b {:keys [on-error frame-id time]}]
  (when-let [failure (take-report! b)]
    (dispatch-safe-intent! on-error (:summary failure) frame-id)
    (promote-egress! failure time)
    (:summary failure)))

;; ---------------------------------------------------------------------------
;; The synchronous containment primitive
;; ---------------------------------------------------------------------------

(defn contain
  "Run `render-thunk` under the containment of `b` and answer what the
  boundary should show. On the JVM structural host and any synchronous host,
  this is the whole boundary: the child render runs inside a try, and a
  render-class throw is CAPTURED rather than propagated, so the boundary
  shows its fallback while a SIBLING subtree keeps rendering.

    {:status :ok       :result <the child's value>}      ; the child rendered
    {:status :contained :summary <the safe summary>}     ; the child threw

  `context` supplies the failure attribution the boundary cannot infer from
  the throwable alone — `:phase`, `:frame-id`, and the evidence `:complete?`
  / `:loss` — merged with the caught `:exception`. WHICH declared view threw
  is not context: it comes from the occurrence seam's note through
  [[attributed-failure]], so a throw several levels below the guarded child
  is attributed to the view that actually threw. A candidate that threw
  published nothing (the atomic shell's law); this decides what replaces it.

  React cannot express its own error boundary as a try around a child render
  — a descendant renders in its own fiber — so the browser drives the SAME
  [[capture!]] through a class boundary's `componentDidCatch` instead. This
  primitive is the JVM/synchronous realisation of the identical law."
  [^Boundary b render-thunk context]
  (if (= :failed (status b))
    {:status :contained :summary (:summary (failure b))}
    (try
      {:status :ok :result (render-thunk)}
      (catch #?(:clj Throwable :cljs :default) e
        ;; The attribution read is keyed by THIS throw, so the guarded region
        ;; needs no scrubbing on the way in and cannot read a note left by a
        ;; failure that is not the one it caught.
        (let [record (capture! b (attributed-failure (assoc context :exception e)))]
          {:status :contained :summary (:summary record)})))))
