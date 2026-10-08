(ns day8.re-frame2-xray.panels.image-view-helpers-cljs-test
  "JVM + CLJS coverage for the EP-0023 image/frame pure-data helpers
  (the `image -> frame -> event stream` public model on the Module-view
  tab).

  Verifies the EP-0023 nouns the surface presents:

    - **image** as a registration-set VALUE — a sealed generation projected
      into its `[kind id]` descriptor set with per-descriptor provenance
      (`project-generation` / `descriptor-provenance`);
    - **frame** as an EXECUTION CONTEXT pointing at its generation
      (`project-frame-row` / `project-frames`).

  Plus the demand-gated top-level projection (`project-image-view` →
  `:images?`) and the display strings. All algebra is pure `data -> data`, so
  this runs under the JVM test target with hand-built generation/frame fixtures
  (the inert shapes `assemble` / `make-frame` produce)."
  (:require #?(:clj  [clojure.test :refer [are deftest is testing]]
               :cljs [cljs.test :refer-macros [are deftest is testing]])
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
;; returns (`:rf.gen/resolver` / `:rf.gen/images` / `:rf.gen/kinds`). Under
;; EP-0026 there is no `:rf.gen/requires` (no image-capability feature).
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

;; A live frame OBJECT — the inert map `make-frame` returns. Under EP-0026
;; there is no `:rf.frame/capabilities` slot.
(def ^:private counter-frame
  {:rf.frame/object       true
   :rf.frame/generation   counter-generation
   :rf.frame/id           :counter/main})

(def ^:private other-frame
  {:rf.frame/object     true
   :rf.frame/generation other-generation
   :rf.frame/id         :counter/alt})

;; ---- descriptor-provenance ----------------------------------------------

(deftest descriptor-provenance-projects-each-provenance-kind
  (testing "an inline descriptor projects to its image id + inline coordinate,
            a framework standard one to the standard marker, and one with no
            recognisable provenance to :unknown (the source-namespace kind is
            pinned by project-generation-shape)"
    (are [descriptor expected] (= expected (h/descriptor-provenance descriptor))
      inline-desc           {:kind :inline :image :test/small :inline [:reg-event :counter/inc]}
      standard-desc         {:kind :standard}
      {:kind :event :id :x} {:kind :unknown})))

;; ---- project-generation: image as a [kind id] descriptor set -------------

(deftest project-generation-shape
  (testing "a sealed generation projects to the image-row: composed image ids,
            sorted kinds, descriptor count, and one descriptor
            row per [kind id] (sorted, each with provenance)"
    (is (= {:images           [:docs.counter/v2]
            :kinds            [:event :sub]
            :descriptor-count 2
            :descriptors      [{:kind :event :id :counter/inc
                                :provenance {:kind :ns :ns "docs.counter.v2"}}
                               {:kind :sub :id :counter/value
                                :provenance {:kind :ns :ns "docs.counter.v2"}}]}
           (h/project-generation counter-generation)))))

;; ---- project-frame-row: frame as an execution context -------------------

(deftest project-frame-row-shape
  (testing "a live frame projects to the frame-row: its id, its adapter
            binding, and the resolved IMAGE it runs (its generation's
            descriptors)"
    (is (= [:counter/main false 2]
           ((juxt :frame-id :has-adapter? (comp :descriptor-count :image))
            (h/project-frame-row :counter/main counter-frame))))))

(deftest project-frames-sorted
  (testing "the live-frame registry projects to frame-rows sorted by frame-id str"
    (let [rows (h/project-frames {:counter/main counter-frame
                                  :counter/alt  other-frame})]
      (is (= [:counter/alt :counter/main] (mapv :frame-id rows))))))

;; ---- project-image-view: demand-gated top-level -------------------------

(deftest project-image-view-with-frames
  (testing "the live registry projects to frame-rows + :images? true when at
            least one frame runs a generation with descriptors"
    (is (= {:frame-count 1 :images? true}
           (dissoc (h/project-image-view {:counter/main counter-frame}) :frames)))))

(deftest project-image-view-empty-is-no-images
  (testing "an empty registry → :images? false (the honest not-using-images
            state — EP-0023's public model is opt-in)"
    (is (false? (:images? (h/project-image-view {}))))))

(deftest project-image-view-frameless-generation-is-no-images
  (testing "a frame whose generation resolves ZERO descriptors does not flip
            :images? — there is no image content to show"
    (is (false? (:images? (h/project-image-view
                            {:empty/main {:rf.frame/object     true
                                          :rf.frame/id         :empty/main
                                          :rf.frame/generation {:rf.gen/resolver {}}}}))))))

;; ---- display strings -----------------------------------------------------

(deftest provenance-summary-strings
  (testing "provenance summaries read cleanly: the source namespace, or the
            inline image + coordinate"
    (is (= "docs.counter.v2"
           (h/provenance-summary {:kind :ns :ns "docs.counter.v2"})))
    (is (= "inline :test/small [:reg-event :counter/inc]"
           (h/provenance-summary {:kind :inline :image :test/small
                                  :inline [:reg-event :counter/inc]})))))

(deftest image-row-summary-string
  (testing "the image summary reads N descriptors · K kinds with correct plurals"
    (is (= "2 descriptors · 2 kinds"
           (h/image-row-summary (h/project-generation counter-generation))))
    (is (= "1 descriptor · 1 kind"
           (h/image-row-summary (h/project-generation other-generation))))))
