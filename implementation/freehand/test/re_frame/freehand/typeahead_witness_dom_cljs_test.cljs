(ns re-frame.freehand.typeahead-witness-dom-cljs-test
  "FH-CTRL-020, the MOUNTED half — the typeahead witness against a real
  Chromium commit.

  `typeahead-witness-cljs-test` proves what the control DECIDES. This file
  proves the things that do not exist outside a real engine, and there is
  no overlap between the two lists:

  - **the anchoring**, as a MEASUREMENT. The structural row proves the list
    carries constants naming an anchor and no geometry; only a browser can
    say whether those constants put the list under the input. They are read
    here as rectangles, against a CONTROL popover in the same document
    carrying no anchor at all — because `the list is below the input` is not
    a claim unless something in the same run is provably not.
  - **the top layer.** A `popover` is promoted out of the normal paint
    order, and `:popover-open` is a state only the platform can be asked
    for.
  - **focus.** The whole reason a combobox uses `aria-activedescendant` is
    that focus must NOT move to the option — and `document.activeElement`
    is the only thing that can testify to that.
  - **the composing Enter.** `key-intent` is pure and its table is proven by
    calling it. WHICH scalars a host keyboard event yields is a browser
    fact, and the failure mode is an engine that reports the composition
    through `keyCode` 229 rather than through `isComposing`. Each press
    below carries exactly ONE signal, so each is falsifiable on its own.

  ## Why the withholding row presses four keys

  `A composing Enter commits nothing` is a hard claim to make FALSIFIABLE,
  and the obvious reading of it is not: a commit retires the record, so a
  control that wrongly took the composing Enter commits once and leaves the
  plain Enter behind it a no-op — exactly one selection in both worlds. The
  row was mutation-tested into its current shape after that reading stayed
  green with the `keyCode` 229 fallback deleted.

  So the sequence is ArrowDown, composing Enter, ArrowUp, plain Enter, and
  the verdict is WHICH option committed. The first arrow is the wiring
  control; the second is a liveness probe that can only move the highlight
  if the record survived the press between them; the plain Enter is the
  timing control and the commit. See [[a-withheld-enter!]].

  ## The container is staged, deliberately

  The browser runner renders its report into the page, so a container
  appended to `document.body` lands wherever that report happens to end.
  `position-try-fallbacks: flip-block` would then legitimately flip the
  list ABOVE the input near the viewport floor, and the measurement would
  be reading the platform's collision handling rather than the anchoring.
  So the container is pinned to the top-left of the viewport — staging, not
  a fixture value, and it is why the rectangle assertions are exact rather
  than approximate.

  This file rides the browser lane through its `-dom-cljs-test` suffix. It
  also matches the node suites' broader regex, where it has no top layer to
  drive and says so rather than passing quietly."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.mount-support :as ms]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.freehand.typeahead-witness :as ta]
            [re-frame.freehand.web :as web]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

(def ctrl-020 (conf/fixture :FH-CTRL-020))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true
     ;; Bookkeeping, not cleanup: it forgets the roots the PREVIOUS test
     ;; made so `ms/residue-clean!` reads only this one's, and tears nothing
     ;; down — a teardown here would run before each test and hide exactly
     ;; what that assertion looks for.
     :init-fn       (fn [] (ms/reset-ledger!))}))

(def ^:private fid :rf.test/typeahead-witness)
(def ^:private doc-id :doc-1)
(def ^:private k [ta/typeahead-kind [:doc doc-id :reviewer]])
(def ^:private control-popover-id "kit-control-popover")

(defn- browser?
  "A real TOP LAYER, not merely a DOM. `showPopover` is the capability every
  claim here rests on, so it is the capability the guard asks for."
  []
  (and (ms/browser?)
       (some? (.-showPopover (.-prototype js/HTMLElement)))))

;; ---------------------------------------------------------------------------
;; The page. Module-level — a declared view cannot close over a test's
;; locals, so everything varying rides props.
;; ---------------------------------------------------------------------------

(v/defview witness-page
  "(v/defview) The witness, plus the CONTROL for its anchoring claim.

  The control popover is `:manual` on purpose: two `:auto` popovers are
  one-at-a-time by the platform's own light-dismiss rule, so an `:auto`
  control would close the moment the witness opened and there would be
  nothing to compare against. `:manual` also takes the UA stylesheet's
  centring placement untouched, which is exactly the placement an anchoring
  that silently did nothing would produce."
  {:props [:map [:id :any] [:field-name :string] [:debounce-ms :int]]}
  [{:keys [id field-name debounce-ms]}]
  [:div
   [ta/reviewer-form {:id id :field-name field-name :debounce-ms debounce-ms}]
   [:div {:id                 control-popover-id
          :popover            :manual
          ::web/popover-open? true
          ;; The advisory this satisfies is a real one: a controlled
          ;; top-layer state with no dismissal handler would be re-opened by
          ;; the next render. A `:manual` popover is never light-dismissed,
          ;; so the honest handler here is one that records the report and
          ;; changes nothing.
          :on-toggle          [:witness/control-toggled ::v/new-state]}
    "unanchored"]])

