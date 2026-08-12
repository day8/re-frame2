(ns re-frame.freehand.buffered-controller-dom-cljs-test
  "FH-CTRL-011 — the reject-and-restore cycle in a real browser.

  The cross-host rows prove what the fence DECIDES. This one proves what
  a browser does to a live node while the decision is taken, and the two
  are not the same claim. Every alternative the ruling disqualified fails
  exactly here and nowhere else:

  - **key-remount** puts the right text on a DIFFERENT node, destroying
    focus, the selection and any composition in flight;
  - **an effect-driven reset** commits one stale frame before it
    corrects, and the caret goes with it.

  Both would pass every structural assertion in the cross-host suite.
  What separates them from a fence over ordinary frame data is node
  IDENTITY and caret position after the caller has rejected a draft — so
  those are what this file reads back off `document`.

  ## Two things this suite is deliberate about

  **The substrate is the REACTIVE, React-shaped one, not plain-atom.** A
  buffered draft is edited through a controlled input, so the correction
  has to reach the host INSIDE the event — the same reason
  `controlled-input-dom-cljs-test` runs on the React-hook spine. The
  draft lives one level further from the DOM than that suite's value
  does, which is precisely what makes the run worth doing.

  **The rejection is dispatched, not simulated.** A caller rejects by
  dispatching its own event, which reasserts the value it already had and
  advances the generation. Nothing here reaches into the control.

  This file rides the browser lane through its `-dom-cljs-test` namespace
  suffix. It also matches the node suites' broader regex, where it has no
  DOM to mount and says so rather than passing quietly."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
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

(def ctrl-011 (conf/fixture :FH-CTRL-011))

(def ^:private fid     (:frame ctrl-011))
(def ^:private address (:control ctrl-011))
(def ^:private kind    :my.ui/buffered-field)
(def ^:private records-root :my.ui/controllers)
(def ^:private invoice-id (second address))

;; ---------------------------------------------------------------------------
;; The pilot library — the same protocol the cross-host suite registers
;; ---------------------------------------------------------------------------

(defn- reg! []
  (rf/reg-sub :my.ui.field/text
    (fn [db [_ record-key revision baseline]]
      (let [record (get-in db [records-root record-key])]
        (if (v/controller-current? (:reset-key record) revision)
          (:draft record)
          baseline))))

  (rf/reg-event :my.ui.field/edited
    (fn [{:keys [db]} [_ record-key revision text]]
      {:db (assoc-in db [records-root record-key] {:reset-key revision :draft text})}))

  (rf/reg-sub :invoice/amount
    (fn [db [_ id]] (get-in db [:invoice id :amount])))

  (rf/reg-sub :invoice/amount-revision
    (fn [db [_ id]] (get-in db [:invoice id :amount-revision])))

  ;; The caller's rejection: reassert the value it already had, and
  ;; advance the generation to say this is a NEW baseline decision.
  (rf/reg-event :invoice/baseline-replaced
    (fn [{:keys [db]} [_ id amount revision]]
      {:db (-> db
               (assoc-in [:invoice id :amount] amount)
               (assoc-in [:invoice id :amount-revision] revision))})))

;; ---------------------------------------------------------------------------
;; Views. Module-level: a declared view cannot close over a test's locals.
;; ---------------------------------------------------------------------------

(v/defview buffered-field
  [{:keys [value] :as props}]
  (let [k (v/controller-key kind props)
        g (v/controller-revision kind props)]
    [:input {:id       "buffered"
             :value    (v/sub [:my.ui.field/text k g value])
             :on-input [:my.ui.field/edited k g ::v/value]}]))

(v/defview invoice-form
  [{:keys [id]}]
  [:form
   [buffered-field {:control   [:invoice id :amount]
                    :value     (v/sub [:invoice/amount id])
                    :reset-key (v/sub [:invoice/amount-revision id])}]])

;; ---------------------------------------------------------------------------
;; Browser seams — the same ones the controlled-input suite uses
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
  DISCRETE event, which is the mechanism the draft rides."
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

(defn- insert-at!
  "Insert `ch` at offset `at`, the way a browser does it: the text grows
  at the caret, the caret follows it, and then `input` fires."
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

(defn- node [container] (.querySelector container "#buffered"))

(defn- seed! []
  (live-frame/make-frame {:id fid})
  (frame/replace-app-db! fid {:invoice {invoice-id {:amount          (:baseline ctrl-011)
                                                    :amount-revision (:revision ctrl-011)}}})
  fid)

(defn- render-form! [root]
  (act #(.render root (shell/provide-frame fid (fr/element [invoice-form {:id invoice-id}])))))

(defn- reject!
  "The caller rejects: it reasserts the value it already had and advances
  the generation. Dispatched inside `act`, because it is ordinary
  application state moving and React has to be allowed to render it."
  [reasserted revision]
  (act #(rf/dispatch-sync [:invoice/baseline-replaced invoice-id reasserted revision]
                          {:frame fid})))

(defn- draft []
  (get-in (frame/frame-app-db-value fid) [records-root [kind address] :draft]))

;; ===========================================================================
;; FH-CTRL-011 — the rejection restores, it does not remount
;; ===========================================================================

(deftest fh-ctrl-011-a-rejection-restores-the-baseline-on-the-same-node
  (testing "Per FH-CTRL-011: the caller rejects a live draft by
            reasserting the value it already had and advancing the
            generation. The baseline comes back, the input node is the
            SAME node it was before, and it still has focus — which is
            exactly what a key-remount destroys and what no structural
            assertion can see."
    (if-not (browser?)
      (skip! "the browser job runs the reject-and-restore assertions")
      (async done
        (reg!)
        (let [{:keys [baseline]} ctrl-011
              {:keys [at ch expected expected-caret]} (:edit ctrl-011)
              {:keys [reasserted-value next-revision displayed]} (:reject ctrl-011)
              _ (seed!)
              [container root] (mount!)]
          (-> (render-form! root)
              (.then
                (fn [_]
                  (live!)
                  (let [field  (node container)
                        before field]
                    (is (= baseline (.-value field)) "the control starts on the baseline")

                    ;; The user drafts, mid-string.
                    (insert-at! field at ch)
                    (is (= expected (.-value field)) "the insert landed where the caret was")
                    (is (= [expected-caret expected-caret] (caret field))
                        "and the caret did not jump on the way through the draft")
                    (is (= expected (draft)) "the draft is controller state, not host state")

                    ;; THE REJECTION. Same value, new generation.
                    (is (= baseline reasserted-value)
                        "non-vacuous: the caller really does reassert an EQUAL value")
                    (-> (reject! reasserted-value next-revision)
                        (.then
                          (fn [_]
                            (is (= displayed (.-value field))
                                "the baseline is back on screen")
                            (is (not= expected displayed)
                                "non-vacuous: the draft really did leave the display")
                            (is (identical? before (node container))
                                "and it came back on the SAME node — restored, not remounted")
                            (is (identical? before (.-activeElement js/document))
                                "which still has focus")))
                        ;; The inner chain keeps its OWN diagnostic but finishes
                        ;; nothing; it is returned into the outer chain, whose
                        ;; single trailing step below owns the one `done`.
                        (.catch (fn [e] (is false (str "the rejection rejected: " e)) nil))))))
              ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of it would claim a later namespace's throw as this
              ;; row's and fire `done` a second time.
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              ;; Every arm tore the mount down identically, so the teardown rides
              ;; the single trailing step: written once, run once per path.
              (.then (fn [_] (teardown! container root) (done)))))))))

(deftest fh-ctrl-011-editing-resumes-at-the-new-generation
  (testing "Per FH-CTRL-011: after the rejection the user types again.
            The next edit belongs to the NEW generation, builds on the
            baseline the caller restored rather than on the draft it
            refused, and puts the caret exactly where the insert was."
    (if-not (browser?)
      (skip! "the browser job runs the resume assertions")
      (async done
        (reg!)
        (let [{:keys [at ch]} (:edit ctrl-011)
              {:keys [reasserted-value next-revision]} (:reject ctrl-011)
              resume (:resume ctrl-011)
              _ (seed!)
              [container root] (mount!)]
          (-> (render-form! root)
              (.then
                (fn [_]
                  (live!)
                  (let [field (node container)]
                    (insert-at! field at ch)
                    (-> (reject! reasserted-value next-revision)
                        (.then
                          (fn [_]
                            (insert-at! field (:at resume) (:ch resume))
                            (is (= (:expected resume) (.-value field))
                                "the resumed edit built on the RESTORED baseline")
                            (is (= [(:expected-caret resume) (:expected-caret resume)]
                                   (caret field))
                                "and the caret followed the insert")
                            (is (= (:expected resume) (draft))
                                "the new draft is live controller state again")))
                        ;; Inner chain: its own diagnostic, but it finishes
                        ;; nothing and is returned into the outer chain.
                        (.catch (fn [e] (is false (str "the rejection rejected: " e)) nil))))))
              ;; Reports and RELEASES, as above.
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              ;; Shared teardown, hoisted onto the single trailing step.
              (.then (fn [_] (teardown! container root) (done)))))))))

;; ===========================================================================
;; FH-CTRL-011 — IME composition through the draft
;; ===========================================================================

(deftest fh-ctrl-011-an-ime-composition-survives-the-buffered-path
  (testing "Per FH-CTRL-011: a composition is a sequence — start, several
            updates, end — and it dies if the node is replaced beneath it
            or its in-flight text is rewritten between updates. Routing
            the value through a draft puts one more round trip in that
            path, so the assertions are the two things that would kill
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
        (let [{:keys [steps final]} (:composition ctrl-011)
              _ (seed!)
              [container root] (mount!)]
          (-> (render-form! root)
              (.then
                (fn [_]
                  (live!)
                  (let [field  (node container)
                        before field]
                    (.focus field)
                    (.dispatchEvent field (js/CompositionEvent.
                                            "compositionstart"
                                            #js {:bubbles true :data ""}))
                    (doseq [s steps]
                      (.dispatchEvent field (js/CompositionEvent.
                                              "compositionupdate"
                                              #js {:bubbles true :data s}))
                      (set-native-value! field s)
                      (fire-input! field s true)
                      (is (= s (.-value field))
                          (str "the composition's in-flight text survived update "
                               (pr-str s)))
                      (is (identical? before (node container))
                          "and the node it is composing into was not replaced"))
                    (.dispatchEvent field (js/CompositionEvent.
                                            "compositionend"
                                            #js {:bubbles true :data final}))
                    (set-native-value! field final)
                    (fire-input! field final false)
                    (is (= final (.-value field)) "the composition committed intact")
                    (is (= final (draft)) "and reached the draft")
                    (is (identical? before (node container))
                        "on the same node it started on"))))
              ;; Reports and RELEASES, as above.
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              ;; Shared teardown, hoisted onto the single trailing step.
              (.then (fn [_] (teardown! container root) (done)))))))))
