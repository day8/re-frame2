(ns re-frame.freehand.to-react-cljs-test
  "FH-REACT-001 … FH-REACT-005 — the outward bridge, everywhere it can be
  proven without a page.

  An exported component is an ordinary JavaScript function, so almost the whole
  contract is reachable by CALLING it and reading the React element it answers:
  the props map the boundary is mounted with, the trailing children, the
  provider that scopes a frame, and every refusal — all of which fire inside
  that call, before React has been asked for anything.

  What genuinely needs a page — a real `react-dom/client` commit, a foreign
  React parent, an ambient frame resolved through a live context, a reloaded
  body actually rendering — is the mounted sibling `to-react-dom-cljs-test`.
  The JVM half of FH-REACT-005 is `to-react-jvm-test`: the door's JVM surface is
  a JVM reflection read, and the law there is about what is NOT on it.

  This file is `.cljs` rather than `.cljc` for the reason FH-REACT-005 states —
  `v/->react` is a browser verb, and the namespace behind it cannot be loaded on
  the JVM at all."
  (:require ["react" :as react]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [goog.object :as gobj]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.to-react :as to-react]
            [re-frame.freehand.to-react-views :as views]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(def react-001 (conf/fixture :FH-REACT-001))
(def react-002 (conf/fixture :FH-REACT-002))
(def react-003 (conf/fixture :FH-REACT-003))
(def react-004 (conf/fixture :FH-REACT-004))
(def react-005 (conf/fixture :FH-REACT-005))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (to-react/reset-exports!) (fr/reset-boundaries!))}))

;; ---------------------------------------------------------------------------
;; Reading an exported component's answer
;; ---------------------------------------------------------------------------

(defn- caught
  "The `ex-data` of the diagnostic `thunk` raised, or nil when it returned."
  [thunk]
  (try (thunk) nil (catch :default e (ex-data e))))

(defn- message
  [thunk]
  (try (thunk) nil (catch :default e (ex-message e))))

(defn- provider?
  [el]
  (and (react/isValidElement el)
       (identical? (.-type el) (.-Provider adapter-context/frame-context))))

(defn- scoped-frame
  "The frame id the answered element scopes, or nil when it scopes none."
  [el]
  (when (provider? el) (gobj/getValueByKeys el "props" "value")))

(defn- boundary
  "The Freehand boundary element inside whatever the export answered — the
  element itself, or the one child a frame provider wraps."
  [el]
  (if (provider? el) (gobj/getValueByKeys el "props" "children") el))

(defn- mounted-props
  "The props map the boundary was mounted with."
  [el]
  (gobj/getValueByKeys (boundary el) "props" "props"))

(defn- obj
  "A JS object from a map of STRING keys — `clj->js` is not usable here,
  because it renders a keyword key through `name` and would silently drop the
  namespace this suite exists to prove is preserved."
  [m]
  (let [o (js-obj)]
    (doseq [[k value] m] (gobj/set o k value))
    o))

;; ===========================================================================
;; FH-REACT-001 — only a declared view crosses, and there is one option
;; ===========================================================================

