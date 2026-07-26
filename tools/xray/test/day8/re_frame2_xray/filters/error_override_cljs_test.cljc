(ns day8.re-frame2-xray.filters.error-override-cljs-test
  "Pure-data contract for the error-override filter bypass (rf2-jqqsh9).

  spec/018-Event-Spine.md §7 Error overrides + §5.4 (`error` never filtered
  out): an errored event a FILTER would hide is surfaced anyway. This is the
  dual-runtime regression for `filters.error-override/apply-error-overrides`
  — the data-layer half of the fix, run under both clojure.test and the
  `:node-test` bundle (rf2-odlm3)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-xray.filters.error-override :as eo]))

;; ---- fixtures -----------------------------------------------------------
;;
;; An event-bundle is errored iff its `:other` bucket carries an error trace
;; (`:op-type :error` / `:rf.error/*`), per
;; `event-status-colour/event-bundle-outcome`.

(def ^:private errored-out
  {:event [:auth/login {}] :dispatch-id 1 :other [{:op-type :error}]})

(def ^:private clean-kept
  {:event [:cart/add {}] :dispatch-id 2 :other []})

(def ^:private clean-dropped
  {:event [:mouse/move {}] :dispatch-id 3 :other []})

(def ^:private errored-in-non-match
  {:event [:order/retry {}] :dispatch-id 4 :other [{:operation :rf.error/handler-threw}]})

;; ---- errored? classifier ------------------------------------------------

(deftest errored?-keys-off-the-shared-outcome-classifier
  (is (true?  (eo/errored? errored-out)))
  (is (true?  (eo/errored? errored-in-non-match))
      "the :rf.error/* namespace fallback also classifies as errored")
  (is (false? (eo/errored? clean-kept)))
  (is (false? (eo/errored? clean-dropped))))

;; ---- apply-error-overrides ----------------------------------------------

(deftest re-adds-an-errored-bundle-an-OUT-pill-dropped
  (testing "an errored event an OUT filter removed is surfaced anyway, tagged
            :rf.xray/filter-bypassed?, in its original scoped position"
    (let [scoped   [errored-out clean-kept clean-dropped]
          filtered [clean-kept]                     ; pills dropped errored + clean-dropped
          result   (eo/apply-error-overrides scoped filtered true)]
      (is (= 2 (count result)) "the errored drop is re-added; the clean drop stays hidden")
      (is (= [1 2] (mapv :dispatch-id result))
          "scoped order is preserved — errored bundle re-inserted ahead of the kept one")
      (is (true? (:rf.xray/filter-bypassed? (first result)))
          "the re-added errored bundle is tagged for the filter-bypass cue")
      (is (nil? (:rf.xray/filter-bypassed? (second result)))
          "a bundle the filters KEPT is never tagged"))))

(deftest re-adds-an-errored-bundle-that-fails-to-match-an-IN-pill
  (testing "an errored event dropped because it failed to match an active IN
            pill is ALSO surfaced (§7: any filter drop, not just OUT matches)"
    (let [scoped   [clean-kept errored-in-non-match]
          filtered [clean-kept]                     ; IN pill kept clean-kept, dropped the errored non-match
          result   (eo/apply-error-overrides scoped filtered true)]
      (is (= #{2 4} (set (mapv :dispatch-id result))))
      (is (true? (:rf.xray/filter-bypassed?
                   (first (filter #(= 4 (:dispatch-id %)) result))))))))

(deftest disabled-is-a-passthrough-noop
  (testing "with the bypass disabled the filtered list passes through unchanged
            — errored drops stay hidden (opt-out honoured)"
    (let [scoped   [errored-out clean-kept]
          filtered [clean-kept]
          result   (eo/apply-error-overrides scoped filtered false)]
      (is (= [2] (mapv :dispatch-id result)))
      (is (not-any? :rf.xray/filter-bypassed? result)
          "nothing is tagged when the override is off"))))

(deftest frame-scope-drops-are-never-re-added
  (testing "the override operates on the frame-SCOPED list — an errored event
            already excluded by the VIEW SCOPE (absent from `scoped`) is never
            dragged back in (frame is a view scope, not a filter)"
    ;; `scoped` is post-view-scope, so a cross-frame errored bundle simply is
    ;; NOT a member of scoped and cannot be re-added.
    (let [scoped   [clean-kept]
          filtered [clean-kept]
          result   (eo/apply-error-overrides scoped filtered true)]
      (is (= [2] (mapv :dispatch-id result))
          "no cross-frame errored bundle appears — only scoped members qualify"))))

(deftest all-kept-is-identity-in-value
  (testing "when the filters dropped nothing, every bundle is returned as-is,
            untagged"
    (let [scoped   [errored-out clean-kept]
          filtered [errored-out clean-kept]         ; no filter active
          result   (eo/apply-error-overrides scoped filtered true)]
      (is (= [1 2] (mapv :dispatch-id result)))
      (is (not-any? :rf.xray/filter-bypassed? result)
          "an errored bundle the filters KEPT is not tagged (it was never bypassed)"))))
