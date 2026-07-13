(ns re-frame.ui.lease-descriptor
  "Internal descriptor grammar for compiled `ui/lease` sites.

  Validation is host-neutral and side-effect-free: the JVM emitter evaluates
  and validates declarations but never acquires a resource, while the CLJS
  capture path validates before it records a desired site. The namespace name
  deliberately differs from the public `re-frame.ui/lease` var so their CLJS
  names cannot occupy the same generated JavaScript property."
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
     "a lease descriptor must be nil or a closed map"
     {:recovery :fix-lease-descriptor
      :extra {:descriptor-summary (error/diag-value-summary descriptor)}})

    :else
    (let [unknown (seq (sort-by pr-str
                                (remove descriptor-keys (keys descriptor))))
          resource (:resource descriptor)]
      (cond
        unknown
        (error/throw-error!
         :rf.error/ui-tree-malformed 're-frame.ui/lease
         (str "a lease descriptor contains unknown key"
              (when (next unknown) "s") " " (pr-str (vec unknown))
              "; the v1 map is closed to :resource, :scope, and :params")
         {:recovery :fix-lease-descriptor
          :extra {:descriptor-keys (-> descriptor error/diag-value-summary :keys)
                  :unknown (vec unknown)}})

        (not (contains? descriptor :resource))
        (error/throw-error!
         :rf.error/ui-tree-malformed 're-frame.ui/lease
         "a lease descriptor requires :resource"
         {:recovery :fix-lease-descriptor
          :extra {:descriptor-keys (-> descriptor error/diag-value-summary :keys)}})

        (not (valid-resource-id? resource))
        (error/throw-error!
         :rf.error/ui-tree-malformed 're-frame.ui/lease
         "a lease descriptor's :resource must be a qualified keyword"
         {:recovery :fix-lease-descriptor
          :extra {:descriptor-keys (-> descriptor error/diag-value-summary :keys)
                  :resource-summary (error/diag-value-summary resource)}})

        :else descriptor))))
