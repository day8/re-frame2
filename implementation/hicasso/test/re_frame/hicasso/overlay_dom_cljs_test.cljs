(ns re-frame.hicasso.overlay-dom-cljs-test
  "THE OVERLAY POSTURE, WITNESSED (rf2-hic-052).

  > The top layer, the focus trap, the inertness, the LIFO stack, the
  > light dismiss and the anchor tracking all belong to the browser. This
  > module owns exactly one thing about them, and that thing is the
  > imperative call.
  >
  > — [[re-frame.hicasso.overlay]]

  A posture is only worth stating if something would go red when it stops
  being true, and for an overlay the thing that stops being true is never
  the markup. A panel can carry every attribute the module means to write
  and still be clipped by its grandparent, trap focus in the wrong place,
  outlive its own listener, or stack under the overlay that opened before
  it. So every row here asserts a BEHAVIOUR the engine decides —
  `document.elementFromPoint`, `document.activeElement`, `:modal`,
  `:popover-open` — and none asserts an attribute the module just wrote.

  | claim | row |
  |---|---|
  | the top layer escapes an ancestor that clips and stacks | [[an-overlay-escapes-an-ancestor-that-clips-and-out-stacks-it]] |
  | a modal makes the rest of the document inert | [[a-modal-makes-the-document-behind-it-unfocusable]] |
  | closing through the platform's door returns focus | [[closing-through-the-platform-door-returns-focus-to-the-trigger]] |
  | the stack is LIFO, and not DOM order | [[the-top-layer-stacks-last-in-first-out-not-in-dom-order]] |
  | an auto popover joins the LIFO stack; a manual one does not | [[an-unrelated-auto-popover-dismisses-the-open-one-and-a-manual-one-is-immune]] |
  | the platform's dismissal arrives as an intent, once | [[the-platform-dismissal-arrives-as-an-intent-and-the-teardown-does-not]] |
  | zero idle listeners, and nothing per frame | [[an-open-overlay-adds-nothing-to-window-document-or-body]] |
  | zero cost when closed | [[a-closed-overlay-has-no-element-no-listener-and-no-rendered-body]] |
  | an intent inside an overlay lowers in the overlay's frame | [[an-intent-on-an-overlay-child-dispatches-in-the-frame-above-it]] |
  | the anchor is claimed while open and put back after | [[the-anchor-is-claimed-while-open-and-handed-back-on-teardown]] |
  | two hooks here, and still two in the shell | [[an-overlay-costs-two-hooks-and-the-shell-still-costs-two]] |
  | the focus one-shot, and which spelling reaches the platform | [[the-focus-one-shot-is-the-attribute-and-not-reacts-prop]] |

  ## What decides these rows, and what cannot

  **The browser lane decides them.** `<dialog>`, the popover API, the top
  layer, inertness and light dismiss have no implementation in the node
  lane at all, so every row below states a skip there rather than a false
  green — as every DOM claim in this package does.

  **Trusted input decides one thing these rows do not.** A real Escape
  keypress and a real outside click cannot be synthesised from inside the
  page: Chromium refuses an untrusted `KeyboardEvent` for a close request
  by design. Every dismissal witnessed here is therefore driven through
  the platform's own programmatic path — opening a second `popover=auto`,
  which makes the engine light-dismiss the first exactly as an outside
  click would, through the same code and the same event. Keyboard conduct
  under a real key press is `rf2-hic-049`'s, which this bead unblocks, and
  is named here so the gap is a stated one.

  ## Both directions, per assertion class

  Every row was run against a deliberate break, and the reds are quoted in
  the PR body. The two directions are not the same edit:

  - REMOVE the behaviour — `showModal` → `show` (a legal call on the same
    element that opens the same dialog and fires the same events, and
    which leaves the page behind it live) reds the top-layer, inertness
    and stacking rows; dropping `close()` from the ref cleanup reds the
    focus row; a `document` listener added while open reds the census row.
  - WIDEN the guard — deleting the `newState` filter in
    `dismissal-handler`, which leaves a handler with the correct SHAPE
    (it is the platform's own dismissal event, on the right element, with
    the author's own intent) and one wrong direction: `beforetoggle` also
    fires on the way IN, so an overlay dispatches `:on-dismiss` when it
    OPENS. That reds the dispatch-count rows with `(not (= 1 2))`.
    Widening `:open?` to default true reds the closed-cost row.

  A third sabotage is recorded here because it did NOT redden, and the
  module changed as a result. The popover's teardown fires the same
  `beforetoggle` a light dismiss fires, so the first draft carried a
  per-node closing mark to tell them apart; deleting the mark left every
  row green. React does not deliver an event from a fiber it is deleting,
  so the mark was unreachable code. It is gone, and
  [[the-platform-dismissal-arrives-as-an-intent-and-the-teardown-does-not]]
  is what holds the property in its place.

  ## What is measured against what

  A census row that counts something the module never touches passes
  vacuously, so each census asserts its own premise first: the listener
  census asserts that the instrument DID see React's element-scoped
  `cancel`/`toggle` listeners before it asserts that it saw nothing on
  `document`; the hook roster asserts the probe was installed before it
  reads a count."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.hook-probe :as probe]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.impl.overlay :as impl-overlay]
            [re-frame.hicasso.overlay :as overlay]
            [re-frame.test-support :as test-support]))

