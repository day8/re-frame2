(ns re-frame.story.ui.subject-text-reset-dom-cljs-test
  "DOM-mount acceptance for rf2-w72ij: Story's chrome text styles stop at the
  subject boundary, and the chrome keeps them.

  The shell, the canvas wrap and each workspace cell set Story's
  `:text-primary` colour and chrome fonts for their own parts. Colour and font
  are inherited, so before this change a subject that set no colour of its own
  rendered in Story's warm off-white: the login testbed card, a white card
  whose heading sets no colour, read 1.19:1. The testbed carried a colour line
  to hide that, and this change removes it, so these tests mount the real card
  WITHOUT it and read what the browser computes.

  What is asserted, at both subject sites (the canvas single pane and a
  workspace cell):

  - the card heading's computed colour equals the browser default, read off a
    control element styled `all: initial`, and is not `:text-primary`;
  - the variant root, `[data-rf-story-variant-root]`, computes the browser
    default font family and size (the card sets its own font family, so the
    heading cannot witness the font reset; the boundary element can);
  - the chrome OUTSIDE the boundary still computes its tokens: the canvas
    section and workspace cell read `:text-primary`, and their title rows
    keep `:info` / `:warning`.

  Control: with the boundary reset removed from
  `rf.story.ui.multi-substrate/subject-root-style`, the heading reads
  `:text-primary` and the colour assertions go red.

  The grain backdrop's half of the item (the overlay may not sit in the
  subject's grading path) is witnessed by the last test, which needs no DOM.

  The fixture mirrors `login-form.stories-cljs-test` and
  `promotion-row-dom-cljs-test`: the source-store baseline is captured once at
  ns load, so the variant frames' `login-form.**` image resolves whatever a
  sibling test ns cleared first. Unlike those two, these tests RENDER the
  card, and the `:reagent` substrate resolves a view through the registrar,
  which `rf.story/clear-all!` wipes. So the registrar as it stood at ns load
  (the login views, events and subs registered) is restored as well.

  `-dom-cljs-test$` opts the file into the `:browser-test` build. `:node-test`
  also loads it (its `cljs-test$` regex matches); the DOM bodies self-gate on
  `(browser?)` and record a visible skip there."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react-dom" :as react-dom]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.source-store :as rf.source-store]
            [re-frame.test-support :as rf.test-support]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.story :as rf.story]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.runtime :as rf.story.runtime]
            [re-frame.story.theme.colors :as rf.story.theme.colors]
            [re-frame.story.theme.depth :as rf.story.theme.depth]
            [re-frame.story.ui.canvas :as rf.story.ui.canvas]
            [re-frame.story.ui.state :as rf.story.ui.state]
            [re-frame.story.ui.workspace :as rf.story.ui.workspace]
            [login-form.events]
            [login-form.subs]
            [login-form.views]
            [login-form.stories :as lf-stories]))

;; ---- fixture --------------------------------------------------------------

(def ^:private variant-id :story.login-form/idle)

(def ^:private registrar-snapshot (atom nil))

(def ^:private source-store-baseline @rf.source-store/kind->id->ns->descriptor)

;; Captured once at ns load, after the `:require` chain has registered the
;; login views, events and subs. A sibling test ns's fixture can clear the
;; registrar before this ns's tests run, and the card cannot render without
;; its view.
(def ^:private ns-load-registrar (rf.test-support/snapshot-registrar))

(defn- before! []
  (reset! registrar-snapshot (rf.test-support/snapshot-registrar))
  (reset! rf.frame/frames {})
  (try (rf/init! rf.adapter.reagent/adapter) (catch :default _ nil))
  (rf.frame/ensure-default-frame!)
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story/clear-all!)
  (rf.test-support/restore-registrar! ns-load-registrar)
  (reset! rf.source-store/kind->id->ns->descriptor source-store-baseline)
  (rf.machines/install-machine-runtime!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.story.ui.canvas/reset-first-rendered!)
  (lf-stories/register-all!))

(defn- after! []
  (when-let [snap @registrar-snapshot]
    (rf.test-support/restore-registrar! snap)
    (reset! registrar-snapshot nil))
  (rf.story.ui.canvas/reset-first-rendered!)
  (reset! rf.frame/frames {}))

(use-fixtures :each {:before before! :after after!})

;; ---- helpers --------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- poll-until
  "Call `(f value)` once `(probe)` returns a truthy value, or with nil once
  `timeout-ms` has passed. Polls on `setTimeout`, so Reagent's render queue
  gets to run between probes."
  [probe timeout-ms f]
  (let [deadline (+ (.now js/Date) timeout-ms)]
    (letfn [(poll []
              (let [v (probe)]
                (cond
                  v                           (f v)
                  (> (.now js/Date) deadline) (f nil)
                  :else                       (js/setTimeout poll 25))))]
      (poll))))

(defn- rendered-text
  "What a mount node actually painted, for a precondition's failure message."
  [^js node]
  (let [text (or (.-textContent node) "")]
    (pr-str (subs text 0 (min 300 (count text))))))

(defn- append-to-body! [^js el]
  (js/document.body.appendChild el)
  el)

(defn- browser-default-control!
  "An element carrying no author styles at all, so its computed text styles
  are the browser defaults a plain page gives."
  []
  (let [el (js/document.createElement "div")]
    (.setAttribute el "style" "all: initial")
    (.appendChild el (js/document.createTextNode "control"))
    (append-to-body! el)))

(defn- computed [^js el prop]
  (.getPropertyValue (js/getComputedStyle el) prop))

