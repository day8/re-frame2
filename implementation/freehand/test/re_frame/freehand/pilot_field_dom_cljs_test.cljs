(ns re-frame.freehand.pilot-field-dom-cljs-test
  "THE FIRST PILOT in a real browser — the CASE A rows whose evidence is a
  live DOM and cannot be anything else.

  `pilot-field-cljs-test` fills in the structural half of §A.2. This file
  fills in the half a tree cannot hold: whether a keystroke's state change
  lands before the browser restores the node (R-A1), whether the caret,
  the selection and an IME composition survive a value the substrate
  reasserts (R-A2), and whether a caller's rejection RESTORES the input
  rather than replacing it (R-A3).

  It mounts the pilot's own `invoice-line` — the same four controlled
  fields and one buffered field the structural suite renders, reached
  through the same public `data-part` addresses, with no test-only
  attribute anywhere in the markup.

  ## What this suite is deliberate about

  **The library's extra hop is under test, not assumed.** The pilot's
  controlled field does not forward the caller's event vector into the
  `:on-input` position; it owns a literal site and re-dispatches the
  caller's event as an effect. That buys a static compiled door proof and
  costs one more event per keystroke — and whether that event still
  settles INSIDE the browser's discrete event is exactly the kind of
  claim a pilot must prove rather than reason about. If the hop fell
  outside the synchronous drain, `pilot-r-a1-rapid-typing-through-the-
  librarys-own-site` would lose characters.

  **The substrate is the REACTIVE, React-shaped one.** A controlled input
  is the case where a correction must reach the host inside the event, so
  the pilot runs on the React-hook spine exactly as `FH-INPUT-003` does.

  **Typing appends.** A keystroke appends to whatever the node currently
  holds and then fires a real bubbling `input` event, so a character the
  host restored away is visible as a loss rather than papered over.

  ## Compiled mode

  The row-by-row keystroke, caret and IME behaviours below run the
  INTERPRETED mount — they exercise the substrate's discrete-event
  handling, which is mode-independent. The pilot's compiled twin is
  browser-mountable as of the compiled React lowering (rf2-drpa3.113): the
  final test mounts `pilot-field-compiled/invoice-line` into a real DOM and
  reads the same public fields back at the same part addresses. The
  compiled tier's exhaustive cross-mode DOM parity lives in
  `compiled-mount-dom-cljs-test`; its JVM structural parity lives in
  `pilot-field-cljs-test`."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand.pilot-field :as pilot]
            [re-frame.freehand.pilot-field-compiled :as promoted]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true}))

(def ^:private fid :acme.pilot/frame)
(def ^:private line-id 1)

(def ^:private seed-line
  {:description "Consulting"
   :quantity    "2"
   :unit-price  "150"
   :account     "4000"
   :reference   "REF-1"
   :reference-revision 0})

;; ---------------------------------------------------------------------------
;; Browser seams
;; ---------------------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real browser mount is required — " why)))

