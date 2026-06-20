(ns re-frame.story.recorder.dom-capture-stop-flush-dom-cljs-test
  "Browser-gated DOM coverage for the recorder type-debounce STOP/DRAIN
  boundary (rf2-eztym.3).

  The sibling `dom-capture-dom-cljs-test` carries the in-session debounce
  coverage. Both files use the `-dom-cljs-test` suffix so they run in the
  `:browser-test` gate (`-dom-cljs-test$` regex) against a real DOM — the
  only place the DOM assertions, and the bug these pin (a flush firing
  AFTER `:recording?` is cleared), are observable. (The sibling was
  previously misnamed `-cljs-test`, so its DOM assertions ran in NO gate;
  fixed under rf2-jmfvc.)

  THE BUG (rf2-eztym.3): a typed `:dom/type` entry is buffered with a
  debounce timer. `flush-type-buffer!` previously routed through
  `record-dom-type!`, gated on `recording-now-ms` (nil once `:recording?`
  is false) AND `append-dom`'s own `:recording?` check. So if the recording
  was STOPPED before the pending debounce timer (or the `remove!` drain)
  fired, the last buffered keystroke was silently dropped — the generated
  `:play-script` lost its final field value.

  THE FIX: the capture-time `:t` is stamped at BUFFER time (while recording
  is live) and the drain appends via `recorder/record-dom-event-buffered!`,
  which bypasses the `:recording?` re-check. So the final keystroke survives
  a post-stop flush."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story.config :as config]
            [re-frame.story.recorder :as recorder]
            [re-frame.story.recorder.dom-capture :as dom]
            [re-frame.story.recorder.play-export :as export]))

;; ---- runtime gate --------------------------------------------------------

(defn- dom-available? []
  (and (exists? js/document)
       (some? (.-body js/document))))

;; ---- transient DOM root --------------------------------------------------

(def ^:private test-root (atom nil))

(defn- mount-root! []
  (let [el (.createElement js/document "div")]
    (.setAttribute el "data-test" "story-canvas-frame")
    (.appendChild (.-body js/document) el)
    (reset! test-root el)
    el))

(defn- unmount-root! []
  (when-let [el @test-root]
    (when (.-parentNode el)
      (.removeChild (.-parentNode el) el)))
  (reset! test-root nil))

(defn- reset-all! [f]
  (if-not (dom-available?)
    (f)
    (do
      (recorder/clear!)
      (dom/set-enabled! true)
      (dom/set-debounce-ms! 0)
      (config/set-egress-profile! config/default-egress-profile)
      (config/reset-suppressed-count!)
      (let [_ (mount-root!)]
        (dom/install! @test-root)
        (try
          (f)
          (finally
            (dom/remove!)
            (unmount-root!)
            (recorder/clear!)
            (config/set-egress-profile! config/default-egress-profile)
            (config/reset-suppressed-count!)
            (dom/set-debounce-ms! 250)))))))

(use-fixtures :each reset-all!)

;; ---- stop-before-flush regression (rf2-eztym.3) --------------------------

(deftest stop-before-flush-still-captures-final-type
  (when (dom-available?)
    (testing "a buffered keystroke survives a flush that fires AFTER the
              recording was stopped — the final :dom/type entry is NOT
              dropped when stop-recording! clears :recording? before the
              pending debounce timer (or the remove! drain) flushes"
      (recorder/start-recording! :story.x/y)
      ;; Hold the buffer open (no synchronous flush) so the keystroke is
      ;; still pending when we stop.
      (dom/set-debounce-ms! 5000)
      (let [input (.createElement js/document "input")]
        (.setAttribute input "id" "name")
        (.appendChild @test-root input)
        (set! (.-value input) "alice")
        (.dispatchEvent input (js/Event. "input" #js {:bubbles true}))
        ;; STOP first (flips :recording? false) — the order that previously
        ;; dropped the entry.
        (recorder/stop-recording!)
        (is (not (recorder/recording?))
            "sanity: the recording is stopped before the drain")
        ;; Now drain — under the bug this was a silent no-op.
        (dom/flush-type-buffer!)
        (let [type-entries (filterv #(= :dom/type (:kind %))
                                    (recorder/recorded-entries))
              entry        (first type-entries)]
          (is (= 1 (count type-entries))
              "the final buffered keystroke survived the post-stop flush")
          (is (= "alice" (:text entry))
              "the surviving entry carries the final typed value")
          (is (number? (:t entry))
              "the entry carries its capture-time :t, stamped at buffer time")
          ;; And the generated play-script carries the final field value.
          (let [spec       (export/recording->script-body
                             (recorder/recorded-entries))
                type-steps (filterv #(= :type (first %)) (:script spec))]
            (is (= [[:type (:selector entry) "alice"]] type-steps)
                "the generated :type step carries the final value")))))))

(deftest remove-after-stop-drains-final-type
  (when (dom-available?)
    (testing "dom/remove! after stop-recording! still drains the pending type
              buffer (the worst-case teardown ordering — remove! routes
              through flush-type-buffer!)"
      (recorder/start-recording! :story.x/y)
      (dom/set-debounce-ms! 5000)
      (let [input (.createElement js/document "input")]
        (.setAttribute input "id" "name")
        (.appendChild @test-root input)
        (set! (.-value input) "bob")
        (.dispatchEvent input (js/Event. "input" #js {:bubbles true}))
        (recorder/stop-recording!)
        (dom/remove!)
        (let [type-entries (filterv #(= :dom/type (:kind %))
                                    (recorder/recorded-entries))]
          (is (= 1 (count type-entries))
              "remove!'s drain captured the final keystroke after stop")
          (is (= "bob" (:text (first type-entries)))))))))

(deftest in-session-flush-unchanged
  (when (dom-available?)
    (testing "the fix does not regress the in-session path — a flush WHILE
              recording still appends exactly one entry with the final value"
      (recorder/start-recording! :story.x/y)
      (dom/set-debounce-ms! 5000)
      (let [input (.createElement js/document "input")]
        (.setAttribute input "id" "name")
        (.appendChild @test-root input)
        (doseq [v ["a" "al" "ali"]]
          (set! (.-value input) v)
          (.dispatchEvent input (js/Event. "input" #js {:bubbles true})))
        (is (recorder/recording?) "still recording at flush time")
        (dom/flush-type-buffer!)
        (let [type-entries (filterv #(= :dom/type (:kind %))
                                    (recorder/recorded-entries))]
          (is (= 1 (count type-entries))
              "rapid typing still folds to a single in-session entry")
          (is (= "ali" (:text (first type-entries)))))))))
