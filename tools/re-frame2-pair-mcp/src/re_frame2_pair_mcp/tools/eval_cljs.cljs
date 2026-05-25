(ns re-frame2-pair-mcp.tools.eval-cljs
  "Tool: eval-cljs — evaluate one CLJS form.

  ## Launch-flag gate (rf2-a0z0h; inverts the prior rf2-cxx5s default)

  The eval-cljs tool is the REPL primitive of a pair-debug session —
  arbitrary form evaluation against the live re-frame2 runtime is the
  whole reason an operator installs re-frame2-pair-mcp. Published
  builds default this surface **ON**; the operator opts OUT via
  `--no-eval` at server launch for the rare paranoid case (CI runs,
  shared dev environments where multiple humans share a single MCP
  process).

  ### Threat-model rationale

  The prior rf2-cxx5s gate (default OFF) parallelled `--allow-writes`
  in shape but not in effect. `--allow-writes` is load-bearing because
  pair-tool writes can confuse the debug audit trail (\"did my app
  produce this state change, or did the pair tool?\"). `--allow-eval`
  did NOT parallel that protection: eval-cljs can express any write
  the writes-gate would block. The two gates are not independent —
  once eval is on, writes are de-facto on. So a default-OFF eval
  surface added friction without adding a separable protection.

  The real defence is **don't expose this MCP to untrusted callers**.
  Once an operator has installed re-frame2-pair-mcp and wired it into
  `~/.claude.json`, they've already declared trust in the surface.

  ### Implementation

  The gate is a single atom (`eval-allowed?`) set by
  `server.cljs/main` from `process.argv` before the dispatcher starts
  handling tools/call requests. The atom defaults to `true`; passing
  `--no-eval` flips it to `false`. Tests flip the atom directly via
  `set-eval-allowed!`."
  (:require [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(defonce ^:private eval-allowed?
  ;; Default ON in published builds. `server.cljs/main` flips this to
  ;; `false` when `--no-eval` is present in `process.argv`.
  (atom true))

(defn set-eval-allowed!
  "Set the eval-cljs launch-flag gate. Called once by `server.cljs/main`
  during boot; called by tests to flip the gate."
  [enabled?]
  (reset! eval-allowed? (boolean enabled?)))

(defn eval-allowed-enabled?
  "Read the current gate state. Exposed for tests + server-side logging."
  []
  @eval-allowed?)

(defn eval-cljs-tool [conn args]
  (let [form      (wire/arg args :form)
        build-id  (wire/arg-build conn args)
        explicit? (wire/arg-build-explicit? conn args)]
    (cond
      (not @eval-allowed?)
      (js/Promise.resolve
        (wire/err-text
          {:ok?    false
           :reason :rf.error/eval-cljs-disabled
           :hint   (str "eval-cljs has been disabled for this server "
                        "instance via --no-eval; relaunch without that "
                        "flag to enable.")}))

      (or (nil? form) (str/blank? form))
      (js/Promise.resolve
        (wire/err-text {:ok? false :reason :missing-form
                        :hint "usage: eval-cljs {form '<cljs-form>' [build :app]}"}))

      :else
      ;; rf2-ivlb3: resolve the build (auto-detect the single running one
      ;; when no explicit :build was passed) and confirm a live runtime
      ;; BEFORE eval'ing. Never emit `:ok? true :value nil` for a build
      ;; with no runtime — that's indistinguishable from a genuine nil.
      (-> (probe/resolve-and-preflight! conn build-id explicit?)
          (.then (fn [resolved-build]
                   (-> (nrepl/cljs-eval-value conn resolved-build form)
                       (.then (fn [v] (wire/ok-text {:ok?   true
                                                     :value v
                                                     :build resolved-build}))))))
          (.catch (fn [err] (probe/err->result :eval-error err)))))))
