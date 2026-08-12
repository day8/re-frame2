(ns re-frame.freehand.controlled-input-dom-cljs-test
  "FH-INPUT-003 — the controlled round trip in a real browser.

  The cross-host suite proves what the door SAYS: which lane a site takes,
  how far the flush reaches. This one proves what a BROWSER DOES, and the
  two are not the same claim. Every classic controlled-input failure is a
  DOM behaviour that no structural assertion can see:

  - **a dropped character** — the state change did not land before the
    host restored the node from the props it last rendered, so the
    keystroke is not late, it is gone;
  - **a caret jump** — the value was rewritten out from under an
    in-progress edit, and the cursor went to the end with it;
  - **a lost selection** — a range collapsed on a re-render;
  - **a broken composition** — an IME's in-flight text was overwritten
    mid-sequence, or its node was replaced underneath it.

  So: a real `react-dom/client` root, a real reactive substrate, real
  `input` and composition events dispatched at a real node, and every
  claim read back off `document`.

  ## Two things this suite is deliberate about

  **The substrate is a REACTIVE, React-shaped one, not plain-atom.** The
  rest of the Freehand browser suites run headless-shaped, where a state
  change corrects at the next commit — plain-atom owns no watches at all,
  by design, because it is the JVM/SSR/headless adapter. A controlled
  input is exactly the case where that is not enough: the correction has
  to reach the host INSIDE the event. So the round trip is proven on the
  React-hook substrate spine, whose `replace-container!` brackets the
  write in an epoch and drains the coalesced invalidation before it
  returns — which is what a browser actually runs, and what makes the
  cell PENDING by the time the door's flush looks for it.

  **Typing appends.** A keystroke is simulated by APPENDING to whatever
  the node currently holds, through the native value setter, and then
  dispatching a real bubbling `input` event. Assigning the whole expected
  string instead would paper over the very failure being tested: if the
  host restored the node from a stale prop, the next keystroke has to
  build on the restored text, and the loss becomes visible. The control
  field below relies on precisely that.

  This file rides the browser lane through its `-dom-cljs-test` namespace
  suffix. It also matches the node suites' broader regex, where it has no
  DOM to mount and says so rather than passing quietly."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true}))

(def input-003 (conf/fixture :FH-INPUT-003))

(def ^:private fid   (:frame input-003))
(def ^:private query (:query input-003))
(def ^:private evt   (:event input-003))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real browser mount is required — " why)))

(defn- act
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- live!
  "Leave React's act environment. Typing has to reach React as a genuine
  DISCRETE event — which is the whole mechanism under test — and inside
  an act environment React diverts that work to the act queue instead of
  flushing it where the browser would."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  nil)

(defn- make-frame! [db]
  (live-frame/make-frame {:id fid})
  (frame/replace-app-db! fid db)
  fid)

(defn- reg! []
  (rf/reg-sub (first query) (fn [db _] (:text db)))
  (rf/reg-sub :fh-input/len (fn [db _] (count (:text db))))
  (rf/reg-sub :fh-input/flag (fn [db _] (:flag db)))
  (rf/reg-sub :fh-input/picked (fn [db _] (:picked db)))
  (rf/reg-event evt (fn [{:keys [db]} [_ text]] {:db (assoc db :text text)})))

(defn- app-text [] (:text (frame/frame-app-db-value fid)))

;; ---------------------------------------------------------------------------
;; Views. Module-level: a declared view cannot close over a test's locals.
;; ---------------------------------------------------------------------------

(v/defview door-field
  "The paved path: a controlled `:value` and a literal event vector on
  `:on-input`. Every fact of the door holds, so this field takes the
  synchronous lane."
  [_]
  [:input {:id       "door"
           :value    (cell/observe! query)
           :on-input [evt :re-frame.freehand/value]}])

(v/defview batched-field
  "The CONTROL, and the reason the assertions above are not vacuous. It
  is the same field with the same intent — one fact outside the door,
  because `v/handler` is imperative and what it dispatches cannot be
  known when the door would have to open. The dispatch it performs is the
  ordinary queued one, so the round trip does not complete inside the
  event and the host restores the node from a stale prop."
  [_]
  [:input {:id       "batched"
           :value    (cell/observe! query)
           :on-input (v/handler [e]
                       (rf/dispatch [evt (.. e -target -value)] {:frame fid}))}])

(v/defview sibling
  "A cell observing the SAME frame for an unrelated reason. Every
  keystroke dirties it too, so a page carrying many of these is what
  contention actually looks like: the frame-scoped flush has to settle
  all of them inside the listener."
  [{:keys [n]}]
  [:span {:data-sibling (str n)} (str (cell/observe! [:fh-input/len]))])

(v/defview page
  [{:keys [siblings]}]
  [:div
   [door-field {}]
   [batched-field {}]
   (for [n (range siblings)]
     [sibling {:key n :n n}])])

(v/defview clearable-field
  "The same door, driven by state that goes NIL. `:value` is PRESENT on
  every render — that is what makes the node controlled — and on the
  second render the value it carries is `nil`, which is the controlled
  EMPTY value and not an absent prop."
  [_]
  [:input {:id        "clearable"
           :value     (cell/observe! query)
           :on-change [evt :re-frame.freehand/value]}])

(v/defview clearable-box
  "`checked` is the second controlled slot, and the one where presence and
  truth are easiest to confuse: an unchecked box and a box with no
  `checked` prop are different nodes to React."
  [_]
  [:input {:id        "clearable-box"
           :type      "checkbox"
           :checked   (cell/observe! [:fh-input/flag])
           :on-change [evt :re-frame.freehand/value]}])

(v/defview clearing-page
  [_]
  [:div [clearable-field {}] [clearable-box {}]])

(v/defview multi-select-field
  "A native `<select multiple>` — the ONE control whose value is not a
  scalar. Its `value` is PRESENT on every render, so the node is
  controlled on every render; what changes is whether that value is a list
  of chosen option values or the empty selection."
  [_]
  [:select {:id        "multi"
            :multiple  true
            :value     (cell/observe! [:fh-input/picked])
            :on-change [evt :re-frame.freehand/value]}
   [:option {:value "a"} "Alpha"]
   [:option {:value "b"} "Bravo"]
   [:option {:value "c"} "Charlie"]])

(v/defview multi-select-page
  [_]
  [:div [multi-select-field {}]])

;; ---------------------------------------------------------------------------
;; Typing, as the browser delivers it
;; ---------------------------------------------------------------------------

(defn- native-value-setter []
  (.-set (js/Object.getOwnPropertyDescriptor
           (.-prototype js/HTMLInputElement) "value")))

(defn- set-native-value!
  "Write `s` through the prototype's own value setter, so React's value
  tracker sees the mutation exactly as it does for a real keystroke."
  [node s]
  (.call (native-value-setter) node s))

(defn- fire-input!
  ([node] (fire-input! node nil false))
  ([node data composing?]
   (.dispatchEvent node (js/InputEvent. "input"
                                        #js {:bubbles     true
                                             :cancelable  false
                                             :data        data
                                             :isComposing composing?}))))

(defn- keystroke!
  "One keystroke: APPEND `ch` to whatever the node currently holds, then
  dispatch a real bubbling `input` event. Appending is what makes a
  dropped character visible — see the namespace docstring."
  [node ch]
  (set-native-value! node (str (.-value node) ch))
  (fire-input! node ch false))

(defn- type-string! [node s]
  (doseq [ch (seq s)] (keystroke! node (str ch))))

(defn- caret [node] [(.-selectionStart node) (.-selectionEnd node)])

(defn- mount! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    [container (rdc/createRoot container)]))

