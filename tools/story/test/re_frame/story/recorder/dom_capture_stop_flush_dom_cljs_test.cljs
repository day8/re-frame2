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
  `:script` lost its final field value.

  THE FIX: the capture-time `:t` is stamped at BUFFER time (while recording
  is live) and the drain appends via `rf.story.recorder/record-dom-event-buffered!`,
  which bypasses the `:recording?` re-check. So the final keystroke survives
  a post-stop flush."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story.config :as rf.story.config]
            [re-frame.story.recorder :as rf.story.recorder]
            [re-frame.story.recorder.dom-capture :as rf.story.recorder.dom-capture]
            [re-frame.story.recorder.play-export :as rf.story.recorder.play-export]))

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
      (rf.story.recorder/clear!)
      (rf.story.recorder.dom-capture/set-enabled! true)
      (rf.story.recorder.dom-capture/set-debounce-ms! 0)
      (rf.story.config/set-egress-profile! rf.story.config/default-egress-profile)
      (rf.story.config/reset-suppressed-count!)
      (let [_ (mount-root!)]
        (rf.story.recorder.dom-capture/install! @test-root)
        (try
          (f)
          (finally
            (rf.story.recorder.dom-capture/remove!)
            (unmount-root!)
            (rf.story.recorder/clear!)
            (rf.story.config/set-egress-profile! rf.story.config/default-egress-profile)
            (rf.story.config/reset-suppressed-count!)
            (rf.story.recorder.dom-capture/set-debounce-ms! 250)))))))

(use-fixtures :each reset-all!)

;; ---- stop-before-flush regression (rf2-eztym.3) --------------------------

