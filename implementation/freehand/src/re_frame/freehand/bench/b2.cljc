(ns re-frame.freehand.bench.b2
  "B2 — capability elision, counted exactly on a mass mount.

  D021's second required workload: a *cells-shaped mount storm* of many
  boundaries, compared under the `:interpreted-vs-compiled` baseline, to
  measure the one thing compilation buys that interpretation cannot — a
  view PROVEN to carry no reactive site OMITS the reactive ViewCell shell,
  and a mount of a thousand such leaves omits a thousand shells. The
  omitted-cell count is the value, and it is GATED on an exact integer.

  ## Two arms, one storm

  The interpreted shell ALWAYS observes frame context: it has no finite
  grammar and cannot prove a body sub-free, so it keeps every ViewCell.
  The compiled tier earns its elision by proof, and B2 is that proof
  counted at scale — so it needs both an arm that elides and an arm that
  does not, or a count of zero omissions and a count of all of them would
  be indistinguishable from a broken oracle.

    - the **capability-free arm** is inert leaves — no `sub`, no committed
      event handler, no `(frame)` read, no `dispatch-fn`, and no host
      capability. Every boundary omits its ViewCell, so the arm's omitted
      count is its whole boundary count.
    - the **three-read arm** is reactive boundaries carrying three
      reactive reads each — subscriptions and a committed event handler.
      Every boundary observes context and RETAINS its ViewCell, so the
      arm omits none.

  A gate over the free arm alone could pass by eliding nothing and
  counting nothing; the read arm is the discrimination that a zero is a
  real absence and not a mis-targeted probe. It is D021's
  `sub/event/presence`-free-versus-loaded contrast, made countable.

  ## What gates and what does not

  The split is [[re-frame.freehand.bench.measure]]'s, and B2 sits exactly
  on the line D021 draws for it: *elision counts are DETERMINISTIC gates,
  not timing evidence.*

    - `:B2/free-arm-omitted-cells` and `:B2/read-arm-omitted-cells`
      observe a `:count`. They are deterministic properties — a static
      function of each declaration's analyzed sites, reproducible on any
      host — they gate, and a violation exits non-zero. The free arm's
      count is an EXACT integer, never a range; the read arm's is exactly
      zero.
    - the mount DECOMPOSITION and the RETAINED-object counts ride the
      `:fixture`, published verbatim with the record's full provenance.
      They are the per-declaration static facts behind the two totals —
      each type's ViewCell verdict, its capability roster and its reactive
      site cardinalities — so a reader sees not only THAT the free arm
      omitted 2600 shells but WHICH declarations earned the verdict.

  ## Static, and honest about it

  The count is read off `re-frame.freehand/manifest` — the analyzer's
  verdict, produced at compile time and carried on the descriptor as data.
  Mounting nothing is deliberate: the verdict a mount would act on is
  already decided before the mount, and reading it as data is how F3d's
  elision proof is stated in the first place (`FH-DIAG-002`). What B2 adds
  is the count AT SCALE — the exact number of ViewCell shells a mount of
  this roster omits, `verdict × mount count`, summed over the plan.

  The mount TIME distribution and the live-heap retained-object reading
  that D021's fuller B2 row also asks for are a different instrument: they
  need the compiled tier's browser lowering (rf2-drpa3.113), which mounts
  a compiled boundary in a real root, and join this workload with that
  slice. Until then B2 measures the exact quantity that IS decidable
  without a mount, and does not sell a static count as a runtime one.

  ## A small case beside the stress case

  D021: *\"Include at least one small realistic case beside each stress
  case so optimization is not tuned only to synthetic extremes.\"* Hence
  two registered workloads over one roster and one `:run`, differing only
  in the `:scale` fixture parameter — mount counts are fixture parameters,
  not language constants.

  Normative owner: `docs/design/freehand/decisions/`
  `D021-performance-budgets-and-release-evidence.md`."
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.bench :as bench]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The capability-free arm — inert leaves, so every ViewCell is omitted
;; ---------------------------------------------------------------------------

