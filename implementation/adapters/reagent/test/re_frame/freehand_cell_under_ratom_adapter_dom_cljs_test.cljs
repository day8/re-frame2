(ns re-frame.freehand-cell-under-ratom-adapter-dom-cljs-test
  "rf2-8cnxg / rf2-jt8vz — a Freehand view, mounted with `v/mount` under the
  stock REAGENT adapter, in a real browser, repaints when its subscription
  moves.

  THE REPRODUCTION THIS REPLACES. The operator's report was measured on one
  page and one build with the installed adapter as the only variable: under
  `re-frame.freehand/adapter` a click moved the readout 0 → 1 → 2; under
  `re-frame.adapter.reagent/adapter` the dispatch LANDED (app-db moved, the
  trace showed the event) and the readout never changed. The decisive number
  was `cell/pending-count` staying 0 — the cell was never MARKED DIRTY, so
  nothing downstream of `mark-dirty!` could be at fault. No notification was
  ever raised at all: the observation port's watch sat on a
  `reagent.ratom/Reaction` that had never captured its sources, because a
  ViewCell is not a Reagent component and so supplies no capture context.
  See the port-level unit arms in
  `re-frame.observation-port-activates-ratom-node-cljs-test`.

  WHY THE TEST LIVES HERE, AND WHY IT MOUNTS. This is the composition
  nothing else in the tree exercised: `git grep reagent
  implementation/freehand/test/` returned only prose. It cannot live under
  `implementation/freehand/`, whose whole claim is that it names no adapter;
  it belongs beside the sibling cross-adapter port suites in this directory,
  which is also where the substrate half of the fix is proved. And it MOUNTS
  rather than driving the cell by hand, because a hand-driven commit reads
  current values through `read-container` regardless of the notification
  channel — exactly the reason a mount-verb-driven scenario stayed green all
  the way through this bug. Only a repaint the DOM shows can tell the two
  apart.

  The `-dom-cljs-test` suffix enrols the file in the `:browser-test` build;
  `:node-test`'s broader `cljs-test$` regex matches it too, where each body
  says so rather than passing quietly."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.live-frame :as live-frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

;; The stock REAGENT adapter is the host under test — the FOREIGN adapter a
;; mixed application seats while `docs/core/freehand/adoption.md` tells it to
;; install Freehand next to what it already ships.
(def ^:private runtime-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter       reagent-adapter/adapter
     :ambient-frame nil
     :async?        true}))

(use-fixtures :each
  {:before (fn []
             (root/reset-registry!)
             (fr/reset-boundaries!)
             ((:before runtime-fixture)))
   :after  (fn []
             ((:after runtime-fixture))
             (root/reset-registry!)
             (fr/reset-boundaries!))})

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(def ^:private fid :freehand-ratom/frame)

;; ---------------------------------------------------------------------------
;; the views — one compiled reader, its interpreted twin, one event site
;; ---------------------------------------------------------------------------

