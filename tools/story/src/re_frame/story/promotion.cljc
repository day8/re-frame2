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
  (:require [re-frame.story.args       :as rf.story.args]
            [re-frame.story.artifact   :as rf.story.artifact]
            [re-frame.story.config     :as rf.story.config]
            [re-frame.story.plan       :as rf.story.plan]
            [re-frame.story.play       :as rf.story.play]
            [re-frame.story.play.runner :as rf.story.play.runner]
            [re-frame.story.registrar  :as rf.story.registrar]))

;; ===========================================================================
;; The source variant's intent (rf2-5vmog)
;; ===========================================================================
;;
;; A promoted regression must be able to FAIL for the reason its source
;; failed. Two things carried that reason and neither survived:
;;
;;   - DECLARATIVE expectations authored beside the program — the source's
;;     terminal `:assertions` (and `:checks`). The body used to copy neither,
;;     so a source that ran `:fail` promoted into a variant that ran `:pass`
;;     with zero assertions.
;;   - `:script` steps that are not dispatches — clicks, typing, waits and
;;     `[:assert …]` checkpoints. Test mode captures a run as the flat
;;     DISPATCH-ONLY projection of its program (`variant-play-events`), so
;;     those steps never reached the artifact at all.
;;
;; A check named in the source's `:compose` carries that reason too, and it was
;; dropped the same way (rf2-6h2z3): `:compose` is child-only, so neither the
;; artifact nor an `:extends` of the source recovers it. Promotion reads the
;; check ids off the source's compiled plan instead of its raw body.
;;
;; Ordinary `:extends` inheritance is deliberately NOT the fix: terminal
;; assertions, `:script` and `:compose` stay child-only, a coherent reuse rule. Promotion
;; is the one transformation that must preserve intent, so it carries them
;; explicitly, and only from the variant the artifact records as its source.

(defn source-variant-id
  "The id of the variant a run `artifact` was captured from, or nil. Pure
  data → data.

  Read from the artifact's own record of its run, never from promotion
  `opts`: `[:source :variant/id]` (stamped by
  `re-frame.story.determinism/->artifact` from the compiled plan) or
  `[:result :variant/id]` (the run-result identity slot a Test-mode capture
  carries). An `:extends` parent is NOT a source — a promoted variant may
  extend anything, and a parent's terminal assertions are its own."
  [artifact]
  (or (get-in artifact [:source :variant/id])
      (get-in artifact [:result :variant/id])))

(defn- composed-check-ids
  "The ids `plan`'s `:compose` resolved to checks, in declared order. Read off
  the compiler's own classification (`[:explain :compose]`), which tries the
  fragment registry first, so an id that resolved to a fragment is never
  taken for a check."
  [plan]
  (into [] (keep (fn [{:keys [kind id]}] (when (= :check kind) id)))
        (get-in plan [:explain :compose])))