(def ^:private frame-id ::overlay)

;; Registered ABOVE `use-fixtures`, for the reason the motion suite gives:
;; the reset fixture captures its source-store baseline when the
;; `use-fixtures` form is evaluated.
(rf/reg-sub ::open? (fn [db [_ k]] (boolean (get-in db [:open k]))))
(rf/reg-sub ::dismissals (fn [db [_ k]] (get-in db [:dismissed k] 0)))
(rf/reg-event ::opened (fn [{:keys [db]} [_ k]] {:db (assoc-in db [:open k] true)}))
(rf/reg-event ::closed (fn [{:keys [db]} [_ k]] {:db (assoc-in db [:open k] false)}))
(rf/reg-event ::dismissed
  (fn [{:keys [db]} [_ k]]
    {:db (-> db
             (assoc-in [:open k] false)
             (update-in [:dismissed k] (fnil inc 0)))}))
;; Deliberately does NOT clear the flag: the row that counts dispatches
;; needs the overlay to survive its own dismissal so the teardown is a
;; separate, later event it can attribute.
(rf/reg-event ::noted (fn [{:keys [db]} [_ k]] {:db (update-in db [:dismissed k] (fnil inc 0))}))
(rf/reg-event ::clicked (fn [{:keys [db]} [_ k]] {:db (assoc-in db [:clicked k] true)}))
(rf/reg-sub ::body-read (fn [db _] (:body-read db)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The headless half — the one part of this module that is a value
;; ---------------------------------------------------------------------------

(deftest a-compass-word-is-a-position-area-and-an-unknown-one-passes-through
  (testing "the taught compass words all resolve, and to distinct areas"
    (is (= "block-end span-inline-end" (impl-overlay/position-area :bottom-start)))
    (is (= "block-end span-inline-start" (impl-overlay/position-area :bottom-end)))
    (is (= "block-start span-inline-end" (impl-overlay/position-area :top-start)))
    (is (= "inline-end" (impl-overlay/position-area :right)))
    (is (= (count impl-overlay/position-areas)
           (count (set (vals impl-overlay/position-areas))))
        "twelve words, twelve areas — a duplicate would be two names for one place"))

  (testing "no placement is no declaration, rather than a default one"
    (is (nil? (impl-overlay/position-area nil))))

  (testing "the escape hatch: a raw `position-area` string is emitted verbatim"
    (is (= "block-end span-inline-start"
           (impl-overlay/position-area "block-end span-inline-start"))))

  (testing "and a misspelt compass word is emitted verbatim too — invalid CSS
            the engine drops, rather than a silent substitution of some
            other placement"
    (is (= "botom-start" (impl-overlay/position-area :botom-start)))
    (is (not (contains? (set (vals impl-overlay/position-areas)) "botom-start")))))

;; ---------------------------------------------------------------------------
;; The DOM half
;; ---------------------------------------------------------------------------

(defn- skip!
  [why]
  (is true (str "a top-layer claim needs a real browser engine — " why)))

(defn- unwitnessed! [why]
  (is false (str why " A gate nobody has watched fire is not evidence.")))

(defn- fresh! []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id}))

(defn- go! [event]
  (rf/with-frame frame-id (rf/dispatch-sync event))
  (mount/settle!)
  nil)

(defn- db [] (rf/app-db-value frame-id))

(defn- at-centre
  "The element painting at the centre of `el`'s layout box — the engine's
  own answer to \"what is on top here\", and the only honest one."
  [el]
  (let [r (.getBoundingClientRect el)]
    (.elementFromPoint js/document
                       (+ (.-left r) (/ (.-width r) 2))
                       (+ (.-top r) (/ (.-height r) 2)))))

(defn- within?
  "Is `hit` `el` or inside it?"
  [el hit]
  (boolean (and el hit (or (identical? el hit) (.contains el hit)))))

(defn- $ [sel] (.querySelector js/document sel))

;; --- the clipping fixture --------------------------------------------------

(def ^:private clip-style
  "An ancestor that clips AND establishes a stacking/containing context —
  the three defeats a hand-rolled overlay meets at once."
  {:overflow  "hidden"
   :height    "20px"
   :position  "relative"
   :transform "translateZ(0)"
   :z-index   0})

