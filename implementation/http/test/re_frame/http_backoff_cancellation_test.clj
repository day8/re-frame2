(ns re-frame.http-backoff-cancellation-test
  "The retry backoff window is cancellable (Spec 014 §Retry and backoff,
  §Aborts, §Abort on actor destroy). A request sleeping between attempts stays
  registered under a handle whose `:abort-fn` cancels the pending retry timer;
  user abort, actor destruction and same-id supersession converge on it. An
  always-500 server counts hits, so a retry that fires after the cancel shows
  as a second hit once the backoff deadline has passed. That an uncancelled
  backoff retries is pinned by every retry-exhaustion round trip."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent.atomic AtomicInteger]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- start-counting-500-server!
  []
  (let [hits   (AtomicInteger. 0)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (.incrementAndGet hits)
                        (let [^HttpExchange ex ex
                              bs (.getBytes "boom" "UTF-8")]
                          (try
                            (.sendResponseHeaders ex 500 (long (count bs)))
                            (with-open [os (.getResponseBody ex)]
                              (.write os bs))
                            (catch Throwable _ nil))))))
    (.setExecutor server nil)
    (.start server)
    {:server server
     :port   (.getPort (.getAddress server))
     :hits   hits}))

(defn- stop-server! [{:keys [^HttpServer server]}]
  (.stop server 0))

;; Long enough to observe the sleeping state and cancel well inside it.
(def ^:private backoff-ms 2000)

(def ^:private retry-config
  {:on           #{:rf.http/http-5xx}
   :max-attempts 5
   :backoff      {:base-ms backoff-ms :factor 1 :max-ms backoff-ms}})

(defn- await-condition!
  ([pred] (await-condition! pred 5000))
  ([pred timeout-ms]
   (rf.test-support/poll-until pred {:timeout-ms timeout-ms :interval-ms 10
                                  :label "http-backoff-cancellation condition"})
   true))

