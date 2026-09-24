(ns re-frame.story.ui.multi-substrate-boundary-dom-cljs-test
  "rf2-3x7nj.29.3 — the side-by-side grid's per-cell error boundary CONTAINS
  a view that throws under one substrate.

  The grid is the surface that shows substrate-portability gaps, so a view
  that throws under one substrate is its whole reason to exist: the healthy
  cell keeps rendering and the failing cell turns red beside it
  (`tools/story/spec/003-Render-Shell.md` §Multi-substrate side-by-side
  rendering). The boundary it used to have captured the error and then
  rendered the same throwing child again, so React handed the error to the
  next boundary up. Story has none, so the whole shell unmounted.

  Every row mounts the REAL grid through `reagent.dom.client` inside an
  outer boundary this file owns, which records anything that escapes the
  cell. An error reaching it is exactly the defect, and containing it here
  keeps a regression from taking down the rest of the browser suite.

  `-dom-cljs-test$` puts this namespace in `:browser-test` AND in
  `:node-test`; the rows need a real fiber, so in Node they say so rather
  than passing quietly."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react-dom" :as react-dom]
            [goog.object :as gobj]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.story :as rf.story]
            [re-frame.story.ui.multi-substrate :as rf.story.ui.multi-substrate]
            [re-frame.story.ui.state :as rf.story.ui.state]))

;; ---- subjects ---------------------------------------------------------------

(defonce ^:private !mounts (atom 0))

(defn- mount-counter
  "A form-2 component: the outer body runs once per MOUNT, so a row can tell
  a re-render from a remount."
  []
  (swap! !mounts inc)
  (fn [] [:span "mounted"]))

(defn- healthy-view
  "Renders under every substrate."
  [_args]
  [:div {:data-test "healthy-view"} "healthy " [mount-counter]])

(defn- throwing-view [_args]
  (throw (js/Error. "boom under uix")))

(defn- uix-render
  "Stand-in for a host-registered `:uix` render fn. It hands back a CHILD
  component, as the real `:reagent` renderer does, so the throw happens while
  React renders the cell's children rather than inside the render fn call.
  `:views/boom` throws under this substrate only; everything else renders."
  [_variant-id view-id eff-args]
  (if (= :views/boom view-id)
    [throwing-view eff-args]
    [:div {:data-test "uix-healthy"} "healthy under uix"]))

;; ---- the outer boundary -----------------------------------------------------

(defonce ^:private !escaped (atom nil))

(def ^:private outer-boundary
  "Stands where the Story shell's root would: anything a cell lets through
  lands here and is recorded, instead of unmounting the test root."
  (r/create-class
    {:display-name "multi-substrate-boundary-test-outer"
     :get-initial-state (fn [_] #js {:escaped false})
     :get-derived-state-from-error (fn [_error] #js {:escaped true})
     :component-did-catch (fn [_this error _info] (reset! !escaped (str error)))
     :reagent-render
     (fn [child]
       (if (gobj/get (.-state (r/current-component)) "escaped")
         [:div {:data-test "escaped"} "the error escaped the grid"]
         child))}))

;; ---- fixture ----------------------------------------------------------------

(defn- reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.adapter.reagent/adapter) (catch :default _ nil))
  (rf.story.ui.state/reset-shell-state!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (reset! !mounts 0)
  (reset! !escaped nil)
  (rf/reg-view* :views/healthy healthy-view)
  (rf/reg-view* :views/boom healthy-view)
  (rf.story/register-substrate! :uix uix-render)
  (rf.story/reg-story* :story.grid-boundary {:doc "rf2-3x7nj.29.3 witness story"})
  (rf.story/reg-variant* :story.grid-boundary/boom
    {:doc        "Renders under :reagent, throws under :uix."
     :component  :views/boom
     :substrates #{:reagent :uix}})
  (rf.story/reg-variant* :story.grid-boundary/healthy
    {:doc        "Renders under both."
     :component  :views/healthy
     :substrates #{:reagent :uix}}))

(defn- restore-registry!
  "`substrate->render-fn` is a `defonce` atom `rf.story/clear-all!` does not
  touch, so the `:uix` stub would leak into later namespaces."
  []
  (rf.story.ui.multi-substrate/unregister-substrate! :uix))

(use-fixtures :each {:before reset-all! :after restore-registry!})

;; ---- helpers ----------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- settle! []
  (r/flush)
  (react-dom/flushSync (fn [] nil))
  nil)

(defn- mount-grid!
  "Render `variant-id`'s grid inside the outer boundary into `root`."
  [root variant-id]
  (react-dom/flushSync
    (fn []
      (rdc/render root
        [outer-boundary
         [rf.story.ui.multi-substrate/multi-substrate-grid variant-id]]))))

(defn- with-root
  "Run `(f node root)` against a fresh mount node, then unmount and detach."
  [f]
  (let [node (js/document.createElement "div")
        _    (js/document.body.appendChild node)
        root (rdc/create-root node)]
    (try
      (f node root)
      (finally
        (react-dom/flushSync (fn [] (.unmount root)))
        (.remove node)))))

(defn- text [^js node] (.-textContent node))

(defn- found? [^js node selector]
  (some? (.querySelector node selector)))

;; ===========================================================================
;; THE WITNESS — one substrate throws, the other keeps rendering
;; ===========================================================================

(deftest a-throwing-substrate-renders-a-red-cell-beside-the-healthy-one
  (testing "rf2-3x7nj.29.3: the view throws under :uix only. The :reagent
            cell renders, the :uix cell shows the render error with its
            message, and nothing reaches the boundary above the grid"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs this row")
      (with-root
        (fn [node root]
          (mount-grid! root :story.grid-boundary/boom)
          (is (nil? @!escaped)
              "the throw stayed inside its cell — before the fix it escaped the grid")
          (is (found? node "[data-test=\"healthy-view\"]")
              "the :reagent cell rendered the view")
          (is (re-find #"uix — render error" (text node))
              "the :uix cell is the red error cell")
          (is (re-find #"boom under uix" (text node))
              "and it carries the thrown message"))))))

(deftest a-shell-state-change-re-renders-the-cells-without-remounting
  (testing "rf2-3x7nj.29.3 (secondary): the grid derefs the shell state, so
            any shell-state change re-renders it. The cell boundary is ONE
            component type, so the subject re-renders in place and keeps its
            local state — a boundary class minted per render remounted every
            cell instead"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs this row")
      (with-root
        (fn [node root]
          (mount-grid! root :story.grid-boundary/healthy)
          (is (found? node "[data-test=\"healthy-view\"]"))
          (is (= 1 @!mounts) "control: the subject mounted once")
          (rf.story.ui.state/swap-state! assoc ::unrelated (random-uuid))
          (settle!)
          (is (found? node "[data-test=\"healthy-view\"]"))
          (is (= 1 @!mounts) "the shell-state change did not remount the subject"))))))

(deftest a-captured-error-clears-when-the-cell-renders-something-else
  (testing "the boundary is one long-lived instance per cell, so a captured
            error must not outlive its inputs: moving the grid to a variant
            that renders cleanly clears the red cell"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs this row")
      (with-root
        (fn [node root]
          (mount-grid! root :story.grid-boundary/boom)
          (is (re-find #"uix — render error" (text node)) "control: the :uix cell is red")
          (mount-grid! root :story.grid-boundary/healthy)
          (is (nil? @!escaped))
          (is (found? node "[data-test=\"uix-healthy\"]")
              "the :uix cell renders the new variant")
          (is (not (re-find #"render error" (text node)))
              "no error cell is left behind"))))))
