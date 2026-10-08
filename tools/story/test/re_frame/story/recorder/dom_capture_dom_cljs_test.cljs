(ns re-frame.story.recorder.dom-capture-dom-cljs-test
  "The recorder's DOM-event capture layer against a real DOM: selector
  picking, the click / type / submit listeners and the type debounce,
  sensitive-input redaction, and the rule that an interaction records its
  DOM step or its dispatch, never both. Each test mounts a transient root in
  `document.body`, installs the capture listeners on it and drives synthetic
  events.

  The `-dom-cljs-test` suffix puts this ns in the `:browser-test` build,
  the only gate with a DOM. `:node-test`'s `cljs-test$` regex matches the
  suffix too; there every row reports a stated skip through `skip!` rather
  than passing with zero assertions."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story.config :as rf.story.config]
            [re-frame.story.recorder :as rf.story.recorder]
            [re-frame.story.recorder.dom-capture :as rf.story.recorder.dom-capture]
            [re-frame.story.recorder.play-export :as rf.story.recorder.play-export]
            [re-frame.story.recorder.selector :as rf.story.recorder.selector]
            [re-frame.story.ui.element-inspector :as rf.story.ui.element-inspector]))

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
    ;; node-test: skip the DOM fixture entirely. Each deftest body
    ;; states its own skip through `skip!`.
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

