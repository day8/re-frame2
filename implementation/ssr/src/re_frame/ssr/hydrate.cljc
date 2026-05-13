(ns re-frame.ssr.hydrate
  "The `:rf/hydrate` event + hydration-mismatch detection. Per Spec 011
  §The :rf/hydrate event and §Hydration-mismatch detection.

  The server's payload carries `:rf/render-hash`; the handler replaces
  app-db with `:rf/app-db` AND stashes the server hash under
  `[:rf/hydration :server-hash]` so `verify-hydration!` can read it
  after the client's first render.

  Also defines the two `:rf.ssr/check-*` compatibility-check fxs the
  hydrate handler dispatches (rf2-69ad2) — best-effort version +
  schema-digest comparison whose mismatch emits a structured warning
  trace without crashing the hydration path.

  All `reg-event-fx` / `reg-fx` calls live in the `re-frame.ssr`
  façade so a `(require 're-frame.ssr :reload)` after
  `(registrar/clear-all!)` re-installs them. This namespace exports
  the handler fns only.

  Per the rf2-gxgo7 split of re-frame.ssr."
  (:require [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.ssr.hash :as hash]
            [re-frame.trace :as trace]))

(defn hydrate-event-handler
  "Handler fn for the `:rf/hydrate` event. Replaces app-db with
  `(:rf/app-db payload)`, stashes server-hash + version under
  `:rf/hydration`, and dispatches the two `:rf.ssr/check-*` fxs."
  [{:keys [db]} [_ payload]]
  (let [new-db        (or (:rf/app-db payload) (:app-db payload) db)
        version       (:rf/version payload)
        schema-digest (:rf/schema-digest payload)
        metadata      (cond-> {}
                        (:rf/render-hash payload) (assoc :server-hash (:rf/render-hash payload))
                        version                   (assoc :version     version))]
    ;; Per Spec 011 §The :rf/hydrate event: dispatch the compatibility-
    ;; check fxs as part of `:fx` so a mismatch surfaces a structured
    ;; trace event without crashing the hydration path. Both fxs gate on
    ;; payload-key presence — the scalar form passed here is the
    ;; server's value (the "expected"); the fx looks up the client-side
    ;; "actual" via late-bind. Per rf2-69ad2.
    {:db (cond-> new-db
           (seq metadata) (assoc :rf/hydration metadata))
     :fx (cond-> []
           version       (conj [:rf.ssr/check-version       version])
           schema-digest (conj [:rf.ssr/check-schema-digest schema-digest]))}))

;; ---- :rf.ssr/check-version + :rf.ssr/check-schema-digest fxs --------------
;;
;; Per Spec 011 §The :rf/hydrate event (rf2-69ad2). The :rf/hydrate handler
;; dispatches these two fxs after replacing the client app-db with the
;; server's authoritative slice. They are best-effort compatibility checks:
;; a mismatch emits a structured warning trace and the hydration proceeds.
;; The runtime never throws on a mismatch — degraded-but-running beats
;; a crashed boot.
;;
;; Arg shape (clarified per rf2-69ad2 because Spec 011 only pinned the
;; trace shape, not the fx-input shape):
;;
;;   - SCALAR — `[:rf.ssr/check-version <server-value>]` per the spec's
;;     reference :rf/hydrate handler. The fx treats the scalar as the
;;     "expected" (server-side) value and looks up the client-side
;;     "actual" via a late-bind hook (`:rf2/runtime-version` for version,
;;     `:schemas/app-schemas-digest` for schema-digest). When the hook is
;;     unavailable (e.g. version-pinning not yet implemented, or schemas
;;     artefact not on the classpath), the fx emits a
;;     `:rf.ssr/compatibility-check-skipped` trace and no-ops the
;;     comparison.
;;
;;   - MAP — `[:rf.ssr/check-version {:expected ... :actual ...}]` for
;;     callers that compute both sides explicitly (test harnesses, hosts
;;     that pin their own version constant). The fx compares the two
;;     values directly.
;;
;; Gating: `:platforms #{:client}` — these checks only make sense on the
;; hydration side. Server-side dispatches no-op via the standard fx-
;; gating contract (`:rf.fx/skipped-on-platform`).

(defn- check-args
  "Normalise the fx argument to `{:expected <server-value> :actual <client-value>}`.
  Returns nil when the argument doesn't carry an `:expected` value the
  fx can compare against. The `actual-lookup-fn` is a 0-arity fn called
  to resolve the client-side value when the caller passed a scalar; it
  may return nil to signal `:lookup-unavailable`."
  [arg actual-lookup-fn]
  (cond
    (and (map? arg) (contains? arg :expected))
    (cond-> {:expected (:expected arg)}
      (contains? arg :actual) (assoc :actual (:actual arg))
      ;; map without :actual falls back to the lookup
      (not (contains? arg :actual)) (assoc :actual (actual-lookup-fn)))

    (nil? arg) nil

    :else
    {:expected arg :actual (actual-lookup-fn)}))

