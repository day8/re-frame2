(ns re-frame.http-interceptor-chain-capture-cljs-test
  "Host-symmetric (JVM + CLJS) contract for rf2-v3f6 — HTTP interceptor
  CHAIN RESOLUTION IS AT ISSUE TIME (Spec 014 §Chain order and frame
  scope).

  A managed request captures its frame's interceptor chain once,
  immediately before running `:before`, and its response walks the SAME
  captured registrations in reverse. Before this, the `:before` chain was
  resolved once at issue while the `:after` chain was resolved AGAIN from
  the live process-global registry at response time, so a response landing
  after the registry changed walked a chain its request never had — and an
  `:after` could be handed a middleware-ctx its own `:before` never
  touched, which is precisely what the ctx-carried-from-`:before` contract
  promises cannot happen.

  ## Why the canned seam, and why there are no sleeps here

  These tests need a request whose response is OUTSTANDING while the
  registry changes underneath it. They get one WITHOUT a clock: the canned
  path splits into `capture-and-run-request-chain` (issue: capture the
  chain, walk `:before`) and `emit-canned-success!` (respond: walk
  `:after`, dispatch), which is exactly the composition
  `canned-success-handler` performs. Holding the value between those two
  calls IS the outstanding response — the test, not a timer, decides when
  each reply lands. Nothing here depends on elapsed time, on test order,
  or on a fixture running in a particular sequence.

  The live-transport counterpart — a real in-process server holding its
  response open across a registry mutation, and the same contract across a
  retry handoff — is JVM-only and lives in `http-interceptors-test`.

  Replies are deliberately UNADDRESSED (no `:reply-to` / `:on-success`),
  so `build-reply-event` silences the dispatch and the assertions observe
  the interceptor walks themselves rather than app-db."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; production HTTP surface — registers the per-frame interceptor
            ;; chain and publishes its late-bind hooks.
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.test-support :as rf.http.test-support]
            [re-frame.test-support :as rf.test-support]
            #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
                :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

;; ---- fixtures-free scaffolding --------------------------------------------
;;
;; Every registration and clear names its frame EXPLICITLY, so these tests
;; depend on no ambient `*current-frame*` scope and read identically on both
;; hosts.

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
  (rf.http.test-support/capture-and-run-request-chain frame-ctx args))

(defn- release!
  "RELEASE an outstanding response: walk `:after` over the chain that
  request captured, then dispatch (silenced — the reply is unaddressed)."
  [captured args]
  (rf.http.test-support/emit-canned-success! frame-ctx args captured))

(defn- clear-frame! []
  (rf.http.managed/clear-all-http-interceptors!))

;; ---- (i) the acceptance criterion: A keeps its chain, B gets the new one ---

(deftest outstanding-response-walks-the-chain-its-request-was-issued-under
  (testing "rf2-v3f6 — request A is issued under [:one :two]; the registry
            then loses :one and gains :three; request B is issued under
            [:two :three]. Both responses are released afterwards. A walks
            ITS OWN chain in reverse with ITS OWN marks, B the new one."
    (clear-frame!)
    (let [log (atom [])]
      (rf/reg-http-interceptor :one (marking :one log))
      (rf/reg-http-interceptor :two (marking :two log))

      ;; A is issued and its response is now outstanding.
      (let [a (issue! {:value :a})]

        ;; The registry changes while A is in flight.
        (rf/clear :http-interceptor :one {:frame test-frame})
        (rf/reg-http-interceptor :three (marking :three log))
        (is (= [:two :three]
               (mapv :id (rf.http.managed/interceptors-snapshot test-frame)))
            "control: the LIVE registry really did change while A was outstanding")

        ;; B is issued under the new chain; its response is outstanding too.
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

;; ---- (ii) capture is BEFORE the :before walk, not after it -----------------
;;
;; This is the test that distinguishes real capture from LATE capture. A
;; snapshot taken where the middleware-ctx is assembled (after the `:before`
;; walk returns) would already contain an interceptor that the walk itself
;; registered — so that interceptor's `:after` would run, holding a ctx its
;; own `:before` never touched, and the nil mark below would appear. No
;; asynchrony of any kind is involved: the whole discriminator is synchronous
;; and the failure is a wrong VALUE, never a slow one.

(deftest a-registration-made-inside-a-before-does-not-join-that-request
  (testing "rf2-v3f6 — an interceptor registered from INSIDE a `:before`
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

      ;; …and it does join the next one.
      (reset! log [])
      (let [b (issue! {:value :b})]
        (release! b {:value :b})
        (is (= [[:joiner :marked] [:opener :marked]] @log)
            "the NEXT request captures :joiner and runs both halves of it")))))

;; ---- (iii) an empty capture is a real snapshot -----------------------------

(deftest an-empty-capture-is-a-real-snapshot-not-a-fallback-signal
  (testing "rf2-v3f6 — a request issued while the frame has NO interceptors
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

;; ---- (v) the canned entry point itself -------------------------------------
;;
;; The three tests above hold the two halves apart deliberately. This one
;; drives the public canned handler, which composes them itself, so the
;; single-capture property is pinned on the entry point real test fixtures
;; call rather than only on the seam.

(deftest canned-success-handler-runs-both-walks-off-ONE-capture
  (testing "rf2-v3f6 — `canned-success-handler` captures once and uses that
            capture for both walks: a `:before`-time registration does not
            reach the reply it interrupted"
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
      (rf.http.test-support/canned-success-handler frame-ctx {:value 1})
      (is (= [[:opener :marked]] @log)
          "one capture drove both walks — the canned path carries the same
           issue-time contract as the real transport"))))