(defn- type-entries []
  (filterv #(= :dom/type (:kind %)) (rf.story.recorder/recorded-entries)))

;; ---- selector picking via real DOM elements ------------------------------

(deftest pick-for-element-falls-back-to-nth
  ;; no useful attributes → nth-of-type fallback
  (if-not (dom-available?)
    (skip!)
    (let [parent (.createElement js/document "div")
          a (.createElement js/document "button")
          b (.createElement js/document "button")
          c (.createElement js/document "button")]
      (.appendChild parent a)
      (.appendChild parent b)
      (.appendChild parent c)
      (is (= "button:nth-of-type(2)"
             (rf.story.recorder.selector/pick-for-element b))))))

(deftest noops-when-dom-capture-disabled
  ;; the toggle drops DOM events even mid-recording
  (if-not (dom-available?)
    (skip!)
    (do
      (rf.story.recorder.dom-capture/set-enabled! false)
      (rf.story.recorder/start-recording! :story.x/y)
      (let [btn (.createElement js/document "button")]
        (.setAttribute btn "data-test" "go")
        (.appendChild @test-root btn)
        (.dispatchEvent btn (js/MouseEvent. "click" #js {:bubbles true}))
        (is (= [] (rf.story.recorder/recorded-entries)))))))

;; ---- the listeners -------------------------------------------------------

(deftest click-listener-captures-with-selector
  (if-not (dom-available?)
    (skip!)
    (do
      (rf.story.recorder/start-recording! :story.x/y)
      (let [btn (.createElement js/document "button")]
        (.setAttribute btn "data-test" "submit")
        (.appendChild @test-root btn)
        (.dispatchEvent btn (js/MouseEvent. "click" #js {:bubbles true}))
        (is (= [[:dom/click "[data-test=\"submit\"]"]]
               (mapv (juxt :kind :selector) (rf.story.recorder/recorded-entries))))))))

(deftest rapid-typing-folds-to-single-entry
  ;; A long debounce window holds the intermediate values until the manual
  ;; flush; the fixture's 0 would flush each input event on its own.
  (if-not (dom-available?)
    (skip!)
    (do
      (rf.story.recorder/start-recording! :story.x/y)
      (rf.story.recorder.dom-capture/set-debounce-ms! 5000)
      (let [input (.createElement js/document "input")]
        (.setAttribute input "id" "name")
        (.appendChild @test-root input)
        (doseq [v ["a" "al" "ali" "alic" "alice"]]
          (set! (.-value input) v)
          (.dispatchEvent input (js/Event. "input" #js {:bubbles true})))
        (rf.story.recorder.dom-capture/flush-type-buffer!)
        (is (= ["alice"] (mapv :text (type-entries))))))))

(deftest change-event-flushes-immediately
  (if-not (dom-available?)
    (skip!)
    (do
      (rf.story.recorder/start-recording! :story.x/y)
      (let [input (.createElement js/document "input")]
        (.setAttribute input "id" "name")
        (.appendChild @test-root input)
        (set! (.-value input) "alice")
        (.dispatchEvent input (js/Event. "change" #js {:bubbles true}))
        (is (= ["alice"] (mapv :text (type-entries))))))))

(deftest submit-listener-captures-form-selector
  (if-not (dom-available?)
    (skip!)
    (do
      (rf.story.recorder/start-recording! :story.x/y)
      (let [form (.createElement js/document "form")]
        (.setAttribute form "id" "login")
        (.appendChild @test-root form)
        (.dispatchEvent form (js/Event. "submit" #js {:bubbles true :cancelable true}))
        (is (= ["[id=\"login\"]"]
               (mapv :selector (filterv #(= :dom/submit (:kind %))
                                        (rf.story.recorder/recorded-entries)))))))))

(deftest click-on-submit-button-records-one-step
  ;; The submit event a submit button's click causes is not captured as a
  ;; second `[:click <form>]` step; a submit with no submitter still is
  ;; (`submit-listener-captures-form-selector`).
  (if-not (dom-available?)
    (skip!)
    (do
      (rf.story.recorder/start-recording! :story.x/y)
      (let [form (.createElement js/document "form")
            btn  (.createElement js/document "button")]
        (.setAttribute form "data-test" "login-form")
        (.setAttribute btn "type" "submit")
        (.setAttribute btn "data-test" "login-submit")
        ;; The variant's own submit handler prevents the navigation.
        (.addEventListener form "submit" (fn [e] (.preventDefault e)))
        (.appendChild form btn)
        (.appendChild @test-root form)
        (.click btn)
        (is (= [[:click "[data-test=\"login-submit\"]"]]
               (:script (rf.story.recorder.play-export/recording->script-body
                          (rf.story.recorder/recorded-entries)))))))))

(deftest click-flushes-pending-type
  ;; a click after typing flushes the type buffer first, preserving order
  (if-not (dom-available?)
    (skip!)
    (do
      (rf.story.recorder/start-recording! :story.x/y)
      (let [input (.createElement js/document "input")
            btn   (.createElement js/document "button")]
        (.setAttribute input "id" "name")
        (.setAttribute btn "data-test" "save")
        (.appendChild @test-root input)
        (.appendChild @test-root btn)
        (rf.story.recorder.dom-capture/set-debounce-ms! 5000)
        (set! (.-value input) "alice")
        (.dispatchEvent input (js/Event. "input" #js {:bubbles true}))
        (.dispatchEvent btn (js/MouseEvent. "click" #js {:bubbles true}))
        (is (= [[:dom/type "alice"] [:dom/click nil]]
               (mapv (juxt :kind :text) (rf.story.recorder/recorded-entries))))))))

(deftest dom-entries-carry-relative-timestamps
  ;; :t is ms since the recording's :started-ms, on the same clock as the
  ;; dispatch rail's entries, so the export's wait gaps stay small
  (if-not (dom-available?)
    (skip!)
    (do
      (rf.story.recorder/start-recording! :story.x/y)
      (rf.story.recorder.dom-capture/record-dom-click! "[data-test=\"a\"]")
      ;; `number?` first: CLJS's `<=` reads nil as 0
      (let [t (:t (first (rf.story.recorder/recorded-entries)))]
        (is (and (number? t) (<= 0 t 10000)) (str ":t " t))))))

;; ---- sensitive-input redaction -------------------------------------------
;;
;; `:entries` is the primary codegen source, so a sensitive field's value is
;; scrubbed at the capture boundary and the generated snippet carries the
;; placeholder, not the plaintext.

(defn- mk-input!
  "Create + mount an `<input>` carrying the given attribute map, return it."
  [attrs]
  (let [input (.createElement js/document "input")]
    (doseq [[k v] attrs]
      (.setAttribute input (name k) v))
    (.appendChild @test-root input)
    input))

(deftest sensitive-fields-are-redacted-at-capture
  ;; One row per path: a sensitive `type`, and a credential autocomplete
  ;; token. Redacting bumps the variant's suppressed counter, which the
  ;; UI's REDACTED hint reads.
  (if-not (dom-available?)
    (skip!)
    (doseq [attrs [{:type "password" :id "pw"}
                   {:type "text" :autocomplete "current-password" :id "c"}]]
      (rf.story.recorder/clear!)
      (rf.story.config/reset-suppressed-count!)
      (rf.story.recorder/start-recording! :story.login/flow)
      (let [el (mk-input! attrs)]
        (set! (.-value el) "hunter2-secret")
        (.dispatchEvent el (js/Event. "change" #js {:bubbles true}))
        (is (= [rf.story.recorder.dom-capture/redacted-type-text]
               (mapv :text (type-entries)))
            (pr-str attrs))
        (is (pos? (rf.story.config/suppressed-count :story.login/flow))
            (pr-str attrs))))))

(deftest ordinary-text-field-is-not-redacted
  (if-not (dom-available?)
    (skip!)
    (do
      (rf.story.recorder/start-recording! :story.x/y)
      (let [name-input (mk-input! {:type "text" :id "name"})]
        (set! (.-value name-input) "alice")
        (.dispatchEvent name-input (js/Event. "change" #js {:bubbles true}))
        (is (= ["alice"] (mapv :text (type-entries))))))))

(deftest local-raw-profile-opts-into-verbatim-capture
  ;; the host opt-in, mirroring the dispatch rail
  (if-not (dom-available?)
    (skip!)
    (do
      (rf.story.config/set-egress-profile! :rf.egress/local-raw)
      (rf.story.recorder/start-recording! :story.login/flow)
      (let [pw (mk-input! {:type "password" :id "pw"})]
        (set! (.-value pw) "hunter2-secret")
        (.dispatchEvent pw (js/Event. "change" #js {:bubbles true}))
        (is (= ["hunter2-secret"] (mapv :text (type-entries))))))))

;; ---- the DOM step or its dispatch, never both ----------------------------
;;
;; The step is recorded in the capture phase at the canvas root, before any
;; handler below it runs, so a handler that stops propagation is the
;; stronger case: it records exactly as a handler that lets the event bubble.

(def ^:private rec-frame :story.dc/login)

(defn- with-recording-frame
  "Run `f` with a live `rec-frame`, the recorder's trace listener installed,
  and a recording in flight against it; tear all three down afterwards."
  [f]
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  (rf/reg-event :dc/submit (fn [{:keys [db]} _] {:db (assoc db :submitted true)}))
  (rf/reg-event :dc/set-pw (fn [{:keys [db]} [_ pw]] {:db (assoc db :pw pw)}))
  (rf/make-frame {:id rec-frame})
  (rf.story.recorder/install-trace-listener!)
  (try
    (rf.story.recorder/start-recording! rec-frame)
    (f)
    (finally
      (rf.story.recorder/remove-trace-listener!)
      (rf/destroy-frame! rec-frame))))

(defn- dispatch-on! [el dom-event event-fn]
  (.addEventListener el dom-event
                     (fn [_] (rf/dispatch-sync (event-fn el) {:frame rec-frame}))))

(defn- dispatch-and-stop-on!
  "Like `dispatch-on!`, but the handler also stops propagation, so the event
  never bubbles back up to the canvas root."
  [el dom-event event-fn]
  (.addEventListener el dom-event
                     (fn [e]
                       (.stopPropagation e)
                       (rf/dispatch-sync (event-fn el) {:frame rec-frame}))))

(deftest a-click-whose-handler-stops-propagation-records-one-step
  ;; replaying the [:click …] step fires the handler's dispatch again, so the
  ;; dispatch is not recorded as a step of its own
  (if-not (dom-available?)
    (skip!)
    (with-recording-frame
      (fn []
        (let [btn (.createElement js/document "button")]
          (.setAttribute btn "data-test" "login")
          (dispatch-and-stop-on! btn "click" (fn [_] [:dc/submit]))
          (.appendChild @test-root btn)
          (.dispatchEvent btn (js/MouseEvent. "click" #js {:bubbles true}))
          (is (true? (:submitted (rf/app-db-value rec-frame)))
              "control: the click's handler dispatched")
          (is (= [[:click "[data-test=\"login\"]"]]
                 (:script (rf.story.recorder.play-export/recording->script-body
                            (rf.story.recorder/recorded-entries))))))))))

(deftest an-inspector-pick-records-no-step
  (if-not (dom-available?)
    (skip!)
    (testing "the element inspector stops its pick at the canvas root, so the
              variant never sees the click and the recording gains no step —
              whichever of the two root listeners runs first"
      (with-recording-frame
        (fn []
          (let [btn (.createElement js/document "button")]
            (.setAttribute btn "data-test" "login")
            (dispatch-on! btn "click" (fn [_] [:dc/submit]))
            (.appendChild @test-root btn)
            (try
              (doseq [[order install!]
                      [["recorder first" #(rf.story.ui.element-inspector/install! @test-root)]
                       ["inspector first" #(do (rf.story.ui.element-inspector/install! @test-root)
                                               (rf.story.recorder.dom-capture/install! @test-root))]]]
                (testing order
                  (install!)
                  (rf.story.ui.element-inspector/set-active! true)
                  (.dispatchEvent btn (js/MouseEvent. "click" #js {:bubbles true}))
                  (is (nil? (:submitted (rf/app-db-value rec-frame)))
                      "control: the pick never reached the variant's handler")
                  (is (= [] (rf.story.recorder/recorded-entries)))))
              (finally
                (rf.story.ui.element-inspector/remove!)))))))))

(deftest a-typed-password-whose-handler-stops-propagation-records-the-redacted-step
  ;; the input's dispatch is skipped and only the redacted :type step lands
  (if-not (dom-available?)
    (skip!)
    (with-recording-frame
      (fn []
        (let [pw (mk-input! {:type "password" :id "pw"})]
          (dispatch-and-stop-on! pw "input" (fn [el] [:dc/set-pw (.-value el)]))
          (set! (.-value pw) "hunter2-secret")
          (.dispatchEvent pw (js/Event. "input" #js {:bubbles true}))
          (rf.story.recorder.dom-capture/flush-type-buffer!)
          (is (= "hunter2-secret" (:pw (rf/app-db-value rec-frame)))
              "control: the input's handler dispatched")
          (is (= [[:dom/type rf.story.recorder.dom-capture/redacted-type-text]]
                 (mapv (juxt :kind :text) (rf.story.recorder/recorded-entries)))))))))

(deftest a-recorded-dispatch-redacts-a-typed-password
  ;; with DOM capture off the dispatch is the recorded step, and the password
  ;; in its payload is redacted as the :type step's text is
  (if-not (dom-available?)
    (skip!)
    (do
      (rf.story.recorder.dom-capture/set-enabled! false)
      (with-recording-frame
        (fn []
          (let [pw (mk-input! {:type "password" :id "pw"})]
            (dispatch-on! pw "input" (fn [el] [:dc/set-pw (.-value el)]))
            (set! (.-value pw) "hunter2-secret")
            (.dispatchEvent pw (js/Event. "input" #js {:bubbles true}))
            (is (= "hunter2-secret" (:pw (rf/app-db-value rec-frame)))
                "control: the app received the real value")
            (is (= [[:dc/set-pw rf.story.recorder.dom-capture/redacted-type-text]]
                   (rf.story.recorder/recorded-events)))))))))
