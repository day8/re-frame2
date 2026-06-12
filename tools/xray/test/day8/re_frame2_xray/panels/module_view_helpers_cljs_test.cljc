(ns day8.re-frame2-xray.panels.module-view-helpers-cljs-test
  "JVM + CLJS coverage for the Module-view pure-data helpers (rf2-wtg9z4
  — the EP-0013 disposition-6 demand-trigger surface).

  Verifies the (realm, frame) address-space projection (EP-0013
  disposition 3): realm→frames grouping, the realm-row shape, the
  single/multi-realm classification, and the zero-ceremony posture (a
  single-realm process projects ONE realm). The per-module provenance
  slots are asserted EMPTY (the seam has not graduated — rf2-wtg9z4
  follow-up)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [day8.re-frame2-xray.panels.module-view-helpers :as h]))

;; ---- realm-frames -------------------------------------------------------

(deftest realm-frames-groups-by-resolver
  (testing "frames group by their realm-of resolution"
    (let [realm-of {:app/main  :rf.realm/default
                    :app/cart  :rf.realm/default
                    :other/x   :app/realm-b}
          grouped  (h/realm-frames [:app/main :app/cart :other/x] realm-of)]
      (is (= #{:app/main :app/cart} (get grouped :rf.realm/default)))
      (is (= #{:other/x} (get grouped :app/realm-b))))))

(deftest realm-frames-nil-resolution-falls-to-default
  (testing "a frame whose realm-of returns nil buckets to the default realm
            (absence = default realm — EP-0013 D1 rule)"
    (let [grouped (h/realm-frames [:app/main :ghost] (constantly nil))]
      (is (= #{:app/main :ghost} (get grouped :rf.realm/default))
          "nil realm → default realm bucket"))))

;; ---- project-realm-row --------------------------------------------------

(deftest project-realm-row-shape
  (testing "the realm-row carries id, sorted frames, count, and EMPTY
            provenance slots (seam not graduated)"
    (let [row (h/project-realm-row :rf.realm/default #{:app/cart :app/main})]
      (is (= :rf.realm/default (:realm row)))
      (is (= [:app/cart :app/main] (:frames row)) "frames sorted by str")
      (is (= 2 (:frame-count row)))
      ;; provenance slots — awaiting the core seam (rf2-wtg9z4 follow-up).
      (is (nil? (:modules row)))
      (is (= #{} (:requires row)))
      (is (nil? (:owns row)))
      (is (nil? (:classification row))))))

;; ---- project-module-view ------------------------------------------------

(deftest project-module-view-single-realm-zero-ceremony
  (testing "a single-realm process projects ONE realm, multi-realm? false
            (zero-ceremony — the realm dimension stays implicit)"
    (let [data (h/project-module-view #{:rf.realm/default}
                                      [:app/main :app/cart]
                                      (constantly :rf.realm/default))]
      (is (= 1 (:realm-count data)))
      (is (false? (:multi-realm? data)))
      (is (= :rf.realm/default (:realm (first (:realms data)))))
      (is (= [:app/cart :app/main] (:frames (first (:realms data)))))
      (is (false? (:provenance-available? data))
          "the provenance seam has NOT graduated (rf2-wtg9z4 follow-up)"))))

(deftest project-module-view-multi-realm
  (testing "more than one realm → multi-realm? true, every realm present,
            sorted by realm-id"
    (let [realm-of {:app/main :rf.realm/default
                    :other/x  :app/realm-b}
          data     (h/project-module-view #{:rf.realm/default :app/realm-b}
                                          [:app/main :other/x]
                                          realm-of)]
      (is (= 2 (:realm-count data)))
      (is (true? (:multi-realm? data)))
      (is (= [:app/realm-b :rf.realm/default]
             (mapv :realm (:realms data)))
          "realms sorted by realm-id str"))))

(deftest project-module-view-empty-realm-still-present
  (testing "an installed realm with no frames still appears (a realm can
            exist with zero frames)"
    (let [data (h/project-module-view #{:rf.realm/default :app/empty}
                                      [:app/main]
                                      (constantly :rf.realm/default))]
      (is (= 2 (:realm-count data)))
      (let [empty-row (first (filter #(= :app/empty (:realm %)) (:realms data)))]
        (is (some? empty-row) "the frameless realm is present")
        (is (= 0 (:frame-count empty-row)))
        (is (= [] (:frames empty-row)))))))

(deftest project-module-view-stale-frame-realm-never-strands
  (testing "a frame resolving to a realm absent from realm-ids still gets a
            row (defensive — a stale frame never strands the view)"
    (let [data (h/project-module-view #{:rf.realm/default}
                                      [:app/main :ghost]
                                      {:app/main :rf.realm/default
                                       :ghost    :app/gone})]
      (is (contains? (set (map :realm (:realms data))) :app/gone)
          "the orphan realm is surfaced rather than dropped"))))

;; ---- captions / summaries -----------------------------------------------

(deftest awaiting-caption-names-the-deferred-facts
  (testing "the awaiting-seam caption names ownership, capability,
            classification, and provenance so the operator knows the
            surface is scaffolded, not broken"
    (let [c h/awaiting-provenance-caption]
      (is (re-find #"(?i)ownership" c))
      (is (re-find #"(?i)capability" c))
      (is (re-find #"(?i)classification" c))
      (is (re-find #"(?i)provenance" c)))))

(deftest realm-summary-line-pluralizes
  (is (= ":rf.realm/default · 1 frame"
         (h/realm-summary-line {:realm :rf.realm/default :frame-count 1})))
  (is (= ":rf.realm/default · 2 frames"
         (h/realm-summary-line {:realm :rf.realm/default :frame-count 2}))))