(v/defview free-label
  "Pure text under one element — the smallest elidable leaf."
  {:compiled true}
  [{:keys [text]}]
  [:span.label text])

(v/defview free-card
  "Structure forwarding children beneath an owned heading. Forwarding
  children is not a reactive read, so the shell stays elided."
  {:compiled true}
  [{:keys [title children]}]
  [:section.card [:h3.card__title title] children])

(v/defview free-list
  "A keyed list. A `for` run is finite structure, not a reactive site, so
  a list of static rows omits the ViewCell like any other inert body."
  {:compiled true}
  [{:keys [items]}]
  [:ul.list
   (for [i items]
     [:li.item {:key i} i])])

(v/defview free-nested
  "Nested plain elements — depth is not a reactive read either, so the
  deepest inert body is still elidable."
  {:compiled true}
  [{:keys [text]}]
  [:div.host [:div.inner [:span.leaf text]]])

;; ---------------------------------------------------------------------------
;; The three-read arm — three reactive reads each, so every ViewCell is kept
;; ---------------------------------------------------------------------------

(v/defview read-panel
  "Two subscriptions and a committed event handler — three reactive reads,
  one retained ViewCell."
  {:compiled true}
  [{:keys [id]}]
  [:section.panel
   [:output.total (v/sub [:panel/total id])]
   [:output.count (v/sub [:panel/count id])]
   [:button.act {:on-click [:panel/act id]} "act"]])

(v/defview read-row
  "One subscription and two committed event handlers — three reactive
  reads over one boundary. Many sites, one shell."
  {:compiled true}
  [{:keys [id]}]
  [:tr.row
   [:td.val (v/sub [:row/value id])]
   [:td [:button.up {:on-click [:row/up id]} "+"]]
   [:td [:button.down {:on-click [:row/down id]} "-"]]])

(v/defview read-widget
  "Three subscriptions — three reactive reads, no events, still one
  retained ViewCell. A view is reactive because it READS state, however
  it reads it."
  {:compiled true}
  [{:keys [id]}]
  [:div.widget
   [:span.a (v/sub [:widget/a id])]
   [:span.b (v/sub [:widget/b id])]
   [:span.c (v/sub [:widget/c id])]])

;; ---------------------------------------------------------------------------
;; The roster and the mount plan
;; ---------------------------------------------------------------------------

(def ^:private free-views
  "The capability-free arm's declaration types. Every one is proven
  elidable by its manifest — a claim the workload re-checks each run
  rather than trusts this vector for."
  [free-label free-card free-list free-nested])

(def ^:private read-views
  "The three-read arm's declaration types. Every one carries three
  reactive reads and keeps its ViewCell."
  [read-panel read-row read-widget])

(defn- plan
  "A mount plan for `views` at `scale`: `[[view mount-count] …]`, each type
  mounted `scale` times. Scale is a fixture parameter — the storm's size
  is a size, not a language constant."
  [views scale]
  (mapv (fn [view] [view scale]) views))

(defn- verdict
  [view]
  (:view-cell (v/manifest view)))

(defn- omitted
  "The exact number of boundaries in `mount-plan` whose compiled lowering
  OMITS the ViewCell — each type's manifest verdict times its mount count.
  Read off the manifest every call, so a type that stopped eliding drops
  its whole mount count out of this total and reds the gate."
  [mount-plan]
  (reduce (fn [n [view mounts]]
            (+ n (if (= :elided (verdict view)) mounts 0)))
          0
          mount-plan))

(defn expected-omitted
  "The omitted-cell count the free arm's roster predicts for `scale`,
  stated as arithmetic: every one of its types is elidable, so a mount of
  `scale` each omits `(count free-views) × scale` shells. A gate against a
  WRITTEN expectation, not against whatever the walk happened to produce."
  [scale]
  (* (count free-views) scale))

