(ns re-frame.story.artifact
  "The `:rf.test/run-artifact` schema + `replay-run-artifact` — a
  serializable record of a Story run, and the function that replays it
  into a fresh frame (NewTestStory rf2-5x1wt.7, spec/017-Testing-Story.md
  §Run artifact and replay + §Artifacts — Run artifact).

  ## What a run artifact is

  A run artifact is the low-level, data-shaped evidence emitted by
  generated tests, failed runs, replay, determinism checks, or tool/agent
  exploration (spec/017 §Artifacts). It is NOT a Story variant: it carries
  no curation, no navigation slot, no `:extends` lineage. It is a captured
  RUN — enough to replay it deterministically and to project the same
  evidence a fresh run would produce.

  Required P1 shape (spec/017 §Artifacts — Run artifact):

      {:artifact/kind :rf.test/run-artifact
       :seed          optional
       :event-program [step …]        ; the dispatch program (setup ⧺ script)
       :fx-decisions  {fx-id override} ; reapplied fx overrides / decisions
       :epoch-tape    [epoch-record …] ; the captured tape (when retained)
       :trace         [trace-event …]  ; optional flat trace
       :result        run-result       ; the projected run-result
       :shrink-path   optional
       :created-at    instant-or-string
       :source        optional}

  The `:event-program` is a vector of TAGGED script steps (the same
  grammar the play runner executes — `[:dispatch …]` / `[:dispatch-sync …]`
  / `[:wait …]` / `[:assert-* …]`). A bare event vector is lifted to
  `[:dispatch …]` by `re-frame.story.play.runner/coerce-script` so a
  recorder can hand a flat event list and get a legal program. Setup and
  script are folded into ONE ordered program — replay re-runs the whole
  program, then projects the captured tape.

  `:fx-decisions` is the serializable record of fx overrides applied at
  capture time: a `{fx-id override}` map, the same shape the dispatch
  `:fx-overrides` opt and the per-frame frame-config `:fx-overrides` slot
  carry (Spec 002 §`:fx-overrides`). Replay REAPPLIES them, so a run that
  stubbed `:http/get` replays against the same stub rather than hitting a
  live effect.

  ## Replay

  `(replay-run-artifact artifact opts)` replays the artifact's dispatch
  program into a FRESH frame, reapplying the fx decisions, captures a NEW
  epoch tape, and projects it through the merged `.4` evidence boundary
  (`re-frame.story.play.evidence/project-evidence`). The returned
  run-result is the SHARED run-result shape (spec/017 §Run result) — the
  same shape the runner returns and the same one `canonicalize` /
  `run-hash` consume — so a later determinism gate (rf2-5x1wt.8) and
  semantic diff (rf2-5x1wt.9) build directly on it.

  ## Pure / JVM-testable

  This ns splits PURE construction (`make-run-artifact`, `run-artifact?`,
  `program-events`, `replay-result`) from the impure HEADLESS replay path
  (`replay-into-frame!`). Construction + result projection are pure
  data → data and run under `clojure -M:test` with no runtime. The
  headless replay path uses the `.2` settled-boundary so each
  `[:dispatch …]` settles deterministically to a fixed point with no async
  yield — `clojure -M:test` exercises a live frame synchronously. Richer
  runners (DOM/browser) supply their own flush-hooks; this ns defaults to
  the headless hooks and never reaches for `dispatch-sync` directly."
  (:require [re-frame.core                        :as rf]
            [re-frame.story.play.evidence         :as evidence]
            [re-frame.story.play.runner           :as runner]
            [re-frame.story.play.settled-boundary :as boundary]))

;; ===========================================================================
;; THE :rf.test/run-artifact SCHEMA
;; ===========================================================================

(def artifact-kind
  "The `:artifact/kind` tag every run artifact carries (spec/017
  §Artifacts — Run artifact). A distinct value so a consumer can tell a
  run artifact apart from a normalized plan or a curated variant body."
  :rf.test/run-artifact)

(def artifact-keys
  "The full set of slots a `:rf.test/run-artifact` MAY carry (spec/017
  §Artifacts — Run artifact). `:artifact/kind` + `:event-program` are the
  load-bearing slots; the rest are optional evidence / provenance.

  Enumerated so `select-keys` can normalize a hand-built / over-stuffed
  map down to the artifact surface without dropping a future-added slot
  silently elsewhere."
  #{:artifact/kind :seed :event-program :fx-decisions :epoch-tape
    :trace :result :shrink-path :created-at :source})

(defn run-artifact?
  "True iff `x` is a `:rf.test/run-artifact` map. The minimal contract is
  the `:artifact/kind` tag plus a vector `:event-program` — the dispatch
  program is what makes the artifact replayable; everything else is
  optional evidence. Pure data → data."
  [x]
  (boolean
    (and (map? x)
         (= artifact-kind (:artifact/kind x))
         (vector? (:event-program x)))))

