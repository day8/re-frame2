(ns re-frame.image-assembly-default-cljs-test
  "The default image (EP-0023 §Default Image Semantics): the implicit selector
  over the whole source store plus the framework standards, run through the same
  assembly pipeline and cache as an explicit image. Two namespaces registering
  one `(kind, id)` fail loud (`:rf.error/image-duplicate-id`) instead of letting
  load order win. A framework replaceable default (`:rf/framework-default?`, nil
  provenance) yields to one application registration, and its `:sensitive`
  carriers ride across that override.

  The fixture snapshots and restores the live source store rather than clearing
  it, so authored registrations survive."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.image          :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.source-store   :as rf.source-store]))

(use-fixtures :each
  (fn [t]
    (let [store-before @rf.source-store/kind->id->ns->descriptor]
      (rf.image-assembly/clear-standards!)
      (rf.image-assembly/clear-generation-cache!)
      (try
        (t)
        (finally
          ;; Restore the source store to exactly its pre-case contents.
          (reset! rf.source-store/kind->id->ns->descriptor store-before)
          (rf.image-assembly/clear-standards!)
          (rf.image-assembly/clear-generation-cache!))))))

(defn- reg-desc
  "A synthetic registered descriptor authored in `provenance-ns`."
  [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

(defn- assembly-error-id
  "The `:rf.error/id` `thunk` throws, or nil."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(deftest empty-store-default-projects-an-empty-generation
  ;; a valid empty projection, with no zero-match failure
  (is (= {} (:rf.gen/resolver (rf.image-assembly/assemble-default []))))
  (rf.image-assembly/register-standard! :fx :rf.nav/push-url {:handler-fn ::std})
  (rf.image-assembly/clear-generation-cache!)
  (is (= #{[:fx :rf.nav/push-url]}
         (set (keys (:rf.gen/resolver (rf.image-assembly/assemble-default [])))))
      "a framework standard alone is projected"))

(deftest default-projection-collision-order-independent
  (let [a (reg-desc "examples.todo.boot"    :event :boot/init ::a)
        b (reg-desc "examples.counter.boot" :event :boot/init ::b)]
    (is (= [:rf.error/image-duplicate-id :rf.error/image-duplicate-id]
           [(assembly-error-id #(rf.image-assembly/assemble-default [a b]))
            (assembly-error-id #(rf.image-assembly/assemble-default [b a]))]))))

(deftest assemble-empty-images-is-the-default-projection
  (let [pool        [(reg-desc "app.core" :event :counter/inc ::inc)]
        via-empty   (rf.image-assembly/assemble [] pool)
        via-default (rf.image-assembly/assemble-default pool)]
    (is (contains? (:rf.gen/resolver via-empty) [:event :counter/inc]))
    (is (identical? via-default via-empty)
        "the empty-:images path shares the one cached default generation")))

(deftest default-path-fails-loud-on-standard-collision
  ;; as the explicit path does: the default path must not silently drop a
  ;; provenanced app descriptor and resolve to the standard
  (rf.image-assembly/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
  (is (= :rf.error/image-standard-replacement-forbidden
         (assembly-error-id #(rf.image-assembly/assemble-default
                               [(reg-desc "shop.nav" :fx :rf.nav/push-url ::app-nav)])))))

(deftest default-projection-drops-framework-own-no-provenance-standard-shadow
  ;; the standard's own nil-provenance registrar copy is filtered, and nothing else
  (rf.image-assembly/register-standard! :fx :rf.nav/push-url {:handler-fn ::std-nav})
  (let [gen (rf.image-assembly/assemble-default
              [{:rf.provenance/ns nil :kind :fx :id :rf.nav/push-url :handler-fn ::std-nav}
               (reg-desc "shop.cart" :event :cart/add ::cart-add)])]
    (is (= [::std-nav ::cart-add]
           [(:handler-fn (rf.image-assembly/resolve-descriptor gen :fx :rf.nav/push-url))
            (:handler-fn (rf.image-assembly/resolve-descriptor gen :event :cart/add))]))))

;; A framework default (`:rf.route/entry-denied`, Spec 012 §Entry is terminal)
;; stands in for a decision the app is invited to make, so one app registration
;; of the same id is the documented override rather than a collision.
(defn- fw-default-desc
  "The framework's own copy of a replaceable default: the reserved
  `:rf/framework-default?` marker and no `:rf.provenance/ns`."
  [kind id impl]
  {:rf.provenance/ns      nil
   :kind                  kind
   :id                    id
   :handler-fn            impl
   :rf/framework-default? true})

(deftest framework-default-alone-resolves-normally
  ;; denial stays safe for an app that registers nothing
  (is (= ::fw-noop
         (:handler-fn (rf.image-assembly/resolve-descriptor
                        (rf.image-assembly/assemble-default
                          [(fw-default-desc :event :rf.route/entry-denied ::fw-noop)])
                        :event :rf.route/entry-denied)))))

(deftest application-registration-supersedes-the-framework-default
  (is (= ::app-denial
         (:handler-fn (rf.image-assembly/resolve-descriptor
                        (rf.image-assembly/assemble-default
                          [(fw-default-desc :event :rf.route/entry-denied ::fw-noop)
                           (reg-desc "shop.auth" :event :rf.route/entry-denied ::app-denial)])
                        :event :rf.route/entry-denied)))))

(deftest two-application-registrations-of-a-default-id-still-collide
  ;; the supersession seam is not a winner rule
  (is (= :rf.error/image-duplicate-id
         (assembly-error-id #(rf.image-assembly/assemble-default
                               [(fw-default-desc :event :rf.route/entry-denied ::fw-noop)
                                (reg-desc "shop.auth"  :event :rf.route/entry-denied ::a)
                                (reg-desc "shop.admin" :event :rf.route/entry-denied ::b)])))))

(deftest the-framework-default-marker-is-not-forgeable-from-app-code
  ;; the marker counts only with nil provenance, so a provenanced app descriptor
  ;; stamping it is an ordinary app registration and collides
  (is (= :rf.error/image-duplicate-id
         (assembly-error-id #(rf.image-assembly/assemble-default
                               [(assoc (reg-desc "shop.auth" :event :cart/add ::a)
                                       :rf/framework-default? true)
                                (reg-desc "shop.admin" :event :cart/add ::b)])))))

;; Replacing a framework default replaces behaviour. The payload the framework
;; builds (the entry-denied map embeds query values and path params) is not the
;; app's to re-describe, so the framework's own :sensitive carriers ride across
;; the override. Nothing else carries.

(deftest framework-default-carrier-classification-rides-an-override
  (rf.source-store/record-descriptor! :event ::denied
                                      {:rf/framework-default? true
                                       :sensitive             [[:requested-url] [:destination]]
                                       :handler-fn            ::fw-noop})
  (is (= {:handler-fn ::app :sensitive [[:requested-url] [:destination]]}
         (rf.image-assembly/retain-framework-default-classification
           :event ::denied {:handler-fn ::app}))
      "an override declaring nothing still classifies the framework's carriers"))

(deftest an-app-declaration-is-additive-over-the-retained-carriers
  ;; a union: the framework's paths first, then the override's own, de-duplicated
  (rf.source-store/record-descriptor! :event ::denied
                                      {:rf/framework-default? true
                                       :sensitive             [[:requested-url] [:target]]
                                       :handler-fn            ::fw-noop})
  (is (= [[[:requested-url] [:target] [:guard]]
          [[:requested-url] [:target]]]
         (mapv #(:sensitive (rf.image-assembly/retain-framework-default-classification
                              :event ::denied {:sensitive %}))
               [[[:guard]] [[:target]]]))))

(deftest retention-is-identity-for-everything-that-is-not-a-framework-default
  ;; an ordinary id, a provenanced descriptor forging the marker, a framework
  ;; default with no carriers, and an unregistered id all return the same value
  (rf.source-store/record-descriptor! :event ::ordinary {:handler-fn ::app
                                                         :sensitive  [[:token]]})
  (rf.source-store/record-descriptor! :event ::forged {:rf.provenance/ns      "shop.auth"
                                                       :rf/framework-default? true
                                                       :sensitive             [[:secret]]
                                                       :handler-fn            ::app})
  (rf.source-store/record-descriptor! :event ::bare-default {:rf/framework-default? true
                                                             :handler-fn            ::fw-noop})
  (let [m {:handler-fn ::app}]
    (is (= [] (remove #(identical? m (rf.image-assembly/retain-framework-default-classification
                                       :event % m))
                      [::ordinary ::forged ::bare-default ::never-registered])))))

(deftest the-reconcile-half-covers-the-inverse-registration-order
  ;; app first, framework default second: the reconcile brings the stored app
  ;; descriptor to the union the default-first order produces
  (let [app-slot #(rf.source-store/descriptor-for :event ::denied "shop.auth")]
    (rf.source-store/record-descriptor! :event ::denied {:ns         "shop.auth"
                                                         :sensitive  [[:guard]]
                                                         :handler-fn ::app})
    (rf.image-assembly/reconcile-framework-default-classification! :event ::denied)
    (is (= [[:guard]] (:sensitive (app-slot)))
        "a no-op while there is no framework default")
    (rf.source-store/record-descriptor! :event ::denied
                                        {:rf/framework-default? true
                                         :sensitive             [[:requested-url] [:target]]
                                         :handler-fn            ::fw-noop})
    (rf.image-assembly/reconcile-framework-default-classification! :event ::denied)
    (is (= {:sensitive [[:requested-url] [:target] [:guard]] :handler-fn ::app}
           (select-keys (app-slot) [:sensitive :handler-fn :rf/framework-default?]))
        "only the carriers moved: the handler stays and the marker does not ride across")
    (is (= [[:requested-url] [:target]]
           (:sensitive (rf.source-store/descriptor-for :event ::denied nil)))
        "the framework's own copy is left alone")
    (let [before (app-slot)]
      (rf.image-assembly/reconcile-framework-default-classification! :event ::denied)
      (is (identical? before (app-slot))
          "idempotent: a second pass writes nothing, so no generation bump"))))
