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
  rf2-7dvg."
  (:require [re-frame-pair2-mcp.nrepl :as nrepl]
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

(defn runtime-preloaded?
  "Probe `js/globalThis.__re_frame_pair2_runtime` — the load-time
  marker set by the preloaded `re-frame-pair2.runtime` namespace.
  Resolves to true iff the marker is present. One bencode round-trip,
  no CLJS compile."
  [conn build-id]
  (-> (nrepl/cljs-eval-value
        conn build-id
        "(some? (and (exists? js/globalThis) (.-__re_frame_pair2_runtime js/globalThis)))")
      (.then (fn [v] (true? v)))
      (.catch (fn [_] false))))

(defn runtime-health!
  "Call `(re-frame-pair2.runtime/health)`. Caller must have already
  confirmed the preload landed via `runtime-preloaded?`."
  [conn build-id]
  (nrepl/cljs-eval-value conn build-id "(re-frame-pair2.runtime/health)"))

(defn ensure-runtime!
  "Confirm the pair2 runtime is preloaded. Resolves to nil on success,
  rejects with a structured error otherwise. Tools that need the
  runtime call this first."
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
