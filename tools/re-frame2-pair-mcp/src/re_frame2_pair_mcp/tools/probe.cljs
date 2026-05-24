(ns re-frame2-pair-mcp.tools.probe
  "Preload probe + error translation (rf2-vrbwx split).

  The `re-frame2-pair.runtime` namespace ships into the consumer app via
  shadow-cljs's `:devtools :preloads` mechanism. Each tool that needs the
  runtime first calls `ensure-runtime!`, which checks
  `js/globalThis.__re_frame2_pair_runtime` — a load-time mirror the
  preload installs. Missing marker means the preload isn't configured;
  the tool refuses with `:reason :runtime-not-preloaded` and a setup
  hint pointing at `skills/re-frame2-pair/SKILL.md`.

  No cljs-eval inject path: the preload is the canonical setup. Earlier
  drops shipped a per-session inject fallback; that path was cut for
  rf2-7dvg.

  ## Probe caching (rf2-sjpx0)

  Once `runtime-preloaded?` resolves to true for a `(conn, build-id)`
  pair, the result is cached on the conn-atom (`:probed-builds`)
  for the lifetime of the socket. Subsequent `ensure-runtime!`
  calls for the same build short-circuit without an nREPL round-trip.
  The cache resets on (re)connect — `nrepl/connect!` and `nrepl/close!`
  both blank `:probed-builds` — so a full page reload (which destroys
  the CLJS heap and the `__re_frame2_pair_runtime` marker) triggers a
  fresh probe on the next tool call.

  Negative results are NOT cached: a missing preload usually surfaces
  on the very first call, and re-probing on each subsequent call
  lets a freshly-added preload land without a server restart (e.g.
  the user edits `shadow-cljs.edn` and shadow-cljs hot-reloads).

  ## Build resolution + fail-loud preflight (rf2-ivlb3)

  `eval-cljs` (and any future read/eval tool) must NOT eval against a
  build with no live re-frame2-pair runtime — doing so returns
  `{:ok? true :value nil}` (shadow's `cljs-eval` against a non-running
  build yields a blank value, which `cljs-eval-value` reads as nil),
  indistinguishable from a form that genuinely returns nil. ~30 min of
  dead-end debugging in the wild.

  Two helpers close the footgun:

    - `running-builds` enumerates the shadow-cljs builds with a live
      watch worker (`shadow.cljs.devtools.api/active-builds`, a JVM-side
      call) so the operator never has to guess `--build`.
    - `resolve-and-preflight!` resolves the build (explicit arg wins;
      otherwise auto-detect the single running build) and confirms the
      runtime sentinel for it. On a runtime-absent build it rejects with
      a structured `:no-runtime-for-build` ex-info enumerating the
      running builds — never a silent `:ok? true :value nil`."
  (:require [cljs.reader]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]))

;; ---------------------------------------------------------------------------
;; Preload probe.
;; ---------------------------------------------------------------------------

(def preload-missing-hint
  (str "re-frame2-pair.runtime is not loaded into this build. Add the "
       "preload entry to your shadow-cljs.edn:\n"
       "  :builds {:app {:devtools {:preloads [re-frame2-pair.runtime]}}}\n"
       "and make sure the directory containing re_frame2_pair/runtime.cljs "
       "is on :source-paths. See skills/re-frame2-pair/SKILL.md (§Setup)."))

;; ---------------------------------------------------------------------------
;; Diagnostic-ladder vocabulary (rf2-7tgfk).
;;
;; A failed preload probe used to surface ONE reason
;; (`:runtime-not-preloaded`) whose hint always read "add the preload
;; to your shadow-cljs.edn". In the 2026-05-25 pair-debug session that
;; suggestion was misleading in three of the four failure modes the
;; operator hit (~30 min of dead-end debugging). The ladder below
;; distinguishes the cases the original single reason hid:
;;
;;   :nrepl-unreachable             - JVM eval round-trip fails. The
;;                                    socket may be dead even though
;;                                    the bash side of the world is up.
;;   :build-not-running             - shadow's active-builds doesn't
;;                                    include the build the tool is
;;                                    targeting. Almost always a
;;                                    --build typo or operator
;;                                    targeted the wrong dev build.
;;   :no-runtime-connected          - build IS running but cljs-eval
;;                                    returns blank — no browser tab
;;                                    has connected (or the tab's ws
;;                                    is dead).
;;   :runtime-loaded-but-preload-missing
;;                                  - the original meaning: a CLJS
;;                                    runtime is alive but the
;;                                    `__re_frame2_pair_runtime` marker
;;                                    is absent. The "add the preload"
;;                                    hint fits ONLY this case.
;;
;; The ladder costs one extra `jvm-eval` (active-builds enumeration)
;; on the failure path; ~50ms total. The probe-cache short-circuit
;; means a healthy session pays nothing.
;; ---------------------------------------------------------------------------

