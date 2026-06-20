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
      :heartbeat-age-ms     ms since the most recent runtime's last
                            ping/pong exchange (the WS heartbeat). High
                            or nil ⇒ the WS is stale / no runtime.

  ## The cross-check verdict

  With both halves we compute `:liveness` — a single keyword an agent
  pattern-matches before trusting a read:

    :fresh           runtime connected, heartbeat recent, build NOT
                     recompiled since the runtime loaded.
    :stale-build     the build flushed AFTER the runtime loaded — the
                     browser is serving OLD code. RELOAD THE PAGE.
    :no-runtime      no REPL runtime connected for the build (WS dropped
                     / no tab open).
    :unknown         the JVM half couldn't be read after a retry — degrade
                     to browser-half-only, never crash. The dominant cause
                     is MULTIPLE / ZOMBIE shadow-cljs JVMs (a stale watch
                     Ctrl-C'd without freeing its ports; the socket reaches
                     a runtime whose build worker is in a DIFFERENT JVM, so
                     the worker lookup misses). The hint names the
                     `npx shadow-cljs stop` → single-watch remediation.
                     Rarer: an old shadow without get-worker, or a
                     transient socket hiccup.

  Everything here is best-effort: a JVM probe failure degrades to the
  browser half plus `:liveness :unknown`. The token is a DIAGNOSTIC
  signal, never a hard gate — a tool still runs; the agent just sees the
  staleness."
  (:require [cljs.reader]
            [re-frame2-pair-mcp.nrepl :as nrepl]))