(defn- teardown! [container root]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
  (.unmount root)
  (.remove container)
  nil)

(defn- node [container id] (.querySelector container (str "#" id)))

(defn- render-page!
  [root siblings]
  (act #(.render root (shell/provide-frame fid (fr/element [page {:siblings siblings}])))))

(defn- render-clearing-page!
  "Write `db` and render the clearing page from it, inside one act — so a
  re-render is a genuine state transition through the same boundary, on
  the same host nodes."
  [root db]
  (act #(do (frame/replace-app-db! fid db)
            (.render root (shell/provide-frame fid (fr/element [clearing-page {}]))))))

(defn- render-multi-select-page!
  "Write `db` and render the multiple-select page from it, inside one act."
  [root db]
  (act #(do (frame/replace-app-db! fid db)
            (.render root (shell/provide-frame fid (fr/element [multi-select-page {}]))))))

(defn- spy-console-errors!
  "Collect what React says on `console.error` while a transition runs, and
  answer the restore thunk. The controlled↔uncontrolled complaint is a
  console diagnostic and nothing else — no exception, no visible failure —
  so a suite that does not listen for it cannot see it.

  ## What listening for a HOST library's warning actually buys

  An assertion of the form \"the host said nothing\" inherits that host's
  WARNING POLICY, including every case where it declines to warn. React
  19's policy is not uniform across the three diagnostics this file reads,
  and the differences decide which shape each row has to take:

  - the controlled↔uncontrolled complaint is raised from React's UPDATE
    path only. It compares the props last committed against the props now
    committing, so no mount can raise it and a row watching for it MUST
    be shaped as a transition. It is also latched behind a page-global
    one-shot flag: React says it ONCE per page, ever;
  - the `value should not be null` complaint is raised on mount, update
    and hydration alike, and is latched behind its own page-global
    one-shot flag;
  - the `<select>` value-SHAPE complaint is raised on mount and hydration
    only — never on update — and is not latched at all. A row watching
    for it must therefore MOUNT the shape it is judging.

  A one-shot flag is the part that bites silently: this file's browser
  suite is one page, so the first test anywhere in it that legitimately
  trips a latched diagnostic disarms every later row watching for the
  same one, and those rows go green while proving nothing. The last
  deftest in this namespace is the control for that — it drives a field
  genuinely uncontrolled and REQUIRES React to complain, which can only
  succeed if the latch was still closed everywhere above it."
  [sink]
  (let [original (.-error js/console)]
    (set! (.-error js/console)
          (fn [& args]
            (swap! sink conj (str/join " " (map str args)))
            (.apply original js/console (to-array args))))
    (fn [] (set! (.-error js/console) original))))