(def ^:private nrepl-unreachable-hint
  (str "The nREPL connection looks dead. Likely: the JVM running "
       "shadow-cljs has stopped (or has been restarted, leaving the "
       "MCP server holding a stale socket). Restart `shadow-cljs watch` "
       "and retry; the MCP server reconnects on the next tool call."))

(defn- build-not-running-hint [build-id running]
  (cond
    (empty? running)
    (str "no shadow-cljs build is currently running. Start your dev "
         "build (`shadow-cljs watch <build>`) before retrying.")

    :else
    (str "shadow-cljs is running " (vec running) " but not "
         build-id ". Pass --build=" (first running)
         " (or set SHADOW_CLJS_BUILD_ID) — or restart the watch worker "
         "for " build-id " if you really mean to target it.")))

(defn- no-runtime-connected-hint [build-id]
  (str "build " build-id " is running but no CLJS runtime is currently "
       "connected (the cljs-eval round-trip returned blank). Open the app "
       "in a browser tab — or if a tab IS open, the WebSocket has dropped: "
       "reload the page so the runtime reconnects."))

(defn- jvm-reachable?
  "Round-trip `1` through `jvm-eval`. Resolves to true on `:value \"1\"`,
  false on any other shape / rejection. Used to disambiguate
  `:nrepl-unreachable` from `:build-not-running`."
  [conn]
  (-> (try
        (nrepl/jvm-eval conn "1")
        (catch :default _ (js/Promise.resolve nil)))
      (.then (fn [resp]
               (= "1" (str (:value resp)))))
      (.catch (fn [_] false))))

