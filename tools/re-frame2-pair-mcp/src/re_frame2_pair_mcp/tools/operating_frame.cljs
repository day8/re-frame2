(ns re-frame2-pair-mcp.tools.operating-frame
  "Tools: set-operating-frame + reset-operating-frame + get-operating-frame
  (rf2-zomfq).

  The three operating-frame ops the [Tool-Pair contract][1] mandates
  (§Tool-surface obligations) for any pair-shaped tool surface. They are
  the MCP-side counterpart of the runtime's *session pin* — the tier-2
  selection in the operating-frame resolution table — letting an AI
  consumer escape the tier-4 `:ambiguous-frame` refusal a multi-frame app
  otherwise traps them in.

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
            [re-frame2-pair-mcp.tools.probe :as probe]))

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
  "Build the validate-then-pin eval form for `frame-id` (a keyword).

  Reads the current frame list, and IF `frame-id` is registered, pins it
  and returns the post-pin triple; ELSE returns a `:no-such-frame`
  envelope carrying the registered frames so the agent can retarget."
  [frame-id]
  (ef/emit
    (ef/rt-let
      ['fl    (ef/rt-call 'frames-list)
       'fids  (ef/rt-raw "(:frames fl)")]
      (ef/rt-raw
        (str "(if (some #{" (pr-str frame-id) "} fids)"
             "  (do (re-frame2-pair.runtime/select-frame! " (pr-str frame-id) ")"
             "      (re-frame2-pair.runtime/frames-list))"
             "  {:ok? false :reason :no-such-frame"
             "   :frame " (pr-str frame-id)
             "   :frames fids})")))))

(defn set-operating-frame-tool [conn raw-args]
  (let [frame-str (wire/arg raw-args :frame)
        frame     (some-> frame-str args/->frame-keyword)
        build-id  (wire/arg-build conn raw-args)]
    (if (nil? frame)
      (js/Promise.resolve
        (wire/err-text
          {:ok?    false
           :reason :missing-frame
           :hint   (str "usage: set-operating-frame {frame \":stories\"}. "
                        "Pin the session's operating frame so subsequent "
                        "frame-targeted ops (dispatch, snapshot, get-path, "
                        "subscribe, …) resolve to it instead of refusing "
                        "with :ambiguous-frame. Call get-operating-frame "
                        "to see the registered frames.")}))
      (let [form (set-form frame)]
        (probe/eval-after-runtime!
          conn build-id form :set-operating-frame-failed
          (fn [v]
            ;; Two runtime shapes resolve into one the agent can rely on:
            ;;   - success: `frames-list`'s {:ok? true :frames :selected
            ;;     :operating} triple. Pass through — `:selected` now
            ;;     equals the just-pinned frame.
            ;;   - unknown frame: {:ok? false :reason :no-such-frame
            ;;     :frame :frames}. Add a corrective hint.
            (wire/ok-text
              (cond
                (not (map? v))
                {:ok? false :reason :unexpected-shape :frame frame :value v}

                (false? (:ok? v))
                (assoc v :hint
                       (str "frame " frame " is not currently registered. "
                            "Pick one of " (vec (:frames v))
                            " — call get-operating-frame to list them."))

                :else v))))))))

;; ---------------------------------------------------------------------------
;; reset-operating-frame
;;
;; Clear the session pin (`select-frame! nil`) and read back the triple so
;; the caller sees the post-reset state (`:selected nil`, `:operating` now
;; the tier-3/4 resolution). One eval form — clear then re-read — so the
;; reported triple reflects the cleared pin.
;; ---------------------------------------------------------------------------

(defn- reset-form []
  (ef/emit
    (ef/rt-let
      ['_ (ef/rt-call 'select-frame! nil)]
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
