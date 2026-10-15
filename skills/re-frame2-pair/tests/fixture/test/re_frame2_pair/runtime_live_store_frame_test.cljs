(ns re-frame2-pair.runtime-live-store-frame-test
  "Live-store frames report their runnable registrations and ambiguous ids without inventing a generation."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame2-pair.runtime :as rt]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest a-frame-made-without-images-is-described-as-resolving-the-live-store
  (rf/reg-event :pair-live/inc {:ns 'app.counter} (fn [db _] db))
  (rf/reg-sub :pair-live/n {:ns 'app.counter} (fn [db _] (:n db)))
  (rf/make-frame {:id :pair-live/main})
  (let [res   (rt/describe-image {:frame :pair-live/main :include-ns? true})
        coord (get (:registrations res) [:event :pair-live/inc])]
    (is (true? (:ok? res)) "the read answers rather than throwing")
    (is (= :pair-live/main (:frame res)))
    (is (true? (:live-store? res)) "and marks the frame as resolving the live store")
    (is (= [] (:images res)) "a frame made without :images composes no image")
    (is (= (count (rf/registrations {:frame :pair-live/main :kind :event}))
           (get-in res [:counts :event]))
        "its counts are what the frame-targeted registrations query answers")
    (is (some #{:sub} (:kinds res)) "and its kinds name what it runs")
    (is (= :registered (:source coord))
        "an event it runs is listed with the source that supplied it")
    (is (= "app.counter" (str (:ns coord))) "and the namespace that registers it")
    (is (contains? (:registrations res) [:sub :pair-live/n]) "and so is its sub")))

(deftest an-ambiguous-id-is-named-with-its-namespaces
  (rf/reg-event :pair-live/save {:ns 'app.a} (fn [db _] db))
  (rf/reg-event :pair-live/save {:ns 'app.b} (fn [db _] db))
  (rf/reg-event :pair-live/load {:ns 'app.a} (fn [db _] db))
  (rf/make-frame {:id :pair-live/main})
  (let [res  (rt/describe-image {:frame :pair-live/main :include-ns? true})
        mine (filterv #(#{:pair-live/save :pair-live/load} (:id %)) (:ambiguous res))]
    (is (= [[:event :pair-live/save]] (mapv (juxt :kind :id) mine))
        "the id two namespaces register is listed as ambiguous")
    (is (= #{"app.a" "app.b"} (set (:namespaces (first mine))))
        "naming both namespaces")
    (is (not (contains? (:registrations res) [:event :pair-live/save]))
        "and is not listed as a registration the frame runs")
    (is (contains? (:registrations res) [:event :pair-live/load])
        "while every other id still is")))

(deftest validate-registered-reports-an-ambiguous-id-as-ambiguous
  (rf/reg-event :pair-live/save {:ns 'app.a} (fn [db _] db))
  (rf/reg-event :pair-live/save {:ns 'app.b} (fn [db _] db))
  (rf/reg-event :pair-live/load {:ns 'app.a} (fn [db _] db))
  (rf/make-frame {:id :pair-live/main})
  (let [res (rt/validate-registered :event :pair-live/save :pair-live/main)]
    (is (= {:ok? false :reason :ambiguous-id :kind :event :id :pair-live/save
            :frame :pair-live/main :namespaces #{"app.a" "app.b"}}
           (-> res (dissoc :hint) (update :namespaces set)))))
  (is (true? (:ok? (rt/validate-registered :event :pair-live/load :pair-live/main)))
      "control: an id one namespace registers validates")
  (is (= :unknown-id (:reason (rt/validate-registered :event :pair-live/sav :pair-live/main)))
      "and a mistyped id is still unknown"))

(deftest a-frame-made-with-images-still-reads-its-generation
  (rf/reg-event :pair-live/inc {:ns 'app.counter} (fn [db _] db))
  (rf/make-frame {:id     :pair-live/sealed
                  :images [(rf/image {:select-ns {:include ["app.counter"]}})]})
  (let [res (rt/describe-image {:frame :pair-live/sealed :include-ns? true})]
    (is (true? (:ok? res)))
    (is (nil? (:live-store? res)) "a frame made with :images is not marked live")
    (is (contains? (:registrations res) [:event :pair-live/inc])
        "and lists its image's registrations as before")))

(deftest a-target-naming-no-frame-still-fails-loud
  (testing "a frame that neither seals an image nor resolves the store live"
    (rf/make-frame {:id :pair-live/main})
    (is (true? (:live-store? (rt/describe-image {:frame :pair-live/main})))
        "control: a live-store frame answers")
    (let [e (try (rt/describe-image {:frame :pair-live/no-such-frame})
                 nil
                 (catch :default e e))]
      (is (= :rf.error/frame-no-generation (:rf.error/id (ex-data e)))))))

(deftest orient-counts-what-a-live-store-frame-runs
  (rf/reg-event :pair-live/inc {:ns 'app.counter} (fn [db _] db))
  (rf/reg-event :pair-live/save {:ns 'app.a} (fn [db _] db))
  (rf/reg-event :pair-live/save {:ns 'app.b} (fn [db _] db))
  (rf/make-frame {:id :pair-live/main})
  (let [view (#'rt/frame-registry-view :pair-live/main)]
    (is (= :frame (:basis view)) "orient's registry is the frame's, not the process fallback")
    (is (= :pair-live/main (:frame view)))
    (is (= (count (rf/registrations {:frame :pair-live/main :kind :event}))
           (get-in view [:counts :event]))
        "its counts are what the frame-targeted registrations query answers")
    (is (some #{:pair-live/inc} (:events view)) "an event the frame runs is listed")
    (is (not (some #{:pair-live/save} (:events view)))
        "an id the frame refuses as ambiguous is not")))

(deftest orient-still-reads-an-image-frame-and-falls-back-for-no-frame
  (rf/reg-event :pair-live/inc {:ns 'app.counter} (fn [db _] db))
  (rf/make-frame {:id     :pair-live/sealed
                  :images [(rf/image {:select-ns {:include ["app.counter"]}})]})
  (let [view (#'rt/frame-registry-view :pair-live/sealed)]
    (is (= :frame (:basis view)) "a frame made with :images is read through its generation")
    (is (some #{:pair-live/inc} (:events view)) "and lists its image's registrations")
    (is (= (count (rf/registrations {:frame :pair-live/sealed :kind :event}))
           (get-in view [:counts :event]))))
  (is (nil? (#'rt/frame-registry-view :pair-live/no-such-frame))
      "a target naming no frame yields nil, so orient falls back to the process view"))
