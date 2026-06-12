(ns re-frame2-pair-mcp.tools.operating-frame
  "Tools: set-operating-frame + reset-operating-frame + get-operating-frame
  (rf2-zomfq); the realm dimension (EP-0013 disposition 3, rf2-09ijml).

  The three operating-frame ops the [Tool-Pair contract][1] mandates
  (§Tool-surface obligations) for any pair-shaped tool surface. They are
  the MCP-side counterpart of the runtime's *session pin* — the tier-2
  selection in the operating-frame resolution table — letting an AI
  consumer escape the tier-4 `:ambiguous-frame` refusal a multi-frame app
  otherwise traps them in.

  ## The realm dimension (EP-0013 disposition 3, rf2-09ijml)

  A frame lives in a runtime REALM; the (realm, frame) PAIR is its full
  address — a frame-id is unique only WITHIN a realm. The operating-frame
  ladder's tier-3 sole-app-frame resolution is therefore realm-scoped: it
  counts only the app frames in the OPERATING REALM (the session realm pin,
  else the default realm). These same three ops carry the realm half of the
  address rather than minting a parallel `set/reset/get-operating-realm`
  trio — the (realm, frame) pair is ONE addressing surface:

  - `set-operating-frame` accepts an OPTIONAL `realm` arg (pin the operating
    realm; validated against `(rf/realm-ids)`). Either `frame`, `realm`, or
    both may be supplied — pinning the realm alone re-scopes tier-3 to that
    realm's app frames.
  - `reset-operating-frame` clears BOTH the frame pin and the realm pin.
  - `get-operating-frame` reports the realm dimension (`:realms`,
    `:operating-realm`, `:selected-realm`, `:frame-realms`) alongside the
    frame triple — it routes through the runtime's `frames-list`, which now
    carries those slots.

  A single-realm app (every frame in `:rf.realm/default`) never needs the
  realm arg — the operating realm is the default realm and realm-scoping is
  a no-op, byte-identical to the pre-realm ladder.

  ## The gap these close

  re-frame2 is multi-frame (Spec 002). Every frame-targeted read/write
  (`dispatch`, `snapshot`, `get-path`, `subscribe`, `list-subscriptions`,
  …) resolves an *operating frame*: explicit per-call `:frame` (tier 1) →
  session pin (tier 2) → sole-registered frame (tier 3) → nil
  (tier 4, ambiguous). When two-plus frames are registered and the call
  omits `:frame` and no session pin is set, resolution lands at tier 4 and
  the op REFUSES with `{:ok? false :reason :ambiguous-frame}` rather than
  guessing (per Tool-Pair §Ambiguity surface — a write that lands in the
  wrong frame is unrecoverable without `restore-epoch`).

  Before this tool, tier 2 was *unreachable from the MCP surface*: the
  runtime exposed `select-frame!` / `current-frame` / `frames-list` but no
  tool wired them onto the wire, so a multi-frame agent had to thread
  `:frame` through every single call forever — defeating the
  implicit-until-reset UX the contract designed. These three ops surface
  the session pin so the agent declares it ONCE and escapes.

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

;; rf2-olvr5 finding 3 — the response cache key is `(tool, build,
;; args-fingerprint)`; it deliberately can NOT include the resolved
;; operating frame for an omitted-`:frame` call (that resolves runtime-
;; side, after the key is built). So a `get-path {path X}` with no
;; `:frame` arg, cached against operating frame A, would serve A's payload
;; after the session switches to frame B if B happens to share A's
;; app-db-hash (the multi-frame identical-initial-db case the bead
;; reproduces). The fix flushes the WHOLE response cache whenever the
;; operating frame changes — and to avoid a `cache → registry →
;; operating-frame → cache` require cycle (registry wires these handlers,
;; cache reads registry's `cacheable?`), the flush is driven from the
;; `invoke` dispatch chokepoint in `re-frame2-pair-mcp.tools` (which
;; already requires both `cache` and `registry`) rather than imported
;; here. `operating-frame-mutating?` is the predicate that chokepoint
;; consults.

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
  "Build the validate-then-pin eval form for an OPTIONAL `realm-id` and/or
  `frame-id` (keywords; nil when not supplied).

  Order matters: the realm is pinned FIRST (via `select-realm!`) so the
  post-pin `frames-list` view (and the frame-membership check) is already
  scoped to the new operating realm. A bad realm short-circuits with
  `:no-such-realm` (no frame pin happens); a bad frame short-circuits with
  `:no-such-frame`. With both valid (or absent), the pins land and the fresh
  `frames-list` map returns — carrying the frame triple AND the realm slots."
  [realm-id frame-id]
  (let [realm-clause
        (when realm-id
          (str "(if (some #{" (pr-str realm-id) "} (:realms (re-frame2-pair.runtime/frames-list)))"
               "  (re-frame2-pair.runtime/select-realm! " (pr-str realm-id) ")"
               "  {:ok? false :reason :no-such-realm"
               "   :realm " (pr-str realm-id)
               "   :realms (:realms (re-frame2-pair.runtime/frames-list))})"))
        frame-clause
        (when frame-id
          (str "(if (some #{" (pr-str frame-id) "} (:frames (re-frame2-pair.runtime/frames-list)))"
               "  (re-frame2-pair.runtime/select-frame! " (pr-str frame-id) ")"
               "  {:ok? false :reason :no-such-frame"
               "   :frame " (pr-str frame-id)
               "   :frames (:frames (re-frame2-pair.runtime/frames-list))})"))]
    (ef/emit
      (ef/rt-raw
        (str "(let [realm-r " (if realm-clause realm-clause "{:ok? true}") "]"
             "  (if (false? (:ok? realm-r))"
             "    realm-r"
             "    (let [frame-r " (if frame-clause frame-clause "{:ok? true}") "]"
             "      (if (false? (:ok? frame-r))"
             "        frame-r"
             "        (re-frame2-pair.runtime/frames-list)))))")))))

(defn set-operating-frame-tool [conn raw-args]
  (let [frame-str (wire/arg raw-args :frame)
        frame     (some-> frame-str args/->frame-keyword)
        realm-str (wire/arg raw-args :realm)
        realm     (some-> realm-str args/->frame-keyword)
        build-id  (wire/arg-build conn raw-args)]
    (cond
      (and (nil? frame) (nil? realm))
      (js/Promise.resolve
        (wire/err-text
          {:ok?    false
           :reason :missing-frame
           :hint   (str "usage: set-operating-frame {frame \":stories\"} "
                        "and/or {realm \":shop/realm\"}. "
                        "Pin the session's operating frame (and/or realm) so "
                        "subsequent frame-targeted ops (dispatch, snapshot, "
                        "get-path, subscribe, …) resolve to it instead of "
                        "refusing with :ambiguous-frame. The (realm, frame) pair "
                        "is the full address — pinning the realm re-scopes "
                        "tier-3 sole-frame resolution to that realm's app frames "
                        "(EP-0013). Call get-operating-frame to see the "
                        "registered frames + realms.")}))

      ;; rf2-wdxyx3 finding 1 — refuse pinning a reserved `:rf/*` TOOL frame
      ;; as the session's operating frame BEFORE any nREPL round-trip. A
      ;; tool frame (Xray's `:rf/xray`, an SSR slot) is a devtool surface,
      ;; NOT the app the operator is pairing against (Tool-Pair §Reserved
      ;; tool frames are excluded from the ambiguity count). Were a pin
      ;; allowed, the runtime's `current-frame` resolver would return the
      ;; tool frame at tier 2, and a later no-`:frame` `get-path {path []}`
      ;; would resolve the wholesale read through it — re-opening the exact
      ;; context-window overflow the rf2-qef58 guard was introduced to
      ;; close (the guard fires on the explicit `:frame` arg; an omitted
      ;; `:frame` resolves runtime-side, where the client guard can't see
      ;; the reserved pin). Closing the pin here removes the bypass at its
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
      (let [form (set-form realm frame)]
        (probe/eval-after-runtime!
          conn build-id form :set-operating-frame-failed
          (fn [v]
            ;; rf2-wdxyx3 finding 2 — a known-tool execution failure
            ;; (`:ok? false`) MUST ride back as an `isError` envelope so the
            ;; MCP host routes recovery through the error channel and the
            ;; invoke chokepoint does not treat a non-pin as a cache-flushing
            ;; success. The runtime shapes resolve into one the agent can
            ;; rely on:
            ;;   - success: `frames-list`'s {:ok? true :frames :app-frames
            ;;     :selected :operating + realm slots} map → `ok-text`;
            ;;     `:selected` / `:selected-realm` now equal the just-pinned
            ;;     frame / realm.
            ;;   - unknown realm: {:ok? false :reason :no-such-realm :realm
            ;;     :realms} → `err-text` with a corrective hint (EP-0013).
            ;;   - unknown frame: {:ok? false :reason :no-such-frame :frame
            ;;     :frames} → `err-text` with a corrective hint.
            ;;   - degraded runtime (non-map) → `err-text` :unexpected-shape.
            (cond
              (not (map? v))
              (wire/err-text {:ok? false :reason :unexpected-shape
                              :frame frame :realm realm :value v})

              (= :no-such-realm (:reason v))
              (wire/err-text
                (assoc v :hint
                       (str "realm " realm " is not installed. "
                            "Pick one of " (vec (:realms v))
                            " — call get-operating-frame to list the installed "
                            "realms. (A single-realm app has only "
                            ":rf.realm/default and needs no realm arg.)")))

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
;; Clear BOTH session pins — the frame pin (`select-frame! nil`) and the
;; realm pin (`select-realm! nil`, EP-0013 rf2-09ijml) — and read back the
;; map so the caller sees the post-reset state (`:selected nil`,
;; `:selected-realm nil`, `:operating` / `:operating-realm` now the tier-3/4
;; resolution and the default realm). One eval form — clear then re-read — so
;; the reported map reflects the cleared pins.
;; ---------------------------------------------------------------------------

(defn- reset-form []
  (ef/emit
    (ef/rt-let
      ['_  (ef/rt-call 'select-frame! nil)
       '_r (ef/rt-call 'select-realm! nil)]
      (ef/rt-call 'frames-list))))

(defn reset-operating-frame-tool [conn raw-args]
  (let [build-id (wire/arg-build conn raw-args)
        form     (reset-form)]
    (probe/eval-after-runtime!
      conn build-id form :reset-operating-frame-failed
      (fn [v]
        (wire/ok-text
          (if (map? v)
            v
            {:ok? false :reason :unexpected-shape :value v}))))))

(def operating-frame-mutating-tools
  "Tool names whose successful invocation changes the session's operating
  frame, invalidating every omitted-`:frame` cached read (rf2-olvr5
  finding 3). The `invoke` chokepoint flushes the response cache after
  these run. `get-operating-frame` is a pure read and is NOT included."
  #{"set-operating-frame" "reset-operating-frame"})

(defn operating-frame-mutating?
  "True iff `tool-name` is an operating-frame mutation (rf2-olvr5
  finding 3) — the predicate `re-frame2-pair-mcp.tools/invoke` consults
  to decide whether to flush the response cache after the call. Lives
  here (not in `cache`) so the cache ns stays free of an
  operating-frame require; lives as a name-set check (not a per-result
  inspection) so the chokepoint never has to parse the tool's envelope."
  [tool-name]
  (contains? operating-frame-mutating-tools tool-name))

;; ---------------------------------------------------------------------------
;; get-operating-frame
;;
;; Pure read — the normative triple (Tool-Pair lines 394-401). Routes
;; through the runtime's `frames-list`, the SAME accessor `discover-app`
;; consults, so the two never disagree about what's registered or pinned.
;; ---------------------------------------------------------------------------

(defn- get-form []
  (ef/emit (ef/rt-call 'frames-list)))

(defn get-operating-frame-tool [conn raw-args]
  (let [build-id (wire/arg-build conn raw-args)
        form     (get-form)]
    (probe/eval-after-runtime!
      conn build-id form :get-operating-frame-failed
      (fn [v]
        (wire/ok-text
          (if (map? v)
            v
            {:ok? false :reason :unexpected-shape :value v}))))))