(defn- token-rgb
  "The computed-style spelling of a `#RRGGBB` colour token."
  [token]
  (let [hex (subs (get rf.story.theme.colors/tokens token) 1)
        [r g b] (map #(js/parseInt (subs hex % (+ % 2)) 16) [0 2 4])]
    (str "rgb(" r ", " g ", " b ")")))

(defn- assert-subject-boundary!
  "The shared reading: `node` holds one mounted login card inside Story
  chrome; `control` is the browser-default element."
  [^js node ^js control]
  (let [heading (.querySelector node "[data-test=\"login-heading\"]")
        subject (.querySelector node "[data-rf-story-variant-root]")]
    (is (some? subject) "precondition: the subject boundary is stamped")
    (is (not= (token-rgb :text-primary) (computed control "color"))
        "precondition: the browser default is not the chrome colour")
    (is (= (computed control "color") (computed heading "color"))
        "the card heading, which sets no colour, reads the browser default")
    (is (not= (token-rgb :text-primary) (computed heading "color"))
        "the card heading does not read Story's :text-primary")
    (when subject
      (is (= (computed control "color") (computed subject "color"))
          "the subject boundary computes the browser-default colour")
      (is (= (computed control "font-family") (computed subject "font-family"))
          "the subject boundary computes the browser-default font family")
      (is (= (computed control "font-size") (computed subject "font-size"))
          "the subject boundary computes the browser-default font size"))))

(defn- mount! [hiccup]
  (let [node (append-to-body! (js/document.createElement "div"))
        root (rdc/create-root node)]
    (react-dom/flushSync (fn [] (rdc/render root hiccup)))
    {:node node :root root}))

(defn- unmount! [{:keys [^js node root]} ^js control]
  (try (.unmount root) (catch :default _ nil))
  (.remove node)
  (.remove control))

;; ---- the canvas single pane ----------------------------------------------

(deftest canvas-subject-reads-browser-default-text-styles
  (testing "rf2-w72ij: in the canvas, the login card heading (no colour of
            its own, the testbed's colour line removed) reads the browser
            default, while the canvas chrome keeps its tokens"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs the real assertion")
      (async done
        (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant variant-id)
        ;; Prepare the run the canvas's own lifecycle would prepare, under the
        ;; same run-key (idempotent per key), and pass the loading skeleton so
        ;; the first commit reaches the subject.
        (rf.story.runtime/prepare-run!
          variant-id
          {:active-modes   nil
           :cell-overrides nil
           :substrate      nil
           :run-key        (rf.story.ui.canvas/run-key @rf.story.ui.state/shell-state-atom
                                                       variant-id)})
        (rf.story.ui.canvas/mark-variant-rendered! variant-id)
        (let [control (browser-default-control!)
              mounted (mount! [rf.story.ui.canvas/canvas])
              node    (:node mounted)]
          (poll-until
            #(.querySelector node "[data-test=\"login-heading\"]")
            10000
            (fn [heading]
              (is (some? heading)
                  (str "precondition: the login card rendered in the canvas; the mount node reads "
                       (rendered-text node)))
              (when heading
                (assert-subject-boundary! node control)
                (let [wrap  (.querySelector node "section[aria-label=\"Variant canvas\"]")
                      title (some-> wrap .-firstElementChild .-firstElementChild)]
                  (is (= (token-rgb :text-primary) (computed wrap "color"))
                      "the canvas chrome outside the boundary still reads :text-primary")
                  (is (= (token-rgb :info) (computed title "color"))
                      "the canvas title row keeps its :info token")
                  (is (not= (computed control "font-family") (computed title "font-family"))
                      "the canvas title row keeps the chrome font")))
              (unmount! mounted control)
              (done))))))))

;; ---- a workspace cell ------------------------------------------------------

(def ^:private variant-cell @#'rf.story.ui.workspace/variant-cell)

(deftest workspace-cell-subject-reads-browser-default-text-styles
  (testing "rf2-w72ij: inside a workspace cell, the login card heading reads
            the browser default, while the cell and its title keep their
            tokens"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs the real assertion")
      (async done
        (let [control (browser-default-control!)
              ;; The cell runs its variant synchronously before its first
              ;; render (`r/with-let`), so the card is on the first commit.
              mounted (mount! [variant-cell variant-id])
              node    (:node mounted)]
          (poll-until
            #(.querySelector node "[data-test=\"login-heading\"]")
            10000
            (fn [heading]
              (is (some? heading)
                  (str "precondition: the login card rendered in the cell; the mount node reads "
                       (rendered-text node)))
              (when heading
                (assert-subject-boundary! node control)
                (let [cell  (.querySelector node "[data-test-variant]")
                      title (some-> cell .-firstElementChild)]
                  (is (= (token-rgb :text-primary) (computed cell "color"))
                      "the cell outside the boundary still reads :text-primary")
                  (is (= (token-rgb :warning) (computed title "color"))
                      "the cell title keeps its :warning token")
                  (is (not= (computed control "font-family") (computed title "font-family"))
                      "the cell title keeps the chrome font")))
              (unmount! mounted control)
              (done))))))))

;; ---- the grain backdrop ----------------------------------------------------

(deftest grain-backdrop-is-a-sibling-layer-not-an-ancestor-pseudo
  (testing "rf2-w72ij: axe leaves a text node's contrast ungraded when ANY
            ancestor carries a positioned background pseudo, and the shell
            root is every subject's ancestor — so the grain styles its own
            layer and no pseudo-element at all"
    (is (re-find #"\[data-rf-story-root\] > \[data-rf-story-grain\]\{position:absolute"
                 rf.story.theme.depth/grain-css)
        "the grain is an absolutely positioned layer inside the root")
    (is (nil? (re-find #"::before|::after" rf.story.theme.depth/grain-css))
        "the grain rules style no pseudo-element")))
