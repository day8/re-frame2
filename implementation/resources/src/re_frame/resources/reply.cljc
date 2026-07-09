(ns re-frame.resources.reply
  "Spec 016 — lower resource + mutation completions onto the uniform reply
  envelope (EP-0011 §Resource Reply And Work Ledger / §Mutation Reply; the
  canonical contract is `spec/Managed-Effects.md` §The uniform reply
  envelope).

  ## What this namespace is

  The internal seam that turns a resource / mutation completion — which
  arrives through the SAME managed-HTTP transport (Spec 014, `:rf.http/
  managed`) the rest of the runtime lowers through — into the ONE canonical
  reply map every managed *async* family produces. Resources and mutations
  do NOT expose a public `:on-success` / `:on-failure` callback vocabulary
  of their own (those are `:rf.http/managed`'s public sugar); the resource /
  mutation reply targets are framework-INTERNAL (`:rf.resource.internal/*` /
  `:rf.mutation.internal/*`), so they receive the canonical reply map
  DIRECTLY rather than the Spec 014 `{:kind …}` public-payload reshape.

  Because resources lower through managed HTTP, the transport delivers its
  CANONICAL reply envelope (`{:status :ok :value v …}` / `{:status :error
  :error <:rf.http/* envelope> …}`, rf2-ibksxg — there is no longer a
  `{:kind :success/:failure}` public reshape) appended as the last arg of
  the internal reply event (Spec 014 §Reply addressing). This namespace
  re-lifts that transport reply — reading `:value` on success and `:error`
  on failure — plus the runtime-owned verification payload (`:work/id` /
  `:resource/key` / `:scope` / `:generation` / `:rf.frame/id`) the resource
  lowering stamped, into the resource / mutation canonical reply map (which
  restamps the resource-family `:rf.reply/work-id` / `:rf.reply/work-kind` / `:correlation`
  onto the same `:status` / `:value` / `:error` shape). The two families
  share the SAME core substrate (`re-frame.reply`): one closed `:status`
  taxonomy, one work-id correlation rule, one stale-suppression boundary,
  one functor law.

  ## The reply-map shape (Managed-Effects §The reply map)

      {:status       :ok | :error | :cancelled | :stale
       :value        decoded-result      ;; :ok only (EP-0007: the reply
                                          ;; result is :value EVERYWHERE)
       :error        :rf.http/* envelope  ;; :error / :cancelled
       :rf.reply/work-id      [:rf.work/resource …] | [:rf.work/resource [:rf.mutation …] …]
       :rf.reply/work-kind    :resource | :mutation
       :rf.reply/work-status  :completed | :failed | :cancelled | :suppressed
       :rf.frame/id  frame-id
       :completed-at causal epoch-ms (when supplied)
       :correlation  {:scope … :generation … :rf.reply/resource-key …
                      :mutation/id … :instance/id …}}

  **The `:value` / `:result` reconciliation (kh9jz6).** The decoded result
  on the *reply map* is `:value` for EVERY family (HTTP, resource, mutation)
  — there is no per-family synonym (EP-0007 one-name-per-fact). The durable
  resource entry stores it under `:data`, and the durable mutation INSTANCE
  stores it under `:result`, as deliberately distinct facts: the entry /
  instance row is a queryable, durable status record (a different LAYER from
  the transient causal reply), so the two spellings name two facts living in
  two layers. `:value` is the reply-map spelling, full stop (Managed-Effects
  §The reply map). This namespace builds the reply with `:value`; the events
  layer reads `(:value reply)` and installs it under the layer-appropriate
  durable key (`:data` for a resource entry, `:result` for a mutation
  instance).

  ## Work-kind (Managed-Effects §Work-id correlation)

  A resource attempt is `:rf.reply/work-kind :resource`; a mutation attempt is
  `:rf.reply/work-kind :mutation` (the ledger row distinguishes the writer). Both
  reuse the `[:rf.work/resource …]` head — a mutation's head carries a
  mutation-instance key `[:rf.mutation instance-id]` (Managed-Effects
  §Work-id correlation, the Mutation row).

  Pure — no atoms, no dispatch, no I/O. `:completed-at` is supplied by the
  caller (the events layer reads the host clock once at settlement and
  threads it in); this namespace never reads a clock. Trace summaries route
  every wire-bearing slot through the shared `re-frame.reply/trace-summary`
  (which calls `re-frame.elision/elide-wire-value`) — never a family-private
  elider (Managed-Effects §Tracing).

  ## Why there is no `target-obsolete?` gate here (rf2-wwfn7q)

  HTTP carries an `actor-destroy-target-obsolete?` predicate
  (`re-frame.http.reply`) that lowers a destroyed-actor reply to `:stale`/
  `:suppressed` (rather than `:cancelled`) when the reply TARGET names the
  destroyed actor. Resources and mutations have NO counterpart, **by
  design** — obsolete-target suppression is HTTP-specific. HTTP is the only
  EP-0011 surface that RE-DISPATCHES a reply at a caller-supplied event
  target (its `:on-failure` / origin-event head), so TARGET-identity
  obsolescence is only definable there.

  Resource / mutation reply targets are framework-INTERNAL
  (`:rf.resource.internal/*` / `:rf.mutation.internal/*`), never a caller-
  supplied app event, so obsolescence is gated on WORK-ID + GENERATION
  ENTRY-LIVENESS, not target-identity. `live-entry-for-reply`
  (`re-frame.resources.events`) verifies the live entry's `:current-work` ==
  the reply's `:work/id` AND its `:generation` == the reply's `:generation`;
  it returns nil for a cross-frame / stale / superseded / vanished reply,
  which is then suppressed through `stale-reply` (a destroyed / aborted
  attempt whose entry is still live lowers to `:cancelled` via
  `failure-reply`, and its public error target does NOT run). All surfaces
  still route through the shared `re-frame.reply/suppress` and spell
  `:cancelled` identically — uniform where it matters; the obsolescence
  DETERMINATION is correctly surface-specific."
  (:require [re-frame.reply :as reply]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Work kinds (Managed-Effects §Work-id correlation — the :work/kind column).
;; ---------------------------------------------------------------------------

(def work-kind-resource
  "The `:work/kind` for a resource read attempt (`:resource`). Per Spec 016
  §Frame work ledger / Managed-Effects §Work-id correlation."
  :resource)

(def work-kind-mutation
  "The `:work/kind` for a mutation write attempt (`:mutation`). Mutation
  work reuses the resource work-id head with a mutation-instance key; the
  ledger row distinguishes the writer with `:work/kind :mutation` (Managed-
  Effects §Work-id correlation, the Mutation row)."
  :mutation)

;; ---------------------------------------------------------------------------
;; The aborted-failure detector (Managed-Effects §Status taxonomy / §
;; Cancellation). An `:rf.http/aborted` failure envelope is a CANCELLATION,
;; not an `:error` — it lowers to `:status :cancelled`, never `:status
;; :error`. Mirrors `re-frame.http.reply`'s abort handling.
;; ---------------------------------------------------------------------------

(def ^:private http-aborted-kind
  "The managed-HTTP failure `:kind` for an intentional abort / cancellation
  (Spec 014). A failure envelope carrying this kind lowers to `:status
  :cancelled`, not `:status :error`."
  :rf.http/aborted)

(defn abort-failure?
  "True iff `error` is a managed-HTTP ABORT failure envelope (`{:kind
  :rf.http/aborted …}`) — an intentional cancellation that lowers to
  `:status :cancelled` (carrying the abort under `:error`), never a
  user-visible `:status :error`. Per Managed-Effects §Status taxonomy / §
  Cancellation."
  [error]
  (= http-aborted-kind (:kind error)))

;; ---------------------------------------------------------------------------
;; Transport-payload extractors (Spec 014 §Reply addressing). The managed-HTTP
;; transport APPENDS its CANONICAL reply envelope as the LAST arg of the
;; framework-internal reply event (rf2-ibksxg — one canonical dialect, no
;; `{:kind :success/:failure}` reshape):
;;   [:rf.*.internal/succeeded <verification-payload> {:status :ok    :value <data> …}]
;;   [:rf.*.internal/failed    <verification-payload> {:status :error :error <envelope> …}]
;; Both the resource read path (`events.cljc`) and the mutation write path
;; (`mutation_events.cljc`) lift arg 3 identically — they differ ONLY by the
;; inline DURABLE-LAYER spelling the direct-dispatch test shape falls back to
;; (`:data` for a resource entry, `:result` for a mutation instance). One
;; shared failure extractor + one fallback-key-parameterized success extractor
;; (rf2-366u0g) so the two surfaces never drift.
;; ---------------------------------------------------------------------------

(defn transport-success-value
  "Extract the decoded success value from a managed-HTTP success reply. The
  transport appends the canonical `{:status :ok :value <decoded> …}` envelope
  as `http-result` (arg 3); read its `:value`. Falls back to an inline value
  on the verification payload under `fallback-key` (the direct-dispatch test
  shape, no transport in the loop) — `:data` for a resource entry, `:result`
  for a mutation instance (kh9jz6 / EP-0007: `:value` is the reply-map
  spelling; `:data` / `:result` are the durable-layer spellings)."
  [verification-payload http-result fallback-key]
  (if (contains? http-result :value)
    (:value http-result)
    (get verification-payload fallback-key)))

(defn transport-failure-envelope
  "Extract the failure envelope from a managed-HTTP failure reply. The
  transport appends the canonical `{:status :error :error <:rf.http/*
  envelope> …}` map as `http-result` (arg 3, rf2-ibksxg); read its `:error`
  (the closed `:rf.http/*` failure shape, the same envelope the durable entry
  `:error` / `:refresh-error` carries — Spec 016 §Status semantics; an abort
  rides under `:error` on a `:status :cancelled` reply too). Falls back to an
  inline `:error` on the verification payload (the direct-dispatch test
  shape). Identical for the resource and mutation surfaces (rf2-366u0g)."
  [verification-payload http-result]
  (if (contains? http-result :error)
    (:error http-result)
    (:error verification-payload)))

;; ---------------------------------------------------------------------------
;; Canonical reply-map builders (Managed-Effects §The reply map / §Status
;; taxonomy). The verification payload the resource / mutation lowering
;; stamped supplies the identity / correlation facts; the transport's
;; success / failure outcome supplies the value / error. `:completed-at` is
;; supplied by the caller; this ns never reads a clock.
;; ---------------------------------------------------------------------------

(defn- base-reply
  "The correlation / identity facts every resource / mutation reply carries,
  independent of status: `:rf.reply/work-id`, `:rf.reply/work-kind`, `:rf.frame/id`,
  `:completed-at` (when supplied), and a `:correlation` map of the carried
  facts (`:scope` / `:generation` / `:rf.reply/resource-key` for a resource;
  `:mutation/id` / `:instance/id` / `:scope` / `:generation` for a
  mutation). Optional facts are omitted when absent rather than filled with
  nil sentinels (Managed-Effects §The reply map).

  `vp` is the runtime-owned verification payload the lowering stamped
  (`:work/id` / `:resource/key` / `:scope` / `:generation` / `:rf.frame/id`
  for a resource; `:work/id` / `:instance-id` / `:mutation-id` / `:scope`
  / `:generation` / `:rf.frame/id` for a mutation). `opts` carries the
  family `:work/kind` and the host `:completed-at`."
  [vp {:keys [work-kind completed-at]}]
  (let [wid (:work/id vp)
        ;; the correlation map carries the public / diagnostic identities
        ;; (scope, generation, resource-key or mutation+instance) — never a
        ;; second stale-suppression key (EP-0007: the work-id is the single
        ;; suppression identity; everything else is :correlation metadata).
        correlation (cond-> {}
                      (contains? vp :scope)        (assoc :scope (:scope vp))
                      (contains? vp :generation)   (assoc :generation (:generation vp))
                      (some? (:resource/key vp))   (assoc :rf.reply/resource-key (:resource/key vp))
                      (some? (:mutation-id vp))    (assoc :mutation/id (:mutation-id vp))
                      (some? (:instance-id vp))    (assoc :instance/id (:instance-id vp)))]
    (cond-> {:rf.reply/work-id     wid
             :rf.reply/work-kind   work-kind}
      (some? (:rf.frame/id vp)) (assoc :rf.frame/id (:rf.frame/id vp))
      (some? completed-at)      (assoc :completed-at completed-at)
      (seq correlation)         (assoc :correlation correlation))))

(defn success-reply
  "Build the canonical `:status :ok` reply map for a successful resource /
  mutation completion. `value` is the decoded result (EP-0007: the reply
  result is `:value`, NOT `:data` / `:result` — those are the durable
  entry / instance layer's spellings). `:rf.reply/work-status :completed`. Per
  Managed-Effects §The reply map / §Status taxonomy."
  [vp value opts]
  (assoc (base-reply vp opts)
         :status      :ok
         :rf.reply/work-status :completed
         :value       value))

(defn failure-reply
  "Build the canonical reply map for a failed / aborted resource / mutation
  completion. `error` is the closed-set `:rf.http/*` failure envelope the
  transport classified (the same envelope the durable entry `:error` /
  `:refresh-error` carries — Spec 016 §Status semantics).

  Status mapping (Managed-Effects §Status taxonomy):
   - `:rf.http/aborted` → `:status :cancelled` (`:rf.reply/work-status :cancelled`,
     `:cancelled? true`, `:rf.reply/cancel-reason`, the abort under `:error`) — an
     intentional cancellation is NOT a user-visible resource error;
   - everything else    → `:status :error` + `:rf.reply/work-status :failed`, the
     classified `:rf.http/*` envelope verbatim under `:error` (it already
     carries the family `:kind` the reply-map contract requires)."
  [vp error opts]
  (if (abort-failure? error)
    (assoc (base-reply vp opts)
           :status        :cancelled
           :rf.reply/work-status   :cancelled
           :cancelled?    true
           :rf.reply/cancel-reason (:reason error)
           :error         error)
    (assoc (base-reply vp opts)
           :status      :error
           :rf.reply/work-status :failed
           :error       error)))

(defn stale-reply
  "Build the canonical `:status :stale` reply map for a SUPERSEDED /
  vanished completion (the verification gate found no live entry / instance
  for this work-id + generation). Delegates to the shared
  `re-frame.reply/suppress` so the stale outcome, the `:rf.reply/work-status
  :suppressed` terminal, and the carried-vs-current trace facts are produced
  uniformly with every other family. The app reply target MUST NOT run; the
  ledger row reaches `:suppressed`; no user-visible state mutates beyond the
  framework-owned ledger / trace bookkeeping (Managed-Effects §Stale
  suppression).

  Returns the `re-frame.reply/suppress` outcome map
  `{:deliver? :reply :rf.reply/work-status :trace}`. `carried` / `current` are the
  data-only correlation gate maps (e.g. `{:work/id … :generation …}`);
  `extra` threads `:rf.reply/work-id` / `:rf.reply/work-kind` / `:rf.frame/id` /
  `:completed-at` / `:rf.reply/stale-reason` onto the stale reply."
  [{:keys [carried current extra]}]
  (reply/suppress nil carried current extra))

;; ---------------------------------------------------------------------------
;; Trace summary (Managed-Effects §Tracing). A data-only summary of the
;; canonical reply for a managed-async trace row, routing every wire-bearing
;; slot through the shared `re-frame.reply/trace-summary` → `re-frame.
;; elision/elide-wire-value` walker. Never a family-private elider.
;; ---------------------------------------------------------------------------

(defn trace-reply
  "Build a DATA-ONLY trace summary of a canonical resource / mutation reply
  map for a managed-async trace row. The wire-bearing slots (`:value`,
  `:error`, `:correlation`, `:meta`) elide through the single shared
  `re-frame.elision/elide-wire-value` walker (via
  `re-frame.reply/trace-summary`) — never a family-private elider (Managed-
  Effects §Tracing); the identity facts (`:status`, `:rf.reply/work-id`,
  `:rf.reply/work-kind`, `:rf.reply/work-status`, `:rf.frame/id`, `:completed-at`) ride
  verbatim. `opts` is forwarded to the elider (e.g. `:frame`)."
  ([reply] (trace-reply reply nil))
  ([reply opts] (reply/trace-summary reply opts)))