;; ---------------------------------------------------------------------------
;; Browser seams
;; ---------------------------------------------------------------------------
;;
;; The mounted LIFECYCLE — the `act` boundary, the `[container root]` pair
;; and the teardown — is `re-frame.freehand.mount-support`, the one facade
;; the browser tier shares. What stays local is the KEYBOARD, because a
;; keydown carrying two independent composition scalars is this suite's own
;; instrument and nothing else needs it.

(defn- press!
  "A real `keydown` on `node`, carrying the two composition scalars
  INDEPENDENTLY: `:composing?` becomes `isComposing` and `:key-code`
  becomes `keyCode`, with no coupling between them.

  That independence is the point. `c/composing?`, the reader beneath
  `key-intent`, ORs the standard flag together with the `keyCode` 229
  fallback that covers the engines which report the candidate-accepting
  Enter with `isComposing` FALSE (WebKit bug 165004), and an event carrying
  BOTH cannot tell a reader of both from a reader of either — so the
  implementation this row exists to catch, the one that consults only
  `isComposing`, would stay green."
  [node key {:keys [composing? key-code]}]
  (.dispatchEvent node (js/KeyboardEvent.
                         "keydown"
                         #js {:bubbles     true
                              :cancelable  true
                              :key         key
                              :isComposing (boolean composing?)
                              :keyCode     (or key-code 0)})))

(defn- plain
  "The scalars of a key with NO composition in flight: the flag false and
  the ordinary `keyCode`. The positive barrier every withholding proof
  closes with."
  []
  {:composing? false :key-code (:plain-key-code ctrl-020)})

