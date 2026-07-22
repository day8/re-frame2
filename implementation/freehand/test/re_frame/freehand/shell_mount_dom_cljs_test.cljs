(ns re-frame.freehand.shell-mount-dom-cljs-test
  "FH-SUB-002 … FH-SUB-006 in a real browser — the atomic shell under the
  host it exists for.

  The cross-host suite proves what the shell SAYS: which handles a commit
  owns, which candidate is refused, what a reconcile releases. This one
  proves what REACT DOES with it, and the two are not the same claim. A
  commit reconciler can be perfectly shaped around a provider retarget
  React never re-renders, an unmount whose layout cleanup never runs, or a
  speculative render the reconciler happily publishes twice — and no
  amount of headless assertion would notice, because the assertion and
  the bug would share an author.

  So: a real `react-dom/client` root, real `useContext` / `useLayoutEffect`
  / `StrictMode`, and every claim read back off the live sub-cache or off
  `document`.

  This file rides the browser lane through its `-dom-cljs-test` namespace
  suffix. It also matches the node suites' broader regex, where it has no
  DOM to mount and says so rather than passing quietly."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.live-frame :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :async?        true}))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- act
  "A React 19 `act` boundary as a promise, so assertions run after the
  commit rather than racing it."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

;; ---------------------------------------------------------------------------
;; Ownership, read off the live sub-cache
;; ---------------------------------------------------------------------------

(defn- ref-count [f q] (:ref-count (get @(:sub-cache (frame/frame f)) q)))

(defn- make-frame! [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

(defn- reg! []
  (rf/reg-sub :dom/v (fn [db _] (:v db)))
  (rf/reg-sub :dom/row (fn [db [_ id]] (get-in db [:rows id])))
  (rf/reg-sub :dom/old (fn [db _] (:old db)))
  (rf/reg-sub :dom/new (fn [db _] (:new db))))

;; ---------------------------------------------------------------------------
;; Views. Module-level, because a declared view cannot close over a test's
;; locals — the render counter is the seam a test reads back.
;; ---------------------------------------------------------------------------

(defonce ^:private render-count (atom 0))

(v/defview reader
  "Reads the ambient frame's `:v` and carries NO props, so a provider
  retarget cannot repaint it through the prop channel. If this text
  follows a retarget, it followed the frame."
  [_]
  (swap! render-count inc)
  [:p {:id "v"} (str (cell/observe! [:dom/v]))])

(v/defview row
  [{:keys [id]}]
  [:li {:data-id (str id)} (str (cell/observe! [:dom/row id]))])

(v/defview row-list
  [{:keys [ids]}]
  [:ul {:id "rows"}
   (for [id ids]
     [row {:key id :id id}])])

(v/defview old-body
  [_]
  [:p {:id "body"} (str (cell/observe! [:dom/old]))])

(v/defview new-body
  [_]
  [:p {:id "body"} (str (cell/observe! [:dom/new]))])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- mount!
  []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    [container (rdc/createRoot container)]))

(defn- teardown!
  [container root]
  (.unmount root)
  (.remove container)
  nil)

(defn- text [container selector]
  (some-> (.querySelector container selector) .-textContent))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

;; ===========================================================================
;; FH-SUB-004 — HAZARD 3: frame retarget, through a real provider
;; ===========================================================================

(deftest fh-sub-004-a-provider-retarget-rebinds-a-props-equal-child
  (testing "Per FH-SUB-004 (hazard: frame retarget, browser): the
            interpreted shell observes frame context UNCONDITIONALLY, so
            a provider retarget from A to B rebinds a child whose own
            props did not move. A shell that peeked at the context
            object's private slot instead of subscribing through
            `useContext` would let React bail the non-consumer child, its
            body would never rerun, and its dependency would stay locked
            to A — which is exactly what the DOM here would show."
    (if-not (browser?)
      (skip! "the browser job runs the retarget assertions")
      (async done
        (reg!)
        (make-frame! :dom/frame-a {:v "A"})
        (make-frame! :dom/frame-b {:v "B"})
        (let [[container root] (mount!)]
          (-> (act #(.render root (shell/provide-frame :dom/frame-a
                                                       (fr/element [reader {}]))))
              (.then (fn [_]
                       (is (= "A" (text container "#v")) "bound to frame A")
                       (is (= 1 (ref-count :dom/frame-a [:dom/v])))
                       (is (nil? (ref-count :dom/frame-b [:dom/v])))
                       (act #(.render root (shell/provide-frame :dom/frame-b
                                                                (fr/element [reader {}]))))))
              (.then (fn [_]
                       (is (= "B" (text container "#v"))
                           "the retarget rebound a child with equal props")
                       (is (nil? (ref-count :dom/frame-a [:dom/v]))
                           "frame A's node was released once frame B's was acquired")
                       (is (= 1 (ref-count :dom/frame-b [:dom/v])))
                       (teardown! container root)
                       (done))
                     (fn [e]
                       (is false (str "mount rejected: " e))
                       (teardown! container root)
                       (done)))))))))

