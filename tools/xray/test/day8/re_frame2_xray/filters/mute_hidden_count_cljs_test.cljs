(ns day8.re-frame2-xray.filters.mute-hidden-count-cljs-test
  "The `N events filtered out` count stays visible when MUTES alone hide
  rows.

  The count renders in the events ribbon (bar-2), and that ribbon sits
  inside a collapse track. A mute hides rows without committing a pill,
  so the track has to open on the count itself, or the count renders
  into a closed, zero-height, fully transparent track. The rows read the
  shell's own tree after a real `:rf.xray/mute-event-id` dispatch."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
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

(defn- setup-with-mute!
  "Seed `event-ids` as dispatched events, then mute `:noise/tick`."
  [event-ids]
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (doseq [[id event-id] (map vector [1 2] event-ids)]
    (trace-collector/seed-trace-for-test!
      {:id        id
       :op-type   :rf.event
       :operation :rf.event/dispatched
       :tags      {:rf.event/v           [event-id]
                   :frame                :rf/default
                   :rf.trace/dispatch-id id}}))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/mute-event-id :noise/tick])))

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

(deftest mutes-alone-open-the-events-ribbon-around-the-count
  (setup-with-mute! [:a :noise/tick])
  (let [{:keys [collapse ribbon] count-node :count} (events-ribbon-parts)]
    (is (re-find #"1 event filtered out" (rf.test-helpers/text-content count-node)))
    (is (= ["true" "false"] [(:data-open (second collapse)) (:aria-hidden (second ribbon))])
        "the track around the count is open and not hidden from assistive tech")))

(deftest a-mute-that-hides-nothing-leaves-the-events-ribbon-closed
  ;; The ribbon opens on the COUNT, not on the mute set.
  (setup-with-mute! [:a])
  (let [{:keys [collapse] count-node :count} (events-ribbon-parts)]
    (is (= [nil "false"] [count-node (:data-open (second collapse))]))))
