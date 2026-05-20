(ns re-frame.trace-buffer-test
  "Spec 009 §Retain-N trace ring buffer + §Dispatch correlation, plus
  Spec 002 §Dispatch origin tagging.

  rf2-smee — three deliverables in one suite:
    1. Trace ring buffer: append, filter, depth/eviction, clear, elision.
    2. :dispatch-id allocation + :parent-dispatch-id linkage (top-level
       dispatches have no parent; dispatches issued from within an fx
       handler inherit the in-flight event's :dispatch-id).
    3. :origin opt: defaults to :app, opt overrides to anything else,
       lands on the :event/dispatched trace under :tags :origin.

  JVM-only by intent — the trace + router machinery is platform-agnostic
  and CLJS adds no signal."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            ;; rf2-qwm0a — load the tooling sibling so the late-bind
            ;; hooks behind `trace/clear-trace-listeners!` / `rf/trace-buffer`
            ;; / `rf/configure :trace-buffer` / etc. are registered.
            [re-frame.trace.tooling]))

;; ---- fixtures --------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-listeners!)
  (rf/clear-trace-buffer!)
  ;; Restore default depth between tests so a depth-tweaking test does
  ;; not bleed configuration into the next.
  (rf/configure :trace-buffer {:depth 200})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers ---------------------------------------------------------------

(defn- dispatched-events
  "Return only the :event :event/dispatched events from a buffer/coll."
  [evs]
  (filterv #(and (= :event (:op-type %))
                 (= :event/dispatched (:operation %)))
           evs))

;; ---- 1. Ring buffer --------------------------------------------------------

