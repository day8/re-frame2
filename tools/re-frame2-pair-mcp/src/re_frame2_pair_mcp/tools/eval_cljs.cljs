(ns re-frame2-pair-mcp.tools.eval-cljs
  "Tool: eval-cljs — evaluate one CLJS form.

  ## Launch-flag gate (rf2-cxx5s, cascade from rf2-czv3p)

  Arbitrary code execution is qualitatively different from named
  mutations (`dispatch`, etc.) — the programmer must opt in explicitly
  at server launch. Published builds default to OFF; the operator
  passes `--allow-eval` on the re-frame2-pair-mcp server command line to enable.

  The gate is a single atom (`allow-eval?`) set by `server.cljs/main`
  from `process.argv` before the dispatcher starts handling tools/call
  requests. When OFF, `eval-cljs-tool` returns the structured error
  `{:ok? false :reason :rf.error/eval-cljs-disabled ...}` without
  touching the nREPL socket.

  Tests flip the atom directly via `set-allow-eval!`."
  (:require [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(defonce ^:private allow-eval?
  ;; Default OFF in published builds. `server.cljs/main` flips this to
  ;; `true` when `--allow-eval` is present in `process.argv`.
  (atom false))

(defn set-allow-eval!
  "Set the eval-cljs launch-flag gate. Called once by `server.cljs/main`
  during boot; called by tests to flip the gate."
  [enabled?]
  (reset! allow-eval? (boolean enabled?)))

(defn allow-eval-enabled?
  "Read the current gate state. Exposed for tests + server-side logging."
  []
  @allow-eval?)

(defn eval-cljs-tool [conn args]
  (let [form     (wire/arg args :form)
        build-id (wire/arg-build args)]
    (cond
      (not @allow-eval?)
      (js/Promise.resolve
        (wire/err-text
          {:ok?    false
           :reason :rf.error/eval-cljs-disabled
           :hint   (str "eval-cljs is disabled by default for security; "
                        "pass --allow-eval at server launch to opt in")}))

      (or (nil? form) (str/blank? form))
      (js/Promise.resolve
        (wire/err-text {:ok? false :reason :missing-form
                        :hint "usage: eval-cljs {form '<cljs-form>' [build :app]}"}))

      :else
      (-> (probe/ensure-runtime! conn build-id)
          (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
          (.then (fn [v] (wire/ok-text {:ok? true :value v})))
          (.catch (fn [err] (probe/err->result :eval-error err)))))))
