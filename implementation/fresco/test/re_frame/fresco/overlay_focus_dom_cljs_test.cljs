(ns re-frame.fresco.overlay-focus-dom-cljs-test
  "L4 — WHAT A KEYBOARD CAN REACH WHILE AN OVERLAY IS OPEN, over the
  overlay module.

  > | Accessibility | Semantic Hiccup and native controls | Structural
  > a11y assertions plus browser focus tests | Names, roles, keyboard,
  > **virtualized/overlay focus** |
  >
  > — product specification §7

  `overlay-dom-cljs-test` establishes the module's posture: the top
  layer, the LIFO stack, light dismiss, the imperative call, the
  listener census. This file owns inertness, and states it as *the page
  behind is unreachable* rather than as *one control refused focus*,
  because only the first is the thing a modal is bought for.

  ## The claims

  | claim | row |
  |---|---|
  | a REAL Tab and a REAL Shift+Tab cycle inside the trap, and a REAL Escape lets the page back out | [[a-real-tab-cycles-within-the-modal-and-a-real-escape-gives-the-page-back]] |
  | the wrap fires at the SEQUENTIAL edge, over a panel whose sequential order is shorter than its markup | [[a-real-tab-wraps-at-the-radio-groups-edge-and-not-at-the-dom-s]] |
  | …and over one whose sequential order is a permutation of it | [[a-real-tab-wraps-at-the-tabindex-ordered-edge]] |
  | …and over one whose LAST element is not a stop at all | [[a-real-tab-wraps-past-a-trailing-hidden-control]] |

  [[a-real-tab-leaves-an-open-popover]] is the other direction on the
  product itself. A popover is deliberately not modal — a filter menu
  that made the page it filters unreachable would be a defect, not a
  feature — so a suite that only ever asserted *an overlay makes
  everything else unfocusable* would be asserting something the module
  does not claim and must not do.

  ## What is measured

  [[reachable]] asks the ENGINE — blur, request focus, read
  `document.activeElement` back — because inertness is decided by the
  engine's own modality and by nothing this module writes. See
  `examples.ledger.keyboard-dom-cljs-test` on why asking is the only
  honest instrument.

  Asking is not quite tabbing, though, and this file's central claim is
  about what a keyboard user can REACH. [[reachable]] answers *which
  elements would take focus if asked*; a trap is *where Tab goes when it
  runs out of panel*.
  [[a-real-tab-cycles-within-the-modal-and-a-real-escape-gives-the-page-back]]
  presses Tab through the browser gate's trusted-input bridge
  (`re-frame.fresco.trusted-input-support`, whose other half is
  `scripts/run-browser-tests.cjs`) and finds it CYCLING — off the last
  panel control back onto the first, never onto `before`, `trigger` or
  `after` — which is the property a modal is actually bought for and the
  one an emptiness claim over `reachable` can only approximate. It
  presses BOTH directions, because a wrap is two edges. The same row then
  presses a real Escape and gets the page back.

  It also sees something no amount of asking would: left to itself the
  engine wraps through the DOCUMENT, and `document.activeElement` reads
  `<body>` for one press between the panel's last control and its first —
  focus resting nowhere, which in a browser with UI is the browser's own
  UI. Inertness is not in question; the WRAP is, and
  `impl.overlay/wrap-tab!` closes both edges. The row asserts the
  three-stop cycle, so it reddens the day that handler stops working — a
  cycle recording `<body>` as a waypoint is a trap that leaks.

  ## What that row cannot fail on, and the two below that can

  Its panel is three plain controls, and over three plain controls the
  ELEMENTS a selector finds and the STOPS Tab visits are the same list in
  the same order. A handler computing the first from document order is
  therefore indistinguishable, here, from one computing it correctly —
  and the difference shows on a panel this fixture cannot hold: a radio
  group, which is three elements and ONE stop, where Shift+Tab off the
  checked member goes to `<body>` if the handler takes the unchecked one
  for the panel's first stop.

  [[a-real-tab-wraps-at-the-radio-groups-edge-and-not-at-the-dom-s]] and
  [[a-real-tab-wraps-at-the-tabindex-ordered-edge]] are that missing
  population, one panel per way the two lists can differ. What
  `impl.overlay/sequential-tab-stops` does and does not model is stated
  on that function; these rows measure the part it claims.

  [[a-real-tab-wraps-past-a-trailing-hidden-control]] is the third way,
  and it is about the boundary itself rather than the model inside it.
  Those two rows differ from the DOM in ORDER; this panel differs in
  LENGTH, and only at the end — a `visibility:hidden` final button,
  which reports a client rect and refuses focus. A candidate like that
  costs only a wrap that does not fire — true and, at the LAST position,
  not free: the surplus becomes the edge, so the handler declines and
  `<body>` gets the focus. The row exists because *refuses focus* and
  *costs nothing* are different claims.

  `overlay-dom-cljs-test` holds the synthetic-versus-trusted comparison
  for Escape itself; this file does not re-litigate it.

  ## Browser lane

  `<dialog>`, `popover`, the top layer and inertness have no
  implementation in the node lane at all, so every row states a skip
  there rather than a false green. The trusted-input row is `async` —
  the press happens in another process — which is why the fixture below
  is the MAP shape (an async row under a POSITIONAL fixture aborts the
  whole run)."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.overlay :as rf.fresco.overlay]
            [re-frame.fresco.test.mounted :as rf.fresco.test.mounted]
            [re-frame.fresco.trusted-input-support :as rf.fresco.trusted-input-support]
            [re-frame.test-support :as rf.test-support]))

