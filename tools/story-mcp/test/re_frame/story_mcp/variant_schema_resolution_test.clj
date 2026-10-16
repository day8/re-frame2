(ns re-frame.story-mcp.variant-schema-resolution-test
  "A Story variant frame validates against the app-db schemas its images
  resolve (Spec 010 §Schemas resolve through the frame's images): the app's
  schema through the story's app image, with no registration of its own, and
  a narrower schema in place of it when the variant layers an image that
  selects one.

  It lives here rather than in Story's own suite because story-mcp's test
  classpath carries the schemas artefact."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as rf.schemas]
            [re-frame.story :as rf.story]
            [re-frame.story.frames :as rf.story.frames]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(def ^:private reset-runtime
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- reset-story [t]
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!)
  (try (t) (finally (rf.story/clear-all!))))

(use-fixtures :each reset-runtime reset-story)

(def ^:private this-ns "re-frame.story-mcp.variant-schema-resolution-test")

(defn- app-image
  "The app image: the registrations this namespace makes."
  []
  (rf/image {:id :test/app :select-ns {:include [this-ns]}}))

(defn- register-cart-app!
  "The app: a `[:cart]` schema and an event writing a candidate cart, and a
  story whose variants inherit the app image."
  []
  (rf/reg-app-schema [:cart] [:map [:items [:vector :keyword]]])
  (rf/reg-event :cart/set (fn [{:keys [db]} [_ cart]] {:db (assoc db :cart cart)}))
  (rf.story/reg-story :story.cart {:doc "A cart." :images [(app-image)]}))

(defn- allocate! [vid]
  (rf.story.frames/allocate! vid (rf.story/resolve-decorators vid)))

(defn- installs?
  "Dispatch `[:cart/set cart]` to variant frame `vid` and report whether the
  candidate installed."
  [vid cart]
  (rf/dispatch-sync [:cart/set cart] {:frame vid})
  (= cart (:cart (rf/app-db-value vid))))

(deftest a-variant-built-from-the-app-image-validates-against-the-apps-schema
  (register-cart-app!)
  (rf.story/reg-variant :story.cart/default {:doc "The default cart."})
  (allocate! :story.cart/default)
  (try
    (is (= #{[:cart]} (set (keys (rf.schemas/app-schemas {:frame :story.cart/default})))))
    (is (installs? :story.cart/default {:items [:apple]}))
    (is (not (installs? :story.cart/default {:items "not-a-vector"}))
        "the app's schema rejects the candidate in the variant frame")
    (finally
      (rf.story.frames/destroy! :story.cart/default))))

(deftest a-variant-image-layers-a-narrower-schema-for-that-variant-alone
  (register-cart-app!)
  (rf/reg-app-schema [:cart] {:ns 'probe.story.single} [:map [:items [:vector {:max 1} :keyword]]])
  (rf.story/reg-variant :story.cart/default {:doc "The default cart."})
  (rf.story/reg-variant :story.cart/single
    {:doc    "A cart holding at most one item."
     :images [(rf/image {:id :test/single :select-ns {:include ["probe.story.single"]}})]})
  (allocate! :story.cart/default)
  (allocate! :story.cart/single)
  (try
    (let [two {:items [:apple :pear]}]
      (is (installs? :story.cart/default two) "the default variant runs the app's schema")
      (is (not (installs? :story.cart/single two)) "the layered variant runs the narrower one")
      (is (installs? :story.cart/single {:items [:apple]})))
    (finally
      (rf.story.frames/destroy! :story.cart/default)
      (rf.story.frames/destroy! :story.cart/single))))
