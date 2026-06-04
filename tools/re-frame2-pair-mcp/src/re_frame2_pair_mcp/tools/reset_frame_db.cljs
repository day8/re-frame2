(ns re-frame2-pair-mcp.tools.reset-frame-db
  "Tool: reset-frame-db — state injection (rf2-ee38b.18).

  The Tool-Pair `reset-frame-db!` write primitive per
  spec/Tool-Pair.md §Pair-tool writes: replace a frame's `app-db` with
  an arbitrary value the runtime never recorded — the explicit
  JSON-loaded-bug-repro use case. Wraps the preload runtime's
  `app-db-reset!` (`(rf/reset-frame-db! frame-id new-db)`), which
  bypasses the dispatch loop, replaces the container directly, and
  records a synthetic `:rf/epoch-record` (`:event-id
  :rf.epoch/db-replaced`) so a subsequent `restore-epoch` can rewind
  past the injection.

  ## Gate (rf2-ee38b.18)

  A write surface — gated behind `--allow-writes` (default OFF). When
  the gate is closed the tool returns `:rf.error/writes-disabled`
  without touching the nREPL socket. See `tools/writes.cljs`.

  ## db is EDN data, not host source

  The `db` arg is parsed as EDN and emitted into the runtime call via
  the normal `pr-str` path (NO `rt-raw` splice) — the same
  injection-closing posture `dispatch` takes (rf2-vflrg). A
  prompt-injected `(println :pwn)` string is data, not code; it would
  read as a symbol/list literal and be injected verbatim (and almost
  certainly rejected by the frame's app-schema), never executed.

  The injection can fail for the documented `:rf.epoch/*` reasons
  (no-such-frame, reset-during-drain, schema-mismatch — see
  spec/Tool-Pair.md §Pair-tool write failure modes). The runtime's
  `app-db-reset!` already returns a structured `{:ok? false :reason
  :reset-rejected ...}` on those soft failures; we pass it through.

  ## Raw-state tap signal MUST precede app-db-reset! (rf2-z7roa)

  The preload's `app-db-reset!` taps BOTH the previous and the next
  app-db value through `tap>` so the human sees what the agent
  changed. Those tap payloads default to RAW until the server signals
  its boot-gate posture to the runtime via `configure-raw-state!`. A
  session launched with `--allow-writes` but the default sensitive-read
  posture (`--allow-sensitive-reads` OFF) could therefore emit raw
  app-db through tap consumers (10x, dev panels, the user's own
  `add-tap`) on the FIRST `reset-frame-db` call — before any read
  surface had a chance to signal the runtime.

  So this tool — like the direct-read surfaces (snapshot / get-path /
  subscribe, rf2-c2dtu) — issues `raw-state/signal-runtime!` AFTER
  `ensure-runtime!` and BEFORE the `app-db-reset!` eval. The signal is
  idempotent + build-keyed; `signal-runtime!` only marks the cache
  successful once the `configure-raw-state!` eval has been issued, so a
  first reset can never tap raw values ahead of the posture landing.
  This is why `reset-frame-db` does NOT use the plain
  `probe/eval-after-runtime!` prelude (which skips the signal step) —
  it threads `signal-runtime!` between the probe and the eval."
  (:require [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.writes :as writes]))

;; The `db` arg has NO shape constraint — a frame's app-db is
;; conventionally a map but the runtime accepts any value the frame's
;; app-schema admits, so we only require readability. That's exactly the
;; shared `args/read-edn-arg` core (rf2-jkake.19); the richer
;; vector-shape `dispatch` parser is deliberately not shared with it.

(defn reset-frame-db-tool [conn raw-args]
  (if-not (writes/writes-allowed?)
    (js/Promise.resolve (writes/disabled-result "reset-frame-db"))
    (let [db-str   (wire/arg raw-args :db)
          build-id (wire/arg-build conn raw-args)
          frame    (some-> (wire/arg raw-args :frame) args/->frame-keyword)
          [tag payload] (args/read-edn-arg db-str :missing-db :invalid-db-edn)]
      (case tag
        :err
        (js/Promise.resolve
          (wire/err-text
            {:ok?    false
             :reason payload
             :hint   "usage: reset-frame-db {db '<edn-app-db-value>' [frame :foo]}. db is parsed as EDN data (not host source) — e.g. \"{:cart {:items []}}\"."}))

        :ok
        (let [new-db payload
              ;; app-db-reset!'s runtime arglist is ([v] [v frame-id]) —
              ;; the value is FIRST, the frame is the optional SECOND.
              ;; The value rides the normal pr-str arg path (data, not
              ;; rt-raw source).
              call (if frame
                     (ef/rt-call 'app-db-reset! new-db frame)
                     (ef/rt-call 'app-db-reset! new-db))
              form (ef/emit call)]
          ;; rf2-z7roa — `signal-runtime!` lands between `ensure-runtime!`
          ;; and the `app-db-reset!` eval so the runtime's tap-emitting
          ;; surface is in its gated (default-elided) posture BEFORE the
          ;; reset taps the pre-/post-reset app-db. This mirrors the
          ;; direct-read surfaces' prelude (rf2-c2dtu) rather than the
          ;; plain `probe/eval-after-runtime!` (which has no signal step).
          (-> (probe/ensure-runtime! conn build-id)
              (.then (fn [_] (raw-state/signal-runtime! conn build-id)))
              (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
              (.then (fn [v]
                       ;; app-db-reset! returns a structured envelope
                       ;; ({:ok? true :frame ...} / {:ok? false :reason
                       ;; :reset-rejected ...}). Pass it through; default
                       ;; to a generic shape if the runtime returned a
                       ;; non-map (degraded / pre-rf2-c2dtu runtime).
                       (wire/ok-text
                         (if (map? v)
                           v
                           {:ok? false :reason :unexpected-shape :value v :frame frame}))))
              (.catch (fn [err] (probe/err->result :reset-frame-db-failed err)))))))))
