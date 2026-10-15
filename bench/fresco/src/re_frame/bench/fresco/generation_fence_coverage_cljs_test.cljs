(ns re-frame.bench.fresco.generation-fence-coverage-cljs-test
  "Registry changes move the staged snapshot basis while held cells keep their frozen stamp."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.fresco.arm1.runtime :as rf.bench.fresco.arm1.runtime]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     ;; The carried-invariant chain resolves the dynamic-var frame tier
     ;; BEFORE React context, so a fixture-installed ambient frame would
     ;; answer reads for a frame this row never made.
     :ambient-frame nil
     :init-fn       (fn [] (rf.bench.fresco.arm1.runtime/reset-runtime!))}))

(def ^:private q
  "This row's own query: a re-registration is a global act."
  [:genfence/v])

(defn- make-frame! [id db]
  (rf.live-frame/make-frame {:id id})
  (rf.frame/replace-app-db! id db)
  id)

(defn- reader
  "A boundary whose whole body is one read."
  [_]
  [:li (str (rf.bench.fresco.arm1.runtime/sub q))])

(deftest the-commit-basis-registry-axis-reaches-a-staged-key-and-not-a-held-one
  (rf/reg-sub (first q) (fn [db _] (:v db)))
  (let [f (make-frame! ::registry {:v 1})]
    (rf.bench.fresco.arm1.runtime/render-body f reader {})
    (let [entry    (rf.bench.fresco.arm1.runtime/last-reads)
          release! (rf.bench.fresco.arm1.runtime/commit-boundary! entry (fn []))]
      (let [basis    (rf.bench.fresco.arm1.runtime/commit-basis f)
            snapshot (rf.bench.fresco.arm1.runtime/snapshot-of entry)]
        (rf.frame/replace-app-db! f {:v 2})
        (is (= {:basis-moved? true :snapshot-moved? true}
               {:basis-moved?    (> (rf.bench.fresco.arm1.runtime/commit-basis f) basis)
                :snapshot-moved? (not= snapshot (rf.bench.fresco.arm1.runtime/snapshot-of entry))})
            "the CONTROL: a frame-state install moves both instruments"))
      ;; A second boundary on its own frame, rendered and NOT committed, so
      ;; its key is staged across the re-registration.
      (let [staged-f     (make-frame! ::registry-staged {:v 1})
            _            (rf.bench.fresco.arm1.runtime/render-body staged-f reader {})
            staged-entry (rf.bench.fresco.arm1.runtime/last-reads)
            staged-snap  (rf.bench.fresco.arm1.runtime/snapshot-of staged-entry)
            basis        (rf.bench.fresco.arm1.runtime/commit-basis f)
            snapshot     (rf.bench.fresco.arm1.runtime/snapshot-of entry)
            generation   (rf.bench.fresco.arm1.runtime/generation)]
        (rf/reg-sub (first q) (fn [db _] (* 10 (:v db))))
        (is (= {:generation-moved? false :basis-moved? true
                :mounted-snapshot-moved? false :staged-snapshot-moved? true}
               {:generation-moved?       (not= generation (rf.bench.fresco.arm1.runtime/generation))
                :basis-moved?            (> (rf.bench.fresco.arm1.runtime/commit-basis f) basis)
                :mounted-snapshot-moved? (not= snapshot (rf.bench.fresco.arm1.runtime/snapshot-of entry))
                :staged-snapshot-moved?  (not= staged-snap (rf.bench.fresco.arm1.runtime/snapshot-of staged-entry))})
            "the axis: the basis moves, the mounted boundary's number does not, the staged one's does"))
      (release!))))
