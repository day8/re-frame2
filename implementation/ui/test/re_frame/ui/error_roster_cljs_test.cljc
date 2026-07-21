(ns re-frame.ui.error-roster-cljs-test
  "THE frozen S1e compile-error roster (rf2-vxgfnd.5; Spec 004 rewrite
  §Template grammar 'compile-error roster with didactic messages').

  One grammar-driven table per tier asserts, for EVERY rejected form:

    1. the STABLE id — `{:rf.ui.compile/error <id>}` ex-data (S1b pinned
       the ids; this roster freezes the full set: renames/additions/
       removals are roster changes and must edit `frozen-error-roster`);
    2. the DIDACTIC message — each message NAMES THE ESCAPE (the correct
       spelling the author should use), pinned by substring (stable in
       meaning, not bytes);
    3. file:line anchoring — errors thrown through the `defview` /
       `custom-element` expansion path carry :file/:line ex-data
       (JVM-asserted; the macro JVM expands for both hosts).

  CLASSIFICATION (the S1e compile-vs-runtime split): every
  `:rf.ui.compile/*` id is a COMPILE diagnostic — thrown at
  macroexpansion, never emitted at runtime, never a trace — so NONE
  needs a Spec 009 catalogue row (Conventions `:rf.ui.compile/*`
  reservation). The runtime tier is the `:rf.error/ui-*` family +
  `:rf.error/jvm-host-op`, all seven catalogued by the S1b slice."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ui.compiler.analyze :as ana]
            [re-frame.ui.compiler.env :as env]
            [re-frame.ui.compiler.header :as header]
            #?@(:clj [[re-frame.ui :as ui]
                      [re-frame.ui.compiler.root :as root]
                      [re-frame.ui.test]])))

;; ---------------------------------------------------------------------------
;; The frozen roster
;; ---------------------------------------------------------------------------

(def frozen-error-roster
  "Every stable compile-error id. FROZEN: a diff to this set is a
  contract change to the S1e roster, not a refactor."
  #{;; heads + children
    :rf.ui.compile/dynamic-head
    :rf.ui.compile/unresolved-head
    :rf.ui.compile/bad-tag
    :rf.ui.compile/duplicate-id-sugar
    :rf.ui.compile/keyword-child
    :rf.ui.compile/markup-returning-map
    :rf.ui.compile/lazy-seq-child
    :rf.ui.compile/unsupported-form
    ;; keyed lists + finite sites
    :rf.ui.compile/unkeyed-list-item
    :rf.ui.compile/constant-list-key
    :rf.ui.compile/nested-for-body
    :rf.ui.compile/bad-for
    :rf.ui.compile/sub-in-loop
    :rf.ui.compile/frame-in-loop
    ;; host hooks (S3, rf2-vxgfnd.95.2) — local / effect placement + effect grammar
    :rf.ui.compile/hook-misplaced
    :rf.ui.compile/bad-effect
    ;; handlers
    :rf.ui.compile/loop-capturing-handler
    :rf.ui.compile/bad-event-vector
    :rf.ui.compile/bad-handler-options
    :rf.ui.compile/contradictory-handler-options
    :rf.ui.compile/bad-ui-event
    ;; explicit callback boundaries + interop recovery/boundary (S3, rf2-vxgfnd.95.3)
    :rf.ui.compile/bad-ui-handler
    :rf.ui.compile/bad-ui-callback
    :rf.ui.compile/bad-error-boundary
    :rf.ui.compile/bad-client-only
    :rf.ui.compile/capability-in-fallback
    ;; presence — declarative enter/exit retention (S4, rf2-uckeg)
    :rf.ui.compile/bad-presence
    :rf.ui.compile/presence-unkeyed-child
    ;; compiled render slots (S3, rf2-ri0k6n)
    :rf.ui.compile/bad-render-fn
    :rf.ui.compile/bad-slot
    :rf.ui.compile/render-fn-misplaced
    :rf.ui.compile/impure-slot-body
    ;; host-exact slot ↔ render-fn arity (rf2-ckviw)
    :rf.ui.compile/slot-arity
    ;; props
    :rf.ui.compile/bad-class
    :rf.ui.compile/bad-style
    :rf.ui.compile/rejected-prop-spelling
    :rf.ui.compile/bare-fn-prop
    :rf.ui.compile/bare-fn-ref
    :rf.ui.compile/dynamic-props-map
    :rf.ui.compile/bad-spread
    ;; ui/spread admitted at a FOREIGN component call site; rejected at an
    ;; internal view (literal props required) (rf2-u53yy.5)
    :rf.ui.compile/spread-internal-view
    ;; literal safe-spread policy (S3, rf2-isdqjv)
    :rf.ui.compile/bad-spread-safe
    :rf.ui.compile/spread-safe-owned-key
    :rf.ui.compile/non-keyword-prop
    :rf.ui.compile/id-sugar-conflict
    :rf.ui.compile/collection-attr-value
    ;; structure
    :rf.ui.compile/void-children
    :rf.ui.compile/html-not-sole-child
    ;; static special-element child shapes the target rejects (rf2-ib4fd):
    ;; (ui/html …) under <textarea>, a textarea's single-text-child contract
    ;; (multiple / value-plus-child / structural), and a multi/structural body
    ;; under a raw-text <script>/<style>
    :rf.ui.compile/html-in-textarea
    :rf.ui.compile/textarea-children
    :rf.ui.compile/raw-text-children
    :rf.ui.compile/bad-html
    :rf.ui.compile/bad-raw
    :rf.ui.compile/raw-fn-child
    :rf.ui.compile/children-prop
    :rf.ui.compile/children-not-accepted
    :rf.ui.compile/undeclared-prop
    :rf.ui.compile/bad-fragment-props
    ;; control forms
    :rf.ui.compile/multi-form-body
    :rf.ui.compile/bad-cond
    :rf.ui.compile/bad-let
    :rf.ui.compile/bad-if
    ;; declaration grammar (defview / custom-element / header)
    :rf.ui.compile/bad-defview-args
    :rf.ui.compile/positional-args
    :rf.ui.compile/key-prop-declared
    :rf.ui.compile/unknown-option
    :rf.ui.compile/bad-view-id
    :rf.ui.compile/bad-custom-element
    ;; the ONE cross-source custom-element declaration law (rf2-vxgfnd.143)
    :rf.ui.compile/custom-element-conflict
    ;; root identity + mount surface (S1c, rf2-vxgfnd.3 — folded into
    ;; the frozen set by the S1f sweep, rf2-vxgfnd.6)
    :rf.ui.compile/bad-root-id
    :rf.ui.compile/bad-disambiguator
    :rf.ui.compile/bad-root-opts
    :rf.ui.compile/no-single-mounted-view
    :rf.ui.compile/runtime-root-form
    :rf.ui.compile/frame-root-misplaced
    :rf.ui.compile/bad-frame-root
    :rf.ui.compile/bad-frame-provider
    :rf.ui.compile/client-entry-on-jvm
    :rf.ui.compile/missing-root-id
    :rf.ui.compile/identity-opts-at-hydrate
    ;; ui.test surface (S1d, rf2-vxgfnd.4 — same fold)
    :rf.ui.compile/bad-test-render-form
    :rf.ui.compile/bad-test-root
    :rf.ui.compile/ui-test-jvm-only
    ;; render-static surface (S5, rf2-oo5lb) — the pure :server static-HTML render
    ;; macro rejects a CLJS expansion (no structural trees in the browser; a CLJS
    ;; expansion would emit the JVM tree + SSR serialiser into a browser bundle)
    :rf.ui.compile/ui-render-static-jvm-only
    ;; render-static no-silent-elision proof (S5, rf2-uv7n6) — a runtime-requiring
    ;; capability anywhere in the static root's server-reachable view closure is a
    ;; loud build error, never a silently dropped capability (EP-0034 §2)
    :rf.ui.compile/static-root-requires-runtime
    ;; render-static UNPROVEN dependency (S5, rf2-uv7n6 hole 2) — a referenced view
    ;; whose static facts are absent from BOTH the ambient index and its registered
    ;; manifest is a loud build error; unknown facts are not proof of static safety
    :rf.ui.compile/static-root-unproven-dependency
    ;; a11y-diagnostic suppression grammar (S4-C, rf2-74vlo) — a malformed
    ;; ^{:rf.ui/suppress {<id> "reason"}} is loud, never a silent no-op
    :rf.ui.compile/bad-suppress})

