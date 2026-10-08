(ns re-frame.story.recorder.play-export-test
  "The recorder → :script translator: per-event and per-entry steps, wait
  insertion, auto-assert, and snippets that read back and parse clean."
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is]]
            [clojure.edn :as edn]
            [re-frame.story.play.runner             :as rf.story.play.runner]
            [re-frame.story.recorder.play-export    :as rf.story.recorder.play-export]
            [re-frame.story.recorder.selector       :as rf.story.recorder.selector]))

;; ---- per-event translation -----------------------------------------------

(deftest event-step-malformed-yields-nil
  (is (nil? (rf.story.recorder.play-export/event->step nil)))
  (is (nil? (rf.story.recorder.play-export/event->step ["not-keyword"]))))

(deftest event-step-carries-captured-cofx
  (is (= [:dispatch [:counter/inc] {:rf.cofx {:rf/time-ms 123 :counter/delta 4}}]
         (rf.story.recorder.play-export/event->step [:counter/inc] {:rf/time-ms 123 :counter/delta 4})))
  (is (= [:dispatch [:counter/inc]]
         (rf.story.recorder.play-export/event->step [:counter/inc] {}))
      "an empty cofx map is treated as absent — no opts slot"))

(deftest recording-threads-parallel-cofx-vector
  (is (= [[:dispatch [:counter/inc] {:rf.cofx {:rf/time-ms 100 :counter/delta 4}}]
          [:dispatch [:auth/login {:user "x"}]]
          [:dispatch [:counter/dec] {:rf.cofx {:rf/time-ms 300}}]]
         (:script (rf.story.recorder.play-export/recording->script-body
                    [[:counter/inc] [:auth/login {:user "x"}] [:counter/dec]]
                    {:cofx [{:rf/time-ms 100 :counter/delta 4} nil {:rf/time-ms 300}]})))))

;; ---- recording-level translation -----------------------------------------

(deftest blank-name-omitted
  (are [n] (not (contains? (rf.story.recorder.play-export/recording->script-body [] {:name n}) :name))
    ""
    nil))

(deftest mixed-events-translate-with-tags
  (is (= [[:dispatch       [:counter/inc]]
          [:dispatch-sync  [:rf.assert/path-equals [:n] 1]]
          [:dispatch       [:counter/dec]]]
         (:script (rf.story.recorder.play-export/recording->script-body
                    [[:counter/inc] [:rf.assert/path-equals [:n] 1] [:rf/redacted] [:counter/dec]]
                    {})))))

;; ---- auto-assert ---------------------------------------------------------

(deftest auto-assert-from-final-db-no-seed
  (is (= [[:dispatch [:counter/inc]]
          [:assert-db [:n] 1]
          [:assert-db [:who] "alice"]]
         (:script (rf.story.recorder.play-export/recording->script-body
                    [[:counter/inc]]
                    {:auto-assert? true :final-db {:n 1 :who "alice"}})))))

(deftest auto-assert-from-seed-diff
  (is (= [[:dispatch [:counter/inc]]
          [:assert-db [:n] 1]
          [:assert-db [:extra] :added]]
         (:script (rf.story.recorder.play-export/recording->script-body
                    [[:counter/inc]]
                    {:auto-assert? true
                     :seed-db      {:n 0 :who "alice"}
                     :final-db     {:n 1 :who "alice" :extra :added}})))
      "only the changed and new keys are asserted")
  (let [db {:n 0 :who "alice"}]
    (is (= [[:dispatch [:counter/inc]]]
           (:script (rf.story.recorder.play-export/recording->script-body
                      [[:counter/inc]]
                      {:auto-assert? true :seed-db db :final-db db})))
        "a recording that changed nothing never falls back to asserting every key")))

(deftest auto-assert-never-asserts-story-bookkeeping
  (let [record {:assertion :rf.assert/path-equals :passed? true :dispatch-id 5}
        seed   {:rf.story/lifecycle :loading :rf.story/assertions [] :n 0}
        final  {:rf.story/lifecycle :ready :rf.story/assertions [record] :n 1}]
    (is (= [[:assert-db [:n] 1]]
           (rf.story.recorder.play-export/auto-assert-steps final {:seed-db seed})))
    (is (= [[:assert-db [:n] 1]]
           (rf.story.recorder.play-export/auto-assert-steps final {})))))

(deftest auto-assert-cap-respected
  (is (= 3 (count (:script (rf.story.recorder.play-export/recording->script-body
                             []
                             {:auto-assert?        true
                              :final-db            (into {} (map (fn [i] [(keyword (str "k" i)) i])) (range 20))
                              :max-auto-assertions 3}))))))

(deftest auto-assert-off-default
  (is (= [[:dispatch [:counter/inc]]]
         (:script (rf.story.recorder.play-export/recording->script-body
                    [[:counter/inc]]
                    {:final-db {:n 1 :a 2 :b 3}})))))