(defn- await-backoff-sleeping!
  "Wait for attempt #1's hit, then settle so the backoff timer is armed. It
  gates on the hit count, not on registry presence, because presence during
  backoff is the invariant under test."
  [^AtomicInteger hits]
  (await-condition! #(>= (.get hits) 1))
  (Thread/sleep 150))

(defn- assert-no-retry-fired!
  "A retry has no positive signal to poll on: wait past the backoff deadline
  and assert only attempt #1 ever reached the server."
  [^AtomicInteger hits]
  (Thread/sleep (long (+ backoff-ms 600)))
  (is (= 1 (.get hits)) "no retry fired after the cancel"))

(def ^:private reply-shape (juxt :status (comp :kind :error) (comp :reason :error)))

(deftest abort-during-backoff-cancels-pending-retry
  (let [{:keys [^AtomicInteger hits] :as srv} (start-counting-500-server!)
          replies (atom [])]
      (try
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" (:port srv) "/")}
                    :decode     :json
                    :retry      retry-config
                    :request-id :race
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        (rf/reg-event :do/abort
          (fn [_ _] {:fx [[:rf.http/managed-abort :race]]}))
        (rf/dispatch-sync [:issue])
        (await-backoff-sleeping! hits)
        (is (contains? (rf.http.registry/in-flight-snapshot) :race)
            "the sleeping request stays registered during backoff")
        (rf/dispatch-sync [:do/abort])
        (await-condition! #(seq @replies))
        (is (empty? (rf.http.registry/in-flight-snapshot)) "the cancel clears the registry at once")
        (assert-no-retry-fired! hits)
        (is (= [[:cancelled :rf.http/aborted :user]] (mapv reply-shape @replies)))
        (finally
          (stop-server! srv)))))

(deftest actor-destroy-during-backoff-cancels-pending-retry
  (let [{:keys [^AtomicInteger hits] :as srv} (start-counting-500-server!)
          replies (atom [])]
      (try
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-machine :worker/race
          {:initial :idle
           :data    {:port (:port srv)}
           :actions {:fire (fn [{data :data}]
                             {:fx [[:rf.http/managed
                                    {:request    {:url (str "http://127.0.0.1:" (:port data) "/")}
                                     :decode     :json
                                     :retry      retry-config
                                     :request-id :race
                                     :on-failure [:reply/recorder]
                                     :on-success [:reply/recorder]}]]})}
           :states  {:idle    {:on {:start :running}}
                     :running {:entry :fire}}})
        (rf/reg-machine :sup/race
          {:initial :idle
           :states  {:idle    {:on {:start :working}}
                     :working {:spawn {:machine-id :worker/race
                                        :start      [:start]}
                               :on    {:cancel :idle}}}})
        (rf/dispatch-sync [:sup/race [:start]])
        (await-backoff-sleeping! hits)
        (is (= 1 (count (rf.http.registry/actor-in-flight-snapshot)))
            "the sleeping retry stays indexed under its issuing actor")
        (rf/dispatch-sync [:sup/race [:cancel]])
        (await-condition! #(seq @replies))
        (is (and (empty? (rf.http.registry/actor-in-flight-snapshot))
                 (empty? (rf.http.registry/in-flight-snapshot))))
        (assert-no-retry-fired! hits)
        (is (= [[:cancelled :rf.http/aborted :actor-destroyed]] (mapv reply-shape @replies)))
        (finally
          (stop-server! srv)))))

(deftest supersede-during-backoff-stale-trace-carries-sleeping-attempt-work-id
  ;; A backoff handle that dropped :issuance / :attempt would default the
  ;; carried work-id to a phantom attempt 1.
  (let [{:keys [^AtomicInteger hits] :as srv} (start-counting-500-server!)
          ;; The superseding request's hit lands on a separate server.
          new-srv (start-counting-500-server!)
          replies (atom [])
          traces  (atom [])
          lid     ::supersede-backoff-stale]
      (try
        (rf.trace.tooling/register-listener! lid (fn [ev] (swap! traces conj ev)))
        (rf/reg-event :reply/recorder
          (fn [_ [_ payload]] (swap! replies conj payload) {}))
        (rf/reg-event :issue-old
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" (:port srv) "/")}
                    :decode     :json
                    :retry      retry-config
                    :request-id :shared
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        (rf/reg-event :issue-new
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" (:port new-srv) "/")}
                    :decode     :json
                    :request-id :shared
                    :on-failure [:reply/recorder]
                    :on-success [:reply/recorder]}]]}))
        (rf/dispatch-sync [:issue-old])
        ;; Two hits, then settle: the old request now sleeps in attempt #2's backoff.
        (await-condition! #(>= (.get hits) 2))
        (Thread/sleep 150)
        (is (contains? (rf.http.registry/in-flight-snapshot) :shared))
        (rf/dispatch-sync [:issue-new])
        (rf.test-support/poll-until
          #(some (fn [ev] (= :rf.http/stale-suppressed (:operation ev))) @traces)
          {:timeout-ms 5000 :label "supersede-backoff-stale"})
        (is (= [{:rf.reply/status      :stale
                 :rf.reply/work-status :suppressed
                 :rf.reply/work-kind   :http
                 :rf.reply/work-id     [:rf.work/http :shared 1 2]
                 :carried              [:rf.work/http :shared 1 2]
                 :current              [:rf.work/http :shared 2 1]}]
               (->> @traces
                    (filter #(= :rf.http/stale-suppressed (:operation %)))
                    (mapv (fn [{:keys [tags]}]
                            (-> (select-keys tags [:rf.reply/status :rf.reply/work-status
                                                   :rf.reply/work-kind :rf.reply/work-id])
                                (assoc :carried (get-in tags [:rf.reply/carried :work/id])
                                       :current (get-in tags [:rf.reply/current :work/id])))))))
            "one stale row, carrying the sleeping attempt's work-id (issuance 1, attempt 2)")
        (Thread/sleep (long (+ backoff-ms 600)))
        (is (= 2 (.get hits)) "the superseded sleeping retry never fired")
        (is (every? #(not= :request-id-superseded (get-in % [:error :reason])) @replies)
            "the superseded request's app reply is suppressed")
        (finally
          (rf.trace.tooling/unregister-listener! lid)
          (stop-server! srv)
          (stop-server! new-srv)))))