(defn source-expectations
  "The declarative expectations a promotion carries from its source variant,
  as `{:assertions … :checks …}` with empty slots omitted. Pure data → data.

  `source-body` is the source's registered body. Its own `:assertions` are the
  load-bearing slot: terminal assertions never inherit through `:extends`, so
  without them a promoted regression has nothing to fail on.

  `:checks` are the check ids the source's verdict depends on, as the plan
  compiler resolved them into `source-plan` (the source's compiled plan):
  inherited and own root→child, then the ones named in its `:compose`, each id
  once. `:compose` is child-only, so no `:extends` recovers a composed check.
  A promotion that `:extends` its source (`extends-source?`) inherits the rest
  through the source's chain, so it carries only the source's own `:checks`
  and its composed ones; any other promotion carries the whole resolved list.
  Without a `source-plan` (the one-argument arity, or a source that does not
  compile here) only the source's own `:checks` ride."
  ([source-body] (source-expectations source-body nil false))
  ([source-body source-plan extends-source?]
   (let [own    (:checks source-body)
         checks (cond
                  (nil? source-plan) own
                  extends-source?    (distinct (concat own (composed-check-ids source-plan)))
                  :else              (get-in source-plan [:expect :checks]))]
     (cond-> {}
       (seq (:assertions source-body)) (assoc :assertions (vec (:assertions source-body)))
       (seq checks)                    (assoc :checks (vec checks))))))

(defn- dispatch-step?
  [step]
  (contains? #{:dispatch :dispatch-sync} (rf.story.play.runner/step-type step)))

(defn- step-events
  [steps]
  (into [] (keep rf.story.play.runner/step-event) steps))

(defn- unless-plan-fails
  "Call `f`, answering nil when it fails to construct a variant plan. Only a
  plan-construction failure is swallowed, exactly as `variant-play-events`
  does; anything else is a real error."
  [f]
  (try (f)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
         (if (= 'rf.story/variant-plan (:where (ex-data e)))
           nil
           (throw e)))))

(defn- recorded-run-opts
  "The run inputs a Test-mode capture recorded on `artifact` under
  `[:source :run-opts]` (see `re-frame.story.ui.promotion/result->artifact`):
  the `:active-modes` / `:cell-overrides` its source ran with, or nil."
  [artifact]
  (get-in artifact [:source :run-opts]))

(defn- source-steps
  "The step program `source-id` executes under `run-opts`
  (`rf.story.play/variant-play-steps`, the program Test mode projects its
  capture from), or nil when the source's plan does not compile here."
  [source-id run-opts]
  (unless-plan-fails #(rf.story.play/variant-play-steps source-id run-opts)))

(defn- source-plan
  "`source-id`'s compiled plan, folded with the arg layers a run of it under
  `run-opts` compiles with (as `variant-play-steps` does), or nil when it does
  not compile here. `source-expectations` reads the compiler's resolution of
  its checks off it."
  [source-id run-opts]
  (unless-plan-fails
    #(rf.story.plan/variant-plan
       source-id {:run-args (rf.story.args/run-arg-layers source-id run-opts)})))

(defn- run-input-args
  "The args the run inputs recorded on a capture supplied to `source-id`, at
  the values the run resolved (rf2-rky08): each top-level key an active mode
  or a cell override names, read off `plan`, the source compiled with those
  inputs (`source-plan`). nil when nothing was recorded or `plan` is nil.

  A promotion carries these as the body's own `:args`. The dialog's default
  draft `:extends` the source, and `:setup` is inherited context whose
  `[:arg]` placeholders re-substitute when the promoted variant compiles, so
  without them it reads the source's defaults, not the input that ran. The
  resolved value is carried rather than the raw input because the promoted
  variant runs with neither the modes nor the overrides, so the variant layer
  must hold what the full precedence fold produced."
  [source-id run-opts plan]
  (when (and plan (seq run-opts))
    (let [{:keys [pre post]} (rf.story.args/run-arg-layers source-id run-opts)
          ;; The two per-run layers: the active modes' args close `:pre`, and
          ;; the cell overrides are `:post`.
          supplied (into #{} (mapcat keys) (cons (peek pre) post))]
      (not-empty (select-keys (get-in plan [:world :args]) supplied)))))

(defn retained-program
  "The event program a promotion builds from: the source variant's FULL step
  program when `artifact` carries only its dispatch-only shadow, else the
  artifact's own `:event-program`.

  The shadow test is exact, so a program that is genuinely different — a
  generated, shrunk or recorded run — is never replaced: every artifact step
  is a dispatch, the source program carries at least one step that is not,
  and the two dispatch sequences are equal. The source program is compiled
  with the run inputs the artifact recorded, so a dispatch whose `[:arg]` a
  mode or cell override supplied still matches its source (rf2-cml0h). Reads
  the Story side-table; registers nothing."
  [artifact source-id]
  (let [program (vec (:event-program artifact))]
    (or (when (and source-id (every? dispatch-step? program))
          (let [steps (source-steps source-id (recorded-run-opts artifact))]
            (when (and (some (complement dispatch-step?) steps)
                       (= (step-events program) (step-events steps)))
              (vec steps))))
        program)))

(defn source-program
  "The step program registered variant `source-id` executes (see
  `source-steps`), or nil when `source-id` names no registered variant or its
  plan does not compile here. A variant with no `:script` yields `[]`.

  `run-opts` is the `run-variant` opts map of the run being captured; its
  `:active-modes` and `:cell-overrides` compile in as they did for the run
  (rf2-cml0h). Without it only the ambient arg layers apply.

  Test mode captures this for a run whose script dispatched nothing, which
  has no dispatch-only projection to stand in for it (rf2-vgthk). Reads the
  Story side-table; registers nothing."
  ([source-id] (source-program source-id nil))
  ([source-id run-opts]
   (when (and source-id (rf.story.registrar/handler-meta :variant source-id))
     (source-steps source-id run-opts))))

;; ===========================================================================
;; Runnable reproducibility slots (rf2-vf8es)
;; ===========================================================================
;;
;; A promoted variant must RUN to the same result as the source artifact —
;; the docstring promises it is "indistinguishable from a hand-authored one
;; except for its :run-artifact provenance slot." For a `:network`-stubbed
;; or `:fx-override`-bearing run that promise was FALSE: the variant body
;; carried only the program (`:setup`/`:script`) + the provenance link, so
;; the registered variant's `[:world :network]` / `[:world :frame
;; :fx-overrides]` were EMPTY. Run normally, a managed HTTP request
;; fail-closed ("no stub matched") and any fx-decision redirect was gone —
;; a SILENT fidelity gap (the gallery cell renders a degraded run).
;;
;; rf2-87duu preserved `:network` on the provenance LINK so
;; `replay-run-artifact` could re-derive the run from the artifact. That is
;; a DIFFERENT path: it replays the artifact, not the registered variant.
;; This fix lifts the runnable inputs onto the variant BODY itself:
;;
;;   - the artifact's `:network` route map → the body's `:network` slot
;;     (the plan compiler keeps it at `[:world :network]` and lowers it to
;;     the managed-stub fx-override the runner installs);
;;   - the artifact's `:fx-decisions` → the body's `:fx-overrides` slot
;;     (the same `{fx-id override}` shape the per-frame `:fx-overrides`
;;     takes — Spec 002 §`:fx-overrides`).
;;
;; THE MANAGED-STUB REDIRECT OVERLAP. A `:network`-stubbed artifact's
;; `:fx-decisions` ALSO carries the lowered redirect `{:rf.http/managed
;; :rf.http/managed-test-stub}` (the plan lowered `:network` to it at
;; capture time). The body's `:network` slot re-derives that SAME redirect
;; via `rf.story.plan/lower-network`, and the compiler's `check-network-fx-conflict!`
;; HARD-FAILS when both `:network` and an explicit `:fx-overrides` target
;; `:rf.http/managed`. So when `:network` is present we DROP `:rf.http/managed`
;; from the lifted `:fx-overrides` — `:network` owns that key. Any OTHER
;; fx-decision (a non-HTTP override) still rides `:fx-overrides`.

(def managed-fx-id
  "Re-export of the managed-HTTP fx id (`rf.story.plan/managed-fx-id`,
  `:rf.http/managed`) the `:network` slot owns. A `:network`-stubbed
  artifact's `:fx-decisions` carries the lowered redirect for this id; we
  drop it from the lifted `:fx-overrides` so it does not conflict with the
  body's `:network` slot (rf2-vf8es)."
  rf.story.plan/managed-fx-id)

(defn lift-fx-overrides
  "Project an artifact's `:fx-decisions` onto a variant body's
  `:fx-overrides` slot (rf2-vf8es). Pure data → data.

  Drops the `:rf.http/managed` redirect when `network?` is true: the
  body's `:network` slot re-derives that redirect through
  `rf.story.plan/lower-network`, and carrying it on BOTH surfaces is a hard
  `:rf.error/story-network-fx-conflict`. Returns nil when nothing
  survives (so the caller omits the slot rather than emitting an empty
  map)."
  [fx-decisions network?]
  (let [fx (cond-> (or fx-decisions {})
             network? (dissoc managed-fx-id))]
    (when (seq fx) fx)))

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

  The returned programs are coerced through `rf.story.play.runner/coerce-script`, so a
  bare event list lifts to a legal `[:dispatch …]` program and an
  already-tagged program passes through."
  [artifact {:keys [setup script setup-count] :as _opts}]
  (cond
    (or (some? setup) (some? script))
    {:setup  (rf.story.play.runner/coerce-script (or setup []))
     :script (rf.story.play.runner/coerce-script (or script []))}

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

  The RUNNABLE reproducibility inputs are lifted onto the body too
  (rf2-vf8es) so a promoted variant runs the SAME as the source artifact,
  not just the artifact's replay:
  - `:network` — the artifact's per-route HTTP reply map, onto the body's
    `:network` slot (compiler keeps it at `[:world :network]` and lowers
    it to the managed-stub fx-override the runner installs);
  - `:fx-overrides` — the artifact's `:fx-decisions`, onto the body's
    `:fx-overrides` slot. The `:rf.http/managed` redirect is dropped when
    `:network` is present (the `:network` slot owns it — see
    `lift-fx-overrides`), so the two surfaces never conflict.

  The SOURCE VARIANT'S INTENT is carried too (rf2-5vmog), so a promoted
  regression fails for the reason its source failed. When the artifact
  records the variant it was captured from (`source-variant-id`) and that
  variant is registered:
  - its own terminal `:assertions` and the check ids its verdict depends on,
    composed ones included, land on the body (`source-expectations`, reading
    the source's compiled plan) — `:extends` alone would drop the assertions
    and every composed check;
  - a dispatch-only capture is replaced by the source's full step program,
    so clicks, typing, waits and `[:assert …]` checkpoints survive
    (`retained-program`).
  Both compile the source with the run inputs a Test-mode capture recorded
  (`[:source :run-opts]`), so an `[:arg]` a mode or cell override supplied
  resolves to the value that ran (rf2-cml0h). Those inputs also ride the
  body's `:args`, at the values the run resolved (`run-input-args`), so the
  context a promotion inherits by `:extends`-ing its source — its `:setup`
  above all — substitutes the input that ran rather than the source's
  default (rf2-rky08).
  An artifact with no registered source is promoted exactly as captured.
  Registers nothing; the source is read from the Story side-table.

  `opts`:
  - `:setup` / `:script` / `:setup-count` — the program partition policy
    (see `partition-program`).
  - `:doc`     — a docstring for the curated variant. Defaults to a
                 generated note that records the promotion provenance.
  - `:extends` — a parent variant id, threaded onto `:extends` so a
                 promoted variant can inherit a story's `:component` /
                 decorators / fixture args.
  - `:tags`    — a tag set for the curated variant.
  - `:args`    — an args map for the curated variant, deep-merged over any
                 carried run inputs, so an explicit arg wins.

  Slots the artifact carries that are not part of the variant surface are
  not copied — the body is a clean authoring surface, the artifact link
  the only provenance."
  ([artifact] (artifact->variant-body artifact nil))
  ([artifact {:keys [doc extends tags args] :as opts}]
   (let [source-id     (source-variant-id artifact)
         source-body   (when source-id
                         (rf.story.registrar/handler-meta :variant source-id))
         run-opts      (recorded-run-opts artifact)
         plan          (when source-body (source-plan source-id run-opts))
         program       (retained-program artifact (when source-body source-id))
         {:keys [setup script]} (partition-program
                                  (assoc artifact :event-program program) opts)
         {:keys [assertions checks]} (source-expectations
                                       source-body
                                       plan
                                       (and (some? extends) (= extends source-id)))
         run-args      (run-input-args source-id run-opts plan)
         args          (if run-args (rf.story.args/deep-merge run-args args) args)
         network       (:network artifact)
         has-network?  (boolean (seq network))
         fx-overrides  (lift-fx-overrides (:fx-decisions artifact) has-network?)]
     (cond-> {:run-artifact (provenance-link artifact)
              :doc          (or doc
                                (str "Promoted from a "
                                     (:artifact/kind artifact :rf.test/run-artifact)
                                     " run artifact (spec/017 §Promotion)."))}
       (seq setup)    (assoc :setup setup)
       (seq script)   (assoc :script script)
       (seq checks)   (assoc :checks checks)
       (seq assertions) (assoc :assertions assertions)
       has-network?   (assoc :network network)
       (some? fx-overrides) (assoc :fx-overrides fx-overrides)
       (some? extends) (assoc :extends extends)
       (some? tags)   (assoc :tags tags)
       (some? args)   (assoc :args args)))))

;; ===========================================================================
;; The artifact precondition (rf2-vgthk)
;; ===========================================================================

(defn- refuse-non-artifact!
  "Throw `:rf.error/story-promote-no-artifact` unless `artifact` is a
  `:rf.test/run-artifact`, naming `where` as the refusing entry point. A body
  built from nil has no program and no source to carry expectations from, so
  it would register a hollow variant that runs `:pass` with zero assertions."
  [artifact where]
  (when-not (rf.story.artifact/run-artifact? artifact)
    (throw (ex-info ":rf.error/story-promote-no-artifact"
                    {:rf.error/id :rf.error/story-promote-no-artifact
                     :where       where
                     :recovery    :supply-run-artifact
                     :reason      (str "re-frame2-story: " where " requires a "
                                       ":rf.test/run-artifact — a nil or "
                                       "non-artifact has no program to promote "
                                       "(spec/017 §Promotion).")}))))

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
  FAILS with `:rf.error/story-promote-no-artifact` on a nil or non-artifact
  (rf2-vgthk), and with the compiler's structured `:rf.error/story-*` ex-info
  on an unknown `:extends` parent, an `:extends` cycle, a missing
  `[:arg …]`, or view-args that violate the view schema."
  ([artifact] (materialize-variant-plan artifact nil))
  ([artifact {:keys [lookup view-lookup validator-fns] :as opts}]
   (refuse-non-artifact! artifact 'rf.story/materialize-variant-plan)
   (let [variant-id   (:variant/id opts)
         body         (artifact->variant-body artifact opts)
         target       (cond-> body
                        (some? variant-id) (assoc :variant/id variant-id))
         compile-opts (cond-> {}
                        (some? lookup)        (assoc :lookup lookup)
                        (some? view-lookup)   (assoc :view-lookup view-lookup)
                        (some? validator-fns) (assoc :validator-fns validator-fns))
         plan         (rf.story.plan/variant-plan target compile-opts)]
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

  `artifact` MUST be a `:rf.test/run-artifact`. A nil or non-artifact throws
  `:rf.error/story-promote-no-artifact` and registers nothing (rf2-vgthk):
  the body it would build has no program and no source to carry
  expectations from, so it would pass with zero assertions.

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

  Production CLJS builds (`rf.story.config/enabled?` false) short-circuit before
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
    (refuse-non-artifact! artifact 'rf.story/promote-run-artifact!)
    (when rf.story.config/enabled?
      (let [body (artifact->variant-body artifact opts)]
        (rf.story.registrar/reg-variant* variant-id body)))))
