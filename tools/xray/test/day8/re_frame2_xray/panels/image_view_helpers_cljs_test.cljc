(ns day8.re-frame2-xray.panels.image-view-helpers-cljs-test
  "JVM + CLJS coverage for the EP-0023 image/frame pure-data helpers
  (rf2-32siq3.12 — the `image -> frame -> event stream` public model on the
  Module-view tab).

  Verifies the three EP-0023 nouns the surface presents:

    - **image** as a registration-set VALUE — a sealed generation projected
      into its `[kind id]` descriptor set with per-descriptor provenance
      (`project-generation` / `descriptor-provenance`);
    - **frame** as an EXECUTION CONTEXT pointing at its generation
      (`project-frame-row` / `project-frames`);
    - **frame-derived RESOLUTION** — the same `[kind id]` resolving to
      DIFFERENT descriptors in frames running different images
      (`resolve-in-frame`).

  Plus the demand-gated top-level projection (`project-image-view` →
  `:images?`) and the display strings. All algebra is pure `data -> data`, so
  this runs under the JVM test target with hand-built generation/frame fixtures
  (the inert shapes `assemble` / `make-frame` produce)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [day8.re-frame2-xray.panels.image-view-helpers :as h]))

;; ---- fixtures: the inert shapes the EP-0023 core surfaces produce -------

;; A resolved descriptor as the source store / image stamps it: :kind / :id /
;; :rf.provenance/ns (a canonical source-namespace STRING — EP-0023
;; §Namespace-Selected Images).
(def ^:private inc-desc
  {:kind :event :id :counter/inc :rf.provenance/ns "docs.counter.v2"})

(def ^:private value-desc
  {:kind :sub :id :counter/value :rf.provenance/ns "docs.counter.v2"})

;; An inline descriptor — identified by its containing image id + inline coord
;; (EP-0023 §Image Fragments), not a source namespace.
(def ^:private inline-desc
  {:kind :event :id :counter/inc
   :rf.provenance/image :test/small
   :rf.provenance/inline [:reg-event :counter/inc]})

;; A framework STANDARD descriptor — tagged `:standard true` by assembly.
(def ^:private standard-desc
  {:kind :interceptor :id :rf.interceptor/path :standard true})

