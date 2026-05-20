(ns re-frame.story-authoring-surface-test
  "Authoring-surface regression net for `reg-variant` (rf2-ctlm5):
  the `:modes` declaration, the `:force-fx-stub` reference shape, and
  the `:extends` decorator-inheritance contract.

  Pairs with `re-frame.story-authoring-validation-test` (which covers
  schema rejection paths) and `re-frame.story-runtime-test` §`resolve-
  args-precedence-chain` (which covers args merge). This namespace
  pins the *round-trips*:

  - **`:modes` round-trip** — a variant declaring `:modes` AND a
    `reg-mode` registration of one of those mode ids produces an
    effective-args map that carries the mode's args when the
    runtime is asked for `:active-modes` containing that id.
  - **`:force-fx-stub` ref-args declared in reg-variant body** —
    the value-form `[:rf.story/force-fx-stub :http {...}]` survives
    the round-trip through the registered :decorators slot AND
    resolve-decorators materialises the per-ref body.
  - **Decorator inheritance via `:extends`** — child variant
    `:extends` parent; child's `:decorators` append to (NOT replace)
    the parent's; story → variant inheritance order is preserved.
  - **Meta → story → variant chain** — story's `:tags` /
    `:argtypes` / `:substrates` cascade into the variant's effective
    body where the variant didn't declare its own; the variant's
    declarations win where it did.

  Per spec/001 §reg-variant authoring schema + spec/002 §:extends
  resolution + spec/010 §Mode authoring."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as frame]
            [re-frame.machines         :as machines]
            [re-frame.registrar        :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story            :as story]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.async      :as async]
            [re-frame.story.config     :as config]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.frames     :as frames]
            [re-frame.story.loaders    :as loaders]
            [re-frame.story.play       :as play]
            [re-frame.story.ui.docs    :as docs]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all [test-fn]
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (config/set-global-args! {})
  (reset! assertions/trace-accumulators {})
  (reset! play/stepper-state            {})
  (reset! frames/stub-call-log          {})
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-all)

;; ===========================================================================
;; :modes declaration in reg-variant — round-trip
;;
;; spec/010 §reg-mode + spec/002 §Args resolution precedence:
;; declaring `:modes #{:Mode.app/dark}` on a variant body is metadata.
;; Activating the mode at runtime (via shell-state's :active-modes or
;; an explicit resolve-args :active-modes opt) deep-merges the
;; registered mode's :args into the effective args.
;;
;; The contract this test pins:
;;
;;   1. The variant body's :modes slot survives unmutated through
;;      registration (queryable via handler-meta).
;;   2. resolve-args with :active-modes containing the mode id merges
;;      the mode's :args in at the precedence layer between :story-args
;;      and :variant-args (rf2-ctlm5 §Modes precedence row).
;;   3. The same effective-args show up when run-variant executes the
;;      variant with :active-modes opts.
;; ===========================================================================

