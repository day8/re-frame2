(ns re-frame2-pair-mcp.tools.probe
  "Preload probe + error translation.

  The `re-frame2-pair.runtime` namespace ships into the consumer app via
  shadow-cljs's `:devtools :preloads` mechanism. Each tool that needs the
  runtime first calls `ensure-runtime!`, which checks
  `js/globalThis.__re_frame2_pair_runtime` — a load-time mirror the
  preload installs. Missing marker means the preload isn't configured;
  the tool refuses with `:reason :runtime-not-preloaded` and a setup
  hint pointing at `skills/re-frame2-pair/SKILL.md`.

  No cljs-eval inject path: the preload is the canonical setup, with no
  per-session inject fallback.

  ## Probe caching

  Once `runtime-preloaded?` resolves to true for a `(conn, build-id)`
  pair, the result is cached on the conn-atom (`:probed-builds`)
  for the lifetime of the socket. Subsequent `ensure-runtime!`
  calls for the same build short-circuit without an nREPL round-trip.
  The cache is cleared by `nrepl/close!` (operator-initiated teardown)
  and is reborn empty whenever `server.cljs/ensure-connection!` builds a
  fresh conn for a new shadow port. It is deliberately PRESERVED across a
  transient same-port socket reopen — the `__re_frame2_pair_runtime`
  marker lives in the browser CLJS heap, which a JVM-side socket hiccup
  doesn't destroy, so re-probing would be wasteful. (A full page reload
  DOES destroy the marker, but it leaves the JVM nREPL socket UP, so it
  doesn't trigger a reconnect-reset. Negative probes aren't cached, and
  the diagnostic ladder surfaces a genuinely-dead marker on the next
  eval against the build.)

  Negative results are NOT cached: a missing preload usually surfaces
  on the very first call, and re-probing on each subsequent call
  lets a freshly-added preload land without a server restart (e.g.
  the user edits `shadow-cljs.edn` and shadow-cljs hot-reloads).

  ## Build resolution + fail-loud preflight

  `eval-cljs` (and any future read/eval tool) must NOT eval against a
  build with no live re-frame2-pair runtime — doing so returns
  `{:ok? true :value nil}` (shadow's `cljs-eval` against a non-running
  build yields a blank value, which `cljs-eval-value` reads as nil),
  indistinguishable from a form that genuinely returns nil — a silent
  footgun that masks the real failure.

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
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
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
;; Diagnostic-ladder vocabulary.
;;
;; A failed preload probe surfaces one of four distinct reasons, each
;; with a targeted hint, rather than a single `:runtime-not-preloaded`
;; whose "add the preload to your shadow-cljs.edn" hint fits only one of
;; them. The ladder below distinguishes the failure modes:
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
;;                                  - a CLJS runtime is alive but the
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

(defn build-arg-form
  "Render a build-id keyword in EXACTLY the string form the `:build` MCP
  arg accepts back. The arg coercer (`fresh-keyword`) is
  colon-tolerant, so the canonical round-trippable form is the keyword's
  own `pr-str` (`:examples/machine-epochs`) — copy-pasting it from an
  error into `:build` resolves to the same build. Used to keep the
  `:running-builds` guidance round-trippable: the error names the valid
  set in a form the operator can paste straight back."
  [build-id]
  (pr-str build-id))

(defn running-build-arg-forms
  "Map the `running` build-id keywords to their round-trippable `:build`
  arg forms — the copy-paste-ready list for an error's
  guidance. `[:examples/machine-epochs :panel-gallery]` ⇒
  `[\":examples/machine-epochs\" \":panel-gallery\"]`."
  [running]
  (mapv build-arg-form running))