(defn- act [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- live!
  "Leave React's act environment: typing has to reach React as a genuine
  DISCRETE event, which is the mechanism under test."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  nil)

(defn- native-value-setter []
  (.-set (js/Object.getOwnPropertyDescriptor
           (.-prototype js/HTMLInputElement) "value")))

(defn- set-native-value! [node s]
  (.call (native-value-setter) node s))

(defn- fire-input!
  ([node] (fire-input! node nil false))
  ([node data composing?]
   (.dispatchEvent node (js/InputEvent. "input"
                                        #js {:bubbles     true
                                             :cancelable  false
                                             :data        data
                                             :isComposing composing?}))))

(defn- fire-key!
  [node k]
  (.dispatchEvent node (js/KeyboardEvent. "keydown"
                                          #js {:bubbles true :cancelable true :key k})))

(defn- fire-blur! [node]
  (.dispatchEvent node (js/FocusEvent. "blur" #js {:bubbles false})))

(defn- keystroke!
  "One keystroke: APPEND `ch` to whatever the node currently holds, then
  fire a real bubbling `input`. Appending is what makes a dropped
  character visible."
  [node ch]
  (set-native-value! node (str (.-value node) ch))
  (fire-input! node ch false))

(defn- type-string! [node s]
  (doseq [ch (seq s)] (keystroke! node (str ch))))

(defn- insert-at!
  "Insert `ch` at offset `at`, the way a browser does: the text grows at
  the caret, the caret follows it, then `input` fires."
  [node at ch]
  (let [text (.-value node)]
    (.focus node)
    (.setSelectionRange node at at)
    (set-native-value! node (str (subs text 0 at) ch (subs text at)))
    (.setSelectionRange node (inc at) (inc at))
    (fire-input! node ch false)))

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

(defn- control
  "The native control of the component named `nm`, reached by its PUBLIC
  part address — no `data-testid` exists in the pilot to reach for."
  [container nm]
  (.querySelector container (str "[data-field='" nm "'] [data-part='control']")))

(defn- error-text
  [container nm]
  (some-> (.querySelector container (str "[data-field='" nm "'] [data-part='error']"))
          (.-textContent)))

(defn- seed! []
  (live-frame/make-frame {:id fid})
  (frame/replace-app-db! fid {:invoice {line-id seed-line}})
  (pilot/register!)
  (pilot/register-app!)
  fid)

(defn- render-line! [root]
  (act #(.render root (shell/provide-frame fid (fr/element [pilot/invoice-line {:id line-id}])))))

(defn- seed-lines!
  "Seed TWO invoice lines, both with a blank description and submit already
  attempted, so both lines' \"description\" error regions render on mount."
  []
  (live-frame/make-frame {:id fid})
  (frame/replace-app-db! fid
    {:invoice {1 (assoc seed-line :description "")
               2 (assoc seed-line :description "")}
     :ui      {1 {:submit-attempted? true}
               2 {:submit-attempted? true}}})
  (pilot/register!)
  (pilot/register-app!)
  fid)

(defn- render-lines! [root]
  (act #(.render root (shell/provide-frame fid (fr/element [pilot/invoice-lines {:ids [1 2]}])))))

(defn- app [] (frame/frame-app-db-value fid))
(defn- line [] (get-in (app) [:invoice line-id]))

(defn- send! [ev] (act #(rf/dispatch-sync ev {:frame fid})))

(defn- draft []
  (get-in (app) [pilot/records-root
                 [pilot/buffered-kind [:invoice line-id :reference]]
                 :draft]))

(defn- with-line
  "Mount the pilot line, leave the act environment, run `f` with the
  container, and tear down. `f` may return a promise."
  [f done]
  (seed!)
  (let [[container root] (mount!)]
    (-> (render-line! root)
        (.then (fn [_]
                 (live!)
                 (js/Promise.resolve (f container))))
        (.catch (fn [e]
                  (is false (str "the mounted pilot rejected: " e))
                  nil))
        (.then (fn [_] (teardown! container root) (done))))))

;; ===========================================================================
;; R-A1 — same-tick echo, through the library's own event site
;; ===========================================================================

(deftest pilot-r-a1-rapid-typing-through-the-librarys-own-site
  (testing "R-A1. A burst of keystrokes into the pilot's CONTROLLED field
            yields a final value equal to the typed string character for
            character, in the DOM and in application state alike — even
            though every keystroke travels through the library's own
            event and then out again as the caller's. The extra hop rides
            inside the synchronous drain the door opened; if it did not,
            characters would be lost here and nowhere else."
    (if-not (browser?)
      (skip! "the browser job runs the typing assertions")
      (async done
        (with-line
          (fn [container]
            (let [node  (control container "quantity")
                  typed "1234567890123456789"]
              (is (= "2" (.-value node)) "the field starts on the seeded value")
              (set-native-value! node "")
              (fire-input! node nil false)
              (type-string! node typed)
              (is (= typed (.-value node))
                  "every one of the nineteen keystrokes survived in the DOM")
              (is (= typed (:quantity (line)))
                  "and application state holds exactly what was typed")))
          done)))))

(deftest pilot-r-a1-four-fields-contend-without-loss
  (testing "R-A1 under contention. Four controlled fields and a buffered
            fifth observe one frame, so every keystroke in any of them
            dirties all of them. Typing into each in turn still yields
            exactly what was typed — the frame-scoped flush settles the
            whole line inside each listener."
    (if-not (browser?)
      (skip! "the browser job runs the contention assertions")
      (async done
        (with-line
          (fn [container]
            (doseq [[nm typed] [["description" "Design work"]
                                ["quantity" "12"]
                                ["unit-price" "99"]
                                ["account" "4100"]]]
              (let [node (control container nm)]
                (set-native-value! node "")
                (fire-input! node nil false)
                (type-string! node typed)
                (is (= typed (.-value node)) (str nm " kept every character"))))
            (is (= {:description "Design work" :quantity "12"
                    :unit-price "99" :account "4100"}
                   (select-keys (line) [:description :quantity :unit-price :account]))
                "and the whole line reads back exactly what was typed"))
          done)))))

;; ===========================================================================
;; R-A2 — caret, selection, IME
;; ===========================================================================

(deftest pilot-r-a2-a-mid-string-insert-leaves-the-caret-where-it-was
  (testing "R-A2. Editing in the middle of an existing value: the
            character lands at the caret and the caret stays with it. A
            round trip that completed late would put the whole value back
            with the cursor at the end."
    (if-not (browser?)
      (skip! "the browser job runs the caret assertions")
      (async done
        (with-line
          (fn [container]
            (let [node (control container "description")]
              (is (= "Consulting" (.-value node)))
              (insert-at! node 3 "X")
              (is (= "ConXsulting" (.-value node)) "the insert landed at the caret")
              (is (= [4 4] (caret node)) "and the caret did not jump to the end")
              (is (= "ConXsulting" (:description (line)))
                  "with application state matching character for character")))
          done)))))

(deftest pilot-r-a2-a-range-selection-survives-the-round-trip
  (testing "R-A2. A range selection made after the round trip is intact —
            the node was neither replaced nor rewritten out from under
            the user."
    (if-not (browser?)
      (skip! "the browser job runs the selection assertions")
      (async done
        (with-line
          (fn [container]
            (let [node   (control container "description")
                  before node]
              (insert-at! node 3 "X")
              (.setSelectionRange node 2 6)
              (is (= [2 6] (caret node)) "the range is set")
              (insert-at! node 1 "Y")
              (is (identical? before (control container "description"))
                  "the control is the SAME node throughout")
              (is (= "CYonXsulting" (.-value node)))
              (is (= [2 2] (caret node))
                  "and the caret is exactly where the browser put it")))
          done)))))

(deftest pilot-r-a2-an-ime-composition-completes-intact
  (testing "R-A2. A composition is a sequence — start, updates, end — and
            it dies if the node is replaced beneath it or its in-flight
            text is rewritten between updates. What a headless Chromium
            cannot supply is a platform input engine, so this drives the
            composition-event protocol the browser really dispatches
            rather than claiming to have exercised a Japanese IME."
    (if-not (browser?)
      (skip! "the browser job runs the composition assertions")
      (async done
        (with-line
          (fn [container]
            (let [node   (control container "description")
                  before node
                  steps  ["k" "ka" "かn" "かん" "かんじ"]
                  final  "漢字"]
              (set-native-value! node "")
              (fire-input! node nil false)
              (.focus node)
              (.dispatchEvent node (js/CompositionEvent.
                                     "compositionstart" #js {:bubbles true :data ""}))
              (doseq [s steps]
                (.dispatchEvent node (js/CompositionEvent.
                                       "compositionupdate" #js {:bubbles true :data s}))
                (set-native-value! node s)
                (fire-input! node s true)
                (is (= s (.-value node))
                    (str "the composition's in-flight text survived update " (pr-str s)))
                (is (identical? before (control container "description"))
                    "and the node it is composing into was not replaced"))
              (.dispatchEvent node (js/CompositionEvent.
                                     "compositionend" #js {:bubbles true :data final}))
              (set-native-value! node final)
              (fire-input! node final false)
              (is (= final (.-value node)) "the composition committed intact")
              (is (= final (:description (line))) "and reached application state")))
          done)))))

;; ===========================================================================
;; R-A3 — the same-value rejection, restored rather than remounted
;; ===========================================================================

(deftest pilot-r-a3-a-rejection-restores-the-baseline-on-the-same-node
  (testing "R-A3, the row this whole design exists for. The user drafts
            into the buffered field; the caller REFUSES the draft and
            stands by the reference it already had. The value before that
            decision and the value after it are identical, so nothing
            derived from the value could have seen it — yet the baseline
            comes back, on the SAME node, which still has focus. A
            key-remount would put the right text on a different node and
            an effect-driven reset would commit one stale frame first;
            both would pass every structural assertion and fail here."
    (if-not (browser?)
      (skip! "the browser job runs the reject-and-restore assertions")
      (async done
        (with-line
          (fn [container]
            (let [node   (control container "reference")
                  before node]
              (is (= "REF-1" (.-value node)) "the control starts on the baseline")
              (insert-at! node 4 "X")
              (is (= "REF-X1" (.-value node)) "the draft is live in the DOM")
              (is (= [5 5] (caret node)) "with the caret where the user put it")
              (is (= "REF-X1" (draft)) "and it is controller state, not host state")

              (let [reference-before (:reference (line))]
                (-> (send! [:acme.invoice/reference-rejected
                            line-id "That reference is not on file."])
                    (.then
                      (fn [_]
                        (is (= reference-before (:reference (line)))
                            "non-vacuous: the caller's VALUE never moved")
                        (is (= "REF-1" (.-value node)) "the baseline is back on screen")
                        (is (identical? before (control container "reference"))
                            "on the SAME node — restored, not remounted")
                        (is (identical? before (.-activeElement js/document))
                            "which still has focus")
                        (is (= "That reference is not on file."
                               (error-text container "reference"))
                            "and the reason is displayed through the control's error part")))))))
          done)))))

(deftest pilot-r-a3-editing-resumes-at-the-new-generation
  (testing "R-A3. After the rejection the user types again. The next edit
            builds on the baseline the caller restored rather than on the
            draft it refused, and the caret follows the insert."
    (if-not (browser?)
      (skip! "the browser job runs the resume assertions")
      (async done
        (with-line
          (fn [container]
            (let [node (control container "reference")]
              (insert-at! node 4 "X")
              (-> (send! [:acme.invoice/reference-rejected line-id nil])
                  (.then
                    (fn [_]
                      (insert-at! node 0 "A")
                      (is (= "AREF-1" (.-value node))
                          "the resumed edit built on the RESTORED baseline")
                      (is (= [1 1] (caret node)) "and the caret followed the insert")
                      (is (= "AREF-1" (draft))
                          "the new draft is live controller state again"))))))
          done)))))

;; ===========================================================================
;; R-A7 — the key protocol, with real keyboard events
;; ===========================================================================

(defn- settle!
  "Fire a NON-door interaction and let it settle.

  Typing is different from every other interaction the pilot has: an
  `:on-input` site on a controlled element is inside the synchronous
  door, so its state change has landed by the time the listener returns.
  A keydown and a blur are NOT — `onInput`/`onChange` is the whole door
  roster — so they take the ordinary batched lane and settle at the next
  host checkpoint. That is correct and invisible to a user, and it means
  a browser test has to WAIT rather than assert straight after the
  dispatch. Re-entering React's act environment is how this suite waits;
  [[live!]] afterwards puts typing back on the discrete-event path."
  [thunk]
  (-> (act thunk)
      (.then (fn [_] (live!) nil))))

(deftest pilot-r-a7-enter-commits-and-escape-reverts-in-the-dom
  (testing "R-A7. Enter and Escape ride ONE registered event carrying the
            live `KeyboardEvent.key`, so the browser proof is that the
            real key events reach it and that the control follows. Both
            are outside the synchronous door by construction, so each is
            allowed to settle before it is read back."
    (if-not (browser?)
      (skip! "the browser job runs the keyboard assertions")
      (async done
        (with-line
          (fn [container]
            (let [node (control container "reference")]
              (insert-at! node 4 "E")
              (is (= "REF-E1" (.-value node)) "the draft is live")
              (-> (settle! #(fire-key! node "Enter"))
                  (.then
                    (fn [_]
                      (is (= "REF-E1" (:reference (line)))
                          "Enter committed the draft to the caller")
                      (is (nil? (draft)) "and retired the session")
                      (is (= "REF-E1" (.-value node))
                          "the control now shows the accepted value")
                      (insert-at! node 0 "Z")
                      (is (= "ZREF-E1" (.-value node)) "a second draft is live")
                      (settle! #(fire-key! node "Escape"))))
                  (.then
                    (fn [_]
                      (is (= "REF-E1" (:reference (line)))
                          "Escape left the caller's value alone")
                      (is (= "REF-E1" (.-value node))
                          "and the control is back on the baseline")
                      (is (nil? (draft)) "with the session retired"))))))
          done)))))

(deftest pilot-r-a7-a-blur-behind-an-escape-does-not-resurrect-the-draft
  (testing "R-A7's ordering trap, in the DOM: Escape clears the record, so
            the blur that follows it consults committed state and finds no
            live edit. This is todomvc's documented
            cancel-unmounts-then-blur-fires hazard, with nothing to
            unmount."
    (if-not (browser?)
      (skip! "the browser job runs the blur-ordering assertions")
      (async done
        (with-line
          (fn [container]
            (let [node (control container "reference")]
              (insert-at! node 4 "G")
              (is (= "REF-G1" (draft)) "non-vacuous: a draft really is live")
              (-> (settle! (fn []
                             (fire-key! node "Escape")
                             (fire-blur! node)))
                  (.then
                    (fn [_]
                      (is (= "REF-1" (:reference (line)))
                          "the late blur found no live edit to commit")
                      (is (= 0 (:re-frame.freehand.pilot-field/accepted (app) 0))
                          "and the caller's accept event never fired")
                      (is (= "REF-1" (.-value node))))))))
          done)))))

;; ===========================================================================
;; R-A10 — busy disables the control, in the DOM
;; ===========================================================================

(deftest pilot-r-a10-the-control-disables-while-its-own-write-is-in-flight
  (testing "R-A10. The buffered control's `disabled` follows the in-flight
            normalisation's own state and nothing else — a real DOM
            property, not an attribute a tree happens to carry."
    (if-not (browser?)
      (skip! "the browser job runs the busy assertions")
      (async done
        (with-line
          (fn [container]
            (let [node (control container "reference")]
              (is (false? (.-disabled node)) "idle")
              (-> (send! [:acme.invoice/reference-submitted line-id "REF-2" :req-1])
                  (.then (fn [_]
                           (is (true? (.-disabled node)) "in flight: disabled")
                           (send! [:acme.invoice/reference-normalised
                                   line-id :req-1 "REF-2"])))
                  (.then (fn [_]
                           (is (false? (.-disabled node)) "settled: enabled again")
                           (is (= "REF-2" (.-value node))
                               "and the normalised value is displayed"))))))
          done)))))

;; ===========================================================================
;; Instance-unique error ids — the mounted a11y check
;; ===========================================================================

(deftest pilot-repeated-fields-resolve-aria-describedby-to-their-own-region
  (testing "rf2-drpa3.134, acceptance 2 (mounted). Two invoice lines
            mounted together put two controlled fields named
            \"description\" on one page. Each control's `aria-describedby`
            resolves — by document id, the way assistive technology
            resolves it — to its OWN error region, and the two ids differ.
            Before the fix both controls pointed at one ambiguous
            `acme-field-description-error`."
    (if-not (browser?)
      (skip! "the browser job runs the duplicate-id assertion")
      (async done
        (seed-lines!)
        (let [[container root] (mount!)]
          (-> (render-lines! root)
              (.then
                (fn [_]
                  (let [controls (.querySelectorAll
                                   container "[data-field='description'] [data-part='control']")
                        c1  (.item controls 0)
                        c2  (.item controls 1)
                        id1 (.getAttribute c1 "aria-describedby")
                        id2 (.getAttribute c2 "aria-describedby")]
                    (is (= 2 (.-length controls)) "non-vacuous: two description fields mounted")
                    (is (some? id1) "line 1's control names a region")
                    (is (some? id2) "line 2's control names a region")
                    (is (not= id1 id2) "the two controls name DISTINCT regions")
                    (is (.contains (.closest c1 "[data-component='acme/field']")
                                   (.getElementById js/document id1))
                        "line 1's aria-describedby resolves inside line 1's own field")
                    (is (.contains (.closest c2 "[data-component='acme/field']")
                                   (.getElementById js/document id2))
                        "line 2's aria-describedby resolves inside line 2's own field"))))
              (.catch (fn [e]
                        (is false (str "the mounted pilot rejected: " e))
                        nil))
              (.then (fn [_] (teardown! container root) (done)))))))))

(deftest pilot-two-instances-a-lossy-slug-collapsed-keep-distinct-mounted-ids
  (testing "rf2-drpa3.146 (mounted). The collision the numeric line-id test
            never reaches: two controlled fields with the same name and the
            instances a lossy slug collapsed onto ONE id — \"a/b\" and
            \"a-b\" — now mount with DISTINCT `aria-describedby` targets, each
            resolving by document id to its own error region."
    (if-not (browser?)
      (skip! "the browser job runs the mounted collision regression")
      (async done
        (live-frame/make-frame {:id fid})
        (frame/replace-app-db! fid {})
        (pilot/register!)
        (pilot/register-app!)
        (let [[container root] (mount!)
              field-form (fn [instance]
                           [pilot/field {:name     "description"
                                         :label    "Description"
                                         :value    ""
                                         :instance instance
                                         :error    "A description is required."
                                         :on-input [:noop]}])]
          (-> (act #(.render root (shell/provide-frame
                                    fid (fr/element [:div
                                                     (field-form "a/b")
                                                     (field-form "a-b")]))))
              (.then
                (fn [_]
                  (let [controls (.querySelectorAll container "[data-part='control']")
                        c1  (.item controls 0)
                        c2  (.item controls 1)
                        id1 (.getAttribute c1 "aria-describedby")
                        id2 (.getAttribute c2 "aria-describedby")]
                    (is (= 2 (.-length controls)) "non-vacuous: two fields mounted")
                    (is (some? id1) "the \"a/b\" control names a region")
                    (is (some? id2) "the \"a-b\" control names a region")
                    (is (not= id1 id2)
                        "\"a/b\" and \"a-b\" no longer collapse onto one id")
                    (is (some? (.getElementById js/document id1))
                        "the \"a/b\" region resolves by document id")
                    (is (some? (.getElementById js/document id2))
                        "the \"a-b\" region resolves by document id"))))
              (.catch (fn [e]
                        (is false (str "the mounted pilot rejected: " e))
                        nil))
              (.then (fn [_] (teardown! container root) (done)))))))))

(deftest pilot-two-emoji-instances-keep-distinct-mounted-ids
  (testing "rf2-drpa3.146, audit reopen (mounted). The collision a lossy first
            surrogate produced: two controlled fields whose instances are the
            emoji 😀 (U+1F600) and 😁 (U+1F601) — which share a high surrogate —
            mount with DISTINCT `aria-describedby` targets, each resolving by
            document id to its own error region. The JVM structural arm proves
            the whole-code-point encoding closes the collision; this browser arm
            proves the two ids also resolve to two regions in a real DOM. The
            emoji are built from their surrogate units so the mount never
            depends on how the runtime reads a literal out of source."
    (if-not (browser?)
      (skip! "the browser job runs the mounted emoji collision regression")
      (async done
        (live-frame/make-frame {:id fid})
        (frame/replace-app-db! fid {})
        (pilot/register!)
        (pilot/register-app!)
        (let [grin (str (char 0xD83D) (char 0xDE00))    ; 😀 U+1F600
              beam (str (char 0xD83D) (char 0xDE01))    ; 😁 U+1F601
              [container root] (mount!)
              field-form (fn [instance]
                           [pilot/field {:name     "description"
                                         :label    "Description"
                                         :value    ""
                                         :instance instance
                                         :error    "A description is required."
                                         :on-input [:noop]}])]
          (-> (act #(.render root (shell/provide-frame
                                    fid (fr/element [:div
                                                     (field-form grin)
                                                     (field-form beam)]))))
              (.then
                (fn [_]
                  (let [controls (.querySelectorAll container "[data-part='control']")
                        c1  (.item controls 0)
                        c2  (.item controls 1)
                        id1 (.getAttribute c1 "aria-describedby")
                        id2 (.getAttribute c2 "aria-describedby")]
                    (is (= 2 (.-length controls)) "non-vacuous: two fields mounted")
                    (is (some? id1) "the 😀 control names a region")
                    (is (some? id2) "the 😁 control names a region")
                    (is (not= id1 id2)
                        "😀 and 😁 no longer collapse onto one shared surrogate id")
                    (is (some? (.getElementById js/document id1))
                        "the 😀 region resolves by document id")
                    (is (some? (.getElementById js/document id2))
                        "the 😁 region resolves by document id"))))
              (.catch (fn [e]
                        (is false (str "the mounted pilot rejected: " e))
                        nil))
              (.then (fn [_] (teardown! container root) (done)))))))))

(deftest pilot-a-typed-instance-and-its-string-namesake-keep-distinct-mounted-ids
  (testing "rf2-drpa3.146, audit follow-up (mounted). The narrowed codec keeps
            the accepted roster TYPE-DISTINGUISHED: a keyword `:a` and the
            string \"a\" are two distinct supported identities a codec that
            ignored type would merge, and here they mount with DISTINCT
            `aria-describedby` targets, each resolving by document id to its own
            error region. The JVM/CLJS structural arm proves the encoding is
            injective over the roster; this browser arm proves the two ids also
            resolve to two regions in a real DOM."
    (if-not (browser?)
      (skip! "the browser job runs the mounted type-distinction regression")
      (async done
        (live-frame/make-frame {:id fid})
        (frame/replace-app-db! fid {})
        (pilot/register!)
        (pilot/register-app!)
        (let [[container root] (mount!)
              field-form (fn [instance]
                           [pilot/field {:name     "description"
                                         :label    "Description"
                                         :value    ""
                                         :instance instance
                                         :error    "A description is required."
                                         :on-input [:noop]}])]
          (-> (act #(.render root (shell/provide-frame
                                    fid (fr/element [:div
                                                     (field-form :a)
                                                     (field-form "a")]))))
              (.then
                (fn [_]
                  (let [controls (.querySelectorAll container "[data-part='control']")
                        c1  (.item controls 0)
                        c2  (.item controls 1)
                        id1 (.getAttribute c1 "aria-describedby")
                        id2 (.getAttribute c2 "aria-describedby")]
                    (is (= 2 (.-length controls)) "non-vacuous: two fields mounted")
                    (is (some? id1) "the keyword `:a` control names a region")
                    (is (some? id2) "the string \"a\" control names a region")
                    (is (not= id1 id2)
                        "`:a` and \"a\" are two ids — the type tag keeps them apart")
                    (is (some? (.getElementById js/document id1))
                        "the `:a` region resolves by document id")
                    (is (some? (.getElementById js/document id2))
                        "the \"a\" region resolves by document id"))))
              (.catch (fn [e]
                        (is false (str "the mounted pilot rejected: " e))
                        nil))
              (.then (fn [_] (teardown! container root) (done)))))))))

;; ===========================================================================
;; The compiled cell, re-opened — the pilot's compiled twin mounts
;; ===========================================================================

(deftest the-compiled-arm-of-every-row-above-mounts-in-a-browser
  (testing "The pilot's COMPILED twin is browser-mountable: the React
            lowering builds `pilot-field-compiled/invoice-line` into real
            DOM through the same public part addresses its interpreted
            original uses, holding the same seeded values. This is the
            matrix cell the refusal used to hold BLOCKED — re-opened by the
            compiled React lowering (rf2-drpa3.113). The compiled tier's
            exhaustive cross-mode DOM parity lives in
            `compiled-mount-dom-cljs-test`; this row keeps the pilot itself
            honest that its own compiled declaration reaches the browser."
    (if-not (browser?)
      (skip! "the browser job runs the compiled-mount assertion")
      (async done
        (seed!)
        (let [[container root] (mount!)]
          (-> (act #(.render root (shell/provide-frame
                                    fid (fr/element [promoted/invoice-line
                                                     {:id line-id}]))))
              (.then
                (fn [_]
                  (doseq [[nm v] [["description" "Consulting"]
                                  ["quantity" "2"]
                                  ["unit-price" "150"]
                                  ["account" "4000"]
                                  ["reference" "REF-1"]]]
                    (let [node (control container nm)]
                      (is (some? node)
                          (str "the compiled twin mounted the " nm " control"))
                      (is (= v (.-value node))
                          (str nm " holds its seeded value"))))))
              (.catch
                (fn [e]
                  (is false (str "the compiled pilot rejected: " e))
                  nil))
              (.then (fn [_] (teardown! container root) (done)))))))))
