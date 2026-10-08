(ns re-frame.http-reply-tail-cljs-test
  "The CLJS retry-storm half of a reply-tail throw. The Fetch `.catch` is
  chained after the `.then` that runs the response cascade, so an unfenced
  throwing `:after` over a 2xx would be classified as `:rf.http/transport`
  and re-send the completed request. The transport fences the reply tail:
  one fetch, one `:rf.error/http-reply-tail-failed`, no reply. The JVM
  silent-swallow half is `re-frame.http-reply-tail-test`."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn- fake-200-json-response []
  #js {:ok         true
       :status     200
       :statusText ""
       :headers    #js {:forEach (fn [cb] (cb "application/json" "content-type"))}
       :text       (fn [] (js/Promise.resolve "{\"ok\":true}"))})

(defn- reply-tail-failures [traces]
  (filter #(= :rf.error/http-reply-tail-failed (:operation %)) @traces))

(deftest ln85eg-cljs-after-throw-over-2xx-no-retry-storm
  (async done
    (rf/init! rf.adapter.reagent/adapter)
    (rf.frame/ensure-default-frame!)
    (rf.http.managed/clear-all-in-flight!)
    (rf.http.managed/clear-all-http-interceptors!)
    (let [fetch-count (atom 0)
          replies     (atom [])
          traces      (atom [])
          cb-id       (gensym "ln85eg-cljs-")
          orig        (.-fetch js/globalThis)
          resp        (fake-200-json-response)
          restore     (fn []
                        (set! (.-fetch js/globalThis) orig)
                        (rf.trace.tooling/unregister-listener! cb-id)
                        (rf.http.managed/clear-all-http-interceptors!))]
      (rf.trace.tooling/register-listener! cb-id (fn [ev] (swap! traces conj ev)))
      (set! (.-fetch js/globalThis)
            (fn [_url _init] (swap! fetch-count inc) (js/Promise.resolve resp)))
      (rf/with-frame :rf/default
        (rf/reg-http-interceptor :boom-after
          {:after (fn [_ctx _resp] (throw (ex-info "reply-tail kaboom" {})))}))
      (rf/reg-event :reply/recorder
        (fn [_ [_ payload]] (swap! replies conj payload) {}))
      (rf/reg-event :issue
        (fn [_ _]
          {:fx [[:rf.http/managed
                 {:request    {:url "/x"}
                  :decode     :json
                  :retry      {:on           #{:rf.http/transport}
                               :max-attempts 5
                               :backoff      {:base-ms 20 :factor 1 :max-ms 20}}
                  :on-success [:reply/recorder]
                  :on-failure [:reply/recorder]}]]}))
      (rf/dispatch-sync [:issue] {:frame :rf/default})
      (-> (rf.test-support/poll-until
            #(seq (reply-tail-failures traces))
            {:timeout-ms 3000 :label "cljs :rf.error/http-reply-tail-failed surfaced"})
          ;; Wait past several backoff windows: a regression re-sends here.
          (.then (fn [_] (js/Promise. (fn [resolve _] (js/setTimeout resolve 150)))))
          (.then (fn [_]
                   (is (= 1 @fetch-count) "no re-send of the completed 2xx")
                   (is (empty? @replies) "delivery is what threw")
                   (is (= [{:kind :success :reply-error-id :rf.error/http-interceptor-failed}]
                          (mapv #(select-keys (:tags %) [:kind :reply-error-id])
                                (reply-tail-failures traces))))))
          (.catch (fn [e]
                    (is false (str "unexpected: " e))
                    nil))
          (.then (fn [_] (restore) (done)))))))
