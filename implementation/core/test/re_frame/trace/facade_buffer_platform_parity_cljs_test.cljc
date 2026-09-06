(ns re-frame.trace.facade-buffer-platform-parity-cljs-test
  "rf2-kuky.51 — `rf/trace-buffer` and `rf/clear-trace-buffer!` exist on
  BOTH platforms.

  Both defs used to sit inside a `#?(:clj (do …))` reader conditional in
  `re-frame.core`, with a docstring reading \"JVM-only alias — CLJS callers
  use `re-frame.trace.tooling/trace-buffer` directly\". Nothing under
  `spec/` ever said so: Spec 009 §`trace-buffer` API tables the facade
  form, Tool-Pair §How AI tools attach names `(rf/trace-buffer frame-id)`
  as THE surface, and 22 normative occurrences spell it. An AI following
  the spec in a CLJS REPL got `nil` / an undeclared-var warning.

  The stated reason was production DCE, and the tree already refuted it:
  `re-frame.trace/trace-buffer` is an UNCONDITIONAL alias to the same
  `re-frame.trace.tooling` fn on both platforms, so the facade adds an
  alias of a shape every CLJS build already carries. `npm run
  test:bundle-isolation` (family `trace-tooling`, sentinel
  `trace-events`) is the proof, not the require graph.

  This suite is the platform witness the fence made impossible: it runs
  under `npm run test:cljs` (the shadow-cljs `:node-test` build picks up
  `*-cljs-test` namespaces) AND under the JVM `clojure -M:test` lane, and
  every assertion goes through the `rf/` facade. Before the fence lift
  the namespace does not even COMPILE on CLJS — `rf/trace-buffer` is an
  undeclared var — so a green CLJS run is itself the acceptance.

  Posture: dev-only, declared by `^:requires-debug` (rf2-d2841). The ring
  is never allocated under `-Dre-frame.debug=false` / `goog.DEBUG=false`,
  so an unguarded body would certify itself green over an empty stream.
  The namespace is still LOADED under the production-gate lane, so a
  load-time regression (the fence coming back) still reddens that job.

  Per Spec 009 §Per-frame trace rings (event-keyed, dev-only) and
  §`trace-buffer` API."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private frame-id :kuky-51/parity-frame)

(defn- run-one-event! []
  ;; `make-reset-runtime-fixture` clears listeners but NOT the per-frame
  ;; trace rings, which are keyed by frame-id and outlive `frames`. Clear
  ;; them here so each deftest reads exactly its own dispatch.
  (rf.trace.tooling/clear-trace-rings!)
  (rf/make-frame {:id frame-id})
  (rf/reg-event :kuky-51/ping
    (fn [{:keys [db]} _] {:db (assoc db :pinged? true)}))
  (rf/dispatch-sync [:kuky-51/ping] {:frame frame-id}))

;; ---------------------------------------------------------------------------
;; The facade reader resolves and reads the same ring as its tooling home.
;; ---------------------------------------------------------------------------

(deftest ^:requires-debug facade-trace-buffer-resolves-on-both-platforms
  (testing "rf/trace-buffer and rf/clear-trace-buffer! are bound vars on this platform"
    (is (fn? rf/trace-buffer)
        "rf/trace-buffer is a fn value here (not an undeclared var / nil)")
    (is (fn? rf/clear-trace-buffer!)
        "rf/clear-trace-buffer! is a fn value here")))

(deftest ^:requires-debug facade-trace-buffer-equals-the-tooling-home
  (testing "(rf/trace-buffer frame-id) returns the same event bundles as the tooling fn"
    (run-one-event!)
    (let [via-facade  (rf/trace-buffer frame-id)
          via-tooling (rf.trace.tooling/trace-buffer frame-id)]
      (is (seq via-facade)
          "the facade read is non-empty after one dispatch")
      (is (= via-facade via-tooling)
          "facade and tooling reads are the same value — one ring, two doors")
      (let [bundle (first via-facade)]
        (is (= [:kuky-51/ping] (:event bundle))
            "the bundle carries the dispatched event vector")
        (is (seq (:trace-events bundle))
            "the bundle carries a non-empty :trace-events slot")))))

(deftest ^:requires-debug facade-trace-buffer-honours-the-flat-opt
  (testing "(rf/trace-buffer frame-id {:flat true}) returns raw trace events"
    (run-one-event!)
    (let [flat (rf/trace-buffer frame-id {:flat true})]
      (is (seq flat)
          "the flat read is non-empty")
      (is (every? :operation flat)
          "every entry is a raw trace event (carries :operation)")
      (is (= flat (rf.trace.tooling/trace-buffer frame-id {:flat true}))
          "the opts arity agrees with the tooling home too"))))

(deftest ^:requires-debug facade-clear-trace-buffer-empties-the-ring
  (testing "(rf/clear-trace-buffer! frame-id) empties the named frame's ring"
    (run-one-event!)
    (is (seq (rf/trace-buffer frame-id))
        "precondition: the ring holds the dispatch")
    (rf/clear-trace-buffer! frame-id)
    (is (= [] (rf/trace-buffer frame-id))
        "the facade clear emptied the ring")
    (is (= [] (rf.trace.tooling/trace-buffer frame-id))
        "and the tooling home agrees — it is the same ring")))

(deftest ^:requires-debug facade-trace-buffer-is-empty-for-an-unknown-frame
  (testing "an unregistered frame reads [] rather than throwing"
    (rf.trace.tooling/clear-trace-rings!)
    (is (= [] (rf/trace-buffer :kuky-51/never-registered)))
    (is (nil? (rf/clear-trace-buffer! :kuky-51/never-registered)))))