(defn make-run-artifact
  "Construct a `:rf.test/run-artifact` from its parts. Pure data → data —
  no runtime, JVM-runnable.

  `parts` is a map carrying any of `artifact-keys`. The `:event-program`
  is coerced through the play runner's `coerce-script` so a bare event
  list (`[[:my/event …] …]`) lifts to a legal tagged dispatch program
  (`[[:dispatch [:my/event …]] …]`) and an already-tagged program passes
  through verbatim. Optional `:setup` / `:script` slots, when present, are
  folded into the program in order (setup first) so a recorder can hand
  the two phases separately; an explicit `:event-program` takes
  precedence over `:setup` / `:script`.

  The result stamps `:artifact/kind` and defaults `:fx-decisions` to an
  empty map, so a replay never has to nil-check the override slot. Slots
  outside `artifact-keys` (plus the `:setup`/`:script` authoring sugar)
  are dropped so the artifact stays a clean, serializable surface."
  [parts]
  (let [program (cond
                  (contains? parts :event-program) (:event-program parts)
                  (or (contains? parts :setup)
                      (contains? parts :script))
                  (into (vec (:setup parts)) (:script parts))
                  :else [])]
    (-> (select-keys parts artifact-keys)
        (assoc :artifact/kind artifact-kind
               :event-program (runner/coerce-script program))
        (update :fx-decisions #(or % {})))))

(defn program-events
  "Project a run artifact's `:event-program` to the ordered vector of
  event vectors its `:dispatch` / `:dispatch-sync` steps dispatch. Pure
  data → data — non-dispatch steps (`:wait`, `:assert-*`) contribute no
  event. Useful for promotion (spec/017 §Promotion) + diagnostics."
  [artifact]
  (into []
        (keep runner/step-event)
        (:event-program artifact)))

;; ===========================================================================
;; REPLAY — fresh frame, reapply fx decisions, capture a new tape
;; ===========================================================================

(defn- gen-replay-frame-id
  "Allocate a unique frame id for a replay. Namespaced under
  `:rf.test.replay/*` so a replay frame is never confused with a variant
  frame or the default frame."
  []
  (keyword "rf.test.replay"
           (str "frame-"
                #?(:clj  (str (java.util.UUID/randomUUID))
                   :cljs (.toString (js/Math.random) 36)))))

(defn- replay-flush-hooks
  "Build the settled-boundary flush-hooks for a replay (spec/017 §Script
  and `settled-boundary`). It starts from `base-hooks` (the headless hooks
  by default — `:provides :headless`, `dispatch-sync*` drain) and WRAPS
  `:dispatch!` so every replayed dispatch carries the artifact's
  `fx-decisions` as the per-call `:fx-overrides`.

  The wrap routes the fx decisions THROUGH the inner `:dispatch!`, never
  around it: it binds the overrides on the lexical-scope `*fx-overrides*`
  via `rf/with-fx-overrides` and then calls `inner`, so whatever dispatch
  path the inner hook owns picks the overrides up at envelope-build time
  (precedence: per-call opt > lexical `with-fx-overrides` > per-frame
  `:fx-overrides`, Spec 002 §`:fx-overrides`). For the headless default
  `inner` is `drain-sync!` (`dispatch-sync*` drain) and the overrides ride
  its envelope; for a richer adapter caller that passes its own
  `base-hooks` (declaring `:provides :dom`, etc. with an enqueue +
  `act()` / microtask `:dispatch!`) the SAME adapter dispatch path runs
  and the overrides ride it too — replay never reaches for `dispatch-sync`
  directly. This keeps replay on the SAME settlement path as a live run
  and lets the boundary's `:cannot-run` / `:error` refusals fire
  unchanged."
  [base-hooks fx-decisions]
  (let [inner (or (:dispatch! base-hooks) boundary/drain-sync!)]
    (assoc base-hooks
           :dispatch!
           (fn replay-dispatch! [frame-id event-vector]
             (if (seq fx-decisions)
               ;; WRAP, don't replace: bind the fx decisions on the
               ;; lexical-scope overrides and route THROUGH `inner` so the
               ;; (possibly richer-adapter) dispatch path still runs and
               ;; the overrides ride its envelope.
               (rf/with-fx-overrides fx-decisions
                 (inner frame-id event-vector))
               (inner frame-id event-vector))))))

(defn replay-into-frame!
  "Replay an artifact's `:event-program` into the LIVE `frame-id`,
  reapplying `fx-decisions` and settling each `[:dispatch …]` step through
  `settled-boundary`. Returns a vector of per-step settle outcomes (one
  per dispatch step), in program order — `{:status :settled :boundary …}`
  on success, a `cannot-run-refusal` / `{:status :error …}` otherwise.

  This is the IMPURE seam — it dispatches into a live frame. The caller
  owns frame allocation + teardown + the epoch-tape read; `replay-run-
  artifact` wires those around it. Non-dispatch steps (`:wait` / `:assert-*`)
  are skipped here: replay reproduces the CAUSAL program (the dispatches),
  and the captured tape + projected evidence carry the assertion / timing
  story. `hooks` defaults to the headless flush-hooks; the fx decisions are
  wrapped onto its `:dispatch!`."
  ([frame-id artifact]
   (replay-into-frame! frame-id artifact boundary/headless-flush-hooks))
  ([frame-id artifact hooks]
   (let [fx-decisions (:fx-decisions artifact {})
         replay-hooks (replay-flush-hooks hooks fx-decisions)]
     (into []
           (keep (fn [step]
                   (when-let [evec (runner/step-event step)]
                     (let [required (boundary/step-required-boundary step)]
                       (boundary/dispatch-and-settle!
                         frame-id evec replay-hooks required step)))))
           (:event-program artifact)))))

(defn replay-result
  "Build the replay run-result from the captured `epoch-tape`, the
  artifact, the per-step settle `outcomes`, and the replay `frame-id`.
  Pure data → data — epoch records + outcomes in, run-result out, so the
  result-shape construction is JVM-testable without a live frame.

  The result is the SHARED run-result shape (spec/017 §Run result): a
  top-level `:status`, the projected `:epoch-tape` / `:schema-violations` /
  `:warnings` / `:effects` / `:sub-runs` / `:renders` / `:narrative`
  evidence (via the `.4` `project-evidence` boundary), the final
  `:app-db`, and a back-link `:run-artifact` to the replayed source. The
  `:script` for the two-level narrative is the artifact's
  `:event-program`.

  `:status` follows the agreement floor (spec/017 §Run-result evidence
  projection): `:cannot-run` if any step refused; `:error` if any step or
  the tape errored; `:fail` if the tape shows unconsumed failure evidence;
  `:pass` otherwise. The status is computed from the PROJECTED evidence and
  the settle outcomes — never a sibling accumulator — so a replay cannot
  read green while the tape is red."
  [{:keys [epoch-tape artifact outcomes frame-id app-db]}]
  (let [evidence-slots (evidence/project-evidence
                         epoch-tape {:script (:event-program artifact)})
        refusal        (some (fn [o] (when (= :cannot-run (:status o)) o)) outcomes)
        errored        (some (fn [o] (when (= :error (:status o)) o)) outcomes)
        tape-red?      (evidence/tape-shows-failure? epoch-tape)
        status         (cond
                         refusal   :cannot-run
                         errored   :error
                         tape-red? :fail
                         :else     :pass)]
    (cond-> (merge {:status        status
                    :runner        :headless
                    :frame         frame-id
                    :app-db        app-db
                    :run-artifact  artifact
                    :replay-steps  outcomes}
                   evidence-slots)
      refusal (assoc :cannot-run refusal)
      errored (assoc :error (:error errored)))))

(defn replay-run-artifact
  "Replay a `:rf.test/run-artifact` into a FRESH frame and return a
  run-result (spec/017 §Run artifact and replay). The contract:

  - Replay the dispatch program into a FRESH frame — a unique
    `:rf.test.replay/*` frame allocated for this replay (or the caller's
    `:frame`), so the replay never observes a sibling run's app-db.
  - Reapply the fx decisions / overrides — the artifact's `:fx-decisions`
    ride the per-call `:fx-overrides` on every replayed dispatch.
  - Capture a NEW epoch tape — read from `re-frame.core/epoch-history`
    after the program settles, NOT the artifact's captured tape.
  - Project that tape through the merged `.4` evidence boundary and return
    the shared run-result shape — stable + canonicalizable, so the
    determinism gate (rf2-5x1wt.8) + semantic diff (rf2-5x1wt.9) build on
    it directly.

  `opts` (all optional):

  - `:frame`       — replay into this frame id instead of allocating a
                     fresh one. The caller then owns teardown; the
                     internally-allocated frame is destroyed automatically.
  - `:hooks`       — settled-boundary flush-hooks (default the headless
                     hooks). A `:dom` adapter passes richer hooks; the fx
                     decisions wrap its `:dispatch!`.
  - `:frame-config`— extra `reg-frame` config for the allocated frame.

  The replayed frame is allocated through `re-frame.core/reg-frame` and —
  when this fn allocated it — torn down through `destroy-frame!` before
  return, so a JVM test leaves no frame behind. A caller-supplied `:frame`
  is left intact (the caller owns its lifecycle)."
  ([artifact] (replay-run-artifact artifact nil))
  ([artifact {:keys [frame hooks frame-config] :as _opts}]
   (let [own-frame? (nil? frame)
         frame-id   (or frame (gen-replay-frame-id))
         hooks      (or hooks boundary/headless-flush-hooks)]
     (when own-frame?
       (rf/reg-frame frame-id (merge {:doc "rf2-5x1wt.7 run-artifact replay frame"}
                                     frame-config)))
     (try
       (let [outcomes (replay-into-frame! frame-id artifact hooks)
             tape     (vec (rf/epoch-history frame-id))
             app-db   (rf/get-frame-db frame-id)]
         (replay-result {:epoch-tape tape
                         :artifact   artifact
                         :outcomes   outcomes
                         :frame-id   frame-id
                         :app-db     app-db}))
       (finally
         (when own-frame?
           (try (rf/destroy-frame! frame-id)
                (catch #?(:clj Throwable :cljs :default) _ nil))))))))
