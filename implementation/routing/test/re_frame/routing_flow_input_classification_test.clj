(ns re-frame.routing-flow-input-classification-test
  "A flow that reads a route param through a `[:rf.db/runtime …]` input traces
  it under the route's classification of the runtime-db the pass read. A
  navigation commits its route through the event's own `:rf.db/runtime`, so on
  the event that navigates onto a route the pending runtime-db already names
  it while the committed one does not, and the param must redact all the same
  (Spec 013 §Flow tracing, Spec 012 §Route data classification).

  `:rf.flow/computed` is a development trace, so under the production-gate
  lane (`-Dre-frame.debug=false`) the same navigations trace no flow input at
  all, while the flow still computes."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows]
            [re-frame.interop :as rf.interop]
            [re-frame.routing-test-support :as rf.routing-test-support]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(defn- computed-input-values
  "Run `thunk` and return the `:input-values` of every `:rf.flow/computed`
  trace it emits."
  [thunk]
  (let [seen (atom [])]
    (rf/register-listener! :trace ::computed
                           (fn [ev]
                             (when (= :rf.flow/computed (:operation ev))
                               (swap! seen conj (get-in ev [:tags :input-values])))))
    (try (thunk) @seen
         (finally (rf/unregister-listener! :trace ::computed)))))

(defn- visit!
  "Navigate `:rf/default` to `url` and return the flow inputs the event traced."
  [url]
  (computed-input-values
    #(rf/dispatch-sync [:rf.route/handle-url-change url {:rf.route/cause :link}]
                       {:frame :rf/default})))

(deftest a-route-param-read-by-a-flow-redacts-on-the-event-that-navigates-onto-the-route
  (rf/reg-route :route/home {} "/home")
  (rf/reg-route :route/vault {:sensitive [[:params :pin]]} "/vault/:pin")
  (rf/reg-flow :vault/pin-length
               {:inputs      [[:rf.db/runtime :rf.runtime/routing :current :params :pin]]
                :output-path [:pin-length]}
               count)
  (rf/make-frame {:id :rf/default :url-bound? true :flows [:vault/pin-length]})
  (let [[home onto again] (mapv visit! ["/home" "/vault/PIN-ONE" "/vault/PIN-TWO"])]
    (if rf.interop/debug-enabled?
      (do
        (is (= [[nil]] home))
        (is (= [[:rf/redacted]] onto)
            "the navigating event: only the pending runtime-db names the route")
        (is (= [[:rf/redacted]] again)
            "control: the route is already current, so the committed runtime-db names it too"))
      (is (= [[] [] []] [home onto again]) "a production build traces no flow input")))
  (is (= 7 (:pin-length (rf/app-db-value :rf/default)))
      "the flow itself reads the raw param"))
