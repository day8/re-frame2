(ns re-frame.story.ui.sidebar-signals
  "Pure data → data derivation of the sidebar's per-variant SIGNAL CHIPS
  (rf2-ba86n.4, spec/018 §7.1 Sidebar + §12.6 Status and colour).

  ## Five DISTINCT chip axes — never collapsed into 'fidelity'

  Spec/018 §7.1 + §12.6 are emphatic: a variant carries up to FIVE
  *separate* signal groups, each with its own label vocabulary and
  tooltip. They MUST NOT be flattened into one 'fidelity' concept — args
  are an input surface, network / fx-overrides are world inputs,
  browser-required is a runner requirement, and attached-frame / MCP-bound
  are frame-binding signals. This leaf keeps each axis as its own derived
  vector so the renderer (`re-frame.story.ui.sidebar`) lays them out as
  adjacent-but-distinct chip groups:

  | Axis                | Members (spec/018 §7.1 / §12.6)                       | Source |
  |---------------------|-------------------------------------------------------|--------|
  | `:status`           | pass / fail / cannot-run / error / pending / running / blocked / dirty / redacted | the run record |
  | `:fidelity`         | real-setup / db-seed / sub-overrides                  | `plan/compute-fidelity` over world inputs |
  | `:world-inputs`     | args / route / network / fx-overrides                 | presence of the body's world slots |
  | `:runner-requirement` | headless / hiccup / cljs-reactive / dom / browser   | `requirements` capability tokens → cheapest runner |
  | `:frame-binding`    | fresh / attached / mcp-bound                          | the body's `:frame-binding` (+ MCP affordance) |

  ## Why a pure `.cljc` leaf

  The render layer is CLJS-only (`sidebar.cljs`), but the SIGNAL
  derivation is pure data → data — a variant body + a run-status keyword
  in, the five chip-axis vectors out. Living in `.cljc` lets the JVM test
  corpus (`clojure -M:test`) exercise every projection without booting
  Reagent, mirroring the `sidebar-search` leaf. Each derivation reuses the
  CANONICAL source of truth rather than re-encoding it:

  - fidelity → `re-frame.story.plan/compute-fidelity` (the SAME fn the
    plan compiler stamps `[:world :fidelity]` with);
  - runner-requirement → `re-frame.story.requirements` capability tokens
    + `cheapest-runner` (the SAME registry the plan compiler reads for
    `:required-runner`);
  - frame-binding → `re-frame.story.requirements/frame-bindings` + the
    default.

  No signal is fabricated: a chip appears only when the variant body (or
  the run record) carries the data behind it."
  (:require [re-frame.story.plan         :as plan]
            [re-frame.story.requirements :as requirements]
            [re-frame.story.theme.status :as status]))

;; ===========================================================================
;; AXIS 1 — STATUS
;; ===========================================================================
;;
;; The run-level verdict for the variant. The shipping run record carries
;; one of `requirements`' / `state.tests`' status keywords; spec/018 §12.6
;; names three further signal states the navigation surface MUST keep
;; distinguishable (`:blocked` / `:dirty` / `:redacted`) which ride
;; alongside the run verdict, not folded into it. This leaf surfaces the
;; ONE run-level status keyword; the renderer maps it to a colour/shape via
;; `sidebar` styles, and the spec's extra signal states attach when their
;; data lands (per-variant dirty tracking + redaction markers are
;; spec/018 §7.1 'SHOULD … after … exists' work).

(def status-order
  "Canonical render order for the status signal — the shared
  `theme.status/order` (spec/018 §12.6, the single status vocabulary).
  `:running` is the in-flight transient between `:pending` and a verdict;
  `:error` is a tool/runtime/schema problem DISTINCT from a failed
  expectation (`:fail`); `:blocked` / `:dirty` / `:redacted` are the
  additional signal states the spec names. Derived (not re-encoded) so the
  sidebar can never drift from the tool-wide order (rf2-8fr3yd)."
  status/order)

