(ns re-frame2-pair-mcp.tools.freshness
  "Freshness / liveness token.

  ## The problem this closes

  A pair session reads live runtime state across a chain of moving
  parts — the MCP server holds an nREPL socket, shadow-cljs holds a
  build worker, a browser tab holds the running CLJS heap. Any link can
  drift from the others WITHOUT failing loudly:

    - the browser tab refreshed → the CLJS heap (and every cached
      handle) reset, but the socket is still up;
    - the WebSocket to the runtime dropped → eval round-trips return
      blank, indistinguishable from a form that genuinely returns nil;
    - a STALE incremental build is serving OLD code → the runtime is
      alive and answering, but it's running code from before your last
      edit. THIS is the silent killer — a stale build masquerades as a
      runtime bug and can burn long debugging detours.

  The freshness token makes all three OBVIOUS up front, on the very
  first `discover-app` call (and optionally on any op result). The
  principle: the pair session is a THIN, runtime-direct reader — read
  truth each call — that ALSO carries liveness so the operator never
  mistakes stale data for wrong data.

  ## The two halves

  The token has a BROWSER half and a JVM half, assembled here:

    Browser half (from `(re-frame2-pair.runtime/freshness)` or the
    `health` map — only the running CLJS heap knows these):
      :runtime-instance-id  per-preload UUID; changes on page reload.
      :runtime-loaded-at    wall-clock ms when the running code loaded.
      :read-at              wall-clock ms at the moment of the read.

    JVM half (from the shadow-cljs build worker — only the JVM holds the
    worker state; read via `jvm-eval`):
      :build-id             the build the runtime belongs to.
      :compile-cycle        MONOTONIC build counter (shadow's
                            `::build/build-info :compile-cycle`). Bumps
                            on every successful compile. This is the
                            'is my edit live?' monotonic id.
      :build-flushed-at     wall-clock ms of the build's last flush
                            (shadow's `:flush-complete`). The compile
                            that produced the bytes currently on disk.
      :runtime-count        connected REPL runtimes for this build.
      :heartbeat-age-ms     ms since the freshest heartbeat among THIS
                            build's runtimes (the relay stamps every
                            message a runtime sends). High ⇒ the WS is
                            stale; absent ⇒ no usable heartbeat sample.

  ## The cross-check verdict

  With both halves we compute `:liveness` — a single keyword an agent
  pattern-matches before trusting a read:

    :fresh           runtime connected, heartbeat recent, build NOT
                     recompiled since the runtime loaded.
    :stale-build     the build flushed AFTER the runtime loaded — the
                     browser is serving OLD code. RELOAD THE PAGE.
    :no-runtime      no REPL runtime connected for the build (WS dropped
                     / no tab open).
    :unknown         freshness could not be verified. `:unknown-reason`
                     says what was missing:
                       :jvm-unreadable        the build state could not be
                                              read after a retry;
                       :no-build-worker       the JVM this socket reaches
                                              runs no worker for the build;
                       :heartbeat-unavailable runtimes are connected but
                                              none has a usable heartbeat.
                     Missing data never reads as `:fresh`, and the hint
                     names one bounded next step — never stopping or
                     restarting shadow-cljs processes, which nothing
                     here can see.

  Everything here is best-effort: a JVM probe failure degrades to the
  browser half plus `:liveness :unknown`, keeping whatever worker, build
  and runtime facts were read. The token is a DIAGNOSTIC signal, never a
  hard gate — a tool still runs; the agent just sees the staleness."
  (:require [cljs.reader]
            [re-frame2-pair-mcp.nrepl :as nrepl]))