(deftest trace-buffer-appends-events
  (testing "every emit! lands in the ring buffer"
    (rf/reg-event-db :ping (fn [db _] (assoc db :seen? true)))
    (rf/dispatch-sync [:ping])
    (let [buf (rf/trace-buffer)]
      (is (vector? buf) "trace-buffer returns a vector")
      (is (seq buf) "buffer has entries after a dispatch")
      ;; The :event/dispatched envelope is the most reliable signal.
      (is (some #(= :event/dispatched (:operation %)) buf)
          "the :event/dispatched trace lands in the buffer"))))

(deftest trace-buffer-filters
  (testing "filter by :operation, :op-type, :since, :frame compose"
    (rf/reg-event-db :ping (fn [db _] db))
    (rf/dispatch-sync [:ping])
    (rf/dispatch-sync [:ping])
    (let [all       (rf/trace-buffer)
          dispatched (rf/trace-buffer {:operation :event/dispatched})]
      (is (seq dispatched))
      (is (every? #(= :event/dispatched (:operation %)) dispatched)
          ":operation filter narrows to one operation")
      (is (<= (count dispatched) (count all)))
      (let [event-only (rf/trace-buffer {:op-type :event})]
        (is (every? #(= :event (:op-type %)) event-only)
            ":op-type filter narrows to the discriminator")))
    (testing ":since filters strictly greater than the given id"
      (let [pre-id (-> (rf/trace-buffer) last :id)]
        (rf/dispatch-sync [:ping])
        (let [after (rf/trace-buffer {:since pre-id})]
          (is (seq after))
          (is (every? #(> (:id %) pre-id) after)))))
    (testing ":frame filter matches via :tags :frame"
      ;; Both default-frame and a named frame produce events; the named
      ;; frame's events should be the only match.
      (rf/reg-frame :tb/scope {:doc "scoped frame"})
      (rf/clear-trace-buffer!)
      (rf/reg-event-db :scoped (fn [db _] db))
      (rf/dispatch-sync [:scoped] {:frame :tb/scope})
      (rf/dispatch-sync [:ping])
      (let [scoped (rf/trace-buffer {:frame :tb/scope})]
        (is (seq scoped))
        (is (every? #(= :tb/scope
                        (or (:frame %) (get-in % [:tags :frame])))
                    scoped))))
    (testing "filters compose"
      (let [combo (rf/trace-buffer {:operation :event/dispatched
                                    :op-type   :event})]
        (is (every? #(and (= :event/dispatched (:operation %))
                          (= :event (:op-type %)))
                    combo))))))

(deftest trace-buffer-respects-depth
  (testing "configure :trace-buffer {:depth N} caps the slot count"
    (rf/configure :trace-buffer {:depth 5})
    (rf/reg-event-db :spam (fn [db _] db))
    (dotimes [_ 30] (rf/dispatch-sync [:spam]))
    (let [buf (rf/trace-buffer)]
      (is (<= (count buf) 5)
          (str "buffer should not exceed configured depth; got " (count buf)))
      (is (pos? (count buf))
          "buffer should still have the most recent slots populated")))
  (testing "depth=0 disables the buffer"
    (rf/configure :trace-buffer {:depth 0})
    (rf/clear-trace-buffer!)
    (rf/reg-event-db :spam (fn [db _] db))
    (dotimes [_ 5] (rf/dispatch-sync [:spam]))
    (is (= [] (rf/trace-buffer))
        "with depth 0, no events accumulate")))

(deftest trace-buffer-clear
  (testing "clear-trace-buffer! empties the buffer"
    (rf/reg-event-db :ping (fn [db _] db))
    (rf/dispatch-sync [:ping])
    (is (seq (rf/trace-buffer)))
    (rf/clear-trace-buffer!)
    (is (= [] (rf/trace-buffer)))))

(deftest trace-buffer-rides-debug-flag
  (testing "the buffer machinery is wrapped in interop/debug-enabled?"
    ;; This is a structural check rather than a runtime simulation: the
    ;; production-elision contract is that no buffer state mutates when
    ;; the flag is false at compile time. We assert the source contains
    ;; the gate at the right call sites; combined with the existing
    ;; trace-test envelope coverage, this protects the elision contract.
    ;;
    ;; Per rf2-qwm0a the buffer + filter-predicate body live in
    ;; re-frame.trace.tooling (the sibling ns split off so production
    ;; counter bundles DCE them); the `re-frame.trace/trace-buffer` etc.
    ;; wrappers delegate through late-bind hooks and return [] when the
    ;; tooling ns is absent. The gate check lives in the tooling source.
    (let [src (slurp "src/re_frame/trace/tooling.cljc")]
      (is (re-find #"\(defn-? push-to-buffer![\s\S]*?interop/debug-enabled\?" src)
          "push-to-buffer! is gated on interop/debug-enabled?")
      (is (re-find #"\(defn-? trace-buffer[\s\S]*?interop/debug-enabled\?" src)
          "trace-buffer reader returns [] under the same gate")
      (is (re-find #"\(defn-? clear-trace-buffer![\s\S]*?interop/debug-enabled\?" src)
          "clear-trace-buffer! is gated")
      (is (re-find #"\(defn-? configure-trace-buffer![\s\S]*?interop/debug-enabled\?" src)
          "configure-trace-buffer! is gated"))))

;; ---- 2. :dispatch-id correlation -------------------------------------------

(deftest dispatch-id-allocated-on-every-dispatch
  (testing "every :event/dispatched trace carries a numeric :dispatch-id under :tags"
    (rf/reg-event-db :ping (fn [db _] db))
    (rf/dispatch-sync [:ping])
    (rf/dispatch-sync [:ping])
    (let [evs (dispatched-events (rf/trace-buffer))
          ids (map #(get-in % [:tags :dispatch-id]) evs)]
      (is (seq evs))
      (is (every? some? ids)
          "every :event/dispatched has a :dispatch-id")
      (is (every? number? ids)
          ":dispatch-id values are numeric (counter-shaped)")
      (is (= (count (distinct ids)) (count ids))
          ":dispatch-id values are unique within a process"))))

(deftest top-level-dispatch-has-no-parent
  (testing "a dispatch issued from outside any in-flight event has no :parent-dispatch-id"
    (rf/reg-event-db :standalone (fn [db _] db))
    (rf/dispatch-sync [:standalone])
    (let [ev (->> (rf/trace-buffer)
                  dispatched-events
                  (filter #(= [:standalone] (get-in % [:tags :event])))
                  first)]
      (is ev "the :event/dispatched trace was emitted")
      (is (nil? (get-in ev [:tags :parent-dispatch-id]))
          "top-level dispatch has no :parent-dispatch-id"))))

(deftest fx-dispatch-inherits-parent-dispatch-id
  (testing "a dispatch issued from inside an event's fx walk inherits the parent's :dispatch-id"
    (rf/reg-event-fx :outer (fn [_ _]
                              {:fx [[:dispatch [:inner]]]}))
    (rf/reg-event-db :inner (fn [db _] (assoc db :inner? true)))
    (rf/dispatch-sync [:outer])
    (let [evs   (dispatched-events (rf/trace-buffer))
          outer (->> evs
                     (filter #(= [:outer] (get-in % [:tags :event])))
                     first)
          inner (->> evs
                     (filter #(= [:inner] (get-in % [:tags :event])))
                     first)]
      (is outer "outer :event/dispatched present")
      (is inner "inner :event/dispatched present")
      (is (number? (get-in outer [:tags :dispatch-id])))
      (is (nil?    (get-in outer [:tags :parent-dispatch-id]))
          "outer (top-level) has no parent")
      (is (= (get-in outer [:tags :dispatch-id])
             (get-in inner [:tags :parent-dispatch-id]))
          "inner's :parent-dispatch-id == outer's :dispatch-id"))))

;; ---- 1b. Extended filter vocabulary (rf2-97ah0) ----------------------------

(deftest trace-buffer-filter-severity
  (testing ":severity filters by :op-type tier (:error / :warning / :info)"
    ;; :rf.error/no-such-handler is a reliable :op-type :error emit.
    (rf/dispatch-sync [:no-such-event-handler])
    (let [errs (rf/trace-buffer {:severity :error})]
      (is (seq errs))
      (is (every? #(= :error (:op-type %)) errs)
          ":severity :error narrows to :op-type :error events"))
    (testing ":severity :warning narrows to :op-type :warning"
      (let [warns (rf/trace-buffer {:severity :warning})]
        (is (every? #(= :warning (:op-type %)) warns)
            (if (seq warns)
              ":severity :warning narrows correctly"
              ":severity :warning returns empty when no warnings"))))
    (testing ":severity composes with :frame"
      (rf/reg-frame :tb/sev-scope {:doc "severity-scope"})
      (rf/clear-trace-buffer!)
      (rf/dispatch-sync [:no-such-event-handler] {:frame :tb/sev-scope})
      (let [scoped (rf/trace-buffer {:severity :error :frame :tb/sev-scope})]
        (is (every? #(and (= :error (:op-type %))
                          (= :tb/sev-scope (or (:frame %)
                                               (get-in % [:tags :frame]))))
                    scoped))))))

(deftest trace-buffer-filter-event-id
  (testing ":event-id filters by :tags :event-id"
    (rf/reg-event-db :ev/alpha (fn [db _] db))
    (rf/reg-event-db :ev/beta  (fn [db _] db))
    (rf/dispatch-sync [:ev/alpha])
    (rf/dispatch-sync [:ev/beta])
    (let [alpha (rf/trace-buffer {:event-id :ev/alpha})]
      (is (seq alpha))
      (is (every? #(= :ev/alpha (get-in % [:tags :event-id])) alpha)
          ":event-id filter narrows to one event-id"))
    (let [beta (rf/trace-buffer {:event-id :ev/beta})]
      (is (seq beta))
      (is (every? #(= :ev/beta (get-in % [:tags :event-id])) beta)))))

(deftest trace-buffer-filter-handler-id
  (testing ":handler-id filters by :tags :handler-id"
    ;; :rf.error/handler-exception carries :handler-id under :tags.
    (rf/reg-event-db :ev/throws
                     (fn [_db _] (throw (ex-info "boom" {}))))
    (try
      (rf/dispatch-sync [:ev/throws])
      (catch Throwable _ nil))
    (let [hits (rf/trace-buffer {:handler-id :ev/throws})]
      (is (seq hits) "at least one event carries :handler-id :ev/throws")
      (is (every? #(= :ev/throws (get-in % [:tags :handler-id])) hits)))))

(deftest trace-buffer-filter-source
  (testing ":source filters by top-level :source slot (hoisted from :tags)"
    (rf/reg-event-db :ping (fn [db _] db))
    (rf/dispatch-sync [:ping] {:source :repl})
    (rf/dispatch-sync [:ping] {:source :timer})
    (let [repl-evs (rf/trace-buffer {:source :repl})]
      (is (seq repl-evs))
      (is (every? #(= :repl (or (:source %) (get-in % [:tags :source])))
                  repl-evs)
          ":source filter matches top-level slot"))
    (let [timer-evs (rf/trace-buffer {:source :timer})]
      (is (seq timer-evs))
      (is (every? #(= :timer (or (:source %) (get-in % [:tags :source])))
                  timer-evs)))))

(deftest trace-buffer-filter-origin
  (testing ":origin filters by :tags :origin"
    (rf/reg-event-db :ping (fn [db _] db))
    (rf/dispatch-sync [:ping] {:origin :pair})
    (rf/dispatch-sync [:ping] {:origin :story})
    (let [pair-evs (rf/trace-buffer {:origin :pair})]
      (is (seq pair-evs))
      (is (every? #(= :pair (get-in % [:tags :origin])) pair-evs)
          ":origin :pair narrows to pair-issued cascades"))
    (let [story-evs (rf/trace-buffer {:origin :story})]
      (is (seq story-evs))
      (is (every? #(= :story (get-in % [:tags :origin])) story-evs)))))

(deftest trace-buffer-filter-dispatch-id
  (testing ":dispatch-id narrows to one cascade (rf2-g6ih4 cascade-wide tag)"
    (rf/reg-event-db :ping (fn [db _] db))
    (rf/dispatch-sync [:ping])
    (rf/dispatch-sync [:ping])
    (let [first-dispatch (->> (rf/trace-buffer)
                              dispatched-events
                              first)
          target-id      (get-in first-dispatch [:tags :dispatch-id])
          slice          (rf/trace-buffer {:dispatch-id target-id})]
      (is (number? target-id))
      (is (seq slice))
      (is (every? #(= target-id (get-in % [:tags :dispatch-id])) slice)
          "every event in the slice carries the same :dispatch-id"))))

(deftest trace-buffer-filter-since-ms
  (testing ":since-ms filters by :time host-clock millisecond bound"
    (rf/reg-event-db :ping (fn [db _] db))
    (rf/dispatch-sync [:ping])
    (let [pre-time (-> (rf/trace-buffer) last :time)]
      ;; Timer-semantics sleep (rf2-ka3n6): we are asserting :since-ms
      ;; filtering against the host clock — the test contract requires
      ;; that the next event's :time is strictly greater. NOT replaceable
      ;; by a deterministic-gate helper; the clock advancement IS the
      ;; thing under test.
      (Thread/sleep 5)
      (rf/dispatch-sync [:ping])
      (let [after (rf/trace-buffer {:since-ms pre-time})]
        (is (seq after))
        (is (every? #(> (:time %) pre-time) after)
            ":since-ms keeps events strictly after the timestamp")))))

(deftest trace-buffer-filter-between
  (testing ":between [t0 t1] filters to a time window (inclusive)"
    (rf/reg-event-db :ping (fn [db _] db))
    (rf/dispatch-sync [:ping])
    (let [t0 (-> (rf/trace-buffer) first :time)
          t1 (-> (rf/trace-buffer) last  :time)
          win (rf/trace-buffer {:between [t0 t1]})]
      (is (seq win))
      (is (every? #(<= t0 (:time %) t1) win)
          "every event in the window falls in [t0, t1]"))
    (testing ":between with a window that excludes everything yields []"
      (let [win (rf/trace-buffer {:between [0 1]})]
        (is (= [] win))))))

(deftest trace-buffer-filter-pred
  (testing ":pred applies an arbitrary predicate"
    (rf/reg-event-db :ping (fn [db _] db))
    (rf/dispatch-sync [:ping])
    (let [errors-and-events (rf/trace-buffer {:pred (fn [ev]
                                                      (#{:event :error}
                                                       (:op-type ev)))})]
      (is (seq errors-and-events))
      (is (every? #(#{:event :error} (:op-type %)) errors-and-events)
          ":pred narrows to events matching the predicate"))
    (testing ":pred composes with named axes"
      (let [evs (rf/trace-buffer {:op-type :event
                                  :pred    (fn [ev]
                                             (= :event/dispatched
                                                (:operation ev)))})]
        (is (every? #(and (= :event (:op-type %))
                          (= :event/dispatched (:operation %)))
                    evs))))))

(deftest trace-buffer-filters-compose-and-wise
  (testing "every filter axis composes; supplying many narrows further"
    (rf/reg-event-db :ev/x (fn [db _] db))
    (rf/dispatch-sync [:ev/x] {:origin :pair :source :repl})
    ;; :event/dispatched carries :origin and :source (via top-level hoist)
    ;; but NOT :event-id (it carries the full :event vector). :event-id
    ;; lives on :event/db-changed and on error emits. Compose four axes
    ;; that all coexist on :event/dispatched.
    (let [evs (rf/trace-buffer {:op-type   :event
                                :operation :event/dispatched
                                :origin    :pair
                                :source    :repl})]
      (is (seq evs))
      (is (every? #(and (= :event           (:op-type %))
                        (= :event/dispatched (:operation %))
                        (= :pair            (get-in % [:tags :origin]))
                        (= :repl            (or (:source %)
                                                (get-in % [:tags :source]))))
                  evs)
          "all four axes match simultaneously"))
    (testing ":event-id composes with the other axes on :event/db-changed"
      (let [evs (rf/trace-buffer {:operation :event/db-changed
                                  :event-id  :ev/x
                                  :origin    :pair})]
        (is (every? #(and (= :event/db-changed (:operation %))
                          (= :ev/x            (get-in % [:tags :event-id]))
                          (= :pair            (get-in % [:tags :origin])))
                    evs))))))

;; ---- 3. :origin opt --------------------------------------------------------

(deftest origin-defaults-to-app
  (testing "no :origin opt → :tags :origin = :app"
    (rf/reg-event-db :ping (fn [db _] db))
    (rf/dispatch-sync [:ping])
    (let [ev (->> (rf/trace-buffer)
                  dispatched-events
                  (filter #(= [:ping] (get-in % [:tags :event])))
                  first)]
      (is ev)
      (is (= :app (get-in ev [:tags :origin]))
          "default :origin is :app per Spec 002 §Dispatch origin tagging"))))

(deftest origin-opt-overrides-default
  (testing ":origin :pair lands on the trace event"
    (rf/reg-event-db :ping (fn [db _] db))
    (rf/dispatch-sync [:ping] {:origin :pair})
    (let [ev (->> (rf/trace-buffer)
                  dispatched-events
                  (filter #(= [:ping] (get-in % [:tags :event])))
                  first)]
      (is ev)
      (is (= :pair (get-in ev [:tags :origin]))
          ":origin :pair lifted onto :tags :origin"))))

(deftest origin-distinct-from-source
  (testing ":origin and :source ride independently"
    (rf/reg-event-db :ping (fn [db _] db))
    (rf/dispatch-sync [:ping] {:origin :pair :source :repl})
    (let [ev (->> (rf/trace-buffer)
                  dispatched-events
                  (filter #(= [:ping] (get-in % [:tags :event])))
                  first)]
      (is ev)
      (is (= :pair (get-in ev [:tags :origin]))   ":origin lands under :tags :origin")
      ;; :source is hoisted to top-level by emit!, per the existing contract.
      (is (= :repl (:source ev))                  ":source is hoisted to top-level"))))

;; ---- 4. :rf/dispatch-origin opt (rf2-t1lxr) -------------------------------

(deftest dispatch-origin-defaults-to-user
  (testing "no :rf/dispatch-origin opt → :tags :rf/dispatch-origin = :user"
    (rf/reg-event-db :ping (fn [db _] db))
    (rf/dispatch-sync [:ping])
    (let [ev (->> (rf/trace-buffer)
                  dispatched-events
                  (filter #(= [:ping] (get-in % [:tags :event])))
                  first)]
      (is ev)
      (is (= :user (get-in ev [:tags :rf/dispatch-origin]))
          "default :rf/dispatch-origin is :user per Spec 009 §Dispatch-origin tagging"))))

(deftest dispatch-origin-opt-overrides-default
  (testing ":rf/dispatch-origin :tool lands on the trace event"
    (rf/reg-event-db :ping (fn [db _] db))
    (rf/dispatch-sync [:ping] {:rf/dispatch-origin :tool})
    (let [ev (->> (rf/trace-buffer)
                  dispatched-events
                  (filter #(= [:ping] (get-in % [:tags :event])))
                  first)]
      (is ev)
      (is (= :tool (get-in ev [:tags :rf/dispatch-origin]))
          ":rf/dispatch-origin :tool lifted onto :tags :rf/dispatch-origin"))))

(deftest dispatch-origin-distinct-from-origin-and-source
  (testing ":rf/dispatch-origin / :origin / :source ride independently"
    (rf/reg-event-db :ping (fn [db _] db))
    (rf/dispatch-sync [:ping] {:rf/dispatch-origin :tool
                               :origin             :pair
                               :source             :repl})
    (let [ev (->> (rf/trace-buffer)
                  dispatched-events
                  (filter #(= [:ping] (get-in % [:tags :event])))
                  first)]
      (is ev)
      (is (= :tool (get-in ev [:tags :rf/dispatch-origin])))
      (is (= :pair (get-in ev [:tags :origin])))
      (is (= :repl (:source ev))))))

(deftest dispatch-origin-fx-emit-on-cascade
  (testing "child dispatches emitted by :dispatch fx are tagged :fx-emit
            regardless of the parent's :rf/dispatch-origin"
    (rf/reg-event-fx :parent
      (fn [_ _] {:fx [[:dispatch [:child]]]}))
    (rf/reg-event-db :child (fn [db _] db))
    (rf/dispatch-sync [:parent] {:rf/dispatch-origin :user})
    (let [parent-ev (->> (rf/trace-buffer)
                          dispatched-events
                          (filter #(= [:parent] (get-in % [:tags :event])))
                          first)
          child-ev  (->> (rf/trace-buffer)
                          dispatched-events
                          (filter #(= [:child] (get-in % [:tags :event])))
                          first)]
      (is parent-ev)
      (is child-ev)
      (is (= :user (get-in parent-ev [:tags :rf/dispatch-origin]))
          "parent carries the explicit :user opt")
      (is (= :fx-emit (get-in child-ev [:tags :rf/dispatch-origin]))
          "child overrides to :fx-emit — origin is the IMMEDIATE source,
           lineage rides on :parent-dispatch-id"))))

(deftest trace-buffer-filter-dispatch-origin
  (testing ":rf/dispatch-origin filters by :tags :rf/dispatch-origin"
    (rf/reg-event-db :ping (fn [db _] db))
    (rf/dispatch-sync [:ping] {:rf/dispatch-origin :tool})
    (rf/dispatch-sync [:ping] {:rf/dispatch-origin :router})
    (let [tool-evs (rf/trace-buffer {:rf/dispatch-origin :tool})]
      (is (seq tool-evs))
      (is (every? #(= :tool (get-in % [:tags :rf/dispatch-origin])) tool-evs)
          ":rf/dispatch-origin :tool narrows to tool-issued cascades"))
    (let [router-evs (rf/trace-buffer {:rf/dispatch-origin :router})]
      (is (seq router-evs))
      (is (every? #(= :router (get-in % [:tags :rf/dispatch-origin])) router-evs)))))
