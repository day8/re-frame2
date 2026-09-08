(ns re-frame.emit-recorder-bracket-cljs-test
  "rf2-kuky.69 — the `with-emit-recorder!` capture bracket, and the tier split
  it exists to express.

  The ruling on rf2-kuky.22 retired `:events` / `:errors` from the public
  `register-listener!` vocabulary. They were a SECOND production observation
  door — unprojected, raw `:exception`, no frame policy, fanned across every
  frame — beside the projected door Spec 015 calls normal, and independent
  corpus observation regardless of a frame's policy is WITHDRAWN as a public
  primitive.

  What the ruling did NOT do is delete the substrates. `re-frame.event-emit`
  and `re-frame.error-emit` survive as IMPLEMENTATION-tier registries for two
  named consumers: the framework's own synchronous-window capture sites (the
  Hicasso server's one-render error window, the test kit's intent capture) and
  TESTS. `re-frame.test-support/with-emit-recorder!` is the test half — ONE
  bracket over both registries rather than a hand-rolled
  register/try/finally/unregister wrapper per file, which is the divergence
  rf2-64iuw measured on the trace side and folded into
  `with-trace-recorder!`.

  Pins, in the acceptance's own terms:

    - one SINK and one temporary error-emit listener observe the SAME live
      failure, each with its own documented shape (projected vs raw) — the
      two tiers are parallel, not alternatives;
    - the bracket captures under an EMPTY frame policy, where no sink route
      exists at all;
    - leaving the bracket ENDS capture — a failure after the body is not
      recorded;
    - the `:events` arm brackets the event substrate, and `:pred` filters;
    - the retired public spelling is gone: `(rf/register-listener! :errors …)`
      throws, and the thrown vocabulary names the two raw dev streams.

  Dual-runtime `*_cljs_test.cljc`: the shadow-cljs `:node-test`
  (`npm run test:cljs`) AND the JVM `clojure -M:test` runner both pick it up.
  Plain CLJC; no DOM dependency."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.observability :as rf.observability]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            ;; The bracket is a macro defined in the `#?(:clj …)` arm of
            ;; `re-frame.test-support`, so JVM refers it directly and CLJS
            ;; reaches it through `:require-macros` below — the same shape
            ;; `with-trace-recorder!`'s consumers use.
            #?(:clj [re-frame.test-support :as rf.test-support
                     :refer [with-emit-recorder!]]
               :cljs [re-frame.test-support :as rf.test-support]))
  #?(:cljs (:require-macros [re-frame.test-support :refer [with-emit-recorder!]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                (rf.event-emit/clear-event-listeners!)
                (rf.error-emit/clear-error-listeners!)
                (rf.observability/clear-observability-sinks!))}))

(defn- reg-boom!
  "Register a handler on `frame-id` that throws when dispatched."
  [frame-id event-id]
  (rf/reg-event event-id
                {:frame frame-id}
                (fn [_ _] (throw (ex-info "boom" {:probe event-id})))))

;; ---------------------------------------------------------------------------
;; 1. The two tiers observe the SAME failure, each in its own shape.
;; ---------------------------------------------------------------------------

