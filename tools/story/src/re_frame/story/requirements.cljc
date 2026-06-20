(ns re-frame.story.requirements
  "The runner capability / requirement registry — the keystone that decides
  WHICH concrete runner a Story plan needs, and REFUSES (`:cannot-run`)
  rather than faking proof a runner cannot supply
  (`tools/story/spec/017-Testing-Story.md` §Runner model +
  §Runner requirements).

  ## Capabilities are SETS, not a tier scalar

  The five runner KINDS (`:headless` / `:hiccup` / `:cljs-reactive` /
  `:dom` / `:browser`, spec/017 §Runner kinds) are NOT a single total
  order: `:hiccup-structure` and `:reactive-counts` are orthogonal
  capabilities — neither subsumes the other. So a runner advertises a SET
  of capability TOKENS, each step/assertion declares the set of tokens it
  requires, and a runner is **valid** for a plan iff its token set is a
  superset of the plan's required-token union. This is the model spec/017
  pins; the scalar `settled-boundary` ladder
  (`re-frame.story.play.settled-boundary`) is the SETTLEMENT contract for a
  single `[:dispatch …]` step (how long to flush), a DIFFERENT axis from
  the capability sets here (what proof a runner can produce). This ns
  layers capabilities OVER that ladder — it does not replace it.

  ## The capability tokens

  Each token names one proof surface. `capability-tokens` is the closed
  P1 set. `:reactive-counts` is the reactive recompute / over-render count
  surface (spec/017 §1a, §Runner kinds): once it was deferred pending a
  NET-NEW instrumentation seam, but Spec 009 ALREADY emits the underlying
  signals — one `:rf.sub/run` per true sub recompute and one
  `:rf.view/rendered` per view render, both carried into the epoch tape
  and projected at settle time (`re-frame.epoch.capture/project-all`). The
  probe is therefore a PROJECTION over those rows
  (`re-frame.story.play.evidence/reactive-counts`), NOT a new core seam,
  and the `:cljs-reactive` runner — which flushes reactions so subs deref
  and views render (the `settled-boundary` reaction-flush boundary) —
  advertises it. A runner that does NOT exercise the reactive substrate
  still refuses `:cannot-run`, and the post-run fail-closed slot check
  catches a `:cljs-reactive` run whose tape carried no reactive rows.

  ## Cost-ordered concrete runners

  `concrete-runners` is the small cost-ordered list of runners P1 can
  actually select among (cheapest first). `:cljs-reactive` IS in the list
  now that `:reactive-counts` has a real seam (the tape projection above):
  it sits between `:hiccup` and `:dom` on the cost ladder — it flushes
  reactions (richer than a pure hiccup render-to-string) but stops short
  of synthetic DOM events / a real browser. `cheapest-runner` walks this
  list and returns the first whose token set is a superset of the required
  tokens — exactly the spec's \"cheapest = first in a small cost-ordered
  concrete-runner list whose set qualifies\".

  ## MCP is a binding, not a tier

  MCP is NOT a runner. It is a transport/control surface over the same
  `story/run` / `story/explain` (spec/017 §Runner kinds). A live agent run
  differs only by FRAME BINDING (`:fresh` vs `:attached`) — modeled here
  as the `:frame-binding` run-opt, NOT as a capability token or a runner
  kind. `normalize-run-opts` carries it through untouched.

  ## Fail-closed, two sides

  A proof is honoured only when BOTH sides agree it is available:

  1. **Preflight** (`select-runner`, `cannot-run?`): the chosen runner's
     token set must be a superset of every selected requirement. A missing
     token → `:cannot-run` refusal (`requirement-refusal`), never a silent
     pass.
  2. **Post-run** (`evidence-slot-satisfied?`, `validate-evidence`): a
     step/assertion that REQUIRED a proof must find the corresponding
     evidence SLOT populated in the projected run evidence
     (`re-frame.story.play.evidence/project-evidence`). A required slot
     that the tape never produced FAILS CLOSED — `:cannot-run` (the proof
     was promised but not delivered), NEVER `:pass`.

  ## Pure / JVM-testable

  Every fn here is pure data → data: tokens / requirement-sets / runner
  descriptors in, selection + refusal + validation maps out. It requires
  NOTHING from `re-frame.core`, the DOM, or a host, so the whole registry
  + selection + validation surface runs under `clojure -M:test`.

  Its `:cannot-run` refusal (`requirement-refusal`) is the CAPABILITY-axis
  sibling of the BOUNDARY-axis refusal that
  `re-frame.story.play.settled-boundary/cannot-run-refusal` builds. Both
  share the `:status :cannot-run` discriminator (spec/017 §`:cannot-run` —
  the distinct THIRD status, never a silent pass), but they are TWO
  parallel refusals on two different axes (a missing capability token here;
  a runner that does not reach the required settle boundary there), keyed
  on their own facts — NOT one shared map. The axes are legitimately
  distinct, so this ns names its own refusal rather than borrowing the
  boundary one."
  (:require [re-frame.story.play.evidence :as evidence]))

