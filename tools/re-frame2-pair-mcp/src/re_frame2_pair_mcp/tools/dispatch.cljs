(ns re-frame2-pair-mcp.tools.dispatch
  "Tool: dispatch — fire an event.

  ## Why parse the `event` arg as EDN (rf2-vflrg)

  The MCP `dispatch` surface is intentionally narrower than `eval-cljs`:
  the contract is `dispatch {event '[:ev/id ...]'}` — an EDN event
  vector, nothing else. An earlier shape inlined the caller's string
  verbatim into the generated eval form via `rt-raw`, which made the
  string a host-form expression rather than data. Any caller (or a
  prompt-injected agent) could supply arbitrary CLJS — `(println :pwn)`
  would have run — defeating the boundary that gates `eval-cljs`
  separately. Pre-alpha posture: this is gating the accident
  (\"I meant to dispatch an event, not execute code\") and the trivial
  malicious-string injection.

  Parsing the arg as EDN and requiring a `vector?` shape forces the
  payload to be data; it is then emitted into the runtime call through
  the normal `pr-str` path (`rt-call` arg). Unreadable strings and
  non-vector shapes return a structured `:reason :invalid-event-edn` /
  `:reason :not-an-event-vector` error rather than reaching the
  runtime.

  ## Render-settle — `:await-render` (rf2-gfu33)

  `pair-dispatch-sync!` returns once the handler has committed app-db,
  but the substrate (Reagent / the React spine) re-renders on a LATER
  tick — so \"dispatch then observe the DOM\" previously needed a manual
  `requestAnimationFrame` dance inside `eval-cljs`. The `:await-render`
  option makes `dispatch → observe` one deterministic step: the tool
  resolves only AFTER the substrate has flushed the new state to the
  DOM and the next paint has been scheduled.

  ### Substrate-agnostic flush via the adapter contract

  The flush is NOT a Reagent API call. The generated runtime form calls
  `re-frame.interop/after-render` — the framework's render-settle
  primitive, which routes through the `:adapter/after-render` late-bind
  hook (Spec 006 §Substrate adapter contract). Each adapter publishes
  its substrate-native impl: Reagent maps it to `r/after-render`
  (post-commit), the UIx / Helix spine to a `React.useLayoutEffect`-
  backed queue drain (post-commit / pre-paint, rf2-334d9), plain-atom /
  SSR to `next-tick`. `after-render` fires once the DOM reflects the new
  state; the form then chains ONE `requestAnimationFrame` so resolution
  lands at the paint boundary. The MCP server therefore stays
  substrate-agnostic — it never names Reagent, UIx, or Helix.

  ### Wire shape

  `:await-render` forces synchronous dispatch (the cascade must have
  committed before we can settle the render against the new state), so
  the result is the `pair-dispatch-sync!` envelope (`:cascade-summary`
  and friends) with `:mode :sync :settled? true` merged in. The form
  returns a browser-side Promise; the server awaits it through the
  shared `await-promise` mailbox (the same plumbing `eval-cljs :await`
  uses). A render-settle that doesn't complete within `:timeout-ms`
  (default 5000) returns `:reason :rf.error/dispatch-await-render-timeout`.

  ## Dispatch-and-settle — `:settle` (rf2-vk79g)

  `:settle true` closes the observe→drive loop SYNCHRONOUSLY and returns
  the COMPLETE epoch in ONE call: dispatch → flush renders → return the
  settled epoch INCLUDING its `:rf.view/render` + `:rf.view/unmounted`
  entries. It routes to the runtime `dispatch-and-settle!`, which:
  dispatch-sync → `(re-frame.substrate.adapter/flush-render!)` (the
  SYNCHRONOUS render-commit contract fn — React `flushSync` /
  `reagent.core/flush`, resolved from the INSTALLED adapter, ZERO
  substrate hardcoding) → re-read the epoch (now carrying the
  back-filled renders/unmounts).

  ### `:settle` vs `:await-render`

  Both make `dispatch → observe` one step; they differ in flush
  primitive and transport:

  - `:await-render` flushes via `re-frame.interop/after-render` (the
    rAF-scheduled, post-commit / pre-paint hook) and is ASYNC — the form
    returns a Promise the server awaits through the mailbox. On a
    backgrounded tab the underlying React lane commit is rAF-throttled,
    so it can stall. It resolves to the dispatch CONSEQUENCE envelope
    (no full epoch).

  - `:settle` flushes via `flush-render!` (`flushSync`) — NOT
    rAF-scheduled, so it commits even headless / backgrounded, and is
    fully SYNCHRONOUS. No Promise, no mailbox: the runtime form returns
    the settled epoch directly. The result carries the assembled
    `:epoch` plus a `:render-events` slot (the `:rf.view/render` +
    `:rf.view/unmounted` entries folded into it) — the headline
    view-lifecycle signal. SCOPE: async fx (http / timers) stay observed
    via `watch-epochs`; `:settle` only settles the SYNCHRONOUS cascade +
    render flush.

  Both force synchronous dispatch. `:settle` wins over `:await-render` /
  `:trace` / `:queued` when set (it is the most complete single-call
  shape).

  ## Epoch-egress privacy — boot-gate signal + projection (rf2-8fin7.3)

  `dispatch` egresses epoch-derived sensitive payloads on THREE paths,
  all governed by the `--allow-sensitive-reads` boot gate
  (`raw-state/raw-state-allowed?`, OFF by default):

    1. `:trace` (`dispatch-and-collect`) returns the raw `:epoch`.
    2. `:settle` (`dispatch-and-settle!`) returns the raw settled
       `:epoch` (+ `:render-events`).
    3. The DEFAULT (any mode) cascade-summary's `:event-vector` copies
       the epoch's raw `:trigger-event` (e.g.
       `[:auth/sign-in {:password \"…\"}]`).

  Paths 1 + 2 route the result's epoch slots through the framework's
  off-box projection (`epoch_egress/project-dispatch-result-src` →
  `re-frame.core/projected-record`) APP-SIDE before the result crosses
  the wire, gated on `raw-state-allowed?` + the `:include-sensitive`
  two-key opt-in (rf2-olvr5 finding 1) — sensitive leaves land as
  `:rf/redacted`, large slots elide, the cascade stays structurally
  useful.

  Path 3 is closed by issuing `raw-state/signal-runtime!` between the
  preload probe and the dispatch eval (the snapshot / get-path /
  dispatch-dry-run prelude shape). The runtime's cascade-summary
  redacts the `:event-vector` only when `(not (:allow-raw-state?
  @raw-state-config))`, and `raw-state-config` DEFAULTS to
  `{:allow-raw-state? true}` — it flips to the server's gate state ONLY
  when a tool signals. `dispatch` was the lone state-emitting tool that
  never signalled, so a FIRST-in-session sensitive dispatch ran with
  the runtime still permissive and shipped its raw event vector even
  under the OFF gate. The signal closes that. Posture parity with
  `dispatch-dry-run` (signal-runtime! + projected egress, same gate)."
  (:require [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.await-promise :as await-promise]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.epoch-egress :as egress]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(defn- parse-event-edn
  "Parse the `event` MCP arg as EDN. Returns
  `[:ok parsed-vector]` on success or `[:err err-map]` on any failure.
  Two failure modes carry distinct reasons:

  - `:invalid-event-edn`     — `read-string` threw / returned nil.
  - `:not-an-event-vector`   — parsed cleanly but the shape is wrong
                                (e.g. a map, a symbol, a bare keyword).

  The hint repeats the documented usage shape so an agent that
  fat-fingers the call gets a corrective example without an extra
  round-trip."
  [event-str]
  (let [trimmed (some-> event-str str/trim)]
    (cond
      (or (nil? trimmed) (str/blank? trimmed))
      [:err {:ok? false :reason :missing-event
             :hint "usage: dispatch {event '[:ev/id ...]' [sync true] [trace true] [frame :foo] [fx-overrides {...}]}"}]

      :else
      (let [parsed (try
                     (cljs.reader/read-string trimmed)
                     (catch :default e
                       ::reader-fail))]
        (cond
          (= ::reader-fail parsed)
          [:err {:ok? false :reason :invalid-event-edn
                 :event event-str
                 :hint "event must be an EDN-readable vector, e.g. \"[:cart/checkout]\""}]

          (not (vector? parsed))
          [:err {:ok? false :reason :not-an-event-vector
                 :event event-str
                 :parsed-type (cond
                                (map? parsed)      :map
                                (keyword? parsed)  :keyword
                                (symbol? parsed)   :symbol
                                (sequential? parsed) :list
                                :else              :scalar)
                 :hint "event must be a vector, e.g. \"[:cart/checkout {:reason :user}]\""}]

          :else
          [:ok parsed])))))

(defn- runtime-envelope->result
  "Translate the runtime dispatch fn's return into the wire envelope.

  rf2-ldfnx — the runtime (`pair-dispatch!` / `pair-dispatch-sync!` /
  `dispatch-and-collect`) returns a structured envelope. On real
  success it carries `:ok? true` (sync/trace) or `:queued? true`
  (queued) plus the cascade slots. On a frame-targeting failure it
  carries `:ok? false` with a `:reason` (`:no-new-epoch`,
  `:no-epoch-recorded`, …) — the dispatch did NOT land.

  The pre-fix shape merged `{:mode <m>}` over whatever the runtime
  returned and ALWAYS emitted a success (`ok-text`) envelope. A
  frame-targeted dispatch that no-op'd (the runtime reporting
  `:ok? false`) therefore rode back as `{:mode :sync}` with no
  `:isError` flag — a silent wrong-success. The `:mode` slot is the
  caller's signal that the dispatch took effect, so it MUST appear
  only on a genuine landing.

  Contract:
    - runtime `:ok? false`  ⇒ `err-text` (`:isError true`), NO `:mode`
      slot. The failure rides through verbatim so the caller sees the
      structured `:reason`/`:hint`. This covers the rf2-3bu3d.3
      `:reason :unknown-id` validation miss too — an unknown event-id
      surfaces as an error envelope carrying `:nearest` matches, NEVER
      a silent success.
    - queued mode whose cascade hasn't drained yet
      (`:cascade-summary-pending? true`) ⇒ `ok-text` with `:settled?
      false` merged in (rf2-3bu3d.2) so the agent knows to
      `watch-epochs` the eventual settlement.
    - otherwise (success / settled queued / non-map degraded runtime) ⇒
      `ok-text` with `{:mode <m>}` merged in."
  [mode v]
  (if (and (map? v) (false? (:ok? v)))
    (wire/err-text v)
    (let [base (merge {:mode mode} (when (map? v) v))
          ;; rf2-3bu3d.2 — async/queued that hasn't settled rides back
          ;; with `:settled? false` so the consequence-vs-ack distinction
          ;; is explicit (the default sync path carries the consequence).
          base (cond-> base
                 (and (= :queued mode)
                      (map? v)
                      (:cascade-summary-pending? v))
                 (assoc :settled? false))]
      (wire/ok-text base))))

