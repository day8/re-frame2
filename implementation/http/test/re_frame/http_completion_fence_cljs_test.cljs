(ns re-frame.http-completion-fence-cljs-test
  "The completion fence, CLJS half: the double-send. Unfenced, a throw above
  the reply tail rejects the completion promise, the Fetch `.catch`
  classifies it as `:rf.http/transport`, and `maybe-retry!` re-sends a request
  whose 2xx already landed. The retry mints a fresh handle, so the caller sees
  success and nothing reaches an error surface: the fetch COUNT is the
  load-bearing assertion. The JVM silent-hang half is
  `re-frame.http-completion-fence-test`.

  `classify-decoded` is stubbed to throw, armed by the fetch stub so the
  request reaches the wire and succeeds before anything throws."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.privacy-body :as rf.http.privacy-body]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn- fake-200-json-response []
  #js {:ok         true
       :status     200
       :statusText ""
       :headers    #js {:forEach (fn [cb] (cb "application/json" "content-type"))}
       :text       (fn [] (js/Promise.resolve "{\"ok\":true}"))})

(defn- ops [traces op]
  (filter #(= op (:operation %)) @traces))

(deftest completion-throw-does-not-re-send-the-completed-request
  (async done
    (rf/init! rf.adapter.reagent/adapter)
    (rf.frame/ensure-default-frame!)
    (rf.http.managed/clear-all-in-flight!)
    (rf.http.managed/clear-all-http-interceptors!)
    (let [fetch-count (atom 0)
          armed?      (atom false)
          replies     (atom [])
          traces      (atom [])
          cb-id       (gensym "completion-fence-cljs-")
          orig-fetch  (.-fetch js/globalThis)
          orig-cd     rf.http.privacy-body/classify-decoded
          resp        (fake-200-json-response)
          restore     (fn []
                        (set! (.-fetch js/globalThis) orig-fetch)
                        (set! rf.http.privacy-body/classify-decoded orig-cd)
                        (rf.trace.tooling/unregister-listener! cb-id)
                        (rf.http.managed/clear-all-http-interceptors!))]
      (rf.trace.tooling/register-listener! cb-id (fn [ev] (swap! traces conj ev)))
      (set! rf.http.privacy-body/classify-decoded
            (fn [& args]
              (if @armed?
                (throw (ex-info "completion cascade kaboom"
                                {:rf.error/id :rf.error/schemas-artefact-missing}))
                (apply orig-cd args))))
      (set! (.-fetch js/globalThis)
            (fn [_url _init]
              (swap! fetch-count inc)
              (reset! armed? true)
              (js/Promise.resolve resp)))
      (rf/reg-event :reply/recorder
        (fn [_ [_ payload]] (swap! replies conj payload) {}))
      (rf/reg-event :issue
        (fn [_ _]
          {:fx [[:rf.http/managed
                 {:request    {:url "/x"}
                  :decode     :json
                  ;; Short backoff, so a re-send lands inside the settle window.
                  :retry      {:on           #{:rf.http/transport}
                               :max-attempts 5
                               :backoff      {:base-ms 20 :factor 1 :max-ms 20}}
                  :request-id :fence/req
                  :on-success [:reply/recorder]
                  :on-failure [:reply/recorder]}]]}))
      (rf/dispatch-sync [:issue] {:frame :rf/default})
      (-> (rf.test-support/poll-until
            #(seq (ops traces :rf.error/http-reply-tail-failed))
            {:timeout-ms 3000
             :label "cljs completion-fence :rf.error/http-reply-tail-failed surfaced"})
          ;; A poll timeout must still reach the fetch-count assertion below.
          (.catch (fn [_] nil))
          (.then (fn [_] (js/Promise. (fn [resolve _] (js/setTimeout resolve 200)))))
          (.then (fn [_]
                   (is (empty? (ops traces :rf.http/replied))
                       "PRECONDITION: the throw landed above the reply tail")
                   (is (= 1 @fetch-count) "fetched exactly once: no double-send")
                   (is (empty? @replies))
                   (is (= [:rf.error/schemas-artefact-missing]
                          (mapv (comp :reply-error-id :tags) (ops traces :rf.error/http-reply-tail-failed)))
                       "surfaced exactly once, carrying the caught throw's own id")))
          (.catch (fn [e]
                    (is false (str "unexpected: " e))
                    nil))
          (.then (fn [_] (restore) (done)))))))