;; A sealed image generation — the inert value `image-assembly/assemble`
;; returns (`:rf.gen/resolver` / `:rf.gen/images` / `:rf.gen/kinds`). EP-0026
;; (rf2-dlvmpc) retired `:rf.gen/requires` with the image-capability feature.
(def ^:private counter-generation
  {:rf.gen/resolver {[:event :counter/inc]   inc-desc
                     [:sub   :counter/value] value-desc}
   :rf.gen/images   [{:rf.image/id :docs.counter/v2 :rf.image/include-ns ["docs.counter.v2"]}]
   :rf.gen/kinds    #{:event :sub}})

;; A SECOND generation where the SAME id (:counter/inc) resolves to a
;; DIFFERENT descriptor (an inline one) — the same-id / different-image story.
(def ^:private other-generation
  {:rf.gen/resolver {[:event :counter/inc] inline-desc}
   :rf.gen/images   [{:rf.image/id :test/small}]
   :rf.gen/kinds    #{:event}})

;; A live frame OBJECT — the inert map `make-frame` returns. EP-0026
;; (rf2-dlvmpc) retired the `:rf.frame/capabilities` slot.
(def ^:private counter-frame
  {:rf.frame/object       true
   :rf.frame/generation   counter-generation
   :rf.frame/id           :counter/main})

(def ^:private other-frame
  {:rf.frame/object     true
   :rf.frame/generation other-generation
   :rf.frame/id         :counter/alt})

;; A pure resolver fn (the role `image-assembly/resolve-descriptor` plays).
(defn- resolve-fn [generation kind id]
  (get (:rf.gen/resolver generation) [kind id]))

;; ---- descriptor-provenance ----------------------------------------------

(deftest descriptor-provenance-source-ns
  (testing "a registered descriptor projects to its source-namespace provenance"
    (is (= {:kind :rf.prov/ns :ns "docs.counter.v2"}
           (h/descriptor-provenance inc-desc)))))

(deftest descriptor-provenance-inline
  (testing "an inline descriptor projects to its image id + inline coordinate"
    (is (= {:kind :rf.prov/inline :image :test/small :inline [:reg-event :counter/inc]}
           (h/descriptor-provenance inline-desc)))))

(deftest descriptor-provenance-standard
  (testing "a framework standard descriptor projects to the standard marker"
    (is (= {:kind :rf.prov/standard} (h/descriptor-provenance standard-desc)))))

(deftest descriptor-provenance-unknown
  (testing "a descriptor with no recognizable provenance projects to :unknown"
    (is (= {:kind :rf.prov/unknown} (h/descriptor-provenance {:kind :event :id :x})))))

;; ---- project-generation: image as a [kind id] descriptor set -------------

(deftest project-generation-shape
  (testing "a sealed generation projects to the image-row: composed image ids,
            sorted kinds, descriptor count, and one descriptor
            row per [kind id] (sorted, each with provenance)"
    (let [img (h/project-generation counter-generation)]
      (is (= [:docs.counter/v2] (:images img)))
      (is (not (contains? img :requires))
          "EP-0026: no :requires — image capabilities are removed")
      (is (= [:event :sub] (:kinds img)) "kinds sorted by str")
      (is (= 2 (:descriptor-count img)))
      (is (= [{:kind :event :id :counter/inc
               :provenance {:kind :rf.prov/ns :ns "docs.counter.v2"}}
              {:kind :sub :id :counter/value
               :provenance {:kind :rf.prov/ns :ns "docs.counter.v2"}}]
             (:descriptors img))
          "one row per resolved [kind id], sorted by (kind id) str"))))

(deftest project-generation-nil-is-empty
  (testing "a nil generation projects to the empty image-row (no descriptors)"
    (let [img (h/project-generation nil)]
      (is (= [] (:images img)))
      (is (not (contains? img :requires)) "EP-0026: no :requires field")
      (is (= [] (:kinds img)))
      (is (= 0 (:descriptor-count img)))
      (is (= [] (:descriptors img))))))

;; ---- project-frame-row: frame as an execution context -------------------

(deftest project-frame-row-shape
  (testing "a live frame projects to the frame-row: id, not-anonymous, and the
            resolved IMAGE it runs (its generation's descriptors)"
    (let [row (h/project-frame-row :counter/main counter-frame)]
      (is (= :counter/main (:frame-id row)))
      (is (false? (:anonymous? row)))
      (is (false? (:has-adapter? row)))
      (is (not (contains? row :capabilities))
          "EP-0026: no :capabilities — image capabilities are removed")
      (is (= 2 (:descriptor-count (:image row)))
          "the frame POINTS AT its generation — projected as the image"))))

(deftest project-frame-row-anonymous
  (testing "a direct (no-id) frame object projects as anonymous"
    (let [row (h/project-frame-row nil (dissoc counter-frame :rf.frame/id))]
      (is (nil? (:frame-id row)))
      (is (true? (:anonymous? row))))))

(deftest project-frames-sorted
  (testing "the live-frame registry projects to frame-rows sorted by frame-id str"
    (let [rows (h/project-frames {:counter/main counter-frame
                                  :counter/alt  other-frame})]
      (is (= [:counter/alt :counter/main] (mapv :frame-id rows))))))

;; ---- resolve-in-frame: frame-derived resolution path --------------------

(deftest resolve-in-frame-same-id-different-image
  (testing "the SAME [kind id] resolves to DIFFERENT descriptors in frames
            running different images — the frame-derived resolution path
            (EP-0023 §Specification)"
    (let [in-counter (h/resolve-in-frame resolve-fn counter-frame :event :counter/inc)
          in-other   (h/resolve-in-frame resolve-fn other-frame   :event :counter/inc)]
      (is (true? (:resolved? in-counter)))
      (is (= {:kind :rf.prov/ns :ns "docs.counter.v2"} (:provenance in-counter))
          "counter-frame resolves :counter/inc to its source-ns descriptor")
      (is (true? (:resolved? in-other)))
      (is (= {:kind :rf.prov/inline :image :test/small :inline [:reg-event :counter/inc]}
             (:provenance in-other))
          "other-frame resolves the SAME id to ITS image's inline descriptor")
      (is (not= (:provenance in-counter) (:provenance in-other))
          "same id, different image → different resolution"))))

(deftest resolve-in-frame-unresolved
  (testing "a [kind id] not in the frame's generation resolves to nothing"
    (let [r (h/resolve-in-frame resolve-fn counter-frame :event :nope/missing)]
      (is (false? (:resolved? r)))
      (is (nil? (:provenance r))))))

;; ---- project-image-view: demand-gated top-level -------------------------

(deftest project-image-view-with-frames
  (testing "the live registry projects to frame-rows + :images? true when at
            least one frame runs a generation with descriptors"
    (let [data (h/project-image-view {:counter/main counter-frame})]
      (is (= 1 (:frame-count data)))
      (is (true? (:images? data)))
      (is (= :counter/main (:frame-id (first (:frames data))))))))

(deftest project-image-view-empty-is-no-images
  (testing "an empty registry → :images? false (the honest not-using-images
            state — EP-0023's public model is opt-in)"
    (let [data (h/project-image-view {})]
      (is (= 0 (:frame-count data)))
      (is (false? (:images? data)))
      (is (= [] (:frames data))))))

(deftest project-image-view-frameless-generation-is-no-images
  (testing "a frame whose generation resolves ZERO descriptors does not flip
            :images? — there is no image content to show"
    (let [empty-frame {:rf.frame/object true
                       :rf.frame/id :empty/main
                       :rf.frame/generation {:rf.gen/resolver {}}}
          data        (h/project-image-view {:empty/main empty-frame})]
      (is (= 1 (:frame-count data)))
      (is (false? (:images? data))))))

;; ---- display strings -----------------------------------------------------

(deftest provenance-summary-strings
  (testing "provenance summaries read cleanly per kind"
    (is (= "docs.counter.v2"
           (h/provenance-summary {:kind :rf.prov/ns :ns "docs.counter.v2"})))
    (is (= "inline :test/small [:reg-event :counter/inc]"
           (h/provenance-summary {:kind :rf.prov/inline :image :test/small
                                  :inline [:reg-event :counter/inc]})))
    (is (= "framework standard"
           (h/provenance-summary {:kind :rf.prov/standard})))
    (is (= "—" (h/provenance-summary {:kind :rf.prov/unknown})))))

(deftest image-row-summary-string
  (testing "the image summary reads N descriptors · K kinds with correct plurals"
    (is (= "2 descriptors · 2 kinds"
           (h/image-row-summary (h/project-generation counter-generation))))
    (is (= "1 descriptor · 1 kind"
           (h/image-row-summary (h/project-generation other-generation))))))