(defn- build-not-running-hint [build-id running]
  (cond
    (empty? running)
    (str "no shadow-cljs build is currently running. Start your dev "
         "build (`shadow-cljs watch <build>`) before retrying.")

    :else
    ;; Render every running id in its round-trippable `:build` arg form
    ;; so the guidance can be copy-pasted straight back.
    (str "shadow-cljs is running " (running-build-arg-forms running) " but not "
         (build-arg-form build-id) ". Pass --build=" (build-arg-form (first running))
         " (or set SHADOW_CLJS_BUILD_ID) — or restart the watch worker "
         "for " (build-arg-form build-id) " if you really mean to target it.")))

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

  Positive results are cached per `(conn, build-id)` on the conn-atom.
  A cached hit resolves synchronously without an nREPL round-trip; a
  miss runs one bencode round-trip and caches a positive outcome before
  resolving."
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
  "Run the failure-path diagnostic ladder. Called only when
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
                        {:reason                  :build-not-running
                         :build                   build-id
                         :running-builds          running
                         ;; The round-trippable copy-paste list: each
                         ;; running build in EXACTLY the `:build` arg
                         ;; form. `:running-builds` keeps the keyword vector
                         ;; (still round-trippable via colon-tolerance); this
                         ;; sibling makes the paste-ready strings explicit.
                         :running-builds-arg-forms (running-build-arg-forms running)
                         :hint                    (build-not-running-hint build-id running)})
                      ;; Build IS running — distinguish "no runtime
                      ;; connected" (cljs-eval returns blank/nil) from
                      ;; "runtime present but marker absent" (cljs-eval
                      ;; returns false). Re-evaluate the raw form and
                      ;; inspect the shape to tell them apart.
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
                                ;; is absent — the case the preload hint
                                ;; addresses.
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
                ;; Ladder itself threw — degrade to the blanket reason.
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
  synchronously from cache — no nREPL round-trip per tool call.

  On a failed probe the diagnostic ladder
  (`diagnose-preload-failure!`) inspects the failure mode and rejects
  with one of four specific reasons — `:nrepl-unreachable`,
  `:build-not-running`, `:no-runtime-connected`, or
  `:runtime-loaded-but-preload-missing` — each with a targeted hint.
  The blanket `:runtime-not-preloaded` reason is reserved as the
  degradation fallback if the ladder itself errors."
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
;; Running-build enumeration + fail-loud build resolution.
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

