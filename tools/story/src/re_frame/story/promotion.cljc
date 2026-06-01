(ns re-frame.story.promotion
  "The run-artifact → variant promotion bridge (NewTestStory rf2-5x1wt.25,
  spec/017-Testing-Story.md §Promotion — Promotion bridge).

  ## What promotion is

  A run artifact (`re-frame.story.artifact` — the `:rf.test/run-artifact`
  shape) is the low-level, data-shaped evidence emitted by a generated
  test, a failed run, a replay, a determinism check, or tool/agent
  exploration (spec/017 §Artifacts). A run artifact is NOT a Story
  variant — it carries no curation, no navigation slot, no `:extends`
  lineage. Most generated failures are throwaway; only a CURATED few
  earn a permanent home in the gallery.

  Promotion is the deliberate act of turning ONE curated run artifact
  into a NAMED Story variant. It splits in two:

  - `materialize-variant-plan` — PURE. Reads a run artifact, projects its
    dispatch program into the four-bucket authoring shape (preconditions →
    `[:world :setup]`, behaviour-under-test → `:script`), compiles it
    through the `.10` variant-plan compiler (read-only), and returns a
    readable, data-shaped normalized plan. Side-effect-free: it registers
    NOTHING. A tool or an author can materialize a plan to READ what a
    promotion WOULD produce, then decide.

  - `promote-run-artifact!` — IMPURE. The ONLY function that registers.
    Builds the same variant body `materialize-variant-plan` compiles and
    writes it into the Story side-table under the explicit `:variant/id`
    via `re-frame.story.registrar/reg-variant*`. No `:variant/id`, no
    registration — there is no auto-register path. A generated failure
    becomes a curated variant only when an author (or an agent acting on
    an author's behalf) makes this call by name.

  ## The projection rule (spec/017 §Promotion)

  Replayable event programs become `:script` when they represent
  behaviour UNDER TEST, and `[:world :setup]` when they are only
  PRECONDITIONS. The artifact's `:event-program` is one ordered program;
  the promotion policy says where the cut between precondition and
  behaviour falls. The default policy treats the whole program as
  behaviour-under-test (`:script`) — the conservative reading, since a
  captured run IS the behaviour the artifact recorded. The caller refines
  the cut via `opts` (`:setup-count` / an explicit `:setup` + `:script`
  partition); see `artifact->variant-body`.

  ## Source-artifact link (spec/017 §Promotion)

  Both the materialized plan and the promoted variant body preserve a
  back-link to the source artifact under the `:run-artifact` slot — the
  same slot `replay-result` stamps on a replay run-result (§Run result),
  so provenance reads the same everywhere. The link is a TRIMMED
  provenance view (`provenance-link`): the replayable + identifying core
  (`:artifact/kind`, `:seed`, `:event-program`, `:fx-decisions`,
  `:network`, `:shrink-path`, `:created-at`, `:source`) WITHOUT the bulky
  captured evidence (`:epoch-tape`, `:trace`, `:result`). `:network`
  rides the core (not the evidence) because replay RE-INSTALLS the
  per-route stubs from it; without it a `:network`-stubbed run cannot be
  re-derived (rf2-tymyh, rf2-87duu). A curated variant can explain where
  it came from and re-derive the run; it does not drag a full epoch tape
  into the registrar side-table.

  ## Purity / elision

  `materialize-variant-plan` + the body/link builders are pure data →
  data and run under `clojure -M:test` with no host. `promote-run-artifact!`
  is the single impure entry; it gates on `re-frame.story.config/enabled?`
  so a production CLJS build short-circuits before touching the side-table
  (mirroring `save-variant`)."
  (:require [re-frame.story.artifact   :as artifact]
            [re-frame.story.config     :as config]
            [re-frame.story.plan       :as plan]
            [re-frame.story.play.runner :as runner]
            [re-frame.story.registrar  :as registrar]))

;; ===========================================================================
;; Source-artifact provenance link
;; ===========================================================================

(def provenance-link-keys
  "The artifact slots a promotion preserves as the source-artifact link
  (spec/017 §Promotion — source-artifact link). The replayable +
  identifying core: enough to explain where a curated variant came from
  AND to re-derive the run. Deliberately EXCLUDES the bulky captured
  evidence (`:epoch-tape`, `:trace`, `:result`) — a registered variant
  body is a curation surface, not an evidence dump; the full evidence
  lives on the original artifact the tool keeps.

  `:network` is in the core because it is load-bearing for re-derivation,
  not bulk: the `:fx-decisions` managed-stub REDIRECT
  (`{:rf.http/managed :rf.http/managed-test-stub}`) survives on its own,
  but `replay-run-artifact` / `with-network-stubs!` RE-INSTALL the actual
  per-route stubs from the artifact's `:network` map (rf2-tymyh,
  spec/017 §The network surface). Drop it and a variant promoted from a
  `:network`-stubbed run carries a link that fail-closes on every managed
  HTTP request (\"no stub matched\") — a DIFFERENT run than the one
  promoted, breaking the docstring's re-derivability promise. Route
  replies are part of run identity (already in `:world :network` /
  artifact `:network`), so the slot is replayable + identifying, not
  evidence."
  #{:artifact/kind :seed :event-program :fx-decisions :network
    :shrink-path :created-at :source})

