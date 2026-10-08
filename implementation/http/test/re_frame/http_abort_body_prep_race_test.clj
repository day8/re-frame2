(ns re-frame.http-abort-body-prep-race-test
  "(JVM) An abort that wins while `run-attempt!` is preparing the request must
  never subsequently enter the host transport. A `:body` thunk can hold
  `prepare-body!` arbitrarily long, and while it does there is no future for
  the abort to cancel; an abort that delivered its cancelled reply and then
  let the attempt call `sendAsync` would issue a side-effecting request after
  telling the app it was cancelled (Spec 014 §Abort precedence, §Aborts).

  The guard is a one-cell issuance CAS shared by the abort closure and the
  host-entry region, whose commit is the host call itself: abort-before-entry
  wins the cell and the transport is never entered; entry-before-abort wins it
  and publishes the cancellable future. On the JVM the commit, `sendAsync` and
  the publication share a per-attempt monitor that the abort's cancel step
  also takes, so an abort racing an in-flight host call waits and then
  cancels the published future. The re-entrant test drives the single-threaded
  ordering CLJS produces through the JVM's copy of the same gate.

  `transport-jvm/jvm-fetch` is stubbed (the seam such a request would escape
  through), and the orderings are made with latches, promises and thread-state
  observation, never sleeps."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.http.transport :as rf.http.transport]
            [re-frame.http.transport-jvm :as rf.http.transport-jvm]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [java.lang Thread$State]
           [java.util.concurrent CompletableFuture]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- await-condition! [pred]
  (rf.test-support/poll-until pred {:timeout-ms 5000 :interval-ms 10
                                    :label "http-abort-body-prep-race condition"}))

(defn- await-blocked!
  "True iff `t` is observed BLOCKED (waiting to enter a monitor) within the
  budget. A thread that must take a held monitor enters BLOCKED and stays
  there; one with no monitor to take never does."
  [^Thread t timeout-ms]
  (let [deadline (+ (System/nanoTime) (* (long timeout-ms) 1000000))]
    (loop []
      (cond
        (= Thread$State/BLOCKED (.getState t)) true
        (> (System/nanoTime) deadline)         false
        :else                                  (do (Thread/sleep 5) (recur))))))

(defn- register-recorder-and-issue! [replies request-id request]
  (rf/reg-event :reply/recorder
    (fn [_ [_ payload]] (swap! replies conj payload) {}))
  (rf/reg-event :issue
    (fn [_ _]
      {:fx [[:rf.http/managed
             {:request    request
              :decode     :text
              :request-id request-id
              :on-failure [:reply/recorder]
              :on-success [:reply/recorder]}]]})))

(defn- counting-fetch
  "A `jvm-fetch` stub counting its calls and keeping the future it returns."
  [fetch-calls returned-cf]
  (fn [_]
    (swap! fetch-calls inc)
    (let [cf (CompletableFuture.)]
      (reset! returned-cf cf)
      cf)))

(def ^:private reply-shape (juxt :status (comp :kind :error) (comp :reason :error)))

(defn- registries-empty? []
  (and (empty? (rf.http.registry/in-flight-snapshot))
       (empty? (rf.http.registry/actor-in-flight-snapshot))))

