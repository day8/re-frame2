(ns re-frame.http-interceptor-chain-capture-cljs-test
  "Host-symmetric (JVM + CLJS) contract: HTTP interceptor CHAIN RESOLUTION IS
  AT ISSUE TIME (Spec 014 §Chain order and frame scope).

  A managed request captures its frame's interceptor chain once, immediately
  before running `:before`, and its response walks the SAME captured
  registrations in reverse. Resolving the `:after` chain again from the live
  registry would let a response landing after the registry changed walk a
  chain its request never had, handing an `:after` a ctx its own `:before`
  never touched.

  The tests hold a response OUTSTANDING without a clock: the canned path
  splits into `capture-and-run-request-chain` (issue: capture, walk `:before`)
  and `emit-canned-success!` (respond: walk `:after`, dispatch), and holding
  the value between those calls is the outstanding response. The
  live-transport counterpart is JVM-only and lives in `http-interceptors-test`.

  Replies are silenced with an explicit `:reply-to nil`, so the assertions
  observe the interceptor walks rather than app-db."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.test-support :as rf.http.test-support]
            [re-frame.test-support :as rf.test-support]
            #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
                :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

;; Every registration and clear names its frame explicitly, so these tests
;; read identically on both hosts whatever the ambient scope.

(def ^:private test-frame :v3f6/app)
(def ^:private frame-ctx {:frame test-frame})

(defn- marking
  "An interceptor that stamps `id` on the ctx in `:before` and, in
  `:after`, appends `[id <the mark it finds on the ctx it is handed>]` to
  `log`. A nil mark is the tell that this `:after` ran for a request whose
  `:before` chain it was never part of."
  [id log]
  {:frame  test-frame
   :before (fn [ctx] (assoc ctx id :marked))
   :after  (fn [ctx response]
             (swap! log conj [id (get ctx id)])
             response)})

(defn- issue!
  "ISSUE a request: capture the chain, walk `:before`. The returned value
  is the request's outstanding response — held by the caller until
  `release!`."
  [args]
  (rf.http.test-support/capture-and-run-request-chain frame-ctx (assoc args :reply-to nil)))

(defn- release!
  "RELEASE an outstanding response: walk `:after` over the chain that
  request captured, then dispatch (silenced by `:reply-to nil`)."
  [captured args]
  (rf.http.test-support/emit-canned-success! frame-ctx (assoc args :reply-to nil) captured))

(defn- clear-frame! []
  (rf.http.managed/clear-all-http-interceptors!))

(deftest outstanding-response-walks-the-chain-its-request-was-issued-under
  (testing "request A is issued under [:one :two]; the registry
            then loses :one and gains :three; request B is issued under
            [:two :three]. Both responses are released afterwards. A walks
            ITS OWN chain in reverse with ITS OWN marks, B the new one."
    (clear-frame!)
    (let [log (atom [])]
      (rf/reg-http-interceptor :one (marking :one log))
      (rf/reg-http-interceptor :two (marking :two log))
      (let [a (issue! {:value :a})]
        (rf/clear :http-interceptor :one {:frame test-frame})
        (rf/reg-http-interceptor :three (marking :three log))
        (is (= [:two :three]
               (mapv :id (rf.http.managed/interceptors-snapshot test-frame)))
            "control: the LIVE registry really did change while A was outstanding")
        (let [b (issue! {:value :b})]
          (release! a {:value :a})
          (is (= [[:two :marked] [:one :marked]] @log)
              "A's :after walk is the REVERSE of A's own captured chain, and
               every :after was handed the ctx its own :before stamped —
               including :one's, whose registration had already been cleared
               when the response landed")
          (reset! log [])
          (release! b {:value :b})
          (is (= [[:three :marked] [:two :marked]] @log)
              "B, issued after the change, walks the NEW chain — the capture
               is per-request, not a process-wide freeze"))))))

;; Distinguishes real capture from LATE capture: a snapshot taken after the
;; `:before` walk returns would already hold an interceptor the walk itself
;; registered, so its `:after` would run with a nil mark.

(deftest a-registration-made-inside-a-before-does-not-join-that-request
  (testing "an interceptor registered from INSIDE a `:before`
            joins only LATER requests; it does not acquire an `:after` on
            the request whose `:before` walk registered it"
    (clear-frame!)
    (let [log (atom [])]
      (rf/reg-http-interceptor :opener
        {:frame  test-frame
         :before (fn [ctx]
                   (rf/reg-http-interceptor :joiner (marking :joiner log))
                   (assoc ctx :opener :marked))
         :after  (fn [ctx response]
                   (swap! log conj [:opener (:opener ctx)])
                   response)})
      (let [a (issue! {:value :a})]
        (is (= [:opener :joiner]
               (mapv :id (rf.http.managed/interceptors-snapshot test-frame)))
            "control: :joiner IS on the live chain — the registration took")
        (release! a {:value :a})
        (is (= [[:opener :marked]] @log)
            ":joiner did not join the request its own registration interrupted"))
      (reset! log [])
      (let [b (issue! {:value :b})]
        (release! b {:value :b})
        (is (= [[:joiner :marked] [:opener :marked]] @log)
            "the NEXT request captures :joiner and runs both halves of it")))))

(deftest an-empty-capture-is-a-real-snapshot-not-a-fallback-signal
  (testing "a request issued while the frame has NO interceptors
            walks no `:after`, however the registry looks when its response
            lands. This is the arm a live-registry fallback would fail."
    (clear-frame!)
    (let [log (atom [])]
      (is (empty? (rf.http.managed/interceptors-snapshot test-frame))
          "control: the frame's chain really is empty at issue")
      (let [a (issue! {:value :a})]
        (rf/reg-http-interceptor :late (marking :late log))
        (is (seq (rf.http.managed/interceptors-snapshot test-frame))
            "control: the registry is non-empty by the time the response lands")
        (release! a {:value :a})
        (is (= [] @log)
            "an empty capture runs NO :after — it is a real snapshot, never a
             signal to consult the live registry")))))