(defn provenance-link
  "Project a run `artifact` to its source-artifact link — the trimmed
  provenance view a promotion stores under `:run-artifact`. Pure data →
  data.

  Keeps `provenance-link-keys` (the replayable + identifying core) and
  drops the captured-evidence slots so the link stays a compact pointer
  back to the source run rather than a full evidence copy."
  [artifact]
  (select-keys artifact provenance-link-keys))

;; ===========================================================================
;; Program → setup / script partition (the projection rule)
;; ===========================================================================

(defn partition-program
  "Partition an artifact's `:event-program` into `{:setup … :script …}`
  per the promotion projection rule (spec/017 §Promotion). Pure data →
  data — tagged step vectors in, two ordered step vectors out.

  `opts` selects the cut between PRECONDITION (`[:world :setup]`) and
  BEHAVIOUR-UNDER-TEST (`:script`):

  - `:setup` + `:script` — an EXPLICIT partition. Each is a step program
    (or bare-event list); they are coerced through the runner and used
    verbatim, ignoring the artifact's own `:event-program`. The escape
    hatch for a caller that already knows the cut (e.g. a recorder that
    tracked which dispatches were arrange-vs-act).
  - `:setup-count n` — the FIRST `n` steps of the program are
    preconditions (`:setup`); the rest are behaviour (`:script`). A
    negative / oversized `n` clamps to the program bounds.
  - neither — the DEFAULT policy: the whole program is behaviour
    (`:script`), `:setup` is empty. Conservative — a captured run IS the
    behaviour the artifact recorded, so absent a hint nothing is demoted
    to a silent precondition.

  The returned programs are coerced through `runner/coerce-script`, so a
  bare event list lifts to a legal `[:dispatch …]` program and an
  already-tagged program passes through."
  [artifact {:keys [setup script setup-count] :as _opts}]
  (cond
    (or (some? setup) (some? script))
    {:setup  (runner/coerce-script (or setup []))
     :script (runner/coerce-script (or script []))}

    (some? setup-count)
    (let [program (vec (:event-program artifact))
          n       (-> setup-count (max 0) (min (count program)))]
      {:setup  (subvec program 0 n)
       :script (subvec program n)})

    :else
    {:setup  []
     :script (vec (:event-program artifact))}))

;; ===========================================================================
;; Artifact → variant body
;; ===========================================================================

(defn artifact->variant-body
  "Build a Story variant body from a run `artifact`. Pure data → data —
  the shared core of `materialize-variant-plan` (which compiles it) and
  `promote-run-artifact!` (which registers it).

  The body carries the four-bucket authoring vocabulary (spec/017 §Public
  vocabulary): the program's preconditions land on `:setup`, its
  behaviour-under-test on `:script` (per the projection rule — see
  `partition-program`). Both are the PUBLIC bare step-vector spelling the
  variant-plan compiler reads (`:setup` → `[:world :setup]`, `:script` →
  the primary play); an empty partition omits the slot. The source-artifact
  link rides on `:run-artifact` (see `provenance-link`).

  `opts`:
  - `:setup` / `:script` / `:setup-count` — the program partition policy
    (see `partition-program`).
  - `:doc`     — a docstring for the curated variant. Defaults to a
                 generated note that records the promotion provenance.
  - `:extends` — a parent variant id, threaded onto `:extends` so a
                 promoted variant can inherit a story's `:component` /
                 decorators / fixture args.
  - `:tags`    — a tag set for the curated variant.
  - `:args`    — an args map for the curated variant.

  Slots the artifact carries that are not part of the variant surface are
  not copied — the body is a clean authoring surface, the artifact link
  the only provenance."
  ([artifact] (artifact->variant-body artifact nil))
  ([artifact {:keys [doc extends tags args] :as opts}]
   (let [{:keys [setup script]} (partition-program artifact opts)]
     (cond-> {:run-artifact (provenance-link artifact)
              :doc          (or doc
                                (str "Promoted from a "
                                     (:artifact/kind artifact :rf.test/run-artifact)
                                     " run artifact (spec/017 §Promotion)."))}
       (seq setup)    (assoc :setup setup)
       (seq script)   (assoc :script script)
       (some? extends) (assoc :extends extends)
       (some? tags)   (assoc :tags tags)
       (some? args)   (assoc :args args)))))

;; ===========================================================================
;; materialize-variant-plan — PURE, registers NOTHING
;; ===========================================================================