;; ---------------------------------------------------------------------------
;; Render-settle — `:await-render` (rf2-gfu33).
;;
;; The settle form runs the dispatch synchronously (so app-db has
;; committed and the substrate has been invalidated), then returns a
;; browser-side Promise that resolves AFTER the substrate's render
;; flush AND the next paint. The flush is the framework's
;; `re-frame.interop/after-render` — a substrate-agnostic primitive
;; routed through the `:adapter/after-render` late-bind hook (Spec 006).
;; A single `requestAnimationFrame` after the flush pins resolution to
;; the paint boundary; environments without rAF (headless / SSR) resolve
;; straight off the after-render callback.
;;
;; The Promise resolves to the dispatch envelope with `:settled? true`
;; merged in, so the caller gets the full `pair-dispatch-sync!` result
;; (`:cascade-summary`, `:epoch-id`, …) AND the settle confirmation in
;; one round-trip. The server awaits the Promise via the shared
;; `await-promise` mailbox.
;; ---------------------------------------------------------------------------

(defn- await-render-callbacks
  "The dispatch `:await-render` result vocabulary for
  `await-promise/handle-sentinel`. On resolution the value IS the
  dispatch envelope (the `pair-dispatch-sync!` / `dispatch-and-collect`
  return) with `:settled? true` already merged in by the settle form —
  route it through `runtime-envelope->result` so a frame-targeting
  failure (`:ok? false`) still surfaces as an `:isError` envelope, never
  a silent `{:mode :sync}` success (the rf2-ldfnx invariant). Timeout /
  malformed-sentinel surface as structured dispatch errors."
  [mode]
  {:on-resolved (fn [{:keys [value]}] (runtime-envelope->result mode value))
   :on-rejected (fn [{:keys [rejection]}]
                  (wire/err-text {:ok?       false
                                  :reason    :rf.error/dispatch-await-render-rejected
                                  :rejection rejection}))
   :on-timeout  (fn [{:keys [timeout-ms]}]
                  (wire/err-text {:ok?        false
                                  :reason     :rf.error/dispatch-await-render-timeout
                                  :timeout-ms timeout-ms
                                  :hint       (str "the render-settle promise did not resolve within "
                                                   "timeout-ms. The dispatch may still have committed; "
                                                   "the substrate's after-render flush never fired (no "
                                                   "mounted root?) or the page reloaded mid-settle.")}))
   :on-missing  (fn [{:keys [reason sentinel]}]
                  (wire/err-text (cond-> {:ok?    false
                                          :reason :rf.error/dispatch-await-render-mailbox-missing
                                          :hint   "the render-settle mailbox vanished before the result was read."}
                                   (= :bad-sentinel reason) (assoc :sentinel sentinel))))})

