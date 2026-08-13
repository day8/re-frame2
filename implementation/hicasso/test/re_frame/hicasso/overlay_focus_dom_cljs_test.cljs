(ns re-frame.hicasso.overlay-focus-dom-cljs-test
  "L4 — WHAT A KEYBOARD CAN REACH WHILE AN OVERLAY IS OPEN
  (rf2-hic-049, over rf2-hic-052's overlay module).

  > | Accessibility | Semantic Hiccup and native controls | Structural
  > a11y assertions plus browser focus tests | Names, roles, keyboard,
  > **virtualized/overlay focus** |
  >
  > — product specification §7

  `overlay-dom-cljs-test` establishes the module's posture: the top
  layer, the LIFO stack, light dismiss, the imperative call, the
  listener census. It also asserts inertness — by focusing ONE control
  behind an open modal and finding that it will not take focus. This
  file finishes that claim, because *one control refused focus* and *the
  page behind is unreachable* are different statements and only the
  second is the thing a modal is bought for.

  ## The three claims, and why the middle one is what makes the others mean
  ## anything

  | claim | row |
  |---|---|
  | a real `<dialog>` traps focus when it is MODAL, and does not when it is merely shown | [[the-instrument-tells-a-trap-from-its-absence]] |
  | the module's modal is the trapping kind: the reachable set becomes EXACTLY the panel's controls | [[an-open-modal-is-the-whole-of-what-a-keyboard-can-reach]] |
  | the module's popover is NOT a trap, and must not be read as one | [[an-open-popover-leaves-the-page-behind-it-reachable]] |
  | a REAL Tab and a REAL Shift+Tab cycle inside the trap, and a REAL Escape lets the page back out | [[a-real-tab-cycles-within-the-modal-and-a-real-escape-gives-the-page-back]] |
  | the wrap fires at the SEQUENTIAL edge, over a panel whose sequential order is shorter than its markup | [[a-real-tab-wraps-at-the-radio-groups-edge-and-not-at-the-dom-s]] |
  | …and over one whose sequential order is a permutation of it | [[a-real-tab-wraps-at-the-tabindex-ordered-edge]] |

  The middle claim is an emptiness claim about everything outside a
  dialog, and an emptiness claim is exactly the shape that passes
  vacuously — an instrument that reported *nothing is focusable* would
  satisfy it perfectly. So the first row is the SEEDED VIOLATION the
  bead's acceptance asks for, made permanent rather than run once by
  hand: the same `<dialog>` element, holding the same controls, opened
  through `show()` instead of `showModal()`. That is a legal call on the
  same element which paints the same panel and fires the same events,
  and the ONE thing it does not do is make the document behind it inert.
  The row asserts that the page behind stays reachable under `show()`,
  which is the trap removed, measured.

  The third row is the other direction on the product itself. A popover
  is deliberately not modal — a filter menu that made the page it
  filters unreachable would be a defect, not a feature — so a suite that
  only ever asserted *an overlay makes everything else unfocusable*
  would be asserting something the module does not claim and must not
  do.

  ## What is measured, and what the fourth row adds

  The first three rows ask the ENGINE — blur, request focus, read
  `document.activeElement` back — because inertness is decided by the
  engine's own modality and by nothing this module writes. See
  `examples.ledger.keyboard-dom-cljs-test` on why asking is the only
  honest instrument.

  Asking is not quite tabbing, though, and this file's central claim is
  about what a keyboard user can REACH. [[reachable]] answers *which
  elements would take focus if asked*; a trap is *where Tab goes when it
  runs out of panel*. The two agree here, and since rf2-il7b that
  agreement is measured rather than assumed:
  [[a-real-tab-cycles-within-the-modal-and-a-real-escape-gives-the-page-back]]
  presses Tab through the browser gate's trusted-input bridge
  (`re-frame.hicasso.trusted-input-support`, whose other half is
  `scripts/run-browser-tests.cjs`) and finds it CYCLING — off the last
  panel control back onto the first, never onto `before`, `trigger` or
  `after` — which is the property a modal is actually bought for and the
  one an emptiness claim over `reachable` can only approximate. It
  presses BOTH directions, because a wrap is two edges. The same row then
  presses a real Escape and gets the page back.

  It also found something no amount of asking would have, and rf2-hic-052
  then fixed rather than blessed: left to itself the engine wraps through
  the DOCUMENT, and `document.activeElement` reads `<body>` for one press
  between the panel's last control and its first — focus resting nowhere,
  which in a browser with UI is the browser's own UI. Inertness was never
  in question; the WRAP was, and `impl.overlay/wrap-tab!` now closes both
  edges. The row asserts the three-stop cycle, so it reddens the day that
  handler stops working — and the version of it that recorded `<body>` as
  a waypoint is what the audit of #8058 correctly refused to accept.

  ## What that row still could not fail on, and the two below that can

  Its panel is three plain controls, and over three plain controls the
  ELEMENTS a selector finds and the STOPS Tab visits are the same list in
  the same order. A handler computing the first from document order was
  therefore indistinguishable, here, from one computing it correctly —
  and the audit of #8071 measured the difference on a panel this fixture
  could not hold: a radio group, which is three elements and ONE stop,
  where Shift+Tab off the checked member went to `<body>` because the
  handler had taken the unchecked one for the panel's first stop.

  [[a-real-tab-wraps-at-the-radio-groups-edge-and-not-at-the-dom-s]] and
  [[a-real-tab-wraps-at-the-tabindex-ordered-edge]] are that missing
  population, one panel per way the two lists can differ. What
  `impl.overlay/sequential-tab-stops` does and does not model is stated
  on that function; these rows measure the part it claims.

  `overlay-dom-cljs-test` holds the synthetic-versus-trusted comparison
  for Escape itself; this file does not re-litigate it.

  ## Browser lane

  `<dialog>`, `popover`, the top layer and inertness have no
  implementation in the node lane at all, so every row states a skip
  there rather than a false green. The trusted-input row is `async` —
  the press happens in another process — which is why the fixture below
  is the MAP shape (rf2-u0j8: an async row under a POSITIONAL fixture
  aborts the whole run)."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.overlay :as overlay]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.hicasso.trusted-input-support :as trusted]
            [re-frame.test-support :as test-support]))

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

