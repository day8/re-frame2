(ns day8.re-frame2-xray.panels.epoch.format
  "View-presentation string formatters for the Epoch panel (rf2-qkygs).

  ## Why a separate ns from `projection`

  `panels.epoch.projection` is the PURE-DATA step-derivation engine —
  epoch-record → ordered vector of pipeline-step rows (data-in /
  data-out). These fns are the OTHER side of that boundary: they turn
  a projected row's slots into the display STRINGS the view paints
  (`0.1ms` / `:my-ns/foo` / `guard :x` / `cancelled (on-exit)`). Parking them
  in the projection ns blurred the data/presentation line — a reader
  could not tell load-bearing derivation from cosmetic formatting, and
  the projection ns carried two jobs.

  This matches the rest of the codebase's pure-data / helpers split
  (`spec/Conventions.md` §Pure-data helpers as `.cljc`; the
  `*_helpers.cljc` per-theme convention). The view requires this ns
  for its per-row labels; the projection ns now reads as the pure
  step-derivation engine only.

  ## Pure-data + JVM-portable

  No DOM, no substrate runtime — display-string builders over already-
  projected row data. JVM-testable via `clojure -M:test`."
  (:require [clojure.string :as str]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]))

(defn format-duration-ms
  "Render a duration in ms as a short string (`0.1ms` / `12ms` /
  `1.2s`). Returns nil for non-numbers so the view can render an
  em-dash on a missing duration without guarding the call site."
  [ms]
  (when (number? ms)
    (cond
      (>= ms 1000) (str (/ (Math/round (double (* (/ ms 1000.0) 10))) 10.0) "s")
      (>= ms 10)   (str (Math/round (double ms)) "ms")
      :else        (let [rounded (/ (Math/round (double (* ms 10))) 10.0)]
                     (str rounded "ms")))))

(defn event-display
  "Render the dispatched event vector as a one-line monospace string
  for the DISPATCH row's target slot."
  [event-vec]
  (when (vector? event-vec)
    (str event-vec)))

(defn path-display
  "Render a db path vector as the `[:foo :bar 0]` repr used by every
  diff-style row. Returns `\"\"` for nil/empty."
  [path]
  (if (sequential? path)
    (str (vec path))
    ""))

(defn ns-keyword
  "Render an id as a clojure-style keyword string (`:my-ns/foo` or
  `:foo`). Falls through `str` for non-keywords."
  [id]
  (cond
    (qualified-keyword? id) (str ":" (namespace id) "/" (name id))
    (keyword? id)           (str ":" (name id))
    :else                   (str id)))

(def inline-verb-label
  "The synthetic verb a cascade row paints for an ANONYMOUS (inline-fn)
  action / guard (rf2-982212). An inline `(fn …)` declared directly in an
  `:on` / `:always` / `:entry` / `:exit` / `:after` slot has a FUNCTION
  OBJECT — not a keyword — as its `:action-id` / `:guard-id` (the runtime
  carries the bare fn; impl: `re-frame.machines.transition` resolve-guard /
  resolve-action), so `ns-keyword`'s `str` fallthrough rendered the raw
  fn-object toString (`#object[Function …]` / a minified blob). Inline fns
  are first-class in every slot (Spec 005), so the case is common; this
  legible placeholder stands in for the un-nameable fn. The row's
  KIND+PHASE badge, per-row outcome chip, and (in dev builds) the
  interleaved SOURCE BODY + click-to-source still carry WHAT it is — the
  verb only needs to read as 'an inline declaration', not garbage."
  "⟨inline⟩")

