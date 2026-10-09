(ns re-frame.bench.p0-write-page-cljs-test
  "The contract of `re-frame.bench.p0-fixture`'s writes, checked without a
  browser: the allocation row that drives `:p0/write-page` is an `:advanced`
  release build run by hand and in no gate. One `:p0/write-page` must change
  every key a mounted page reads, at the db's own width, while
  `:p0/write-all` keeps rebuilding the published 300-cell grid."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.bench.p0-fixture :as rf.bench.p0-fixture]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private frame-id :p0.wp/frame)

;; `:p0/cell` is also registered by two fresco bench namespaces this build
;; loads, so the image is scoped to the fixture's own registrations.
(def ^:private fixture-image
  (rf/image {:id :p0.wp/app :select-ns {:include ["re-frame.bench.p0-fixture"]}}))

(defn- frame-at
  "Stand a frame up seeded at `width` cells, with `q` unique fan keys."
  [width q]
  (rf.bench.p0-fixture/register!)
  (rf.bench.p0-fixture/set-fan-keys! q)
  (rf/make-frame {:id frame-id :images [fixture-image] :initial-events [[:p0/seed width]]}))

(defn- db [] (rf/app-db-value frame-id))

(defn- fan [k] (rf/subscribe-once [:p0/fan k] {:frame frame-id}))

(deftest an-unstated-width-is-the-published-page-to-the-byte
  (is (= rf.bench.p0-fixture/cells-n (count (:cells (rf.bench.p0-fixture/seed-db)))))
  (is (= (rf.bench.p0-fixture/seed-db) (rf.bench.p0-fixture/seed-db rf.bench.p0-fixture/cells-n))))

(deftest every-key-a-mounted-page-reads-sees-a-changed-value
  ;; 480 distinct keys fold onto a 4-cell grid; every one must see the write.
  (frame-at 4 480)
  (rf/dispatch-sync [:p0/write-page 2] {:frame frame-id})
  (is (= [2 2 2 2] (:cells (db))))
  (is (every? #(= 2 %) (map fan (range 480)))))

(deftest write-all-still-rebuilds-the-published-grid-whatever-is-mounted
  (frame-at 4 1)
  (rf/dispatch-sync [:p0/write-all 1] {:frame frame-id})
  (is (= (vec (repeat rf.bench.p0-fixture/cells-n 1)) (:cells (db)))))

(deftest the-narrow-write-is-untouched
  (frame-at 4 4)
  (rf/dispatch-sync [:p0/write-one 2 8] {:frame frame-id})
  (is (= [0 0 8 0] (:cells (db))) "one slot moved, three did not"))
