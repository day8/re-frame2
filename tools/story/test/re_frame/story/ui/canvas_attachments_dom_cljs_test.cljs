(ns re-frame.story.ui.canvas-attachments-dom-cljs-test
  "What the shell attaches to the variant canvas, against a real document:

  - Inspect mode installs its listeners on the CURRENT canvas root when it
    turns on. The root exists only while a variant shows on the Canvas or
    Tests tab, so listeners bound once at shell mount would leave inspect
    mode attached to nothing.
  - The a11y scan reaches every variant root the side-by-side grid mounts,
    and a re-scan or a frame reset clears the outlines it stamped.
  - The stored mode tab seeds only on a selection gesture: rendering the
    tab strip never writes it, so a URL with no `mode-tab=` stays on Canvas.

  `:browser-test` runs the document-bound assertions; the node lane has no
  `document`, so there only the document-free halves run."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.story.ui.a11y :as rf.story.ui.a11y]
            [re-frame.story.ui.element-inspector :as rf.story.ui.element-inspector]
            [re-frame.story.ui.mode-tabs :as rf.story.ui.mode-tabs]
            [re-frame.story.ui.state :as rf.story.ui.state]))

(defn- doc [] (.-document js/globalThis))

(defn- local-storage []
  (try (.-localStorage js/globalThis) (catch :default _ nil)))

(def ^:private host (atom nil))

(defn- mount-host!
  "Append a fresh host element holding `html` to the body."
  [html]
  (when-let [d (doc)]
    (let [el (.createElement d "div")]
      (set! (.-innerHTML el) html)
      (.appendChild (.-body d) el)
      (reset! host el))))

(defn- unmount-host! []
  (when-let [el @host]
    (.remove el)
    (reset! host nil)))

(def ^:private mode-tab-vid :story.attachments/tabbed)

(defn- clear-mode-tab-storage! []
  (when-let [ls (local-storage)]
    (.removeItem ls (str rf.story.ui.mode-tabs/ls-key-prefix mode-tab-vid))))

(use-fixtures :each
  {:before (fn []
             (rf.story.ui.element-inspector/remove!)
             (rf.story.ui.state/reset-shell-state!)
             (clear-mode-tab-storage!))
   :after  (fn []
             (rf.story.ui.element-inspector/remove!)
             (unmount-host!)
             (clear-mode-tab-storage!))})

;; ---- inspect mode --------------------------------------------------------

(deftest inspect-mode-attaches-to-the-canvas-shown-when-it-turns-on
  (when (doc)
    (mount-host! "<div data-test=\"story-canvas-frame\"><button>b</button></div>")
    (is (not (rf.story.ui.element-inspector/installed?))
        "precondition: nothing installed the inspector before")
    (rf.story.ui.element-inspector/set-active! true)
    (is (rf.story.ui.element-inspector/installed?)
        "turning inspect mode on installed its listeners on the canvas root")))

;; ---- a11y scope and outlines ---------------------------------------------

(def ^:private grid-vid :story.attachments/grid)

(deftest the-a11y-scan-covers-every-grid-cell
  (when (doc)
    (let [sel (rf.story.ui.a11y/variant-root-selector grid-vid)
          root-attr (str "data-rf-story-variant-root=\"" (pr-str grid-vid) "\"")]
      (mount-host! (str "<div " root-attr "></div><div " root-attr "></div>"))
      (is (= sel (rf.story.ui.a11y/variant-root-context grid-vid))
          "two mounted roots scan through their selector, which axe expands to both")
      (unmount-host!)
      (mount-host! (str "<div " root-attr "></div>"))
      (is (= (.querySelector (doc) sel) (rf.story.ui.a11y/variant-root-context grid-vid))
          "one mounted root scans as that element")
      (unmount-host!)
      (is (nil? (rf.story.ui.a11y/variant-root-context grid-vid))
          "no mounted root has no scan context"))))

(deftest a-frame-reset-clears-the-violation-outlines
  (when (doc)
    (let [root-attr (str "data-rf-story-variant-root=\"" (pr-str grid-vid) "\"")]
      (mount-host! (str "<div " root-attr "><span data-rf-a11y-violation=\"serious\">x</span></div>"
                        "<span id=\"chrome\" data-rf-a11y-violation=\"minor\">c</span>"))
      (rf.story.ui.a11y/drop-frame-state! grid-vid)
      (is (nil? (.querySelector @host (str "[" root-attr "] [data-rf-a11y-violation]")))
          "the reset frame's subject carries no outline")
      (is (some? (.querySelector @host "#chrome[data-rf-a11y-violation]"))
          "an outline outside the frame's roots is left alone"))))

;; ---- the stored mode tab -------------------------------------------------

(deftest rendering-the-tab-strip-never-seeds-the-stored-tab
  (testing "a URL that dissocs the tab (Back to an entry with no mode-tab=)
            stays on Canvas: the strip renders without reloading the stored
            tab, and only the selection gesture's transition applies it"
    (when-let [ls (local-storage)]
      (.setItem ls (str rf.story.ui.mode-tabs/ls-key-prefix mode-tab-vid) "docs"))
    (rf.story.ui.state/swap-state! assoc :selected-variant mode-tab-vid)
    (rf.story.ui.mode-tabs/mode-tabs-strip mode-tab-vid)
    (is (not (contains? (:active-mode-tab (rf.story.ui.state/get-state)) mode-tab-vid))
        "rendering wrote no tab")
    (when (local-storage)
      (is (= :docs (get-in (rf.story.ui.mode-tabs/with-stored-mode-tab
                             (rf.story.ui.state/get-state) mode-tab-vid)
                           [:active-mode-tab mode-tab-vid]))
          "the selection transition seeds the stored tab"))))
