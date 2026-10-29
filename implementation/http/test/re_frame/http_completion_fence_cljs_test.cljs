(ns re-frame.http-completion-fence-cljs-test
  "The completion fence, CLJS half: the double-send. Unfenced, a throw above
  the reply tail rejects the completion promise, the Fetch `.catch`
  classifies it as `:rf.http/transport`, and `maybe-retry!` re-sends a request
  whose 2xx already landed. The retry mints a fresh handle, so the caller sees
  success and nothing reaches an error surface: the fetch COUNT is the
  load-bearing assertion. The JVM silent-hang half is
  `re-frame.http-completion-fence-test`.

  `encoding/valid-accept-return?`, the accept-shape check, is stubbed to
  throw, armed by the fetch stub so the request reaches the wire and succeeds
  before anything throws. A rejected fetch's failure tail is fenced the same
  way."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.http.encoding :as rf.http.encoding]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.transport-cljs :as rf.http.transport-cljs]
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
    ;; The node bundle shares one adapter seat across suites, and init!
    ;; refuses a different adapter, so cold-start it.
    (rf/destroy-adapter!)
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
          orig-va     rf.http.encoding/valid-accept-return?
          resp        (fake-200-json-response)
          restore     (fn []
                        (set! (.-fetch js/globalThis) orig-fetch)
                        (set! rf.http.encoding/valid-accept-return? orig-va)
                        (rf.trace.tooling/unregister-listener! cb-id)
                        (rf.http.managed/clear-all-http-interceptors!))]
      (rf.trace.tooling/register-listener! cb-id (fn [ev] (swap! traces conj ev)))
      (set! rf.http.encoding/valid-accept-return?
            (fn [& args]
              (if @armed?
                (throw (ex-info "completion cascade kaboom"
                                {:rf.error/id :rf.error/schemas-artefact-missing}))
                (apply orig-va args))))
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
          ;; A poll timeout fails as itself, then still reaches the fetch-count
          ;; assertion below.
          (.catch (fn [e] (is false (.-message e)) nil))
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

(deftest a-rejected-fetch-whose-failure-tail-throws-is-fenced
  (async done
    (rf/destroy-adapter!)
    (rf/init! rf.adapter.reagent/adapter)
    (rf.frame/ensure-default-frame!)
    (rf.http.managed/clear-all-in-flight!)
    (rf.http.managed/clear-all-http-interceptors!)
    (let [replies       (atom [])
          traces        (atom [])
          cb-id         (gensym "completion-fence-cljs-catch-")
          orig-fetch    (.-fetch js/globalThis)
          orig-classify rf.http.transport-cljs/classify-cljs-error
          restore       (fn []
                          (set! (.-fetch js/globalThis) orig-fetch)
                          (set! rf.http.transport-cljs/classify-cljs-error orig-classify)
                          (rf.trace.tooling/unregister-listener! cb-id))]
      (rf.trace.tooling/register-listener! cb-id (fn [ev] (swap! traces conj ev)))
      ;; The failure tail starts by classifying the rejection.
      (set! rf.http.transport-cljs/classify-cljs-error
            (fn [& _]
              (throw (ex-info "failure tail kaboom"
                              {:rf.error/id :rf.error/schemas-artefact-missing}))))
      (set! (.-fetch js/globalThis)
            (fn [_url _init] (js/Promise.reject (js/TypeError. "Failed to fetch"))))
      (rf/reg-event :reply/rejected-recorder
        (fn [_ [_ payload]] (swap! replies conj payload) {}))
      (rf/reg-event :issue-rejected
        (fn [_ _]
          {:fx [[:rf.http/managed
                 {:request    {:url "/x"}
                  :request-id :fence/rejected
                  :on-success [:reply/rejected-recorder]
                  :on-failure [:reply/rejected-recorder]}]]}))
      (rf/dispatch-sync [:issue-rejected] {:frame :rf/default})
      (-> (rf.test-support/poll-until
            #(seq (ops traces :rf.error/http-reply-tail-failed))
            {:timeout-ms 3000
             :label "cljs rejected-fetch :rf.error/http-reply-tail-failed surfaced"})
          (.catch (fn [e] (is false (.-message e)) nil))
          (.then (fn [_]
                   (is (= [:rf.error/schemas-artefact-missing]
                          (mapv (comp :reply-error-id :tags) (ops traces :rf.error/http-reply-tail-failed)))
                       "surfaced exactly once, carrying the caught throw's own id")
                   (is (empty? @replies))
                   (is (not (contains? (rf.http.managed/in-flight-snapshot) :fence/rejected))
                       "the fence retires the request")))
          (.catch (fn [e]
                    (is false (str "unexpected: " e))
                    nil))
          (.then (fn [_] (restore) (done)))))))