(deftest fh-react-001-only-a-declared-view-crosses
  (testing "Per FH-REACT-001: the bridge exports a DESCRIPTOR. The four shapes
            a caller reaches for instead — a plain function, hiccup, the view's
            own id keyword, and a rendered form — are each refused with the one
            diagnostic and the one recovery, because declared identity is what
            keeps the exported component debuggable once React owns it."
    (let [expected (:accepted react-001)
          exported (v/->react views/person-cell)]
      (is (fn? exported) "a declared view answers a React component")
      (is (= (:display-name expected) (gobj/get exported "displayName"))
          "named after the view, so debugging does not stop at a wrapper")
      (is (= (:view-id expected) (:view-id (v/describe views/person-cell)))
          "non-vacuous: the fixture names the view actually exported"))

    (is (= 4 (count (:rejected react-001))) "non-vacuous: four near-misses")
    (doseq [{:keys [note kind recovery]} (:rejected react-001)]
      (let [value (case kind
                    :fn       (fn [_] nil)
                    :vector   [:span "markup"]
                    :keyword  (:view-id (v/describe views/person-cell))
                    :rendered [views/person-cell {:person-id 1}])
            d     (caught #(v/->react value))]
        (is (= (:error react-001) (:rf.error/id d)) note)
        (is (= recovery (:recovery d)) (str note " — names the recovery"))))

    (testing "and the refusal SAYS what to do instead"
      (let [m (message #(v/->react (fn [_] nil)))]
        (is (re-find #"DECLARED view" m))
        (is (re-find #"wrapper" m))))))

(deftest fh-react-001-the-option-roster-is-closed-at-one-key
  (testing "Per FH-REACT-001: `:map-props` is the whole roster. An unknown
            option is REFUSED rather than ignored — ignoring it would hand the
            caller the default shallow rule and a subtly wrong render, with
            nothing anywhere saying the option did not exist."
    (is (= [:map-props] (:roster (:options react-001))))
    (is (fn? (v/->react views/person-cell {:map-props (fn [_] {})}))
        "the one option is accepted")

    (doseq [{:keys [note opts recovery]} (:rejected (:options react-001))]
      (let [value (cond
                    (not (map? opts))           opts
                    (contains? opts :map-porps) {:map-porps (fn [_] {})}
                    :else                       {:map-props :not-a-fn})
            d     (caught #(v/->react views/person-cell value))]
        (is (= (:error react-001) (:rf.error/id d)) note)
        (is (= recovery (:recovery d)) (str note " — names the recovery"))))))

;; ===========================================================================
;; FH-REACT-002 — one shallow rule, or one adapter; three owned names
;; ===========================================================================

(deftest fh-react-002-props-cross-shallow-and-by-exact-name
  (testing "Per FH-REACT-002: every own enumerable property becomes a props-map
            entry by EXACT name. The `:never` roster is the real assertion —
            every plausible bug here is a name transformation somebody thought
            was helpful, so a camelised, namespace-dropped or snake-cased
            spelling must appear nowhere."
    (let [{:keys [foreign props never]} (:shallow react-002)
          exported (v/->react views/person-cell)
          answered (mounted-props (exported (obj foreign)))]
      (is (= props answered) "the props map, by exact name, values untouched")
      (is (seq never) "non-vacuous: there are transformed spellings to look for")
      (doseq [k never]
        (is (not (contains? answered k)) (str "no transformed spelling: " k))))))

(deftest fh-react-002-a-value-is-carried-not-converted
  (testing "Per FH-REACT-002: values are UNCOERCED. A deep walk would answer an
            equal-looking Clojure map, so the assertion is identity — the view
            receives the very object the foreign library handed over."
    (let [params   #js {"data" #js {"id" 3}}
          prop     (:prop (:uncoerced react-002))
          exported (v/->react views/person-cell)
          answered (mounted-props (exported (obj {prop params})))]
      (is (identical? params (get answered (keyword prop)))
          "the identical JS object, not a converted twin"))))

(deftest fh-react-002-one-explicit-adapter-projects-the-foreign-object
  (testing "Per FH-REACT-002: `:map-props` receives the RAW foreign object —
            every property, including the ones the bridge reserves and the ones
            the projection ignores — and returns the one props map. The
            projection is named, localised code at the host edge rather than a
            conversion rule the substrate would have to pretend was general.
            An adapter does not displace the bridge's own reading of `frame`:
            the props map is entirely the adapter's, and the subtree is still
            scoped."
    (rf/make-frame {:id (:frame-id react-002) :initial-events [[:rf/set-db {}]]})
    (let [{:keys [foreign sees props]} (:adapter react-002)
          seen     (atom nil)
          exported (v/->react views/person-cell
                              {:map-props (fn [p]
                                            (reset! seen p)
                                            {:person-id (gobj/getValueByKeys p "data" "id")})})
          answered (exported (obj (assoc foreign
                                         "data"  #js {"id" 11}
                                         "frame" (:frame-id react-002))))]
      (is (= props (mounted-props answered))
          "the adapter's map is the map the view is mounted with")
      (is (seq sees) "non-vacuous: the fixture names what the adapter must see")
      (is (= (set sees) (set (js/Object.keys @seen)))
          "the adapter saw the RAW object — reserved and ignored properties alike")
      (is (= (:frame-id react-002) (scoped-frame answered))
          "and the bridge still read `frame` off it"))))

(deftest fh-react-002-the-bridge-owns-frame-and-children
  (testing "Per FH-REACT-002: `frame` is consumed and never forwarded, and
            `children` becomes the boundary's TRAILING children — the same
            slot, on the other side of the boundary, which is what lets React
            content nest inside an exported view at all."
    (rf/make-frame {:id (:frame-id react-002) :initial-events [[:rf/set-db {}]]})
    (let [owned    (:owned react-002)
          exported (v/->react views/panel)
          child    (react/createElement "b" nil (:text (:children owned)))
          answered (exported (obj {"frame"    (:frame-id react-002)
                                   "title"    (:title (:children owned))
                                   "children" child}))
          props    (mounted-props answered)]
      (is (not (contains? props (:never (:frame owned))))
          "`frame` is consumed — it is never a props-map key")
      (is (= (:title (:children owned)) (:title props))
          "an ordinary prop beside it still crosses")
      (is (= [child] (:children props))
          "React's content slot IS the boundary's trailing-children slot"))))

(deftest fh-react-002-a-react-ref-is-refused
  (testing "Per FH-REACT-002: `ref` is refused. Freehand retired the neutral
            ref tier outright, and a ref that resolved silently to nothing
            would leave a foreign owner holding a handle that never fills — so
            the bridge declines rather than accepting an inert prop."
    (let [exported (v/->react views/person-cell)
          d        (caught #(exported (obj {"ref" #js {}})))]
      (is (= (:error react-002) (:rf.error/id d)))
      (is (= (:recovery (:ref (:owned react-002))) (:recovery d))
          "and names the wrapper / behavior recovery"))))

(deftest fh-react-002-the-views-own-children-policy-still-decides
  (testing "Per FH-REACT-002: the bridge softens nothing. A view declaring
            `:children-policy :none` refuses React children with the
            DECLARATION's own diagnostic, so an author meets the rule their
            view states rather than a bridge-specific one."
    (let [exported (v/->react views/badge)
          d        (caught #(exported (obj {"label"    "x"
                                            "children" (react/createElement "b" nil "no")})))]
      (is (= (:error (:children-policy react-002)) (:rf.error/id d)))
      (is (= (:view (:children-policy react-002)) (:view-id (v/describe views/badge)))
          "non-vacuous: the fixture names the view that declares the policy"))))

(deftest fh-react-002-a-props-map-may-not-carry-the-reserved-frame
  (testing "Per FH-REACT-002: `:frame` is the bridge's name. It cannot arrive
            through the shallow arm — the property was consumed — so the
            collision is reachable only through an adapter that mints it, and
            there it is a genuine one: the same name read two ways, only one of
            which can happen."
    (let [{:keys [props recovery names]} (:reserved-collision react-002)
          exported (v/->react views/person-cell {:map-props (fn [_] props)})
          d        (caught #(exported (js-obj)))]
      (is (= (:error react-002) (:rf.error/id d)))
      (is (= recovery (:recovery d)))
      (is (re-find (re-pattern names) (message #(exported (js-obj))))
          "and the refusal NAMES the view"))))

(deftest fh-react-002-an-adapter-must-return-a-map
  (testing "Per FH-REACT-002: the adapter returns THE props map. Anything else
            is refused at the adapter rather than surfacing later as a
            malformed boundary call, so the diagnostic points at the code that
            is wrong."
    (let [exported (v/->react views/person-cell {:map-props (fn [_] "nope")})]
      (is (= (:error react-002) (:rf.error/id (caught #(exported (js-obj)))))))))

;; ===========================================================================
;; FH-REACT-003 — the exported component is stable
;; ===========================================================================

(deftest fh-react-003-one-view-and-one-adapter-answer-one-component
  (testing "Per FH-REACT-003: React reconciles on component TYPE, so repeated
            exports must answer the IDENTICAL object — a fresh function per
            call would instruct React to rebuild the foreign library's whole
            subtree every time the exporting expression ran."
    (let [components (doall (repeatedly (:repeats react-003) #(v/->react views/person-cell)))]
      (is (= (:repeats react-003) (count components)) "non-vacuous: several exports")
      (is (every? #(identical? (first components) %) components) "all one object"))

    (testing "and an adapter is half the identity, so two adapters are two components"
      (let [f (fn [_] {})
            g (fn [_] {})]
        (is (identical? (v/->react views/person-cell {:map-props f})
                        (v/->react views/person-cell {:map-props f}))
            "the same adapter is the same export")
        (is (not (identical? (v/->react views/person-cell {:map-props f})
                             (v/->react views/person-cell {:map-props g})))
            "a different adapter is a different export")))

    (testing "and two views are two components"
      (is (= (:a (:views react-003)) (:view-id (v/describe views/person-cell))))
      (is (= (:b (:views react-003)) (:view-id (v/describe views/badge))))
      (is (not (identical? (v/->react views/person-cell) (v/->react views/badge)))))))

(deftest fh-react-003-a-reload-republishes-rather-than-reminting
  (testing "Per FH-REACT-003: a hot reload mints a FRESH descriptor for the
            same view, so a cache keyed on descriptor identity would miss on
            every reload — technically memoised, and a remount in practice.
            Keying on the view id makes the reload a republication: the same
            component object, and the new body published into the boundary
            React is already reconciling on."
    (let [{:keys [view-id same-component]} (:reload react-003)]
      (is (not (identical? views/generation-1 views/generation-2))
          "non-vacuous: the two generations really are different descriptors")
      (is (= view-id (:view-id (v/describe views/generation-1))
                     (:view-id (v/describe views/generation-2)))
          "carrying one qualified id, which is what a reload preserves")

      (let [before (v/->react views/generation-1)]
        (before (obj {"person-id" 7}))
        (is (= 0 (get-in (fr/boundary-cache) [view-id :revision]))
            "one boundary, at its first body")

        (let [after (v/->react views/generation-2)]
          (is (= same-component (identical? before after))
              "the same component object — React is never told to remount")
          (after (obj {"person-id" 7}))
          (is (= 1 (get-in (fr/boundary-cache) [view-id :revision]))
              "and the reloaded body was PUBLISHED into that same boundary"))))))

;; ===========================================================================
;; FH-REACT-004 — a frame is selected, never created
;; ===========================================================================

(deftest fh-react-004-an-own-frame-prop-scopes-a-live-frame
  (testing "Per FH-REACT-004: an own `frame` prop SCOPES an already-live frame.
            The exported subtree is wrapped in the shared frame context and the
            reserved property does not reach the view."
    (rf/make-frame {:id (:frame-id react-004)
                    :initial-events [[:rf/set-db (:db react-004)]]})
    (let [exported (v/->react views/greeting)
          answered (exported (obj {"frame"      (:frame-id react-004)
                                   "salutation" (:salutation (:props react-004))}))]
      (is (= (:frame-id react-004) (scoped-frame answered))
          "the answered element scopes the named frame")
      (is (= (:props react-004) (mounted-props answered))
          "and the reserved property did not reach the view"))))

(deftest fh-react-004-a-stated-target-that-names-nothing-fails-loud
  (testing "Per FH-REACT-004: own-property PRESENCE decides, not truthiness.
            `frame={null}` and no `frame` at all read the same under any
            truthiness test and are opposite intentions — a caller who wrote
            the property meant to state a target, so a target naming nothing is
            a failure rather than a quiet fall-through to the ambient chain.
            Every one of them is attributed to the bridge, not to the shared
            provider it scopes through."
    (rf/make-frame {:id (:frame-id react-004) :initial-events [[:rf/set-db {}]]})
    (let [exported (v/->react views/person-cell)
          rows     (:stated-but-unusable react-004)]
      (is (= 3 (count rows)) "non-vacuous: nil, malformed, and absent")
      (doseq [{:keys [note target error recovery]} rows]
        (let [d (caught #(exported (obj {"frame" target})))]
          (is (= error (:rf.error/id d)) note)
          (when recovery
            (is (= recovery (:recovery d)) (str note " — names the recovery")))
          (is (= (:attribution react-004) (:where d))
              (str note " — attributed to the bridge, not the shared provider")))))))

(deftest fh-react-004-an-omitted-prop-takes-the-ambient-chain
  (testing "Per FH-REACT-004: with NO `frame` property the exported view
            resolves its frame the ordinary way, so the bridge inserts no
            provider of its own and what the subtree runs under is decided by
            whatever frame boundary the foreign tree already has. The mounted
            sibling proves the resolution; this proves the bridge added
            nothing."
    (let [exported (v/->react views/person-cell)
          answered (exported (obj {"person-id" 1}))]
      (is (not (provider? answered))
          "no provider is inserted — the bridge scopes only what it was told to")
      (is (react/isValidElement answered)
          "and the boundary element is what comes back"))))

(deftest fh-react-004-scoping-creates-no-frame
  (testing "Per FH-REACT-004: SCOPE-only. Exporting and rendering under a frame
            prop leaves the live-frame population exactly where it was — a
            bridge that created a frame when it could not find one would split
            an application into two runtimes that agree about nothing, and the
            symptom would be silence rather than an error."
    (rf/make-frame {:id (:frame-id react-004) :initial-events [[:rf/set-db {}]]})
    (is (true? (:creates-no-frame react-004)))
    (let [before   (frame/frame-ids)
          exported (v/->react views/person-cell)]
      (is (contains? before (:frame-id react-004)) "non-vacuous: the frame is live")
      (exported (obj {"frame" (:frame-id react-004) "person-id" 1}))
      (is (= before (frame/frame-ids))
          "no frame was created, refreshed or destroyed"))))

;; ===========================================================================
;; FH-REACT-005 — the CLJS half: the verb is HERE
;; ===========================================================================

(deftest fh-react-005-the-bridge-is-a-browser-verb
  (testing "Per FH-REACT-005: the JVM half of this law is an absence, and an
            absence is only worth asserting when the presence is asserted too.
            This is that half — the verb the JVM surface does not carry is on
            the ClojureScript one, so the JVM sibling's empty answer cannot
            mean the verb was deleted."
    (is (= "->react" (:var react-005)))
    (is (fn? v/->react) "the door publishes it on this host")
    (is (fn? (v/->react views/person-cell)) "and it answers a component here")
    (is (contains? (set (:absent-on-jvm react-005)) (:var react-005))
        "non-vacuous: the fixture's JVM roster really names this verb")))