(defn- click! [node]
  (.dispatchEvent node (js/MouseEvent. "click" #js {:bubbles true :cancelable true})))

(def ^:private never-fires-ms
  "The quiet period the mounted rows type with — set past the run on
  purpose. `:dispatch-later` is the REAL framework effect, so a keystroke
  arms a real host timer; one that fired after this suite's frame was torn
  down would dispatch into a destroyed frame at an arbitrary later moment,
  and the failure would land in whichever unrelated suite happened to be
  running. So the delayed event is delivered by hand — the same handler and
  the same arguments the timer would deliver — which drives the production
  path with the clock as a parameter rather than as a race."
  60000)

(defn- seed! []
  (live-frame/make-frame {:id fid})
  (ta/register!)
  (ta/register-app!)
  ;; The control popover's dismissal report. It changes nothing — a
  ;; `:manual` popover is never light-dismissed — and exists so the page
  ;; declares no controlled top-layer state without a handler for it.
  (rf/reg-event :witness/control-toggled (fn [_ _] {}))
  (frame/replace-app-db! fid {:doc {doc-id {:reviewer "" :reviewer-revision 0}}})
  fid)

(defn- stage!
  "A root whose container is pinned to the top-left of the viewport — see
  this namespace's docstring for why the staging is load-bearing."
  []
  (let [[container root] (ms/create-root!)]
    (set! (.. container -style -cssText)
          "position:fixed;top:0;left:0;width:320px;margin:0;padding:0")
    [container root]))

(defn- render! [root]
  (ms/act #(.render root
                    (shell/provide-frame
                      fid
                      (fr/element [witness-page {:id          doc-id
                                                 :field-name  (:field-name ctrl-020)
                                                 :debounce-ms never-fires-ms}])))))

(defn- app-db [] (frame/frame-app-db-value fid))
(defn- record [] (get-in (app-db) [ta/records-root k]))
(defn- seen [] (:seen (record) 0))
(defn- requests [] (get (app-db) ta/requests-key []))
(defn- selections [] (get (app-db) ta/selections-key []))

(defn- settled
  "Wait for `pred`, on a bounded deadline. Every intent here rides the
  ORDINARY batched path — the synchronous door is `onInput`/`onChange` on a
  controlled node and nothing else — so a keydown's effect is not readable
  on the statement after it. Waiting for the STATE rather than for a
  duration keeps the row bounded by a deadline it never normally reaches
  instead of by a sleep somebody has to tune."
  [pred label]
  (test-support/poll-until pred {:label label :timeout-ms 4000 :interval-ms 5}))

(defn- answer!
  "Reply to the request the control's debounce sent, the way the caller
  does: conj the outcome onto the reply prefix it was handed."
  [outcome]
  (let [request (last (requests))]
    (rf/dispatch-sync (conj (vec (:reply-to request)) outcome) {:frame fid})))

(defn- open-with-results!
  "Drive the control to an open list the ordinary way: TYPE `query` into the
  live input, deliver the delayed search the keystrokes scheduled, answer
  it, and settle when the list is open on screen.

  The typing is real — every character goes through the controlled-input
  door and round-trips through app-db — and the only thing supplied by hand
  is the CLOCK. Nothing is written behind the control's back."
  [container query results]
  (let [input (ms/q container (str "#" (:input-id ctrl-020)))]
    (ms/live!)
    (ms/type-string! input query)
    (-> (settled #(= query (:query (record))) "the keystrokes reach the record")
        (.then (fn [_]
                 (ms/act #(rf/dispatch-sync
                            [:kit.ui.typeahead/due k (:token (record))
                             [:desk/search-requested]]
                            {:frame fid}))))
        (.then (fn [_] (settled #(seq (requests)) "the caller is asked")))
        (.then (fn [_] (ms/act #(answer! {:results results}))))
        (.then (fn [_] (settled #(.matches (ms/q container (str "#" (:listbox-id ctrl-020)))
                                           ":popover-open")
                                "the list opens in the top layer")))
        (.then (fn [_] input)))))

(defn- ask!
  "Everything [[open-with-results!]] does UP TO the answer, and nothing
  after it: type `query` into the live input and deliver the delayed search
  the keystrokes scheduled, leaving the request open.

  The rows that need a FAILURE start here, because a failure is an answer
  too and the control has to have really asked before one can arrive."
  [container query]
  (let [input (ms/q container (str "#" (:input-id ctrl-020)))]
    (ms/live!)
    (ms/type-string! input query)
    (-> (settled #(= query (:query (record))) "the keystrokes reach the record")
        (.then (fn [_]
                 (ms/act #(rf/dispatch-sync
                            [:kit.ui.typeahead/due k (:token (record))
                             [:desk/search-requested]]
                            {:frame fid}))))
        (.then (fn [_] (settled #(seq (requests)) "the caller is asked")))
        (.then (fn [_] input)))))

(defn- teardown-clean!
  "The SUCCESS arm's last act: tear the mount down through the shared
  lifecycle, and assert that NOTHING it created survived.

  The residue assertion is what EARNS the record's `:residue :none` rather
  than asserting it. It runs AFTER teardown, over the facade's own books —
  every root this test created and what each left behind — plus the
  control's own book, the records root, which the owner's release empties.

  It does NOT end the row. The single `done` sits at the TAIL of the chain
  with nothing after it, and this is one of the two steps that tail waits
  on; [[report-failure!]] is the other, and says why they cannot be the
  same step."
  [container root]
  (ms/destroy-root! container root)
  (ms/residue-clean! "FH-CTRL-020 — after teardown"
                     [["the control's records root" #(get (app-db) ta/records-root {})]]))

(defn- report-failure!
  "The REJECTION arm: record the rejection against this row, tear the mount
  down — a rejected row must not leak its root either — and deliberately do
  NOT end the row. The chain's single trailing `done` does that.

  It reads no residue, and that asymmetry against [[teardown-clean!]] is
  load-bearing: a second failure attributed to the leak rule would bury the
  one that actually happened. It is also why the teardown stays written in
  both arms instead of riding the shared trailing step — the two arms do
  different amounts of work, so there is nothing here to hoist.

  **A rejection handler may not sit downstream of the step that calls
  `done`.** [[re-frame.freehand.mount-support/each-mode]] carries the
  long-form account (rf2-qpns): `run-block` hands `done` a continuation
  that runs the whole remainder of the run synchronously, so a `.catch` out
  past it claims a LATER namespace's throw as this row's failure and fires
  `done` a second time.

  One of these per row, on the OUTERMOST chain. Every nested chain here is
  returned into its parent, so a rejection anywhere inside reaches this
  handler without a handler of its own (rf2-29ua)."
  [container root]
  (fn [e]
    (is false (str "the browser step rejected: " e))
    (ms/destroy-root! container root)
    nil))

(defn- release!
  "The owner's exit path, dispatched before teardown so the residue read
  above has something to be true about."
  []
  (ms/act #(rf/dispatch-sync [:desk/closed k] {:frame fid})))

;; ===========================================================================
;; FH-CTRL-020 — the anchored popover, measured
;; ===========================================================================

(defn- rect [node] (.getBoundingClientRect node))
(defn- near? [a b tol] (<= (js/Math.abs (- a b)) tol))

(deftest fh-ctrl-020-the-list-is-promoted-and-anchored-to-the-input
  (testing "Per FH-CTRL-020: the suggestion list is a real popover in the
            top layer, and its top-left corner IS the input's bottom-left
            corner — because `anchor(bottom)` and `anchor(left)` say so and
            nothing measured anything.

            The CONTROL is what makes that a claim. A second popover in the
            same document, carrying no `position-anchor` at all, takes the
            UA stylesheet's centred placement — which is exactly where the
            list would be if the anchoring had silently done nothing. So
            the row asserts both: the list is at the input, and the
            unanchored twin is not."
    (if-not (browser?)
      (ms/skip! "the browser job runs the top-layer and anchoring assertions")
      (async done
        (let [{:keys [query results listbox-id anchor-tolerance-px]} ctrl-020
              _ (seed!)
              [container root] (stage!)]
          (-> (render! root)
              (.then (fn [_] (open-with-results! container query results)))
              (.then
                (fn [input]
                  (let [lst  (ms/q container (str "#" listbox-id))
                        ctl  (ms/q container (str "#" control-popover-id))
                        ir   (rect input)
                        lr   (rect lst)
                        cr   (rect ctl)]
                    (is (.matches lst ":popover-open")
                        "the list really is promoted into the top layer")
                    (is (.matches ctl ":popover-open")
                        "non-vacuous: so is the control, so the comparison is
                         between two promoted elements rather than with a
                         hidden one")

                    (is (near? (.-top lr) (.-bottom ir) anchor-tolerance-px)
                        (str "the list's top edge sits on the input's bottom edge "
                             "(list top " (.-top lr) ", input bottom " (.-bottom ir) ")"))
                    (is (near? (.-left lr) (.-left ir) anchor-tolerance-px)
                        (str "and their left edges align "
                             "(list left " (.-left lr) ", input left " (.-left ir) ")"))
                    (is (>= (.-width lr) (.-width ir))
                        "with the list at least as wide as what it is answering
                         — `anchor-size(width)`, also declared rather than measured")

                    (is (not (and (near? (.-top cr) (.-bottom ir) anchor-tolerance-px)
                                  (near? (.-left cr) (.-left ir) anchor-tolerance-px)))
                        (str "while the UNANCHORED control is somewhere else entirely "
                             "— so the placement above is the anchoring rather than "
                             "a coincidence of this page (control at "
                             (.-top cr) "," (.-left cr) ")"))
                    (release!))))
              (.then (fn [_] (teardown-clean! container root)))
              (.catch (report-failure! container root))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; FH-CTRL-020 — keyboard navigation, with focus staying put
;; ===========================================================================

(deftest fh-ctrl-020-the-arrows-move-the-active-descendant-and-not-the-focus
  (testing "Per FH-CTRL-020: ArrowDown and ArrowUp move the highlight, and
            `document.activeElement` never stops being the input. That is
            the whole reason a combobox names its active option through
            `aria-activedescendant` instead of focusing it — a listbox that
            took focus would stop the user typing, and no structural
            assertion can tell the two arrangements apart."
      (if-not (browser?)
        (ms/skip! "the browser job runs the focus assertions")
        (async done
          (let [{:keys [query results option-ids active-index first-index
                        seen-after-arrow]} ctrl-020
                _ (seed!)
                [container root] (stage!)]
            (-> (render! root)
                (.then (fn [_] (open-with-results! container query results)))
                (.then
                  (fn [input]
                    (.focus input)
                    (is (identical? input (.-activeElement js/document))
                        "non-vacuous: the input starts with focus")
                    (is (= (nth option-ids first-index)
                           (.getAttribute input "aria-activedescendant"))
                        "and a fresh answer starts on its first option")

                    (press! input ta/next-key (plain))
                    (-> (settled #(= active-index (:active (record)))
                                 "the ArrowDown moves the highlight")
                        (.then
                          (fn [_]
                            (is (= (nth option-ids active-index)
                                   (.getAttribute input "aria-activedescendant"))
                                "the active descendant followed the arrow, on screen")
                            (is (= "true" (.getAttribute
                                            (ms/q container (str "#" (nth option-ids active-index)))
                                            "aria-selected"))
                                "and so did the option's own selected state")
                            (is (identical? input (.-activeElement js/document))
                                "while focus never left the input")
                            (is (= seen-after-arrow (seen))
                                "the control's own handler ran exactly once — the
                                 positive barrier every withholding row below rests on")

                            (press! input ta/previous-key (plain))
                            (settled #(= first-index (:active (record)))
                                     "the ArrowUp moves it back")))
                        (.then
                          (fn [_]
                            (is (= (nth option-ids first-index)
                                   (.getAttribute input "aria-activedescendant"))
                                "and the DOM followed it back")
                            (is (identical? input (.-activeElement js/document))
                                "with focus still where the user left it")
                            (release!)))
                        (.then (fn [_] (teardown-clean! container root))))))
                (.catch (report-failure! container root))
                (.then (fn [_] (done)))))))))

;; ===========================================================================
;; FH-CTRL-020 — commit, and the Enter that belongs to the input method
;; ===========================================================================

(defn- a-withheld-enter!
  "ArrowDown, a COMPOSING Enter carrying `composing-press`'s scalars,
  ArrowUp, then a PLAIN Enter — on one live input — and assert what
  actually committed.

  ## Every press carries part of the proof

  Counting selections cannot prove this on its own, and the shape that
  looks like it can is a trap this row was mutation-tested out of: a commit
  RETIRES the record, so a control that wrongly took the composing Enter
  would commit once and leave the plain Enter behind it a no-op — exactly
  one selection in both worlds, and the row stays green with the `keyCode`
  229 fallback deleted.

    * the **ArrowDown** is the WIRING control. It moves the highlight on
      screen and takes `:seen` to one, so `the composing Enter dispatched
      nothing` is a statement about the composition rather than about a
      listener that was never attached.
    * the **ArrowUp** is the LIVENESS probe, and it is what makes the
      withholding observable at all: it can only move the highlight if the
      record survived the press before it.
    * the **plain Enter** is the TIMING control and the commit. The
      composing press had the same queue, the same node and more time, so a
      verdict read after it is about the composition rather than the clock.

  The committed value is therefore the whole verdict, and the fixture names
  both halves of it — the option that must be committed, and the one a
  control that took the composing Enter would have committed instead.

  ## Why this is a shared helper

  Because the two callers differ in EXACTLY ONE thing: which composition
  signal their event carries. The reader ORs those signals, so a proof that
  pressed both at once could not fail for either individually; giving each
  its own press over one identical procedure is what makes each OR branch
  falsifiable on its own."
  [composing-press done]
  (let [{:keys [query results active-index first-index seen-after-arrow
                selections-after-plain-enter committed-after-withheld-enter
                committed-if-enter-were-taken input-id]} ctrl-020
        _ (seed!)
        [container root] (stage!)]
    (-> (render! root)
        (.then (fn [_] (open-with-results! container query results)))
        (.then
          (fn [input]
            (.focus input)
            (press! input ta/next-key (plain))
            (-> (settled #(= active-index (:active (record))) "the arrow lands")
                (.then
                  (fn [_]
                    (is (= seen-after-arrow (seen))
                        "non-vacuous: the keydown site is live and reaching the handler")
                    (is (empty? (selections)) "and nothing has committed yet")
                    (is (not= committed-after-withheld-enter
                              committed-if-enter-were-taken)
                        "non-vacuous: the fixture's two verdicts really differ, so
                         the assertion below can fail")

                    ;; THE COMPOSING ENTER — the input method's, not the
                    ;; control's. Then the liveness probe, then the commit.
                    (press! input "Enter" composing-press)
                    (press! input ta/previous-key (plain))
                    (press! input "Enter" (plain))
                    (settled #(seq (selections)) "the plain Enter's commit lands")))
                (.then
                  (fn [_]
                    (is (= selections-after-plain-enter (count (selections)))
                        "exactly one selection reached the application")
                    (is (= committed-after-withheld-enter (first (selections)))
                        "and it is the option the ArrowUp moved back to — which it
                         could only do because the composing Enter left the record
                         alive, so this value IS the withholding")
                    (is (not= committed-if-enter-were-taken (first (selections)))
                        "and specifically NOT what a control that took the composing
                         Enter would have committed")
                    (is (= committed-after-withheld-enter
                           (.-value (ms/q container (str "#" input-id))))
                        "with the committed value back on the live node")
                    (is (= (:value (nth results first-index))
                           (first (selections)))
                        "which is the FIRST option — the one the highlight was
                         moved off and then back onto, read out of the answer set
                         rather than out of a second copy of it")
                    (is (nil? (record))
                        "and the commit retired the record, so the list went with it")
                    (release!)))
                (.then (fn [_] (teardown-clean! container root))))))
        (.catch (report-failure! container root))
        (.then (fn [_] (done))))))

(deftest fh-ctrl-020-a-composing-enter-commits-nothing-in-a-real-browser
  (testing "Per FH-CTRL-020: an Enter carrying the STANDARD `isComposing`
            flag — and nothing else — commits nothing, while the plain
            Enter behind it does. `keyCode` here is the plain Enter's, so
            the press CANNOT be withheld by the 229 sentinel the sibling row
            owns; the flag is the only thing between it and a commit."
    (if-not (browser?)
      (ms/skip! "the browser job runs the composition assertions")
      (async done
        (a-withheld-enter! {:composing? true
                            :key-code   (:plain-key-code ctrl-020)}
                           done)))))

(deftest fh-ctrl-020-a-keycode-229-enter-commits-nothing-with-iscomposing-false
  (testing "Per FH-CTRL-020: the COMPATIBILITY FALLBACK, on its own.
            `keyCode` 229 is what the UI Events legacy algorithm assigns
            while an input method is processing a keydown, and `isComposing`
            here is FALSE — the pairing WebKit bug 165004 reported, fixed in
            April 2026 (bug 311717) and still live on deployed engines. This
            is the press the compatibility claim is actually about: with
            both signals set on one event, an implementation reading only
            `isComposing` — the one the fallback exists to beat — would stay
            green."
    (if-not (browser?)
      (ms/skip! "the browser job runs the composition assertions")
      (async done
        (a-withheld-enter! {:composing? false
                            :key-code   (:composing-key-code ctrl-020)}
                           done)))))

;; ===========================================================================
;; FH-CTRL-020 — a pointer choice, and where focus is afterwards
;; ===========================================================================

(deftest fh-ctrl-020-a-pointer-choice-commits-and-leaves-focus-on-the-input
  (testing "Per FH-CTRL-020: clicking an option commits THAT option — not
            the highlighted one — and the input still has focus afterwards,
            so the user carries on typing where they were. The options are
            not focusable, which is what makes that true by construction
            rather than by a restore; asserting it is what stops a later
            change from making them focusable and breaking it silently."
    (if-not (browser?)
      (ms/skip! "the browser job runs the pointer assertions")
      (async done
        (let [{:keys [query results option-ids active-index first-index first-value]}
              ctrl-020
              _ (seed!)
              [container root] (stage!)]
          (-> (render! root)
              (.then (fn [_] (open-with-results! container query results)))
              (.then
                (fn [input]
                  (.focus input)
                  ;; Move the highlight AWAY from the option about to be
                  ;; clicked, so a row that committed the highlight instead
                  ;; of the click could not be green.
                  (press! input ta/next-key (plain))
                  (-> (settled #(= active-index (:active (record))) "the arrow lands")
                      (.then
                        (fn [_]
                          (click! (ms/q container (str "#" (nth option-ids first-index))))
                          (settled #(seq (selections)) "the click commits")))
                      (.then
                        (fn [_]
                          (is (= [first-value] (selections))
                              "the option that was CLICKED committed, not the one
                               that was highlighted")
                          (is (identical? input (.-activeElement js/document))
                              "and focus is still on the input")
                          (is (nil? (record))
                              "the commit retired the record, so the list is gone
                               with it")
                          (teardown-clean! container root))))))
              (.catch (report-failure! container root))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; FH-CTRL-020 — dismissal, from both ends, committing nothing
;; ===========================================================================

(deftest fh-ctrl-020-escape-and-a-platform-dismissal-both-close-without-committing
  (testing "Per FH-CTRL-020: the top layer is driven from BOTH ends, and the
            record and the platform never disagree about whether the list is
            open.

            The PLATFORM goes first, in both directions. `hidePopover` is
            what an Escape and a light dismiss both come down to inside the
            browser, and `showPopover` is what a `popovertarget` invoker
            comes down to; the substrate writes no application state on the
            browser's behalf for either, so each is reconciled by the
            `toggle` event reaching the AUTHOR's handler. The record follows
            the platform out of the top layer and back into it, and neither
            move commits anything.

            The CONTROL goes last: an Escape keydown resolves to `:cancel`
            through the same pure grammar Enter goes through, the substrate
            takes the list out of the top layer behind it, and the draft
            SURVIVES — the user is still editing, only the suggestions went
            away.

            ## Why the platform's moves are the WAITS, and why Escape is last

            A `toggle` report is QUEUED by the platform, not delivered
            synchronously, and it lands on the author's handler through the
            ordinary event queue. So a row that moves on from one transition
            before that transition's report has been PROCESSED leaves a
            report in flight over the next one — and `dispatch-sync`, which
            jumps the queue the report is waiting in, then loses to it. That
            is the shape this row failed in under CI load: a re-open
            dispatched over an unprocessed dismissal report was closed again
            the moment the report drained.

            Waiting on `:open?` is what makes it deterministic, because
            after `open-with-results!` only a REPORT can move that flag —
            the platform's calls below write no application state. Settling
            on the flag therefore proves the report was delivered AND
            consumed, and the platform coalesces its own queued reports, so
            nothing is left in flight behind it. The control's Escape is
            last precisely because nothing follows it and nothing has to
            wait on its report."
    (if-not (browser?)
      (ms/skip! "the browser job runs the dismissal assertions")
      (async done
        (let [{:keys [query results listbox-id]} ctrl-020
              _ (seed!)
              [container root] (stage!)]
          (-> (render! root)
              (.then (fn [_] (open-with-results! container query results)))
              (.then
                (fn [input]
                  (.focus input)
                  (let [lst (ms/q container (str "#" listbox-id))]
                    ;; THE PLATFORM'S DISMISSAL — the one thing an Escape
                    ;; and a light dismiss both come down to inside the
                    ;; browser.
                    (.hidePopover lst)
                    (-> (settled #(false? (:open? (record)))
                                 "the platform's own dismissal reaches the author's
                                  handler and the record follows it")
                        (.then
                          (fn [_]
                            (is (not (.matches lst ":popover-open"))
                                "the list left the top layer")
                            (is (empty? (selections))
                                "and a browser-initiated dismissal commits nothing")
                            (is (= query (:query (record)))
                                "and discards nothing")

                            ;; THE PLATFORM'S OPENING, for the same reason:
                            ;; a `popovertarget` invoker promotes a popover
                            ;; without asking the application either.
                            (.showPopover lst)
                            (settled #(true? (:open? (record)))
                                     "and the platform's own opening reaches it too —
                                      the reconciliation runs in both directions")))
                        (.then
                          (fn [_]
                            (is (.matches lst ":popover-open")
                                "non-vacuous: the list really is back in the top
                                 layer, so the Escape below has something to close")

                            ;; THE CONTROL'S DISMISSAL.
                            (press! input "Escape" (plain))
                            (settled #(and (false? (:open? (record)))
                                           (not (.matches lst ":popover-open")))
                                     "Escape closes the list, and the substrate takes
                                      it out of the top layer behind the record")))
                        (.then
                          (fn [_]
                            (is (empty? (selections))
                                "nothing was committed on the way out")
                            (is (= query (:query (record)))
                                "while the draft SURVIVED — a dismissal is not a
                                 discard")
                            (is (= query (.-value input))
                                "and it is still what the live node shows")
                            (release!)))
                        (.then (fn [_] (teardown-clean! container root)))))))
              (.catch (report-failure! container root))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; FH-CTRL-020 — a stale reply, on a real clock, and total release
;; ===========================================================================

(deftest fh-ctrl-020-a-superseded-reply-is-inert-against-a-live-control
  (testing "Per FH-CTRL-020: the headless rows prove the fences DECIDE
            correctly. This one proves the decision survives a real clock, a
            real debounce and a live node: the user types on while an answer
            is in flight, the slow answer arrives, and it changes neither
            the list nor — the thing that actually corrupts a typeahead —
            the text the user is in the middle of typing.

            Then the owner's own event releases the control, and the last
            assertion is an ABSENCE read after teardown."
    (if-not (browser?)
      (ms/skip! "the browser job runs the live-clock assertions")
      (async done
        (let [{:keys [query later-query results listbox-id]} ctrl-020
              _ (seed!)
              [container root] (stage!)
              input (atom nil)]
          (-> (render! root)
              (.then (fn [_] (open-with-results! container query results)))
              (.then
                (fn [node]
                  (reset! input node)
                  (let [slow (last (requests))]
                    ;; The user keeps typing. The keystroke supersedes the
                    ;; answer already on the record.
                    (ms/live!)
                    (ms/type-string! node (subs later-query (count query)))
                    (-> (settled #(= later-query (:query (record)))
                                 "the keystroke reaches the record")
                        (.then
                          (fn [_]
                            (is (= later-query (.-value node))
                                "non-vacuous: the live node carries what was typed")
                            ;; And NOW the slow answer to the old question lands.
                            (ms/act #(rf/dispatch-sync
                                       (conj (vec (:reply-to slow)) {:results results})
                                       {:frame fid}))))
                        (.then
                          (fn [_]
                            (is (= later-query (.-value node))
                                "the late reply did not touch the text the user is
                                 typing — the corruption every naive typeahead has")
                            (is (empty? (selections))
                                "and committed nothing")
                            (-> (release!)
                                (.then (fn [_]
                                         (settled #(nil? (record))
                                                  "the owner's release empties the record"))))))
                        (.then
                          (fn [_]
                            (is (nil? (record)) "the record is gone")
                            (is (not (.matches (ms/q container (str "#" listbox-id))
                                               ":popover-open"))
                                "and the list is out of the top layer with it")
                            (teardown-clean! container root)))))))
              (.catch (report-failure! container root))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; FH-CTRL-020 — the retry, as something a user can actually reach
;; ===========================================================================

(deftest fh-ctrl-020-a-retry-is-a-real-button-and-supersedes-the-failure-it-retries
  (testing "Per FH-CTRL-020: the structural half proves the retry DECIDES
            correctly — a new token, the failed request's late answer inert.
            What a browser adds is the half a value cannot carry: that the
            decision is REACHABLE. The `retry` part is a real `<button>`
            that the failure puts on the page and nothing else does, and a
            real pointer click on it is what asks again. A retry no pointer
            can reach is not a retry, and a fence proven only by a
            hand-dispatched event is a fence around a door nobody found.

            So this row never names `:kit.ui.typeahead/retried`. It clicks.

            Then the failed request answers LATE, against a live mounted
            control — which is the arrangement the fence exists for, and the
            one where getting it wrong resurrects a stale answer on screen
            rather than merely in a map. The two answer sets differ in
            LENGTH as well as content, so the option ids below tell them
            apart: the retry's single option is present and the failed
            request's second one is absent."
    (if-not (browser?)
      (ms/skip! "the browser job runs the live retry")
      (async done
        (let [{:keys [query results retry-results error-message error-part
                      option-ids listbox-id]} ctrl-020
              _       (seed!)
              [container root] (stage!)
              retry-q (str "[data-part=\"" error-part "\"]")
              failed  (atom nil)
              retried (atom nil)]
          (-> (render! root)
              (.then (fn [_] (ask! container query)))
              (.then
                (fn [_]
                  (reset! failed (last (requests)))
                  (is (nil? (ms/q container retry-q))
                      "non-vacuous: nothing offers a retry while nothing has
                       failed — the part is minted by the state, not rendered
                       hidden")
                  (ms/act #(answer! {:results [] :error error-message}))))
              (.then
                (fn [_]
                  (settled #(some? (ms/q container retry-q))
                           "the failure puts a real retry button on the page")))
              (.then
                (fn [_]
                  (is (= (str "Search failed: " error-message)
                         (ms/text-of container "[data-part=\"status\"]"))
                      "beside a status region that says what went wrong")
                  ;; THE CLICK. Not the event — the button.
                  (ms/live!)
                  (click! (ms/q container retry-q))
                  (settled #(and (nil? (:error (record)))
                                 (not= (:reply-to @failed) (:reply-to (last (requests)))))
                           "the click clears the failure and asks again, under a
                            NEW correlation")))
              (.then
                (fn [_]
                  (reset! retried (last (requests)))
                  (is (nil? (ms/q container retry-q))
                      "and the button goes with the error that minted it")
                  ;; The failed request answers LATE, against a live control.
                  (ms/act #(rf/dispatch-sync
                             (conj (vec (:reply-to @failed)) {:results results})
                             {:frame fid}))))
              (.then
                (fn [_]
                  (is (not (.matches (ms/q container (str "#" listbox-id))
                                     ":popover-open"))
                      "the retried-past request's answer opens nothing — the
                       resurrection this fence exists to prevent")
                  (ms/act #(rf/dispatch-sync
                             (conj (vec (:reply-to @retried)) {:results retry-results})
                             {:frame fid}))))
              (.then
                (fn [_]
                  (settled #(.matches (ms/q container (str "#" listbox-id))
                                      ":popover-open")
                           "while the retry's own answer opens the list")))
              (.then
                (fn [_]
                  (is (= (:label (first retry-results))
                         (ms/text-of container (str "#" (nth option-ids 0))))
                      "showing the RETRY's answer")
                  (is (nil? (ms/q container (str "#" (nth option-ids 1))))
                      "and only it — the failed request answered with two
                       options and neither of them is on the page")
                  (release!)))
              (.then (fn [_] (teardown-clean! container root)))
              (.catch (report-failure! container root))
              (.then (fn [_] (done)))))))))
