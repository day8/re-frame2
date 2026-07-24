(ns re-frame.freehand.tool-reader-jvm-test
  "rf2-hytu5 — the Freehand tool-tier reader door, and the compile-tier
  findings it exists to reach.

  Two defects, one seam. `re-frame.freehand.tool` did not exist at all — its
  only mention in the tree was a docstring in
  `re-frame.freehand.compiler.a11y` promising `tool/view-manifest` to a reader
  that could not resolve it — and `structural-manifest` carried no
  `:diagnostics` key, so every a11y finding was collected in the compiler env
  and published nowhere.

  Suppression is what makes that second half matter. A SUPPRESSED finding is
  deliberately silent: it prints nothing, and the manifest fact carrying its
  reason was its only trace. With no `:diagnostics` on the manifest, a
  suppressed finding was simply lost — the author's reason recorded into a map
  that no reader could ever open.

  Everything below reads a REAL `v/defview {:compiled true}` declaration
  through the production door. The findings ride SUPPRESSED so the suite is
  silent on stderr, which is also the arm that was unreachable."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.tool :as tool]))

(def ^:private suppress-reason
  "drag surface; the keyboard path is the toolbar button")

(v/defview clean-view
  "A compiled declaration the a11y roster has nothing to say about."
  {:compiled true}
  [{:keys [title]}]
  [:section.panel [:h2 title]])

(v/defview flagged-view
  "A compiled declaration carrying one SUPPRESSED a11y finding — the case whose
  only trace is the manifest fact."
  {:compiled true}
  [_]
  [:div
   ^{:rf.ui/suppress
     {:rf.ui.compile/a11y-click-non-interactive
      "drag surface; the keyboard path is the toolbar button"}}
   [:div {:on-click [:row/open]} "Open"]])

(v/defview interpreted-view
  "The paved path: no analysis, so nothing statically known."
  [{:keys [title]}]
  [:section title])

;; ---------------------------------------------------------------------------
;; The door is total
;; ---------------------------------------------------------------------------

(deftest view-manifest-answers-nil-for-everything-that-has-no-analysis
  (testing "A tool reads whatever it is holding — a var swept out of a
            namespace, a value from a registry it does not own. A reader that
            threw on the first non-view could not sweep at all, so every value
            has an answer and `nil` means 'nothing statically known'."
    (doseq [x [nil 42 "a string" :a/keyword [] {} identity]]
      (is (nil? (tool/view-manifest x)) (pr-str x)))
    (is (nil? (tool/view-manifest interpreted-view))
        "an interpreted declaration has no analysis to report")))

(deftest view-manifest-answers-the-compiled-declarations-own-manifest
  (testing "The read itself: a compiled declaration's manifest, returned as the
            data the declaration is already carrying."
    (let [m (tool/view-manifest clean-view)]
      (is (map? m))
      (is (= :re-frame.freehand.tool-reader-jvm-test/clean-view (:view-id m)))
      (is (= :re-frame.freehand/v1 (:grammar m)))
      (is (= m (v/manifest clean-view))
          "the tool door reads the same value the application door does — one
           manifest, two callers, never two projections that could drift"))))

;; ---------------------------------------------------------------------------
;; :diagnostics reaches a reader
;; ---------------------------------------------------------------------------

(deftest the-manifest-publishes-the-compile-tier-finding-roster
  (testing "The key that did not exist. Every compiled declaration carries a
            `:diagnostics` roster, empty when the a11y checks found nothing —
            present either way, so a reader can tell 'no findings' from 'this
            manifest predates the roster'."
    (is (contains? (tool/view-manifest clean-view) :diagnostics)
        "a clean declaration still publishes the roster")
    (is (= [] (:diagnostics (tool/view-manifest clean-view)))
        "and it is empty rather than absent")))

(deftest a-suppressed-finding-survives-to-the-reader-with-its-reason
  (testing "The fact this Bead exists for. A suppressed finding prints nothing
            — that is what suppression IS — so the manifest entry carrying the
            author's reason is its only trace. Before `:diagnostics` was
            published it had none: the reason went into the compiler env and
            stopped there."
    (let [findings (:diagnostics (tool/view-manifest flagged-view))]
      (is (= 1 (count findings))
          "the finding reaches the reader through the production door")
      (let [d (first findings)]
        (is (= :rf.ui.compile/a11y-click-non-interactive (:id d)))
        (is (true? (:suppressed? d)))
        (is (= suppress-reason (:reason d))
            "carrying the author's REASON — the whole content of a suppression")
        (is (= :div (:tag d)) "and the node it was minted from")
        (is (string? (:sid d))
            "under the compiler-minted stable site id its warning would name")
        (is (vector? (:path d)))))))

(deftest an-unflagged-declaration-is-the-control
  (testing "Non-vacuity for the row above: the two declarations differ, so a
            roster that answered `[]` to everything — or one that answered the
            same thing to everything — cannot pass both."
    (is (not= (:diagnostics (tool/view-manifest clean-view))
              (:diagnostics (tool/view-manifest flagged-view))))))
