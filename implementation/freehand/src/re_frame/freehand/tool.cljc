(ns re-frame.freehand.tool
  "The Freehand TOOL-TIER reader door — what an inspector reads a
  declaration's compile-time analysis through.

  One namespace, one question: **what did the compiler statically learn about
  this view?** The answer is the manifest the compiled tier already builds —
  its subscription, event, render-slot, trusted-markup, committed-frame and
  boundary-crossing rosters, its capability set, its ViewCell verdict, and the
  compile-tier `:diagnostics` findings. Nothing here computes anything; every
  read is a projection of a value the declaration is already carrying.

  ## Two doors, because there are two callers

  [[view-manifest]] takes a VALUE, and is TOTAL over every value there is: a
  sweep over a namespace's publics hands it strings, numbers, plain functions
  and interpreted views, and each of those answers `nil` rather than throwing,
  because a reader that throws on the first non-view is a reader that cannot
  sweep. `nil` means \"nothing statically known\" in every case.

  [[read-view-manifest]], [[read-view-dependencies]] and
  [[read-view-event-sites]] take a view ID, because an MCP inspector holds no
  values. It arrives over a wire with `:app.people/people-list` and asks what
  that view declares — a question a value-taking reader cannot be asked. The id
  resolves through the dev-only [[re-frame.freehand.registry]] index each
  declaration records itself in, so an inspector that attaches LATE, to an
  already-running application, still sees every view it declared.

  Neither door is the other's arity. `(view-manifest :a/keyword)` answers `nil`
  because a keyword is not a view, and it must keep answering that — the sweep
  depends on it — so the id-taking reads carry their own names.

  ## Why it is not `v/manifest`

  [[re-frame.freehand/manifest]] is the APPLICATION's read: it is asked about a
  view, by code that knows it has one, and it answers the manifest bare. The
  reads here answer it inside the four-axis evidence projection every Freehand
  evidence surface states — `:scope`, `:basis`, `:complete?`, `:loss` — because
  a tool has to be able to tell an INTERPRETED declaration (no analysis, so
  nothing knowable) from a compiled one whose rosters really are empty. That is
  `:basis :static-proof` with `:loss nil` against `:basis :opaque` with
  `{:reason :no-static-analysis :dropped :unknown}`, and it is the whole reason
  the projection vocabulary exists: *unknown must not look like none*
  ([[re-frame.freehand.evidence]], D012/D020).

  ## What this door deliberately is NOT

  It is a READER, not a tool framework. There is no accumulator — the donor's
  per-occurrence evidence accumulator was ruled out permanently (rf2-drpa3.167,
  REPLACE), not merely deferred — no root registry, no interval log and no
  history store: retention is Spec 009's ring, and nothing here retains
  anything at all. The declared-view index it reads through holds ONE row per
  declaration and no occurrence, generation or count.

  So none of these reads can say what is MOUNTED, and none of them pretends
  to. Current-occurrence reads (`mounted-views`, rf2-xftdv) and the bounded
  `explain-render` over the Spec 009 ring (rf2-cpfbg) are separate slices with
  separate owners and their own release seams.

  ## DEV-ONLY

  Every id-taking read is gated on `re-frame.interop/debug-enabled?` and
  answers `nil` under `:advanced` + `goog.DEBUG=false`, which is also what the
  index it reads holds there: nothing. A consumer distinguishes that from an
  unregistered view the way it distinguishes any absence — by asking about a
  view it knows the application declares.

  Normative owner: [`spec/004-Views.md`](../../../../spec/004-Views.md);
  the instrumentation contract these reads serve is
  [`spec/009-Instrumentation.md`](../../../../spec/009-Instrumentation.md)."
  (:require [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.evidence :as evidence]
            [re-frame.freehand.registry :as registry]
            [re-frame.interop :as interop]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The value-taking read — total, because a sweep cannot afford otherwise
;; ---------------------------------------------------------------------------

(defn view-manifest
  "The compile-time manifest `x` carries, or `nil` when there is none.

      (tool/view-manifest people-list)
      ;; => {:view-id       :app.people/people-list
      ;;     :grammar       :re-frame.freehand/v1
      ;;     :subscriptions [{:sid … :query [:person 7] :source-coord {…} :path [0]}]
      ;;     :events [] :slots [] :html-sites [] :frame-ops []
      ;;     :diagnostics   [{:sid … :id :rf.ui.compile/a11y-click-non-interactive
      ;;                      :tag :div :path [0] :suppressed? false}]
      ;;     :capabilities  #{:sub}
      ;;     :reactive?     true
      ;;     :view-cell     :present
      ;;     :crossings     [{:view-id … :lowering :compiled :source-coord {…} :path [1]}]}

  TOTAL over every value, which is what makes it a tool read rather than an
  application one: a sweep over a namespace's publics hands this function
  strings, numbers, plain functions and interpreted views, and each of those
  answers `nil` rather than throwing. `nil` means the same thing in every
  case — the compiler knows nothing statically about this value — and a caller
  that needs to tell an interpreted DECLARATION from a non-view asks
  [[re-frame.freehand/view?]], which is the predicate that question belongs to.

  The manifest is the declaration's own data, returned as-is. Its shape is
  documented once, on [[re-frame.freehand.compiler/structural-manifest]]."
  [x]
  (when (descriptor/view? x)
    (descriptor/manifest x)))

;; ---------------------------------------------------------------------------
;; The id-taking reads — the shape every one of them answers in
;; ---------------------------------------------------------------------------

(defn- static-proof
  "The four axes a COMPILED declaration's roster read states: every site the
  grammar can see, proved from what the compiler saw, complete and lossless.

  Complete is a claim relative to `:scope` and to nothing else. The analyzer
  walked the whole body and the grammar is finite, so the roster is every site
  there CAN be — which is a different and much smaller claim than knowing which
  of them ran."
  [roster]
  (evidence/projection
    (assoc roster
           :scope     :possible-sites
           :basis     :static-proof
           :complete? true
           :loss      nil)))

(defn- no-static-analysis
  "The four axes an INTERPRETED declaration's roster read states: the same
  scope, on an `:opaque` basis, INCOMPLETE, with the loss named.

  This is the arm the projection vocabulary exists for. An interpreted body has
  no analysis step, so the number of sites it could reach is not merely
  uncounted — it is unknowable without running it, and an empty roster reported
  as `:complete? true :loss nil` would be saying it found nothing where what it
  means is that it never looked."
  [roster]
  (evidence/projection
    (assoc roster
           :scope     :possible-sites
           :basis     :opaque
           :complete? false
           :loss      {:reason  :no-static-analysis
                       :dropped evidence/unknown})))

(defn- envelope
  "Stamp a projection with what a consumer validates before it parses anything:
  the schema version, WHICH read answered, the view it answered about, and the
  lowering that decides how much the answer could have said.

  `:lowering` is NAMED rather than inferred from the projection's basis, for the
  reason one evidence schema covers both execution modes at all: a tool must not
  have to reverse-engineer how a view was compiled out of how much its evidence
  could claim."
  [read-kind view-id view projection]
  (assoc projection
         :schema   evidence/schema
         :read     read-kind
         :view-id  view-id
         :lowering (:lowering (descriptor/describe view))))

(defn read-view-manifest
  "What the compiler statically knows about the view declared as `view-id`,
  inside the projection that says how far to trust it. `nil` for an id this
  build declared no view under, and `nil` in a production build.

      (tool/read-view-manifest :app.people/people-list)
      ;; => {:schema    :re-frame.freehand.evidence/v1
      ;;     :read      :view-manifest
      ;;     :view-id   :app.people/people-list
      ;;     :lowering  :compiled
      ;;     :scope     :possible-sites
      ;;     :basis     :static-proof
      ;;     :complete? true
      ;;     :loss      nil
      ;;     :manifest  {…}}

  The manifest rides VERBATIM under `:manifest` — the declaration's own data,
  not a second projection of it. One value published twice cannot drift; two
  projections of one value eventually do, and the reader has no way to tell
  which they are holding. Its shape is documented once, on
  [[re-frame.freehand.compiler/structural-manifest]], and the compile-tier
  a11y findings ride inside it under `:diagnostics` — including the SUPPRESSED
  ones, carrying the author's reason, which is a suppression's only trace.

  An interpreted declaration answers `:manifest nil` with the `:opaque` basis
  and an explicit `{:reason :no-static-analysis :dropped :unknown}` loss, never
  an empty roster that would read as a clean bill of health."
  [view-id]
  (when interop/debug-enabled?
    (when-some [view (registry/lookup view-id)]
      (envelope :view-manifest view-id view
                (evidence/manifest-projection view)))))

;; ---- query-shape honesty ----------------------------------------------------

(defn- literal-form?
  "True when `x` is pure literal DATA — a scalar, a collection built wholly of
  literals, or a `(quote …)` — with NO free symbol and NO call.

  A subscription query that satisfies this IS the authored runtime shape and is
  safe to project verbatim. Anything carrying a free symbol (a captured local:
  `(v/sub [:person/by-id id])`) or a call is a form whose value does not exist
  until the view renders, and showing it as though it were the query would be
  showing a reader source code where they asked for data."
  [x]
  (cond
    (or (nil? x) (boolean? x) (number? x) (char? x) (string? x) (keyword? x)) true
    (symbol? x) false
    (vector? x) (every? literal-form? x)
    (set? x)    (every? literal-form? x)
    (map? x)    (every? (fn [[k v]] (and (literal-form? k) (literal-form? v))) x)
    (seq? x)    (= 'quote (first x))
    :else       false))

(defn- dependency-site
  "One subscription site, with its query-shape honesty stated per entry.

  A fully-literal query is projected as the value it is (`:dynamic? false`). A
  query carrying a captured local is `:dynamic? true` and its literal
  `:query-id` is still shown when the head is one — the part the compiler
  really does know — while the runtime argument is left unsaid rather than
  invented."
  [{:keys [query] :as site}]
  (merge (select-keys site [:sid :source-coord :path])
         (if (literal-form? query)
           {:dynamic? false :query query}
           (cond-> {:dynamic? true}
             (and (vector? query) (keyword? (first query)))
             (assoc :query-id (first query))))))

(defn read-view-dependencies
  "The reactive dependency SITES the view declared as `view-id` declares — its
  `v/sub` sites — read from the manifest and so available BEFORE anything
  mounts. `nil` for an unknown id, and `nil` in a production build.

      (tool/read-view-dependencies :app.people/people-list)
      ;; => {:schema … :read :view-dependencies :view-id … :lowering :compiled
      ;;     :scope :possible-sites :basis :static-proof
      ;;     :complete? true :loss nil
      ;;     :subscriptions [{:sid … :source-coord {…} :path [0]
      ;;                      :dynamic? false :query [:person/by-id 7]}
      ;;                     {:sid … :source-coord {…} :path [1]
      ;;                      :dynamic? true :query-id :person/by-id}]}

  SITES, not reads. The roster is one entry per LEXICAL site, so a site inside
  a keyed list is one entry however many times it runs; what a render actually
  subscribed to is a different quantity and is not derivable from this one.

  `:source-coord` is total or absent — a whole `{:file :line :column}`, or
  omitted entirely for a declaration that carries no reader location — never
  partial and never invented."
  [view-id]
  (when interop/debug-enabled?
    (when-some [view (registry/lookup view-id)]
      (envelope :view-dependencies view-id view
                (if-some [m (descriptor/manifest view)]
                  (static-proof {:subscriptions (mapv dependency-site (:subscriptions m))})
                  (no-static-analysis {:subscriptions []}))))))

(def ^:private event-site-facts
  "The per-entry facts an event site in the manifest CAN state today.

  Published beside the roster because the analyzer records more than this and
  the manifest does not carry it: `:prop`, `:classification`, `:serializable?`,
  `:sync?` and the authored `:handler` are collected in the compiler's site
  index and dropped by the manifest projection (rf2-z0blg — the same defect
  rf2-hytu5 fixed for `:diagnostics`).

  So a reader seeing no `:handler` on a site is seeing a fact this build does
  not publish, NOT a site with no handler — and the difference has to be
  legible from the answer rather than from a changelog. Naming what an entry
  states is how an empty-handed roster says which hand is empty. The set
  retires with the gap it names."
  #{:sid :source-coord :path})

(defn read-view-event-sites
  "The event-handler SITES the view declared as `view-id` declares, read from
  the manifest. `nil` for an unknown id, and `nil` in a production build.

      (tool/read-view-event-sites :app.people/people-list)
      ;; => {:schema … :read :view-event-sites :view-id … :lowering :compiled
      ;;     :scope :possible-sites :basis :static-proof
      ;;     :complete? true :loss nil
      ;;     :event-sites [{:sid … :source-coord {…} :path [1 0]}]
      ;;     :site-facts  #{:sid :source-coord :path}}

  The ROSTER is complete and lossless: every event site the grammar can see is
  here. What each entry can SAY is narrower than what the compiler knows, and
  `:site-facts` states exactly how narrow — see [[event-site-facts]]. Nothing
  is inferred to fill the gap: this read answers WHERE a view dispatches from,
  and until the manifest publishes the classification it will not pretend to
  answer WHAT it dispatches."
  [view-id]
  (when interop/debug-enabled?
    (when-some [view (registry/lookup view-id)]
      (envelope :view-event-sites view-id view
                (if-some [m (descriptor/manifest view)]
                  (static-proof {:event-sites (:events m)
                                 :site-facts  event-site-facts})
                  (no-static-analysis {:event-sites []
                                       :site-facts  event-site-facts}))))))
