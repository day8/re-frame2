(ns re-frame.http-reply-tail-cljs-test
  "CLJS coverage for rf2-ln85eg — the RETRY-STORM half of the reply-tail leak.

  On CLJS the Fetch `.catch` is chained AFTER the `.then` that runs the
  response cascade. Pre-fix a throwing `:after` interceptor over a 2xx
  rejected the completion promise, and the `.catch` reclassified the throw
  via `classify-cljs-error` as `:rf.http/transport` → `maybe-retry!`
  RE-SENT the already-completed request (a retry-storm — each retry mints a
  fresh handle that bypasses the once-only reply guard). Post-fix the
  transport FENCES the reply tail (`dispatch-reply!` catches a
  post-transport-success throw), so the throw never reaches the `.catch`
  classifier: the request is fetched EXACTLY ONCE and the failure surfaces
  once as `:rf.error/http-reply-tail-failed`, without retry.

  The JVM silent-swallow half lives in `re-frame.http-reply-tail-test`.

  Async + inline runtime setup (this file drives the full `:rf.http/managed`
  Fetch pipeline), mirroring the `with-counting-500-fetch` idiom in
  `re-frame.http-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.http.managed :as http-managed]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]))

(defn- fake-200-json-response
  "A minimal Fetch `Response` stand-in that 200s a JSON body."
  []
  #js {:ok         true
       :status     200
       :statusText ""
       :headers    #js {:forEach (fn [cb] (cb "application/json" "content-type"))}
       :text       (fn [] (js/Promise.resolve "{\"ok\":true}"))})

(defn- reply-tail-failures [traces]
  (filter #(= :rf.error/http-reply-tail-failed (:operation %)) @traces))

(deftest ln85eg-cljs-after-throw-over-2xx-no-retry-storm
  (testing "rf2-ln85eg (CLJS) — a throwing :after interceptor over a 2xx is
            fetched EXACTLY ONCE (no retry-storm re-sending the completed
            request), surfaces once as :rf.error/http-reply-tail-failed
            (NOT reclassified as :rf.http/transport), and delivers NO reply —
            even under a :retry {:on #{:rf.http/transport}} policy that the
            pre-fix leak would have triggered."
    (async done
      (rf/init! reagent-adapter/adapter)
      (frame/ensure-default-frame!)
      (http-managed/clear-all-in-flight!)
      (http-managed/clear-all-http-interceptors!)
      (let [fetch-count (atom 0)
            replies     (atom [])
            traces      (atom [])
            cb-id       (gensym "ln85eg-cljs-")
            orig        (.-fetch js/globalThis)
            resp        (fake-200-json-response)
            restore     (fn []
                          (set! (.-fetch js/globalThis) orig)
                          (trace/unregister-listener! cb-id)
                          (http-managed/clear-all-http-interceptors!))]
        (trace/register-listener! cb-id (fn [ev] (swap! traces conj ev)))
        (set! (.-fetch js/globalThis)
              (fn [_url _init] (swap! fetch-count inc) (js/Promise.resolve resp)))
        ;; EP-0002: reg-http-interceptor is context-required frame-local.
        (rf/with-frame :rf/default
          (rf/reg-http-interceptor :boom-after
            {:after (fn [_ctx _resp]
                      (throw (ex-info "reply-tail kaboom" {:detail :synthetic})))}))
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url "/x"}
                    :decode     :json
                    ;; the pre-fix leak misclassified the reply-tail throw as
                    ;; :rf.http/transport and retried under exactly this policy.
                    :retry      {:on           #{:rf.http/transport}
                                 :max-attempts 5
                                 :backoff      {:base-ms 20 :factor 1 :max-ms 20}}
                    :on-success [:reply/recorder]
                    :on-failure [:reply/recorder]}]]}))
        (rf/dispatch-sync [:issue] {:frame :rf/default})
        (-> (test-support/poll-until
              #(seq (reply-tail-failures traces))
              {:timeout-ms 3000 :label "cljs :rf.error/http-reply-tail-failed surfaced"})
            (.then (fn [_]
                     ;; Wait past several backoff windows to prove the retry
                     ;; timer never fired (a regression would re-send here).
                     (js/Promise. (fn [resolve _] (js/setTimeout resolve 150)))))
            (.then (fn [_]
                     (is (= 1 @fetch-count)
                         "fetched exactly once — no retry-storm re-sending the completed 2xx")
                     (is (empty? @replies)
                         "no reply delivered — the reply tail threw before dispatch")
                     (let [rtf (reply-tail-failures traces)]
                       (is (= 1 (count rtf))
                           "exactly one :rf.error/http-reply-tail-failed (observed, once)")
                       (let [tags (:tags (first rtf))]
                         (is (= :success (:kind tags))
                             "the success reply branch's delivery is what threw")
                         (is (= :rf.error/http-interceptor-failed (:reply-error-id tags))
                             "the caught :after interceptor failure id rides the trace")))
                     ;; NOT reclassified as a transport failure anywhere.
                     (is (empty? (filter (fn [ev]
                                           (= :rf.http/transport
                                              (get-in ev [:tags :kind])))
                                         @traces))
                         "the reply-tail throw was NOT reclassified as :rf.http/transport")))
            (.catch (fn [e]
                      (is false (str "rf2-ln85eg — unexpected: " e))
                      nil))
            (.then (fn [_] (restore) (done))))))))
