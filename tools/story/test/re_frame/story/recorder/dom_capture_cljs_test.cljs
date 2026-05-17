(ns re-frame.story.recorder.dom-capture-cljs-test
  "CLJS-only tests for the recorder's DOM-event capture layer
  (rf2-d5u89). Exercises:

  - Selector picking via real DOM nodes.
  - The impure recorder seams (`record-dom-click!` etc.) appending
    onto the recorder's `:entries` stream.
  - Click handler captures with the right selector tier.
  - Type debounce — rapid input + change yields a single :dom/type
    entry with the final value.
  - Form submit captures `[:dom/submit ...]`.

  The corpus mounts a transient DOM root inside the test document
  (`document.body`) for each test, installs the capture listeners
  on it, drives synthetic events, and tears the root down on each
  fixture exit.

  ## Runtime gating

  shadow-cljs's `:node-test` build picks up every `*_cljs_test.cljs`
  under the classpath. Node doesn't carry a `js/document`, so every
  deftest body below short-circuits via `dom-available?` when run
  on node-test. Each test ships its real assertions only under the
  `:browser-test` runner. The shared ns-regexp tier means the file
  IS picked up under both; the gate keeps node-test green without
  any per-test rename/exclude dance."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story.recorder :as recorder]
            [re-frame.story.recorder.dom-capture :as dom]
            [re-frame.story.recorder.selector :as sel]))

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
    ;; node-test: skip the DOM fixture entirely. The individual
    ;; deftest bodies short-circuit on `dom-available?` too.
    (f)
    (do
      (recorder/clear!)
      (dom/set-enabled! true)
      (dom/set-debounce-ms! 0)
      (let [_ (mount-root!)]
        (dom/install! @test-root)
        (try
          (f)
          (finally
            (dom/remove!)
            (unmount-root!)
            (recorder/clear!)
            (dom/set-debounce-ms! 250)))))))

(use-fixtures :each reset-all!)

;; ---- selector picking via real DOM elements ------------------------------

(deftest pick-for-element-prefers-data-test
  (when (dom-available?)
    (testing "pick-for-element walks priority on a real DOM element"
      (let [btn (.createElement js/document "button")]
        (.setAttribute btn "data-test" "go")
        (.setAttribute btn "id" "btn-1")
        (is (= "[data-test=\"go\"]"
               (sel/pick-for-element btn)))))))

(deftest pick-for-element-falls-back-to-nth
  (when (dom-available?)
    (testing "no useful attributes → nth-of-type fallback"
      (let [parent (.createElement js/document "div")
            a (.createElement js/document "button")
            b (.createElement js/document "button")
            c (.createElement js/document "button")]
        (.appendChild parent a)
        (.appendChild parent b)
        (.appendChild parent c)
        (is (= "button:nth-of-type(2)"
               (sel/pick-for-element b)))))))

;; ---- impure recorder seams ----------------------------------------------

(deftest record-dom-click-appends-entry
  (when (dom-available?)
    (recorder/start-recording! :story.x/y)
    (dom/record-dom-click! "[data-test=\"go\"]")
    (let [entries (recorder/recorded-entries)]
      (is (= 1 (count entries)))
      (let [{:keys [kind selector t]} (first entries)]
        (is (= :dom/click kind))
        (is (= "[data-test=\"go\"]" selector))
        (is (number? t))))))

(deftest record-dom-type-appends-entry
  (when (dom-available?)
    (recorder/start-recording! :story.x/y)
    (dom/record-dom-type! "[id=\"name\"]" "alice")
    (let [{:keys [kind selector text]} (first (recorder/recorded-entries))]
      (is (= :dom/type kind))
      (is (= "[id=\"name\"]" selector))
      (is (= "alice" text)))))

(deftest record-dom-submit-appends-entry
  (when (dom-available?)
    (recorder/start-recording! :story.x/y)
    (dom/record-dom-submit! "[id=\"login\"]")
    (is (= :dom/submit (:kind (first (recorder/recorded-entries)))))))

(deftest noops-when-not-recording
  (when (dom-available?)
    (testing "DOM-event records drop when no recording is in flight"
      (is (not (recorder/recording?)))
      (dom/record-dom-click! "anywhere")
      (dom/record-dom-type! "anywhere" "x")
      (dom/record-dom-submit! "anywhere")
      (is (= [] (recorder/recorded-entries))))))