(h/defview clipped-page [_]
  [:div
   [:button#outside {:type "button"} "Outside"]
   [:div#clip {:style clip-style}
    ;; The legal near-miss: an ordinary positioned element with the
    ;; largest z-index there is. It loses anyway, which is the point.
    [:div#ladder {:style {:position "fixed" :top "300px" :left "0px"
                          :width "120px" :height "120px" :z-index 2147483647
                          :background "rgb(255 0 0)"}}]
    [overlay/modal {:open?      (h/sub [::open? :m])
                    :on-dismiss [::dismissed :m]
                    :label      "Confirm deletion"}
     [:p "in the top layer"]
     [:button#inside {:type "button"} "Inside"]]]])

(deftest an-overlay-escapes-an-ancestor-that-clips-and-out-stacks-it
  (if-not (mount/browser?)
    (skip! ":node-test has no top layer, so nothing paints and nothing hit-tests")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [clipped-page {}])]
        (try
          (mount/settle!)
          (go! [::opened :m])
          (let [dialog ($ "dialog")
                ladder ($ "#ladder")]
            (testing "premise: both elements are inside the same clipping,
                      transformed, stacking ancestor"
              (is (some? dialog))
              (is (some? ladder))
              (is (.contains ($ "#clip") dialog))
              (is (.contains ($ "#clip") ladder)))

            (testing "THE NEAR-MISS: the highest z-index there is loses to
                      `overflow: hidden` on a grandparent"
              (is (not (within? ladder (at-centre ladder)))
                  "nothing paints where the ladder lays out — it is clipped away"))

            (testing "THE TOP LAYER: the overlay paints, and hit-tests, outside
                      the box that clipped its sibling"
              (is (within? dialog (at-centre dialog))
                  "the engine's own hit test lands in the dialog")
              (is (= 1 (.-length (.querySelectorAll js/document ":modal")))
                  "and the engine agrees it is IN the top layer, modally")
              (let [d (.getBoundingClientRect dialog)
                    c (.getBoundingClientRect ($ "#clip"))]
                (is (or (< (.-top d) (.-top c)) (> (.-bottom d) (.-bottom c)))
                    (str "its painted box is not contained by the 20px box that "
                         "clips it. dialog=[" (.-top d) "," (.-bottom d) "] "
                         "clip=[" (.-top c) "," (.-bottom c) "]")))))
          (finally (mount/release! handle)))))))

(deftest a-modal-makes-the-document-behind-it-unfocusable
  (if-not (mount/browser?)
    (skip! ":node-test has no inertness, because it has no modal dialog")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [clipped-page {}])]
        (try
          (mount/settle!)
          (let [outside ($ "#outside")]
            (testing "premise: with no modal open the same control takes focus —
                      so the claim below is about the modal and not about the
                      control"
              (.focus outside)
              (is (identical? outside (.-activeElement js/document))))

            (go! [::opened :m])

            (testing "INERTNESS: the engine refuses focus to everything outside
                      the dialog — a property of the platform's modality, not of
                      a key handler this module wrote"
              (.focus outside)
              (is (not (identical? outside (.-activeElement js/document)))
                  "the outside control cannot take focus while the modal is open")
              (is (.contains (or (.-parentNode ($ "dialog")) js/document.body)
                             (.-activeElement js/document))
                  "and focus is somewhere inside the dialog's tree"))

            (testing "and it comes back when the modal goes"
              (go! [::closed :m])
              (.focus outside)
              (is (identical? outside (.-activeElement js/document)))))
          (finally (mount/release! handle)))))))

;; --- focus restore ---------------------------------------------------------

