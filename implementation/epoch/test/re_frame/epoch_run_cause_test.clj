(ns re-frame.epoch-run-cause-test
  "`re-frame.epoch.capture/run-cause`, the `:epoch/run-cause` hook that
  `:rf.view/rendered` and `:rf.sub/run` emit sites call to attribute a render
  or recompute to the event run in flight, and to enforce the per-run render
  cap. It walks the frame's capture buffer, which these tests seed directly."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch]
            [re-frame.epoch.capture :as rf.epoch.capture]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.machines]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf/configure! {:epoch-history {:trace-events-keep 5}}))}))

(defn- run-start [event-id]
  {:op-type   :rf.event
   :operation :rf.event/run-start
   :tags      {:rf.trace/phase :run-start :rf.trace/event-id event-id}})

(defn- sub-run
  ([sub-id] (sub-run sub-id false))
  ([sub-id value-changed?]
   {:op-type   :rf.sub
    :operation :rf.sub/run
    :tags      {:rf.sub/id sub-id :rf.sub/value-changed? value-changed?}}))

(def ^:private rendered
  {:op-type :rf.view :operation :rf.view/rendered :tags {}})

(def ^:private rendered-cap-reached
  {:op-type :rf.view :operation :rf.view/rendered-cap-reached :tags {}})

(defn- run-cause-of
  "Seed a fresh frame's capture buffer with `events`, then call run-cause."
  [events & args]
  (let [frame-id (keyword "test" (str (gensym "cc")))]
    (doseq [ev events] (rf.epoch.state/buffer-event! frame-id ev))
    (apply rf.epoch.capture/run-cause frame-id args)))

(deftest run-cause-summarises-the-in-flight-buffer
  (doseq [[events expected]
          [;; Outside any run: only the render count, every other slot omitted.
           [[] {:rendered-so-far 0}]
           ;; No run-start: no :cause-event-id, but the subs still surface.
           [[(sub-run :a true)]
            {:rendered-so-far 0 :cause-subs [:a] :value-changed-subs #{:a}}]
           ;; No sub changed value (a structural re-render): the slot is omitted.
           [[(run-start :ev) (sub-run :a) (sub-run :b)]
            {:rendered-so-far 0 :cause-event-id :ev :cause-subs [:a :b]}]
           ;; The first run-start wins; subs dedup in first-seen order; the
           ;; cap marker counts as a render so the emit site keeps suppressing.
           [[(run-start :ev) (sub-run :a true) (sub-run :b) (sub-run :a)
             (sub-run :c true) (sub-run :b) (run-start :child)
             rendered rendered rendered-cap-reached]
            {:rendered-so-far 3 :cause-event-id :ev :cause-subs [:a :b :c]
             :value-changed-subs #{:a :c}}]]]
    (is (= expected (run-cause-of events)) (pr-str events))))

(deftest run-cause-caps-both-sub-slots-independently
  (let [result (run-cause-of (into [(run-start :ev)]
                                   (map #(sub-run (keyword (str "s" %)) true))
                                   (range 10))
                             3)]
    (is (= [[:s0 :s1 :s2] #{:s0 :s1 :s2}]
           ((juxt :cause-subs :value-changed-subs) result)))))
