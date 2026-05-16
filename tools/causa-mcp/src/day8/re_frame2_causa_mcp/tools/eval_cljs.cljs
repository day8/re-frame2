(ns day8.re-frame2-causa-mcp.tools.eval-cljs
  "Tool: `eval-cljs` — arbitrary CLJS evaluation in the host runtime,
  gated by `--allow-eval` (rf2-8xzoe.29, T-Eval-1 of the causa-mcp
  meta tranche).

  ## Launch-flag gate

  Arbitrary code execution is qualitatively different from named
  mutations (`dispatch` / `restore-epoch` / `reset-frame-db`) — the
  programmer must opt in explicitly at server launch. Published builds
  default OFF; the operator passes `--allow-eval` on the causa-mcp
  server command line to enable. When OFF, this tool returns the
  structured refusal envelope `{:ok? false
  :reason :rf.error/eval-cljs-disabled :hint <opt-in-instructions>}`
  without touching the nREPL socket.

  Mirrors pair2-mcp's `eval-cljs` posture (rf2-cxx5s cascade from
  rf2-czv3p). The gate atom (`allow-eval?`) is set by
  `server.cljs/main` from `process.argv` before the dispatcher starts
  handling tools/call requests. Tests flip the atom directly via
  `set-allow-eval!`.

  ## Sync-extent origin tag (Lock #4 / I6)

  When eval is enabled, the tool ships the user's form wrapped in a
  `(binding [day8.re-frame2-causa.runtime/*current-origin* :causa-mcp]
     <user-form>)`
  block so any mutation the form performs during its synchronous
  extent tags `:origin :causa-mcp`. **Async-extent handlers fired
  AFTER the user-form returns do NOT inherit the binding** — this is
  the documented async-tagging gap (Lock #4 / I6 of
  `tools/causa-mcp/spec/Principles.md`), spec'd as known incomplete.
  Agents that want stable async tagging should use the named-mutation
  tools (`dispatch`, etc.) which carry the contract through the
  framework's existing `:tags` plumbing.

  ## Egress scrub

  The eval'd value is shaped by the runtime's `eval-form-result` —
  routes the value through `re-frame.core/elide-wire-value` with the
  caller's `:include-sensitive?` / `:include-large?` opt-in. Privacy +
  elision still apply on the result; bypass requires both
  `--allow-eval` AND `:include-*` true.

  ## Wire-boundary contract

  - **B-1 privacy** — runtime walker substitutes `:rf/redacted` at
    declared-sensitive leaves; `:include-sensitive?` opt-in passes
    through.
  - **W-6 size elision** — runtime walker substitutes
    `{:rf.size/large-elided ...}` at over-threshold leaves; counted
    onto `:elided-large`.
  - **W-1 token cap** — dispatcher-level via `tools.cljs/invoke`.

  ## Args

  | Arg | Type | Default | Notes |
  |---|---|---|---|
  | `:form` | string | nil (REQUIRED) | CLJS source string to eval |
  | `:include-sensitive?` | bool | false | passes to runtime walker |
  | `:include-large?` | bool | false | passes to runtime walker |
  | `:max-tokens` | int | 5000 | per-call cap (`[500, 50000]`) |

  ## Return shape (enabled)

      {:ok? true :value <edn>
       :elided-large <int?>}

  ## Refusal shape (default — gate OFF)

      {:ok?    false
       :reason :rf.error/eval-cljs-disabled
       :hint   \"eval-cljs is disabled by default for security; pass
                 --allow-eval at server launch to opt in.\"}

  ## Source-coord pin

  Cite: `ai/findings/causa-epics-breakdown-2026-05-17.md` §Part 1
  bead #29. Catalogue entry lives in
  `tools/causa-mcp/spec/004-Tools-Catalogue.md`."
  (:require [clojure.string :as str]
            [day8.re-frame2-causa-mcp.elision :as elision]
            [day8.re-frame2-causa-mcp.eval-form :as ef]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]
            [day8.re-frame2-causa-mcp.privacy :as privacy]
            [day8.re-frame2-causa-mcp.probe :as probe]
            [day8.re-frame2-causa-mcp.registry :as registry]
            [day8.re-frame2-causa-mcp.wire :as wire]))

;; ---------------------------------------------------------------------------
;; Launch-flag gate
;; ---------------------------------------------------------------------------

(defonce ^:private allow-eval?
  ;; Default OFF in published builds. `server.cljs/main` flips this to
  ;; `true` when `--allow-eval` is present in `process.argv`.
  (atom false))

