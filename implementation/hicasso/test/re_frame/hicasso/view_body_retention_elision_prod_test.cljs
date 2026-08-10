(ns re-frame.hicasso.view-body-retention-elision-prod-test
  "PRODUCTION ERASURE OF THE RETAINED VIEW BODY (rf2-kjf5).

  `mint-view!` attaches the body function to the minted head so the L0–L2
  test kit can render `[some-view …]` without React
  (`re-frame.hicasso.impl.codec/retain-body!`). The operator's ruling makes
  that retention **dev only** and non-negotiably so: the write sits inside
  `(when ^boolean js/goog.DEBUG …)`, and under `:advanced` +
  `goog.DEBUG=false` the Closure compiler removes it — the call, the slot
  and `retain-body!` behind it.

  This file compiles under `:browser-test-prod-elision`, the dedicated
  build with `goog.DEBUG=false` + `:advanced`, so what is asserted below is
  a genuine constant-fold rather than a `with-redefs`. Every assertion here
  would FAIL under `goog.DEBUG=true`: they assert ABSENCE, which is only
  true in a production build.

  ## Why there is no bundle scan beside it

  `re-frame.hicasso.error-source-coord-elision-prod-test` is only half of
  its proof — `check_source_coord_elision.cjs` scans the release artefact,
  because the thing that leaks there is a STRING, and a string Closure kept
  but nothing reads is invisible from inside the page. What leaks here is a
  FUNCTION REFERENCE on a live object, which is exactly what a page can
  see. [[a-production-head-retains-no-body]] reads it off the real advanced
  bundle's own head, so a scan would prove nothing this cannot.

  The [[no-own-property-of-a-production-head-holds-a-function]] row is the
  part that does not depend on knowing the slot's name, so a renamed or
  re-spelled property cannot walk past it.

  ## The declarations are next door, and that is not tidiness

  `re-frame.hicasso.coord-sentinel-source` carries them, for the reason
  rf2-hic-007 recorded: `cljs.test` stamps `:file` into the report map of
  every `deftest` and every `is`, so a test namespace names itself in a
  release bundle before anything else does. That namespace carries no
  `deftest`, and its `sentinel-row` is minted by the same `h/defview` door
  every application view is."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.hicasso.coord-sentinel-source :as sentinel]
            [re-frame.hicasso.impl.codec :as codec]))

(deftest the-sentinel-head-is-a-real-minted-boundary-in-this-bundle
  (testing "the POSITIVE CONTROL, and the reason the absences below are not
            green for the wrong reason: an unminted, missing or
            wholly-DCE'd head would satisfy every one of them trivially"
    (is (fn? sentinel/sentinel-row))
    (is (true? (codec/boundary-head? sentinel/sentinel-row)))
    (is (= sentinel/view-name (.-displayName sentinel/sentinel-row))
        "the view name is NOT elided — it is the measure id and the React
         DevTools label")))

(deftest a-production-head-retains-no-body
  (testing "`(when ^boolean js/goog.DEBUG (codec/retain-body! head body-fn))`
            in `mint-view!` folds away whole, so the slot the test kit reads
            was never written and the body is unreachable from the head"
    (is (nil? (codec/retained-body sentinel/sentinel-row)))))

(deftest no-own-property-of-a-production-head-holds-a-function
  (testing "the slot-name-independent form of the same claim: the body is a
            FUNCTION, and a production head hangs no function off itself
            under any name at all — so a renamed, re-spelled or
            accidentally-duplicated retention is caught here even though the
            row above reads one slot"
    (let [held (into {}
                     (comp (map (fn [k] [k (unchecked-get sentinel/sentinel-row k)]))
                           (filter (fn [[_ v]] (fn? v))))
                     (js->clj (js/Object.keys sentinel/sentinel-row)))]
      (is (= {} held)
          "a production head's own properties are the display name and the
           boundary markers — never a body"))))