(def status-labels
  "Pure data → data: the compact chip label per status keyword. Derived
  from `theme.status/descriptors` — the single source of truth for the
  status vocabulary (spec/018 §12.6 — pending / pass / fail / error /
  cannot-run / blocked / dirty / redacted MUST be distinguishable in text,
  not only colour). Single-sourced (not re-encoded) so the sidebar chip
  label can never drift from the canonical status label (rf2-8fr3yd)."
  (into {} (map (fn [[k d]] [k (:label d)])) status/descriptors))

(defn status-signal
  "Pure data → data: the single status chip for a variant given its run
  `status` keyword (one of `re-frame.story.ui.state.tests/test-run-statuses`
  plus the spec/018 §12.6 signal states). Returns `{:axis :status :value
  <kw> :label <string>}`, defaulting an unknown / nil status to `:pending`.
  Distinct from fidelity / world-inputs / runner / frame-binding — a status
  is a VERDICT, never a tier or an input."
  [status]
  (let [v (if (contains? status-labels status) status :pending)]
    {:axis  :status
     :value v
     :label (get status-labels v)}))

;; ===========================================================================
;; AXIS 2 — FIDELITY
;; ===========================================================================
;;
;; The evidence rung(s) a variant's render rests on, computed from its world
;; inputs (NOT typed by the author). Reuses `plan/compute-fidelity` — the
;; SAME projection the plan compiler stamps `[:world :fidelity]` with — so
;; the sidebar chip and the plan can never disagree about what a variant
;; leans on. `:real-setup` > `:db-seed` > `:sub-overrides` (spec/017
;; §View-state subscription overrides — fidelity ladder).

(def fidelity-order
  "Pure data → data: highest-fidelity-first render order for the fidelity
  chips (spec/017 fidelity ladder)."
  [:real-setup :db-seed :sub-overrides])

(def fidelity-labels
  "Pure data → data: the compact chip label per fidelity rung. These are
  fidelity rungs ONLY — args / network / fx-overrides are world inputs and
  live on the world-inputs axis, never here (spec/018 §7.1)."
  {:real-setup    "real setup"
   :db-seed       "db seed"
   :sub-overrides "sub overrides"})

(defn fidelity-signals
  "Pure data → data: the fidelity chips for a variant `body`. Derives the
  world inputs the fidelity ladder reads — setup/script (real-setup),
  `:db-seed`, `:sub-overrides` — and runs them through the canonical
  `plan/compute-fidelity` so the sidebar and the plan agree. Returns a
  vector of `{:axis :fidelity :value <kw> :label <string>}` in
  `fidelity-order`; empty for a bare render-as-mounted variant (no setup,
  seed, or overrides) — which is legitimate, not a defect."
  [body]
  (let [fidelity (plan/compute-fidelity
                   {:setup         (or (:setup body) (:events body))
                    :script        (or (:script body) (:play-script body) (:plays body))
                    :db-seed       (:db-seed body)
                    :sub-overrides (:sub-overrides body)})]
    (into []
          (keep (fn [rung]
                  (when (contains? fidelity rung)
                    {:axis :fidelity :value rung :label (get fidelity-labels rung)})))
          fidelity-order)))

;; ===========================================================================
;; AXIS 3 — WORLD INPUTS
;; ===========================================================================
;;
;; The explicit inputs that shape the variant's world: args, route,
;; network, fx-overrides (spec/018 §7.1 + §12.6). DISTINCT from fidelity —
;; these say WHAT was supplied, not how trustworthy the resulting render is.
;; A chip appears only when the body declares the slot non-empty.

(def world-input-order
  "Pure data → data: render order for the world-input chips (spec/018 §7.1
  — 'world-input chips: args, route, network, fx-overrides')."
  [:args :route :network :fx-overrides])

(def world-input-labels
  "Pure data → data: the compact chip label per world-input slot."
  {:args         "args"
   :route        "route"
   :network      "network"
   :fx-overrides "fx overrides"})

