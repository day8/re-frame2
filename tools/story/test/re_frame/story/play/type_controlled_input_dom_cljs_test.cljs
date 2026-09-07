(ns re-frame.story.play.type-controlled-input-dom-cljs-test
  "ACCEPTANCE test for the play runner's `:type` step against REAL
  React-controlled inputs (rf2-7aqo).

  The bug: `rf.story.play.dom/type!` assigned `node.value` directly. React
  redefines `value` as an OWN accessor on every controlled input
  (`trackValueOnNode`); that setter records the incoming value in React's
  value tracker BEFORE forwarding to the native setter, so
  `updateValueIfChanged` afterwards reports 'value did not change' and
  React DISCARDS both the `input` and the `change` synthetic events. The
  DOM showed the text, the component's `on-change` never fired, and
  `type!` returned true regardless — so a play could type an email and a
  password and submit empty credentials.

  The repair writes through the node's PROTOTYPE `value` setter, which is
  the write a real keystroke performs: React's tracker stays stale, so it
  synthesises the change.

  These assertions cannot be made without a real React render — a hiccup
  or pure-data test cannot observe the value tracker at all. Hence the
  `-dom-cljs-test$` suffix (rf2-2hrj8), which opts the file into the
  `:browser-test` build (Playwright + Chromium, real React via
  `react-dom/client`). `:node-test` also loads it — its `cljs-test$`
  regex matches this suffix — where every test self-gates on `(browser?)`
  and exits early.

  `direct-assignment-is-the-regression` is the load-bearing control: it
  performs the OLD write on an identical mounted input and asserts the
  controlled state does NOT update. Restoring the direct assignment in
  `type!` therefore reds the tests above it AND leaves this one green,
  which is what pins the seam rather than merely exercising it."
  (:require [cljs.test :refer-macros [deftest is testing]]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [reagent.core :as r]
            [re-frame.story.play.dom :as rf.story.play.dom]))

;; ---- browser + act gate (mirrors the sibling DOM suites) -----------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- get-act []
  (when (exists? (.-act React)) (.-act React)))

(defn- make-mount-node! []
  (let [node (js/document.createElement "div")]
    (js/document.body.appendChild node)
    node))

