(ns day8.re-frame2-causa-mcp.tools.discover-app
  "Tool: `discover-app` — health summary for the host runtime
  (rf2-8xzoe.30, T-Meta-1 of the causa-mcp meta tranche).

  One-call summary of the runtime's view of the world. Used by an
  agent to confirm the environment is healthy on every session's first
  tool call — preload status, debug-enabled flag, registered frames
  (and ambiguity flag), source-coord annotation status,
  `eval-cljs` gate state, current `:origin` tag default.

  Mirrors pair2-mcp's `discover-app` shape so cross-MCP agents read
  the same warning posture from both servers; the structural
  differences are intentional:

    - `:eval-cljs-enabled?` reflects causa's `--allow-eval` gate
      (sibling to pair2-mcp's same-named gate).
    - `:build-id` is the shadow-cljs build the server probed
      (resolved from `SHADOW_CLJS_BUILD_ID` env or `:app` default).

  ## Wire-boundary contract

  - **B-1 privacy** — not applicable (health metadata, not
    user data).
  - **W-6 size elision** — counted defensively on the health payload.
  - **W-1 token cap** — dispatcher-level via `tools.cljs/invoke`.

  ## Args

  | Arg | Type | Default | Notes |
  |---|---|---|---|
  | `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

  ## Return shape

      ;; healthy
      {:ok? true
       :session-id <uuid>
       :debug-enabled? <bool>
       :frames <vec>
       :ambiguous-frame? <bool>
       :coord-annotation-enabled? <bool>
       :origin :causa-mcp
       :eval-cljs-enabled? <bool>
       :build-id <kw>}

      ;; warnings — still :ok? true, but with :warning + :note
      {... :ok? true :warning :ambiguous-frame :note <s> ...}

      ;; failures
      {:ok? false :reason :runtime-not-preloaded :hint <setup>}
      {:ok? false :reason :debug-disabled :hint <prod-build-explanation>}
      {:ok? false :reason :no-frames-registered :hint <init-explanation>}

  ## Source-coord pin

  Cite: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
  bead #30. Catalogue entry lives in
  `tools/causa-mcp/spec/004-Tools-Catalogue.md`."
  (:require [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.tools.eval-cljs :as eval-cljs]
            [day8.re-frame2-causa-mcp.wire :as wire]))

(defn shape-envelope
  "Project the runtime's `health` map onto the MCP envelope, adding
  the server-side concerns (`:eval-cljs-enabled?`, `:build-id`) and
  surfacing operator-actionable warnings. Pure — tests pin the
  warning ladder.

  The warning ladder is fail-loud-but-useful:

    1. `:debug-disabled` → production build; trace/epoch surfaces are
       elided. Returns `:ok? false` so agents short-circuit.
    2. `:no-frames-registered` → app hasn't called `(rf/init!)`. Same.
    3. `:ambiguous-frame` → multi-frame app; mutating ops require an
       explicit `:frame` arg. `:ok? true` with `:warning`.
    4. `:no-source-coord-annotation` → DOM coord annotation not
       enabled. `:ok? true` with `:warning`.
    5. otherwise `:ok? true` with no warning."
  [health build-id eval-cljs-enabled?]
  (let [base (merge {:eval-cljs-enabled? eval-cljs-enabled?
                     :build-id           build-id}
                    health)]
    (cond
      (not (:ok? health))
      health

      (not (:debug-enabled? health))
      {:ok?    false
       :reason :debug-disabled
       :hint   (str "re-frame.interop/debug-enabled? is false. This is a "
                    "production build (or goog.DEBUG was forced off). "
                    "Trace and epoch surfaces are elided.")}

      (empty? (:frames health))
      {:ok?    false
       :reason :no-frames-registered
       :hint   "Call (rf/init!) to register :rf/default, or wait for app boot."}

      (:ambiguous-frame? health)
      (assoc base :warning :ambiguous-frame
                  :note    (str "Multiple frames registered: "
                                (vec (:frames health))
                                ". Mutating ops require :frame :foo "
                                "or run `frames/select` first."))

      (not (:coord-annotation-enabled? health))
      (assoc base :warning :no-source-coord-annotation
                  :note    (str "Neither data-rf2-source-coord nor "
                                "data-rc-src is on any element. "
                                "DOM->source ops will degrade. Enable "
                                "(rf/configure :source-coords {:annotate-dom? true}) "
                                "or use re-com with :src (at)."))

      :else
      base)))

(defn discover-app-tool
  "MCP handler for `discover-app`. Probes preload, fetches health, and
  shapes the envelope."
  [conn args]
  (let [build-id (wire/arg-build args)
        eval-en? (eval-cljs/allow-eval-enabled?)]
    (-> (probe/ensure-runtime! conn build-id)
        (.then (fn [_] (probe/runtime-health! conn build-id)))
        (.then
          (fn [health]
            (let [shaped (shape-envelope health build-id eval-en?)
                  elided (elision/count-elided-markers shaped)]
              (wire/ok-text (wire/with-indicators shaped {:elided elided})))))
        (.catch (fn [err] (probe/err->result :discover-failed err))))))

(def descriptor
  {:name        "discover-app"
   :description (str "One-call summary of the runtime's view of the "
                     "world — preload status, debug-enabled flag, "
                     "registered frames (and ambiguity flag), "
                     "source-coord annotation status, --allow-eval "
                     "gate state. Use as the first call of every "
                     "session to confirm the environment is healthy. "
                     "Warnings (:ambiguous-frame, "
                     ":no-source-coord-annotation) surface as :ok? "
                     "true with :warning + :note; failures "
                     "(:debug-disabled, :no-frames-registered, "
                     ":runtime-not-preloaded) surface as :ok? false.")
   :input-schema #js {:type "object"
                      :properties #js {:max-tokens #js {:type "integer"}}}})

(registry/register-tool! (:name descriptor) discover-app-tool descriptor)