(defn- runtime-version-lookup
  "Look up the client-side runtime version. No constant is pinned in
  re-frame.core today (per rf2-69ad2 scope); the value is sourced via
  the optional `:rf2/runtime-version` late-bind hook — a host that
  bundles a version-stamp registers it at boot. When the hook is
  absent, returns nil and the check emits
  `:rf.ssr/compatibility-check-skipped`."
  []
  (when-let [f (late-bind/get-fn :rf2/runtime-version)]
    (f)))

(defn- schema-digest-lookup
  "Look up the active frame's `app-schemas-digest`. Sourced via the
  schemas artefact's `:schemas/app-schemas-digest` late-bind hook so
  re-frame.ssr does not statically `:require` the schemas artefact —
  in builds where schemas is absent the lookup returns nil and the
  check emits `:rf.ssr/compatibility-check-skipped`."
  []
  (when-let [f (late-bind/get-fn :schemas/app-schemas-digest)]
    (f)))

(defn check-version-fx
  "Handler fn for the `:rf.ssr/check-version` fx. Compares the
  payload's `:rf/version` (server) against the client runtime's
  version via the `:rf2/runtime-version` late-bind hook."
  [{:keys [frame]} arg]
  (let [{:keys [expected actual]} (check-args arg runtime-version-lookup)]
    (cond
      (nil? expected) nil                              ;; nothing to check

      (nil? actual)
      (trace/emit! :warning :rf.ssr/compatibility-check-skipped
                   {:check    :rf.ssr/check-version
                    :expected expected
                    :reason   "No runtime version available for comparison (no :rf2/runtime-version hook registered)."
                    :frame    frame
                    :recovery :skipped})

      (not= expected actual)
      (trace/emit! :warning :rf.ssr/version-mismatch
                   {:expected expected
                    :actual   actual
                    :frame    frame
                    :reason   (str "Hydration version-mismatch: server '"
                                   expected "' != client '" actual
                                   "'. Hydrating anyway (best-effort).")
                    :recovery :warned-and-applied})

      :else nil)))                                     ;; match → silent

(defn check-schema-digest-fx
  "Handler fn for the `:rf.ssr/check-schema-digest` fx. Compares the
  payload's `:rf/schema-digest` (server) against the client's
  registered app-schema digest via the `:schemas/app-schemas-digest`
  late-bind hook."
  [{:keys [frame]} arg]
  (let [{:keys [expected actual]} (check-args arg schema-digest-lookup)]
    (cond
      (nil? expected) nil                              ;; nothing to check

      (nil? actual)
      (trace/emit! :warning :rf.ssr/compatibility-check-skipped
                   {:check    :rf.ssr/check-schema-digest
                    :expected expected
                    :reason   "No schema digest available for comparison (schemas artefact not on classpath, or :schemas/app-schemas-digest hook absent)."
                    :frame    frame
                    :recovery :skipped})

      (not= expected actual)
      (trace/emit! :warning :rf.ssr/schema-digest-mismatch
                   {:expected expected
                    :actual   actual
                    :frame    frame
                    :reason   (str "Hydration schema-digest mismatch: server '"
                                   expected "' != client '" actual
                                   "'. Deploy drift — server and client are running different schema sets. Hydrating anyway (best-effort).")
                    :recovery :warned-and-applied})

      :else nil)))                                     ;; match → silent

(defn verify-hydration!
  "Per Spec 011 §Hydration-mismatch detection. Called by client code
  after the first render. Compares the post-render hash to the server
  hash stashed during :rf/hydrate; on disagreement emits
  :rf.ssr/hydration-mismatch with :recovery :warned-and-replaced.

  The second arg may be EITHER a render tree (we hash it) OR a
  pre-computed hash string (used by test harnesses that simulate the
  client render).

    (verify-hydration! frame-id render-tree)
    (verify-hydration! frame-id render-tree opts)

  opts may carry :first-diff-path, :failing-id, AND :server-hash.
  The :server-hash opt overrides the [:rf/hydration :server-hash]
  slot in app-db — useful when the user's :rf/hydrate handler doesn't
  populate that slot (e.g. fixture-overridden handlers)."
  ([frame-id tree-or-hash] (verify-hydration! frame-id tree-or-hash {}))
  ([frame-id tree-or-hash {:keys [first-diff-path failing-id server-hash]}]
   (let [db          (frame/frame-app-db-value frame-id)
         server-hash (or server-hash
                         (get-in db [:rf/hydration :server-hash]))
         client-hash (cond
                       (string? tree-or-hash) tree-or-hash
                       tree-or-hash           (hash/render-tree-hash tree-or-hash))]
     (when (and server-hash client-hash (not= server-hash client-hash))
       (let [trace-fn (late-bind/get-fn :trace/emit-error!)]
         (when trace-fn
           (trace-fn :rf.ssr/hydration-mismatch
            (cond-> {:server-hash server-hash
                     :client-hash client-hash
                     :frame       frame-id
                     :failing-id  (or failing-id :rf/hydrate)
                     :reason      (str "Hydration mismatch: server hash '"
                                       server-hash
                                       "' != client hash '"
                                       client-hash
                                       "'. Re-rendering client-side.")
                     :recovery    :warned-and-replaced}
              first-diff-path (assoc :first-diff-path first-diff-path)))))))))
