(ns re-frame.ui.analyze-accept-cljs-test
  "Template-grammar ACCEPT table: the blessed forms lower into the closed
  AST node set, on both hosts (the analyzer is pure — resolution is
  injected, so this suite runs identically under `clojure -M:test` and
  `npm run test:ui`). Also pins the AST-shape gate (closed op set) and
  the serialisation boundary (AST print/read round-trip)."
  (:require [clojure.test :refer [deftest is testing]]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [re-frame.ui.compiler.analyze :as ana]
            [re-frame.ui.compiler.env :as env]
            [re-frame.ui.fingerprint :as fingerprint]))

(def resolver
  "Injected Q5 resolution stub — fqn + var meta per symbol."
  (fn [sym]
    (case sym
      map         {:fqn 'clojure.core/map :meta {}}
      sub         {:fqn 're-frame.ui/sub :meta {}}
      lease       {:fqn 're-frame.ui/lease :meta {}}
      frame       {:fqn 're-frame.ui/frame :meta {}}
      ;; aliased + fully-qualified spellings resolve to the same vars
      ;; (rf2-vxgfnd.266 head reservation must key on the fqn, not the spelling)
      ui/sub            {:fqn 're-frame.ui/sub :meta {}}
      ui/lease          {:fqn 're-frame.ui/lease :meta {}}
      ui/frame          {:fqn 're-frame.ui/frame :meta {}}
      re-frame.ui/sub   {:fqn 're-frame.ui/sub :meta {}}
      re-frame.ui/lease {:fqn 're-frame.ui/lease :meta {}}
      re-frame.ui/frame {:fqn 're-frame.ui/frame :meta {}}
      raw         {:fqn 're-frame.ui/raw :meta {}}
      html        {:fqn 're-frame.ui/html :meta {}}
      raw-fn      {:fqn 're-frame.ui/raw-fn :meta {}}
      spread      {:fqn 're-frame.ui/spread :meta {}}
      if-let      {:fqn 'clojure.core/if-let :meta {:macro true}}
      ->          {:fqn 'clojure.core/-> :meta {:macro true}}
      frame-provider {:fqn 're-frame.ui/frame-provider :meta {}}
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
  injected resolver ALSO resolves sub/lease/frame to their public reactive
  authoring vars, so this env is the rf2-vxgfnd.274 crux: a head equal to
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
      "fragments accept exactly {:key ...}"))

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
  ;; rf2-vxgfnd.266 — reserving sub/lease/frame heads BEFORE generic component
  ;; classification must not disturb genuine foreign components or ordinary view
  ;; heads: only a head resolving to one of the three public reactive authoring
  ;; vars is reserved. A DIRECT (sub …)/(frame) CALL in child position is still
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
          "a direct (sub …) child is one indexed site, not a reserved head"))
    (let [{:keys [sites]} (ana-full '[:div (:frame (frame))])]
      (is (= 1 (count (:frame-ops sites)))
          "a direct (frame) read is one indexed frame-ops site"))))

(deftest self-head-outranks-reactive-verb-reservation
  ;; rf2-vxgfnd.274 — vector-head precedence. A head equal to the view being
  ;; `defview`d right now (`:self`) classifies as an internal view BEFORE the
  ;; reactive-authoring reservation (rf2-vxgfnd.266) can reject it, EVEN THOUGH
  ;; the same injected resolver resolves that spelling to a public reactive
  ;; authoring var. Self-recursion works because the view Var need not exist yet
  ;; (Q5 rule 1) — so classification here is resolution-free. Reverting the
  ;; precedence to reserved-before-self throws :rf.ui.compile/unsupported-form
  ;; on every accept row below, so this deftest is the mutation fixture.
  (doseq [verb '[sub lease frame]]
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
                                       :capture true}} "x"])]
    (is (= :options (:classification (first (get-in el [:props :events])))))
    (is (true? (:capture? (first (get-in el [:props :events]))))))
  (let [el (ana* '[:button {:on-click (fn [e] e)} "x"])]
    (is (= :fn (:classification (first (get-in el [:props :events]))))
        "bare fns are legal in known native event properties"))
  (let [el (ana* '[:button {:on-click (if a [:x/a] [:x/b])} "x"])]
    (is (= :dynamic (:classification (first (get-in el [:props :events]))))))
  (let [{:keys [ast sites]}
        (ana-full '[:button {:on-click (if (sub [:enabled?]) [:x/go] nil)} "x"])]
    (is (= :dynamic (:classification (first (get-in ast [:props :events])))))
    (is (= 1 (count (:subs sites)))
        "dynamic handler classification runs at render, so its sub is finite")))

(deftest event-sites-index-into-the-manifest
  (let [{:keys [sites]} (ana-full '[:div
                                    [:button {:on-click [:a/b 1]} "x"]
                                    [:input {:on-input (fn [e] e)}]])]
    (is (= 2 (count (:events sites))))
    (is (= [:a/b 1] (:handler (first (:events sites)))))
    (is (true? (:serializable? (first (:events sites)))))
    (is (= :opaque (:handler (second (:events sites)))))
    (is (false? (:serializable? (second (:events sites)))))))

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
  ;; leaves a public ui/sub as an unlowered call at runtime).
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
             (get-in ast [:props :attrs 0 :value]))))))

(deftest legitimate-value-flow-still-compiles
  ;; rf2-vxgfnd.252 — the escape guard rejects ONLY bare reactive authoring
  ;; vars (sub/lease/frame). Ordinary NON-reactive value flow through a computed
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
  ;; named sub/lease/frame) is not the reactive var, so it still compiles and
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
          "(sub :fallback) calls the earlier local sub, never re-frame.ui/sub")))
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
  (testing "a local named sub is an ordinary call, not re-frame.ui/sub"
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
;; (frame) — the compiled operation-bundle body form (rf2-vxgfnd.184)
;; ---------------------------------------------------------------------------

(deftest frame-form-lowers-to-the-runtime-bridge
  (testing "the exact Guide 05 spelling: a let-bound destructured bundle"
    (let [{:keys [ast sites]}
          (ana-full '(let [{:keys [frame dispatch]} (frame)]
                       [:div.bridge (str frame)]))]
      (is (= :let (:op ast)))
      (is (= '(re-frame.ui.frames/frame-ops)
             (second (:bindings ast)))
          "the zero-arg call lowers to the internal frame-ops bridge")
      (is (= 1 (count (:frame-ops sites)))
          "the site is recorded in the compiler manifest")
      (is (some? (:sid (first (:frame-ops sites)))))
      (is (vector? (:expr-path (first (:frame-ops sites)))))))
  (testing "an expression-position read lowers too"
    (let [{:keys [ast sites]}
          (ana-full '[:div {:data-frame (:frame (frame))} "x"])]
      (is (= '(:frame (re-frame.ui.frames/frame-ops))
             (get-in ast [:props :attrs 0 :value])))
      (is (= 1 (count (:frame-ops sites))))))
  (testing "two sites are two manifest rows with distinct sids"
    (let [{:keys [sites]}
          (ana-full '(let [a (frame) b (frame)] [:div (str a b)]))]
      (is (= 2 (count (:frame-ops sites))))
      (is (= 2 (count (distinct (map :sid (:frame-ops sites)))))))))

(deftest frame-shadowing-never-mints-a-frame-site
  (testing "a local named frame is an ordinary call, not re-frame.ui/frame"
    (let [{:keys [ast sites]}
          (ana-full '[:div {:title (let [frame identity] (frame))}])]
      (is (empty? (:frame-ops sites)))
      (is (= '(let [frame identity] (frame))
             (get-in ast [:props :attrs 0 :value])))))
  (testing "the bundle's own destructured :frame key shadows the form after binding"
    (let [{:keys [sites]}
          (ana-full '(let [{:keys [frame]} (frame)] [:div (str frame)]))]
      (is (= 1 (count (:frame-ops sites)))
          "exactly the init call is a site; the bound local is a plain symbol"))))

(deftest html-sites-index
  (testing "ui/html records a manifest site — the profile row's 'manifest
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
  (is (= :raw (:op (ana* '(raw some-element)))))
  (let [el (ana* '[:div.content (html "<b>x</b>")])]
    (is (= :element (:op el)))
    (is (= "<b>x</b>" (get-in el [:html :form])) "sole-child ui/html rides the element"))
  (let [el (ana* '[:div (spread base {:class "x"})])]
    (is (some? (get-in el [:props :spread])))))

(deftest raw-and-raw-fn-props-mark
  (let [v (ana* '[ForeignComp {:el (raw host-el) :cb (raw-fn f)}])]
    (is (= [:foreign :ui/raw-fn]
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
    [:div (spread b o)]
    (raw el)
    [:div.c (html "<hr/>")]]  )

;; ---------------------------------------------------------------------------
;; frame-provider (S2c) — the SCOPE form lowers to :frame-provider anywhere
;; ---------------------------------------------------------------------------

(deftest frame-provider-lowers
  (let [a (ana* '[frame-provider {:frame :app/session} [:p "scoped"]])]
    (is (= :frame-provider (:op a)))
    (is (= :app/session (:frame a)) "the :frame target is carried as a runtime value")
    (is (= [:element] (mapv :op (:children a))) "children analyze normally")
    (is (false? (:static? a)) "a scope boundary is never static"))
  (testing "the :frame target may be a runtime expression"
    (let [a (ana* '[frame-provider {:frame (:id row)} [:span "x"]])]
      (is (= :frame-provider (:op a)))
      (is (= '(:id row) (:frame a)))))
  (testing "a frame-provider indexes a sub site in its :frame expression"
    (let [{:keys [ast sites]} (ana-full '[frame-provider {:frame (sub [:current-frame])}
                                          [:p "x"]])]
      (is (= :frame-provider (:op ast)))
      (is (= 1 (count (:subs sites))) "the (sub ...) in the target is a finite site"))))

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
  (is (= [:rf.ui.compile/bare-fn-in-loop]
         (mapv :id (:warnings (ana-full '(for [x xs]
                                           [:li {:key x :on-click (fn [] x)} x])))))
      "bare fns in loops warn (per-row closures work but defeat the data idiom)")
  (is (= [:rf.ui.compile/placeholder-not-top-level]
         (mapv :id (:warnings (ana-full '[:input {:on-input [:a/b [:rf.ui/value]]}]))))
      "nested placeholder keywords warn — placeholders splice at top level only"))
