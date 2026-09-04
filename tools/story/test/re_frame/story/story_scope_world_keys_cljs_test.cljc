(ns re-frame.story.story-scope-world-keys-cljs-test
  "rf2-sc5g0 — a STORY-level `:substrates` / `:component` declaration must
  reach the compiled plan.

  ## The defect this namespace is the witness for

  `rf.story.plan/variant-plan` folded `:substrates` and `:component` from the
  VARIANT body and its `:extends` chain only. It resolved the parent story
  for decorators and tags and for nothing else, so a story that declared
  either key ONCE — the shape `001-Authoring.md` calls the normal one, the
  parent carrying the subject while variants vary by args — never had it
  land on `[:world …]`.

  Both slots have a plan-side reader, which is why that was a live defect
  rather than an untidy plan:

  - `canonical/render-host-scope` takes the substrate off
    `[:world :substrates]` (rf2-3afns) and feeds it to
    `multi-substrate/single-render-substrate`, which answers the `:reagent`
    host default for an absent set. So a story declaring `#{:uix}` or
    `#{:hicasso}` painted correctly on the live canvas — which resolves
    variant-then-story through `multi-substrate/resolve-substrate-set` —
    and rendered under REAGENT through `render-variant`. Exactly the
    disagreement rf2-3afns closed, one level up.
  - `rf.story.render/prepare-render` takes the subject off `[:world :component]`
    with NO fallback of its own, so the same story shape gave
    `render-variant` a NIL view. Measured, not inferred: before the fix
    `(get-in (rf.story.render/prepare-render v) [:render-inputs :view])` answered
    `nil` for a story-level `:component` and the view id for a
    variant-level one.

  ## Why the fix is on the plan and not on the two readers

  rf2-3afns established that the compiled plan is the one thing the canvas
  and `render-variant` both read, so they cannot drift. Teaching each
  reader its own `(or variant story)` walk would have been a third and
  fourth copy of a precedence rule that already exists in four places.

  ## Scope

  `:substrates` and `:component` are the only two `rf.story.plan/context-keys` with
  a `[:world …]` reader anywhere in `tools/story/src` — `:modes`,
  `:viewport`, `:background`, `:xray`, `:platforms` and
  `:dispatch-console?` ride the plan for `:plan-hash` + explain and are
  read off the bodies by the UI, which resolves story scope itself. So
  this is a fix to two slots, not a new inheritance rule for the plan.

  Both arms: `.cljc` with a `-cljs-test` ns, so the JVM runner
  (`clojure -M:test` from `tools/story`) and the shadow `:node-test` build
  (`npm run test:cljs`) each run every row. Every claim here is about
  DATA — the compiled plan and the host-free render-prep — so neither arm
  needs a renderer."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.registrar :as rf.registrar]
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.render :as rf.story.render]))

;; ---- fixture --------------------------------------------------------------
;;
;; Both registrars are wiped: the Story side-table (whose `:story` kind is
;; what the plan's DEFAULT story-lookup reads — the production path, not a
;; threaded test double) and the framework `:view` registrar (which the
;; default view-lookup reads for the props schema).

(use-fixtures :each
  (fn [t]
    (rf.story.registrar/clear-all!)
    (rf.registrar/clear-kind! :view)
    (t)))

(defn- reg-view-meta!
  "Register a `:view` slot carrying `metadata` on the framework registrar —
  the slot the plan compiler's default view-lookup reads."
  [view-id metadata]
  (rf.registrar/register! :view view-id
                                 (assoc metadata :handler-fn (fn [_] nil))))

;; ===========================================================================
;; 1 · :substrates — the bead's exact repro
;; ===========================================================================

