(ns re-frame2-pair-mcp.tools.discover-app
  "Tool: discover-app — verify the stack and probe the preloaded runtime.

  ## Freshness / liveness token (rf2-ertqw)

  Every `:ok? true` discover-app payload carries a `:freshness` token —
  the browser-side runtime-instance-id + load time merged with the
  JVM-side monotonic compile-cycle + last-flush timestamp + WS heartbeat
  age, plus a single `:liveness` verdict (`:fresh` / `:stale-build` /
  `:no-runtime` / `:unknown`). It makes 'the runtime I'm reading is
  stale / disconnected / serving a stale BUILD' obvious on the FIRST
  call — the signal that would have killed the rf2-lo28u stale-build
  false-alarm detour instantly. Assembled by
  `re-frame2-pair-mcp.tools.freshness`."
  (:require [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.freshness :as freshness]))

(defn- port-unresolved-result
  "Error envelope for a `:port` arg that didn't resolve to a build via
  the `:dev-http` map (rf2-fyf0h)."
  [port]
  (wire/ok-text
    {:ok?    false
     :reason :port-unresolved
     :port   port
     :hint   (str "No shadow-cljs build serves port " port " via the "
                  ":dev-http map (the port may be missing from :dev-http, "
                  "or no build's :output-dir matches its file roots). "
                  "Check the port in your browser's address bar, or pass "
                  ":build with the build-id directly.")}))

