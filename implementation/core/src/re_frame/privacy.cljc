(ns re-frame.privacy
  "Privacy policy helpers.

  Schema metadata is the canonical path-level privacy declaration:
  `{:sensitive? true}` on an app-schema slot feeds the elision registry
  and the router installs an internal redaction interceptor for matching
  path-scoped handlers. Path-marked sensitive classification (planned)
  supersedes the previous handler-meta `:sensitive?` annotation —
  sensitivity is now a property of the data value at a path, not of the
  handler that touched it."
  (:require [re-frame.interceptor :as interceptor]
            [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

(def redacted-sentinel
  :rf/redacted)

(defn sensitive?
  [trace-event]
  (and (map? trace-event)
       (true? (:sensitive? trace-event))))

(defn- path-prefix?
  [prefix path]
  (let [prefix (vec prefix)
        path   (vec path)]
    (and (<= (count prefix) (count path))
         (= prefix (subvec path 0 (count prefix))))))

(defn- relative-path
  [prefix path]
  (subvec (vec path) (count (vec prefix))))

(defn- handler-db-paths
  [interceptors]
  (into []
        (comp (filter map?)
              (keep :path))
        interceptors))

(defn- sensitive-declarations
  [frame-id]
  (if-let [f (late-bind/get-fn :elision/sensitive-declarations)]
    (f frame-id)
    {}))

(defn schema-redaction-paths
  "Return event-payload paths that should be redacted for a handler
  whose interceptor chain focuses app-db through `path` interceptors."
  [frame-id interceptors]
  (let [sensitive-paths (keys (sensitive-declarations frame-id))]
    (vec
      (distinct
        (mapcat
          (fn [db-path]
            (keep (fn [sensitive-path]
                    (when (path-prefix? db-path sensitive-path)
                      (relative-path db-path sensitive-path)))
                  sensitive-paths))
          (handler-db-paths interceptors))))))

(defn schema-sensitive-handler?
  "True when a handler's path-scoped db slice overlaps a schema-declared
  sensitive app-db slot."
  [frame-id interceptors]
  (boolean (seq (schema-redaction-paths frame-id interceptors))))

(defn- redact-path
  [payload path]
  (let [path (vec path)]
    (cond
      (empty? path)
      redacted-sentinel

      (some? (get-in payload (butlast path)))
      (assoc-in payload path redacted-sentinel)

      :else
      payload)))

(defn redact-paths
  [payload paths]
  (reduce redact-path payload paths))

(defn redact-event
  "Redact schema-derived payload paths in a conventional event vector.
  Non-map payload shapes pass through unchanged."
  [event paths]
  (if (and (vector? event)
           (>= (count event) 2)
           (map? (second event)))
    (let [[id payload & rest-args] event
          redacted-payload (redact-paths payload paths)]
      (into [id redacted-payload] rest-args))
    event))

(defn redacted-event-from-ctx
  [ctx]
  (or (:rf/redacted-event ctx)
      (interceptor/get-coeffect ctx :event)))

(defn schema-redaction-interceptor
  "Internal interceptor installed by the router for schema-sensitive
  path-scoped handlers. The handler body keeps the original `:event`
  coeffect; trace/error emit sites read `:rf/redacted-event`."
  [paths]
  (let [paths (vec paths)]
    (interceptor/->interceptor
      :id :rf/schema-redaction
      :before
      (fn [ctx]
        (assoc ctx :rf/redacted-event
               (redact-event (interceptor/get-coeffect ctx :event) paths))))))

;; ---- redact-interceptor — user-installed positional interceptor ----------------
;;
;; The positional `redact-interceptor` interceptor scrubs named payload keys
;; *before* the handler body runs. The handler sees the unredacted value
;; via the regular `:event` coeffect; the trace surface sees the redacted
;; version via `:rf/redacted-event`.
;;
;; The interceptor carries its `:paths` on the interceptor map itself so
;; the router can collect them at chain-assembly time and fold them into
;; the pre-chain `:run-start` / `emit-cascade-trailers` event projection
;; (which fires BEFORE any chain `:before` runs). The `:before` here remains
;; load-bearing for the in-chain composition with `:rf/schema-redaction`:
;; when both interceptors are present, this `:before` extends the already-
;; stashed `:rf/redacted-event` rather than overwriting it, so the union
;; of paths is scrubbed.

(def redact-interceptor-id :rf/redact-interceptor)

(defn redact-interceptor
  "Build a positional interceptor that overwrites the named keys in the
  event vector's payload map with the `:rf/redacted` sentinel before the
  handler body runs.

  The handler itself receives the UNREDACTED payload via the regular
  `:event` coeffect slot; the redaction is for the trace surface only
  (`:event/*` trace events, `:event/db-changed`, `:rf.error/handler-
  exception`, and the always-on error-emit substrate's record).

  `paths` is a sequence of `get-in`-style key paths into the payload map
  (the second element of the event vector, per the canonical M-19 map-
  payload form). A path that targets a missing leaf is a no-op; an empty
  path scrubs the whole payload to `:rf/redacted`. Non-map payload shapes
  pass through unchanged.

  Composition:
    - With schema `:sensitive?` on a path-scoped handler — additive. The
      router installs an internal redaction interceptor for schema-
      declared paths; this user-installed interceptor extends (does not
      replace) the stashed `:rf/redacted-event` with its own paths.
    - With epoch `:redact-fn` — independent. The redact-fn runs at the
      assembled epoch-record boundary; this interceptor runs per
      handler invocation on the trace surface inside the cascade. The
      record carries the already-scrubbed trace events into the fn.

  Usage:

      (rf/reg-event-fx :auth/login
        [(rf/redact-interceptor [[:password] [:token]])]
        (fn [{:keys [db]} [_ {:keys [username password token]}]]
          ;; password + token visible HERE (unredacted via :event coeffect)
          ;; trace surface sees them as :rf/redacted
          ...))

  Per [API.md §Privacy](API.md#privacy-spec-009-privacy--sensitive-data-in-traces)
  and [Security.md §Behavioural MUSTs across the privacy surface](Security.md#behavioural-musts-across-the-privacy-surface)."
  [paths]
  (let [paths (vec paths)]
    (interceptor/->interceptor
      :id     redact-interceptor-id
      ;; Paths are exposed on the interceptor map for chain-walking
      ;; consumers (router `prepare-handler-ctx` collects them so the
      ;; pre-chain `:run-start` trace event already carries the
      ;; redacted projection).
      :paths  paths
      :before
      (fn [ctx]
        (let [base    (or (:rf/redacted-event ctx)
                          (interceptor/get-coeffect ctx :event))
              scrubbed (redact-event base paths)]
          (assoc ctx :rf/redacted-event scrubbed))))))

(defn- redact-interceptor?
  [interceptor]
  (and (map? interceptor)
       (= redact-interceptor-id (:id interceptor))))

(defn user-redaction-paths
  "Walk an interceptor chain and return the concatenated `:paths` vectors
  of every `redact-interceptor` interceptor it contains.

  Read by the router at chain-assembly time so the pre-chain trace events
  (`:run-start`, `emit-cascade-trailers`'s `:run-end`) and the schema-
  derived emit-event projection both honour user-declared payload paths."
  [interceptors]
  (into []
        (comp (filter redact-interceptor?)
              (mapcat :paths))
        interceptors))

(defn clear-suppression-cache!
  "Compatibility hook name used by frame teardown. Path-D privacy has no
  registration-warning cache, so this is intentionally a no-op."
  []
  nil)

(late-bind/set-fn! :privacy/clear-suppression-cache! clear-suppression-cache!)
