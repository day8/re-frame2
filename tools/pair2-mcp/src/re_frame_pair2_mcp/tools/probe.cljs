(ns re-frame-pair2-mcp.tools.probe
  "Preload probe + error translation (rf2-vrbwx split).

  The `re-frame-pair2.runtime` namespace ships into the consumer app via
  shadow-cljs's `:devtools :preloads` mechanism. Each tool that needs the
  runtime first calls `ensure-runtime!`, which checks
  `js/globalThis.__re_frame_pair2_runtime` — a load-time mirror the
  preload installs. Missing marker means the preload isn't configured;
  the tool refuses with `:reason :runtime-not-preloaded` and a setup
  hint pointing at `skills/re-frame-pair2/SKILL.md`.

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
  the CLJS heap and the `__re_frame_pair2_runtime` marker) triggers a
  fresh probe on the next tool call.

  Negative results are NOT cached: a missing preload usually surfaces
  on the very first call, and re-probing on each subsequent call
  lets a freshly-added preload land without a server restart (e.g.
  the user edits `shadow-cljs.edn` and shadow-cljs hot-reloads)."
  (:require [re-frame-pair2-mcp.nrepl :as nrepl]
            [re-frame-pair2-mcp.tools.eval-form :as ef]
            [re-frame-pair2-mcp.tools.wire :as wire]))

;; ---------------------------------------------------------------------------
;; Preload probe.
;; ---------------------------------------------------------------------------

(def preload-missing-hint
  (str "re-frame-pair2.runtime is not loaded into this build. Add the "
       "preload entry to your shadow-cljs.edn:\n"
       "  :builds {:app {:devtools {:preloads [re-frame-pair2.runtime]}}}\n"
       "and make sure the directory containing re_frame_pair2/runtime.cljs "
       "is on :source-paths. See skills/re-frame-pair2/SKILL.md (§Setup)."))

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
  "Probe `js/globalThis.__re_frame_pair2_runtime` — the load-time
  marker set by the preloaded `re-frame-pair2.runtime` namespace.
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
          "(some? (and (exists? js/globalThis) (.-__re_frame_pair2_runtime js/globalThis)))")
        (.then (fn [v]
                 (let [ok? (true? v)]
                   (when ok? (mark-conn-probed! conn build-id))
                   ok?)))
        (.catch (fn [_] false)))))

(defn runtime-health!
  "Call `(re-frame-pair2.runtime/health)`. Caller must have already
  confirmed the preload landed via `runtime-preloaded?`."
  [conn build-id]
  (nrepl/cljs-eval-value conn build-id (ef/emit (ef/rt-call 'health))))

(defn ensure-runtime!
  "Confirm the pair2 runtime is preloaded. Resolves to nil on success,
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
                   (ex-info "pair2 runtime not preloaded"
                            {:reason :runtime-not-preloaded
                             :hint   preload-missing-hint})))))))

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