;; ---------------------------------------------------------------------------
;; The mount decomposition — the static facts behind the two totals
;; ---------------------------------------------------------------------------

(defn- boundary
  "One declaration's line in the decomposition: the verdict it earned, the
  capabilities that earned it, its reactive site cardinalities, and how
  many times the plan mounts it."
  [[view mounts]]
  (let [m (v/manifest view)]
    {:view-id       (:view-id m)
     :view-cell     (:view-cell m)
     :capabilities  (:capabilities m)
     :subscriptions (count (:subscriptions m))
     :events        (count (:events m))
     :mounts        mounts}))

(defn- arm-decomposition
  "An arm's published decomposition: its per-declaration lines, its total
  boundary count, the ViewCell shells the mount OMITS, and the ones it
  RETAINS — the retained-object count, as the static count of reactive
  shells a mount of this arm keeps alive."
  [mount-plan]
  (let [lines     (mapv boundary mount-plan)
        total     (reduce + 0 (map :mounts lines))
        omitted-n (omitted mount-plan)]
    {:boundaries          total
     :omitted-view-cells  omitted-n
     :retained-view-cells (- total omitted-n)
     :decomposition       lines}))

;; ---------------------------------------------------------------------------
;; One iteration
;; ---------------------------------------------------------------------------

(defn observe
  "Run one B2 iteration over `fixture` and answer its observations: the
  exact omitted-ViewCell count of each arm, read off the manifests the
  plan's declarations carry."
  [{:keys [free-plan read-plan]}]
  {:B2/free-arm-omitted-cells (omitted free-plan)
   :B2/read-arm-omitted-cells (omitted read-plan)})

;; ---------------------------------------------------------------------------
;; The workloads
;; ---------------------------------------------------------------------------

(defn workload
  "Build the B2 workload for `scale`, under `id` and `sampling`.

  The fixture carries the mount plans the `:run` reads and, published
  beside them, the mount decomposition and the retained-object counts D021
  asks B2 to publish — every one a per-declaration static fact, so the two
  gated totals trace back to the declarations that produced them."
  [{:keys [id doc scale sampling]}]
  (let [free-plan (plan free-views scale)
        read-plan (plan read-views scale)]
    {:id       id
     :doc      doc
     :fixture  {:scale     scale
                :free-plan free-plan
                :read-plan read-plan
                :free-arm  (arm-decomposition free-plan)
                :read-arm  (arm-decomposition read-plan)}
     :baseline {:kind      :interpreted-vs-compiled
                :reference {:arm  :interpreted
                            :note (str "the interpreted lowering retains every ViewCell; the "
                                       "omitted count is the shells the compiled tier proves "
                                       "safe to drop that interpretation cannot")}}
     :sampling sampling
     :measurements
     [{:id         :B2/free-arm-omitted-cells
       :doc        "the exact ViewCell shells a mount of the capability-free arm omits"
       :observable :count
       :expect     (expected-omitted scale)}
      {:id         :B2/read-arm-omitted-cells
       :doc        "the ViewCell shells the three-read arm omits — exactly zero, it is all reactive"
       :observable :count
       :expect     0}]
     :run observe}))

(def workloads
  "B2's registered workloads: the mount storm D021 names, and the small
  realistic case it requires beside it. `:scale` is per-type mount count,
  so the free arm's stress case omits `4 × 650 = 2600` ViewCell shells —
  in the cells-shaped band D021's B2 row names."
  [(workload {:id       :B2/mass-mount
              :doc      "a cells-shaped mount storm: a capability-free arm and a three-read arm"
              :scale    650
              :sampling {:warmup 5 :samples 40}})
   (workload {:id       :B2/small-mount
              :doc      "the same roster at a realistic small size — the control on the storm"
              :scale    2
              :sampling {:warmup 5 :samples 40}})])

(def registered
  "Registering at load: requiring this namespace puts B2 in the standing
  evidence suite `clojure -M:bench` runs."
  (mapv bench/register! workloads))
