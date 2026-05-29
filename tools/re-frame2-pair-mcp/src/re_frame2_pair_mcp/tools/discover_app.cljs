(ns re-frame2-pair-mcp.tools.discover-app
  "Tool: discover-app — verify the stack and probe the preloaded runtime."
  (:require [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(defn- resolve-build-id
  "Resolve the build-id discover-app should probe. Returns a Promise of
  `[build-id auto-selected?]`.

  When the caller passed an explicit `:build` arg (or a prior
  discover-app cached one on the conn), `wire/arg-build` already carries
  a deliberate choice — honour it verbatim, never auto-select.

  Otherwise (`arg-build` would fall through to the bare
  `SHADOW_CLJS_BUILD_ID` / `:app` env default), try to auto-select the
  single running build (rf2-v70kv): on a checkout where `:app` isn't the
  running watch, defaulting to `:app` fails by construction on the very
  first no-arg call. Exactly one running build → select it and flag the
  auto-selection so the result can note it. Zero or many running → keep
  the `:app` default and let `ensure-runtime!`'s diagnostic ladder
  surface `:build-not-running` with the running-builds list (the
  multi-build path stays ambiguous-and-loud, deliberately)."
  [conn args]
  (let [build-id (wire/arg-build conn args)]
    (if (wire/arg-build-explicit? conn args)
      (js/Promise.resolve [build-id false])
      (-> (probe/auto-select-single-build conn)
          (.then (fn [[selected auto?]]
                   (if auto? [selected true] [build-id false])))))))

(defn- with-auto-selection
  "Annotate an `:ok? true` discover-app payload with the auto-selection
  fact (rf2-v70kv). No-op when the build was NOT auto-selected (an
  explicit / cached / default build). When auto-selected, records
  `:auto-selected-build` and either seeds or prepends an auto-selection
  sentence to `:note` so the operator sees which build the no-arg call
  landed on."
  [m auto-selected? build-id]
  (if-not auto-selected?
    m
    (let [sentence (str "No :build arg given and exactly one shadow-cljs "
                        "build is running (" build-id ") — auto-selected it.")]
      (-> m
          (assoc :auto-selected-build build-id)
          (update :note (fn [existing]
                          (if existing (str sentence " " existing) sentence)))))))

(defn discover-app [conn args]
  (-> (resolve-build-id conn args)
   (.then
    (fn [[build-id auto-selected?]]
    (-> (probe/ensure-runtime! conn build-id)
        (.then (fn [_] (probe/runtime-health! conn build-id)))
        (.then
          (fn [health]
            (cond
              (not (:ok? health))
              (wire/ok-text health)

              (not (:debug-enabled? health))
              (wire/ok-text {:ok? false :reason :debug-disabled
                             :hint (str "re-frame.interop/debug-enabled? is false. "
                                        "This is a production build (or goog.DEBUG was "
                                        "forced off). Trace and epoch surfaces are elided.")})

              (empty? (:frames health))
              (wire/ok-text {:ok? false :reason :no-frames-registered
                             :hint "Call (rf/init!) to register :rf/default, or wait for app boot."})

              (:ambiguous-frame? health)
              (do
                ;; rf2-l9ixp: even on the ambiguous-frame warning the
                ;; build-id is resolved — record it so subsequent tool
                ;; calls (with the frame disambiguator) don't need to
                ;; re-specify `:build`.
                (wire/mark-resolved-build-id! conn build-id)
                (wire/ok-text
                  (with-auto-selection
                    (assoc health :ok? true
                                  :warning :ambiguous-frame
                                  :note (str "Multiple frames registered: "
                                             (vec (:frames health))
                                             ". Mutating ops require --frame :foo "
                                             "or run `frames/select` first."))
                    auto-selected? build-id)))

              (not (:coord-annotation-enabled? health))
              (do
                (wire/mark-resolved-build-id! conn build-id)
                (wire/ok-text
                  (with-auto-selection
                    (assoc health :ok? true
                                  :warning :no-source-coord-annotation
                                  :note (str "Neither data-rf2-source-coord nor "
                                             "data-rc-src is on any element. "
                                             "DOM->source ops will degrade. Enable "
                                             "(rf/configure :source-coords {:annotate-dom? true}) "
                                             "or use re-com with :src (at)."))
                    auto-selected? build-id)))

              :else
              (do
                ;; rf2-l9ixp: cache the resolved build-id session-wide so
                ;; subsequent tool calls without an explicit `:build` arg
                ;; default to it instead of the `SHADOW_CLJS_BUILD_ID` /
                ;; `:app` env-var fallback. Invalidates on nREPL reconnect.
                (wire/mark-resolved-build-id! conn build-id)
                (wire/ok-text
                  (with-auto-selection
                    (assoc health :ok? true :build-id build-id)
                    auto-selected? build-id))))))
        (.catch (fn [err] (probe/err->result :discover-failed err))))))))
