(ns re-frame.story.network-runtime-test
  "A variant's authored `:network` fixture is realized on the live run paths
  (spec/017 §The network surface): the runner installs the route map and
  the frame takes the plan's `:fx-overrides` redirect to the stub fx.

  Every test registers `:rf.http/managed` as a capture-only sentinel that
  records its payload and issues nothing, so an empty `live-requests` proves
  the redirect fired and a non-empty one is the failure guarded against.

  JVM-only: `rf.story/run` resolves synchronously and the canned reply is
  dispatched inside the fx walk, so the round trip settles before `.get`
  returns."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core      :as rf]
            [re-frame.frame     :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story         :as rf.story]
            [re-frame.story.frames  :as rf.story.frames]
            [re-frame.story.network :as rf.story.network]))

(def ^:private live-requests (atom []))

(defn- reset-rf! [test-fn]
  ;; Release first, so a previous test's undestroyed frame unwinds the shared
  ;; install/uninstall pair before `clear-all!` drops the registrar.
  (doseq [id (keys @rf.story.network/frame-routes)]
    (rf.story.network/release-frame! id))
  (reset! live-requests [])
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf/reg-fx :rf.http/managed
             (fn [_ctx payload] (swap! live-requests conj payload) nil))
  (rf/reg-event :net/load
                (fn [_ _]
                  {:fx [[:rf.http/managed
                         {:request    {:method :get :url "/api/cart"}
                          :on-success [:net/loaded]
                          :on-failure [:net/failed]}]]}))
  (rf/reg-event :net/loaded (fn [{:keys [db]} [_ reply]] {:db (assoc db :cart (:value reply))}))
  (rf/reg-event :net/failed (fn [{:keys [db]} [_ reply]] {:db (assoc db :error reply)}))
  (test-fn))

(use-fixtures :each reset-rf!)

(defn- run-target [target]
  (.get ^java.util.concurrent.CompletableFuture (rf.story/run target)))

(def ^:private cart-fixture
  {[:get "/api/cart"] {:reply {:ok {:items [1]}}}})

(deftest extends-child-flipping-a-route-to-failure-fires-the-failure
  ;; Were the route deep-merged to {:reply {:ok .. :failure ..}}, the stub
  ;; would test :ok first and the child would answer the parent's :ok.
  (rf.story/reg-variant :story.netext/ok
                        {:network cart-fixture
                         :setup   [[:dispatch [:net/load]]]})
  (rf.story/reg-variant :story.netext/fails
                        {:extends :story.netext/ok
                         :network {[:get "/api/cart"]
                                   {:reply {:failure {:kind :rf.http/http-4xx :status 409}}}}})
  (let [db (:app-db (run-target :story.netext/fails))]
    (is (= {:kind :rf.http/http-4xx :status 409}
           (select-keys (get-in db [:error :error]) [:kind :status])))
    (is (= [] @live-requests))))

(deftest inline-plan-realizes-and-releases-its-network-fixture
  (let [result (run-target {:network cart-fixture
                            :setup   [[:dispatch [:net/load]]]})]
    (is (= {:items [1]} (:cart (:app-db result))))
    (is (= {} @rf.story.network/frame-routes)
        "the inline run's teardown released the anonymous frame's routes")
    (is (= [] @live-requests))))

(deftest app-image-frame-keeps-application-isolation
  (testing "a variant on an explicit app image still reaches its fixture, and
            the fixture adds only the one stub fx the redirect names: a
            registration outside the app image's namespace stays invisible"
    (require 'story.test-helpers.image-behaviour-v1 :reload)
    (rf.story/reg-variant :story.netimg3/cart
                          {:images  [(rf/image {:id        :net/app-image
                                                :select-ns {:include ["re-frame.story.network-runtime-test"]}})]
                           :network cart-fixture
                           :setup   [[:dispatch [:net/load]]]})
    (let [result   (run-target :story.netimg3/cart)
          resolver (:rf.gen/resolver (rf/frame-generation :story.netimg3/cart))]
      (is (= {:items [1]} (:cart (:app-db result))))
      (is (contains? resolver [:fx :rf.http/managed-test-stub]))
      (is (not (contains? resolver [:event :img.counter/step])))
      (is (= [] @live-requests)))))

(deftest concurrently-mounted-variants-keep-their-own-replies
  (testing "two mounted variants stubbing the same url each answer their own
            reply, and destroying one releases only its fixture"
    (rf.story/reg-variant :story.net/a
                          {:network {[:get "/api/cart"] {:reply {:ok {:items [:a]}}}}
                           :setup   [[:dispatch [:net/load]]]})
    (rf.story/reg-variant :story.net/b
                          {:network {[:get "/api/cart"] {:reply {:ok {:items [:b]}}}}
                           :setup   [[:dispatch [:net/load]]]})
    (let [cart #(:cart (:app-db (run-target %)))]
      (is (= [{:items [:a]} {:items [:b]} {:items [:a]}]
             [(cart :story.net/a) (cart :story.net/b) (cart :story.net/a)])
          "A still answers its own fixture with B mounted alongside")
      (rf.story.frames/destroy! :story.net/a)
      (is (nil? (rf.story.network/routes-for :story.net/a)))
      (is (= {:items [:b]} (cart :story.net/b))
          "B still answers after the sibling teardown")
      (rf.story.frames/destroy! :story.net/b)
      (is (= {} @rf.story.network/frame-routes)
          "the last release empties the registry")
      (is (= [] @live-requests)))))
