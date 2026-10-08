(ns re-frame.story.recorder.dom-capture-stop-flush-dom-cljs-test
  "The recorder type-debounce STOP/DRAIN boundary against a real DOM.

  A keystroke is buffered with its capture-time `:t` stamped while the
  recording is live, and the drain appends it through
  `rf.story.recorder/record-dom-event-buffered!`, which skips the
  `:recording?` check — so the final keystroke survives a flush that fires
  after the recording stopped, or the generated `:script` would lose its
  last field value. `start-recording!` drains and cancels the buffer, so a
  keystroke from one recording cannot bleed into the next.

  The `-dom-cljs-test` suffix puts this ns in the `:browser-test` build;
  under `:node-test` every row reports a stated skip through `skip!`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.story.config :as rf.story.config]
            [re-frame.story.recorder :as rf.story.recorder]
            [re-frame.story.recorder.dom-capture :as rf.story.recorder.dom-capture]))

;; ---- runtime gate --------------------------------------------------------

(defn- dom-available? []
  (and (exists? js/document)
       (some? (.-body js/document))))

(defn- skip!
  "The stated skip for a row under `:node-test`, which has no
  `js/document`: one marker assertion, so the row reports a skip instead
  of passing with zero assertions. Under `:browser-test` the
  real body runs."
  []
  (is true "skipped: needs a real DOM — the assertions run under :browser-test"))

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

(defn- type-into!
  "Start a recording against `variant`, hold the debounce buffer open, and
  type `v` into a fresh input, leaving the keystroke pending."
  [variant v]
  (rf.story.recorder/start-recording! variant)
  (rf.story.recorder.dom-capture/set-debounce-ms! 5000)
  (let [input (.createElement js/document "input")]
    (.setAttribute input "id" "name")
    (.appendChild @test-root input)
    (set! (.-value input) v)
    (.dispatchEvent input (js/Event. "input" #js {:bubbles true}))))

(defn- typed-texts []
  (mapv :text (filterv #(= :dom/type (:kind %)) (rf.story.recorder/recorded-entries))))

(deftest stop-before-flush-still-captures-final-type
  ;; STOP first (flips :recording? false), then drain: a recording-gated
  ;; drain would be a silent no-op here
  (if-not (dom-available?)
    (skip!)
    (do
      (type-into! :story.x/y "alice")
      (rf.story.recorder/stop-recording!)
      (rf.story.recorder.dom-capture/flush-type-buffer!)
      (is (= ["alice"] (typed-texts))))))

(deftest remove-after-stop-drains-final-type
  ;; the worst-case teardown ordering: remove! drains the buffer after stop
  (if-not (dom-available?)
    (skip!)
    (do
      (type-into! :story.x/y "bob")
      (rf.story.recorder/stop-recording!)
      (rf.story.recorder.dom-capture/remove!)
      (is (= ["bob"] (typed-texts))))))

(deftest stop-then-restart-does-not-bleed-across-recordings
  ;; A keystroke buffered under recording A, a non-flushing stop, then
  ;; recording B within the debounce window: forcing the flush in B finds
  ;; nothing, since `start-recording!` cancelled and emptied A's buffer.
  (if-not (dom-available?)
    (skip!)
    (do
      (type-into! :story.a/rec "aaa")
      (rf.story.recorder/stop-recording!)
      (rf.story.recorder/start-recording! :story.b/rec)
      (rf.story.recorder.dom-capture/flush-type-buffer!)
      (is (= [] (typed-texts))))))
