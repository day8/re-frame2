(ns ^:no-doc re-frame.freehand.occurrences
  "INTERNAL, DEV-ONLY. The index that answers *what is connected right now*.

  ## The one requirement that justifies any live state at all

  An MCP inspector ATTACHES LATE. It connects to an application that has been
  running, and mounting, and re-rendering, for minutes before it arrived. The
  commit seam ([[re-frame.freehand.cell/emit-commit-evidence!]]) is
  fire-and-forget: it hands each selected commit to whatever sink is installed
  and keeps nothing, so a consumer that starts listening at attach time learns
  about commits from that instant onward and can never answer \"what is mounted
  right now\". Every other Freehand tool read is a projection of a value the
  caller already holds; this one is not, and that is the whole reason it exists.

  So the substrate keeps ONE row per connected occurrence, written at the
  commit seam under the dev gate — not installed by the inspector, because an
  index installed at attach time would have exactly the blind spot it exists to
  remove.

  ## Current state, not history

  Insert-or-update on the SELECTED commit, remove on disconnect. That is the
  entire lifecycle, and the shape is a consequence of the rf2-drpa3.167 ruling,
  which rejected porting the donor's per-occurrence evidence accumulator:

    - no lifetime render or batch counts — a row carries the LATEST committed
      generation, which is a fact about now, not a tally;
    - no accumulated target union — a row carries the reads THAT commit staged;
    - no lifecycle interval ledger, and no hide-versus-unmount labels — a
      disconnect removes the row and says nothing about why the host tore down;
    - no weak-keyed store and no reload migration — the rows are values under
      plain occurrence keys;
    - no cell-to-root attribution — root identity is not a fact this seam has,
      so no row claims one;
    - no second retention knob — nothing here is retained past disconnect, and
      Spec 009's ring stays the one history mechanism
      ([[re-frame.freehand.evidence/retention]]).

  A row per LIVE occurrence, keyed by the runtime occurrence, is what makes two
  simultaneous occurrences of one view distinguishable — the fact a
  declaration-keyed index ([[re-frame.freehand.registry]]) structurally cannot
  state, which is why these are two structures and not one.

  ## What this namespace does not know

  It stores values it is handed and reads them back. It builds nothing,
  validates nothing, and names no schema: the evidence schema's door is the
  commit seam's, and reaching it from here would put a second Freehand
  namespace onto the render path's path to that schema
  (`evidence-boundary-jvm-test` pins the roster closed at exactly two, and the
  reason it can stay closed is that this index holds plain maps).

  ## Staleness is bounded and stated

  Removal is the host's explicit teardown — Freehand's shell cleanup calling
  [[re-frame.freehand.cell/disconnect!]]. A host that is torn down some other
  way, without ever disconnecting its cells, leaves those rows behind. That is
  bounded staleness of the same kind [[re-frame.freehand.registry]] documents
  for a deleted declaration: one row per live occurrence, never a growing log,
  and each row states the generation it was written at so a reader can see how
  old it is. It is written down here rather than papered over with a
  weak-keyed store, which the ruling refused, or a liveness sweep, which no
  caller asked for.

  ## DEV-ONLY, gated at the call sites

  Every call site is inside `re-frame.interop/debug-enabled?` in
  [[re-frame.freehand.cell]] — the gate Closure folds to `false` under
  `:advanced`, taking the calls and every path into this namespace with them,
  so a production build carries no occurrence index because nothing reaches
  the atom for Closure to keep it alive for. The gate is at the call sites and
  not in here, so there is exactly one place to read the policy from; that the
  call sites really are gated is asserted over cell.cljc's own forms by
  `evidence-boundary-jvm-test`.

  Normative owner: [`spec/004-Views.md`](../../../../spec/004-Views.md); the
  instrumentation contract the reads over this index serve is
  [`spec/009-Instrumentation.md`](../../../../spec/009-Instrumentation.md).")

#?(:clj (set! *warn-on-reflection* true))

(defonce ^:private connected
  ;; occurrence key -> that occurrence's latest committed row. An atom
  ;; because connecting is a RUNTIME act (a host mounting), never a
  ;; compile-time one, and `defonce` so reloading THIS namespace does not
  ;; drop rows the application's live occurrences put here — the same
  ;; reasoning `registry/declared` carries, for the same reason.
  (atom {}))

(defn observe!
  "Record `row` as the current state of `occurrence`, answering `row`.

  INSERT-OR-UPDATE, which is the whole difference between an index and an
  accumulator: a second commit for one occurrence REPLACES its row rather than
  appending to it, so the index cannot grow along the time axis and a reader
  cannot mistake it for a render log. It is also what makes a compatible hot
  reload a non-event — the reloaded declaration re-renders the same occurrences,
  each commit replacing its own row, leaving one row per occurrence exactly as
  before.

  `occurrence` is the caller's runtime occurrence key. This namespace does not
  interpret it: two keys that are `=` are one occurrence, and that is the only
  property the index needs."
  [occurrence row]
  (swap! connected assoc occurrence row)
  row)

(defn forget!
  "Remove `occurrence`'s row, answering nil.

  The release seam. A disconnected occurrence is not a mounted occurrence with
  a `:disconnected` flag on it — it is absent, because `mounted-views` means
  *connected now* and a row that outlived its occurrence would be a reader's
  false positive. Removing the row releases the committed projection it held
  along with it; nothing survives the removal, which is why there is no
  disconnected state to read and no interval to report."
  [occurrence]
  (swap! connected dissoc occurrence)
  nil)

(defn row
  "The row `occurrence` currently holds, or nil."
  [occurrence]
  (get @connected occurrence))

(defn rows
  "Every current row, in a deterministic order.

  Ordered, because the order of a hash map is not a fact about an application
  and a tool that printed one would invite a reader to believe in it. Sorted by
  the row's own stated identity — view id, then occurrence key — as STRINGS,
  because an occurrence key is whatever the host's identity primitive mints and
  two of them are not required to be mutually comparable."
  []
  (->> (vals @connected)
       (sort-by (juxt #(str (:view-id %)) #(str (:key (:occurrence %)))))
       vec))

(defn clear!
  "Drop every row, answering nil. A test-fixture read — the shape
  `re-frame.trace.tooling/clear-trace-rings!` has, and for the same reason: a
  suite that mounts occurrences must be able to start from a known index rather
  than from whatever a preceding suite left connected."
  []
  (reset! connected {})
  nil)
