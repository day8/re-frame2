(ns re-frame.freehand.react-lowering-jvm-test
  "The React emitter lowers EVERY node kind `:re-frame.freehand/v1` admits.

  `re-frame.freehand.compiler.emit-react` walks the normalized AST with a
  `case` that carries no default arm, and that is deliberate: the grammar
  check refuses every node kind outside v1 before emission, so a missing
  arm is not a fallback, it is a crash. `case` states it as
  `IllegalArgumentException: No matching clause`, thrown during MACRO
  EXPANSION of the ClojureScript compile — so a declaration using the
  unlowered form does not render wrongly, it fails to compile, with a
  diagnostic that names neither the view nor the form.

  A `v/slot` in a `{:compiled true}` body was exactly that: the structural
  emitter had lowered it since the slots slice shipped, and the browser
  emitter had no `:slot` arm at all.

  So this suite drives one source body per admitted op through the analyzer
  and the React emitter, and asserts the table COVERS
  [[re-frame.freehand.compiler.grammar/admitted-ops]] exactly. Widening the
  grammar without widening the emitter fails here, at the emitter, rather
  than in a consumer's ClojureScript build.

  It runs on the JVM against the `:clj` resolver because the two emitters
  consume the SAME normalized AST — the analysis under test is the analysis
  a ClojureScript expansion performs, and only the resolver that reads the
  namespace's aliases differs. The mounted browser half of the claim is
  `re-frame.freehand.compiled-slot-dom-cljs-test`.

  Normative owner:
  [`spec/004D-Freehand-Compiled-Grammar.md`](../../../../../spec/004D-Freehand-Compiled-Grammar.md)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand :as v]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.emit-react :as emit-react]
            [re-frame.freehand.compiler.env :as env]
            [re-frame.freehand.compiler.grammar :as grammar]))

(v/defview leaf
  "A declared child, so a `:view` crossing has something to cross to."
  [{:keys [label]}]
  [:i label])

