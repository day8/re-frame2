(ns re-frame-pair2-mcp.tools.resource-controls
  "Session-wide resource controls for streaming surfaces (rf2-3ijbl).

  An MCP session is one stdio-attached server process — see
  `cache.cljs` (rf2-3rt1f) for the equivalence. Per-sub queue caps and
  drop-oldest-on-overflow already live runtime-side (rf2-ho4ve), but a
  buggy or hostile client can still:

    1. Open many concurrent `subscribe` calls — each allocates a fresh
       runtime-side queue + poll loop, multiplying memory pressure.
    2. Tighten `poll-ms` to fire ticks faster than the agent host or
       runtime can keep up — the queue evicts hard but the wire still
       fills.
    3. Trip overflow repeatedly — a signal that the consumer is mis-
       configured or hostile; should terminate the stream rather than
       silently churn under pressure.

  This namespace owns three independent gates. All three are
  operator-configurable via CLI flags AND env vars; the parsed defaults
  come from `parse-resource-flags` / `read-resource-env`.

  ## Concurrent-stream cap

  `acquire-stream!` increments the active-stream counter when room
  remains under `max-concurrent-streams` (default 10); returns a
  structured `:rf.error/concurrent-stream-limit` rejection otherwise.
  `release-stream!` decrements when the stream terminates (any reason).
  The counter is a single atom — atomic compare-and-set via `swap-vals!`
  avoids the TOCTOU window between read and increment.

  ## Per-session event rate-limit (token bucket)

  `check-rate!` is called once per delivered event. The session-wide
  token bucket refills at `max-events-per-sec` (default 100) and caps
  at the same value. Excess events are rate-dropped — `check-rate!`
  returns `false` and the tick is silently skipped (counted via
  `rate-dropped-count` for the final summary).

  Token-bucket vs leaky-bucket: token-bucket allows brief bursts up to
  the cap, leaky-bucket smooths uniformly. Streaming trace events are
  bursty by nature (an event triggers a cascade of fx + sub-runs +
  renders in one drain) — token-bucket matches the workload.

  ## Disconnect-on-abuse heuristic

  `record-overflow!` is called whenever a drain reports `:ov-reason`
  non-nil (the runtime's per-sub queue evicted). The session tracks
  overflow events over a rolling window (`abuse-window-ms`, default
  10000 = 10s). When the count exceeds `abuse-overflow-threshold`
  (default 50) in that window, `record-overflow!` returns
  `:abuse-detected` — the caller (the streaming-loop) terminates the
  stream with `:reason :rf.error/stream-abuse-detected` and logs to
  stderr.

  The window is a vector of timestamps; on each call we drop stamps
  older than `abuse-window-ms` and append the new one. O(n) per call
  but n is bounded by the threshold + small margin (the window prunes
  as it grows) — cheap enough for the 100/s rate-limit ceiling.

  ## Why one namespace, not three

  All three gates share:
    - the same session-scope (one process, one stdio session).
    - the same configuration surface (CLI flags + env vars).
    - the same test-fixture pattern (reset between tests).

  Splitting them three ways would force three parallel
  `parse-*-flags` / `read-*-env` / `set-*` triplets without saving any
  per-call cycles. One namespace, three independent atoms, one
  configuration entry point. Mirrors story-mcp's `config.cljc` shape
  (single ns owning all the operator-configurable gates).

  ## Symmetry with sibling gates

  Pre-existing pair2-mcp gates:
    - `eval-cljs/allow-eval?` (`--allow-eval`) — rf2-cxx5s
    - `raw-state/allow-raw-state?` (`--allow-raw-state`) — rf2-c2dtu

  This namespace adds:
    - `max-concurrent-streams` (`--max-concurrent-streams` /
      `RE_FRAME_PAIR2_MCP_MAX_STREAMS`)
    - `max-events-per-sec` (`--max-events-per-sec` /
      `RE_FRAME_PAIR2_MCP_MAX_EVENTS_PER_SEC`)
    - `abuse-overflow-threshold` (`--abuse-overflow-threshold` /
      `RE_FRAME_PAIR2_MCP_ABUSE_OVERFLOW_THRESHOLD`)
    - `abuse-window-ms` (`--abuse-window-ms` /
      `RE_FRAME_PAIR2_MCP_ABUSE_WINDOW_MS`)

  The boolean flags above are opt-in (default OFF, operator must pass
  the flag); these integer caps are opt-OUT (default values active,
  operator overrides if the default is wrong for their workload)."
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Default values — single source of truth, surface in three places:
;;   1. The atoms below (initial values).
;;   2. The CLI / env help text printed at server boot.
;;   3. The spec docs (003-Tool-Catalogue.md §Universal: server resource
;;      controls).
;;
;; Defaults chosen for a typical agent workflow:
;;
;; - 10 streams: an agent rarely needs more than a handful open at once
;;   (one per topic + spare); 10 is generous for normal use and tight
;;   enough to cap pathological loops.
;; - 100 events/sec: at ~5 KB/event (typical epoch record post-
;;   elision), that's ~500 KB/sec — well under the bandwidth of a
;;   local nREPL socket but enough headroom for legitimate bursty
;;   workloads. The runtime poll cadence is 100ms = 10 polls/sec; at
;;   ~10 events per drain on average a healthy stream sits well under
;;   the cap.
;; - 50 overflows in 10s: ~5/sec of sustained eviction. A momentary
;;   overflow (one heavy event landed on a near-full queue) is normal;
;;   sustained eviction at >5/sec means the consumer can't keep up
;;   and the stream should terminate rather than churn forever.
;; ---------------------------------------------------------------------------

(def ^:private defaults
  "The four integer caps — single source of truth. Override via CLI
  flags (`--<flag-name>=<n>`) or env vars (`<ENV_NAME>=<n>`). Names
  match the `parse-resource-flags` / `read-resource-env` keys."
  {:max-concurrent-streams   10
   :max-events-per-sec       100
   :abuse-overflow-threshold 50
   :abuse-window-ms          10000})

(defn default-value
  "Return the documented default for a resource-control gate.

  Exposed for the spec / help text — the operator-facing defaults
  surface in `003-Tool-Catalogue.md` and (future) `server.cljs/main`
  startup banner. Test seam: pins each default against an explicit
  expectation, so a future tuning surfaces as a failing test rather
  than a silent doc drift."
  [k]
  (get defaults k))

;; ---------------------------------------------------------------------------
;; Configuration atoms — settable via `apply-resource-config!`.
;; ---------------------------------------------------------------------------

(defonce ^:private config
  (atom defaults))

(defn current-config
  "Read the current resource-control configuration. Exposed for tests
  + the server-side startup banner."
  []
  @config)

(defn set-config!
  "Replace the resource-control configuration map. Unknown keys are
  ignored; missing keys fall back to defaults. Coerces values via
  `parse-positive-int` so string-form configuration (env vars, JSON
  args) flows through one parser. Returns the new merged config."
  [m]
  (let [coerced (into {} (for [[k v] m
                               :when (contains? defaults k)
                               :let  [n (parse-long (str v))]
                               :when (and n (pos? n))]
                           [k n]))
        merged  (merge defaults coerced)]
    (reset! config merged)
    merged))

;; ---------------------------------------------------------------------------
;; Concurrent-stream cap.
;; ---------------------------------------------------------------------------

(defonce ^:private active-streams
  ;; Count of currently-acquired streaming subscriptions. Atomic
  ;; compare-and-set via `swap-vals!` in `acquire-stream!` so two
  ;; concurrent `subscribe` calls can't both squeak past the cap at
  ;; the boundary tick.
  (atom 0))

(defn active-stream-count
  "Read the current active-stream count. Exposed for tests +
  subscription-info diagnostics."
  []
  @active-streams)

(defn acquire-stream!
  "Atomically reserve a slot for a new stream. Returns `{:ok? true}`
  when capacity remains; otherwise `{:ok? false :reason
  :rf.error/concurrent-stream-limit :limit N :active N}`.

  The check-and-increment is one `swap-vals!` so two concurrent
  `subscribe` calls can't both observe `< limit` and both increment
  past it. CLJS's single-threaded JS runtime makes this academic for
  the current callsites — but the contract is right and a future
  parallel-pump runtime won't break it.

  Caller (subscribe-tool) MUST call `release-stream!` on every exit
  path (normal termination, error, abort) so the counter doesn't
  leak — pair with try/finally semantics at the promise boundary."
  []
  (let [limit (:max-concurrent-streams @config)
        [old new] (swap-vals! active-streams
                              (fn [n] (if (< n limit) (inc n) n)))]
    (if (= old new)
      {:ok?    false
       :reason :rf.error/concurrent-stream-limit
       :limit  limit
       :active old
       :hint   (str "max-concurrent-streams cap reached. "
                    "Close an existing subscription (via the `unsubscribe` "
                    "tool or by cancelling its `tools/call`) before opening "
                    "another, or raise the cap with "
                    "--max-concurrent-streams=N at server launch.")}
      {:ok? true :active new :limit limit})))

(defn release-stream!
  "Decrement the active-stream count. Idempotent at the floor (clamps
  at zero) — a double-release on a buggy termination path doesn't
  drive the counter negative."
  []
  (swap! active-streams #(max 0 (dec %)))
  nil)

;; ---------------------------------------------------------------------------
;; Per-session event rate-limit (token bucket).
;; ---------------------------------------------------------------------------
;;
;; One bucket for the whole session — refill rate = bucket cap =
;; `max-events-per-sec`. The bucket holds fractional tokens; we refill
;; lazily on each `check-rate!` based on elapsed milliseconds since the
;; last refill.

(defonce ^:private rate-bucket
  ;; `{:tokens <float> :last-refill-ms <epoch-ms>}`. Initialised on
  ;; first `check-rate!` call (lazy: we don't know `max-events-per-sec`
  ;; until launch flags / env are applied).
  (atom nil))

(defn- now-ms []
  (.now js/Date))

(defn- refill-bucket
  "Pure helper — refill the bucket based on elapsed-ms since `last`.
  Caps at the configured rate (bucket size = refill rate)."
  [{:keys [tokens last-refill-ms]} max-per-sec now]
  (let [elapsed-ms  (max 0 (- now last-refill-ms))
        added       (* (/ elapsed-ms 1000.0) max-per-sec)
        new-tokens  (min (double max-per-sec) (+ tokens added))]
    {:tokens         new-tokens
     :last-refill-ms now}))

(defn check-rate!
  "Try to consume one token from the rate-limit bucket. Returns `true`
  if a token was available (the caller may proceed to emit the event);
  `false` if the bucket is empty (the caller should drop the event
  silently and tally it as rate-dropped on the stream's final summary).

  Lazy initialisation: the bucket is filled to its cap on first call
  so a fresh session doesn't pay a ramp-up penalty. Reset between
  tests via `reset-rate-bucket!`.

  ## Return semantics

  The atom's `:consumed?` slot records whether THIS swap consumed a
  token. A read of `(:tokens new)` alone can't discriminate the two
  failure-adjacent shapes: after a successful consume the bucket may
  sit at 0.0; after a denied consume on a refilled bucket the bucket
  may sit at 0.5. Surfacing `:consumed?` on the swap result is the
  unambiguous signal."
  []
  (let [max-per-sec (:max-events-per-sec @config)
        now         (now-ms)
        new-state
        (swap! rate-bucket
               (fn [state]
                 (let [s  (or state {:tokens         (double max-per-sec)
                                     :last-refill-ms now})
                       s' (refill-bucket s max-per-sec now)]
                   (if (>= (:tokens s') 1.0)
                     (-> s' (update :tokens - 1.0) (assoc :consumed? true))
                     (assoc s' :consumed? false)))))]
    (boolean (:consumed? new-state))))

(defn reset-rate-bucket!
  "Clear the rate-bucket so the next `check-rate!` reinitialises.
  Exposed for tests."
  []
  (reset! rate-bucket nil))

(defn current-tokens
  "Read the current token count. Exposed for tests; returns the float
  tokens-remaining or `nil` if the bucket hasn't been initialised."
  []
  (:tokens @rate-bucket))

;; ---------------------------------------------------------------------------
;; Disconnect-on-abuse heuristic — rolling window of overflow timestamps.
;; ---------------------------------------------------------------------------
;;
;; Each session keeps a single vector of overflow timestamps (one slot
;; per overflowing drain). On each `record-overflow!` call we:
;;
;;   1. Drop stamps older than `abuse-window-ms`.
;;   2. Append `now`.
;;   3. If the remaining vector length exceeds `abuse-overflow-threshold`,
;;      return `:abuse-detected`. The caller terminates the stream.
;;
;; The vector grows to at most `threshold + 1` before pruning fires —
;; bounded memory, O(n) per call where n is small.

(defonce ^:private abuse-window
  (atom []))

(defn- prune-window
  "Drop overflow stamps older than `window-ms` before `now`."
  [stamps window-ms now]
  (let [cutoff (- now window-ms)]
    (filterv #(> % cutoff) stamps)))

(defn record-overflow!
  "Record one overflow event against the session's abuse-detection
  window. Returns `:abuse-detected` when the count over the window
  exceeds the threshold; returns `:ok` otherwise.

  The streaming loop calls this once per drain that reports
  `:ov-reason` non-nil (a runtime queue-eviction trip). When the count
  exceeds threshold over `abuse-window-ms`, the caller should
  terminate the stream with
  `:reason :rf.error/stream-abuse-detected` and log to stderr — the
  consumer is sustainedly failing to keep up and continuing the
  stream just burns CPU + wire bandwidth."
  []
  (let [{:keys [abuse-window-ms abuse-overflow-threshold]} @config
        now (now-ms)
        new-stamps
        (swap! abuse-window
               (fn [stamps]
                 (-> stamps
                     (prune-window abuse-window-ms now)
                     (conj now))))]
    (if (> (count new-stamps) abuse-overflow-threshold)
      :abuse-detected
      :ok)))

(defn reset-abuse-window!
  "Clear the abuse-detection window. Exposed for tests + per-stream
  reset (each stream gets a fresh window; abuse on one stream doesn't
  carry over to the next subscribe call after the offender terminates).

  Actually — the design is session-wide, not per-stream: a hostile
  client opening one abusive stream then another should still trip the
  detection. The reset is for the test-fixture path only; in
  production the window is monotone across the session's lifetime."
  []
  (reset! abuse-window []))

(defn abuse-window-size
  "Read the current overflow-window size (after pruning happens on
  the next `record-overflow!` call). Exposed for tests."
  []
  (count @abuse-window))

;; ---------------------------------------------------------------------------
;; Configuration parsing — launch flags + env vars.
;; ---------------------------------------------------------------------------
;;
;; Each gate accepts an integer via either a CLI flag (`--<name>=N`)
;; or an env var (`<ENV_NAME>=N`). CLI flags win on conflict (the
;; operator who passes the flag at the command line is making the
;; more deliberate choice).

(def ^:private flag->key
  "Map of CLI-flag prefix → config key. Single source of truth; both
  the parser and the documentation reflect from this map."
  {"--max-concurrent-streams"   :max-concurrent-streams
   "--max-events-per-sec"       :max-events-per-sec
   "--abuse-overflow-threshold" :abuse-overflow-threshold
   "--abuse-window-ms"          :abuse-window-ms})

(def ^:private env->key
  "Map of env-var name → config key. Symmetric with `flag->key` — same
  four gates, different config surface."
  {"RE_FRAME_PAIR2_MCP_MAX_STREAMS"              :max-concurrent-streams
   "RE_FRAME_PAIR2_MCP_MAX_EVENTS_PER_SEC"       :max-events-per-sec
   "RE_FRAME_PAIR2_MCP_ABUSE_OVERFLOW_THRESHOLD" :abuse-overflow-threshold
   "RE_FRAME_PAIR2_MCP_ABUSE_WINDOW_MS"          :abuse-window-ms})

(defn- parse-flag-value
  "Parse one CLI-flag string of the form `--name=value`. Returns
  `[key int-value]` when the flag matches a known prefix and the
  value parses to a positive integer; `nil` otherwise.

  Accepts the `--name=value` form only — there's no two-arg
  `--name value` shape so a future flag addition doesn't have to
  reason about position-sensitive parsing. Mirrors the cross-MCP
  convention (`--max-tokens=N`)."
  [flag]
  (let [[prefix raw-val] (str/split flag #"=" 2)]
    (when-let [k (get flag->key prefix)]
      (when-let [n (parse-long (or raw-val ""))]
        (when (pos? n)
          [k n])))))

(defn parse-resource-flags
  "Parse `--max-concurrent-streams=N` / `--max-events-per-sec=N` /
  `--abuse-overflow-threshold=N` / `--abuse-window-ms=N` out of the
  raw argv. Returns a map of `{config-key int-value}` covering the
  flags that were present + parsed.

  Symmetric with `server/parse-launch-flags`: takes a vector of
  argv strings, returns the parsed slice. Unknown flags are ignored.

  Public so tests can pin the parser shape directly."
  [argv]
  (->> argv
       (keep parse-flag-value)
       (into {})))

(defn read-resource-env
  "Read resource-control configuration from `process.env`. Returns
  a map of `{config-key int-value}` covering the env vars that were
  set + parsed to a positive integer. Env vars that aren't set, are
  blank, or don't parse, are silently skipped (the per-key default
  applies).

  Called once at boot by `server.cljs/main`. Public so tests can
  exercise the env-parsing path with a stubbed `process.env`."
  ([]
   (read-resource-env (j/get js/process :env)))
  ([env-obj]
   (->> env->key
        (keep (fn [[var-name k]]
                (let [raw (some-> env-obj (j/get var-name))]
                  (when (and (string? raw) (seq raw))
                    (when-let [n (parse-long raw)]
                      (when (pos? n)
                        [k n]))))))
        (into {}))))

(defn merge-config
  "Merge the env + CLI-flag config maps. CLI flags win on conflict —
  the operator who passes the flag at the command line is making the
  more deliberate choice than one inherited from environment.

  Public so tests can pin the precedence directly."
  [env-cfg flag-cfg]
  (merge env-cfg flag-cfg))

(defn apply-resource-config!
  "Wire the parsed configuration into the runtime atoms. Called once
  by `server.cljs/main` after `parse-resource-flags` + `read-resource-env`.

  Returns the applied map — `set-config!`'s post-merge value so the
  caller sees the actual gates in force (with defaults filled in for
  keys the operator didn't override). Returning the bare merge of
  overrides (without defaults) caused a startup-banner regression
  where unset gates printed as blank values."
  [env-cfg flag-cfg]
  (set-config! (merge-config env-cfg flag-cfg)))

;; ---------------------------------------------------------------------------
;; Test-fixture helpers — full reset between tests.
;; ---------------------------------------------------------------------------

(defn reset-for-tests!
  "Reset every gate to its documented default + clear all transient
  state (active-stream count, rate bucket, abuse window). Exposed for
  test fixtures — one call between tests = a clean slate."
  []
  (reset! config defaults)
  (reset! active-streams 0)
  (reset! rate-bucket nil)
  (reset! abuse-window []))