(def frozen-warning-roster
  "Dev WARNINGS (env/warn!, never thrown) — same namespace, same freeze.
  Warning-tier ids carry NO Spec 009 catalogue row for the same reason the
  error tier does not: detection is at macroexpansion, and nothing is ever
  emitted at runtime (Spec 004 §Compile-tier warnings)."
  #{;; template-shape warnings (S1)
    :rf.ui.compile/placeholder-not-top-level
    :rf.ui.compile/bare-fn-in-loop
    :rf.ui.compile/controlled-input-async-handler
    ;; high-confidence a11y diagnostics (S4-C, rf2-74vlo)
    :rf.ui.compile/a11y-missing-accessible-name
    :rf.ui.compile/a11y-invalid-literal-aria
    :rf.ui.compile/a11y-click-non-interactive
    :rf.ui.compile/a11y-presence-exit-interactive})

(def ^:private jvm-tier-ids
  "Ids thrown only inside the JVM-side expansion pipeline
  (`defview*` / `custom-element*` / the mount-surface + ui.test macro
  bodies — the macro JVM expands for BOTH hosts, so these hold for the
  CLJS path too)."
  #{:rf.ui.compile/unknown-option
    :rf.ui.compile/bad-view-id
    :rf.ui.compile/bad-custom-element
    ;; S1c/S1d ids exercised by this file's JVM tier (below)
    :rf.ui.compile/missing-root-id
    :rf.ui.compile/identity-opts-at-hydrate
    :rf.ui.compile/ui-test-jvm-only})

(def ^:private owning-suite-ids
  "S1c/S1d ids frozen HERE but exercised in their OWNING suites (the
  roster stays the single frozen set; the rejection fixtures live with
  the surface that throws them):

    root_analysis_cljs_test  bad-root-id / bad-disambiguator /
                             bad-root-opts / no-single-mounted-view /
                             runtime-root-form / frame-root-misplaced /
                             bad-frame-root
    root_mount_jvm_test      client-entry-on-jvm
    test_render_jvm_test     bad-test-render-form / bad-test-root
    render_static_jvm_test   ui-render-static-jvm-only (re-frame.ssr artefact —
                             render-static is a JVM/server surface, so its CLJS-
                             expansion rejection is exercised where it renders) +
                             static-root-requires-runtime (the no-silent-elision
                             proof needs the build view-static index populated —
                             not reachable by macroexpanding a single form) +
                             static-root-unproven-dependency (an unobtainable-facts
                             dependency, exercised there via a controlled index +
                             registry gap)
    custom_element_conflict_jvm_test
                             custom-element-conflict (needs TWO declaring
                             sources in one build — not reachable by
                             macroexpanding a single form)
    local_effect_dispatch_fn_jvm_test
                             bad-effect (a malformed effect deps arg is only
                             reachable in a hooks region — the full defview
                             expansion path, rf2-vxgfnd.95.2)"
  #{:rf.ui.compile/custom-element-conflict
    :rf.ui.compile/bad-effect
    :rf.ui.compile/bad-root-id
    :rf.ui.compile/bad-disambiguator
    :rf.ui.compile/bad-root-opts
    :rf.ui.compile/no-single-mounted-view
    :rf.ui.compile/runtime-root-form
    :rf.ui.compile/frame-root-misplaced
    :rf.ui.compile/bad-frame-root
    :rf.ui.compile/client-entry-on-jvm
    :rf.ui.compile/bad-test-render-form
    :rf.ui.compile/bad-test-root
    :rf.ui.compile/ui-render-static-jvm-only
    :rf.ui.compile/static-root-requires-runtime
    :rf.ui.compile/static-root-unproven-dependency})