(h/defview trigger-page [_]
  [:div
   [:button#trigger {:type "button" :on-click [::opened :m]} "Open"]
   [overlay/modal {:open?      (h/sub [::open? :m])
                   :on-dismiss [::dismissed :m]
                   :label      "Confirm"}
    [:button#first {:type "button"} "First"]
    [:button#chosen {:type "button" :auto-focus true} "Chosen"]]])

(deftest closing-through-the-platform-door-returns-focus-to-the-trigger
  (if-not (mount/browser?)
    (skip! ":node-test restores nothing, because nothing was ever focused")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [trigger-page {}])]
        (try
          (mount/settle!)
          (let [trigger ($ "#trigger")]
            (.focus trigger)
            (is (identical? trigger (.-activeElement js/document))
                "premise: the trigger holds focus at the instant the overlay opens")

            (go! [::opened :m])
            (is (.contains ($ "dialog") (.-activeElement js/document))
                "opening moves focus into the dialog")

            (testing "RESTORE: the module closes through the platform's door
                      while the node is still connected, so the platform's own
                      restoration runs. Removing the node instead restores
                      nothing and focus lands on <body>"
              (go! [::closed :m])
              (is (nil? ($ "dialog")) "the element is gone")
              (is (identical? trigger (.-activeElement js/document))
                  "and focus is back on the trigger, with no restore handler
                   anywhere in the application")))
          (finally (mount/release! handle)))))))

(deftest the-focus-one-shot-is-the-platforms-focus-delegate-and-not-reacts-autofocus
  (if-not (mount/browser?)
    (skip! ":node-test runs no dialog focusing steps")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [trigger-page {}])]
        (try
          (mount/settle!)
          (go! [::opened :m])
          (testing "THE ONE-SHOT, as the engine actually performs it: opening
                    runs the platform's own dialog-focusing steps, which take
                    the FOCUS DELEGATE — the first element carrying the
                    `autofocus` ATTRIBUTE, or, when there is none, the first
                    focusable control in tree order"
            (is (= "first" (.-id (.-activeElement js/document)))
                (str "focus is on the first focusable control. Active: "
                     (.-id (.-activeElement js/document))))
            (is (not (.hasAttribute ($ "#chosen") "autofocus"))
                (str "and `:auto-focus true` did NOT put the attribute in the "
                     "DOM: it camelCases to React's own `autoFocus` prop, which "
                     "React implements by calling .focus() during the commit — "
                     "child-first, one commit BEFORE this module's ref attaches "
                     "and calls showModal, at which point the dialog is still "
                     "display:none and nothing inside it is focusable. React "
                     "rejects the unhyphenated `:autofocus` outright, with "
                     "`Invalid DOM property autofocus. Did you mean autoFocus?`, "
                     "and emits no attribute either. So NEITHER spelling reaches "
                     "the platform, and the recipe is tree order.")))
          (finally (mount/release! handle)))))))

(deftest a-close-request-on-a-modal-arrives-as-an-intent
  (if-not (mount/browser?)
    (skip! ":node-test has no dialog to make a close request of")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [trigger-page {}])]
        (try
          (mount/settle!)
          (go! [::opened :m])
          (let [dialog ($ "dialog")]
            (if-not (fn? (.-requestClose dialog))
              (skip! (str "this engine has no HTMLDialogElement.requestClose, "
                          "which is the only close request reachable without "
                          "trusted input — a real Escape key is rf2-hic-049's"))
              (testing "THE ROUND TRIP: a close request takes the platform's
                        `cancel` path — the same one Escape and a `closedby`
                        backdrop click take — and arrives in app-db as the
                        author's own intent, which is what closes the overlay"
                (is (zero? (get-in (db) [:dismissed :m] 0)) "premise: nothing yet")
                (.requestClose dialog)
                (mount/settle!)
                (is (= 1 (get-in (db) [:dismissed :m]))
                    "the intent landed exactly once")
                (is (false? (get-in (db) [:open :m]))
                    "and the author's handler is what wrote the open flag false")
                (is (nil? ($ "dialog"))
                    "so the element left because app-db said so, and not
                     because the platform closed it behind app-db's back"))))

          (testing "and the modal needs no guard for its own teardown: a
                    programmatic `close()` fires `close` and never `cancel`, so
                    an application closing its own dialog reports no dismissal"
            (go! [::opened :m])
            (is (some? ($ "dialog")) "premise: open again, and genuinely open")
            (let [before (get-in (db) [:dismissed :m] 0)]
              (go! [::closed :m])
              (is (nil? ($ "dialog")))
              (is (= before (get-in (db) [:dismissed :m] 0))
                  "the count did not move")))
          (finally (mount/release! handle)))))))

;; --- the stack -------------------------------------------------------------

(h/defview two-modals-page [_]
  ;; Written SECOND-first in the DOM, so a row that passed on DOM order
  ;; would fail here.
  [:div
   [overlay/modal {:open? (h/sub [::open? :b]) :on-dismiss [::dismissed :b]
                   :label "B" :id "modal-b"}
    [:p "B"]]
   [overlay/modal {:open? (h/sub [::open? :a]) :on-dismiss [::dismissed :a]
                   :label "A" :id "modal-a"}
    [:p "A"]]])

(deftest the-top-layer-stacks-last-in-first-out-not-in-dom-order
  (if-not (mount/browser?)
    (skip! ":node-test has no top layer to stack in")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [two-modals-page {}])]
        (try
          (mount/settle!)
          (go! [::opened :a])
          (go! [::opened :b])
          (let [a ($ "#modal-a") b ($ "#modal-b")]
            (testing "premise: both are open, both are modal, and B is the one
                      written FIRST in the DOM"
              (is (= 2 (.-length (.querySelectorAll js/document ":modal"))))
              (is (= 4 (.compareDocumentPosition b a))
                  "DOCUMENT_POSITION_FOLLOWING — A comes after B in the DOM"))

            (testing "LIFO: the one opened LAST is on top, whatever the DOM says"
              (is (within? b (at-centre b))
                  "B opened second, so B paints over A where they overlap"))

            (testing "and closing it puts the earlier one back on top"
              (go! [::closed :b])
              (is (= 1 (.-length (.querySelectorAll js/document ":modal"))))
              (is (within? a (at-centre a))
                  "A is the top of the stack again — the stack popped rather
                   than collapsed")))
          (finally (mount/release! handle)))))))