;; ---------------------------------------------------------------------------
;; JVM-side build-state probe.
;; ---------------------------------------------------------------------------
;;
;; shadow-cljs holds, per build worker, a `:build-state` whose
;; `::build/build-info` carries `:compile-cycle` (monotonic) and
;; `:flush-complete` (ms of the last flush). The worker's `:runtimes`
;; map carries each connected REPL runtime's `:last-ping` / `:last-pong`
;; (the WS heartbeat). We read both JVM-side in one form, defending
;; every access so an old shadow / a build with no worker collapses to
;; nil rather than throwing — the caller degrades to `:liveness
;; :unknown`.
;;
;; We reach the worker via the public-ish `get-worker` (used elsewhere
;; in shadow's own API) and deref its `:state-ref`. `compile-cycle` /
;; `flush-complete` live under the namespaced `:shadow.build/build-info`
;; key on the build-state; we read it by its fully-qualified keyword so
;; we don't depend on a `require` of shadow's build ns on the eval
;; classpath.

(defn- build-state-jvm-form
  "JVM-side form returning a freshness map for `build-id`, or nil on any
  failure. Reads shadow's build worker state-ref:

    {:compile-cycle    <int or nil>
     :build-flushed-at <ms or nil>
     :runtime-count    <int>
     :heartbeat-age-ms <ms or nil>}

  `build-id` is rendered as its keyword literal. Every access is
  `try`-guarded; a missing worker / old shadow / unexpected shape
  collapses to nil so the caller degrades cleanly."
  [build-id]
  (let [bid (if (keyword? build-id) (str build-id) (str ":" (name (or build-id :app))))]
    (str
      "(try"
      "  (let [sup (:supervisor (shadow.cljs.devtools.server.runtime/get-instance!))"
      "        worker (when sup (shadow.cljs.devtools.server.supervisor/get-worker sup " bid "))"
      "        st (some-> worker :state-ref deref)"
      "        bs (:build-state st)"
      "        info (get bs :shadow.build/build-info)"
      "        runtimes (vals (:runtimes st))"
      ;; most-recent heartbeat across connected runtimes: max last-pong,
      ;; then age = now - that. nil when no runtime is connected.
      "        last-pong (when (seq runtimes)"
      "                    (apply max (keep :last-pong runtimes)))"
      "        now (System/currentTimeMillis)]"
      "    {:compile-cycle    (:compile-cycle info)"
      "     :build-flushed-at (:flush-complete info)"
      "     :runtime-count    (count runtimes)"
      "     :heartbeat-age-ms (when last-pong (- now last-pong))})"
      "  (catch Throwable _ nil))")))

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
  `build-id`. Resolves to the parsed map or nil (unreadable / blank /
  non-map / socket hiccup). The single-attempt primitive
  `jvm-build-freshness` retries over this."
  [conn build-id]
  (-> (try
        (nrepl/jvm-eval conn (build-state-jvm-form build-id))
        (catch :default _ (js/Promise.resolve nil)))
      (.then (fn [resp]
               (let [v (some-> (:value resp) cljs.reader/read-string)]
                 (when (map? v) v))))
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
  after the retry IS the real `:unknown` (old shadow with no
  `get-worker`, or a dead worker), and now the caller's hint can name the
  concrete next step honestly."
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
    :unknown       the JVM half is absent (couldn't read shadow state —
                   most often a multiple/zombie-shadow JVM split).

  Order matters: `:no-runtime` wins over `:stale-build` (you can't be
  serving stale code if nothing's connected), and both win over
  `:fresh`."
  [{:keys [runtime-loaded-at build-flushed-at
           runtime-count heartbeat-age-ms jvm-read?]}]
  (cond
    (not jvm-read?)
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

    :else
    :fresh))

(defn- reload-target
  "The concrete thing the human reloads to wake/refresh the runtime.
  Prefer the app URL when the port is known (the agent then
  relays ONE crisp instruction — `reload http://localhost:<port>`);
  otherwise name the build so `shadow-cljs watch <build>` is unambiguous."
  [{:keys [port build-id]}]
  (cond
    (number? port) (str "http://localhost:" port)
    (some? port)   (str "http://localhost:" port)
    (some? build-id) (str "the app served by build " (pr-str build-id))
    :else          "the app tab"))

(defn- verdict-hint
  "Operator-facing one-liner for a non-fresh verdict. nil for `:fresh`.

  `ctx` carries the optional `:port` (the browser URL port discover-app
  resolved the build from) + `:build-id` so the `:no-runtime` / `:unknown`
  hints can name the EXACT thing the human reloads — the agent cannot
  reload a browser itself, so a non-fresh verdict is an early, crisp
  human-in-the-loop instruction, not a self-heal."
  [liveness {:keys [build-flushed-at runtime-loaded-at build-id] :as ctx}]
  (let [target (reload-target ctx)]
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
           "will return blank until one reconnects. ACTION (the agent "
           "cannot do this): open or reload " target ", then re-run "
           "discover-app to confirm :liveness :fresh.")

      :unknown
      (str "LIVENESS UNKNOWN: could not read the shadow-cljs build worker "
           "state after a retry. The browser-side instance id + load time "
           "are still reported and reads may still work, but stale-build "
           "detection is unavailable this call. Most common cause: MULTIPLE / "
           "ZOMBIE shadow-cljs JVMs — a stale watch (Ctrl-C does not always "
           "free shadow's ports) left an orphan JVM, and the nREPL socket "
           "reached a runtime whose build worker lives in a DIFFERENT JVM, so "
           "the worker lookup misses. (Less common: an old shadow without "
           "get-worker, or the watch worker is down.) ACTION (the agent "
           "cannot do this): run `npx shadow-cljs stop` to kill ALL shadow "
           "JVMs, then start exactly ONE `shadow-cljs watch "
           (if (some? build-id) (pr-str build-id) "<build>") "`, reload "
           target ", and re-run discover-app to confirm :liveness :fresh. If "
           "it still stays unknown after a single clean watch, the JVM-side "
           "build worker genuinely is not answering (old shadow).")

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
     :heartbeat-age-ms    <ms|nil>    ; JVM
     :build-id            <kw>
     :liveness            <:fresh|:stale-build|:no-runtime|:unknown>
     :hint                <str>}      ; only when non-fresh

  The optional 4-arity `opts` carries `:port` (the browser URL port the
  caller knows — discover-app's `:port` arg) so a non-fresh hint names
  the EXACT `http://localhost:<port>` the human reloads.
  `:port` rides on the token too, so the agent can relay it.

  Best-effort: a nil JVM half degrades to `:liveness :unknown` with the
  browser half intact. Never rejects."
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
                liveness (liveness-verdict (assoc merged :jvm-read? jvm?))
                hint     (verdict-hint liveness merged)]
            (cond-> (assoc merged :liveness liveness)
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
                           :hint                (verdict-hint
                                                  :unknown
                                                  (assoc browser :build-id build-id
                                                                 :port port))}
                    (some? port) (assoc :port port))))))))

(defn token-from-health
  "Convenience: extract the browser half from a `health` map and
  assemble the full token. `health` carries `:runtime-instance-id`,
  `:runtime-loaded-at`, and `:read-at`. Returns a
  Promise of the token map.

  The optional 4-arity `opts` carries `:port` so a non-fresh
  hint can name `http://localhost:<port>` — the EXACT URL the human
  reloads to wake / refresh a quiet runtime."
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