(defn set-allow-eval!
  "Set the `eval-cljs` launch-flag gate. Called once by
  `server.cljs/main` during boot; called by tests to flip the gate.
  `enabled?` is coerced to boolean."
  [enabled?]
  (reset! allow-eval? (boolean enabled?)))

(defn allow-eval-enabled?
  "Read the current gate state. Exposed for tests + server-side
  logging."
  []
  @allow-eval?)

;; ---------------------------------------------------------------------------
;; Form composition
;; ---------------------------------------------------------------------------

(defn build-form
  "Wrap the user's CLJS source string in the origin-binding + the
  runtime's `eval-form-result` shaper. Pure — tests pin the structure.

  The shape is

      (binding [day8.re-frame2-causa.runtime/*current-origin* :causa-mcp]
        (day8.re-frame2-causa.runtime/eval-form-result
          <user-form> <opts-map>))

  so any mutation the user-form performs during its synchronous extent
  carries `:origin :causa-mcp`, and the returned value is routed
  through `elide-wire-value` server-side before the result crosses the
  nREPL wire."
  [user-form opts]
  (-> (ef/rt-call 'eval-form-result (ef/rt-raw user-form) opts)
      (ef/emit)
      (ef/wrap-origin)))

(defn shape-envelope
  "Shape the runtime's `{:ok? true :value <edn>}` response. Pure —
  tests pin the indicator stamping."
  [runtime-envelope]
  (let [{:keys [ok?] :as env} (if (map? runtime-envelope) runtime-envelope {})]
    (cond
      (false? ok?) env

      (not (true? ok?))
      {:ok?     false
       :reason  :unexpected-shape
       :runtime runtime-envelope
       :hint    "runtime/eval-form-result returned a non-envelope shape"}

      :else
      (let [elided (elision/count-elided-markers (:value env))]
        (wire/with-indicators env {:elided elided})))))

;; ---------------------------------------------------------------------------
;; Tool handler
;; ---------------------------------------------------------------------------

(def disabled-hint
  "Operator-facing setup hint surfaced on the structured
  `:rf.error/eval-cljs-disabled` refusal envelope."
  (str "eval-cljs is disabled by default for security; pass "
       "--allow-eval at server launch to opt in."))

(defn eval-cljs-tool [conn args]
  (let [build-id  (wire/arg-build args)
        form      (wire/arg args :form)
        incl?     (privacy/parse-include-sensitive args)
        incl-large? (elision/parse-include-large args)]
    (cond
      (not @allow-eval?)
      (js/Promise.resolve
        (wire/err-text {:ok?    false
                        :reason :rf.error/eval-cljs-disabled
                        :hint   disabled-hint}))

      (or (nil? form) (and (string? form) (str/blank? form)))
      (js/Promise.resolve
        (wire/ok-text {:ok?    false
                       :reason :missing-form
                       :hint   "Pass :form \"<cljs-source-string>\"."}))

      :else
      (let [opts        {:include-sensitive? incl?
                         :include-large?     incl-large?}
            wrapped     (build-form form opts)]
        (-> (probe/ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id wrapped)))
            (.then (fn [runtime-env]
                     (wire/ok-text (shape-envelope runtime-env))))
            (.catch (fn [err] (probe/err->result :eval-error err))))))))

(def descriptor
  {:name        "eval-cljs"
   :description (str "Evaluate an arbitrary CLJS form in the host "
                     "runtime. GATED behind --allow-eval at server "
                     "launch; default OFF returns "
                     ":rf.error/eval-cljs-disabled refusal envelope. "
                     "When enabled, the form runs inside a "
                     "(binding [*current-origin* :causa-mcp] ...) "
                     "wrapper so synchronous-extent mutations tag "
                     ":origin :causa-mcp (async-extent handlers do NOT "
                     "inherit the binding — documented Lock #4 / I6 "
                     "gap). The result routes through "
                     "elide-wire-value (privacy + size scrub still "
                     "apply; pass :include-sensitive? / "
                     ":include-large? true to opt out).")
   :input-schema #js {:type "object"
                      :required #js ["form"]
                      :properties #js {:form               #js {:type "string"}
                                       :include-sensitive? #js {:type "boolean"}
                                       :include-large?     #js {:type "boolean"}
                                       :max-tokens         #js {:type "integer"}}}})

(registry/register-tool! (:name descriptor) eval-cljs-tool descriptor)