(defn- control-complaints
  "The captured console lines that are React telling us a node changed
  between controlled and uncontrolled — the exact diagnostic an omitted
  `value`/`checked` prop raises."
  [lines]
  (filterv #(re-find #"(?i)uncontrolled|should not be null" %) lines))

(defn- value-shape-complaints
  "The captured console lines that are React telling us a `<select>`'s
  `value` is the WRONG SHAPE — a scalar on a multiple select, or an array
  on a single one. Like the controlled/uncontrolled complaint it is a
  console diagnostic and nothing else, so a suite that does not listen for
  it renders a visibly-correct page over a contract it is breaking.

  React runs this check when the element is CREATED and when it is
  hydrated, and at no other time: an update never re-reads the shape. So
  a row asking this question judges the shape of the value the node was
  MOUNTED with, whatever else has happened to the node since."
  [lines]
  (filterv #(re-find #"(?i)must be an array|must be a scalar" %) lines))

;; ===========================================================================
;; FH-INPUT-003 — rapid typing, and the control that proves it is a claim
;; ===========================================================================

(deftest fh-input-003-rapid-typing-drops-no-characters
  (testing "Per FH-INPUT-003: a burst of keystrokes delivered back to
            back in one browser task yields a final value equal to the
            typed string CHARACTER FOR CHARACTER, in the DOM and in
            application state alike."
    (if-not (browser?)
      (skip! "the browser job runs the typing assertions")
      (async done
        (reg!)
        (let [{:keys [seed typed]} (:rapid input-003)
              _ (make-frame! {:text seed})
              [container root] (mount!)]
          (-> (render-page! root 0)
              (.then
                (fn [_]
                  (live!)
                  (let [door (node container "door")]
                    (is (= seed (.-value door)) "the field starts at the seeded value")
                    (type-string! door typed)
                    (is (= typed (.-value door))
                        "every keystroke survived in the DOM")
                    (is (= typed (app-text))
                        "and application state holds exactly what was typed"))))
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              (.then (fn [_] (teardown! container root) (done)))))))))

(deftest fh-input-003-the-control-field-loses-characters
  (testing "Per FH-INPUT-003: the CONTROL, and the reason every
            assertion above is a claim about the door rather than about
            the browser being forgiving. The same field, the same intent,
            the same typing — one fact outside the door, so the round
            trip does not complete inside the event and the browser
            restores the node from a stale prop. If this row ever goes
            green, the hazard has moved and the suite says so instead of
            reporting a confident pass over it."
    (if-not (browser?)
      (skip! "the browser job runs the control assertions")
      (async done
        (reg!)
        (let [{:keys [seed typed]} (:rapid input-003)
              _ (make-frame! {:text seed})
              [container root] (mount!)]
          (-> (render-page! root 0)
              (.then
                (fn [_]
                  (live!)
                  (let [batched (node container "batched")]
                    (is (= seed (.-value batched)) "the control field starts at the seed")
                    (type-string! batched typed)
                    (is (not= typed (.-value batched))
                        (str "the control field must LOSE characters — it holds "
                             (pr-str (.-value batched))))
                    (is (< (count (.-value batched)) (count typed))
                        "and it loses them by dropping, not by reordering"))))
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              (.then (fn [_] (teardown! container root) (done)))))))))

;; ===========================================================================
;; FH-INPUT-003 — caret and selection
;; ===========================================================================

(deftest fh-input-003-the-caret-survives-a-controlled-round-trip
  (testing "Per FH-INPUT-003: a character inserted in the MIDDLE of the
            text leaves the caret immediately after it. A round trip that
            arrived late would have the host write the whole value back,
            and writing a value moves the caret to the end — the classic
            jump, and the one an author notices first."
    (if-not (browser?)
      (skip! "the browser job runs the caret assertions")
      (async done
        (reg!)
        (let [{:keys [seed at ch expected] expected-caret :expected-caret} (:caret input-003)
              _ (make-frame! {:text seed})
              [container root] (mount!)]
          (-> (render-page! root 0)
              (.then
                (fn [_]
                  (live!)
                  (let [door (node container "door")]
                    (.focus door)
                    (.setSelectionRange door at at)
                    ;; What the browser does for a mid-string insert: the
                    ;; text grows at the caret, and the caret follows it.
                    (set-native-value! door (str (subs seed 0 at) ch (subs seed at)))
                    (.setSelectionRange door (inc at) (inc at))
                    (fire-input! door ch false)
                    (is (= expected (.-value door)) "the insert landed where the caret was")
                    (is (= expected (app-text)) "and round-tripped through application state")
                    (is (= [expected-caret expected-caret] (caret door))
                        "the caret did not jump"))))
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              (.then (fn [_] (teardown! container root) (done)))))))))

(deftest fh-input-003-a-range-selection-is-replaced-not-lost
  (testing "Per FH-INPUT-003: typing over a range selection replaces the
            range and collapses the caret after the inserted character —
            the browser's own behaviour, which a value rewritten out from
            under the edit would destroy."
    (if-not (browser?)
      (skip! "the browser job runs the selection assertions")
      (async done
        (reg!)
        (let [{:keys [seed from to ch expected] expected-caret :expected-caret}
              (:selection input-003)
              _ (make-frame! {:text seed})
              [container root] (mount!)]
          (-> (render-page! root 0)
              (.then
                (fn [_]
                  (live!)
                  (let [door (node container "door")]
                    (.focus door)
                    (.setSelectionRange door from to)
                    (is (= [from to] (caret door)) "the range was made")
                    (set-native-value! door (str (subs seed 0 from) ch (subs seed to)))
                    (.setSelectionRange door (inc from) (inc from))
                    (fire-input! door ch false)
                    (is (= expected (.-value door)) "the range was replaced")
                    (is (= expected (app-text)))
                    (is (= [expected-caret expected-caret] (caret door))
                        "and the caret collapsed after the insert rather than jumping"))))
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              (.then (fn [_] (teardown! container root) (done)))))))))

;; ===========================================================================
;; FH-INPUT-003 — IME composition
;; ===========================================================================

(deftest fh-input-003-an-ime-composition-survives-the-round-trip
  (testing "Per FH-INPUT-003: a composition is a sequence — start,
            several updates, end — and it dies if the node is replaced
            beneath it or its in-flight text is rewritten between
            updates. So the assertions are the two things that would kill
            one: the node is the SAME node throughout, and every
            intermediate read holds exactly the text the composition just
            produced. What a headless Chromium cannot supply is a
            platform input engine, so this drives the composition-event
            protocol the browser really dispatches rather than claiming
            to have exercised a Japanese IME."
    (if-not (browser?)
      (skip! "the browser job runs the composition assertions")
      (async done
        (reg!)
        (let [{:keys [seed steps final]} (:composition input-003)
              _ (make-frame! {:text seed})
              [container root] (mount!)]
          (-> (render-page! root 0)
              (.then
                (fn [_]
                  (live!)
                  (let [door   (node container "door")
                        before door]
                    (.focus door)
                    (.dispatchEvent door (js/CompositionEvent.
                                           "compositionstart"
                                           #js {:bubbles true :data ""}))
                    (doseq [s steps]
                      (.dispatchEvent door (js/CompositionEvent.
                                             "compositionupdate"
                                             #js {:bubbles true :data s}))
                      (set-native-value! door s)
                      (fire-input! door s true)
                      (is (= s (.-value door))
                          (str "the composition's in-flight text survived update "
                               (pr-str s)))
                      (is (identical? before (node container "door"))
                          "and the node it is composing into was not replaced"))
                    (.dispatchEvent door (js/CompositionEvent.
                                           "compositionend"
                                           #js {:bubbles true :data final}))
                    (set-native-value! door final)
                    (fire-input! door final false)
                    (is (= final (.-value door)) "the composition committed intact")
                    (is (= final (app-text)) "and reached application state")
                    (is (identical? before (node container "door"))
                        "on the same node it started on"))))
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              (.then (fn [_] (teardown! container root) (done)))))))))

;; ===========================================================================
;; FH-INPUT-003 — clearing a controlled field
;; ===========================================================================

(deftest fh-input-003-an-explicit-nil-clears-the-live-node
  (testing "Per FH-INPUT-003: state going NIL is how an author clears a
            controlled field, and it is the one transition where PRESENCE
            and TRUTH part company. `:value` is present on both renders, so
            the node is controlled on both; the second render's value is
            the controlled EMPTY value. An emitter that dropped the prop
            because it was nil would tell React the node had become
            UNCONTROLLED — and React does not clear an uncontrolled node,
            it keeps the value it last rendered. The symptom is the old
            text still on screen while application state says empty, on a
            site the door has already put on the synchronous lane, so
            nothing throws and nothing else says so.

            The console row is shaped as a TRANSITION on purpose, and the
            shape is load-bearing rather than incidental. React raises the
            controlled↔uncontrolled complaint by comparing the props it
            last committed against the props it is committing now, which
            it can only do on an update — mounting an already-uncontrolled
            node raises nothing at all. Rewriting this row to mount the
            bad state, the way a value-SHAPE row has to be written, would
            silence it permanently.

            It has been shown to fail: drop the nil `:value` slot instead
            of writing the controlled empty value and React answers with
            `A component is changing a controlled input to be
            uncontrolled`, and the row reds. Dropping the nil `:checked`
            slot reds it the same way — the diagnostic reads whichever
            slot the element's `type` makes its controlling one."
    (if-not (browser?)
      (skip! "the browser job runs the clearing assertions")
      (async done
        (reg!)
        (let [{:keys [seed cleared unchecked] flag :checked} (:clearing input-003)
              _ (make-frame! {:text seed :flag flag})
              [container root] (mount!)
              errors  (atom [])
              restore (spy-console-errors! errors)]
          (-> (render-clearing-page! root {:text seed :flag flag})
              (.then
                (fn [_]
                  (let [field (node container "clearable")
                        box   (node container "clearable-box")]
                    (is (= seed (.-value field)) "the field starts at the seeded value")
                    (is (true? (.-checked box)) "and the box starts checked")
                    ;; RETURNED into the outer chain — so the trailing step below
                    ;; waits for the transition, and the `.catch` upstream of it
                    ;; still sees a rejection raised in here.
                    (-> (render-clearing-page! root {:text nil :flag nil})
                        (.then
                          (fn [_]
                            (is (identical? field (node container "clearable"))
                                "the SAME host node — this is a value transition, not a remount")
                            (is (identical? box (node container "clearable-box")))
                            (is (= cleared (.-value field))
                                (str "the field cleared — it holds " (pr-str (.-value field))))
                            (is (= unchecked (.-checked box))
                                "and the box unchecked")
                            (is (= [] (control-complaints @errors))
                                (str "React raised no controlled/uncontrolled diagnostic: "
                                     (pr-str @errors)))))))))
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              (.then (fn [_] (restore) (teardown! container root) (done)))))))))

;; ===========================================================================
;; FH-INPUT-003 — the deterministic half of contention
;; ===========================================================================

(deftest fh-input-003-typing-holds-while-siblings-on-the-frame-are-dirty
  (testing "Per FH-INPUT-003: every keystroke also dirties a dozen
            sibling cells observing the same frame, so the frame-scoped
            flush has to settle all of them inside the listener. The
            DETERMINISTIC half of the contention claim is the one
            asserted here — zero dropped characters — and the siblings
            are read back too, so a flush that quietly skipped them
            cannot pass. Latency distributions under a 20 Hz background
            are evidence, and belong to the measurement spine."
    (if-not (browser?)
      (skip! "the browser job runs the contention assertions")
      (async done
        (reg!)
        (let [{:keys [typed]} (:rapid input-003)
              n (:sibling-count input-003)
              _ (make-frame! {:text ""})
              [container root] (mount!)]
          (-> (render-page! root n)
              (.then
                (fn [_]
                  (live!)
                  (let [door (node container "door")]
                    (is (= n (.-length (.querySelectorAll container "[data-sibling]")))
                        "the contending siblings mounted")
                    (type-string! door typed)
                    (is (= typed (.-value door))
                        "no character was dropped while the frame was busy")
                    (is (= typed (app-text)))
                    (is (= (repeat n (str (count typed)))
                           (map #(.-textContent %)
                                (array-seq (.querySelectorAll container "[data-sibling]"))))
                        "and every sibling settled on the same keystroke's state"))))
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              (.then (fn [_] (teardown! container root) (done)))))))))

;; ===========================================================================
;; A CONTROLLED native `<select multiple>`, in a real browser
;; ===========================================================================

(deftest a-controlled-multiple-select-selects-exactly-the-authored-values
  (testing "A native `<select multiple>` is the single element in the DOM
            whose value is not a scalar: what is selected is the LIST of
            chosen option values, and React's own contract reads the prop
            as an array. An author writes ordinary Clojure data and the
            emitter converts at the boundary — so a vector selects exactly
            those options, and an explicit nil clears every one of them
            while the node stays controlled.

            The EMPTY selection is mounted first, deliberately. React
            validates a select's value SHAPE when the element is created,
            and an explicit nil is exactly where a scalar-only empty-value
            table would hand it the empty string — the wrong shape, on
            the transition an author uses to clear a field. Neither half is
            visible to a structural assertion, and a wrong shape does not
            throw: React writes a console diagnostic and renders a page
            that can look entirely correct, which is why the console is
            watched here as closely as the DOM is.

            What the console CANNOT see here is the clearing transition
            itself. React re-reads a select's value shape at no point
            after creation, and it has no controlled↔uncontrolled
            complaint for `<select>` at all — an emitter that stopped
            writing the slot leaves the old options selected in total
            silence. So the clearing claim rests on the DOM row that
            reads `selectedOptions` back, and the console rows after it
            are the mount's verdict restated plus the one complaint a
            select does still make on update: that its `value` arrived as
            a literal null."
    (if-not (browser?)
      (skip! "the browser job runs the multiple-select assertions")
      (async done
        (reg!)
        (make-frame! {:picked nil})
        (let [[container root] (mount!)
              errors  (atom [])
              restore (spy-console-errors! errors)
              picked  (fn [n] (mapv #(.-value %) (array-seq (.-selectedOptions n))))
              ;; Tears down; it does NOT finish. A closure that CLOSES OVER
              ;; `done` finishes the row just as a literal `(done)` does — and
              ;; is invisible to both campaign signatures, which look for a
              ;; literal call or a helper that TAKES `done` as a parameter
              ;; (rf2-o0n1). The single `done` sits at the tail below.
              release (fn [] (restore) (teardown! container root) nil)]
          (-> (render-multi-select-page! root {:picked nil})
              (.then
                (fn [_]
                  (let [sel (node container "multi")]
                    (is (true? (.-multiple sel)) "the node really is a multiple select")
                    (is (= [] (picked sel))
                        "an explicitly nil value mounts with nothing selected")
                    (is (= [] (value-shape-complaints @errors))
                        (str "and React accepted the value's SHAPE at mount — the empty "
                             "selection reached it as an array, not as the empty string a "
                             "scalar control clears with: " (pr-str @errors)))
                    (-> (render-multi-select-page! root {:picked ["a" "c"]})
                        (.then
                          (fn [_]
                            (is (identical? sel (node container "multi"))
                                "the SAME host node — a value transition, not a remount")
                            (is (= ["a" "c"] (picked sel))
                                (str "exactly the authored options are selected — the node "
                                     "holds " (pr-str (picked sel))))
                            (-> (render-multi-select-page! root {:picked nil})
                                (.then
                                  (fn [_]
                                    (is (identical? sel (node container "multi")))
                                    (is (= [] (picked sel))
                                        (str "an explicit nil cleared every selected option "
                                             "— the node holds " (pr-str (picked sel))))
                                    (is (= [] (control-complaints @errors))
                                        (str "the empty selection reached React as the "
                                             "empty ARRAY and never as a literal null — "
                                             "the one control complaint a `<select>` "
                                             "still makes on update: " (pr-str @errors)))
                                    (is (= [] (value-shape-complaints @errors))
                                        (str "and the mount's shape verdict still stands, "
                                             "unchanged by anything since: "
                                             (pr-str @errors))))))))))))
              ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of a step that finished the row would claim a later
              ;; namespace's throw as this row's and fire `done` a second time.
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              ;; Both arms released identically, so it rides the single trailing
              ;; step: written once, run once per path.
              (.then (fn [_] (release) (done)))))))))

;; ===========================================================================
;; THE CONTROL FOR THE CONSOLE ITSELF — declared LAST, and that is the point
;; ===========================================================================

(v/defview slot-dropping-field
  "The ONE deliberately wrong view in this file: when state goes nil it
  OMITS `:value` rather than writing the controlled empty value, which is
  exactly the emitter defect the clearing row asserts React does not
  report. Nothing reads it but the control below."
  [_]
  (let [text (cell/observe! query)]
    (if (nil? text)
      [:input {:id        "slot-dropped"
               :on-change [evt :re-frame.freehand/value]}]
      [:input {:id        "slot-dropped"
               :value     text
               :on-change [evt :re-frame.freehand/value]}])))

(v/defview slot-dropping-page
  [_]
  [:div [slot-dropping-field {}]])

(defn- render-slot-dropping-page!
  [root db]
  (act #(do (frame/replace-app-db! fid db)
            (.render root (shell/provide-frame fid (fr/element [slot-dropping-page {}]))))))

(deftest fh-input-003-the-controlled-to-uncontrolled-channel-was-still-live
  (testing "The CONTROL for every `React said nothing` row above, and the
            reason they are claims rather than silence about silence. A
            row that watches a host library for a warning inherits that
            library's warning POLICY, and React's policy for the
            controlled↔uncontrolled complaint is to raise it ONCE per
            page and never again. This suite is one page. So the first
            test anywhere in it that legitimately drives a field
            uncontrolled spends the diagnostic, and every later row
            watching for it goes green having proved nothing — with
            nothing on screen to say so.

            This row drives a field genuinely uncontrolled and REQUIRES
            the complaint. It can only arrive if the latch was still
            closed, which it can only have been if no row above it
            consumed it. Declared LAST for that reason: it spends the
            diagnostic itself, so anything wanting it must come first.

            The DOM row underneath it is this control's own control —
            React does not clear an uncontrolled node, so the field must
            still be holding the text it was seeded with. A field that
            cleared anyway never went uncontrolled, and a complaint about
            it would be about something else."
    (if-not (browser?)
      (skip! "the browser job runs the console control")
      (async done
        (reg!)
        (let [{:keys [seed]} (:clearing input-003)
              _ (make-frame! {:text seed})
              [container root] (mount!)
              errors  (atom [])
              restore (spy-console-errors! errors)
              ;; Tears down; it does NOT finish — as above.
              release (fn [] (restore) (teardown! container root) nil)]
          (-> (render-slot-dropping-page! root {:text seed})
              (.then
                (fn [_]
                  (let [field (node container "slot-dropped")]
                    (is (= seed (.-value field)) "the control field starts controlled")
                    (-> (render-slot-dropping-page! root {:text nil})
                        (.then
                          (fn [_]
                            (is (identical? field (node container "slot-dropped"))
                                (str "the SAME host node — an update, which is the only "
                                     "path that can raise the diagnostic at all"))
                            (is (= seed (.-value field))
                                (str "the node really did go uncontrolled — React kept the "
                                     "text it last rendered instead of clearing it; it "
                                     "holds " (pr-str (.-value field))))
                            (is (seq (control-complaints @errors))
                                (str "React did NOT report a field that genuinely went "
                                     "uncontrolled. It raises that diagnostic once per "
                                     "PAGE, so something above this row already spent it "
                                     "— which means every `React said nothing` row in "
                                     "this namespace was unable to fail. Captured: "
                                     (pr-str @errors)))))))))
              ;; Reports and RELEASES, as above.
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              ;; Shared release, hoisted onto the single trailing step.
              (.then (fn [_] (release) (done)))))))))
