(ns re-frame.cascade-envelope-propagation-test
  "Cascade propagation (Spec 002 §Cascade propagation, §Drain-loop pseudocode
  `inheritable-envelope-keys`): a child queued by `:fx [[:dispatch …]]` or
  `:dispatch-later` inherits the parent envelope's `:fx-overrides`,
  `:interceptor-overrides`, `:trace-id`, `:origin` and `:frame`, while
  `:source` is re-stamped by the fx that queued it (`:fx-dispatch` /
  `:fx-dispatch-later`). A user fx-handler receives the envelope as
  `(:envelope m)`.

  Every claim reads a production surface — fx-handler arguments or
  `(:envelope m)` — so the whole namespace runs under
  `scripts/test-core-prod-gate.sh` too."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  ;; The ambient scope the top-level dispatches resolve against; children
  ;; carry the parent's `:frame` explicitly.
  (rf.frame/ensure-default-frame!)
  (binding [rf.frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

(deftest fx-overrides-propagate-through-dispatch-cascade
  (let [real (atom [])
        stub (atom [])]
    (rf/reg-fx :test/http (fn [_ args] (swap! real conj args)))
    (rf/reg-fx :test/http.stub (fn [_ args] (swap! stub conj args)))
    (rf/reg-event :test/parent
      (fn [_ _]
        {:fx [[:test/http {:tag :from-parent}]
              [:dispatch [:test/child]]]}))
    (rf/reg-event :test/child
      (fn [_ _]
        {:fx [[:test/http {:tag :from-child}]]}))
    (rf/dispatch-sync [:test/parent] {:fx-overrides {:test/http :test/http.stub}})
    (is (= {:real [] :stub [{:tag :from-parent} {:tag :from-child}]}
           {:real @real :stub @stub})
        "the parent's and the child's :test/http calls both hit the stub")))

(deftest trace-id-origin-propagate-source-overridden-through-cascade
  (let [child-env (atom nil)]
    (rf/reg-fx :test/probe (fn [m _] (reset! child-env (:envelope m))))
    (rf/reg-event :test/parent (fn [_ _] {:fx [[:dispatch [:test/child]]]}))
    (rf/reg-event :test/child (fn [_ _] {:fx [[:test/probe]]}))
    (rf/dispatch-sync [:test/parent] {:trace-id ::scoped-trace
                                      :origin   :test
                                      :source   :test})
    (is (= {:trace-id ::scoped-trace :origin :test :source :fx-dispatch}
           (select-keys @child-env [:trace-id :origin :source]))
        ":trace-id and :origin are inherited; :source is re-stamped by the :dispatch fx")))

(deftest fx-handler-ctx-carries-envelope-slot
  (let [captured (atom nil)]
    (rf/reg-fx :test/capture-envelope (fn [m _] (reset! captured (:envelope m))))
    (rf/reg-event :test/run (fn [_ _] {:fx [[:test/capture-envelope]]}))
    (rf/dispatch-sync [:test/run] {:trace-id ::abc
                                   :origin   :test
                                   :source   :unit-test})
    (is (= {:trace-id ::abc :origin :test :source :unit-test :event [:test/run]}
           (select-keys @captured [:trace-id :origin :source :event])))))

(deftest in-flight-envelope-is-bound-for-the-handler-call
  ;; `current-event-envelope` is the seam machine completion carriers use to
  ;; queue a child through `child-dispatch!` without an fx ctx.
  (let [seen (atom nil)]
    (rf/reg-fx :test/http (fn [_ _]))
    (rf/reg-fx :test/http.stub (fn [_ _]))
    (rf/reg-event :test/peek
      (fn [_ _]
        (reset! seen {:own   (rf.frame/current-event-envelope :rf/default)
                      :other (rf.frame/current-event-envelope :test/other-frame)})
        {}))
    (rf/dispatch-sync [:test/peek] {:fx-overrides {:test/http :test/http.stub}
                                    :origin       :test
                                    :trace-id     ::peek})
    (is (= {:own   {:event        [:test/peek]
                    :fx-overrides {:test/http :test/http.stub}
                    :origin       :test
                    :trace-id     ::peek}
            :other nil}
           (update @seen :own select-keys [:event :fx-overrides :origin :trace-id]))
        "the handler sees its own dequeued envelope, scoped to its frame")
    (is (nil? (rf.frame/current-event-envelope :rf/default))
        "unbound outside any handler pipeline")))

(deftest dispatch-later-propagates-inheritable-keys
  (let [stub-fired (atom [])
        done       (promise)]
    (rf/reg-fx :test/http (fn [_ _]))
    (rf/reg-fx :test/http.stub
      (fn [_ args]
        (swap! stub-fired conj args)
        (deliver done :fired)))
    (rf/reg-event :test/parent
      (fn [_ _] {:fx [[:dispatch-later {:ms 1 :event [:test/child]}]]}))
    (rf/reg-event :test/child
      (fn [_ _] {:fx [[:test/http {:tag :deferred}]]}))
    (rf/dispatch-sync [:test/parent] {:fx-overrides {:test/http :test/http.stub}})
    (deref done 2000 nil)
    (is (= [{:tag :deferred}] @stub-fired)
        ":fx-overrides reached :dispatch-later's deferred dispatch")))