(defn- world-input-present?
  "True iff a world-input slot carries data on the body — a non-empty map
  for `:args` / `:network` / `:fx-overrides`, or any present `:route`
  value. Pure data → data."
  [body slot]
  (let [v (get body slot)]
    (case slot
      :route       (some? v)
      (boolean (seq v)))))

(defn world-input-signals
  "Pure data → data: the world-input chips for a variant `body`. One chip
  per declared world-input slot (`:args` / `:route` / `:network` /
  `:fx-overrides`), in `world-input-order`. Returns a vector of
  `{:axis :world-inputs :value <kw> :label <string>}`; empty when the
  variant declares no explicit world inputs. These are INPUTS — never
  fidelity, never a runner tier (spec/018 §7.1)."
  [body]
  (into []
        (keep (fn [slot]
                (when (world-input-present? body slot)
                  {:axis  :world-inputs
                   :value slot
                   :label (get world-input-labels slot)})))
        world-input-order))

;; ===========================================================================
;; AXIS 4 — RUNNER REQUIREMENT
;; ===========================================================================
;;
;; The cheapest concrete runner KIND that can prove the variant's declared
;; steps + assertions (spec/018 §7.1 — 'runner-requirement chips: headless,
;; hiccup, DOM, browser'; the optional `:cljs-reactive` rung is included
;; because the substrate now advertises a real reactive-counts seam,
;; spec/017 §1a). Derived from the canonical capability-token registry in
;; `re-frame.story.requirements` (the SAME source the plan compiler reads
;; for `:required-runner`), NOT a re-encoded mapping. Runner requirement is
;; a CAPABILITY axis — never a fidelity rung, never a frame binding.

(def runner-requirement-order
  "Pure data → data: cost-ordered render order for the runner-requirement
  chip (cheapest first), mirroring `requirements/concrete-runners`."
  [:headless :hiccup :cljs-reactive :dom :browser])

(def runner-requirement-labels
  "Pure data → data: the compact chip label per runner kind. `:dom` /
  `:browser` keep the spec/018 §7.1 capitalisation intent (DOM, browser);
  `:cljs-reactive` is the deferred-optional rung (spec/018 §7.1)."
  {:headless      "headless"
   :hiccup        "hiccup"
   :cljs-reactive "cljs-reactive"
   :dom           "DOM"
   :browser       "browser"})

(defn- body-script-steps
  "Pure data → data: the variant body's declared script steps, flattened
  across the `:script` / `:play-script` / `:plays` spellings. Each step is
  a tagged vector (or a bare event vector); `requirements/step-tokens`
  reads the head tag, so no coercion is needed for the requirement signal —
  a bare event vector has no recognised step tag and contributes the
  headless-floor empty token set, which is correct."
  [body]
  (cond
    (contains? body :script)
    (vec (:script body))

    (contains? body :plays)
    (into [] (mapcat (fn [p] (or (:script p) (when (vector? p) p) [])))
          (or (:plays body) []))

    (contains? body :play-script)
    (let [ps (:play-script body)]
      (cond
        (vector? ps) (vec ps)
        (map? ps)    (vec (:script ps))
        :else        []))

    :else []))

(defn required-runner-kind
  "Pure data → data: the cheapest concrete runner kind that can prove the
  variant `body`'s declared script steps + setup + terminal assertions, via
  the canonical `requirements` registry. Unions the per-step / per-assertion
  capability tokens (`requirements/required-tokens`) and picks the
  `requirements/cheapest-runner`. Returns a runner-kind keyword (one of
  `runner-requirement-order`), defaulting to `:headless` when no concrete
  runner qualifies (the headless floor — a navigation signal never refuses)."
  [body]
  (let [setup      (or (:setup body) (:events body) [])
        script     (body-script-steps body)
        assertions (or (:assertions body) [])
        tokens     (requirements/required-tokens setup script assertions)]
    (or (requirements/cheapest-runner tokens) :headless)))