;; ===========================================================================
;; CAPABILITY TOKENS  (spec/017 §Runner kinds and capabilities)
;; ===========================================================================
;;
;; Each token names ONE proof surface. The set is closed for P1; new proof
;; surfaces (a future `:network-record` token, say) extend it additively.

(def capability-tokens
  "The closed P1 set of capability tokens. A token names one proof surface
  a runner either can or cannot produce. `:reactive-counts` is the reactive
  recompute / over-render count surface (spec/017 §1a); it has a real seam
  — the `re-frame.story.play.evidence/reactive-counts` projection over the
  `:rf.sub/run` / `:rf.view/rendered` rows Spec 009 already lands in the
  tape — and is advertised by the `:cljs-reactive` runner."
  #{:app-db          ; final app-db / event / cofx state — the headless floor
    :effects         ; emitted effect rows
    :schema          ; schema-validation-failure trace evidence
    :trace           ; trace-event stream (warnings, dispatched, caused, …)
    :pure-subs       ; compute-sub against the snapshot (no reactive cache)
    :hiccup-structure ; render-to-string / hiccup tree + text + handler wiring
    :reactive-counts ; reactive recompute / over-render counts (:cljs-reactive)
    :dom             ; DOM events, focus, visibility, browser APIs
    :pixels          ; real-browser layout / screenshots / pixel diffs
    :a11y-engine})   ; axe-style accessibility engine

(def reactive-counts-token
  "The reactive recompute / over-render count token (spec/017 §1a). The
  `:cljs-reactive` runner advertises it — its proof is the
  `re-frame.story.play.evidence/reactive-counts` projection over the
  `:rf.sub/run` / `:rf.view/rendered` rows the framework already retains in
  the epoch tape. A runner that does not flush reactions cannot prove it,
  so a requirement on it resolves to `:cannot-run` under those runners.
  Named so the call site reads explicitly and the projection has one place
  to anchor."
  :reactive-counts)

;; ===========================================================================
;; COST-ORDERED CONCRETE RUNNERS  (spec/017 §Runner kinds and capabilities)
;; ===========================================================================
;;
;; Cheapest first. Each runner advertises the token SET it can prove. A
;; richer runner is a SUPERSET of every cheaper one for the tokens that ARE
;; ordered (app-db ⊂ hiccup ⊂ cljs-reactive ⊂ dom ⊂ browser), and adds its
;; own orthogonal tokens. `:reactive-counts` enters at the `:cljs-reactive`
;; rung (reaction flush) and rides up through `:dom` / `:browser`.