;; The two pages differ in the OVERLAY and in nothing else: the same
;; three panel controls, the same three page controls, the same flag.

(h/defview a-page-with-a-modal [_]
  [:div
   [:button#before {:type "button"} "Before"]
   [:button#trigger {:type "button" :on-click [::opened]} "Open"]
   [overlay/modal {:open?      (h/sub [::open?])
                   :on-dismiss [::dismissed]
                   :label      "Confirm deletion"}
    [:button#confirm {:type "button"} "Delete"]
    [:input#reason {:type "text" :aria-label "Reason"}]
    [:button#cancel {:type "button" :on-click [::closed]} "Cancel"]]
   [:button#after {:type "button"} "After"]])

(h/defview a-page-with-a-popover [_]
  [:div
   [:button#before {:type "button"} "Before"]
   [:button#trigger {:type "button" :on-click [::opened]} "Open"]
   [overlay/popover {:open?      (h/sub [::open?])
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
;; DOCUMENT order, which is the difference the audit of #8071 measured
;; `impl.overlay` getting wrong; the rows above are about inertness and
;; the popover contrast, and folding a radio group into their fixture
;; would change what every one of them reads without making any of them
;; measure a wrap.

(h/defview a-page-with-a-modal-holding-a-radio-group [_]
  ;; A RADIO GROUP IS THREE ELEMENTS AND ONE TAB STOP. Document order in
  ;; this panel is [small large ok reason]; sequential order is
  ;; [large ok reason], because `small` is an unchecked member of a group
  ;; whose checked member is `large`. The group is FIRST on purpose — that
  ;; puts the discrepancy on the backward edge, where a handler reading
  ;; document order finds `small` and never `large`.
  [:div
   [:button#before {:type "button"} "Before"]
   [:button#trigger {:type "button" :on-click [::opened]} "Open"]
   [overlay/modal {:open?      (h/sub [::open?])
                   :on-dismiss [::dismissed]
                   :label      "Pick a size"}
    [:input#small {:type "radio" :name "size" :aria-label "Small"}]
    [:input#large {:type "radio" :name "size" :default-checked true
                   :aria-label "Large"}]
    [:button#ok {:type "button"} "OK"]
    [:input#reason {:type "text" :aria-label "Reason"}]]
   [:button#after {:type "button"} "After"]])

(h/defview a-page-with-a-modal-ordered-by-tabindex [_]
  ;; A POSITIVE `tabindex` SORTS AHEAD OF EVERY ZERO. Document order is
  ;; [second first third]; sequential order is [first second third],
  ;; because 1 precedes 2 and both precede the implicit 0.
  [:div
   [:button#before {:type "button"} "Before"]
   [:button#trigger {:type "button" :on-click [::opened]} "Open"]
   [overlay/modal {:open?      (h/sub [::open?])
                   :on-dismiss [::dismissed]
                   :label      "Ordered by tabindex"}
    [:button#second {:type "button" :tab-index 2} "Second"]
    [:button#first {:type "button" :tab-index 1} "First"]
    [:button#third {:type "button"} "Third"]]
   [:button#after {:type "button"} "After"]])

;; `:async? true` — the MAP shape — because the trusted-input row below
;; is `(async done …)`. `make-reset-runtime-fixture` returns the
;; POSITIONAL fn by default, and cljs.test throws a bare string that
;; unwinds the entire lane the moment an async body appears under one
;; (rf2-u0j8).
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
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
  every row here asks rather than reads."
  [^js el]
  (some-> (active) (.blur))
  (.focus el)
  (identical? el (active)))

(defn- reachable
  "Every element under `root` the engine will focus, in document order,
  as readable names.

  Scoped to a root rather than to the document because a mount's own
  container is what a page is here, and because the sabotage row below
  parks a raw `<dialog>` on `document.body` deliberately outside it."
  [^js root]
  (let [order (->> (.querySelectorAll root "a[href],button,input,select,textarea,[tabindex]")
                   (array-seq)
                   (filterv focusable?)
                   (mapv label-of))]
    (some-> (active) (.blur))
    order))

(defn- open! [m] (hm/dispatch-and-settle! m [::opened]) m)
(defn- close! [m] (hm/dispatch-and-settle! m [::closed]) m)

(defn- $ [m sel] (.querySelector (:container m) sel))

;; ---------------------------------------------------------------------------
;; The seeded violation — one method name apart
;; ---------------------------------------------------------------------------

(defn- raw-dialog!
  "A `<dialog>` holding one button, built from raw DOM and parked on
  `document.body`.

  Raw, and not this module's, on purpose: the claim it settles is about
  the PLATFORM's two doors, and the shortest honest way to compare them
  is an element nothing else has touched. It is parked outside the
  mount's container so that removing it cannot disturb React's
  reconciliation of a tree it does not own."
  []
  (let [d (.createElement js/document "dialog")
        b (.createElement js/document "button")]
    (set! (.-id b) "raw-inside")
    (set! (.-textContent b) "Inside")
    (.appendChild d b)
    (.appendChild (.-body js/document) d)
    d))

(deftest the-instrument-tells-a-trap-from-its-absence
  ;; THE SEEDED VIOLATION, made permanent. Every row below this one is an
  ;; emptiness claim about the page behind an overlay, and an instrument
  ;; that answered `not focusable` to everything would satisfy all of
  ;; them at once. So the trap is removed here — the same element, the
  ;; same contents, `show()` where `showModal()` was — and the page
  ;; behind must be measured STILL REACHABLE.
  (if-not (browser?)
    (skip! ":node-test has no <dialog>")
    (let [m      (hm/mount! [a-page-with-a-modal {}])
          ^js d  (raw-dialog!)]
      (try
        (testing "premise: with nothing open, the page's own controls are
                  the reachable set"
          (is (= ["before" "trigger" "after"] (reachable (:container m)))))

        (testing "SHOWN, NOT MODAL — the trap removed. A legal call on
                  the same element: it paints the same panel, fires the
                  same events, joins no top layer of its own, and leaves
                  the document behind it live"
          (.show d)
          (is (true? (.-open d)) "premise: it really is open")
          (is (zero? (.-length (.querySelectorAll js/document ":modal")))
              "and the engine does not consider it modal")
          (is (= ["before" "trigger" "after"] (reachable (:container m)))
              "so every control behind it is still the engine's to focus
               — which is what a missing focus trap looks like, measured
               rather than described")
          (.close d))

        (testing "MODAL — the same element, one method name apart, and
                  the page behind goes inert"
          (.showModal d)
          (is (= 1 (.-length (.querySelectorAll js/document ":modal"))))
          (is (= [] (reachable (:container m)))
              "nothing behind it can be focused. The two arms differ in
               `show` versus `showModal` and in nothing else, so the
               instrument is measuring modality and not, say, an element
               it cannot see")
          (is (true? (focusable? (.querySelector d "#raw-inside")))
              "while the dialog's own control still takes focus, so the
               reading above is inertness rather than a page that has
               stopped answering")
          (.close d))

        (finally
          (when (.-open d) (.close d))
          (.remove d)
          (hm/unmount! m))))))

;; ---------------------------------------------------------------------------
;; The module's modal — the trap, complete
;; ---------------------------------------------------------------------------

(deftest an-open-modal-is-the-whole-of-what-a-keyboard-can-reach
  (if-not (browser?)
    (skip! ":node-test has no modality")
    (let [m (hm/mount! [a-page-with-a-modal {}])]
      (try
        (testing "premise: closed, the page is its own three controls and
                  the panel is not in the document at all"
          (is (= ["before" "trigger" "after"] (reachable (:container m))))
          (is (nil? ($ m "dialog"))))

        (open! m)

        (testing "opening moves focus INTO the panel — the platform's own
                  dialog-focusing steps, which `overlay-dom-cljs-test`
                  holds in detail; asserted here only as the premise the
                  next row needs"
          (is (.contains ($ m "dialog") (active))))

        (is (= ["confirm" "reason" "cancel"] (reachable (:container m)))
            "THE TRAP, COMPLETE. The reachable set is now EXACTLY the
             panel's three controls, in the panel's document order —
             `before`, `trigger` and `after` have all left it. Stated as
             the whole sequence rather than as `is #before still
             focusable?`, because a modal that leaked one control of the
             page behind it is a modal a keyboard user tabs straight out
             of, and a membership test over one control cannot see the
             leak in another")

        (close! m)

        (testing "and closing gives the page back — the same three
                  controls, in the same order, so the reading above was
                  a state the page was in and not a state it is now
                  stuck in"
          (is (nil? ($ m "dialog")))
          (is (= ["before" "trigger" "after"] (reachable (:container m)))))

        (finally (hm/unmount! m))))))

;; ---------------------------------------------------------------------------
;; The trap under a real keyboard
;; ---------------------------------------------------------------------------

(deftest a-real-tab-cycles-within-the-modal-and-a-real-escape-gives-the-page-back
  ;; WHAT THIS ADDS TO THE ROW ABOVE, and it is not decoration.
  ;; [[an-open-modal-is-the-whole-of-what-a-keyboard-can-reach]] is an
  ;; emptiness claim: it asks each of the page's controls whether it
  ;; would take focus, and finds that none would. That is inertness.
  ;; A TRAP is the positive fact next door — that Tab off the end of the
  ;; panel comes back to its start rather than leaving — and no amount
  ;; of asking elements whether they are focusable can see it, because
  ;; the thing being measured is where the engine SENDS focus when
  ;; nobody asked.
  ;;
  ;; Escape closes the row out, because a trap with no exit is a defect
  ;; rather than a feature, and the exit is the platform's alone.
  (if-not (browser?)
    (skip! ":node-test has no modality, and no engine to press a key at")
    (if-not (trusted/bridge?)
      (trusted/unwitnessed! "the trap under real sequential navigation")
      (async done
        (let [m      (hm/mount! [a-page-with-a-modal {}])
              finish (fn [] (hm/unmount! m) (done))]
          (try
            (open! m)
            (is (.contains ($ m "dialog") (active))
                "premise: opening put focus inside the panel")
            (is (= "confirm" (label-of (active)))
                (str "premise: on the panel's first control. Active: "
                     (label-of (active))))

            ;; FIVE, so the sequence closes: three presses is a wrap, five
            ;; is a CYCLE with its start repeated, and only the second
            ;; rules out a one-off excursion that happened to come back.
            (trusted/walk!
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
                  (is (empty? (filter #{"before" "trigger" "after" "body"} forward))
                      (str "and not one landing was a control of the page "
                           "behind, nor the nowhere between them. Pressed: "
                           (pr-str forward)))

                  ;; Back to the first control, so the backward lap is
                  ;; measured from the edge Shift+Tab has to wrap AT rather
                  ;; than from wherever the forward lap finished.
                  (.focus ($ m "#confirm"))
                  (is (= "confirm" (label-of (active)))
                      "premise: back on the panel's first control")

                  (trusted/walk!
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
                        (is (empty? (filter #{"before" "trigger" "after" "body"} backward))
                            (str "and the backward lap leaves the panel no "
                                 "more than the forward one does. Pressed: "
                                 (pr-str backward)))

                        (trusted/press-once!
                          "Escape"
                          (fn []
                            (try
                              (hm/settle! m)
                              (testing "AND OUT: the platform's own dismissal,
                                        which is the only exit a trap has — and
                                        it still is, because the wrap answers
                                        Tab and nothing else. The panel goes,
                                        and the page comes back exactly as
                                        [[an-open-modal-is-the-whole-of-what-a-keyboard-can-reach]]
                                        found it after a programmatic close"
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
;; WHY THE ROW ABOVE COULD NOT HAVE CAUGHT THIS. Its panel is three plain
;; controls, and over three plain controls *the elements a selector finds*
;; and *the stops Tab visits* are the same list in the same order. So a
;; handler that read the first and taught itself it had the second passed
;; that row perfectly, and went on choosing the wrong edge on any panel
;; where the two lists differ — which the audit of #8071 then measured.
;;
;; The two rows below are that population, added rather than argued: one
;; panel whose sequential order is SHORTER than its document order (a
;; radio group), one whose sequential order is a PERMUTATION of it (a
;; positive `tabindex`). Both drive both edges with trusted input, and
;; both name `body` explicitly, because `<body>` is where a wrap that did
;; not fire puts focus and reading it as merely "some other control" is
;; how #8058 shipped.

(defn- both-edges!
  "Focus `start`, walk `n` trusted Tabs, re-focus `start`, walk `n` trusted
  Shift+Tabs, and hand the two laps to `k`.

  Re-focusing between the laps rather than continuing from wherever the
  forward one finished, so the backward lap is measured FROM the edge
  Shift+Tab has to wrap at."
  [m start n k]
  (.focus ($ m start))
  (trusted/walk!
    "Tab" n
    (fn [] (some-> (active) label-of))
    (fn [forward]
      (.focus ($ m start))
      (trusted/walk!
        "Shift+Tab" n
        (fn [] (some-> (active) label-of))
        (fn [backward] (k forward backward))))))

(deftest a-real-tab-wraps-at-the-radio-groups-edge-and-not-at-the-dom-s
  (if-not (browser?)
    (skip! ":node-test has no modality, and no engine to press a key at")
    (if-not (trusted/bridge?)
      (trusted/unwitnessed! "the trap over a panel holding a radio group")
      (async done
        (let [m      (hm/mount! [a-page-with-a-modal-holding-a-radio-group {}])
              finish (fn [] (hm/unmount! m) (done))]
          (try
            (open! m)
            (is (.contains ($ m "dialog") (active))
                "premise: opening put focus inside the panel")
            (is (true? (.-checked ($ m "#large")))
                "premise: `large` is the group's checked member, so it — and
                 not `small` — is the one stop the group contributes")

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
                           "of it. Against the algorithm this row was "
                           "written for, the third landing was `small`. "
                           "Pressed: " (pr-str forward)))
                  (is (= ["reason" "ok" "large" "reason" "ok"] backward)
                      (str "THE CYCLE, BACKWARD, and this is the edge that "
                           "was measured broken. `large` IS the panel's "
                           "first stop, so Shift+Tab off it must land "
                           "directly on `reason`; a handler that took the "
                           "panel's first ELEMENT for its first STOP found "
                           "`small`, matched nothing, let the default action "
                           "stand and put focus on `<body>`. Pressed: "
                           (pr-str backward)))
                  (is (empty? (filter #{"before" "trigger" "after" "body" "small"}
                                      (concat forward backward)))
                      (str "and not one landing in either lap was a control "
                           "of the page behind, the nowhere between them, or "
                           "the group member the engine does not sequence to. "
                           "Pressed: " (pr-str (concat forward backward))))
                  (finally (finish)))))
            (catch :default e
              (is false (str "the radio-group trap row threw: " (.-message e)))
              (finish))))))))

(deftest a-real-tab-wraps-at-the-tabindex-ordered-edge
  (if-not (browser?)
    (skip! ":node-test has no modality, and no engine to press a key at")
    (if-not (trusted/bridge?)
      (trusted/unwitnessed! "the trap over a panel ordered by positive tabindex")
      (async done
        (let [m      (hm/mount! [a-page-with-a-modal-ordered-by-tabindex {}])
              finish (fn [] (hm/unmount! m) (done))]
          (try
            (open! m)
            (is (.contains ($ m "dialog") (active))
                "premise: opening put focus inside the panel")

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
                  (is (empty? (filter #{"before" "trigger" "after" "body"}
                                      (concat forward backward)))
                      (str "and neither lap left the panel or rested nowhere. "
                           "Pressed: " (pr-str (concat forward backward))))
                  (finally (finish)))))
            (catch :default e
              (is false (str "the tabindex trap row threw: " (.-message e)))
              (finish))))))))

;; ---------------------------------------------------------------------------
;; The module's popover — deliberately not a trap
;; ---------------------------------------------------------------------------

(deftest an-open-popover-leaves-the-page-behind-it-reachable
  ;; The other direction, on the product rather than on the instrument.
  ;; A popover is an anchored panel over a live page: a filter menu that
  ;; made the list it filters unreachable would be a defect. Without
  ;; this row the suite would have proved only that opening SOMETHING
  ;; makes everything else unfocusable, which is not what the module
  ;; claims and not what it does.
  (if-not (browser?)
    (skip! ":node-test has no popover")
    (let [m (hm/mount! [a-page-with-a-popover {}])]
      (try
        (is (= ["before" "trigger" "after"] (reachable (:container m)))
            "premise: closed, the page is its own three controls")

        (open! m)

        (is (some? ($ m "[popover]")) "premise: the panel is in the document")

        (is (= ["before" "trigger" "confirm" "reason" "cancel" "after"]
               (reachable (:container m)))
            "THE CONTRAST. The panel's controls JOIN the page's rather
             than replacing them, and they join at the panel's place in
             the DOM — which is where the author wrote it, because the
             top layer changes paint order and not tree order. A
             keyboard user can tab out of a popover and back into the
             page, which is exactly the conduct that separates a menu
             from a dialog")

        (close! m)
        (is (= ["before" "trigger" "after"] (reachable (:container m))))

        (finally (hm/unmount! m))))))

(deftest a-real-tab-leaves-an-open-popover
  ;; THE FENCE ON THE MODAL'S WRAP, and the row above cannot stand in for
  ;; it. `reachable` asks each element whether it would take focus, and
  ;; `impl.overlay/wrap-tab!` changes no element's focusability whatever —
  ;; it changes where Tab GOES at one edge. So a wrap that had been wired
  ;; into the popover as well as the modal would leave every assertion in
  ;; this file green except this one, which presses a real Tab off the
  ;; panel's last control and requires it to land on the page behind.
  ;;
  ;; That is not a defect being tolerated: a filter menu a keyboard user
  ;; cannot tab out of is the defect, and the module withholds the handler
  ;; from the popover for exactly that reason.
  (if-not (browser?)
    (skip! ":node-test has no popover, and no engine to press a key at")
    (if-not (trusted/bridge?)
      (trusted/unwitnessed! "a popover's deliberate lack of a trap")
      (async done
        (let [m      (hm/mount! [a-page-with-a-popover {}])
              finish (fn [] (hm/unmount! m) (done))]
          (try
            (open! m)
            (.focus ($ m "#cancel"))
            (is (= "cancel" (label-of (active)))
                (str "premise: on the panel's last control. Active: "
                     (label-of (active))))

            (trusted/press-once!
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
