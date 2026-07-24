(ns re-frame.freehand.analyze-reject-cljs-test
  "Template-grammar REJECT table: every rejected form throws a compile
  error carrying {:rf.ui.compile/error <id>} — the S1e didactic-message
  roster keys off these ids. Runs on both hosts (injected resolution)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.check :as check]
            [re-frame.freehand.compiler.grammar :as grammar]
            [re-frame.freehand.analyze-accept-cljs-test :refer [mk-env mk-self-env]]))

(defn reject-id
  "nil when accepted; the :rf.ui.compile/error id when rejected."
  [form]
  (try
    (ana/analyze (mk-env) form)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) ex
      (:rf.ui.compile/error (ex-data ex)))))

(defn reject-msg
  "nil when accepted; the compile error's human MESSAGE when rejected —
  for the rows where the SENTENCE is the contract and not only the id."
  [form]
  (try
    (ana/analyze (mk-env) form)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) ex
      (ex-message ex))))

(defn reject-id-in
  "reject-id against a supplied env (for self-head precedence rows)."
  [e form]
  (try
    (ana/analyze e form)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) ex
      (:rf.ui.compile/error (ex-data ex)))))

(deftest heads
  (is (= :rf.ui.compile/dynamic-head
         (reject-id '[(if x :div :span) "y"]))
      "dynamic tag heads")
  (is (= :rf.ui.compile/dynamic-head
         (reject-id '(let [h child-view] [h {}])))
      "local-bound component heads are dynamic heads")
  (is (= :rf.ui.compile/unresolved-head
         (reject-id '[nope-not-a-thing {}]))
      "unresolved heads name the (declare ^:rf.ui/view ...) fix")
  (is (= :rf.ui.compile/bad-tag (reject-id [:ns/div "x"]))
      "element heads are unqualified keywords"))

(deftest children-position
  (is (= :rf.ui.compile/keyword-child (reject-id [:div :oops])))
  (is (= :rf.ui.compile/markup-returning-map
         (reject-id '[:ul (map render-item items)]))
      "markup-returning map -> use (for ...) with :key"))

(deftest keyed-lists
  (is (= :rf.ui.compile/unkeyed-list-item
         (reject-id '(for [x xs] [:li x])))
      "missing :key = build failure")
  (is (= :rf.ui.compile/unkeyed-list-item
         (reject-id '(for [x xs] (str x))))
      "for body must be a keyed element/view/fragment")
  (is (= :rf.ui.compile/constant-list-key
         (reject-id '(for [x xs] [:li {:key 1} x])))
      "a constant key guarantees duplicates")
  (is (= :rf.ui.compile/nested-for-body
         (reject-id '(for [x xs] (for [y x] [:li {:key y} y]))))
      "nested iteration = multiple binding pairs in ONE for (Q6)")
  (is (= :rf.ui.compile/bad-for
         (reject-id '(for [x xs :unknown y] [:li {:key x} x])))
      "the modifier subgrammar is :let/:when/:while (Q6)"))

(deftest a-wrapped-for-body-is-its-own-rejection
  (testing "rf2-drpa3.164. `(for [i is] (let [r (nth rows i)] [:div {:key
            (:id r)} …]))` is the natural Clojure spelling for binding the
            record beside the index, and the row inside it IS keyed. Naming
            a missing key sent the author to a line that has one, and the
            ladder advised keying every row — advice already taken. The
            rule they need is that the body must BE the node; the fix is
            `for`'s own `:let` modifier."
    (let [wrapped '(for [i is] (let [r (nth rows i)] [:div {:key (:id r)} r]))]
      (is (= :rf.ui.compile/indirect-list-body (reject-id wrapped))
          "the wrapped body is its own id, not the missing-key one")
      (let [msg (reject-msg wrapped)]
        (is (str/includes? msg "must BE the keyed node")
            "the sentence states the real rule")
        (is (str/includes? msg ":let")
            "and names for's :let modifier as the fix")
        (is (str/includes? msg "(for [i (range start end) :let [r (nth rows i)]]")
            "with the spelling, not just the name")
        (is (not (str/includes? msg "missing :key"))
            "and never reports a key as missing when one is present"))))
  (testing "the recovery the message names is on the LADDER too, so a
            checker report carries it without reading prose"
    (is (= [:bind-with-for-let :extract-declared-child :keep-interpreted]
           (grammar/recovery :rf.ui.compile/indirect-list-body)))
    (is (str/includes? (get grammar/recoveries :bind-with-for-let) ":let")
        "and the rung's own sentence spells the modifier")
    (is (= :iteration-is-not-lexically-flat
           (check/reason :rf.ui.compile/indirect-list-body))
        "a wrapper between the for and its row is the flatness reason"))
  (testing "the recovery this diagnostic advises actually compiles — the
            whole point of naming it"
    (is (nil? (reject-id '(for [i is :let [r (nth rows i)]]
                            [:div {:key (:id r)} r])))))
  (testing "a wrapper that :let cannot fix is told to extract a child view"
    (let [msg (reject-msg '(for [x xs] (if x [:li {:key x} 1] [:li {:key x} 2])))]
      (is (str/includes? msg "must BE the keyed node"))
      (is (str/includes? msg "Extract a declared child view"))))
  (testing "a body carrying no row at all keeps the general message"
    (is (= :rf.ui.compile/unkeyed-list-item (reject-id '(for [x xs] (str x)))))))

(deftest finite-sites
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '(for [x xs] [:li {:key x} (sub [:q x])]))))
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '(for [x xs, y (sub [:q x])] [:li {:key y} y])))
      "later coll expressions evaluate per row")
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '(for [x xs :let [v (sub [:q x])]] [:li {:key x} v]))))
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '[:button {:on-click [::open (sub [:q])]} "x"])))
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '[:button {:on-click {:event [::open (sub [:q])]}} "x"])))
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '[:button {:on-click (fn [_] (sub [:q]))} "x"])))
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '[:div {:ref (raw-fn (fn [_] (sub [:q])))}])))
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '[:div {:title (mapv (fn [x] (sub [:q x])) xs)}])))
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '[:div {:title (loop [x 0] (sub [:q x]))}])))
  ;; rf2-u53yy.4 admits if-let / when-let / if-some / when-some — the family
  ;; DESUGARS into the analyzer's own let + if, so a (sub …) in the init lowers
  ;; (see if-let-binder-family-is-admitted). The admission is BOUNDED: an
  ;; out-of-family macro still fails loudly — the closed grammar did not open.
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (.. x -y -z)}]))
      "an out-of-family macro (.. / doto / case in expression position) still fails loudly — the if-let admission did not relax the closed grammar")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (-> [:q] sub)}]))
      "a macro cannot turn a bare resolved sub reference into an unindexed call")
  ;; rf2-vxgfnd.217 — a COMPUTED callee is an evaluated position, so it is
  ;; analyzed under the same rules; a callee that cannot own a finite site is
  ;; rejected didactically rather than silently swallowing an invisible read.
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '[:div {:title ((fn [_] (sub [:q])) 1)}]))
      "an immediately-invoked fn callee with a reactive body is a deferred site")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title ((-> [:q] sub) 1)}]))
      "a bare sub reference under a macro in the callee position fails loudly too")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '(let [{:keys [x] :or {x (sub [:q])}} value] [:div x]))))
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '(let [{:keys [x] :or {x (-> [:q] sub)}} value]
                       [:div x]))))
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '(for [{:keys [x] :or {x (sub [:q])}} xs]
                       [:div {:key x} x]))))
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:button {:on-click
                               (fn [{:keys [x] :or {x (sub [:q])}}] x)}])))
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (sub)}])))
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (sub [:a] [:b])}]))))

(deftest if-let-family-admission-is-resolver-confirmed
  ;; rf2-u53yy.4 audit repair (chronological reopen 2026-07-21) — the family is
  ;; admitted by RESOLUTION to the core binder var, not by raw spelling. A
  ;; namespace-level USER macro spelled `if-let` (a look-alike `:refer`d in)
  ;; resolves to its OWN fqn, so it is not the core binder: it fails loudly as an
  ;; unaudited macro, exactly like any other opaque macro. Before the repair the
  ;; raw-spelling match would have silently rewritten it as the core binder.
  (let [user-if-let-env
        (assoc (mk-env)
               :resolver (fn [sym]
                           (case sym
                             if-let {:fqn 'app.userland/if-let :meta {:macro true}}
                             sub    {:fqn 're-frame.freehand/sub :meta {}}
                             nil)))]
    (is (= :rf.ui.compile/unsupported-form
           (reject-id-in user-if-let-env
                         '(if-let [x (sub [:q])] [:p x] [:p "no"])))
        "a user macro spelled if-let is not the core binder — it rejects as an unaudited macro, never desugared")
    (is (= :rf.ui.compile/unsupported-form
           (reject-id-in user-if-let-env
                         '[:div {:title (if-let [x (sub [:q])] x "none")}]))
        "the same rejection holds in expression position")))

(deftest computed-callee-value-escape
  ;; rf2-vxgfnd.252 — a reactive authoring var (sub/frame) is sound ONLY
  ;; as a compiler-owned DIRECT CALL HEAD, which the rewriter lowers to an
  ;; indexed runtime site. A BARE reactive var that instead flows as a VALUE —
  ;; into a computed callee, a let alias, an argument, or a collection — leaves
  ;; the manifest under-declaring while a public authoring var survives; the
  ;; optimized build then elides the ViewCell and the read escapes ownership.
  ;; This is the escape #5855/.217 (direct calls in computed callees) did not
  ;; close. Removing the leaf guard makes every reject row below fail (they
  ;; silently re-accept), so this deftest is the mutation fixture too.
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title ((if p sub inc) [:q])}]))
      "bare sub in a computed (if …) callee — the .252 counterexample")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title ((identity sub) [:q])}]))
      "bare sub through a computed-callee wrapper escapes identically")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (let [f sub] (f [:q]))}]))
      "a let-bound sub alias becomes an unindexed invocation target")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (str sub)}]))
      "a bare sub passed as an argument is value flow, not a render site")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (let [g frame] (g))}]))
      "bare frame escapes through a let alias identically")
  ;; The soundness rule does not touch the legal forms:
  (is (nil? (reject-id '[:div {:title ((if (sub [:op]) inc dec) 1)}]))
      "a DIRECT (sub …) in the computed callee is still one indexed site (.217)")
  (is (nil? (reject-id '[:div {:title (let [sub identity] (sub query))}]))
      "a local shadowing sub is an ordinary call, never a reactive site")
  (is (nil? (reject-id '[:div {:title '((if p sub inc) [:q])}]))
      "quoted data is inert — never an invocation target"))

(deftest indirect-frame-diagnostic-recommends-the-zero-arity-form
  (let [ex (try
             (ana/analyze (mk-env) '[:div {:title (-> x frame)}])
             nil
             (catch #?(:clj clojure.lang.ExceptionInfo
                       :cljs cljs.core/ExceptionInfo) ex
               ex))
        message (some-> ex ex-message)]
    (is (= :rf.ui.compile/unsupported-form
           (:rf.ui.compile/error (ex-data ex))))
    (is (str/includes? message "(frame)")
        "the didactic repair is the frozen zero-arity frame form")
    (is (not (str/includes? message "frame query"))
        "the diagnostic never recommends the invalid argument-taking form")))

(deftest or-default-value-escape
  ;; rf2-dzyqis — the sibling gap PR #5874 (.252) left open. reject-reactive-
  ;; binding! catches an executable (sub …)/(frame) embedded in a
  ;; binding pattern, but a BARE reactive authoring var used as a destructuring
  ;; :or DEFAULT is a value, not a call. Binding patterns never pass through
  ;; expression rewriting, so that bare var flows as a VALUE into the host's
  ;; destructuring default with no compiler-owned render site — the manifest
  ;; under-declares and the optimized build elides the read. The reject is
  ;; binding-position-AWARE: every symbol the pattern BINDS is treated as a
  ;; local, so only a bare var reaching a default from OUTSIDE the pattern is
  ;; rejected. Removing the :or-default guard silently re-accepts every reject
  ;; row below, so this deftest is the mutation fixture too.
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (let [{:keys [x] :or {x sub}} m] x)}]))
      "bare sub as an :or default — the rf2-dzyqis counterexample")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (let [{:keys [x] :or {x frame}} m] x)}]))
      "bare frame as an :or default escapes identically")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (fn [{:keys [x] :or {x sub}}] x)}]))
      "a bare sub :or default in an fn destructuring arg escapes identically")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (let [{:keys [x] :or {x (str sub)}} m] x)}]))
      "a bare sub flowing through an :or default expression escapes too")
  ;; The binding-position-aware guard leaves the legal forms alone:
  (is (nil? (reject-id '[:div {:title (let [{:keys [sub]} m] (str sub))}]))
      "a local NAMED sub the pattern binds is a legitimate lexical shadow")
  (is (nil? (reject-id '[:div {:title (let [{:keys [sub a] :or {a sub}} m] a)}]))
      "an :or default referencing a sub the SAME pattern binds is the shadow")
  (is (nil? (reject-id '[:div {:title (let [{:keys [x] :or {x 0}} m] x)}]))
      "an ordinary literal :or default is untouched")
  (is (nil? (reject-id '[:div {:title (let [{:keys [x] :or {x 'sub}} m] x)}]))
      "quoted data in an :or default is inert — never a reactive read"))

(deftest destructuring-scope-follows-host-evaluation-order
  ;; rf2-vxgfnd.268 — the ordered-scope correction to rf2-dzyqis. CLJ/CLJS
  ;; destructuring binds SEQUENTIALLY, so a bare reactive var in a default is
  ;; shadowed ONLY by a same-pattern local bound EARLIER in evaluation order.
  ;; The dzyqis guard put every symbol the pattern binds into one flat scope
  ;; BEFORE scanning, so a LATER-bound local named sub/frame (and a SELF
  ;; default) falsely shadowed the escape and the pre-fix head compiled these
  ;; with an EMPTY manifest, leaving the public authoring var to survive to
  ;; runtime unindexed. Each row below reads the outer public var; reverting the
  ;; ordered scope re-accepts them all, so this deftest is the mutation fixture.
  (testing "a LATER-bound local does not shadow an earlier default (over-accept)"
    ;; `f` binds first; its :or default `sub` evaluates before local `sub` — it
    ;; is the outer re-frame.freehand/sub, not the later local.
    (is (= :rf.ui.compile/unsupported-form
           (reject-id '[:div {:title (let [{:keys [f sub] :or {f sub}} m] f)}]))
        "bare sub default shadowed only by a LATER binding escapes")
    (is (= :rf.ui.compile/unsupported-form
           (reject-id '[:div {:title (let [{:keys [f frame] :or {f frame}} m] f)}]))
        "bare frame escapes identically under a later shadow"))
  (testing "a SELF default is not its own shadow (over-accept)"
    ;; `sub`'s :or default `sub` evaluates while local `sub` is still being
    ;; bound — the default is the outer public var.
    (is (= :rf.ui.compile/unsupported-form
           (reject-id '[:div {:title (let [{:keys [sub] :or {sub sub}} m] sub)}]))
        "a self-referential sub default is the outer var, not the local")
    (is (= :rf.ui.compile/unsupported-form
           (reject-id '[:div {:title (let [{:keys [frame] :or {frame frame}} m] frame)}]))
        "a self-referential frame default escapes identically"))
  (testing "an explicit map lookup-key expression is an evaluated slot too"
    ;; `{x sub}` binds x to (get m sub) — the lookup key `sub` is evaluated, so
    ;; the bare reactive var escapes there just like a default.
    (is (= :rf.ui.compile/unsupported-form
           (reject-id '[:div {:title (let [{x sub} m] x)}]))
        "a bare reactive var as an explicit lookup key escapes")
    (let [msg (try (ana/analyze (mk-env)
                                '[:div {:title (let [{x sub} m] x)}])
                   nil
                   (catch #?(:clj clojure.lang.ExceptionInfo
                             :cljs cljs.core/ExceptionInfo) ex
                     (ex-message ex)))]
      (is (re-find #"lookup-key" msg)
          "the lookup-key diagnostic identifies the slot, not an :or default")
      (is (not (re-find #":or default" msg))
          "and does not misreport it as an :or default"))))

(deftest binding-plan-is-host-faithful-order
  ;; rf2-vxgfnd.283 — the ordered-scope model must be the HOST `destructure`
  ;; `bes` order, not an invented explicit-first + rank-sorted `:keys`/`:strs`/
  ;; `:syms` order. Where the two disagree, a bare reactive var in a default
  ;; that the host binds BEFORE its shadow escapes while the invented order
  ;; falsely accepts it. These rows reject ONLY under the faithful order —
  ;; restoring the source/rank order re-accepts them (the mutation fixture).
  (testing "the ≥9-entry hash-map regime reorders the shadow behind the default"
    ;; The host promotes the transformed bindings map to a PersistentHashMap at
    ;; nine entries; here it binds `target` (with default `sub`) BEFORE `sub`, so
    ;; the default is the outer public var. The invented explicit-first order put
    ;; `sub` first and false-accepted.
    (is (= :rf.ui.compile/unsupported-form
           (reject-id '[:div {:title (let [{:keys [sub a b c d e f g h target]
                                            :or {target sub}} m] target)}]))
        "a 10-entry hash-regime default the host expands before its shadow escapes"))
  (testing "mixed :keys/:strs/:syms follow SOURCE group order, not a fixed rank"
    ;; Host group order = the order the group directives appear in the pattern.
    ;; With `:strs`/`:keys`/`:syms` written in that order the host binds
    ;; a, target, sub — so target's default `sub` is the outer var. A fixed
    ;; keys<strs<syms rank would bind sub first and false-accept.
    (is (= :rf.ui.compile/unsupported-form
           (reject-id '[:div {:title (let [{:strs [a] :keys [target] :syms [sub]
                                            :or {target sub}} m] target)}]))
        "reordered mixed groups keep the host's source group order"))
  (testing "the faithful order does not over-reject a genuine earlier shadow"
    ;; Array-map regime (two entries): the host binds `sub` then `target`, so
    ;; target's `:or` default resolves to the earlier local, not the outer var.
    ;; (In the hash regime source order is NOT bind order — the 10-entry row
    ;; above binds `target` before `sub` even though `sub` is written first.)
    (is (nil? (reject-id '[:div {:title (let [{:keys [sub target] :or {target sub}}
                                              m] target)}]))
        "an earlier-bound local in the array regime still shadows the default")))

(deftest portable-binding-grammar-close
  ;; rf2-vxgfnd.283 — close the portable grammar to simple-symbol (and nested)
  ;; explicit locals and simple-symbol :or keys. The host tolerates keyword and
  ;; namespace-qualified explicit locals (binding them name-only) and composite
  ;; :or keys (a dead default), but the analyzer's scope walk keeps the written
  ;; form — a divergence that can mis-lower a reactive read. Reject up front with
  ;; the shared typed unsupported-form error rather than reproduce those shapes.
  (testing "keyword and qualified explicit locals are rejected"
    (is (= :rf.ui.compile/unsupported-form
           (reject-id '[:div {:title (let [{:foo :bar} m] 1)}]))
        "a keyword explicit local is nonportable")
    (is (= :rf.ui.compile/unsupported-form
           (reject-id '[:div {:title (let [{a/b :bar} m] 1)}]))
        "a namespace-qualified explicit local is nonportable"))
  (testing "composite :or keys are rejected"
    (is (= :rf.ui.compile/unsupported-form
           (reject-id '[:div {:title (let [{[a b] :pair :or {[a b] [1 2]}} m] a)}]))
        "a composite :or key never defaults a bound local"))
  (testing "portable simple-symbol and nested destructuring stays legal"
    (is (nil? (reject-id '[:div {:title (let [{:keys [x] :or {x 0}} m] x)}]))
        "simple-symbol keys + literal default")
    (is (nil? (reject-id '[:div {:title (let [{[a b] :pair} m] [a b])}]))
        "a nested destructuring explicit local")))

(deftest reactive-verb-head-reserved-before-foreign-classification
  ;; rf2-vxgfnd.266 — the next escape in the .252/dzyqis family. sub/frame
  ;; are reactive authoring verbs, sound ONLY as their compiler-owned DIRECT
  ;; forms. A distinct analyzer route reaches a Hiccup component HEAD directly:
  ;; env/classify-head resolves any non-:rf.ui/view var to a plain :foreign
  ;; React component, so [sub {…}]/[frame] would compile as a foreign
  ;; component with an EMPTY reactive manifest — the public authoring var
  ;; survives to runtime unindexed, bypassing reactive-site indexing entirely.
  ;; The verbs are RESERVED before generic classification. Removing the
  ;; reserved-head check re-accepts every reject row below as :foreign, so this
  ;; deftest is the mutation fixture too.
  (is (= :rf.ui.compile/unsupported-form (reject-id '[sub {}]))
      "bare sub in component-head position — the rf2-vxgfnd.266 counterexample")
  (is (= :rf.ui.compile/unsupported-form (reject-id '[frame]))
      "bare frame in component-head position (no props) escapes identically")
  (is (= :rf.ui.compile/unsupported-form (reject-id '[sub {} [:p "child"]]))
      "children do not change the reserved-head classification")
  ;; qualified + aliased spellings resolve to the exact vars — reserved too
  (is (= :rf.ui.compile/unsupported-form (reject-id '[v/sub {}]))
      "an aliased spelling resolving to re-frame.freehand/sub is reserved")
  (is (= :rf.ui.compile/unsupported-form (reject-id '[re-frame.freehand/frame]))
      "a fully-qualified frame head is reserved")
  ;; a lexical shadow is NOT the reactive var: it falls through to the ordinary
  ;; local-head rule (a local binding can never be a literal head → dynamic-head)
  (is (= :rf.ui.compile/dynamic-head
         (reject-id '(let [sub child-view] [sub {}])))
      "a local shadowing sub is an ordinary local head, never a reserved verb")
  ;; a genuine foreign component head still classifies (accepts)
  (is (nil? (reject-id '[ForeignComp {}]))
      "a genuine foreign component head still classifies as :foreign"))

(deftest local-shadow-outranks-self-head
  ;; rf2-vxgfnd.274 — self precedence is tier 2: a LEXICAL SHADOW of the self
  ;; spelling (tier 1) still outranks it. In a view `defview`d as `sub`, a local
  ;; also named `sub` makes the head a dynamic (local) head — never the self
  ;; view — exactly as the Q5 rule pins (a local-bound head is a compile error,
  ;; since dynamic component heads are rejected).
  (let [e (mk-self-env 'sub)]
    (is (= :rf.ui.compile/dynamic-head
           (reject-id-in e '(let [sub identity] [sub {}])))
        "a let-bound local named sub outranks the self view named sub")
    (is (= :rf.ui.compile/dynamic-head
           (reject-id-in e '(letfn [(sub [_] nil)] [sub {}])))
        "a letfn binding named sub shadows self and is a dynamic head too")
    (is (= :view (:op (ana/analyze e '[sub {}])))
        "control: without a shadow the same self head IS the internal view")))

(deftest letfn*-malformed-flat-bindings
  ;; rf2-vxgfnd.221 — letfn* has FLAT name/initializer bindings; a malformed
  ;; flat vector fails through the typed :rf.ui.compile/* surface, never a raw
  ;; host exception (before the split, an odd/mis-shaped letfn* threw a bare
  ;; IllegalArgumentException from the source-letfn parser).
  (is (= :rf.ui.compile/bad-let
         (reject-id '[:div {:title (letfn* [f (fn* f ([x] x)) g] (f 7))}]))
      "odd-length flat bindings -> typed bad-let (never IllegalArgumentException)")
  (is (= :rf.ui.compile/bad-let
         (reject-id '[:div {:title (letfn* [42 (fn* _ ([x] x))] 1)}]))
      "non-symbol binding name -> typed bad-let")
  ;; rf2-rgqn9 — the flat-binding validator checked only vector parity + symbol?
  ;; then blindly rewrote each initializer, so these three slipped through the
  ;; typed surface (a bare value / qualified name were ACCEPTED and only failed
  ;; in the later host compiler; a malformed fn* threw a raw ISeq exception).
  (is (= :rf.ui.compile/bad-let
         (reject-id '[:div {:title (letfn* [f 42] f)}]))
      "a bare non-fn initializer -> typed bad-let (never accepted then host-failed)")
  (is (= :rf.ui.compile/bad-let
         (reject-id '[:div {:title (letfn* [f (fn* 42)] f)}]))
      "a malformed fn* arity -> typed bad-let (never a raw IllegalArgumentException)")
  (is (= :rf.ui.compile/bad-let
         (reject-id '[:div {:title (letfn* [foo/bar (fn* [] 1)] foo/bar)}]))
      "a qualified binding name -> typed bad-let (host rejects qualified locals)")
  (is (= :rf.ui.compile/sub-in-loop
         (reject-id '[:div {:title (letfn* [f (fn* f ([x] (sub [:q])))] (f 7))}]))
      "a reactive read inside a deferred local fn body is not a finite render site"))

(deftest letfn*-raw-fn*-parameter-and-arity-grammar
  ;; rf2-9rqmq — fn-init-shape? treated any vector as a well-formed argv and any
  ;; vector-led arity list as valid, so host-illegal RAW `fn*` grammar escaped
  ;; the UI compiler: a destructuring/qualified parameter or a malformed `&` was
  ;; ACCEPTED (only to crash the later host compiler), and a malformed directive
  ;; like `(fn* [{:keys 42}] …)` threw a raw IllegalArgumentException from
  ;; binding-plan. The raw `fn*` special form binds only simple symbols with an
  ;; optional single `& rest`, and rejects duplicate/incompatible arities — the
  ;; UI analyzer must reject the same shapes, on the typed bad-let surface, and
  ;; on BOTH hosts (the analyzer is pure — this IS the CLJ/CLJS parity fixture).
  (testing "raw fn* parameters must be host-portable simple symbols"
    (is (= :rf.ui.compile/bad-let
           (reject-id '[:div {:title (letfn* [f (fn* [{:keys [x]}] x)] f)}]))
        "a destructuring parameter is host-illegal in raw fn*")
    (is (= :rf.ui.compile/bad-let
           (reject-id '[:div {:title (letfn* [f (fn* [foo/bar] foo/bar)] f)}]))
        "a qualified parameter is host-illegal in raw fn*")
    (is (= :rf.ui.compile/bad-let
           (reject-id '[:div {:title (letfn* [f (fn* [{:keys 42}] 1)] f)}]))
        "a malformed directive is typed bad-let (never a raw IllegalArgumentException)")
    (is (= :rf.ui.compile/bad-let
           (reject-id '[:div {:title (letfn* [f (fn* [a & b c] a)] f)}]))
        "a malformed & (two rest params) is host-illegal")
    (is (= :rf.ui.compile/bad-let
           (reject-id '[:div {:title (letfn* [f (fn* [a &] a)] f)}]))
        "a bare & (no rest param) is host-illegal"))
  (testing "raw fn* rejects duplicate / multiple-variadic arities"
    (is (= :rf.ui.compile/bad-let
           (reject-id '[:div {:title (letfn* [f (fn* ([x] x) ([y] y))] f)}]))
        "two overloads of the same fixed arity are host-illegal")
    (is (= :rf.ui.compile/bad-let
           (reject-id '[:div {:title (letfn* [f (fn* ([& a] a) ([x & b] x))] f)}]))
        "more than one variadic overload is host-illegal"))
  (testing "the boundary is raw fn* ONLY — macro fn / source letfn destructuring stays legal"
    (is (nil? (reject-id '[:div {:title (letfn* [f (fn [{:keys [x]}] x)] f)}]))
        "macro fn owns its destructuring expansion — accepted")
    (is (nil? (reject-id '[:div {:title (letfn* [f (fn [[a b]] a)] f)}]))
        "macro fn nested/sequential destructuring — accepted")
    (is (nil? (reject-id '[:div {:title (letfn* [f (fn* f ([x] x))] (f 7))}]))
        "a legal named raw fn* retains its exact shape")
    (is (nil? (reject-id '[:div {:title (letfn* [f (fn* [a & b] a)] f)}]))
        "a legal raw fn* variadic (single trailing & rest) — accepted")
    (is (nil? (reject-id '[:div {:title (letfn* [f (fn* ([] 0) ([x] x))] f)}]))
        "legal distinct raw fn* fixed arities — accepted")))

(defn- letfn*-init-id
  "reject-id for `init` used as the single raw letfn* initializer."
  [init]
  (reject-id (list :div {:title (list 'letfn* ['f init] 'f)})))

(deftest letfn*-raw-fn*-binding-and-arity-is-host-portable
  ;; rf2-wnhbm — rf2-9rqmq's bounded grammar still let three host-NON-portable
  ;; shapes through the typed bad-let boundary. Verdicts below are the measured
  ;; behaviour of both host compilers, not inference:
  ;;
  ;;   duplicate bindings  `[x x]` / `[x & x]` — BOTH hosts compile, by
  ;;     different mechanisms (JVM shadows the earlier binding, CLJS
  ;;     gensym-renames the later to `x__$1`). This analyzer threads lexical
  ;;     scope as a SET, so it cannot model either: `[x x]` collapses to one
  ;;     local and a reactive-escape check on the second `x` reads the first's
  ;;     scope. Unmodellable, never intentional -> reject.
  ;;
  ;;   qualified internal name  `(fn* foo/bar ([x] x))` — the JVM ACCEPTS it;
  ;;     CLJS emits `function cljs$user$foo.bar(x){…}`, which is not valid
  ;;     JavaScript (`SyntaxError: Unexpected token '.'`). A hard host split.
  ;;
  ;;   fixed-vs-variadic arity — writing `v` for the variadic overload's
  ;;     required (pre-`&`) count, so its emitted parameter count is `v + 1`:
  ;;       f <= v      both hosts compile it identically — legal.
  ;;       f == v + 1  JVM REJECTS ("Can't have fixed arity function with more
  ;;                   params than variadic function"); CLJS compiles with
  ;;                   :variadic-max-arity + :overload-arity ("Can't have 2
  ;;                   overloads with same arity") and emits a dispatch that
  ;;                   silently DROPS the fixed overload.
  ;;       f >  v + 1  JVM REJECTS; CLJS warns :variadic-max-arity, same broken
  ;;                   dispatch.
  ;;     So `f <= v` is exactly where the JVM's hard error and CLJS's warnings
  ;;     both begin — the one rule that makes the verdict host-portable.
  ;;
  ;; The analyzer is pure, so this .cljc file IS the CLJ/CLJS parity fixture:
  ;; every row below is asserted on BOTH hosts and must agree.
  (testing "raw fn* argv bindings must be distinct"
    (is (= :rf.ui.compile/bad-let (letfn*-init-id '(fn* [x x] x)))
        "a duplicate fixed binding is not modellable by set-shaped scope")
    (is (= :rf.ui.compile/bad-let (letfn*-init-id '(fn* [x & x] x)))
        "the rest binding may not duplicate a fixed binding")
    (is (= :rf.ui.compile/bad-let (letfn*-init-id '(fn* [x y x] x)))
        "adversarial: non-adjacent duplicates are still duplicates")
    (is (= :rf.ui.compile/bad-let (letfn*-init-id '(fn* ([a] a) ([b b] b))))
        "adversarial: a duplicate in a LATER overload of a legal-looking fn*")
    (is (= :rf.ui.compile/bad-let (letfn*-init-id '(fn* [a b & a] a)))
        "adversarial: rest duplicating the FIRST of several fixed bindings"))
  (testing "the raw fn* internal name must be a simple symbol"
    (is (= :rf.ui.compile/bad-let (letfn*-init-id '(fn* foo/bar ([x] x))))
        "a qualified internal name compiles on the JVM but emits invalid JS")
    (is (= :rf.ui.compile/bad-let (letfn*-init-id '(fn* foo/bar [x] x)))
        "adversarial: same hole via the single-arity spelling"))
  (testing "every fixed arity must be <= the variadic overload's required count"
    (is (= :rf.ui.compile/bad-let (letfn*-init-id '(fn* ([x] x) ([& r] r))))
        "f == v+1 (v=0): JVM rejects, CLJS drops the fixed overload")
    (is (= :rf.ui.compile/bad-let (letfn*-init-id '(fn* ([a b] a) ([x & r] x))))
        "f == v+1 (v=1): same boundary one arity up")
    (is (= :rf.ui.compile/bad-let (letfn*-init-id '(fn* ([a b c] a) ([x & r] x))))
        "f > v+1: JVM rejects, CLJS emits a broken dispatch")
    (is (= :rf.ui.compile/bad-let (letfn*-init-id '(fn* ([& r] r) ([a] a))))
        "adversarial: offending pair with the variadic overload written FIRST")
    (is (= :rf.ui.compile/bad-let
           (letfn*-init-id '(fn* ([] 0) ([a b] a) ([c & r] c))))
        "adversarial: a legal 0-arity prefix does not excuse the f=2 > v=1 pair"))
  (testing "legal host-portable raw fn* stays accepted"
    (is (nil? (letfn*-init-id '(fn* ([x] x) ([a b & r] a))))
        "f < v control (f=1, v=2) — accepted")
    (is (nil? (letfn*-init-id '(fn* ([a b] a) ([c d & r] c))))
        "f == v boundary (f=2, v=2) — both hosts compile it cleanly, so ACCEPT")
    (is (nil? (letfn*-init-id '(fn* ([] 0) ([& r] r))))
        "f == v == 0 — accepted")
    (is (nil? (letfn*-init-id '(fn* f [x & ys] x)))
        "distinct bindings + simple internal name — accepted"))
  (testing "the boundary is raw fn* ONLY — macro fn keeps its own grammar"
    (is (nil? (letfn*-init-id '(fn [x x] x)))
        "macro fn owns its parameter grammar — not the raw fn* boundary")
    (is (nil? (letfn*-init-id '(fn ([x] x) ([& r] r))))
        "macro fn arity combinations are its expansion's problem, not ours"))
  (testing "the accept/reject verdict is identical on both analyzer hosts"
    ;; A single table asserted on whichever host is running: CLJ and CLJS must
    ;; produce this same vector, which is what "host-portable" means here.
    (is (= [:rf.ui.compile/bad-let :rf.ui.compile/bad-let :rf.ui.compile/bad-let
            :rf.ui.compile/bad-let :rf.ui.compile/bad-let nil nil]
           (mapv letfn*-init-id
                 ['(fn* [x x] x)
                  '(fn* [x & x] x)
                  '(fn* foo/bar ([x] x))
                  '(fn* ([x] x) ([& r] r))
                  '(fn* ([a b c] a) ([x & r] x))
                  '(fn* ([a b] a) ([c d & r] c))
                  '(fn* f ([x] x))]))
        (str "host-portable verdict table, evaluated here on "
             #?(:clj "the JVM analyzer host" :cljs "the ClojureScript analyzer host")))))

(deftest frame-finite-sites
  (is (= :rf.ui.compile/frame-in-loop
         (reject-id '(for [x xs] [:li {:key x} (:frame (frame))])))
      "(frame) is a finite render-time site — never per-row")
  (is (= :rf.ui.compile/frame-in-loop
         (reject-id '(for [x xs :let [h (frame)]] [:li {:key x} (str h)])))
      "loop :let modifiers are row-scoped")
  (is (= :rf.ui.compile/frame-in-loop
         (reject-id '[:button {:on-click (fn [_] (do-send! (:dispatch (frame))))} "x"]))
      "deferred callbacks cannot capture the frame at event time — hoist the read")
  (is (= :rf.ui.compile/frame-in-loop
         (reject-id '[:button {:on-click [::open (:frame (frame))]} "x"]))
      "event-vector args evaluate at event time — deferred scope")
  (is (= :rf.ui.compile/frame-in-loop
         (reject-id '[:div {:ref (raw-fn (fn [_] (frame)))}]))
      "raw-fn bodies are host-deferred")
  (is (= :rf.ui.compile/frame-in-loop
         (reject-id '[:div {:title (loop [x 0] (frame))}])))
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (frame :app)}]))
      "(frame) takes no arguments — explicit targeting is frame-provider's job")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '(let [{:keys [x] :or {x (frame)}} value] [:div x])))
      "binding patterns/defaults cannot own a lexical render site")
  (is (nil? (reject-id '[:div {:title (-> (frame) :dispatch)}]))
      "a DIRECT call below a transparent macro is indexed normally")
  (is (= :rf.ui.compile/unsupported-form
         (reject-id '[:div {:title (-> x frame)}]))
      "a bare frame reference below a macro would become an unindexed
       call after expansion — rejected before expansion can hide it"))

(deftest loop-captured-handlers
  (is (= :rf.ui.compile/loop-capturing-handler
         (reject-id '(for [t ts] [:li {:key (:id t) :on-click [::open (:id t)]} "x"])))
      "per-row committed slots need per-row instances — extract a keyed child view")
  (is (= :rf.ui.compile/loop-capturing-handler
         (reject-id '(for [t ts]
                       [:li {:key (:id t)
                             :on-click {:event [::open t] :prevent-default true}} "x"])))
      "the options form is a site too")
  (is (= :rf.ui.compile/loop-capturing-handler
         (reject-id '(for [{:keys [id]} ts] [:li {:key id :on-click [::open id]} "x"])))
      "destructured loop bindings count")
  (is (= :rf.ui.compile/loop-capturing-handler
         (reject-id '(for [t ts]
                       [:input {:key (:id t)
                                :on-input (event [e] (conj [::set (:id t)]
                                                           (.. e -target -value)))}])))
      "a v/event capturing the loop binding is a site too — extract a keyed child")
  (is (nil? (reject-id '(let [id 1] [:li {:on-click [::open id]} "x"])))
      "non-loop locals in event vectors are the normal case")
  (is (nil? (reject-id '[:input {:value v
                                 :on-input (event [e] (conj on-value
                                                            (.. e -target -value)))}]))
      "a v/event capturing a view-level prefix (not a loop var) is the reusable case"))

(deftest handler-grammar
  (is (= :rf.ui.compile/bad-event-vector
         (reject-id '[:button {:on-click [event-sym 1]} "x"]))
      "event vectors start with a literal event-id keyword")
  (is (= :rf.ui.compile/bad-handler-options
         (reject-id '[:button {:on-click {:event [:a/b] :bubble true}} "x"]))
      "the listener option vocabulary is closed")
  (is (= :rf.ui.compile/bad-handler-options
         (reject-id '[:button {:on-click {:prevent-default true}} "x"]))
      "options maps need a literal :event vector")
  (is (= :rf.ui.compile/bad-ui-event
         (reject-id '[:input {:on-input (event [] [:x])}]))
      "v/event binds exactly the native event")
  (is (= :rf.ui.compile/bad-ui-event
         (reject-id '[:input {:on-input (event [a b] [:x])}]))
      "v/event binds exactly one event arg, not several"))

(deftest bare-fn-law
  (is (= :rf.ui.compile/bare-fn-prop
         (reject-id '[ForeignComp {:on-select (fn [x] x)}]))
      "bare fn at a foreign boundary — invoker/phase unknown")
  ;; C-13a (2026-07-16): a bare fn between INTERNAL views is a legal OPAQUE
  ;; value — identity-compared, never invoked by the framework, no invocation
  ;; phase. (Its ACCEPT fixture lives in analyze-accept-cljs-test.)
  (is (nil? (reject-id '[child-view {:cb #(inc %)}]))
      "bare fn at an INTERNAL-view boundary is a legal opaque value (C-13a)")
  (is (= :rf.ui.compile/bare-fn-prop
         (reject-id '[:div {:data-cb (fn [x] x)} "x"]))
      "bare fn at a non-event DOM prop")
  (is (= :rf.ui.compile/bare-fn-ref
         (reject-id '[:div {:ref (fn [el] el)} "x"]))
      "callback refs must be explicit (v/raw-fn f)")
  (is (nil? (reject-id '[:div {:ref (raw-fn (fn [el] el))} "x"]))
      "(v/raw-fn f) is the callback-ref spelling")
  ;; rf2-u53yy.3: an internal view forwards :ref (React 19 ref-as-prop) — an
  ;; object ref carries through the props object; the ref contract is the
  ;; element's, so a bare fn still needs the explicit (v/raw-fn f).
  (is (nil? (reject-id '[child-view {:ref object-ref}]))
      "an object :ref forwards to an internal view (declared-ref forwarding)")
  (is (nil? (reject-id '[child-view {:ref (raw-fn (fn [el] el))}]))
      "(v/raw-fn f) is the callback-ref forwarding spelling on a view")
  (is (= :rf.ui.compile/bare-fn-ref
         (reject-id '[child-view {:ref (fn [el] el)}]))
      "a bare-fn :ref on an internal view still needs (v/raw-fn f)")
  ;; rf2-u53yy.3 obligation 3: the foreign seam is NOT a bare-fn exception —
  ;; Spec 004 and the guide say every callback ref is explicit (v/raw-fn f).
  (is (= :rf.ui.compile/bare-fn-ref
         (reject-id '[ForeignComp {:ref (fn [el] el)}]))
      "a bare-fn :ref at a foreign component still needs (v/raw-fn f)")
  (is (nil? (reject-id '[ForeignComp {:ref (raw-fn (fn [el] el))}]))
      "(v/raw-fn f) is the callback-ref spelling at a foreign component")
  (is (nil? (reject-id '[ForeignComp {:ref object-ref}]))
      "an object :ref forwards to a foreign component (ordinary foreign props stay open)"))

(deftest ui-handler-grammar
  (is (= :rf.ui.compile/bad-ui-handler
         (reject-id '[:button {:on-click (handler [] (f))} "x"]))
      "v/handler binds at least the invoker's argument")
  (is (= :rf.ui.compile/bad-ui-handler
         (reject-id '[:button {:on-click (handler [a b] (f a b))} "x"]))
      "v/handler at a DOM site binds exactly the native event")
  (is (= :rf.ui.compile/bad-ui-callback
         (reject-id '[ForeignComp {:on-select (event [a b] [:x])}]))
      "v/event at a component prop binds exactly one invoker arg")
  (is (= :rf.ui.compile/bad-ui-callback
         (reject-id '[ForeignComp {:on-open (handler [& rest] (f rest))}]))
      "a component-prop v/handler is a fixed-arity vector (no &)"))

(deftest error-boundary-grammar
  (is (= :rf.ui.compile/bad-error-boundary
         (reject-id '(error-boundary {} [:p "x"])))
      ":fallback is required")
  (is (= :rf.ui.compile/bad-error-boundary
         (reject-id '(error-boundary {:fallback ForeignComp} [:p "x"])))
      ":fallback must be a defview, not a foreign component")
  (is (= :rf.ui.compile/bad-error-boundary
         (reject-id '(error-boundary {:fallback child-view :catch true} [:p "x"])))
      "the option set is closed")
  (is (= :rf.ui.compile/bad-error-boundary
         (reject-id '(error-boundary {:fallback child-view :on-error [oops]} [:p "x"])))
      ":on-error is a literal event vector [:domain/event …]")
  ;; The zero/one/many table the interpreted mount refuses identically
  ;; (`errors-tree-cljs-test` `an-error-boundary-guards-exactly-one-child`).
  (is (= :rf.ui.compile/bad-error-boundary
         (reject-id '(error-boundary {:fallback child-view})))
      "zero guarded children is refused — the boundary guards a region")
  (is (= :rf.ui.compile/bad-error-boundary
         (reject-id '(error-boundary {:fallback child-view} [:p "a"] [:p "b"])))
      "exactly ONE guarded child"))

(deftest client-only-grammar
  (is (= :rf.ui.compile/bad-client-only
         (reject-id '(client-only {} [:div "live"])))
      ":fallback is required")
  (is (= :rf.ui.compile/bad-client-only
         (reject-id '(client-only {:fallback [:p "…"] :extra 1} [:div "live"])))
      "the only option is :fallback")
  (is (= :rf.ui.compile/bad-client-only
         (reject-id '(client-only {:fallback [:p "…"]} [:a "x"] [:b "y"])))
      "exactly ONE client child")
  (is (= :rf.ui.compile/capability-in-fallback
         (reject-id '(client-only {:fallback [:button {:on-click [:retry]} "r"]}
                                  [:div "live"])))
      "an event handler in the fallback is a capability — reject")
  (is (= :rf.ui.compile/capability-in-fallback
         (reject-id '(client-only {:fallback [:p (sub [:q])]} [:div "live"])))
      "a reactive read in the fallback is a capability — reject"))

(deftest element-props
  (is (= :rf.ui.compile/rejected-prop-spelling (reject-id '[:div {:class-name "x"}]))
      "one spelling per name: :class")
  (is (= :rf.ui.compile/rejected-prop-spelling (reject-id '[:label {:html-for "x"}]))
      "one spelling per name: :for")
  (is (= :rf.ui.compile/rejected-prop-spelling
         (reject-id '[:div {:dangerouslySetInnerHTML {:__html "x"}}]))
      "dangerouslySetInnerHTML does not exist — v/html is the spelling")
  (is (= :rf.ui.compile/rejected-prop-spelling (reject-id '[:div {:children [x]}]))
      "children are positional")
  (is (= :rf.ui.compile/id-sugar-conflict (reject-id '[:div#a {:id "b"}]))
      "two id spellings on one element is an ambiguity")
  (is (= :rf.ui.compile/duplicate-id-sugar (reject-id [:div#a#b "x"])))
  (is (= :rf.ui.compile/collection-attr-value (reject-id '[:div {:data-foo {:a 1}} "x"]))
      "collections are only meaningful for :class/:style")
  (is (= :rf.ui.compile/non-keyword-prop (reject-id '[:div {"str-key" 1} "x"])))
  (is (= :rf.ui.compile/bad-style (reject-id '[:div {:style {(kw) 1}} "x"]))
      ":style keys must be literal keywords")
  (is (= :rf.ui.compile/bad-class (reject-id '[:div {:class {(kw) true}} "x"]))
      ":class flag-map keys must be literal names")
  ;; hiccup structure disambiguates props: a non-map second element is a
  ;; CHILD, never a dynamic props map — runtime prop maps go through
  ;; (v/spread base overrides)
  (is (nil? (reject-id '[:div some-expr "x"]))
      "a non-map expression after the tag is a dynamic child, not props"))

;; The rejected-spelling check reads the prop name the emitter WRITES —
;; `:react-name` is `(rules/react-prop-name (name k))` — so a namespace on the
;; key changes the spelling at the site and nothing about where the value
;; lands. Checking the raw keyword let `:x/children` compile straight into
;; React's reserved `children` slot, so a compiled declaration rendered content
;; its structural twin did not carry.
(deftest element-prop-keys-are-read-by-their-emitted-name
  (testing "an alias of a rejected spelling is rejected with it"
    (doseq [k '[:x/class-name :x/html-for :x/dangerouslySetInnerHTML
                :x/dangerously-set-inner-html :x/inner-html :x/children]]
      (is (= :rf.ui.compile/rejected-prop-spelling (reject-id [:div {k "x"}]))
          (str k " projects onto the same prop its exact spelling does"))))

  (testing ":ref has ONE accepted spelling; an alias would reach the reserved
            ref slot around the ref contract entirely"
    (is (= :rf.ui.compile/rejected-prop-spelling (reject-id '[:div {:x/ref r}]))
        ":x/ref is not a second spelling of :ref")
    (is (nil? (reject-id '[:div {:ref object-ref}]))
        "and the exact :ref spelling still routes through the ref contract"))

  ;; React's `key` is not a prop at all — the reconciler consumes it and it
  ;; never reaches the DOM — so an alias routed into that slot would change
  ;; RECONCILIATION IDENTITY rather than an attribute: wrong element reuse,
  ;; which is `:children`'s hazard class rather than a misspelled
  ;; attribute's. `:class` and `:style` go the other way in the same ruling,
  ;; because they ARE ordinary props (rf2-drpa3.93).
  (testing ":key has ONE accepted spelling; an alias would address the
            reconciler's element identity through a spelling the grammar
            never declared"
    (is (= :rf.ui.compile/rejected-prop-spelling (reject-id '[:div {:x/key "k"}]))
        ":x/key is not a second spelling of :key")
    (is (nil? (reject-id '[:div {:key "k"}]))
        "and the exact :key spelling is the ordinary keyed element"))

  (testing "the rule is not over-broad: a qualified key whose name projects
            onto an ORDINARY prop keeps its current semantics, an alias of a
            slot-owning key is ROUTED rather than refused, and so does every
            legitimate key that neighbours a rejected one"
    (doseq [form '[[:div {:x/title "ok"}]
                   [:div {:x/tab-index 3}]
                   [:div {:x/data-priority "high"}]
                   [:div {:data-priority "high"}]
                   [:div {:aria-hidden true}]
                   [:div {:class "a"}]
                   [:div.a.b {:x/class "c"}]
                   [:div.a.b {:x/class {:open true}}]
                   [:div {:x/style {:color "red"}}]
                   [:label {:for "n"}]
                   [:div {:x/on-click [:e]}]]]
      (is (nil? (reject-id form)) (str (pr-str form) " is an ordinary declaration")))))

(deftest void-elements
  (doseq [tag [:br :hr :img :input :param :keygen]]
    (is (= :rf.ui.compile/void-children (reject-id [tag "child"]))
        (str tag " cannot have children (React 19 throw parity incl. param/keygen)")))
  (is (= :rf.ui.compile/void-children (reject-id [:menuitem "child"]))
      "menuitem rejects children though it is not self-closing")
  (is (nil? (reject-id [:br])) "void elements without children are fine"))

(deftest component-call-sites
  (is (= :rf.ui.compile/children-prop (reject-id '[child-view {:children [x]}]))
      ":children as an explicit prop — children are positional (Q4)")
  (is (= :rf.ui.compile/children-not-accepted
         (reject-id '[leaf-view {} [:p "kid"]]))
      "children to a view that declares none (Q4)")
  (is (= :rf.ui.compile/undeclared-prop
         (reject-id '[closed-view {:a 1 :c 3}]))
      ":props present = closed map (Q2)")
  (is (nil? (reject-id '[closed-view {:a 1 :b 2 :key k}]))
      "declared keys + :key pass the closed check")
  ;; a non-map expression at a component site is a CHILD (the structural
  ;; literal-props pin): a childless view therefore rejects it outright
  (is (= :rf.ui.compile/children-not-accepted
         (reject-id '[leaf-view props-expr]))
      "no dynamic-props back door — a non-map is a child, and leaf views take none")
  ;; rf2-u53yy.5 — v/spread IS admitted at a FOREIGN component call site, but an
  ;; internal view keeps the literal-props requirement (its per-slot memo
  ;; comparator and slot ABI need the literal keys).
  (is (nil? (reject-id '[ForeignComp (spread {:selected d :on-change (handler [v] (pick v))}
                                             forwarded)]))
      "a foreign head accepts (v/spread literal-part runtime-map)")
  (is (nil? (reject-id '[ForeignComp (spread forwarded)]))
      "a foreign head accepts a plain forwarded map spelled through spread")
  (is (= :rf.ui.compile/spread-internal-view
         (reject-id '[child-view (spread {:a 1} forwarded)]))
      "an internal view rejects v/spread — literal props required")
  (is (= :rf.ui.compile/spread-internal-view
         (reject-id '[leaf-view (spread forwarded)]))
      "the internal-view spread rejection covers the plain forwarded form too"))

(deftest interop-positions
  (is (= :rf.ui.compile/html-not-sole-child
         (reject-id '[:div [:span "s"] (html "<b>x</b>")]))
      "(v/html ...) must be the SOLE child of a DOM element")
  (is (= :rf.ui.compile/html-not-sole-child
         (reject-id '(html "<b>x</b>")))
      "standalone v/html has no host element to own the markup")
  (is (= :rf.ui.compile/raw-fn-child (reject-id '(raw-fn f))))
  (is (= :rf.ui.compile/bad-spread (reject-id '(spread base)))
      "(v/spread ...) belongs in an element's props position")
  (is (= :rf.ui.compile/bad-raw (reject-id '(raw)))
      "(v/raw react-element) takes one argument")
  (is (= :rf.ui.compile/void-children (reject-id '[:br (html "<b>x</b>")]))
      "void elements cannot own trusted markup either"))

(deftest special-element-child-shapes
  ;; rf2-ib4fd — static special-element child shapes the TARGET (React 19.2 /
  ;; the JVM serialiser) rejects are rejected at COMPILE, not emitted to fail
  ;; later. Fail-fast with a clear diagnostic beats a silent wrong render.
  (testing "(v/html …) beneath a static <textarea> is rejected (React 19.2 "
           "rejects dangerouslySetInnerHTML on a textarea)"
    ;; RED-BEFORE lever: this compiled and lowered to React
    ;; dangerouslySetInnerHTML / a divergent JVM trusted-markup body.
    (is (= :rf.ui.compile/html-in-textarea
           (reject-id '[:textarea (html "<b>x</b>")]))
        "a textarea's content is :value or a text child, not trusted markup"))
  (testing "a static <textarea>'s single-text-child contract rejects the "
           "host-divergent multi/value+child/structural shapes (rf2-ib4fd "
           "residual — the cases #6517's (v/html …) rule did NOT cover)"
    ;; RED-BEFORE lever: each of these COMPILED before this rule — React 19.2
    ;; throws "<textarea> can only have at most one child" for two children and
    ;; for value+child, and renders a structural child as "[object Object]",
    ;; while the JVM serialiser emitted `ab` / silently dropped the child for
    ;; `v` / emitted `<span>x</span>` respectively.
    (is (= :rf.ui.compile/textarea-children
           (reject-id '[:textarea "a" "b"]))
        "two text children — React allows at most one")
    (is (= :rf.ui.compile/textarea-children
           (reject-id '[:textarea {:value "v"} "c"]))
        "literal :value AND an authored child — React rejects the pair")
    (is (= :rf.ui.compile/textarea-children
           (reject-id '[:textarea {:default-value "v"} "c"]))
        "literal :default-value AND an authored child rejects identically")
    (is (= :rf.ui.compile/textarea-children
           (reject-id '[:textarea [:span "x"]]))
        "a structural (hiccup element) sole child — React renders [object Object]")
    (is (= :rf.ui.compile/textarea-children
           (reject-id '[:textarea (for [x xs] [:span {:key x} x])]))
        "a `for` list sole child (a seq of children) rejects the same way"))
  (testing "an ordinary <textarea> body stays supported"
    (is (nil? (reject-id '[:textarea {:value txt}]))
        ":value is the textarea content channel")
    (is (nil? (reject-id '[:textarea {:default-value "d"}]))
        ":default-value alone (no child) is fine")
    (is (nil? (reject-id '[:textarea "plain text"]))
        "an ordinary text child is fine")
    (is (nil? (reject-id '[:textarea body-str]))
        "a runtime-dynamic sole text child stays programmer-trusted")
    (is (nil? (reject-id '[:textarea {:read-only true} txt]))
        "a non-value prop plus a single text child is fine")
    (is (nil? (reject-id '[:textarea]))
        "an empty textarea is fine"))
  (testing "a static <script>/<style> raw-text element rejects a host-divergent "
           "multi-child or structural-child body"
    ;; RED-BEFORE lever: `[:script "a" "b"]` compiled (the emitter coalesced the
    ;; strings), and `[:script [:span]]` compiled through analyze (failing only
    ;; later at the JVM serialiser) — React warns/loses the multi body and
    ;; drops/stringifies a structural child.
    (is (= :rf.ui.compile/raw-text-children (reject-id '[:script "a" "b"]))
        "multiple source children under <script>")
    (is (= :rf.ui.compile/raw-text-children (reject-id '[:style "a" "b"]))
        "multiple source children under <style>")
    (is (= :rf.ui.compile/raw-text-children (reject-id '[:script [:span "x"]]))
        "a visibly structural (hiccup) sole child under <script>")
    (is (= :rf.ui.compile/raw-text-children
           (reject-id '[:style (for [x xs] [:span {:key x} x])]))
        "a `for` list markup sole child under <style>"))
  (testing "the accepted <script>/<style> bodies retain their intended paths"
    (is (nil? (reject-id '[:script "console.log(1)"]))
        "one literal text child")
    (is (nil? (reject-id '[:style (str a b)]))
        "one text-producing expression")
    (is (nil? (reject-id '[:script js-src]))
        "a runtime-dynamic scalar stays programmer-trusted")
    (is (nil? (reject-id '[:script (html "console.log(1)")]))
        "a sole (v/html …) is the sanctioned trusted-markup body")
    (is (nil? (reject-id '[:style]))
        "no body is fine")
    (is (nil? (reject-id '[:script {:src "/main.js"}]))
        "attributes with no body are fine")))

(deftest statically-pure-bodies
  (is (= :rf.ui.compile/multi-form-body
         (reject-id '(do (prn "side effect") [:p "x"])))
      "multi-form do — side effects don't belong in templates")
  (is (= :rf.ui.compile/multi-form-body
         (reject-id '(when c [:p "a"] [:p "b"])))
      "when takes ONE template form; siblings wrap in [:<> ...]")
  (is (= :rf.ui.compile/multi-form-body
         (reject-id '(let [x 1] [:p "a"] [:p x])))))

(deftest fragment-props
  (is (= :rf.ui.compile/bad-fragment-props
         (reject-id '[:<> {:key k :class "x"} [:p "a"]]))
      "fragments take only {:key ...}"))