(defn- with-browser-act [f]
  (if-not (browser?)
    (is true ":node-test: no DOM — the browser-test runner exercises these assertions")
    (let [act-fn (get-act)]
      (if (nil? act-fn)
        (is true "act() not reachable from this runner; skipping")
        (do (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
            (f act-fn))))))

(defn- mount!
  "Render `component` (a Reagent form) into a fresh node under `act-fn`.
  Returns `[mount-node root]`."
  [act-fn component]
  (let [mount-node (make-mount-node!)
        root       (react-dom-client/createRoot mount-node)]
    (act-fn (fn [] (.render root (r/as-element component))))
    [mount-node root]))

(defn- unmount! [root]
  (try (.unmount root) (catch :default _ nil)))

;; ---- 1 · a controlled text input receives the typed text -----------------
;;
;; The view is authored the completely ordinary way: `:value` from a
;; ratom, `:on-change` writing that ratom. It has no idea a play is
;; driving it.

(defn- controlled-input [state]
  (fn []
    [:input {:type      "text"
             :data-test "typed-email"
             :value     (:email @state)
             :on-change #(swap! state assoc :email (.. % -target -value))}]))

(deftest type-step-reaches-controlled-input-on-change
  (testing "the public :type step delivers text to a React-controlled
            input's on-change, and the rerender retains it"
    (with-browser-act
      (fn [act-fn]
        (let [state       (r/atom {:email ""})
              [node root] (mount! act-fn [(controlled-input state)])
              input       (.querySelector node "[data-test=\"typed-email\"]")]
          (try
            (is (some? input) "the controlled input mounted")
            (is (= "" (:email @state)) "precondition: component state starts empty")

            (act-fn (fn [] (rf.story.play.dom/type! input "ada@example.com")))

            (is (= "ada@example.com" (:email @state))
                "the component's on-change received the typed text")
            ;; The rerender retains it: React re-renders from the ratom, so
            ;; a DOM value still reading the typed text after the flush
            ;; proves the component OWNS that value rather than the DOM
            ;; merely displaying an assignment React is about to revert.
            (is (= "ada@example.com"
                   (.-value (.querySelector node "[data-test=\"typed-email\"]")))
                "the controlled rerender kept the typed text on screen")
            (finally (unmount! root))))))))

;; ---- 2 · textarea takes the same path ------------------------------------

(defn- controlled-textarea [state]
  (fn []
    [:textarea {:data-test "typed-notes"
                :value     (:notes @state)
                :on-change #(swap! state assoc :notes (.. % -target -value))}]))

(deftest type-step-reaches-controlled-textarea-on-change
  (testing "a controlled <textarea> receives the typed text too — the
            prototype-setter walk is not input-specific"
    (with-browser-act
      (fn [act-fn]
        (let [state       (r/atom {:notes ""})
              [node root] (mount! act-fn [(controlled-textarea state)])
              area        (.querySelector node "[data-test=\"typed-notes\"]")]
          (try
            (is (some? area) "the controlled textarea mounted")
            (act-fn (fn [] (rf.story.play.dom/type! area "line one")))
            (is (= "line one" (:notes @state))
                "the textarea's on-change received the typed text")
            (is (= "line one"
                   (.-value (.querySelector node "[data-test=\"typed-notes\"]")))
                "the controlled rerender kept the typed text")
            (finally (unmount! root))))))))

;; ---- 3 · the submit snapshot — the symptom the bead names ----------------
;;
;; A minimal equivalent of `tools/story/testbeds/login_form/views.cljs`:
;; controlled email + password whose submit handler reads the RATOM, not
;; the raw DOM. This is the assertion that fails as "submitted empty
;; credentials despite visibly filled inputs" under the old write.

(defn- login-form [state submitted]
  (fn []
    [:form {:data-test "type-login-form"
            :on-submit (fn [e]
                         (.preventDefault e)
                         (reset! submitted @state))}
     [:input {:type      "email"
              :data-test "login-email"
              :value     (:email @state)
              :on-change #(swap! state assoc :email (.. % -target -value))}]
     [:input {:type      "password"
              :data-test "login-password"
              :value     (:password @state)
              :on-change #(swap! state assoc :password (.. % -target -value))}]]))

(deftest typed-credentials-reach-the-submit-snapshot
  (testing "type email · type password · submit — the handler's snapshot
            carries both typed values, not the empty initial state"
    (with-browser-act
      (fn [act-fn]
        (let [state       (r/atom {:email "" :password ""})
              submitted   (atom nil)
              [node root] (mount! act-fn [(login-form state submitted)])]
          (try
            (act-fn
              (fn []
                (rf.story.play.dom/type!
                  (.querySelector node "[data-test=\"login-email\"]") "grace@example.com")))
            (act-fn
              (fn []
                (rf.story.play.dom/type!
                  (.querySelector node "[data-test=\"login-password\"]") "hopper")))
            (act-fn
              (fn []
                (.dispatchEvent (.querySelector node "[data-test=\"type-login-form\"]")
                                (js/Event. "submit" #js {:bubbles true :cancelable true}))))
            (is (= {:email "grace@example.com" :password "hopper"} @submitted)
                "the submit handler read the typed credentials off component state")
            (finally (unmount! root))))))))

;; ---- 4 · the plain-DOM path is unchanged ---------------------------------

(deftest type-step-still-drives-a-plain-dom-input
  (testing "a plain (non-React) input still takes the value and still sees
            both dispatched events — the repair keeps the plain-DOM path"
    (if-not (browser?)
      (is true ":node-test: no DOM — the browser-test runner exercises this")
      (let [input  (js/document.createElement "input")
            seen   (atom [])]
        (set! (.-type input) "text")
        (js/document.body.appendChild input)
        (.addEventListener input "input"  (fn [_] (swap! seen conj :input)))
        (.addEventListener input "change" (fn [_] (swap! seen conj :change)))
        (try
          (is (true? (rf.story.play.dom/type! input "plain")))
          (is (= "plain" (.-value input)) "the plain input carries the value")
          (is (= [:input :change] @seen)
              "both events still reach a plain-DOM listener")
          (finally (.remove input)))))))

;; ---- 5 · the regression control ------------------------------------------
;;
;; This is what makes the four tests above load-bearing rather than
;; merely green. It performs the WRITE `type!` used to perform — a direct
;; `(set! (.-value node) …)` followed by the same two events — on an
;; identical mounted input, and asserts the controlled component state
;; does NOT move. If React ever stops filtering that write, this test
;; reds and tells us the seam changed; until then it pins exactly why the
;; repair is necessary.

(deftest direct-assignment-is-the-regression
  (testing "the OLD write (direct node.value assignment) leaves the
            controlled component's on-change unfired — the defect rf2-7aqo
            records"
    (with-browser-act
      (fn [act-fn]
        (let [state       (r/atom {:email ""})
              [node root] (mount! act-fn [(controlled-input state)])
              input       (.querySelector node "[data-test=\"typed-email\"]")]
          (try
            (act-fn
              (fn []
                (set! (.-value input) "swallowed@example.com")
                (.dispatchEvent input (js/Event. "input"  #js {:bubbles true :cancelable true}))
                (.dispatchEvent input (js/Event. "change" #js {:bubbles true :cancelable true}))))
            (is (= "" (:email @state))
                "React's value tracker swallowed the direct assignment — this is
                 the bug; `type!` must not write this way")
            (finally (unmount! root))))))))

;; ---- 6 · the mechanism, asserted directly --------------------------------

(deftest native-value-setter-skips-the-own-property-setter
  (testing "native-value-setter returns the PROTOTYPE setter, not an
            own-property setter installed on the node"
    (if-not (browser?)
      (is true ":node-test: no DOM — the browser-test runner exercises this")
      (let [input      (js/document.createElement "input")
            proto-set  (.-set (js/Object.getOwnPropertyDescriptor
                                (js/Object.getPrototypeOf input) "value"))
            calls      (atom [])]
        ;; Install an own-property accessor exactly the way React's
        ;; `trackValueOnNode` does, and confirm the walk steps over it.
        (js/Object.defineProperty
          input "value"
          #js {:configurable true
               :get (fn [] (.call (.-get (js/Object.getOwnPropertyDescriptor
                                           (js/Object.getPrototypeOf input) "value"))
                                  input))
               :set (fn [v] (swap! calls conj v) (.call proto-set input v))})
        (let [found (rf.story.play.dom/native-value-setter input)]
          (is (some? found) "a prototype value setter is reachable")
          (is (identical? proto-set found)
              "the walk returned the prototype setter, skipping the node's own one")
          (.call found input "direct")
          (is (= [] @calls)
              "writing through it did NOT go via the own-property setter — which
               is precisely why React's tracker stays stale")
          (is (= "direct" (.-value input))
              "and the value still landed on the node"))))))
