(ns re-frame2-pair-mcp.tools.operating-frame
  "Tools: set-operating-frame + reset-operating-frame + get-operating-frame.

  The three operating-frame ops the [Tool-Pair contract][1] mandates
  (§Tool-surface obligations) for any pair-shaped tool surface. They are
  the MCP-side counterpart of the runtime's *session pin* — the tier-2
  selection in the operating-frame resolution table — letting an AI
  consumer escape the tier-4 `:ambiguous-frame` refusal a multi-frame app
  otherwise traps them in.

  ## The public address is the frame (EP-0023)

  The EP-0023 public model is `image -> frame -> event stream` (EP-0023
  §Specification). The public target of every frame-scoped op is a FRAME — a
  process-local frame id in the live-frame registry (or a direct frame object
  in tests/harnesses). A single process-local frame-id space: the frame is the
  thing you pin, and its resolution universe is the resolved image generation
  it runs.

  So `set-operating-frame` pins a FRAME ID, full stop. `reset-operating-frame`
  clears the pin so a session resets to a clean posture.

  ## Why these ops exist

  re-frame2 is multi-frame (Spec 002). Every frame-targeted read/write
  (`dispatch`, `snapshot`, `get-path`, `read-sub`, `list-subscriptions`,
  …) resolves an *operating frame*: explicit per-call `:frame` (tier 1) →
  session pin (tier 2) → sole-registered frame (tier 3) → nil
  (tier 4, ambiguous). When two-plus frames are registered and the call
  omits `:frame` and no session pin is set, resolution lands at tier 4 and
  the op REFUSES with `{:ok? false :reason :ambiguous-frame}` rather than
  guessing (per Tool-Pair §Ambiguity surface — a write that lands in the
  wrong frame is unrecoverable without `restore-epoch`).

  These three ops surface tier 2 — the session pin — on the MCP wire. The
  runtime holds `select-frame!` / `current-frame` / `frames-list`; these
  tools wire them onto the wire so a multi-frame agent declares its
  operating frame ONCE and escapes the per-call `:frame` threading,
  delivering the implicit-until-reset UX the contract designs for.

  ## `subscribe` is outside the cascade (rf2-wyza)

  The pin governs ops that RESOLVE a frame. `subscribe` does not resolve
  one: `subscribe-tool` reads no `:frame` arg, and the runtime
  `subscribe!` it emits destructures only `{:topic :filter
  :max-buffered-events :max-buffered-bytes}` — `current-frame` is never
  consulted. Delivery is a global fan-out (`dispatch-trace-to-subs!`
  offers every trace event to every subscription), so scope is the
  subscription's own filter — `{:frame :foo}`, matched by
  `pure/trace-matches?` and `pure/epoch-matches?` — and a pinned session
  that omits it streams every frame. The one place the pin does reach a
  stream is the drain's elision registry: `subscribe`'s drain form
  threads `current-frame` as the per-element FALLBACK frame for a
  genuinely frameless event, which selects whose sensitive/large
  declarations apply, never which events arrive.

  So `subscribe` must not be listed among the ops this pin scopes. The
  descriptor, the `:missing-frame` hint below and the onboarding blob's
  routing rule 6 each name the exception; `skills/re-frame2-pair/SKILL.md`
  §Multi-frame model has carried the correct wording throughout.

  ## The trio

  - **set-operating-frame** `{:frame \":stories\"}` — pin the session's
    operating frame. Validates the frame-id names a currently-registered
    frame (Tool-Pair §Tool-surface obligations: \"SHOULD validate … an
    unknown frame returns `{:ok? false :reason :no-such-frame}`\"). On
    success returns the resolved triple so the caller sees the pin took.
  - **reset-operating-frame** `{}` — clear the session pin. Subsequent
    ops resolve at tier 3 / 4 again. Returns the post-reset triple
    (`:selected nil`).
  - **get-operating-frame** `{}` — the read. Returns the normative triple
    per Tool-Pair lines 394-401: `:frames` (all registered, so the caller
    can pick a target), `:selected` (the tier-2 session pin, nil when
    unset), `:operating` (the result of full resolution, nil = ambiguous).

  ## How it escapes `:ambiguous-frame`

  A frame-consuming op with no per-call `:frame` reads the session pin via
  the runtime's `current-frame` resolver. Set writes that pin; from then
  on `current-frame` returns the pinned id at tier 2 and the op proceeds
  against it instead of refusing. Reset clears the pin, returning the
  session to the tier-3/4 default posture.

  ## All three route through the runtime's session pin

  The session pin lives in the `re-frame2-pair.runtime` preload's
  `selected-frame` atom — the SAME atom every other frame-consuming op
  consults via `current-frame`. These tools are thin wrappers over the
  already-published runtime fns:

  - set   → validate against `(frames-list)` then `(select-frame! id)`.
  - reset → `(select-frame! nil)` then read back the triple.
  - get   → `(frames-list)`.

  Composing the validation in the emitted eval form (rather than a new
  runtime fn) keeps these ops on the pair-mcp surface and reuses the
  runtime's single source of truth for the pin — set / get / reset can
  never disagree about where the pin lives.

  [1]: ../../../../spec/Tool-Pair.md"
  (:require [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.reserved-frame-guard :as guard]))

;; The response cache key is `(tool, build, args-fingerprint)`; it
;; deliberately can NOT include the resolved operating frame for an
;; omitted-`:frame` call (that resolves runtime-side, after the key is
;; built). So a `get-path {path X}` with no `:frame` arg, cached against
;; operating frame A, would serve A's payload after the session switches
;; to frame B if B happens to share A's app-db-hash (the multi-frame
;; identical-initial-db case). To prevent that, the WHOLE response cache
;; flushes whenever the operating frame changes — and to avoid a
;; `cache → registry → operating-frame → cache` require cycle (registry
;; wires these handlers, cache reads registry's `cacheable?`), the flush
;; is driven from the `invoke` dispatch chokepoint in
;; `re-frame2-pair-mcp.tools` (which already requires both `cache` and
;; `registry`) rather than imported here. `operating-frame-mutating?` is
;; the predicate that chokepoint consults.

;; ---------------------------------------------------------------------------
;; set-operating-frame
;;
;; Validate-then-pin as ONE eval form so the membership check and the
;; pin write happen atomically against the same runtime read — no
;; check-then-act race across two round-trips. `frames-list` returns the
;; `{:ok? :frames :selected :operating}` triple; we read `:frames`,
;; test membership, and either `select-frame!` (returning the fresh
;; triple) or refuse with `:no-such-frame` (carrying the registered
;; `:frames` so the caller can pick a valid target).
;; ---------------------------------------------------------------------------

(defn- set-form
  "Build the validate-then-pin eval form for a `frame-id` keyword.

  Validation and the pin write are ONE eval form against the same runtime
  read — no check-then-act race across two round-trips. `frames-list`
  returns the `{:ok? :frames :selected :operating …}` map; we read
  `:frames`, test membership, and either `select-frame!` (returning the
  fresh map) or refuse with `:no-such-frame` (carrying the registered
  `:frames` so the caller can pick a valid target)."
  [frame-id]
  (ef/emit
    (ef/rt-raw
      (str "(if (some #{" (pr-str frame-id) "} (:frames (re-frame2-pair.runtime/frames-list)))"
           "  (re-frame2-pair.runtime/select-frame! " (pr-str frame-id) ")"
           "  {:ok? false :reason :no-such-frame"
           "   :frame " (pr-str frame-id)
           "   :frames (:frames (re-frame2-pair.runtime/frames-list))})"))))

(defn set-operating-frame-tool [conn raw-args]
  (let [frame-str (wire/arg raw-args :frame)
        frame     (some-> frame-str args/->frame-keyword)
        build-id  (wire/arg-build conn raw-args)]
    (cond
      (nil? frame)
      (js/Promise.resolve
        (wire/err-text
          {:ok?    false
           :reason :missing-frame
           :hint   (str "usage: set-operating-frame {frame \":stories\"}. "
                        "Pin the session's operating frame so subsequent "
                        "frame-targeted ops (dispatch, snapshot, get-path, "
                        "read-sub, …) resolve to it instead of refusing with "
                        ":ambiguous-frame. The public address is the FRAME id "
                        "(EP-0023: image -> frame -> event stream); call "
                        "get-operating-frame to see the registered frames. "
                        "subscribe is the exception — it takes no :frame and the "
                        "pin does not scope its delivery (it only supplies the "
                        "drain's elision fallback frame for a genuinely frameless "
                        "event); scope a stream with its filter, "
                        "{:frame :stories}.")}))

      ;; Refuse pinning a reserved `:rf/*` TOOL frame as the session's
      ;; operating frame BEFORE any nREPL round-trip. A tool frame
      ;; (Xray's `:rf/xray`, an SSR slot) is a devtool surface, NOT the
      ;; app the operator is pairing against (Tool-Pair §Reserved tool
      ;; frames are excluded from the ambiguity count). Were a pin
      ;; allowed, the runtime's `current-frame` resolver would return the
      ;; tool frame at tier 2, and a later no-`:frame` `get-path {path []}`
      ;; would resolve the wholesale read through it — re-opening the exact
      ;; context-window overflow the wholesale-read guard closes (that
      ;; guard fires on the explicit `:frame` arg; an omitted `:frame`
      ;; resolves runtime-side, where the client guard can't see the
      ;; reserved pin). Closing the pin here removes the bypass at its
      ;; source: a reserved frame is never the operating frame, so the
      ;; nil-`:frame` resolution can never land on one. Sliced/explicit
      ;; reads of a tool frame stay available via the per-call `:frame`
      ;; arg. `:rf/default` is an app frame (the predicate's carve-out) and
      ;; is allowed.
      (guard/reserved-tool-frame? frame)
      (js/Promise.resolve
        (wire/err-text
          {:ok?    false
           :reason :reserved-tool-frame
           :frame  frame
           :hint   (str "Refusing to pin the reserved :rf/* TOOL frame " frame
                        " as the operating frame. Reserved :rf/* frames are devtool "
                        "surfaces (e.g. Xray's :rf/xray), not the app frame you pair "
                        "against — pinning one would resolve every omitted-:frame read "
                        "against it and re-open the wholesale-read overflow. Pin an APP "
                        "frame instead (call get-operating-frame to list :app-frames), "
                        "or pass :frame " frame " on a single call for a TARGETED "
                        "(sliced) read of the tool frame.")}))

      :else
      (let [form (set-form frame)]
        (probe/eval-after-runtime!
          conn build-id form :set-operating-frame-failed
          (fn [v]
            ;; A known-tool execution failure
            ;; (`:ok? false`) MUST ride back as an `isError` envelope so the
            ;; MCP host routes recovery through the error channel and the
            ;; invoke chokepoint does not treat a non-pin as a cache-flushing
            ;; success. The runtime shapes resolve into one the agent can
            ;; rely on:
            ;;   - success: `frames-list`'s {:ok? true :frames :app-frames
            ;;     :selected :operating + installation-boundary slots} map →
            ;;     `ok-text`; `:selected` now equals the just-pinned frame.
            ;;   - unknown frame: {:ok? false :reason :no-such-frame :frame
            ;;     :frames} → `err-text` with a corrective hint.
            ;;   - degraded runtime (non-map) → `err-text` :unexpected-shape.
            (cond
              (not (map? v))
              (wire/err-text {:ok? false :reason :unexpected-shape
                              :frame frame :value v})

              (false? (:ok? v))
              (wire/err-text
                (assoc v :hint
                       (str "frame " frame " is not currently registered. "
                            "Pick one of " (vec (:frames v))
                            " — call get-operating-frame to list them.")))

              :else (wire/ok-text v))))))))

;; ---------------------------------------------------------------------------
;; reset-operating-frame
;;
;; Clear the session's frame pin (`select-frame! nil`) and read back the map
;; so the caller sees the post-reset state (`:selected nil`, `:operating`
;; now the tier-3/4 resolution). One eval form — clear then re-read — so the
;; reported map reflects the cleared pin.
;; ---------------------------------------------------------------------------

(defn- reset-form []
  (ef/emit
    (ef/rt-let
      ['_  (ef/rt-call 'select-frame! nil)]
      (ef/rt-call 'frames-list))))

(defn reset-operating-frame-tool [conn raw-args]
  (let [build-id (wire/arg-build conn raw-args)
        form     (reset-form)]
    (probe/eval-after-runtime!
      conn build-id form :reset-operating-frame-failed
      (fn [v]
        ;; rf2-acckgr: `frames-list` (via `select-frame!` here) always
        ;; answers `:ok? true` against a live runtime, so a non-map `v`
        ;; means the eval came back blank/degraded — a known-tool
        ;; failure, not a success carrying bad news. err-text (not
        ;; ok-text), mirroring the sibling guard in
        ;; `set-operating-frame-tool`.
        (if (map? v)
          (wire/ok-text v)
          (wire/err-text {:ok? false :reason :unexpected-shape :value v}))))))

(def operating-frame-mutating-tools
  "Tool names whose successful invocation changes the session's operating
  frame, invalidating every omitted-`:frame` cached read. The `invoke`
  chokepoint flushes the response cache after these run.
  `get-operating-frame` is a pure read and is NOT included."
  #{"set-operating-frame" "reset-operating-frame"})

(defn operating-frame-mutating?
  "True iff `tool-name` is an operating-frame mutation — the predicate
  `re-frame2-pair-mcp.tools/invoke` consults to decide whether to flush
  the response cache after the call. Lives
  here (not in `cache`) so the cache ns stays free of an
  operating-frame require; lives as a name-set check (not a per-result
  inspection) so the chokepoint never has to parse the tool's envelope."
  [tool-name]
  (contains? operating-frame-mutating-tools tool-name))

;; ---------------------------------------------------------------------------
;; get-operating-frame
;;
;; Pure read — the normative triple (Tool-Pair §Tool-surface obligations:
;; `:frames` / `:selected` / `:operating`). Routes through the runtime's
;; `frames-list`, the SAME accessor `discover-app` consults, so the two never
;; disagree about what's registered or pinned.
;; ---------------------------------------------------------------------------

(defn- get-form []
  (ef/emit (ef/rt-call 'frames-list)))

(defn get-operating-frame-tool [conn raw-args]
  (let [build-id (wire/arg-build conn raw-args)
        form     (get-form)]
    (probe/eval-after-runtime!
      conn build-id form :get-operating-frame-failed
      (fn [v]
        ;; rf2-acckgr: `frames-list` always answers `:ok? true` against
        ;; a live runtime, so a non-map `v` means the eval came back
        ;; blank/degraded — a known-tool failure, not a success carrying
        ;; bad news. err-text (not ok-text).
        (if (map? v)
          (wire/ok-text v)
          (wire/err-text {:ok? false :reason :unexpected-shape :value v}))))))
