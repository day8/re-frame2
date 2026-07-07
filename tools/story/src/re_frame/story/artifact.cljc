(ns re-frame.story.artifact
  "The `:rf.test/run-artifact` schema + `replay-run-artifact` — a
  serializable record of a Story run, and the function that replays it
  into a fresh frame (spec/017-Testing-Story.md
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
       :network       {[m url] {:reply …}} ; per-route HTTP stubs (reinstalled)
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

  `:network` is the serializable record of the variant's per-route HTTP
  reply data (`{[method url] {:reply …}}`, spec/017 §The network surface),
  captured from the plan's `[:world :network]` slot at materialize time.
  The `:network` world slot LOWERS to a `:fx-decisions` redirect
  (`{:rf.http/managed :rf.http/managed-test-stub}`), so the redirect alone
  survives in `:fx-decisions` — but the redirect points at a stub fx that
  is only registered (with the routes) by
  `re-frame.http.test-support/install-managed-request-stubs!`. Carrying the
  route map in `:network` lets replay RE-INSTALL those route stubs before
  replaying, so a replayed `:network` request matches its route and
  synthesises the recorded reply rather than fail-closing on
  \"no stub matched\" (spec/017 §The network surface).

  ## Replay

  `(replay-run-artifact artifact opts)` replays the artifact's dispatch
  program into a FRESH frame, reapplying the fx decisions, RE-INSTALLING
  the `:network` route stubs (when present), captures a NEW epoch tape, and
  projects it through the merged `.4` evidence boundary
  (`re-frame.story.play.evidence/project-evidence`). The returned
  run-result is the SHARED run-result shape (spec/017 §Run result) — the
  same shape the runner returns and the same one `canonicalize` /
  `run-hash` consume — so a later determinism gate and semantic diff
  build directly on it.

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
            [re-frame.story.play.runner-events    :as runner-events]
            [re-frame.story.play.settled-boundary :as boundary]
            ;; The raw HTTP stub pair lives in `re-frame.http.test-support`
            ;; (reached through its home namespace, not the `re-frame.core`
            ;; façade). CLJS requires it directly; on the JVM it is resolved
            ;; LAZILY at call time (`with-network-stubs!`) so requiring this
            ;; ns from the
            ;; `re-frame.story` MACRO-ns does NOT drag the http artefact's
            ;; JVM-only transitive deps (e.g. cheshire) onto the macro
            ;; classpath. CLJS pulls only the CLJS-clean `.cljc` surface.
            #?(:cljs [re-frame.http.test-support :as http-test-support])))

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

  `:network` is the per-route HTTP reply map (`{[method url] {:reply …}}`)
  carried so replay can re-install the managed-request stubs the
  `:fx-decisions` redirect points at (spec/017 §The network
  surface — \"the runner installs the route map via
  install-managed-request-stubs!\").

  Enumerated so `select-keys` can normalize a hand-built / over-stuffed
  map down to the artifact surface without dropping a future-added slot
  silently elsewhere."
  #{:artifact/kind :seed :event-program :fx-decisions :network :epoch-tape
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
  unchanged.

  EP-0017 replay fidelity: the wrapped `:dispatch!` is the
  optional 3-arity `(replay-dispatch! frame-id event-vector dispatch-opts)`
  the boundary's 6-arity `dispatch-and-settle!` invokes when a step carries
  dispatch opts. `replay-into-frame!` builds those opts so every replayed
  dispatch is STRICT (`:rf.cofx/mint-policy :strict`) and re-presents the
  step's recorded `:rf.cofx` envelope verbatim — so a complete record
  replays its recorded recordable coeffects and an incomplete record fails
  loudly (`:rf.error/missing-required-cofx`) rather than minting a fresh
  host value mid-replay (EP-0017 §6 binding point 1, Tool-Pair §Replay).
  The opts are routed THROUGH `inner`'s 3-arity (the headless default
  `drain-sync!` merges them onto the dispatch envelope), alongside the
  lexical `fx-decisions` wrap — both survive whatever dispatch path the
  (possibly richer-adapter) inner hook owns. A 2-arity replay (no opts)
  stays byte-identical to the opts-free dispatch path."
  [base-hooks fx-decisions]
  (let [inner (or (:dispatch! base-hooks) boundary/drain-sync!)
        ;; Route the dispatch through `inner`, optionally with opts, always
        ;; under the `fx-decisions` lexical wrap. The fx decisions and the
        ;; EP-0017 dispatch opts are independent seams (lexical overrides vs
        ;; per-call opts) and compose — both ride the SAME inner dispatch.
        fire  (fn [frame-id event-vector opts]
                (let [call (if (and (map? opts) (seq opts))
                             #(inner frame-id event-vector opts)
                             #(inner frame-id event-vector))]
                  (if (seq fx-decisions)
                    (rf/with-fx-overrides fx-decisions (call))
                    (call))))]
    (assoc base-hooks
           :dispatch!
           (fn replay-dispatch!
             ([frame-id event-vector]      (fire frame-id event-vector nil))
             ([frame-id event-vector opts] (fire frame-id event-vector opts))))))

(defn with-network-stubs!
  "Run `thunk` with the artifact's `:network` per-route HTTP stubs
  installed, then uninstall them (spec/017 §The network surface). When the
  artifact carries a non-empty `:network` route map, this installs the
  managed-request stub fx (`:rf.http/managed-test-stub`) with those routes
  via `re-frame.http.test-support/install-managed-request-stubs!` — the SAME
  seam a live run uses — so the `:fx-decisions` redirect (`{:rf.http/managed
  :rf.http/managed-test-stub}`) resolves to a stub that matches the routes
  rather than fail-closing on \"no stub matched\". The stubs are
  uninstalled in a `finally` so a replay never leaks the stub fx into a
  later test.

  When the artifact carries no `:network` routes, `thunk` runs unchanged
  (no install, no uninstall) — so an artifact without HTTP stays clear of
  the test-support surface entirely. The install/uninstall pair lives in
  `re-frame.http.test-support` (reached through its home namespace, not
  the `re-frame.core` façade), which Story already
  carries on its require closure via the `day8/re-frame2-http` dep."
  [artifact thunk]
  (let [network (:network artifact)]
    (if (seq network)
      ;; JVM resolves the home-ns fns lazily so requiring this ns from the
      ;; story MACRO-ns never hard-loads the http artefact's JVM deps; CLJS
      ;; calls the directly-required surface.
      (let [install   #?(:clj  (requiring-resolve 're-frame.http.test-support/install-managed-request-stubs!)
                         :cljs http-test-support/install-managed-request-stubs!)
            uninstall #?(:clj  (requiring-resolve 're-frame.http.test-support/uninstall-managed-request-stubs!)
                         :cljs http-test-support/uninstall-managed-request-stubs!)]
        (try
          (install network)
          (thunk)
          (finally
            (uninstall))))
      (thunk))))

(defn- step-dispatch-opts
  "Build the per-call dispatch opts a replayed `[:dispatch …]` step carries
  (EP-0017 §6 / Tool-Pair §Replay). Replay is UNCONDITIONALLY
  STRICT: every replayed dispatch stamps `:rf.cofx/mint-policy :strict`, so a
  generator-backed recordable fact that is ABSENT from the record fails loudly
  (`:rf.error/missing-required-cofx`) instead of minting a fresh, divergent
  host value mid-replay. When the step recorded a flat `:rf.cofx` envelope
  (the framework `:rf/time-ms` plus any provided recordable facts captured at
  record time), it is carried verbatim under `:rf.cofx` so a complete record
  re-presents its recorded coeffects rather than restamping.

  Returns the opts map (always at least the strict mint policy) — passed as
  the boundary's `dispatch-opts`, which the replay `:dispatch!` wrap threads
  onto the dispatch envelope. Pure data → data."
  [step]
  (let [cofx (runner/step-cofx step)]
    (cond-> {:rf.cofx/mint-policy :strict}
      (and (map? cofx) (seq cofx)) (assoc :rf.cofx cofx))))

(defn replay-into-frame!
  "Replay an artifact's `:event-program` into the LIVE `frame-id`,
  reapplying `fx-decisions` and settling each `[:dispatch …]` step through
  `settled-boundary`. Returns a vector of per-step settle outcomes (one
  per dispatch step), in program order — `{:status :settled :boundary …}`
  on success, a `cannot-run-refusal` / `{:status :error …}` otherwise.

  The returned vector carries an `:attribution` metadata slot: the
  last-committed `:epoch-id` (`runner-events/last-epoch-id` — a genuine
  monotonic identity, NOT a ring-length snapshot; rf2-96qsjr) at the start
  of each dispatch step's settle, in dispatch-step order. `replay-result`
  reads it (via `(:attribution (meta outcomes))`) and hands it to
  `project-evidence` so the replay narrative is attributed EXACTLY
  (`evidence/spans-from-stamps`) rather than via the EVEN heuristic. The
  metadata leaves the documented return VALUE (the outcomes vector)
  unchanged. The boundaries are positional with respect to DISPATCH-STEP
  ORDER — the Nth element is the Nth dispatch step's settle boundary — so
  they zip directly onto the dispatch steps of the `:event-program` the
  narrative spans, regardless of how much of the `epoch-history` ring
  (default depth 50) survives eviction by the time the tape is read.

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
         replay-hooks (replay-flush-hooks hooks fx-decisions)
         boundaries   (volatile! [])
         outcomes     (into []
                            (keep (fn [step]
                                    (when-let [evec (runner/step-event step)]
                                      (let [required (boundary/step-required-boundary step)
                                            dispatch-opts (step-dispatch-opts step)]
                                        ;; Snapshot the last-committed
                                        ;; `:epoch-id` BEFORE the settle — this
                                        ;; dispatch step owns every record
                                        ;; whose OWN id is greater (rf2-96qsjr:
                                        ;; an identity, not a ring-length
                                        ;; snapshot, so it stays correct
                                        ;; whatever the ring evicts).
                                        (vswap! boundaries conj (runner-events/last-epoch-id frame-id))
                                        (boundary/dispatch-and-settle!
                                          frame-id evec replay-hooks required step
                                          dispatch-opts)))))
                            (:event-program artifact))]
     (with-meta outcomes {:attribution @boundaries}))))

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
                         epoch-tape {:script      (:event-program artifact)
                                     ;; The per-dispatch-step settle
                                     ;; boundaries `replay-into-frame!`
                                     ;; recorded (on the outcomes vector's
                                     ;; metadata) light up EXACT narrative
                                     ;; attribution. Absent (a hand-built
                                     ;; `outcomes` with no metadata) → EVEN
                                     ;; fallback, unchanged.
                                     :attribution (:attribution (meta outcomes))})
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
    determinism gate + semantic diff build on it directly.

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
       ;; Re-install the artifact's `:network` route stubs (when any) for the
       ;; duration of the replay, so the `:fx-decisions` managed-stub redirect
       ;; resolves to a stub that matches the recorded routes rather than
       ;; fail-closing (spec/017 §The network surface).
       (with-network-stubs! artifact
         (fn []
           (let [outcomes (replay-into-frame! frame-id artifact hooks)
                 tape     (vec (rf/epoch-history frame-id))
                 app-db   (rf/app-db-value frame-id)]
             (replay-result {:epoch-tape tape
                             :artifact   artifact
                             :outcomes   outcomes
                             :frame-id   frame-id
                             :app-db     app-db}))))
       (finally
         (when own-frame?
           (try (rf/destroy-frame! frame-id)
                (catch #?(:clj Throwable :cljs :default) _ nil))))))))
