(ns re-frame.ui.defview-grammar-jvm-test
  "defview declaration grammar (arities, options map, Q2 header rules,
  the RULED custom-element grammar) + the removed-forms export-surface
  check. Macro-level, so JVM-hosted — the macro code is host-shared, so
  these pins hold for the CLJS expansion path too."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            ;; `sub`/`frame` are referred so a BARE spelling resolves to
            ;; the public reactive authoring var through REAL CLJ macroexpansion.
            ;; The rf2-vxgfnd.268 ordered-scope proofs need the bare `sub` the
            ;; dzyqis flat scope over-accepted; the rf2-vxgfnd.274 self-precedence
            ;; proofs need the bare spellings so a recursive `defview`
            ;; named for a verb reproduces the reserved-before-self escape.
            [re-frame.ui :as ui :refer [defview sub frame]]
            [re-frame.ui.compiler :as compiler]
            [re-frame.ui.compiler.build :as build]
            [re-frame.ui.compiler.emit-cljs :as emit-cljs]
            [re-frame.ui.compiler.emit-jvm :as emit-jvm]
            [re-frame.ui.compiler.header :as header]))

(defn- expand-error
  "Macroexpand a defview/custom-element form; nil when it expands, the
  :rf.ui.compile/error id when it throws."
  [form]
  (try
    (macroexpand-1 form)
    nil
    (catch clojure.lang.ExceptionInfo ex
      (:rf.ui.compile/error (ex-data ex)))
    (catch Exception ex
      ;; macroexpansion wraps in CompilerException in some paths
      (or (:rf.ui.compile/error (ex-data ex))
          (let [c (.getCause ex)]
            (when (instance? clojure.lang.ExceptionInfo c)
              (:rf.ui.compile/error (ex-data c))))))))