;; ---------------------------------------------------------------------------
;; URL/port → build resolution via the shadow-cljs :dev-http map.
;;
;; A pair session naturally starts from the URL of the open browser tab
;; (e.g. http://localhost:8031/counter), but discover-app speaks build-
;; ids. shadow's `:dev-http` map binds a port to a LIST OF FILE ROOTS
;; (not a build-id directly); the serving build is the one whose
;; `:output-dir` is among those roots — exactly how shadow's dev server
;; finds the compiled assets to serve. We resolve port → build entirely
;; JVM-side (where the config lives), matching `:output-dir` against the
;; port's roots, and return the build-id keyword.
;; ---------------------------------------------------------------------------

(defn- port->build-jvm-form
  "JVM-side form that reads the shadow-cljs config, looks up `port` in the
  `:dev-http` map, and returns the keyword build-id whose `:output-dir`
  is one of that port's file roots — or nil. Defensive: any failure
  (old shadow, unparseable config, no match) collapses to nil so the
  caller degrades to a clear `:port-unresolved` reason rather than a
  crash. `:output-dir` strings are normalised (leading `./` and trailing
  `/` stripped) on both sides before comparison so trivial path-spelling
  differences don't miss a real match."
  [port]
  (str
    "(try"
    "  (let [cfg (shadow.cljs.devtools.config/load-cljs-edn)"
    "        norm (fn [s] (when s (-> (str s)"
    "                                 (clojure.string/replace #\"^\\./\" \"\")"
    "                                 (clojure.string/replace #\"/$\" \"\"))))"
    "        roots (set (map norm (get (:dev-http cfg) " port ")))]"
    "    (when (seq roots)"
    "      (some (fn [[bid b]]"
    "              (when (and (map? b) (contains? roots (norm (:output-dir b)))) bid))"
    "            (:builds cfg))))"
    "  (catch Throwable _ nil))"))

(defn resolve-build-by-port
  "Resolve the shadow-cljs build-id serving `port` via the `:dev-http`
  map. Returns a Promise resolving to the build-id keyword, or nil when
  the port isn't in `:dev-http`, no build's `:output-dir` matches its
  roots, or the JVM probe fails. `port` is an integer (a string is
  coerced).

  This saves the manual `grep shadow-cljs.edn for 8031` step: an agent
  that only knows the browser URL passes the port and gets the build."
  [conn port]
  (let [p (cond
            (number? port) (long port)
            (string? port) (let [n (js/parseInt port 10)]
                             (when-not (js/isNaN n) n))
            :else nil)]
    (if (nil? p)
      (js/Promise.resolve nil)
      (-> (try
            (nrepl/jvm-eval conn (port->build-jvm-form p))
            (catch :default _ (js/Promise.resolve nil)))
          (.then (fn [resp]
                   (let [v (some-> (:value resp) cljs.reader/read-string)]
                     (when (keyword? v) v))))
          (.catch (fn [_] nil))))))

;; ---------------------------------------------------------------------------
;; Forgiving suffix→canonical build resolution.
;;
;; shadow-cljs build ids are namespaced keywords (`:examples/machine-epochs`).
;; An operator reading the app at a glance — or copying a name out of an
;; error / chat — naturally reaches for the short tail (`machine-epochs`).
;; One shared forgiving resolver accepts a bare tail uniformly across
;; every op, so a suffix form resolves to the running build it names
;; rather than being rejected with `:build-not-running` even when that
;; build sits right there in the error's own `:running-builds` list.
;;
;; Match rule (deterministic, no most-recent / fuzzy heuristics — those
;; are the silent-wrong-build footguns Mike rejected for `auto-select`):
;;
;;   1. EXACT keyword match            ⇒ that build (the common case).
;;   2. UNIQUE name-suffix match       ⇒ that build. The requested
;;      keyword's `name` equals the `name` of EXACTLY ONE running build
;;      (`:machine-epochs` ⇒ `:examples/machine-epochs`). The requested
;;      id is itself bare (no namespace) so it can only be a tail.
;;   3. no / ambiguous match           ⇒ the requested id UNCHANGED, so
;;      the existing diagnostic ladder fires `:build-not-running` with
;;      the round-trippable running list. Two builds sharing a tail stays
;;      ambiguous — never silently pick one.
;; ---------------------------------------------------------------------------

(defn match-running-build
  "Resolve `requested` (a build-id keyword) against the `running` vector of
  build-id keywords by exact-or-unique-suffix match. Returns
  the canonical running keyword on a hit, else `requested` unchanged (so a
  no/ambiguous match falls through to the diagnostic ladder). Pure.

  The suffix rule (step 2 above) applies ONLY when `requested` is itself
  bare (no namespace) — a namespaced request that isn't an exact match
  falls straight through to `requested` unchanged, never redirected to a
  same-bare-named build in a different namespace."
  [requested running]
  (cond
    (nil? requested) requested
    (some #(= requested %) running) requested
    (some? (namespace requested)) requested
    :else
    (let [tail     (name requested)
          suffixed (filterv #(= tail (name %)) running)]
      (if (= 1 (count suffixed))
        (first suffixed)
        requested))))

(defn canonicalize-build!
  "Forgiving suffix→canonical build resolution. Resolves a
  Promise of the canonical running build-id for `requested`:

    - `requested` already in this socket's confirmed `:probed-builds`
      ⇒ resolve it verbatim with NO round-trip (it's exactly running).
    - a cached alias on the conn ⇒ resolve the cached canonical id, again
      with no round-trip.
    - otherwise fetch `active-builds` once and `match-running-build`;
      record the resolution (identity included) in `:build-alias` so a
      later read for the same requested id is round-trip-free.

  A nil `requested` resolves to nil. Defensive against a non-atom `conn`
  (test stubs) — those have no caches, so it always falls through to the
  `running-builds` fetch (which itself degrades to `[]` on a stub).

  The resolution is NOT a probe: it only maps the NAME to the canonical
  running id. The runtime-sentinel check (`runtime-preloaded?`) still runs
  afterwards, so a name that suffix-resolves to a build with no live
  runtime still surfaces the real `:no-runtime-connected` diagnostic."
  [conn requested]
  (if (nil? requested)
    (js/Promise.resolve nil)
    (let [atom?    (and (some? conn) (satisfies? IDeref conn))
          snap     (when atom? @conn)
          probed   (:probed-builds snap #{})
          alias    (:build-alias snap {})]
      (cond
        (contains? probed requested) (js/Promise.resolve requested)
        (contains? alias requested)  (js/Promise.resolve (get alias requested))
        :else
        (-> (running-builds conn)
            (.then (fn [running]
                     (let [canonical (match-running-build requested running)]
                       (when atom?
                         (swap! conn assoc-in [:build-alias requested] canonical))
                       canonical)))
            (.catch (fn [_] requested)))))))

(defn auto-select-single-build
  "Resolve a build-id when EXACTLY ONE shadow-cljs build is running, else
  nil. Returns a Promise resolving to `[build-id true]` when
  one build was auto-selected, or `[nil false]` when zero or many run.

  Unlike `resolve-build!` (the eval-path resolver, which REJECTS on
  zero/many), this is the soft probe `discover-app` uses to fill an
  omitted `:build` arg without losing the existing multi-build error
  path: on zero/many the caller keeps its default build-id and lets the
  diagnostic ladder surface `:build-not-running` with the running list.

  Deliberately does NOT pick a most-recently-active build when several
  run — that's a silent wrong-build footgun Mike explicitly rejected.
  Two running builds stays ambiguous."
  [conn]
  (-> (running-builds conn)
      (.then (fn [running]
               (if (= 1 (count running))
                 [(first running) true]
                 [nil false])))
      (.catch (fn [_] [nil false]))))

(defn- auto-detect-hint [running]
  (cond
    (empty? running)
    (str "no shadow-cljs build is running. Start your dev build "
         "(`shadow-cljs watch <build>`) before eval'ing.")

    (= 1 (count running))
    ;; Shouldn't reach here (a single running build is auto-selected),
    ;; but keep the branch explicit.
    (str "pass --build=" (build-arg-form (first running)) " or set SHADOW_CLJS_BUILD_ID.")

    :else
    ;; Round-trippable copy-paste forms.
    (str "multiple shadow-cljs builds are running " (running-build-arg-forms running)
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
                         {:reason                   :no-runtime-for-build
                          :build                    (when explicit? build)
                          :running-builds           running
                          :running-builds-arg-forms (running-build-arg-forms running)
                          :hint                     (str (auto-detect-hint running)
                                                         " The default 'app' build is "
                                                         "not running.")}))))))))

(defn resolve-and-preflight!
  "The shared eval-path guard. Resolves the build then
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
                                       {:reason                   :no-runtime-for-build
                                        :build                    build-id
                                        :running-builds           running
                                        :running-builds-arg-forms (running-build-arg-forms running)
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
  "Translate a Promise rejection (a runtime / preflight / transport
  failure surfaced by the shared eval-path guards) into an MCP ERROR
  result. Structured ex-info reasons (e.g. `:no-runtime-for-build`,
  `:runtime-loaded-but-preload-missing`) surface verbatim; other errors
  fall through to a generic `fallback-reason` shape.

  A rejection here is a known-tool failure with `:ok? false`, which the
  MCP error-handling contract requires be returned with `isError: true`
  (API §Result shape; 001-Wire-Protocol §JSON-RPC error codes). Using
  `wire/err-text` (not `wire/ok-text`) is what makes that true: the host
  surfaces the failure to the LLM as an error rather than a success-shaped
  value, AND the response cache (which bypasses `:isError` results) can no
  longer cache a transient failure and mask a later successful read."
  [fallback-reason err]
  (if-let [data (ex-data err)]
    (wire/err-text (merge {:ok? false} data))
    (wire/err-text {:ok? false :reason fallback-reason :message (.-message err)})))

;; ---------------------------------------------------------------------------
;; Shared eval-prelude.
;;
;; The single-eval read/write tools all wear the SAME four-step promise
;; chain: confirm the runtime is preloaded, send ONE form over nREPL,
;; shape the resolved value into an MCP envelope, and translate any
;; rejection through `err->result`. Only the middle `.then` (how the
;; resolved value becomes an envelope) and the `:fail-reason` keyword
;; differ per tool. `eval-after-runtime!` factors out the invariant
;; prelude/postlude so each tool body reads as just "what form do I
;; send, and how do I shape the response" — the prelude stops obscuring
;; the per-tool logic.
;;
;; State-emitting tools wear the SAME chain plus ONE extra step: a
;; `raw-state/signal-runtime!` reconfigure between `ensure-runtime!` and
;; the eval (the raw-state tap gate) so the runtime is in its
;; gated posture before the eval taps / egresses app-db.
;; `eval-after-runtime-signalled!` is the sibling combinator for that
;; shape — same prelude/postlude, the signal interposed.
;;
;; Tools that DON'T use either helper, and why:
;;   - eval-cljs — uses `resolve-and-preflight!` (fail-loud build
;;     resolution), not the plain `ensure-runtime!` probe.
;;   - watch-until / tail-build — poll a form on a cadence, not a single
;;     eval (the signal step lands, but the eval recurs inside a poll
;;     loop, not as one terminal `.then`).
;;   - subscribe — signals then opens a streaming controller whose
;;     `.catch` must release the stream slot before `err->result`, so its
;;     postlude is a different shape.
;;   - discover-app — chains a `runtime-health!` read after the probe.
;; ---------------------------------------------------------------------------

(defn eval-after-runtime!
  "The shared single-eval prelude. Confirm the runtime is
  preloaded for `(conn, build-id)`, eval `form` over nREPL, pass the
  resolved value to `on-value` (which returns the MCP envelope), and
  translate any rejection via `err->result` keyed by `fail-reason`.

  `on-value` is a 1-arity fn `(fn [v] => js-mcp-result)` carrying the
  tool's own response-shaping — the part that genuinely differs per
  tool. Returns the Promise of the MCP result.

  `nrepl/cljs-eval-value` is resolved per-call (a plain var reference),
  so the `set!`-based test seam — every per-tool test stubs that var —
  keeps working through this helper exactly as it did at the inline
  call-sites."
  [conn build-id form fail-reason on-value]
  (-> (ensure-runtime! conn build-id)
      (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
      (.then on-value)
      (.catch (fn [err] (err->result fail-reason err)))))

(defn eval-after-runtime-signalled!
  "The shared single-eval prelude for STATE-EMITTING tools. Identical to
  `eval-after-runtime!` except it interposes one extra step — a
  `raw-state/signal-runtime!` reconfigure between `ensure-runtime!` and
  the eval (the raw-state tap gate) — so the runtime is in its
  gated (default-elided) posture before the eval taps / egresses app-db.

  Confirm the runtime is preloaded for `(conn, build-id)`, signal the
  raw-state posture, eval `form` over nREPL, pass the resolved value to
  `on-value` (which returns the MCP envelope), and translate any
  rejection via `err->result` keyed by `fail-reason`. Returns the Promise
  of the MCP result.

  `on-value` carries the part that genuinely differs per tool. The signal
  re-runs on every call rather than caching a per-build flag — the
  runtime's posture resets on reload. As with
  `eval-after-runtime!`, `nrepl/cljs-eval-value` is resolved per-call (a
  plain var reference) so the per-tool `set!`-based test seam keeps
  working through this helper."
  [conn build-id form fail-reason on-value]
  (-> (ensure-runtime! conn build-id)
      (.then (fn [_] (raw-state/signal-runtime! conn build-id)))
      (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
      (.then on-value)
      (.catch (fn [err] (err->result fail-reason err)))))

;; ---------------------------------------------------------------------------
;; Map-envelope projection for the "runtime fn always returns a map" tools.
;;
;; `read-dom` and `read-ui` both route a single
;; `(re-frame2-pair.runtime/<dom-read|ui-read> {...})` form through
;; `eval-after-runtime!`. The runtime fn ALWAYS returns a map (its own
;; `{:ok? ...}` envelope, or a structured no-document / bad-selector
;; error). A nil / non-map value reaching the `on-value` callback therefore
;; means the eval came back BLANK — `cljs-eval-value` reads a blank shadow
;; result as `nil` (the runtime didn't answer: a dropped WebSocket, or a
;; full page refresh wiped the preload marker).
;;
;; Left unguarded, `(wire/ok-text nil)` projects to a `null`
;; structuredContent the npm SDK's outputSchema validation rejects at the
;; transport layer (`expected record at structuredContent, received null`)
;; — bypassing the normal `{:ok? false}` error contract. This helper
;; folds the shared map?/blank guard onto ONE projection so a fix or
;; regression-guard on the blank-result contract covers both read tools.
;; Only the per-tool `:reason`, `:hint`, and any extra error slots
;; (read-dom echoes `:selector`) differ — those ride in as args.
;; ---------------------------------------------------------------------------

(defn map-result-or-blank
  "Build the `on-value` callback for a tool whose runtime fn always
  returns a MAP envelope (read-dom / read-ui). Returns a
  1-arity fn suitable as `eval-after-runtime!`'s `on-value`:

    - a `:ok? true` MAP envelope ⇒
      `(wire/ok-text (assoc envelope :build build-id))`, echoing the
      canonical resolved `:build`.
    - a `:ok? false` MAP envelope ⇒ `(wire/err-text ...)` carrying the
      runtime's own structured failure verbatim, `:build`-stamped.
      The runtime fn returns `:ok? false` ONLY on a
      genuine fault — a thrown `:rf.error/read-dom-bad-selector` /
      `:rf.error/ui-read-bad-selector` (malformed CSS ⇒ querySelectorAll
      threw), `:rf.error/read-dom-no-document`, `:no-element`,
      `:no-target-arg` — never as a legitimate-but-empty answer (those
      are `:ok? true`, e.g. read-dom's `{:ok? true :count 0 :nodes []}`).
      So branching on `:ok?` flags every fault `isError:true` per
      spec/003-Tool-Catalogue.md §381 \"every `:ok? false` is
      `isError:true`\" and keeps it out of the response cache (cache
      eligibility bypasses isError), with no legitimate structured-answer
      false-positive. This is the read-family analogue of the sibling
      `:ok?` branches in read_sub / dispatch / operating_frame — NOT the
      handler-meta `:not-registered` carve-out, which rides as `ok-text`
      because a not-registered lookup is a legitimate answer-as-data, not
      a fault (see result_envelope/error?, keyed on `::codec-error` meta,
      a different layer).
    - a nil / non-map ⇒ `(wire/err-text {...})` carrying `:ok? false`,
      `:build`, the per-tool `:reason` / `:hint`, and any `error-extras`
      (e.g. read-dom's `:selector`) — the structured blank-result error
      (never a `null` structuredContent).

  `blank-reason` / `blank-hint` are the tool-specific blank-result
  vocabulary; `error-extras` is an optional map of extra slots merged
  into the error envelope."
  ([build-id blank-reason blank-hint]
   (map-result-or-blank build-id blank-reason blank-hint nil))
  ([build-id blank-reason blank-hint error-extras]
   (fn [envelope]
     (cond
       (and (map? envelope) (:ok? envelope))
       (wire/ok-text (assoc envelope :build build-id))

       (map? envelope)
       ;; A genuine `:ok? false` runtime failure (bad-selector /
       ;; no-document / no-element / no-target-arg). Forward verbatim as
       ;; an :isError envelope per the universal `:ok? false` contract.
       (wire/err-text (assoc envelope :build build-id))

       :else
       (wire/err-text
         (merge {:ok?    false
                 :reason blank-reason
                 :build  build-id
                 :hint   blank-hint}
                error-extras))))))
