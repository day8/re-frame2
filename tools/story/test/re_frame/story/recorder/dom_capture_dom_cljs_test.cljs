(ns re-frame.story.recorder.dom-capture-dom-cljs-test
  "DOM-gated tests for the recorder's DOM-event capture layer
  (rf2-d5u89). Exercises:

  - Selector picking via real DOM nodes.
  - The impure recorder seams (`record-dom-click!` etc.) appending
    onto the recorder's `:entries` stream.
  - Click handler captures with the right selector tier.
  - Type debounce — rapid input + change yields a single :dom/type
    entry with the final value.
  - Form submit captures `[:dom/submit ...]`.
  - Sensitive-input redaction on the DOM capture rail.

  The corpus mounts a transient DOM root inside the test document
  (`document.body`) for each test, installs the capture listeners
  on it, drives synthetic events, and tears the root down on each
  fixture exit.

  ## Runtime gating (rf2-jmfvc)

  This ns is suffixed `-dom-cljs-test` (file `*_dom_cljs_test.cljs`)
  so it matches the `:browser-test` build's `-dom-cljs-test$`
  ns-regexp and ACTUALLY RUNS against a real DOM — the only gate
  where the DOM bodies below execute. It was previously named
  `-cljs-test`: under `:node-test` (`cljs-test$`) its bodies
  short-circuit via `dom-available?` (no `js/document` on node), and
  it never matched the `:browser-test` gate, so its ~25 assertions
  ran in NO gate (latent false-green). The `-dom-cljs-test` suffix
  fixes that — `:node-test`'s `cljs-test$` regex still matches it
  too (bodies stay green-by-short-circuit on node), and
  `:browser-test` now picks it up and runs the real assertions."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story.config :as config]
            [re-frame.story.recorder :as recorder]
            [re-frame.story.recorder.dom-capture :as dom]
            [re-frame.story.recorder.play-export :as export]
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

;; NOTE: the rf2-eztym.3 stop-before-flush regression lives in the sibling
;; `dom-capture-stop-flush-dom-cljs-test` (the `-dom-cljs-test` suffix runs
;; it under the `:browser-test` gate against a real DOM — the only place the
;; post-stop-flush bug is observable; this `-cljs-test` file's bodies
;; short-circuit on node).

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

;; ---- sensitive-input redaction (rf2-0qoi0) -------------------------------
;;
;; The DOM-capture rail is the SECOND credential/PII egress (the dispatch
;; rail being the first, redacted by `recorder/trace-listener`). Post
;; rf2-nkjkj `:entries` is the PRIMARY codegen source, so a typed password
;; would otherwise ride verbatim into the generated `:play-script` step.
;; These tests pin the record-but-redact policy on the DOM rail: a
;; password field's value is scrubbed at the capture boundary so the
;; generated snippet carries the placeholder, not the plaintext.

(defn- mk-input!
  "Create + mount an `<input>` carrying the given attribute map, return it."
  [attrs]
  (let [input (.createElement js/document "input")]
    (doseq [[k v] attrs]
      (.setAttribute input (name k) v))
    (.appendChild @test-root input)
    input))

(deftest password-field-type-is-redacted-in-generated-snippet
  (when (dom-available?)
    (testing "RED→GREEN (rf2-0qoi0): a typed <input type=password> value is
              SCRUBBED — neither the recorded :dom/type entry nor the
              generated play-script :type step carries the plaintext"
      (recorder/start-recording! :story.login/flow)
      (let [pw (mk-input! {:type "password" :id "pw"})]
        (set! (.-value pw) "hunter2-secret")
        (.dispatchEvent pw (js/Event. "change" #js {:bubbles true}))
        (let [{:keys [text] :as entry}
              (first (filterv #(= :dom/type (:kind %)) (recorder/recorded-entries)))]
          ;; The recorded entry carries the placeholder, not the password.
          (is (= dom/redacted-type-text text)
              "the recorded :dom/type text is the redacted placeholder")
          (is (not= "hunter2-secret" text)
              "the plaintext password never reaches the recorder atom")
          ;; The generated play-script step carries the placeholder too.
          (let [spec (export/recording->script-body (recorder/recorded-entries))
                type-steps (filterv #(= :type (first %)) (:script spec))]
            (is (= [[:type (:selector entry) dom/redacted-type-text]] type-steps)
                "the generated :type step is scrubbed")
            (is (not (re-find #"hunter2-secret" (export/render-script-body spec)))
                "the rendered snippet text leaks no plaintext")))))))

(deftest email-and-tel-and-autocomplete-fields-are-redacted
  (when (dom-available?)
    (testing "email / tel inputs + a credential autocomplete token are scrubbed"
      (doseq [attrs [{:type "email" :id "e"}
                     {:type "tel" :id "t"}
                     {:type "text" :autocomplete "current-password" :id "c"}
                     {:type "text" :autocomplete "cc-number" :id "n"}]]
        (recorder/clear!)
        (recorder/start-recording! :story.login/flow)
        (let [el (mk-input! attrs)]
          (set! (.-value el) "secret-value")
          (.dispatchEvent el (js/Event. "change" #js {:bubbles true}))
          (let [text (:text (first (filterv #(= :dom/type (:kind %))
                                            (recorder/recorded-entries))))]
            (is (= dom/redacted-type-text text)
                (str "scrubbed for attrs " (pr-str attrs)))))))))

(deftest ordinary-text-field-is-not-redacted
  (when (dom-available?)
    (testing "a plain <input type=text> + a <select> choice flow through verbatim
              — only sensitive typed inputs are scrubbed (no over-redaction)"
      (recorder/start-recording! :story.x/y)
      (let [name-input (mk-input! {:type "text" :id "name"})]
        (set! (.-value name-input) "alice")
        (.dispatchEvent name-input (js/Event. "change" #js {:bubbles true}))
        (is (= "alice"
               (:text (first (filterv #(= :dom/type (:kind %))
                                      (recorder/recorded-entries))))))))))

(deftest local-raw-profile-opts-into-verbatim-capture
  (when (dom-available?)
    (testing ":rf.egress/local-raw → the DOM rail captures the verbatim
              password (host opt-in, mirrors the dispatch rail; EP-0015 rf2-3t26eh)"
      (config/set-egress-profile! :rf.egress/local-raw)
      (recorder/start-recording! :story.login/flow)
      (let [pw (mk-input! {:type "password" :id "pw"})]
        (set! (.-value pw) "hunter2-secret")
        (.dispatchEvent pw (js/Event. "change" #js {:bubbles true}))
        (is (= "hunter2-secret"
               (:text (first (filterv #(= :dom/type (:kind %))
                                      (recorder/recorded-entries))))))))))

(deftest redacting-a-password-bumps-the-suppressed-counter
  (when (dom-available?)
    (testing "the suppressed-events counter for the recording variant is bumped
              on redaction so the UI's REDACTED hint stays accurate"
      (config/reset-suppressed-count!)
      (recorder/start-recording! :story.login/flow)
      (let [pw (mk-input! {:type "password" :id "pw"})]
        (set! (.-value pw) "hunter2-secret")
        (.dispatchEvent pw (js/Event. "change" #js {:bubbles true}))
        (is (pos? (config/suppressed-count :story.login/flow))
            "redaction bumped the per-variant suppressed counter")))))
