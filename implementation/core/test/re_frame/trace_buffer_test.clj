(ns re-frame.trace-buffer-test
  "Per-frame cascade-keyed trace ring tests (rf2-g1b2m spec + rf2-8uwce
  impl).

  Three deliverables in one suite:
    1. Per-frame ring: event-bundle reads, `:flat` opt, eviction by
       cascade, filter vocab, configure knob, clear, elision.
    2. `:rf.trace/dispatch-id` allocation + parent-dispatch-id linkage.
    3. `:origin` / `:source` opts ride trace events. Per rf2-1ve9h
       the prior parallel `:rf/dispatch-origin` axis was collapsed
       into `:source` — see `dispatch-source-*` deftests below.

  Per Spec 009 §Per-frame trace rings (cascade-keyed, dev-only) and
  §Dispatch correlation. JVM-only by intent — the trace + router
  machinery is platform-agnostic and CLJS adds no signal."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            [re-frame.trace.tooling :as trace-tooling]))

;; ---- fixtures --------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (trace-tooling/clear-trace-rings!)
  (trace/clear-frame-no-emit!)
  ;; Restore default events-retained between tests so a depth-tweaking
  ;; test does not bleed configuration into the next.
  (rf/configure! {:trace-buffer {:events-retained 50}})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- flat-events
  "Per-frame raw trace events for the named frame, oldest-first."
  ([frame-id] (rf/trace-buffer frame-id {:flat true}))
  ([frame-id opts] (rf/trace-buffer frame-id (assoc opts :flat true))))

(defn- dispatched-events
  "Filter raw events down to `:rf.event/dispatched` only."
  [evs]
  (filterv #(and (= :rf.event (:op-type %))
                 (= :rf.event/dispatched (:operation %)))
           evs))

;; ---- 1. Per-frame ring -----------------------------------------------------

(deftest trace-buffer-appends-events-as-cascades
  (testing "every emit lands in its frame's ring, grouped by :dispatch-id"
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db (assoc db :seen? true)}))
    (rf/dispatch-sync [:ping])
    (let [cascades (rf/trace-buffer :rf/default)]
      (is (vector? cascades) "trace-buffer returns a vector")
      (is (seq cascades) "ring has at least one cascade after a dispatch")
      (let [c (first cascades)]
        (is (number? (:dispatch-id c)) "cascade carries its :dispatch-id")
        (is (= :rf/default (:frame c)) "cascade carries the frame-id")
        (is (= [:ping] (:event c)) "cascade carries the event vector")
        (is (vector? (:trace-events c)) "cascade carries the raw events")
        (is (seq (:trace-events c)) "trace-events is non-empty")
        (is (some? (:dispatched c)) "cascade carries the :rf.event/dispatched event")))))