(defn materialize-variant-plan
  "Project a run `artifact` into a readable normalized variant plan
  (spec/017 §Promotion — Promotion bridge). Pure / side-effect-free — it
  registers NOTHING. Use it to READ what a promotion would produce.

  Pipeline:
  1. Build a variant body from the artifact (`artifact->variant-body`) —
     the program's preconditions on `:setup`, behaviour on `:script`, the
     source-artifact link on `:run-artifact`.
  2. Compile the body through the `.10` variant-plan compiler
     (`re-frame.story.plan/variant-plan`) — called READ-ONLY as an inline
     map target, resolving `:extends`, lowering the four-bucket
     vocabulary, substituting `[:arg …]`, and emitting the normalized
     `:world` / `:script` / `:expect` plan.
  3. Re-attach the `:run-artifact` source-artifact link to the plan (the
     compiler reads only known variant slots, so the link must be carried
     across explicitly — promotion's invariant is that the plan can
     always explain its provenance).

  `opts` (all optional):
  - `:setup` / `:script` / `:setup-count` — program partition policy
    (`partition-program`).
  - `:variant/id` — the plan's `:variant/id`. Materialization does NOT
    require one (the plan is readable without a name); when supplied it
    rides onto the inline plan target so the compiled plan is named.
  - `:doc` / `:extends` / `:tags` / `:args` — variant-body slots
    (`artifact->variant-body`).
  - `:lookup` / `:view-lookup` / `:validator-fns` — threaded to
    `variant-plan` for `:extends` parent resolution + view-args schema
    validation (defaults: the Story side-table + framework `:view`
    registrar).

  Returns the normalized plan map with a `:run-artifact` source link.
  FAILS with the compiler's structured `:rf.error/story-*` ex-info on an
  unknown `:extends` parent, an `:extends` cycle, a missing `[:arg …]`,
  or view-args that violate the view schema."
  ([artifact] (materialize-variant-plan artifact nil))
  ([artifact {:keys [lookup view-lookup validator-fns] :as opts}]
   (let [variant-id   (:variant/id opts)
         body         (artifact->variant-body artifact opts)
         target       (cond-> body
                        (some? variant-id) (assoc :variant/id variant-id))
         compile-opts (cond-> {}
                        (some? lookup)        (assoc :lookup lookup)
                        (some? view-lookup)   (assoc :view-lookup view-lookup)
                        (some? validator-fns) (assoc :validator-fns validator-fns))
         plan         (plan/variant-plan target compile-opts)]
     ;; The compiler reads only known variant slots, so the source link
     ;; does not survive into the plan on its own — re-attach it so the
     ;; materialized plan ALWAYS carries its provenance (spec/017
     ;; §Promotion — source-artifact link).
     (assoc plan :run-artifact (provenance-link artifact)))))

;; ===========================================================================
;; promote-run-artifact! — IMPURE, the ONLY registering path
;; ===========================================================================

(defn promote-run-artifact!
  "Promote a curated run `artifact` into a NAMED Story variant (spec/017
  §Promotion — Promotion bridge). This is the ONLY function in the bridge
  that REGISTERS — `materialize-variant-plan` is pure and registers
  nothing. There is no auto-register path: a generated failure becomes a
  curated variant ONLY through this explicit, named call.

  `opts` MUST carry `:variant/id` — the keyword id of the variant to
  register (e.g. `:story.checkout/regression-042`). A missing id throws
  `:rf.error/story-promote-no-id`: registration without a name is exactly
  the auto-register the projection rule forbids.

  `opts` MAY also carry the `artifact->variant-body` slots (`:setup` /
  `:script` / `:setup-count` / `:doc` / `:extends` / `:tags` / `:args`).

  Builds the same variant body `materialize-variant-plan` compiles —
  preconditions on `:setup`, behaviour on `:script`, the source-artifact
  link on `:run-artifact` — and writes it into the Story side-table via
  `re-frame.story.registrar/reg-variant*` (the same write path the
  `reg-variant` macro expands to: shape validation, `:extends`
  resolution, source-coord stamping). The registered variant is
  indistinguishable from a hand-authored one except for its
  `:run-artifact` provenance slot.

  Production CLJS builds (`config/enabled?` false) short-circuit before
  the write and return nil, mirroring `save-variant`.

  Returns the registered variant id on success (the value
  `reg-variant*` returns)."
  [artifact opts]
  (let [variant-id (:variant/id opts)]
    (when (nil? variant-id)
      (throw (ex-info ":rf.error/story-promote-no-id"
                      {:rf.error/id :rf.error/story-promote-no-id
                       :where       'rf.story/promote-run-artifact!
                       :recovery    :supply-variant-id
                       :reason      (str "re-frame2-story: promote-run-artifact! "
                                         "requires an explicit :variant/id — a run "
                                         "artifact is never auto-registered (spec/017 "
                                         "§Promotion).")
                       :artifact    (provenance-link artifact)})))
    (when config/enabled?
      (let [body (artifact->variant-body artifact opts)]
        (registrar/reg-variant* variant-id body)))))
