(ns re-frame.core-schemas
  "Public-API wrappers for the optional schemas artefact (Spec 010).

  Per rf2-p7va the schemas implementation ships in the
  `day8/re-frame2-schemas` Maven artefact. The core artefact MUST NOT
  statically `:require [re-frame.schemas]` — that would pull schemas
  (and its Malli dep) onto every consumer's classpath even when no
  schema is registered.

  The fns in this namespace look the schemas API up through the
  late-bind hook table at call time, which the schemas artefact
  populates from its own ns-load.

  Per rf2-hoiu these wrappers live here (and `re-frame.core` re-exports
  them) so `core.cljc` is not cluttered with optional-artefact glue.
  The single-import contract is preserved: users continue to write
  `rf/reg-app-schema` after `(:require [re-frame.core :as rf])`."
  (:require [re-frame.late-bind :as late-bind]))

(defn app-schema-at
  "Return the registered schema for a path in a frame, or nil. Per Spec
  010 §Schemas as a tooling and agent surface. Returns nil when the
  schemas artefact is not on the classpath."
  ([path] (app-schema-at path {}))
  ([path opts]
   (when-let [f (late-bind/get-fn :schemas/app-schema-at)]
     (f path opts))))

(defn app-schemas
  "Return every registered `app-schema-at` declaration for a frame as a
  `{path → schema}` map. Per Spec 010 §Per-frame schemas. Returns `{}`
  when the schemas artefact is not on the classpath."
  ([] (app-schemas {}))
  ([opts-or-frame-id]
   (if-let [f (late-bind/get-fn :schemas/app-schemas)]
     (f opts-or-frame-id)
     {})))

(defn app-schemas-digest
  "Return a stable digest of the registered schemas for a frame. Per
  Spec 010 §Digest algorithm. Returns `nil` when the schemas artefact
  is not on the classpath."
  ([] (app-schemas-digest {}))
  ([opts-or-frame-id]
   (when-let [f (late-bind/get-fn :schemas/app-schemas-digest)]
     (f opts-or-frame-id))))

(defn set-schema-validator!
  "Register the validator fn that every dev-time schema-validation site
  routes through. Per Spec 010 §Non-Malli validators (rf2-froe) the
  seam is the substitute-Malli extension point — apps that want to
  drop the ~24 KB gzipped Malli surface (rf2-qnxf bundle audit) swap
  in their own validator at boot.

  Argument shapes:

    (rf/set-schema-validator! validate-fn)
      validate-fn :: (fn [schema value] truthy?)
                   | nil   ;; disables validation entirely

    (rf/set-schema-validator! {:validate validate-fn :explain explain-fn})
      Atomic swap of both fns at once.

  Default behaviour ships Malli's validate / explain pair; calling
  this fn replaces them. Returns nil when the schemas artefact is
  not on the classpath (apps that don't want schemas don't need to
  pull `day8/re-frame2-schemas`)."
  [validate-fn-or-map]
  (when-let [f (late-bind/get-fn :schemas/set-schema-validator!)]
    (f validate-fn-or-map)))

(defn set-schema-explainer!
  "Register the explainer fn — `(fn [schema value] explanation)` — used
  to enrich schema-validation-failure traces' `:explain` key. Per
  Spec 010 §Non-Malli validators (rf2-froe). See
  `set-schema-validator!` for the validator companion. Returns nil
  when the schemas artefact is not on the classpath."
  [explain-fn]
  (when-let [f (late-bind/get-fn :schemas/set-schema-explainer!)]
    (f explain-fn)))

(defn reg-app-schema
  "Fn-form delegate that performs the late-bind lookup for
  `reg-app-schema`. The `re-frame.core/reg-app-schema` macro (JVM) and
  the CLJS `def`-alias both route here, so the late-bind logic and the
  missing-artefact error message live in one place."
  ([path schema] (reg-app-schema path schema {}))
  ([path schema opts]
   (if-let [f (late-bind/get-fn :schemas/reg-app-schema)]
     (f path schema opts)
     (throw (ex-info ":rf.error/schemas-artefact-missing"
                     {:where    'reg-app-schema
                      :path     path
                      :recovery :no-recovery
                      :reason   "rf/reg-app-schema requires day8/re-frame2-schemas on the classpath; add it to deps and require re-frame.schemas at app boot."})))))