(defn verb-label
  "Render a cascade row's VERB id (an `:action-id` / `:guard-id`) as a
  legible label (rf2-982212). A keyword (a NAMED action/guard registered in
  the machine's `:actions` / `:guards` map) renders cleanly via `ns-keyword`
  (`:my-guard` / `:my-ns/foo`). A non-keyword id — an ANONYMOUS inline `(fn
  …)` carried bare by the runtime — renders the synthetic
  `inline-verb-label` placeholder rather than the raw fn-object toString
  (`#object[Function …]` / minified blob). nil renders an empty string so a
  slot that legitimately carries no verb (the pill + chip carry it) stays
  blank rather than printing `\"\"`-ish garbage. Pure-data."
  [id]
  (cond
    (nil? id)     ""
    (keyword? id) (ns-keyword id)
    :else         inline-verb-label))

(defn truncate
  "Truncate a string to `n` chars with an ellipsis. Pure fn used by
  the view layer for long arg displays in the FX table."
  ([s] (truncate s 60))
  ([s n]
   (let [s (str s)]
     (if (<= (count s) n)
       s
       (str (subs s 0 n) "…")))))

;; -- render-args size elision (rf2-yi0nr) --------------------------------
;;
;; The VIEWS step's col-2 render-args cell mounts the args VECTOR through
;; the shared `ei/edn-inspector`. The framework's wire-elision walker
;; (`re-frame.elision/elide-wire-value`) is SCHEMA-DRIVEN — it only
;; substitutes the `:rf.size/large-elided` marker on slots a schema marks
;; `{:large? true}`. View props are arbitrary positional render args, not
;; a schema-addressed db path, so they ride through un-elided AND Xray
;; reads RAW epoch records in-process (the egress walk never touches what
;; Xray sees — see `panels.app-db-diff-helpers` head comment). Net: a view
;; that takes a substantial map/collection prop — which ANY real app does —
;; dumped its FULL value into the cell.
;;
;; The fix mirrors the App-db panel's large-state treatment: oversized
;; values render as the framework's canonical `{:rf.size/large-elided …}`
;; sentinel, which the edn-inspector already paints as a yellow `● large ·
;; N bytes` chip (drill-in deferred per rf2-ndb13 — same affordance App-db
;; gets). The cap is purely a DISPLAY guard (Xray is read-only, in-process);
;; we reuse the framework's WIRE VOCABULARY (`:rf.size/large-elided` + the
;; `:bytes :type :reason :hint :path :handle` body keys per spec/015) so the
;; chip reads identically — `:reason :size` marks the tool-side, threshold-
;; driven origin (vs the framework's schema-declared `:reason :schema`).

(def render-args-byte-budget
  "Byte budget (UTF-8 `pr-str` length) above which a single render-arg
  ELEMENT is elided to the `:rf.size/large-elided` chip in the VIEWS
  col-2 cell (rf2-yi0nr). 512 bytes ≈ a small-to-mid prop map renders
  inline / browsable; a fat props payload (the machine-epochs runner's
  26-map steps vector is ~thousands of bytes) collapses to the size chip.
  Public so the unit test pins the threshold without re-deriving it."
  512)

(defn- pr-str-bytes
  "UTF-8 BYTE count of `v`'s `pr-str` form — the same size metric the
  framework's `re-frame.elision` walker uses for its `:bytes` slot, so a
  tool-side chip reports the same figure a schema-driven one would.

  Bytes on BOTH hosts, and that is load-bearing twice over: the figure is
  PUBLISHED (the edn-inspector's `● large · N bytes` chip) and it is
  ENFORCED (`render-args-byte-budget` decides whether an arg is elided at
  all). Until rf2-2rtt6.131 the CLJS arm was `(count s)` — UTF-16 CODE
  UNITS — beneath a docstring claiming byte-exactness 'isn't available' on
  CLJS. That premise was FALSE, and the false premise is why the defect
  survived: `TextEncoder` is UTF-8 by definition, is present in every
  browser and in Node, and carries no encoding argument a later edit could
  silently drop. While it stood, one budget had two rulers — the same
  render arg could ride through inline in the browser panel and elide under
  the JVM test.

  Code units agree with UTF-8 bytes only for ASCII, which is what makes the
  mistake fail open: on an ASCII payload the wrong expression prints the
  right number and nothing notices, until a prop grows an em-dash, an
  ellipsis or an emoji. An ASCII-only test cannot see it — hence the
  non-ASCII + ASTRAL fixtures pinning this in
  `panels/epoch/projection_cljs_test.cljc`.

  `TextEncoder` and not `Buffer.byteLength`: this ns compiles into the
  BROWSER panel bundle (and under `:advanced`), where `Buffer` is not
  there. Same helper shape as `re-frame.ssr.hash` and the hicasso lane's
  `utf8-bytes` (rf2-2rtt6.121)."
  [v]
  (let [s (pr-str v)]
    #?(:clj  (alength (.getBytes ^String s "UTF-8"))
       :cljs (let [^js enc (js/TextEncoder.)
                   ^js buf (.encode enc s)]
               (.-length buf)))))

(defn- render-arg-value-type
  "Coarse value-type tag for the size-marker body — mirrors the
  framework's `re-frame.elision/value-type` closed set so the chip's
  `· <type>` suffix reads identically."
  [v]
  (cond
    (map? v)    :map
    (vector? v) :vector
    (set? v)    :set
    (string? v) :string
    :else       :scalar))

(defn- ->size-marker
  "Wrap `v` in the framework's canonical `{:rf.size/large-elided <body>}`
  sentinel (spec/015 wire vocabulary) so the edn-inspector renders the
  shared yellow size chip. `:reason :size` distinguishes this tool-side,
  threshold-driven elision from the framework's schema-declared
  `:reason :schema`. `:path` is the positional arg index vector."
  [v idx]
  {:rf.size/large-elided
   {:path   [idx]
    :bytes  (pr-str-bytes v)
    :type   (render-arg-value-type v)
    :reason :size
    :hint   "Large render arg elided by Xray (display-only size cap); inspect via the live runtime."
    :handle [:rf.elision/at [idx]]}})

(defn elide-large-render-args
  "Size-guard a VIEWS-row render-args VECTOR before it mounts in the
  shared edn-inspector (rf2-yi0nr). Walks the TOP-LEVEL positional args:
  any element whose `pr-str` exceeds `render-args-byte-budget` is replaced
  by the `:rf.size/large-elided` size-marker (the SAME sentinel + chip the
  App-db panel surfaces for large state); small elements pass through
  untouched so a modest prop renders inline / browsable.

  Per-element (not whole-vector) so a render that mixes a small id arg
  with a fat props map elides ONLY the fat element — the operator still
  reads the cheap args inline. Returns the input unchanged when it is not
  a vector (defensive — the projection always stamps a vector) or carries
  no oversized element, so the no-op path allocates nothing new.

  Pure / JVM-portable — no DOM, no rf reads."
  [render-args]
  (if (vector? render-args)
    (reduce-kv
      (fn [acc idx v]
        (assoc acc idx
               (if (> (pr-str-bytes v) render-args-byte-budget)
                 (->size-marker v idx)
                 v)))
      render-args
      render-args)
    render-args))

(defn coeffect-row-display
  "Render a coeffect row's id → value pair as a one-liner for the
  view's diff-style add row (`+ [:session] {:user-id 42 …}`)."
  [{:keys [id value]}]
  (let [head (str "+ [" (ns-keyword id) "] ")
        tail (truncate (pr-str value) 80)]
    (str head tail)))

(defn phase-label
  "Render a machine action-ran `:phase` keyword as a UI label string."
  [phase]
  (case phase
    :exit            "exit"
    :transition      "transition"
    :entry           "entry"
    :always          "always"
    :after-action    "after-action"
    :initial-entry   "initial-entry"
    :destroy-exit    "destroy-exit"
    (when (keyword? phase) (name phase))))

(defn timer-reason-label
  "Render a timer-cancelled `:reason` keyword as a UI label string."
  [reason]
  (case reason
    :on-exit          "on-exit"
    :on-destroy       "on-destroy"
    :on-resolution    "on-resolution"
    :on-supersede     "on-supersede"
    :on-frame-destroy "on-frame-destroy"
    (when (keyword? reason) (name reason))))

(def ^:private start-cause->label
  "Map a `:rf.machine/started` `:cause` enum → the short tag rendered on
  the `[START]` badge (rf2-it4vt). The cause tells the operator HOW the
  machine came to life:

    :explicit — a deliberate eager `[:machine-id [:rf.machine/start]]` kick
                (xstate's `createActor(m).start()`).
    :lazy     — init folded into the first REAL event's epoch. Flags an
                ORDERING SMELL: something dispatched to the machine before
                it was explicitly started.
    :spawned  — the spawn fx pre-seeded the snapshot; init ran on the
                actor's first dispatch."
  {:explicit "explicit"
   :lazy     "lazy"
   :spawned  "spawned"})

(defn start-cause-label
  "Render a `:rf.machine/started` `:cause` enum as the short tag string the
  `[START]` badge carries (rf2-it4vt). Falls through `name` for an unknown
  cause keyword so a future enum value still paints, nil for non-keywords."
  [cause]
  (or (get start-cause->label cause)
      (when (keyword? cause) (name cause))))

(defn start-cause-smell?
  "True iff a `[START]` row's `:cause` is the ORDERING SMELL `:lazy`
  (rf2-it4vt) — something dispatched to the machine before it was explicitly
  started, so init folded into that event's epoch. The view paints the
  `:lazy` cause tag with a warning tone to flag it; `:explicit` / `:spawned`
  ride the muted/neutral tone (clean birth)."
  [cause]
  (= :lazy cause))

(defn cascade-row-label
  "Render a cascade row's human-readable verb (rf2-u69j7). Used by the
  view's per-row header. Pure-data; the view never reaches into a
  row's slots to compute its label."
  ;; rf2-iu3no — `:event` is no longer destructured: the `:no-op` verb
  ;; (the only case that read it) collapsed to "staying in {state}" and no
  ;; longer echoes the event (the focused-epoch Event header names it).
  [{:keys [kind action-id guard-id from-state to-state state reason
           machine-id show-machine-name? cause]}]
  (case kind
    ;; rf2-h710p item B — the GUARD verb is JUST the guard-id. The leading
    ;; "guard" word DUPLICATED the `[GUARD]` kind-pill (which already says
    ;; GUARD), so it is dropped. The state the guard gates rides the
    ;; `for <state>` clause (the item-6 pattern; `cascade-guard-for-state`)
    ;; rendered alongside the verb in the view — `[GUARD] for :open :may-close?`.
    ;; rf2-982212 — a NAMED guard renders its keyword; an INLINE `(fn …)`
    ;; guard (a bare fn id) renders the `⟨inline⟩` placeholder via
    ;; `verb-label` rather than the raw fn-object toString.
    :guard       (verb-label guard-id)
    ;; rf2-nhovk — the ACTION kind-pill + phase chip already convey kind +
    ;; phase, so the verb is JUST the action-id (the pill + chip + source body
    ;; carry the rest). rf2-982212 — a NAMED action renders its keyword; an
    ;; INLINE `(fn …)` action renders the `⟨inline⟩` placeholder via
    ;; `verb-label` (was: the raw fn-object toString); a nil action-id renders
    ;; empty. The redundant "{phase} action " prefix is dropped.
    :action      (verb-label action-id)
    ;; rf2-ge6uj ISSUE 3 — the TRANSITION row's verb is JUST the state
    ;; change `<before> → <after>`, made the focal point. The redundant
    ;; leading "transition" word (the KIND pill already says TRANSITION)
    ;; and the machine-name echo (`:door/main` — already the cascade
    ;; context) are DROPPED; the lower-line state/event repetition is
    ;; dropped in the view (`cascade-row-transition-details`).
    :transition  (str (if from-state (pr-str from-state) "?")
                      " → "
                      (if to-state (pr-str to-state) "?"))
    ;; rf2-bvwv4q — a parent-owned parallel `:always` ROUND's regional
    ;; transition. The verb is the region-relative state change `<from> →
    ;; <to>`; the `[ALWAYS]` pill + the `for <region> · round <n>` clause
    ;; (rendered in the view) carry the region + round index.
    :microstep   (str (if from-state (pr-str from-state) "?")
                      " → "
                      (if to-state (pr-str to-state) "?"))
    :timer       (str "timer " (when state (pr-str state))
                      (when reason (str " · " (name reason))))
    ;; rf2-iu3no — the benign unhandled-user-event no-op. The verb is the
    ;; CONSEQUENCE only: "staying in {state}" (the machine matched no
    ;; transition, so its state is unchanged). The `[NO OP]` kind-pill is
    ;; the sole marker; the focused-epoch Event header already names the
    ;; event — so the rf2-ugdas "no-op — <machine> received <event> in
    ;; <state>, no transition" sentence (badge + prefix + event echo +
    ;; suffix, all saying the same thing) is collapsed away.
    ;;
    ;; The machine name is kept ONLY when >1 machine is in play this epoch
    ;; (broadcast event / parallel regions) so the operator can tell WHICH
    ;; machine stood pat — `machine-cascade-rows` stamps `:show-machine-name?`
    ;; on the no-op row when the cascade spans multiple machine-ids. The
    ;; single-machine case drops it (the EVENT HANDLER section names the
    ;; machine above).
    :no-op       (str (when (and show-machine-name? machine-id)
                        (str (ns-keyword machine-id) " "))
                      "staying in "
                      (if state (pr-str state) "?"))
    ;; rf2-it4vt — the machine's BIRTH verb: "<machine-id> started in
    ;; {state}". The `[START]` kind-pill is the badge; the verb names WHICH
    ;; machine was born and its INITIAL logical state (`:state` off the
    ;; `:rf.machine/started` trace — a keyword / path-vector for flat /
    ;; compound, a region→state map for parallel; rendered verbatim). The
    ;; initial `:data` rides the body box (`cascade-row-source-body`); the
    ;; `:cause` rides a tag chip (`start-cause-label`).
    :start       (str (when machine-id (str (ns-keyword machine-id) " "))
                      "started in "
                      (if state (pr-str state) "?"))
    (str (when kind (name kind)))))

(defn transition-slot->spec-prefix
  "rf2-lai1qv — turn the substrate's EXACT spec-path discriminator
  (`:transition-slot`, stamped by `re-frame.machines.transition/
  transition-slot` on a selected transition's `:rf.machine/action-ran`
  emit) into the inline-source spec-path PREFIX — the path up to and
  including the slot, BEFORE the `:action` / `:guard` leaf. The caller
  appends the leaf.

  The discriminator carries the matched declaration faithfully — the
  candidate index for a multi-candidate VECTOR form (nil for the
  index-free single-map / keyword / vector-target forms, matching the
  macro's bare-slot keying), the `:after` delay-key, and the root-vs-state
  distinction — so this resolves the EXACT slot that the
  reconstruct-from-`source-state`/`event`/`phase` path could not (it
  hardcoded candidate 0, could not name the `:after` delay-key, and
  assumed a `:states` prefix even for a root `:on`).

    {:slot :on :event-key :submit :decl-path [:idle] :candidate-idx nil}
      => [:states :idle :on :submit]
    {:slot :on :event-key :go :decl-path [:a :b] :candidate-idx 2}
      => [:states :a :states :b :on :go 2]
    {:slot :on :event-key :logout :decl-path [] :root? true}
      => [:on :logout]                       ;; root / parallel-root :on
    {:slot :always :decl-path [:loading] :candidate-idx 1}
      => [:states :loading :always 1]
    {:slot :always :decl-path [:loading] :candidate-idx nil}
      => [:states :loading :always]          ;; single-map :always (k7yqod shape)
    {:slot :after :delay-key 1000 :decl-path [:idle] :candidate-idx nil}
      => [:states :idle :after 1000]

  Returns nil for an unrecognised / empty discriminator (the caller then
  falls back to the reconstruct-from-phase path). Pure data."
  [{:keys [slot event-key delay-key candidate-idx decl-path]}]
  (let [;; Root `:on` lives OUTSIDE `:states` (decl-path []); a state-scoped
        ;; slot interleaves `:states`. `state-spec-path-prefix` returns nil
        ;; for an empty path, which is exactly the root prefix.
        prefix (or (proj/state-spec-path-prefix decl-path) [])
        slot-path (case slot
                    :on    (when event-key (conj prefix :on event-key))
                    :always (conj prefix :always)
                    :after (when (some? delay-key) (conj prefix :after delay-key))
                    nil)]
    (when slot-path
      (cond-> slot-path
        ;; A vector-candidate form carries the matched index; index-free
        ;; forms (candidate-idx nil) stay at the bare-slot path, matching
        ;; the macro's keying (rf2-k7yqod / rf2-lai1qv).
        (some? candidate-idx) (conj candidate-idx)))))

(defn cascade-row-source-key
  "Spec-path tuple used to look up a cascade row's source-coord on the
  registered machine spec. Pure-data; the view layer reuses this for the
  coord lookup so the source-link affordance reads off ONE authoritative
  key.

  rf2-lai1qv — when the row carries an EXACT spec-path (`:spec-path`, an
  explicit tuple) or the substrate's spec-path discriminator
  (`:transition-slot`, for an inline transition `:action`), those WIN over
  the reconstruct-from-`source-state`/`event`/`phase` path below. The
  reconstruction is the fallback for rows lacking the carried slot
  (named-handler keys, `:exit` / `:entry` boundary actions, timers, and
  legacy traces without the discriminator).

  Per rf2-npvsx / rf2-vqja2 the lookup target differs by tuple shape:
  - Named `[:guards <id>]` / `[:actions <id>]` keys resolve to the
    co-located element entry's `:source-coords` / `:source-code`.
  - Reference-site `[:states ...]` keys resolve to the `:source-coords`
    co-located on the nearest enclosing `:states`-tree map node
    (`projection/state-node-source-coords`).
  The view's `named-element-key` discriminator routes between the two.

  Dispatch (rf2-u69j7 baseline + rf2-wwc3j inline-fn extensions):

  - `:action` with a keyword `:action-id` → `[:actions <id>]`
    (definition-site stamp; the named-handler path).
  - `:action` with an inline `:action-id` (fn) — derive from the row's
    `:phase` + state slot (`:source-state` / `:target-state`, stamped
    by `enrich-cascade-rows`):
    - `:entry` / `:initial-entry` → `[:states <state>... :entry]`
      (target-state)
    - `:exit` / `:destroy-exit`   → `[:states <state>... :exit]`
      (source-state)
    - `:transition`               → `[:states <state>... :on <event> :action]`
      (source-state + event-id)
    - `:always`                   → `[:states <state>... :always :action]`
      (the index-free single-map shape, mirroring the single-map `:on`
      convention — the macro keys a single-map `:always` at the bare
      `:always` path, rf2-k7yqod). The view read-back PROBES BOTH this
      index-free path AND the index-0 vector-candidate path
      (`[:states <state>... :always 0 :source-code :action]`) so a
      vector `:always [{…}]` still resolves; richer per-candidate index
      resolution (carrying the matched always-index from the substrate)
      is the rf2-lai1qv follow-on.
    - `:after-action`             → `[:states <state>... :after :action]`
      (best-effort: timer fn-form path; the macro doesn't yet stamp
      per-delay `:after` coords; D2 follow-on bead handles richer index).
  - `:guard` with a keyword `:guard-id` → `[:guards <id>]`
    (definition-site stamp; the named-guard path).
  - `:guard` with an inline `:guard-id` (fn) — derive from state +
    event-id:
    `[:states <state>... :on <event> :guard]` (best-effort: no vector
    transition-option index — for the common single-map transition).
  - `:transition` → `[:states <from-state>... :on <event>]`
    (the transition map's spec-path; opens the operator on the
    transition literal in the spec).
  - `:timer` → `[:states <state>...]`
    (D1 minimum-viable: the parent state's source-coord chip; richer
    per-`:after` coord is the D2 follow-on bead's surface)."
  [{:keys [kind action-id guard-id phase source-state target-state event-id
           spec-path transition-slot]
    timer-state :state}]
  (let [source-prefix (proj/state-spec-path-prefix source-state)
        target-prefix (proj/state-spec-path-prefix target-state)
        timer-prefix  (proj/state-spec-path-prefix timer-state)
        ;; rf2-lai1qv — the EXACT slot prefix the substrate's discriminator
        ;; resolves to (candidate index / `:after` delay-key / root), or nil
        ;; when the row carries no discriminator (boundary actions, named
        ;; handlers, legacy traces).
        slot-prefix   (when (map? transition-slot)
                        (transition-slot->spec-prefix transition-slot))]
    (case kind
      :action
      (cond
        ;; Named-handler path (keyword id) — definition-site stamp.
        (keyword? action-id) [:actions action-id]
        ;; rf2-lai1qv — an EXACT carried spec-path wins outright.
        (vector? spec-path) spec-path
        ;; rf2-lai1qv — the substrate's spec-path discriminator addresses
        ;; the precise inline-source slot for the transition `:action`
        ;; (candidate-vector `:on`, nonzero `:always` candidate, `:after`
        ;; delay-key, root `:on`) — append the `:action` leaf.
        slot-prefix (conj slot-prefix :action)
        ;; Inline-fn path — slot stamp under the relevant state.
        (contains? #{:entry :initial-entry} phase)
        (when target-prefix (conj target-prefix :entry))
        (contains? #{:exit :destroy-exit} phase)
        (when source-prefix (conj source-prefix :exit))
        (= :transition phase)
        (when (and source-prefix event-id)
          (conj source-prefix :on event-id :action))
        (= :always phase)
        ;; Fallback for a row WITHOUT the discriminator (legacy trace): the
        ;; index-free single-map shape (rf2-k7yqod). The macro keys a
        ;; single-map `:always` at the bare `:always` path; the view's
        ;; read-back additionally probes the index-0 vector-candidate path
        ;; so a `:always [{…}]` vector resolves too. The discriminator path
        ;; above carries the EXACT matched index when present (rf2-lai1qv).
        (when source-prefix (conj source-prefix :always :action))
        (= :after-action phase)
        (when source-prefix (conj source-prefix :after :action))
        :else nil)

      :guard
      (cond
        (keyword? guard-id) [:guards guard-id]
        ;; rf2-lai1qv — an EXACT carried spec-path wins (the substrate may
        ;; stamp `:spec-path` on the `:rf.machine/guard-evaluated` trace for
        ;; an inline candidate-vector guard; `guard-cascade-row` carries it).
        (vector? spec-path) spec-path
        :else
        (when (and source-prefix event-id)
          (conj source-prefix :on event-id :guard)))

      :transition
      (when (and source-prefix event-id)
        (conj source-prefix :on event-id))

      :timer
      ;; The row's `:state` is the cancelled state vector (substrate
      ;; payload). D1 minimum-viable shape: point at the parent state's
      ;; spec-path so the operator orients on the `:after`-bearing node.
      (or timer-prefix source-prefix target-prefix)

      nil)))

(defn cascade-outcome-label
  "Render a cascade row's outcome for the view's outcome chip
  (rf2-u69j7). Pure-data.

    :guard       → `pass | fail | threw`
    :action      → `ok | threw` (the action's outcome map is rich;
                                 the chip carries only the headline)
    :timer       → `cancelled (<reason>)`

  rf2-cdgva — the `:transition` row no longer carries an outcome label.
  The prior `N microstep(s)` summary was redundant: every `:always`
  microstep (N>0) is itself a first-class cascade row in the same
  mini-pipeline (post akvfe/2hj0h), so the count merely tallied rows
  already present; when N=0 (the common case) it was pure noise. The
  prominent `<before> → <after>` header verb is the transition's whole
  story, so a `:transition` row returns nil here (the no-op default)."
  [{:keys [kind outcome threw? reason]}]
  (case kind
    :guard      (if (keyword? outcome) (name outcome) nil)
    :action     (cond
                  threw?                "threw"
                  (= :ok outcome)       "ok"
                  (map? outcome)        "ok"
                  (keyword? outcome)    (name outcome)
                  :else                 nil)
    :timer      (str "cancelled"
                     (when reason (str " (" (name reason) ")")))
    ;; rf2-iu3no — the benign no-op carries NO outcome chip. The "[NO OP]"
    ;; kind-pill + the "staying in {state}" verb (`cascade-row-label`) are
    ;; the whole story; the rf2-ugdas "ignored" chip was a third restatement.
    nil))

;; rf2-2hj0h item 6 — the merged ACTION badge is followed by ` for <state> `
;; then the action name, so an action row's header reads
;; `[EXIT ACTION] for :closed :clear-hold` / `[ENTRY ACTION] for :open
;; :count-open`. `<state>` is the state the action BELONGS TO:
;;
;;   :exit / :destroy-exit                       → the EXITED state
;;                                                 (`:source-state`)
;;   :entry / :initial-entry / :always /
;;   :after-action                               → the ENTERED state
;;                                                 (`:target-state`)
;;   :transition (the LCA action)                → the source state
;;                                                 (the change anchors there)
;;
;; The state slots are stamped by `enrich-cascade-rows` (the same
;; `:source-state` / `:target-state` slots `cascade-row-source-key` reads),
;; so the view never re-walks the trace. The state renders via `pr-str` (it
;; may be a keyword OR a path vector / region→state map for compound /
;; parallel machines), matching the transition headline's state rendering.

(defn cascade-action-for-state
  "The state an `:action` cascade row belongs to (rf2-2hj0h item 6), for
  the ` for <state> ` clause of the merged-action-badge header. Reads the
  row's `:phase` to pick `:source-state` (exit phases) vs `:target-state`
  (entry / post-entry phases); falls back across the two when the
  phase-preferred slot is absent (best-effort enrichment). Returns nil when
  neither state was stamped — the view then omits the ` for <state> `
  clause and renders just the action name. Pure-data."
  [{:keys [phase source-state target-state]}]
  (let [exit-phase? (contains? #{:exit :destroy-exit :transition} phase)]
    (if exit-phase?
      (or source-state target-state)
      (or target-state source-state))))

(defn cascade-guard-for-state
  "The state a `:guard` cascade row gates (rf2-h710p item B), for the
  ` for <state> ` clause of the GUARD-row header — `[GUARD] for <state>
  <guard-name>` (e.g. `[GUARD] for :open :may-close?`). A guard gates the
  ENTRY into / fire of a transition OUT OF its source state, so the
  belongs-to state is the transition's `:source-state` (the state whose
  `:on` map carries the guard — the same slot `enrich-cascade-rows` stamps
  off the surrounding `:transition`'s `:from-state`). Falls back to
  `:target-state` when no source was stamped (best-effort enrichment).
  Returns nil when neither state was stamped — the view then omits the
  ` for <state> ` clause and renders just the guard name. Pure-data."
  [{:keys [source-state target-state]}]
  (or source-state target-state))

(defn history-kind-label
  "Render a history `:kind` keyword (`:shallow` / `:deep`) as a UI label."
  [kind]
  (case kind
    :shallow "shallow"
    :deep    "deep"
    (when (keyword? kind) (name kind))))

(defn history-restored-headline
  "Render the headline string for a `:rf.machine.history/restored` record
  (rf2-mle6e.5) — the one-line answer to 'why did this re-entry land HERE?':

    :recorded → 'restored [:player] from DEEP history · [:player :paused] → [:player :paused]'
    :default  → 'restored [:player] from DEFAULT (no recording) via :default-target → [:player :playing]'

  Reads the spec/009 §`:rf.machine.history/restored` shape:
  `:compound-path` `:kind` `:source` `:fallback` `:restored-config`
  `:resolved-leaf`. On `:recorded` the headline shows the recorded config
  that drove the restore → the concrete resolved leaf; on `:default` (the
  compound was never exited, or the recorded path was dangling after a hot
  reload) it names the fallback that resolved the leaf. Pure-data."
  [{:keys [compound-path kind source fallback restored-config resolved-leaf]}]
  (let [kind-label (history-kind-label kind)
        compound   (path-display compound-path)
        leaf       (pr-str resolved-leaf)]
    (if (= :default source)
      (str "restored " compound " from DEFAULT (no recording)"
           (when fallback (str " via :" (name fallback)))
           " → " leaf)
      (str "restored " compound " from "
           (when kind-label (str (str/upper-case kind-label) " "))
           "history · " (pr-str restored-config) " → " leaf))))

(defn history-recorded-headline
  "Render the headline string for a `:rf.machine.history/recorded` record
  (rf2-mle6e.5) — 'this exit wrote the compound's config into :rf/history':

    first write   → 'history recorded [:player] = [:player :paused]'
    later write   → 'history advanced [:player] from [:player :playing] to [:player :paused]'

  Reads the spec/009 §`:rf.machine.history/recorded` shape: `:compound-path`
  `:kind` `:recorded-config` `:prev-config` (absent on the first-ever write).
  Pure-data."
  [{:keys [compound-path recorded-config prev-config]}]
  (let [compound (path-display compound-path)]
    (if (some? prev-config)
      (str "history advanced " compound
           " from " (pr-str prev-config)
           " to " (pr-str recorded-config))
      (str "history recorded " compound " = " (pr-str recorded-config)))))

(defn handler-flavour-label
  "Human-readable label for a handler flavour keyword — the HANDLER step's
  verb.

  EP-0018 collapsed the three public event registrars onto the ONE public
  `reg-event` form, so the verb the panel surfaces is `reg-event` for BOTH
  the db-only and the fx-bearing event flavours. The internal
  `:db-only` / `:effectful` discriminator (read off the trace stream
  by `projection/handler-flavour`) still drives WHICH sections the HANDLER
  body renders (a `:db`-only diff vs a `:db` diff plus per-fx blocks) — that
  observed effect-shape distinction is real and useful — but it is a
  behavior-based internal classification (what the handler returned), not a
  user-facing registrar name.

  `:reg-machine` keeps its own verb: machines are a distinct registration
  concept (`reg-machine`), untouched by the event-registration collapse."
  [flavour]
  (case flavour
    :db-only      "reg-event"
    :effectful    "reg-event"
    :reg-machine  "reg-machine"
    (str flavour)))

(def machine-start-marker
  "The reserved synthetic marker a machine receives as its creation kick
  (`[<machine-id> [:rf.machine/start]]`). It is NOT a real trigger — per F‴
  (rf2-gl588) it runs the initial-entry cascade then STOPS (a PURE init-kick,
  xstate's `createActor(m).start()` / `xstate.init`)
  (ai/findings/2026-06-03.machine-creation-bootstrap-review.md §1). Renamed
  from `:rf.machine/bootstrap` (pre-alpha, no back-compat shim). The EVENT
  HANDLER orientation line (rf2-akvfe) suppresses itself for a pure creation
  kick — the birth story rides the `[START]` cascade row, not a
  'Processing …' line."
  :rf.machine/start)

;; rf2-akvfe — the rf2-18oe3 DISPATCH gloss (`machine-event-gloss`, the
;; 'this means the machine <id> received the trigger <trigger>' sub-line)
;; is RETIRED. It is superseded by the structured EVENT HANDLER orientation
;; line — `Processing [TRIGGER] <vec> for [MACHINE] <id> in [STATE] <state>`
;; — projected by `projection/machine-event-orientation` and rendered with
;; grey chip-labels + code-formatted values under the EVENT HANDLER heading
;; (a better location, more scannable, and it carries the pre-transition
;; STATE the gloss never showed). The value-display formatting is below.

(defn orientation-value
  "Render an EVENT HANDLER orientation-line VALUE (the trigger vector, the
  machine id, or the pre-transition state) as its code-formatted display
  string (rf2-akvfe). A keyword renders via `ns-keyword` (`:door/main`); a
  vector / other value renders via `pr-str` (the full trigger vector incl.
  args, `[:door/close 42]`). nil renders the muted em-dash placeholder so a
  missing slot stays visible rather than collapsing. Pure-data."
  [v]
  (cond
    (nil? v)     "—"
    (keyword? v) (ns-keyword v)
    :else        (pr-str v)))
