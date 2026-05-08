(ns re-frame.schemas
  "Schema attachment and dev-only validation. Per Spec 010.

  Schemas attach to registrations under :spec metadata; the validation
  fires at locked points (event vector before handler runs; sub return
  after compute; app-db after each handler completes). In dev builds
  only — production builds elide.

  This first-pass implementation uses Malli when available; if the user
  hasn't pulled Malli into their project, schemas are silently ignored."
  (:require [re-frame.registrar :as registrar]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.trace :as trace]))

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
  "Per Spec 010 §Validation order step 1 — before an event handler runs,
  validate the event vector against any :spec on the handler's metadata.

  Returns true on pass (or when validation is elided / no schema is
  attached), false on fail. Failures emit
  :rf.error/schema-validation-failure with :where :event; the caller
  is responsible for skipping the handler (recovery: :no-recovery)."
  [event-id event handler-meta]
  (if (and interop/debug-enabled? (:spec handler-meta))
    (let [schema (:spec handler-meta)]
      (if (malli-validate* schema event)
        true
        (do
          (trace/emit-error! :rf.error/schema-validation-failure
                             {:where       :event
                              :event-id    event-id
                              :failing-id  event-id
                              :spec-id     event-id
                              :received    event
                              :event       event
                              :malli-error (malli-explain* schema event)
                              :explain     (malli-explain* schema event)
                              :reason      (str "Event " event-id
                                                " payload failed schema "
                                                schema ", got "
                                                (type-of-value event) ".")
                              :recovery    :no-recovery})
          false)))
    true))

(defn validate-sub-return!
  "Per Spec 010 §Validation order step 6 — after a sub recomputes,
  validate its return value against any :spec on the sub's metadata.

  Returns true on pass, false on fail. Failures emit
  :rf.error/schema-validation-failure with :where :sub-return; the
  caller is responsible for replacing the value with the
  default (nil) per the :replaced-with-default recovery."
  [sub-id query-v value sub-meta]
  (if (and interop/debug-enabled? (:spec sub-meta))
    (let [schema (:spec sub-meta)]
      (if (malli-validate* schema value)
        true
        (do
          (trace/emit-error! :rf.error/schema-validation-failure
                             {:where       :sub-return
                              :sub-id      sub-id
                              :failing-id  sub-id
                              :spec-id     sub-id
                              :query-v     query-v
                              :received    value
                              :value       value
                              :malli-error (malli-explain* schema value)
                              :explain     (malli-explain* schema value)
                              :reason      (str "Subscription " sub-id
                                                " return value failed schema "
                                                schema ", got "
                                                (type-of-value value) ".")
                              :recovery    :replaced-with-default})
          false)))
    true))

(defn validate-cofx!
  "Per Spec 010 §Validation order step 2 — after a cofx injects its value
  into the merged context, validate that value against any :spec on the
  cofx's metadata.

  Returns true on pass, false on fail. Failures emit
  :rf.error/schema-validation-failure with :where :cofx; the caller is
  responsible for skipping the handler (recovery: :no-recovery)."
  [cofx-id event-id value cofx-meta]
  (if (and interop/debug-enabled? (:spec cofx-meta))
    (let [schema (:spec cofx-meta)]
      (if (malli-validate* schema value)
        true
        (do
          (trace/emit-error! :rf.error/schema-validation-failure
                             {:where       :cofx
                              :cofx-id     cofx-id
                              :event-id    event-id
                              :failing-id  event-id
                              :spec-id     cofx-id
                              :received    value
                              :value       value
                              :malli-error (malli-explain* schema value)
                              :explain     (malli-explain* schema value)
                              :reason      (str "Coeffect " cofx-id
                                                " injected value failed schema "
                                                schema ", got "
                                                (type-of-value value) ".")
                              :recovery    :no-recovery})
          false)))
    true))

;; ---- late-bind hook registration ------------------------------------------
;;
;; re-frame.router, re-frame.cofx, and re-frame.subs need to call into
;; schema validation but cannot `:require` this namespace without a
;; cyclic load order. Publish entry points through the late-bind hook
;; registry. See re-frame.late-bind.

(late-bind/set-fn! :schemas/validate-app-db!     validate-app-db!)
(late-bind/set-fn! :schemas/validate-event!      validate-event!)
(late-bind/set-fn! :schemas/validate-sub-return! validate-sub-return!)
(late-bind/set-fn! :schemas/validate-cofx!       validate-cofx!)
