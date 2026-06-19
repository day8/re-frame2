(ns re-frame2-pair-mcp.tools.restore-epoch
  "Tool: restore-epoch — time-travel undo (rf2-ee38b.18).

  The canonical pair-tool undo gesture per spec/Tool-Pair.md
  §Time-travel: rewind a frame's whole `frame-state` — BOTH the app-db
  and runtime-db partitions — to a recorded prior epoch's
  `:frame-state-after` value, reinstalled atomically via
  `replace-frame-state!` (machine snapshots, the route slice, elision
  declarations, and SSR metadata revive alongside app-db, not just the
  app-db projection — EP-0001, Mike ruling #2). Wraps the preload
  runtime's `restore-epoch` primitive (`(rf/restore-epoch! frame-id
  epoch-id)`), which is itself the Tool-Pair `restore-epoch` write
  primitive the server is the canonical consumer of (000-Vision /
  003-Tool-Catalogue).

  ## Gate (rf2-ee38b.18)

  A write surface — gated behind `--allow-writes` (default OFF). When
  the gate is closed the tool returns `:rf.error/writes-disabled`
  without touching the nREPL socket. See `tools/writes.cljs`.

  ## Sensitive-read posture (rf2-6nks4, finding-2)

  Restore's success envelope carries a `:cascade-summary` whose
  `:event-vector` slot copies the TARGET epoch's raw `:trigger-event`.
  A restore of a SENSITIVE historical epoch must NOT ship that raw
  payload off-box under the default `--allow-sensitive-reads` OFF
  posture. Like `dispatch-dry-run` (which restore-epoch's primitive
  internally drives), this tool issues `raw-state/signal-runtime!`
  between `ensure-runtime!` and the eval so the runtime's
  `configure-raw-state!` posture is flipped to the server's boot-gate
  state BEFORE `restore-cascade-summary` builds the projection. The
  runtime then redacts the sensitive `:event-vector` to `:rf/redacted`
  (see `redact-sensitive-event-vector` in the preload runtime).
  Fail-closed: gate OFF redacts, gate ON (operator opted in) ships raw.

  ## epoch-id is `:any`

  The `epoch-id` arg is parsed as EDN, not assumed `string?` — the
  reference epoch runtime emits INTEGER epoch-ids (the same `:any`
  contract that drives the cursor fix). A bare integer string (\"7\")
  reads as the integer 7; a keyword reads as a keyword; an unreadable
  value returns `:invalid-epoch-id`.

  Restore is a query against a finite per-frame history; it can fail
  for the documented `:rf.epoch/*` reasons (no-such-epoch, ring
  age-out, restore-during-drain — see spec/Tool-Pair.md §Restore
  failure modes). The runtime primitive returns `false` on any failure
  (the frame's app-db is unchanged); we surface that as
  `{:ok? false :reason :restore-rejected}`."
  (:require [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.writes :as writes]))

;; The `epoch-id` arg is parsed as EDN with NO shape constraint — any
;; readable value (integer / keyword / string; `:epoch-id` is `:any`) is
;; accepted, exactly the shared `args/read-edn-arg` core (rf2-jkake.19).

(defn restore-epoch-tool [conn raw-args]
  (if-not (writes/writes-allowed?)
    (js/Promise.resolve (writes/disabled-result "restore-epoch"))
    (let [epoch-id-str (wire/arg raw-args :epoch-id)
          build-id     (wire/arg-build conn raw-args)
          frame        (some-> (wire/arg raw-args :frame) args/->frame-keyword)
          [tag payload] (args/read-edn-arg epoch-id-str :missing-epoch-id :invalid-epoch-id)]
      (case tag
        :err
        (js/Promise.resolve
          (wire/err-text
            {:ok?      false
             :reason   payload
             :epoch-id epoch-id-str
             :hint     "usage: restore-epoch {epoch-id '<id>' [frame :foo]}. The id is parsed as EDN — an integer id like 7 may be passed as \"7\"."}))

        :ok
        (let [epoch-id payload
              ;; restore-epoch's runtime arglist is ([epoch-id]
              ;; [epoch-id frame-id]) — the frame is the SECOND arg.
              call (if frame
                     (ef/rt-call 'restore-epoch epoch-id frame)
                     (ef/rt-call 'restore-epoch epoch-id))
              form (ef/emit call)
              on-value
              (fn [v]
                ;; Per rf2-6yqdl: the runtime now returns a structured
                ;; envelope on success carrying `:ok? :restored? :epoch-id
                ;; :frame :cascade-summary :unreplayable-effects`. Failure
                ;; remains the legacy `false` so older runtimes still
                ;; surface as a clean reject envelope here.
                (let [result
                      (cond
                        (map? v)
                        v

                        v
                        {:ok?       true
                         :restored? true
                         :epoch-id  epoch-id
                         :frame     frame}

                        :else
                        {:ok?       false
                         :restored? false
                         :reason    :restore-rejected
                         :epoch-id  epoch-id
                         :frame     frame
                         :hint      (str "restore-epoch returned false — the epoch-id is not in the "
                                         "ring, or a drain is in flight. Read the structured reason "
                                         "with (re-frame.trace.tooling/trace-buffer {:op-type :error}) "
                                         "filtered to :rf.epoch/*.")})]
                  ;; rf2-or8s29 — a soft failure (the legacy `false` reject
                  ;; OR a structured `{:ok? false ...}` envelope from a newer
                  ;; runtime) is NOT a terminal-empty outcome: the write did
                  ;; not land. It MUST ride as an `isError: true` result so
                  ;; the MCP host sees the failure + reason rather than a
                  ;; success-shaped envelope (003-Tool-Catalogue §"Every
                  ;; :ok? false response is isError: true"). Only a genuine
                  ;; success returns `ok-text`.
                  (if (false? (:ok? result))
                    (wire/err-text result)
                    (wire/ok-text result))))]
          ;; rf2-6nks4 (finding-2): the signalled prelude signals the
          ;; raw-state posture to the runtime BEFORE the restore eval, so
          ;; the cascade-summary it builds redacts a sensitive
          ;; `:event-vector` under the default gate-OFF posture. Mirrors
          ;; dispatch-dry-run's prelude shape (which restore-epoch's
          ;; primitive internally drives) — the plain `eval-after-runtime!`
          ;; has no `signal-runtime!` step, so this uses the `-signalled!`
          ;; sibling. The signal reconfigures the posture before every
          ;; state-emitting eval (rf2-olvr5 finding 2 — the runtime's
          ;; posture resets on reload, so a cached-delivered flag would
          ;; leave a post-reload restore tapping raw values).
          (probe/eval-after-runtime-signalled!
            conn build-id form :restore-epoch-failed on-value))))))