(deftest stop-before-flush-still-captures-final-type
  (when (dom-available?)
    (testing "a buffered keystroke survives a flush that fires AFTER the
              recording was stopped — the final :dom/type entry is NOT
              dropped when stop-recording! clears :recording? before the
              pending debounce timer (or the remove! drain) flushes"
      (rf.story.recorder/start-recording! :story.x/y)
      ;; Hold the buffer open (no synchronous flush) so the keystroke is
      ;; still pending when we stop.
      (rf.story.recorder.dom-capture/set-debounce-ms! 5000)
      (let [input (.createElement js/document "input")]
        (.setAttribute input "id" "name")
        (.appendChild @test-root input)
        (set! (.-value input) "alice")
        (.dispatchEvent input (js/Event. "input" #js {:bubbles true}))
        ;; STOP first (flips :recording? false) — the order that previously
        ;; dropped the entry.
        (rf.story.recorder/stop-recording!)
        (is (not (rf.story.recorder/recording?))
            "sanity: the recording is stopped before the drain")
        ;; Now drain — under the bug this was a silent no-op.
        (rf.story.recorder.dom-capture/flush-type-buffer!)
        (let [type-entries (filterv #(= :dom/type (:kind %))
                                    (rf.story.recorder/recorded-entries))
              entry        (first type-entries)]
          (is (= 1 (count type-entries))
              "the final buffered keystroke survived the post-stop flush")
          (is (= "alice" (:text entry))
              "the surviving entry carries the final typed value")
          (is (number? (:t entry))
              "the entry carries its capture-time :t, stamped at buffer time")
          ;; And the generated play-script carries the final field value.
          (let [spec       (rf.story.recorder.play-export/recording->script-body
                             (rf.story.recorder/recorded-entries))
                type-steps (filterv #(= :type (first %)) (:script spec))]
            (is (= [[:type (:selector entry) "alice"]] type-steps)
                "the generated :type step carries the final value")))))))

(deftest remove-after-stop-drains-final-type
  (when (dom-available?)
    (testing "rf.story.recorder.dom-capture/remove! after stop-recording! still drains the pending type
              buffer (the worst-case teardown ordering — remove! routes
              through flush-type-buffer!)"
      (rf.story.recorder/start-recording! :story.x/y)
      (rf.story.recorder.dom-capture/set-debounce-ms! 5000)
      (let [input (.createElement js/document "input")]
        (.setAttribute input "id" "name")
        (.appendChild @test-root input)
        (set! (.-value input) "bob")
        (.dispatchEvent input (js/Event. "input" #js {:bubbles true}))
        (rf.story.recorder/stop-recording!)
        (rf.story.recorder.dom-capture/remove!)
        (let [type-entries (filterv #(= :dom/type (:kind %))
                                    (rf.story.recorder/recorded-entries))]
          (is (= 1 (count type-entries))
              "remove!'s drain captured the final keystroke after stop")
          (is (= "bob" (:text (first type-entries)))))))))

(deftest in-session-flush-unchanged
  (when (dom-available?)
    (testing "the fix does not regress the in-session path — a flush WHILE
              recording still appends exactly one entry with the final value"
      (rf.story.recorder/start-recording! :story.x/y)
      (rf.story.recorder.dom-capture/set-debounce-ms! 5000)
      (let [input (.createElement js/document "input")]
        (.setAttribute input "id" "name")
        (.appendChild @test-root input)
        (doseq [v ["a" "al" "ali"]]
          (set! (.-value input) v)
          (.dispatchEvent input (js/Event. "input" #js {:bubbles true})))
        (is (rf.story.recorder/recording?) "still recording at flush time")
        (rf.story.recorder.dom-capture/flush-type-buffer!)
        (let [type-entries (filterv #(= :dom/type (:kind %))
                                    (rf.story.recorder/recorded-entries))]
          (is (= 1 (count type-entries))
              "rapid typing still folds to a single in-session entry")
          (is (= "ali" (:text (first type-entries)))))))))

;; ---- cross-recording bleed regression (rf2-x76af2.18) --------------------
;;
;; rf2-eztym.3 made `flush-type-buffer!` bypass the `:recording?` re-check so
;; the FINAL keystroke survives a flush firing after stop (stop-into-SAME-
;; recording). But it left the DOM type-buffer + its live `setTimeout` timers
;; untied to the recorder start/clear boundary: a keystroke buffered under
;; recording A, whose pending flush fired AFTER a fresh `start-recording!` B
;; (or `clear!`), appended UNCONDITIONALLY into the CURRENT recorder atom —
;; bleeding an A-relative `:dom/type` step into B (or a phantom into the next
;; recording). The fix: `start-recording!` / `clear!` drain + cancel the
;; pending buffer via the `:recorder/reset-dom-buffer` late-bind seam.

(deftest stop-then-restart-does-not-bleed-across-recordings
  (when (dom-available?)
    (testing "a keystroke buffered under recording A, then a NON-flushing
              stop + start-recording! B within the debounce window, does NOT
              land in B's :entries — start-recording! drains + cancels the
              pending DOM type-buffer (rf2-x76af2.18)"
      (rf.story.recorder/start-recording! :story.a/rec)
      (rf.story.recorder.dom-capture/set-debounce-ms! 5000)          ; hold the buffer open (no sync flush)
      (let [input (.createElement js/document "input")]
        (.setAttribute input "id" "note")
        (.appendChild @test-root input)
        (set! (.-value input) "aaa")
        (.dispatchEvent input (js/Event. "input" #js {:bubbles true}))
        ;; STOP via the non-flushing facade path; the debounce timer T is
        ;; still pending, the buffered "aaa" still held.
        (rf.story.recorder/stop-recording!)
        ;; A FRESH recording B starts before T fires. This must DRAIN A's
        ;; pending buffer (cancel T + drop the entry), not carry it into B.
        (rf.story.recorder/start-recording! :story.b/rec)
        (is (rf.story.recorder/recording?) "B is recording")
        (is (empty? (rf.story.recorder/recorded-entries)) "B starts with no entries")
        ;; Force any surviving timer to fire. Under the bug A's "aaa" appends
        ;; here into B (append-dom-buffered ignores :recording?); under the
        ;; fix the buffer was cancelled + emptied, so this is a no-op.
        (rf.story.recorder.dom-capture/flush-type-buffer!)
        (let [type-entries (filterv #(= :dom/type (:kind %))
                                    (rf.story.recorder/recorded-entries))]
          (is (= [] type-entries)
              "A's buffered keystroke did NOT bleed into recording B"))))))

(deftest clear-while-typing-leaves-no-phantom-in-next-recording
  (when (dom-available?)
    (testing "clear! while a keystroke is buffered cancels the pending flush,
              so a subsequent recording sees no phantom entry (rf2-x76af2.18)"
      (rf.story.recorder/start-recording! :story.a/rec)
      (rf.story.recorder.dom-capture/set-debounce-ms! 5000)
      (let [input (.createElement js/document "input")]
        (.setAttribute input "id" "note")
        (.appendChild @test-root input)
        (set! (.-value input) "bbb")
        (.dispatchEvent input (js/Event. "input" #js {:bubbles true}))
        ;; Discard the recording mid-type.
        (rf.story.recorder/clear!)
        ;; Start a fresh recording; the cancelled buffer must not resurface.
        (rf.story.recorder/start-recording! :story.c/rec)
        (rf.story.recorder.dom-capture/flush-type-buffer!)
        (is (empty? (filterv #(= :dom/type (:kind %))
                             (rf.story.recorder/recorded-entries)))
            "clear! cancelled the pending flush — no phantom entry in C")))))

(deftest stop-into-same-recording-final-keystroke-still-survives
  (when (dom-available?)
    (testing "rf2-eztym.3 guard still holds under the rf2-x76af2.18 fix:
              stop-recording! does NOT drain the buffer, so a flush firing
              after stop (with NO intervening start/clear) still captures the
              final keystroke into the stopped recording"
      (rf.story.recorder/start-recording! :story.x/same)
      (rf.story.recorder.dom-capture/set-debounce-ms! 5000)
      (let [input (.createElement js/document "input")]
        (.setAttribute input "id" "note")
        (.appendChild @test-root input)
        (set! (.-value input) "keep")
        (.dispatchEvent input (js/Event. "input" #js {:bubbles true}))
        (rf.story.recorder/stop-recording!)          ; no start/clear after → buffer intact
        (rf.story.recorder.dom-capture/flush-type-buffer!)            ; the pending timer's late fire
        (let [type-entries (filterv #(= :dom/type (:kind %))
                                    (rf.story.recorder/recorded-entries))]
          (is (= 1 (count type-entries))
              "the final keystroke survived the post-stop flush (rf2-eztym.3)")
          (is (= "keep" (:text (first type-entries)))))))))