(defn- render-settle-form
  "Build the CLJS source for an `:await-render` dispatch. `fn-sym` is the
  runtime dispatch fn (always a synchronous variant under await-render);
  `event-vec` + `opts-form` are the dispatch payload. Emits a form whose
  synchronous return is a `js/Promise` resolving to the dispatch envelope
  merged with `{:settled? true}` once the substrate has flushed + the
  next paint is scheduled."
  [fn-sym event-vec opts-form]
  (str
    "(js/Promise."
    "  (fn [resolve# _reject#]"
    "    (let [result# " (ef/emit (ef/rt-call fn-sym event-vec opts-form))
    "          done#   (fn [] (resolve# (if (map? result#)"
    "                                     (assoc result# :settled? true)"
    "                                     {:settled? true :result result#})))]"
    "      (re-frame.interop/after-render"
    "        (fn []"
    "          (if (exists? js/requestAnimationFrame)"
    "            (js/requestAnimationFrame (fn [_#] (done#)))"
    "            (done#)))))))"))

(defn dispatch-tool [conn args]
  (let [event-str    (wire/arg args :event)
        build-id     (wire/arg-build conn args)
        ;; rf2-vk79g: `:settle` is the most complete single-call shape —
        ;; dispatch → SYNCHRONOUS flush-render! → return the settled epoch
        ;; WITH its `:rf.view/render` / `:rf.view/unmounted` entries. It
        ;; routes to the runtime `dispatch-and-settle!` (fully synchronous
        ;; — no Promise / mailbox), so it wins over every other mode flag.
        settle?      (boolean (wire/arg args :settle))
        await-render? (boolean (and (wire/arg args :await-render) (not settle?)))
        ;; rf2-wz66k7 — validate `:timeout-ms` as a positive-millisecond
        ;; integer BEFORE it threads into `await-promise/poll-mailbox!`'s
        ;; `(>= elapsed timeout-ms)` deadline (the `:await-render` path).
        ;; A non-numeric value (`"never"`) makes `(>= n NaN)` never true ⇒
        ;; the render-settle mailbox poll loop runs FOREVER; a zero /
        ;; negative value times out immediately. Absent ⇒ the documented
        ;; default. Bad value short-circuits to an honest isError below.
        timeout-r    (args/parse-timeout-arg "timeout-ms" (wire/arg args :timeout-ms))
        timeout-ms   (or (second timeout-r) await-promise/default-timeout-ms)
        ;; rf2-gfu33: `:await-render` forces synchronous dispatch — the
        ;; cascade must have committed before the render can settle
        ;; against the new state. `:settle` likewise forces sync. An
        ;; explicit `:trace` still wins for the non-settle modes (the
        ;; caller asked for the assembled epoch); otherwise sync.
        sync?        (boolean (or (wire/arg args :sync) await-render? settle?))
        trace?       (boolean (and (wire/arg args :trace) (not settle?)))
        ;; rf2-3bu3d.2 — `:queued true` opts INTO the async transport-ack
        ;; shape (`{:settled? false}`). Without it the default is the
        ;; SYNC CONSEQUENCE (`dispatch-consequence!`) so a no-op is
        ;; VISIBLE (`:db-changed? false :effects-fired []`) instead of a
        ;; fake ack. `:trace` / `:sync` / `:await-render` still win.
        queued?      (boolean (and (wire/arg args :queued)
                                   (not trace?) (not sync?) (not settle?)))
        ;; rf2-ldfnx — coerce `frame` via the colon-tolerant
        ;; `->frame-keyword` (the shared `fresh-keyword` path), NOT the
        ;; raw `(keyword ...)` of `wire/arg-keyword`. The documented
        ;; `frame` arg is the colon-prefixed id (`":rf/xray"`, `":foo"`
        ;; — Tool-Catalogue §Id representation, rf2-cg37y). Raw
        ;; `(keyword ":rf/xray")` mints the MALFORMED `::rf/xray`
        ;; (namespace literally `":rf"`), which matches no registered
        ;; frame — the runtime then no-op'd while the tool reported
        ;; `{:mode :sync}`. `->frame-keyword` strips the leading colon
        ;; so the frame routes the same way `eval-cljs` / `snapshot` /
        ;; `replace-app-db` already route it.
        frame        (some-> (wire/arg args :frame) args/->frame-keyword)
        ;; rf2-hf7m9j — coerce override VALUES too. A plain
        ;; `(js->clj o :keywordize-keys true)` keywordizes object keys
        ;; only, leaving a documented `{":http": ":stub-http"}` target as
        ;; the string `":stub-http"`. Core's `resolve-fx-with-overrides`
        ;; honours only keyword / fn / nil targets and SILENTLY falls a
        ;; string through to the original fx — the real http/navigate
        ;; effect fires despite the recipe saying it was stubbed.
        ;; `parse-fx-overrides` coerces colon-prefixed strings to keywords
        ;; and REJECTS any other value with a structured error.
        fx-r         (args/parse-fx-overrides (wire/arg args :fx-overrides))
        fx-overrides (when (= :ok (first fx-r)) (second fx-r))
        ;; rf2-q6s1nb / EP-0010 + EP-0017 — caller-scripted recordable
        ;; coeffects. The agent supplies `cofx "{:rf/time-ms …}"` (EDN data,
        ;; same data-not-source gate as `event`); the router PRESERVES the
        ;; supplied map verbatim and stamps only the framework-required
        ;; `:rf/time-ms` when absent, so a dispatched event carries a scripted
        ;; causal time / owner-qualified recordable facts and the resulting
        ;; state is REPRODUCIBLE (the agent-replay-determinism affordance). A
        ;; malformed value short-circuits to an honest isError below rather
        ;; than threading a value the runtime's `:rf/dispatch-opts` validation
        ;; would reject deep in the eval.
        cofx-r       (args/parse-cofx (wire/arg args :cofx))
        cofx         (when (= :ok (first cofx-r)) (second cofx-r))
        ;; rf2-v52xsr · EP-0017 §6 / Tool-Pair §Replay — the named STRICT
        ;; REPLAY affordance. `cofx` alone is the LIVE scripted-coeffect path
        ;; (the router's `:live` default — a declared-but-absent
        ;; generator-backed fact is freshly MINTED). Replay is the other
        ;; gesture: re-drive a recorded event by re-presenting its recorded
        ;; `:rf.cofx` UNDER `:rf.cofx/mint-policy :strict`, so a recorded fact
        ;; MISSING from the token fails LOUDLY
        ;; (`:rf.error/missing-required-cofx` — no generator runs, no host
        ;; read) instead of silently re-minting a divergent value. Per
        ;; Tool-Pair, a pair tool that re-dispatches a recorded event ALWAYS
        ;; pairs the recorded cofx with strict; the strictness is a property
        ;; of the replay gesture, and the per-call opt is the most-specific
        ;; binding point so it wins over the (possibly `:live`) frame config.
        ;; `replay` is independent of a supplied `cofx`: a record with no
        ;; scripted facts still re-drives strict so any declared recordable
        ;; fact absent from the (empty) record halts. Bare colon-tolerant
        ;; boolean — no value parse needed.
        replay?      (boolean (wire/arg args :replay))
        ;; rf2-olvr5 finding 1 — `:trace` (`dispatch-and-collect`) and
        ;; `:settle` (`dispatch-and-settle!`) return RAW epoch material
        ;; (`:epoch` carrying `:db-before`/`:db-after`/`:trigger-event`/
        ;; `:trace-events`; `:settle` also `:render-events`). Unlike the
        ;; default sync / queued consequence shapes — which carry no raw
        ;; app-db — these MUST route through the framework's off-box
        ;; projection (`re-frame.core/projected-record`) before crossing
        ;; the nREPL/MCP wire, exactly as the pull-mode `trace-window` /
        ;; `watch-epochs` surfaces do (rf2-6wvh5). The `--allow-sensitive-
        ;; reads` boot gate (`raw-state-allowed?`, positive sense — true
        ;; only when the operator opted in at launch) governs whether the
        ;; per-call `:include-sensitive true` is honoured.
        ;; rf2-m9duxl — `incl?` (gate-ON + `:include-sensitive true`) NO
        ;; LONGER bypasses the epoch projection: the `:trace` / `:settle`
        ;; epoch ALWAYS routes through `projected-record`, and `incl?`
        ;; threads `{:include-sensitive? true}` INTO it (app-db sensitive
        ;; axis only). The orthogonal fx-args / runtime-db / large axes
        ;; and the app `:redact-fn` stay fail-closed regardless of
        ;; `:include-sensitive` (Security.md §Off-box egress).
        incl?        (if (raw-state/raw-state-allowed?)
                       (boolean (wire/arg args :include-sensitive))
                       false)
        [tag payload] (parse-event-edn event-str)]
    (cond
      ;; rf2-wz66k7 — a malformed `:timeout-ms` short-circuits to an honest
      ;; isError BEFORE the dispatch eval, rather than threading a value
      ;; that would spin the `:await-render` mailbox poll loop unbounded.
      (= :err (first timeout-r))
      (js/Promise.resolve (wire/err-text (second timeout-r)))

      ;; rf2-hf7m9j — an invalid fx-overrides target short-circuits to an
      ;; honest isError rather than silently falling through to the real
      ;; fx (which the documented stub recipe promised was inert).
      (= :err (first fx-r))
      (js/Promise.resolve (wire/err-text (second fx-r)))

      ;; rf2-q6s1nb · EP-0017 — a malformed `cofx` map (non-map / unreadable /
      ;; non-integer :rf/time-ms) short-circuits to an honest isError before
      ;; the dispatch eval, rather than threading a value the runtime would
      ;; reject.
      (= :err (first cofx-r))
      (js/Promise.resolve (wire/err-text (second cofx-r)))

      :else
      (case tag
        :err
        (js/Promise.resolve (wire/err-text payload))

        :ok
      (let [event-vec payload
            opts-form (cond-> {}
                        frame        (assoc :frame frame)
                        fx-overrides (assoc :fx-overrides fx-overrides)
                        ;; rf2-q6s1nb · EP-0017 — thread the scripted
                        ;; recordable coeffects under the flat `:rf.cofx` opts
                        ;; key the router reads. The map is `pr-str`'d as an
                        ;; EDN literal by `rt-call`'s arg-emit path, exactly
                        ;; like `:fx-overrides`.
                        cofx         (assoc :rf.cofx cofx)
                        ;; rf2-v52xsr · EP-0017 §6 / Tool-Pair §Replay — the
                        ;; named replay affordance HARD-WIRES the strict mint
                        ;; policy alongside the recorded `:rf.cofx`. The
                        ;; per-call opt is the most-specific binding point and
                        ;; wins over a `:live` frame config, so an incomplete
                        ;; record fails loudly rather than re-minting. Emitted
                        ;; whether or not a `cofx` token was supplied (a record
                        ;; with no scripted facts still re-drives strict).
                        replay?      (assoc :rf.cofx/mint-policy :strict))
            ;; rf2-vflrg: the event is now a parsed CLJS vector. Pass it
            ;; through `rt-call`'s normal arg-emit path so it is `pr-str`'d
            ;; as an EDN literal — the runtime fn receives data, not host
            ;; source. NO `rt-raw` splice on this surface.
            ;;
            ;; rf2-3bu3d.2 / rf2-3bu3d.3 — the DEFAULT (and explicit
            ;; `:sync` / `:await-render`) routes through
            ;; `dispatch-consequence!`, which (a) VALIDATES the event-id
            ;; against the live `:event` registrar and returns a
            ;; structured `:unknown-id` error WITHOUT dispatching on a
            ;; miss (no silent no-op), (b) ECHOes the resolved event
            ;; under `:resolved`, and (c) returns the CONSEQUENCE
            ;; (`:db-changed? :changed-paths :effects-fired :no-op?`) so
            ;; a genuine no-op is visible. `:queued` opts into the async
            ;; settled?-false transport ack; `:trace` returns the full
            ;; assembled epoch.
            ;; rf2-vk79g — `:settle` routes to the synchronous
            ;; `dispatch-and-settle!` (dispatch-sync → flush-render! →
            ;; re-read the settled epoch). It returns a map directly, so
            ;; it rides the non-await prelude (ensure-runtime! →
            ;; signal-runtime! → eval, rf2-8fin7.3) — no Promise / mailbox,
            ;; unlike `:await-render`.
            fn-sym     (cond settle? 'dispatch-and-settle!
                             trace?  'dispatch-and-collect
                             queued? 'pair-dispatch!
                             :else   'dispatch-consequence!)
            mode (cond settle? :settle trace? :trace queued? :queued :else :sync)]
        (if await-render?
          ;; rf2-gfu33 — render-settle path. The form's synchronous
          ;; return is a Promise; await it through the shared mailbox so
          ;; `dispatch → observe` is one deterministic step.
          (let [settle-form (render-settle-form fn-sym event-vec opts-form)
                mailbox-id  (await-promise/mailbox-key)
                wrapped     (await-promise/wrap-form settle-form mailbox-id)]
            ;; rf2-8fin7.3 — the signalled prelude flips the boot-gate
            ;; posture BEFORE the dispatch eval, exactly as
            ;; `dispatch-dry-run` does. This sets the runtime's
            ;; `raw-state-config` to the server's gate state (OFF by
            ;; default) so the cascade-summary's `:event-vector` (the raw
            ;; `:trigger-event`) redacts to `:rf/redacted` for a sensitive
            ;; epoch — closing the path-3 leak (a first-in-session
            ;; sensitive dispatch shipping its raw event vector under the
            ;; default OFF gate). Without the signal the runtime stays at
            ;; its permissive `{:allow-raw-state? true}` default and the
            ;; redaction never fires. See raw-state/signal-runtime!.
            (probe/eval-after-runtime-signalled!
              conn build-id wrapped :dispatch-failed
              (fn [sentinel]
                (await-promise/handle-sentinel
                  conn build-id timeout-ms sentinel
                  (await-render-callbacks mode)))))
          (let [call-src (ef/emit (ef/rt-call fn-sym event-vec opts-form))
                ;; rf2-olvr5 finding 1 — only the epoch-bearing modes
                ;; (`:trace` / `:settle`) carry raw `:epoch` /
                ;; `:render-events`; wrap their emitted form so the
                ;; projection runs APP-SIDE (where the frame's
                ;; `[:rf.runtime/elision]` runtime-db registry is reachable, same as
                ;; the snapshot / trace-window walkers) before the result
                ;; crosses the wire. The sync / queued consequence shapes
                ;; carry no raw app-db, so they emit the bare call.
                form     (if (contains? #{:trace :settle} mode)
                           (egress/project-dispatch-result-src call-src incl?)
                           call-src)]
            ;; rf2-8fin7.3 — the signalled prelude issues the boot-gate
            ;; signal between the preload probe and the dispatch eval (the
            ;; snapshot / get-path / dispatch-dry-run prelude shape, NOT
            ;; the bare `eval-after-runtime!`). `signal-runtime!` flips the
            ;; runtime's `raw-state-config` to the server's gate state so
            ;; the DEFAULT cascade-summary's `:event-vector` (the raw
            ;; `:trigger-event`) redacts under the OFF gate — closing the
            ;; path-3 leak where a first-in-session sensitive dispatch
            ;; shipped its raw event vector because the runtime was still
            ;; at its permissive `{:allow-raw-state? true}` default. The
            ;; `:trace` / `:settle` epoch slots are ALSO projected
            ;; (egress/project-dispatch-result-src above, rf2-olvr5) — the
            ;; two protections compose, mirroring dispatch-dry-run.
            (probe/eval-after-runtime-signalled!
              conn build-id form :dispatch-failed
              (fn [v] (runtime-envelope->result mode v))))))))))
