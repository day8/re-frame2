(ns re-frame.resources-http-completed-at-clock-cljs-test
  "The live managed-HTTP transport stamps `:completed-at` from the wall clock
  (`js/Date.now`), not the perf clock, so a just-loaded entry is not stale
  against the freshness readers' clock. Other suites script the reply time
  and the JVM cannot tell the two clocks apart, so this drives the real
  transport with only `js/fetch` stubbed."
  (:require
   [cljs.test :refer-macros [deftest is testing async]]
   [re-frame.adapter.reagent :as rf.adapter.reagent]
   [re-frame.core :as rf]
   [re-frame.frame :as rf.frame]
   [re-frame.http.managed]
   [re-frame.resources]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.schemas]
   [re-frame.test-support :as rf.test-support]))

(def ^:private stale-after-ms 60000)

(defn- json-200
  "A minimal Fetch `Response` stand-in resolving a 200 JSON body via `.text()`."
  [text-val]
  #js {:ok          true
       :status      200
       :statusText  ""
       :headers     #js {:forEach (fn [cb] (cb "application/json" "content-type"))}
       :text        (fn [] (js/Promise.resolve text-val))
       :blob        (fn [] (js/Promise.resolve nil))
       :arrayBuffer (fn [] (js/Promise.resolve nil))
       :formData    (fn [] (js/Promise.resolve nil))})

(defn- article-spec []
  {:scope          :rf.scope/global
   :params-schema  [:map [:slug :string]]
   :stale-after-ms stale-after-ms
   :tags           (fn [{:keys [slug]} _data] #{[:article slug]})})

(def ^:private article-spec-request
  (fn [{:keys [slug]} _ctx]
    {:request {:method :get :url (str "/api/articles/" slug)}}))

(deftest live-completed-at-is-wall-clock-not-immediately-stale
  (testing "a resource loaded through the live transport is not stale against js/Date.now"
    (async done
      ;; The node bundle shares the adapter slot with suites seating other
      ;; adapters, and init! refuses a different adapter, so cold-start it.
      (rf/destroy-adapter!)
      (rf/init! rf.adapter.reagent/adapter)
      (rf.frame/ensure-default-frame!)
      (let [scoped-key (rf.resources.state/scoped-resource-key
                         :rf.scope/global :clk/article {:slug "w"})
            orig       (.-fetch js/globalThis)
            entry      #(get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                (rf.resources.state/entry-path scoped-key))]
        (rf/reg-resource :clk/article (article-spec) article-spec-request)
        (set! (.-fetch js/globalThis)
              (fn [_url _init] (js/Promise.resolve (json-200 "{\"title\":\"Welcome\"}"))))
        (rf/dispatch-sync [:rf.resource/ensure
                           {:resource :clk/article :scope :rf.scope/global
                            :params {:slug "w"} :owner [:app :clk 1]}]
                          {:frame :rf/default})
        (is (= :loading (:status (entry))) "precondition: in flight on the real transport")
        (-> (rf.test-support/poll-until
              #(= :loaded (:status (entry)))
              {:timeout-ms 2000 :label "live load settles :loaded"})
            (.then
              (fn [_]
                (let [e         (entry)
                      now-epoch (js/Date.now)
                      loaded-at (:loaded-at e)
                      stale-at  (:stale-at e)]
                  (is (= {:title "Welcome"} (:data e)))
                  (is (false? (rf.resources.state/entry-stale? e now-epoch)))
                  (is (< (js/Math.abs (- now-epoch loaded-at)) 10000)
                      ":loaded-at is wall-clock epoch ms, not a perf-clock offset")
                  (is (= (+ loaded-at stale-after-ms) stale-at)))))
            ;; Reports and releases; the fetch restore rides the trailing step.
            (.catch
              (fn [err]
                (is false (str "unexpected: " err))
                nil))
            (.then
              (fn [_]
                (set! (.-fetch js/globalThis) orig)
                (done))))))))
