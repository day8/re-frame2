(ns re-frame.freehand.presence-nil-key-dom-cljs-test
  "An explicit `nil` key is a KEY. An absent one is not.

  React coerces a supplied key to a string, so `{:key nil}` is the identity
  `\"null\"` — a perfectly ordinary sibling identity, and one a keyed
  reconciler tracks like any other. `{}` supplies no key at all. The two
  mean different things, and the interpreted emitter must not flatten them
  together: `(:key m)` answers `nil` for both, so key PRESENCE is asked with
  `contains?`, not by testing the value.

  It matters most under `(v/presence …)`, which tracks children BY KEY and
  drops a keyless child. Erasing an explicit `nil` there turns a legal
  identity into silent content loss — and turns `{:compiled true}`, which
  tracks key presence separately and emits the nil expression to React, into
  a behaviour switch.

  The three interpreted child paths are asserted against React's own
  coercion as the control: a literal element, a declared-view boundary, and
  a keyed fragment.

  This file rides the browser lane through its `-dom-cljs-test` suffix; the
  key-parity assertions need no DOM and fire in Node too."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.freehand :as v]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.presence-runtime :as presence]
            [re-frame.freehand.react :as fr]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :async?        true}))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- act
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(def ^:private timeout-ms 100)

(v/defview chip
  [{:keys [label]}]
  [:div.chip {:data-label (str label)
              :data-phase (name (v/presence-phase))}
   (str label)])

(v/defview nil-key-stack
  [{:keys [show?]}]
  [:div#nil-key-stack
   (v/presence {:timeout-ms timeout-ms}
     (when show? [chip {:key nil :label "draft"}]))])

(v/defview nil-key-element-stack
  [{:keys [show?]}]
  [:div#nil-key-element-stack
   (v/presence {:timeout-ms timeout-ms}
     (when show? [:div.chip {:key nil :data-label "draft"} "draft"]))])

(v/defview duplicate-nil-stack
  [_]
  [:div#duplicate-nil-stack
   (v/presence {:timeout-ms timeout-ms}
     [chip {:key nil :label "first"}]
     [chip {:key nil :label "second"}])])

;; ===========================================================================
;; The three interpreted child paths, against React's own coercion
;; ===========================================================================