;; ===========================================================================
;; FH-SUB-005 — HAZARD 4: key reorder
;; ===========================================================================

(deftest fh-sub-005-a-keyed-reorder-moves-occurrences-it-does-not-rebuild-them
  (testing "Per FH-SUB-005 (hazard: key reorder, browser): reordering a
            keyed run MOVES each occurrence — the same host node, the
            same boundary, the same cell — rather than rebuilding the run
            positionally. The proof is DOM node IDENTITY: a positional
            rebuild would hand row 3 a different node, and its cell would
            have been torn down and re-acquired on a reorder that changed
            nothing about it."
    (if-not (browser?)
      (skip! "the browser job runs the reorder assertions")
      (async done
        (reg!)
        (make-frame! :dom/frame-a {:rows {1 "one" 2 "two" 3 "three"}})
        (let [[container root] (mount!)
              node-3 (atom nil)]
          (-> (act #(.render root (shell/provide-frame :dom/frame-a
                                                       (fr/element [row-list {:ids [1 2 3]}]))))
              (.then (fn [_]
                       (is (= "onetwothree" (text container "#rows")))
                       (is (= 1 (ref-count :dom/frame-a [:dom/row 1])))
                       (is (= 1 (ref-count :dom/frame-a [:dom/row 3])))
                       (reset! node-3 (.querySelector container "[data-id='3']"))
                       (act #(.render root (shell/provide-frame
                                             :dom/frame-a
                                             (fr/element [row-list {:ids [3 1 2]}]))))))
              (.then (fn [_]
                       (is (= "threeonetwo" (text container "#rows"))
                           "the run follows the keys, not the positions")
                       (is (identical? @node-3 (.querySelector container "[data-id='3']"))
                           "row 3 was MOVED — the same host node, so the same boundary
                            and the same cell")
                       (is (= 1 (ref-count :dom/frame-a [:dom/row 1]))
                           "and no row acquired a second owner across the reorder")
                       (is (= 1 (ref-count :dom/frame-a [:dom/row 3])))
                       (teardown! container root)
                       (done))
                     (fn [e]
                       (is false (str "mount rejected: " e))
                       (teardown! container root)
                       (done)))))))))

;; ===========================================================================
;; FH-SUB-006 — HAZARD 5: disconnect
;; ===========================================================================