(defmacro injected-sub []
  '(re-frame.ui/sub [:injected]))

;; ---------------------------------------------------------------------------
;; Arities + options
;; ---------------------------------------------------------------------------

(deftest declaration-arities
  (is (nil? (expand-error '(re-frame.ui/defview v1 [] [:div "x"])))
      "zero-arg view")
  (is (nil? (expand-error '(re-frame.ui/defview v2 "doc" [] [:div "x"])))
      "docstring")
  (is (nil? (expand-error '(re-frame.ui/defview v3 "doc" {:display-name "V"}
                             [{:keys [a]}] [:div a])))
      "docstring + options")
  (is (nil? (expand-error '(re-frame.ui/defview v4 [p] [:div (str p)])))
      "bare symbol argv ≡ {:as p}")
  (is (= :rf.ui.compile/bad-defview-args
         (expand-error '(re-frame.ui/defview v [:div "no argv"]))))
  (is (= :rf.ui.compile/positional-args
         (expand-error '(re-frame.ui/defview v [a b] [:div a])))
      "no positional args — one props map")
  (is (= :rf.ui.compile/multi-form-body
         (expand-error '(re-frame.ui/defview v [] [:div "a"] [:div "b"])))
      "the body is exactly ONE template form")
  (is (= :rf.ui.compile/bad-defview-args
         (expand-error '(re-frame.ui/defview v ["not-a-binding"] [:div "x"])))
      "the one argument is a map-destructuring form or a symbol"))

(deftest reactive-header-defaults-are-rejected-before-host-lowering
  (is (= :rf.ui.compile/unsupported-form
         (expand-error
          '(re-frame.ui/defview reactive-header
             [{:keys [sub] :or {sub (re-frame.ui/sub [:fallback])}}]
             [:div sub])))
      "a header default cannot bypass site indexing and select sub-free render")
  (is (= :rf.ui.compile/unsupported-form
         (expand-error
          '(re-frame.ui/defview threaded-reactive-header
             [{:keys [x]
               :or {x (-> [:fallback] re-frame.ui/sub)}}]
             [:div x])))
      "a header macro cannot manufacture a one-arity read after site analysis")
  (is (= :rf.ui.compile/unsupported-form
         (expand-error
          '(re-frame.ui/defview opaque-macro-header
             [{:keys [x]
               :or {x (re-frame.ui.defview-grammar-jvm-test/injected-sub)}}]
             [:div x])))
      "even an invocation with no visible sub token is fenced")
  (is (nil? (expand-error
             '(re-frame.ui/defview ordinary-header
                [{:keys [x] :or {x (str "fallback")}}]
                [:div x])))
      "ordinary pure defaults keep the documented header ergonomics")
  (is (nil? (expand-error
             '(re-frame.ui/defview shadowing-header [{:keys [sub]}] [:div sub])))
      "the header local shadows re-frame.ui/sub only in the body"))

(deftest header-destructuring-scope-follows-host-evaluation-order
  ;; rf2-vxgfnd.268 — the ordered-scope correction, proved through ACTUAL CLJ
  ;; defview macroexpansion (the macro JVM expands the CLJS path too). A defview
  ;; header destructures SEQUENTIALLY, so a bare `sub` default is shadowed only
  ;; by a same-pattern local bound EARLIER. The dzyqis flat scope treated every
  ;; header-bound symbol as in-scope before scanning, so a LATER-bound / SELF
  ;; `sub` falsely shadowed the escape and the header compiled with an empty
  ;; manifest, leaving public re-frame.ui/sub to survive to runtime unindexed.
  ;; *ns* is bound to THIS ns (which refers `sub`) so the bare, non-qualified
  ;; spelling resolves to re-frame.ui/sub through real macroexpansion — the test
  ;; runner otherwise leaves *ns* at `user`, where only qualified names resolve.
  (binding [*ns* (find-ns 're-frame.ui.defview-grammar-jvm-test)]
    (is (= :rf.ui.compile/unsupported-form
           (expand-error
            '(re-frame.ui/defview self-default-header
               [{:keys [sub] :or {sub sub}}] [:div sub])))
        "a self-referential bare sub default is the outer var, not the local")
    (is (= :rf.ui.compile/unsupported-form
           (expand-error
            '(re-frame.ui/defview later-shadow-header
               [{:keys [f sub] :or {f sub}}] [:div f])))
        "a bare sub default shadowed only by a LATER header binding escapes")
    (is (nil? (expand-error
               '(re-frame.ui/defview earlier-shadow-header
                  [{:keys [sub f] :or {f sub}}] [:div f])))
        "a default referencing an EARLIER-bound header local is the shadow — legal")
    (is (nil? (expand-error
               '(re-frame.ui/defview earlier-call-header
                  [{:keys [sub f] :or {f (sub :fallback)}}] [:div f])))
        "a call on an earlier-bound local shadow is an ordinary local call — legal")))

(deftest reactive-verb-in-component-head-is-reserved-through-real-host-resolution
  ;; rf2-vxgfnd.266 — the pure-analyzer fixtures inject resolution; this pins the
  ;; same reservation through ACTUAL CLJ var resolution during defview
  ;; macroexpansion (the macro JVM expands for the CLJS path too). A Hiccup head
  ;; resolving to a public reactive authoring var (sub/frame) must be
  ;; reserved BEFORE env/classify-head can classify it as a plain :foreign
  ;; component with an empty reactive manifest, letting the public authoring var
  ;; survive to runtime unindexed.
  (is (= :rf.ui.compile/unsupported-form
         (expand-error '(re-frame.ui/defview v [] [:div [re-frame.ui/sub {}]])))
      "a fully-qualified sub head is reserved through real resolution")
  (is (= :rf.ui.compile/unsupported-form
         (expand-error '(re-frame.ui/defview v [] [:div [re-frame.ui/frame]])))
      "a fully-qualified frame head is reserved")
  ;; (aliased/unqualified spellings are covered by the pure-analyzer reject
  ;; fixture, whose injected resolver models the analyzer resolution contract;
  ;; this file follows the established convention of fully-qualified spellings
  ;; under raw macroexpand-1, where namespace-alias resolution is not available.)
  ;; the reservation is NARROW: a direct (sub …) CALL is still the compiler-owned
  ;; form and expands to exactly one manifest site — only the head form is reserved
  (is (nil? (expand-error '(re-frame.ui/defview v [] [:div (re-frame.ui/sub [:q])])))
      "sub is still legal as its compiler-owned direct call — only the head is reserved")
  (is (nil? (expand-error '(re-frame.ui/defview v [] [:div (:frame (re-frame.ui/frame))])))
      "a direct (frame) read still expands — only the head form is reserved"))

(deftest self-recursive-defview-named-reactive-verb-compiles-through-real-resolution
  ;; rf2-vxgfnd.274 — the escape the .266 reservation opened. A `defview` whose
  ;; OWN name is a referred reactive authoring verb recurses on itself with a
  ;; bare `[self …]` head. That head resolves (through REAL CLJ macroexpansion,
  ;; *ns* bound below so the bare spelling is the referred re-frame.ui var) to
  ;; the public authoring var — so the .266 reservation rejected it BEFORE the
  ;; Q5 self-recursion rule could select it, even though the view Var may not
  ;; exist yet. Self precedence (tier 2) now fires ahead of the reservation.
  ;; Reverting to reserved-before-self re-throws :rf.ui.compile/unsupported-form
  ;; on the accept rows, so this deftest is the real-host mutation fixture.
  (binding [*ns* (find-ns 're-frame.ui.defview-grammar-jvm-test)]
    (testing "a recursive defview named for each reactive verb expands cleanly"
      (is (nil? (expand-error '(re-frame.ui/defview sub [] [sub {}])))
          "a view named sub recurses on a bare [sub …] self head")
      (is (nil? (expand-error '(re-frame.ui/defview frame [] [frame {}])))
          "a view named frame recurses on a bare [frame …] self head"))
    (testing "a local binding of the self spelling still outranks self (tier 1)"
      (is (= :rf.ui.compile/dynamic-head
             (expand-error
              '(re-frame.ui/defview sub [{:keys [sub]}] [sub {}])))
          "a header slot named sub shadows self — the head is a dynamic head"))
    (testing "the emitted output carries NO surviving public authoring var"
      ;; rf2-vxgfnd.274 output proof: the self head lowers to the view's own
      ;; registered component, never a re-frame.ui/sub reference or a lowered
      ;; sub-read site — the public authoring function does not reach executable
      ;; output through the recursive head.
      (let [text (pr-str (macroexpand-1 '(re-frame.ui/defview sub [] [sub {}])))]
        (is (not (re-find #"re-frame\.ui/sub" text))
            "no qualified re-frame.ui/sub survives the self head")
        (is (not (re-find #"sub-read" text))
            "the self head is a view element, not a lowered reactive read")
        (is (re-find #"register-view!" text)
            "the recursive view still registers as an ordinary internal view")))))

(deftest an-opaque-macro-cannot-inject-an-unmanifested-template-site
  (is (= :rf.ui.compile/unsupported-form
         (expand-error
          '(re-frame.ui/defview injected-template []
             [:div (re-frame.ui.defview-grammar-jvm-test/injected-sub)])))
      "sub-free production selection cannot be fooled by later macroexpansion"))

(deftest options-map-is-closed
  (is (= :rf.ui.compile/unknown-option
         (expand-error '(re-frame.ui/defview v {:memo false} [] [:div])))
      ":memo false was considered and rejected")
  (is (= :rf.ui.compile/unknown-option
         (expand-error '(re-frame.ui/defview v {:on-mount [:x/y]} [] [:div])))
      ":on-mount cannot ride mechanical React lifecycle")
  (is (= :rf.ui.compile/unknown-option
         (expand-error '(re-frame.ui/defview v {:catch true} [] [:div])))
      "error handling is the explicit ui/error-boundary component")
  (is (= :rf.ui.compile/bad-view-id
         (expand-error '(re-frame.ui/defview v {:id :unqualified} [] [:div])))
      ":id override must be a qualified keyword"))

;; ---------------------------------------------------------------------------
;; Q2 — header rules
;; ---------------------------------------------------------------------------

(deftest header-rules
  (is (= :rf.ui.compile/key-prop-declared
         (expand-error '(re-frame.ui/defview v [{:keys [key]}] [:div key])))
      ":key is reserved — it feeds React's key slot")
  (is (= :rf.ui.compile/key-prop-declared
         (expand-error '(re-frame.ui/defview v {:props [:map [:key :string]]}
                          [] [:div])))
      "…including via the :props schema")
  (is (nil? (expand-error '(re-frame.ui/defview v [{r :ref}] [:div {:ref r}])))
      "a declared :ref is an ordinary forwardable slot (React 19 ref-as-prop)")
  (is (= :rf.ui.compile/bad-defview-args
         (expand-error '(re-frame.ui/defview v [{:strs [a]}] [:div a])))
      ":strs/:syms are outside the props ABI — slots are keywords")
  (is (= :rf.ui.compile/bad-defview-args
         (expand-error '(re-frame.ui/defview v [{:keys [a] :or {b 1}}] [:div a])))
      ":or keys must match bound slot symbols"))

(deftest qualified-key-ref-props-accepted-in-every-keys-spelling
  ;; rf2-wix0o — the raw reserved-slot `:keys` arm derived each group key with
  ;; `(keyword (name s))`, DROPPING a qualified symbol's namespace: the legal
  ;; `{:keys [acct/key]}` was wrongly rejected as reserved React `:key` and
  ;; `{:keys [acct/ref]}` as bare `:ref`, while the EQUIVALENT `{:acct/keys …}`
  ;; and explicit `{p :acct/key}` spellings preserved the namespace and
  ;; compiled. Raw diagnostics now reuse the canonical group-key transform
  ;; (`bp/key-group-directive-fn`) — the SAME one the binding plan uses — so
  ;; every equivalent host spelling derives the same qualified slots, while a
  ;; genuinely bare `:key`/`:ref` group symbol still fails before collapse.
  (testing "all equivalent host spellings derive the SAME qualified slots"
    (let [group  (:slots (header/parse-header '[{:keys [acct/key acct/ref]}]))
          nsgrp  (:slots (header/parse-header '[{:acct/keys [key ref]}]))
          explct (:slots (header/parse-header '[{k :acct/key r :acct/ref}]))]
      (is (= [:acct/key :acct/ref] group)
          "bare :keys keeps each qualified symbol's namespace, not bare :key/:ref")
      (is (= group nsgrp)   ":acct/keys spelling derives the same slots")
      (is (= group explct)  "explicit qualified-lookup spelling derives the same slots")))
  (testing "the qualified slots match REAL clojure.core/destructure lookup keys"
    ;; native destructure binds local `key` via `(get m :acct/key)` — ground truth
    (let [f (eval (list 'fn '[{:keys [acct/key acct/ref]}] '[key ref]))]
      (is (= [1 2] (f {:acct/key 1 :acct/ref 2}))
          "native destructure reads the QUALIFIED slots into locals key/ref")))
  (testing "all three spellings COMPILE as real defviews (not emitter-form inspection)"
    (is (nil? (expand-error '(re-frame.ui/defview v [{:keys [acct/key acct/ref]}] [:div "x"])))
        "the bare :keys spelling with qualified symbols compiles")
    (is (nil? (expand-error '(re-frame.ui/defview v [{:acct/keys [key ref]}] [:div "x"])))
        "the :acct/keys spelling compiles")
    (is (nil? (expand-error '(re-frame.ui/defview v [{k :acct/key r :acct/ref}] [:div "x"])))
        "the explicit qualified-lookup spelling compiles"))
  (testing "a genuinely BARE :key group symbol stays rejected; :ref is forwardable"
    (is (= :rf.ui.compile/key-prop-declared
           (expand-error '(re-frame.ui/defview v [{:keys [key]}] [:div "x"])))
        "a bare `:keys [key]` still feeds the reserved React key slot")
    (is (nil? (expand-error '(re-frame.ui/defview v [{:keys [ref]}] [:div {:ref ref}])))
        "a bare `:keys [ref]` declares the forwardable ref slot (React 19 ref-as-prop)")
    (is (= [:ref] (:slots (header/parse-header '[{:keys [ref]}])))
        "the declared :ref is a real comparator/manifest slot read from props.ref")))

(deftest q3-slot-encoding-table
  ;; the encode function E — the props-ABI freeze's normative core
  (is (= "product"    (header/slot-name :product)))
  (is (= "cart/item"  (header/slot-name :cart/item)) "namespace preserved")
  (is (= "on-select"  (header/slot-name :on-select)) "no camelization — view props are not DOM props")
  (is (= "a-b?"       (header/slot-name :a-b?)) "punctuation verbatim (quoted JS access)")
  (is (= "class"      (header/slot-name :class)) "no reserved-JS-word mangling")
  (is (= "children"   (header/slot-name :children)) "children is the children slot"))

(deftest q2-declared-slots-and-closure
  (let [hdr (header/parse-header '[{:keys [a b] :cart/keys [item] :as all}])]
    (is (= [:a :b :cart/item] (:slots hdr)))
    (is (= :as (:mode hdr)))
    (is (true? (:children? hdr)) ":as reaches every slot — children included"))
  (is (= [:a :b :c]
         (header/declared-slots (header/parse-header '[{:keys [a b]}])
                                (header/props-schema-keys [:map [:b :int] [:c :int]])))
      "header order first, then schema-only keys")
  (is (nil? (header/props-schema-keys 'SomeSchemaVar))
      "non-literal schemas cannot be introspected — no closed-map enforcement"))

(deftest qualified-group-name-collision-drops-the-dead-declared-slot
  ;; rf2-7imr5y — header `{:keys [ns/x] x :other}`: the qualified group local
  ;; `ns/x` name-strips onto the explicit local `x`; the host binds `x <- :ns/x`
  ;; (last-wins) and the explicit `:other` lookup is DEAD (never bound). The
  ;; collapse now drops `:other` from the declared slots, so it no longer
  ;; over-declares in the manifest / over-compares in the memo comparator.
  (testing "the dead-shadowed :other is not a declared slot; :ns/x is"
    (let [hdr (header/parse-header '[{:keys [ns/x] x :other}])]
      (is (= [:ns/x] (:slots hdr)) "only the host-winning :ns/x is declared")
      (is (= [:ns/x] (header/declared-slots hdr nil)))))
  ;; The observable BEHAVIOUR CHANGE (flagged in the PR): under a CLOSED :props
  ;; schema the dead `:other` was silently ACCEPTED as a declared prop; after the
  ;; collapse it is NOT declared, so passing it at a literal call site is a
  ;; compile error. Correct — `:other` is never bound, so declaring it was the
  ;; bug. Pinned via a self-render call site (exercises :self-closed-keys).
  (testing "CLOSED :props now REJECTS the dead :other at a call site"
    (is (nil? (expand-error
               '(re-frame.ui/defview v {:props [:map [:ns/x :any]]}
                  [{:keys [ns/x] x :other}] [v {:ns/x 1}])))
        "the declared, host-winning :ns/x prop is accepted")
    (is (= :rf.ui.compile/undeclared-prop
           (expand-error
            '(re-frame.ui/defview v {:props [:map [:ns/x :any]]}
               [{:keys [ns/x] x :other}] [v {:other 1}])))
        "the dead :other is no longer a declared prop — closed-map rejects it"))
  (testing "OPEN :props (no schema) is unchanged — extra props stay legal"
    (is (nil? (expand-error
               '(re-frame.ui/defview v
                  [{:keys [ns/x] x :other}] [v {:other 1}])))
        "an open map accepts undeclared props, dead-shadowed or not")))

(deftest nested-and-partial-overlap-drive-the-closed-props-set-too
  ;; rf2-4xpah — PR #6228 extended the collapse to NESTED and partially
  ;; overlapping patterns, but pinned the consequence only on the comparator and
  ;; the manifest (compiled-CLJS lane). The declared-slot set also gates CLOSED
  ;; `:props`, so the same two shapes belong here beside their sibling case.
  (testing "NESTED: [x] <- :other is fully reclaimed, so :other is undeclared"
    (let [hdr (header/parse-header '[{[x] :other :keys [ns/x]}])]
      (is (= [:ns/x] (:slots hdr)))
      (is (= [:ns/x] (header/declared-slots hdr nil))))
    (is (nil? (expand-error
               '(re-frame.ui/defview v {:props [:map [:ns/x :any]]}
                  [{[x] :other :keys [ns/x]}] [v {:ns/x 1}])))
        "the host-winning :ns/x prop is accepted")
    (is (= :rf.ui.compile/undeclared-prop
           (expand-error
            '(re-frame.ui/defview v {:props [:map [:ns/x :any]]}
               [{[x] :other :keys [ns/x]}] [v {:other [1]}])))
        "the dead :other binds nothing, so a closed map must reject it"))
  (testing "PARTIAL overlap: b still reads :other, so :other STAYS declared"
    (let [hdr (header/parse-header '[{[a b] :other :keys [ns/a]}])]
      (is (= [:other :ns/a] (:slots hdr)))
      (is (= [:other :ns/a] (header/declared-slots hdr nil))))
    (is (nil? (expand-error
               '(re-frame.ui/defview v {:props [:map [:other :any] [:ns/a :any]]}
                  [{[a b] :other :keys [ns/a]}] [v {:other [1 2]}])))
        "a surviving visible local keeps its slot legal at a closed call site")))

;; ---------------------------------------------------------------------------
;; custom-element (RULED grammar, closed)
;; ---------------------------------------------------------------------------

(deftest custom-element-grammar
  (is (nil? (expand-error '(re-frame.ui/custom-element :x-el {:properties #{:a-b}}))))
  (is (nil? (expand-error '(re-frame.ui/custom-element :x-el {}))))
  (is (= :rf.ui.compile/bad-custom-element
         (expand-error '(re-frame.ui/custom-element :div {:properties #{}})))
      "custom elements are tags containing '-'")
  (is (= :rf.ui.compile/bad-custom-element
         (expand-error '(re-frame.ui/custom-element :x-el {:events #{:changed}})))
      "the options map is CLOSED: {:properties #{...}} is the entire v1 grammar")
  (is (= :rf.ui.compile/bad-custom-element
         (expand-error '(re-frame.ui/custom-element :x-el {:properties [:a]})))
      ":properties is a literal set"))

;; ---------------------------------------------------------------------------
;; Removed forms — the export surface IS the absence check
;; ---------------------------------------------------------------------------

(deftest export-surface-is-exactly-the-blessed-set
  (is (= '#{defview custom-element sub raw html raw-fn spread
            ;; S1c (rf2-vxgfnd.3) — root identity + the mount surface
            mount create-root render! hydrate-root unmount! frame-root
            ;; S2c (rf2-vxgfnd.9) — the SCOPE-only frame-provider form
            frame-provider
            ;; S2 public boot substrate (12 §2 frozen row)
            adapter
            ;; S2 ops-bundle body form (rf2-vxgfnd.184)
            frame
            ;; S3 committed handler form: the compiler-owned ui/event site
            ;; whose synchronous vector result rides the sync door (rf2-8k14ia)
            event
            ;; S3 internal compiled render slots: the pure render-slot callback
            ;; and its compiler-owned invocation form (rf2-ri0k6n)
            render-fn slot
            ;; S3 literal safe-spread policy — attr passthrough that cannot
            ;; clobber owned props or forfeit the sync door (rf2-isdqjv)
            spread-safe
            ;; S3 host hooks — view-local ephemera, the DOM-node ref, host
            ;; effects, and the stable committed-frame dispatcher (rf2-vxgfnd.95.2;
            ;; ref promoted from the re-frame.ui.react ref hook, rf2-u53yy.9)
            local ref effect dispatch-fn
            ;; S3 explicit callback boundaries, error recovery, client-only
            ;; (rf2-vxgfnd.95.3): the imperative committed callback + the two
            ;; interop recovery/boundary forms
            handler error-boundary client-only
            ;; S3 route-link (rf2-vxgfnd.95.5): an ORDINARY compiled defview over
            ;; the routing-owned late-bound link seam — the FIRST framework-
            ;; provided compiled view, not a compiler intrinsic
            route-link
            ;; S4 presence (rf2-uckeg): declarative enter/exit retention — the
            ;; template form + its single phase read
            presence presence-phase
            ;; S5 render-static (rf2-oo5lb): the pure :server static-HTML render
            ;; macro — non-hydrating (no manifest/payload/phase flip); enforces the
            ;; literal root form like the other root macros
            render-static
            ;; S6 ->react (rf2-u53yy.2): the OUTWARD interop bridge — export a
            ;; compiled view as a foreign React component (memoised per view id,
            ;; scopes a supplied frame, no root/preflight). CLJS delegates to
            ;; re-frame.ui.react/->react; a JVM call raises :rf.error/jvm-host-op
            ->react}
         (set (keys (ns-publics 're-frame.ui))))
      "no reg-view family, no Form-1/2/3 helpers, no h macro, no view
       lookup, no ratom/cursor/reaction — Spec 004 §Removed forms"))

;; ---------------------------------------------------------------------------
;; CLJS emission wiring (order-free pin: the emitter is .cljc, so the
;; exact CLJS expansion is data on this host)
;; ---------------------------------------------------------------------------

(deftest cljs-emission-wires-memo-registration-and-debug-gate
  (let [forms (compiler/defview* '(defview probe [] [:div "x"])
                                 {:ns {:name 'app.probe}} ; cljs env marker
                                 'probe
                                 '([] [:div "x"]))
        text  (pr-str forms)]
    (is (str/includes? text "re-frame.ui.runtime/memo-view")
        "every internal view is memoized — no opt-out")
    (is (str/includes? text "re-frame.ui.runtime/register-view!")
        "defview emits the registrar :view registration")
    (is (str/includes? text "js/goog.DEBUG")
        "the stable-shell registration/manifest emission is dev-gated (I-12)")
    (is (re-find #"\(if js/goog.DEBUG \(re-frame.ui.runtime/register-view!.*\(re-frame.ui.runtime/memo-view"
                 text)
        "golden: DEV registers the stable shell while the sibling production
         arm is the direct React.memo path — no unconditional HMR residue")
    (is (not (str/includes? text "bd1-"))
        "no-pass REPL expansion may replace the body but never publishes build identity")
    (let [def-sym (some #(when (and (seq? %) (= 'def (first %))
                                    (= 'probe (second %)))
                           (second %))
                        forms)]
      (is (true? (:rf.ui/view (meta def-sym)))
          "the public var carries the Q5 discrimination meta")
      (is (= :app.probe/probe (:rf.ui/view-id (meta def-sym)))))))

(deftest cljs-emission-keeps-dev-hooks-fixed-and-production-specialized
  (let [args        (fn [vname subs]
                      {:vname vname
                       :view-id (keyword "app.probe" (name vname))
                       :display-name (str "app.probe/" vname)
                       :docstring nil
                       :header (header/parse-header [])
                       :slots []
                       :ast {:op :text :value "x"}
                       :manifest {:view-id (keyword "app.probe" (name vname))
                                  :hook-signature "hs1-fixed"
                                  :sites {:subs subs}}
                       :closed-keys nil
                       :children? false})
        plain-forms (emit-cljs/emit-defview (args 'plain []))
        sub-forms   (emit-cljs/emit-defview
                     (args 'observed [{:query [:value]}]))
        plain-text  (pr-str plain-forms)
        sub-text    (pr-str sub-forms)]
    (testing "sub-free production stays on the raw memo path"
      (is (not (str/includes? plain-text "re-frame.ui.viewcell/render")))
      (is (not (str/includes? plain-text "plain$host_render")))
      (is (re-find #"re-frame.ui.runtime/memo-view plain\$render"
                   plain-text)))
    (testing "sub-bearing production gets the ViewCell host wrapper"
      (is (str/includes? sub-text "observed$host_render"))
      (is (str/includes? sub-text "re-frame.ui.viewcell/render"))
      (is (re-find #"re-frame.ui.runtime/register-view! :app.probe/observed observed\$render"
                   sub-text)
          "DEV publishes the raw body; stable Inner supplies the invariant skeleton")
      (is (re-find #"re-frame.ui.runtime/memo-view observed\$host_render"
                   sub-text)
          "production memoizes the specialized ViewCell host wrapper"))))

(deftest both-host-forms-carry-only-the-compact-site-id-and-literals-hoist
  (let [args (fn [vname query]
               {:vname vname
                :view-id (keyword "app.probe" (name vname))
                :display-name (str "app.probe/" vname)
                :docstring nil
                :header (header/parse-header [])
                :slots []
                :ast {:op :expr
                      :form (list 're-frame.ui.reactive/sub-read
                                  "sid1-fixture" query)}
                :manifest {:view-id (keyword "app.probe" (name vname))
                           :hook-signature "hs1-fixed"
                           :sites {:subs [{:sid "sid1-fixture"
                                          :query query
                                          :path [:child 0]
                                          :expr-path [:expr]}]}}
                :closed-keys nil
                :children? false})
        literal-cljs (pr-str (emit-cljs/emit-defview
                              (args 'literal-site [:item/by-id 1])))
        dynamic-cljs (pr-str (emit-cljs/emit-defview
                              (args 'dynamic-site [:item/by-id 'id])))
        jvm-text (pr-str (emit-jvm/emit-defview
                          (args 'jvm-site [:item/by-id 1])))]
    (is (re-find #"literal-site\$query\$0 \[:item/by-id 1\]" literal-cljs)
        "literal queries are module constants, not per-render allocations")
    (is (re-find #"sub-read \"sid1-fixture\" literal-site\$query\$0"
                 literal-cljs))
    (is (re-find #"sub-read \"sid1-fixture\" \[:item/by-id id\]"
                 dynamic-cljs)
        "parametric queries remain direct runtime expressions")
    (is (re-find #"sub-read \"sid1-fixture\" \[:item/by-id 1\]" jvm-text)
        "the JVM structural host receives the same compiler site id")
    (is (str/includes? literal-cljs ":expr-path")
        "source/structural diagnostics remain available in the dev manifest")
    (is (= 1 (count (re-seq
                     #"sub-read \"sid1-fixture\" literal-site\$query\$0"
                     literal-cljs)))
        "the hot call itself carries only scalar sid + query")))

(deftest quoted-runtime-sub-lookalikes-never-hoist
  (let [runtime 're-frame.ui.reactive/sub-read
        payload {:marker ::quoted-lookalikes
                 :list   (list runtime :fake/list [:q/list])
                 :vector [(list runtime :fake/vector [:q/vector])]
                 :map    {(list runtime :fake/key [:q/key])
                          (list runtime :fake/value [:q/value])}
                 :set    #{(list runtime :fake/set [:q/set])}}
        quoted  (list 'quote payload)
        forms   (emit-cljs/emit-defview
                 {:vname 'quoted-lookalikes
                  :view-id :app.probe/quoted-lookalikes
                  :display-name "app.probe/quoted-lookalikes"
                  :docstring nil
                  :header (header/parse-header [])
                  :slots []
                  :ast {:op :expr :form (list 'str quoted)}
                  :manifest {:view-id :app.probe/quoted-lookalikes
                             :hook-signature "hs1-fixed"
                             :sites {:subs []}}
                  :closed-keys nil
                  :children? false})
        emitted-quote
        (some (fn [x]
                (when (and (seq? x)
                           (= 'quote (first x))
                           (= ::quoted-lookalikes (:marker (second x))))
                  x))
              (tree-seq coll? seq forms))]
    (is (= quoted emitted-quote)
        "lists, vectors, map keys/values, and sets beneath quote round-trip unchanged")
    (is (not (str/includes? (pr-str forms) "quoted-lookalikes$query$"))
        "a quoted internal-looking form never synthesizes a module query def")))

(deftest file-pass-registration-does-not-embed-a-per-view-digest
  (build/begin-build! ::file '#{app.file})
  (try
    (let [forms (binding [build/*build-id* ::file]
                  (compiler/defview* '(defview file-probe [] [:div "x"])
                                     {:ns {:name 'app.file}}
                                     'file-probe
                                     '([] [:div "x"])))
          text (pr-str forms)]
      (is (str/includes? text "re-frame.ui.runtime/register-view!"))
      (is (not (str/includes? text "bd1-"))
          "view registrations bake no build-identity scalar into the emitted output"))
    (finally
      (build/abort-build! ::file))))

