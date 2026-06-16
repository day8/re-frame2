(ns day8.re-frame2-xray.panels.image-view-reads-cljs-test
  "CLJS coverage for the EP-0023 live image/frame read seam + the
  Xray-as-its-own-image DOGFOODING (rf2-32siq3.12 — EP-0023 §Xray Beside The
  Target).

  This is the test the .29 dogfooding review verifies: Xray models itself as a
  SEPARATE image/frame that inspects the target frame as DATA — NOT as shared
  registration state. Concretely:

    - Xray constructs its OWN `rf/image` (`image-view-reads/xray-image`),
      selecting its own source namespaces (`day8.re-frame2-xray.**`);
    - Xray's `:rf.xray/*` registrations do not leak INTO a target frame's
      image (a target image selects the target's own namespaces, not Xray's);
    - a target frame's registrations do not leak INTO Xray's image;
    - Xray reads the target frame's generation + state as DATA through the
      live-read fns, with the two images registration-disjoint.

  The reads are also exercised against the real EP-0023 live-frame registry +
  sealed generations (the fail-soft seam) so the projection runs end-to-end on
  the values `make-frame` / `assemble` actually produce."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.image :as image]
            [re-frame.live-frame :as live-frame]
            [re-frame.image-assembly :as image-assembly]
            [day8.re-frame2-xray.panels.image-view-reads :as reads]))

;; Each case starts from a clean live-frame registry so an `:id` from one case
;; never collides with the next (EP-0023 §Id Spaces — a frame id is unique in
;; the process-local registry).
(use-fixtures :each
  {:before #(live-frame/clear-live-frames!)
   :after  #(live-frame/clear-live-frames!)})

;; A target image + a target frame, built against an EXPLICIT descriptor pool
;; (so the test does not depend on the live source store carrying any host
;; registrations). The target image selects the target's OWN namespaces.
(def ^:private target-pool
  ;; The explicit descriptor pool `assemble`'s 2-arity selects from: a FLAT seq
  ;; of descriptor maps (each carrying :kind / :id / :rf.provenance/ns — the
  ;; source store's output shape), matching `image/select-descriptors`'s input.
  [{:kind :event :id :counter/inc   :rf.provenance/ns "app.counter" :impl :inc}
   {:kind :sub   :id :counter/value :rf.provenance/ns "app.counter" :impl :val}])

(def ^:private target-image
  (image/image {:id :app/counter :include-ns ["app.counter"]}))

;; ---- Xray's own image (the dogfooding) -----------------------------------

(deftest xray-image-is-its-own-value
  (testing "Xray constructs its OWN inert image VALUE selecting its own source
            namespaces — the inspector's instruction set as data (EP-0023
            §Xray Beside The Target)"
    (let [img (reads/xray-image)]
      (is (= :rf.xray/image (:rf.image/id img)))
      (is (= ["day8.re-frame2-xray.**"] (:rf.image/include-ns img))
          "Xray selects ONLY its own source namespaces")
      ;; PURE — an image is data, not registration: constructing it twice
      ;; yields equal values and touches no registry.
      (is (= img (reads/xray-image)) "rf/image is pure — equal values"))))

(deftest xray-image-isolated-from-target-image
  (testing "Xray's image and the target frame's image are REGISTRATION-DISJOINT
            — Xray's registrations do not leak into / from the target's image
            (the .29-review invariant)"
    (is (true? (reads/xray-image-isolated-from? target-image))
        "Xray (day8.re-frame2-xray.**) and the target (app.counter) select
         disjoint source namespaces → isolated")
    ;; The negative: an image that DID select Xray's namespaces would NOT be
    ;; isolated — the predicate is a real check, not a constant true.
    (let [leaky (image/image {:id :leaky/img
                              :include-ns ["day8.re-frame2-xray.**" "app.counter"]})]
      (is (false? (reads/xray-image-isolated-from? leaky))
          "an image selecting Xray's namespaces is NOT isolated from Xray"))))

(deftest xray-and-target-resolvers-do-not-share-registrations
  (testing "a frame built from Xray's image and a frame built from the target's
            image have DISJOINT resolver keysets — neither sees the other's
            registrations (Xray is NOT part of the thing being inspected)"
    ;; Assemble the target generation against its explicit pool. (Xray's image
    ;; selects the LIVE source store, where Xray's own :rf.xray/* regs live;
    ;; the target pool is explicit + carries no :rf.xray/* descriptor, so the
    ;; two resolver keysets cannot overlap regardless of what is loaded.)
    (let [target-gen (image-assembly/assemble [target-image] target-pool)
          target-keys (set (keys (:rf.gen/resolver target-gen)))]
      ;; The target frame resolves ITS ids; none are :rf.xray/* and none come
      ;; from Xray's source namespaces.
      (is (contains? target-keys [:event :counter/inc]))
      (is (every? (fn [[_ id]] (not= "rf.xray" (namespace id))) target-keys)
          "no :rf.xray/* id leaked into the target frame's image"))))

;; ---- live reads of the real registry (fail-soft seam) --------------------

(deftest live-reads-project-real-frames
  (testing "the live-read seam projects the REAL EP-0023 live-frame registry +
            sealed generations end-to-end"
    ;; A target frame loaded with the target image against its explicit pool,
    ;; registered under an :id so it enters the live-frame registry.
    (live-frame/make-frame {:id :app/main :images [target-image]} target-pool)
    (let [data (reads/image-view-data)
          row  (first (filter #(= :app/main (:frame-id %)) (:frames data)))]
      (is (true? (:images? data)) "a live image-loaded frame → :images? true")
      (is (some? row) "the registered frame is projected")
      (is (= 2 (:descriptor-count (:image row)))
          "the frame's resolved image carries its [kind id] descriptors")
      (is (contains? (set (map (juxt :kind :id) (:descriptors (:image row))))
                     [:event :counter/inc])
          "the resolved descriptor set is the frame's image as a value"))))

(deftest live-reads-fail-soft-empty-registry
  (testing "an empty live-frame registry projects to the honest no-image state"
    (let [data (reads/image-view-data)]
      (is (= 0 (:frame-count data)))
      (is (false? (:images? data))))))

(deftest resolve-descriptor-is-frame-derived
  (testing "resolving a [kind id] through a real frame's generation yields the
            frame's OWN descriptor (the frame-derived resolution path)"
    (let [frame (live-frame/make-frame {:images [target-image]} target-pool)
          gen   (:rf.frame/generation frame)
          desc  (reads/resolve-descriptor gen :event :counter/inc)]
      (is (= :counter/inc (:id desc)))
      (is (= "app.counter" (:rf.provenance/ns desc))
          "resolves to the target image's descriptor, not Xray's"))
    (testing "a nil generation is fail-soft → nil (no throw)"
      (is (nil? (reads/resolve-descriptor nil :event :counter/inc))))))