;; --- the popover's LIFO dismissal, and the manual arm ----------------------

(h/defview two-popovers-page [_]
  [:div
   [:button#anchor-a {:type "button"} "A"]
   [overlay/popover {:open?      (h/sub [::open? :pa])
                     ;; NOTE: `::noted` does not clear the flag, so the
                     ;; element survives its own dismissal and the row below
                     ;; can attribute a SECOND dispatch to the teardown.
                     :on-dismiss [::noted :pa]
                     :anchor     "anchor-a"
                     :placement  :bottom-start
                     :id         "pop-a"}
    [:ul {:role "menu"} [:li "one"]]]
   [overlay/popover {:open?      (h/sub [::open? :pb])
                     :on-dismiss [::dismissed :pb]
                     :id         "pop-b"}
    [:p "B"]]
   ;; The same panel with NO `:on-dismiss` — `popover="manual"`, and
   ;; therefore not a member of the auto stack at all.
   [overlay/popover {:open? (h/sub [::open? :pm]) :id "pop-m"}
    [:p "M"]]])

(deftest an-unrelated-auto-popover-dismisses-the-open-one-and-a-manual-one-is-immune
  (if-not (mount/browser?)
    (skip! ":node-test has no popover API and no auto stack")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [two-popovers-page {}])]
        (try
          (mount/settle!)
          (go! [::opened :pa])
          (is (.matches ($ "#pop-a") ":popover-open")
              "premise: A is open and in the top layer")

          (testing "THE AUTO STACK: opening an unrelated auto popover light-
                    dismisses the open one — the same engine path an outside
                    click takes, and the reason the module does not own one"
            (go! [::opened :pb])
            (mount/settle!)
            (is (.matches ($ "#pop-b") ":popover-open"))
            (is (not (.matches ($ "#pop-a") ":popover-open"))
                "A was dismissed by the platform, not by this module")
            (is (= 1 (get-in (db) [:dismissed :pa]))
                "and the dismissal arrived in app-db as an ordinary intent"))

          (testing "MANUAL: the same panel written without `:on-dismiss` is in
                    the top layer and dismisses for nothing — which is what
                    makes a dismissal with nowhere to go unreachable rather
                    than merely discouraged"
            (go! [::opened :pm])
            (is (.matches ($ "#pop-m") ":popover-open"))
            (is (= "manual" (.getAttribute ($ "#pop-m") "popover")))
            ;; A's flag is still TRUE while its element is closed — `::noted`
            ;; left it that way on purpose — so it has to be cleared before it
            ;; can be set again, or the re-render never happens.
            (go! [::closed :pa])
            (go! [::opened :pa])
            (is (.matches ($ "#pop-a") ":popover-open"))
            (is (.matches ($ "#pop-m") ":popover-open")
                "the auto popover joining the stack did not disturb it"))
          (finally (mount/release! handle)))))))

(deftest the-platform-dismissal-arrives-as-an-intent-and-the-teardown-does-not
  (if-not (mount/browser?)
    (skip! ":node-test dismisses nothing, because nothing opened")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [two-popovers-page {}])]
        (try
          (mount/settle!)
          (go! [::opened :pa])
          (go! [::opened :pb])
          (is (= 1 (get-in (db) [:dismissed :pa]))
              "premise: the light dismiss dispatched exactly once")
          (is (true? (get-in (db) [:open :pa]))
              "premise: `::noted` deliberately left the flag TRUE, so the
               element is still mounted and its teardown is still ahead")

          (testing "tearing down an ALREADY-DISMISSED overlay adds nothing: its
                    element is closed, so the module's `hide!` has nothing to do
                    and fires no event at all"
            (go! [::closed :pa])
            (is (nil? ($ "#pop-a")) "the element is gone")
            (is (= 1 (get-in (db) [:dismissed :pa]))
                "still one — the teardown is not a second dismissal"))

          (testing "TEARDOWN OF A STILL-OPEN OVERLAY: the module hides it
                    through the platform's own `hidePopover`, which fires the
                    identical `beforetoggle` a light dismiss fires — and no
                    dismissal is reported, because React delivers no event from
                    a fiber it is deleting. This row is the whole of that claim:
                    a first draft defended it with a per-node closing mark, and
                    removing the mark reddened nothing, which is how the mark
                    was found to be unreachable. If React's deletion order ever
                    changes, THIS is what reds — and every close in the
                    application would otherwise dispatch `:on-dismiss` twice"
            (is (.matches ($ "#pop-b") ":popover-open")
                "premise: B is genuinely open, so its teardown really does hide it")
            (is (zero? (get-in (db) [:dismissed :pb] 0))
                "premise: B has never been dismissed")
            (go! [::closed :pb])
            (is (nil? ($ "#pop-b")) "the element is gone")
            (is (zero? (get-in (db) [:dismissed :pb] 0))
                "and no dismissal was reported for a close the application asked
                 for itself"))
          (finally (mount/release! handle)))))))

