(ns re-frame2-pair-mcp.tools.get-stream-controls
  "Tool: get-stream-controls — read-only diagnostic over the SERVER-SIDE
  streaming resource-control state.

  ## What this answers

  When a stream is denied, quiet, throttled, or terminated for sustained
  overflow, the operator needs to ask the MCP surface what the
  server-side resource controller currently believes — effective caps,
  active slot count, token-bucket pressure, abuse-window count. This
  tool is the first-class way to query that state live, where it
  otherwise surfaces only indirectly through code/test seams, a passive
  stderr banner, and the rejection envelopes themselves.

  `list-streams` answers the complementary RUNTIME question (\"what
  trace/epoch/fx streams are open in the browser?\") — it wraps the
  runtime's `subscription-info` registry over nREPL. This tool answers
  the SERVER question (\"what does the resource controller believe?\")
  and reads `resource-controls` atoms IN-PROCESS — no nREPL round-trip,
  so it answers even when the runtime is down (exactly when an operator
  is most likely diagnosing a denied / stalled stream).

  ## Cross-link with list-streams

  `:active-streams` here is the server's count of acquired slots; the
  runtime `list-streams` count is the number of registered streaming-tap
  subscriptions. They SHOULD agree. A `get-stream-controls` active count
  with NO matching `list-streams` row signals a LEAKED server slot (a
  stream that died without releasing); the reverse signals a stale
  runtime subscription. We surface the server count + the cross-check
  hint so the mismatch is actionable, but we do NOT call the runtime from
  here (that would re-introduce the nREPL dependency this tool exists to
  avoid) — the operator runs `list-streams` to complete the cross-check.

  ## Privacy

  The payload is server-local control state only — caps, counts, bucket
  pressure. It carries NO event payloads and NO app-db data, so it is
  unconditionally safe (no `--allow-sensitive-reads` gate)."
  (:require [re-frame2-pair-mcp.tools.resource-controls :as resource]
            [re-frame2-pair-mcp.tools.wire :as wire]))

(defn- rate-bucket-view
  "Project the token bucket's pressure into a reportable shape. The
  bucket is LAZILY initialised on the first `check-rate!` call, so
  before any stream has ticked `current-tokens` is nil — report
  `:initialized? false` and treat the bucket as full (its lazy-init
  state) so the operator reading a fresh session sees the cap, not a
  confusing nil."
  [cfg]
  (let [cap     (:max-events-per-sec cfg)
        tokens  (resource/current-tokens)]
    (if (nil? tokens)
      {:capacity      cap
       :tokens        cap
       :initialized?  false
       :throttling?   false}
      {:capacity      cap
       :tokens        tokens
       :initialized?  true
       ;; Fewer than one whole token left ⇒ the next poll cycle defers
       ;; (rate-limited). The operator reads this as live back-pressure.
       :throttling?   (< tokens 1.0)})))

(defn get-stream-controls-tool
  "Return the server-side streaming resource-control state. `conn` /
  `args` are accepted for handler-shape uniformity but unused — the
  state is process-local, no nREPL round-trip. Always `:ok? true`
  (there is no failure mode for an in-process atom read).

  Returns:
    {:ok?    true
     :config {<the four effective caps>}
     :concurrent-streams {:active N :limit N :at-capacity? bool}
     :rate-limit {:capacity N :tokens <float> :initialized? bool :throttling? bool}
     :abuse-window {:count N :threshold N :window-ms N :tripped? bool}
     :cross-check <hint string>}"
  [_conn _args]
  (let [cfg          (resource/current-config)
        active       (resource/active-stream-count)
        stream-limit (:max-concurrent-streams cfg)
        abuse-count  (resource/abuse-window-size)
        threshold    (:abuse-overflow-threshold cfg)]
    (js/Promise.resolve
      (wire/ok-text
        {:ok?    true
         :config cfg
         :concurrent-streams {:active        active
                              :limit         stream-limit
                              :at-capacity?  (>= active stream-limit)}
         :rate-limit         (rate-bucket-view cfg)
         :abuse-window       {:count     abuse-count
                              :threshold threshold
                              :window-ms (:abuse-window-ms cfg)
                              ;; abuse fires when count EXCEEDS threshold;
                              ;; equal-to is the last clean tick.
                              :tripped?  (> abuse-count threshold)}
         :cross-check (str "Cross-check :active against `list-streams` row count. "
                           "A server :active with no matching list-streams row = a leaked "
                           "server slot (stream died without releasing); the reverse = a "
                           "stale runtime subscription. This tool reads SERVER state only "
                           "(no nREPL); run `list-streams` for the runtime side.")}))))