(def ^:private direct-call-ids
  "Ids exercised by a direct analyzer-fn call below (defensive sites the
  hiccup structural pins make unreachable through `analyze` itself)."
  #{:rf.ui.compile/dynamic-props-map})

;; ---------------------------------------------------------------------------
;; Injected environment (pure analyzer — identical on both hosts)
;; ---------------------------------------------------------------------------

(def ^:private core-heads
  (into #{} (map (comp symbol name))
        (concat ana/markup-map-fqns ana/lazy-seq-fqns)))

(defn- resolver [sym]
  (if (contains? core-heads sym)
    {:fqn (symbol "clojure.core" (name sym)) :meta {}}
    (case sym
      sub         {:fqn 're-frame.ui/sub :meta {}}
      frame       {:fqn 're-frame.ui/frame :meta {}}
      local       {:fqn 're-frame.ui/local :meta {}}
      effect      {:fqn 're-frame.ui/effect :meta {}}
      dispatch-fn {:fqn 're-frame.ui/dispatch-fn :meta {}}
      raw         {:fqn 're-frame.ui/raw :meta {}}
      html        {:fqn 're-frame.ui/html :meta {}}
      raw-fn      {:fqn 're-frame.ui/raw-fn :meta {}}
      spread      {:fqn 're-frame.ui/spread :meta {}}
      spread-safe {:fqn 're-frame.ui/spread-safe :meta {}}
      event       {:fqn 're-frame.ui/event :meta {}}
      handler     {:fqn 're-frame.ui/handler :meta {}}
      render-fn   {:fqn 're-frame.ui/render-fn :meta {}}
      slot        {:fqn 're-frame.ui/slot :meta {}}
      error-boundary {:fqn 're-frame.ui/error-boundary :meta {}}
      client-only    {:fqn 're-frame.ui/client-only :meta {}}
      presence       {:fqn 're-frame.ui/presence :meta {}}
      frame-provider {:fqn 're-frame.ui/frame-provider :meta {}}
      child-view  {:fqn 'app.views/child-view
                   :meta {:rf.ui/view true :rf.ui/children? true}}
      leaf-view   {:fqn 'app.views/leaf-view
                   :meta {:rf.ui/view true :rf.ui/children? false}}
      closed-view {:fqn 'app.views/closed-view
                   :meta {:rf.ui/view true :rf.ui/closed-prop-keys [:a :b]}}
      ForeignComp {:fqn 'app.interop/ForeignComp :meta {}}
      nil)))

(defn- mk-env []
  (-> (env/make-env {:host :clj :ns-sym 'app.roster
                     :self 'self-view :self-id :app.roster/self-view
                     :resolver resolver})
      (assoc :self-children? false :self-closed-keys nil)))

(defn- reject
  "Analyze `form`; -> the thrown compile-error ExceptionInfo, nil when
  accepted."
  [form]
  (try
    (ana/analyze (mk-env) form)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) ex
      ex)))

(defn- assert-row!
  "One roster row: `form` throws `id`, and the message contains every
  `names` fragment (the escape the author should use)."
  [ex id form names]
  (is (some? ex) (str (pr-str form) " must be rejected [" id "]"))
  (when ex
    (is (= id (:rf.ui.compile/error (ex-data ex)))
        (str (pr-str form) " carries its frozen roster id"))
    (doseq [n names]
      (is (str/includes? (ex-message ex) n)
          (str id " must name the escape " (pr-str n)
               " — got: " (ex-message ex))))))

;; ---------------------------------------------------------------------------
;; Analyzer tier — template grammar
;; ---------------------------------------------------------------------------

(def analyzer-roster
  "[id rejected-form [escape-naming fragments]] — the template-grammar
  rejection table. Every distinct throw SITE has a row."
  [;; heads
   [:rf.ui.compile/dynamic-head '[(if x :div :span) "y"] ["ui/raw"]]
   [:rf.ui.compile/dynamic-head '(let [h child-view] [h {}]) ["ui/raw"]]
   [:rf.ui.compile/unresolved-head '[nope-not-a-thing {}] ["declare ^:rf.ui/view"]]
   [:rf.ui.compile/bad-tag [:ns/div "x"] ["write :div"]]
   [:rf.ui.compile/bad-tag [:.card "x"] [":div.card"]]
   [:rf.ui.compile/duplicate-id-sugar [:div#a#b "x"] ["one #id"]]
   ;; children position
   [:rf.ui.compile/keyword-child [:div :oops] ["\"oops\""]]
   [:rf.ui.compile/markup-returning-map '[:ul (map render-item items)] ["(for ["]]
   [:rf.ui.compile/markup-returning-map '[:ul (mapcat render-rows groups)] ["(for ["]]
   [:rf.ui.compile/lazy-seq-child '[:ul (filter visible? items)] ["(for [" "str/join"]]
   [:rf.ui.compile/unsupported-form {:not "renderable"} ["(str"]]
   ;; keyed lists + finite sites
   [:rf.ui.compile/unkeyed-list-item '(for [x xs] [:li x]) [":key"]]
   [:rf.ui.compile/unkeyed-list-item '(for [x xs] (str x)) ["keyed element"]]
   [:rf.ui.compile/constant-list-key '(for [x xs] [:li {:key 1} x]) ["vary per row"]]
   [:rf.ui.compile/nested-for-body '(for [x xs] (for [y x] [:li {:key y} y])) ["ONE for"]]
   [:rf.ui.compile/bad-for '(for [x xs :unknown y] [:li {:key x} x]) [":let / :when / :while"]]
   [:rf.ui.compile/bad-for '(for [] [:li {:key 1} "x"]) ["seq-exprs"]]
   [:rf.ui.compile/sub-in-loop '(for [x xs] [:li {:key x} (sub [:q x])]) ["keyed child view"]]
   [:rf.ui.compile/frame-in-loop
    '(for [x xs] [:li {:key x} (:frame (frame))])
    ["Hoist the read into the view body"]]
   [:rf.ui.compile/frame-in-loop
    '[:button {:on-click (fn [_] (do-send! (:dispatch (frame))))} "x"]
    ["finite render-time site"]]
   ;; host hooks (S3, rf2-vxgfnd.95.2): a `local` outside the unconditional
   ;; hooks region (here, a prop value) and an `effect` used as an expression.
   [:rf.ui.compile/hook-misplaced '[:div {:title (local 1)}] ["host hook" "top region"]]
   [:rf.ui.compile/hook-misplaced
    '[:div {:title (effect [x] (f x))}] ["host-effect STATEMENT"]]
   ;; rf2-vxgfnd.252 — a bare reactive authoring var escaping into computed
   ;; callee / value flow (distinct throw site: the leaf value-flow guard).
   [:rf.ui.compile/unsupported-form
    '[:div {:title ((if p sub inc) [:q])}]
    ["direct call head" "defview"]]
   ;; rf2-dzyqis — a bare reactive authoring var used as a destructuring :or
   ;; DEFAULT (distinct throw site: reject-reactive-binding!'s :or-default guard).
   [:rf.ui.compile/unsupported-form
    '[:div {:title (let [{:keys [x] :or {x sub}} m] x)}]
    ["destructuring :or" "direct call head"]]
   ;; rf2-vxgfnd.266 — a reactive authoring verb in Hiccup component-head
   ;; position (distinct throw site: the reserved-head guard, ahead of
   ;; env/classify-head's :foreign classification). Recovery is kind-correct:
   ;; (sub query) and (frame).
   [:rf.ui.compile/unsupported-form '[sub {}] ["component-head" "(sub query)"]]
   [:rf.ui.compile/unsupported-form '[frame] ["component-head" "(frame)"]]
   ;; handlers
   [:rf.ui.compile/loop-capturing-handler
    '(for [t ts] [:li {:key (:id t) :on-click [::open (:id t)]} "x"])
    ["keyed child view" "as a prop"]]
   [:rf.ui.compile/bad-event-vector '[:button {:on-click [event-sym 1]} "x"] ["[:domain/event"]]
   [:rf.ui.compile/bad-handler-options
    '[:button {:on-click {:event [:a/b] :bubble true}} "x"] [":stop-propagation"]]
   [:rf.ui.compile/bad-handler-options
    '[:button {:on-click {:prevent-default true}} "x"] [":event [:domain/event"]]
   [:rf.ui.compile/contradictory-handler-options
    '[:button {:on-click {:event [:a/b] :passive true :prevent-default true}} "x"]
    ["preventDefault" "Drop one"]]
   [:rf.ui.compile/bad-ui-event
    '[:input {:on-input (event [a b] [:x])}]
    ["native event" "event vector" "ui/handler"]]
   ;; explicit callback boundaries + interop recovery/boundary (S3, rf2-vxgfnd.95.3)
   [:rf.ui.compile/bad-ui-handler
    '[:button {:on-click (handler [a b] (f a b))} "x"]
    ["native event" "ui/event"]]
   [:rf.ui.compile/bad-ui-callback
    '[ForeignComp {:on-select (event [a b] [:x])}]
    ["event vector"]]
   [:rf.ui.compile/bad-error-boundary
    '(error-boundary {:fallback ForeignComp} [:p "x"])
    ["defview" ":error"]]
   [:rf.ui.compile/bad-client-only
    '(client-only {} [:div "live"])
    [":fallback" "capability-free"]]
   [:rf.ui.compile/capability-in-fallback
    '(client-only {:fallback [:p (sub [:q])]} [:div "live"])
    ["CAPABILITY-FREE" "client subtree"]]
   ;; presence — declarative enter/exit retention (S4, rf2-uckeg)
   [:rf.ui.compile/bad-presence
    '(presence [:li {:key 1} "x"])
    ["literal opts map" ":timeout-ms"]]
   [:rf.ui.compile/bad-presence
    '(presence {:timeout-ms 300 :easing :ease} (for [x xs] [:li {:key x} x]))
    ["unknown presence option" ":timeout-ms"]]
   [:rf.ui.compile/bad-presence
    '(presence {} (for [x xs] [:li {:key x} x]))
    [":timeout-ms is MANDATORY" "safety bound"]]
   [:rf.ui.compile/bad-presence
    '(presence {:timeout-ms 0} (for [x xs] [:li {:key x} x]))
    ["positive number of milliseconds"]]
   [:rf.ui.compile/presence-unkeyed-child
    '(presence {:timeout-ms 300} [:li "x"])
    ["KEYED" "build failure"]]
   [:rf.ui.compile/presence-unkeyed-child
    '(presence {:timeout-ms 300} [child-view {:toast 1}])
    ["KEYED"]]
   ;; compiled render slots (S3, rf2-ri0k6n)
   [:rf.ui.compile/bad-render-fn
    '[child-view {:row (render-fn [a] [:p "a"] [:p "b"])}]
    ["ONE template" "(let"]]
   [:rf.ui.compile/bad-render-fn
    '[child-view {:row (render-fn xs [:p "a"])}]
    ["parameter binding vector"]]
   [:rf.ui.compile/bad-render-fn
    '[child-view {:row (render-fn [& xs] [:p "a"])}]
    ["FIXED arg list"]]
   [:rf.ui.compile/bad-slot '(slot) ["render-fn value"]]
   [:rf.ui.compile/bad-slot '[:div {:title (slot r 1)}] ["child position"]]
   ;; host-exact slot ↔ render-fn arity (rf2-ckviw): an INLINE render-fn's
   ;; parameter count must match the slot's argument count — too-few and too-many
   ;; are host-independent compile errors (JS silently drops surplus args; the
   ;; JVM throws ArityException — the compiler owns ONE contract instead).
   [:rf.ui.compile/slot-arity
    '(slot (render-fn [a b] [:p a b]) x)
    ["FIXED-arity" "declares 2 parameter"]]
   [:rf.ui.compile/slot-arity
    '(slot (render-fn [a] [:p a]) x y)
    ["FIXED-arity" "Drop the surplus"]]
   [:rf.ui.compile/render-fn-misplaced
    '(render-fn [a] [:p a]) ["ui/slot" "prop value"]]
   [:rf.ui.compile/render-fn-misplaced
    '[:div {:title (render-fn [] [:p "x"])}] ["ui/slot"]]
   [:rf.ui.compile/impure-slot-body
    '(slot (render-fn [a] [:div (sub [:q])])) ["PURE render" "MOUNTS a defview"]]
   [:rf.ui.compile/impure-slot-body
    '(slot (render-fn [a] [:div (:frame (frame))])) ["PURE render"]]
   [:rf.ui.compile/impure-slot-body
    '(slot (render-fn [a] [:button {:on-click [:x a]} "b"]))
    ["DISPATCHES" "mount a defview"]]
   [:rf.ui.compile/impure-slot-body
    '(slot (render-fn [a] [:div {:ref r} "x"]))
    ["commit-phase" "Mount a defview"]]
   ;; transitive purity fence (rf2-vtfzn): a COMMITTED-CALLBACK / dispatch surface
   ;; authored inside a render-fn body escapes the fence unless rejected. A
   ;; component-prop ui/event / ui/handler is the correctness-critical one — a
   ;; repeated slot shares ONE lexical callback site, aliasing every row's
   ;; closure to the last row. (The leading (effect …) statement needs a hooks
   ;; region and is exercised in effect-inside-render-fn-is-impure below.)
   [:rf.ui.compile/impure-slot-body
    '(slot (render-fn [row] [child-view {:on-pick (event [e] [:pick (:id row)])}]) item)
    ["DISPATCHES" "alias the last row"]]
   [:rf.ui.compile/impure-slot-body
    '(slot (render-fn [row] [child-view {:on-pick (handler [e] (do-it (:id row)))}]) item)
    ["DISPATCHES" "alias the last row"]]
   [:rf.ui.compile/impure-slot-body
    '(slot (render-fn [a] (error-boundary {:fallback child-view :on-error [:oops a]}
                                          [:p "x"])) item)
    [":on-error" "DISPATCHES"]]
   ;; props
   [:rf.ui.compile/bad-class '[:div {:class {(kw) true}} "x"] ["literal names"]]
   [:rf.ui.compile/bad-style '[:div {:style {(kw) 1}} "x"] ["dynamic expression"]]
   [:rf.ui.compile/rejected-prop-spelling '[:div {:class-name "x"}] [":class"]]
   [:rf.ui.compile/rejected-prop-spelling
    '[:div {:dangerouslySetInnerHTML {:__html "x"}}] ["(ui/html"]]
   [:rf.ui.compile/bare-fn-prop '[:div {:data-cb (fn [x] x)} "x"] ["ui/raw-fn"]]
   [:rf.ui.compile/bare-fn-prop '[ForeignComp {:on-select (fn [x] x)}] ["ui/raw-fn"]]
   [:rf.ui.compile/bare-fn-ref '[:div {:ref (fn [el] el)} "x"] ["(ui/raw-fn f)"]]
   [:rf.ui.compile/bad-spread '[:div (spread)] ["(ui/spread base"]]
   [:rf.ui.compile/bad-spread '(spread base) ["props position"]]
   ;; ui/spread at an INTERNAL view call site — rejected (literal props
   ;; required); admitted only at a FOREIGN head (rf2-u53yy.5)
   [:rf.ui.compile/spread-internal-view
    '[child-view (spread {:a 1} m)] ["LITERAL props map" "FOREIGN"]]
   [:rf.ui.compile/spread-internal-view
    '[child-view (spread m)] ["LITERAL props map" "FOREIGN"]]
   ;; literal safe-spread policy (S3, rf2-isdqjv): malformed form + owned-key deny
   [:rf.ui.compile/bad-spread-safe '[:div (spread-safe dynmap caller)] ["LITERAL"]]
   [:rf.ui.compile/bad-spread-safe '[:div (spread-safe {})] ["owned caller"]]
   [:rf.ui.compile/bad-spread-safe '(spread-safe {} caller) ["props position"]]
   [:rf.ui.compile/spread-safe-owned-key
    '[:input (spread-safe {:value v :on-change [:x]} {:value 5})] ["ui/spread base"]]
   [:rf.ui.compile/spread-safe-owned-key
    '[:input (spread-safe {:on-change [:x]} {:on-change [:evil]})] ["denied in every build"]]
   [:rf.ui.compile/spread-safe-owned-key
    '[:div (spread-safe {} {:ref r})] ["denied in every build"]]
   [:rf.ui.compile/non-keyword-prop '[:div {"str-key" 1} "x"] ["literal keywords"]]
   [:rf.ui.compile/non-keyword-prop '[child-view {"k" 1}] ["literal keywords"]]
   [:rf.ui.compile/id-sugar-conflict '[:div#a {:id "b"}] ["Keep one"]]
   [:rf.ui.compile/collection-attr-value '[:div {:data-foo {:a 1}} "x"] ["str/join"]]
   ;; structure + interop positions
   [:rf.ui.compile/void-children [:br "child"] ["cannot have children"]]
   [:rf.ui.compile/html-not-sole-child '[:div [:span "s"] (html "<b>x</b>")] ["[:div (ui/html s)]"]]
   [:rf.ui.compile/html-not-sole-child '(html "<b>x</b>") ["[:div (ui/html s)]"]]
   [:rf.ui.compile/bad-html '[:div (html "a" "b")] ["exactly one argument"]]
   [:rf.ui.compile/bad-html '[:div (html 42)] ["requires a string"]]
   ;; static special-element child shapes the target rejects (rf2-ib4fd)
   [:rf.ui.compile/html-in-textarea '[:textarea (html "<b>x</b>")] [":value"]]
   ;; the textarea single-text-child contract — one row per throw site
   ;; (value-plus-child / multiple children / structural child)
   [:rf.ui.compile/textarea-children '[:textarea {:value "v"} "c"] [":value"]]
   [:rf.ui.compile/textarea-children '[:textarea "a" "b"] [":value"]]
   [:rf.ui.compile/textarea-children '[:textarea [:span "x"]] [":value"]]
   [:rf.ui.compile/raw-text-children '[:script "a" "b"] ["(str" "ui/html"]]
   [:rf.ui.compile/raw-text-children '[:style [:span "x"]] ["(str" "ui/html"]]
   [:rf.ui.compile/bad-raw '(raw) ["one argument"]]
   [:rf.ui.compile/raw-fn-child '(raw-fn f) ["prop positions"]]
   [:rf.ui.compile/children-prop '[child-view {:children [x]}] ["positional"]]
   [:rf.ui.compile/children-not-accepted '[leaf-view {} [:p "kid"]] [":children"]]
   [:rf.ui.compile/undeclared-prop '[closed-view {:a 1 :c 3}] ["declared:"]]
   [:rf.ui.compile/bad-fragment-props '[:<> {:key k :class "x"} [:p "a"]] ["{:key"]]
   ;; frame-provider (S2c) — the SCOPE form's rejection roster
   [:rf.ui.compile/bad-frame-provider '[frame-provider "x"] ["literal props map"]]
   [:rf.ui.compile/bad-frame-provider '[frame-provider {:id :x} [:p "y"]] ["frame-root {:id"]]
   [:rf.ui.compile/bad-frame-provider '[frame-provider {} [:p "y"]] ["requires :frame"]]
   [:rf.ui.compile/bad-frame-provider
    '[frame-provider {:frame :y :extra 1} [:p "z"]] ["only scope"]]
   ;; control forms
   [:rf.ui.compile/multi-form-body '(when c [:p "a"] [:p "b"]) ["[:<>"]]
   [:rf.ui.compile/bad-cond '(cond a) [":else"]]
   [:rf.ui.compile/bad-let '(let [x] [:p "a"]) ["even bindings"]]
   [:rf.ui.compile/bad-let '(letfn (f) [:p "a"]) ["fnspecs vector"]]
   [:rf.ui.compile/bad-if '(if a [:p "a"] [:p "b"] [:p "c"]) ["test then else"]]
   ;; a11y suppression grammar (S4-C, rf2-74vlo) — an unknown id, a blank
   ;; reason, and a non-map payload all reject; a suppression that silently
   ;; stopped suppressing would be the worst failure mode of the mechanism.
   ;; (The full grammar table lives in a11y_diagnostics_cljs_test.)
   [:rf.ui.compile/bad-suppress
    '^{:rf.ui/suppress {:rf.ui.compile/a11y-nope "reason"}}
    [:div {:on-click [:x/y]} "z"]
    ["a11y-click-non-interactive"]]])

(deftest analyzer-tier-roster
  (doseq [[id form names] analyzer-roster]
    (testing (str id " <- " (pr-str form))
      (assert-row! (reject form) id form names))))

(deftest effect-inside-render-fn-is-impure
  ;; rf2-vtfzn — the primary escape: a render-fn slot body INHERITS the enclosing
  ;; hooks region, so a leading (effect …) in a top-region let inside the body was
  ;; accepted and emitted as a React lifecycle hook INSIDE the deferred callback.
  ;; This needs a hooks-region env (a slot authored in a defview's top region), so
  ;; it cannot ride the mk-env roster table above.
  (let [e (assoc (mk-env) :hooks-region? true :top-region? true)
        ex (try (ana/analyze e '(slot (render-fn [a]
                                        (let [x 1] (effect [] (side-effect! x a)) [:div]))
                                      item))
                nil
                (catch #?(:clj clojure.lang.ExceptionInfo
                          :cljs cljs.core/ExceptionInfo) ex ex))]
    (assert-row! ex :rf.ui.compile/impure-slot-body
                 '(slot (render-fn [a] (let [x 1] (effect [] (side-effect! x a)) [:div])) item)
                 ["lifecycle hook" "MOUNT a defview"]))
  (testing "mutation control — the SAME effect in a real defview top region is LEGAL"
    ;; the fence must fire ONLY inside :in-render-fn?, not on a legitimate
    ;; top-region effect: a leading (effect …) in a genuine hooks region (no
    ;; render-fn wrapper) still lowers to a :hook-prefix, so the fence is not
    ;; over-firing on ordinary top-region effects.
    (let [e   (assoc (mk-env) :hooks-region? true :top-region? true)
          ast (ana/analyze e '(let [x 1] (effect [] (side-effect! x)) [:div "ok"]))]
      (is (= :hook-prefix (get-in ast [:body :op]))
          "a leading (effect …) in a genuine defview top region stays legal"))))

(deftest dynamic-props-map-diagnostic
  ;; hiccup structure makes a non-map/non-spread props position a CHILD,
  ;; so the guard is only reachable by direct call — it stays didactic.
  (let [ex (try
             (ana/analyze-element-props
              (mk-env) {:tag :div :id nil :classes []} false 'props-expr)
             nil
             (catch #?(:clj clojure.lang.ExceptionInfo
                       :cljs cljs.core/ExceptionInfo) ex ex))]
    (assert-row! ex :rf.ui.compile/dynamic-props-map 'props-expr ["ui/spread"])))

(deftest lazy-seq-vocabulary-sweep
  ;; EVERY member of the closed raw-lazy-seq vocabulary rejects in child
  ;; position, naming both escapes (keyed for-rows; str/join text).
  (doseq [head (into (sorted-set) (map (comp symbol name)) ana/lazy-seq-fqns)]
    (let [form [:ul (list head 'xs)]]
      (testing (str "lazy-seq-child <- " (pr-str form))
        (assert-row! (reject form) :rf.ui.compile/lazy-seq-child form
                     [(str "(" head) "(for [" "str/join"])))))

(deftest markup-map-vocabulary-sweep
  (doseq [head (into (sorted-set) (map (comp symbol name)) ana/markup-map-fqns)]
    (let [form [:ul (list head 'f 'xs)]]
      (testing (str "markup-returning-map <- " (pr-str form))
        (assert-row! (reject form) :rf.ui.compile/markup-returning-map form
                     ["(for ["])))))

(deftest lazy-heads-stay-legal-in-opaque-positions
  ;; only RENDERED CONTENT rejects raw seqs — expression positions are
  ;; opaque (prop values, for-collections, if-tests).
  (is (nil? (reject '[:div {:data-n (filter visible? items)} "x"]))
      "prop values are opaque expressions")
  (is (nil? (reject '(for [x (filter visible? items)] [:li {:key x} x])))
      "the for collection is an opaque expression")
  (is (nil? (reject '(if (seq items) [:p "some"] [:p "none"])))
      "test positions are opaque expressions"))

;; ---------------------------------------------------------------------------
;; Warning tier
;; ---------------------------------------------------------------------------

(defn- warnings-for [form]
  (let [e (mk-env)]
    (ana/analyze e form)
    @(:warnings e)))

(def ^:private a11y-suite-warning-ids
  "The S4-C a11y ids, frozen HERE but exercised — in BOTH the firing and the
  silent direction, which is the load-bearing half — by their owning suite
  `re-frame.ui.a11y-diagnostics-cljs-test`."
  #{:rf.ui.compile/a11y-missing-accessible-name
    :rf.ui.compile/a11y-invalid-literal-aria
    :rf.ui.compile/a11y-click-non-interactive
    :rf.ui.compile/a11y-presence-exit-interactive})

(def warning-roster
  "[id warning-producing-form [message fragments]] — the dev-warning table."
  ;; NB each row isolates ONE warning: the click handlers sit on `:button`
  ;; rather than a generic element so the S4-C a11y roster stays silent and
  ;; the assertion below can demand an exact singleton.
  [[:rf.ui.compile/bare-fn-in-loop
    '(for [x xs] [:button {:key x :on-click (fn [] x)} "y"])
    ["event vector or a keyed child view"]]
   [:rf.ui.compile/placeholder-not-top-level
    '[:button {:on-click [:a/b {:v :rf.ui/value}]} "x"]
    ["top-level"]]
   [:rf.ui.compile/controlled-input-async-handler
    '[:input {:value v :on-change some-callback}]
    ["literal event vector" "ui/event"]]])

(deftest warning-tier-roster
  (doseq [[id form fragments] warning-roster]
    (testing (str id " <- " (pr-str form))
      (let [[w :as ws] (warnings-for form)]
        (is (= [id] (mapv :id ws)) (str (pr-str form) " warns exactly [" id "]"))
        (doseq [f fragments]
          (is (and w (str/includes? (:msg w) f))
              (str id " must name " (pr-str f) " — got: " (:msg w))))))))

(deftest warning-roster-is-frozen-and-complete
  (is (= frozen-warning-roster
         (into a11y-suite-warning-ids (map first) warning-roster))
      (str "every frozen WARNING id has an exercised form (here or in its "
           "named owning suite), and no warning is emitted outside the frozen "
           "roster — additions/renames edit frozen-warning-roster deliberately")))

;; ---------------------------------------------------------------------------
;; Header tier — the Q2 surface (host-shared fns; runs on both hosts)
;; ---------------------------------------------------------------------------

(def header-roster
  "[id argv [escape-naming fragments]] — defview header rejections."
  [[:rf.ui.compile/positional-args '[a b] ["one props map"]]
   [:rf.ui.compile/key-prop-declared '[{k :key}] ["call site"]]
   [:rf.ui.compile/bad-defview-args '[{:strs [a]}] [":keys"]]
   [:rf.ui.compile/bad-defview-args '["nope"] ["map-destructuring"]]
   [:rf.ui.compile/bad-defview-args '[{:keys [a] :or [a 1]}] [":or needs a map"]]
   [:rf.ui.compile/bad-defview-args '[{:foo [a]}] ["supported: :keys"]]])

(deftest header-tier-roster
  (doseq [[id argv names] header-roster]
    (testing (str id " <- " (pr-str argv))
      (let [ex (try
                 (header/parse-header argv)
                 nil
                 (catch #?(:clj clojure.lang.ExceptionInfo
                           :cljs cljs.core/ExceptionInfo) ex ex))]
        (assert-row! ex id argv names)))))

;; ---------------------------------------------------------------------------
;; Completeness — the freeze
;; ---------------------------------------------------------------------------

(deftest roster-is-frozen-and-complete
  (is (= frozen-error-roster
         (-> #{}
             (into (map first) analyzer-roster)
             (into (map first) header-roster)
             (into direct-call-ids)
             (into jvm-tier-ids)
             (into owning-suite-ids)))
      (str "every frozen id has an exercised rejected form (here or in "
           "its named owning suite), and no id is thrown outside the "
           "frozen roster — additions/renames edit frozen-error-roster "
           "deliberately")))

;; ---------------------------------------------------------------------------
;; JVM pipeline tier — defview / custom-element expansion (the macro JVM
;; expands for both hosts, so these pins hold for the CLJS path too)
;; ---------------------------------------------------------------------------

#?(:clj
   (do

(defn- expand-ex
  "Macroexpand; -> the compile-error ExceptionInfo (unwrapping the
  CompilerException some paths add), nil when it expands."
  [form]
  (try
    (macroexpand-1 form)
    nil
    (catch clojure.lang.ExceptionInfo ex ex)
    (catch Exception ex
      (let [c (.getCause ex)]
        (when (instance? clojure.lang.ExceptionInfo c) c)))))

(def jvm-roster
  "[id declaration-form [escape-naming fragments]]."
  [[:rf.ui.compile/bad-defview-args
    '(re-frame.ui/defview v [:div "no argv"]) ["argument vector is missing"]]
   [:rf.ui.compile/multi-form-body
    '(re-frame.ui/defview v [] [:div "a"] [:div "b"]) ["ONE template form"]]
   [:rf.ui.compile/unknown-option
    '(re-frame.ui/defview v {:memo false} [] [:div]) [":display-name"]]
   [:rf.ui.compile/bad-view-id
    '(re-frame.ui/defview v {:id :unqualified} [] [:div]) ["qualified keyword"]]
   [:rf.ui.compile/key-prop-declared
    '(re-frame.ui/defview v {:props [:map [:key :string]]} [] [:div "x"])
    ["call site"]]
   [:rf.ui.compile/bad-custom-element
    '(re-frame.ui/custom-element :plain {}) ["containing '-'"]]
   [:rf.ui.compile/bad-custom-element
    '(re-frame.ui/custom-element :x-el "nope") ["options map"]]
   [:rf.ui.compile/bad-custom-element
    '(re-frame.ui/custom-element :x-el {:events []}) ["{:properties"]]
   [:rf.ui.compile/bad-custom-element
    '(re-frame.ui/custom-element :x-el {:properties [:a]}) ["literal set"]]])

(deftest jvm-pipeline-roster
  (doseq [[id form names] jvm-roster]
    (testing (str id " <- " (pr-str form))
      (assert-row! (expand-ex form) id form names)))
  (is (every? frozen-error-roster (map first jvm-roster))))

(deftest s1c-s1d-jvm-only-ids
  ;; The three folded ids no other suite can reach: the CLJS-side macro
  ;; bodies invoked directly with a synthetic CLJS menv (identity checks
  ;; fire BEFORE any resolution, so no cljs compiler state is touched),
  ;; and the ui.test/render CLJS-expansion guard.
  (testing "missing-root-id — create-root has no root form to derive from"
    (let [ex (try (root/create-root-form '(ui/create-root n {})
                                         {:ns {:name 'app.roster}} 'n {})
                  nil
                  (catch clojure.lang.ExceptionInfo ex ex))]
      (assert-row! ex :rf.ui.compile/missing-root-id
                   '(ui/create-root n {}) [":root-id" "ui/mount"])))
  (testing "identity-opts-at-hydrate — hydration reads identity FROM the manifest"
    (let [ex (try (root/hydrate-root-form
                   '(ui/hydrate-root n [v {}] {:root-id :a/b})
                   {:ns {:name 'app.roster}} 'n '[v {}] {:root-id :a/b})
                  nil
                  (catch clojure.lang.ExceptionInfo ex ex))]
      (assert-row! ex :rf.ui.compile/identity-opts-at-hydrate
                   '(ui/hydrate-root n [v {}] {:root-id :a/b})
                   ["manifest"])))
  (testing "ui-test-jvm-only — Tier-1 structural renders are JVM-only"
    (let [ex (try ((deref #'re-frame.ui.test/render)
                   '(re-frame.ui.test/render some-view)
                   {:ns {:name 'app.roster}}
                   'some-view
                   nil)
                  nil
                  (catch clojure.lang.ExceptionInfo ex ex))]
      (assert-row! ex :rf.ui.compile/ui-test-jvm-only
                   '(ui.test/render some-view) ["Tier-1" "with-root"]))))

(deftest compile-errors-carry-source-anchor
  ;; S1e file:line anchoring: errors thrown through the expansion path
  ;; carry the declaration form's source coordinates in ex-data (the
  ;; meta &form carries — attached explicitly here so the assertion is
  ;; deterministic across readers).
  (doseq [form ['(re-frame.ui/defview v [a b] [:div a])
                '(re-frame.ui/defview v [] [:ul (map f xs)])
                '(re-frame.ui/custom-element :plain {})]]
    (let [data (ex-data (expand-ex (with-meta form {:line 42 :column 7})))]
      (is (some? (:file data)) (str (pr-str form) " anchors :file"))
      (is (= 42 (:line data)) (str (pr-str form) " anchors :line"))
      (is (= 7 (:column data)) (str (pr-str form) " anchors :column"))
      (is (some? (:rf.ui.compile/error data)) "id survives anchoring"))))

))
