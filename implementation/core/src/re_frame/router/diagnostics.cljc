(ns re-frame.router.diagnostics
  "Dev-only diagnostics for the router: cross-frame dispatch-sync warnings
  and the no-handler error path. A sibling of `re-frame.router`, holding
  the warning / error fns that the router facade reaches into.

  There is no `:rf/default` floor for a bare dispatch to slide onto: a
  dispatch under no established scope fails loudly at envelope-build time
  with `:rf.error/no-frame-context` (emitted from
  `re-frame.frame/require-current-frame!`) — an always-on,
  production-survivable error.

  Every fn here either runs on a cold/error path or sits behind
  `interop/debug-enabled?` — production builds (`:advanced` +
  `goog.DEBUG=false`) DCE the bodies and the keyword reason-strings.

  The cross-ns indirection from the router facade is amortised: callers
  (`process-event*`, `dispatch!`, `dispatch-sync!`) all sit on the
  facade and reach into this ns only on the rare warning / error paths."
  (:require [clojure.string :as str]
            [re-frame.cofx.value-check :as value-check]
            [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

(defn handle-no-handler!
  "Emit `:rf.error/no-such-handler` when a dispatched event has no
  registered handler on its (explicitly carried) target frame. The
  dispatch reached here only because its frame was resolved (an explicit
  `{:frame …}` override, an established scope, or a captured stamp) — a
  frameless bare dispatch never gets this far: it raised
  `:rf.error/no-frame-context` at envelope-build time (EP-0002 §Dispatch
  And Router).

  The `:rf.error/no-such-handler` category ALSO fans out through the
  always-on error-emit listener (surface #4) so
  it survives `:advanced` + `goog.DEBUG=false` and reaches off-box
  observability shippers — a dispatch to a never-registered handler is a
  production-meaningful runtime error. Recovery is the runtime's built-in
  `:replaced-with-default` (a no-op); there is no app-steering recovery
  policy. Reached via the `:error-emit/dispatch-on-error` late-bind hook
  (this diagnostics ns cannot static-require `re-frame.error-emit`
  without a load cycle). The `:frame`-stampable record carries the target
  `frame` + attempted `event` for 7d30s + shipper attribution.

  The dev `trace/emit-error!` below stays dev-only (it DCEs under
  `goog.DEBUG=false`); the always-on listener fan-out is what survives."
  [event-id event frame]
  ;; Both channels via the shared helper: axis 1 the always-on
  ;; listener (survives prod elision), axis 2 the dev trace (DCEs under
  ;; `:advanced` + `goog.DEBUG=false`). No exception — invalid op; `elapsed-ms 0`.
  ;; Reached via the `:error-emit/emit-error-both` hook (this diagnostics ns
  ;; cannot static-require error-emit — load cycle).
  (when-let [emit-error-both!
             (late-bind/get-fn-cached :error-emit/emit-error-both)]
    (emit-error-both!
      :rf.error/no-such-handler
      event
      event-id
      frame
      nil                                 ;; no exception — invalid op
      0                                   ;; elapsed-ms
      (interop/now-ms)                    ;; time
      {:rf.trace/event-id event-id
       :rf.event/v        event
       :frame             frame
       :kind              :event
       :recovery          :replaced-with-default})))

(defn other-frame-mid-drain
  "Per Spec 002 §dispatch-sync cross-frame note. Return the
  frame-id of any registered, non-destroyed frame OTHER than `target-id`
  whose router currently shows `:in-sync-drain?` or `:in-drain?` true.
  Returns nil when no such frame exists.

  Used by `re-frame.router/dispatch-sync!` to detect the cross-frame
  cascade pattern (frame A mid-drain, a handler calls
  `(rf/dispatch-sync! [...] {:frame :b})`). The same-frame case is
  already an error; the cross-frame case is intentional but surprising,
  so we surface it as
  `:rf.warning/cross-frame-dispatch-sync-during-drain` rather than
  refuse. Frames are independent state machines (per Spec 002 §Rules
  rule 1) and frame B's drain doesn't violate frame A's contract.

  Dev-only — the caller gates on `interop/debug-enabled?` to skip the
  registry walk in production."
  [target-id]
  (some (fn [id]
          (when (not= id target-id)
            (when-let [fr (frame/frame id)]
              (let [router-state @(:router fr)]
                (when (or (:in-sync-drain? router-state) (:in-drain? router-state))
                  id)))))
        (frame/frame-ids)))

(def ^:const known-dispatch-opts
  "The closed set of keys `build-envelope` reads off the
  `dispatch` / `dispatch-sync` opts map (and therefore the only keys that
  affect dispatch behaviour). Every other key is silently swallowed, so a
  typo'd opt (`:fram` for `:frame`, `:src` for `:source`) changes nothing
  and gives no signal — the no-silent-swallow principle
  forbids that quietness. `emit-unknown-dispatch-opts-warning!` warns on
  any opts key outside this set.

  The set mirrors the reads in `re-frame.router/build-envelope`:
    :frame                  resolved target frame
    :fx-overrides           per-call fx-id remapping
    :interceptor-overrides  per-call interceptor remapping
    :trace-id               tooling correlation id
    :source                 closed-enum trigger-kind / functional-origin
    :source-detail          per-source-kind detail payload
    :origin                 actor identity tag
    :rf.cofx                EP-0017 flat recordable-coeffect map
                            (caller-supplied `:rf/time-ms` + owner-qualified
                            facts; the router fills `:rf/time-ms` when absent)
    :rf.cofx/mint-policy    EP-0017 §6 / slice-B.8 per-call cofx mint policy
                            (`:live` / `:strict` / `:explicit-live`); the
                            most-specific binding point — a Tool-Pair replay
                            supplies `:strict`, a nondeterminism-declaring test
                            supplies `:explicit-live`. Absent ⇒ the frame
                            config's policy, else the router's `:live` default
    :rf.trace/call-site     macro-stamped invocation coord (dev-only)
    :rf.machine/internal?   machine-internal continuation flag (front-queue)
    :step-index             frame-construction setup-step index, stamped by
                            `frame.cljc`'s `run-setup-events!` onto each
                            `:initial-events` dispatch (EP-0027); read by
                            `build-envelope` and carried onto the dispatched
                            trace as `:rf.frame/init-step-index`"
  #{:frame :fx-overrides :interceptor-overrides :trace-id :source
    :source-detail :origin :rf.cofx :rf.cofx/mint-policy :rf.trace/call-site
    :rf.machine/internal? :step-index})

(def ^:const retired-draft-opt-hints
  "Dispatch-opt keys that named a fact only ever spelled in the spec's own
  DRAFTS (never shipped in a released artefact), mapped to a targeted
  did-you-mean hint. A retired DRAFT name earns NO dedicated retired-name
  error id — that privilege is reserved for names that SHIPPED and so carry
  wide training-data exposure (per [Conventions §The tombstone rule], the
  shipped-names-only rule). A draft-era key is therefore handled by the
  SAME generic closed-key surface as any other unrecognised opt
  (`emit-unknown-dispatch-opts-warning!`), but with a specific replacement
  suggestion appended so the fix stays actionable:

    :rf.world/inputs → :rf.cofx  (EP-0017 renamed the recordable-coeffect
                                  envelope field; the flat `fact-name → value`
                                  map is now supplied under `:rf.cofx`)
    :dispatched-at   → the durable causal-time fact
                       `(:rf/time-ms (:rf.cofx envelope))` (EP-0010 —
                       there is no caller-supplied dispatch-time field)

  Dev-only, like the warning it feeds: the whole map DCEs under `:advanced` +
  `goog.DEBUG=false` (only reached inside `emit-unknown-dispatch-opts-warning!`,
  itself gated on `interop/debug-enabled?`)."
  {:rf.world/inputs
   "did you mean `:rf.cofx`? (EP-0017 renamed the recordable-coeffect envelope field — supply the flat `fact-name → value` map under `:rf.cofx`; the framework time fact is `:rf/time-ms`)"
   :dispatched-at
   "there is no caller-supplied dispatch-time opt — the durable causal-time fact is `(:rf/time-ms (:rf.cofx envelope))`, stamped by the framework; for a diagnostic dispatch-time read the trace event's own `:time` stamp (Spec 009)"})

(defn- validate-supplied-cofx-values!
  "ALWAYS-ON structural-EDN check of a SUPPLIED `:rf.cofx` map's values.
  Every value other than
  the framework's `:rf/time-ms` fact rides the durable causal record, so each
  MUST be recordable EDN data (EP-0017:386). The first non-recordable value
  throws `:rf.error/cofx-value-invalid` (reason `:non-edn-recordable-value`).

  ALWAYS-ON — NOT gated on `interop/debug-enabled?` (EP-0017 §3 / §5 / Open
  Issue 9 — structural EDN always, hard errors in production as well as dev):
  a supplied recordable value that is a host handle folds a non-EDN value into
  the durable causal record (epoch ledger, replay, SSR payload, Xray) — corrupt
  durable state, not a dev nicety, the same `:dispatched-at` causal-token
  precedent the map-shape / `:rf/time-ms` checks above already enforce
  always-on. The walk runs only when a `:rf.cofx` was supplied (no allocation
  on the override-free hot path), and the recordable predicate short-circuits
  at the first non-EDN leaf. The declared-`:schema` check (cofx.cljc) is the
  complementary always-on per-supplier contract; this is the structural
  always-EDN floor that fires even when no `:schema` was declared.

  The `:supplied` arm of the shared `value-check/check-edn-value!`
  — its generated-value twin (slice B) lives at the generator write-back site
  (`re-frame.cofx`). The always-on listener carries the triggering `event` (the
  supplied path runs at the dispatch boundary, before any frame is owned)."
  [supplied event]
  (let [event-id (first event)]
    (reduce-kv
      (fn [_ cofx-id value]
        ;; `:rf/time-ms` is already shape-checked above (integer); skip it.
        (when-not (= cofx-id :rf/time-ms)
          (value-check/check-edn-value! :supplied cofx-id value event-id event nil))
        nil)
      nil
      supplied)))

(defn validate-cofx!
  "Throw `:rf.error/invalid-cofx` when a caller-supplied `:rf.cofx` is
  structurally malformed at the PUBLIC dispatch boundary.

  `:rf.cofx` is the durable causal token (the flat
  recordable-coeffect map): its `:rf/time-ms` is the one host-clock fact
  durable writes fold (Spec 002 §Recordable coeffects), and replay / restore
  / SSR-hydration feed it verbatim. So a malformed token is not a harmless
  typo — a non-map supplied value, or a non-integer `:rf/time-ms`, propagates
  straight into durable-write code (the epoch record's `:committed-at`,
  resource `:settled-at`, …) and makes the fold dishonest. The pair-tool
  already validates its own wire shape, but that does NOT protect ordinary
  public `dispatch` / `dispatch-sync`; a single central validator at the
  dispatch boundary is simpler to reason about than relying on every caller /
  tool to validate.

  The contract MIRRORS the `:rf.cofx` schema (Spec-Schemas.md §:rf.cofx):

    - a supplied `:rf.cofx` MUST be nil or a MAP (`nil` ⇒ the router
      stamps a fresh map — the ordinary live-dispatch path);
    - a supplied `:rf/time-ms` MUST be an integer (wall-clock epoch ms — the
      framework's lone provided-on-the-envelope fact).

  Other facts (app-owned, subsystem-qualified) pass the cheap MAP-SHAPE gate
  here, then — ALWAYS-ON (per EP-0017 Open Issue 9 — structural EDN always,
  hard error in production too) —
  each value is walked by `validate-supplied-cofx-values!` to confirm it is
  recordable EDN data (EP-0017:386): a host handle (DOM node, Promise, function,
  atom, Date, JS / Java object) supplied as a recordable coeffect throws
  `:rf.error/cofx-value-invalid` (reason `:non-edn-recordable-value`) in dev AND
  production. A narrower per-supplier `:schema` gate (cofx.cljc satisfaction
  step, the complementary always-on production contract) is the deeper check
  that follows; this is the structural always-EDN floor that catches the author
  error at the dispatch source.

  ALWAYS-ON — NOT gated on `interop/debug-enabled?`: like
  `reject-retired-dispatch-opts!`, a corrupt causal token is a correctness
  contract that must fail fast in `:advanced` + `goog.DEBUG=false`
  production too (a non-integer `:rf/time-ms` folded into a durable timestamp
  is a production data-integrity bug, not a dev nicety). The cost is a
  `contains?` + a `map?` / `int?` check on the dispatch path — no allocation
  unless the caller supplied a `:rf.cofx`.

  Checked at the causal boundary in `re-frame.router/build-envelope` BEFORE
  `ensure-cofx` stamps `:rf/time-ms`, so an invalid token fails fast WITHOUT
  first reading the clock for a dispatch that cannot proceed (the same
  fail-before-clock-read ordering as the retirement check). Per Spec 002
  §Recordable coeffects + EP-0010 §Time + EP-0017 §The envelope field."
  [opts event]
  (when (contains? opts :rf.cofx)
    (let [supplied (:rf.cofx opts)]
      (when-not (or (nil? supplied) (map? supplied))
        (error/throw-error!
          :rf.error/invalid-cofx 're-frame.router/build-envelope
          (str ":rf.cofx must be nil or a MAP "
               "of recordable coeffect facts (Spec 002 "
               "§Recordable coeffects), got "
               (pr-str supplied)
               ". The map's `:rf/time-ms` (wall-clock "
               "epoch ms) is the durable causal token "
               "durable writes fold; a non-map value "
               "cannot carry it.")
          {:extra {:event-id (first event)
                   :event    event
                   :supplied supplied}}))
      (when (and (map? supplied)
                 (contains? supplied :rf/time-ms)
                 (not (int? (:rf/time-ms supplied))))
        (error/throw-error!
          :rf.error/invalid-cofx 're-frame.router/build-envelope
          (str ":rf.cofx :rf/time-ms must be an "
               "INTEGER (wall-clock epoch milliseconds "
               "— Spec-Schemas.md §:rf.cofx), "
               "got " (pr-str (:rf/time-ms supplied))
               ". This value becomes the durable "
               "causal time the frame fold reads "
               "(epoch `:committed-at`, resource "
               "`:settled-at`, …); a non-integer "
               "corrupts the durable fold and breaks "
               "replay determinism.")
          {:extra {:event-id   (first event)
                   :event      event
                   :rf/time-ms (:rf/time-ms supplied)}}))
      ;; Structural-EDN-ALWAYS check of the
      ;; SUPPLIED values, AFTER the map-shape + `:rf/time-ms` shape checks above
      ;; and BEFORE the per-supplier `:schema` validation (cofx.cljc,
      ;; satisfaction step). ALWAYS-ON (EP-0017 Open Issue 9 — production hard
      ;; error too): a non-EDN recordable value (a host handle) throws
      ;; `:rf.error/cofx-value-invalid` / `:non-edn-recordable-value`. Only
      ;; runs when `supplied` is a map (the non-map case already threw above).
      (when (map? supplied)
        (validate-supplied-cofx-values! supplied event)))))

(defn unknown-dispatch-opts
  "Return the seq of keys in `opts` that fall OUTSIDE
  `known-dispatch-opts`, or nil when every key is known (so callers can
  `when-let` the result). Dev-only — the sole caller gates on
  `interop/debug-enabled?`."
  [opts]
  (when interop/debug-enabled?
    (seq (remove known-dispatch-opts (keys opts)))))

(defn emit-unknown-dispatch-opts-warning!
  "Emit `:rf.warning/unknown-dispatch-opt` when a
  `dispatch` / `dispatch-sync` opts map carries one or more keys outside
  the recognised `known-dispatch-opts` set. The runtime reads only the
  known keys in `build-envelope`; an unrecognised key (almost always a
  typo — `:fram` instead of `:frame`) is otherwise silently swallowed and
  changes nothing, producing wrong behaviour with no signal. Pre-alpha
  posture: surface it loudly rather than ship a quiet footgun (aligns with
  the no-silent-swallow principle).

  One warning per dispatch call carrying unknown keys: the message names
  every bad key and the full known set so the fix is obvious. The dispatch
  proceeds unchanged — this is observational, never refusal (`:recovery
  :no-recovery`).

  A key that named a RETIRED DRAFT fact (`retired-draft-opt-hints` —
  `:rf.world/inputs`, `:dispatched-at`) rides this same generic surface (it
  earns no dedicated retired-name error id per the shipped-names-only
  tombstone rule, [Conventions §The tombstone rule]) but the reason string
  appends a specific did-you-mean naming the canonical replacement, so the
  fix stays as actionable as a bespoke error would have made it.

  Body gated on `interop/debug-enabled?` so the whole surface — the
  warning keyword's interned slot, the reason-string allocation, the
  `unknown-dispatch-opts` walk — DCEs wholesale under `:advanced` +
  `goog.DEBUG=false`. The caller in `build-envelope` reads the
  unknown-key seq inside the same gate so production never walks the opts."
  [unknown event]
  (when interop/debug-enabled?
    (let [event-id (first event)
          unknown  (vec unknown)
          hints    (into []
                         (keep (fn [k]
                                 (when-let [h (retired-draft-opt-hints k)]
                                   (str "`" k "` — " h))))
                         unknown)
          reason   (str "Dispatch of `" event-id "` was given unrecognised "
                        "opts key" (when (> (count unknown) 1) "s") " "
                        (pr-str unknown) ". The runtime reads only "
                        (pr-str (vec (sort known-dispatch-opts)))
                        " — any other key is silently ignored, so a typo "
                        "(e.g. `:fram` for `:frame`) changes nothing and "
                        "gives no signal. Check for a misspelt opt; if you "
                        "intended a custom payload, put it inside the event "
                        "vector, not the dispatch opts map."
                        (when (seq hints)
                          (str " " (str/join " " hints))))]
      (trace/emit! :warning
                   :rf.warning/unknown-dispatch-opt
                   {:event        event
                    :event-id     event-id
                    :unknown-keys unknown
                    :known-keys   (vec (sort known-dispatch-opts))
                    :detected-at  (interop/now-ms)
                    :reason       reason
                    :recovery     :no-recovery}))))

(defn emit-cross-frame-warning!
  "Emit `:rf.warning/cross-frame-dispatch-sync-during-drain`
  when `dispatch-sync!` lands on frame `target-id` while a different
  frame (`other-id`) is mid-drain. The caller frame is read from
  `frame/*current-frame*`; when unbound (no frame context — e.g. a
  process-level REPL caller threading the dispatch through some unusual
  path) the field is `:rf/none`.

  The cross-frame case is intentional but surprising: warn, do not refuse.
  Continues with the dispatch."
  [target-id other-id event]
  (let [caller-id (or frame/*current-frame* :rf/none)
        reason    (str "dispatch-sync! against `" target-id "` while frame `"
                       other-id "` is mid-drain. The two cascades will "
                       "interleave: `" target-id "`'s drain runs to settled "
                       "while `" other-id "` is still in flight, then `"
                       other-id "` continues. Frames are independent state "
                       "machines so this does not violate either frame's "
                       "contract (per Spec 002 §Run-to-completion §Rules "
                       "rule 1 — no cross-frame drain), but the interleaved "
                       "ordering is rarely the caller's intent. If the goal "
                       "is fire-and-forget cross-frame coordination, prefer "
                       "the async form `(rf/dispatch event {:frame other})` "
                       "— it queues on the target frame's router and drains "
                       "on a later cycle, after the caller's cascade settles.")]
    (trace/emit! :warning
                 :rf.warning/cross-frame-dispatch-sync-during-drain
                 {:caller-frame caller-id
                  :target-frame target-id
                  :other-frame  other-id
                  :event        event
                  :reason       reason
                  :recovery     :no-recovery})))