(defn- conn-has-probed?
  "True iff `build-id` has been confirmed preloaded on the current
  socket generation. Defensive against `nil` / non-atom `conn` —
  conformance tests pass a stub conn that doesn't carry the cache."
  [conn build-id]
  (and (some? conn)
       (satisfies? IDeref conn)
       (contains? (:probed-builds @conn #{}) build-id)))

(defn- mark-conn-probed!
  "Record a positive probe result on the conn-atom so the next
  `ensure-runtime!` for the same build can short-circuit. Defensive
  against a non-atom `conn` (test stubs)."
  [conn build-id]
  (when (and (some? conn) (satisfies? IDeref conn))
    (swap! conn update :probed-builds (fnil conj #{}) build-id)))

(defn runtime-preloaded?
  "Probe `js/globalThis.__re_frame2_pair_runtime` — the load-time
  marker set by the preloaded `re-frame2-pair.runtime` namespace.
  Resolves to true iff the marker is present.

  Positive results are cached per `(conn, build-id)` on the conn-atom
  (rf2-sjpx0). A cached hit resolves synchronously without an nREPL
  round-trip; a miss runs one bencode round-trip and caches a positive
  outcome before resolving."
  [conn build-id]
  (if (conn-has-probed? conn build-id)
    (js/Promise.resolve true)
    (-> (nrepl/cljs-eval-value
          conn build-id
          "(some? (and (exists? js/globalThis) (.-__re_frame2_pair_runtime js/globalThis)))")
        (.then (fn [v]
                 (let [ok? (true? v)]
                   (when ok? (mark-conn-probed! conn build-id))
                   ok?)))
        (.catch (fn [_] false)))))

;; Forward declare for diagnose-preload-failure! — running-builds is
;; defined below alongside resolve-build! and friends; the ladder uses
;; it on the failure path. Keeping running-builds where it is preserves
;; the grouping with resolve-build! (both touch shadow's JVM API).
(declare running-builds)

(defn diagnose-preload-failure!
  "Run the failure-path diagnostic ladder (rf2-7tgfk). Called only when
  `runtime-preloaded?` returned false, so the cost is paid exactly when
  it matters. Resolves to a map `{:reason <kw> :hint <str> ...}` whose
  `:reason` distinguishes:

    :nrepl-unreachable                     - the nREPL JVM round-trip fails.
    :build-not-running                     - shadow's active-builds doesn't
                                             include the targeted build.
                                             Carries :running-builds for
                                             the operator's next move.
    :no-runtime-connected                  - build IS running but cljs-eval
                                             returns blank (no browser tab
                                             connected, or its ws is dead).
    :runtime-loaded-but-preload-missing    - a CLJS runtime is alive but
                                             the preload marker is absent.
                                             The original hint applies here.

  The ladder runs one extra `jvm-eval` (active-builds enumeration) plus
  one `cljs-eval` (blank-vs-false discriminator) — ~50ms on the failure
  path; the probe cache keeps the success path free."
  [conn build-id]
  (-> (jvm-reachable? conn)
      (.then
        (fn [nrepl-ok?]
          (if-not nrepl-ok?
            (js/Promise.resolve
              {:reason :nrepl-unreachable
               :build  build-id
               :hint   nrepl-unreachable-hint})
            (-> (running-builds conn)
                (.then
                  (fn [running]
                    (if-not (some #(= build-id %) running)
                      (js/Promise.resolve
                        {:reason         :build-not-running
                         :build          build-id
                         :running-builds running
                         :hint           (build-not-running-hint build-id running)})
                      ;; Build IS running — distinguish "no runtime
                      ;; connected" (cljs-eval returns blank/nil) from
                      ;; "runtime present but marker absent" (cljs-eval
                      ;; returns false). The original probe collapsed
                      ;; both into "false"; here we re-evaluate the
                      ;; raw form and inspect the shape.
                      (-> (nrepl/cljs-eval-value
                            conn build-id
                            "(some? (and (exists? js/globalThis) (.-__re_frame2_pair_runtime js/globalThis)))")
                          (.then
                            (fn [v]
                              (cond
                                ;; blank/nil → no runtime answered the eval
                                (nil? v)
                                {:reason         :no-runtime-connected
                                 :build          build-id
                                 :running-builds running
                                 :hint           (no-runtime-connected-hint build-id)}

                                ;; false → runtime is alive but marker
                                ;; is absent — the case the original hint
                                ;; was written for.
                                (false? v)
                                {:reason :runtime-loaded-but-preload-missing
                                 :build  build-id
                                 :hint   preload-missing-hint}

                                ;; Should not happen — a true here
                                ;; means the probe state flipped under
                                ;; us. Treat as missing marker.
                                :else
                                {:reason :runtime-loaded-but-preload-missing
                                 :build  build-id
                                 :hint   preload-missing-hint})))
                          (.catch (fn [_]
                                    ;; The discriminator eval threw —
                                    ;; we know the build is running
                                    ;; (active-builds confirmed it), so
                                    ;; this is most likely a transient
                                    ;; cljs-eval failure. Fall back to
                                    ;; the most conservative reason.
                                    (js/Promise.resolve
                                      {:reason :no-runtime-connected
                                       :build  build-id
                                       :running-builds running
                                       :hint   (no-runtime-connected-hint build-id)})))))))))))
      (.catch (fn [_]
                ;; Ladder itself threw — degrade to the original reason.
                (js/Promise.resolve
                  {:reason :runtime-not-preloaded
                   :build  build-id
                   :hint   preload-missing-hint})))))

(defn runtime-health!
  "Call `(re-frame2-pair.runtime/health)`. Caller must have already
  confirmed the preload landed via `runtime-preloaded?`."
  [conn build-id]
  (nrepl/cljs-eval-value conn build-id (ef/emit (ef/rt-call 'health))))

(defn ensure-runtime!
  "Confirm the re-frame2-pair runtime is preloaded. Resolves to nil on success,
  rejects with a structured error otherwise. Tools that need the
  runtime call this first.

  After the first positive probe per `(conn, build-id)`, this resolves
  synchronously from cache — no nREPL round-trip per tool call
  (rf2-sjpx0).

  rf2-7tgfk: on a failed probe the rejection no longer always reads
  `:runtime-not-preloaded`. The diagnostic ladder
  (`diagnose-preload-failure!`) inspects the failure mode and rejects
  with one of four specific reasons — `:nrepl-unreachable`,
  `:build-not-running`, `:no-runtime-connected`, or
  `:runtime-loaded-but-preload-missing` — each with a targeted hint.
  The original blanket `:runtime-not-preloaded` reason is reserved as
  the degradation fallback if the ladder itself errors."
  [conn build-id]
  (-> (runtime-preloaded? conn build-id)
      (.then (fn [ok?]
               (if ok?
                 nil
                 (-> (diagnose-preload-failure! conn build-id)
                     (.then (fn [diag]
                              (js/Promise.reject
                                (ex-info "re-frame2-pair runtime probe failed"
                                         diag))))))))))

;; ---------------------------------------------------------------------------
;; Running-build enumeration + fail-loud build resolution (rf2-ivlb3).
;; ---------------------------------------------------------------------------

(defn running-builds
  "Enumerate the shadow-cljs build ids that currently have a live watch
  worker, as a vector of keywords. JVM-side call — `active-builds`
  lives on the shadow API, not in the CLJS heap, so this works even
  when no build has the re-frame2-pair runtime preloaded (the whole
  point: it tells the operator which builds ARE running).

  Resolves to `[]` on any error (old shadow without `active-builds`,
  socket hiccup, or a nil/stub conn in the conformance harness) — the
  caller degrades to a hint-only error rather than crashing.

  The `jvm-eval` call is wrapped in `try` so a SYNCHRONOUS throw
  (`@nil` IDeref on a nil conn before the Promise chain even starts)
  collapses to `[]` too — `.catch` only catches async rejections."
  [conn]
  (-> (try
        (nrepl/jvm-eval
          conn
          "(try (vec (shadow.cljs.devtools.api/active-builds)) (catch Throwable _ []))")
        (catch :default _ (js/Promise.resolve nil)))
      (.then (fn [resp]
               (let [v (some-> (:value resp) cljs.reader/read-string)]
                 (if (vector? v) v []))))
      (.catch (fn [_] []))))

(defn- auto-detect-hint [running]
  (cond
    (empty? running)
    (str "no shadow-cljs build is running. Start your dev build "
         "(`shadow-cljs watch <build>`) before eval'ing.")

    (= 1 (count running))
    ;; Shouldn't reach here (a single running build is auto-selected),
    ;; but keep the branch explicit.
    (str "pass --build=" (first running) " or set SHADOW_CLJS_BUILD_ID.")

    :else
    (str "multiple shadow-cljs builds are running " (vec running)
         "; pass --build=<one-of-them> or set SHADOW_CLJS_BUILD_ID.")))

(defn resolve-build!
  "Resolve the build to eval against. Returns a Promise.

    - explicit build (operator passed `:build`)  ⇒ resolves to it
      verbatim; the runtime-sentinel preflight catches a typo'd /
      non-running build.
    - exactly one running build                  ⇒ resolves to it
      (auto-detect; the operator needn't know the id).
    - zero or many running                       ⇒ rejects with a
      structured `:no-runtime-for-build` ex-info enumerating
      `:running-builds`.

  `explicit?` says whether `build` came from a real `:build` arg vs.
  the env/`:app` fallback in `wire/arg-build`. We auto-detect ONLY
  when the build is the bare default (`explicit?` false) — an operator
  who typed `:build foo` gets `foo`, footgun-and-all (caught by
  preflight)."
  [conn build explicit?]
  (if (and build explicit?)
    (js/Promise.resolve build)
    (-> (running-builds conn)
        (.then
          (fn [running]
            (cond
              (= 1 (count running))
              (first running)

              :else
              (js/Promise.reject
                (ex-info "cannot auto-detect a single running build"
                         {:reason         :no-runtime-for-build
                          :build          (when explicit? build)
                          :running-builds running
                          :hint           (str (auto-detect-hint running)
                                               " The default 'app' build is "
                                               "not running.")}))))))))

(defn resolve-and-preflight!
  "The shared eval-path guard (rf2-ivlb3). Resolves the build then
  confirms a live re-frame2-pair runtime for it. Resolves to the
  resolved build-id on success; rejects with a structured ex-info
  otherwise.

  Reject reasons:
    - `:no-runtime-for-build` — auto-detect found zero/many builds, OR
      the resolved build has no runtime sentinel. Carries
      `:running-builds` so the operator sees the right `--build`.
    - (`:runtime-not-preloaded` is folded into `:no-runtime-for-build`
      here — both mean \"this build can't be eval'd\"; the enriched
      reason carries the running-build enumeration the bare preload
      error lacked.)

  NEVER resolves for a runtime-absent build, so the caller can never
  emit `:ok? true :value nil` for an eval that didn't actually run."
  [conn build explicit?]
  (-> (resolve-build! conn build explicit?)
      (.then
        (fn [build-id]
          (-> (runtime-preloaded? conn build-id)
              (.then
                (fn [ok?]
                  (if ok?
                    build-id
                    ;; Resolved a concrete build but its runtime sentinel
                    ;; is absent — enrich with the running-build list so
                    ;; the operator sees which build to target instead.
                    (-> (running-builds conn)
                        (.then
                          (fn [running]
                            (js/Promise.reject
                              (ex-info "no re-frame2-pair runtime for build"
                                       {:reason         :no-runtime-for-build
                                        :build          build-id
                                        :running-builds running
                                        :hint
                                        (str "build " build-id " has no live "
                                             "re-frame2-pair runtime. "
                                             (auto-detect-hint running)
                                             " (Or add the :devtools :preloads "
                                             "[re-frame2-pair.runtime] entry — see "
                                             "skills/re-frame2-pair/SKILL.md §Setup.)")})))))))))))))

;; ---------------------------------------------------------------------------
;; Error helpers — surface structured `ex-info` from `ensure-runtime!`.
;; ---------------------------------------------------------------------------

(defn err->result
  "Translate a Promise rejection into an `ok-text` result. Structured
  ex-info reasons (e.g. `:runtime-not-preloaded`) surface verbatim;
  other errors fall through to a generic eval-error shape."
  [fallback-reason err]
  (if-let [data (ex-data err)]
    (wire/ok-text (merge {:ok? false} data))
    (wire/ok-text {:ok? false :reason fallback-reason :message (.-message err)})))
