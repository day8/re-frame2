(ns re-frame.freehand.analyze-accept-cljs-test
  "Template-grammar ACCEPT table: the blessed forms lower into the closed
  AST node set, on both hosts (the analyzer is pure — resolution is
  injected, so this suite runs identically under `clojure -M:test` and
  `npm run test:freehand`). Also pins the AST-shape gate (closed op set) and
  the serialisation boundary (AST print/read round-trip)."
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.env :as env]
            [re-frame.freehand.controlled :as controlled]
            [re-frame.freehand.fingerprint :as fingerprint]))

(def resolver
  "Injected Q5 resolution stub — fqn + var meta per symbol."
  (fn [sym]
    (case sym
      map         {:fqn 'clojure.core/map :meta {}}
      sub         {:fqn 're-frame.freehand/sub :meta {}}
      ;; aliased + fully-qualified spellings resolve to the same var
      ;; (rf2-vxgfnd.266 head reservation must key on the fqn, not the spelling)
      v/sub            {:fqn 're-frame.freehand/sub :meta {}}
      re-frame.freehand/sub   {:fqn 're-frame.freehand/sub :meta {}}
      html        {:fqn 're-frame.freehand/html :meta {}}
      raw-fn      {:fqn 're-frame.freehand/raw-fn :meta {}}
      spread      {:fqn 're-frame.freehand/spread :meta {}}
      spread-safe {:fqn 're-frame.freehand/spread-safe :meta {}}
      event       {:fqn 're-frame.freehand/event :meta {}}
      handler     {:fqn 're-frame.freehand/handler :meta {}}
      render-fn   {:fqn 're-frame.freehand/render-fn :meta {}}
      slot        {:fqn 're-frame.freehand/slot :meta {}}
      error-boundary {:fqn 're-frame.freehand/error-boundary :meta {}}
      client-only    {:fqn 're-frame.freehand/client-only :meta {}}
      ..          {:fqn 'clojure.core/.. :meta {:macro true}}
      ;; The if-let binder family (rf2-u53yy.4) — admission is RESOLVER-confirmed,
      ;; so every member resolves to its core var, and a fully-qualified spelling
      ;; resolves to the SAME var (admitted identically).
      if-let      {:fqn 'clojure.core/if-let    :meta {:macro true}}
      when-let    {:fqn 'clojure.core/when-let  :meta {:macro true}}
      if-some     {:fqn 'clojure.core/if-some   :meta {:macro true}}
      when-some   {:fqn 'clojure.core/when-some :meta {:macro true}}
      clojure.core/if-some {:fqn 'clojure.core/if-some :meta {:macro true}}
      ->          {:fqn 'clojure.core/-> :meta {:macro true}}
      child-view  {:fqn 'app.views/child-view
                   :meta {:rf.ui/view true :rf.ui/children? true}}
      leaf-view   {:fqn 'app.views/leaf-view
                   :meta {:rf.ui/view true :rf.ui/children? false}}
      closed-view {:fqn 'app.views/closed-view
                   :meta {:rf.ui/view true
                          :rf.ui/closed-prop-keys [:a :b]}}
      declared-view {:fqn 'app.views/declared-view
                     :meta {:rf.ui/view true}} ; (declare ^:rf.ui/view ...)
      ForeignComp {:fqn 'app.interop/ForeignComp :meta {}}
      var-copy    {:fqn 'app.views/var-copy :meta {}} ; (def var-copy view)
      nil)))

(defn mk-env []
  (-> (env/make-env {:host :clj :ns-sym 'app.test
                     :self 'self-view :self-id :app.test/self-view
                     :resolver resolver})
      (assoc :self-children? false :self-closed-keys nil)))

(defn mk-self-env
  "Like `mk-env` but the view being compiled (`:self`) is `self-sym`. The
  injected resolver ALSO resolves `sub` to its public reactive
  authoring var, so this env is the rf2-vxgfnd.274 crux: a head equal to
  `self-sym` must classify as a self-recursive internal view BEFORE the
  reactive-authoring reservation can reject it."
  [self-sym]
  (-> (env/make-env {:host :clj :ns-sym 'app.test
                     :self self-sym
                     :self-id (keyword "app.test" (name self-sym))
                     :resolver resolver})
      (assoc :self-children? false :self-closed-keys nil)))

(defn ana* [form]
  (ana/analyze (mk-env) form))

(defn ana-full
  "-> {:ast .. :warnings [..] :sites {..}}"
  [form]
  (let [e (mk-env)
        ast (ana/analyze e form)]
    {:ast ast :warnings @(:warnings e) :sites @(:sites e)}))

(defn- analyze-site-fixture [host source template]
  (let [e (-> (env/make-env {:host host :ns-sym 'app.test
                              :self 'self-view :self-id :app.test/self-view
                              :source source
                              :template-anchor
                              (fingerprint/digest "sta1-" template)
                              :resolver resolver})
              (assoc :self-children? false :self-closed-keys nil))
        ast (ana/analyze e template)]
    {:ast ast :sites @(:sites e)}))

(defn- located-sub [line column query]
  (with-meta (list 'sub query)
    {:line line :column column :end-line (+ line 10) :end-column 99}))

;; ---------------------------------------------------------------------------
;; Scalars + basics
;; ---------------------------------------------------------------------------

(deftest scalars-lower
  (is (= {:op :text :value "hi" :static? true} (ana* "hi")))
  (is (= {:op :text :value "3" :static? true} (ana* 3)) "numbers -> JS ToString text")
  (is (= {:op :text :value "3" :static? true} (ana* 3.0)) "integral double -> no .0")
  (is (= {:op :nothing :static? true} (ana* nil)))
  (is (= {:op :nothing :static? true} (ana* false)))
  (is (= {:op :nothing :static? true} (ana* true)) "true renders nothing (pinned)"))

(deftest element-sugar-both-orders
  (let [a (ana* [:div.card#main "x"])
        b (ana* [:div#main.card "x"])]
    (is (= :element (:op a)))
    (is (= (get-in a [:props :class :base-str]) "card"))
    (is (= (get-in b [:props :class :base-str]) "card"))
    (is (= "main" (:value (first (get-in a [:props :attrs])))))
    (is (= "main" (:value (first (get-in b [:props :attrs])))))))

(deftest sugar-class-merges-before-explicit
  (is (= "card extra" (get-in (ana* [:div.card {:class "extra"}])
                              [:props :class :base-str]))
      ".class sugar renders first, then the explicit :class form"))

(deftest flag-map-classes-lexicographic
  (is (= [["alpha" 'a?] ["beta" 'b?]]
         (get-in (ana* [:div {:class {:beta 'b? :alpha 'a?}}])
                 [:props :class :flags]))
      "flag-map entries order lexicographically (map order never trusted)"))

(deftest fragments-lower
  (is (= :fragment (:op (ana* [:<> [:p "a"] [:p "b"]]))))
  (is (true? (get-in (ana* [:<> {:key 'k} [:p "a"]]) [:key :present?]))
      "fragments accept exactly {:key ...}")
  ;; rf2-xoz1s — `:present?` is the KEY's presence, not the props map's. An
  ;; empty props map is legal (only `:key` is admissible, and it is optional)
  ;; and supplies no identity, so it must not report one.
  (is (= :fragment (:op (ana* [:<> {} [:p "a"]])))
      "an empty fragment props map is still a legal fragment")
  (is (false? (get-in (ana* [:<> {} [:p "a"]]) [:key :present?]))
      "[:<> {} …] has a props map and NO key — reporting a key there hands
       every downstream consumer an identity that was never written")
  (is (false? (get-in (ana* [:<> [:p "a"]]) [:key :present?]))
      "and a fragment with no props map at all is likewise keyless"))

;; ---------------------------------------------------------------------------
;; Control forms normalize INTO the AST
;; ---------------------------------------------------------------------------

(deftest control-forms-normalize
  (is (= :if (:op (ana* '(if c [:p "y"] [:p "n"])))))
  (is (= :if (:op (ana* '(if-not c [:p "y"])))))
  (is (= :if (:op (ana* '(when c [:p "y"])))))
  (is (= :if (:op (ana* '(when-not c [:p "y"])))))
  (is (= :if (:op (ana* '(cond a [:p "1"] :else [:p "2"])))))
  (is (= :case (:op (ana* '(case x :a [:p "a"] [:p "d"])))))
  (is (= :let (:op (ana* '(let [x 1] [:p x])))))
  (is (= :letfn (:op (ana* '(letfn [(f [n] (* n 2))] [:p (f 2)])))))
  (is (= :text (:op (ana* '(do "just this")))) "single-form do unwraps")
  (is (= :for (:op (ana* '(for [x xs] [:li {:key x} x]))))))

(deftest q6-for-subgrammar
  (testing "destructuring patterns bind"
    (is (= :for (:op (ana* '(for [{:keys [id]} xs] [:li {:key id} id]))))))
  (testing ":let/:when/:while modifiers"
    (is (= :for (:op (ana* '(for [x xs
                                  :let [y (inc x)]
                                  :when (odd? y)
                                  :while (< y 10)]
                              [:li {:key x} y]))))))
  (testing "multiple binding pairs = nested iteration in ONE keyed site"
    (is (= :for (:op (ana* '(for [x xs, y (f x)] [:li {:key [x y]} y]))))))
  (testing "sub in the FIRST coll expression is one site — legal"
    (let [{:keys [sites]} (ana-full '(for [x (sub [:q])] [:li {:key x} x]))]
      (is (= 1 (count (:subs sites)))))))

(deftest capture-free-vector-in-loop-is-legal
  (is (= :for (:op (ana* '(for [x xs] [:li {:key x :on-click [:list/refresh]} x]))))
      "capture-free literal vectors share one callback across rows"))

;; ---------------------------------------------------------------------------
;; Q5 — head discrimination
;; ---------------------------------------------------------------------------

(deftest q5-head-classification
  (is (= :view (:op (ana* '[child-view {:x 1} [:p "c"]]))))
  (is (= :app.views/child-view (:view-id (ana* '[child-view {}]))))
  (is (= :view (:op (ana* '[self-view {}]))) "self-recursion classifies internal")
  (is (= :view (:op (ana* '[declared-view {}])))
      "(declare ^:rf.ui/view b) marks forward/mutual references internal")
  (is (= :foreign (:op (ana* '[ForeignComp {:anything (quote x)}]))))
  (is (= :foreign (:op (ana* '[var-copy {}])))
      "var copies do not carry view-ness (def does not copy var meta) — foreign"))

(deftest reactive-verb-head-reservation-is-narrow
  ;; rf2-vxgfnd.266 — reserving the `sub` head BEFORE generic component
  ;; classification must not disturb genuine foreign components or ordinary view
  ;; heads: only a head resolving to the public reactive authoring
  ;; var is reserved. A DIRECT (sub …) CALL in child position is still
  ;; the compiler-owned form and mints exactly one manifest site.
  (is (= :foreign (:op (ana* '[ForeignComp {}])))
      "a genuine foreign component head still classifies as :foreign")
  (is (= :view (:op (ana* '[child-view {}])))
      "an ordinary view head is untouched")
  (is (= :foreign (:op (ana* '[var-copy {}])))
      "a non-reactive var copy still classifies as :foreign (unchanged)")
  (testing "the reservation touches ONLY the head form — direct calls still index"
    (let [{:keys [sites]} (ana-full '[:div (sub [:q])])]
      (is (= 1 (count (:subs sites)))
          "a direct (sub …) child is one indexed site, not a reserved head"))))

(deftest self-head-outranks-reactive-verb-reservation
  ;; rf2-vxgfnd.274 — vector-head precedence. A head equal to the view being
  ;; `defview`d right now (`:self`) classifies as an internal view BEFORE the
  ;; reactive-authoring reservation (rf2-vxgfnd.266) can reject it, EVEN THOUGH
  ;; the same injected resolver resolves that spelling to a public reactive
  ;; authoring var. Self-recursion works because the view Var need not exist yet
  ;; (Q5 rule 1) — so classification here is resolution-free. Reverting the
  ;; precedence to reserved-before-self throws :rf.ui.compile/unsupported-form
  ;; on every accept row below, so this deftest is the mutation fixture.
  (doseq [verb '[sub]]
    (let [e   (mk-self-env verb)
          ast (ana/analyze e [verb {}])]
      (is (= :view (:op ast))
          (str "a recursive [" verb " …] self head classifies as an internal view"))
      (is (= (keyword "app.test" (name verb)) (:view-id ast))
          (str verb " registers under the defview's own view id, not the verb var"))
      (is (= verb (:sym ast)) "the authored self spelling is preserved")))
  (testing "a self head nested deep in the template still classifies as a view"
    (let [ast (ana/analyze (mk-self-env 'sub) '[:div [:section [sub {}]]])]
      (is (= :view (get-in ast [:children 0 :children 0 :op]))
          "self precedence is carried through the whole template walk")))
  (testing "a self head accepts children when the view declares them"
    (let [ast (ana/analyze (assoc (mk-self-env 'sub) :self-children? true)
                           '[sub {} [:p "kid"]])]
      (is (= :view (:op ast)))
      (is (= 1 (count (:children ast))) "self-recursion with declared children")))
  (testing "a self head mints NO reactive site — it is a view, not a sub read"
    (let [e   (mk-self-env 'sub)
          _   (ana/analyze e '[sub {}])]
      (is (empty? (:subs @(:sites e)))
          "the self head does not leak the public authoring var into the manifest"))))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(deftest handler-classification-table
  (let [el (ana* '[:button {:on-click [:cart/add id]} "x"])]
    (is (= :vector (:classification (first (get-in el [:props :events])))))
    (is (true? (:serializable? (first (get-in el [:props :events]))))))
  (let [el (ana* '[:input {:on-input [:form/typed :email :rf.ui/value]}])]
    (is (true? (:hoistable? (first (get-in el [:props :events]))))
        "literal/placeholder-only vectors hoist"))
  (let [el (ana* '[:button {:on-click {:event [:x/y] :prevent-default true
                                       :capture true :once true}} "x"])]
    (is (= :options (:classification (first (get-in el [:props :events])))))
    (is (true? (:capture? (first (get-in el [:props :events])))))
    (is (true? (get-in el [:props :events 0 :form :once]))))
  (let [el (ana* '[:button {:on-wheel {:event [:x/scroll]
                                       :passive true}} "x"])
        h  (first (get-in el [:props :events]))]
    (is (= :options (:classification h)))
    (is (true? (:passive? h))
        "literal passive maps select the narrow native-listener seam"))
  (let [el (ana* '[:button {:on-click (fn [e] e)} "x"])]
    (is (= :fn (:classification (first (get-in el [:props :events]))))
        "bare fns are legal in known native event properties"))
  (let [el (ana* '[:input {:on-input (event [e] [:form/typed (.. e -target -value)])}])
        h  (first (get-in el [:props :events]))]
    (is (= :ui-event (:classification h))
        "a v/event handler is its own compiler-known committed-callback class")
    (is (false? (:serializable? h)))
    (is (false? (:hoistable? h)))
    (is (= '(re-frame.freehand.events/callback
             :event (fn [e] [:form/typed (.. e -target -value)]) 1)
           (:form h))
        (str "the v/event body lowers to the ROSTER CONSTRUCTOR the interpreted "
             "v/event macro expands to — not the bare fn it carries, which "
             "event-plan classifies :bare-fn and fires without dispatching")))
  (let [el (ana* '[:button {:on-click (if a [:x/a] [:x/b])} "x"])]
    (is (= :dynamic (:classification (first (get-in el [:props :events]))))))
  (let [{:keys [ast sites]}
        (ana-full '[:button {:on-click (if (sub [:enabled?]) [:x/go] nil)} "x"])]
    (is (= :dynamic (:classification (first (get-in ast [:props :events])))))
    (is (= 1 (count (:subs sites)))
        "dynamic handler classification runs at render, so its sub is finite")))

;; ---------------------------------------------------------------------------
;; S3 explicit callback boundaries (rf2-vxgfnd.95.3) — v/handler, and the
;; committed callbacks + C-13a opaque fn-props at component seams
;; ---------------------------------------------------------------------------

(deftest ui-handler-at-a-dom-site
  (let [el (ana* '[:button {:on-click (handler [e] (.preventDefault e))} "x"])
        h  (first (get-in el [:props :events]))]
    (is (= :handler (:classification h))
        "v/handler — the explicit imperative committed callback (bare-fn shorthand)")
    (is (false? (:serializable? h)))
    (is (= '(re-frame.freehand.events/callback
             :handler (fn [e] (.preventDefault e)) 1)
           (:form h))
        (str "the body lowers to the roster constructor v/handler expands to — one "
             "lowering for the form, at the whole-handler position as everywhere else")))
  ;; v/handler is NOT a controlled-input sync-door class (imperative, not a vector)
  (let [el (ana* '[:input {:value v :on-input (handler [e] (do-something e))}])
        h  (first (get-in el [:props :events]))]
    (is (= :handler (:classification h)))
    (is (not (:sync? h)) "an imperative v/handler never rides the sync door")))

(deftest c13a-internal-fn-props-are-legal-opaque-values
  (testing "a bare fn between INTERNAL views is a legal opaque value (marker nil)"
    (let [en (first (get-in (ana* '[child-view {:cb (fn [x] x)}]) [:props :entries]))]
      (is (nil? (:marker en)) "no framework invocation phase — an ordinary value")
      (is (false? (:literal? en)))))
  (testing "a foreign boundary still rejects a bare fn prop"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                 (ana* '[ForeignComp {:cb (fn [x] x)}])))))

(deftest committed-callbacks-at-component-seams
  (testing "v/event at a foreign prop is a committed event callback"
    (let [{:keys [ast sites]}
          (ana-full '[ForeignComp {:on-select (event [e] [:sel/pick e])}])
          en (first (get-in ast [:props :entries]))]
      (is (= :ui-event (:marker en)))
      (is (= 'fn (first (:callback-fn en))) "the body lowers to a fn")
      (is (= 1 (count (:events sites))) "it records an event site")))
  (testing "v/handler at a foreign prop is an imperative committed callback"
    (let [en (first (get-in (ana* '[ForeignComp {:on-open (handler [a b] (do-it a b))}])
                            [:props :entries]))]
      (is (= :handler (:marker en)))
      (is (= '[a b] (second (:callback-fn en))) "the full fixed arg list binds through")))
  (testing "v/event / v/handler are equally legal at an INTERNAL-view seam"
    (let [en (first (get-in (ana* '[child-view {:cb (handler [x] (use-it x))}])
                            [:props :entries]))]
      (is (= :handler (:marker en))
          "a per-site stable identity at an internal-view seam (C-13a)")))
  (testing "v/event at a foreign prop capturing a loop binding is rejected"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                 (ana* '(for [row rows]
                          [ForeignComp {:key (:id row)
                                        :on-select (event [e] [:pick (:id row)])}]))))))

;; ---------------------------------------------------------------------------
;; v/spread at a FOREIGN component call site (rf2-u53yy.5)
;; ---------------------------------------------------------------------------

(deftest foreign-spread-admits-literal-part-plus-opaque-forwarded-map
  (testing "the literal part is analysed normally (compiled handler + props); "
           "the forwarded runtime map is opaque and marks the site dynamic"
    (let [{:keys [ast sites]}
          (ana-full '[ForeignComp
                      (spread {:selected date
                               :on-change (handler [v] (pick! v))}
                              forwarded-props)])
          en (first (get-in ast [:props :entries]))]
      (is (= :foreign (:op ast)) "a foreign head with spread is a foreign component")
      (is (= 'forwarded-props (get-in ast [:props :spread :base]))
          "the forwarded runtime map is carried opaque as the spread base")
      (is (some #(= :on-change (:k %)) (get-in ast [:props :entries]))
          "the literal part's keys are analysed as ordinary call-site props")
      (is (= :handler (:marker (some #(when (= :on-change (:k %)) %)
                                     (get-in ast [:props :entries]))))
          "a v/handler in the literal part compiles to a committed callback")
      (is (some #(= :spread (:classification %)) (:events sites))
          (str "the forwarded map records ONE opaque :spread event site — "
               "the manifest marks the call site :dynamic"))))
  (testing "the plain forwarded-map form has no literal part"
    (let [ast (ana* '[ForeignComp (spread forwarded-props)])]
      (is (= :foreign (:op ast)))
      (is (= 'forwarded-props (get-in ast [:props :spread :base])))
      (is (empty? (get-in ast [:props :entries])) "no literal part → no entries")))
  (testing "a lone literal map spelled through spread has NO forwarded part"
    (let [ast (ana* '[ForeignComp (spread {:a 1})])]
      (is (= :foreign (:op ast)))
      (is (nil? (get-in ast [:props :spread]))
          "a literal-only spread carries no opaque forwarded map")
      (is (= [:a] (mapv :k (get-in ast [:props :entries])))
          "its keys are ordinary literal call-site props")))
  (testing "children ride the same foreign spread call site"
    (let [ast (ana* '[ForeignComp (spread {:a 1} m) [:p "kid"]])]
      (is (= :foreign (:op ast)))
      (is (= 1 (count (:children ast))) "positional children still analyse")))
  (testing "an internal view rejects spread (literal props required)"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                 (ana* '[child-view (spread {:a 1} m)])))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                 (ana* '[leaf-view (spread m)]))))
  (testing "a bare fn in a foreign spread's literal part is still rejected"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                 (ana* '[ForeignComp (spread {:on-select (fn [x] x)} m)])))))

;; ---------------------------------------------------------------------------
;; v/error-boundary + v/client-only (rf2-vxgfnd.95.3)
;; ---------------------------------------------------------------------------

(deftest error-boundary-lowers
  (let [a (ana* '(error-boundary {:fallback child-view :reset-key k
                                  :on-error [:err/log :ctx]}
                                 [:div "guarded"]))]
    (is (= :error-boundary (:op a)))
    (is (= :view (:kind (:fallback a))) "the fallback resolves to a defview")
    (is (true? (:has-reset-key? a)))
    (is (= 'k (:reset-key a)) "the reset-key is a carried runtime value")
    (is (= [:err/log :ctx] (:on-error a)) "the on-error event vector is carried")
    (is (= :element (get-in a [:child :op])) "the single guarded child analyzes"))
  (testing "minimal form: just a fallback + child"
    (let [a (ana* '(error-boundary {:fallback child-view} [:p "x"]))]
      (is (= :error-boundary (:op a)))
      (is (false? (:has-reset-key? a)))
      (is (nil? (:on-error a)))))
  (testing "an on-error arg indexes its sub site (evaluated when the closure builds)"
    (let [{:keys [ast sites]}
          (ana-full '(error-boundary {:fallback child-view
                                      :on-error [:err/log (sub [:ctx])]}
                                     [:p "x"]))]
      (is (= :error-boundary (:op ast)))
      (is (= 1 (count (:subs sites))) "the (sub …) in :on-error is a finite site"))))

(deftest client-only-lowers
  (let [a (ana* '(client-only {:fallback [:div.skeleton "loading…"]}
                              [ForeignComp {:p 1}]))]
    (is (= :client-only (:op a)))
    (is (= :element (get-in a [:fallback :op])) "the capability-free fallback analyzes")
    (is (= :foreign (get-in a [:child :op])) "the browser-only client subtree analyzes"))
  (testing "the fallback may be a static internal view or plain markup"
    (is (= :client-only (:op (ana* '(client-only {:fallback [:span "…"]} [:div "live"]))))))
  (testing "a capability in the fallback is a compile error"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                 (ana* '(client-only {:fallback [:p (sub [:q])]} [:div "live"])))
        "a reactive read in the fallback would tear on hydration")))

(deftest deferred-callback-bodies-accept-opaque-host-macros
  ;; A deferred callback (v/event / bare fn) is opaque host code: interop and
  ;; other non-admitted macros (.. , doto, …) that a RENDER body rejects pass
  ;; through here verbatim, because sub/frame — the only things lexical analysis
  ;; protects — are already illegal in deferred scope. The canonical v/event
  ;; payload `(.. e -target -value)` therefore compiles. (The admitted if-let
  ;; family is analyzed, not passed through — see the admitted-family deftest.)
  (testing "a v/event body keeps its interop macro verbatim"
    (let [el (ana* '[:input {:on-input
                             (event [e] (conj on-value (.. e -target -value)))}])
          h  (first (get-in el [:props :events]))]
      (is (= :ui-event (:classification h)))
      ;; `(events/callback :event (fn [e] …) 1)` — the carried fn is the third
      ;; element, and its body the last form of that fn.
      (is (= '(conj on-value (.. e -target -value)) (last (nth (:form h) 2)))
          "the interop macro survives into the compiled fn body")))
  (testing "a bare fn handler body keeps its interop macro verbatim"
    (let [el (ana* '[:button {:on-click (fn [e] (.. e -target -value))} "x"])]
      (is (= :fn (:classification (first (get-in el [:props :events])))))))
  (testing "a render-body opaque macro is still rejected (the deferred/render split)"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                 (ana* '[:div {:title (.. x -y -z)}])))))

(deftest if-let-binder-family-is-admitted
  ;; rf2-u53yy.4 — if-let / when-let / if-some / when-some are admitted
  ;; conditional binders in BOTH grammar tiers. Each desugars into the
  ;; analyzer's OWN let + if/when: the binding init owns a finite reactive site,
  ;; the pattern binds into the then/body branch only, and the some?-variants
  ;; test the raw init value with some? (never destructure-then-test). The closed
  ;; grammar is unchanged — out-of-family macros still reject (see the reject
  ;; table); this admits exactly the four named forms and nothing more.
  (testing "template position desugars through the analyzer's own let, init is a finite site"
    (doseq [form '[(if-let    [u (sub [:user])] [:p "hi"] [:p "bye"])
                   (if-some   [u (sub [:user])] [:p "hi"] [:p "bye"])
                   (when-let  [u (sub [:user])] [:p "hi"])
                   (when-some [u (sub [:user])] [:p "hi"])
                   (if-let    [u (sub [:user])] [:p "hi"])]] ; no else -> nothing
      (let [{:keys [ast sites]} (ana-full form)]
        (is (= :let (:op ast)) (str "desugars through the analyzer's let: " form))
        (is (= :if (get-in ast [:body :op])) (str "the branch is a conditional: " form))
        (is (= 1 (count (:subs sites)))
            (str "the binding init is a single finite reactive site: " form)))))
  (testing "some?-variants test the raw init with a HOST-QUALIFIED core nil test; truthy-variants test it bare"
    ;; The generated nil test is `(clojure.core/not= temp nil)` (host :clj here) —
    ;; a namespace-qualified symbol no user local can shadow, and a plain function
    ;; (not the cljs.core `some?`/`nil?` MACROS) so it survives re-analysis.
    (let [t (get-in (:ast (ana-full '(if-some [u v] [:p "a"] [:p "b"]))) [:body :test])]
      (is (= 'clojure.core/not= (first t)) "if-some's nil test is host-qualified core not=")
      (is (= 3 (count t)) "shape is (not= temp nil)")
      (is (nil? (nth t 2)) "compared against the nil literal"))
    (is (= 'clojure.core/not=
           (first (get-in (:ast (ana-full '(when-some [u v] [:p "a"])))
                          [:body :test])))
        "when-some tests (clojure.core/not= temp nil)")
    (is (symbol? (get-in (:ast (ana-full '(if-let [u v] [:p "a"] [:p "b"])))
                         [:body :test]))
        "if-let tests the bare temp")
    (is (= :if (get-in (:ast (ana-full '(when-let [u v] [:p "a"]))) [:body :op]))
        "a when-* form lowers to `if … nil` (NOT a generated `when`) so no user local named `when` can capture the branch")
    (is (= :nothing (get-in (:ast (ana-full '(when-let [u v] [:p "a"])))
                            [:body :else :op]))
        "a when-* form renders nothing on the falsy branch (no else)"))
  (testing "admission is resolver-confirmed: a fully-qualified core binder is admitted"
    (let [{:keys [ast]} (ana-full '(clojure.core/if-some [u v] [:p "a"] [:p "b"]))]
      (is (= :let (:op ast)) "the qualified core binder desugars like the bare spelling")
      (is (= 'clojure.core/not= (first (get-in ast [:body :test])))
          "and its generated nil test is host-safe too")))
  (testing "expression position (a prop value) lowers the init's reactive site"
    (doseq [form '[[:div {:title (if-let    [x (sub [:q])] x "none")}]
                   [:div {:title (if-some   [x (sub [:q])] x "none")}]
                   [:div {:title (when-let  [x (sub [:q])] x)}]
                   [:div {:title (when-some [x (sub [:q])] x)}]]]
      (let [{:keys [ast sites]} (ana-full form)]
        (is (= 1 (count (:subs sites))) (str "prop-value init lowers one site: " form))
        (is (re-find #"re-frame.freehand.reactive/sub-read" (pr-str ast))
            (str "the lowered runtime site is present: " form)))))
  (testing "the binding pattern is still host-consumed — a reactive escape rejects"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                 (ana* '(if-let [{:keys [x] :or {x (sub [:q])}} m] [:p x] [:p "no"])))
        "a (sub …) manufactured in an :or default cannot own a lexical site")
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                 (ana* '[:div {:title (when-let [{:keys [x] :or {x (sub [:q])}} m] x)}]))
        "the pattern escape rejects in expression position too"))
  (testing "a malformed binding vector fails loudly (bad-let)"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                 (ana* '(if-let [a b c] [:p "x"] [:p "y"])))
        "the binding is a single [pattern init] pair")))

(deftest if-let-family-desugar-is-hygienic
  ;; rf2-u53yy.4 audit repair (chronological reopen 2026-07-21) — the desugar
  ;; emits ONLY host-safe generated semantics (a host-qualified core `(not= temp
  ;; nil)` test and the special form `if`), so no hostile user local can capture
  ;; the compiler-generated test/branch. Without the repair, the generated bare
  ;; `(some? temp)` was captured by `(let [some? (constantly false)] (if-some [x
  ;; 1] x :else))` (choosing the wrong arm), and a generated `when` could become
  ;; an ordinary user call.
  (testing "a user local named `not=` does NOT capture the some?-variant's nil test"
    (let [e   (update (mk-env) :locals conj 'not=)
          ast (ana/analyze e '(if-some [x v] [:p "a"] [:p "b"]))]
      (is (= 'clojure.core/not= (first (get-in ast [:body :test])))
          "the generated nil test stays the qualified core `not=`, never the shadowing local (which would flip `(if-some [x 1] …)` to the wrong arm)")))
  (testing "a user local named `when` cannot capture the when-* branch — it lowers to `if`"
    (let [e   (update (mk-env) :locals conj 'when)
          ast (ana/analyze e '(when-let [x v] [:p "a"]))]
      (is (= :if (get-in ast [:body :op]))
          "the single-body when-* shape is `(if test body nil)` — `if` is a special form and unshadowable, so no generated `when` exists to capture")))
  (testing "a LOCAL shadow of the family spelling falls through to an ordinary call (not admitted)"
    (let [e (update (mk-env) :locals conj 'if-let)]
      (is (not= :let (:op (ana/analyze e '(if-let [x v] [:p "a"] [:p "b"]))))
          "a shadowed spelling is an ordinary call, never the admitted binder"))))

(deftest event-sites-index-into-the-manifest
  (let [{:keys [sites]} (ana-full '[:div
                                    [:button {:on-click [:a/b 1]} "x"]
                                    [:input {:on-input (fn [e] e)}]])]
    (is (= 2 (count (:events sites))))
    (is (= [:a/b 1] (:handler (first (:events sites)))))
    (is (true? (:serializable? (first (:events sites)))))
    (is (= :opaque (:handler (second (:events sites)))))
    (is (false? (:serializable? (second (:events sites)))))))

(deftest event-sites-carry-runtime-and-tool-identity
  (let [{:keys [ast sites]}
        (analyze-site-fixture
         :cljs {:file "src/app/view.cljs" :line 10 :column 2}
         '[:input {:value value
                   :on-input [:form/typed :rf.ui/value]}])
        handler (first (get-in ast [:props :events]))
        site    (first (:events sites))]
    (is (= 0 (:site-index handler))
        "the generated production callback key is a compact per-view index")
    (is (= (:sid handler) (:sid site)))
    (is (string? (:sid site))
        "dev tooling and HMR use a deterministic lexical site id")
    (is (= :app.test/self-view (:view-id site)))
    (is (= {:file "src/app/view.cljs" :line 10 :column 2}
           (:source-coord site)))
    (is (= [] (:path site)))
    (is (true? (:sync? handler))
        "literal controlled :on-input vector sites open the one sync door")))

(deftest controlled-input-sync-door-is-compiler-proven-and-narrow
  (testing "the literal-vector + literal controlled-prop predicate"
    (is (true? (-> (ana* '[:input {:value value
                                    :on-change [:form/typed :rf.ui/value]}])
                   (get-in [:props :events 0 :sync?]))))
    (is (true? (-> (ana* '[:input {:checked checked?
                                   :on-change [:form/check]}])
                   (get-in [:props :events 0 :sync?])))))
  (testing "an options map carrying a vector rides the door (D009 fact 4)"
    (is (true? (-> (ana* '[:input {:value value
                                   :on-input {:event [:form/typed :rf.ui/value]
                                              :prevent-default true}}])
                   (get-in [:props :events 0 :sync?])))))
  (testing ":on-before-input is OUTSIDE the door, and gets no unactionable nag"
    ;; beforeinput fires BEFORE the DOM mutation, so target.value is not
    ;; generally the candidate value — admitting it needs its own
    ;; projection and composition contract, not a name added to a list.
    (let [{:keys [ast warnings]}
          (ana-full '[:input {:checked checked? :on-before-input [:form/check]}])]
      (is (false? (get-in ast [:props :events 0 :sync?])))
      (is (empty? warnings)
          "no door could have opened here, so there is nothing to advise")))
  (testing "a synchronous v/event body rides the same door as a literal vector"
    ;; The widening (readiness P0-2): the site proof stays static (literal
    ;; controlled prop co-present); the appended prefix/payload stay runtime.
    (let [{:keys [ast warnings]}
          (ana-full '[:input {:value value
                              :on-input (event [e]
                                          (conj on-value (.. e -target -value)))}])]
      (is (= :ui-event (get-in ast [:props :events 0 :classification])))
      (is (true? (get-in ast [:props :events 0 :sync?]))
          "a controlled v/event site opens the one sync door")
      (is (empty? warnings)
          "a proven v/event door emits no async-handler fallback warning"))
    (is (true? (-> (ana* '[:input {:checked checked?
                                   :on-change (event [e] [:prefs/set (.. e -target -checked)])}])
                   (get-in [:props :events 0 :sync?])))))
  (testing "an uncontrolled v/event site simply batches, with no diagnostic"
    (let [{:keys [ast warnings]}
          (ana-full '[:input {:on-input (event [e] [:log (.. e -target -value)])}])]
      (is (= :ui-event (get-in ast [:props :events 0 :classification])))
      (is (false? (get-in ast [:props :events 0 :sync?])))
      (is (empty? warnings))))
  (testing "a capture/passive listener is a different attachment lane"
    (is (false? (-> (ana* '[:input {:value value
                                    :on-input {:event [:form/typed] :capture true}}])
                    (get-in [:props :events 0 :sync?]))))
    (is (false? (-> (ana* '[:input {:value value
                                    :on-input {:event [:form/typed] :passive true}}])
                    (get-in [:props :events 0 :sync?])))))
  (testing "the door is a fact about the TAG too — value on a :div is not a control"
    (let [{:keys [ast warnings]}
          (ana-full '[:div {:value value :on-input [:form/typed :rf.ui/value]}])]
      (is (false? (get-in ast [:props :events 0 :sync?])))
      (is (empty? warnings) "a :div has no value React restores, so no advisory")))
  (testing "ordinary sites and non-data handlers leave :sync? unproven"
    (is (false? (-> (ana* '[:button {:value value :on-click [:form/go]}])
                    (get-in [:props :events 0 :sync?]))))
    (let [{:keys [ast warnings]}
          (ana-full '[:input {:value value
                              :on-input handler-value}])]
      (is (false? (get-in ast [:props :events 0 :sync?])))
      (is (= [:rf.ui.compile/controlled-input-async-handler]
             (mapv :id warnings))
          "a fallback site names the exact conditions it failed to prove")
      ;; rf2-r0775 — the recovery guidance must name BOTH sync-door forms
      ;; (#{:vector :ui-event} per controlled-event-sync?): a literal event
      ;; vector OR a (v/event …) handler. A reusable control that needs the
      ;; native payload may not be expressible as a literal vector, so the
      ;; diagnostic must not prescribe only the vector door.
      (let [msg (:msg (first warnings))]
        (is (re-find #"literal event vector" msg)
            "the diagnostic still names the literal-event-vector door")
        (is (re-find #"v/event" msg)
            "the diagnostic ALSO names the (v/event …) door")
        ;; rf2-pv0ne — the advisory reports the loss of STATIC EVIDENCE, not
        ;; a change of dispatch lane. The emitted element facts are constants
        ;; and `controlled/door?` decides at COMMIT from the runtime handler
        ;; value, so a site the analyzer cannot classify still reaches the
        ;; door in both modes; what it cannot do is prove anything first.
        (is (re-find #"(?i)opaque" msg)
            "the diagnostic names the opaque site")
        (is (not (re-find #"(?i)batch" msg))
            "and never claims the site moves to the batched path")))
    (testing "a bare fn at a controlled site is unprovable with the diagnostic"
      (let [{:keys [ast warnings]}
            (ana-full '[:input {:value value :on-input (fn [e] (js/console.log e))}])]
        (is (false? (get-in ast [:props :events 0 :sync?])))
        (is (= [:rf.ui.compile/controlled-input-async-handler]
               (mapv :id warnings)))))))

;; ---------------------------------------------------------------------------
;; The door reads the SLOT it is about to emit into (rf2-drpa3.119)
;; ---------------------------------------------------------------------------

(def ^:private controlled-spellings
  "Every authored key below reaches the SAME React prop the exact spelling
  reaches. A namespace is dropped on the way to the DOM, so `:x/value` IS
  `value`: the node it sits on is controlled, and both modes have to say
  so or `{:compiled true}` would move a field between the two dispatch
  lanes."
  [{:note "the exact spelling"                        :k :value         :controlled? true}
   {:note "a namespaced alias reaches the same prop"  :k :x/value       :controlled? true}
   {:note "checked is the second controlled slot"     :k :checked       :controlled? true}
   {:note "and its aliased spelling counts too"       :k :x/checked     :controlled? true}
   {:note ":default-value seeds an UNCONTROLLED input" :k :default-value :controlled? false}
   {:note "and an aliased :default-value is still it"  :k :x/default-value :controlled? false}
   {:note "an ordinary attribute is not a control"    :k :title         :controlled? false}])

(defn- door-form [k v]
  [:input {k v :on-input [:form/typed :rf.ui/value]}])

(deftest the-compiled-door-judges-normalized-prop-slots
  (testing "The compiled analyzer decides CONTROLLED through the shared
            normalized-slot rule, not by comparing raw keys. An aliased
            `:x/value` is written into React's own `value` prop and makes
            the node controlled; judging the authored keyword instead left
            the site batched while the emitted prop said otherwise."
    (doseq [{:keys [note k controlled?]} controlled-spellings]
      (is (= controlled?
             (-> (ana* (door-form k 'v)) (get-in [:props :events 0 :sync?])))
          (str note " — " k))))
  (testing "PRESENCE, not truth: an explicit nil on an aliased controlled
            slot is a controlled empty value, exactly as the exact spelling
            is."
    (is (true? (-> (ana* (door-form :x/value nil))
                   (get-in [:props :events 0 :sync?])))))
  (testing "the emitted prop slot and the verdict are ONE decision — the
            analyzer judged the very name it then writes"
    (let [ast (ana* (door-form :x/value 'v))]
      (is (= "value" (get-in ast [:props :attrs 0 :react-name])))
      (is (true? (get-in ast [:props :events 0 :sync?])))))
  (testing "the async-handler advisory follows the same rule: an aliased
            controlled prop with an unprovable handler IS advised, because
            the door could have opened there"
    (let [{:keys [ast warnings]}
          (ana-full '[:input {:x/value value :on-input handler-value}])]
      (is (false? (get-in ast [:props :events 0 :sync?])))
      (is (= [:rf.ui.compile/controlled-input-async-handler] (mapv :id warnings)))))
  (testing "and a site the door can never admit still gets no unactionable
            nag, whatever the spelling"
    (let [{:keys [ast warnings]}
          (ana-full '[:div {:x/value value :on-input [:form/typed]}])]
      (is (false? (get-in ast [:props :events 0 :sync?])))
      (is (empty? warnings)))))

(deftest both-modes-agree-on-which-spellings-make-a-node-controlled
  (testing "The interpreted walk asks
            `re-frame.freehand.controlled/controlled-props?` over the
            authored keys; the compiled analyzer now asks the same
            predicate over the same keys. Asserting the two answers TOGETHER
            is what makes promotion parity a fact rather than two lists that
            happen to agree today."
    (doseq [{:keys [note k controlled?]} controlled-spellings]
      (is (= controlled? (controlled/controlled-props? [k]))
          (str "interpreted — " note))
      (is (= (controlled/controlled-props? [k])
             (-> (ana* (door-form k 'v)) (get-in [:props :events 0 :sync?])))
          (str "the two modes agree — " note)))))

(deftest sub-sites-index
  (let [{:keys [sites]} (ana-full '[:div
                                    [:h1 (sub [:title])]
                                    [:p {:data-n (sub [:count])} "x"]])]
    (is (= 2 (count (:subs sites))))
    (is (= [[:title]] (map :query (take 1 (:subs sites)))))))

(deftest computed-callee-subs-index-into-the-manifest
  ;; rf2-vxgfnd.217 — Clojure evaluates a COMPUTED callee before its arguments,
  ;; so the function/callee position is an ordinary evaluated expression that can
  ;; host a finite render-time (sub …). It must be rewritten + indexed under a
  ;; stable :callee token, never preserved verbatim with an empty manifest (which
  ;; leaves a public v/sub as an unlowered call at runtime).
  (testing "an (if …) computed callee with one sub → exactly one indexed site"
    (let [{:keys [sites]} (ana-full '[:div {:title ((if (sub [:op]) inc dec) 1)}])
          site (first (:subs sites))]
      (is (= 1 (count (:subs sites))))
      (is (= [:op] (:query site)))
      (is (some? (:sid site)) "the site carries a deterministic compiler-owned sid")
      (is (some #{:callee} (:expr-path site))
          "the site is credited to the callee position, not an argument")))
  (testing "a str-wrapped computed callee in a child expression indexes too"
    (let [{:keys [sites]} (ana-full '[:p (str ((if (sub [:operation]) inc dec) 1))])]
      (is (= 1 (count (:subs sites))))
      (is (= [[:operation]] (map :query (:subs sites))))))
  (testing "computed vector / map / set callee expressions index their visible subs"
    (is (= 1 (count (:subs (:sites (ana-full '[:div {:title ([(sub [:a]) dec] 0)}]))))))
    (is (= 1 (count (:subs (:sites (ana-full '[:div {:title ({(sub [:k]) :v} :x)}]))))))
    (is (= 1 (count (:subs (:sites (ana-full '[:div {:title (#{(sub [:s])} 1)}])))))))
  (testing "the callee-site sid is host-portable (CLJ == CLJS) and finite"
    (let [tmpl  '[:div {:title ((if (sub [:op]) inc dec) 1)}]
          clj   (analyze-site-fixture :clj  {:line 10 :column 1} tmpl)
          cljs  (analyze-site-fixture :cljs {:line 10 :column 1} tmpl)
          sid   #(get-in % [:sites :subs 0 :sid])]
      (is (= 1 (count (get-in clj [:sites :subs]))))
      (is (= (sid clj) (sid cljs))
          "host is not part of the callee site's lexical identity")))
  (testing "a plain symbol head keeps its ordinary (non-callee) argument paths"
    (let [{:keys [sites]} (ana-full '[:div {:title (str (sub [:s]))}])
          site (first (:subs sites))]
      (is (= 1 (count (:subs sites))))
      (is (not (some #{:callee} (:expr-path site)))
          "a symbol head is preserved verbatim — no :callee token is minted"))))

(deftest letfn*-flat-bindings-parse-with-their-real-lexical-grammar
  ;; rf2-vxgfnd.221 — the HOST special form letfn* has FLAT name/initializer
  ;; bindings [name init …], NOT source letfn's paired fnspec-LIST grammar. The
  ;; opaque-expression rewriter parses the flat shape directly; a mutation that
  ;; routes letfn* back through the source-letfn parser crashes / mis-scopes
  ;; these fixtures, so they double as the anti-regression guard. Runs on both
  ;; hosts (the analyzer is pure), so this IS the CLJ/CLJS parity fixture.
  (testing "a legal flat letfn* compiles and preserves its flat binding shape"
    (let [{:keys [ast sites]}
          (ana-full '[:div {:title (letfn* [f (fn* f ([x] x))] (f 7))}])]
      (is (empty? (:subs sites)))
      (is (= '(letfn* [f (fn* f ([x] x))] (f 7))
             (get-in ast [:props :attrs 0 :value]))
          "the flat [name init …] vector is preserved verbatim")))
  (testing "a visible sub in the OUTER letfn* body lowers to exactly one site"
    (let [{:keys [sites]}
          (ana-full '[:div {:title (letfn* [f (fn* f ([x] x))] (sub [:q]))}])]
      (is (= 1 (count (:subs sites))))
      (is (= [[:q]] (map :query (:subs sites))))))
  (testing "mutually recursive flat bindings each see every declared name"
    (let [{:keys [ast]}
          (ana-full '[:div {:title (letfn* [evn (fn* evn ([x] (odd* x)))
                                            odd* (fn* odd* ([x] (evn x)))]
                                     (evn 4))}])]
      (is (= '(letfn* [evn (fn* evn ([x] (odd* x)))
                       odd* (fn* odd* ([x] (evn x)))]
                (evn 4))
             (get-in ast [:props :attrs 0 :value]))
          "each initializer resolves its sibling name; the flat shape is kept")))
  (testing "a local flat binding named sub shadows the public authoring var"
    (let [{:keys [ast sites]}
          (ana-full '[:div {:title (letfn* [sub (fn* sub ([x] x))] (sub 3))}])]
      (is (empty? (:subs sites)) "the shadowed sub mints no reactive site")
      (is (= '(letfn* [sub (fn* sub ([x] x))] (sub 3))
             (get-in ast [:props :attrs 0 :value])))))
  (testing "rf2-rgqn9 — single-arity and multi-arity fn* initializers are legal"
    ;; The bounded shape validator must permit BOTH the `[argv] body` single
    ;; arity and the `([argv] body …)` arity-list forms — not only the named
    ;; multi-arity shape the earlier fixtures used.
    (let [{:keys [ast sites]}
          (ana-full '[:div {:title (letfn* [f (fn* [x] x)
                                            g (fn* ([] 0) ([x] x))]
                                     (f (g)))}])]
      (is (empty? (:subs sites)))
      (is (= '(letfn* [f (fn* [x] x)
                       g (fn* ([] 0) ([x] x))]
                (f (g)))
             (get-in ast [:props :attrs 0 :value]))
          "flat bindings with single- and multi-arity fn* initializers are kept verbatim"))))

(deftest legitimate-value-flow-still-compiles
  ;; rf2-vxgfnd.252 — the escape guard rejects ONLY bare reactive authoring
  ;; vars (sub/frame). Ordinary NON-reactive value flow through a computed
  ;; callee, a let alias, or an argument stays legal and mints no reactive site,
  ;; and a DIRECT reactive call passed as a value still lowers to one site.
  (testing "a non-reactive computed callee is ordinary value flow (no site)"
    (let [{:keys [ast sites]} (ana-full '[:div {:title ((if p inc dec) 1)}])]
      (is (empty? (:subs sites)))
      (is (= '((if p inc dec) 1) (get-in ast [:props :attrs 0 :value]))
          "the non-reactive callee is preserved verbatim")))
  (testing "a let-bound non-reactive alias is ordinary value flow (no site)"
    (let [{:keys [ast sites]} (ana-full '[:div {:title (let [f inc] (f 1))}])]
      (is (empty? (:subs sites)))
      (is (= '(let [f inc] (f 1)) (get-in ast [:props :attrs 0 :value])))))
  (testing "a DIRECT reactive call passed as an argument still lowers to a site"
    (let [{:keys [sites]} (ana-full '[:div {:title (str (sub [:n]))}])]
      (is (= 1 (count (:subs sites)))
          "the direct (sub …) is a compiler-owned call head, not an escaping var")
      (is (= [[:n]] (map :query (:subs sites)))))))

(deftest legitimate-or-default-shadow-still-compiles
  ;; rf2-dzyqis — reject-reactive-binding! now also rejects a BARE reactive
  ;; authoring var used as a destructuring :or default, but the reject is
  ;; binding-position-AWARE: a local the pattern itself BINDS (a lexical shadow
  ;; named sub/frame) is not the reactive var, so it still compiles and
  ;; mints no reactive site.
  (testing "a local named sub bound by the pattern is an ordinary shadow (no site)"
    (let [{:keys [sites]} (ana-full '[:div {:title (let [{:keys [sub]} m] (str sub))}])]
      (is (empty? (:subs sites))
          "the destructured local sub is a value, never a reactive read")))
  (testing "an :or default referencing a same-pattern shadow compiles (no site)"
    (let [{:keys [sites]} (ana-full '[:div {:title (let [{:keys [sub a] :or {a sub}} m] a)}])]
      (is (empty? (:subs sites)))))
  (testing "a literal :or default is untouched (no site)"
    (let [{:keys [sites]} (ana-full '[:div {:title (let [{:keys [x] :or {x 0}} m] x)}])]
      (is (empty? (:subs sites))))))

(deftest destructuring-earlier-bound-shadow-compiles
  ;; rf2-vxgfnd.268 — the reverse of the reject rows: a same-pattern local bound
  ;; EARLIER in host evaluation order genuinely shadows the reactive authoring
  ;; var, so its later use in a default is an ordinary local reference, mints no
  ;; reactive site, and compiles. The pre-fix `reactive-call-kind` scan used no
  ;; same-pattern scope (`#{}`), so the earlier-local function-call row was
  ;; UNDER-shadowed and wrongly rejected; the ordered scope restores it.
  (testing "a bare default referencing an EARLIER-bound local shadow (no site)"
    (let [{:keys [sites]}
          (ana-full '[:div {:title (let [{:keys [sub f] :or {f sub}} m] f)}])]
      (is (empty? (:subs sites))
          "sub binds before f, so f's default is the local, not the reactive var")))
  (testing "a function CALL on an earlier-bound local shadow (no site)"
    (let [{:keys [sites]}
          (ana-full '[:div {:title (let [{:keys [sub f] :or {f (sub :fallback)}} m] f)}])]
      (is (empty? (:subs sites))
          "(sub :fallback) calls the earlier local sub, never re-frame.freehand/sub")))
  (testing "an explicit lookup key referencing an earlier-bound local (no site)"
    (let [{:keys [sites]}
          (ana-full '[:div {:title (let [{sub :s x sub} m] x)}])]
      (is (empty? (:subs sites))
          "x's lookup key `sub` is the local bound by the earlier {sub :s} entry"))))

(deftest lexical-site-id-is-portable-stable-and-query-independent
  (let [template-a [:div (located-sub 102 8 [:item/by-id 'id])]
        template-b [:div (located-sub 202 8 [:item/by-id 'id])]
        clj-a (analyze-site-fixture :clj {:line 100 :column 1} template-a)
        cljs-a (analyze-site-fixture :cljs {:line 100 :column 1} template-a)
        moved (analyze-site-fixture :clj {:line 200 :column 1} template-b)
        changed-query
        (analyze-site-fixture
         :clj {:line 100 :column 1}
         [:div (located-sub 102 8 [:item/by-id 'other-id])])
        sid #(get-in % [:sites :subs 0 :sid])]
    (is (= (sid clj-a) (sid cljs-a))
        "host is not part of lexical identity")
    (is (= (sid clj-a) (sid moved))
        "moving the whole declaration preserves relative source identity")
    (is (= (sid clj-a) (sid changed-query))
        "the query value is destination data, never ownership identity")
    (is (= (fingerprint/template-fingerprint
            (ana/template-fingerprint-projection (:ast clj-a)))
           (fingerprint/template-fingerprint
            (ana/template-fingerprint-projection (:ast moved))))
        "source/site movement does not perturb semantic template identity")
    (is (not=
         (fingerprint/template-fingerprint
          (ana/template-fingerprint-projection (:ast clj-a)))
         (fingerprint/template-fingerprint
          (ana/template-fingerprint-projection (:ast changed-query))))
        "site id is projected out, but the semantic query remains")))

(deftest quoted-runtime-sub-lookalikes-are-fingerprint-opaque
  (let [runtime  're-frame.freehand.reactive/sub-read
        quoted   (with-meta
                   (list 'quote (list runtime :quoted/sid [:quoted/query]))
                   {:line 17 :column 9})
        ast      {:op :expr :form (list 'str quoted)}
        projected (ana/template-fingerprint-projection ast)
        digest   (fn [sid query]
                   (fingerprint/template-fingerprint
                    (ana/template-fingerprint-projection
                     {:op :expr
                      :form (list 'str
                                  (list 'quote (list runtime sid query)))})))]
    (is (= ast projected)
        "fingerprint projection preserves quoted internal-looking data exactly")
    (is (identical? quoted (second (:form projected)))
        "the quote form itself is opaque, preserving its spelling and metadata")
    (is (apply distinct?
               [(digest :quoted/sid-a [:quoted/query])
                (digest :quoted/sid-b [:quoted/query])
                (digest :quoted/sid-a [:quoted/other-query])])
        "quoted lookalike sid and query data both remain part of build identity")))

(deftest metadata-loss-reacquires-safely-instead-of-transferring-an-ordinal
  (let [old (analyze-site-fixture :clj {:line 1} '[:div (sub [:a])])
        edited (analyze-site-fixture
                :clj {:line 1} '[:div (sub [:b]) (sub [:a])])
        old-sid (get-in old [:sites :subs 0 :sid])
        new-sids (mapv :sid (get-in edited [:sites :subs]))]
    (is (= 2 (count (distinct new-sids)))
        "equal/different destinations never collapse distinct lexical paths")
    (is (not (some #{old-sid} new-sids))
        "without reader anchors the whole-template fallback changes all ids;
         no bare preorder ordinal can transfer A's ownership to inserted B")))

(deftest lexical-shadowing-never-mints-a-reactive-site
  (testing "a local named sub is an ordinary call, not re-frame.freehand/sub"
    (let [{:keys [ast sites]}
          (ana-full '[:div {:title (let [sub identity] (sub query))}])]
      (is (empty? (:subs sites)))
      (is (= '(let [sub identity] (sub query))
             (get-in ast [:props :attrs 0 :value])))))
  (testing "catch bindings receive the same lexical-shadow treatment"
    (let [{:keys [sites]}
          (ana-full '[:div {:title (try value
                                    (catch Exception sub (sub query)))}])]
      (is (empty? (:subs sites))))))

(deftest ordinary-destructuring-defaults-remain-legal
  (let [{:keys [sites]}
        (ana-full '(let [{:keys [x] :or {x (str "fallback")}} value]
                     [:div x]))]
    (is (empty? (:subs sites)))))

;; ---------------------------------------------------------------------------
;; (frame) — RETIRED (rf2-h1ae3). `re-frame.freehand/frame` is published on
;; neither host and the arm lowered to `re-frame.freehand.frames/frame-ops`, a
;; namespace that exists nowhere, so the recognition was dead at both ends. A
;; body spelling it now walks as an ORDINARY opaque call, indexing nothing —
;; which is what `re-frame.freehand.unpublished-head-absence-jvm-test` proves
;; through the production door rather than an injected resolver.
;; ---------------------------------------------------------------------------

(deftest a-frame-call-is-an-ordinary-opaque-call
  (testing "the retired spelling survives verbatim and mints no site of any kind"
    (let [{:keys [ast sites]}
          (ana-full '[:div {:title (re-frame.freehand/frame)}])]
      (is (= '(re-frame.freehand/frame)
             (get-in ast [:props :attrs 0 :value]))
          "the authored call is passed through, not lowered to a bridge")
      (is (empty? (mapcat val sites))
          "and no site bucket records it"))))

(deftest html-sites-index
  (testing "v/html records a manifest site — the profile row's 'manifest
  site recording' sub-assertion: every visible escaping bypass is listed with
  its source/template path and serialisability flag"
    (let [{:keys [sites]} (ana-full '[:div
                                      [:section.a (html "<b>x</b>")]
                                      [:aside.b (html "<i>y</i>")]])]
      (is (= 2 (count (:htmls sites))) "both bypass sites recorded")
      (is (= "<b>x</b>" (:form (first (:htmls sites)))))
      (is (true? (:static? (first (:htmls sites)))))
      (is (true? (:serializable? (first (:htmls sites))))
          "a literal-string bypass is serialisable data")
      (is (vector? (:path (first (:htmls sites))))
          "the site carries its template path")))
  (testing "a non-string (dynamic) html argument records a non-serialisable site"
    (let [{:keys [sites]} (ana-full '[:div.c (html markup)])]
      (is (= 1 (count (:htmls sites))))
      (is (false? (:static? (first (:htmls sites)))))
      (is (false? (:serializable? (first (:htmls sites))))))))

;; ---------------------------------------------------------------------------
;; Interop forms
;; ---------------------------------------------------------------------------

(deftest interop-forms-lower
  (let [el (ana* '[:div.content (html "<b>x</b>")])]
    (is (= :element (:op el)))
    (is (= "<b>x</b>" (get-in el [:html :form])) "sole-child v/html rides the element"))
  (let [el (ana* '[:div (spread base {:class "x"})])]
    (is (some? (get-in el [:props :spread])))))

;; ---------------------------------------------------------------------------
;; v/spread-safe — the literal safe-spread policy (S3, rf2-isdqjv)
;; ---------------------------------------------------------------------------

(deftest spread-safe-lowers-owned-props-plus-guarded-caller
  (let [el (ana* '[:input.form-control
                   (spread-safe {:value v :on-change [:set :rf.ui/value] :type "text"}
                                attr)])]
    (is (= :element (:op el)))
    (is (false? (:static? el)) "a runtime caller map is never static")
    (testing "the OWNED map is analysed as normal element props"
      (is (= "text" (:value (first (filter #(= :type (:k %)) (get-in el [:props :attrs]))))))
      (is (some? (get-in el [:props :class])) "sugar + owned class present"))
    (testing "the caller map rides a :safe-spread slot with the owned-handler keys"
      (is (some? (get-in el [:props :safe-spread])))
      (is (= #{:on-change} (get-in el [:props :safe-spread :owned-handler-keys])))
      (is (contains? (get-in el [:props :safe-spread]) :base) "the walked caller form"))))

(deftest spread-safe-retains-the-sync-door-general-spread-forfeits-it
  (testing "policy form: the owned controlled handler keeps the sync door"
    (let [el (ana* '[:input (spread-safe {:value v :on-change [:set :rf.ui/value]} attr)])]
      (is (true? (get-in el [:props :events 0 :sync?]))
          "the compiler-proven controlled owned site RETAINS the sync door")
      (is (some? (get-in el [:props :safe-spread])))))
  (testing "general spread at the SAME site forfeits it (one opaque site, no sync)"
    (let [el (ana* '[:input (spread {:value v :on-change [:set :rf.ui/value]} attr)])]
      (is (some? (get-in el [:props :spread])))
      (is (empty? (get-in el [:props :events]))
          "no compiled per-site handler — the site is opaque")
      (is (not (true? (get-in el [:props :spread :sync?])))
          "a general spread never opens the sync door")))
  (testing "a v/event owned handler also keeps the door under the policy form"
    (let [el (ana* '[:input (spread-safe {:checked c
                                          :on-change (event [e] [:set (.. e -target -checked)])}
                                         attr)])]
      (is (true? (get-in el [:props :events 0 :sync?]))))))

(deftest spread-safe-non-controlled-carries-only-the-structural-deny
  ;; No owned handler -> owned-handler-keys is empty; only the fixed four are denied.
  (let [el (ana* '[:div.card (spread-safe {:role "region"} attr)])]
    (is (= #{} (get-in el [:props :safe-spread :owned-handler-keys])))
    (is (false? (:static? el)))))

(deftest spread-safe-literal-caller-denies-alternate-spellings
  ;; rf2-izep3 — the COMPILE-TIME literal-caller denial shares the canonicalized
  ;; grammar (`rules/spread-safe-denied-key?`), so a namespaced/string/symbol
  ;; alias of a denied key is rejected at compile time, not only the exact
  ;; keyword. Owned :on-change makes on-change aliases denied too.
  (doseq [caller '[{:x/ref "r"} {"ref" "r"} {:some/value "v"} {"checked" "c"}
                   {:evil/on-change [:hijack]}]]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                 (ana* [:input (list 'spread-safe
                                     {:value 'v :on-change [:set :rf.ui/value]}
                                     caller)]))
        (str "literal alternate spelling denied at compile: " (pr-str caller))))
  (testing "an exact allowed literal caller (incl. a string spelling) still analyzes"
    (is (some? (ana* '[:div.card (spread-safe {:role "region"}
                                              {:aria-label "x" "data-y" "y"})])))))

(deftest raw-fn-prop-marks-and-a-plain-value-does-not
  ;; rf2-4gnrs retired the `(v/raw …)` prop recognition and the `:foreign`
  ;; marker it alone minted: a runtime React ELEMENT crosses a boundary
  ;; unwrapped, so it is an ordinary walked value under the nil marker.
  (let [v (ana* '[ForeignComp {:el host-el :cb (raw-fn f)}])]
    (is (= [nil :v/raw-fn]
           (mapv :marker (get-in v [:props :entries]))))))

;; ---------------------------------------------------------------------------
;; The AST-shape gate + serialisation boundary
;; ---------------------------------------------------------------------------

(def fixture-templates
  '[[:div.card#main {:style {:padding 16} :data-x 1 :on-click [:a/b]}
     "text" 42
     (when c [:p "cond"])
     (for [x xs] [:li {:key x} x])
     [child-view {:v (quote data)} [:em "child"]]
     [ForeignComp {:p 1}]
     [:<> [:i "f1"] [:i "f2"]]]
    (let [x 1] (case x 1 [:p "one"] [:p "other"]))
    (letfn [(f [n] n)] [:p (f 1)])
    (if-let [u (sub [:user])] [:p "hi"] [:p "bye"])
    [:div {:title (when-some [x (sub [:q])] x)}]
    [:div (spread b o)]
    [:input.form-control (spread-safe {:value v :on-change [:set :rf.ui/value]} attr)]
    [:div.c (html "<hr/>")]
    [:ul (slot row-renderer 3 item) (slot (render-fn [x] [:li x]) item)]]  )

;; ---------------------------------------------------------------------------
;; Compiled render slots (S3, rf2-ri0k6n) — v/render-fn + v/slot
;; ---------------------------------------------------------------------------

(deftest render-fn-prop-lowers-to-a-compiled-slot-callback
  (testing "a v/render-fn prop value compiles its body into a slot callback"
    (let [v (ana* '[child-view {:row (render-fn [i x] [:li.item i x])}])
          e (first (get-in v [:props :entries]))]
      (is (= :render-fn (:marker e)) "the entry is marked a compiled render slot")
      (is (= '[i x] (get-in e [:render-fn :params])) "params carried verbatim")
      (is (= :element (get-in e [:render-fn :body :op]))
          "the body is a COMPILED template node, not an opaque expression")
      (is (nil? (:value e)) "a render-fn prop carries no plain :value")))
  (testing "an ordinary function prop at a FOREIGN boundary is still the bare-fn error"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
                 (ana* '[ForeignComp {:row (fn [i x] [:li i x])}]))
        "a foreign component invokes it at an unknown phase — reject")))

(deftest ui-slot-lowers-and-indexes-its-site
  (testing "v/slot over a prop-carried render-fn value"
    (let [{:keys [ast sites]}
          (ana-full '[:ul (slot row-renderer 3 item)])
          slot (first (get-in ast [:children]))]
      (is (= :slot (:op slot)))
      (is (= 'row-renderer (:slot-value slot)) "the runtime slot value is carried")
      (is (= 2 (count (:args slot))) "the runtime args are carried")
      (is (nil? (:render-fn slot)) "a prop-carried slot has no inline body")
      (is (= 1 (count (:slots sites))) "the slot site indexes into the manifest")
      (is (false? (:inline? (first (:slots sites)))))
      (is (vector? (:path (first (:slots sites)))) "the site carries its template path")))
  (testing "v/slot over an INLINE v/render-fn compiles the body here"
    (let [{:keys [ast sites]}
          (ana-full '[:ul (slot (render-fn [x] [:li x]) item)])
          slot (first (get-in ast [:children]))]
      (is (= :slot (:op slot)))
      (is (= '[x] (get-in slot [:render-fn :params])))
      (is (= :element (get-in slot [:render-fn :body :op])) "inline body is compiled")
      (is (true? (:inline? (first (:slots sites)))))))
  (testing "nil is a legal v/slot value (renders nothing)"
    (let [ast (ana* '[:ul (slot nil)])]
      (is (= :slot (:op (first (:children ast)))))
      (is (nil? (get-in ast [:children 0 :slot-value]))))))

(deftest slot-args-are-not-deferred-so-the-owning-view-may-read-them
  ;; A slot ARGUMENT is the LIBRARY view's own render-time expression — a
  ;; (sub …) there is the library's finite read, indexed into ITS manifest.
  (let [{:keys [ast sites]} (ana-full '[:ul (slot row-renderer (sub [:selected]))])]
    (is (= :slot (:op (first (:children ast)))))
    (is (= 1 (count (:subs sites)))
        "the sub in a slot ARG is the owning view's finite site, not deferred")))

(deftest static-defview-head-mounts-inside-a-slot-body
  ;; THE grammar requirement (rf2-a62fje coverage): a slot body must permit a
  ;; statically-referenced internal view head — a stateful replacement part is
  ;; a PURE slot body mounting a static defview that owns its own state. If this
  ;; regressed, the wave-2 registered-ui/view gate analysis would have to redo.
  ;; NOTE the matched slot arity: each inline render-fn declares exactly the
  ;; parameters the slot supplies (1 arg → 1 param), the fixed-arity contract
  ;; (rf2-ckviw).
  (let [ast (ana* '[:ul (slot (render-fn [x] [child-view {:v x} [:em "kid"]]) item)])
        slot (first (:children ast))
        body (get-in slot [:render-fn :body])]
    (is (= :slot (:op slot)))
    (is (= :view (:op body)) "the slot body mounts an internal view head")
    (is (= :app.views/child-view (:view-id body)))
    (testing "a slot body may also mount a static view under control flow / for"
      (let [ast2 (ana* '[:ul (slot (render-fn [xs]
                                     (for [x xs] [child-view {:key (:id x) :v x}]))
                                   items)])
            body2 (get-in ast2 [:children 0 :render-fn :body])]
        (is (= :for (:op body2)))
        (is (= :view (get-in body2 [:body :op])) "the keyed for row is an internal view")))))

(defn- ops-of [ast]
  (let [acc (atom #{})]
    ((fn walk [n]
       (when (map? n)
         (when-let [op (:op n)] (swap! acc conj op))
         (doseq [[_ v] n]
           (cond (map? v) (walk v)
                 (vector? v) (doseq [x v] (walk x))))))
     ast)
    @acc))

(deftest ast-shape-gate-closed-op-set
  (doseq [tpl fixture-templates]
    (let [ast (ana* tpl)]
      (is (every? ana/node-ops (ops-of ast))
          (str "every op within the closed set for " (pr-str tpl))))))

(deftest ast-serialisation-boundary
  (doseq [tpl fixture-templates]
    (let [ast (ana* tpl)]
      (is (= ast (edn/read-string {:default (fn [_ v] v)} (pr-str ast)))
          "the AST survives a print/read round-trip (no fn objects, ever)"))))

;; ---------------------------------------------------------------------------
;; Warnings (collected, not thrown)
;; ---------------------------------------------------------------------------

(deftest warning-table
  ;; the row is a `:button` so the S4-C a11y roster stays silent and this
  ;; assertion isolates the ONE warning it is about
  (is (= [:rf.ui.compile/bare-fn-in-loop]
         (mapv :id (:warnings (ana-full '(for [x xs]
                                           [:button {:key x :on-click (fn [] x)} x])))))
      "bare fns in loops warn (per-row closures work but defeat the data idiom)")
  (is (= [:rf.ui.compile/placeholder-not-top-level]
         (mapv :id (:warnings (ana-full '[:input {:on-input [:a/b [:rf.ui/value]]}]))))
      "nested placeholder keywords warn — placeholders splice at top level only"))
