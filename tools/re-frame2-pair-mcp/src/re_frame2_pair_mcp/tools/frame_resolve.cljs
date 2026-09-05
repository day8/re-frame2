(ns re-frame2-pair-mcp.tools.frame-resolve
  "Browser-side operating-frame resolution for an emitted eval form —
  the one place a frame-targeted tool turns tier-4 ambiguity into a
  refusal rather than a confident answer about the wrong frame.

  ## The contract this enforces

  re-frame2 is multi-frame, so every frame-targeted op resolves an
  operating frame: explicit per-call `frame` (tier 1) -> session pin
  (tier 2) -> the sole registered app frame (tier 3) -> nil (tier 4,
  ambiguous). 003-Tool-Catalogue §\"Why these ship\" makes tier 4 a
  REFUSAL — `{:ok? false :reason :ambiguous-frame}` — rather than a
  guess.

  ## Why the guard has to be IN the emitted form

  The MCP server cannot resolve the frame: the registry lives in the
  browser. So a tool that omits `frame` emits an implicit-frame call
  and the runtime resolves it — and every runtime read fn is
  nil-TOLERANT. `(epoch-history nil)` bottoms out in a per-frame
  lookup that answers `[]` for an unknown frame; `(snapshot)` is
  `(rf/app-db-value nil)` = nil. Neither errors. The tool therefore
  reports an EMPTY result in the same voice it reports a genuinely
  empty one, and the agent is told \"nothing happened\" when the truth
  is \"I could not tell which frame you meant\" (rf2-q17a for
  `get-path`; rf2-yo4s for `trace-window` / `watch-epochs`, where the
  empty ring also makes a live cursor look aged out).

  Resolving ONCE, before any read, is what makes that impossible: the
  wrong branch is never taken, so there is no wrong answer to dress up
  afterwards. A tool that resolved separately for the read and for a
  sibling concern (an elision handle, a history count) could also have
  the two disagree; binding one id and using it everywhere removes
  that by construction.

  ## No new vocabulary

  The refusal is the runtime's OWN `ambiguous-frame-error` envelope,
  the same one `read-sub`, `sub-cache-info` and `describe-image`
  already return: `:reason :ambiguous-frame` plus `:available-frames`,
  the current `:selected-frame`, and a `:hint` naming the two
  recoveries that already ship (pass `frame`, or pin one with
  `set-operating-frame`). It rides out through each tool's existing
  `:ok? false` -> `wire/err-text` path as an `isError` envelope."
  (:require [re-frame2-pair-mcp.tools.eval-form :as ef]))

(def resolved-frame-sym
  "Name of the let-binding [[with-resolved-frame]] puts the resolved
  operating-frame id in. A tool's inner form reads through this name —
  for the app-db/history read AND for anything else that must describe
  the same frame — so one resolution is one truth."
  "fid")

(defn with-resolved-frame
  "Wrap `inner-src` — an emitted eval form, as a source string — in the
  operating-frame resolution and the tier-4 refusal.

  `operation` is the refusing op's keyword (`:get-path`,
  `:trace-window`, …); it names the op in the envelope and in its
  hint. `frame` is the caller's explicit target (or nil), passed as
  the tier-1 override so a tool's own stickier notion of the frame —
  a paginated tool's cursor-carried `:frame`, say — is honoured ahead
  of the session pin simply by being handed in here.

  `inner-src` is evaluated only on the resolved branch, and reads the
  id by the name [[resolved-frame-sym]] binds."
  [operation frame inner-src]
  (ef/emit
    (ef/rt-let
      [(symbol resolved-frame-sym)
       (if frame
         (ef/rt-call 'current-frame frame)
         (ef/rt-call 'current-frame))]
      (ef/rt-raw
        (str "(if (nil? " resolved-frame-sym ") "
             (ef/emit (ef/rt-call 'ambiguous-frame-error operation))
             " "
             inner-src
             ")")))))

(defn frame-sym-call
  "`(rt-call sym ... )` with the resolved-frame binding as the trailing
  frame argument — the shape a runtime fn's explicit-frame arity takes.
  Sugar for the `(ef/rt-raw resolved-frame-sym)` every call-site would
  otherwise repeat."
  [sym & args]
  (apply ef/rt-call sym (concat args [(ef/rt-raw resolved-frame-sym)])))

(defn refusal?
  "True when a runtime eval result is a structured refusal rather than
  the payload the tool asked for — `{:ok? false ...}`, which under
  [[with-resolved-frame]] is the `:ambiguous-frame` envelope.

  `(false? ...)` deliberately, not `(not ...)`: a blank eval result
  (nil, from a dead runtime) has `(:ok? nil)` = nil and is NOT a
  refusal — it is each tool's own blank-result case."
  [v]
  (and (map? v) (false? (:ok? v))))