(deftest trace-buffer-flat-returns-raw-events
  (testing "{:flat true} returns the raw event stream"
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
    (rf/dispatch-sync [:ping])
    (let [evs (flat-events :rf/default)]
      (is (vector? evs))
      (is (seq evs))
      (is (some #(= :rf.event/dispatched (:operation %)) evs)
          "the :rf.event/dispatched trace lands in the flat stream"))))

;; ---- 1b. Cascade-keyed eviction ------------------------------------------

(deftest cascade-keyed-eviction
  (testing "ring evicts by CASCADE slot, not by raw event count"
    (rf/configure! {:trace-buffer {:events-retained 3}})
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
    (dotimes [_ 10] (rf/dispatch-sync [:ping]))
    (let [cascades (rf/trace-buffer :rf/default)]
      (is (= 3 (count cascades))
          (str "ring caps at 3 cascade slots; got " (count cascades))))))

(deftest cascade-burst-cannot-evict-prior-cascades
  (testing "a single cascade's burst of :rf.sub/skip-like noise can't displace OTHER cascades"
    (rf/configure! {:trace-buffer {:events-retained 5}})
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
    ;; Run 5 cascades; emit a small burst of synthetic events under
    ;; cascade #3 (simulating a sub-skip flood inside one event).
    (dotimes [_ 5] (rf/dispatch-sync [:ping]))
    (let [cs        (rf/trace-buffer :rf/default)
          target    (nth cs 2) ;; the middle one
          target-id (:dispatch-id target)]
      (binding [trace/*handler-scope*
                (trace/->HandlerScope nil nil target-id false false)]
        ;; Re-emit 200 trace events under the same in-flight cascade.
        ;; This pushes 200 events into one slot — but the slot count
        ;; stays at 5, so no other cascade is evicted.
        (dotimes [_ 200]
          (trace/emit! :rf.sub :rf.sub/skip
                       {:rf.sub/id :foo :frame :rf/default})))
      (let [cs-after (rf/trace-buffer :rf/default)]
        (is (= 5 (count cs-after))
            "still 5 cascades")
        (is (some #(= target-id (:dispatch-id %)) cs-after)
            "the targeted cascade is still present")
        ;; The targeted cascade's :trace-events grew without bound.
        (let [t (first (filter #(= target-id (:dispatch-id %)) cs-after))]
          (is (> (count (:trace-events t)) 200)
              "the cascade's events grew under the burst (cascade has no event-count cap)"))))))

;; ---- 1c. Per-frame isolation ---------------------------------------------

(deftest frame-isolation-cascade-bursts-dont-cross-rings
  (testing "a burst of cascades in one frame cannot evict cascades in another"
    (rf/configure! {:trace-buffer {:events-retained 3}})
    (rf/reg-frame :app/main {:doc "ordinary application frame"})
    (rf/reg-event :work (fn [{:keys [db]} _] {:db db}))
    (rf/dispatch-sync [:work] {:frame :app/main})
    (let [pre (rf/trace-buffer :app/main)]
      (is (= 1 (count pre))
          "single cascade in :app/main"))
    ;; Run a flood of cascades against :rf/default — the ring cap is 3
    ;; so :rf/default's ring rotates, but :app/main's ring stays intact.
    (dotimes [_ 50] (rf/dispatch-sync [:work]))
    (let [main-cs    (rf/trace-buffer :app/main)
          default-cs (rf/trace-buffer :rf/default)]
      (is (= 1 (count main-cs))
          ":app/main's ring is untouched by :rf/default's flood")
      (is (<= (count default-cs) 3)
          ":rf/default's ring stays within its slot cap"))))

;; ---- 1d. Frameless emits skip the ring (B3) ------------------------------

(deftest frameless-emits-bypass-the-ring
  (testing "trace events emitted OUTSIDE any cascade never land in any ring"
    ;; Emit with no handler-scope and no frame.
    (binding [trace/*handler-scope* nil]
      (trace/emit! :rf.registry :rf.registry/handler-registered
                   {:kind :event :id :synthetic/probe}))
    ;; The registration trace is frameless (no `:dispatch-id`) — it
    ;; should appear in NO frame's ring.
    (is (empty? (rf/trace-buffer :rf/default))
        ":rf/default's ring did NOT pick up the frameless emit")
    (rf/reg-frame :probe/scope {:doc "scope"})
    (is (empty? (rf/trace-buffer :probe/scope))
        ":probe/scope's ring did NOT pick up the frameless emit either")))

(deftest registration-emits-are-frameless
  (testing "registering a handler at top level is a frameless emit"
    (rf/clear-trace-buffer! :rf/default)
    ;; reg-event is a frameless emit — no in-flight cascade.
    (rf/reg-event :synthetic/probe (fn [{:keys [db]} _] {:db db}))
    (is (empty? (rf/trace-buffer :rf/default))
        "registration didn't grow any ring")))

;; ---- 1e. events-retained knob ------------------------------------------

(deftest per-frame-events-retained-override
  (testing ":rf.trace/events-retained on reg-frame applies per-frame"
    (rf/configure! {:trace-buffer {:events-retained 5}})
    (rf/reg-frame :tb/deep {:rf.trace/events-retained 200
                            :doc "deep diagnostics"})
    (rf/reg-frame :tb/shallow {:doc "shallow — default applies"})
    (rf/reg-event :tb/spam (fn [{:keys [db]} _] {:db db}))
    (dotimes [_ 100] (rf/dispatch-sync [:tb/spam] {:frame :tb/deep}))
    (dotimes [_ 100] (rf/dispatch-sync [:tb/spam] {:frame :tb/shallow}))
    (is (= 100 (count (rf/trace-buffer :tb/deep)))
        ":tb/deep retained all 100 cascades (200-deep ring)")
    (is (= 5 (count (rf/trace-buffer :tb/shallow)))
        ":tb/shallow capped at 5 (inherited the process-default of 5)")))

(deftest events-retained-zero-disables-retention
  (testing "{:events-retained 0} disables the ring; surface stays live"
    (rf/configure! {:trace-buffer {:events-retained 0}})
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
    (dotimes [_ 5] (rf/dispatch-sync [:ping]))
    (is (= [] (rf/trace-buffer :rf/default))
        "no cascades retained when retention is 0")
    ;; A registered listener still fires under depth=0.
    (let [recv (atom [])]
      (rf/register-listener! :trace ::probe (fn [ev] (swap! recv conj ev)))
      (rf/dispatch-sync [:ping])
      (rf/unregister-listener! :trace ::probe)
      (is (seq @recv)
          "live stream continues firing under events-retained 0"))))

;; ---- 1e-bis. Re-configuring the process default retunes inherited rings ---
;; (rf2-va65k finding 1)

(deftest configure-lowers-already-used-inherited-frame
  (testing "lowering the process default trims an ALREADY-USED inherited ring"
    ;; The frame's ring is allocated (it emitted cascades) and inherits
    ;; the process default — no per-frame override. Lowering the default
    ;; afterwards must retune + trim it. The pre-fix bug: every allocated
    ;; ring stores :events-retained, so the inherited/override guard was
    ;; always true and configure! silently skipped this ring.
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
    (dotimes [_ 10] (rf/dispatch-sync [:ping]))
    (is (= 10 (count (rf/trace-buffer :rf/default)))
        ":rf/default retained all 10 cascades at the default cap")
    (rf/configure! {:trace-buffer {:events-retained 1}})
    (is (<= (count (rf/trace-buffer :rf/default)) 1)
        "lowering the default trimmed the already-used inherited ring")
    ;; A subsequent emit also respects the lowered cap.
    (rf/dispatch-sync [:ping])
    (is (<= (count (rf/trace-buffer :rf/default)) 1)
        "post-reconfigure emits stay within the lowered cap")))

(deftest configure-retunes-inherited-but-preserves-override
  (testing "a re-configured default retunes inherited frames; explicit overrides survive"
    (rf/configure! {:trace-buffer {:events-retained 50}})
    ;; :tb/inherit takes the process default; :tb/pinned pins its own cap.
    (rf/reg-frame :tb/inherit {:doc "inherits the default"})
    (rf/reg-frame :tb/pinned  {:rf.trace/events-retained 8
                               :doc "explicit per-frame override"})
    (rf/reg-event :spam (fn [{:keys [db]} _] {:db db}))
    (dotimes [_ 10] (rf/dispatch-sync [:spam] {:frame :tb/inherit}))
    (dotimes [_ 10] (rf/dispatch-sync [:spam] {:frame :tb/pinned}))
    (is (= 10 (count (rf/trace-buffer :tb/inherit))))
    (is (= 8  (count (rf/trace-buffer :tb/pinned)))
        ":tb/pinned capped at its own override (8)")
    ;; Lower the process default: inherited frame is retuned, override is not.
    (rf/configure! {:trace-buffer {:events-retained 2}})
    (is (<= (count (rf/trace-buffer :tb/inherit)) 2)
        "inherited frame trimmed to the new default")
    (is (= 8 (count (rf/trace-buffer :tb/pinned)))
        "explicit per-frame override is NOT clobbered by the new default")))

(deftest configure-zero-disables-inherited-ring-only
  (testing "configure! 0 disables inherited rings but leaves overrides alone"
    (rf/reg-frame :tb/inherit {:doc "inherits"})
    (rf/reg-frame :tb/pinned  {:rf.trace/events-retained 4 :doc "override"})
    (rf/reg-event :spam (fn [{:keys [db]} _] {:db db}))
    (dotimes [_ 6] (rf/dispatch-sync [:spam] {:frame :tb/inherit}))
    (dotimes [_ 6] (rf/dispatch-sync [:spam] {:frame :tb/pinned}))
    (rf/configure! {:trace-buffer {:events-retained 0}})
    (is (= [] (rf/trace-buffer :tb/inherit))
        "inherited ring disabled by the new 0 default")
    (is (= 4 (count (rf/trace-buffer :tb/pinned)))
        "override frame keeps its retained cascades")))

;; ---- 1e-ter. Per-frame override registered BEFORE first emit -------------
;; (rf2-va65k finding 2)

(deftest per-frame-override-before-first-emit-does-not-crash
  (testing "reg-frame with a low cap BEFORE first dispatch survives a cap-exceeding burst"
    ;; Pre-fix: set-frame-events-retained! wrote a partial
    ;; {:events-retained N} map (no :cascade-order). push-to-ring!
    ;; started :cascade-order from nil → conj built a PersistentList →
    ;; subvec threw ClassCastException once the cap was exceeded.
    (rf/reg-frame :tb/early {:rf.trace/events-retained 3
                             :doc "cap registered before first emit"})
    (rf/reg-event :tb/ev (fn [{:keys [db]} _] {:db db}))
    ;; Four dispatches: the fourth pushes past the cap of 3.
    (is (nil? (dotimes [_ 4] (rf/dispatch-sync [:tb/ev] {:frame :tb/early})))
        "dispatch past the cap does not throw")
    (let [cs (rf/trace-buffer :tb/early)]
      (is (= 3 (count cs))
          "ring caps at the per-frame override of 3")
      ;; Oldest-first retained order: the FIRST cascade was evicted, so
      ;; the three retained dispatch-ids are strictly increasing and the
      ;; earliest retained is greater than the very first emitted id.
      (let [ids (mapv :dispatch-id cs)]
        (is (apply < ids) "retained cascades are in oldest-first order")
        (is (= ids (vec (sort ids))) "oldest-first preserved after eviction")))))

(deftest per-frame-override-zero-before-first-emit-disables-cleanly
  (testing "a 0 per-frame override before first emit disables the ring without crashing"
    (rf/reg-frame :tb/silent {:rf.trace/events-retained 0 :doc "disabled"})
    (rf/reg-event :tb/ev (fn [{:keys [db]} _] {:db db}))
    (is (nil? (dotimes [_ 5] (rf/dispatch-sync [:tb/ev] {:frame :tb/silent})))
        "dispatches against a 0-cap override frame do not throw")
    (is (= [] (rf/trace-buffer :tb/silent))
        "0-cap override retains nothing")))

;; ---- 1f. clear-trace-buffer! and frame-destroy --------------------------

(deftest clear-trace-buffer-clears-only-the-named-frame
  (testing "clear-trace-buffer! is per-frame"
    (rf/reg-frame :app/a {:doc "a"})
    (rf/reg-frame :app/b {:doc "b"})
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
    (rf/dispatch-sync [:ping] {:frame :app/a})
    (rf/dispatch-sync [:ping] {:frame :app/b})
    (is (seq (rf/trace-buffer :app/a)))
    (is (seq (rf/trace-buffer :app/b)))
    (rf/clear-trace-buffer! :app/a)
    (is (= [] (rf/trace-buffer :app/a)) ":app/a's ring is empty")
    (is (seq (rf/trace-buffer :app/b)) ":app/b's ring is intact")))

(deftest frame-destroy-clears-the-rings
  (testing "destroy-frame! releases the destroyed frame's ring"
    (rf/reg-frame :app/transient {:doc "short-lived"})
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
    (rf/dispatch-sync [:ping] {:frame :app/transient})
    (is (seq (rf/trace-buffer :app/transient))
        "ring has content before destroy")
    (frame/destroy-frame! :app/transient)
    (is (= [] (rf/trace-buffer :app/transient))
        "ring is empty after frame destroy")))

(deftest read-against-unknown-frame-returns-empty
  (testing "trace-buffer for a never-registered frame returns []"
    (is (= [] (rf/trace-buffer :no-such-frame)))
    (is (= [] (rf/trace-buffer :no-such-frame {:flat true})))))

;; ---- 1g. Filter vocabulary (event-level, :flat true) ---------------------

(deftest trace-buffer-filter-operation
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ping])
  (let [dispatched (flat-events :rf/default {:operation :rf.event/dispatched})]
    (is (seq dispatched))
    (is (every? #(= :rf.event/dispatched (:operation %)) dispatched))))

(deftest trace-buffer-filter-op-type
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ping])
  (let [event-only (flat-events :rf/default {:op-type :rf.event})]
    (is (seq event-only))
    (is (every? #(= :rf.event (:op-type %)) event-only))))

(deftest trace-buffer-filter-since
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ping])
  (let [pre-id (-> (flat-events :rf/default) last :id)]
    (rf/dispatch-sync [:ping])
    (let [after (flat-events :rf/default {:since pre-id})]
      (is (seq after))
      (is (every? #(> (:id %) pre-id) after)))))

(deftest trace-buffer-filter-severity
  (testing ":severity :error narrows to :op-type :error events"
    (rf/dispatch-sync [:no-such-event-handler])
    (let [errs (flat-events :rf/default {:severity :error})]
      (is (seq errs))
      (is (every? #(= :error (:op-type %)) errs)))))

(deftest trace-buffer-filter-event-id
  (rf/reg-event :ev/alpha (fn [{:keys [db]} _] {:db db}))
  (rf/reg-event :ev/beta  (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ev/alpha])
  (rf/dispatch-sync [:ev/beta])
  (let [alpha (flat-events :rf/default {:event-id :ev/alpha})]
    (is (seq alpha))
    (is (every? #(= :ev/alpha (get-in % [:tags :rf.trace/event-id])) alpha))))

(deftest trace-buffer-filter-handler-id
  (rf/reg-event :ev/throws (fn [{:keys [db]} _] {:db (throw (ex-info "boom" {}))}))
  (try (rf/dispatch-sync [:ev/throws]) (catch Throwable _ nil))
  (let [hits (flat-events :rf/default {:handler-id :ev/throws})]
    (is (seq hits))
    (is (every? #(= :ev/throws (get-in % [:tags :handler-id])) hits))))

(deftest trace-buffer-filter-source
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ping] {:source :repl})
  (rf/dispatch-sync [:ping] {:source :after-timer})
  (let [repl-evs (flat-events :rf/default {:source :repl})]
    (is (seq repl-evs))
    (is (every? #(= :repl (or (:source %) (get-in % [:tags :source])))
                repl-evs))))

(deftest trace-buffer-filter-origin
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ping] {:origin :pair})
  (rf/dispatch-sync [:ping] {:origin :story})
  (let [pair-evs (flat-events :rf/default {:origin :pair})]
    (is (seq pair-evs))
    (is (every? #(= :pair (get-in % [:tags :rf.event/origin])) pair-evs))))

(deftest trace-buffer-filter-dispatch-id-flat
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ping])
  (rf/dispatch-sync [:ping])
  (let [first-dispatch (->> (flat-events :rf/default)
                            dispatched-events
                            first)
        target-id      (get-in first-dispatch [:tags :rf.trace/dispatch-id])
        slice          (flat-events :rf/default {:dispatch-id target-id})]
    (is (number? target-id))
    (is (seq slice))
    (is (every? #(= target-id (get-in % [:tags :rf.trace/dispatch-id])) slice))))

(deftest trace-buffer-filter-since-ms
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ping])
  (let [pre-time (-> (flat-events :rf/default) last :time)]
    (Thread/sleep 5)
    (rf/dispatch-sync [:ping])
    (let [after (flat-events :rf/default {:since-ms pre-time})]
      (is (seq after))
      (is (every? #(> (:time %) pre-time) after)))))

(deftest trace-buffer-filter-between
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ping])
  (let [t0 (-> (flat-events :rf/default) first :time)
        t1 (-> (flat-events :rf/default) last  :time)
        win (flat-events :rf/default {:between [t0 t1]})]
    (is (seq win))
    (is (every? #(<= t0 (:time %) t1) win)))
  (let [win (flat-events :rf/default {:between [0 1]})]
    (is (= [] win))))

(deftest trace-buffer-filter-sensitive
  (testing ":sensitive? filter matches top-level slot"
    (rf/reg-event :ev/x {:rf/sensitive? true} (fn [{:keys [db]} _] {:db db}))
    (rf/reg-event :ev/y (fn [{:keys [db]} _] {:db db}))
    (rf/dispatch-sync [:ev/x])
    (rf/dispatch-sync [:ev/y])
    (let [sens   (flat-events :rf/default {:sensitive? true})
          plain  (flat-events :rf/default {:sensitive? false})]
      (is (seq sens) "at least one sensitive event")
      (is (every? #(true? (:sensitive? %)) sens))
      (is (seq plain) "at least one non-sensitive event")
      (is (every? #(not (true? (:sensitive? %))) plain)))))

(deftest trace-buffer-filter-pred
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ping])
  (let [evs (flat-events :rf/default
                         {:pred (fn [ev] (#{:rf.event :error} (:op-type ev)))})]
    (is (seq evs))
    (is (every? #(#{:rf.event :error} (:op-type %)) evs))))

;; ---- 1h. Cascade-bundle filters ------------------------------------------

(deftest trace-buffer-cascade-filter-event-id
  (rf/reg-event :ev/alpha (fn [{:keys [db]} _] {:db db}))
  (rf/reg-event :ev/beta  (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ev/alpha])
  (rf/dispatch-sync [:ev/beta])
  (let [alpha (rf/trace-buffer :rf/default {:event-id :ev/alpha})]
    (is (seq alpha))
    (is (every? #(= :ev/alpha (first (:event %))) alpha))))

(deftest trace-buffer-cascade-filter-dispatch-id
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ping])
  (rf/dispatch-sync [:ping])
  (let [cs        (rf/trace-buffer :rf/default)
        target-id (:dispatch-id (first cs))
        narrowed  (rf/trace-buffer :rf/default {:dispatch-id target-id})]
    (is (= 1 (count narrowed))
        ":dispatch-id narrows event-bundle reads to one cascade")
    (is (= target-id (:dispatch-id (first narrowed))))))

(deftest trace-buffer-cascade-filter-origin
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ping] {:origin :pair})
  (rf/dispatch-sync [:ping] {:origin :story})
  (let [pair (rf/trace-buffer :rf/default {:origin :pair})]
    (is (seq pair))
    (is (every? #(= :pair (get-in % [:dispatched :tags :rf.event/origin])) pair))))

;; ---- 2. :dispatch-id correlation -----------------------------------------

(deftest dispatch-id-allocated-on-every-dispatch
  (testing "every :rf.event/dispatched trace carries a numeric :rf.trace/dispatch-id"
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
    (rf/dispatch-sync [:ping])
    (rf/dispatch-sync [:ping])
    (let [evs (dispatched-events (flat-events :rf/default))
          ids (map #(get-in % [:tags :rf.trace/dispatch-id]) evs)]
      (is (seq evs))
      (is (every? some? ids))
      (is (every? number? ids))
      (is (= (count (distinct ids)) (count ids))))))

(deftest top-level-dispatch-has-no-parent
  (rf/reg-event :standalone (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:standalone])
  (let [ev (->> (flat-events :rf/default)
                dispatched-events
                (filter #(= [:standalone] (get-in % [:tags :rf.event/v])))
                first)]
    (is ev)
    (is (nil? (get-in ev [:tags :rf.trace/parent-dispatch-id])))))

(deftest fx-dispatch-inherits-parent-dispatch-id
  (rf/reg-event :outer (fn [_ _] {:fx [[:dispatch [:inner]]]}))
  (rf/reg-event :inner (fn [{:keys [db]} _] {:db (assoc db :inner? true)}))
  (rf/dispatch-sync [:outer])
  (let [evs   (dispatched-events (flat-events :rf/default))
        outer (->> evs
                   (filter #(= [:outer] (get-in % [:tags :rf.event/v])))
                   first)
        inner (->> evs
                   (filter #(= [:inner] (get-in % [:tags :rf.event/v])))
                   first)]
    (is outer)
    (is inner)
    (is (= (get-in outer [:tags :rf.trace/dispatch-id])
           (get-in inner [:tags :rf.trace/parent-dispatch-id])))))

;; ---- 3. :origin opt -------------------------------------------------------

(deftest origin-defaults-to-app
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ping])
  (let [ev (->> (flat-events :rf/default)
                dispatched-events
                (filter #(= [:ping] (get-in % [:tags :rf.event/v])))
                first)]
    (is ev)
    (is (= :app (get-in ev [:tags :rf.event/origin])))))

(deftest origin-opt-overrides-default
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ping] {:origin :pair})
  (let [ev (->> (flat-events :rf/default)
                dispatched-events
                (filter #(= [:ping] (get-in % [:tags :rf.event/v])))
                first)]
    (is ev)
    (is (= :pair (get-in ev [:tags :rf.event/origin])))))

;; ---- 4. :source opt (post-rf2-1ve9h — collapsed from :rf/dispatch-origin)

(deftest dispatch-source-defaults-to-unknown
  ;; Per rf2-hxj0d the default `:source` is `:unknown` (the un-stamped
  ;; dispatch site). Per rf2-1ve9h the prior parallel
  ;; `:rf/dispatch-origin :user` default was collapsed — the single
  ;; closed-enum functional-origin axis is now `:source`.
  ;;
  ;; `:source` is hoisted as a top-level slot on every trace event
  ;; (see `re-frame.trace/build-event` — Spec 009 §Core fields hoist
  ;; contract), not stamped under `:tags`. Filters that key on
  ;; `:source` should read the top-level slot first.
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ping])
  (let [ev (->> (flat-events :rf/default)
                dispatched-events
                (filter #(= [:ping] (get-in % [:tags :rf.event/v])))
                first)]
    (is ev)
    (is (= :unknown (:source ev)))
    (is (nil? (get-in ev [:tags :rf/dispatch-origin]))
        ":rf/dispatch-origin retired per rf2-1ve9h")))

(deftest dispatch-source-opt-overrides-default
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:ping] {:source :tool})
  (let [ev (->> (flat-events :rf/default)
                dispatched-events
                (filter #(= [:ping] (get-in % [:tags :rf.event/v])))
                first)]
    (is ev)
    (is (= :tool (:source ev)))))

(deftest dispatch-source-fx-cascade-stamps-fx-dispatch
  (testing "child dispatches emitted by :dispatch fx are tagged :fx-dispatch"
    ;; Per rf2-c3990: a `:dispatch` fx from a non-machine parent stamps
    ;; `:source :fx-dispatch` on the child envelope (the actor-message
    ;; path stamps `:machine-action` instead). Per rf2-1ve9h these are
    ;; the surviving axes after the `:rf/dispatch-origin` collapse.
    (rf/reg-event :parent (fn [_ _] {:fx [[:dispatch [:child]]]}))
    (rf/reg-event :child (fn [{:keys [db]} _] {:db db}))
    (rf/dispatch-sync [:parent] {:source :ui})
    (let [parent-ev (->> (flat-events :rf/default)
                         dispatched-events
                         (filter #(= [:parent] (get-in % [:tags :rf.event/v])))
                         first)
          child-ev  (->> (flat-events :rf/default)
                         dispatched-events
                         (filter #(= [:child] (get-in % [:tags :rf.event/v])))
                         first)]
      (is parent-ev)
      (is child-ev)
      (is (= :ui          (:source parent-ev)))
      (is (= :fx-dispatch (:source child-ev))))))

;; ---- 5. Frame-level trace-emission gate (rf2-2qaqh) ----------------------

(deftest tool-frame-emits-no-trace
  (testing "a frame registered :rf.trace/frame-no-emit? true grows the ring by 0"
    (rf/reg-frame :tool/inspector {:rf.trace/frame-no-emit? true})
    (rf/reg-event :tool/work (fn [{:keys [db]} _] {:db (assoc db :ran? true)}))
    (rf/clear-trace-buffer! :tool/inspector)
    (rf/dispatch-sync [:tool/work] {:frame :tool/inspector})
    (is (= [] (rf/trace-buffer :tool/inspector))
        "no trace event from a trace-disabled frame's cascade reaches the ring")))

(deftest app-frame-emits-trace-while-tool-frame-silent
  (testing "an app frame's cascade DOES grow its ring; the tool frame's does NOT"
    (rf/reg-frame :tool/inspector {:rf.trace/frame-no-emit? true})
    (rf/reg-frame :app/main {:doc "ordinary application frame"})
    (rf/reg-event :work (fn [{:keys [db]} _] {:db (assoc db :ran? true)}))
    (rf/clear-trace-buffer! :app/main)
    (rf/clear-trace-buffer! :tool/inspector)
    (dotimes [_ 20] (rf/dispatch-sync [:work] {:frame :tool/inspector}))
    (rf/dispatch-sync [:work] {:frame :app/main})
    (is (seq (rf/trace-buffer :app/main))
        "app-frame cascade produced a trace cascade in its own ring")
    (is (= [] (rf/trace-buffer :tool/inspector))
        "tool-frame's ring stays empty")))

(deftest destroy-clears-trace-disabled-flag
  ;; rf2-zcl055: `reg-frame` adds a `:rf.trace/frame-no-emit? true` frame to
  ;; the process-global trace-disabled set; `destroy-frame!` must remove it
  ;; (the teardown counterpart to `set-frame-no-emit!`, symmetric with the
  ;; per-frame trace-ring release). Without the fix the destroyed frame's id
  ;; lingers permanently in `trace/trace-disabled-frames` — a process-global
  ;; id leak (one keyword per destroyed-and-never-re-registered tool frame).
  (testing "a destroyed trace-disabled frame leaves no entry in the trace-disabled set"
    (rf/reg-frame :tool/inspector {:rf.trace/frame-no-emit? true})
    (is (trace/frame-trace-disabled? :tool/inspector)
        "reg-frame marked the tool frame trace-disabled")
    (frame/destroy-frame! :tool/inspector)
    (is (not (trace/frame-trace-disabled? :tool/inspector))
        "destroy-frame! removed the frame id from trace-disabled-frames (no process-global leak)"))
  (testing "destroying one tool frame does not disturb another's trace-disabled flag"
    (rf/reg-frame :tool/a {:rf.trace/frame-no-emit? true})
    (rf/reg-frame :tool/b {:rf.trace/frame-no-emit? true})
    (frame/destroy-frame! :tool/a)
    (is (not (trace/frame-trace-disabled? :tool/a))
        "destroyed frame's flag is cleared")
    (is (trace/frame-trace-disabled? :tool/b)
        "the surviving tool frame's flag is untouched")))

;; ---- 6. B4 hot-reload dedup-by-shape ------------------------------------

(deftest hot-reload-unchanged-handler-emits-zero-traces
  (testing "re-registering an identical handler emits ZERO traces (B4 dedup)"
    (let [handler-fn (fn [{:keys [db]} _] {:db db})]
      (rf/reg-event :ev/hot {:doc "hot"} handler-fn)
      (rf/clear-trace-buffer! :rf/default)
      ;; Identical re-registration — same fn, same meta. B4: zero emits.
      (rf/reg-event :ev/hot {:doc "hot"} handler-fn)
      (rf/reg-event :ev/hot {:doc "hot"} handler-fn)
      (rf/reg-event :ev/hot {:doc "hot"} handler-fn)
      (is (= [] (rf/trace-buffer :rf/default {:flat true
                                              :op-type :rf.registry}))
          "no :rf.registry/* traces from idempotent hot-reload"))))

(deftest hot-reload-changed-handler-emits-one-trace
  (testing "re-registering a CHANGED handler emits exactly one :rf.registry/handler-replaced"
    (let [recv (atom [])]
      (rf/register-listener! :trace ::probe
                             (fn [ev]
                               (when (= :rf.registry/handler-replaced
                                        (:operation ev))
                                 (swap! recv conj ev))))
      (rf/reg-event :ev/hot {:doc "v1"} (fn [{:keys [db]} _] {:db (assoc db :v 1)}))
      (reset! recv [])
      ;; Real edit — different handler-fn body.
      (rf/reg-event :ev/hot {:doc "v1"} (fn [{:keys [db]} _] {:db (assoc db :v 2)}))
      (rf/unregister-listener! :trace ::probe)
      (is (= 1 (count @recv))
          "exactly one :rf.registry/handler-replaced emitted on real edit")
      (is (= :ev/hot (-> @recv first :tags :id))))))

(deftest hot-reload-dedup-clears-on-reset
  (testing "clear-listeners! (and clear-trace-rings!) reset the dedup table"
    (let [handler-fn (fn [{:keys [db]} _] {:db db})
          recv (atom [])]
      ;; Register, then identical re-register — dedup suppresses.
      (rf/reg-event :ev/cycle {:doc "doc"} handler-fn)
      (rf/reg-event :ev/cycle {:doc "doc"} handler-fn) ;; suppressed
      ;; Reset dedup state, then re-register the SAME handler — should
      ;; now emit again because the table is fresh.
      (rf/clear-listeners! :trace)
      (rf/register-listener! :trace ::probe
                             (fn [ev]
                               (when (and (= :rf.registry (:op-type ev))
                                          (= :ev/cycle (-> ev :tags :id)))
                                 (swap! recv conj ev))))
      (rf/reg-event :ev/cycle {:doc "doc"} handler-fn)
      (rf/unregister-listener! :trace ::probe)
      (is (seq @recv)
          "post-clear re-registration emits at least one registry trace"))))

(deftest hot-reload-dedup-per-kind-id
  (testing "dedup is per-(kind, id) — separate ids don't interfere"
    (let [recv (atom [])]
      (rf/register-listener! :trace ::probe
                             (fn [ev]
                               (when (= :rf.registry (:op-type ev))
                                 (swap! recv conj ev))))
      (rf/reg-event :a {:doc "a"} (fn [{:keys [db]} _] {:db db}))
      (rf/reg-event :b {:doc "b"} (fn [{:keys [db]} _] {:db db}))
      (rf/unregister-listener! :trace ::probe)
      ;; Different ids never share dedup state — both initial
      ;; registrations are first-time emits.
      (let [a-emits (filter #(= :a (-> % :tags :id)) @recv)
            b-emits (filter #(= :b (-> % :tags :id)) @recv)]
        (is (seq a-emits) "first :a registration emits")
        (is (seq b-emits) "first :b registration emits")))))