(deftest a-story-level-substrate-reaches-the-plan
  (testing "rf2-sc5g0 — the assertion that returned `nil`. A story declares
            the authoring layer once; its variants inherit it and declare
            nothing. `[:world :substrates]` is where
            `canonical/render-host-scope` reads it, so an absent slot is a
            silent Reagent render."
    (rf.story.registrar/reg-story* :story.scope-sub
      {:doc "declares the authoring layer once, for every variant"
       :component  :views/probe
       :substrates #{:hicasso}})
    (rf.story.registrar/reg-variant* :story.scope-sub/child {:doc "declares nothing"})
    (is (= #{:hicasso}
           (get-in (rf.story.plan/variant-plan :story.scope-sub/child)
                   [:world :substrates]))))

  (testing "and it is the DEFAULT side-table lookup that resolves it — no
            `:story-lookup` was threaded above. A fix that only worked
            through an injected test double would leave the production path
            exactly as broken as it was."
    (is (= #{:hicasso}
           (:substrates (rf.story.registrar/handler-meta :story :story.scope-sub))))))

(deftest the-variant-still-wins-over-its-story
  (testing "precedence is variant-chain FIRST, then the story — the same
            order `multi-substrate/resolve-substrate-set` applies, and the
            same order `canvas/variant-component` applies for the subject.
            A story-level default must not overwrite a variant that
            deliberately differs."
    (rf.story.registrar/reg-story* :story.scope-win
      {:doc "story default" :component :views/probe :substrates #{:reagent}})
    (rf.story.registrar/reg-variant* :story.scope-win/override
      {:doc "this one variant is authored against another layer"
       :substrates #{:uix}})
    (is (= #{:uix}
           (get-in (rf.story.plan/variant-plan :story.scope-win/override)
                   [:world :substrates])))))

(deftest an-extends-chain-still-wins-over-its-story
  (testing "the `:extends` chain is part of the VARIANT layer, so an
            inherited declaration beats the story's too — the parent-first
            fold this bead added sits UNDER the chain, not over it"
    (rf.story.registrar/reg-story* :story.scope-ext
      {:doc "story default" :component :views/probe :substrates #{:reagent}})
    (rf.story.registrar/reg-variant* :story.scope-ext/base
      {:doc "base" :substrates #{:uix}})
    (rf.story.registrar/reg-variant* :story.scope-ext/child
      {:doc "child declares nothing of its own" :extends :story.scope-ext/base})
    (is (= #{:uix}
           (get-in (rf.story.plan/variant-plan :story.scope-ext/child)
                   [:world :substrates])))))

(deftest an-empty-variant-set-declares-nothing
  (testing "`#{}` on the variant is not a declaration — both
            `resolve-substrate-set` and `single-render-substrate` already
            read an empty set as `nothing declared`, so the plan falls
            through to the story rather than pinning emptiness the canvas
            would ignore. This is the row that says the fold mirrors the
            canvas's rule instead of merely adding a fallback."
    (rf.story.registrar/reg-story* :story.scope-empty
      {:doc "story declares" :component :views/probe :substrates #{:uix}})
    (rf.story.registrar/reg-variant* :story.scope-empty/v
      {:doc "declares an empty set" :substrates #{}})
    (is (= #{:uix}
           (get-in (rf.story.plan/variant-plan :story.scope-empty/v)
                   [:world :substrates])))))

(deftest nothing-declared-leaves-the-slot-absent
  (testing "neither body declares ⇒ NO `:substrates` slot, which is what
            lets `single-render-substrate` apply the host default. A fold
            that wrote `#{}` or `nil` here would be render-transparent
            today and a trap for any reader that tests presence."
    (rf.story.registrar/reg-story* :story.scope-none {:doc "declares nothing"
                                             :component :views/probe})
    (rf.story.registrar/reg-variant* :story.scope-none/v {:doc "declares nothing"})
    (is (not (contains? (:world (rf.story.plan/variant-plan :story.scope-none/v))
                        :substrates)))))

;; ===========================================================================
;; 2 · :component — the same asymmetry, and it was painting a nil view
;; ===========================================================================

(deftest a-story-level-component-reaches-the-plan-and-the-render-inputs
  (testing "rf2-sc5g0 — `rf.story.render/prepare-render` reads
            `(get-in plan [:world :component])` with no fallback, so the
            NORMAL authoring shape (`001-Authoring.md`: the parent story
            carries the component, variants vary by args) handed
            `render-variant` a nil view. The canvas was unaffected because
            `canvas/variant-component` walks to the story itself."
    (rf.story.registrar/reg-story* :story.scope-cmp
      {:doc "the parent carries the subject" :component :views/probe})
    (rf.story.registrar/reg-variant* :story.scope-cmp/v
      {:doc "varies by args only" :args {:label "Go"}})
    (let [p (rf.story.plan/variant-plan :story.scope-cmp/v)]
      (is (= :views/probe (get-in p [:world :component]))))
    (let [prepared (rf.story.render/prepare-render :story.scope-cmp/v)]
      (is (= :prepared (:status prepared)))
      (is (= :views/probe (get-in prepared [:render-inputs :view]))
          "the subject reaches the host render hook — this is the
           assertion that answered nil before the fix"))))

(deftest a-variant-component-still-overrides-its-story
  (testing "`:component` is documented as the per-variant OVERRIDE
            (`schemas/VariantBody`), so the variant's value wins"
    (rf.story.registrar/reg-story* :story.scope-cmp-ovr
      {:doc "parent subject" :component :views/parent})
    (rf.story.registrar/reg-variant* :story.scope-cmp-ovr/v
      {:doc "names its own subject" :component :views/own})
    (is (= :views/own
           (get-in (rf.story.plan/variant-plan :story.scope-cmp-ovr/v)
                   [:world :component])))))

(deftest no-component-anywhere-leaves-the-slot-absent
  (testing "an events-only variant under a story that names no subject
            carries no `:component` slot — unchanged, and the row that
            says the fold did not start inventing one"
    (rf.story.registrar/reg-story* :story.scope-cmp-none {:doc "no subject"})
    (rf.story.registrar/reg-variant* :story.scope-cmp-none/v {:doc "no subject"})
    (is (not (contains? (:world (rf.story.plan/variant-plan :story.scope-cmp-none/v))
                        :component)))))

;; ===========================================================================
;; 3 · the deliberate consequence — the view-args contract follows the subject
;; ===========================================================================

(deftest the-view-args-schema-follows-a-story-level-component
  (testing "rf2-sc5g0 — folding `:component` makes the plan compiler's
            view-args schema resolution see the story-level subject too.
            That is the point, not a side effect: the explicit-view-input
            contract must not apply or not apply depending on WHICH body
            happens to name the view."
    (reg-view-meta! :views/widget {:rf/props [:map [:label :string]]})
    (rf.story.registrar/reg-story* :story.scope-schema
      {:doc "parent carries the subject" :component :views/widget})
    (rf.story.registrar/reg-variant* :story.scope-schema/v
      {:doc "varies by args" :args {:label "Hi"}})
    (is (= [:map [:label :string]]
           (get-in (rf.story.plan/variant-plan :story.scope-schema/v)
                   [:world :view-args-schema])))))

(deftest a-missing-required-view-input-fails-under-a-story-level-component
  (testing "and the schema is ENFORCED, not merely copied — a required view
            input the variant never supplies fails plan construction with
            `:rf.error/story-view-args-invalid`, exactly as it does when
            the variant names the component itself. Without this row the
            row above would pass on a plan that carried the schema and
            checked nothing."
    (reg-view-meta! :views/strict {:rf/props [:map [:label :string]]})
    (rf.story.registrar/reg-story* :story.scope-strict
      {:doc "parent carries the subject" :component :views/strict})
    (rf.story.registrar/reg-variant* :story.scope-strict/v {:doc "supplies no args"})
    (let [e (try (rf.story.plan/variant-plan :story.scope-strict/v)
                 nil
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))]
      (is (some? e) "plan construction failed")
      (is (= :rf.error/story-view-args-invalid (:rf.error/id (ex-data e))))
      (is (= :views/strict (:component (ex-data e)))))))
