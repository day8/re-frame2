(ns re-frame2-pair-mcp.tools.discover-app
  "Tool: discover-app — verify the stack and probe the preloaded runtime."
  (:require [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(defn- port-unresolved-result
  "Error envelope for a `:port` arg that didn't resolve to a build via
  the `:dev-http` map (rf2-fyf0h)."
  [port]
  (wire/ok-text
    {:ok?    false
     :reason :port-unresolved
     :port   port
     :hint   (str "No shadow-cljs build serves port " port " via the "
                  ":dev-http map (the port may be missing from :dev-http, "
                  "or no build's :output-dir matches its file roots). "
                  "Check the port in your browser's address bar, or pass "
                  ":build with the build-id directly.")}))

(defn- resolve-build-id
  "Resolve the build discover-app should probe. Returns a Promise of a
  result map. Precedence (highest first):

    1. Explicit `:build` arg (or a build cached by a prior discover-app)
       — `wire/arg-build` carries a deliberate choice; honour it
       verbatim, never auto-select. Wins over `:port`.
    2. `:port` arg (rf2-fyf0h) — resolve the serving build from the
       shadow-cljs `:dev-http` map so an agent that only knows the
       browser URL (e.g. http://localhost:8031/...) needn't grep the
       repo for the build-id. A `:port` that resolves to no build is a
       loud `:port-unresolved` error — NOT a silent fall-through to the
       `:app` default (the operator asked for that port specifically).
    3. Single running build (rf2-v70kv) — exactly one → auto-select +
       flag it. Zero/many → keep the `:app` default so the diagnostic
       ladder surfaces `:build-not-running` with the running list.

  Return shapes:
    {:build-id <kw> :auto-selected? <bool>}   — proceed to probe.
    {:error <js-envelope>}                     — short-circuit (port-unresolved)."
  [conn args]
  (let [explicit-build (wire/arg-build conn args)
        port           (wire/arg args :port)]
    (cond
      (wire/arg-build-explicit? conn args)
      (js/Promise.resolve {:build-id explicit-build :auto-selected? false})

      (some? port)
      (-> (probe/resolve-build-by-port conn port)
          (.then (fn [resolved]
                   (if resolved
                     {:build-id resolved :auto-selected? false}
                     {:error (port-unresolved-result port)}))))

      :else
      (-> (probe/auto-select-single-build conn)
          (.then (fn [[selected auto?]]
                   (if auto?
                     {:build-id selected :auto-selected? true}
                     {:build-id explicit-build :auto-selected? false})))))))

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
    (fn [{:keys [build-id auto-selected? error]}]
    (if error
     error
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
                                             "(rf/configure! :source-coords {:annotate-dom? true}) "
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
        (.catch (fn [err] (probe/err->result :discover-failed err)))))))))
