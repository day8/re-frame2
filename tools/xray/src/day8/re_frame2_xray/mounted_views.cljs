(ns day8.re-frame2-xray.mounted-views
  "Xray's consumer of the Freehand TOOL-TIER reader door
  (`re-frame.freehand.tool`) — the Views panel's Mounted Views and Declared
  View Sites sections.

  ## A reader, and nothing else

  There is no acquire, no install, no owner, no receipt and no revision here,
  because there is nothing to own. The Freehand door has no registry and no
  ownership plane — deliberately and permanently (rf2-drpa3.167 ruled the
  donor's per-occurrence accumulator out rather than deferring it) — so a
  consumer cannot claim it, cannot be locked out of it by another tool, and
  cannot read a superseded span's data through it. Every read below is a pure
  projection of state the substrate already holds, taken at the moment the
  panel asks.

  That is the whole reason this namespace is short. Its predecessor layered an
  ownership span, an opaque per-span receipt fencing a same-key ABA reclaim,
  and a reactive ownership-revision bridge into `:rf/xray` app-db over the
  donor's install/uninstall registry. None of that has a counterpart, and none
  of it is missing: a fence against a hazard that cannot occur is not a
  protection, it is a thing to maintain.

  ## What the panel asks, and which read answers it

    - `read-mounted-views`  → what is CONNECTED right now. One row per
                              connected occurrence, carrying the LATEST
                              committed generation, the commit's frame and its
                              staged reads. Current state — not a history, and
                              not a tally.
    - `explain-render`      → WHY each of those rendered, folded at read time
                              from Spec 009's retained window under Spec 009's
                              one knob. Complete when the run the commit was
                              correlated to is still retained; otherwise an
                              explicit `:loss` naming `:cap` or `:uncorrelated`,
                              with `:candidates` offered as leads and never as
                              the answer.
    - `read-view-manifest` /
      `read-view-dependencies` /
      `read-view-event-sites` → what each view DECLARES, from the compiler
                              manifest, available before anything mounts.

  [[rows]] joins the first two. Both project the same current-occurrence index
  in one synchronous turn, so on a single-threaded host every roster row has an
  explanation; a row that somehow had none states `:explained? false` with a
  nil `:cause` rather than inventing one.

  ## What the donor row carried and this one does not

  Named here because a reader migrating from the donor panel will look for
  them, and a fact that is absent has to say so:

    - a LIFETIME render count and batch count. Freehand keeps no accumulator;
      a row states `:generation`, the latest committed generation, which is a
      fact about now.
    - a first/latest EPOCH span. Same reason; `:at` is the commit's reading of
      the monotonic clock, which is what lets a reader see a stale row.
    - a hide-versus-unmount LIFECYCLE interval log. A disconnect removes the
      row and says nothing about why the host tore down, so there is no
      disconnected state to label.
    - an accumulated UNION of every target the occurrence observed, with its
      `identity-exact?` fidelity flag and its saturating dropped-count floor.
      `:reads` is the SELECTED COMMIT's dependency set — `:complete? true`,
      `:loss nil` — a smaller and exact claim in place of a larger approximate
      one.
    - the owning ROOT. `:root` is always `:unknown`: cells do not know their
      owning root and the commit seam carries no root identity, so a row that
      named one would be inventing it. It is projected as the explicit
      `:unknown` the substrate states, never elided into a nil that reads as
      \"no root\".

  ## Schema honesty

  Every Freehand projection stamps `:schema`. [[consumed-evidence-schema]] is
  the version THIS Xray build was written to parse — a CONSUMER-OWNED literal,
  deliberately not `re-frame.freehand.evidence/schema`. Deriving support from
  the producer's own var makes ANY producer bump silently \"supported\", so an
  evolved shape would be mis-parsed as exact and the version boundary would be
  nominal. A projection stamped a schema this build was not taught suppresses
  rows ([[rows]] answers `[]`) and surfaces through [[schema-status]], so the
  panel renders the honest banner instead of an empty section that looks like
  a substrate-free host.

  ## DEV-ONLY

  Every read is `nil` in a production build (the Freehand door nil-gates on
  `re-frame.interop/debug-enabled?`), and Xray itself never reaches a
  production bundle (the tools/README bundle-isolation contract). A host that
  is not running Freehand projects zero rows and zero sites — the same answer,
  by the same door, with nothing special-cased."
  (:require [re-frame.freehand.evidence :as evidence]
            [re-frame.freehand.tool :as tool]))

(def consumed-evidence-schema
  "The Freehand evidence-schema version THIS Xray build was written to parse.

  A consumer-owned literal, NOT `re-frame.freehand.evidence/schema`: pinning a
  literal makes a producer that evolves its shape a DETECTABLE mismatch until
  Xray is taught the new shape and this pin is bumped in lockstep. Currently
  `:re-frame.freehand.evidence/v1`."
  :re-frame.freehand.evidence/v1)

(defn- supported?
  "True when a projection carries EXACTLY the schema this build understands.
  Any other version — older or newer — degrades rather than mis-parses."
  [schema]
  (= consumed-evidence-schema schema))

(defn schema-status
  "`{:schema s :supported? bool}` for the schema the running application's
  Freehand door stamps, or nil when the door answers nothing (no Freehand on
  the host's classpath, or a production build).

  The stamp rides EVERY projection, so one read is representative.
  `:supported? false` means [[rows]] is suppressed to avoid a mis-parse, and
  the panel says so rather than showing an empty section."
  []
  (when-some [envelope (tool/read-mounted-views)]
    (let [schema (:schema envelope)]
      {:schema     schema
       :supported? (supported? schema)})))

(defn- occurrence-row
  "Shape ONE connected-occurrence row, with its explanation joined in.

  The two halves stay distinguishable on the row itself: everything from
  `:view-id` through `:reads` is CURRENT STATE the occurrence index holds, and
  everything from `:cause` down is the bounded read-time fold over Spec 009's
  window, whose completeness is its own claim. `:explained?` and `:loss` are
  that claim — a nil `:cause` with `:explained? false` and a named loss reason
  is a window that has forgotten, which is a different fact from a render that
  had no cause."
  [explanations row]
  (let [commit (:commit row)
        expl   (get explanations (:occurrence row))]
    {:view-id     (:view-id row)
     :occurrence  (:occurrence row)
     :lowering    (:lowering row)
     :generation  (:generation row)
     :connection  (:connection row)
     :root        (:root row)
     :at          (:at row)
     :dispatch-id (:dispatch-id row)
     :frame       (:frame commit)
     :reads       (:reads commit [])
     :cause       (:cause expl)
     :candidates  (:candidates expl [])
     :explained?  (boolean (:complete? expl))
     :loss        (:loss expl)
     :window      (:scope expl)}))

(defn rows
  "The developer-facing projection over the occurrences connected RIGHT NOW —
  a vector in the substrate's own deterministic order (view id, then occurrence
  key), one entry per connected occurrence:

    {:view-id     vid       ; the declaring view
     :occurrence  {…}       ; the runtime occurrence key — two entries with one
                            ;   :view-id and different keys are two live
                            ;   occurrences of that view
     :lowering    :compiled ; / :interpreted — stated, never inferred
     :generation  n         ; LATEST committed generation. NOT a render tally
     :connection  :connected
     :root        :unknown  ; always: the commit seam has no root identity
     :at          t         ; the commit's monotonic-clock reading
     :dispatch-id id|nil    ; the cascade in scope at the commit; nil is common
     :frame       fid       ; the frame the commit ran over
     :reads       [{…}]     ; THAT commit's staged reads — queries without the
                            ;   values they returned
     :cause       {…}|nil   ; the run the commit was correlated to, joined out
                            ;   of Spec 009's retained window
     :candidates  [{…}]     ; runs that COULD have driven it — leads, not causes
     :explained?  bool      ; the explanation's own completeness claim
     :loss        {…}|nil   ; :cap (window empty or the run evicted) or
                            ;   :uncorrelated (the commit named no run)
     :window      {…}}      ; :retained-runs / :spans-commit? / :window-starts-at

  `[]` when nothing is connected, when the producer stamps a schema this build
  was not taught (degrade — never mis-parse an evolved shape as exact), when
  the host is not running Freehand, or in a production build."
  []
  (let [envelope (tool/read-mounted-views)]
    (if (and (some? envelope) (supported? (:schema envelope)))
      (let [explained    (tool/explain-render)
            explanations (if (and (some? explained)
                                  (supported? (:schema explained)))
                           (into {}
                                 (map (juxt :occurrence identity))
                                 (:explanations explained))
                           {})]
        (mapv #(occurrence-row explanations %) (:occurrences envelope)))
      [])))

(defn view-sites
  "The DECLARED per-view site projections for `view-ids` — what each view
  declares, read from the compiler manifest and so answerable before anything
  mounts:

    {:view-id       vid
     :lowering      :compiled|:interpreted
     :basis         :static-proof|:opaque
     :complete?     bool
     :loss          {…}|nil        ; {:reason :no-static-analysis …} when opaque
     :capabilities  #{…}
     :view-cell     :present|:elided
     :reactive?     bool
     :subscriptions [{:sid :source-coord :path :dynamic? :query|:query-id}]
     :event-sites   [{:sid :source-coord :path :prop :classification
                      :serializable? :sync? :handler :event-id}]
     :site-facts    #{…}           ; the CLOSED set an event-site row states
     :diagnostics   [{:sid :id :tag :path :suppressed? :reason}]}

  An INTERPRETED declaration is the case the projection vocabulary exists for
  and it is passed through rather than flattened: `:basis :opaque`,
  `:complete? false`, `:loss {:reason :no-static-analysis :dropped :unknown}`
  and empty rosters. The panel renders that as an explicit \"nothing was
  looked at\", never as a clean bill of health — the donor tier could not
  express the difference, and its consumer skipped such views entirely.

  One entry per view-id that resolves to a DECLARED view; an unregistered id is
  skipped rather than given a fabricated `(no manifest)` row. A view whose
  producer stamps an unrecognised schema is skipped for the same reason
  [[rows]] degrades. nil-safe; distinct, first-seen order preserved."
  [view-ids]
  (into []
        (comp
         (distinct)
         (keep (fn [view-id]
                 (when-some [m (tool/read-view-manifest view-id)]
                   (when (supported? (:schema m))
                     (let [deps     (tool/read-view-dependencies view-id)
                           events   (tool/read-view-event-sites view-id)
                           manifest (:manifest m)]
                       {:view-id       view-id
                        :lowering      (:lowering m)
                        :basis         (:basis m)
                        :complete?     (:complete? m)
                        :loss          (:loss m)
                        :capabilities  (:capabilities manifest)
                        :view-cell     (:view-cell manifest)
                        :reactive?     (:reactive? manifest)
                        :subscriptions (:subscriptions deps [])
                        :event-sites   (:event-sites events [])
                        :site-facts    (:site-facts events)
                        ;; The compile-tier a11y findings. Static evidence like
                        ;; everything else on this row: the COMPILER minted the
                        ;; site ids and made the suppressed-vs-printed call;
                        ;; this tier only reads them. Nothing is re-derived and
                        ;; no check runs here.
                        :diagnostics   (:diagnostics manifest [])}))))))
        (or view-ids [])))

(defn unknown?
  "True when `x` is the substrate's explicit `:unknown` — the value a
  projection uses where it knows it lost something and cannot say how much,
  and where a row states a fact it does not have (`:root`).

  Exposed so the panel renders that marker as the absence it is rather than
  printing the keyword at a developer, and so the comparison lives beside the
  schema pin instead of being spelled out at each render site."
  [x]
  (= evidence/unknown x))
