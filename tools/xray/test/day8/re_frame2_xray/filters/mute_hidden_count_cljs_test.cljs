(ns day8.re-frame2-xray.filters.mute-hidden-count-cljs-test
  "The `N events filtered out` count stays visible when MUTES alone hide
  rows.

  The count renders in the events ribbon (bar-2), and that ribbon sits
  inside a collapse track. A mute hides rows without committing a pill,
  so the track has to open on the count itself, or the count renders
  into a closed, zero-height, fully transparent track.

  The state is produced the way a user produces it — seeded events and a
  real `:rf.xray/mute-event-id` dispatch — and read through the shell's
  own tree, so the rows grade the shipped composition rather than a
  hand-built summary map."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.frame-switcher :as frame-switcher]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-helpers.dynamic-shell-tree
             :as dynamic-shell-tree]
            [day8.re-frame2-xray.spine-filters :as spine-filters]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (spine-filters/clear-raw!)
                   (frame-switcher/clear!))}))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (spine-filters/hydrate!))

(defn- frame-sub [q]
  (rf/with-frame :rf/xray
    @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync ev)))

(defn- seed-event! [id event-vec]
  (trace-collector/seed-trace-for-test!
    {:id        id
     :op-type   :rf.event
     :operation :rf.event/dispatched
     :tags      {:rf.event/v           event-vec
                 :frame                :rf/default
                 :rf.trace/dispatch-id id}}))

(defn- events-ribbon-parts
  "The collapse track, the ribbon inside it, and the count node found
  INSIDE the track — so a count rendered anywhere else does not count."
  []
  (rf/with-frame :rf/xray
    (let [tree     (dynamic-shell-tree/shell-view-tree)
          collapse (rf.test-helpers/find-by-testid tree "rf-xray-events-ribbon-collapse")]
      {:collapse collapse
       :ribbon   (rf.test-helpers/find-by-testid collapse "rf-xray-events-ribbon")
       :count    (rf.test-helpers/find-by-testid collapse "rf-xray-filters-hidden-count")})))

(defn- pill-count []
  (let [{:keys [in out]} (frame-sub [:rf.xray/active-filters])]
    (+ (count in) (count out))))

(deftest mutes-alone-open-the-events-ribbon-around-the-count
  (testing "a mute that hides a row, with no pill committed, opens the
            events ribbon so its `N events filtered out` count is visible"
    (xray-setup!)
    (seed-event! 1 [:a])
    (seed-event! 2 [:noise/tick])
    (frame-dispatch [:rf.xray/mute-event-id :noise/tick])
    (is (zero? (pill-count)) "precondition: no pill is committed")
    (is (true? (:visible? (frame-sub [:rf.xray/hidden-by-filters])))
        "precondition: the mute hides a row, so the count renders")
    (let [{:keys [collapse ribbon] count-node :count} (events-ribbon-parts)]
      (is (some? count-node) "the count renders inside the events-ribbon track")
      (is (re-find #"1 event filtered out" (rf.test-helpers/text-content count-node)))
      (is (= "true" (:data-open (second collapse)))
          "the track around the count is OPEN")
      (is (= "false" (:aria-hidden (second ribbon)))
          "the ribbon carrying the count is not hidden from assistive tech"))))

(deftest a-mute-that-hides-nothing-leaves-the-events-ribbon-closed
  (testing "the ribbon opens on the COUNT, not on the mute set: a mute
            that matches no event hides nothing, so no count renders and
            the ribbon stays closed"
    (xray-setup!)
    (seed-event! 1 [:a])
    (frame-dispatch [:rf.xray/mute-event-id :noise/tick])
    (is (seq (frame-sub [:rf.xray/muted-event-ids])) "precondition: a mute is set")
    (let [{:keys [collapse] count-node :count} (events-ribbon-parts)]
      (is (nil? count-node) "no count renders when nothing is hidden")
      (is (= "false" (:data-open (second collapse)))
          "the track stays CLOSED"))))
