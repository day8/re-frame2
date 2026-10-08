(ns re-frame.story-runtime-lifecycle-test
  "`watch-variant` and `destroy-variant!`: watcher fan-out, unsubscribe and
  teardown (002-Runtime §Programmatic API, §Lifecycle state machine)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core            :as rf]
            [re-frame.frame           :as rf.frame]
            [re-frame.machines        :as rf.machines]
            [re-frame.registrar       :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story           :as rf.story]
            [re-frame.story.async     :as rf.story.async]
            [re-frame.story.config    :as rf.story.config]
            [re-frame.story.frames    :as rf.story.frames]
            [re-frame.story.loaders   :as rf.story.loaders]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all [t]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.config/set-global-args! {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  ;; `:loaders [[:test/noop]]` keeps a variant on the four-phase route
  ;; (:pre-mount → :mounting → :loading → :ready) rather than the fast path.
  (rf/reg-event :test/noop (fn [{:keys [db]} _] {:db db}))
  (t))

(defn- allocate-and-load! [vid]
  (rf.story.frames/allocate! vid (rf.story/resolve-decorators vid))
  (rf.story.loaders/start-loaders! vid)
  (rf.story.loaders/finish-loaders! vid))

(use-fixtures :each reset-all)

(deftest unsubscribe-drops-only-the-caller-callback
  (rf.story/reg-variant :story.watch.solo/v {:loaders [[:test/noop]]})
  (let [seen-a  (atom [])
        seen-b  (atom [])
        unsub-a (rf.story/watch-variant :story.watch.solo/v #(swap! seen-a conj (:to %)))]
    (rf.story/watch-variant :story.watch.solo/v #(swap! seen-b conj (:to %)))
    (unsub-a)
    (allocate-and-load! :story.watch.solo/v)
    (is (= [[] [:mounting :loading :ready]] [@seen-a @seen-b]))
    (rf.story.frames/destroy! :story.watch.solo/v)))

(deftest multiple-watchers-each-see-every-transition
  (rf.story/reg-variant :story.watch.multi/v {:loaders [[:test/noop]]})
  (let [seen-a (atom [])
        seen-b (atom [])]
    (rf.story/watch-variant :story.watch.multi/v #(swap! seen-a conj [(:from %) (:to %)]))
    (rf.story/watch-variant :story.watch.multi/v #(swap! seen-b conj [(:from %) (:to %)]))
    (allocate-and-load! :story.watch.multi/v)
    (is (= [[:pre-mount :mounting] [:mounting :loading] [:loading :ready]] @seen-a @seen-b))
    (rf.story.frames/destroy! :story.watch.multi/v)))

(deftest throwing-watcher-does-not-starve-peers
  (rf.story/reg-variant :story.watch.boom/v {:loaders [[:test/noop]]})
  (let [peer-seen (atom [])]
    (rf.story/watch-variant :story.watch.boom/v (fn [_t] (throw (ex-info "boom" {:why :test}))))
    (rf.story/watch-variant :story.watch.boom/v #(swap! peer-seen conj (:to %)))
    (allocate-and-load! :story.watch.boom/v)
    (is (= [:mounting :loading :ready] @peer-seen))
    (rf.story.frames/destroy! :story.watch.boom/v)))

(deftest destroy-variant-clears-the-watcher-table-for-the-frame
  (rf.story/reg-variant :story.destroy.watchers/v {:loaders [[:test/noop]]})
  (let [seen (atom 0)]
    (rf.story/watch-variant :story.destroy.watchers/v (fn [_] (swap! seen inc)))
    (allocate-and-load! :story.destroy.watchers/v)
    (let [before-destroy @seen]
      (rf.story/destroy-variant! :story.destroy.watchers/v)
      (allocate-and-load! :story.destroy.watchers/v)
      (is (= before-destroy @seen) "destroyed-frame watchers do not fire on the next run")
      (rf.story.frames/destroy! :story.destroy.watchers/v))))

(deftest destroy-variant-is-idempotent
  (rf/reg-event :test/nothing (fn [{:keys [db]} _] {:db db}))
  (rf.story/reg-variant :story.destroy.twice/v {:setup [[:test/nothing]]})
  (rf.story.async/deref-blocking (rf.story/run-variant :story.destroy.twice/v) 5000)
  (is (= [nil nil nil] [(rf.story/destroy-variant! :story.destroy.twice/v)
                        (rf.story/destroy-variant! :story.destroy.twice/v)
                        (rf.story/destroy-variant! :story.never/allocated)])))

(deftest watch-variant-during-run-receives-finalising-transitions
  (testing "a watcher registered after allocate sees the remaining transitions"
    (rf.story/reg-variant :story.watch.late/v {:loaders [[:test/noop]]})
    (let [seen (atom [])]
      (rf.story.frames/allocate! :story.watch.late/v
                                 (rf.story/resolve-decorators :story.watch.late/v))
      (rf.story/watch-variant :story.watch.late/v #(swap! seen conj [(:from %) (:to %)]))
      (rf.story.loaders/start-loaders! :story.watch.late/v)
      (rf.story.loaders/finish-loaders! :story.watch.late/v)
      (is (= [[:mounting :loading] [:loading :ready]] @seen))
      (rf.story.frames/destroy! :story.watch.late/v))))
