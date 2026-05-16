(ns day8.re-frame2-causa-mcp.probe
  "Preload probe + error translation for causa-mcp tools (rf2-8xzoe
  T-Insp tranche shared infra).

  The `day8.re-frame2-causa.runtime` namespace ships into the consumer
  app via shadow-cljs's `:devtools :preloads` (riding Causa-the-panel's
  preload, per `tools/causa-mcp/spec/000-Vision.md` §Two namespaces,
  two sides). Each tool that needs the runtime first calls
  `ensure-runtime!`, which checks `js/globalThis.__day8_re_frame2_causa_runtime`
  — a load-time mirror the preload installs (see
  `tools/causa/src/day8/re_frame2_causa/runtime.cljs` §Session
  sentinel). Missing marker means the preload isn't configured; the
  tool refuses with `{:ok? false :reason :runtime-not-preloaded}` and a
  setup hint.

  No cljs-eval inject path: the preload is the canonical setup
  (parallel to pair2-mcp's rf2-7dvg posture).

  ## Probe caching

  Once `runtime-preloaded?` resolves to true for a `(conn, build-id)`
  pair, the result is cached on the conn-atom (`:probed-builds`) for
  the lifetime of the socket. Subsequent `ensure-runtime!` calls for
  the same build short-circuit without an nREPL round-trip. The cache
  resets on (re)connect — both `nrepl/connect!` and `nrepl/close!`
  blank `:probed-builds` — so a full page reload (which destroys the
  CLJS heap and the global sentinel) triggers a fresh probe on the
  next tool call.

  Negative results are NOT cached: a missing preload usually surfaces
  on the very first call, and re-probing on each subsequent call lets
  a freshly-added preload land without a server restart."
  (:require [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.wire :as wire]))

;; ---------------------------------------------------------------------------
;; Preload probe.
;; ---------------------------------------------------------------------------

(def preload-missing-hint
  "Operator-facing setup hint surfaced on the structured
  `:runtime-not-preloaded` error envelope. Points at the
  Causa-the-panel preload entry — the runtime ns rides that preload
  per `tools/causa-mcp/spec/000-Vision.md` §Two namespaces, two sides."
  (str "day8.re-frame2-causa.runtime is not loaded into this build. "
       "The Causa-the-panel preload pulls the runtime — add it to "
       "your shadow-cljs.edn:\n"
       "  :builds {:app {:devtools {:preloads [day8.re-frame2-causa.preload]}}}\n"
       "and make sure tools/causa is on :source-paths."))

(defn- conn-has-probed?
  "True iff `build-id` has been confirmed preloaded on the current
  socket generation. Defensive against `nil` / non-atom `conn` —
  tests pass stub conns that don't carry the cache."
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
  "Probe `js/globalThis.__day8_re_frame2_causa_runtime` — the
  load-time marker set by the preloaded `day8.re-frame2-causa.runtime`
  namespace. Resolves to a Promise of `true` iff the marker is
  present.

  Positive results are cached per `(conn, build-id)` on the conn-atom.
  A cached hit resolves synchronously without an nREPL round-trip; a
  miss runs one bencode round-trip and caches a positive outcome
  before resolving."
  [conn build-id]
  (if (conn-has-probed? conn build-id)
    (js/Promise.resolve true)
    (-> (nrepl/cljs-eval-value
          conn build-id
          "(some? (and (exists? js/globalThis) (.-__day8_re_frame2_causa_runtime js/globalThis)))")
        (.then (fn [v]
                 (let [ok? (true? v)]
                   (when ok? (mark-conn-probed! conn build-id))
                   ok?)))
        (.catch (fn [_] false)))))

(defn ensure-runtime!
  "Confirm the causa runtime is preloaded. Resolves to nil on success,
  rejects with a structured `ex-info` carrying
  `{:reason :runtime-not-preloaded :hint <setup>}` otherwise. Tools
  that need the runtime call this first.

  After the first positive probe per `(conn, build-id)`, this resolves
  synchronously from cache — no nREPL round-trip per tool call."
  [conn build-id]
  (-> (runtime-preloaded? conn build-id)
      (.then (fn [ok?]
               (if ok?
                 nil
                 (js/Promise.reject
                   (ex-info "causa runtime not preloaded"
                            {:reason :runtime-not-preloaded
                             :hint   preload-missing-hint})))))))

;; ---------------------------------------------------------------------------
;; Error helpers — translate Promise rejections into structured envelopes.
;; ---------------------------------------------------------------------------

(defn err->result
  "Translate a Promise rejection into an `ok-text` result. Structured
  `ex-info` reasons (e.g. `:runtime-not-preloaded`) surface verbatim;
  other errors fall through to a generic eval-error shape stamped with
  `fallback-reason`."
  [fallback-reason err]
  (if-let [data (ex-data err)]
    (wire/ok-text (merge {:ok? false} data))
    (wire/ok-text {:ok? false :reason fallback-reason :message (.-message err)})))
