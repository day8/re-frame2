(ns re-frame.freehand.to-react-dom-cljs-test
  "FH-REACT-002 … FH-REACT-004 in a real browser — the outward bridge under a
  real foreign React parent.

  The element-tree sibling (`to-react-cljs-test`) proves what the bridge SAYS:
  the props map, the trailing children, the provider it inserts, and every
  refusal. This file proves what REACT DOES with that answer, and the two are
  not the same claim. An element can be perfectly shaped around a subtree React
  never commits, a frame no subscription ever resolves against, or a reload
  that quietly remounts — and no element assertion would notice, because the
  assertion and the bug would share an author.

  So: a real `react-dom/client` root, a real React parent component rendering
  the exported component as an ordinary child, and every assertion read back
  off `document`.

  This file rides the browser lane through its `-dom-cljs-test` namespace
  suffix. It also matches the node suites' broader regex, where it has no DOM
  to mount and says so rather than passing quietly."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.freehand.to-react :as to-react]
            [re-frame.freehand.to-react-views :as views]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(def react-002 (conf/fixture :FH-REACT-002))
(def react-003 (conf/fixture :FH-REACT-003))
(def react-004 (conf/fixture :FH-REACT-004))

(use-fixtures :each
  ;; `:async? true` is required rather than stylistic — `cljs.test` hard-errors
  ;; on a fn-form fixture around an `(async done …)` test. `:ambient-frame nil`
  ;; is load-bearing for a different reason, and a subtler one: the shell
  ;; prefers a bound `re-frame.frame/*current-frame*` over the React context,
  ;; so a fixture that established one would let the ambient-resolution row
  ;; below pass through the dynamic tier without the context ever being
  ;; consulted — a green over the wrong mechanism entirely.
  (test-support/make-reset-runtime-fixture
    {:adapter        plain-atom/adapter
     :async?         true
     :ambient-frame  nil
     :init-fn        (fn [] (to-react/reset-exports!) (fr/reset-boundaries!))}))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- act
  "A React 19 `act` boundary as a promise, so assertions run after the commit
  rather than racing it."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- host-node! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    container))

(defn- text [container selector]
  (some-> (.querySelector container selector) .-textContent))

(defn- seed!
  "A live frame carrying the fixture's state — the host application's own boot,
  which is the only place a frame is ever created in this contract."
  []
  (rf/reg-sub (first (:query react-004)) (fn [db _] (get-in db [:person :name])))
  (rf/make-frame {:id (:frame-id react-004)
                  :initial-events [[:rf/set-db (:db react-004)]]}))

(defn- foreign-parent
  "A REAL foreign React component — plain `createElement`, no Freehand above
  it — that renders `child-element` inside markup of its own. This is the shape
  the whole bridge exists for: React-world owning the tree, a Freehand view
  inside it."
  [child-element]
  (react/createElement "main" #js {:className "foreign"}
                       (react/createElement "h1" nil "Foreign")
                       child-element))

(defn- mount!
  "Commit `element` into a fresh container and hand `[container root]` on."
  [element]
  (let [container (host-node!)
        root      (rdc/createRoot container)]
    (-> (act #(.render root element))
        (.then (fn [_] #js [container root])))))

(defn- teardown!
  [pair]
  (let [container (aget pair 0)
        root      (aget pair 1)]
    (-> (act #(.unmount root))
        (.then (fn [_] (.remove container) nil)))))

;; ===========================================================================
;; FH-REACT-004 — a declared view renders inside a real React tree
;; ===========================================================================

(deftest fh-react-004-an-exported-view-renders-inside-a-foreign-react-tree
  (testing "Per FH-REACT-004: the frame the `frame` prop names is the frame the
            exported subtree runs in — proven by a SUBSCRIPTION resolving
            against its state. A view that read nothing would render
            identically under the right frame and under none at all, so the
            read is what makes this a frame assertion rather than a render
            one."
    (if-not (browser?)
      (skip! "the browser job runs the assertions")
      (async done
        (seed!)
        (let [exported (v/->react views/greeting)]
          (-> (mount! (foreign-parent
                        (react/createElement
                          exported
                          #js {"frame"      (:frame-id react-004)
                               "salutation" (:salutation (:props react-004))})))
              (.then (fn [pair]
                       (let [container (aget pair 0)]
                         (is (= "Foreign" (text container "h1"))
                             "the foreign parent's own markup rendered")
                         (is (= (:rendered react-004) (text container "p.greeting"))
                             "and the Freehand view rendered, having resolved the frame"))
                       (teardown! pair)))
              (.then (fn [_] (done))
                     (fn [e] (is false (str "mount rejected: " e)) (done)))))))))

(deftest fh-react-004-an-omitted-frame-prop-resolves-ambiently
  (testing "Per FH-REACT-004: with NO `frame` property the exported view takes
            the ordinary ambient chain — a frame boundary the FOREIGN tree
            already established resolves it, because the bridge scopes through
            the same shared React context every other frame boundary uses. That
            is what makes an exported view composable with a host app that
            already knows about frames, instead of an island that has to be
            told twice."
    (if-not (browser?)
      (skip! "the browser job runs the assertions")
      (async done
        (seed!)
        (let [exported (v/->react views/greeting)]
          (-> (mount! (shell/provide-frame
                        (:frame-id react-004)
                        (foreign-parent
                          (react/createElement
                            exported
                            #js {"salutation" (:salutation (:props react-004))}))))
              (.then (fn [pair]
                       (is (= (:rendered (:ambient react-004))
                              (text (aget pair 0) "p.greeting"))
                           "resolved from the boundary above, with no prop at all")
                       (teardown! pair)))
              (.then (fn [_] (done))
                     (fn [e] (is false (str "mount rejected: " e)) (done)))))))))

;; ===========================================================================
;; FH-REACT-002 — React content nests inside an exported view
;; ===========================================================================

(deftest fh-react-002-react-children-land-inside-the-exported-view
  (testing "Per FH-REACT-002: React's content slot IS the boundary's
            trailing-children slot, so a foreign parent nests React elements
            inside an exported Freehand view and they land where the view puts
            its children. This is the composition the nested-root workaround
            cannot do in the other direction, and it costs the bridge one
            mapping rather than a protocol."
    (if-not (browser?)
      (skip! "the browser job runs the assertions")
      (async done
        (seed!)
        (let [owned    (:children (:owned react-002))
              exported (v/->react views/panel)]
          (-> (mount! (react/createElement
                        exported
                        #js {"frame" (:frame-id react-004)
                             "title" (:title owned)}
                        (react/createElement "b" #js {:className "nested"} (:text owned))))
              (.then (fn [pair]
                       (let [container (aget pair 0)]
                         (is (= (:title owned) (text container "section.panel h2.title"))
                             "the view's own markup rendered")
                         (is (= (:text owned) (text container "section.panel div.body b.nested"))
                             "and the foreign React child landed INSIDE the view's children slot"))
                       (teardown! pair)))
              (.then (fn [_] (done))
                     (fn [e] (is false (str "mount rejected: " e)) (done)))))))))

;; ===========================================================================
;; FH-REACT-003 — a reload does not remount the foreign subtree
;; ===========================================================================

(deftest fh-react-003-a-reload-renders-the-new-body-in-place
  (testing "Per FH-REACT-003: re-exporting after a redefinition answers the
            same component object, so React re-renders the boundary it already
            mounted rather than unmounting the foreign library's subtree and
            building a new one. The proof is the reloaded body's output
            appearing in the SAME committed tree — a remount would produce the
            same text and destroy everything below it, which is exactly why the
            text alone is not the assertion."
    (if-not (browser?)
      (skip! "the browser job runs the assertions")
      (async done
        (seed!)
        ;; The two exports are deliberately SEQUENCED around the first commit.
        ;; Exporting both up front would republish the reloaded descriptor
        ;; before anything rendered, and the first assertion would be reading
        ;; the second generation's output — a test that passed on the behaviour
        ;; it exists to distinguish.
        (let [before (v/->react views/generation-1)]
          (-> (mount! (react/createElement
                        before
                        #js {"frame" (:frame-id react-004) "person-id" 7}))
              (.then (fn [pair]
                       (is (= (:before (:reload react-003)) (text (aget pair 0) "span.cell"))
                           "the first generation's body rendered")
                       (let [after (v/->react views/generation-2)]
                         (is (= (:same-component (:reload react-003))
                                (identical? before after))
                             "the reload answered one component object")
                         (-> (act #(.render (aget pair 1)
                                            (react/createElement
                                              after
                                              #js {"frame" (:frame-id react-004)
                                                   "person-id" 7})))
                             (.then (fn [_] pair))))))
              (.then (fn [pair]
                       (is (= (:after (:reload react-003)) (text (aget pair 0) "span.cell"))
                           "and the reloaded body rendered through the same boundary")
                       (teardown! pair)))
              (.then (fn [_] (done))
                     (fn [e] (is false (str "mount rejected: " e)) (done)))))))))
