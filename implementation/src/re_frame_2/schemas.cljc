(ns re-frame-2.schemas
  "Schema attachment and dev-only validation. Per Spec 010.

  Schemas attach to registrations under :spec metadata; the validation
  fires at locked points (event vector before handler runs; sub return
  after compute; app-db after each handler completes). In dev builds
  only — production builds elide.

  This first-pass implementation uses Malli when available; if the user
  hasn't pulled Malli into their project, schemas are silently ignored."
  (:require [re-frame-2.registrar :as registrar]
            [re-frame-2.interop :as interop]
            [re-frame-2.trace :as trace]))

(defn- malli-validate*
  [schema value]
  ;; Defensive: if Malli isn't loaded, treat as 'pass'. Real builds will
  ;; have Malli on the path. Uses requiring-resolve on JVM (lazy load);
  ;; on CLJS we require malli.core directly via :require so the var is
  ;; reachable through resolve.
  #?(:clj
     (try
       (let [validate (requiring-resolve 'malli.core/validate)]
         (validate schema value))
       (catch Throwable _ true))
     :cljs
     (try
       (let [validate (resolve 'malli.core/validate)]
         (if validate (validate schema value) true))
       (catch :default _ true))))

(defn- malli-explain*
  [schema value]
  #?(:clj
     (try
       (let [explain (requiring-resolve 'malli.core/explain)]
         (explain schema value))
       (catch Throwable _ nil))
     :cljs
     (try
       (let [explain (resolve 'malli.core/explain)]
         (when explain (explain schema value)))
       (catch :default _ nil))))

;; ---- app-db schema registration -------------------------------------------

(defn reg-app-schema
  "Register a Malli schema at a path inside app-db. Validation runs in
  dev whenever an event handler returns a new app-db; failures emit
  :rf.error/schema-validation-failure."
  [path schema]
  (registrar/register! :app-schema path {:schema schema :path path})
  path)

(defn app-schema-at
  "Lookup the registered schema for a path, or nil."
  [path]
  (when-let [meta (registrar/lookup :app-schema path)]
    (:schema meta)))

;; ---- validation entry points ----------------------------------------------

(defn- type-of-value [v]
  (cond
    (string? v)  "string"
    (integer? v) "integer"
    (number? v)  "number"
    (boolean? v) "boolean"
    (keyword? v) "keyword"
    (map? v)     "map"
    (vector? v)  "vector"
    (nil? v)     "nil"
    :else        (str (type v))))

(defn validate-app-db!
  "After a handler commits :db, walk every registered app-schema and
  validate the post-state. Failures trace as
  :rf.error/schema-validation-failure with a Malli explanation.

  event-id (optional) names the handler whose commit prompted the
  failure — surfaced as :failing-id in the error tags."
  ([db] (validate-app-db! db nil))
  ([db event-id]
   (when interop/debug-enabled?
     (doseq [[path meta] (registrar/handlers :app-schema)]
       (let [val-at (get-in db path)
             schema (:schema meta)]
         (when-not (malli-validate* schema val-at)
           (trace/emit-error! :rf.error/schema-validation-failure
                              (cond-> {:where    :app-db
                                       :path     path
                                       :value    val-at
                                       :explain  (malli-explain* schema val-at)
                                       :reason   (str "App-db at path " path
                                                      " failed schema " schema
                                                      ": expected "
                                                      (cond
                                                        (and (vector? schema)
                                                             (= 1 (count schema))
                                                             (keyword? (first schema)))
                                                        (first schema)
                                                        :else schema)
                                                      ", got " (type-of-value val-at) ".")
                                       :recovery :no-recovery}
                                event-id (assoc :failing-id event-id)))))))))

(defn validate-event!
  "Before an event handler runs, validate the event vector against any
  :spec on the handler's metadata."
  [event-id event handler-meta]
  (when interop/debug-enabled?
    (when-let [schema (:spec handler-meta)]
      (when-not (malli-validate* schema event)
        (trace/emit-error! :rf.error/schema-validation-failure
                           {:where   :event
                            :event-id event-id
                            :event   event
                            :explain (malli-explain* schema event)
                            :recovery :no-recovery})))))
