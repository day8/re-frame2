(ns re-frame.ui.lease
  "Internal descriptor grammar for compiled `ui/lease` sites.

  Validation is host-neutral and side-effect-free: the JVM emitter evaluates
  and validates declarations but never acquires a resource, while the CLJS
  capture path validates before it records a desired site."
  (:require [re-frame.error :as error]))

(def descriptor-keys
  "The closed v1 descriptor vocabulary. Internal compiler/test seam."
  #{:resource :scope :params})

(defn valid-resource-id?
  [x]
  (and (keyword? x) (some? (namespace x))))

(defn validate-descriptor!
  "Return `descriptor` unchanged when it is nil (inactive) or a valid closed
  resource descriptor. Fail loudly before capture/dispatch otherwise.

  Optional key presence is deliberately preserved: omitted `:params` and
  explicit `:params nil` are distinct payloads."
  [descriptor]
  (cond
    (nil? descriptor) nil

    (not (map? descriptor))
    (error/throw-error!
     :rf.error/ui-tree-malformed 're-frame.ui/lease
     (str "a lease descriptor must be nil or a map; got "
          (pr-str descriptor))
     {:extra {:descriptor descriptor :recovery :fix-lease-descriptor}})

    :else
    (let [unknown (seq (remove descriptor-keys (keys descriptor)))
          resource (:resource descriptor)]
      (cond
        unknown
        (error/throw-error!
         :rf.error/ui-tree-malformed 're-frame.ui/lease
         (str "a lease descriptor contains unknown key"
              (when (next unknown) "s") " " (pr-str (vec unknown))
              "; the v1 map is closed to :resource, :scope, and :params")
         {:extra {:descriptor descriptor
                  :unknown (vec unknown)
                  :recovery :fix-lease-descriptor}})

        (not (contains? descriptor :resource))
        (error/throw-error!
         :rf.error/ui-tree-malformed 're-frame.ui/lease
         "a lease descriptor requires :resource"
         {:extra {:descriptor descriptor :recovery :fix-lease-descriptor}})

        (not (valid-resource-id? resource))
        (error/throw-error!
         :rf.error/ui-tree-malformed 're-frame.ui/lease
         (str "a lease descriptor's :resource must be a qualified keyword; got "
              (pr-str resource))
         {:extra {:descriptor descriptor :recovery :fix-lease-descriptor}})

        :else descriptor))))
