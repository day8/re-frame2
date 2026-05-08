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
            [re-frame.flows :as flows]
            [re-frame.trace :as trace]))

;; ---- fixtures --------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (trace/clear-trace-cbs!)
  (rf/clear-trace-buffer!)
  ;; Restore default depth between tests so a depth-tweaking test does
  ;; not bleed configuration into the next.
  (rf/configure :trace-buffer {:depth 200})
  (rf/init!)
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
    (let [src (slurp "src/re_frame/trace.cljc")]
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
