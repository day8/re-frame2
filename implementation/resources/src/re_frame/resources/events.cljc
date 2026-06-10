(ns re-frame.resources.events
  "The resource event handlers — the causal write surface over the
  resource cache + work ledger. Per Spec 016 §Public API §Events.

  The public resource events take MAP payloads (not positional argument
  vectors):

    [:rf.resource/ensure          {:resource … :scope … :params … :owner … :cause …}]
    [:rf.resource/refetch         {:resource … :scope … :params … :cause …}]
    [:rf.resource/invalidate-tags {:scope … :tags … :cause …}]
    [:rf.resource/release-owner   {:owner …}]
    [:rf.resource/clear-scope     {:scope … :cause …}]
    [:rf.resource/remove          {:resource … :scope … :params …}]

  Plus the framework-INTERNAL replies (`:rf.resource.internal/*`) that
  carry the verification payload (`:work-id` / `:resource-key` / `:scope`
  / `:generation` / `:rf.frame/id`) — user code MUST NOT dispatch them.

  Every handler carries framework-write authority
  (`state/framework-authority-meta`) so a returned `:rf.db/runtime`
  effect is in-bounds (Spec 016 §Write authority); the registrations live
  in the `re-frame.resources` façade so a `(require … :reload)` on a
  fresh registrar re-wires them.

  SKELETON slice (rf2-p10npe): the handler FNS are defined here with
  their payload contract documented, but their bodies are stubs that
  raise `:rf.error/resource-not-implemented` — the entry transition
  function, work-ledger join/dedupe, stale suppression, GC, and
  invalidation land with the runtime slices (rf2-afpdkn / rf2-pbxj48 /
  …). A stub fails LOUDLY rather than silently no-opping, so a premature
  call is obvious. The handler IDS, payload shapes, and authority stamp
  are pinned now."
  (:require [re-frame.resources.transport :as transport]))

#?(:clj (set! *warn-on-reflection* true))

(defn- not-implemented
  "Raise the canonical not-yet-implemented error for a skeleton handler
  body (rf2-p10npe). The handler is REGISTERED (so the registry surface,
  Xray's static view, and the late-bind wiring are real) but its runtime
  logic lands in a later slice; a premature dispatch fails loudly here."
  [event-id slice]
  (throw (ex-info ":rf.error/resource-not-implemented"
                  {:rf.error/id :rf.error/resource-not-implemented
                   :where       event-id
                   :recovery    :no-recovery
                   :reason      (str event-id " is registered but its runtime "
                                     "logic lands in a later EP-0003 slice ("
                                     slice "). This is the rf2-p10npe artefact "
                                     "skeleton.")
                   :event-id    event-id
                   :slice       slice})))

;; ---- public resource events ----------------------------------------------

(defn ensure-handler
  "`:rf.resource/ensure` — ensure a resource instance is loaded (load it
  if absent / stale per policy; join the in-flight work record if one
  exists; attach `:owner` to entry + ledger row; record `:cause`). Per
  Spec 016 §Events and §Race and in-flight semantics. Payload:
  `{:resource :scope :params :owner :cause}`.

  SKELETON: runtime lands in rf2-pbxj48 (resource runtime — entries,
  scopes, canonical params, status transitions, structural sharing)."
  [_cofx [_event-id _payload]]
  (not-implemented :rf.resource/ensure "rf2-pbxj48 resource runtime"))

(defn refetch-handler
  "`:rf.resource/refetch` — force a refresh of a resource instance (may
  force a new generation; supersede + suppress any in-flight prior
  request). Per Spec 016 §Events and §Race and in-flight semantics.
  Payload: `{:resource :scope :params :cause}`.

  SKELETON: runtime lands in rf2-pbxj48."
  [_cofx [_event-id _payload]]
  (not-implemented :rf.resource/refetch "rf2-pbxj48 resource runtime"))

(defn invalidate-tags-handler
  "`:rf.resource/invalidate-tags` — exact tag invalidation (mark matched
  entries stale; refetch the active-owner ones; leave inactive ones stale
  / GC-eligible; emit one decision summary + per-entry details). Scoped by
  default. Per Spec 016 §Invalidation. Payload: `{:scope :tags :cause}`.

  SKELETON: runtime lands in rf2-pbxj48 / the invalidation+GC slice."
  [_cofx [_event-id _payload]]
  (not-implemented :rf.resource/invalidate-tags "rf2-pbxj48 / invalidation slice"))