(def headless-tokens
  "What `:headless` proves: app-db / events / effects / schema / trace /
  pure subscriptions (spec/017 §Runner kinds — the headless floor)."
  #{:app-db :effects :schema :trace :pure-subs})

(def hiccup-tokens
  "`:hiccup` proves the headless floor AND hiccup structure / text /
  handler wiring / render-to-string facts."
  (into headless-tokens #{:hiccup-structure}))

(def cljs-reactive-tokens
  "`:cljs-reactive` proves the hiccup set AND `:reactive-counts` — the
  reactive recompute / over-render count surface (spec/017 §Runner kinds —
  \"reactive subscription cache, recompute count, render cause, over-render
  checks\"). It flushes reactions (the `settled-boundary`
  reaction-flush boundary) so subs deref and views render, landing the
  `:rf.sub/run` / `:rf.view/rendered` rows the
  `re-frame.story.play.evidence/reactive-counts` projection counts. It does
  NOT add `:dom` — synthetic DOM events / focus / visibility need the
  richer `:dom` rung."
  (into hiccup-tokens #{:reactive-counts}))

(def dom-tokens
  "`:dom` proves the cljs-reactive set (incl. `:reactive-counts` — a DOM
  runner flushes reactions too) AND DOM events / focus / visibility /
  browser APIs."
  (into cljs-reactive-tokens #{:dom}))

(def browser-tokens
  "`:browser` proves the dom set AND real-browser layout / screenshots /
  pixel diffs / the a11y engine."
  (into dom-tokens #{:pixels :a11y-engine}))

(def concrete-runners
  "The cost-ordered concrete-runner list P1 selects among — cheapest
  first. `cheapest-runner` returns the FIRST whose `:provides` token set is
  a superset of the plan's required tokens (spec/017 §Runner kinds —
  \"cheapest = first in a small cost-ordered concrete-runner list whose set
  qualifies\").

  `:cljs-reactive` sits between `:hiccup` and `:dom`: its distinguishing
  token `:reactive-counts` now has a real seam (the
  `re-frame.story.play.evidence/reactive-counts` projection over the
  `:rf.sub/run` / `:rf.view/rendered` rows the framework retains), so it is
  the cheapest runner that can prove a reactive-count requirement
  (`:rf.assert/caused` / `:rf.assert/no-cascade-rerender`)."
  [{:runner :headless      :provides headless-tokens}
   {:runner :hiccup        :provides hiccup-tokens}
   {:runner :cljs-reactive :provides cljs-reactive-tokens}
   {:runner :dom           :provides dom-tokens}
   {:runner :browser       :provides browser-tokens}])

(def runner-kinds
  "All runner KIND keywords spec/017 §Runner kinds names. `:auto` is the
  SELECTION mode (not a kind) — handled by `normalize-run-opts`, not present
  here."
  #{:headless :hiccup :cljs-reactive :dom :browser})

(defn- runner-descriptor
  "The concrete-runner descriptor for `runner-kind`, or nil when the kind
  is not a recognised concrete runner."
  [runner-kind]
  (some (fn [d] (when (= runner-kind (:runner d)) d)) concrete-runners))

(defn runner-provides
  "The capability token set a concrete `runner-kind` advertises, or `#{}`
  for an unknown / not-P1-selectable kind (fail-closed — an unknown runner
  proves nothing)."
  [runner-kind]
  (:provides (runner-descriptor runner-kind) #{}))

(defn runner-cost
  "The 0-based cost rank of `runner-kind` on `concrete-runners` (cheapest =
  0). Returns a large sentinel for a non-selectable kind so it sorts last —
  it is never preferred."
  [runner-kind]
  (let [idx (reduce-kv (fn [_ i d] (when (= runner-kind (:runner d)) (reduced i)))
                       nil
                       (vec concrete-runners))]
    (if (integer? idx) idx 1000000)))

;; ===========================================================================
;; REQUIREMENT INFERENCE  (spec/017 §Runner requirements)
;; ===========================================================================
;;
;; Each STEP and ASSERTION declares the capability TOKENS it requires. These
;; are the single source of truth the plan compiler (`re-frame.story.plan`)
;; reads to compute `:required-runner`. The registry has ONE home here.

(def step-capabilities
  "Capability tokens each SCRIPT/SETUP step tag requires (spec/017 §Script
  step grammar + §Runner requirements). `:dispatch` needs only `:app-db`
  (the headless floor drains the queue to fixed point); DOM-driving steps
  need `:dom`. `:wait` / `:wait-until` require nothing of their own — the
  predicate / wall-clock dictates the settlement, and the boundary ladder
  (`settled-boundary`) governs flush, not the capability set."
  {:dispatch       #{:app-db}
   :dispatch-sync  #{:app-db}
   :wait           #{}
   :wait-until     #{}
   :assert-db      #{:app-db}
   :assert         #{:app-db}   ; in-script checkpoint — tokens come from its assertion atom
   :assert-dom     #{:dom}
   :click          #{:dom}
   :type           #{:dom}
   :focus          #{:dom}})

(def assertion-capabilities
  "Capability tokens each terminal/`[:assert …]` assertion id requires
  (spec/017 §Canonical P1 assertions + §Runner requirements). The seven
  shipping `:rf.assert/*` ids prove against app-db / trace; the schema
  assertion reads the schema-violation projection; the DOM family needs
  `:dom`; visual/a11y need browser-tier tokens. The reactive-count
  assertions (`:rf.assert/caused` / `:rf.assert/no-cascade-rerender`)
  require `:reactive-counts` — now a real seam (the
  `re-frame.story.play.evidence/reactive-counts` projection over the
  `:rf.sub/run` / `:rf.view/rendered` rows the framework retains), proven
  by the `:cljs-reactive` runner. Under a runner that does not flush
  reactions (`:headless` / `:hiccup`) they still resolve to `:cannot-run`,
  and the post-run fail-closed slot check refuses a `:cljs-reactive` run
  whose tape carried no reactive rows.

  ## Browser-tier oracles (spec/017 §Visual, a11y, and browser checks)

  The two pixel/a11y-engine ids are runner-tiered assertions on the SAME
  variant/result model, NOT a separate visual-testing system:

  - `:rf.assert/visual-snapshot` requires `:pixels` — a real-browser
    screenshot + pixel diff (or, reusing the existing visual-regression
    seam, the `re-frame.story.identity` content-hash as the snapshot
    IDENTITY key). No P1 headless/hiccup runner advertises `:pixels`, so a
    headless run resolves to `:cannot-run`.
  - `:rf.assert/a11y` requires `:a11y-engine` — an axe-style scan, reusing
    the existing `re-frame.story.ui.a11y` axe-core hook (the
    `violations-by-frame` atom the MCP `read-a11y-violations` tool already reads). No
    P1 headless/hiccup runner advertises `:a11y-engine`, so a headless run
    resolves to `:cannot-run`.

  `:rf.assert/a11y-structural` is the STRUCTURAL accessibility check that
  needs only `:hiccup-structure` — it inspects the rendered hiccup TREE
  (data) for structural a11y facts (img missing `:alt`, control missing an
  accessible name, …) without a real browser / axe engine. Because the
  hiccup tree is pure data, the `:hiccup` runner satisfies it and the
  executor (`re-frame.story.play.browser/eval-structural-a11y`) is fully
  JVM-testable. This is the spec's \"structural a11y checks MAY require
  only `:hiccup`\" rung (§Visual, a11y, and browser checks)."
  {:rf.assert/path-equals          #{:app-db}
   :rf.assert/path-matches         #{:app-db}
   :rf.assert/sub-equals           #{:app-db :pure-subs}
   :rf.assert/dispatched?          #{:app-db :trace}
   :rf.assert/state-is             #{:app-db}
   :rf.assert/no-warnings          #{:trace}
   :rf.assert/effect-emitted       #{:effects}
   :rf.assert/schema-error         #{:schema}
   :rf.assert/dom-visible          #{:dom}
   :rf.assert/dom-hidden           #{:dom}
   :rf.assert/dom-text             #{:dom}
   :rf.assert/visual-snapshot      #{:pixels}
   :rf.assert/a11y                 #{:a11y-engine}
   ;; Structural a11y rides the :hiccup tier — the hiccup tree is data, so
   ;; no real browser / axe engine is needed (spec/017 §Visual, a11y, and
   ;; browser checks — \"structural a11y checks MAY require only `:hiccup`\").
   :rf.assert/a11y-structural      #{:hiccup-structure}
   ;; Reactive-count assertions require `:reactive-counts` — proven by the
   ;; `:cljs-reactive` runner via the `evidence/reactive-counts` projection
   ;; (spec/017 §1a). Under `:headless` / `:hiccup` they resolve to
   ;; `:cannot-run`; `:auto` escalates to `:cljs-reactive`.
   :rf.assert/caused               #{:reactive-counts}
   :rf.assert/no-cascade-rerender  #{:reactive-counts}})

(declare assertion-tokens)

(defn- tag
  "The head keyword of a tagged vector, or nil for a non-vector / untagged
  form. Pure."
  [v]
  (when (and (vector? v) (pos? (count v)))
    (let [h (first v)] (when (keyword? h) h))))

(defn step-tokens
  "Capability tokens `step` requires. For a `[:assert assertion-atom]`
  in-script checkpoint the tokens come from the WRAPPED assertion atom (the
  `:app-db` of the `:assert` tag PLUS whatever the assertion itself needs),
  so a `[:assert [:rf.assert/visual-snapshot …]]` checkpoint correctly
  requires `:pixels`. Pure data → data."
  [step]
  (let [t (tag step)]
    (if (= :assert t)
      (into (get step-capabilities :assert #{})
            (assertion-tokens (second step)))
      (get step-capabilities t #{}))))

(defn assertion-tokens
  "Capability tokens the assertion atom `assertion` requires, keyed by its
  `:rf.assert/*` id. An unknown id requires nothing (`#{}`) — it cannot be
  proven by a richer runner anyway, and the post-run evidence check still
  governs it. Pure data → data."
  [assertion]
  (get assertion-capabilities (tag assertion) #{}))

(defn required-tokens
  "Union the capability tokens demanded by every setup step, script step,
  and terminal assertion (spec/017 §Runner requirements — `:required-runner`
  is the union). `:headless` work contributes the empty set (which resolves
  to the cheapest `:headless` runner). Pure data → data.

  This is the single source of truth `re-frame.story.plan` reads to fill
  the plan's `:required-runner` slot — capability tokens, NOT a tier
  scalar."
  ([setup script assertions] (required-tokens setup script assertions nil))
  ([setup script assertions in-script-assertions]
   (reduce into #{}
           (concat (map step-tokens (or setup []))
                   (map step-tokens (or script []))
                   (map assertion-tokens (or assertions []))
                   (map assertion-tokens (or in-script-assertions []))))))

;; ===========================================================================
;; RUNNER SELECTION  (spec/017 §Runner policy — single pass, refuse above it)
;; ===========================================================================
;;
;; Two policies (spec/017 §Runner policy):
;;
;;   - FIXED-RUNNER (default): the caller's `:runner` (or `:headless`) runs
;;     the WHOLE plan in one pass. A step/assertion whose required tokens
;;     the fixed runner lacks records `:cannot-run` per-requirement (pure
;;     set-difference) — one `:visual-snapshot` does NOT drag a 95%-headless
;;     variant to `:browser`. The variant-level aggregation rule (below)
;;     then decides the run's status.
;;   - AUTO / ESCALATE: choose the CHEAPEST concrete runner whose token set
;;     satisfies ALL selected requirements. When none can (a requirement on
;;     `:reactive-counts`), the run is `:cannot-run`.

(defn missing-tokens
  "The capability tokens `required` demands that `provided` lacks — pure
  set-difference. Empty iff `provided` is a superset of `required`. Pure
  data → data."
  [required provided]
  (into #{} (remove (set provided)) (set required)))

(defn runner-satisfies?
  "True iff a runner that PROVIDES `provided` can prove every token in
  `required` (its set is a superset). Pure data → data; fail-closed (an
  unknown provided set proves nothing)."
  [provided required]
  (empty? (missing-tokens required provided)))

(defn cheapest-runner
  "The cheapest concrete runner (first on `concrete-runners`) whose
  `:provides` token set is a superset of `required-tokens`, or nil when NO
  concrete runner qualifies (a requirement on `:reactive-counts`). Pure
  data → data — the `:auto` / `:escalate` selection (spec/017 §Runner
  policy)."
  [required-tokens]
  (some (fn [{:keys [runner provides]}]
          (when (runner-satisfies? provides required-tokens) runner))
        concrete-runners))

(defn requirement-refusal
  "Build the `:cannot-run` refusal for a requirement the chosen runner
  cannot satisfy. Shape per spec/017 §`:cannot-run` (the distinct THIRD
  status, never a silent pass) — reusing the ONE refusal vocabulary
  (`re-frame.story.play.settled-boundary/cannot-run-refusal` is the
  boundary-axis sibling; this is the capability-axis form):

      {:status           :cannot-run
       :required-runner  #{token …}    ; the tokens the unit needs
       :available-runner #{token …}    ; the chosen runner's tokens
       :missing          #{token …}    ; required − available (the gap)
       :reason           <keyword>     ; :runner-lacks-capability | :no-runner-satisfies
       :runner           <runner-kind> ; the runner that refused (when fixed)
       :unit             <step|assertion>}  ; what required the proof

  `reason` defaults to `:runner-lacks-capability`; the auto-selection
  no-qualifying-runner case passes `:no-runner-satisfies` (and a nil
  runner)."
  ([required available unit]
   (requirement-refusal required available unit :runner-lacks-capability nil))
  ([required available unit reason runner]
   (cond-> {:status           :cannot-run
            :required-runner  (set required)
            :available-runner (set available)
            :missing          (missing-tokens required available)
            :reason           reason}
     (some? runner) (assoc :runner runner)
     (some? unit)   (assoc :unit unit))))

(defn select-runner
  "Decide the runner for a plan's `required-tokens` under the run `opts`
  (spec/017 §Runner policy — single pass, refuse above it). Pure data →
  data. `opts` is a normalized run-opts map (`normalize-run-opts`):

      {:runner :headless|:hiccup|:dom|:browser  ; fixed-runner policy
       :mode   :fixed | :auto}                   ; :auto = escalate

  Returns one of:

  - `{:status :ok :runner <kind> :provides #{…} :required #{…}
      :unmet #{…} :policy :fixed|:auto}` — a runner was chosen. `:unmet`
    is the tokens the chosen runner does NOT provide; under `:fixed` policy
    it may be non-empty (the variant-level aggregation rule then decides
    whether the run is `:cannot-run`); under `:auto` it is always empty
    (the cheapest qualifying runner was chosen, so it satisfies all).
  - a `requirement-refusal` — `:auto` policy and NO concrete runner can
    satisfy `required-tokens` (`:reason :no-runner-satisfies`). The whole
    run is `:cannot-run` (spec/017: \"if no available runner can satisfy
    the requested policy, the run returns `:cannot-run`\")."
  [required-tokens {:keys [runner mode] :as _opts}]
  (let [required (set required-tokens)]
    (if (= :auto mode)
      ;; ESCALATE / :auto — cheapest concrete runner that satisfies ALL.
      (if-let [chosen (cheapest-runner required)]
        {:status   :ok
         :runner   chosen
         :provides (runner-provides chosen)
         :required required
         :unmet    #{}
         :policy   :auto}
        (requirement-refusal required #{} nil :no-runner-satisfies nil))
      ;; FIXED-RUNNER — run the whole plan under `runner` (default
      ;; :headless). Per-requirement `:cannot-run` is decided downstream
      ;; (`unmet-requirements`); the plan runs single-pass regardless.
      (let [runner   (or runner :headless)
            provides (runner-provides runner)]
        {:status   :ok
         :runner   runner
         :provides provides
         :required required
         :unmet    (missing-tokens required provides)
         :policy   :fixed}))))

;; ===========================================================================
;; PER-UNIT REFUSAL + VARIANT AGGREGATION  (spec/017 §`:cannot-run`)
;; ===========================================================================
;;
;; Under fixed-runner policy a unit (step or assertion) whose required
;; tokens the runner lacks is `:cannot-run` — pure set-difference, no
;; whole-plan escalation. The variant-level AGGREGATION rule is stated ONCE
;; (spec/017 §`:cannot-run`): a variant whose ONLY unmet expectations are
;; `:cannot-run` is itself `:cannot-run`, not a silent pass.

(defn unit-cannot-run?
  "True iff a runner PROVIDING `provided` cannot satisfy `unit`'s required
  tokens (`unit-tokens` is `step-tokens` for a step, `assertion-tokens` for
  an assertion). Pure data → data."
  [provided unit-tokens]
  (not (runner-satisfies? provided unit-tokens)))

(defn unmet-assertions
  "The `:cannot-run` refusals for every terminal assertion the chosen
  `runner` cannot prove, attributed to its source assertion atom (spec/017
  §`:cannot-run` — explain MUST attribute unmet requirements to the
  originating assertion; no hidden global effect). Pure data → data.

  `assertions` is the plan's terminal assertion vector. Returns a vector of
  `requirement-refusal` maps (each carrying `:unit` = the assertion atom),
  empty when the runner proves every assertion."
  [runner assertions]
  (let [provided (runner-provides runner)]
    (into []
          (keep (fn [a]
                  (let [req (assertion-tokens a)]
                    (when (unit-cannot-run? provided req)
                      (requirement-refusal req provided a
                                           :runner-lacks-capability runner)))))
          (or assertions []))))

(defn unmet-steps
  "The `:cannot-run` refusals for every script/setup step the chosen
  `runner` cannot settle for capability reasons (a `:dom` `[:click …]`
  under `:headless`), attributed to the originating step. Pure data → data.
  Complements the `settled-boundary` boundary-axis refusal: this is the
  capability-axis (what proof), that is the settlement-axis (how long to
  flush)."
  [runner steps]
  (let [provided (runner-provides runner)]
    (into []
          (keep (fn [s]
                  (let [req (step-tokens s)]
                    (when (unit-cannot-run? provided req)
                      (requirement-refusal req provided s
                                           :runner-lacks-capability runner)))))
          (or steps []))))

(defn aggregate-status
  "The variant-level status, applying the ONE aggregation rule (spec/017
  §`:cannot-run`): a variant whose only unmet expectations are
  `:cannot-run` is itself `:cannot-run`, not a silent pass; a genuine
  assertion failure is `:fail`; everything met-and-passing is `:pass`.
  Pure data → data.

  `assertion-records` is the run's evaluated assertion records (each with a
  `:status` / `:passed?`); `unmet` is the vector of `:cannot-run` refusals
  for expectations the runner could not even attempt. Precedence:
  `:error` > `:fail` > `:cannot-run` > `:pass` — a real failure or error
  is never masked by a refusal."
  [assertion-records unmet]
  (let [statuses (map (fn [r]
                        (or (:status r)
                            (if (:passed? r) :pass :fail)))
                      (or assertion-records []))]
    (cond
      (some #{:error} statuses)            :error
      (some #{:fail} statuses)             :fail
      (or (seq unmet) (some #{:cannot-run} statuses)) :cannot-run
      :else                                :pass)))

;; ===========================================================================
;; POST-RUN EVIDENCE-SLOT VALIDATION  (spec/017 §`:cannot-run` — fail closed)
;; ===========================================================================
;;
;; Preflight (above) confirms the chosen runner CLAIMS the tokens. Post-run
;; validation confirms the tape actually PRODUCED the evidence: a
;; step/assertion that REQUIRED a proof FAILS CLOSED if the projected
;; evidence (`evidence/project-evidence`) does not carry the matching slot.
;; This is the second half of the spec's two-sided fail-closed rule — a
;; runner that claims `:effects` but emits a tape with no effect rows for an
;; `:rf.assert/effect-emitted` expectation must NOT silently pass.

(def token->evidence-slots
  "Map each capability token to the run-evidence SLOT(S) whose PRESENCE in
  the projected evidence (`evidence/project-evidence`) proves the token was
  actually exercised. A token with NO slot needs no post-run check — its
  proof is the assertion's own evaluation, not a distinct stream the runner
  could fail to produce.

  ## The empty-slot-means-no-proof invariant

  A token belongs here ONLY when an EMPTY/absent slot genuinely means the
  runner promised a proof it never delivered — i.e. the slot is non-empty
  for EVERY healthy run of an assertion requiring that token. The listed
  tokens satisfy that:

  - `:effects` / `:schema` — `:rf.assert/effect-emitted` /
    `:rf.assert/schema-error` are HEALTHY only when the tape carried the
    matching row; empty = the promised effect / schema-failure never
    happened = genuinely no proof.
  - `:reactive-counts` — present only when the tape carried `:rf.sub/run` /
    `:rf.view/rendered` rows; absent means the reactive substrate was never
    exercised, so a `:rf.assert/caused` / `:rf.assert/no-cascade-rerender`
    run on it has no proof.
  - `:pixels` / `:a11y-engine` — browser-only oracle streams; empty = no
    screenshot / no axe scan = no proof.

  ## Tokens DELIBERATELY absent (empty-is-HEALTHY)

  Tokens whose slot is empty in the NORMAL passing case must NOT be listed —
  keying a fail-closed presence check on them emits a FALSE `:cannot-run`
  for healthy runs:

  - `:trace` — the trace-event stream is always-on for any run; the only
    projections `evidence/project-evidence` exposes (`:warnings`,
    `:schema-violations`) are FILTERED views, not a faithful presence slot
    for the whole stream. `:rf.assert/no-warnings` PASSES precisely when
    `:warnings` is EMPTY, and `:rf.assert/dispatched?` proves against trace
    DISPATCH rows, not warnings — so `:trace → :warnings` would false-refuse
    both healthy cases. Its proof is the assertion's own trace evaluation.
  - `:hiccup-structure` / `:dom` — `:renders` is empty whenever an assertion
    legitimately asserts ABSENCE (`:rf.assert/dom-hidden`, a structural-a11y
    check that finds no offending node) or runs against a tree that
    committed no render row. The proof is the assertion's own inspection of
    the rendered tree, not the COUNT of render rows. The runner-selection
    preflight (`select-runner` / `unmet-assertions`) already fail-closes
    these against a runner that cannot render at all; a post-run render-count
    gate would only add false refusals.

  Only tokens whose proof is a DISTINCT, always-non-empty-when-healthy
  evidence stream are listed; an empty/absent slot for a REQUIRED such token
  is the fail-closed trigger (spec/017 §`:cannot-run` — post-run validation
  confirms the required evidence slots are present)."
  {:effects          [:effects]
   :schema           [:schema-violations]
   :reactive-counts  [:reactive-counts]   ; present only when the tape carried reactive rows
   :pixels           [:pixels]            ; browser-only slot — never present headless
   :a11y-engine      [:a11y]})            ; browser-only slot

(defn evidence-slot-satisfied?
  "True iff EVERY evidence slot the `required-tokens` demand is POPULATED in
  the projected `evidence` map (`evidence/project-evidence` output). Pure
  data → data. A token with no entry in `token->evidence-slots` (e.g.
  `:app-db`, `:trace`, `:hiccup-structure` — the always-on / empty-is-healthy
  tokens, see that map's docstring) imposes no slot requirement: its proof is
  the assertion's own evaluation, not a distinct stream. A required token
  whose slot IS in the map but is absent / empty is NOT satisfied — the proof
  was promised by the runner but the tape did not deliver it.

  This is the post-run half of the fail-closed contract: NEVER report a
  required proof as passing when the evidence stream that should carry it is
  empty."
  [required-tokens evidence]
  (every? (fn [tok]
            (let [slots (get token->evidence-slots tok)]
              (or (nil? slots)
                  (some (fn [slot] (seq (get evidence slot))) slots))))
          (set required-tokens)))

(defn validate-evidence
  "Post-run, fail-closed evidence-slot validation for ONE assertion atom
  (spec/017 §`:cannot-run` — \"a step/assertion that REQUIRED a proof FAILS
  CLOSED if the tape/result did not contain it\"). Pure data → data.

  `assertion` is the assertion atom; `evidence` is the projected run
  evidence (`evidence/project-evidence`); `runner` is the runner kind that
  ran. Returns nil when the required evidence is present (the assertion's
  own pass/fail then stands), or a `requirement-refusal` (`:reason
  :required-evidence-missing`) when a required proof slot is empty — so a
  missing required evidence slot reports `:cannot-run`, NEVER a pass.

  This consumes `.4`'s projection (`evidence/project-evidence`) as the
  single source of truth: the proof check reads the SAME tape projection
  the run-result slots derive from, so a duplicate accumulator cannot
  report green when the tape is empty."
  [assertion evidence runner]
  (let [req (assertion-tokens assertion)]
    (when-not (evidence-slot-satisfied? req evidence)
      (assoc (requirement-refusal req (runner-provides runner) assertion
                                  :required-evidence-missing runner)
             :missing-evidence
             (into #{}
                   (mapcat (fn [tok]
                             (let [slots (get token->evidence-slots tok)]
                               (when (and slots
                                          (not (some #(seq (get evidence %)) slots)))
                                 slots))))
                   req)))))

(defn validate-run-evidence
  "Post-run, fail-closed validation across a plan's terminal `assertions`
  against the projected run `evidence` and the `runner` that ran. Pure
  data → data — the run-level entry point.

  Returns `{:status :ok}` when every assertion's required proof slots are
  present, or `{:status :cannot-run :missing-evidence [refusal …]}` listing
  the per-assertion `requirement-refusal`s for assertions whose required
  evidence the tape did not deliver. The runner merges a non-`:ok` result
  into the run-result as `:cannot-run`, NEVER reporting those assertions as
  passing (spec/017 §`:cannot-run`)."
  [assertions evidence runner]
  (let [refusals (into []
                       (keep #(validate-evidence % evidence runner))
                       (or assertions []))]
    (if (empty? refusals)
      {:status :ok}
      {:status           :cannot-run
       :missing-evidence refusals})))

;; ===========================================================================
;; RUN / `is` OPTS  (spec/017 §Public execution API — the three verbs)
;; ===========================================================================
;;
;; The P1 run/is opts (spec/017 §Public execution API):
;;
;;   {:runner :headless|:hiccup|:dom|:browser|:auto
;;    :escalate boolean                ; synonym for :runner :auto when true
;;    :frame-binding :fresh | :attached ; MCP-as-binding, NOT a runner tier
;;    :platform :client | :server}
;;
;; `normalize-run-opts` is the ONE place these collapse into the canonical
;; selection shape `select-runner` consumes, so the (deferred) three-verb
;; surface and the MCP transport thread the SAME normalization.

(def default-runner
  "The default `:runner` for `story/run` / `story/is` (spec/017 §Public
  execution API — \"default `:runner` is `:headless` for test
  execution\")."
  :headless)

(def default-frame-binding
  "The default frame binding — a `:fresh` frame per run. `:attached` is the
  live-agent / MCP binding over the same `story/run` (spec/017 §Runner kinds
  — MCP is a binding, not a tier)."
  :fresh)

(def default-platform
  "The default execution platform (spec/017 §Public execution API)."
  :client)

(def frame-bindings #{:fresh :attached})
(def platforms      #{:client :server})

(defn normalize-run-opts
  "Normalize raw `story/run` / `story/is` opts into the canonical selection
  shape (spec/017 §Public execution API). Pure data → data. Collapses the
  `:runner :auto` / `:escalate true` synonyms into one `:mode`, defaults
  the runner / frame-binding / platform, and carries the MCP-as-binding
  `:frame-binding` through untouched (it is NOT a runner tier).

  Returns:

      {:runner :headless|:hiccup|:dom|:browser   ; the FIXED runner (when :mode :fixed)
       :mode   :fixed | :auto                     ; :auto = escalate to cheapest
       :frame-binding :fresh | :attached
       :platform :client | :server}

  `:escalate true` OR `:runner :auto` → `{:mode :auto}` (the `:runner` slot
  is then advisory — `select-runner` chooses the cheapest qualifying
  runner). Any other `:runner` → `{:mode :fixed :runner <kind>}`. An
  unrecognised `:runner` falls back to the default `:headless` fixed
  policy (fail-safe; an unknown tier never silently escalates)."
  ([] (normalize-run-opts nil))
  ([{:keys [runner escalate frame-binding platform] :as _opts}]
   (let [auto? (or (true? escalate) (= :auto runner))
         fb    (if (contains? frame-bindings frame-binding) frame-binding default-frame-binding)
         plat  (if (contains? platforms platform) platform default-platform)]
     (if auto?
       {:mode :auto :frame-binding fb :platform plat}
       (let [r (if (contains? runner-kinds runner)
                 runner
                 default-runner)]
         {:mode :fixed :runner r :frame-binding fb :platform plat})))))

;; ===========================================================================
;; PLAN INTEGRATION  (the `:required-runner` slot helper)
;; ===========================================================================

(defn plan-required-runner
  "Compute a plan's `:required-runner` capability set from its resolved
  setup / script / terminal assertions (spec/017 §Runner requirements). The
  single helper `re-frame.story.plan` calls to fill the `:required-runner`
  slot — capability tokens, NOT a tier scalar. Pure data → data.

  `in-script-assertions` (optional) is the vector of assertion atoms pulled
  from `[:assert …]` checkpoint steps; `step-tokens` already folds an
  `[:assert …]` step's wrapped-atom tokens, so passing them again is
  idempotent — the optional arg is for callers that collect checkpoints
  separately from the raw script."
  ([setup script assertions] (required-tokens setup script assertions))
  ([setup script assertions in-script-assertions]
   (required-tokens setup script assertions in-script-assertions)))