;; --- the census ------------------------------------------------------------

(def ^:private global-target?
  (fn [t]
    (or (identical? t js/window)
        (identical? t js/document)
        (identical? t (.-documentElement js/document))
        (identical? t (.-body js/document)))))

(defn- install-census!
  "Instrument every way an overlay could acquire idle cost, and answer
  `{:log :restore!}`.

  Listeners are split by TARGET rather than counted: React attaches
  `cancel` / `toggle` directly to the overlay element, which is both
  expected and self-cleaning, while a listener on `document` or `window`
  is the one that outlives its overlay. A census that counted both would
  have to be read against a baseline nobody can state."
  []
  (let [proto      (.-prototype js/EventTarget)
        orig-add   (.-addEventListener proto)
        orig-rm    (.-removeEventListener proto)
        orig-raf   (.-requestAnimationFrame js/globalThis)
        orig-int   (.-setInterval js/globalThis)
        observers  ["ResizeObserver" "MutationObserver" "IntersectionObserver"]
        originals  (into {} (map (fn [n] [n (unchecked-get js/globalThis n)])) observers)
        !log       (atom {:global [] :element [] :removed [] :rafs 0
                          :intervals 0 :observers 0})]
    (set! (.-addEventListener proto)
          (fn [& args]
            (this-as this
              (swap! !log update (if (global-target? this) :global :element)
                     conj (first args))
              (.apply orig-add this (to-array args)))))
    (set! (.-removeEventListener proto)
          (fn [& args]
            (this-as this
              (swap! !log update :removed conj (first args))
              (.apply orig-rm this (to-array args)))))
    (when orig-raf
      (set! (.-requestAnimationFrame js/globalThis)
            (fn [& args]
              (swap! !log update :rafs inc)
              (.apply orig-raf js/globalThis (to-array args)))))
    (when orig-int
      (set! (.-setInterval js/globalThis)
            (fn [& args]
              (swap! !log update :intervals inc)
              (.apply orig-int js/globalThis (to-array args)))))
    (doseq [n observers]
      (when-some [orig (get originals n)]
        (unchecked-set js/globalThis n
                       (fn [& args]
                         (swap! !log update :observers inc)
                         (js/Reflect.construct orig (to-array args))))))
    {:log      !log
     :restore! (fn []
                 (set! (.-addEventListener proto) orig-add)
                 (set! (.-removeEventListener proto) orig-rm)
                 (when orig-raf (set! (.-requestAnimationFrame js/globalThis) orig-raf))
                 (when orig-int (set! (.-setInterval js/globalThis) orig-int))
                 (doseq [n observers]
                   (when-some [orig (get originals n)]
                     (unchecked-set js/globalThis n orig)))
                 nil)}))

(deftest an-open-overlay-adds-nothing-to-window-document-or-body
  (if-not (mount/browser?)
    (skip! ":node-test attaches nothing because it mounts nothing")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [two-popovers-page {}])]
        (try
          (mount/settle!)
          ;; Instrumented AFTER the root exists, so React's own root-level
          ;; wiring is outside the window and the delta is the overlay's.
          (let [{:keys [log restore!]} (install-census!)]
            (try
              (go! [::opened :pa])
              (.scrollTo js/window 0 40)
              (.dispatchEvent js/window (js/Event. "resize"))
              (mount/settle!)
              (let [open-log @log]
                (testing "premise: the instrument sees listeners at all —
                          React's own `beforetoggle`/`toggle` on the element"
                  (is (seq (:element open-log))
                      (str "nothing was recorded, so a zero below would be "
                           "vacuous. Element listeners: " (pr-str (:element open-log)))))

                (testing "ZERO IDLE LISTENERS: nothing on window, document,
                          <html> or <body> — the document listener that outlives
                          its overlay is not a bug this module can have, because
                          it never attaches one"
                  (is (= [] (:global open-log))
                      (str "a global listener appeared: " (pr-str (:global open-log)))))

                (testing "and nothing per frame: anchor tracking is the
                          compositor's, so there is no frame callback, no
                          interval and no observer to attribute"
                  (is (zero? (:rafs open-log)))
                  (is (zero? (:intervals open-log)))
                  (is (zero? (:observers open-log)))))

              (let [node ($ "#pop-a")]
                (go! [::closed :pa])
                (let [closed-log @log]
                  (testing "TEARDOWN: the element leaves the document, and the
                            listeners leave with it. React does not call
                            `removeEventListener` for a discarded node and does
                            not need to — a disconnected node is unreachable
                            from any event path, which is exactly why an
                            ELEMENT-scoped listener cannot be idle and a
                            `document`-scoped one can"
                    (is (nil? ($ "#pop-a")))
                    (is (false? (.-isConnected node))
                        "the node this window's listeners were attached to is
                         no longer in the document")
                    (is (= [] (:global closed-log))
                        (str "and still nothing global, on the way out either: "
                             (pr-str (:global closed-log)))))))
              (finally (restore!))))
          (finally (mount/release! handle)))))))