(defn release-owner-handler
  "`:rf.resource/release-owner` — release a liveness lease (drop the owner
  from every entry's `:active-owners` + the owner-index; abort in-flight
  work only when no remaining owner needs it). Per Spec 016 §Active owners
  and causes and §Race and in-flight semantics. Payload: `{:owner …}`.

  SKELETON: runtime lands in rf2-pbxj48."
  [_cofx [_event-id _payload]]
  (not-implemented :rf.resource/release-owner "rf2-pbxj48 resource runtime"))

(defn clear-scope-handler
  "`:rf.resource/clear-scope` — causal scope clear (remove/mark-unusable
  every entry in the scope; release its owners; abort in-flight requests
  with no remaining owner outside the scope; suppress late replies by
  scope + generation; emit explaining trace rows). The logout / account-
  switch / tenant-switch / impersonation-change boundary. Per Spec 016
  §clear-scope is causal. Payload: `{:scope :cause}`.

  SKELETON: runtime lands in rf2-pbxj48."
  [_cofx [_event-id _payload]]
  (not-implemented :rf.resource/clear-scope "rf2-pbxj48 resource runtime"))

(defn remove-handler
  "`:rf.resource/remove` — remove a single resource instance from the
  cache by its scoped key. Per Spec 016 §Events. Payload:
  `{:resource :scope :params}`.

  SKELETON: runtime lands in rf2-pbxj48."
  [_cofx [_event-id _payload]]
  (not-implemented :rf.resource/remove "rf2-pbxj48 resource runtime"))

;; ---- framework-internal reply handlers -----------------------------------
;;
;; These carry the verification payload and MUST verify frame + work-id +
;; generation before writing (Spec 016 §Transport — stale suppression is
;; the correctness boundary). User code MUST NOT dispatch them.

(defn succeeded-handler
  "`:rf.resource.internal/succeeded` — a transport read succeeded. Verify
  frame + work-id + generation against the live entry; on match, install
  the decoded data (`:loaded`), preserving the old `:data` value when the
  new data is `=` (structural sharing); prune the terminal work row. Per
  Spec 016 §Transport / §Structural sharing / §Ledger row retention.

  SKELETON: runtime lands in rf2-pbxj48."
  [_cofx [_event-id _payload]]
  (not-implemented :rf.resource.internal/succeeded "rf2-pbxj48 resource runtime"))

(defn failed-handler
  "`:rf.resource.internal/failed` — a transport read failed. Verify
  frame + work-id + generation; a first-load failure settles `:error`
  (no usable data); a background-refresh failure returns to `:loaded`,
  keeps prior `:data`, and records `:refresh-error`. Per Spec 016 §Status
  semantics.

  SKELETON: runtime lands in rf2-pbxj48."
  [_cofx [_event-id _payload]]
  (not-implemented :rf.resource.internal/failed "rf2-pbxj48 resource runtime"))

(defn aborted-handler
  "`:rf.resource.internal/aborted` — a transport read was aborted (owner
  exit / scope clear / route supersession / newer generation). Reconcile
  the work row to `:cancelled`; the entry settles to its last stable
  status. Per Spec 016 §Cancellation is opportunistic; stale suppression
  is mandatory.

  SKELETON: runtime lands in rf2-afpdkn (work-ledger substrate)."
  [_cofx [_event-id _payload]]
  (not-implemented :rf.resource.internal/aborted "rf2-afpdkn work-ledger substrate"))

(defn gc-fired-handler
  "`:rf.resource.internal/gc-fired` — an inactive-GC timer fired. Re-check
  owner sets + entry generation after wake (timers are advisory); remove
  the entry only if still GC-eligible. Per Spec 016 §Stale and GC
  scheduling.

  SKELETON: runtime lands in the stale/GC slice."
  [_cofx [_event-id _payload]]
  (not-implemented :rf.resource.internal/gc-fired "stale/GC slice"))

(defn stale-suppressed-handler
  "`:rf.resource.internal/stale-suppressed` — a late reply carrying a
  superseded work-id / generation was suppressed (it MUST NEVER mutate a
  newer entry). Records the suppression in trace / ledger. Per Spec 016
  §Cancellation is opportunistic; stale suppression is mandatory.

  SKELETON: runtime lands in rf2-afpdkn (work-ledger substrate)."
  [_cofx [_event-id _payload]]
  (not-implemented :rf.resource.internal/stale-suppressed "rf2-afpdkn work-ledger substrate"))

;; A load-bearing reference to the transport seam so the require is honest
;; in the skeleton (the runtime slice's ensure/refetch handlers lower
;; through `transport/lower-ensure`).
(def ^:private _transport-seam transport/lower-ensure)
