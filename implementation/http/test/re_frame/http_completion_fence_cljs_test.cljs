(ns re-frame.http-completion-fence-cljs-test
  "rf2-1eng8 seam 2, CLJS half — the DOUBLE-SEND.

  On CLJS the Fetch `.catch` is chained after the `.then` that runs the whole
  response cascade. `dispatch-reply!`'s reply-tail fence (rf2-ln85eg) covers
  the BOTTOM of that cascade — the `:after` chain and the reply dispatch — and
  `re-frame.http-reply-tail-cljs-test` pins it. Everything ABOVE it was
  unfenced: status classification, response-body schema classification, the
  retry decision, the finalise and teardown. A throw there rejected the
  completion promise, the `.catch` fed `classify-cljs-error` →
  `:rf.http/transport` → `maybe-retry!`, and the request was RE-SENT although
  its 2xx had already landed.

  **This is the dangerous one of the three seams, and the reason it is worth a
  dedicated namespace: a double-send presents to the caller as SUCCESS.** The
  retry mints a fresh handle, so it bypasses the once-only reply guard; the app
  gets a reply, nothing appears on any error surface, and the only trace of the
  fault is that a non-idempotent endpoint ran twice. So the load-bearing
  assertion here is the FETCH COUNT, not the error trace.

  The JVM half — where the same throw hangs the caller silently and leaves the
  registry populated — lives in `re-frame.http-completion-fence-test`.

  ## Planting the throw

  Nothing user-facing throws above the reply tail: a `:decode` throw is fenced
  into `:rf.http/decode-failure` and an `:accept` throw into
  `:rf.http/accept-failure`, both by design. That is precisely why this seam is
  subtle, and it means the plant has to be an internal one. `classify-decoded`
  is stubbed to throw, which lands the throw at exactly the site the item's own
  reachable case used to reach — `handle-response!`'s 2xx accept phase, above
  `finalise-success!` and so above both the registry clear and the reply tail.

  The stub is ARMED BY THE FETCH STUB rather than at test start, so the
  dispatch-time half of the same change (the `:decode` marks check now forced
  in `handlers/normalise-args`) is not what fires: the request must reach the
  wire and succeed before anything throws. That ordering is asserted, not
  assumed.

  Async + inline runtime setup, mirroring `re-frame.http-reply-tail-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.privacy-body :as rf.http.privacy-body]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn- fake-200-json-response
  "A minimal Fetch `Response` stand-in that 200s a JSON body."
  []
  #js {:ok         true
       :status     200
       :statusText ""
       :headers    #js {:forEach (fn [cb] (cb "application/json" "content-type"))}
       :text       (fn [] (js/Promise.resolve "{\"ok\":true}"))})

(defn- ops [traces op]
  (filter #(= op (:operation %)) @traces))

(deftest completion-throw-does-not-re-send-the-completed-request
  (testing "rf2-1eng8 (CLJS) — a throw in the completion cascade ABOVE the reply
            tail is fenced at the completion boundary, so the already-successful
            request is fetched EXACTLY ONCE. Pre-fix the throw rejected the
            completion promise, the Fetch .catch reclassified it as
            :rf.http/transport, and maybe-retry! re-sent a request whose 2xx had
            already landed — a double-send that presents to the caller as
            success."
    (async done
      (rf/init! rf.adapter.reagent/adapter)
      (rf.frame/ensure-default-frame!)
      (rf.http.managed/clear-all-in-flight!)
      (rf.http.managed/clear-all-http-interceptors!)
      (let [fetch-count (atom 0)
            throw-count (atom 0)
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
        ;; The plant. Inert until the fetch stub arms it, so the request is
        ;; issued and SUCCEEDS on the wire before anything throws — which is
        ;; what makes this a COMPLETION-cascade throw rather than a
        ;; dispatch-time one.
        (set! rf.http.privacy-body/classify-decoded
              (fn [& args]
                (if @armed?
                  (do (swap! throw-count inc)
                      (throw (ex-info "completion cascade kaboom"
                                      {:rf.error/id :rf.error/schemas-artefact-missing})))
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
                    ;; The policy the pre-fix leak retried under. Short backoff
                    ;; so a re-send would land well inside the settle window
                    ;; below rather than after the assertions.
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
            (.then (fn [_]
                     ;; Wait past several backoff windows. A regression re-sends
                     ;; HERE, so the fetch-count assertion below is only
                     ;; meaningful after this settle.
                     (js/Promise. (fn [resolve _] (js/setTimeout resolve 200)))))
            (.then (fn [_]
                     ;; ---- PRECONDITIONS --------------------------------
                     ;; The planted throw was actually reached. If the cascade
                     ;; stops calling classify-decoded on the 2xx path this
                     ;; reads 0 and the pin reds rather than passing while
                     ;; exercising nothing.
                     (is (pos? @throw-count)
                         "PRECONDITION: the planted throw was reached in the completion cascade")
                     ;; It landed ABOVE the reply tail. :rf.http/replied is
                     ;; emitted from dispatch-success!, below finalise-success!;
                     ;; its absence is what says this is the COMPLETION fence
                     ;; and not dispatch-reply!'s pre-existing one.
                     (is (empty? (ops traces :rf.http/replied))
                         "PRECONDITION: the throw landed ABOVE the reply tail — no reply envelope was built")

                     ;; ---- VERDICT --------------------------------------
                     ;; The headline. A double-send is invisible to the caller,
                     ;; so this count is the only thing that can catch it.
                     (is (= 1 @fetch-count)
                         "fetched EXACTLY ONCE — the completion throw was not
                          reclassified as :rf.http/transport and re-sent")
                     (is (empty? @replies)
                         "no reply delivered — the cascade threw before any reply was built")
                     (let [rtf (ops traces :rf.error/http-reply-tail-failed)]
                       (is (= 1 (count rtf))
                           "exactly one :rf.error/http-reply-tail-failed (observed, once)")
                       (is (= :rf.error/schemas-artefact-missing
                              (:reply-error-id (:tags (first rtf))))
                           "the caught throw's own :rf.error/id rides the trace, which is
                            what distinguishes a completion-cascade throw from an :after throw"))
                     (is (empty? (filter (fn [ev]
                                           (= :rf.http/transport
                                              (get-in ev [:tags :kind])))
                                         @traces))
                         "the completion throw was NOT reclassified as :rf.http/transport")))
            (.catch (fn [e]
                      (is false (str "rf2-1eng8 — unexpected: " e))
                      nil))
            (.then (fn [_] (restore) (done))))))))
