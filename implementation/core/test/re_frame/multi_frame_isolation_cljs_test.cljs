(ns re-frame.multi-frame-isolation-cljs-test
  "Multi-frame isolation on the data layer (Spec 002 §Per-instance frames,
  Spec 006 §The cache is held inside the frame container). Two frames run the
  same handlers, registered once: each keeps its own app-db, a sub read under
  `with-frame` sees only that frame, `rf/app-db-value` is keyed by frame id
  rather than by the ambient frame, and destroying one frame leaves the other
  intact.

  Xray's panels_e2e tests cover the tool side (`parallel_frames_e2e` for
  target-frame switching, `multi_frame_isolation_e2e` for cross-frame fx
  routing). The frame ids `:above` / `:below` match the Xray
  `two_frame_isolation` testbed."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private frame-above :above)
(def ^:private frame-below :below)

(defn- install-handlers! []
  (rf/reg-event ::initialise
    (fn [_ _ev] {:db {:counter 0}}))
  (rf/reg-event ::counter-inc
    (fn [{:keys [db]} _ev] {:db (update db :counter (fnil inc 0))}))
  (rf/reg-sub ::counter (fn [db _] (:counter db))))

(defn- seed-frames!
  "Register `:above` and `:below` and seed each app-db to `{:counter 0}`."
  []
  (rf/make-frame {:id frame-above})
  (rf/make-frame {:id frame-below})
  (rf/dispatch-sync [::initialise] {:frame frame-above})
  (rf/dispatch-sync [::initialise] {:frame frame-below}))

(defn- sub-in
  "Subscribe inside `frame-id` and dereference."
  [frame-id query]
  (rf/with-frame frame-id @(rf/subscribe query)))

(deftest no-cross-frame-sub-leakage-and-app-db-value-is-the-only-read
  (install-handlers!)
  (seed-frames!)
  (dotimes [_ 5] (rf/dispatch-sync [::counter-inc] {:frame frame-above}))
  (dotimes [_ 2] (rf/dispatch-sync [::counter-inc] {:frame frame-below}))
  (is (= [5 2 5 2]
         [(sub-in frame-above [::counter])
          (sub-in frame-below [::counter])
          (:counter (rf/app-db-value frame-above))
          (:counter (rf/app-db-value frame-below))])
      "each frame's dispatches, subs and app-db stay its own")
  (rf/with-frame frame-above
    (is (= 2 (:counter (rf/app-db-value frame-below)))
        "rf/app-db-value is keyed by frame id, so it reads :below from inside :above")))

(deftest destroying-one-frame-leaves-the-other-intact
  (install-handlers!)
  (seed-frames!)
  (dotimes [_ 4] (rf/dispatch-sync [::counter-inc] {:frame frame-above}))
  (dotimes [_ 7] (rf/dispatch-sync [::counter-inc] {:frame frame-below}))
  (let [above-record-pre (rf.frame/frame frame-above)
        above-app-db-pre (:app-db above-record-pre)
        above-sub-cache  (:sub-cache above-record-pre)]
    (rf/destroy-frame! frame-below)
    (is (nil? (rf.frame/frame frame-below)) "destroy-frame! removed :below from the registry")
    (let [above-record-post (rf.frame/frame frame-above)]
      (is (identical? above-app-db-pre (:app-db above-record-post))
          ":above's app-db container is unchanged")
      (is (identical? above-sub-cache (:sub-cache above-record-post))
          ":above's sub-cache is unchanged"))
    (is (= 4 (sub-in frame-above [::counter])) ":above's sub still resolves against its app-db")
    (rf/dispatch-sync [::counter-inc] {:frame frame-above})
    (is (= 5 (:counter (rf/app-db-value frame-above))) ":above still takes dispatches")))
