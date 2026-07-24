(ns re-frame.freehand.viewcell-elision-oracle-jvm-test
  "THE ELISION ORACLE — proving an ABSENCE with an instrument whose
  discrimination is proven first.

  A compiled view that carries no reactive site omits the reactive
  ViewCell shell. That is an ABSENCE, and an absence is only as good as the
  instrument that looked for it.

  The instrument is the compiled MANIFEST, read as data at build time: the
  `:view-cell` verdict, the `:subscriptions` roster and the `:capabilities`
  set that `structural-manifest` assembles from one analysis of one body.
  Spec 004D §Evidence for the absence rules out the alternative — NOT a
  production bundle text search, because identifier text survives into
  release inside docstrings and Closure inlines small functions, so a symbol
  can be absent while its code is present.

  Discrimination comes from the pairing: every claim below is made against
  BOTH a body that carries the reactive site and one that does not, so a
  reported `:elided` is a difference the instrument can see rather than a
  reading it gives by default. The analyzer runs with the injected resolver
  the reactive-read forms need."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.compiler :as compiler]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.env :as env]
            [re-frame.freehand.fingerprint :as fingerprint]))

(def ^:private resolver
  (fn [sym]
    (case sym
      sub   {:fqn 're-frame.freehand/sub :meta {}}
      frame {:fqn 're-frame.freehand/frame :meta {}}
      nil)))

(defn- manifest-of
  "The compiled manifest `structural-manifest` builds for `template`,
  analyzed with the injected resolver so the reactive-read forms are
  recognised the way the real build recognises them once they are public."
  [template]
  (let [e   (-> (env/make-env {:host :clj :ns-sym 'app.test
                               :self 'v :self-id :app.test/v
                               :source {:file "x.cljc" :line 1 :column 1}
                               :template-anchor (fingerprint/digest "sta1-" (list template))
                               :resolver resolver})
                (assoc :self-children? false :self-closed-keys nil :hooks-region? true))
        ast (ana/analyze-view-body e (list template))]
    (compiler/structural-manifest :app.test/v ast @(:sites e))))

(deftest a-subscription-populates-the-roster-and-flips-the-verdict
  (testing "A `sub` read is a reactive site: it fills the manifest's
            `:subscriptions` roster with the query it read, adds `:sub` to
            the capabilities, and makes the ViewCell PRESENT. A sub-free
            body reports the empty roster and the elided verdict — the
            discrimination the absence claim rests on."
    (let [reads (manifest-of '[:span (sub [:count])])
          inert (manifest-of '[:span "x"])]
      (is (= 1 (count (:subscriptions reads))) "one subscription site recorded")
      (is (= [:count] (:query (first (:subscriptions reads))))
          "the roster carries the query the site read")
      (is (contains? (:capabilities reads) :sub) "capabilities name :sub")
      (is (= :present (:view-cell reads)) "a subscribing view keeps its ViewCell")
      (is (true? (:reactive? reads)))

      (is (empty? (:subscriptions inert)) "no subscription site in the inert body")
      (is (not (contains? (:capabilities inert) :sub)))
      (is (= :elided (:view-cell inert)) "the sub-free view omits its ViewCell")
      (is (false? (:reactive? inert))))))

(deftest a-frame-read-is-reactive-too
  (testing "`(frame)` resolves the committed frame — a reactive site — so
            it populates `:frame-ops` and keeps the ViewCell, the same as a
            subscription."
    (let [m (manifest-of '[:span (str (:frame (frame)))])]
      (is (= 1 (count (:frame-ops m))) "one frame-op site recorded")
      (is (contains? (:capabilities m) :frame))
      (is (= :present (:view-cell m)) "a frame-reading view keeps its ViewCell"))))

(deftest structurally-inert-bodies-report-the-elided-verdict
  ;; The absence itself, over the shapes the deleted emitted-form oracle
  ;; walked: markup, nested markup and a keyed loop carry no reactive site,
  ;; so each reports `:elided` and an empty reactive census. Paired against
  ;; the reactive bodies above, a blanket `:elided` cannot pass unnoticed.
  (doseq [template '[[:div "x"]
                     [:section [:h3 "t"] [:p "body"]]
                     [:ul (for [i [1 2 3]] [:li {:key i} i])]]]
    (let [m (manifest-of template)]
      (is (= :elided (:view-cell m))
          (str "no reactive site in " (pr-str template) " — the ViewCell is omitted"))
      (is (false? (:reactive? m)))
      (is (empty? (:subscriptions m)))
      (is (empty? (:frame-ops m)))))
  (testing "the moment a body carries a committed event handler the verdict flips"
    (let [m (manifest-of '[:button {:on-click [:go]} "go"])]
      (is (= :present (:view-cell m))
          "the elision is earned by the absence of a reactive site, not given by default")
      (is (true? (:reactive? m))))))