(deftest modes-declared-on-variant-survive-registration
  (testing ":modes #{...} on a variant body round-trips through the
            registrar — the value is queryable on the registered body"
    (story/reg-mode :Mode.app/dark   {:args {:theme :dark}})
    (story/reg-mode :Mode.app/mobile {:args {:viewport :mobile}})
    (story/reg-variant :story.modedecl/v
      {:modes  #{:Mode.app/dark :Mode.app/mobile}
       :events []})
    (let [body (story/handler-meta :variant :story.modedecl/v)]
      (is (= #{:Mode.app/dark :Mode.app/mobile} (:modes body))
          ":modes set survives unmutated — used by docs panel +
           toolbar to scope mode chips per variant"))))

(deftest modes-active-merges-into-effective-args
  (testing ":active-modes opt threads a registered mode's :args into
            the effective args at the spec'd precedence layer
            (between :story-args and :variant-args). Per spec/002
            §Args resolution precedence."
    (story/configure! {:rf.story/global-args {:theme :light :viewport :desktop}})
    (story/reg-mode :Mode.app/dark   {:args {:theme :dark}})
    (story/reg-mode :Mode.app/mobile {:args {:viewport :mobile}})
    (story/reg-story :story.modemix
      {:args {:label "story-label"}})
    (story/reg-variant :story.modemix/v
      {:modes  #{:Mode.app/dark :Mode.app/mobile}
       :args   {:label "variant-label"}
       :events []})
    ;; Single mode active.
    (let [r (story/resolve-args :story.modemix/v
                                {:active-modes [:Mode.app/dark]})]
      (is (= :dark           (:theme r))    "mode wins over global :light")
      (is (= :desktop        (:viewport r)) "non-active mode does NOT apply")
      (is (= "variant-label" (:label r))    "variant wins over story"))
    ;; Two modes active simultaneously (multi-axis selection).
    (let [r (story/resolve-args :story.modemix/v
                                {:active-modes [:Mode.app/dark
                                                :Mode.app/mobile]})]
      (is (= :dark   (:theme r))     "first mode applied")
      (is (= :mobile (:viewport r))  "second mode applied")
      (is (= "variant-label" (:label r))))))

(deftest modes-resolved-from-run-variant
  (testing "run-variant with :active-modes opts exposes the merged
            mode :args on the result map's :effective-args slot. Proves
            the modes resolution actually fires through the runtime's
            prepare-context phase (not just at the standalone
            resolve-args helper).

            Per spec/002 §Args resolution precedence the chain is
            `global < story < mode < variant < cell-overrides`. So
            a mode arg LOSES to a variant arg with the same key but
            WINS over the equivalent story / global arg."
    (story/configure! {:rf.story/global-args {:viewport :desktop}})
    (story/reg-mode :Mode.app/dark   {:args {:theme :dark}})
    (story/reg-mode :Mode.app/mobile {:args {:viewport :mobile}})
    (story/reg-story :story.modeprobe
      {:args {:theme :light}})
    (story/reg-variant :story.modeprobe/v
      {:modes  #{:Mode.app/dark :Mode.app/mobile}
       ;; variant declares NO :theme — so the mode's :theme :dark wins
       ;; over the story's :theme :light.
       :events []})
    ;; Active mode wins over story-level arg.
    (let [r (async/deref-blocking
              (story/run-variant :story.modeprobe/v
                                 {:active-modes [:Mode.app/dark
                                                 :Mode.app/mobile]})
              5000)]
      (is (= :ready (:lifecycle r)))
      ;; :effective-args is the canonical 'these are the args the variant
      ;; actually rendered against' projection on the result map (per
      ;; spec/002 §Run-variant result shape). The mode merge happens in
      ;; prepare-context → args/resolve-args.
      (is (= :dark (-> r :effective-args :theme))
          ":Mode.app/dark wins over story's :theme :light (mode beats story)")
      (is (= :mobile (-> r :effective-args :viewport))
          ":Mode.app/mobile wins over global :viewport :desktop (mode beats global)"))
    ;; Without :active-modes the story-level value wins (no mode merge).
    (let [r2 (async/deref-blocking
               (story/reset-variant :story.modeprobe/v {}) 5000)]
      (is (= :light (-> r2 :effective-args :theme))
          "without :active-modes the story's :theme :light wins")
      (is (= :desktop (-> r2 :effective-args :viewport))
          "without :active-modes the global :viewport :desktop wins"))
    (story/destroy-variant! :story.modeprobe/v)))

;; ===========================================================================
;; :force-fx-stub via reg-variant body (ref-args form)
;;
;; The authoring surface that ships in spec/005 §force-fx-stub is the
;; value-form `[:rf.story/force-fx-stub <fx-id> <response>]` declared
;; in the variant's :decorators vector. This test pins the round-trip:
;; the form survives registration, resolve-decorators materialises a
;; per-ref :body, and run-variant honours the stub.
;; ===========================================================================

(deftest force-fx-stub-declared-in-reg-variant-body
  (testing "[:rf.story/force-fx-stub <fx-id> <response>] declared in
            the variant's :decorators slot round-trips through
            registration AND materialises in resolve-decorators"
    (story/reg-variant :story.authfx/v
      {:decorators [[:rf.story/force-fx-stub :http      {:status :ok}]
                    [:rf.story/force-fx-stub :analytics {:ack? true}]]
       :events     []})
    ;; The body's :decorators slot keeps the user-facing ref form
    ;; verbatim — Storybook-style decorator references stay textually
    ;; identical to what the author typed.
    (let [body (story/handler-meta :variant :story.authfx/v)]
      (is (= [[:rf.story/force-fx-stub :http      {:status :ok}]
              [:rf.story/force-fx-stub :analytics {:ack? true}]]
             (:decorators body))
          ":decorators vector survives unmutated through registration"))
    ;; resolve-decorators materialises each ref into a per-call body
    ;; with the fx-id + response carried explicitly.
    (let [r (story/resolve-decorators :story.authfx/v)]
      (is (= 2 (count (:fx-override r))))
      (let [bodies (sort-by #(-> % :body :fx-id) (:fx-override r))]
        (is (= :analytics    (-> bodies first  :body :fx-id)))
        (is (= {:ack? true}  (-> bodies first  :body :response)))
        (is (= :http         (-> bodies second :body :fx-id)))
        (is (= {:status :ok} (-> bodies second :body :response))))
      ;; The framework :fx-overrides stack picks up both with distinct
      ;; stub-event-ids (proves the value-form authoring works end-to-end
      ;; with the multi-decorator path).
      (let [stack (decorators/fx-overrides-map (:fx-override r))]
        (is (= 2 (count (:overrides stack))))
        (is (contains? (:overrides stack) :http))
        (is (contains? (:overrides stack) :analytics))))))

(deftest force-fx-stub-runtime-intercepts-from-author-form
  (testing "the author-facing value-form actually intercepts the fx at
            runtime — proves the round-trip from declaration to live
            redirect is unbroken"
    (rf/reg-event-fx :do/emit-http
      (fn [_ _] {:fx [[:http {:url "/probe"}]]}))
    (story/reg-variant :story.authfx-rt/v
      {:decorators [[:rf.story/force-fx-stub :http {:status :ok}]]
       :events     []
       :play-script [[:dispatch-sync [:do/emit-http]]
                    [:dispatch-sync [:rf.assert/effect-emitted :http]]]})
    (let [r (async/deref-blocking
              (story/run-variant :story.authfx-rt/v) 5000)]
      (is (= :ready (:lifecycle r)))
      (is (every? :passed? (:assertions r)))
      (is (= 1 (count (frames/stub-call-log-for :story.authfx-rt/v)))
          "exactly one stub call recorded"))
    (story/destroy-variant! :story.authfx-rt/v)))

;; ===========================================================================
;; Decorator inheritance via :extends — child appends; parent decorators
;; precede child decorators (preserving outer-first → inner-last)
;; ===========================================================================

(deftest extends-inherits-decorators-when-child-declares-none
  (testing "decorator inheritance via :extends happens through key
            absence — the child INHERITS the parent's :decorators only
            when the child does NOT declare its own. Per spec/007
            §Composed variants the merge semantics are plain
            `(merge parent child)`. The contract rf2-ctlm5 pins:
            inheritance is via key-absence, NOT vector-append."
    (story/reg-decorator :inherited-deco
      {:kind :hiccup :wrap (fn [body _] [:div.inherited body])})
    (story/reg-variant :story.inherit-bare/parent
      {:decorators [[:inherited-deco]]
       :events     []})
    (story/reg-variant :story.inherit-bare/child
      {:extends :story.inherit-bare/parent
       :events  []})
    (let [body (story/handler-meta :variant :story.inherit-bare/child)]
      (is (= [[:inherited-deco]] (:decorators body))
          "child with no :decorators inherits the parent's stack")
      (is (nil? (:extends body))
          ":extends slot is stripped from the resolved body (consumed
           at registration time)"))
    (let [pack (story/resolve-decorators :story.inherit-bare/child)]
      (is (= [:inherited-deco] (mapv :id (:hiccup pack)))
          "resolved hiccup stack carries the inherited decorator"))))

(deftest extends-child-decorators-replace-parent
  (testing "when the child declares its OWN :decorators, the child's
            slot REPLACES the parent's. This is the spec/007 merge
            semantics — `(merge parent child)` — and applies to every
            top-level body slot uniformly. The same rule covers :args,
            :events, :tags, :modes, etc."
    (story/reg-decorator :parent-deco
      {:kind :hiccup :wrap (fn [body _] [:div.parent body])})
    (story/reg-decorator :child-deco
      {:kind :hiccup :wrap (fn [body _] [:div.child body])})
    (story/reg-variant :story.inherit-replace/parent
      {:decorators [[:parent-deco]]
       :events     []})
    (story/reg-variant :story.inherit-replace/child
      {:extends    :story.inherit-replace/parent
       :decorators [[:child-deco]]
       :events     []})
    (let [body (story/handler-meta :variant :story.inherit-replace/child)]
      (is (= [[:child-deco]] (:decorators body))
          "child's :decorators replaces parent's — NO concat / NO
           append; the child's slot is the final value"))
    (let [pack (story/resolve-decorators :story.inherit-replace/child)]
      (is (= [:child-deco] (mapv :id (:hiccup pack)))
          "resolved hiccup stack reflects ONLY the child's decorators"))))

;; ===========================================================================
;; Meta → story → variant inheritance (rf2-ctlm5 §Decorator inheritance)
;;
;; Story-level slots cascade into the variant when the variant didn't
;; declare its own. The story's :tags / :argtypes / :decorators flow
;; through to the variant's effective body via the docs-pane row
;; helpers and resolve-decorators.
;; ===========================================================================

(deftest story-decorators-cascade-into-variant-resolved-stack
  (testing "story-level :decorators precede variant-level :decorators
            in the resolved hiccup stack (per spec/002 §Decorator
            composition: story outer, variant inner)"
    (story/reg-decorator :story-wrap
      {:kind :hiccup :wrap (fn [body _] [:div.story body])})
    (story/reg-decorator :variant-wrap
      {:kind :hiccup :wrap (fn [body _] [:div.variant body])})
    (story/reg-story :story.cascade
      {:decorators [[:story-wrap]]})
    (story/reg-variant :story.cascade/v
      {:decorators [[:variant-wrap]]
       :events     []})
    (let [pack (story/resolve-decorators :story.cascade/v)
          ids  (mapv :id (:hiccup pack))]
      (is (= [:story-wrap :variant-wrap] ids)
          "story decorators precede variant decorators — outer-first
           → inner-last is preserved"))))

(deftest story-tags-cascade-to-variant-when-variant-declares-none
  (testing "story-level :tags appear on the variant when the variant
            didn't declare its own :tags. Per spec/001 §Authoring
            inheritance."
    (story/reg-story :story.tagcascade
      {:tags #{:dev :docs}})
    (story/reg-variant :story.tagcascade/no-tags
      {:events []})
    (story/reg-variant :story.tagcascade/own-tags
      {:tags   #{:test}
       :events []})
    ;; The variant body's :tags slot may be empty if the variant
    ;; declared none — the docs-pane variant-tags helper is where the
    ;; cascade happens. Pin that the cascade reads through to the
    ;; effective set used by tools that consume `variant-tags`.
    (let [no-tags  (docs/variant-tags :story.tagcascade/no-tags)
          own-tags (docs/variant-tags :story.tagcascade/own-tags)]
      (is (= [:dev :docs] no-tags)
          "no-tags variant inherits story's :tags")
      (is (= [:test] own-tags)
          "own-tags variant uses its own :tags (no merge with parent)"))))
