(ns re-frame.freehand.pilot-field-cljs-test
  "THE FIRST PILOT, judged: the fitness harness's CASE A §A.2 requirements
  against [[re-frame.freehand.pilot-field]], one requirement per `deftest`,
  on both hosts and in BOTH execution modes.

  This is not a substrate law suite. Nothing here pins a Freehand
  contract; every assertion is a consumer's claim about a consumer's
  components, and the pass/fail column it fills in is
  `docs/design/freehand/studio/fitness-harness.md` §A.2 (R-A1 … R-A12).
  Rows whose evidence is a live DOM — the caret, an IME composition, the
  synchronous round trip as a user-visible fact — are OWED to
  `pilot-field-dom-cljs-test` and say so here rather than being restated
  structurally, because a structural stand-in for a caret proves nothing.

  ## Both modes, one body of assertions

  Every test runs its assertions over [[modes]]: the interpreted
  declarations and their `{:compiled true}` twins in
  `pilot-field-compiled`. Same call sites, same props, same expected
  values — the view-id namespace is the only mechanical substitution, and
  it is a difference living in another FILE makes, not one promotion
  makes. The library's dataflow is registered ONCE for both, because a
  `reg-sub` and a `reg-event` know nothing about execution mode.

  ## Why the render goes through a capture, and why that is a FINDING

  `t/render` is the blessed public structural surface (API manifest tier
  `:testing`), and it walks a body without opening a render candidate. So
  `t/render` on ANY view that calls `v/sub` raises
  `:rf.error/view-read-outside-render` — which is every stateful view a
  form is made of. [[render-on!]] composes the internal
  `cell/candidate` + `cell/with-capture` to supply the missing candidate,
  exactly as the substrate's own controller suites do. That composition is
  NOT part of the public door; a consumer writing these tests today has
  to reach past it. Recorded in the PR body as the pilot's principal
  ergonomic finding, and reproduced verbatim by
  [[t-render-alone-cannot-render-a-state-reading-view]] below."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.walk :as walk]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.pilot-field :as pilot]
            [re-frame.freehand.pilot-field-compiled :as promoted]
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; ===========================================================================
;; Harness
;; ===========================================================================

(def ^:private fid :rf/default)

(def modes
  "The two execution modes, keyed by the roster each holds. Every
  requirement below is asserted against both."
  {:interpreted pilot/by-name
   :compiled    promoted/by-name})

(defn- form
  "The call form for `view` in `mode` — the SAME props map either way."
  [mode view props]
  [(get-in modes [mode view]) props])

(defn- init! []
  (pilot/register!)
  (pilot/register-app!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :init-fn init!}))

(defn- app-db [] (frame/frame-app-db-value fid))
(defn- seed!  [db] (frame/replace-app-db! fid db))
(defn- send!  [ev] (rf/dispatch-sync ev {:frame fid}))

(defn- render-on!
  "Render `f` under `cell` — one candidate, one structural walk, NO commit
  — and answer the tree.

  The `cell/candidate` + `cell/with-capture` pair is the internal
  composition `t/render` does not supply; see the namespace docstring."
  [c f]
  (cell/with-capture (cell/candidate c fid) (fn [] (t/render f))))

(defn- render!
  "Render `f` on a fresh cell."
  [f]
  (render-on! (cell/cell :acme.ui/probe) f))