(deftest noops-when-dom-capture-disabled
  (when (dom-available?)
    (testing "DOM-event records drop when the toggle is off — even mid-recording"
      (dom/set-enabled! false)
      (recorder/start-recording! :story.x/y)
      (let [btn (.createElement js/document "button")]
        (.setAttribute btn "data-test" "go")
        (.appendChild @test-root btn)
        (.dispatchEvent btn (js/MouseEvent. "click" #js {:bubbles true}))
        (is (= [] (recorder/recorded-entries))
            "no DOM entries captured while the toggle is off")))))

;; ---- click handler via synthetic DOM events ------------------------------

(deftest click-listener-captures-with-selector
  (when (dom-available?)
    (recorder/start-recording! :story.x/y)
    (let [btn (.createElement js/document "button")]
      (.setAttribute btn "data-test" "submit")
      (.appendChild @test-root btn)
      (.dispatchEvent btn (js/MouseEvent. "click" #js {:bubbles true}))
      (let [entries (recorder/recorded-entries)]
        (is (= 1 (count entries)))
        (is (= :dom/click (:kind (first entries))))
        (is (= "[data-test=\"submit\"]" (:selector (first entries))))))))

;; ---- type debounce (final-value semantics) ------------------------------

(deftest rapid-typing-folds-to-single-entry
  (when (dom-available?)
    (testing "many input events on the same input → ONE :dom/type entry with the final value"
      (recorder/start-recording! :story.x/y)
      ;; Bump the debounce window high so the buffer holds the
      ;; intermediate input values until we trigger a flush
      ;; manually. The fixture's default debounce-ms (0) would
      ;; cause each input event to flush synchronously — that's
      ;; the path the click-as-flush-point test covers; this one
      ;; verifies the debounce semantics itself.
      (dom/set-debounce-ms! 5000)
      (let [input (.createElement js/document "input")]
        (.setAttribute input "id" "name")
        (.appendChild @test-root input)
        (doseq [v ["a" "al" "ali" "alic" "alice"]]
          (set! (.-value input) v)
          (.dispatchEvent input (js/Event. "input" #js {:bubbles true})))
        ;; Flush manually; under real use this happens on debounce
        ;; expiry / click / stop-recording.
        (dom/flush-type-buffer!)
        (let [type-entries (filterv #(= :dom/type (:kind %))
                                    (recorder/recorded-entries))]
          (is (= 1 (count type-entries))
              "five input events fold to a single :dom/type entry")
          (is (= "alice" (:text (first type-entries)))
              "the entry carries the final typed value"))))))

(deftest change-event-flushes-immediately
  (when (dom-available?)
    (testing "a `change` event drains the per-selector type buffer"
      (recorder/start-recording! :story.x/y)
      (let [input (.createElement js/document "input")]
        (.setAttribute input "id" "name")
        (.appendChild @test-root input)
        (set! (.-value input) "alice")
        (.dispatchEvent input (js/Event. "change" #js {:bubbles true}))
        (let [type-entries (filterv #(= :dom/type (:kind %))
                                    (recorder/recorded-entries))]
          (is (= 1 (count type-entries)))
          (is (= "alice" (:text (first type-entries)))))))))

(deftest submit-listener-captures-form-selector
  (when (dom-available?)
    (recorder/start-recording! :story.x/y)
    (let [form (.createElement js/document "form")]
      (.setAttribute form "id" "login")
      (.appendChild @test-root form)
      (let [ev (js/Event. "submit" #js {:bubbles true :cancelable true})]
        (.dispatchEvent form ev))
      (let [submit-entries (filterv #(= :dom/submit (:kind %))
                                    (recorder/recorded-entries))]
        (is (= 1 (count submit-entries)))
        (is (= "[id=\"login\"]" (:selector (first submit-entries))))))))

(deftest click-flushes-pending-type
  (when (dom-available?)
    (testing "a click after typing flushes the type buffer first, preserving order"
      (recorder/start-recording! :story.x/y)
      (let [input (.createElement js/document "input")
            btn   (.createElement js/document "button")]
        (.setAttribute input "id" "name")
        (.setAttribute btn "data-test" "save")
        (.appendChild @test-root input)
        (.appendChild @test-root btn)
        (dom/set-debounce-ms! 5000)
        (set! (.-value input) "alice")
        (.dispatchEvent input (js/Event. "input" #js {:bubbles true}))
        (.dispatchEvent btn (js/MouseEvent. "click" #js {:bubbles true}))
        (let [entries (recorder/recorded-entries)
              kinds   (mapv :kind entries)]
          (is (= [:dom/type :dom/click] kinds)
              "type lands before click")
          (is (= "alice" (:text (first entries)))))))))

;; ---- enabled? / set-enabled! --------------------------------------------

(deftest set-enabled-roundtrips
  (when (dom-available?)
    (dom/set-enabled! false)
    (is (not (dom/enabled?)))
    (dom/set-enabled! true)
    (is (dom/enabled?))))

;; ---- timestamps ride through to entries ---------------------------------

(deftest dom-entries-carry-relative-timestamps
  (when (dom-available?)
    (testing "the recorded :t is relative to the recording's :started-ms"
      (recorder/start-recording! :story.x/y)
      (dom/record-dom-click! "[data-test=\"a\"]")
      (let [{:keys [t]} (first (recorder/recorded-entries))]
        (is (number? t))
        (is (>= t 0)
            ":t is non-negative ms since :started-ms")
        (is (< t 10000)
            "sanity: not an absolute epoch")))))