(v/defview readout
  "The COMPILED arm. Its `v/sub` read routes through `cell/observe-site!`,
  so the mounted occurrence repaints only if the committed observation is
  actually notified."
  {:compiled true}
  [_]
  [:output#compiled (str (v/sub [:fh/count]))])

(v/defview readout-interpreted
  "The INTERPRETED twin, same body without the marker. It reads through the
  same port, so it is a control for the LOWERING and not for the bug: under
  the defect BOTH arms froze."
  [_]
  [:output#interpreted (str (v/sub [:fh/count]))])

(v/defview bump-button
  "A committed `:on-click` site — the operator pressed a real button, and a
  real press is what this drives too."
  {:compiled true}
  [_]
  [:button#bump {:on-click [:fh/bump]} "+"])

(v/defview page
  "Both readers and the button under ONE Freehand root, so the repaint claim
  is made about one commit discipline at one moment."
  [_]
  [:div#page [readout {}] [readout-interpreted {}] [bump-button {}]])

;; ---------------------------------------------------------------------------
;; harness
;; ---------------------------------------------------------------------------

(defn- register! []
  (rf/reg-sub :fh/count (fn [db _] (:count db)))
  (rf/reg-event :fh/bump (fn [{:keys [db]} _] {:db (update db :count inc)})))

(defn- seed! [db]
  (live-frame/make-frame {:id fid})
  (frame/replace-app-db! fid db)
  fid)

(defn- act
  "A React 19 `act` boundary as a promise, so the mount's commit has landed
  before anything is read back off `document`."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- live!
  "Leave the act environment. The repaint under test is driven by a
  DEPENDENCY rather than by a re-render call, and inside act React diverts
  that work to the act queue — which would make the assertion a claim about
  act's drain order instead of about the substrate."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  nil)

(defn- tick! []
  (js/Promise. (fn [resolve] (js/setTimeout #(resolve nil) 0))))

(defn- settle!
  "Close the cells' pending window and let the browser paint what that
  notification scheduled."
  []
  (cell/flush!)
  (tick!))

(defn- host-node! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    container))

(defn- unmount! [container mounted]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
  (when (some? mounted)
    (.unmount (.-react-root ^root/Root mounted)))
  (.remove container)
  nil)

(defn- text [container selector]
  (some-> (.querySelector container selector) .-textContent))

(defn- click! [container selector]
  (.click (.querySelector container selector)))

;; ===========================================================================
;; the bead's reproduction, as a gate
;; ===========================================================================

(deftest a-freehand-cell-repaints-under-the-reagent-adapter
  (testing "rf2-jt8vz's exact arm (a): one page, one build, the REAGENT
            adapter installed. A dispatch that moves the subscription must
            repaint the mounted occurrence — the outcome that did not survive
            before, when the readout stayed at its first render forever"
    (if-not (browser?)
      (skip! "the browser job runs the repaint assertions")
      (async done
        (register!)
        (seed! {:count 0})
        (let [container (host-node!)
              mounted   (atom nil)]
          (->
           (act #(v/mount [page {}] container {:frame fid}))
           (.then
            (fn [m]
              (reset! mounted m)
              (is (= "0" (text container "#compiled"))
                  "the first render observed the seeded value")
              (is (= "0" (text container "#interpreted")))
              (live!)
              (rf/dispatch-sync [:fh/bump] {:frame fid})
              (is (= 1 (:count (frame/frame-app-db-value fid)))
                  "precondition — the dispatch LANDS. It always did; the bug
                   was never that app-db failed to move")
              (is (pos? (cell/pending-count))
                  "THE MEASUREMENT THAT WAS ZERO: the observation reached the
                   cell and marked it dirty. A zero here is the bug itself —
                   no notification raised, nothing for any flush to drain")
              (settle!)))
           (.then
            (fn [_]
              (is (= "1" (text container "#compiled"))
                  "the COMPILED Freehand arm repainted the mounted occurrence
                   under a foreign ratom adapter")
              (is (= "1" (text container "#interpreted"))
                  "and so did its interpreted twin — both ride the same port")
              ;; A second movement: the channel must stay armed rather than
              ;; fire once and lapse.
              (rf/dispatch-sync [:fh/bump] {:frame fid})
              (settle!)))
           (.then
            (fn [_]
              (is (= "2" (text container "#compiled"))
                  "0 → 1 → 2, the sequence the Freehand-hosted arm produced
                   and the Reagent-hosted one could not")))
           ;; Reports and releases; it never finishes (rf2-fyba). The unmount both
           ;; arms used to duplicate now rides the single trailing step, so it is
           ;; written once, still runs on both paths, and `done` is last.
           (.catch
            (fn [e]
              (is false (str "freehand-under-reagent repaint rejected: " e))
              nil))
           (.then (fn [_] (unmount! container @mounted) (done)))))))))

(deftest a-real-press-repaints-a-freehand-cell-under-the-reagent-adapter
  (testing "the operator pressed a button, so drive a real DOM press: the
            committed `:on-click` dispatches into the frame the commit bound,
            and the sibling reader repaints off the same movement"
    (if-not (browser?)
      (skip! "the browser job runs the press assertions")
      (async done
        (register!)
        (seed! {:count 41})
        (let [container (host-node!)
              mounted   (atom nil)]
          (->
           (act #(v/mount [page {}] container {:frame fid}))
           (.then
            (fn [m]
              (reset! mounted m)
              (is (= "41" (text container "#compiled")))
              (live!)
              (click! container "#bump")
              (settle!)))
           (.then
            (fn [_]
              (is (= 42 (:count (frame/frame-app-db-value fid)))
                  "the press dispatched into the committed frame")
              (is (= "42" (text container "#compiled"))
                  "and the mounted Freehand occurrence repainted from it")))
           (.catch
            (fn [e]
              (is false (str "freehand-under-reagent press rejected: " e))
              nil))
           (.then (fn [_] (unmount! container @mounted) (done)))))))))

(deftest an-unmoved-write-repaints-no-freehand-cell-under-the-reagent-adapter
  (testing "the adversarial companion. Activating the node must not make the
            channel chatty: a write that leaves the subscribed value where it
            was must mark NO cell dirty and repaint nothing. Without this arm
            a repaint-on-everything regression would read as a pass above"
    (if-not (browser?)
      (skip! "the browser job runs the no-movement assertions")
      (async done
        (register!)
        (rf/reg-event :fh/set-unrelated
                      (fn [{:keys [db]} [_ v]] {:db (assoc db :unrelated v)}))
        (rf/reg-event :fh/set-count
                      (fn [{:keys [db]} [_ v]] {:db (assoc db :count v)}))
        (seed! {:count 5})
        (let [container (host-node!)
              mounted   (atom nil)]
          (->
           (act #(v/mount [page {}] container {:frame fid}))
           (.then
            (fn [m]
              (reset! mounted m)
              (is (= "5" (text container "#compiled")))
              (live!)
              (rf/dispatch-sync [:fh/set-count 5] {:frame fid})
              (is (zero? (cell/pending-count))
                  "an equal re-write moved nothing, so no cell was dirtied")
              (rf/dispatch-sync [:fh/set-unrelated :noise] {:frame fid})
              (is (zero? (cell/pending-count))
                  "…and neither did a write to a key this sub never reads")
              (settle!)))
           (.then
            (fn [_]
              (is (= "5" (text container "#compiled"))
                  "the DOM is untouched by either write")
              ;; Positive control — the silences above are silences, not a
              ;; dead channel that would make this whole test vacuous.
              (rf/dispatch-sync [:fh/set-count 6] {:frame fid})
              (is (pos? (cell/pending-count))
                  "a REAL movement still dirties the cell")
              (settle!)))
           (.then
            (fn [_]
              (is (= "6" (text container "#compiled"))
                  "…and still repaints")))
           (.catch
            (fn [e]
              (is false (str "freehand-under-reagent no-movement arm rejected: " e))
              nil))
           (.then (fn [_] (unmount! container @mounted) (done)))))))))