(defn- resolve-build-id
  "Resolve the build discover-app should probe. Returns a Promise of a
  result map. Precedence (highest first):

    1. Explicit `:build` arg (or a build cached by a prior discover-app)
       — `wire/arg-build` carries a deliberate choice; honour it
       verbatim, never auto-select. Wins over `:port`.
    2. `:port` arg (rf2-fyf0h) — resolve the serving build from the
       shadow-cljs `:dev-http` map so an agent that only knows the
       browser URL (e.g. http://localhost:8031/...) needn't grep the
       repo for the build-id. A `:port` that resolves to no build is a
       loud `:port-unresolved` error — NOT a silent fall-through to the
       `:app` default (the operator asked for that port specifically).
    3. Single running build (rf2-v70kv) — exactly one → auto-select +
       flag it. Zero/many → keep the `:app` default so the diagnostic
       ladder surfaces `:build-not-running` with the running list.

  Return shapes:
    {:build-id <kw> :auto-selected? <bool>}   — proceed to probe.
    {:error <js-envelope>}                     — short-circuit (port-unresolved)."
  [conn args]
  (let [explicit-build (wire/arg-build conn args)
        port           (wire/arg args :port)]
    (cond
      (wire/arg-build-explicit? conn args)
      (js/Promise.resolve {:build-id explicit-build :auto-selected? false})

      (some? port)
      (-> (probe/resolve-build-by-port conn port)
          (.then (fn [resolved]
                   (if resolved
                     {:build-id resolved :auto-selected? false}
                     {:error (port-unresolved-result port)}))))

      :else
      (-> (probe/auto-select-single-build conn)
          (.then (fn [[selected auto?]]
                   (if auto?
                     {:build-id selected :auto-selected? true}
                     {:build-id explicit-build :auto-selected? false})))))))

(defn- with-auto-selection
  "Annotate an `:ok? true` discover-app payload with the auto-selection
  fact (rf2-v70kv). No-op when the build was NOT auto-selected (an
  explicit / cached / default build). When auto-selected, records
  `:auto-selected-build` and either seeds or prepends an auto-selection
  sentence to `:note` so the operator sees which build the no-arg call
  landed on."
  [m auto-selected? build-id]
  (if-not auto-selected?
    m
    (let [sentence (str "No :build arg given and exactly one shadow-cljs "
                        "build is running (" build-id ") — auto-selected it.")]
      (-> m
          (assoc :auto-selected-build build-id)
          (update :note (fn [existing]
                          (if existing (str sentence " " existing) sentence)))))))

(defn- with-frame-resolution
  "Annotate a healthy (non-ambiguous) discover-app payload with the
  resolved operating frame and, when applicable, the auto-resolution
  fact (rf2-3bu3d.4).

  `health` carries `:operating-frame` (the runtime's resolved frame),
  `:frames` (all registered), and `:app-frames` (frames with `:rf/*`
  tool frames removed). When a tool frame was excluded — `:app-frames`
  shorter than `:frames` — AND resolution auto-landed on the sole
  remaining app frame, seed a note so the operator sees that ops resolve
  to that frame WITHOUT a `frames/select`, and which frames the tooling
  excluded. The resolved frame is also echoed under `:operating-frame`
  (already on `health`) so every op's frame target is never a guess."
  [health]
  (let [{:keys [frames app-frames operating-frame]} health
        excluded (vec (remove (set app-frames) frames))]
    (if (and (seq excluded) operating-frame)
      (let [sentence (str "Operating frame auto-resolved to " operating-frame
                          " — the sole app frame. Excluded :rf/* tool frame(s): "
                          excluded ". No frames/select needed; pass --frame to "
                          "target a tool frame explicitly.")]
        (update health :note (fn [existing]
                               (if existing (str sentence " " existing) sentence))))
      health)))

(defn- with-freshness
  "Attach the assembled freshness/liveness token to an `:ok? true`
  health payload (rf2-ertqw). Async — reads the JVM-side build worker
  state and merges with the browser half already on `health`. The
  payload-shaping fn `shape` takes the freshness-annotated health map
  and returns the final MCP envelope. On a stale-BUILD verdict the
  `:warning` is promoted to `:stale-build` (unless a louder warning is
  already set) so the alarm rides at the top level too.

  `opts` carries the optional `:port` (the browser URL port discover-app
  resolved the build from, rf2-jkwu4) so a non-fresh hint names the EXACT
  `http://localhost:<port>` the human reloads to wake / refresh a quiet
  runtime — the agent can't reload a browser, so this is the early,
  actionable, human-in-the-loop signal."
  [conn build-id health opts shape]
  (-> (freshness/token-from-health conn build-id health opts)
      (.then
        (fn [token]
          (let [stale?     (freshness/stale-build? token)
                annotated  (cond-> (assoc health :freshness token)
                             ;; surface the stale-build alarm at the top
                             ;; level when no other warning already owns
                             ;; the slot (ambiguous-frame / coord warning
                             ;; are still useful; the token carries the
                             ;; staleness regardless).
                             (and stale? (not (:warning health)))
                             (assoc :warning :stale-build))]
            (shape annotated))))))

(defn- port-opts
  "Build the freshness `opts` map carrying the browser URL `:port` the
  caller passed (rf2-jkwu4), or nil when no `:port` arg is present. The
  port lets a non-fresh liveness hint name the EXACT
  `http://localhost:<port>` the human reloads to wake a quiet runtime.
  The port may arrive as a string off the MCP wire; coerce to an int so
  the hint reads `http://localhost:8033`, not `http://localhost:\"8033\"`."
  [args]
  (when-let [raw (wire/arg args :port)]
    (let [p (cond
              (number? raw) (long raw)
              (string? raw) (let [n (js/parseInt raw 10)] (when-not (js/isNaN n) n))
              :else         nil)]
      (when (some? p) {:port p}))))

(defn discover-app [conn args]
  (let [opts (port-opts args)]
  (-> (resolve-build-id conn args)
   (.then
    (fn [{:keys [build-id auto-selected? error]}]
    (if error
     error
    (-> (probe/ensure-runtime! conn build-id)
        (.then (fn [_] (probe/runtime-health! conn build-id)))
        (.then
          (fn [health]
            (cond
              (not (:ok? health))
              (js/Promise.resolve (wire/ok-text health))

              (not (:debug-enabled? health))
              (js/Promise.resolve
                (wire/ok-text {:ok? false :reason :debug-disabled
                               :hint (str "re-frame.interop/debug-enabled? is false. "
                                          "This is a production build (or goog.DEBUG was "
                                          "forced off). Trace and epoch surfaces are elided.")}))

              (empty? (:frames health))
              (js/Promise.resolve
                ;; EP-0002 (rf2-bd4div) — `rf/init!` installs adapters /
                ;; runtime capabilities; it does NOT register `:rf/default`
                ;; (the runtime never synthesises a default frame — Spec 002
                ;; §`:rf/default` is an ordinary id). The app registers its
                ;; own frame explicitly (`reg-frame` / a root provider), so
                ;; the hint points at app boot / explicit registration
                ;; rather than teaching `init!` as a way to create a default.
                (wire/ok-text {:ok? false :reason :no-frames-registered
                               :hint (str "No frames registered yet. Wait for the app to boot, "
                                          "or register a frame explicitly "
                                          "(`re-frame.core/reg-frame` / a root frame-provider). "
                                          "`rf/init!` installs adapters but does NOT create a frame.")}))

              (:ambiguous-frame? health)
              (do
                ;; rf2-l9ixp: even on the ambiguous-frame warning the
                ;; build-id is resolved — record it so subsequent tool
                ;; calls (with the frame disambiguator) don't need to
                ;; re-specify `:build`.
                (wire/mark-resolved-build-id! conn build-id)
                (with-freshness conn build-id
                  (assoc health :ok? true
                                :warning :ambiguous-frame
                                ;; rf2-3bu3d.4 — point the operator at the
                                ;; APP frames (the real choices). `:rf/*`
                                ;; tool frames (`:rf/xray`, …) were already
                                ;; excluded from the ambiguity count, so
                                ;; naming them here as candidates would
                                ;; mislead. Genuine ambiguity = two-plus
                                ;; app frames.
                                :note (str "Multiple app frames registered: "
                                           (vec (or (:app-frames health) (:frames health)))
                                           ". Mutating ops require --frame :foo "
                                           "or run `frames/select` first. "
                                           "(`:rf/*` tool frames are excluded "
                                           "from this list.)"))
                  opts
                  (fn [h] (wire/ok-text (with-auto-selection h auto-selected? build-id)))))

              (not (:coord-annotation-enabled? health))
              (do
                (wire/mark-resolved-build-id! conn build-id)
                (with-freshness conn build-id
                  (assoc health :ok? true
                                :warning :no-source-coord-annotation
                                :note (str "Neither data-rf2-source-coord nor "
                                           "data-rc-src is on any element. "
                                           "DOM->source ops will degrade. Enable "
                                           "(rf/configure! {:source-coords {:annotate-dom? true}}) "
                                           "or use re-com with :src (at)."))
                  opts
                  (fn [h] (wire/ok-text (with-auto-selection h auto-selected? build-id)))))

              :else
              (do
                ;; rf2-l9ixp: cache the resolved build-id session-wide so
                ;; subsequent tool calls without an explicit `:build` arg
                ;; default to it instead of the `SHADOW_CLJS_BUILD_ID` /
                ;; `:app` env-var fallback. Invalidates on nREPL reconnect.
                (wire/mark-resolved-build-id! conn build-id)
                (with-freshness conn build-id
                  ;; rf2-8t3ct — echo the CANONICAL `:build` keyword
                  ;; alongside the historical `:build-id`. The two carry
                  ;; the same value; `:build` matches the INPUT arg name so
                  ;; an agent copies it straight back into a later tool's
                  ;; `:build` slot (round-trippable). `fresh-keyword` reads
                  ;; both `"examples/step-deck"` and `":examples/step-deck"`
                  ;; back to this same keyword.
                  (with-frame-resolution (assoc health :ok? true
                                                       :build-id build-id
                                                       :build    build-id))
                  opts
                  (fn [h] (wire/ok-text (with-auto-selection h auto-selected? build-id))))))))
        (.catch (fn [err] (probe/err->result :discover-failed err))))))))))