(deftest abort-during-body-prep-never-enters-host-transport
  (let [fetch-calls   (atom 0)
        thunk-entered (promise)
        release       (promise)
        replies       (atom [])]
    (with-redefs [rf.http.transport-jvm/jvm-fetch (counting-fetch fetch-calls (atom nil))]
      (register-recorder-and-issue!
        replies :prep-race
        {:url    "http://127.0.0.1:0/x"
         :method :post
         ;; Holds the attempt inside prepare-body! while the abort completes.
         :body   (fn []
                   (deliver thunk-entered true)
                   @release
                   "held-body")})
      (let [worker (future (rf/dispatch-sync [:issue]))]
        (try
          (is (true? (deref thunk-entered 5000 false)) "preparation is in progress")
          (is (true? (rf.http.registry/abort-in-flight! :prep-race :user)))
          (deliver release true)
          (is (not= ::timeout (deref worker 5000 ::timeout))
              "the attempt ran to its end after release, so the zero below is final")
          (is (zero? @fetch-calls) "the cancelled attempt never reached sendAsync")
          (await-condition! #(seq @replies))
          (is (= [[:cancelled :rf.http/aborted :user]] (mapv reply-shape @replies)))
          (is (registries-empty?))
          (finally
            (deliver release true)
            (deref worker 5000 nil)))))))

(deftest reentrant-abort-inside-body-thunk-never-enters-host-transport
  ;; The thunk aborts its own request and then returns, so preparation succeeds
  ;; and the gate alone stands between the cancellation and the send.
  (let [fetch-calls  (atom 0)
        abort-result (atom ::not-fired)
        replies      (atom [])]
    (with-redefs [rf.http.transport-jvm/jvm-fetch (counting-fetch fetch-calls (atom nil))]
      (register-recorder-and-issue!
        replies :prep-reentrant
        {:url    "http://127.0.0.1:0/x"
         :method :post
         :body   (fn []
                   (reset! abort-result
                           (rf.http.registry/abort-in-flight! :prep-reentrant :user))
                   "reentrant-body")})
      (rf/dispatch-sync [:issue])
      (is (= [true 0] [@abort-result @fetch-calls]))
      (await-condition! #(seq @replies))
      (is (= [[:cancelled :rf.http/aborted :user]] (mapv reply-shape @replies)))
      (is (empty? (rf.http.registry/in-flight-snapshot))))))

(deftest abort-after-issuance-cancels-published-future
  ;; The handoff control: when issuance wins, the future is published and an
  ;; abort cancels it without a second reply.
  (let [fetch-calls (atom 0)
        returned-cf (atom nil)
        replies     (atom [])]
    (with-redefs [rf.http.transport-jvm/jvm-fetch (counting-fetch fetch-calls returned-cf)]
      (register-recorder-and-issue!
        replies :issued-then-abort
        {:url "http://127.0.0.1:0/x" :method :post :body "plain"})
      (rf/dispatch-sync [:issue])
      (is (= 1 @fetch-calls))
      (is (true? (rf.http.registry/abort-in-flight! :issued-then-abort :user)))
      (is (.isCancelled ^CompletableFuture @returned-cf) "the abort cancelled the published future")
      (await-condition! #(seq @replies))
      (is (= [[:cancelled :rf.http/aborted :user]] (mapv reply-shape @replies)))
      (is (empty? (rf.http.registry/in-flight-snapshot))))))

(deftest abort-completing-at-issuance-boundary-issues-no-request
  ;; An abort that completes one step before the host call must leave zero host
  ;; calls behind it: cancelling a future afterwards cannot un-send a POST.
  (let [fetch-calls  (atom 0)
        abort-result (atom ::not-fired)
        replies      (atom [])]
    (try
      (with-redefs [rf.http.transport-jvm/jvm-fetch (counting-fetch fetch-calls (atom nil))]
        (register-recorder-and-issue!
          replies :issuance-boundary
          {:url "http://127.0.0.1:0/x" :method :post :body "plain"})
        (rf.http.transport/set-test-interleave-hook!
          (fn [point ctx]
            (when (and (= point :issue/before-send)
                       (= :issuance-boundary (:request-id ctx))
                       (= ::not-fired @abort-result))
              (reset! abort-result
                      (rf.http.registry/abort-in-flight! :issuance-boundary :user)))))
        (rf/dispatch-sync [:issue])
        (is (= [true 0] [@abort-result @fetch-calls]))
        (await-condition! #(seq @replies))
        (is (= [[:cancelled :rf.http/aborted :user]] (mapv reply-shape @replies)))
        (is (registries-empty?)))
      (finally
        (rf.http.transport/set-test-interleave-hook! nil)))))

(deftest concurrent-abort-cannot-complete-while-host-call-is-in-flight
  ;; The opposite ordering: an abort fired from inside the host call blocks on
  ;; the issuance monitor, so it can neither clear the registry nor reply until
  ;; the future is published, which it then cancels. Issuance won, so one host
  ;; call is correct.
  (let [fetch-calls        (atom 0)
        returned-cf        (atom nil)
        abort-result       (atom ::not-fired)
        aborter            (atom nil)
        observed-blocked   (atom ::not-observed)
        registry-in-region (atom ::not-sampled)
        replies-in-region  (atom ::not-sampled)
        replies            (atom [])]
    (with-redefs [rf.http.transport-jvm/jvm-fetch
                  (fn [_]
                    (swap! fetch-calls inc)
                    (let [cf (CompletableFuture.)
                          t  (Thread.
                               ^Runnable
                               (fn []
                                 (reset! abort-result
                                         (rf.http.registry/abort-in-flight! :handshake :user)))
                               "rf2-rsv2n-aborter")]
                      (reset! returned-cf cf)
                      (reset! aborter t)
                      (.start t)
                      (reset! observed-blocked (await-blocked! t 2000))
                      (reset! registry-in-region (rf.http.registry/in-flight-snapshot))
                      (reset! replies-in-region @replies)
                      cf))]
      (register-recorder-and-issue!
        replies :handshake
        {:url "http://127.0.0.1:0/x" :method :post :body "plain"})
      (rf/dispatch-sync [:issue])
      (.join ^Thread @aborter 5000)
      (is (= [true true []]
             [@observed-blocked (contains? @registry-in-region :handshake) @replies-in-region])
          "while the host call was in flight the abort was BLOCKED, and had neither cleared the registry nor replied")
      (is (= [true 1 true] [@abort-result @fetch-calls (.isCancelled ^CompletableFuture @returned-cf)])
          "once the region released, the abort cancelled the published future")
      (await-condition! #(seq @replies))
      (is (= [[:cancelled :rf.http/aborted :user]] (mapv reply-shape @replies)))
      (is (registries-empty?)))))
