(ns re-frame.bench.hicasso.arm1.presence-intent-dom-cljs-test
  "AN INTENT ON A PRESENCE CHILD (rf2-2rtt6.66).

  `arm1/presence_dom_cljs_test` proves the retention machine through a
  real DOM: the exit attributes land, re-entry takes them off, the node
  leaves on the timeout. Every child in that file is **callback-free**,
  and that was not a stylistic choice — it was the shape of a defect.

  A presence child is hiccup **data**, written in the parent boundary's
  body; it is **lowered inside the presence component's own React
  render**, one render later, after the parent body's dynamic extent has
  unwound. With no ambient frame re-bound there, `intent/*dispatch*` was
  nil at the moment the codec walked those props, so

      [:div.toast {:key id :on-click [:toasts/dismiss id]}]

  raised `:rf.error/hicasso-intent-outside-boundary` at render — and an
  `h/fn` at an event position raised the same id at invocation. Loud,
  never silent, and never *writable*: the inline tray with no child view
  is the whole of what HD-025 sells, and its dismiss button could not be
  written. This file is that button, clicked.

  Six claims, and the third and fourth are the ones that matter:

  1. a **bare intent vector** on a native presence child dispatches;
  2. an **`h/fn` at an event position** on one dispatches what it returns;
  3. both of them still dispatch **while the child is being retained** —
     the `::h/unmounting` window, where the child is gone from app-db,
     still on screen, still clickable, and re-lowered by presence on
     every one of its own renders;
  4. the intent lands in the frame the **tray** was mounted under, proved
     against a second live frame on the same page rather than against an
     absence;
  5. a tray with no frame above it is still legal until a child writes an
     intent, and that intent is still the loud error, **named**;
  6. presence costs three hooks and `runtime/shell` still costs two —
     counted at React's own dispatcher, so the budget claim is a reading.

  ## The host child, and why it is not witnessed here

  The bead names native and host children alike. The `defhost` door is
  HD-011's own surface and does not exist on `main` yet (rf2-2rtt6.65),
  so there is nothing here to mount one with. The repair does not
  distinguish them and cannot: **both kinds cross the single
  `codec/as-element` call** `presence-body` now wraps, so a host child's
  declared `:callbacks` entry is lowered inside the same binding as a
  native `:on-click`. The host-hatch suite is where that witness belongs,
  and this bead is what un-fences its presence-tray children.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` every DOM claim degrades to a stated
  skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.boundary :refer [boundary]]
            [re-frame.bench.hicasso.arm1.hook-probe :as probe]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.presence :refer [presence]]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview hfn]]))

(def ^:private frame-id ::arm1-presence-intent)
(def ^:private other-frame-id ::arm1-presence-intent-other)
(def ^:private timeout-ms 400)

;; Registered ABOVE `use-fixtures`, deliberately — the reset fixture captures
;; its source-store baseline when the `use-fixtures` form is EVALUATED and
;; restores it before every test, so a `reg-sub` written below it is erased
;; before the first row runs.

(rf/reg-sub :presence-intent/visible (fn [db _] (:toasts db)))

(rf/reg-event :presence-intent/set
              (fn [{:keys [db]} [_ ts]] {:db (assoc db :toasts ts)}))

(rf/reg-event :presence-intent/dismissed
              (fn [{:keys [db]} [_ id]]
                {:db (update db :dismissed (fnil conj []) id)}))

(rf/reg-event :presence-intent/noted
              (fn [{:keys [db]} [_ id]]
                {:db (update db :noted (fnil conj []) id)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     ;; `:ambient-frame nil` is load-bearing. The fixture's default leaves a
     ;; dynamic-var frame stamp in scope, and a tray that resolved the frame
     ;; through that tier instead of through React context would look correct
     ;; here for the wrong reason. With no ambient stamp, the only thing that
     ;; can name a frame is the provider above the tray.
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rt/reset-runtime!))}))

(defn- skip! [why]
  (is true (str "an intent-on-a-presence-child claim needs a real React DOM — " why)))

(defn- fresh! [frame-kw ts]
  (lane/leave-act-environment!)
  (rf/make-frame {:id frame-kw})
  (rf/with-frame frame-kw (rf/dispatch-sync [:presence-intent/set ts]))
  frame-kw)

(defn- db [frame-kw] (rf/app-db-value frame-kw))

;; ---------------------------------------------------------------------------
;; The screen — the tray HD-025 sells, with the button it could not carry
;; ---------------------------------------------------------------------------

(defview toast-tray
  "The inline tray, with **no child view**, and now with controls on the
  toast. `:on-click` carries a bare intent vector on one button and the
  one callback form on the other, so both rows of the position table are
  written at the same position on the same child."
  [_]
  [presence {:timeout-ms timeout-ms}
   (for [t (rt/sub [:presence-intent/visible])]
     [:div.toast {:key (:id t)
                  :data-id (:id t)
                  :re-frame.hicasso/unmounting {:class "toast--exit"}}
      (:message t)
      [:button.dismiss {:data-id  (:id t)
                        :on-click [:presence-intent/dismissed (:id t)]}
       "dismiss"]
      [:button.note {:data-id  (:id t)
                     :on-click (hfn [e]
                                 ;; A live event read, and ONE intent
                                 ;; returned — the event contract the
                                 ;; position imposes.
                                 (when (= "note" (.. e -target -dataset -role))
                                   [:presence-intent/noted (:id t)]))
                     :data-role "note"}
       "note"]])])

(defview toast-card
  "A BOUNDARY child of presence. It resolves its own frame in its own
  shell and receives `:rf/phase` as an ordinary prop, so it is the
  control: presence's new binding must leave this path exactly as it was."
  [{:keys [id]}]
  [:div.card {:data-id id}
   [:button.card-dismiss {:on-click [:presence-intent/dismissed id]} "dismiss"]])

(defview card-tray
  [_]
  [presence {:timeout-ms timeout-ms}
   (for [t (rt/sub [:presence-intent/visible])]
     [toast-card {:key (:id t) :id (:id t)}])])

(def ^:private two [{:id 1 :message "Saved"} {:id 2 :message "Copied"}])
(def ^:private one [{:id 1 :message "Saved"}])

(defn- query [handle sel] (.querySelector (:container handle) sel))

(defn- button [handle cls id]
  (.querySelector (:container handle)
                  (str ".toast[data-id=\"" id "\"] " cls)))

(defn- click! [node]
  (.click node)
  (mount/settle!))

;; ---------------------------------------------------------------------------
;; 1 — a bare intent vector on a native presence child
;; ---------------------------------------------------------------------------

(deftest a-native-presence-child-dispatches-its-inline-intent
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh! frame-id two)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [toast-tray {}])]
        (try
          (is (some? (query handle ".toast"))
              "the tray rendered at all — before this repair the intent on the
               child raised :rf.error/hicasso-intent-outside-boundary during
               presence's own render and there was no tray to click")
          (is (nil? (:dismissed (db frame-id))))
          (click! (button handle ".dismiss" 2))
          (is (= [2] (:dismissed (db frame-id)))
              "the inline dismiss button dispatched, which is the whole of what
               the inline tray was sold on")
          (click! (button handle ".dismiss" 1))
          (is (= [2 1] (:dismissed (db frame-id)))
              "and again, so this is a live handler and not a one-shot")
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 2 — the one callback form at a presence child's event position
;; ---------------------------------------------------------------------------

(deftest a-callback-at-a-presence-childs-event-position-dispatches-what-it-returned
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh! frame-id two)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [toast-tray {}])]
        (try
          (click! (button handle ".note" 1))
          (is (= [1] (:noted (db frame-id)))
              "the h/fn read the real event and the vector it RETURNED drained
               through the arm's synchronous door — the second row of the
               position table, at a position presence lowered")
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 3 — the unmounting window: retained, on screen, and still clickable
;; ---------------------------------------------------------------------------

(deftest a-retained-child-dispatches-during-the-unmounting-window
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh! frame-id two)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [toast-tray {}])]
        (try
          (mount/dispatch! handle [:presence-intent/set one])
          (is (= 2 (.-length (.querySelectorAll (:container handle) ".toast")))
              "toast 2 is gone from app-db and RETAINED on screen")
          (is (.contains (.-classList (query handle ".toast[data-id=\"2\"]"))
                         "toast--exit")
              "and it is in the unmounting phase, not merely still present")
          (testing "the retained child's controls still reach the frame. This is
                    the sharpest case: the hiccup being lowered is the LAST one
                    the parent produced for that key, re-lowered by presence on
                    every one of its own renders, with the parent body long
                    returned"
            (click! (button handle ".dismiss" 2))
            (is (= [2] (:dismissed (db frame-id))))
            (click! (button handle ".note" 2))
            (is (= [2] (:noted (db frame-id)))
                "both intent shapes, from the retained phase"))
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 4 — the frame is the TRAY's, proved against a second live frame
;; ---------------------------------------------------------------------------

(deftest a-presence-child-lands-in-the-frame-the-tray-was-mounted-under
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh! frame-id two)
      (fresh! other-frame-id two)
      (let [a (mount/root! (mount/fresh-container!) frame-id [toast-tray {}])
            b (mount/root! (mount/fresh-container!) other-frame-id [toast-tray {}])]
        (try
          (click! (button a ".dismiss" 1))
          (is (= [1] (:dismissed (db frame-id))))
          (is (nil? (:dismissed (db other-frame-id)))
              "the second frame's app-db did not move — a frame is an isolated
               context, and a tray that dispatched to whatever was ambient
               rather than to its own provider would have moved both")
          (click! (button b ".dismiss" 2))
          (is (= [2] (:dismissed (db other-frame-id))))
          (is (= [1] (:dismissed (db frame-id)))
              "and symmetrically the other way")
          (finally (mount/release! a) (mount/release! b)))))))

(deftest a-boundary-child-of-presence-still-lowers-in-its-own-render
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh! frame-id two)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [card-tray {}])]
        (try
          (click! (.querySelector (:container handle)
                                  ".card[data-id=\"2\"] .card-dismiss"))
          (is (= [2] (:dismissed (db frame-id)))
              "a boundary child resolves its own frame in its own shell, and
               presence's binding around the crossing left that path alone")
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 5 — a frameless tray is legal; an intent under one is still the loud error
;; ---------------------------------------------------------------------------

(def ^:private !caught (atom nil))

(defn- frameless-root!
  "A root whose provider carries the **no-provider sentinel** — which is
  the React-context default, so this is exactly a tray with no frame
  boundary above it, reached without a second door in `mount`."
  [hiccup]
  (mount/root! (mount/fresh-container!) adapter-context/no-provider-sentinel hiccup))

(deftest a-frameless-tray-is-legal-until-a-child-writes-an-intent
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (lane/leave-act-environment!)
      (testing "a tray with no frame above it and no intent below it renders.
                Presence reads no subscription, so requiring a frame it does not
                need would refuse a legal page"
        (let [handle (frameless-root!
                       [presence {:timeout-ms timeout-ms}
                        [:div.toast {:key 1 :data-id 1} "quiet"]])]
          (try
            (is (some? (query handle ".toast")))
            (finally (mount/release! handle)))))
      (testing "and an intent under one is the loud error, NAMED — the
                diagnostic points at the intent rather than at the tray"
        (reset! !caught nil)
        (let [handle (frameless-root!
                       [boundary {:fallback [:p.oops "no frame"]
                                  :on-error (fn [e] (reset! !caught e))}
                        [presence {:timeout-ms timeout-ms}
                         [:div.toast {:key 1
                                      :on-click [:presence-intent/dismissed 1]}
                          "loud"]]])]
          (try
            (is (some? (query handle ".oops"))
                "the tray failed rather than rendering an inert handler")
            (is (= :rf.error/hicasso-intent-outside-boundary
                   (:rf.error/id (ex-data @!caught)))
                (str "and it named the intent: " (pr-str (ex-data @!caught))))
            (is (= [:presence-intent/dismissed 1] (:intent (ex-data @!caught))))
            (finally (mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; 6 — the hook budget, counted at React's own dispatcher
;; ---------------------------------------------------------------------------

(defn- unwitnessed! []
  (is false (str "React's internals slot was not found, so the hook counts are "
                 "UNWITNESSED on this build. A gate nobody has watched fire is "
                 "not evidence — fix "
                 (pr-str 're-frame.bench.hicasso.arm1.hook-probe)
                 " rather than reading this as a pass.")))

(deftest presence-costs-three-hooks-and-the-shell-still-costs-two
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (if-not (probe/install!)
      (unwitnessed!)
      (do
        (fresh! frame-id one)
        (let [handle (volatile! nil)
              names  (probe/record!
                       (fn []
                         (vreset! handle
                                  (mount/root! (mount/fresh-container!)
                                               frame-id [toast-tray {}]))))]
          (try
            (is (= ["useContext" "useSyncExternalStore"
                    "useContext" "useState" "useEffect"]
                   names)
                (str "the SHELL's two first, in the order rt/shell-hook-ledger "
                     "declares, then presence's own three: " (pr-str names)))
            (is (= (count rt/shell-hook-ledger) (count (take 2 names)))
                "the boundary shell gained nothing — HD-020(b)'s ≤2 budget is
                 the shell's, and presence is not a shell: it reads no
                 subscription, mounts no registration and takes no cell")
            (is (= 3 (count (drop 2 names)))
                "presence's cost is three: the frame hook this repair added,
                 plus the retention state and the clock")
            (finally (mount/release! @handle))))))))
