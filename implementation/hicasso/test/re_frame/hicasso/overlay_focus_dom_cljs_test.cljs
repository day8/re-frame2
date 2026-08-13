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
  | a REAL Tab cycles inside the trap, and a REAL Escape lets the page back out | [[a-real-tab-cycles-within-the-modal-and-a-real-escape-gives-the-page-back]] |

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
  one an emptiness claim over `reachable` can only approximate. The same
  row then presses a real Escape and gets the page back.

  It also found something no amount of asking would have: the wrap goes
  through the DOCUMENT. `document.activeElement` reads `<body>` for one
  step between the panel's last control and its first, so the cycle is
  four stops for three controls. That is recorded on the row itself,
  because it is the engine's conduct and not this module's.

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

            ;; FIVE, so the sequence closes: four presses is a wrap, five is
            ;; a CYCLE, and only the second rules out a one-off excursion
            ;; that happened to come back.
            (trusted/walk!
              "Tab" 5
              (fn [] (some-> (active) label-of))
              (fn [landings]
                (try
                  (is (= ["reason" "cancel" "body" "confirm" "reason"] landings)
                      (str "THE CYCLE, AS THE ENGINE ACTUALLY PERFORMS IT. From "
                           "the panel's first control, Tab runs to its last and "
                           "then wraps back to its first — never to `before`, "
                           "`trigger` or `after`, and the fifth press repeats "
                           "the second, so this closes rather than merely "
                           "returning once.\n"
                           "  MEASURED, AND NOT WHAT WAS PREDICTED: the wrap "
                           "goes through the DOCUMENT. Off the last control, "
                           "`document.activeElement` is `<body>` for one step — "
                           "the nothing-is-focused reading — and the step after "
                           "re-enters the panel. So a modal's cycle is four "
                           "stops for three controls. That waypoint is not a "
                           "leak: `<body>` is not a control, it is what "
                           "`activeElement` says when focus rests nowhere, and "
                           "the next press proves the scope never widened. A "
                           "row that had asserted the tidier "
                           "`[reason cancel confirm]` would have been asserting "
                           "a guess about the engine. Pressed: " (pr-str landings)))
                  (is (empty? (filter #{"before" "trigger" "after"} landings))
                      (str "and not one landing was a control of the page "
                           "behind — the leak a keyboard user tabs straight "
                           "out of. Pressed: " (pr-str landings)))

                  (trusted/press-once!
                    "Escape"
                    (fn []
                      (try
                        (hm/settle! m)
                        (testing "AND OUT: the platform's own dismissal, which
                                  is the only exit a trap has. The panel goes,
                                  and the page comes back exactly as
                                  [[an-open-modal-is-the-whole-of-what-a-keyboard-can-reach]]
                                  found it after a programmatic close"
                          (is (nil? ($ m "dialog")))
                          (is (= ["before" "trigger" "after"]
                                 (reachable (:container m)))))
                        (finally (finish)))))
                  (catch :default e
                    (is false (str "the cycle assertion threw: " (.-message e)))
                    (finish)))))
            (catch :default e
              (is false (str "the trap row threw: " (.-message e)))
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