;; --- zero cost when closed -------------------------------------------------

(h/defview closed-page [_]
  [:div
   [overlay/modal {:open? (h/sub [::open? :m]) :on-dismiss [::dismissed :m]}
    [:p (str "body read: " (h/sub [::body-read]))]]
   [overlay/popover {:open? (h/sub [::open? :p]) :on-dismiss [::dismissed :p]}
    [:p "p"]]])

(deftest a-closed-overlay-has-no-element-no-listener-and-no-rendered-body
  (if-not (mount/browser?)
    (skip! ":node-test cannot tell an absent element from an absent DOM")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [closed-page {}])]
        (try
          (mount/settle!)
          ;; Instrumented AFTER the root, deliberately: creating a React root
          ;; attaches React's own `selectionchange` listener to the document,
          ;; and a census that had to whitelist it would be a census with an
          ;; opinion about React's internals. What is measured here is the
          ;; DELTA a closed overlay costs on top of a page that already exists.
          (let [{:keys [log restore!]} (install-census!)]
            (try
              (go! [::closed :m])
              (go! [::closed :p])
              (testing "ZERO COST WHEN CLOSED: `:open?` false renders nil, so
                        there is no element to put in the top layer, nothing for
                        React to attach a `cancel`/`toggle` listener to, and no
                        body to read a subscription from"
                (is (nil? ($ "dialog")))
                (is (nil? (.querySelector js/document "[popover]")))
                (is (zero? (.-length (.querySelectorAll js/document ":modal"))))
                (is (= [] (:global @log)))
                (is (= [] (filterv #{"cancel" "close" "toggle" "beforetoggle"}
                                   (:element @log)))
                    (str "no overlay listener exists to be idle. Element: "
                         (pr-str (:element @log))))
                (is (zero? (:rafs @log)))
                (is (zero? (:observers @log))))

              (testing "premise: the same page DOES acquire all of that the
                        moment it opens — so the zeros above are a closed
                        overlay's, not an instrument that sees nothing"
                (go! [::opened :m])
                (is (some? ($ "dialog")))
                (is (seq (filterv #{"cancel" "close" "toggle" "beforetoggle"}
                                  (:element @log)))))
              (finally (restore!))))
          (finally (mount/release! handle)))))))

;; --- intents inside an overlay --------------------------------------------

(h/defview intent-page [_]
  [:div
   [overlay/modal {:open? (h/sub [::open? :m]) :on-dismiss [::dismissed :m]}
    [:button#act {:type "button" :on-click [::clicked :m]} "Act"]]])

(deftest an-intent-on-an-overlay-child-dispatches-in-the-frame-above-it
  (if-not (mount/browser?)
    (skip! ":node-test cannot click a node it does not have")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [intent-page {}])]
        (try
          (mount/settle!)
          (go! [::opened :m])
          (testing "an overlay's children are hiccup DATA lowered in the
                    overlay component's own render, one render after the body
                    that wrote them unwound. Without the frame re-bound there,
                    this button raises `:rf.error/hicasso-intent-outside-boundary`
                    at render rather than dispatching"
            (is (nil? (get-in (db) [:clicked :m])) "premise: nothing clicked yet")
            (.click ($ "#act"))
            (mount/settle!)
            (is (true? (get-in (db) [:clicked :m]))
                "the intent landed, in the frame the overlay was mounted under"))
          (finally (mount/release! handle)))))))

;; --- the anchor ------------------------------------------------------------