(deftest auto-assert-no-final-db-noop
  (is (= [[:dispatch [:counter/inc]]]
         (:script (rf.story.recorder.play-export/recording->script-body
                    [[:counter/inc]]
                    {:auto-assert? true})))))

;; ---- render round-trip ---------------------------------------------------

(deftest render-script-body-round-trips
  (let [empty-spec (rf.story.recorder.play-export/recording->script-body [])]
    (is (= {:script [] :auto-run? true} empty-spec))
    (doseq [spec [empty-spec
                  (rf.story.recorder.play-export/recording->script-body
                    [[:counter/inc] [:counter/dec]]
                    {:name "round trip" :auto-run? true})]]
      (is (= spec (edn/read-string (rf.story.recorder.play-export/render-script-body spec)))))))

(deftest render-variant-form-round-trips
  (is (= '(rf.story/reg-variant :story.x/recorded
            {:extends :story.x/source
             :script  {:auto-run? false :script [[:dispatch [:counter/inc]]]}})
         (edn/read-string
           (rf.story.recorder.play-export/render-variant-form
             (rf.story.recorder.play-export/recording->script-body [[:counter/inc]] {:auto-run? false})
             {:variant-id :story.x/recorded :extends :story.x/source})))))

(deftest render-variant-form-default-alias-and-id
  (is (= '(rf.story/reg-variant :story.recorded/play-export
            {:script {:auto-run? true :script []}})
         (edn/read-string
           (rf.story.recorder.play-export/render-variant-form {:script [] :auto-run? true} {})))))

;; ---- runner round-trip ---------------------------------------------------

(deftest exported-script-survives-runner-parse-spec
  (let [spec   (rf.story.recorder.play-export/recording->script-body
                 [{:kind :event/dispatch :event [:counter/inc] :t 0}
                  {:kind :event/dispatch :event [:rf.assert/path-equals [:n] 1] :t 0}
                  {:kind :dom/click :selector "[data-test=\"b\"]" :t 80}
                  {:kind :dom/type  :selector "[id=\"x\"]" :text "hi" :t 200}]
                 {:name "rt" :auto-assert? true :final-db {:n 1}})
        parsed (rf.story.play.runner/parse-spec spec)]
    (is (= spec parsed) "the runner parses the exported spec identically")
    (is (= [] (rf.story.play.runner/validate-script (:script parsed))))))

;; ---- entry->step ---------------------------------------------------------

(deftest entry->step-translates-each-dom-kind-and-drops-the-rest
  (are [entry step] (= step (rf.story.recorder.play-export/entry->step entry))
    {:kind :dom/click :selector "[data-test=\"submit\"]" :t 250}
    [:click "[data-test=\"submit\"]"]

    {:kind :dom/type :selector "[id=\"name\"]" :text "alice" :t 300}
    [:type "[id=\"name\"]" "alice"]

    ;; a missing :text types the empty string
    {:kind :dom/type :selector "[id=\"x\"]" :t 0}
    [:type "[id=\"x\"]" ""]

    ;; a submission replays as a click on the form
    {:kind :dom/submit :selector "[id=\"login-form\"]" :t 0}
    [:click "[id=\"login-form\"]"]

    {:kind :unknown :selector "x" :t 0}
    nil

    nil
    nil))

;; ---- entries->steps + wait insertion -------------------------------------

(deftest entries->steps-waits-only-across-a-gap-past-the-threshold
  (are [t opts steps]
       (= steps (rf.story.recorder.play-export/entries->steps
                  [{:kind :dom/click :selector "[data-test=\"a\"]" :t 0}
                   {:kind :dom/click :selector "[data-test=\"b\"]" :t t}]
                  opts))
    100 {}
    [[:click "[data-test=\"a\"]"] [:wait 100] [:click "[data-test=\"b\"]"]]

    25 {}
    [[:click "[data-test=\"a\"]"] [:click "[data-test=\"b\"]"]]

    30 {:wait-threshold-ms 10}
    [[:click "[data-test=\"a\"]"] [:wait 30] [:click "[data-test=\"b\"]"]]))

(deftest redacted-entries-do-not-leave-orphan-waits
  (is (= [[:dispatch [:counter/inc]]
          [:wait 200]
          [:dispatch [:counter/dec]]]
         (rf.story.recorder.play-export/entries->steps
           [{:kind :event/dispatch :event [:counter/inc]   :t 0}
            {:kind :event/dispatch :event [:rf/redacted]   :t 100}
            {:kind :event/dispatch :event [:counter/dec]   :t 200}]))))

;; ---- :event/timer-child — the forced wait for a re-armed timer ------------
;;
;; A fired `:dispatch-later` child records as a payload-free marker, never a
;; step, because replaying its root re-arms the timer. Its wait must bring the
;; replay up to the time the child fired, measured from the replay's own
;; clock, which runs behind the recorded one by every sub-threshold gap the
;; export folded out.

