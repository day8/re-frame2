(ns re-frame.resources-route-prefetch-cljs-test
  "The resources-side WARM-mode prefetch plan
  (`re-frame.resources.route/route-resource-warm-plan` + `on-route-prefetch-fx`,
  published as `:routing/on-route-prefetch`; Spec 016 §Route-plan prefetch —
  warm-mode), as a delta over an activation plan: every ensure is ownerless
  (GC-eligible), carries cause `[:route-prefetch route-id]`, and ignores
  `:blocking?`; a planning failure carries `:plan-cause :prefetch` and no
  `:nav-token`, and dispatches no partial ensures. The end-to-end behaviour is
  the `ep-0037-r3-prefetch-*` conformance fixtures'."
  (:require
   #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
   ;; load-bearing side-effecting requires: register the resources runtime + the
   ;; late-bound :routing/* integration hooks.
   [re-frame.core :as rf]
   [re-frame.resources :as rf.resources]
   [re-frame.resources.route :as rf.resources.route]))

(def ^:private res-id :prefetch/warm-res)

(defn- with-resource [f]
  (rf.resources/reg-resource
    res-id
    {:scope :rf.scope/global :params-schema [:map]}
    (fn [_params _ctx] {:request {:method :get :url "/api/warm"}}))
  (try (f)
       (finally (rf/clear :resource res-id))))

(use-fixtures :each with-resource)

(defn- prefetch-fx [route-id route-meta]
  (rf.resources.route/on-route-prefetch-fx
    {:route-id     route-id
     :params       {}
     :query        {}
     :fragment     nil
     :app-db       {}
     :branch       [{:route-id route-id :route-meta route-meta}]
     :branch-error nil}))

(deftest warm-plan-ensures-are-ownerless-cause-only-and-blocking-inert
  (let [{:keys [fx warmed plan-error]} (prefetch-fx :route/article {:resources [{:resource res-id :blocking? true}]})
        [[fx-id [ensure-id payload]]] fx]
    (is (= [nil 1 1 :dispatch :rf.resource/ensure]
           [plan-error warmed (count fx) fx-id ensure-id])
        "exactly one ensure is planned")
    (is (= [{:resource res-id :scope :rf.scope/global :params {} :cause [:route-prefetch :route/article]}
            [false false false]]
           [(select-keys payload [:resource :scope :params :cause])
            (mapv #(contains? payload %) [:owner :blocking? :keep-previous?])])
        "it targets the declared resource under the spec-policy scope with the warm cause, carrying no :owner (GC-eligible) and no blocking classification")))

(deftest warm-plan-branch-error-is-a-prefetch-planning-failure
  (let [{:keys [fx warmed plan-error]}
        (rf.resources.route/route-resource-warm-plan
          {:id :route/child}
          {:app-db       {}
           :branch       [{:route-id :route/child :route-meta nil}]
           :branch-error {:kind :unknown-parent :route-id* :route/ghost}})]
    (is (= [[] 0 :prefetch false :rf.error/resource-route-plan :route/child]
           [fx warmed (:plan-cause plan-error) (contains? plan-error :nav-token)
            (:rf.error/id plan-error) (:route-id plan-error)])
        "no partial ensures, and a prefetch planning failure owning no route state, so no nav-token slot")))

(deftest warm-plan-is-a-noop-when-no-branch-contributor-declares-resources
  (is (nil? (prefetch-fx :route/plain nil))
      "a resource-free branch warms nothing"))