(defn- analyzed
  "The normalized AST for one view body, analysed exactly as a compiled
  declaration in THIS namespace would be."
  [body]
  (let [e (-> (env/make-env {:host            :clj
                             :cljs-env        nil
                             :ns-sym          're-frame.freehand.react-lowering-jvm-test
                             :self            'subject
                             :self-id         ::subject
                             :template-anchor "react-lowering"})
              (assoc :self-children? true :hooks-region? true)
              (env/with-locals '#{props}))
        ast (ana/analyze-view-body e [body])]
    (grammar/check! e ::subject ast)
    [e ast]))

(defn- ops
  "Every `:op` the analyzed AST carries."
  [ast]
  (let [found (volatile! #{})]
    (walk/postwalk (fn [x]
                     (when (and (map? x) (keyword? (:op x)))
                       (vswap! found conj (:op x)))
                     x)
                   ast)
    @found))

(def rows
  "One source body per admitted node kind. `:op` is the kind the row
  exists to reach — asserted to really be in the analysed AST, so a row
  that stopped producing its own node kind cannot keep passing."
  [{:op :text     :body '[:div "text"]}
   {:op :nothing  :body '[:div nil]}
   {:op :expr     :body '[:div (:label props)]}
   {:op :element  :body '[:div.card {:id "x"} "a"]}
   {:op :fragment :body '[:<> [:i "a"] [:b "b"]]}
   {:op :view     :body '[:div [leaf {:label "a"}]]}
   {:op :for      :body '[:ul (for [i (:items props)] [:li {:key i} i])]}
   {:op :if       :body '[:div (if (:flag props) [:i "y"] [:b "n"])]}
   {:op :let      :body '[:div (let [x (:label props)] [:i x])]}
   {:op :letfn    :body '[:div (letfn [(f [] "x")] [:i (f)])]}
   {:op :case     :body '[:div (case (:kind props) :a [:i "a"] [:b "z"])]}
   {:op :presence :body '(v/presence {:timeout-ms 120} [:div {:key "a"} "x"])}
   ;; The framework host attachment: `[v/behavior {…} node]` lowers to
   ;; `:op :behavior`, and the browser emitter reaches the shared behaviors
   ;; runtime (rf2-drpa3.116/.127). `:use` need not be registered to COMPILE —
   ;; registration is the render's read, exactly as the interpreted twin's is.
   {:op :behavior :body '[v/behavior {:use :x/y :target :t} [:div "x"]]}
   ;; The row this file was written for: an inline render-fn, whose body is
   ;; lowered by THIS emitter. The prop-carried half — the render-fn authored
   ;; at a CALL SITE, which the analyzer records as an analysed template
   ;; rather than a value — has its own claim below.
   {:op :slot     :body '[:div (v/slot (v/render-fn [r] [:span r]) (:label props))]}])

(deftest the-react-emitter-lowers-every-admitted-node-kind
  (testing "The emitter's `case` has no default arm, so an unlowered kind
            is a macro-expansion crash rather than a diagnostic. Every kind
            the grammar admits is emitted here, and the failure below names
            the kind."
    (doseq [{:keys [op body]} rows]
      (let [[e ast] (analyzed body)]
        (is (contains? (ops ast) op)
            (str op " — the row really produces the node kind it names"))
        (is (some? (emit-react/emit-react-body e '[props] ast))
            (str op " — the React emitter has an arm for it"))))))

(deftest the-table-covers-the-whole-admitted-roster
  (testing "A per-kind table is only as good as its coverage, so the
            coverage is the assertion: widening `admitted-ops` without
            widening this table — and the emitter it drives — fails here."
    (is (= grammar/admitted-ops (into #{} (map :op) rows))
        "one row per admitted node kind, and no row outside the roster")))

;; ---------------------------------------------------------------------------
;; The OTHER axis — one row per props CARRIER
;; ---------------------------------------------------------------------------
;;
;; The table above is per node KIND, and a per-kind table has a structural
;; blind spot: `grammar/check!` walks `:op`, and so does the coverage
;; assertion, but the content an author writes mostly lives INSIDE a node's
;; `:props`. `:element` is admitted, so an emitter that never read
;; `(:spread props)` produced a perfectly well-formed `:element` with the
;; whole forwarded attribute map missing, and every row above stayed green.
;;
;; Three defects of exactly that shape landed in a row — a call-site
;; `v/render-fn` emitting `nil`, `v/spread` / `v/spread-safe` dropped
;; entirely, a call-site `v/event` emitting `nil` — so the table gained the
;; second axis. Each row names a carrier, proves the analyzed AST really
;; FILLS it, and asserts the emitted form REACHES the lowering that carrier
;; requires. The coverage assertions hold the tables to
;; `grammar/element-props-carriers` and `grammar/crossing-prop-markers`,
;; exactly as the kind table is held to `grammar/admitted-ops`.

(defn- emitted
  "The React lowering of one body, as text to look for calls in."
  [body]
  (let [[e ast] (analyzed body)]
    (pr-str (emit-react/emit-react-body e '[props] ast))))

(def element-rows
  "One source body per ELEMENT props carrier. `:reaches` is the call that
  proves the emitter read it — a per-carrier claim, because `some?` on the
  whole emission proves nothing: a dropped carrier still emits an element."
  [{:carrier :attrs       :body '[:div {:title (:t props)}]
    :reaches "compiled-react/attr!"}
   {:carrier :class       :body '[:div {:class (:c props)}]
    :reaches "compiled-react/class!"}
   {:carrier :style       :body '[:div {:style (:s props)}]
    :reaches "compiled-react/style!"}
   {:carrier :events      :body '[:div {:on-click [:app/go]}]
    :reaches "re-frame.freehand.reactive/event-site"}
   {:carrier :key         :body '[:div {:key (:k props)}]
    :reaches "cljs.core/unchecked-set"}
   {:carrier :spread      :body '[:div.sugar (v/spread (:attrs props) {:class "c"})]
    :reaches "re-frame.freehand.node/spread-attrs"}
   {:carrier :safe-spread :body '[:input (v/spread-safe {:value "v"} (:attrs props))]
    :reaches "re-frame.freehand.node/safe-caller-attrs"}])

(def crossing-rows
  "One source body per CROSSING prop marker. `:reaches` is what the emitted
  props map must carry for the prop to arrive at all."
  [{:marker nil          :body '[:div [leaf {:label (:l props)}]]
    :reaches ":label (:l props)"}
   {:marker :render-fn   :body '[:div [leaf {:row (v/render-fn [r] [:span r])}]]
    :reaches ":render-fn"}
   {:marker :ui-event    :body '[:div [leaf {:on-pick (v/event [x] [:app/picked x])}]]
    :reaches ":event"}
   {:marker :handler     :body '[:div [leaf {:on-done (v/handler [x] (prn x))}]]
    :reaches ":handler"}
   {:marker :foreign     :body '[:div [leaf {:node (v/raw (host-element))}]]
    :reaches "(host-element)"}
   {:marker :v/raw-fn    :body '[:div [leaf {:cb (v/raw-fn (:f props))}]]
    :reaches "re-frame.freehand.events/raw-fn"}])

(defn- carriers-of
  "Every ELEMENT props carrier the analyzed AST actually filled."
  [ast]
  (let [found (volatile! #{})]
    (walk/postwalk (fn [x]
                     (when (= :element (:op x))
                       (doseq [[k v] (:props x)
                               :when (and (contains? grammar/element-props-carriers k)
                                          (if (= :key k) (:present? v) (seq v)))]
                         (vswap! found conj k)))
                     x)
                   ast)
    @found))

(deftest the-react-emitter-reads-every-element-props-carrier
  (testing "A carrier the emitter never reads is not a crash and not a
            diagnostic — it is a well-formed element missing exactly what
            the author put in that slot. So each row proves its carrier is
            really in the analyzed props, then proves the emission reaches
            the lowering that carrier requires."
    (doseq [{:keys [carrier body reaches]} element-rows]
      (let [[_ ast] (analyzed body)]
        (is (contains? (carriers-of ast) carrier)
            (str carrier " — the row really fills the carrier it names")))
      (is (.contains ^String (emitted body) ^String reaches)
          (str carrier " — the emitted body reaches " reaches)))))

(deftest the-react-spread-lowering-threads-the-id-sugar-fact
  (testing "rf2-5r1af #6837 audit. The compiled React path folds `v/spread`
            through `node/spread-attrs` and never reaches the element-fold id
            check the interpreted React walk makes, so a forwarded id beside
            `#id` sugar slipped through — the sugar id silently dropped, no
            refusal. The fix threads the element's sugar fact into the shared
            seam, where the runtime refusal lives. Here we prove the WIRING:
            the emitted spread-attrs call carries the tag and the sugar id, so
            the seam has what it needs to refuse. Before the fix the sugar id
            was absent from the emission entirely — a non-vacuous oracle."
    (let [text (emitted '[:div#hero (v/spread (:attrs props))])]
      (is (.contains ^String text "re-frame.freehand.node/spread-attrs")
          "the react lowering still folds through the shared seam")
      (is (.contains ^String text "\"hero\"")
          "and threads the #id sugar value into it — dropped before the fix")
      (is (.contains ^String text ":div \"hero\"")
          "the tag and sugar id ride together as the seam's element context"))))

(deftest the-react-emitter-lowers-every-crossing-prop-marker
  (testing "A marked crossing prop carries ANALYSED CONTENT under its own
            key rather than a plain `:value`, which is exactly the shape an
            emitter reading only `:value` turns into `nil` — a prop that
            reaches the boundary ABSENT and renders nothing. Each row
            asserts what the emitted props map must carry, and that it
            carries no nil under the prop's own key."
    (doseq [{:keys [marker body reaches]} crossing-rows]
      (let [text (emitted body)]
        (is (.contains ^String text ^String reaches)
            (str marker " — the emitted props map reaches " reaches))
        (is (not (re-find #":(label|row|on-pick|on-done|node|cb) nil" text))
            (str marker " — and the boundary is not handed an absent prop"))))))

(deftest the-props-tables-cover-the-whole-admitted-rosters
  (testing "A per-carrier table is only as good as its coverage, so the
            coverage is the assertion — the same claim the node-kind table
            above makes, on the axis that table cannot see."
    (is (= grammar/element-props-carriers (into #{} (map :carrier) element-rows))
        "one row per element props carrier, and no row outside the roster")
    (is (= grammar/crossing-prop-markers (into #{} (map :marker) crossing-rows))
        "one row per crossing prop marker, and no row outside the roster")))

(deftest a-ref-is-refused-rather-than-silently-unread
  (testing "`:ref` is a props carrier no v1 emitter lowers, so the grammar
            refuses it. Silently leaving it unread rendered an element with
            the ref gone, while the same declaration INTERPRETED refuses
            outright — one declaration, two answers, neither of them said."
    (is (= :rf.ui.compile/unsupported-form
           (try (analyzed '[:div {:ref (v/raw-fn (:f props))}])
                nil
                (catch clojure.lang.ExceptionInfo ex
                  (:rf.ui.compile/error (ex-data ex)))))
        "a compiled :ref names the grammar that refused it")
    (is (some? (analyzed '[:div {:title "t"}]))
        "non-vacuous: the same element without a ref compiles")))

(deftest a-compiled-slot-lowers-to-the-shared-carrier-contract
  (testing "The browser lowering reaches the SAME three runtime calls the
            structural one does — the gate, the host-independent arity
            check, and the carrier's own fn — so a slot cannot mean one
            thing in a structural render and another in the DOM."
    (let [[e ast] (analyzed '[:div (v/slot (v/render-fn [r] [:span r]) (:label props))])
          emitted (pr-str (emit-react/emit-react-body e '[props] ast))]
      (doseq [call ["re-frame.freehand.events/callback"
                    "re-frame.freehand.events/slot-ready?"
                    "re-frame.freehand.events/check-slot-arity!"
                    "re-frame.freehand.events/callback-fn"]]
        (is (.contains emitted call)
            (str "the emitted body calls " call)))
      (is (.contains emitted "re-frame.freehand.compiled-react/el")
          "and the render-fn's own body was lowered through the React emitter"))))

(deftest a-compiled-slot-evaluates-its-arguments-before-the-gate
  (testing "rf2-drpa3.133 — an interpreted `v/slot` is an ordinary eager
            call, so its arguments evaluate before the nil-slot gate is
            even reached. Promotion must not move that evaluation inside the
            gate: the emitted body binds each argument in the `let` BEFORE
            the `slot-ready?` `when`, so a side-effecting argument — a
            `v/sub` the analyzer attributes to the enclosing view — runs
            whether or not the slot renders. Asserted on the emitted FORM,
            so the React lowering is pinned without the deferred
            compiled-browser runtime; the behavioural cross-mode oracle is
            `slots-grammar-parity-jvm-test`."
    (let [[e ast] (analyzed '[:div (v/slot (:slot props) (probe-arg))])
          form    (emit-react/emit-react-body e '[props] ast)
          nodes   (tree-seq coll? seq form)
          gate    (first (filter (fn [x]
                                   (and (seq? x)
                                        (= 'clojure.core/when (first x))
                                        (some (fn [y]
                                                (and (seq? y)
                                                     (= 're-frame.freehand.events/slot-ready?
                                                        (first y))))
                                              (tree-seq coll? seq x))))
                                 nodes))]
      (is (some? gate)
          "the slot lowers to a slot-ready? gate")
      (is (some #{'(probe-arg)} nodes)
          "the argument expression is emitted at all")
      (is (not (some #{'(probe-arg)} (tree-seq coll? seq gate)))
          "and it is NOT evaluated inside the gate — it is bound before it,
           so a nil slot still runs it once"))))

(deftest a-call-site-render-fn-prop-carries-the-slot-across-the-boundary
  (testing "The library seam — content authored at the CALL SITE and
            invoked by the callee through `v/slot`. The analyzer records
            such a prop as an analysed TEMPLATE, so the entry carries no
            plain value at all: an emitter that reads only `:value` puts
            `nil` on the props map, the seam's slot gates as absent, and
            the crossing renders nothing while every declaration compiles
            and every mount resolves. Assert the carrier and its lowered
            body are really on the emitted props map."
    (let [[e ast] (analyzed '[:div [leaf {:row (v/render-fn [r] [:span r])}]])
          emitted (pr-str (emit-react/emit-react-body e '[props] ast))]
      (is (.contains emitted "re-frame.freehand.events/callback")
          "the prop carries the same roster callback an interpreted v/render-fn expands to")
      (is (.contains emitted "re-frame.freehand.compiled-react/el")
          "and the slot body was lowered through the React emitter")
      (is (not (.contains emitted ":row nil"))
          "so the boundary is not handed an absent slot"))))

(deftest a-runtime-valued-select-multiple-settles-its-empty-value-at-render
  (testing "rf2-sf9n5 — the React emitter settled the multiple-select verdict
            only from a build-time literal, so a runtime-valued :multiple wrote
            the constant `false` and mis-shaped an explicitly nil value. It is
            now settled at RENDER: the :multiple value is bound once, the
            verdict is derived from it once, and BOTH the :multiple write and
            the nil :value normalization read that one runtime verdict —
            never the constant false the bug baked in. Asserted on the emitted
            FORM; the runtime value shape is `compiled-select-multiple-cljs-test`."
    (let [[e ast]  (analyzed '[:select {:multiple (:flag props) :value nil}])
          form     (emit-react/emit-react-body e '[props] ast)
          nodes    (tree-seq coll? seq form)
          attr!s   (filter #(and (seq? %)
                                 (= 're-frame.freehand.compiled-react/attr! (first %)))
                           nodes)
          value-write   (first (filter #(= :value (nth % 3 nil)) attr!s))
          verdict-forms (filter #(and (seq? %)
                                      (= 're-frame.freehand.controlled/multiple-select?
                                         (first %)))
                                nodes)]
      (is (= 1 (count verdict-forms))
          "the runtime multiple-select verdict is computed exactly once")
      (is (some? value-write)
          "the explicitly nil :value is written at runtime")
      (let [verdict-arg (nth value-write 5 nil)]
        (is (symbol? verdict-arg)
            "the :value write's verdict is the once-bound runtime verdict, not a constant")
        (is (not (false? verdict-arg))
            "and specifically NOT the build-time constant false the bug baked in"))
      (is (= 1 (count (filter #(= '(:flag props) %) nodes)))
          "and the runtime :multiple expression is evaluated exactly once"))))

(deftest a-runtime-select-multiple-evaluates-value-before-multiple-in-source-order
  (testing "rf2-sf9n5 gap #2 — the runtime verdict used to be hoisted into the
            outer let ahead of every write, so the :multiple expression was
            evaluated BEFORE the :value expression, diverging from the
            interpreted walk (which evaluates the whole authored map in source
            order before deciding anything). The owned dynamic values are now
            bound once in source order and the verdict reads them, so the
            observable evaluation order matches — each expression still once."
    (let [[e ast] (analyzed '[:select {:value (:v props) :multiple (:m props) :on-change [:e]}])
          form    (emit-react/emit-react-body e '[props] ast)
          nodes   (vec (tree-seq coll? seq form))
          v-idx   (.indexOf nodes '(:v props))
          m-idx   (.indexOf nodes '(:m props))]
      (is (<= 0 v-idx) "the :value expression is present in the emitted body")
      (is (<= 0 m-idx) "the :multiple expression is present in the emitted body")
      (is (< v-idx m-idx)
          "the :value expression is bound — and so evaluated — before the :multiple one")
      (is (= 1 (count (filter #(= '(:v props) %) nodes)))
          "the :value expression is evaluated exactly once")
      (is (= 1 (count (filter #(= '(:m props) %) nodes)))
          "and the :multiple expression exactly once"))))

(deftest a-safe-spread-caller-multiple-shapes-the-owned-nil-value
  (testing "rf2-sf9n5 gap #1 — a v/spread-safe caller may legally carry
            :multiple, and it folds UNDER the owned props. The compiled emitter
            now settles the multiple-select verdict over the guarded caller too,
            BEFORE the owned nil :value is normalized: the caller map is bound
            once, the verdict derives from it, and the :value write reads that
            verdict — never the build-time false that mis-shaped it under a
            caller-supplied multiple."
    (let [[e ast]      (analyzed '[:select (v/spread-safe {:value nil} {:multiple true})])
          form         (emit-react/emit-react-body e '[props] ast)
          nodes        (tree-seq coll? seq form)
          caller-binds (filter #(and (seq? %)
                                     (= 're-frame.freehand.node/safe-caller-attrs (first %)))
                               nodes)
          verdicts     (filter #(and (seq? %)
                                     (= 're-frame.freehand.controlled/multiple-select? (first %)))
                               nodes)
          attr!s       (filter #(and (seq? %)
                                     (= 're-frame.freehand.compiled-react/attr! (first %)))
                               nodes)
          value-write  (first (filter #(= :value (nth % 3 nil)) attr!s))]
      (is (= 1 (count caller-binds))
          "the guarded caller map is evaluated exactly once, bound before the writes")
      (is (= 1 (count verdicts))
          "the runtime multiple-select verdict is computed exactly once, over the caller")
      (is (some? value-write) "the explicitly nil owned :value is written at runtime")
      (let [verdict-arg (nth value-write 5 nil)]
        (is (symbol? verdict-arg)
            "the :value write's verdict is the once-bound runtime verdict, not a constant")
        (is (not (false? verdict-arg))
            "and specifically NOT the build-time false the bug baked in")))))

(deftest an-owned-multiple-wins-over-the-safe-spread-caller-in-the-lowering
  (testing "rf2-sf9n5 #6847 audit — a v/spread-safe caller folds UNDER the
            owned props, so an owned :multiple declaration settles the whole-
            element verdict and the caller is consulted only when the owned
            props are silent on the slot. PR #6847 ORed the caller into the
            verdict, so an owned :multiple false beside a caller :multiple true
            shaped the owned nil :value as the empty collection though owned-
            false won at caller-spread!. These prove the emitted verdict now
            reads the EFFECTIVE owned source, never the caller."
    (testing "owned literal :multiple false — the exact reproduction. The
              verdict is the compile-time constant, so NO runtime verdict call
              over the caller is emitted at all (before the fix an `or` with a
              `(multiple-select? … caller …)` call was)."
      (let [[e ast]  (analyzed '[:select (v/spread-safe {:value nil :multiple false}
                                                        {:multiple true})])
            form     (emit-react/emit-react-body e '[props] ast)
            nodes    (tree-seq coll? seq form)
            verdicts (filter #(and (seq? %)
                                   (= 're-frame.freehand.controlled/multiple-select? (first %)))
                             nodes)
            callers  (filter #(and (seq? %)
                                   (= 're-frame.freehand.node/safe-caller-attrs (first %)))
                             nodes)]
        (is (= 1 (count callers))
            "the guarded caller map is still evaluated once — it is written, just not consulted")
        (is (empty? verdicts)
            "no multiple-select? verdict call is emitted: owned false is the constant verdict")))
    (testing "owned DYNAMIC :multiple — the verdict reads the owned expression,
              over the [[:multiple …]] owned source, never the caller map."
      (let [[e ast]  (analyzed '[:select (v/spread-safe {:value nil :multiple (:m props)}
                                                        {:multiple true})])
            form     (emit-react/emit-react-body e '[props] ast)
            nodes    (tree-seq coll? seq form)
            verdicts (filter #(and (seq? %)
                                   (= 're-frame.freehand.controlled/multiple-select? (first %)))
                             nodes)]
        (is (= 1 (count verdicts))
            "exactly one runtime verdict is derived")
        (is (vector? (nth (first verdicts) 2 nil))
            "and it reads the OWNED [[:multiple …]] source (a vector), not the caller map (a symbol)")
        (is (= 1 (count (filter #(= '(:m props) %) nodes)))
            "the owned :multiple expression is evaluated exactly once")))))

(deftest a-compiled-popover-carries-the-runtime-reconciliation-verdict
  (testing "rf2-drpa3.173 — #6792 recorded every analyzed reconciler position
            (:on-toggle / :on-close / …) as a compile-time `true` in the
            top-layer advisory context. That is not the runtime predicate the
            shared advisory uses: `reconciled?` asks `(some? (get attrs k))`,
            and a DYNAMIC handler is allowed to evaluate to nil, in which case
            `event-site` returns nil and `handler!` writes no DOM handler — yet
            the compile-time `true` suppressed the advisory anyway, so the node
            springs back open after native dismissal with nothing said. The
            context must instead carry whether the runtime site produced a
            handler, derived from the SAME bound site the handler write uses so
            the authored expression is evaluated once."
    (let [[e ast] (analyzed '[:div {:popover :auto
                                    :re-frame.freehand.web/popover-open? true
                                    :on-toggle (:on-tog props)}])
          form    (emit-react/emit-react-body e '[props] ast)
          nodes   (vec (tree-seq coll? seq form))
          install (first (filter #(and (seq? %)
                                       (= 're-frame.freehand.top-layer/install! (first %)))
                                 nodes))
          context (nth install 3 nil)
          on-tog  (get context :on-toggle)
          handler-writes (filter #(and (seq? %)
                                       (= 're-frame.freehand.compiled-react/handler! (first %)))
                                 nodes)
          event-sites (filter #(and (seq? %)
                                     (= 're-frame.freehand.reactive/event-site (first %)))
                               nodes)]
      (is (some? install) "the compiled popover installs the top-layer host call")
      (is (map? context) "install! is handed the element-facts context map")
      (testing "the reconciler position is a runtime some?-verdict, not a compile-time true"
        (is (not (true? on-tog))
            "a literal `true` (the #6792 shortcut) suppresses the advisory even for a nil handler")
        (is (and (seq? on-tog) (= 'if (first on-tog)))
            ":on-toggle carries `(if (some? <site>) true nil)` — nil when the runtime site produced no handler")
        (is (some #(and (seq? %) (= 'cljs.core/some? (first %)))
                  (tree-seq coll? seq on-tog))
            "and the verdict is derived from (some? <bound-site>)"))
      (testing "the handler expression is evaluated exactly once"
        (is (= 1 (count (filter #(= '(:on-tog props) %) nodes)))
            "the dynamic handler body appears once — not once for props and again for the context"))
      (testing "the handler! write and the context share ONE bound site"
        (is (= 1 (count event-sites))
            "exactly one event-site is emitted for the sole handler")
        (let [site-sym (nth (first handler-writes) 3 nil)]
          (is (symbol? site-sym)
              "handler! receives the pre-bound site symbol, not an inline event-site call")
          (is (some #{site-sym} (tree-seq coll? seq on-tog))
              "and that same bound site is what the context's verdict tests"))))))

(deftest a-compiled-modal-dialog-carries-the-runtime-close-verdict
  (testing "rf2-drpa3.173 — the dialog siblings. A modal `<dialog>` reconciles
            its own dismissal through :on-close / :on-cancel, and a dynamic
            :on-close may evaluate to nil exactly as :on-toggle can. The
            compiled context must carry the same runtime some?-verdict for the
            modal axis, so a nil close handler warns rather than being suppressed
            by a compile-time `true`."
    (let [[e ast] (analyzed '[:dialog {:re-frame.freehand.web/modal-open? true
                                       :on-close (:on-cl props)}])
          form    (emit-react/emit-react-body e '[props] ast)
          nodes   (vec (tree-seq coll? seq form))
          install (first (filter #(and (seq? %)
                                       (= 're-frame.freehand.top-layer/install! (first %)))
                                 nodes))
          on-close (get (nth install 3 nil) :on-close)]
      (is (some? install) "the compiled modal installs the top-layer host call")
      (is (not (true? on-close))
          "the modal close position is not a compile-time `true`")
      (is (and (seq? on-close) (= 'if (first on-close)))
          ":on-close carries the runtime some?-verdict, nil when no handler was produced")
      (is (= 1 (count (filter #(= '(:on-cl props) %) nodes)))
          "the dynamic close handler body is evaluated exactly once"))))
