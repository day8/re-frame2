(ns re-frame.privacy
  "Privacy policy helpers.

  Schema metadata is the canonical path-level privacy declaration:
  `{:sensitive? true}` on an app-schema slot feeds the elision registry
  and the router installs an internal redaction interceptor for matching
  path-scoped handlers. Handler metadata `:sensitive?` remains the
  cross-cutting escape hatch."
  (:require [re-frame.interceptor :as interceptor]
            [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

(def redacted-sentinel
  :rf/redacted)

(defn sensitive?-from-meta
  [meta]
  (true? (:sensitive? meta)))

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

(defn clear-suppression-cache!
  "Compatibility hook name used by frame teardown. Path-D privacy has no
  registration-warning cache, so this is intentionally a no-op."
  []
  nil)

(late-bind/set-fn! :privacy/clear-suppression-cache! clear-suppression-cache!)