(deftest timer-child-wait-covers-a-folded-out-gap
  (is (= [[:dispatch [:t/root]]
          [:dispatch [:t/other]]
          [:wait 80]]
         (rf.story.recorder.play-export/entries->steps
           [{:kind :event/dispatch :event [:t/root]  :t 0}
            {:kind :event/dispatch :event [:t/other] :t 40}
            {:kind :event/timer-child :t 80}]))))

(deftest timer-child-wait-counts-only-what-the-replay-has-not-waited
  ;; 100 waited, 30 folded out, 50 to the child.
  (is (= [[:dispatch [:t/root]]
          [:wait 100]
          [:dispatch [:t/a]]
          [:dispatch [:t/b]]
          [:wait 80]]
         (rf.story.recorder.play-export/entries->steps
           [{:kind :event/dispatch :event [:t/root] :t 0}
            {:kind :event/dispatch :event [:t/a]    :t 100}
            {:kind :event/dispatch :event [:t/b]    :t 130}
            {:kind :event/timer-child :t 180}]))))

(deftest ordinary-gaps-still-fold-around-a-timer-child
  (is (= [[:dispatch [:t/root]]
          [:dispatch [:t/other]]
          [:wait 80]
          [:click "[data-test=\"a\"]"]
          [:wait 60]
          [:click "[data-test=\"b\"]"]]
         (rf.story.recorder.play-export/entries->steps
           [{:kind :event/dispatch :event [:t/root]  :t 0}
            {:kind :event/dispatch :event [:t/other] :t 40}
            {:kind :event/timer-child :t 80}
            {:kind :dom/click :selector "[data-test=\"a\"]" :t 100}
            {:kind :dom/click :selector "[data-test=\"b\"]" :t 160}]))))

(deftest timer-child-wait-never-undercuts-its-scheduled-delay
  ;; children after a root dispatched at 0 → the waits they emit
  (are [children waits]
       (= (into [[:dispatch [:t/root]]] (map (fn [w] [:wait w])) waits)
          (rf.story.recorder.play-export/entries->steps
            (into [{:kind :event/dispatch :event [:t/root] :t 0}] children)))
    ;; fired a millisecond early: the wait still covers the scheduled delay
    [{:kind :event/timer-child :t 79 :ms 80}]                                          [80]
    ;; a child that re-arms its own timer: the next wait counts from its firing
    [{:kind :event/timer-child :t 80 :ms 80} {:kind :event/timer-child :t 159 :ms 80}] [80 80]
    ;; a fractional delay rounds up
    [{:kind :event/timer-child :t 79 :ms 80.5}]                                        [81]
    ;; a measured gap past the delay still catches the replay up
    [{:kind :event/timer-child :t 95 :ms 80}]                                          [95]))

;; ---- recording->script-body with the rich :entries shape -----------------

(deftest recording-from-entries-respects-wait-threshold-opt
  (is (= [[:dispatch [:counter/inc]]
          [:dispatch [:counter/dec]]]
         (:script (rf.story.recorder.play-export/recording->script-body
                    [{:kind :event/dispatch :event [:counter/inc] :t 0}
                     {:kind :event/dispatch :event [:counter/dec] :t 75}]
                    {:wait-threshold-ms 100})))))

;; ---- a positional selector carries the harden hint ------

(deftest positional-selector-steps-carry-the-harden-hint
  (let [input-sel  (rf.story.recorder.selector/pick-selector
                     {:tag "input" :attrs {} :index-of-type 1})
        button-sel (rf.story.recorder.selector/pick-selector
                     {:tag "button" :attrs {} :index-of-type 1})
        hooked-sel (rf.story.recorder.selector/pick-selector
                     {:tag "button" :attrs {"data-test" "save"} :index-of-type 1})
        {:keys [spec snippet]} (rf.story.recorder.play-export/save-dialog-output
                                 [{:kind :dom/type  :selector input-sel :text "bob" :t 0}
                                  {:kind :dom/click :selector button-sel :t 0}
                                  {:kind :dom/click :selector hooked-sel :t 0}]
                                 {:variant-id :story.x/recorded
                                  :extends    :story.x/source})
        lines      (mapv str/trim (str/split-lines snippet))
        hint-for   (fn [sel] (str ";; TODO harden selector: " (pr-str sel)))
        above      (fn [step]
                     (let [s (pr-str step)
                           i (first (keep-indexed (fn [i l] (when (= s l) i)) lines))]
                       (when (and i (pos? i)) (nth lines (dec i)))))]
    (is (= [[:type input-sel "bob"] [:click button-sel] [:click hooked-sel]]
           (:script spec)))
    (is (str/includes? (str (above [:type input-sel "bob"])) (hint-for input-sel)))
    (is (str/includes? (str (above [:click button-sel])) (hint-for button-sel)))
    (is (= 2 (count (filter #(str/includes? % "TODO harden selector") lines)))
        "the data-test step carries no hint")
    (is (= (:script spec) (:script (:script (nth (edn/read-string snippet) 2))))
        "the hint is a comment: the form still reads back to the same script")))