(h/defview anchored-page [_]
  [:div
   ;; An author-written `anchor-name` the module must hand back untouched.
   [:button#anchor-a {:type "button" :style {:anchor-name "--mine"}} "A"]
   [overlay/popover {:open?      (h/sub [::open? :pa])
                     :on-dismiss [::dismissed :pa]
                     :anchor     "anchor-a"
                     :placement  :bottom-start
                     :id         "pop-a"}
    [:p "menu"]]
   [overlay/popover {:open?      (h/sub [::open? :px])
                     :on-dismiss [::dismissed :px]
                     :anchor     "no-such-element"
                     :placement  :bottom-start
                     :id         "pop-x"}
    [:p "unanchored"]]
   ;; Room BELOW the trigger, so `scrollIntoView` can put it at the centre
   ;; of the viewport rather than at the bottom edge. A `[popover]` is
   ;; `position: fixed`, so its containing block is the viewport and a
   ;; `block-end` placement with no viewport left under the anchor is
   ;; clamped — which is the engine being right and the fixture being
   ;; wrong. This lane's page carries every earlier suite's container, so
   ;; without a spacer the trigger lands at the very end of the document.
   [:div {:style {:height "1200px"}}]])

(deftest the-anchor-is-claimed-while-open-and-handed-back-on-teardown
  (if-not (mount/browser?)
    (skip! ":node-test resolves no anchors, because it computes no style")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [anchored-page {}])]
        (try
          (mount/settle!)
          (let [trigger ($ "#anchor-a")]
            (is (= "--mine" (.. trigger -style -anchorName))
                "premise: the trigger carries the author's own anchor name")

            ;; A `[popover]` is `position: fixed`, so its containing block is
            ;; the VIEWPORT: a trigger scrolled far out of view leaves no
            ;; in-viewport region below it and the used position is clamped.
            ;; This lane's page carries every earlier suite's container, so the
            ;; trigger starts tens of thousands of pixels down.
            (.scrollIntoView trigger #js {"block" "center"})
            (go! [::opened :pa])
            (let [panel ($ "#pop-a")]
              (testing "ANCHORED: the panel sits against the trigger, and the
                        engine is what put it there"
                (let [t (.getBoundingClientRect trigger)
                      p (.getBoundingClientRect panel)
                      cs (js/getComputedStyle panel)]
                  (is (>= (.-top p) (- (.-bottom t) 1))
                      (str ":bottom-start puts the panel below the trigger. "
                           "trigger=[" (.-top t) "," (.-bottom t) "] "
                           "panel=[" (.-top p) "," (.-bottom p) "] "
                           "position=" (.-position cs)
                           " position-anchor=" (.getPropertyValue cs "position-anchor")
                           " position-area=" (.getPropertyValue cs "position-area")
                           " anchor-name(trigger)="
                           (.getPropertyValue (js/getComputedStyle trigger) "anchor-name")))
                  (is (< (js/Math.abs (- (.-left p) (.-left t))) 2)
                      (str "and left-aligned with it. trigger.left=" (.-left t)
                           " panel.left=" (.-left p)))))

              (testing "the claim is on the trigger while the panel is open"
                (is (not= "--mine" (.. trigger -style -anchorName)))))

            (testing "HANDED BACK: teardown restores what the author wrote,
                      rather than clearing the property"
              (go! [::closed :pa])
              (is (= "--mine" (.. trigger -style -anchorName)))))

          (testing "an `:anchor` naming no element is a no-op in this bead —
                    the panel opens in the top layer, visibly unanchored, and
                    raises nothing. The refusal the guide promises is reserved,
                    provisional, and needs a Spec 009 row: see the door"
            (go! [::opened :px])
            (is (some? ($ "#pop-x")))
            (is (.matches ($ "#pop-x") ":popover-open")))
          (finally (mount/release! handle)))))))

;; --- the budget ------------------------------------------------------------

(h/defview budget-page [_]
  [overlay/modal {:open? (h/sub [::open? :m]) :on-dismiss [::dismissed :m]}
   [:p "cost"]])

(deftest an-overlay-costs-two-hooks-and-the-shell-still-costs-two
  (if-not (mount/browser?)
    (skip! ":node-test does not run this suite's mount")
    (if-not (probe/install!)
      (unwitnessed! "React's internals slot was not found, so the hook counts
                     are UNWITNESSED on this build.")
      (do
        (fresh!)
        (let [handle (volatile! nil)
              names  (probe/record!
                       (fn []
                         (vreset! handle
                                  (mount/root! (mount/fresh-container!)
                                               frame-id [budget-page {}]))))]
          (try
            (testing "premise: the instrument can report more than two — the
                      shell's own two are read here, in the order its ledger
                      declares them"
              (is (= ["useContext" "useSyncExternalStore"] (vec (take 2 names)))
                  (str "raw: " (pr-str names)))
              (is (= (count collector/shell-hook-ledger) 2)
                  "and the declared shell ledger is still two"))

            (let [tail (vec (distinct (drop 2 names)))]
              (testing "THE MODULE'S OWN ROSTER: two names, and neither is a
                        subscription. The ≤2-hook ceiling I9 polices is the
                        boundary SHELL's, which this module does not touch —
                        it adds no hook to any boundary at all"
                (is (= ["useContext" "useRef"] tail)
                    (str "the frame hook and the instance cell. Raw tail: "
                         (pr-str (drop 2 names))))
                (is (empty? (filter #{"useSyncExternalStore" "useState" "useEffect"
                                      "useLayoutEffect" "useMemo" "useCallback"}
                                    tail))
                    "no effect, no state, no second store subscription — the
                     work happens in a ref callback, which is not a hook")))
            (finally (when @handle (mount/release! @handle)))))))))
