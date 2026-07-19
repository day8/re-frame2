(ns re-frame.ui.binding-plan-advanced-elision-prod-test
  "rf2-4xpah — the ADVANCED-CLJS half of the defview binding-plan proof.

  The two companions stop short of the mode the proof actually needs:

  - `binding_plan_host_faithful_jvm_test` pins that an authored `^js` group-local
    hint survives the canonical plan by INSPECTING the emitted form
    (`(meta (emit-local …)) => {:tag js}`). A form is not a build.
  - `binding_plan_host_faithful_cljs_test` executes REAL `defview`s, but on the
    `:node-test` lane — `:none`, `:infer-externs false`. Nothing is renamed
    there, so a lost `^js` hint is INVISIBLE: both a hinted and an unhinted
    property read work.

  `^js` exists for exactly one reason — to survive `:advanced`. This namespace
  runs ONLY in `:browser-test-prod-elision` (`shadow-cljs release` ⇒
  `:optimizations :advanced`, shadow's default `:infer-externs :auto`,
  goog.DEBUG=false), so the binding plan is finally observed in the mode it is
  written for.

  The causal chain under proof:

    authored `{:keys [^js probe/node]}`
      → `bp/assoc-binding-units` reconstructs the name-stripped group local as
        `(with-meta (symbol nil (name bb)) (meta bb))`
      → the emitted `let` binds `^js node`
      → shadow's externs inference CLAIMS `.bpAdvancedHinted` (it only claims
        reads whose target it can type)
      → Closure leaves that read alone under `:advanced`.

  Drop the `with-meta` and the chain breaks at step 2: the local is bare, no
  extern claims the read, Closure renames it, and the read misses the object's
  string key — which `js-obj` (a `goog.object.create` call, not an object
  literal) makes unrenamable. The fixture below goes RED. Verified by mutation.

  `advanced-build-really-renames-unexterned-property-reads` is the NON-VACUITY
  control and must be read first: it asserts an UNHINTED read of an equivalent
  property IS renamed away in this exact bundle. Without it, a build that had
  quietly stopped renaming would let the hinted fixture pass while proving
  nothing."
  (:require [cljs.test :refer-macros [deftest is testing]]
            ["react-dom/server" :as rds]
            [re-frame.ui :refer [defview]]
            [re-frame.ui.runtime :as rt]))

;; `js-obj` sets its properties by STRING through `goog.object.create`, so these
;; keys are dynamic: Closure can neither rename them nor read them as an object
;; literal. Whether a read below finds its value therefore depends on ONE thing
;; — whether the READ was renamed.
(def ^:private probe
  (js-obj "bpAdvancedHinted"   "kept"
          "bpAdvancedUnhinted" "kept"))

(defn- markup [view props] (rds/renderToStaticMarkup (rt/jsx2 view props)))

;; ---------------------------------------------------------------------------
;; Fixtures — REAL defviews, compiled by the production header lowering into an
;; :advanced release bundle
;; ---------------------------------------------------------------------------

;; The fixture under proof. The group local is QUALIFIED (`probe/node`) as well
;; as `^js`-hinted, so it exercises the name-strip and the metadata carriage in
;; one shape — the host binds `node`, reading slot "probe/node".
;; rf2-kxork — G-11 payload sentinels. This view is REALLY RENDERED below in
;; the `:advanced` bundle, so its docstring and its props-schema `:doc` are
;; carried by a live, rooted view rather than a dead declaration. The G-11
;; scanner (`scripts/check-ui-mounted-prod-elision.cjs`) asserts both literals
;; ABSENT from that bundle: docs projections and schema metadata are dev-only
;; material and must not reach production. Do not rename or delete them without
;; updating that scanner — they are its subjects, not decoration.
(defview js-hint-view
  "rf2-g11-view-docstring-sentinel-v1 — docs-projection egress probe."
  {:props [:map {:doc "rf2-g11-schema-doc-sentinel-v1"}]}
  [{:keys [^js probe/node]}]
  [:span {:data-role "bp-hinted"} (str "[" (.-bpAdvancedHinted node) "]")])

;; The non-vacuity control: the same shape with NO `^js`. Nothing externs
;; `.bpAdvancedUnhinted`, so `:advanced` renames the read.
;;
;; This view is the sole source of the ONE `:infer-warning` this build emits
;; ("Cannot infer target type in expression (. node -bpAdvancedUnhinted)"). It is
;; DELIBERATE and load-bearing — it is the compiler stating, at build time, that
;; it will not extern this read. Do not "fix" it by adding a hint: that silences
;; the warning and destroys the control. The corresponding evidence for the
;; fixture above is the ABSENCE of a second such warning; drop the `with-meta` in
;; `bp/assoc-binding-units` and a second one appears for `.bpAdvancedHinted`.
(defview js-unhinted-control-view
  [{:keys [probe/node]}]
  [:span {:data-role "bp-unhinted"} (str "[" (.-bpAdvancedUnhinted node) "]")])

;; PR #6228's collapse shape, compiled `:advanced`. Slot reads are
;; `(unchecked-get props "ns/x")` — STRING-keyed, so munging cannot touch them —
;; and the host's `let` last-wins still picks `:ns/x` over the dead
;; `[x] <- :other` unit after Closure has renamed everything it may.
(defview nested-shadow-view
  [{[x] :other :keys [ns/x]}]
  [:span {:data-role "bp-nested"} (str "[" x "]")])

;; ---------------------------------------------------------------------------
;; The control — this bundle really is renaming unexterned reads
;; ---------------------------------------------------------------------------

(deftest advanced-build-really-renames-unexterned-property-reads
  (testing "the probe object's string keys are intact (js-obj keys are unrenamable)"
    (is (= "kept" (unchecked-get probe "bpAdvancedUnhinted")))
    (is (= "kept" (unchecked-get probe "bpAdvancedHinted"))))
  (testing "yet an UNHINTED dotted read of one of them finds nothing"
    (is (= "<span data-role=\"bp-unhinted\">[]</span>"
           (markup js-unhinted-control-view (js-obj "probe/node" probe)))
        (str "rf-bp-advanced-renaming-live-v1: :advanced renamed the unexterned "
             "read — so the hinted fixture below is a real proof, not a "
             "build that stopped optimizing"))))

;; ---------------------------------------------------------------------------
;; The proof — the authored ^js reaches the advanced Closure build
;; ---------------------------------------------------------------------------

(deftest authored-js-hint-survives-the-canonical-plan-into-the-advanced-build
  (is (= "<span data-role=\"bp-hinted\">[kept]</span>"
         (markup js-hint-view (js-obj "probe/node" probe)))
      (str "rf-bp-advanced-js-hint-v1: the authored ^js reached the emitted let "
           "binding through bp/assoc-binding-units' with-meta, so shadow "
           "externed the read and Closure left it alone. Drop that with-meta "
           "and this renders [] like the control above.")))

;; ---------------------------------------------------------------------------
;; The #6228 collapse shape holds under advanced too
;; ---------------------------------------------------------------------------

(deftest quoted-slot-reads-and-host-last-wins-survive-advanced
  (testing "present :ns/x wins over the shadowed [x] <- :other read"
    (is (= "<span data-role=\"bp-nested\">[1]</span>"
           (markup nested-shadow-view (js-obj "ns/x" 1 "other" [9])))))
  (testing "absent :ns/x -> nil; the dead :other value never surfaces, minified or not"
    (is (= "<span data-role=\"bp-nested\">[]</span>"
           (markup nested-shadow-view (js-obj "other" [9]))))))
