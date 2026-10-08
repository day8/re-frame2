(ns re-frame.story.play.type-controlled-input-dom-cljs-test
  "ACCEPTANCE test for the play runner's `:type` step against REAL
  React-controlled inputs.

  React redefines `value` as an OWN accessor on every controlled input
  (`trackValueOnNode`); that setter records the incoming value in React's
  value tracker BEFORE forwarding to the native setter, so
  `updateValueIfChanged` afterwards reports 'value did not change' and
  React DISCARDS both the `input` and the `change` synthetic events. A
  `type!` that assigned `node.value` directly would leave the DOM showing
  the text while the component's `on-change` never fired, and `type!` —
  which returns true once it has written and dispatched — would report
  success, so a play could type an email and a password and submit empty
  credentials.

  `rf.story.play.dom/type!` therefore writes through the node's PROTOTYPE
  `value` setter, which is the write a real keystroke performs: React's
  tracker stays stale, so it synthesises the change.

  These assertions cannot be made without a real React render — a hiccup
  or pure-data test cannot observe the value tracker at all. Hence the
  `-dom-cljs-test$` suffix, which opts the file into the
  `:browser-test` build (Playwright + Chromium, real React via
  `react-dom/client`). `:node-test` also loads it — its `cljs-test$`
  regex matches this suffix — where every test self-gates on `(browser?)`
  and exits early.

  `native-value-setter-skips-the-own-property-setter` pins the mechanism
  without React: the setter `type!` writes through is the prototype's,
  never the own-property accessor React installs."
  (:require [cljs.test :refer-macros [deftest is]]
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

;; ---- controlled fields ---------------------------------------------------
;;
;; Each view is authored the completely ordinary way: `:value` from a
;; ratom, `:on-change` writing that ratom. The DOM value still reading the
;; typed text after the rerender proves the component OWNS it, rather than
;; the DOM displaying an assignment React is about to revert.

(defn- controlled-input [state]
  (fn []
    [:input {:type      "text"
             :data-test "typed-email"
             :value     (:email @state)
             :on-change #(swap! state assoc :email (.. % -target -value))}]))

(deftest type-step-reaches-controlled-input-on-change
  (with-browser-act
    (fn [act-fn]
      (let [state       (r/atom {:email ""})
            [node root] (mount! act-fn [(controlled-input state)])
            sel         "[data-test=\"typed-email\"]"]
        (try
          (act-fn (fn [] (rf.story.play.dom/type! (.querySelector node sel) "ada@example.com")))
          (is (= ["ada@example.com" "ada@example.com"]
                 [(:email @state) (.-value (.querySelector node sel))]))
          (finally (unmount! root)))))))

(defn- controlled-textarea [state]
  (fn []
    [:textarea {:data-test "typed-notes"
                :value     (:notes @state)
                :on-change #(swap! state assoc :notes (.. % -target -value))}]))

(deftest type-step-reaches-controlled-textarea-on-change
  ;; the prototype-setter walk is not input-specific
  (with-browser-act
    (fn [act-fn]
      (let [state       (r/atom {:notes ""})
            [node root] (mount! act-fn [(controlled-textarea state)])
            sel         "[data-test=\"typed-notes\"]"]
        (try
          (act-fn (fn [] (rf.story.play.dom/type! (.querySelector node sel) "line one")))
          (is (= ["line one" "line one"]
                 [(:notes @state) (.-value (.querySelector node sel))]))
          (finally (unmount! root)))))))

;; ---- the plain-DOM path --------------------------------------------------

(deftest type-step-still-drives-a-plain-dom-input
  ;; a plain (non-React) input takes the value and sees both dispatched events
  (if-not (browser?)
    (is true ":node-test: no DOM — the browser-test runner exercises this")
    (let [input (js/document.createElement "input")
          seen  (atom [])]
      (set! (.-type input) "text")
      (js/document.body.appendChild input)
      (.addEventListener input "input"  (fn [_] (swap! seen conj :input)))
      (.addEventListener input "change" (fn [_] (swap! seen conj :change)))
      (try
        (is (= [true "plain" [:input :change]]
               [(rf.story.play.dom/type! input "plain") (.-value input) @seen]))
        (finally (.remove input))))))

;; ---- the mechanism, asserted directly ------------------------------------

(deftest native-value-setter-skips-the-own-property-setter
  ;; An own-property accessor installed exactly the way React's
  ;; `trackValueOnNode` does; the walk steps over it to the prototype's.
  (if-not (browser?)
    (is true ":node-test: no DOM — the browser-test runner exercises this")
    (let [input     (js/document.createElement "input")
          proto     (js/Object.getOwnPropertyDescriptor (js/Object.getPrototypeOf input) "value")
          proto-set (.-set proto)]
      (js/Object.defineProperty
        input "value"
        #js {:configurable true
             :get (fn [] (.call (.-get proto) input))
             :set (fn [v] (.call proto-set input v))})
      (is (identical? proto-set (rf.story.play.dom/native-value-setter input))))))