(defn- nodes
  "Every ELEMENT node under `tree` matching `pred`, in document order.
  Text leaves are excluded, so a predicate may read attributes freely."
  [tree pred]
  (t/find-all tree #(and (map? %) (contains? % :tag) (pred %))))

(defn- node
  "The first element node under `tree` matching `pred`, or nil."
  [tree pred]
  (first (nodes tree pred)))

(defn- component-node
  "The root node of component `component` — the node carrying its
  `data-component` scope."
  [tree component]
  (node tree #(= component (:data-component (:attrs %)))))

(defn- part-node
  "The node addressed by `part-id` inside the nearest `component` scope —
  the way a stylesheet reaches a named region of a component."
  [tree component part-id]
  (node (component-node tree component) #(= part-id (:data-part (:attrs %)))))

(defn- field-node
  "The `<label>` root of the controlled field named `nm`."
  [tree nm]
  (node tree #(and (= "acme/field" (:data-component (:attrs %)))
                   (= nm (:data-field (:attrs %))))))

(defn- buffered-node
  [tree nm]
  (node tree #(and (= "acme/buffered-field" (:data-component (:attrs %)))
                   (= nm (:data-field (:attrs %))))))

(defn- control-of
  "The native control inside a component root."
  [root]
  (node root #(= "control" (:data-part (:attrs %)))))

(defn- error-of
  [root]
  (node root #(= "error" (:data-part (:attrs %)))))

(defn- intent
  "The event vector node `n` carries under `prop`."
  [n prop]
  (get (t/attrs n) prop))

(defn- fire!
  "Dispatch the intent `n` carries under `prop`, with `args` filling the
  projection positions a live payload would."
  [n prop & args]
  (let [ev (intent n prop)]
    (send! (if (seq args) (into (vec (butlast ev)) args) ev))))

(defn- line-form  [mode id]  (form mode :invoice-line {:id id}))
(defn- lines-form [mode ids] (form mode :invoice-lines {:ids ids}))

(defn- shown
  "What the control named `nm` displays in `tree`."
  [tree nm]
  (:value (t/attrs (control-of (field-node tree nm)))))

(defn- shown-buffered
  [tree nm]
  (:value (t/attrs (control-of (buffered-node tree nm)))))

(defn- type!
  "Type `text` into the controlled field named `nm` — the intent its
  `:on-input` site carries, fired with the live payload the browser would
  have supplied."
  [mode id nm text]
  (fire! (control-of (field-node (render! (line-form mode id)) nm)) :on-input text))

(defn- blur!
  [mode id nm]
  (fire! (control-of (field-node (render! (line-form mode id)) nm)) :on-blur))

(defn- seed-line!
  ([id] (seed-line! id {}))
  ([id overrides]
   (seed! {:invoice {id (merge {:description "Consulting"
                                :quantity    "2"
                                :unit-price  "150"
                                :account     "4000"
                                :reference   "REF-1"
                                :reference-revision 0}
                               overrides)}})))

(defn- seed-lines! [ids]
  (seed! {:invoice (into {} (map (fn [id] [id {:description "Consulting"
                                               :quantity    "2"
                                               :unit-price  "150"
                                               :account     "4000"
                                               :reference   (str "REF-" id)
                                               :reference-revision 0}]))
                         ids)}))

(defn- record
  "The library's controller record at `address`."
  [address]
  (get-in (app-db) [pilot/records-root [pilot/buffered-kind address]]))

(defn- moved-paths
  "The leaf paths at which two values differ, in a stable order — what an
  epoch diff shows a tool, and the honest unit for 'how much state moved'."
  ([a b] (moved-paths a b []))
  ([a b path]
   (cond
     (= a b)                 []
     (and (map? a) (map? b)) (into []
                                   (mapcat #(moved-paths (get a % ::absent)
                                                         (get b % ::absent)
                                                         (conj path %)))
                                   (sort-by str (distinct (concat (keys a) (keys b)))))
     :else                   [path])))

(def ^:private modes-list [:interpreted :compiled])

(defn- each-mode
  "Run `f` for each mode, seeding first. The one place the two-mode sweep
  is spelled, so every requirement below is proven twice by construction."
  [f]
  (doseq [mode modes-list]
    (testing (str "mode " mode)
      (f mode))))

;; ===========================================================================
;; The finding this suite had to work around, reproduced
;; ===========================================================================

(deftest t-render-alone-cannot-render-a-state-reading-view
  (testing "The blessed public structural surface cannot render a view
            that reads state. `t/render` walks a body but opens no render
            candidate, so the first `v/sub` raises
            `:rf.error/view-read-outside-render` — and every form control
            that displays application state is such a view. The internal
            candidate/capture pair this file composes is what closes the
            gap; a consumer has no published way to do it."
    (seed-line! 1)
    (each-mode
      (fn [mode]
        (let [thrown (try (t/render (line-form mode 1))
                          nil
                          (catch #?(:clj Throwable :cljs :default) e
                            (:rf.error/id (ex-data e))))]
          (is (= :rf.error/view-read-outside-render thrown)
              "t/render alone refuses the state-reading view")
          (is (some? (render! (line-form mode 1)))
              "non-vacuous: the SAME form renders once a candidate exists"))))))

;; ===========================================================================
;; R-A1 — same-tick echo
;; ===========================================================================

(deftest r-a1-every-editing-site-is-inside-the-controlled-input-door
  (testing "R-A1 (structural half). The door is one predicate over five
            final-normalized facts, and the four this suite can see are
            structural: a supported control tag, `value` PRESENT, a
            firing attribute that normalizes to `onInput`, and a handler
            whose outcome is synchronously known — a LITERAL event
            vector. Every editing site the pilot renders carries all four,
            in both modes.

            The library owns that literal vector deliberately. Forwarding
            the caller's event vector into the `:on-input` position would
            leave the site a DYNAMIC handler expression the compiled
            analyzer cannot classify. The door is not what that costs —
            it is decided at commit from the runtime handler value, in
            both modes — but the EVIDENCE is: the site would be opaque,
            its intent absent from the compiled manifest, and none of the
            four facts below assertable before it fires. Carrying the
            caller's event as an ARGUMENT inside the library's own literal
            vector keeps the site's proof static and the prefix a runtime
            value.

            The user-visible half — that a keystroke's state change lands
            before the browser restores the node — is owed to
            `pilot-field-dom-cljs-test`."
    (seed-line! 1)
    (each-mode
      (fn [mode]
        (let [tree     (render! (line-form mode 1))
              controls (nodes tree #(and (= :input (:tag %))
                                         (contains? (t/attrs %) :on-input)))]
          (is (= 5 (count controls))
              "four controlled fields and one buffered field are editing sites")
          (doseq [c controls]
            (let [a  (t/attrs c)
                  ev (:on-input a)]
              (is (= :input (:tag c)) "a tag whose value React restores")
              (is (contains? a :value) "`value` present — controlled by PRESENCE")
              (is (vector? ev) "the handler's outcome is one event vector, known now")
              (is (keyword? (first ev)) "headed by a literal event id")
              (is (= "acme.ui" (subs (namespace (first ev)) 0 7))
                  "and the id is the LIBRARY's, so the site stays literal")))
          (is (some #(= :acme.ui.buffered/edited (first (:on-input (t/attrs %))))
                    (nodes tree #(= :input (:tag %))))
              "non-vacuous: the buffered control's own site is present too"))))))

(deftest r-a1-the-caller-never-writes-a-projection-marker
  (testing "R-A1, ergonomic half. The call site names an event PREFIX and
            nothing else; the live value arrives as its last argument
            because the library's site appends the marker. A caller that
            had to write `::v/value` at every call would be writing the
            library's implementation detail into its own code."
    (seed-line! 1)
    (each-mode
      (fn [mode]
        (let [tree (render! (line-form mode 1))
              c    (control-of (field-node tree "quantity"))
              ev   (:on-input (t/attrs c))]
          (is (= [:acme.ui.field/changed [:acme.invoice/field-edited 1 :quantity] ::v/value]
                 ev)
              "the caller's prefix rides as data inside the library's site")
          (send! (v/materialize-event ev {::v/value "9"}))
          (is (= "9" (get-in (app-db) [:invoice 1 :quantity]))
              "and the materialized event reaches the caller's own handler"))))))

;; ===========================================================================
;; R-A2 — caret / selection / IME
;; ===========================================================================

(deftest r-a2-is-owed-to-the-browser
  (testing "R-A2 is caret position, range selection and IME composition
            surviving a value the substrate reasserts. None of that has a
            structural shadow: a tree cannot hold a caret, and asserting
            some proxy for one here would be the false green this
            programme keeps catching. What IS assertable headlessly is
            the precondition — the pilot never remounts to reset, because
            it holds no host state to reset. That is asserted by R-A3's
            'no record was deleted' row and by
            `r-a9-rendering-is-free-of-consequence` below; the caret and
            the composition are proven in
            `pilot-field-dom-cljs-test`."
    (is true "R-A2 evidence lives in the browser suite (stated, not stubbed)")))

;; ===========================================================================
;; R-A3 — same-value rejection visibility
;; ===========================================================================

(deftest r-a3-a-rejection-lands-even-though-the-value-never-moved
  (testing "R-A3. The caller refuses a committed draft by advancing the
            reference revision and reasserting nothing. The value before
            the decision and the value after it are IDENTICAL — which is
            exactly the case value-equality is blind to, and the defect
            re-com documents at input_text.cljs:91-94 — yet the draft
            leaves the display at once."
    (each-mode
      (fn [mode]
        (seed-line! 1)
        (let [address [:invoice 1 :reference]
              draft   "REF-BAD"]
          (is (= "REF-1" (shown-buffered (render! (line-form mode 1)) "reference"))
              "the control starts on the caller's baseline")

          (fire! (control-of (buffered-node (render! (line-form mode 1)) "reference"))
                 :on-input draft)
          (is (= draft (shown-buffered (render! (line-form mode 1)) "reference"))
              "the draft is displayed")
          (is (= draft (:draft (record address))) "and is ordinary frame data")

          (let [before (get-in (app-db) [:invoice 1 :reference])]
            (send! [:acme.invoice/reference-rejected 1 "That reference is not on file."])
            (is (= before (get-in (app-db) [:invoice 1 :reference]))
                "non-vacuous: the caller's VALUE did not move at all")
            (is (= "REF-1" (shown-buffered (render! (line-form mode 1)) "reference"))
                "yet the baseline is back on screen")
            (is (not= draft "REF-1")
                "non-vacuous: the display really did move off the draft"))

          (testing "and the superseded record is INELIGIBLE, not erased —
                    keeping the stamp is what lets a tool name the
                    generation that orphaned it"
            (is (= {:reset-key 0 :draft draft} (record address)))))))))

(deftest r-a3-an-external-value-change-is-not-a-rejection
  (testing "R-A3's counter-case. A value that moves while the generation
            holds is an OBSERVATION, not a decision about the user's
            edit, so a live draft continues. Without this row the
            rejection above would be indistinguishable from 'any caller
            write clobbers the draft'."
    (each-mode
      (fn [mode]
        (seed-line! 1)
        (fire! (control-of (buffered-node (render! (line-form mode 1)) "reference"))
               :on-input "REF-DRAFT")
        (frame/replace-app-db! fid (assoc-in (app-db) [:invoice 1 :reference] "REF-ELSEWHERE"))
        (is (= "REF-DRAFT" (shown-buffered (render! (line-form mode 1)) "reference"))
            "the draft survives a value change that made no baseline decision")))))

;; ===========================================================================
;; R-A4 — draft vs canonical, without reformat-jitter
;; ===========================================================================

(deftest r-a4-raw-text-and-canonical-value-coexist
  (testing "R-A4. The field being edited echoes what was typed, verbatim;
            every other reader sees the derived value. Typing `\"1.\"`
            leaves `\"1.\"` in the control — nothing reformats under the
            caret — while the canonical read is nil until the text becomes
            a number, and the derived total is nil with it."
    (each-mode
      (fn [mode]
        (seed-line! 1)
        (type! mode 1 "quantity" "1.")
        (is (= "1." (shown (render! (line-form mode 1)) "quantity"))
            "the control echoes the keystrokes verbatim")
        (is (nil? (rf/subscribe-once [:acme.invoice/quantity-canonical 1] {:frame fid}))
            "the canonical value is absent, not a guess")
        (is (nil? (rf/subscribe-once [:acme.invoice/line-total 1] {:frame fid}))
            "and nothing derived from it invents a number")

        (type! mode 1 "quantity" "1.5")
        (is (= "1.5" (shown (render! (line-form mode 1)) "quantity")))
        (is (= 1.5 (rf/subscribe-once [:acme.invoice/quantity-canonical 1] {:frame fid})))
        (is (= 225.0 (rf/subscribe-once [:acme.invoice/line-total 1] {:frame fid}))
            "and the derived total follows")
        (is (= (str (rf/subscribe-once [:acme.invoice/line-total 1] {:frame fid}))
               (t/text (part-node (render! (line-form mode 1))
                                  "acme/invoice-line" "total")))
            "which the view reads as an ordinary value — compared against the
             derived read rather than a literal, because `(str 225.0)` is
             \"225.0\" on the JVM and \"225\" in ClojureScript, and a `.cljc`
             view that prints a raw double is host-sensitive by construction")))))

;; ===========================================================================
;; R-A5 — validation display gated by touch or submit-attempt
;; ===========================================================================

(deftest r-a5-errors-appear-only-after-touch-or-submit-attempt
  (testing "R-A5, per field and per instance. A blank description is a
            problem from the first render; it is DISPLAYED only once the
            user has left that field, or once submit has been attempted —
            and touching one field says nothing about another."
    (each-mode
      (fn [mode]
        (seed-line! 1 {:description ""})
        (is (nil? (error-of (field-node (render! (line-form mode 1)) "description")))
            "untouched and unsubmitted: no error region at all")

        (blur! mode 1 "description")
        (let [tree (render! (line-form mode 1))
              node (field-node tree "description")]
          (is (= "A description is required." (t/text (error-of node)))
              "blur reveals the field's own problem")
          (is (= "true" (:aria-invalid (t/attrs (control-of node))))
              "and the control is marked invalid for assistive technology")
          (is (= (:id (t/attrs (error-of node)))
                 (:aria-describedby (t/attrs (control-of node))))
              "with the message wired to the control by id"))

        (testing "a second blank field stays quiet until it is touched too"
          (frame/replace-app-db! fid (assoc-in (app-db) [:invoice 1 :account] ""))
          (is (nil? (error-of (field-node (render! (line-form mode 1)) "account")))
              "untouched, still quiet")
          (send! [:acme.invoice/submit-attempted 1])
          (is (= "An account is required."
                 (t/text (error-of (field-node (render! (line-form mode 1)) "account"))))
              "a submit attempt reveals every field at once"))))))

(deftest r-a5-gating-is-per-instance
  (testing "R-A5. Two lines on one page: touching a field on the first
            reveals nothing on the second. The touched set is addressed by
            the line's own identity, so there is no shared 'form state' to
            leak across instances."
    (each-mode
      (fn [mode]
        (seed-lines! [1 2])
        (frame/replace-app-db! fid (-> (app-db)
                                       (assoc-in [:invoice 1 :description] "")
                                       (assoc-in [:invoice 2 :description] "")))
        (send! [:acme.invoice/field-blurred 1 :description])
        (let [tree  (render! (lines-form mode [1 2]))
              roots (nodes tree #(= "acme/invoice-line" (:data-component (:attrs %))))]
          (is (= 2 (count roots)) "non-vacuous: both lines rendered")
          (is (some? (error-of (field-node (first roots) "description")))
              "the touched line shows its problem")
          (is (nil? (error-of (field-node (second roots) "description")))
              "the untouched line shows nothing"))))))

;; ===========================================================================
;; R-A6 — a derived gate the view and the handler both read
;; ===========================================================================

(deftest r-a6-the-submit-gate-cannot-go-stale
  (testing "R-A6. `:acme.invoice/can-submit?` is defined once. The button
            disables off it and the submit handler READS it — through the
            frame-explicit one-shot read, not by recomputing the rule — so
            the two can never disagree about whether the form is
            submittable."
    (each-mode
      (fn [mode]
        (seed-line! 1 {:quantity "nope"})
        (let [button (part-node (render! (line-form mode 1)) "acme/invoice-line" "submit")]
          (is (true? (:disabled (t/attrs button))) "the view refuses")
          (is (false? (rf/subscribe-once [:acme.invoice/can-submit? 1] {:frame fid}))
              "the gate agrees"))
        (send! [:acme.invoice/submit-attempted 1])
        (is (nil? (::pilot/submitted (app-db)))
            "and the handler refused it too — one rule, two readers")

        (send! [:acme.invoice/field-edited 1 :quantity "3"])
        (let [button (part-node (render! (line-form mode 1)) "acme/invoice-line" "submit")]
          (is (false? (:disabled (t/attrs button))) "a valid line enables the button")
          (is (true? (rf/subscribe-once [:acme.invoice/can-submit? 1] {:frame fid}))))
        (send! [:acme.invoice/submit-attempted 1])
        (is (= [1] (::pilot/submitted (app-db)))
            "and the same attempt now submits — non-vacuous in both directions")))))

;; ===========================================================================
;; R-A7 — the commit / cancel / blur key protocol
;; ===========================================================================

(deftest r-a7-enter-commits-escape-reverts-blur-commits
  (testing "R-A7. Three interactions, no component-local state machine:
            Enter and Escape ride ONE registered event carrying the live
            `KeyboardEvent.key` as data, and blur is an ordinary intent.
            Every one of them is decided in the handler against committed
            state."
    (each-mode
      (fn [mode]
        (let [ctl (fn [] (control-of (buffered-node (render! (line-form mode 1)) "reference")))]

          (testing "Enter commits the draft to the caller"
            (seed-line! 1)
            (fire! (ctl) :on-input "REF-ENTER")
            (fire! (ctl) :on-key-down "Enter")
            (is (= "REF-ENTER" (get-in (app-db) [:invoice 1 :reference]))
                "the caller's own event received the draft")
            (is (nil? (record [:invoice 1 :reference])) "and the session retired"))

          (testing "Escape reverts to the caller's baseline"
            (seed-line! 1)
            (fire! (ctl) :on-input "REF-ESCAPE")
            (fire! (ctl) :on-key-down "Escape")
            (is (= "REF-1" (get-in (app-db) [:invoice 1 :reference]))
                "the caller's value is untouched")
            (is (= "REF-1" (shown-buffered (render! (line-form mode 1)) "reference"))
                "and the baseline is displayed again"))

          (testing "an ordinary character key changes nothing"
            (seed-line! 1)
            (fire! (ctl) :on-input "REF-X")
            (let [before (app-db)]
              (fire! (ctl) :on-key-down "x")
              (is (= before (app-db))
                  "the key site fires on every key and settles a no-op for most")))

          (testing "blur commits"
            (seed-line! 1)
            (fire! (ctl) :on-input "REF-BLUR")
            (fire! (ctl) :on-blur)
            (is (= "REF-BLUR" (get-in (app-db) [:invoice 1 :reference]))))

          (testing "and Escape BEATS a blur already on its way — the
                    cancelled draft is not resurrected by the blur that
                    follows it"
            (seed-line! 1)
            (fire! (ctl) :on-input "REF-GONE")
            (let [late-blur (intent (ctl) :on-blur)]
              (is (some? late-blur) "non-vacuous: a blur really was in flight")
              (fire! (ctl) :on-key-down "Escape")
              (send! late-blur))
            (is (= "REF-1" (get-in (app-db) [:invoice 1 :reference]))
                "the late blur found no live edit")
            (is (= 0 (::pilot/accepted (app-db) 0))
                "and the caller's accept event never fired")))))))

(deftest r-a7-a-repeated-commit-is-idempotent
  (testing "R-A7. A double blur — or a blur behind an Enter — dispatches
            the caller's event exactly once, because the second commit
            consults committed state and finds no record."
    (each-mode
      (fn [mode]
        (seed-line! 1)
        (let [ctl (control-of (buffered-node (render! (line-form mode 1)) "reference"))]
          (fire! ctl :on-input "REF-ONCE")
          (let [blur (intent ctl :on-blur)]
            (send! blur)
            (send! blur)
            (send! blur)))
        (is (= "REF-ONCE" (get-in (app-db) [:invoice 1 :reference])))
        (is (= 1 (::pilot/accepted (app-db)))
            "exactly one caller event for three fires")))))

;; ===========================================================================
;; R-A8 — N instances, zero collisions, zero coordination
;; ===========================================================================

(deftest r-a8-two-lines-hold-independent-drafts-and-values
  (testing "R-A8. The same two components, twice on one page, with no
            coordination between them. Each buffered record is addressed
            by the domain identity that owns it, so an edit at one line
            moves one record and leaves the other showing its own
            baseline."
    (each-mode
      (fn [mode]
        (seed-lines! [1 2])
        (let [roots (fn [] (nodes (render! (lines-form mode [1 2]))
                                  #(= "acme/invoice-line" (:data-component (:attrs %)))))]
          (fire! (control-of (buffered-node (first (roots)) "reference"))
                 :on-input "DRAFT-1")
          (fire! (control-of (field-node (first (roots)) "description"))
                 :on-input "Line one")

          (is (= "DRAFT-1" (:value (t/attrs (control-of (buffered-node (first (roots)) "reference")))))
              "line 1 shows its draft")
          (is (= "REF-2" (:value (t/attrs (control-of (buffered-node (second (roots)) "reference")))))
              "line 2 still shows its own baseline")
          (is (= "Line one" (:value (t/attrs (control-of (field-node (first (roots)) "description"))))))
          (is (= "Consulting" (:value (t/attrs (control-of (field-node (second (roots)) "description"))))))

          (is (= 1 (count (get (app-db) pilot/records-root)))
              "exactly one controller record exists")
          (is (some? (record [:invoice 1 :reference])) "and it belongs to line 1")
          (is (nil? (record [:invoice 2 :reference])))

          (testing "rejecting line 1's draft leaves line 2 untouched"
            (send! [:acme.invoice/reference-rejected 1 "That reference is not on file."])
            (is (= "REF-1" (:value (t/attrs (control-of (buffered-node (first (roots)) "reference"))))))
            (is (= "REF-2" (:value (t/attrs (control-of (buffered-node (second (roots)) "reference"))))))))))))

(deftest repeated-fields-emit-instance-unique-error-ids
  (testing "rf2-drpa3.134. `invoice-lines` renders two lines at once, so
            two controlled fields named \"description\" and two buffered
            fields named \"reference\" sit on one page. Each field derives
            its error-region id from the caller's instance identity — the
            controlled field from its `:instance` line id, the buffered
            field from its `:control` address — so the same field name
            twice no longer collides, and every control's `aria-describedby`
            resolves to its OWN region."
    (each-mode
      (fn [mode]
        (seed-lines! [1 2])
        ;; Every field invalid, and every gate open, so every error region
        ;; renders: blank the descriptions, attempt submit on both lines,
        ;; and give each reference a rejection reason to display.
        (frame/replace-app-db! fid (-> (app-db)
                                       (assoc-in [:invoice 1 :description] "")
                                       (assoc-in [:invoice 2 :description] "")
                                       (assoc-in [:invoice 1 :reference-error] "Not on file.")
                                       (assoc-in [:invoice 2 :reference-error] "Not on file.")))
        (send! [:acme.invoice/submit-attempted 1])
        (send! [:acme.invoice/submit-attempted 2])
        (let [tree     (render! (lines-form mode [1 2]))
              roots    (nodes tree #(= "acme/invoice-line" (:data-component (:attrs %))))
              [l1 l2]  roots
              resolves? (fn [field-node]
                          (let [ctl   (control-of field-node)
                                err   (error-of field-node)
                                by    (:aria-describedby (t/attrs ctl))]
                            {:by by :id (:id (t/attrs err))}))]
          (is (= 2 (count roots)) "non-vacuous: two lines rendered")

          (testing "two same-named CONTROLLED fields do not collide"
            (let [a (resolves? (field-node l1 "description"))
                  b (resolves? (field-node l2 "description"))]
              (is (not= (:id a) (:id b))
                  "distinct error-region ids for the two \"description\" fields")
              (is (= (:id a) (:by a)) "line 1's control points at line 1's region")
              (is (= (:id b) (:by b)) "line 2's control points at line 2's region")))

          (testing "and the two BUFFERED fields do not collide either"
            (let [a (resolves? (buffered-node l1 "reference"))
                  b (resolves? (buffered-node l2 "reference"))]
              (is (not= (:id a) (:id b))
                  "distinct error-region ids for the two \"reference\" fields")
              (is (= (:id a) (:by a)))
              (is (= (:id b) (:by b))))))))))

(deftest an-error-id-is-stable-across-a-list-reorder
  (testing "rf2-drpa3.134, acceptance 3. The id is a pure function of the
            caller's instance identity, not of render position, so line 1's
            description carries the SAME id whether it is rendered first or
            second — an ordinary reorder migrates no ids."
    (each-mode
      (fn [mode]
        (seed-lines! [1 2])
        (frame/replace-app-db! fid (-> (app-db)
                                       (assoc-in [:invoice 1 :description] "")
                                       (assoc-in [:invoice 2 :description] "")))
        (send! [:acme.invoice/submit-attempted 1])
        (send! [:acme.invoice/submit-attempted 2])
        (let [id-of (fn [ids position]
                      (let [roots (nodes (render! (lines-form mode ids))
                                         #(= "acme/invoice-line" (:data-component (:attrs %))))]
                        (:id (t/attrs (error-of (field-node (nth roots position) "description"))))))]
          (is (= (id-of [1 2] 0) (id-of [2 1] 1))
              "line 1's description keeps its id when it moves to second position")
          (is (= (id-of [1 2] 1) (id-of [2 1] 0))
              "and line 2's likewise, when it moves to first"))))))

(deftest a-single-instance-field-keeps-the-short-id
  (testing "rf2-drpa3.134, acceptance 4. A field the caller renders once
            supplies no instance, so its error id stays the short
            `acme-field-<name>-error` — the single-instance call is
            unchanged and no id allocator, registry or policing appears."
    (each-mode
      (fn [mode]
        (seed-line! 1 {:description ""})
        (send! [:acme.invoice/submit-attempted 1])
        (let [solo (render! (form mode :field {:name     "description"
                                               :label    "Description"
                                               :value    ""
                                               :error    "A description is required."
                                               :on-input [:noop]}))]
          (is (= "acme-field-description-error"
                 (:id (t/attrs (error-of (field-node solo "description")))))
              "no :instance, so no instance segment"))))))

(deftest error-region-ids-are-injective-over-supported-identities
  (testing "rf2-drpa3.146. The id names ONE instance, and the library points
            `aria-describedby` at it, so distinct SUPPORTED identities must
            yield distinct ids. The lossy slug collapsed several onto one: it
            replaced every non-alnum run with a single '-' and dropped a token
            that trimmed to blank, so `\"a/b\"` and `\"a-b\"`, `1` and `\"1\"`,
            and `\"!\"` and `\"@\"` all aliased. Line ids 1 and 2 never
            exercised this — they slugged apart already."
    (let [id (fn [instance] (pilot/error-region-id "acme-field" instance "description"))]
      (is (not= (id "a/b") (id "a-b"))
          "two string instances the slug collapsed are two ids")
      (is (not= (id 1) (id "1"))
          "a number and the string of its digits are two ids")
      (is (not= (id "!") (id "@"))
          "two punctuation-only identities are two ids, neither dropped")
      (is (not= (id "!") (id nil))
          "and a punctuation identity is not the same as no identity")
      (is (not= (pilot/error-region-id "acme-field" "a" "b-c")
                (pilot/error-region-id "acme-field" "a-b" "c"))
          "the join cannot blur where one identity part ends and the next begins"))))

(deftest a-buffered-control-address-is-injective-as-one-token
  (testing "rf2-drpa3.146, acceptance 2. The buffered field slugs its whole
            `:control` address as ONE token, so two distinct addresses must
            still land on two ids — the same collision, at the surface the
            controlled-instance fix does not touch."
    (let [id (fn [control] (pilot/error-region-id "acme-buffered" control))]
      (is (not= (id [:invoice 1 :reference]) (id [:invoice 2 :reference]))
          "two line addresses are two ids")
      (is (not= (id [:invoice 42 :reference]) (id [:invoice-42 :reference]))
          "a boundary a lossy slug blurred — [:invoice 42 …] versus [:invoice-42 …]")
      (is (not= (id [:invoice 1 :reference]) (id [:invoice 1 :ref]))
          "and a different leaf is a different address"))))

(deftest the-single-instance-short-id-and-purity-survive-the-fix
  (testing "rf2-drpa3.146, acceptance 5. The fix adds no allocator and changes
            neither the short single-instance id nor the purity the reorder
            test relies on: an absent instance keeps the short readable form,
            and the same identity yields the same id every call."
    (is (= "acme-field-description-error"
           (pilot/error-region-id "acme-field" nil "description"))
        "no instance — the short, readable id is preserved")
    (is (= (pilot/error-region-id "acme-field" "a/b" "description")
           (pilot/error-region-id "acme-field" "a/b" "description"))
        "pure: the same string identity yields the same id")
    (is (= (pilot/error-region-id "acme-buffered" [:invoice 1 :reference])
           (pilot/error-region-id "acme-buffered" [:invoice 1 :reference]))
        "including a control-address identity")))

;; ===========================================================================
;; R-A9 — asynchronous transform race safety
;; ===========================================================================

(deftest r-a9-an-async-normalisation-neither-flickers-nor-needs-arity-tricks
  (testing "R-A9. The committed reference goes away to be normalised. The
            settle carries the token of the request it answers, so a
            superseded reply is INERT; the accepted reply advances the
            generation, which is what makes the normalised value visible
            even when it is character-for-character what the user typed.
            No done-fn, no arity sniffing, no second callback contract."
    (each-mode
      (fn [mode]
        (seed-line! 1)
        (send! [:acme.invoice/reference-submitted 1 "ref-99" :req-1])
        (is (true? (rf/subscribe-once [:acme.invoice/normalising? 1] {:frame fid})))

        (testing "a reply to a request the caller has already superseded
                  lands nowhere"
          (send! [:acme.invoice/reference-submitted 1 "ref-100" :req-2])
          (send! [:acme.invoice/reference-normalised 1 :req-1 "REF-99"])
          (is (= "REF-1" (get-in (app-db) [:invoice 1 :reference]))
              "the stale reply did not move the value")
          (is (nil? (::pilot/normalised (app-db)))
              "and produced no settle at all"))

        (testing "the current reply lands, and the control follows it"
          (send! [:acme.invoice/reference-normalised 1 :req-2 "REF-100"])
          (is (= ["REF-100"] (::pilot/normalised (app-db))))
          (is (= "REF-100" (shown-buffered (render! (line-form mode 1)) "reference"))))

        (testing "and a normalisation that returns exactly what was
                  submitted still shows — the generation moved even though
                  the value did not"
          (fire! (control-of (buffered-node (render! (line-form mode 1)) "reference"))
                 :on-input "REF-TYPED")
          (send! [:acme.invoice/reference-submitted 1 "REF-100" :req-3])
          (let [before (get-in (app-db) [:invoice 1 :reference])]
            (send! [:acme.invoice/reference-normalised 1 :req-3 "REF-100"])
            (is (= before (get-in (app-db) [:invoice 1 :reference]))
                "non-vacuous: the value is unchanged across the settle")
            (is (= "REF-100" (shown-buffered (render! (line-form mode 1)) "reference"))
                "yet the typed draft is gone from the display")))))))

(deftest r-a9-rendering-is-free-of-consequence
  (testing "R-A9's structural precondition, and R-A2's. The buffered read
            is a pure function of (record, generation, baseline), so
            repeating a render is uneventful — no render-time dispatch, no
            render-phase mutation, nothing to abandon. That is what makes
            a concurrent restart, a StrictMode double-invoke and a
            rejected candidate all ordinary, and it is why the caret never
            has to be rescued."
    (each-mode
      (fn [mode]
        (seed-line! 1)
        (fire! (control-of (buffered-node (render! (line-form mode 1)) "reference"))
               :on-input "REF-DRAFT")
        (let [before  (app-db)
              results (vec (repeatedly 4 #(shown-buffered (render! (line-form mode 1))
                                                          "reference")))]
          (is (= before (app-db)) "four renders moved no state at all")
          (is (apply = results) "and every pass displayed the same thing")
          (is (= "REF-DRAFT" (first results))
              "non-vacuous: what they agreed on is the live draft"))))))

(deftest r-a9-terminal-outcomes-are-mutually-exclusive
  (testing "rf2-drpa3.135. A submitted request settles exactly once. The
            token IS the request's identity, so whichever terminal outcome —
            success or refusal — lands FIRST retires it, and any later or
            duplicate settle for that request is inert. A success also
            clears a prior refusal's message, because it is the newer
            baseline decision. These are the terminal-order seams a token
            protocol that only GUARDS the winner, without RETIRING the
            request, leaves open."
    (each-mode
      (fn [mode]
        (testing "a refusal retires the request: a success still in flight
                  for it cannot land afterwards and overwrite the refusal"
          (seed-line! 1)
          (send! [:acme.invoice/reference-submitted 1 "ref-x" :req-1])
          (send! [:acme.invoice/reference-refused 1 :req-1 "That reference is not on file."])
          (send! [:acme.invoice/reference-normalised 1 :req-1 "REF-LATE"])
          (is (= "REF-1" (get-in (app-db) [:invoice 1 :reference]))
              "the late success did not move the refused baseline")
          (is (nil? (::pilot/normalised (app-db)))
              "and settled nothing at all")
          (is (= "That reference is not on file."
                 (get-in (app-db) [:invoice 1 :reference-error]))
              "the refusal's message still stands"))

        (testing "and the mirror order: a SUCCESS retires the request, so a
                  refusal still in flight for it cannot land afterwards and
                  reverse the settled success"
          (seed-line! 1)
          (send! [:acme.invoice/reference-submitted 1 "ref-w" :req-4])
          (send! [:acme.invoice/reference-normalised 1 :req-4 "REF-W"])
          (let [settled (app-db)]
            (send! [:acme.invoice/reference-refused 1 :req-4 "Too late."])
            (is (= settled (app-db))
                "the late refusal moved no state at all")
            (is (= "REF-W" (get-in (app-db) [:invoice 1 :reference]))
                "non-vacuous: the success's value is the one still standing")
            (is (nil? (get-in (app-db) [:invoice 1 :reference-error]))
                "and it wrote no error over the settled success")
            (is (nil? (::pilot/refused (app-db)))
                "nor counted itself in the domain log")))

        (testing "a duplicate refusal for an already-refused request is
                  inert — applying the refusal twice equals applying it once"
          (seed-line! 1)
          (send! [:acme.invoice/reference-submitted 1 "ref-v" :req-5])
          (send! [:acme.invoice/reference-refused 1 :req-5 "That reference is not on file."])
          (let [after-first (app-db)]
            (send! [:acme.invoice/reference-refused 1 :req-5 "That reference is not on file."])
            (is (= after-first (app-db))
                "the duplicate refusal moved no state at all")
            (is (= 1 (::pilot/refused (app-db)))
                "non-vacuous: the refusal was counted exactly once")
            (is (= 1 (get-in (app-db) [:invoice 1 :reference-revision]))
                "and advanced the revision exactly once")))

        (testing "a refusal for a request the caller has already superseded
                  lands nowhere — the same rule the success obeys"
          (seed-line! 1)
          (send! [:acme.invoice/reference-submitted 1 "ref-u" :req-6])
          (send! [:acme.invoice/reference-submitted 1 "ref-u2" :req-7])
          (let [before (app-db)]
            (send! [:acme.invoice/reference-refused 1 :req-6 "Stale."])
            (is (= before (app-db))
                "the superseded refusal moved no state at all")
            (is (true? (rf/subscribe-once [:acme.invoice/normalising? 1] {:frame fid}))
                "non-vacuous: the CURRENT request is still in flight")))

        (testing "a current success clears a prior rejection's error as it
                  stores the normalised value"
          (seed-line! 1)
          (send! [:acme.invoice/reference-rejected 1 "That reference is not on file."])
          (is (= "That reference is not on file."
                 (get-in (app-db) [:invoice 1 :reference-error]))
              "the rejection set an error")
          (send! [:acme.invoice/reference-submitted 1 "ref-y" :req-2])
          (send! [:acme.invoice/reference-normalised 1 :req-2 "REF-Y"])
          (is (= "REF-Y" (get-in (app-db) [:invoice 1 :reference]))
              "the fresh request's success lands")
          (is (nil? (get-in (app-db) [:invoice 1 :reference-error]))
              "and the stale rejection message is gone, not retained"))

        (testing "a duplicate success for an already-settled request is
                  inert — the first outcome retired it"
          (seed-line! 1)
          (send! [:acme.invoice/reference-submitted 1 "ref-z" :req-3])
          (send! [:acme.invoice/reference-normalised 1 :req-3 "REF-Z"])
          (let [after-first (app-db)]
            (send! [:acme.invoice/reference-normalised 1 :req-3 "REF-Z"])
            (is (= after-first (app-db))
                "the duplicate settle moved no state at all")
            (is (= ["REF-Z"] (::pilot/normalised (app-db)))
                "non-vacuous: the success was recorded exactly once")))))))

(deftest r-a3-a-direct-rejection-is-not-a-late-reply
  (testing "The direct baseline rejection and the asynchronous refusal are
            two events because they answer two questions. A direct rejection
            is the caller deciding NOW about the draft in front of it: there
            is no request to be current with, so it always lands, restores
            the baseline and advances the revision — R-A3's whole point. The
            asynchronous refusal names the request it answers and lands only
            while that request is outstanding. One event shape could not be
            both without letting a late reply masquerade as a fresh decision."
    (each-mode
      (fn [_mode]
        (seed-line! 1)
        (send! [:acme.invoice/reference-rejected 1 "Not on file."])
        (is (= 1 (::pilot/refused (app-db)))
            "the direct rejection landed with no request outstanding")
        (is (= "Not on file." (get-in (app-db) [:invoice 1 :reference-error])))

        (testing "and it is idempotent in no sense at all — every direct
                  rejection is a NEW baseline decision"
          (send! [:acme.invoice/reference-rejected 1 "Not on file."])
          (is (= 2 (::pilot/refused (app-db)))
              "a second direct rejection is a second decision")
          (is (= 2 (get-in (app-db) [:invoice 1 :reference-revision]))
              "which the revision reports, exactly as R-A3 requires"))

        (testing "while a direct rejection also supersedes a request in
                  flight — a reply for it can no longer land"
          (seed-line! 1)
          (send! [:acme.invoice/reference-submitted 1 "ref-q" :req-9])
          (send! [:acme.invoice/reference-rejected 1 "Not on file."])
          (let [decided (app-db)]
            (send! [:acme.invoice/reference-normalised 1 :req-9 "REF-Q"])
            (is (= decided (app-db))
                "the superseded success moved no state at all")
            (send! [:acme.invoice/reference-refused 1 :req-9 "Also too late."])
            (is (= decided (app-db))
                "and neither did the superseded refusal")))))))

;; ===========================================================================
;; R-A10 — busy from the in-flight write's own state
;; ===========================================================================

(deftest r-a10-busy-comes-from-the-write-not-a-local-flag
  (testing "R-A10. The buffered control disables while its own
            normalisation is in flight, and re-enables when the reply
            settles. Nothing anywhere holds a `busy?` boolean of its own."
    (each-mode
      (fn [mode]
        (seed-line! 1)
        (is (false? (:disabled (t/attrs (control-of (buffered-node (render! (line-form mode 1))
                                                                    "reference")))))
            "idle: present-false, so the fact is stated rather than absent")
        (send! [:acme.invoice/reference-submitted 1 "REF-2" :req-1])
        (is (true? (:disabled (t/attrs (control-of (buffered-node (render! (line-form mode 1))
                                                                  "reference")))))
            "in flight: disabled")
        (send! [:acme.invoice/reference-normalised 1 :req-1 "REF-2"])
        (is (false? (:disabled (t/attrs (control-of (buffered-node (render! (line-form mode 1))
                                                                    "reference")))))
            "settled: enabled again")))))

;; ===========================================================================
;; R-A11 — headless assertability
;; ===========================================================================

(deftest r-a11-the-whole-loop-is-assertable-without-a-browser
  (testing "R-A11. Draft, validity and gate in one pass, with no DOM and
            no test hooks in the markup: the components are reached by
            their PUBLIC part addresses, the intents are read as data and
            fired by equality, and the derived gate is an ordinary value.
            No `data-testid` appears anywhere in the pilot."
    (each-mode
      (fn [mode]
        (seed-line! 1 {:quantity ""})
        (let [tree (render! (line-form mode 1))]
          (is (empty? (nodes tree #(contains? (:attrs %) :data-testid)))
              "the pilot carries no test-only attribute")
          (is (= ["root" "label" "control"]
                 (mapv #(:data-part (:attrs %))
                       (nodes (field-node tree "quantity")
                              #(contains? (:attrs %) :data-part))))
              "the parts a caller may style are exactly the ones emitted"))

        (send! [:acme.invoice/field-blurred 1 :quantity])
        (is (= "A quantity is required."
               (t/text (error-of (field-node (render! (line-form mode 1)) "quantity"))))
            "the error is reachable by part address")
        (is (false? (rf/subscribe-once [:acme.invoice/can-submit? 1] {:frame fid}))
            "and the gate is an ordinary value")

        (type! mode 1 "quantity" "4")
        (is (nil? (error-of (field-node (render! (line-form mode 1)) "quantity")))
            "typing a valid quantity clears the display")
        (is (true? (rf/subscribe-once [:acme.invoice/can-submit? 1] {:frame fid}))
            "and flips the gate — the whole loop, headless")))))

;; ===========================================================================
;; R-A12 — per-keystroke cost, stated mechanically
;; ===========================================================================

(defn- per-keystroke
  "Drive ONE keystroke and answer what it cost: the events that settled,
  the leaf paths that moved in the frame, and which of the form's declared
  reads changed value.

  `reads` is the roster of queries the four-field form makes, so the third
  number is the invalidation fan-out an author actually pays."
  [reads keystroke!]
  (let [seen (atom [])]
    (rf/register-listener! :events ::keystroke-probe #(swap! seen conj (:event-id %)))
    (let [before      (app-db)
          before-vals (mapv #(rf/subscribe-once % {:frame fid}) reads)
          _           (keystroke!)
          after       (app-db)
          after-vals  (mapv #(rf/subscribe-once % {:frame fid}) reads)]
      (rf/unregister-listener! :events ::keystroke-probe)
      {:events       @seen
       :db-paths     (moved-paths before after)
       :reads-moved  (into [] (keep-indexed (fn [i q]
                                              (when (not= (nth before-vals i)
                                                          (nth after-vals i))
                                                q)))
                           reads)})))

(def ^:private form-reads
  "Every query the four-field form and its buffered field make of the
  application, for one line."
  [[:acme.invoice/line 1]
   [:acme.invoice/normalising? 1]
   [:acme.invoice/can-submit? 1]
   [:acme.invoice/line-total 1]
   [:acme.invoice/theme]
   [:acme.invoice/field-error 1 :description]
   [:acme.invoice/field-error 1 :quantity]
   [:acme.invoice/field-error 1 :unit-price]
   [:acme.invoice/field-error 1 :account]
   [:acme.invoice/reference 1]
   [:acme.invoice/reference-revision 1]])

(deftest r-a12-the-per-keystroke-cost-of-a-four-field-form
  (testing "R-A12. EVIDENCE, not a threshold: what one keystroke costs on
            a four-field form with a buffered fifth. The numbers are
            asserted exactly so a change to them is a visible decision
            rather than a drift."
    (each-mode
      (fn [mode]
        (testing "a keystroke in a CONTROLLED field"
          (seed-line! 1)
          (let [c   (control-of (field-node (render! (line-form mode 1)) "quantity"))
                ev  (:on-input (t/attrs c))
                cost (per-keystroke form-reads
                                    #(send! (v/materialize-event ev {::v/value "21"})))]
            (is (= [:acme.ui.field/changed :acme.invoice/field-edited] (:events cost))
                "TWO events: the library's site, then the caller's own handler")
            (is (= [[:invoice 1 :quantity]] (:db-paths cost))
                "ONE leaf path moved in the frame")
            (is (= [[:acme.invoice/line 1]
                    [:acme.invoice/line-total 1]]
                   (:reads-moved cost))
                "TWO of the form's eleven reads moved")))

        (testing "a keystroke in the BUFFERED field"
          (seed-line! 1)
          (let [c    (control-of (buffered-node (render! (line-form mode 1)) "reference"))
                down (:on-key-down (t/attrs c))
                edit (:on-input (t/attrs c))
                cost (per-keystroke form-reads
                                    (fn []
                                      (send! (v/materialize-event down {::v/key "R"}))
                                      (send! (v/materialize-event edit {::v/value "REF-1R"}))))]
            (is (= [:acme.ui.buffered/key-pressed :acme.ui.buffered/edited] (:events cost))
                "TWO events: the keydown site settles a no-op, then the edit")
            (is (= [[:acme.ui/controllers]] (:db-paths cost))
                "ONE location moved — the record is written atomically, so the
                 first keystroke of a session materializes the library's whole
                 root and later ones move the record alone")
            (is (= [] (:reads-moved cost))
                "and NONE of the form's application reads moved: a draft is the
                 library's own state, invisible to the caller until it commits")))

        (testing "the fan-out is stated honestly: a props-only field takes
                  its value as a prop, so the PARENT boundary is what a
                  keystroke invalidates. Every field on the line
                  re-renders, and only the edited one's props changed."
          (seed-line! 1)
          (let [before (render! (line-form mode 1))]
            (send! [:acme.invoice/field-edited 1 :quantity "7"])
            (let [after (render! (line-form mode 1))]
              (is (not= before after) "the line's tree moved")
              (is (= (field-node before "account") (field-node after "account"))
                  "while three of the four fields render byte-identical output"))))))))

;; ===========================================================================
;; D007 — CASE A's datum: one data event, and the cost it pays
;; ===========================================================================

(deftest d007-case-a-wants-suppression-not-prevent-default
  (testing "rf2-drpa3.124, evidence for D007. The gate asks whether the
            closed key-condition map earns its place. CASE A's datum is
            that it did NOT use one, and the reason is the useful part.

            (1) Its two key intents — Enter commits, Escape reverts — ride
            ONE registered event that branches on the live key string, so
            the key map buys CASE A nothing on the per-key preventDefault
            grounds D007 argues from. (2) The one preventDefault CASE A
            wants is form-level submission, and the options map already
            carries it — no keyboard site asks for one. (3) The cost a key
            map WOULD pay for is dispatch SUPPRESSION: the data site fires
            on every key, so an unmatched key still settles a no-op that a
            closed map would elide. That argument applies to every
            keyboard-bearing control, not only the ones that preventDefault,
            and it is the one to weigh on its own. The MEASURED per-keystroke
            cost lives in `r-a12-the-per-keystroke-cost-of-a-four-field-form`;
            this row records the datum as a decision input."
    (each-mode
      (fn [mode]
        (seed-line! 1)
        (let [tree      (render! (line-form mode 1))
              form-node (component-node tree "acme/invoice-line")
              c         (control-of (buffered-node tree "reference"))
              down      (:on-key-down (t/attrs c))]

          (testing "the keys are ONE data event, not a closed key map"
            (is (= :acme.ui.buffered/key-pressed (first down))
                "every key routes through one registered event")
            (is (= ::v/key (last down))
                "carrying the live key as data — a JVM test drives it by equality")
            (is (empty? (filter #{"Enter" "Escape" "Tab" "ArrowUp"} down))
                "no key literal sits at the site: the branch is in the handler,
                 so there is no closed key MAP here for D007 to weigh"))

          (testing "the one preventDefault CASE A wants is form submission,
                    and it rides the options map"
            (let [submit (:on-submit (t/attrs form-node))]
              (is (map? submit) "submission is declared as an options map")
              (is (true? (:prevent-default submit))
                  "which is where the single preventDefault lives")
              (is (vector? (:event submit)) "carrying the caller's own intent"))
            (is (not (contains? (t/attrs c) :prevent-default))
                "the keyboard site itself asks for no preventDefault — what
                 CASE A wants there is suppression, not browser mechanics"))

          (testing "the ONE event decides Enter, Escape and everything else —
                    and an unmatched key still DISPATCHES, which is the no-op
                    a key map would suppress"
            (fire! c :on-input "REF-DRAFT")
            (is (some? (record [:invoice 1 :reference])) "a live draft exists to settle")
            (let [seen (atom [])]
              (rf/register-listener! :events ::key-probe #(swap! seen conj (:event-id %)))
              (let [before (app-db)]
                (send! (v/materialize-event down {::v/key "a"}))
                (is (= before (app-db))
                    "an unmatched key moves NO state — 'a missing key is a no-op'")
                (is (= [:acme.ui.buffered/key-pressed] @seen)
                    "yet it DID dispatch: exactly the traffic a key map would elide"))
              (rf/unregister-listener! :events ::key-probe))
            (send! (v/materialize-event down {::v/key "Escape"}))
            (is (nil? (record [:invoice 1 :reference]))
                "and Escape reverts through that same one event")))))))

;; ===========================================================================
;; Promotion parity — the same suite, and the same trees
;; ===========================================================================

(defn- as-mode-ids
  "Rewrite the interpreted view-ids onto the promoted twins' namespace —
  the only difference living in another file makes."
  [tree]
  (walk/postwalk
    (fn [x]
      (if (and (keyword? x) (= "re-frame.freehand.pilot-field" (namespace x)))
        (keyword "re-frame.freehand.pilot-field-compiled" (name x))
        x))
    tree))

(deftest promotion-changes-no-call-site-and-no-structural-output
  (testing "Adding `{:compiled true}` is the whole diff. Every call site
            in `invoice-line` is byte-identical between the two files, the
            props maps are the same maps, and the structural tree the two
            modes render is the same value once the view-id namespace is
            substituted."
    (seed-line! 1)
    (send! [:acme.invoice/field-blurred 1 :quantity])
    (frame/replace-app-db! fid (assoc-in (app-db) [:invoice 1 :quantity] "oops"))
    (let [interpreted (render! (line-form :interpreted 1))
          compiled    (render! (line-form :compiled 1))]
      (is (= (as-mode-ids interpreted) compiled)
          "one tree, from two modes, over a state that exercises a branch"))

    (testing "with the drafted, rejected and resumed states too"
      (fire! (control-of (buffered-node (render! (line-form :interpreted 1)) "reference"))
             :on-input "REF-PARITY")
      (is (= (as-mode-ids (render! (line-form :interpreted 1)))
             (render! (line-form :compiled 1))))
      (send! [:acme.invoice/reference-rejected 1 "That reference is not on file."])
      (is (= (as-mode-ids (render! (line-form :interpreted 1)))
             (render! (line-form :compiled 1)))))))

(deftest the-parity-proof-is-not-vacuous
  (testing "A parity proof where both sides are interpreted proves
            nothing, so each side's declared lowering is asserted before
            its output is trusted."
    (doseq [[nm interpreted] pilot/by-name]
      (let [promoted-view (get promoted/by-name nm)]
        (is (some? promoted-view) (str nm " has a promoted twin"))
        (is (= :interpreted (:lowering (v/describe interpreted)))
            (str nm " is declared interpreted"))
        (is (= :compiled (:lowering (v/describe promoted-view)))
            (str nm " is declared compiled"))
        (is (= (:props-schema (v/describe interpreted))
               (:props-schema (v/describe promoted-view)))
            (str nm "'s props contract is the same contract in both modes"))))))

(deftest the-compiled-twins-carry-a-real-manifest
  (testing "What promotion actually buys, read off the public
            inspection surface: a compiled declaration reports its
            analysis, and an interpreted one honestly reports nothing."
    (doseq [[nm interpreted] pilot/by-name]
      (is (nil? (v/manifest interpreted))
          (str nm " interpreted: no analysis to claim"))
      (let [m (v/manifest (get promoted/by-name nm))]
        (is (= :re-frame.freehand/v1 (:grammar m))
            (str nm " compiled: analysed under the v1 grammar"))
        (is (= (get {:field          true
                     :buffered-field true
                     :invoice-line   true
                     :invoice-lines  false}
                    nm)
               (:reactive? m))
            (str nm " compiled: the capability-elision verdict, on an exact
                 view rather than a threshold"))))
    (testing "and the elision is real: the list wrapper reads nothing and
              owns no committed site, so promotion drops its ViewCell
              entirely — the one measurable thing compilation buys this
              pilot"
      (is (false? (:reactive? (v/manifest promoted/invoice-lines))))
      (is (= :elided (:view-cell (v/manifest promoted/invoice-lines))))
      (is (= :present (:view-cell (v/manifest promoted/invoice-line)))
          "non-vacuous: a view that DOES read keeps its cell"))))

;; ===========================================================================
;; The library's public surface — schema, parts, tokens
;; ===========================================================================

(deftest the-props-schema-closes-the-props-map
  (testing "A declared schema CLOSES the map: a misspelled prop is an
            error rather than a value the view will silently never read.
            The schema is inert data — it names which props are legal, and
            validating their VALUES is a separate seam — so the pilot
            states what it closes and nothing more."
    (each-mode
      (fn [mode]
        (let [declared (:props-schema (v/describe (get-in modes [mode :field])))]
          (is (= :map (first declared)) "the schema is a literal map schema")
          (is (= [:name :label :value :on-input :on-blur :error :busy? :columns :instance]
                 (mapv first (rest declared)))
              "and names every prop the component takes, in order"))))))

(deftest the-part-addresses-are-the-published-ones
  (testing "Part ids are API. Every id the components emit is in the
            published roster, and every published id is actually emitted —
            a roster that drifted from the markup would be worse than no
            roster."
    (seed-line! 1 {:description ""})
    (send! [:acme.invoice/submit-attempted 1])
    (send! [:acme.invoice/reference-rejected 1 "That reference is not on file."])
    (each-mode
      (fn [mode]
        (let [tree (render! (line-form mode 1))]
          (doseq [[component ids] pilot/parts]
            (let [emitted (into #{}
                                (comp (mapcat #(nodes % (fn [n] (some? (:data-part (:attrs n))))))
                                      (map #(:data-part (:attrs %))))
                                (nodes tree #(= component (:data-component (:attrs %)))))]
              (is (= (set ids) emitted)
                  (str component " emits exactly its published parts")))))))))

(deftest tokens-ride-the-cascade-and-the-one-dynamic-value-rides-inline
  (testing "D018's two planes, as the pilot spells them. The theme is ONE
            attribute on the form root, so a switch is an attribute change
            the cascade resolves for every descendant — no token
            subscription per leaf and no remount. The single value a
            stylesheet cannot know rides as a namespaced custom property
            on the one field that has it."
    (each-mode
      (fn [mode]
        (seed-line! 1)
        (let [form-node (component-node (render! (line-form mode 1)) "acme/invoice-line")]
          (is (nil? (:data-part (:attrs form-node)))
              "the form root publishes no part id — it is not a styling target")
          (is (= "light" (:data-theme (t/attrs form-node))) "the theme scopes at the root")
          (send! [:acme.invoice/theme-changed "dark"])
          (let [after (component-node (render! (line-form mode 1)) "acme/invoice-line")]
            (is (= "dark" (:data-theme (t/attrs after)))
                "and a switch is one attribute, on one node")
            (is (= (dissoc (t/attrs form-node) :data-theme)
                   (dissoc (t/attrs after) :data-theme))
                "non-vacuous: nothing else about the root moved")))

        (let [tree (render! (line-form mode 1))]
          (is (= {:--acme-field-columns "40"}
                 (:style (t/attrs (field-node tree "description"))))
              "the one dynamic token rides inline, namespaced")
          (is (nil? (:style (t/attrs (field-node tree "quantity"))))
              "and a field without one emits no style at all"))))))
