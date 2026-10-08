(ns re-frame.http-anonymous-frame-ownership-test
  "A managed request is owned by the frame that issued it, even with no
  `:request-id` and no owning actor. The two frame-lifecycle boundaries — frame
  destroy and the epoch-restore quiesce — cancel every request the frame issued
  and suppress its late reply, recording a `:rf.http/stale-suppressed` row whose
  `:recovery` names the boundary.

  The host transport is replaced only at `jvm-fetch`; the managed effect,
  registry, router, `destroy-frame!` and reply dispatch are real."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.http.transport-jvm :as rf.http.transport-jvm]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [java.io IOException]
           [java.util.concurrent CompletableFuture]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- settle-router!
  "FIFO barrier. The router drains on the runtime's single-thread executor, so a
  task queued behind a reply dispatch runs only once that reply's event has been
  handled and committed."
  []
  (let [done (promise)]
    (rf.interop/next-tick #(deliver done true))
    (is (true? (deref done 2000 false)) "the router barrier ran")))

(def ^:private ok-response
  {:ok? true :status 200 :status-text "OK" :headers {} :body-text "old-incarnation"})

(defn- reg-anonymous-load!
  "`:anon/load` issues an ANONYMOUS managed request: no `:request-id`, and from
  an ordinary event handler, so no owning actor. `:anon/reply` records each
  reply and commits its value to app-db."
  ([replies] (reg-anonymous-load! replies nil))
  ([replies retry]
   (rf/reg-event :anon/load
     (fn [_ _]
       {:fx [[:rf.http/managed
              (cond-> {:request  {:url "http://example.invalid/anon"}
                       :decode   :text
                       :reply-to [:anon/reply]}
                retry (assoc :retry retry))]]}))
   (rf/reg-event :anon/reply
     (fn [{:keys [db]} [_ reply]]
       (swap! replies conj reply)
       {:db (assoc db :late-response (:value reply))}))))

(defn- stale-suppressed [traces]
  (filter #(= :rf.http/stale-suppressed (:operation %)) traces))

(deftest destroy-then-recreate-drops-the-anonymous-late-reply
  (testing "an anonymous request in flight when its frame is destroyed is
            cancelled, and its late completion is not delivered into a new frame
            created under the same id"
    (let [cf      (CompletableFuture.)
          replies (atom [])]
      (reg-anonymous-load! replies)
      (rf/make-frame {:id :frame/anon})
      (with-redefs [rf.http.transport-jvm/jvm-fetch (fn [_] cf)]
        (rf/dispatch-sync [:anon/load] {:frame :frame/anon})
        (rf/destroy-frame! :frame/anon)
        (is (.isCancelled cf)
            "destroy-frame! cancelled the anonymous request's host future")
        (rf/make-frame {:id :frame/anon})
        ;; The completion callback runs on this thread, so any reply it
        ;; dispatches is queued ahead of the barrier below.
        (.complete cf ok-response)
        (settle-router!)
        (is (empty? @replies)
            "no reply from the destroyed incarnation was delivered")))))

(deftest frame-boundaries-cancel-every-anonymous-request-and-spare-siblings
  (doseq [[label hook recovery] [[:frame-destroy :http/on-frame-destroyed! :suppressed-on-frame-destroy]
                                 [:epoch-restore :http/abort-in-flight-for-frame! :suppressed-on-epoch-restore]]]
    (testing (str label " — through its published hook, every anonymous request
                  frame A issued is cancelled and suppressed with one stale row
                  each, while frame B's identical request is untouched and still
                  delivers")
      (rf.http.registry/clear-all-in-flight!)
      (let [issued  (atom [])
            replies (atom [])
            traces  (atom [])]
        (reg-anonymous-load! replies)
        (rf/make-frame {:id :frame/a})
        (rf/make-frame {:id :frame/b})
        (with-redefs [rf.http.transport-jvm/jvm-fetch
                      (fn [opts]
                        (let [cf (CompletableFuture.)]
                          (swap! issued conj [(:frame opts) cf])
                          cf))]
          (rf/dispatch-sync [:anon/load] {:frame :frame/a})
          (rf/dispatch-sync [:anon/load] {:frame :frame/a})
          (rf/dispatch-sync [:anon/load] {:frame :frame/b})
          (let [futures-of (fn [frame-id]
                             (keep (fn [[f cf]] (when (= f frame-id) cf)) @issued))
                a-futures  (futures-of :frame/a)
                [b-future] (futures-of :frame/b)]
            (try
              (rf.trace.tooling/register-listener! ::boundary #(swap! traces conj %))
              ((rf.late-bind/get-fn hook) :frame/a)
              (finally
                (rf.trace.tooling/unregister-listener! ::boundary)))
            (is (= [true true] (map #(.isCancelled ^CompletableFuture %) a-futures))
                "both of frame A's anonymous requests were cancelled by the one sweep")
            (is (false? (.isCancelled ^CompletableFuture b-future))
                "the sibling frame's anonymous request is untouched")
            (is (= (repeat 2 [recovery {:rf.reply/status       :stale
                                        :rf.reply/work-status  :suppressed
                                        :rf.reply/stale-reason :rf.http/request-id-superseded
                                        :frame                 :frame/a}])
                   (map (juxt :recovery #(select-keys (:tags %) [:rf.reply/status :rf.reply/work-status
                                                                 :rf.reply/stale-reason :frame]))
                        (stale-suppressed @traces)))
                ":recovery names the boundary")
            (doseq [cf a-futures] (.complete ^CompletableFuture cf ok-response))
            (.complete ^CompletableFuture b-future ok-response)
            (settle-router!)
            (is (= [:frame/b] (mapv :rf.frame/id @replies))
                "only the sibling's reply was delivered; frame A's late completions were suppressed")))
        (rf/destroy-frame! :frame/a)
        (rf/destroy-frame! :frame/b)))))

(deftest frame-destroy-cancels-an-anonymous-request-sleeping-in-backoff
  (testing "an anonymous request sleeping in its retry backoff when its frame is
            destroyed never issues attempt 2 and delivers nothing"
    (let [fetches    (atom 0)
          replies    (atom [])
          backoff-ms 100]
      (reg-anonymous-load! replies {:on           #{:rf.http/http-5xx}
                                    :max-attempts 3
                                    :backoff      {:base-ms backoff-ms :factor 1
                                                   :max-ms  backoff-ms}})
      (rf/make-frame {:id :frame/anon})
      (with-redefs [rf.http.transport-jvm/jvm-fetch
                    (fn [_]
                      (swap! fetches inc)
                      (CompletableFuture/completedFuture
                        {:ok? false :status 503 :status-text "" :headers {} :body-text "busy"}))]
        ;; Attempt 1 completes synchronously with a retryable 503, so by the
        ;; time dispatch-sync returns the request is sleeping in its backoff.
        (rf/dispatch-sync [:anon/load] {:frame :frame/anon})
        (rf/destroy-frame! :frame/anon)
        ;; A retry that must NOT happen has no positive signal to wait for:
        ;; outlast two further backoff windows, then count.
        (Thread/sleep (* 3 backoff-ms))
        (settle-router!)
        (is (= 1 @fetches)
            "the pending retry was cancelled — attempt 2 was never issued")
        (is (empty? @replies)
            "nothing was delivered for the destroyed frame's request")))))

(deftest completed-anonymous-requests-leave-nothing-for-the-frame-sweep
  (testing "a success and a failure each release their frame ownership when they
            complete: a later sweep of the frame finds only the request that is
            still live"
    (let [futures (atom [])
          replies (atom [])
          traces  (atom [])]
      (reg-anonymous-load! replies)
      (rf/make-frame {:id :frame/anon})
      (with-redefs [rf.http.transport-jvm/jvm-fetch
                    (fn [_]
                      (let [cf (CompletableFuture.)]
                        (swap! futures conj cf)
                        cf))]
        (dotimes [_ 3] (rf/dispatch-sync [:anon/load] {:frame :frame/anon}))
        (let [[ok-cf failed-cf live-cf] @futures]
          (.complete ^CompletableFuture ok-cf ok-response)
          (.completeExceptionally ^CompletableFuture failed-cf (IOException. "connection reset"))
          (settle-router!)
          (try
            (rf.trace.tooling/register-listener! ::sweep #(swap! traces conj %))
            (rf.http.registry/abort-in-flight-on-frame-destroyed! :frame/anon)
            (is (= 1 (count (stale-suppressed @traces)))
                "the sweep found exactly one handle: neither completed request was retained")
            (is (.isCancelled ^CompletableFuture live-cf)
                "and that one was the request still in flight")
            (finally
              (rf.trace.tooling/unregister-listener! ::sweep))))))))
