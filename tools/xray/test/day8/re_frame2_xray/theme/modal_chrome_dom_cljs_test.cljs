(ns day8.re-frame2-xray.theme.modal-chrome-dom-cljs-test
  "Live-DOM witnesses that an open modal keeps the keyboard user's place.

  `modal-chrome` runs on every render of the view that calls it, and its
  dialog carries the `a11y/dialog-ref` focus contract as a React `:ref`.
  React detaches a ref that changed between renders and attaches the new
  one, and that contract's detach restores focus to the opener while its
  attach moves focus to the dialog's first control. So the dialog's ref
  has to be the same value on every render, or each re-render of an open
  dialog drags focus back to its first control.

  These rows mount the chrome under a real React root, move focus to a
  later control, re-render, and read `document.activeElement`. Only a
  committed DOM can answer that: a node-lane tree walk sees the `:ref` as
  a value and never runs it.

  ## Test target

  The ns ends in `-dom-cljs-test`, so it runs under the `:browser-test`
  build (real DOM + React via Chromium). The `:node-test` build also
  loads it, where every row reports the skip rather than passing
  silently."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [day8.re-frame2-xray.theme.modal-chrome :as modal-chrome]))

(defn- browser?
  "True only under the real-DOM `:browser-test` build."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- witness
  "An open modal whose second control's label carries `pass`, so each new
  `pass` is a real re-render of the same dialog."
  [pass]
  (modal-chrome/modal-chrome
    {:positioning      :fixed
     :backdrop-style   {}
     :dialog-style     {}
     :on-dismiss       (fn [] nil)
     :label            "Focus witness"
     :dialog-testid    "modal-chrome-witness-dialog"
     :dialog-tab-index "-1"}
    [:button {:data-testid "modal-chrome-witness-first"} "first"]
    [:button {:data-testid "modal-chrome-witness-second"} (str "second " pass)]))

;; Every accessor is nil-tolerant: the browser lane runs as one block with
;; no try/catch, so a throw on an absent node would abort every namespace
;; after this one instead of failing a row.

(defn- q [container testid]
  (some-> container (.querySelector (str "[data-testid=\"" testid "\"]"))))

(defn- focus! [node]
  (some-> node (.focus)))

(defn- active [] (.-activeElement js/document))

(defn- render!
  "Render `[witness pass]` into `root`, committed before this returns."
  [root pass]
  (react-dom/flushSync (fn [] (rdc/render root [witness pass]))))

(defn- open!
  "Focus a fresh opener button outside the dialog, then mount the dialog
  into its own container. Returns the handles the rows and cleanup need."
  []
  (let [opener    (.createElement js/document "button")
        container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) opener)
    (.appendChild (.-body js/document) container)
    (.focus opener)
    (render! root 0)
    {:opener opener :container container :root root}))

(defn- close!
  "Unmount inside `flushSync`, so the ref's teardown has run when this
  returns. A second unmount of the same root is a no-op."
  [root]
  (react-dom/flushSync (fn [] (.unmount root))))

(defn- cleanup! [{:keys [opener container]}]
  (.remove container)
  (.remove opener))

(deftest an-open-dialog-keeps-focus-across-a-re-render
  (testing "re-rendering an open dialog leaves focus on the control that
            had it — the ref the chrome hands React is the same value on
            every render, so React never detaches and re-attaches it"
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [{:keys [container root] :as handles} (open!)
            second-btn (fn [] (q container "modal-chrome-witness-second"))]
        (try
          (focus! (second-btn))
          (is (and (some? (second-btn)) (identical? (second-btn) (active)))
              "precondition: the dialog's SECOND control holds focus")
          (render! root 1)
          (is (= "second 1" (some-> (second-btn) .-textContent))
              "the re-render committed — the second control's label moved")
          (is (and (some? (second-btn)) (identical? (second-btn) (active)))
              "focus is still on the second control after the re-render")
          (finally
            (close! root)
            (cleanup! handles)))))))

(deftest the-dialog-still-takes-and-returns-focus
  (testing "the focus contract still runs at the two ends of the dialog's
            life: mounting lands focus on the first control, and
            unmounting returns it to the opener"
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [{:keys [opener container root] :as handles} (open!)]
        (try
          (is (identical? (q container "modal-chrome-witness-first") (active))
              "mount moved focus to the dialog's first control")
          (render! root 1)
          (close! root)
          (is (identical? opener (active))
              "unmount restored focus to the opener")
          (finally
            (close! root)
            (cleanup! handles)))))))
