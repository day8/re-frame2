(ns day8.re-frame2-xray.panels.reactivity.app-db-diff-reactivity-cljs-test
  "Sub-reactivity guard for the App-db Diff panel's primary sub,
  `:rf.xray/app-db-current+diff` (→ `:rf.xray/app-db-state`): it re-fires
  with the focused epoch's values when focus moves, so the panel never
  freezes on a previously-pinned epoch. The spine slot's own reactivity is
  `focus-sub-live-auto-follows-epoch-id-rf2-70tkv`'s."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.test-helpers.sub-reactivity :as h]))

(use-fixtures :each h/fixture)

;; ---- fixtures -----------------------------------------------------------

(def cascades
  [(h/cascade :c1 :rf/default)
   (h/cascade :c2 :rf/default)])

(def epoch-history
  [(h/mock-epoch :e1 :c1 {} {:counter 1})
   (h/mock-epoch :e2 :c2 {:counter 1} {:counter 2})])

;; ---- tests --------------------------------------------------------------

(deftest live-mode-auto-follows-new-cascade-rf2-70tkv
  (testing "LIVE on :e1; a new cascade :c2 arrives with epoch :e2 and the
            panel advances to it without a click"
    (h/setup-xray-frame!)
    (h/seed-cascades! [(first cascades)])
    (h/seed-epoch-history! [(first epoch-history)])
    (is (= :e1 (:epoch-id (h/read-sub :rf.xray/app-db-current+diff))))
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    (is (= :e2 (:epoch-id (h/read-sub :rf.xray/app-db-current+diff))))))

;; ---- atomic per-epoch-delta before+after --------------------------------
;;
;; ONE sub resolves the focused record's slots in a single computation, so
;; `:before` can never lag `:epoch-id` (the zoom-nav stale-frame flash), and
;; `:value` is that epoch's own `:db-after` — the per-epoch delta, not the
;; cumulative live-vs-`db-before(N)`.

(defn- db-before-of [epoch-id]
  (some (fn [r] (when (= epoch-id (:epoch-id r)) (:db-before r)))
        epoch-history))

(defn- db-after-of [epoch-id]
  (some (fn [r] (when (= epoch-id (:epoch-id r)) (:db-after r)))
        epoch-history))

(defn- assert-atomic!
  "Assert `:before` and `:value` are the `:db-before` / `:db-after` of the
  sub's own `:epoch-id`, and return that id."
  [label]
  (let [{:keys [value before epoch-id]} (h/read-sub :rf.xray/app-db-current+diff)]
    (is (= (db-before-of epoch-id) before)
        (str label " — :before must equal the :db-before of :epoch-id; got before="
             (pr-str before) " epoch-id=" (pr-str epoch-id)))
    (is (= (db-after-of epoch-id) value)
        (str label " — :value must equal the :db-after of :epoch-id; got value="
             (pr-str value) " epoch-id=" (pr-str epoch-id)))
    epoch-id))

(deftest current+diff-before-is-atomic-with-epoch-id
  (h/setup-xray-frame!)
  (h/seed-cascades! cascades)
  (h/seed-epoch-history! epoch-history)
  (h/focus-cascade! :c1)
  (is (= :e1 (assert-atomic! "focus :c1")))
  (h/focus-cascade! :c2)
  (is (= :e2 (assert-atomic! "focus :c2")))
  (h/focus-cascade! :c1)
  (is (= :e1 (assert-atomic! "focus :c1 (return)"))))

;; ---- no later-event bleed onto an earlier selection ---------------------
;;
;; Focusing a NON-head epoch is what separates the per-epoch delta from the
;; cumulative diff: at the head the two agree.

(def bleed-cascades
  [(h/cascade :early :rf/default)
   (h/cascade :late  :rf/default)])

(def bleed-history
  ;; :early adds :media/deep; the LATER :late adds :media/shallow.
  [(h/mock-epoch :ep-early :early
                 {:counter 1}
                 {:counter 1 :media/deep :on})
   (h/mock-epoch :ep-late  :late
                 {:counter 1 :media/deep :on}
                 {:counter 1 :media/deep :on :media/shallow :on})])

(deftest earlier-epoch-shows-own-delta-no-later-bleed
  (h/setup-xray-frame!)
  (h/seed-cascades! bleed-cascades)
  (h/seed-epoch-history! bleed-history)
  (h/focus-cascade! :early)
  (let [{:keys [value before epoch-id]} (h/read-sub :rf.xray/app-db-current+diff)]
    (is (= :ep-early epoch-id))
    (is (= {:counter 1 :media/deep :on} value)
        "the later event's :media/shallow does not bleed into :value")
    (is (= {:counter 1} before)))
  (testing "control — the LATER epoch shows its own delta"
    (h/focus-cascade! :late)
    (let [{:keys [value before]} (h/read-sub :rf.xray/app-db-current+diff)]
      (is (= [:media/shallow] (vec (remove (set (keys before)) (keys value))))))))

(deftest app-db-state-section-model-tracks-focused-before
  (testing "the section model's :before-top re-derives per focused epoch"
    (h/setup-xray-frame!)
    (h/seed-cascades! cascades)
    (h/seed-epoch-history! epoch-history)
    (h/focus-cascade! :c1)
    (let [m1 (h/read-sub :rf.xray/app-db-state)]
      (h/focus-cascade! :c2)
      (is (not= (:before-top m1) (:before-top (h/read-sub :rf.xray/app-db-state)))))))