;; ---------------------------------------------------------------------------
;; JVM-side build-state probe.
;; ---------------------------------------------------------------------------
;;
;; shadow-cljs holds, per build worker, a `:build-state` whose
;; `::build/build-info` carries `:compile-cycle` (monotonic) and
;; `:flush-complete` (ms of the last flush), and a `:runtimes` map of the
;; build's connected runtimes keyed by relay client id.
;;
;; The heartbeat lives on the RELAY, not the worker. shadow's local relay
;; keeps every client it serves under `:clients`, keyed by the same client
;; id, and stamps that client's `:last-pong` on every message the client
;; sends — its answers to the relay's idle pings included. The worker
;; runtime's own `:last-pong` is written only by a `:cljs-repl-pong`
;; reply, which current shadow-cljs never solicits, so on a healthy tab
;; it stays absent. Both are read: a runtime's heartbeat is the freshest
;; usable timestamp across its relay entry and its worker entry.
;;
;; The relay serves other builds' tabs, tools and the CLJ runtime too, so
;; a heartbeat counts only when its client id is one of THIS build's
;; runtimes. That match, the timestamp checks and the reduction all run
;; Node-side in `summarise-build-state`: the JVM form only projects, so an
;; empty or partial sample cannot throw there and erase the worker, build
;; and runtime facts the read did get.
;;
;; We reach the worker via the public-ish `get-worker` (used elsewhere
;; in shadow's own API) and deref its `:state-ref`. `compile-cycle` /
;; `flush-complete` live under the namespaced `:shadow.build/build-info`
;; key on the build-state; we read it by its fully-qualified keyword so
;; we don't depend on a `require` of shadow's build ns on the eval
;; classpath.

(defn- build-state-jvm-form
  "JVM-side form returning the raw build-state facts for `build-id`, or
  nil on any failure:

    {:worker?          <bool>         ; a worker exists for the build
     :compile-cycle    <int or nil>
     :build-flushed-at <ms or nil>
     :runtimes         {<client-id> {:last-pong <ms or nil>}}  ; the build's
     :relay-clients    {<client-id> {:last-pong <ms or nil>}}  ; every client
     :now              <ms>}          ; read after both snapshots

  `build-id` is rendered as its keyword literal via
  `nrepl/build-id-literal`, which refuses (nil → this fn throws, and the
  caller's `try` degrades to nil) an id that would not print as a single
  keyword: this form is evaluated as Clojure on the shadow JVM, so a
  multi-token id would run as code. The form is `try`-guarded and reduces
  nothing; a missing worker reads as `:worker? false` with empty maps."
  [build-id]
  (let [bid (or (nrepl/build-id-literal build-id)
                (throw (ex-info (str "Refusing the freshness read for build id " (pr-str build-id)
                                     ": it does not print as a single keyword."
                                     " [" :rf.error/pair-mcp-malformed-build-id "]")
                                {:rf.error/id :rf.error/pair-mcp-malformed-build-id
                                 :build       (pr-str build-id)})))]
    (str
      "(try"
      "  (let [inst (shadow.cljs.devtools.server.runtime/get-instance!)"
      "        sup (:supervisor inst)"
      "        worker (when sup (shadow.cljs.devtools.server.supervisor/get-worker sup " bid "))"
      "        st (some-> worker :state-ref deref)"
      "        info (get (:build-state st) :shadow.build/build-info)"
      "        pongs (fn [m] (into {} (map (fn [[id x]] [id {:last-pong (:last-pong x)}])) m))"
      "        runtimes (pongs (:runtimes st))"
      "        clients (pongs (some-> inst :relay :state-ref deref :clients))"
      ;; Read the clock AFTER both snapshots, so a heartbeat stamped
      ;; while they were taken can never read as later than `now`.
      "        now (System/currentTimeMillis)]"
      "    {:worker?          (some? worker)"
      "     :compile-cycle    (:compile-cycle info)"
      "     :build-flushed-at (:flush-complete info)"
      "     :runtimes         runtimes"
      "     :relay-clients    clients"
      "     :now              now})"
      "  (catch Throwable _ nil))")))

(defn- heartbeat-at
  "`t` when it is a usable heartbeat timestamp for a read taken at `now`:
  a finite, positive number no later than the read. Anything else — nil,
  a string, a timestamp from the future — is not evidence of a live
  socket."
  [t now]
  (when (and (number? t) (js/isFinite t) (pos? t) (number? now) (<= t now))
    t))

(defn summarise-build-state
  "The JVM half of the token from the raw facts `build-state-jvm-form`
  returns:

    {:worker?          <bool>
     :compile-cycle    <int or nil>
     :build-flushed-at <ms or nil>
     :runtime-count    <int>
     :heartbeat-age-ms <ms>}          ; only with a usable sample

  A heartbeat is taken ONLY from the build's own runtimes: each worker
  runtime id is looked up in the relay's client table, and the runtime's
  own worker entry is read alongside it. A relay client that is not one
  of these runtimes contributes nothing, whatever it reports. With no
  usable sample `:heartbeat-age-ms` is absent, never guessed."
  [{:keys [worker? compile-cycle build-flushed-at runtimes relay-clients now]}]
  (let [samples (for [[id rt] runtimes
                      t       [(:last-pong (get relay-clients id)) (:last-pong rt)]
                      :let    [t (heartbeat-at t now)]
                      :when   t]
                  t)]
    (cond-> {:worker?          (true? worker?)
             :compile-cycle    compile-cycle
             :build-flushed-at build-flushed-at
             :runtime-count    (count runtimes)}
      (seq samples) (assoc :heartbeat-age-ms (- now (apply max samples))))))

(defn- conn-has-socket?
  "True when the conn-atom carries a live (non-closed) socket. A conn
  with no socket (a fresh test stub, or a never-connected conn) has no
  JVM half to read — probing it would attempt a real TCP connect. We
  skip straight to nil so the caller degrades to `:liveness :unknown`
  without a socket round-trip. Defensive against a nil / non-deref conn
  (conformance stubs)."
  [conn]
  (and (some? conn)
       (satisfies? IDeref conn)
       (let [{:keys [socket closed?]} @conn]
         (and (some? socket) (not closed?)))))

(defn- jvm-build-freshness-once
  "One JVM round-trip reading the build-worker freshness half for
  `build-id`. Resolves to the summarised half (`summarise-build-state`)
  or nil (unreadable / blank / non-map / socket hiccup). The
  single-attempt primitive `jvm-build-freshness` retries over this."
  [conn build-id]
  (-> (try
        (nrepl/jvm-eval conn (build-state-jvm-form build-id))
        (catch :default _ (js/Promise.resolve nil)))
      (.then (fn [resp]
               (let [v (some-> (:value resp) cljs.reader/read-string)]
                 (when (map? v) (summarise-build-state v)))))
      (.catch (fn [_] nil))))

(defn retry-once-on-nil
  "Run the Promise-returning thunk `read!`; if it resolves to a non-map
  (nil / blank), run it ONCE more and return that. A nil read
  of the build-worker state is most often a TRANSIENT socket hiccup, so
  one retry recovers the common case before the caller degrades to the
  non-actionable `:liveness :unknown`. The retry round-trip is paid only
  on the cold/degraded path — a map-valued first read returns straight
  away with no second call. Pure combinator (no nREPL coupling) so it's
  unit-testable without a global var stub."
  [read!]
  (-> (read!)
      (.then (fn [v] (if (map? v) v (read!))))
      (.catch (fn [_] nil))))

(defn jvm-build-freshness
  "Read the JVM-side freshness half for `build-id`. Returns a Promise
  resolving to the parsed map (see `build-state-jvm-form`) or nil on any
  failure — a nil result is the caller's signal to degrade to the
  browser half with `:liveness :unknown`.

  Short-circuits to nil when the conn has no live socket (no JVM to
  read), so a never-connected / stub conn never triggers a real TCP
  connect.

  ## One retry before degrading

  A `:liveness :unknown` verdict is degraded and non-actionable — the
  agent can't tell the runtime is live and only finds out when a later
  read returns blank. A nil first read is most often a TRANSIENT socket
  hiccup (the bencode round-trip raced a `data` chunk, the worker was
  mid-flush), not a genuinely-unreadable old shadow. So when the first
  read comes back nil we retry ONCE before degrading — a single extra
  ~5-50ms round-trip on the cold/degraded path only, never on the
  healthy path (the first read already succeeds there). A persistent nil
  after the retry IS the real `:unknown :jvm-unreadable`, and the
  caller's hint names the concrete next step honestly."
  [conn build-id]
  (if-not (conn-has-socket? conn)
    (js/Promise.resolve nil)
    (retry-once-on-nil #(jvm-build-freshness-once conn build-id))))

;; ---------------------------------------------------------------------------
;; Cross-check verdict.
;; ---------------------------------------------------------------------------

(def ^:private heartbeat-stale-ms
  "WS heartbeat older than this ⇒ treat the runtime connection as
  suspect. shadow pings on a short cadence; 30s of silence means the
  socket is very likely dead. Conservative — a `:no-runtime` verdict on
  a merely-slow runtime is recoverable (re-discover), whereas calling a
  dead socket `:fresh` is the failure we're closing."
  30000)

(defn liveness-verdict
  "Compute the single `:liveness` keyword from the merged token halves.

    :no-runtime    JVM half reports zero connected runtimes (or a
                   heartbeat older than `heartbeat-stale-ms`).
    :stale-build   the build flushed AFTER the runtime loaded — the
                   browser is serving old code (RELOAD).
    :fresh         runtime connected, heartbeat recent, build not
                   recompiled since load.
    :unknown       the JVM half is absent, the reached JVM has no worker
                   for the build (`:worker? false`), or the connected
                   runtimes carry no usable heartbeat.

  Order matters: `:no-runtime` wins over `:stale-build` (you can't be
  serving stale code if nothing's connected), and `:stale-build` wins
  over a missing heartbeat, which needs no heartbeat to prove. `:fresh`
  needs a heartbeat: a connected runtime with none is unverified."
  [{:keys [runtime-loaded-at build-flushed-at
           runtime-count heartbeat-age-ms jvm-read? worker?]}]
  (cond
    (or (not jvm-read?) (false? worker?))
    :unknown

    (or (nil? runtime-count) (zero? runtime-count)
        (and (number? heartbeat-age-ms) (> heartbeat-age-ms heartbeat-stale-ms)))
    :no-runtime

    ;; The stale-BUILD detector: the build's last flush is strictly
    ;; newer than the moment the running code loaded ⇒ a recompile
    ;; landed on disk that the browser tab never picked up.
    (and (number? build-flushed-at)
         (number? runtime-loaded-at)
         (> build-flushed-at runtime-loaded-at))
    :stale-build

    (not (number? heartbeat-age-ms))
    :unknown

    :else
    :fresh))

(defn- unknown-reason
  "Which fact an `:unknown` verdict is missing — the same inputs as
  `liveness-verdict`, checked in the same order."
  [{:keys [jvm-read? worker?]}]
  (cond
    (not jvm-read?)  :jvm-unreadable
    (false? worker?) :no-build-worker
    :else            :heartbeat-unavailable))

(defn- reload-target
  "The concrete thing to reload to wake/refresh the runtime. Prefer the
  app URL when the port is known (ONE crisp instruction — `reload
  http://localhost:<port>`); otherwise name the build so the target is
  unambiguous."
  [{:keys [port build-id]}]
  (cond
    (number? port) (str "http://localhost:" port)
    (some? port)   (str "http://localhost:" port)
    (some? build-id) (str "the app served by build " (pr-str build-id))
    :else          "the app tab"))

(defn- verdict-hint
  "Operator-facing one-liner for a non-fresh verdict. nil for `:fresh`.

  `ctx` carries the optional `:port` (the browser URL port discover-app
  resolved the build from) + `:build-id` so the `:stale-build` /
  `:no-runtime` hints can name the EXACT thing to reload, and, for
  `:unknown`, the `:unknown-reason` and `:runtime-count` the hint states.
  Whether the agent reloads a browser itself or asks the user is the
  skill's capability-and-ownership check, not this hint's to decide."
  [liveness {:keys [build-flushed-at runtime-loaded-at build-id unknown-reason runtime-count]
             :as   ctx}]
  (let [target (reload-target ctx)
        build  (if (some? build-id) (pr-str build-id) "<build>")]
    (case liveness
      :stale-build
      (str "STALE BUILD: this build recompiled "
           (when (and (number? build-flushed-at) (number? runtime-loaded-at))
             (str (- build-flushed-at runtime-loaded-at) "ms "))
           "AFTER the running browser code loaded — the tab is serving OLD "
           "code. ACTION: reload " target " so the runtime picks up the "
           "latest build before trusting any read or hot-swap.")

      :no-runtime
      (str "NO RUNTIME: no live CLJS runtime is connected to this build "
           "(the WebSocket dropped, or no browser tab is open) — reads "
           "will return blank until one reconnects. ACTION: open or reload "
           target ", then re-run discover-app to confirm :liveness :fresh.")

      :unknown
      (case unknown-reason
        :no-build-worker
        (str "LIVENESS UNKNOWN: the shadow-cljs process this nREPL connection "
             "reaches runs no build worker for " build ", so its build state and "
             "runtime heartbeats cannot be read; treat reads as unverified. "
             "ACTION: confirm " build " is the build serving the app and that its "
             "`shadow-cljs watch` runs in the process whose nREPL port Pair "
             "connected to, then re-run discover-app. Watches for other builds "
             "are unrelated; leave them running.")

        :heartbeat-unavailable
        (str "LIVENESS UNKNOWN: " runtime-count " runtime(s) are connected to "
             build " and its build state was read, but none of them carries a "
             "usable heartbeat timestamp, so the connection's freshness is "
             "unverified. Stale-build detection still ran; reads may work but "
             "are unverified. ACTION: re-run discover-app once. If it stays "
             "unknown, this shadow-cljs version records runtime heartbeats where "
             "Pair does not read them — report that as a Pair compatibility gap. "
             "Restarting the browser or shadow-cljs does not change where the "
             "heartbeat is recorded.")

        (str "LIVENESS UNKNOWN: the shadow-cljs build state for " build " could "
             "not be read over this nREPL connection after a retry, so "
             "stale-build and heartbeat checks are unavailable this call. The "
             "browser-side instance id and load time are still reported; treat "
             "reads as unverified. ACTION: re-run discover-app once. If it stays "
             "unknown, check that the nREPL port Pair connected to belongs to the "
             "shadow-cljs process running `watch` for " build ", and report what "
             "you find; nothing in this reply points at any other shadow-cljs "
             "process."))

      nil)))

(defn assemble
  "Merge the browser half (`browser`, the `(re-frame2-pair.runtime/
  freshness)` / `health` map) with the JVM half (read here) into the
  freshness token. Returns a Promise of the token map:

    {:runtime-instance-id <uuid>      ; browser
     :runtime-loaded-at   <ms>        ; browser
     :read-at             <ms>        ; browser
     :compile-cycle       <int|nil>   ; JVM (monotonic build id)
     :build-flushed-at    <ms|nil>    ; JVM
     :runtime-count       <int|nil>   ; JVM
     :heartbeat-age-ms    <ms>        ; JVM, only with a usable heartbeat
     :build-id            <kw>
     :liveness            <:fresh|:stale-build|:no-runtime|:unknown>
     :unknown-reason      <kw>        ; only when :unknown
     :hint                <str>}      ; only when non-fresh

  The optional 4-arity `opts` carries `:port` (the browser URL port the
  caller knows — discover-app's `:port` arg) so a non-fresh hint names
  the EXACT `http://localhost:<port>` to reload.
  `:port` rides on the token too, so the agent can relay it.

  Best-effort: a nil JVM half degrades to `:liveness :unknown` with the
  browser half intact, and a half with no heartbeat keeps its worker,
  build and runtime facts. Never rejects."
  ([conn build-id browser] (assemble conn build-id browser nil))
  ([conn build-id browser opts]
  (-> (jvm-build-freshness conn build-id)
      (.then
        (fn [jvm]
          (let [browser (or browser {})
                port    (:port opts)
                jvm?    (map? jvm)
                merged  (merge {:runtime-instance-id (:runtime-instance-id browser)
                                :runtime-loaded-at   (:runtime-loaded-at browser)
                                :read-at             (:read-at browser)
                                :build-id            build-id}
                               (when (some? port) {:port port})
                               (when jvm?
                                 (select-keys jvm [:compile-cycle :build-flushed-at
                                                   :runtime-count :heartbeat-age-ms])))
                facts    (assoc merged :jvm-read? jvm? :worker? (:worker? jvm))
                liveness (liveness-verdict facts)
                token    (cond-> (assoc merged :liveness liveness)
                           (= :unknown liveness) (assoc :unknown-reason (unknown-reason facts)))
                hint     (verdict-hint liveness token)]
            (cond-> token
              hint (assoc :hint hint)))))
      (.catch (fn [_]
                ;; Defensive: even an unexpected throw degrades to the
                ;; browser half + :unknown rather than failing the tool.
                (let [browser (or browser {})
                      port    (:port opts)]
                  (cond-> {:runtime-instance-id (:runtime-instance-id browser)
                           :runtime-loaded-at   (:runtime-loaded-at browser)
                           :read-at             (:read-at browser)
                           :build-id            build-id
                           :liveness            :unknown
                           :unknown-reason      :jvm-unreadable
                           :hint                (verdict-hint
                                                  :unknown
                                                  (assoc browser :build-id build-id
                                                                 :port port
                                                                 :unknown-reason :jvm-unreadable))}
                    (some? port) (assoc :port port))))))))

(defn token-from-health
  "Convenience: extract the browser half from a `health` map and
  assemble the full token. `health` carries `:runtime-instance-id`,
  `:runtime-loaded-at`, and `:read-at`. Returns a
  Promise of the token map.

  The optional 4-arity `opts` carries `:port` so a non-fresh
  hint can name `http://localhost:<port>` — the EXACT URL to reload to
  wake / refresh a quiet runtime."
  ([conn build-id health] (token-from-health conn build-id health nil))
  ([conn build-id health opts]
   (assemble conn build-id
             {:runtime-instance-id (:runtime-instance-id health)
              :runtime-loaded-at   (:runtime-loaded-at health)
              :read-at             (:read-at health)}
             opts)))

(defn stale-build?
  "True when the token's verdict is `:stale-build`. Sugar for callers
  that only care about the one alarm."
  [token]
  (= :stale-build (:liveness token)))
