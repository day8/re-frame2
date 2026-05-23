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
  (rf2-sjpx0)."
  [conn build-id]
  (-> (runtime-preloaded? conn build-id)
      (.then (fn [ok?]
               (if ok?
                 nil
                 (js/Promise.reject
                   (ex-info "re-frame2-pair runtime not preloaded"
                            {:reason :runtime-not-preloaded
                             :hint   preload-missing-hint})))))))

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
