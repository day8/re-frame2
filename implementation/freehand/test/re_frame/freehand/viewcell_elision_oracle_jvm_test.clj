(ns re-frame.freehand.viewcell-elision-oracle-jvm-test
  "THE ELISION ORACLE — proving an ABSENCE with an instrument whose
  discrimination is proven first.

  A compiled view that carries no reactive site omits the reactive
  ViewCell shell. That is an ABSENCE, and an absence is only as good as the
  instrument that looked for it. This suite is that instrument, and it is
  the one Spec 004D §Evidence for the absence prescribes: NOT a production
  bundle text search — identifier text survives into release inside
  docstrings, and Closure inlines small functions, so a symbol can be
  absent while its code is present — but the emitted lowering walked as
  DATA, symbol by symbol at build time, where nothing has been inlined,
  minified, or renamed.

  The emitted lowering is obtained from the REAL cljs `defview*` emit path
  (`{:ns …}` menv selects `emit-cljs/emit-defview`, which returns the
  expansion as data on this JVM host). The reactive discriminator is a
  committed event handler — the publicly reachable reactive site — so the
  ONLY variable between the two emitted views is whether the body carries a
  reactive site, exactly the control the absence claim needs.

  Before the absence is trusted the instrument is proven to discriminate,
  in the SAME emit configuration: it must find a KNOWN-PRESENT symbol,
  report a KNOWN-ABSENT one as absent, and flag a COUNTERFACTUAL lowering
  that DOES name the reactive shell.

  The second half drops to the analyzer tier — with the injected resolver
  the reactive-read forms need — to prove the manifest's `:subscriptions`
  roster populates and flips the ViewCell verdict for a `sub` read, which
  the public event-only census cannot exercise yet."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.compiler :as compiler]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.env :as env]
            [re-frame.freehand.fingerprint :as fingerprint]))

;; ---------------------------------------------------------------------------
;; The instrument: walk the emitted lowering as data
;; ---------------------------------------------------------------------------

(defn- cljs-emit
  "The emitted cljs `defview` lowering for `template`, as DATA."
  [ns-sym vname template]
  (compiler/defview*
   (with-meta (list 'defview vname [] template) {:line 1 :column 1})
   {:ns {:name ns-sym}}
   vname
   (list [] template)))

(defn- symbols-of [form]
  (filter symbol? (tree-seq coll? seq form)))

;; The reactive-shell runtime namespace. Held as a symbol the oracle
;; compares AGAINST — never grepped as text — so the check is a reference
;; test over the emitted data, not a substring test over its printout.
(def ^:private shell-ns 're-frame.freehand.viewcell)

(defn- names-viewcell-shell?
  "The ORACLE. True when the emitted lowering `form` names a reactive
  ViewCell entry point — a symbol whose namespace is the reactive-shell
  runtime namespace. Walked over the emitted DATA: `namespace` returns nil
  for an unqualified symbol and for every non-symbol node, so a keyword, a
  string, or a docstring that happens to contain the shell's name can never
  satisfy this — only a genuine qualified reference does. That is precisely
  why the oracle is a data walk and not a text grep."
  [form]
  (boolean (some #(= (name shell-ns) (namespace %))
                 (filter namespace (symbols-of form)))))

;; ---------------------------------------------------------------------------
;; Prove the instrument discriminates BEFORE trusting its absence
;; ---------------------------------------------------------------------------

(deftest the-oracle-discriminates-known-present-from-known-absent
  (testing "In the same emit configuration the walk must find a symbol we
            KNOW is present and miss one we KNOW is absent — otherwise a
            zero is a mis-targeted probe, not evidence."
    (let [inert (cljs-emit 'app.probe 'inert '[:div "x"])]
      (is (some #(= 're-frame.freehand.runtime/memo-view %) (symbols-of inert))
          "KNOWN-PRESENT: every emitted view names the production memo-view
           constructor, so the walk reaches real symbols")
      (is (not (some #(= 're-frame.freehand.viewcell/does-not-exist %)
                     (symbols-of inert)))
          "KNOWN-ABSENT: a symbol that was never emitted is reported absent"))))

(deftest the-oracle-flags-a-counterfactual-that-names-the-shell
  (testing "The COUNTERFACTUAL: a lowering that DOES wire the reactive
            shell is flagged by the same oracle, so its silence on the
            inert view is a discrimination, not a blind spot."
    (let [reactive (cljs-emit 'app.probe 'evt '[:button {:on-click [:x]} "go"])]
      (is (names-viewcell-shell? reactive)
          "an event-bearing view names the reactive shell")
      (is (some #(= 're-frame.freehand.viewcell/render-events %)
                (symbols-of reactive))
          "specifically the committed-event host render"))))

(deftest the-oracle-is-a-reference-test-not-a-text-grep
  (testing "The probe's own text must not satisfy its own probe. The
            sentinel appears in THIS namespace as prose and as a held
            symbol; because the oracle compares qualified SYMBOLS in the
            emitted data, none of those textual occurrences can be a false
            positive — a string or keyword carrying the shell's name is not
            flagged."
    (is (not (names-viewcell-shell? "re-frame.freehand.viewcell/render-subs"))
        "a STRING carrying the sentinel text is not a reference")
    (is (not (names-viewcell-shell? :re-frame.freehand.viewcell/render-subs))
        "a KEYWORD carrying the sentinel text is not a reference")
    (is (not (names-viewcell-shell? '[:div "re-frame.freehand.viewcell/render-subs"]))
        "the sentinel as literal text in a data structure is not a reference")))

;; ---------------------------------------------------------------------------
;; The absence itself
;; ---------------------------------------------------------------------------

(deftest an-elided-view-names-no-reactive-shell
  (testing "The load-bearing claim: a compiled view with no reactive site
            names no ViewCell entry point in its emitted lowering — so the
            reactive-shell runtime module is unreferenced from it, and a
            bundle of only such views cannot reach it."
    (doseq [template '[[:div "x"]
                       [:section [:h3 "t"] [:p "body"]]
                       [:ul (for [i [1 2 3]] [:li {:key i} i])]]]
      (let [form (cljs-emit 'app.elide 'v template)]
        (is (not (names-viewcell-shell? form))
            (str "no reactive shell in the lowering of " (pr-str template)))))))

(deftest a-reactive-view-retains-the-reactive-shell
  (testing "The other side of the discrimination: the moment a body carries
            a committed event handler, its lowering DOES name the shell —
            the elision is earned by the absence of a reactive site, not
            given by default."
    (let [form (cljs-emit 'app.keep 'v '[:button {:on-click [:go]} "go"])]
      (is (names-viewcell-shell? form)))))

;; ---------------------------------------------------------------------------
;; The manifest's subscription roster (analyzer tier, injected resolver)
;; ---------------------------------------------------------------------------

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
            body reports the empty roster and the elided verdict — the same
            discrimination the emitted-form oracle proves, read off the
            manifest."
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
