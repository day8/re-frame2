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
  ;; have Malli on the path.
  (try
    (let [m (requiring-resolve 'malli.core/validate)]
      ((m) schema value))
    (catch #?(:clj Throwable :cljs :default) _ true)))

(defn- malli-explain*
  [schema value]
  (try
    (let [e (requiring-resolve 'malli.core/explain)]
      ((e) schema value))
    (catch #?(:clj Throwable :cljs :default) _ nil)))

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

(defn validate-app-db!
  "After a handler commits :db, walk every registered app-schema and
  validate the post-state. Failures trace as
  :rf.error/schema-validation-failure with a Malli explanation."
  [db]
  (when interop/debug-enabled?
    (doseq [[path meta] (registrar/handlers :app-schema)]
      (let [val-at (get-in db path)
            schema (:schema meta)]
        (when-not (malli-validate* schema val-at)
          (trace/emit-error! :rf.error/schema-validation-failure
                             {:where    :app-db
                              :path     path
                              :value    val-at
                              :explain  (malli-explain* schema val-at)
                              :recovery :no-recovery}))))))

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