(deftest an-explicit-nil-key-reaches-react-from-every-interpreted-path
  (testing "React string-coerces a supplied key, so `nil` is the identity
            \"null\". Every interpreted child path must deliver it — a
            literal element, a declared-view boundary, and a keyed fragment
            — matching React's own control and the compiled emitter, which
            passes the nil key expression through."
    (is (= "null" (.-key (react/createElement "div" #js {:key nil})))
        "the control: React itself coerces a nil key to \"null\"")
    (is (= "null" (.-key (fr/element [:div {:key nil} "draft"])))
        "an interpreted literal element carries the explicit nil key")
    (is (= "null" (.-key (fr/element [chip {:key nil :label "draft"}])))
        "so does an interpreted declared-view boundary")
    (is (= "null" (.-key (fr/element [:<> {:key nil} "draft"])))
        "and so does an interpreted keyed fragment")))

(deftest an-absent-key-is-still-absent-from-every-interpreted-path
  (testing "The other half: key ABSENCE must survive too. A value of `nil`
            and no key at all are different authored facts, and the emitter
            may not conflate them in either direction."
    (is (nil? (.-key (react/createElement "div" #js {})))
        "the control: React leaves an unsupplied key nil")
    (is (nil? (.-key (fr/element [:div {} "draft"])))
        "an interpreted literal element with no :key has no React key")
    (is (nil? (.-key (fr/element [chip {:label "draft"}])))
        "nor does a declared-view boundary with no :key")
    (is (nil? (.-key (fr/element [:<> {} "draft"])))
        "nor does a fragment with no :key")))

(deftest normalize-call-reports-key-presence-without-widening-props
  (testing "`normalize-call` is the one normalizer both emitters share, so
            it is where a boundary call's key presence has to survive. It
            keeps stripping `:key` from the props the body sees and keeping
            it outside props equality — and now also reports whether the
            call supplied one at all, which is the only thing that tells an
            explicit nil from an absent key."
    (let [explicit (descriptor/normalize-call chip [{:key nil :label "draft"}])
          valued   (descriptor/normalize-call chip [{:key "k" :label "draft"}])
          absent   (descriptor/normalize-call chip [{:label "draft"}])]
      (is (= {:label "draft"} (:props explicit))
          ":key is stripped from the props map")
      (is (= (:props absent) (:props explicit) (:props valued))
          "so it stays outside props equality, whatever its value")
      (is (true? (:keyed? explicit)) "an explicit nil key is reported PRESENT")
      (is (nil? (:key explicit)) "carrying its authored value, nil")
      (is (true? (:keyed? valued)) "an ordinary key is reported present too")
      (is (false? (:keyed? absent)) "and an absent key is reported absent")
      (is (nil? (:key absent)) "with the same nil value — which is why :keyed? exists"))))

;; ===========================================================================
;; Mounted: an explicit-nil-key child is a full presence citizen
;; ===========================================================================

(defn- mount! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    [container (rdc/createRoot container)]))

(defn- teardown! [container root]
  (.unmount root)
  (.remove container)
  nil)

(defn- chip-el [container] (.querySelector container ".chip"))
(defn- phase-of [container] (some-> (chip-el container) (.getAttribute "data-phase")))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(deftest a-nil-keyed-view-child-lives-the-whole-presence-lifecycle
  (testing "A declared-view presence child keyed with an explicit `nil`
            renders, settles `:present`, is RETAINED `:unmounting` when it
            departs, and is removed terminally by the timeout — never
            dropped as keyless."
    (if-not (browser?)
      (skip! "the browser job runs the mounted lifecycle assertions")
      (async done
        (presence/reset-clock!)
        (presence/set-wall-clock! false)
        (let [[container root] (mount!)]
          (-> (act #(.render root (fr/element [nil-key-stack {:show? true}])))
              (.then (fn [_]
                       (is (some? (chip-el container))
                           "the explicit-nil-key child RENDERED — it was not dropped")
                       (is (= "present" (phase-of container))
                           "and settled :present, so the enter transition ran")
                       (is (= 0 (presence/pending-count)) "nothing is retained yet")
                       (act #(.render root (fr/element [nil-key-stack {:show? false}])))))
              (.then (fn [_]
                       (is (some? (chip-el container))
                           "departing RETAINS it — presence tracked it by the \"null\" identity")
                       (is (= "unmounting" (phase-of container))
                           "and it reads :unmounting through the phase context")
                       (is (= 1 (presence/pending-count)) "its exit is scheduled")
                       (act #(presence/flush-presence!))))
              (.then (fn [_]
                       (is (nil? (chip-el container))
                           "and the timeout removes it terminally")
                       (is (= 0 (presence/pending-count)) "leaving nothing pending")))
              ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of it would claim a later namespace's throw as this
              ;; row's and fire `done` a second time.
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              ;; Both arms tore down identically, so the teardown rides the
              ;; single trailing step: written once, run once per path.
              (.then (fn [_] (teardown! container root) (done)))))))))

(deftest a-nil-keyed-literal-element-child-is-retained-too
  (testing "The literal-element path is a presence citizen on the same
            terms: an explicit-nil-key `[:div …]` child renders, is retained
            when it departs, and is removed by the timeout."
    (if-not (browser?)
      (skip! "the browser job runs the mounted element-path assertions")
      (async done
        (presence/reset-clock!)
        (presence/set-wall-clock! false)
        (let [[container root] (mount!)]
          (-> (act #(.render root (fr/element [nil-key-element-stack {:show? true}])))
              (.then (fn [_]
                       (is (some? (chip-el container))
                           "the explicit-nil-key element child RENDERED")
                       (act #(.render root (fr/element [nil-key-element-stack {:show? false}])))))
              (.then (fn [_]
                       (is (some? (chip-el container)) "departing RETAINS it")
                       (is (= 1 (presence/pending-count)) "its exit is scheduled")
                       (act #(presence/flush-presence!))))
              (.then (fn [_]
                       (is (nil? (chip-el container)) "and the timeout removes it")))
              ;; Reports and RELEASES, as above.
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              ;; Shared teardown, hoisted onto the single trailing step.
              (.then (fn [_] (teardown! container root) (done)))))))))

(deftest duplicate-nil-keys-collide-in-the-string-coerced-domain
  (testing "\"null\" is an ordinary identity, so two explicit-nil-key
            children of one boundary COLLIDE exactly as `1` and `\"1\"` do —
            the existing duplicate-identity law applies unchanged, and the
            FIRST claimant keeps the key."
    (if-not (browser?)
      (skip! "the browser job runs the duplicate-identity assertions")
      (async done
        (presence/reset-clock!)
        (presence/set-wall-clock! false)
        (let [[container root] (mount!)]
          (-> (act #(.render root (fr/element [duplicate-nil-stack {}])))
              (.then (fn [_]
                       (is (= 1 (.-length (.querySelectorAll container ".chip")))
                           "one child owns the \"null\" identity, not two")
                       (is (= "first" (.getAttribute (chip-el container) "data-label"))
                           "and it is the FIRST claimant in document order")))
              ;; Reports and RELEASES, as above.
              (.catch (fn [e] (is false (str "mount rejected: " e)) nil))
              ;; Shared teardown, hoisted onto the single trailing step.
              (.then (fn [_] (teardown! container root) (done)))))))))
