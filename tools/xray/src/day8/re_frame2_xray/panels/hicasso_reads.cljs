(ns day8.re-frame2-xray.panels.hicasso-reads
  "Xray's consumer of the Hicasso TOOL-TIER reader door
  (`re-frame.hicasso.tool`) — the live read seam behind the Hicasso tab
  (rf2-hic-023).

  ## A reader, and nothing else

  There is no acquire, no install, no owner, no receipt and no revision
  here, because there is nothing to own. The Hicasso door has no registry
  and no ownership plane — deliberately and permanently — so a consumer
  cannot claim it, cannot be locked out of it by another tool, and cannot
  read a superseded span's data through it. Every read below is a pure
  projection of state the substrate already holds, taken at the moment the
  panel asks.

  ## The envelope is passed through UNCHANGED

  Each read here answers the producer's value, verbatim. That is the whole
  of Xray's side of the byte-for-byte contract: the AI pair calls the same
  four functions on the same runtime and receives the same bytes, because
  the door takes no consumer discriminator and this seam reshapes nothing.
  Shaping into rows happens one layer up, in the pure
  `hicasso-helpers`, against the envelope a reader can still see whole.

  A witness pins it: `hicasso-reads-cljs-test` asserts the seam's answer is
  `identical?` to the door's, which a reshaping seam could not be.

  ## Fail-soft at read time

  Every call is `try`-guarded and degrades to `nil` — the same answer a
  host that is not running Hicasso gives. A devtools panel must not be
  able to take down the application it is inspecting, and the panel
  already renders `nil` as the honest \"no Hicasso evidence on this host\"
  state rather than as an empty table.

  ## DEV-ONLY

  Every read is `nil` in a production build: the Hicasso door nil-gates on
  `re-frame.interop/debug-enabled?`, and Xray itself never reaches a
  production bundle (the tools/README bundle-isolation contract)."
  (:require [re-frame.hicasso.tool :as tool]
            [re-frame.trace.tooling :as trace-tooling]))

(defn- soft
  "Call `f`, answering nil on any throw.

  A panel read runs inside a subscription's recompute, so a throw here
  would surface as a broken Xray rather than as a missing section — and
  the reader would have no way to tell an instrumented application from an
  Xray bug."
  [f]
  (try (f) (catch :default _ nil)))

(defn mounted-boundaries
  "The producer's `:mounted-boundaries` envelope, verbatim, or nil."
  []
  (soft tool/read-mounted-boundaries))

(defn read-attribution
  "The producer's `:read-attribution` envelope, verbatim, or nil."
  []
  (soft tool/read-read-attribution))

(defn intents
  "The producer's `:intents` envelope, verbatim, or nil."
  []
  (soft tool/read-intents))

(defn explain-render
  "The producer's `:explain-render` envelope, verbatim, or nil."
  []
  (soft tool/explain-render))

(defn evidence
  "All four envelopes in ONE turn, keyed by read.

  One turn matters: the four rosters are projections of one runtime state,
  and reading them across two turns would let a mount land between the
  mounted roster and the attribution roster, so a reader would see an
  edge whose boundary is not in the census. On a single-threaded host,
  taken together here, they cannot disagree."
  []
  {:mounted-boundaries (mounted-boundaries)
   :read-attribution   (read-attribution)
   :intents            (intents)
   :explain-render     (explain-render)})

(defn trace-windows
  "Spec 009's retained ring for every frame this Hicasso runtime touches,
  as `{frame-id [event-bundle …]}` — the advisor's clock and the causal
  slice's event seam (rf2-hic-037).

  ## Why this read exists beside the four, rather than inside them

  The Hicasso door deliberately carries no duration. Its four reads
  project the substrate's own tables, and a clock is not one of them: per
  `lanes/left-field-ideas.md` §Capability receipts, per-boundary self time
  was KILLED as a decision rather than deferred, because the 0.1 ms timer
  grain is coarser than the quantity being measured. What Spec 009 does
  publish is `:rf.sub/elapsed-ms` on the reactive recompute path — the
  cost of the SUBSCRIPTIONS a boundary reads, which is a different
  quantity, honestly measured, and the only one of the five pressure
  classes this tab can weigh.

  So this is a SECOND producer, not a fifth Hicasso read, and it is kept
  separate for that reason: `hicasso-advisor/sub-timing` stamps it with
  its own schema and its own `:cap` loss, and a reader can see at a glance
  which producer vouched for which number.

  ## The frames come from the evidence, not from a guess

  `explain-render`'s `:window :frames` is already the union this needs —
  the frames the runtime dispatches through PLUS any frame a boundary
  reads from — computed by the producer for exactly the same reason a
  per-boundary window is scoped that way. Reading every frame in the
  process instead would let an unrelated application's activity inflate
  this one's ranking.

  Answers `{}` when Hicasso is absent, which is the same shape an idle
  runtime gives — and the advisor renders that as a capped window rather
  than as a quiet application."
  [envelopes]
  (soft
    (fn []
      (let [frames (or (get-in envelopes [:explain-render :window :frames])
                       (get-in envelopes [:intents :scope :frames])
                       [])]
        (into {} (map (fn [fid] [fid (trace-tooling/trace-buffer fid)])) frames)))))
