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

  **The substrate is the reagent adapter, not plain-atom.** The rest of
  the Freehand browser suites run headless-shaped, where a state change
  corrects at the next commit. A controlled input is exactly the case
  where that is not enough — the correction has to reach the host inside
  the event — so the round trip is proven on a substrate whose derived
  values are watchable, which is what a browser actually runs.

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
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.reagent :as reagent-adapter]
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
    {:adapter       reagent-adapter/adapter
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

;; ===========================================================================
;; FH-INPUT-003 — rapid typing, and the control that proves it is a claim
;; ===========================================================================

(deftest fh-input-003-rapid-typing-drops-no-characters
  (testing "Per FH-INPUT-003: a burst of keystrokes delivered back to
            back in one browser task yields a final value equal to the
            typed string CHARACTER FOR CHARACTER, in the DOM and in
            application state alike. The control field — the same intent,
            one fact outside the door — is typed at identically and does
            NOT survive, which is what makes the first assertion a claim
            about the door rather than about the browser being forgiving."
    (if-not (browser?)
      (skip! "the browser job runs the typing assertions")
      (async done
        (reg!)
        (let [{:keys [seed typed]} (:rapid input-003)
              _ (make-frame! {:text seed})
              [container root] (mount!)]
          (-> (render-page! root (:sibling-count input-003))
              (.then
                (fn [_]
                  (live!)
                  (let [door (node container "door")]
                    (is (= seed (.-value door)) "the field starts at the seeded value")
                    (type-string! door typed)
                    (is (= typed (.-value door))
                        "every keystroke survived in the DOM")
                    (is (= typed (app-text))
                        "and application state holds exactly what was typed"))
                  ;; The control, from the same seed.
                  (frame/replace-app-db! fid {:text seed})
                  (act #(js/undefined))))
              (.then
                (fn [_]
                  (live!)
                  (let [batched (node container "batched")]
                    (is (= seed (.-value batched)) "the control field reset to the seed")
                    (type-string! batched typed)
                    (is (not= typed (.-value batched))
                        (str "the control field must LOSE characters — it holds "
                             (pr-str (.-value batched)) ". If this ever passes, the "
                             "browser has stopped restoring controlled nodes and the "
                             "assertions above prove nothing"))
                    (is (< (count (.-value batched)) (count typed))
                        "and it loses them by dropping, not by reordering"))
                  (teardown! container root)
                  (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (teardown! container root)
                        (done)))))))))

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
                        "the caret did not jump"))
                  (teardown! container root)
                  (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (teardown! container root)
                        (done)))))))))

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
                        "and the caret collapsed after the insert rather than jumping"))
                  (teardown! container root)
                  (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (teardown! container root)
                        (done)))))))))

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
                        "on the same node it started on"))
                  (teardown! container root)
                  (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (teardown! container root)
                        (done)))))))))

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
                        "and every sibling settled on the same keystroke's state"))
                  (teardown! container root)
                  (done)))
              (.catch (fn [e]
                        (is false (str "mount rejected: " e))
                        (teardown! container root)
                        (done)))))))))