(deftest fh-sub-006-unmount-releases-every-dependency-the-tree-owned
  (testing "Per FH-SUB-006 (hazard: disconnect, browser): React unmount
            runs the shell's lifecycle cleanup, which releases exactly
            what the selected commit owned. Every node reaches its
            zero-owner disposal edge — a tree that unmounted without
            releasing would leave the sub-cache holding live nodes for
            boundaries that no longer exist, and nothing else in the
            system would ever notice."
    (if-not (browser?)
      (skip! "the browser job runs the teardown assertions")
      (async done
        (reg!)
        (make-frame! :dom/frame-a {:rows {1 "one" 2 "two" 3 "three"}})
        (let [[container root] (mount!)]
          (-> (act #(.render root (shell/provide-frame :dom/frame-a
                                                       (fr/element [row-list {:ids [1 2 3]}]))))
              (.then (fn [_]
                       (is (= 1 (ref-count :dom/frame-a [:dom/row 1])))
                       (is (= 1 (ref-count :dom/frame-a [:dom/row 2])))
                       (is (= 1 (ref-count :dom/frame-a [:dom/row 3])))
                       (act #(.unmount root))))
              (.then (fn [_]
                       (is (nil? (ref-count :dom/frame-a [:dom/row 1])))
                       (is (nil? (ref-count :dom/frame-a [:dom/row 2])))
                       (is (nil? (ref-count :dom/frame-a [:dom/row 3]))
                           "every row's node disposed on its zero-owner edge")
                       (.remove container)
                       (done))
                     (fn [e]
                       (is false (str "mount rejected: " e))
                       (.remove container)
                       (done)))))))))

;; ===========================================================================
;; FH-SUB-002 — HAZARD 1: an abandoned render publishes nothing
;; ===========================================================================

(deftest fh-sub-002-strictmode-renders-twice-and-publishes-once
  (testing "Per FH-SUB-002 (hazard: abandoned render, browser):
            StrictMode renders every body TWICE and keeps ONE of them.
            The discarded render's candidate is a value React never
            retained, so it publishes nothing — and the boundary ends up
            with exactly ONE owner, not two. A shell that published from
            an ambient render slot would double-own here, and the second
            owner would never be released."
    (if-not (browser?)
      (skip! "the browser job runs the StrictMode assertions")
      (async done
        (reg!)
        (make-frame! :dom/frame-a {:v "A"})
        (reset! render-count 0)
        (let [[container root] (mount!)]
          (-> (act #(.render root (react/createElement
                                    react/StrictMode nil
                                    (shell/provide-frame :dom/frame-a
                                                         (fr/element [reader {}])))))
              (.then (fn [_]
                       (is (<= 2 @render-count)
                           "StrictMode really did render the body more than once —
                            without this the one-owner assertion below is vacuous")
                       (is (= "A" (text container "#v")))
                       (is (= 1 (ref-count :dom/frame-a [:dom/v]))
                           "exactly ONE owner survived the double render")
                       (act #(.unmount root))))
              (.then (fn [_]
                       (is (nil? (ref-count :dom/frame-a [:dom/v]))
                           "and that one owner released cleanly, so nothing was
                            double-acquired and left behind")
                       (.remove container)
                       (done))
                     (fn [e]
                       (is false (str "mount rejected: " e))
                       (.remove container)
                       (done)))))))))

;; ===========================================================================
;; FH-SUB-003 — HAZARD 2: hot reload across a live cell
;; ===========================================================================

(deftest fh-sub-003-a-redeclared-view-remounts-and-releases-the-old-body
  (testing "Per FH-SUB-003 (hazard: HMR across a live cell, browser): a
            reload redeclares the view, and a declared view is a
            DESCRIPTOR VALUE — so the emitter mints a new component,
            React remounts the boundary, and the old cell's lifecycle
            cleanup releases exactly the ownership the OLD body had. No
            cell is left owning a dependency of a body that no longer
            exists, and no cell publishes a body it did not execute."
    (if-not (browser?)
      (skip! "the browser job runs the reload assertions")
      (async done
        (reg!)
        (make-frame! :dom/frame-a {:old "before" :new "after"})
        (let [[container root] (mount!)]
          (-> (act #(.render root (shell/provide-frame :dom/frame-a
                                                       (fr/element [old-body {}]))))
              (.then (fn [_]
                       (is (= "before" (text container "#body")))
                       (is (= 1 (ref-count :dom/frame-a [:dom/old])))
                       (is (nil? (ref-count :dom/frame-a [:dom/new])))
                       (act #(.render root (shell/provide-frame :dom/frame-a
                                                                (fr/element [new-body {}]))))))
              (.then (fn [_]
                       (is (= "after" (text container "#body"))
                           "the reloaded body is what renders")
                       (is (nil? (ref-count :dom/frame-a [:dom/old]))
                           "and the old body's dependency was released with its cell")
                       (is (= 1 (ref-count :dom/frame-a [:dom/new])))
                       (teardown! container root)
                       (done))
                     (fn [e]
                       (is false (str "mount rejected: " e))
                       (teardown! container root)
                       (done)))))))))
