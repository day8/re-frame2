(ns day8.re-frame2-xray.host-registrar-ambiguity-cljs-test
  "An id two namespaces register is an ambiguous registration: the store read
  `(rf/handler-meta {:kind k :id id})` throws for it. Every Xray panel that
  reads the host registrar through the source store renders such an id as it
  renders an unregistered one, instead of throwing while it renders."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.host-registrar :as host-registrar]
            [day8.re-frame2-xray.panels.epoch-panel :as epoch-panel]
            [day8.re-frame2-xray.panels.reactive-panel-view :as reactive-view]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.spine :as spine]
            [day8.re-frame2-xray.static.interceptors.panel :as interceptors-panel]
            [day8.re-frame2-xray.test-helpers.static-machines-tree :as machines-tree]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each (xray-test-support/make-xray-runtime-fixture {}))

(defn- register-twice!
  "Register `id` of `kind` from two namespaces."
  [kind id descriptor]
  (rf.registrar/register! kind id (assoc descriptor :rf.provenance/ns "host.a"))
  (rf.registrar/register! kind id (assoc descriptor :rf.provenance/ns "host.b")))

(defn- register-once! [kind id descriptor]
  (rf.registrar/register! kind id (assoc descriptor :rf.provenance/ns "host.a")))

(defn- store-read-error [kind id]
  (try (rf/handler-meta {:kind kind :id id})
       nil
       (catch :default e (:rf.error/id (ex-data e)))))

(deftest the-store-read-throws-for-an-ambiguous-id
  ;; the premise each panel row below rests on
  (register-twice! :view :host/Card {:handler-fn ::card})
  (is (= :rf.error/ambiguous-registration (store-read-error :view :host/Card))))

(deftest host-registrar-names-the-namespaces-and-reads-nothing
  (register-twice! :event :host/save {:handler-fn ::save})
  (register-once! :event :host/load {:handler-fn ::load})
  (is (= ["host.a" "host.b"] (host-registrar/ambiguous-namespaces :event :host/save)))
  (is (nil? (host-registrar/store-meta :event :host/save)))
  (is (nil? (host-registrar/ambiguous-namespaces :event :host/load)))
  (is (= ::load (:handler-fn (host-registrar/store-meta :event :host/load)))
      "a single registration still reads through"))

(deftest epoch-panel-resolvers-read-an-ambiguous-id-as-unregistered
  (register-twice! :event :host/save {:handler-fn ::save
                                      :interceptors [:host/audit]})
  (register-twice! :interceptor :host/audit
                   {:rf/interceptor-descriptor {:id :host/audit :before identity}})
  (register-twice! :cofx :host/now {:handler-fn identity :recordable? true})
  (register-once! :event :host/edit {:handler-fn       ::edit
                                     :interceptors     [:host/audit]
                                     :rf.cofx/requires [:host/now]})
  (testing "an ambiguous event has no authored chain and no recordables"
    (is (nil? (epoch-panel/resolve-event-interceptors :host/save)))
    (is (nil? (epoch-panel/resolve-event-recordables :host/save))))
  (testing "an event whose chain and requirements name ambiguous ids"
    (let [{:keys [entries resolve-meta-fn]} (epoch-panel/resolve-event-interceptors :host/edit)]
      (is (= [:host/audit] entries))
      (is (nil? (resolve-meta-fn :host/audit))))
    (is (= #{} (epoch-panel/resolve-event-recordables :host/edit))
        "an ambiguous cofx is not a declared recordable leaf")))

(deftest epoch-panel-recordables-of-a-registered-event-declaring-none
  (register-once! :event :host/plain {:handler-fn ::plain})
  (is (= #{} (epoch-panel/resolve-event-recordables :host/plain))
      "a registered event that declares no recordables filters every
       undeclared leaf, rather than reading as no resolver and showing all")
  (is (nil? (epoch-panel/resolve-event-recordables :host/unregistered))
      "control: an unregistered event leaves the projection's show-all
       fallback in place"))

(deftest static-interceptors-catalogue-lists-an-ambiguous-ref
  (register-twice! :interceptor :host/audit
                   {:rf/interceptor-descriptor {:id :host/audit :before identity}})
  (let [rows (interceptors-panel/collect-interceptors
               {:host/edit {:interceptors [:host/audit]}})]
    (is (= [:host/audit] (mapv :id rows)))
    (is (false? (:before? (first rows))) "no descriptor to read hooks from")))

(deftest reactive-panel-renders-an-ambiguous-view
  (register-twice! :view :host/Card {:handler-fn ::card})
  (let [tree (reactive-view/reactive-panel
               nil
               {:has-event-bundle? true :frame :rf/app
                :focus (spine/compose-focus {:mode :retro :dispatch-id 1} [])
                :counts {} :level-1-subs [] :level-2-subs []
                :view-rows [{:view-id :host/Card :action :rerender
                             :reason {:kind :structural} :elapsed-ms 1.0}]
                :unmounted-views [{:view-id :host/Card}]})]
    (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-reactive-view-node-_host_Card")))
    (is (some? (rf.test-helpers/find-by-testid tree "rf-xray-reactive-unmounted-row-_host_Card")))))

(deftest static-machines-panel-renders-an-ambiguous-machine
  (registry/register-xray-handlers!)
  (xray-test-support/install-test-overrides!)
  (rf/make-frame {:id :rf/xray})
  (register-twice! :event :host/door {:handler-fn  ::door
                                      :rf/machine? true
                                      :rf/machine  {:initial :closed
                                                    :states  {:closed {}}}})
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-registered-machines-override-for-test [:host/door]])
    (is (= {} @(rf/subscribe [:rf.xray/machine-definitions]))
        "an ambiguous machine has no definition to read")
    (is (some? (machines-tree/panel-tree)) "the sub-tab renders")))