;; ---------------------------------------------------------------------------
;; The application — one flag, and the two overlays over the same panel
;; ---------------------------------------------------------------------------
;;
;; Registered ABOVE `use-fixtures`, for the reason the overlay and motion
;; suites give: the reset fixture snapshots the registrar when the
;; `use-fixtures` form is evaluated.

(rf/reg-sub ::open? (fn [db _] (boolean (:open? db))))
(rf/reg-event ::opened (fn [{:keys [db]} _] {:db (assoc db :open? true)}))
(rf/reg-event ::closed (fn [{:keys [db]} _] {:db (assoc db :open? false)}))
(rf/reg-event ::dismissed (fn [{:keys [db]} _] {:db (assoc db :open? false)}))
;; The claimed-Tab row's observable: the panel control's OWN handling of
;; the key it claimed. Counted rather than flagged, so a double delivery
;; would be visible too.
(rf/reg-event ::tab-claimed (fn [{:keys [db]} _] {:db (update db :tab-claims (fnil inc 0))}))

;; The two pages differ in the OVERLAY and in nothing else: the same
;; three panel controls, the same three page controls, the same flag.

(rf.fresco/defview a-page-with-a-modal [_]
  [:div
   [:button#before {:type "button"} "Before"]
   [:button#trigger {:type "button" :on-click [::opened]} "Open"]
   [rf.fresco.overlay/modal {:open?      (rf.fresco/sub [::open?])
                   :on-dismiss [::dismissed]
                   :label      "Confirm deletion"}
    [:button#confirm {:type "button"} "Delete"]
    [:input#reason {:type "text" :aria-label "Reason"}]
    [:button#cancel {:type "button" :on-click [::closed]} "Cancel"]]
   [:button#after {:type "button"} "After"]])

(rf.fresco/defview a-page-with-a-popover [_]
  [:div
   [:button#before {:type "button"} "Before"]
   [:button#trigger {:type "button" :on-click [::opened]} "Open"]
   [rf.fresco.overlay/popover {:open?      (rf.fresco/sub [::open?])
                     :on-dismiss [::dismissed]
                     :anchor     "trigger"
                     :placement  :bottom-start}
    [:button#confirm {:type "button"} "Delete"]
    [:input#reason {:type "text" :aria-label "Reason"}]
    [:button#cancel {:type "button" :on-click [::closed]} "Cancel"]]
   [:button#after {:type "button"} "After"]])

;; THE TWO SHAPES THE THREE-PLAIN-CONTROL PANEL ABOVE CANNOT CONTAIN, and
;; the reason they are separate pages rather than extra controls on that
;; one. Both are panels whose SEQUENTIAL focus order differs from their
;; DOCUMENT order, which is the difference a wrap computed from document
;; order gets wrong; folding a radio group into the fixture above would
;; change what every row over it reads.

(rf.fresco/defview a-page-with-a-modal-holding-a-radio-group [_]
  ;; A RADIO GROUP IS THREE ELEMENTS AND ONE TAB STOP. Document order in
  ;; this panel is [small large ok reason]; sequential order is
  ;; [large ok reason], because `small` is an unchecked member of a group
  ;; whose checked member is `large`. The group is FIRST on purpose — that
  ;; puts the discrepancy on the backward edge, where a handler reading
  ;; document order finds `small` and never `large`.
  [:div
   [:button#before {:type "button"} "Before"]
   [:button#trigger {:type "button" :on-click [::opened]} "Open"]
   [rf.fresco.overlay/modal {:open?      (rf.fresco/sub [::open?])
                   :on-dismiss [::dismissed]
                   :label      "Pick a size"}
    [:input#small {:type "radio" :name "size" :aria-label "Small"}]
    [:input#large {:type "radio" :name "size" :default-checked true
                   :aria-label "Large"}]
    [:button#ok {:type "button"} "OK"]
    [:input#reason {:type "text" :aria-label "Reason"}]]
   [:button#after {:type "button"} "After"]])

(rf.fresco/defview a-page-with-a-modal-ordered-by-tabindex [_]
  ;; A POSITIVE `tabindex` SORTS AHEAD OF EVERY ZERO. Document order is
  ;; [second first third]; sequential order is [first second third],
  ;; because 1 precedes 2 and both precede the implicit 0.
  [:div
   [:button#before {:type "button"} "Before"]
   [:button#trigger {:type "button" :on-click [::opened]} "Open"]
   [rf.fresco.overlay/modal {:open?      (rf.fresco/sub [::open?])
                   :on-dismiss [::dismissed]
                   :label      "Ordered by tabindex"}
    [:button#second {:type "button" :tab-index 2} "Second"]
    [:button#first {:type "button" :tab-index 1} "First"]
    [:button#third {:type "button"} "Third"]]
   [:button#after {:type "button"} "After"]])

(rf.fresco/defview a-page-with-a-modal-whose-last-control-is-hidden [_]
  ;; A TRAILING `visibility:hidden` CONTROL, which is ordinary modal
  ;; markup — a final button hidden by a condition rather than removed.
  ;; Document order is [reason ok cancel ghost]; sequential order is
  ;; [reason ok cancel], because `ghost` takes no focus.
  ;;
  ;; `ghost` is LAST on purpose, and that position is the whole row.
  ;; A surplus candidate anywhere else is free — the handler never looks
  ;; at it. At the END it is what `peek` returns, so it becomes both the
  ;; edge the forward wrap tries to MATCH and the control the backward
  ;; wrap tries to LAND on, and it can be neither.
  [:div
   [:button#before {:type "button"} "Before"]
   [:button#trigger {:type "button" :on-click [::opened]} "Open"]
   [rf.fresco.overlay/modal {:open?      (rf.fresco/sub [::open?])
                   :on-dismiss [::dismissed]
                   :label      "Confirm"}
    [:input#reason {:type "text" :aria-label "Reason"}]
    [:button#ok {:type "button"} "OK"]
    [:button#cancel {:type "button"} "Cancel"]
    [:button#ghost {:type "button" :style {:visibility "hidden"}} "Ghost"]]
   [:button#after {:type "button"} "After"]])

;; THE TWO THE PAGE ABOVE DOES NOT COVER, and they are separate pages for
;; the reason the two above it are: each puts its ghost LAST, and a panel
;; can only have one last element. They differ from the hidden one in the
;; only way that matters here — the platform still calls them VISIBLE, so
;; `checkVisibility` cannot reach them and the exclusion has to be asked
;; for another way.

(rf.fresco/defview a-page-with-a-modal-whose-last-control-is-inert [_]
  ;; A TRAILING `inert` REGION, which is ordinary modal markup: the step
  ;; of a wizard the user has not arrived at yet, painted but switched
  ;; off. Document order is [reason ok cancel ghost]; sequential order is
  ;; [reason ok cancel], because an inert subtree takes no focus.
  ;;
  ;; `ghost` is LAST for the reason the hidden page gives, and its
  ;; wrapper carries the attribute rather than the button itself so the
  ;; row measures INHERITED inertness — the shape a wizard actually has,
  ;; and the one a predicate reading only the element in hand misses.
  [:div
   [:button#before {:type "button"} "Before"]
   [:button#trigger {:type "button" :on-click [::opened]} "Open"]
   [rf.fresco.overlay/modal {:open?      (rf.fresco/sub [::open?])
                   :on-dismiss [::dismissed]
                   :label      "Confirm"}
    [:input#reason {:type "text" :aria-label "Reason"}]
    [:button#ok {:type "button"} "OK"]
    [:button#cancel {:type "button"} "Cancel"]
    [:div#later {:inert true}
     [:button#ghost {:type "button"} "Ghost"]]]
   [:button#after {:type "button"} "After"]])

(rf.fresco/defview a-page-with-a-modal-whose-last-control-is-in-a-disabled-fieldset [_]
  ;; A TRAILING `disabled` `<fieldset>`, equally ordinary: a form section
  ;; switched off until an earlier choice enables it. Document order is
  ;; [reason ok cancel ghost]; sequential order is [reason ok cancel].
  ;;
  ;; The fieldset also carries the case that says WHICH reading is
  ;; correct. `ghost.disabled` is FALSE — the IDL property reflects the
  ;; element's own attribute and the element has none — so a predicate
  ;; reading the property cannot see this at all, while the
  ;; `:disabled` PSEUDO-CLASS matches the effective state.
  [:div
   [:button#before {:type "button"} "Before"]
   [:button#trigger {:type "button" :on-click [::opened]} "Open"]
   [rf.fresco.overlay/modal {:open?      (rf.fresco/sub [::open?])
                   :on-dismiss [::dismissed]
                   :label      "Confirm"}
    [:input#reason {:type "text" :aria-label "Reason"}]
    [:button#ok {:type "button"} "OK"]
    [:button#cancel {:type "button"} "Cancel"]
    [:fieldset#later {:disabled true}
     ;; The FIRST `<legend>` of a disabled fieldset is NOT disabled, and
     ;; `legend-button` is here to hold that boundary: it is the one
     ;; control in this subtree the engine still focuses, so a predicate
     ;; that excluded the whole fieldset by walking ancestors would drop a
     ;; REAL stop and move the panel's last edge onto `cancel`'s
     ;; predecessor. It is written before `ghost` and is therefore not
     ;; the edge; the forward cycle names it, which is how the row
     ;; notices if it ever stops being a stop.
     [:legend [:button#legend-button {:type "button"} "Legend"]]
     [:button#ghost {:type "button"} "Ghost"]]]
   [:button#after {:type "button"} "After"]])

(rf.fresco/defview a-page-with-a-modal-whose-last-control-claims-tab [_]
  ;; A LAST CONTROL THAT HANDLES Tab ITSELF, which is ordinary panel
  ;; markup: a grid, a combobox or an embedded editor claims the key for
  ;; its own internal focus movement and says so with `preventDefault`.
  ;; The claim is written declaratively — the key-map form, whose lowered
  ;; handler prevents the default and dispatches — and the promise under
  ;; test is overlay.cljs's own: *the wrap yields to a press one of them
  ;; has already claimed with `preventDefault`*.
  ;;
  ;; The claimer is LAST on purpose. That puts the claimed press on the
  ;; edge the wrap fires at, so a wrap that failed to yield would not be
  ;; merely impolite: it would aim focus at `confirm` while the control's
  ;; own handling ran — one press moving focus two ways at once.
  [:div
   [:button#before {:type "button"} "Before"]
   [:button#trigger {:type "button" :on-click [::opened]} "Open"]
   [rf.fresco.overlay/modal {:open?      (rf.fresco/sub [::open?])
                   :on-dismiss [::dismissed]
                   :label      "Claimed Tab"}
    [:button#confirm {:type "button"} "Delete"]
    [:input#reason {:type "text" :aria-label "Reason"}]
    [:button#cancel {:type "button"
                     :on-key-down {"Tab" [::rf.fresco/prevent [::tab-claimed]]}}
     "Cancel"]]
   [:button#after {:type "button"} "After"]])

;; `:async? true` — the MAP shape — because the trusted-input row below
;; is `(async done …)`. `make-reset-runtime-fixture` returns the
;; POSITIONAL fn by default, and cljs.test throws a bare string that
;; unwinds the entire lane the moment an async body appears under one.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true}))

;; ---------------------------------------------------------------------------
;; The lane
;; ---------------------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? js/document)
       (some? (.-body js/document))
       (fn? (.-showModal (.-prototype js/HTMLDialogElement)))))

(defn- skip! [why]
  (is true (str "modality, inertness and the top layer are the engine's — " why)))

;; ---------------------------------------------------------------------------
;; The instrument
;; ---------------------------------------------------------------------------

(defn- active [] (.-activeElement js/document))

(defn- label-of [^js el]
  (cond
    (seq (.-id el))        (.-id el)
    (seq (.-className el)) (first (.split (.-className el) " "))
    :else                  (.toLowerCase (.-tagName el))))

(defn- focusable?
  "Does the ENGINE accept focus on `el`? Blur, ask, read
  `document.activeElement` back. Inertness is invisible in markup — the
  page behind a modal carries no attribute saying so — which is why
  this asks rather than reads."
  [^js el]
  (some-> (active) (.blur))
  (.focus el)
  (identical? el (active)))

(defn- reachable
  "Every element under `root` the engine will focus, in document order,
  as readable names.

  Scoped to a root rather than to the document because a mount's own
  container is what a page is here."
  [^js root]
  (let [order (->> (.querySelectorAll root "a[href],button,input,select,textarea,[tabindex]")
                   (array-seq)
                   (filterv focusable?)
                   (mapv label-of))]
    (some-> (active) (.blur))
    order))

(defn- open! [m] (rf.fresco.test.mounted/dispatch-and-settle! m [::opened]) m)

(defn- $ [m sel] (.querySelector (:container m) sel))

;; ---------------------------------------------------------------------------
;; The trap under a real keyboard
;; ---------------------------------------------------------------------------

(deftest a-real-tab-cycles-within-the-modal-and-a-real-escape-gives-the-page-back
  ;; A TRAP is a positive fact — that Tab off the end of the panel comes
  ;; back to its start rather than leaving — and no amount of asking
  ;; elements whether they are focusable can see it, because the thing
  ;; being measured is where the engine SENDS focus when nobody asked.
  ;;
  ;; Escape closes the row out, because a trap with no exit is a defect
  ;; rather than a feature, and the exit is the platform's alone.
  (if-not (browser?)
    (skip! ":node-test has no modality, and no engine to press a key at")
    (if-not (rf.fresco.trusted-input-support/bridge?)
      (rf.fresco.trusted-input-support/unwitnessed! "the trap under real sequential navigation")
      (async done
        (let [m      (rf.fresco.test.mounted/mount! [a-page-with-a-modal {}])
              finish (fn [] (rf.fresco.test.mounted/unmount! m) (done))]
          (try
            (open! m)
            (is (= "confirm" (label-of (active)))
                (str "premise: on the panel's first control. Active: "
                     (label-of (active))))

            ;; FIVE, so the sequence closes: three presses is a wrap, five
            ;; is a CYCLE with its start repeated, and only the second
            ;; rules out a one-off excursion that happened to come back.
            (rf.fresco.trusted-input-support/walk!
              "Tab" 5
              (fn [] (some-> (active) label-of))
              (fn [forward]
                (try
                  (is (= ["reason" "cancel" "confirm" "reason" "cancel"] forward)
                      (str "THE CYCLE, FORWARD. From the panel's first "
                           "control, Tab runs to its last and then lands "
                           "DIRECTLY on its first — never on `before`, "
                           "`trigger` or `after`, and never on `<body>`. "
                           "Three controls, three stops, and the fourth "
                           "press repeats the first, so this closes rather "
                           "than merely returning once.\n"
                           "  THE THIRD STOP IS THE WHOLE ROW. Left to "
                           "itself the engine wraps through the document's "
                           "end-of-scope step, and `document.activeElement` "
                           "reads `<body>` there for one press — focus "
                           "resting nowhere, which in a browser with UI is "
                           "the browser's own UI rather than the page. That "
                           "is what `impl.overlay/wrap-tab!` closes, and "
                           "this row is what reddens if it stops. Pressed: "
                           (pr-str forward)))

                  ;; Back to the first control, so the backward lap is
                  ;; measured from the edge Shift+Tab has to wrap AT rather
                  ;; than from wherever the forward lap finished.
                  (.focus ($ m "#confirm"))

                  (rf.fresco.trusted-input-support/walk!
                    "Shift+Tab" 5
                    (fn [] (some-> (active) label-of))
                    (fn [backward]
                      (try
                        (is (= ["cancel" "reason" "confirm" "cancel" "reason"] backward)
                            (str "THE CYCLE, BACKWARD, and it is a separate "
                                 "claim: a wrap is two edges and the engine "
                                 "parks focus on `<body>` at both of them. "
                                 "Shift+Tab off the panel's FIRST control "
                                 "lands directly on its last. Pressed: "
                                 (pr-str backward)))

                        (rf.fresco.trusted-input-support/press-once!
                          "Escape"
                          (fn []
                            (try
                              (rf.fresco.test.mounted/settle! m)
                              (testing "AND OUT: the platform's own dismissal,
                                        which is the only exit a trap has — and
                                        it still is, because the wrap answers
                                        Tab and nothing else. The panel goes,
                                        and the page behind is reachable
                                        again"
                                (is (nil? ($ m "dialog")))
                                (is (= ["before" "trigger" "after"]
                                       (reachable (:container m)))))
                              (finally (finish)))))
                        (catch :default e
                          (is false (str "the backward cycle assertion threw: "
                                         (.-message e)))
                          (finish)))))
                  (catch :default e
                    (is false (str "the cycle assertion threw: " (.-message e)))
                    (finish)))))
            (catch :default e
              (is false (str "the trap row threw: " (.-message e)))
              (finish))))))))

;; ---------------------------------------------------------------------------
;; The trap over a panel whose sequential order is not its document order
;; ---------------------------------------------------------------------------
;;
;; WHY THE ROW ABOVE CANNOT CATCH THIS. Its panel is three plain
;; controls, and over three plain controls *the elements a selector finds*
;; and *the stops Tab visits* are the same list in the same order. So a
;; handler that read the first and took it for the second would pass
;; that row perfectly, and choose the wrong edge on any panel where the
;; two lists differ.
;;
;; The two rows below are that population: one panel whose sequential
;; order is SHORTER than its document order (a radio group), one whose
;; sequential order is a PERMUTATION of it (a positive `tabindex`). Both
;; drive both edges with trusted input and assert the exact cycle, so a
;; landing on `<body>` — where a wrap that did not fire puts focus — reds
;; either one.

(defn- both-edges!
  "Focus `start`, walk `n` trusted Tabs, re-focus `start`, walk `n` trusted
  Shift+Tabs, and hand the two laps to `k`.

  Re-focusing between the laps rather than continuing from wherever the
  forward one finished, so the backward lap is measured FROM the edge
  Shift+Tab has to wrap at."
  [m start n k]
  (.focus ($ m start))
  (rf.fresco.trusted-input-support/walk!
    "Tab" n
    (fn [] (some-> (active) label-of))
    (fn [forward]
      (.focus ($ m start))
      (rf.fresco.trusted-input-support/walk!
        "Shift+Tab" n
        (fn [] (some-> (active) label-of))
        (fn [backward] (k forward backward))))))

(deftest a-real-tab-wraps-at-the-radio-groups-edge-and-not-at-the-dom-s
  (if-not (browser?)
    (skip! ":node-test has no modality, and no engine to press a key at")
    (if-not (rf.fresco.trusted-input-support/bridge?)
      (rf.fresco.trusted-input-support/unwitnessed! "the trap over a panel holding a radio group")
      (async done
        (let [m      (rf.fresco.test.mounted/mount! [a-page-with-a-modal-holding-a-radio-group {}])
              finish (fn [] (rf.fresco.test.mounted/unmount! m) (done))]
          (try
            (open! m)

            ;; FIVE presses over a three-stop cycle, for the reason the
            ;; three-control row gives: three is a wrap, five closes it.
            (both-edges!
              m "#large" 5
              (fn [forward backward]
                (try
                  (is (= ["ok" "reason" "large" "ok" "reason"] forward)
                      (str "THE CYCLE, FORWARD, OVER A PANEL WHOSE FIRST "
                           "STOP IS NOT ITS FIRST ELEMENT. The wrap off "
                           "`reason` lands on `large` — the group's CHECKED "
                           "member, which is where the engine's own "
                           "sequential order starts — and never on `small`, "
                           "which is an element of the panel but not a stop "
                           "of it. Against a handler reading document "
                           "order, the third landing would be `small`. "
                           "Pressed: " (pr-str forward)))
                  (is (= ["reason" "ok" "large" "reason" "ok"] backward)
                      (str "THE CYCLE, BACKWARD, the edge a document-order "
                           "handler breaks. `large` IS the panel's "
                           "first stop, so Shift+Tab off it must land "
                           "directly on `reason`; a handler that took the "
                           "panel's first ELEMENT for its first STOP would find "
                           "`small`, match nothing, let the default action "
                           "stand and put focus on `<body>`. Pressed: "
                           (pr-str backward)))
                  (finally (finish)))))
            (catch :default e
              (is false (str "the radio-group trap row threw: " (.-message e)))
              (finish))))))))

(deftest a-real-tab-wraps-at-the-tabindex-ordered-edge
  (if-not (browser?)
    (skip! ":node-test has no modality, and no engine to press a key at")
    (if-not (rf.fresco.trusted-input-support/bridge?)
      (rf.fresco.trusted-input-support/unwitnessed! "the trap over a panel ordered by positive tabindex")
      (async done
        (let [m      (rf.fresco.test.mounted/mount! [a-page-with-a-modal-ordered-by-tabindex {}])
              finish (fn [] (rf.fresco.test.mounted/unmount! m) (done))]
          (try
            (open! m)
            (both-edges!
              m "#first" 5
              (fn [forward backward]
                (try
                  (is (= ["second" "third" "first" "second" "third"] forward)
                      (str "THE CYCLE, FORWARD, OVER A PANEL WHOSE ORDER IS "
                           "A PERMUTATION OF ITS MARKUP. `first` carries "
                           "`tabindex=1` and is written SECOND; the wrap off "
                           "`third` must land on it rather than on the "
                           "panel's first element. Pressed: " (pr-str forward)))
                  (is (= ["third" "second" "first" "third" "second"] backward)
                      (str "THE CYCLE, BACKWARD. `first` is the panel's "
                           "first stop, so Shift+Tab off it lands directly "
                           "on `third` — the last, because a `tabindex=0` "
                           "sorts AFTER every positive one. Pressed: "
                           (pr-str backward)))
                  (finally (finish)))))
            (catch :default e
              (is false (str "the tabindex trap row threw: " (.-message e)))
              (finish))))))))

;; ---------------------------------------------------------------------------
;; The trap over a panel whose LAST element is not a stop
;; ---------------------------------------------------------------------------
;;
;; THE THIRD WAY THE TWO LISTS DIFFER. The two rows above are about a
;; candidate set in the wrong ORDER. This one is about a candidate set of
;; the wrong LENGTH at the one end where length is load-bearing.
;;
;; A candidate the engine skips refuses `.focus()`, and `wrap-tab!`
;; confirms the landing before taking the default action away, so a
;; surplus candidate costs a wrap that DOES NOT FIRE rather than one that
;; fires at the wrong place. That argument is sound — but *refuses focus*
;; and *costs nothing* are different claims.
;;
;; A missed wrap is not free at the LAST candidate. There the surplus is
;; what `peek` returns, so Tab off the real final control matches no
;; edge at all, the handler declines, and the engine's own end-of-scope
;; step puts `document.activeElement` on `<body>` — the exact leak this
;; handler exists to close, reached by a fourth route. This row pins it
;; with a conditionally-hidden final button, which `sequential-tab-stops`
;; excludes; the two rows after it pin the cases the platform still
;; calls visible.

(deftest a-real-tab-wraps-past-a-trailing-hidden-control
  (if-not (browser?)
    (skip! ":node-test has no modality, and no engine to press a key at")
    (if-not (rf.fresco.trusted-input-support/bridge?)
      (rf.fresco.trusted-input-support/unwitnessed! "the trap over a panel whose last element is hidden")
      (async done
        (let [m      (rf.fresco.test.mounted/mount! [a-page-with-a-modal-whose-last-control-is-hidden {}])
              finish (fn [] (rf.fresco.test.mounted/unmount! m) (done))]
          (try
            (open! m)
            ;; THE PREMISE THAT MAKES THIS PANEL THE CASE IT IS: a
            ;; rects-based reading would count `ghost`, while a keyboard
            ;; never reaches it.
            (is (pos? (.-length (.getClientRects ($ m "#ghost"))))
                "premise: `visibility:hidden` still occupies layout, so
                 the hidden button DOES report a client rect — this is
                 not `display:none`, and a predicate reading rects has
                 no way to tell it from a real control")

            (both-edges!
              m "#reason" 5
              (fn [forward backward]
                (try
                  (is (= ["ok" "cancel" "reason" "ok" "cancel"] forward)
                      (str "THE CYCLE, FORWARD, OVER A PANEL WHOSE LAST "
                           "ELEMENT IS NOT ITS LAST STOP. `cancel` is the "
                           "final stop, so Tab off it must land on "
                           "`reason`. Against a predicate that counted "
                           "`ghost`, the panel's edge would be an element the "
                           "press can never arrive at: the handler would "
                           "match nothing, let the default action stand, "
                           "and the third landing would be `body`. Pressed: "
                           (pr-str forward)))
                  (is (= ["cancel" "ok" "reason" "cancel" "ok"] backward)
                      (str "THE CYCLE, BACKWARD, which a counted `ghost` breaks "
                           "the other way for the same reason. Shift+Tab off "
                           "`reason` must land on `cancel`; with `ghost` "
                           "counted, the wrap AIMS at `ghost`, `.focus()` "
                           "declines, `preventDefault` is correctly "
                           "withheld — and withholding it is what lets the "
                           "engine put focus on `body`. Pressed: "
                           (pr-str backward)))
                  (finally (finish)))))
            (catch :default e
              (is false (str "the hidden-trailing-control row threw: "
                             (.-message e)))
              (finish))))))))

;; ---------------------------------------------------------------------------
;; The two the platform still calls VISIBLE
;; ---------------------------------------------------------------------------
;;
;; SAME DEFECT, AND THE REASON THE ROW ABOVE CANNOT REACH THEM. That
;; row is answered by `checkVisibility`, because `visibility:hidden` and a
;; closed `<details>` are exactly what the platform means by not visible.
;; An `inert` region and a `disabled` `<fieldset>` are PAINTED — the user
;; can read them, they are simply switched off — so `checkVisibility`
;; answers true for both, as the premises below assert, and no amount of
;; visibility reasoning will exclude them.
;;
;; The argument for leaving them counted is that a surplus candidate only ever
;; costs a wrap that does not fire. That is priced wrong at the last position
;; — a surplus at the END is what `peek` returns. These two pages put each of
;; them last, and both are the ordinary kind: the wizard step not yet
;; reached, and the form section a prior answer has not unlocked. Counted,
;; either one takes `document.activeElement` to `<body>` on BOTH edges.

(deftest a-real-tab-wraps-past-a-trailing-inert-region
  (if-not (browser?)
    (skip! ":node-test has no modality, and no engine to press a key at")
    (if-not (rf.fresco.trusted-input-support/bridge?)
      (rf.fresco.trusted-input-support/unwitnessed! "the trap over a panel ending in an inert region")
      (async done
        (let [m      (rf.fresco.test.mounted/mount! [a-page-with-a-modal-whose-last-control-is-inert {}])
              finish (fn [] (rf.fresco.test.mounted/unmount! m) (done))]
          (try
            (open! m)
            ;; THE PREMISE that separates this row from the hidden one.
            (is (true? (.checkVisibility ($ m "#ghost")
                                         #js {"visibilityProperty" true}))
                "premise: the platform calls it VISIBLE, which is why
                 the `checkVisibility` exclusion that answers the hidden
                 control cannot answer this one")

            (both-edges!
              m "#reason" 5
              (fn [forward backward]
                (try
                  (is (= ["ok" "cancel" "reason" "ok" "cancel"] forward)
                      (str "THE CYCLE, FORWARD. `cancel` is the last stop, "
                           "so Tab off it lands on `reason`. Against a "
                           "predicate that counted an inert element, the "
                           "computed edge would be `ghost`, the press off "
                           "`cancel` would match nothing, and the third landing "
                           "would be `body`. Pressed: " (pr-str forward)))
                  (is (= ["cancel" "ok" "reason" "cancel" "ok"] backward)
                      (str "THE CYCLE, BACKWARD. Shift+Tab off `reason` "
                           "lands on `cancel`. Against that predicate the "
                           "wrap would aim at `ghost`, `.focus()` would decline, "
                           "`preventDefault` would be correctly withheld, and "
                           "withholding it would put the FIRST landing on "
                           "`body`. Pressed: " (pr-str backward)))
                  (finally (finish)))))
            (catch :default e
              (is false (str "the inert-trailing-region row threw: "
                             (.-message e)))
              (finish))))))))

(deftest a-real-tab-wraps-past-a-trailing-disabled-fieldset
  (if-not (browser?)
    (skip! ":node-test has no modality, and no engine to press a key at")
    (if-not (rf.fresco.trusted-input-support/bridge?)
      (rf.fresco.trusted-input-support/unwitnessed! "the trap over a panel ending in a disabled fieldset")
      (async done
        (let [m      (rf.fresco.test.mounted/mount!
                       [a-page-with-a-modal-whose-last-control-is-in-a-disabled-fieldset {}])
              finish (fn [] (rf.fresco.test.mounted/unmount! m) (done))]
          (try
            (open! m)
            ;; THE PREMISE THAT NAMES THE READING. The property and the
            ;; pseudo-class disagree here, and that disagreement is the
            ;; entire defect: one reflects the element's own attribute,
            ;; the other the state the engine actually enforces.
            (is (false? (.-disabled ($ m "#ghost")))
                "premise: `ghost.disabled` is FALSE — the IDL property
                 reflects the button's own attribute and it has none, so
                 the property reading cannot see this case at all")
            (is (true? (.checkVisibility ($ m "#ghost")
                                         #js {"visibilityProperty" true}))
                "premise: and it is VISIBLE — painted and greyed, not
                 hidden — so the `checkVisibility` exclusion does not
                 reach it either")

            (both-edges!
              m "#reason" 5
              (fn [forward backward]
                (try
                  (is (= ["ok" "cancel" "legend-button" "reason" "ok"] forward)
                      (str "THE CYCLE, FORWARD, and the last stop is "
                           "`legend-button` — not `cancel`, and not "
                           "`ghost`. Against a predicate that read the "
                           "`.disabled` PROPERTY, the computed edge would be "
                           "`ghost`, and the fourth landing would be `body`. "
                           "A predicate that excluded the fieldset "
                           "wholesale reds here the other way, landing the "
                           "wrap on `cancel`. Pressed: " (pr-str forward)))
                  (is (= ["legend-button" "cancel" "ok" "reason" "legend-button"]
                         backward)
                      (str "THE CYCLE, BACKWARD. `reason` is the first "
                           "stop, so Shift+Tab off it lands directly on "
                           "`legend-button`. Against that predicate the "
                           "wrap would aim at `ghost`, fail to land, and put the "
                           "FIRST landing on `body`. Pressed: "
                           (pr-str backward)))
                  (finally (finish)))))
            (catch :default e
              (is false (str "the disabled-fieldset row threw: "
                             (.-message e)))
              (finish))))))))

;; ---------------------------------------------------------------------------
;; The wrap's yield — a press a panel control has already claimed
;; ---------------------------------------------------------------------------

(deftest a-real-tab-a-panel-control-has-claimed-does-not-wrap
  ;; THE YIELD, measured on the flag the module actually reads.
  ;; `impl.overlay/wrap-tab!` reads the NATIVE event's `defaultPrevented`
  ;; rather than the synthetic copy React takes at construction, and the
  ;; distinction is real: the child's `preventDefault` runs during the
  ;; same dispatch the panel's handler is part of, so only the native
  ;; flag has moved by the time the wrap asks. No other row can see any
  ;; of this — every wrap row presses keys nothing claims, and the
  ;; popover row has no wrap at all.
  (if-not (browser?)
    (skip! ":node-test has no modality, and no engine to press a key at")
    (if-not (rf.fresco.trusted-input-support/bridge?)
      (rf.fresco.trusted-input-support/unwitnessed! "the wrap's yield to a claimed press")
      (async done
        (let [m      (rf.fresco.test.mounted/mount! [a-page-with-a-modal-whose-last-control-claims-tab {}])
              finish (fn [] (rf.fresco.test.mounted/unmount! m) (done))]
          (try
            (open! m)
            (.focus ($ m "#cancel"))

            (rf.fresco.trusted-input-support/press-once!
              "Tab"
              (fn []
                (try
                  (rf.fresco.test.mounted/settle! m)
                  (is (= 1 (:tab-claims (rf/app-db-value (:frame m))))
                      "premise: the press reached the control and its claim
                       dispatched, exactly once — so the reading below is of
                       a delivered key, not of a bridge that never fired")
                  (is (= "cancel" (label-of (active)))
                      (str "THE YIELD. Tab off the panel's last control is "
                           "the edge every other row measures the wrap "
                           "firing at, landing on `confirm` — and this "
                           "press was claimed by the control itself, so "
                           "the wrap stands down and focus does not move: "
                           "not to `confirm`, and — the engine's default "
                           "action being prevented too — not to anything "
                           "else. Active: " (label-of (active))))
                  (finally (finish)))))
            (catch :default e
              (is false (str "the claimed-tab row threw: " (.-message e)))
              (finish))))))))

;; ---------------------------------------------------------------------------
;; The module's popover — deliberately not a trap
;; ---------------------------------------------------------------------------

(deftest a-real-tab-leaves-an-open-popover
  ;; THE FENCE ON THE MODAL'S WRAP. `impl.overlay/wrap-tab!` changes no
  ;; element's focusability whatever — it changes where Tab GOES at one
  ;; edge. So a wrap that had been wired
  ;; into the popover as well as the modal would leave every assertion in
  ;; this file green except this one, which presses a real Tab off the
  ;; panel's last control and requires it to land on the page behind.
  ;;
  ;; That is not a defect being tolerated: a filter menu a keyboard user
  ;; cannot tab out of is the defect, and the module withholds the handler
  ;; from the popover for exactly that reason.
  (if-not (browser?)
    (skip! ":node-test has no popover, and no engine to press a key at")
    (if-not (rf.fresco.trusted-input-support/bridge?)
      (rf.fresco.trusted-input-support/unwitnessed! "a popover's deliberate lack of a trap")
      (async done
        (let [m      (rf.fresco.test.mounted/mount! [a-page-with-a-popover {}])
              finish (fn [] (rf.fresco.test.mounted/unmount! m) (done))]
          (try
            (open! m)
            (.focus ($ m "#cancel"))
            (is (= "cancel" (label-of (active)))
                (str "premise: on the panel's last control. Active: "
                     (label-of (active))))

            (rf.fresco.trusted-input-support/press-once!
              "Tab"
              (fn []
                (try
                  (is (= "after" (label-of (active)))
                      (str "A REAL TAB OFF THE PANEL'S LAST CONTROL LEAVES "
                           "IT, and lands at the panel's own place in the "
                           "DOM — `after`, the control the author wrote "
                           "next. The modal's two-edge wrap is the modal's "
                           "alone. Active: " (label-of (active))))
                  (finally (finish)))))
            (catch :default e
              (is false (str "the popover row threw: " (.-message e)))
              (finish))))))))