(deftest sink-and-bracket-observe-the-same-failure-in-their-own-shapes
  (testing "a frame-declared :errors sink and a bracketed error-emit listener
            both see ONE live handler exception. The sink's record is the
            PROJECTED :rf.observe/error; the bracket's is the RAW substrate
            record. Neither is a fallback for the other — the tiers are
            parallel, which is what makes retiring the public listener
            spelling safe."
    (let [sunk (atom [])]
      (rf/register-observability-sink! :test.sinks/sentry
                                       (fn [record] (swap! sunk conj record)))
      (rf/make-frame {:id :bracket/paired
                      :observability {:errors [{:sink :test.sinks/sentry}]}})
      (reg-boom! :bracket/paired :paired/boom)
      (with-emit-recorder! [raw]
        (rf/dispatch-sync [:paired/boom] {:frame :bracket/paired})
        (is (= 1 (count @raw))
            "the bracketed listener saw exactly one record")
        (is (= 1 (count @sunk))
            "the declared sink saw exactly one record")
        (let [r (first @raw)
              s (first @sunk)]
          ;; RAW shape: the substrate record is error-keyed and carries the
          ;; host throwable.
          (is (= :rf.error/handler-exception (:error r))
              "the raw record is keyed by :error")
          (is (some? (:exception r))
              "the raw record carries the host throwable")
          (is (= :bracket/paired (:frame r)))
          ;; PROJECTED shape: the sink record is kind-keyed.
          (is (= :rf.observe/error (:kind s))
              "the sink record is a canonical :rf.observe/error")
          (is (= :bracket/paired (:frame s)))
          ;; It is the SAME failure seen twice, not two failures: both
          ;; records name the same category.
          (is (= (:error r) (:error s))
              "both tiers report the same :rf.error/* category")
          ;; The shapes are genuinely different — the discriminator is the
          ;; record's own top-level key set. `:kind` is the projected
          ;; record's; the raw substrate record has never carried one.
          (is (not (contains? r :kind))
              "the raw record is NOT a projected record"))))))

;; ---------------------------------------------------------------------------
;; 2. The bracket captures where NO sink route exists.
;; ---------------------------------------------------------------------------

(deftest bracket-captures-under-an-empty-frame-policy
  (testing "a frame declaring an EMPTY :errors policy opts out of sink
            routing for that stream, so nothing is routed — and the bracket
            still captures the record, because it reads the substrate under
            the routing layer. This is the leg that makes the bracket usable
            in tests that assert on frames with no observability at all."
    (let [sunk (atom [])]
      (rf/register-observability-sink! :test.sinks/never
                                       (fn [record] (swap! sunk conj record)))
      (rf/make-frame {:id :bracket/empty
                      :observability {:errors []}})
      (reg-boom! :bracket/empty :empty/boom)
      (with-emit-recorder! [raw]
        (rf/dispatch-sync [:empty/boom] {:frame :bracket/empty})
        (is (= 1 (count @raw))
            "the bracket captured the record under an empty policy")
        (is (= :rf.error/handler-exception (:error (first @raw))))
        (is (zero? (count @sunk))
            "an empty policy routes NOTHING to any sink")))))

(deftest bracket-captures-with-no-observability-key-at-all
  (testing "a frame that declares no :observability key routes nothing (no
            :rf/default synthesis, no borrowed policy) — the bracket is the
            only observer, which is precisely the test-tier job the ruling
            kept these registries for."
    (rf/make-frame {:id :bracket/bare})
    (reg-boom! :bracket/bare :bare/boom)
    (with-emit-recorder! [raw]
      (rf/dispatch-sync [:bare/boom] {:frame :bracket/bare})
      (is (= 1 (count @raw)))
      (is (= :bare/boom (:event-id (first @raw)))))))

;; ---------------------------------------------------------------------------
;; 3. Leaving the bracket ends capture.
;; ---------------------------------------------------------------------------

