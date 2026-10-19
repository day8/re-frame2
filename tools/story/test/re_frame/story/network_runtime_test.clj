(ns re-frame.story.network-runtime-test
  "A variant's authored `:network` fixture is realized on the live run paths
  (spec/017 §The network surface): the runner installs the route map on the
  variant frame as a stub transport, beneath the real `:rf.http/managed`.

  JVM-only: `rf.story/run` resolves synchronously and a route answers inline,
  inside the fx walk, so the round trip settles before `.get` returns."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core      :as rf]
            [re-frame.frame     :as rf.frame]
            [re-frame.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story         :as rf.story]
            [re-frame.story.frames  :as rf.story.frames]))

(defn- installed-transports
  "Every frame id holding an installed stub transport."
  []
  (set (keys @@#'rf.http.registry/frame-transports)))

(defn- reset-rf! [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.http.registry/clear-all-in-flight!)
  ;; `clear-all!` also drops the HTTP artefact's load-time registrations, so
  ;; reload it: the variants' requests go through the real `:rf.http/managed`.
  (require 're-frame.http.managed :reload)
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf/reg-event :net/load
                (fn [_ _]
                  {:fx [[:rf.http/managed
                         {:request    {:method :get :url "/api/cart"}
                          :decode     :json
                          :on-success [:net/loaded]
                          :on-failure [:net/failed]}]]}))
  (rf/reg-event :net/loaded (fn [{:keys [db]} [_ reply]] {:db (assoc db :cart (:value reply))}))
  (rf/reg-event :net/failed (fn [{:keys [db]} [_ reply]] {:db (assoc db :error reply)}))
  (test-fn))

(use-fixtures :each reset-rf!)

(defn- run-target [target]
  (.get ^java.util.concurrent.CompletableFuture (rf.story/run target)))

(def ^:private cart-fixture
  {[:get "/api/cart"] {:body {:items [1]}}})

(deftest extends-child-flipping-a-route-to-failure-fires-the-failure
  ;; Were the route deep-merged, the child's 409 would ride beside the
  ;; parent's body instead of replacing the response.
  (rf.story/reg-variant :story.netext/ok
                        {:network cart-fixture
                         :setup   [[:dispatch [:net/load]]]})
  (rf.story/reg-variant :story.netext/fails
                        {:extends :story.netext/ok
                         :network {[:get "/api/cart"] {:status 409 :body {:error "sold out"}}}})
  (let [db (:app-db (run-target :story.netext/fails))]
    (is (= {:kind :rf.http/http-4xx :http-status 409}
           (select-keys (get-in db [:error :error]) [:kind :http-status])))))

(deftest inline-plan-realizes-and-releases-its-network-fixture
  (let [result (run-target {:network cart-fixture
                            :setup   [[:dispatch [:net/load]]]})]
    (is (= {:items [1]} (:cart (:app-db result))))
    (is (= #{} (installed-transports))
        "the inline run's teardown dropped the anonymous frame's transport")))

(deftest a-variant-without-network-stays-off-the-network
  (testing "with no `:network`, the `:story` preset fails each managed request
            as :rf.http/transport instead of sending it"
    (rf.story/reg-variant :story.netnone/load {:setup [[:dispatch [:net/load]]]})
    (let [db (:app-db (run-target :story.netnone/load))]
      (is (= :rf.http/transport (get-in db [:error :error :kind])))
      (is (nil? (:cart db))))))

(deftest app-image-frame-keeps-application-isolation
  (testing "a variant on an explicit app image still reaches its fixture
            through the real :rf.http/managed, and a registration outside the
            app image's namespace stays invisible"
    (require 'story.test-helpers.image-behaviour-v1 :reload)
    (rf.story/reg-variant :story.netimg3/cart
                          {:images  [(rf/image {:id        :net/app-image
                                                :select-ns {:include ["re-frame.story.network-runtime-test"]}})]
                           :network cart-fixture
                           :setup   [[:dispatch [:net/load]]]})
    (let [result   (run-target :story.netimg3/cart)
          resolver (:rf.gen/resolver (rf/frame-generation :story.netimg3/cart))]
      (is (= {:items [1]} (:cart (:app-db result))))
      (is (contains? resolver [:fx :rf.http/managed]))
      (is (not (contains? resolver [:event :img.counter/step]))))))

(deftest concurrently-mounted-variants-keep-their-own-replies
  (testing "two mounted variants stubbing the same url each answer their own
            response, and destroying one drops only its transport"
    (rf.story/reg-variant :story.net/a
                          {:network {[:get "/api/cart"] {:body {:items ["a"]}}}
                           :setup   [[:dispatch [:net/load]]]})
    (rf.story/reg-variant :story.net/b
                          {:network {[:get "/api/cart"] {:body {:items ["b"]}}}
                           :setup   [[:dispatch [:net/load]]]})
    (let [cart #(:cart (:app-db (run-target %)))]
      (is (= [{:items ["a"]} {:items ["b"]} {:items ["a"]}]
             [(cart :story.net/a) (cart :story.net/b) (cart :story.net/a)])
          "A still answers its own fixture with B mounted alongside")
      (rf.story.frames/destroy! :story.net/a)
      (is (= #{:story.net/b} (installed-transports)))
      (is (= {:items ["b"]} (cart :story.net/b))
          "B still answers after the sibling teardown")
      (rf.story.frames/destroy! :story.net/b)
      (is (= #{} (installed-transports))
          "the last teardown empties the registry"))))
