(ns re-frame.http-interceptor-chain-capture-cljs-test
  "Host-symmetric (JVM + CLJS) contract: HTTP interceptor CHAIN RESOLUTION IS
  AT ISSUE TIME (Spec 014 §Chain order and frame scope).

  A managed request resolves its frame's `:http-interceptors` chain once,
  immediately before running `:before`, and its response walks the SAME
  captured definitions in reverse. Resolving the `:after` chain again would
  let a response landing after a definition or the frame's chain changed
  walk a chain its request never had, handing an `:after` a ctx its own
  `:before` never touched.

  The tests hold a response OUTSTANDING without a clock: a pending stub
  transport receives the request once it has captured its chain and walked
  `:before`, and holds it until the test answers it, which is the response
  landing. The live-transport counterpart is JVM-only and lives in
  `http-interceptors-test`.

  Replies are silenced with an explicit `:reply-to nil`, so the assertions
  observe the interceptor walks rather than app-db."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.stub-transport :as rf.http.stub-transport]
            [re-frame.test-support :as rf.test-support]
            #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
                :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

;; Every request names its frame explicitly, so these tests read identically
;; on both hosts whatever the ambient scope.

(def ^:private test-frame :v3f6/app)

(defn- name-chain! [ids]
  (rf/make-frame {:id test-frame :http-interceptors ids}))

(defn- marking
  "A definition that stamps `id` on the ctx in `:before` and, in `:after`,
  appends `[tag <the mark it finds on the ctx it is handed>]` to `log`. A nil
  mark is the tell that this `:after` ran for a request whose `:before` chain
  it was never part of."
  [id tag log]
  {:before (fn [ctx] (assoc ctx id :marked))
   :after  (fn [ctx response]
             (swap! log conj [tag (get ctx id)])
             response)})

(defn- issue!
  "ISSUE a request from the test frame through a pending transport: it
  captures its chain, walks `:before` and is held. Returns the held exchange,
  the request's outstanding response, for `release!`."
  []
  (let [{:keys [transport exchanges]} (rf.http.stub-transport/pending-transport)]
    (rf/reg-event ::issue
      {:doc "Issues the request under test, its reply silenced."}
      (fn [_ _] {:fx [[:rf.http/managed {:request  {:method :get :url "/v3f6"}
                                         :reply-to nil}]]}))
    (rf.http.stub-transport/with-transport transport
      #(rf/dispatch-sync [::issue] {:frame test-frame}))
    (first @exchanges)))

(defn- release!
  "RELEASE an outstanding response: answer the held exchange, so the request
  walks `:after` over the chain it captured. Returns whether it settled."
  [exchange]
  ((:respond! exchange) {:body {}}))

;; Distinguishes real capture from LATE capture: a snapshot taken after the
;; `:before` walk returns would already hold the definition the walk itself
;; registered, so its `:after` would run with a nil mark.

(deftest a-definition-replaced-inside-a-before-does-not-reach-that-request
  (testing "a definition re-registered from INSIDE a `:before` reaches only
            LATER requests; the request whose `:before` walk replaced it runs
            the definition it captured, both halves"
    (let [log (atom [])]
      (rf/reg-http-interceptor :swapped (marking :swapped :v1 log))
      (rf/reg-http-interceptor :opener
        {:before (fn [ctx]
                   (rf/reg-http-interceptor :swapped (marking :swapped :v2 log))
                   (assoc ctx :opener :marked))
         :after  (fn [ctx response]
                   (swap! log conj [:opener (:opener ctx)])
                   response)})
      (name-chain! [:opener :swapped])
      (let [a (issue!)]
        (is (true? (release! a)))
        (is (= [[:v1 :marked] [:opener :marked]] @log)
            "the request ran the :swapped it captured, not the replacement"))
      (reset! log [])
      (let [b (issue!)]
        (is (true? (release! b)))
        (is (= [[:v2 :marked] [:opener :marked]] @log)
            "the NEXT request captures the replacement and runs both halves of it")))))

(deftest an-empty-capture-is-a-real-snapshot-not-a-fallback-signal
  (testing "a request issued while the frame names NO chain walks no
            `:after`, whatever the frame names when its response lands. This
            is the arm a late re-resolution would fail."
    (let [log (atom [])]
      (name-chain! [])
      (is (empty? (rf.http.managed/interceptors-snapshot test-frame))
          "control: the frame's chain really is empty at issue")
      (let [a (issue!)]
        (rf/reg-http-interceptor :late (marking :late :late log))
        (name-chain! [:late])
        (is (seq (rf.http.managed/interceptors-snapshot test-frame))
            "control: the frame names a chain by the time the response lands")
        (is (true? (release! a)) "the response landed")
        (is (= [] @log)
            "an empty capture runs NO :after — it is a real snapshot, never a
             signal to resolve the frame's chain again")))))