(defn runner-requirement-signal
  "Pure data → data: the single runner-requirement chip for a variant
  `body`. Returns `{:axis :runner-requirement :value <runner-kind> :label
  <string>}`. The value is the CHEAPEST runner the variant's declared
  proof surface needs — `:headless` for a pure app-db variant, escalating
  to `:hiccup` / `:cljs-reactive` / `:dom` / `:browser` as the steps /
  assertions demand richer capabilities (spec/018 §7.1). Distinct from
  fidelity and frame-binding."
  [body]
  (let [kind (required-runner-kind body)]
    {:axis  :runner-requirement
     :value kind
     :label (get runner-requirement-labels kind)}))

;; ===========================================================================
;; AXIS 5 — FRAME BINDING
;; ===========================================================================
;;
;; How the run binds to a frame: `:fresh` (a new frame per run, the
;; default) vs `:attached` (a live agent / shared frame). 'MCP-bound' is a
;; UI affordance for an attached frame reached over the Story MCP transport
;; — NOT a third binding value (spec/018 §7.2 — 'the substrate has two
;; frame-binding values, :fresh and :attached'; spec/017 §Runner kinds —
;; MCP is a binding, not a tier). Frame binding is NOT a runner tier.

(def frame-binding-order
  "Pure data → data: render order for the frame-binding chip. `:mcp-bound`
  is the attached-over-MCP affordance, trailing the two substrate values."
  [:fresh :attached :mcp-bound])

(def frame-binding-labels
  "Pure data → data: the compact chip label per frame-binding value."
  {:fresh     "fresh frame"
   :attached  "attached frame"
   :mcp-bound "MCP-bound"})

(defn frame-binding-signal
  "Pure data → data: the frame-binding chip for a variant `body`. Reads the
  body's `:frame-binding` (validated against
  `requirements/frame-bindings` — `#{:fresh :attached}`), defaulting to
  `requirements/default-frame-binding` (`:fresh`). When the body flags
  `:mcp-bound` truthy, the chip surfaces the MCP-bound affordance (still an
  ATTACHED binding underneath — MCP is a binding, not a tier). Returns
  `{:axis :frame-binding :value <kw> :label <string>}`. Always present —
  every variant has a frame binding."
  [body]
  (let [raw   (:frame-binding body)
        bound (if (contains? requirements/frame-bindings raw)
                raw
                requirements/default-frame-binding)
        value (if (:mcp-bound body) :mcp-bound bound)]
    {:axis  :frame-binding
     :value value
     :label (get frame-binding-labels value)}))

;; ===========================================================================
;; COMPOSITE — all five axes, kept distinct
;; ===========================================================================

(def chip-axes
  "Pure data → data: the five chip axes, in render order. Each is its OWN
  signal group with its own label vocabulary (spec/018 §7.1 + §12.6); the
  renderer lays them out adjacent-but-distinct and MUST NOT collapse them
  into one 'fidelity' concept."
  [:status :fidelity :world-inputs :runner-requirement :frame-binding])

(defn variant-signals
  "Pure data → data: the full per-variant signal-chip bundle, keyed by axis
  so the renderer can lay out each as a distinct group. `body` is the raw
  variant body; `status` is the variant's run-status keyword (from the
  shell-state `[:tests :runs vid :status]` slot, or `:pending`). Returns:

      {:status             {:axis … :value … :label …}
       :fidelity           [{…} …]   ; possibly empty
       :world-inputs       [{…} …]   ; possibly empty
       :runner-requirement {:axis … :value … :label …}
       :frame-binding      {:axis … :value … :label …}}

  Status / runner-requirement / frame-binding are always present (single
  chip each); fidelity / world-inputs are vectors that may be empty when
  the variant declares no evidence rung / no explicit world input. Every
  axis stays SEPARATE — the contract is that args / network / fx-overrides /
  browser / MCP-bound are never folded into fidelity (spec/018 §7.1)."
  [body status]
  {:status             (status-signal status)
   :fidelity           (fidelity-signals body)
   :world-inputs       (world-input-signals body)
   :runner-requirement (runner-requirement-signal body)
   :frame-binding      (frame-binding-signal body)})
