(ns re-frame.observability
  "Frame-owned observability sink routing — the EP-0015 §9 central claim
  made production-live. Graduated into
  [`spec/015-Data-Classification.md` §Frame-owned observability sink policy]
  (../../../../../spec/015-Data-Classification.md#frame-owned-observability-sink-policy).

  ## The §9 claim, wired

  > App authors declare a sink under frame `:observability`; the runtime
  > projects every record under the owning frame's classification and the
  > sink's egress profile BEFORE the sink sees it; sinks consume
  > already-projected records only.

  A frame's `make-frame` `:observability` config (validated for SHAPE at
  construction time by `re-frame.frame-classification`) names two production
  observation streams:

      (rf/make-frame {:id :app/main}
        {:observability
         {:handled-events [{:sink :my-app.sinks/datadog
                            :rf.egress/profile :rf.egress/off-box-observability}]
          :errors         [{:sink :my-app.sinks/sentry
                            :rf.egress/profile :rf.egress/off-box-observability}]}})

  - `:handled-events` — ONE production-safe observation record per re-frame
    event processed by THIS frame (NOT browser DOM events; NOT the
    fine-grained dev trace stream). The router calls
    [[route-handled-event!]] once per processed event after the cascade
    settles, ALONGSIDE the always-on `event-emit` listener fan-out.
  - `:errors` — production-survivable error records (EP-0008's always-on
    error axis). `error-emit/dispatch-on-error!` calls [[route-error!]]
    for every event-centric `:rf.error/*` site, and
    `error-emit/dispatch-error-record!` calls [[route-error-record!]] for
    the EP-0008 NON-EVENT union records (the frame-teardown report, the
    promoted SSR categories) — BOTH ALONGSIDE the always-on error-emit
    listener fan-out.

  This is the THIRD of the three observation streams (Spec 015 §The three
  observation streams) — the bounded, projected, frame-`:observability`-
  routed PRODUCTION observation stream. It is distinct from, and parallel
  to, the corpus-wide `register-event-listener!` / `register-error-listener!`
  registries (`re-frame.event-emit` / `re-frame.error-emit`), which EP-0015
  §9 / Spec 015 relegate to ADVANCED integration APIs — not the normal
  production Datadog/Sentry story. The NORMAL story is declaring a sink
  under frame `:observability`; THIS namespace is that story's runtime.

  ## Sinks receive ALREADY-PROJECTED records (never re-implement redaction)

  Each sink entry names a `:sink` keyword id. The actual sink FN is
  registered against that id via [[register-observability-sink!]] (an
  app/integration-library concern — the framework does not ship Datadog /
  Sentry clients, EP-0015 Non-Goals). At routing time the runtime:

    1. Builds the canonical `:rf.observe/*` record (handled-event / error)
       carrying ONLY the summary fields the stream defines.
    2. Projects it through `re-frame.projection/project-egress` under the
       OWNING frame's classification and the entry's `:rf.egress/profile`
       (defaulting to `:rf.egress/off-box-observability` — the hosted-
       monitoring boundary — when the entry omits the profile).
    3. Delivers the PROJECTED record to the resolved sink fn.

  The sink sees a record that has ALREADY had sensitive paths redacted and
  large paths elided. A sink author writes `(fn [record] ...)` —
  no sink-local redaction (EP-0015 §9).

  ## Fail closed on an unresolved frame (EP-0002 / Spec 015 §Direct reads)

  Observability is frame-scoped. If the routing site has no resolvable
  frame record (a destroyed / never-registered frame), routing is a NO-OP:
  the runtime does NOT synthesise `:rf/default`, does NOT borrow another
  frame's sink policy, and does NOT ship a record under unknown
  classification. `project-egress` itself ALSO fails closed per-slot (it
  redacts a tree slot to `:rf/redacted` when the frame is unknown) — so the
  fail-closed posture is belt-and-braces: no frame ⇒ no routing AND, were a
  record to reach the projector frameless, it redacts rather than leaks.

  ## A buggy sink cannot block siblings

  Each sink invocation is try/catch wrapped. A throwing sink is dropped
  (the throw is a sibling-isolation concern, not a framework error — same
  posture as the `re-frame.error-emit` listener fan-out); the remaining
  declared sinks still receive the record.

  ## Load order / late-bind

  This ns sits in core but ABOVE the router and the emit substrates in the
  conceptual call graph: the router (`re-frame.router`) and the error-emit
  substrate (`re-frame.error-emit`) reach the routing fns through the
  `:observability/route-handled-event` / `:observability/route-error`
  late-bind hooks published at the foot of this ns — never a static
  require (a static `router` → `observability` → `projection` → `elision`
  → `frame` require would close a load cycle; the late-bind seam is the
  same cycle-break the sibling always-on substrates use). `re-frame.core`
  requires this ns at boot, so the hooks are bound before any dispatch."
  (:require [re-frame.frame                :as rf.frame]
            [re-frame.frame-classification :as rf.frame-classification]
            [re-frame.late-bind            :as rf.late-bind]
            [re-frame.projection           :as rf.projection]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- sink registry --------------------------------------------------------
;;
;; sink-id (keyword) -> sink fn. `defonce` so a hot reload of THIS namespace
;; does not silently drop a long-lived production sink the consuming app
;; registered at boot (same posture as the event-emit / error-emit listener
;; registries).

(defonce ^:private sinks (atom {}))

(defn register-observability-sink!
  "Register an observability sink FN `f` under the keyword `sink-id`. The
  `sink-id` is the same user/library-owned id named by a frame's
  `:observability` `{:sink <sink-id> ...}` entry. Re-registering the same
  id replaces. `f` receives a single ALREADY-PROJECTED record (a
  `:rf.observe/handled-event` or `:rf.observe/error` record projected under
  the frame's classification and the entry's egress profile); its return
  value is ignored. Returns `sink-id`.

  The framework does not ship Datadog / Sentry clients (EP-0015 Non-Goals);
  registering the concrete sink fn is an app / integration-library concern.
  A sink author writes `(fn [record] (datadog/send record))`
  — NO sink-local redaction (the record is already projected, EP-0015 §9)."
  [sink-id f]
  (swap! sinks assoc sink-id f)
  sink-id)

(defn unregister-observability-sink!
  "Drop the observability sink registered under `sink-id`. Returns nil."
  [sink-id]
  (swap! sinks dissoc sink-id)
  nil)

(defn clear-observability-sinks!
  "Drop every registered observability sink. Test-isolation only;
  production code should never call this. Returns nil."
  []
  (reset! sinks {})
  nil)

;; ---- process-default sink policy (rf2-kuky.67) ----------------------------
;;
;; A frame's `:observability` says who observes THAT frame. The process
;; default says who observes the PROCESS — declared once, at boot, beside
;; every other `configure!` knob.
;;
;; It exists for two reasons, and the second is the one that made it
;; structural rather than a convenience:
;;
;;   1. A multi-frame app restated its Sentry policy on every `make-frame`
;;      call. Policy is a deployment property, not a per-frame one.
;;   2. Three producers emit records with NO resolvable frame owner, so no
;;      frame policy can ever route them: `:rf.error/no-frame-context` (by
;;      construction there is no frame), the pre-frame SSR hydration-parse
;;      arm of `:rf.error/malformed-hydration-payload`, and fresco's
;;      compute-sub `:rf.error/sub-exception` (stamped `:frame nil` BY
;;      CONSTRUCTION). Before this default those records reached only the
;;      corpus-wide `register-listener!` `:errors` stream — the door
;;      rf2-kuky.69 retires. The default is where they land instead, so the
;;      retirement closes a door that is no longer the only one.
;;
;; `defonce` for the same reason the sink registry is: a hot reload of THIS
;; ns must not silently drop the policy a long-lived production process
;; declared at boot. nil is the CLEARED state, and it is distinct from `{}`
;; only in spelling — both route nothing.

(defonce ^:private process-default (atom nil))

(defn configure!
  "Install the PROCESS-DEFAULT `:observability` sink policy — the subsystem
  configurator behind `(rf/configure! {:observability …})`. Returns nil.

  Takes the SAME closed value a frame's `:observability` key takes:

      {:errors         [{:sink :app/sentry}]
       :handled-events [{:sink :app/datadog
                         :rf.egress/profile :rf.egress/off-box-observability}]}

  and it is validated by the SAME validator `make-frame` uses
  (`frame-classification/validate-observability-policy!`), fail-loud at CALL
  time with `:rf.error/bad-frame-classification` and `:where 'rf/configure!`.
  One grammar, two scopes — see that fn for why there is no second error
  category.

  `nil` CLEARS the default. That is the whole of the absent/empty
  distinction at this door: absent from the `configure!` map leaves the
  default untouched (every other `configure!` key behaves this way), and an
  explicit `nil` removes it. The `[]` opt-out is a FRAME-level spelling —
  see [[resolve-route]] — because only a frame has something to opt out OF.

  Whole-map replacement, not a merge: a second call REPLACES the policy.
  Merging would make the clear unspellable and leave no way to remove one
  stream without knowing what the previous call installed."
  [observability]
  (rf.frame-classification/validate-observability-policy! observability)
  (reset! process-default observability)
  nil)

(defn current-observability-config
  "The process-default `:observability` policy currently in effect, or nil.
  The read twin of [[configure!]], behind `rf/current-config`'s
  `:observability` key. Returns the declared value VERBATIM — no frame
  resolution, no defaults synthesised."
  []
  @process-default)

(defn clear-observability-default!
  "Drop the process-default `:observability` policy. Test-isolation only,
  and deliberately NOT exported on the `re-frame.core` facade — a `clear-*`
  whose only caller is a fixture stays at its owning ns (per Conventions
  §Fixture-tier surfaces), exactly as [[clear-observability-sinks!]] does.
  Returns nil."
  []
  (reset! process-default nil)
  nil)

;; ---- routing --------------------------------------------------------------
;;
;; The default egress profile when a sink entry omits `:rf.egress/profile`.
;; §9 names hosted monitoring as the home of the frame `:observability`
;; stream; the off-box-observability boundary is its profile (redact
;; sensitive, elide large, omit digests).

(def ^:const default-profile :rf.egress/off-box-observability)

(defn- resolve-route
  "Resolve the ONE policy source for `stream` (`:handled-events` / `:errors`)
  and the frame whose classification GOVERNS the projection. Returns
  `[entries governing-frame]`.

  Two questions, deliberately separated — conflating them is what made the
  frameless case unroutable:

  **Which entries?** Per-STREAM precedence. A frame that DECLARES the stream
  uses its own entries for it; a frame that OMITS the stream inherits the
  process default's. Declaration is read by KEY PRESENCE, not truthiness, so
  `{:errors []}` is the frame's OPT-OUT — it declares the stream and names no
  sinks, which is a different statement from omitting it. Exactly ONE source
  is ever consulted per record per stream, so a sink id named by BOTH the
  frame and the default is invoked once; the duplicate-delivery rule is a
  consequence of this shape rather than a check bolted onto it.

  **Which classification governs?** The frame, whenever there IS one. A frame
  inheriting the default's entries is still projected under its OWN
  classification — inheritance moves the sink list, never the redaction
  authority.

  `frame-authority?` false means NO FRAME MAY BE CONSULTED for this record,
  and it is the producer's statement rather than a fact this fn could
  rediscover. `rf.frame/frame` cannot tell `never-registered` from
  `dissociated`, so an unresolved-id test here would pass a SAME-ID SUCCESSOR
  and hand A's teardown report to B's sink — the exact confusion
  `capture_frame_reincarnation_sink_route_cljs_test` pins against. The
  authority bit is therefore carried from the producer seam
  (`error-emit`'s `route-frame?`), never inferred.

  With no frame authority — a frameless `:frame nil` record, or a producer
  that revoked it — the process default is the only source and the governing
  frame is EXPLICITLY nil. `project-egress` reads `:frame` by KEY PRESENCE
  (rf2-kuky.5), so an explicit nil FAILS CLOSED: tree slots project to
  `:rf/redacted` and the summary ids stay intact. That is the whole reason
  `route-frame? false` can now mean *no frame authority — the process default
  still delivers* rather than *no sink route*: the record reaches an operator
  without any frame's classification vouching for its contents."
  [frame-id stream frame-authority?]
  (let [owner (when (and frame-authority? frame-id)
                (rf.frame/frame frame-id))]
    (if owner
      (let [declared (get-in owner [:config :observability])]
        [(if (contains? declared stream)
           (get declared stream)
           (get @process-default stream))
         frame-id])
      [(get @process-default stream) nil])))

(defn- deliver-to-sink!
  "Resolve `sink-id`'s registered sink fn and deliver the ALREADY-PROJECTED
  `projected` record to it. A throwing sink is dropped (sibling isolation —
  a buggy sink cannot block its siblings, same posture as the error-emit
  fan-out). A `sink-id` with no registered fn is a no-op (the policy named
  a sink the app has not wired yet).

  Returns 1 when a registered sink fn was INVOKED and 0 when none resolved.
  Invocation is the unit, not outcome: a sink that then threw still counts,
  because the sink author HAS the record and the swallow above must not read
  downstream as nobody-received-it. That count is what `error-emit`'s dev
  console fallback keys on (rf2-kuky.18) — see [[route-error!]]."
  [sink-id projected]
  (if-let [f (get @sinks sink-id)]
    (do (try
          (f projected)
          (catch #?(:clj Throwable :cljs :default) _ nil))
        1)
    0))

(defn- route-stream!
  "Route `record` to every sink entry on one `:observability` stream
  (`:handled-events` / `:errors`). For each entry: project `record` through
  `project-egress` under `frame-id`'s classification and the entry's
  `:rf.egress/profile` (defaulting to `off-box-observability`), then deliver
  the projected record to the entry's `:sink`.

  Returns the NUMBER of registered sinks the record was delivered to — 0 for
  nil / empty `entries`, and 0 for entries that name only sinks the app has
  not registered. Declaring a policy is not routing: that distinction is the
  whole of the second ownership arm in `error-emit`'s console fallback.

  `governing-frame` is the frame whose classification the projector applies —
  [[resolve-route]]'s second return, NOT the record's own `:frame` slot. The
  two diverge for exactly the records this route exists to reach: a frameless
  or stale-owner record keeps its `:frame` slot as a DIAGNOSTIC (a stale id
  still tells an operator which frame died) while projecting under an
  explicitly nil governing frame, which fails closed. Passing the record's
  `:frame` here instead would re-resolve a stale id against the live
  registry — the same-id-successor confusion [[resolve-route]] refuses."
  [governing-frame record entries]
  (reduce
    (fn [n entry]
      (if-some [sink-id (:sink entry)]
        (let [profile   (get entry :rf.egress/profile default-profile)
              projected (rf.projection/project-egress
                          record
                          {:frame             governing-frame
                           :rf.egress/profile profile})]
          (+ n (deliver-to-sink! sink-id projected)))
        n))
    0
    entries))

(defn route-handled-event!
  "Route ONE `:rf.observe/handled-event` record for a processed event to
  the owning frame's declared `:observability :handled-events` sinks
  (EP-0015 §9, the central claim).

  Builds the canonical handled-event record — `:frame`, `:event-id`,
  `:status` (the dispatch outcome), `:elapsed-ms`, `:effects` (the effect
  keys), and `:correlation` ids — and the `:event` slot (the raw dispatched
  vector). The projector applies the off-box rule: under the default
  off-box-observability profile the `:event` ARGS slot is OMITTED ENTIRELY
  (EP-0015 issue 4); a trusted-local profile keeps it PROJECTED (never raw).
  Each sink receives the ALREADY-PROJECTED record.

  Fail-closed: a NO-OP when neither `frame-id` nor the process default names
  a `:handled-events` sink — the common case, so a frame with no
  observability policy allocates nothing here. A frame that declares no
  `:handled-events` INHERITS the process default's for that stream, and one
  that declares `{:handled-events []}` opts out ([[resolve-route]]). Returns
  nil.

  `effects` is the seq of effect keys the cascade walked; `correlation` is
  the `{:work-id ... :dispatch-id ...}` correlation map (or nil — the slot
  is then absent). Called once per processed event from the router's
  cascade trailers via the `:observability/route-handled-event` late-bind
  hook, ALONGSIDE the always-on `event-emit` fan-out."
  [event event-id frame-id status elapsed-ms effects correlation]
  (let [[entries governing-frame] (resolve-route frame-id :handled-events true)]
    (when (seq entries)
      (let [record (cond-> {:kind       :rf.observe/handled-event
                            :frame      frame-id
                            :event-id   event-id
                            :event      event
                            :status     status
                            :elapsed-ms elapsed-ms}
                     (some? effects)     (assoc :effects effects)
                     (some? correlation) (assoc :correlation correlation))]
        (route-stream! governing-frame record entries))))
  nil)

(def ^:private attribution-summary-keys
  "The producer's COMPONENT-ATTRIBUTION slots that map onto canonical
  `:rf.observe/error` SUMMARY slots — structural identifiers, passed through
  the projector unchanged (rf2-kuky.65 / Spec 015 §Frame-owned observability
  sink policy).

  These are exactly the tight identifiers `error-emit/dispatch-on-error!`
  merges onto its corpus-wide record: `:failing-id` (the interceptor / cofx
  whose id is DISTINCT from `:event-id`), `:flow-id` + `:where` (the flow-eval
  attribution rf2-z1332c lifted so it SURVIVES an egress profile that drops
  `:exception`), and the `:source-coord` `{:ns :file :line}` the always-on
  error-coord registry resolves for Sentry-style shippers. The producer's own
  contract for them is `callers keep these to tight identifiers — this record
  is production-surviving and NOT privacy-gated`.

  Every OTHER attribution slot — `:reason`, and anything a future category
  lifts — is NOT a summary slot. It rides the `:tags` tree-key so the projector
  walks it under frame classification. That is a sound DEFAULT, NOT a
  fail-closed safety property, and the difference matters: the path walker
  deliberately passes an UNCLASSIFIED slot through, and being PATH-based it
  cannot redact a secret a producer interpolated into a scalar `:reason` merely
  because `[:auth :token]` was classified elsewhere (value-matching / taint is
  exactly what EP-0025 removed). What `:tags` buys is REACH: a frame that
  classifies `[:reason]` redacts that slot WHOLE to `:rf/redacted` while the
  structural identifiers above survive beside it — and a future category's
  attribution slot lands on the walked side by default rather than on the
  production-surviving summary surface. It is also the rule the non-event
  [[route-error-record!]] route already applies. `:reason` in particular is
  free-form prose that INTERPOLATES app values: the coeffect categories fold
  the thrown exception's own message into it (`cofx/emit-missing-required-cofx!`
  builds `\"Coeffect supplier for `x` threw: <ex-message>\"`), and `router.cljc`
  already declines to lift a `:failing-id` at two sites precisely so its
  interpolating `:reason` cannot reach the always-on record. So it is a TREE
  slot on both routes, never a public summary one."
  #{:failing-id :flow-id :where :source-coord})

(defn route-error!
  "Route ONE `:rf.observe/error` record for an `:rf.error/*` site to the
  owning frame's declared `:observability :errors` sinks (EP-0015 §9).

  Builds the canonical error record — `:error` (the `:rf.error/*` category),
  `:event-id`, `:event` (the dispatched vector, a tree slot the projector
  redacts under frame policy), `:exception` (the host exception — dropped
  under `:rf.egress/public-error`, walked otherwise), `:elapsed-ms`,
  `:time`, and `:correlation`. Each sink receives the ALREADY-PROJECTED
  record.

  Called from `error-emit/dispatch-on-error!` via the
  `:observability/route-error` late-bind hook, ALONGSIDE the always-on
  corpus-wide error-listener fan-out.

  `frame-authority?` (trailing, default true — rf2-kuky.67) is the producer's
  statement that `frame-id` MAY be consulted for policy. False for a
  known-dead incarnation, where the bare id must never resolve to a same-id
  successor's sink. It no longer suppresses the route: the record goes to the
  PROCESS DEFAULT under an explicitly nil governing frame ([[resolve-route]]),
  so a teardown report reaches the operator without any successor's
  classification vouching for it. Before this, `route-frame? false` meant no
  sink route at all and the record's only channel was the corpus-wide
  `:errors` stream rf2-kuky.69 retires.

  Returns the NUMBER of registered sinks this record was DELIVERED to (0 when
  neither the frame nor the process default names a sink, or when the entries
  name only sinks the app never registered). `error-emit` takes its dev
  console fallback decision off that count (rf2-kuky.18): a record a policy
  actually handed to a sink is owned — the PROCESS DEFAULT owns it on exactly
  the same terms as a frame's policy, which is Q7 — and printing it beside
  the sink would be the duplicate the fallback exists to avoid, while a
  policy that routed NOWHERE leaves the console the only channel it has.

  `raw-event?` (trailing, default false — #6441 / rf2-zwgqe) marks `:event` as
  a subscription QUERY VECTOR: raw IDENTITY that egresses VERBATIM, never
  app-db-elided. When set, the record carries the transient projector-input
  marker `:re-frame.projection/raw-event?` (an internal keyword OUTSIDE the
  closed `:rf.observe/*` `:kind` vocabulary; dropped before sink delivery) so
  `re-frame.projection/project-error-record` keeps `:event` raw on this sink
  route rather than policy-walking it (a concrete integer app-db path
  coincidentally matching a query-vector coordinate would otherwise mutate
  identity here, exactly as it did on the corpus-wide record). The 8-arity
  keeps every dispatched-event caller unchanged (elided as before).

  `attrs` (trailing, rf2-kuky.65) is the producer's COMPONENT-ATTRIBUTION map —
  the same slots `error-emit/dispatch-on-error!` merges onto its corpus-wide
  record, plus `:source-coord`. Without it a sink learned the CATEGORY but never
  WHICH interceptor / cofx / flow failed, and no egress profile could restore
  what the record never carried; once the corpus-wide `:errors` stream retires
  this route is the ONLY production door, so that is lost diagnosis rather than
  redundancy. The slots are SPLIT, never blanket-merged (Spec 015 §Frame-owned
  observability sink policy): [[attribution-summary-keys]] ride the top level as
  canonical summary slots the projector passes through unchanged, and EVERY
  other slot — `:reason` among them — rides `:tags`, which the projector walks
  and redacts under frame classification, symmetric with the non-event
  [[route-error-record!]] route. The base observability fields always WIN over
  an attribution slot of the same name, exactly as they do at the producer."
  ([error-kw event event-id frame-id exception elapsed-ms time correlation]
   (route-error! error-kw event event-id frame-id exception elapsed-ms time
                 correlation false nil true))
  ([error-kw event event-id frame-id exception elapsed-ms time correlation raw-event?]
   (route-error! error-kw event event-id frame-id exception elapsed-ms time
                 correlation raw-event? nil true))
  ([error-kw event event-id frame-id exception elapsed-ms time correlation raw-event?
    attrs]
   (route-error! error-kw event event-id frame-id exception elapsed-ms time
                 correlation raw-event? attrs true))
  ([error-kw event event-id frame-id exception elapsed-ms time correlation raw-event?
    attrs frame-authority?]
   (let [[entries governing-frame] (resolve-route frame-id :errors frame-authority?)]
     (if (seq entries)
       (let [attribution (into {} (remove (comp nil? val)) attrs)
             summary     (select-keys attribution attribution-summary-keys)
             tags        (not-empty (apply dissoc attribution
                                           attribution-summary-keys))
             record (cond-> (merge summary
                                   {:kind       :rf.observe/error
                                    :frame      frame-id
                                    :error      error-kw
                                    :event-id   event-id
                                    :event      event
                                    :exception  exception
                                    :elapsed-ms elapsed-ms
                                    :time       time})
                      tags                (assoc :tags tags)
                      (some? correlation) (assoc :correlation correlation)
                      raw-event?          (assoc :re-frame.projection/raw-event? true))]
         (route-stream! governing-frame record entries))
       0))))

;; ---- non-event union record route (EP-0008) -------------------------------
;;
;; `route-error!` (above) is the EVENT-centric route: its positional signature
;; `[error-kw event event-id frame-id exception elapsed-ms time correlation]`
;; builds an `:rf.observe/error` record from a dispatched-event / subscribe
;; failure. The EP-0008 NON-EVENT always-on records — the frame-teardown
;; report (`:hook-failures`) and the promoted SSR categories (`:phase` /
;; `:reason` / `:projector-id` / …) — do NOT fit that positional shape: they
;; are pre-built union records with flat category-specific slots and no
;; `:event` / `:event-id`. `route-error-record!` is their route to the
;; frame-owned `:observability :errors` sinks, so that — per Spec 015
;; §Frame-owned observability sink policy — EVERY production-reachable
;; `:rf.error/*` site reaches the frame sinks ALONGSIDE the corpus-wide
;; `register-error-listener!` fan-out, keeping the production sink model (the
;; Datadog/Sentry story) fed with EP-0008's teardown report.

(def ^:private error-record-summary-keys
  "Slots of a non-event union error record that map onto the canonical
  `:rf.observe/error` SUMMARY slots (structural metadata — passed through the
  projector unchanged). Every OTHER non-`:error` slot is lifted onto the
  `:tags` tree-key so the projector walks it under frame classification
  (sensitive redaction / large elision) — symmetric with the SSR
  `error-emit-projection-listener`'s generic tags-lift."
  #{:frame :error :event-id :elapsed-ms :time :correlation})

(defn route-error-record!
  "Route ONE pre-built NON-EVENT union error `record` to the owning frame's
  declared `:observability :errors` sinks (EP-0015 §9 / Spec 015 §Frame-owned
  observability sink policy). The non-event counterpart of [[route-error!]] —
  for the EP-0008 `:rf.error/*` categories that are NOT a dispatched-event /
  subscribe failure (the frame-teardown report, the promoted SSR categories),
  whose union record `{:error <kw> :frame <id-or-nil> :time <ms> + flat
  category keys}` does not fit the event-centric positional signature.

  Projects the record into a canonical `:rf.observe/error` shape and routes it
  through `project-egress` exactly like [[route-error!]], so the sink receives
  an ALREADY-PROJECTED record (EP-0015 §9 — sinks never re-implement
  redaction). The canonical SUMMARY slots (`:frame` / `:error` / `:event-id` /
  `:elapsed-ms` / `:time` / `:correlation`) pass through; the host
  `:exception`, if present, rides the top-level `:exception` slot the projector
  DROPS under `:rf.egress/public-error` and walks otherwise; EVERY remaining
  flat category slot (`:hook-failures` / `:phase` / `:reason` / `:projector-id`
  / …) is lifted onto the `:tags` tree-key so the projector REDACTS it under
  frame classification (a `:hook-failures` entry's nested exception ex-data, an
  app value folded into `:reason`, …). This is the same generic tags-lift the
  SSR `error-emit-projection-listener` performs — so the projected record the
  sink sees is structurally consistent across the event and non-event paths.

  This is the route the THREE FRAMELESS PRODUCERS reach (rf2-kuky.67):
  `:rf.error/no-frame-context`, the pre-frame SSR hydration-parse arm of
  `:rf.error/malformed-hydration-payload`, and fresco's compute-sub
  `:rf.error/sub-exception` — all stamped `:frame nil` BY CONSTRUCTION, so no
  frame policy can ever route them. They go to the PROCESS DEFAULT
  ([[configure!]]) under an explicitly nil governing frame, which fails
  closed: tree slots project to `:rf/redacted`, summary ids stay intact. A
  record whose `:frame` no longer RESOLVES takes the same arm and keeps its
  stale id in the summary as a diagnostic — the id is never re-resolved
  against the live registry.

  Called from `error-emit/dispatch-error-record!` via the
  `:observability/route-error-record` late-bind hook, ALONGSIDE the always-on
  corpus-wide error-listener fan-out.

  `frame-authority?` (trailing, default true) carries the producer's
  route-frame? bit — see [[route-error!]] and [[resolve-route]] for why it
  cannot be inferred here.

  Returns the NUMBER of registered sinks this record was DELIVERED to, on the
  same terms as [[route-error!]] — so a frameless record returns 0 only when
  the process default names no `:errors` sink either, which is the untooled
  case the console fallback exists for."
  ([record] (route-error-record! record true))
  ([record frame-authority?]
   (let [frame-id (:frame record)
         [entries governing-frame] (resolve-route frame-id :errors frame-authority?)]
     (if (seq entries)
       (let [summary  (select-keys record error-record-summary-keys)
             ;; Everything that is NOT a summary slot, the literal :error
             ;; category, or the (separately-handled) :exception rides :tags so
             ;; the projector walks + redacts it under frame classification.
             tags     (dissoc record :error :exception
                              :frame :event-id :elapsed-ms :time :correlation)
             ;; `:frame` keeps the record's OWN id — stale or nil — as the
             ;; summary diagnostic. `governing-frame` is what the projector
             ;; redacts under, and the two differ for exactly the frameless /
             ;; stale-owner records this arm exists to carry.
             observe  (cond-> (assoc summary
                                     :kind  :rf.observe/error
                                     :error (:error record)
                                     :frame frame-id)
                        (seq tags)              (assoc :tags tags)
                        (contains? record :exception)
                        (assoc :exception (:exception record)))]
         (route-stream! governing-frame observe entries))
       0))))

;; ---- late-bind hook registration ------------------------------------------
;;
;; The router (`re-frame.router`) fires the handled-event route once per
;; processed event; the error-emit substrate (`re-frame.error-emit`) fires
;; the error route from every `:rf.error/*` site. BOTH reach this ns through
;; the late-bind hook table at call time rather than static-requiring it —
;; a static `router`/`error-emit` → `observability` → `projection` →
;; `elision` → `frame` require would close a load cycle. `re-frame.core`
;; requires this ns at boot, so the hooks are bound before any dispatch.

(rf.late-bind/set-fn! :observability/route-handled-event route-handled-event!)
(rf.late-bind/set-fn! :observability/route-error         route-error!)
(rf.late-bind/set-fn! :observability/route-error-record  route-error-record!)