(deftest leaving-the-bracket-unregisters
  (testing "the bracket unregisters in a `finally`, so a failure driven AFTER
            the body is not recorded. Without this the atom a test asserts on
            keeps growing under sibling tests in the same namespace."
    (rf/make-frame {:id :bracket/scoped})
    (reg-boom! :bracket/scoped :scoped/boom)
    (let [captured (with-emit-recorder! [raw]
                     (rf/dispatch-sync [:scoped/boom] {:frame :bracket/scoped})
                     raw)]
      (is (= 1 (count @captured)) "one record inside the bracket")
      ;; Same failure again, now outside the bracket.
      (rf/dispatch-sync [:scoped/boom] {:frame :bracket/scoped})
      (is (= 1 (count @captured))
          "the listener was unregistered on exit — the second failure is not
           captured"))))

(deftest bracket-unregisters-even-when-the-body-throws
  (testing "the `finally` holds when the body itself throws — otherwise one
            failing test leaks a listener into every test after it."
    (rf/make-frame {:id :bracket/throwing})
    (reg-boom! :bracket/throwing :throwing/boom)
    (let [escaped (atom nil)]
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (with-emit-recorder! [raw]
                     (reset! escaped raw)
                     (rf/dispatch-sync [:throwing/boom] {:frame :bracket/throwing})
                     (throw (ex-info "body blew up" {}))))
          "the body's exception propagates out of the bracket")
      ;; `escaped` holds the bracket's recording ATOM, so read through both.
      (is (= 1 (count @@escaped)) "the record captured before the throw is kept")
      (rf/dispatch-sync [:throwing/boom] {:frame :bracket/throwing})
      (is (= 1 (count @@escaped))
          "the listener was still unregistered on the exceptional path"))))

;; ---------------------------------------------------------------------------
;; 4. The `:events` arm, and `:pred`.
;; ---------------------------------------------------------------------------

(deftest events-arm-brackets-the-event-substrate
  (testing "`:stream :events` brackets `re-frame.event-emit` — one record per
            processed event, with the substrate's own `:outcome` spelling
            (the projected sink record spells the same value `:status`)."
    (rf/make-frame {:id :bracket/events})
    (rf/reg-event :ev/ok {:frame :bracket/events} (fn [{:keys [db]} _] {:db db}))
    (with-emit-recorder! [seen {:stream :events}]
      (rf/dispatch-sync [:ev/ok] {:frame :bracket/events})
      (is (= 1 (count @seen)))
      (let [r (first @seen)]
        (is (= :ev/ok (:event-id r)))
        (is (= :bracket/events (:frame r)))
        (is (= :ok (:outcome r))
            "the raw substrate record spells the dispatch result :outcome")))))

(deftest pred-filters-what-is-recorded
  (testing "`:pred` narrows the capture without a per-file wrapper — the
            option that stops each test growing its own filtering listener."
    (rf/make-frame {:id :bracket/pred})
    (rf/reg-event :pred/a {:frame :bracket/pred} (fn [{:keys [db]} _] {:db db}))
    (rf/reg-event :pred/b {:frame :bracket/pred} (fn [{:keys [db]} _] {:db db}))
    (with-emit-recorder! [seen {:stream :events
                                :pred   #(= :pred/b (:event-id %))}]
      (rf/dispatch-sync [:pred/a] {:frame :bracket/pred})
      (rf/dispatch-sync [:pred/b] {:frame :bracket/pred})
      (is (= [:pred/b] (mapv :event-id @seen))
          "only records matching :pred are conj'd"))))

(deftest two-brackets-in-one-test-do-not-collide
  (testing "the default key is gensym'd per expansion site, so nesting or
            sequencing two brackets does not have the second replace the
            first's registration."
    (rf/make-frame {:id :bracket/two})
    (reg-boom! :bracket/two :two/boom)
    (with-emit-recorder! [outer]
      (with-emit-recorder! [inner]
        (rf/dispatch-sync [:two/boom] {:frame :bracket/two})
        (is (= 1 (count @inner)) "the inner bracket captured"))
      (is (= 1 (count @outer))
          "the outer bracket captured the same record — the inner
           registration did not replace it"))))

;; ---------------------------------------------------------------------------
;; 5. The retired public spelling is gone.
;; ---------------------------------------------------------------------------

(deftest the-public-facade-no-longer-offers-the-always-on-streams
  (testing "rf2-kuky.69 — `(rf/register-listener! :errors …)` and its
            `:events` sibling throw `:rf.error/unknown-listener-stream`,
            and the refusal's `:valid` slot names the TWO raw dev streams
            that remain. This is the retirement itself, asserted from the
            public surface."
    (doseq [stream [:errors :events]]
      (let [e    (try (rf/register-listener! stream ::probe (fn [_]))
                      nil
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) ex
                        ex))
            data (ex-data e)]
        (is (some? e) (str stream " is refused"))
        (is (= :rf.error/unknown-listener-stream (:rf.error/id data)))
        (is (= #{:trace :epoch} (:valid data))
            "the closed vocabulary is the two raw dev streams")))))
