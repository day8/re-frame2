(ns re-frame.freehand.tool
  "The Freehand TOOL-TIER reader door — what an inspector reads a view through.

  Two questions, and the split between them is the whole shape of this
  namespace. **What did the compiler statically learn about this view?** is
  answered from the manifest the compiled tier already builds — its
  subscription, event, render-slot, trusted-markup and boundary-crossing
  rosters, its capability set, its ViewCell verdict, and the compile-tier
  `:diagnostics` findings — so those reads compute nothing and retain nothing;
  each is a projection of a value the declaration is already carrying. **What
  is mounted right now, and why did it render?** cannot be answered that way,
  and [[read-mounted-views]] / [[explain-render]] say below exactly what they
  read instead and what it costs.

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
  history store. Nothing in this namespace retains anything; the two structures
  it reads are a declared-view index holding ONE row per declaration
  ([[re-frame.freehand.registry]]) and a current-occurrence index holding ONE
  row per CONNECTED occurrence that drops it at disconnect
  ([[re-frame.freehand.occurrences]]). Neither has a time axis. History is
  Spec 009's retained ring, under Spec 009's one knob, and [[explain-render]]
  folds that ring at READ time rather than keeping a fold of its own.

  Which is why every read here can be asked when it will be honest and no
  earlier. A declaration read answers before anything mounts. A
  current-occurrence read answers about now and says nothing about what once
  was. An explanation answers only as far back as the configured window
  reaches, and reports the rest as loss.

  ## DEV-ONLY

  Every read except the total value-taking [[view-manifest]] is gated on
  `re-frame.interop/debug-enabled?` and answers `nil` under `:advanced` +
  `goog.DEBUG=false`, which is also what both indexes hold there: nothing. A
  consumer distinguishes that from an unregistered view the way it
  distinguishes any absence — by asking about a view it knows the application
  declares.

  Normative owner: [`spec/004-Views.md`](../../../../spec/004-Views.md);
  the instrumentation contract these reads serve is
  [`spec/009-Instrumentation.md`](../../../../spec/009-Instrumentation.md)."
  (:require [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.evidence :as evidence]
            [re-frame.freehand.occurrences :as occurrences]
            [re-frame.freehand.registry :as registry]
            [re-frame.interop :as interop]
            [re-frame.trace.tooling :as trace-tooling]))

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
      ;;     :events [] :slots [] :html-sites []
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
    ;; Vectors and sets are one case, not two: both are literal exactly when
    ;; every element is, and the element walk does not care which it is.
    (or (vector? x) (set? x)) (every? literal-form? x)
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
  "The CLOSED set of facts one event-site row states.

  Every member is present on every entry, which is what lets this set be
  asserted as an EQUALITY against the entries themselves rather than as a
  decoration beside them. Naming what an entry states is how a row says which
  hand is empty.

  The narrow set this used to name — coordinates and nothing else — WAS the gap:
  `:prop`, `:classification`, `:serializable?`, `:sync?` and `:handler` were
  recorded in the analyzer's site index and dropped by the manifest projection,
  so an event-site read could say WHERE a view dispatches from and not WHAT it
  dispatches (rf2-z0blg). The manifest publishes them now, so this names the
  whole vocabulary rather than the remainder of an unpublished one.

  An unknown fact is STATED unknown rather than left out — `:handler` is the
  `:opaque` marker and `:event-id` is `nil` exactly where the handler is not
  statically known data ([[event-site]]) — so a reader never has to work out
  whether a missing key means the site lacks the fact or the build lacks it."
  #{:sid :source-coord :path :prop :classification :serializable? :sync?
    :handler :event-id})

(defn- event-site
  "One event site, with its handler-shape honesty stated per entry — the rule
  [[dependency-site]] applies to a subscription's `:query`, over the handler
  form the manifest records.

  A fully-literal handler IS the shape that will dispatch, so it is projected
  verbatim. A handler carrying a captured local or a call is a form whose value
  does not exist until the view renders, and a handler that is a callback BODY
  is code with no event vector at all; both project as the `:opaque` marker the
  analyzer already uses for the second, because what a reader can do with them
  is the same — nothing. `:event-id` still shows the literal event-id where the
  authored form has one, the part the compiler really does know, while the
  runtime arguments are left unsaid rather than invented.

  `:classification` sits beside them and keeps the two apart: an `:opaque`
  handler on a `:vector` or `:options` site is an event vector with a runtime
  argument, and on any other classification it is a callback."
  [{:keys [handler] :as site}]
  (let [;; The event VECTOR the handler carries, under either spelling that
        ;; carries one — the bare vector (`:vector`) or the options map that
        ;; wraps it (`:options`) — and nil where the handler is code.
        event (cond
                (vector? handler) handler
                (map? handler)    (:event handler)
                :else             nil)]
    (assoc (select-keys site [:sid :source-coord :path :prop :classification
                              :serializable? :sync?])
           :handler  (if (and (some? event) (literal-form? handler)) handler :opaque)
           :event-id (when (and (vector? event) (keyword? (first event)))
                       (first event)))))

(defn read-view-event-sites
  "The event-handler SITES the view declared as `view-id` declares, read from
  the manifest. `nil` for an unknown id, and `nil` in a production build.

      (tool/read-view-event-sites :app.people/people-list)
      ;; => {:schema … :read :view-event-sites :view-id … :lowering :compiled
      ;;     :scope :possible-sites :basis :static-proof
      ;;     :complete? true :loss nil
      ;;     :event-sites [{:sid … :source-coord {…} :path [1 0]
      ;;                    :prop :on-click :classification :vector
      ;;                    :serializable? true :sync? false
      ;;                    :handler [:person/selected 7]
      ;;                    :event-id :person/selected}
      ;;                   {:sid … :source-coord {…} :path [2 0]
      ;;                    :prop :on-click :classification :vector
      ;;                    :serializable? true :sync? false
      ;;                    :handler :opaque :event-id :person/removed}]
      ;;     :site-facts  #{:sid :source-coord :path :prop :classification
      ;;                    :serializable? :sync? :handler :event-id}}

  SITES, not dispatches. The roster is one entry per LEXICAL site, so a site
  inside a keyed list is one entry however many times it runs; what a render
  actually dispatched is a different quantity and is not derivable from this one.

  Complete and lossless: every event site the grammar can see is here, each
  saying WHERE it dispatches from AND what it dispatches — with the
  literal/dynamic split its sibling [[read-view-dependencies]] applies to a
  query stated per entry, so a handler whose value does not exist until render
  is marked rather than shown as source code. `:site-facts` names the closed set
  of facts a row states — see [[event-site-facts]]."
  [view-id]
  (when interop/debug-enabled?
    (when-some [view (registry/lookup view-id)]
      (envelope :view-event-sites view-id view
                (if-some [m (descriptor/manifest view)]
                  (static-proof {:event-sites (mapv event-site (:events m))
                                 :site-facts  event-site-facts})
                  (no-static-analysis {:event-sites []
                                       :site-facts  event-site-facts}))))))

;; ---------------------------------------------------------------------------
;; The CURRENT-OCCURRENCE reads — the only reads that need live state
;; ---------------------------------------------------------------------------

(def ^:private occurrence-facts
  "The closed set of facts one mounted-occurrence row states.

  Published beside the roster for the reason [[event-site-facts]] is: naming
  what an entry states is how an empty-handed row says which hand is empty. A
  reader migrating from the donor tool tier will look for a render count, a
  batch count, a lifecycle interval ledger, a hide-versus-unmount label and an
  accumulated union of every target the occurrence ever observed. None of those
  is here, and none is coming: rf2-drpa3.167 ruled the cumulative
  per-occurrence accumulator out permanently rather than deferring it. This is
  CURRENT STATE — what is connected now, at which generation, over which
  frame, having read what — so a lifetime quantity is not a fact it is missing
  but a fact it does not have.

  Three members are there to keep a LATER read honest rather than to describe
  the render, and none of them is a lifetime quantity:

    - `:root` is always `:unknown`. Cells do not know their owning root, and
      the commit seam that writes these rows carries no root identity, so a
      row that named one would be inventing it.
    - `:dispatch-id` is the cascade in scope at the commit, or nil. It is the
      join Spec 009's retained ring is keyed on, and nil is the common case: a
      commit in a post-settle React batch has no cascade on the stack, and
      nothing is minted to paper over that. It is what lets [[explain-render]]
      tell an EVICTED cause from one that never existed.
    - `:at` is the commit's reading of the monotonic clock — not wall-clock,
      not an interval — so a read can state whether the retained window
      reaches back to the commit at all."
  #{:view-id :occurrence :lowering :generation :connection :root :dispatch-id
    :at :commit})

(defn read-mounted-views
  "Every Freehand occurrence CONNECTED RIGHT NOW, inside the projection that
  says how far to trust the roster. `nil` in a production build.

      (tool/read-mounted-views)
      ;; => {:schema      :re-frame.freehand.evidence/v1
      ;;     :read        :mounted-views
      ;;     :scope       :connected-occurrences
      ;;     :basis       :observation
      ;;     :complete?   true
      ;;     :loss        nil
      ;;     :occurrences [{:view-id    :app.people/people-list
      ;;                    :occurrence {:parent nil :key 71}
      ;;                    :lowering   :compiled
      ;;                    :generation 4
      ;;                    :connection :connected
      ;;                    :root       :unknown
      ;;                    :at         18422.7
      ;;                    :commit     {:scope {:committed-generation 4}
      ;;                                 :basis :observation
      ;;                                 :complete? true :loss nil
      ;;                                 :frame :rf/default
      ;;                                 :reads [{:site-key … :query [:person 7]
      ;;                                          :frame-id :rf/default :owned? true}]}}]
      ;;     :occurrence-facts #{…}}
      ;;
      ;;    Two rows with the same `:view-id` and different `:occurrence` keys
      ;;    are two live occurrences of one view. That is the read this index
      ;;    exists for, and the declaration-keyed index the other reads resolve
      ;;    through structurally cannot state it.
      ;;
      ;;    `:reads` is the SELECTED COMMIT's dependency set, not a union over
      ;;    the occurrence's life, and it carries the queries WITHOUT the values
      ;;    they returned — a value is application data, and an evidence read
      ;;    is not a second egress path for it.
      ;;
      ;;    No arg, deliberately: the question is *what is mounted*, and a view
      ;;    id would answer a narrower one a caller can get by filtering.

  THE ONE READ THAT NEEDS LIVE STATE, because it is the one question a
  fire-and-forget commit sink cannot answer. An inspector attaches LATE, to an
  application that has been mounting for minutes, so a consumer that starts
  listening at attach time can only learn about commits from that moment on.
  The substrate therefore keeps one row per connected occurrence at the commit
  seam ([[re-frame.freehand.occurrences]]) — which is why a session that
  attaches now still sees what connected before it arrived.

  `:complete? true` is a claim about UNDER-reporting, and it is exact: the
  scope is *occurrences connected now that have published a selected commit*,
  and the index holds every one of them (a cell that never published is not
  connected — it has no bundle — so it is outside the scope rather than missing
  from the roster). Over-reporting has its own, bounded shape and is stated
  rather than claimed away: removal is the host's explicit teardown, so a host
  torn down some other way, without disconnecting its cells, leaves rows behind
  until it does. Each row's `:generation` and `:at` are what let a reader see a
  stale one for what it is."
  []
  (when interop/debug-enabled?
    (evidence/projection
      {:schema           evidence/schema
       :read             :mounted-views
       :scope            :connected-occurrences
       :basis            :observation
       :complete?        true
       :loss             nil
       :occurrences      (occurrences/rows)
       :occurrence-facts occurrence-facts})))

;; ---------------------------------------------------------------------------
;; explain-render — a bounded fold of Spec 009's retained window, at READ time
;; ---------------------------------------------------------------------------

(defn- read-query-ids
  "The set of query IDS one commit's staged reads name.

  Query ids, not whole query vectors, because that is the axis the retained
  trace stream keys sub recomputes on (`:rf.sub/id`) and a join has to be over
  a shared spelling. A dynamic query's arguments are not part of the match —
  matching on them would silently narrow the join whenever an argument moved
  between the run and the commit."
  [commit]
  (into #{}
        (keep (fn [{:keys [query]}]
                (cond
                  (keyword? query)                       query
                  (and (vector? query) (seq query))      (first query))))
        (:reads commit)))

(defn- run-row
  "One retained run, as a cause row: which run, which event kicked it off, and
  which of `query-ids` it recomputed.

  `:cause-event-id` and never the event VECTOR: an event id is a keyword and
  carries no application data, which is the same reason Spec 009 catalogues
  `:rf.view/cause-event-id` as the attribution slot rather than the args."
  [query-ids bundle]
  {:dispatch-id    (:dispatch-id bundle)
   :cause-event-id (first (:event bundle))
   :sub-ids        (into #{}
                         (comp (keep #(get-in % [:tags :rf.sub/id]))
                               (filter query-ids))
                         (:subs bundle))})

(defn- window-start
  "The oldest retained `:time` across `bundles`, or nil when the window holds
  nothing. The one number an eviction proof needs: a window that begins AFTER a
  commit cannot contain that commit's cause."
  [bundles]
  (->> bundles
       (mapcat :trace-events)
       (keep :time)
       (reduce (fn [a b] (if (or (nil? a) (< b a)) b a)) nil)))

(defn- explanation
  "One occurrence's explanation, folded from `bundles` — the runs Spec 009's
  ring retains for the frame that occurrence committed against — with the
  completeness claim and the loss account computed from the fold rather than
  asserted by the caller.

  THE ANSWER IS `:cause`, AND IT IS A JOIN, NOT A GUESS. The row recorded the
  cascade in scope when the commit ran, so the explanation looks that exact run
  up in the window. Found, and the render is explained: this is the run, this is
  the event that started it, and these are the subscriptions it recomputed that
  this commit reads.

  Three ways it cannot be, each reported as LOSS rather than as an
  empty-but-confident answer:

    - the window holds NOTHING for the frame. Retention is off
      (`:rf.trace/events-retained 0`), nothing has been dispatched, or the frame
      was destroyed and released its ring. `:cap`, because the window's one knob
      is what decides this.
    - the commit named a run and the window no longer holds it. EVICTED, and
      provably so — the id is right there and the ring does not have it. `:cap`
      again, and a bigger buffer is the remedy.
    - the commit named NO run. Spec 009 retains a run only when an event carries
      both a frame and a `:rf.trace/dispatch-id`, and a Freehand commit usually
      lands in a post-settle React batch with no cascade on the stack, so there
      was no correlation to record. `:uncorrelated` — a different reason and a
      different remedy from a full buffer, which is why it is its own member of
      the closed vocabulary.

  `:candidates` is offered in every case and is deliberately NOT the answer:
  the runs the window still holds that recomputed a subscription this commit
  reads. With no correlation those are leads — runs that COULD have driven this
  render — and presenting a lead as a cause is exactly the shape
  `:complete? true` with an invented explanation would have.

  `:dropped` is `:unknown` throughout: a window cannot say how many runs it
  never held."
  [row bundles]
  (let [commit     (:commit row)
        query-ids  (read-query-ids commit)
        of-run     (into {} (map (juxt :dispatch-id identity)) bundles)
        correlated (:dispatch-id row)
        exact      (when correlated (get of-run correlated))
        candidates (into []
                         (comp (map #(run-row query-ids %))
                               (filter (comp seq :sub-ids)))
                         bundles)
        started    (window-start bundles)
        loss       (cond
                     (empty? bundles)    {:reason :cap :dropped evidence/unknown}
                     (nil? correlated)   {:reason :uncorrelated :dropped evidence/unknown}
                     (nil? exact)        {:reason :cap :dropped evidence/unknown})]
    (evidence/projection
      (cond-> {:view-id     (:view-id row)
               :occurrence  (:occurrence row)
               :lowering    (:lowering row)
               :generation  (:generation row)
               :frame       (:frame commit)
               :dispatch-id correlated
               :scope       {:retained-runs (count bundles)
                             :spans-commit? (boolean (and started (<= started (:at row))))}
               :basis       :observation
               :complete?   (nil? loss)
               :loss        loss
               :commit      commit
               :cause       (when exact (run-row query-ids exact))
               :candidates  candidates}
        (some? started) (assoc-in [:scope :window-starts-at] started)))))

(defn explain-render
  "Why the live occurrences of `view-id` rendered, folded from Spec 009's
  RETAINED WINDOW at read time. No arg spans every current occurrence. `nil`
  for an id this build declared no view under, and `nil` in production.

      (tool/explain-render :app.people/people-list)
      ;; => {:schema :re-frame.freehand.evidence/v1
      ;;     :read   :explain-render
      ;;     :view-id :app.people/people-list
      ;;     :scope :connected-occurrences :basis :observation
      ;;     :complete? true :loss nil
      ;;     :explanations
      ;;     [{:view-id … :occurrence {…} :lowering :compiled :generation 4
      ;;       :frame :rf/default :dispatch-id 41
      ;;       :scope {:retained-runs 12 :spans-commit? true
      ;;               :window-starts-at 17980.2}
      ;;       :basis :observation :complete? true :loss nil
      ;;       :commit {…}
      ;;       :cause {:dispatch-id 41 :cause-event-id :people/loaded
      ;;               :sub-ids #{:person/by-id}}
      ;;       :candidates [{…}]}]}

  NOTHING IS RETAINED TO ANSWER THIS. The fold runs against the per-frame
  retained-event ring Spec 009 already owns, under its single knob
  (`:rf.trace/events-retained`, set through
  `(rf/configure! {:trace-buffer {:events-retained N}})` — see
  [[re-frame.freehand.evidence/retention]]), and the occurrence rows it folds
  against are current state that disappears at disconnect. There is no Freehand
  history store, no second window and no second knob: a second retention
  mechanism would be a second thing to disable in production, a second place a
  payload could survive, and a second answer to \"how far back does this go?\"
  that disagrees with the first.

  Which is exactly why the OUTER roster is complete while an inner explanation
  often is not, and why the two claims are separate projections. The roster is
  complete because the index knows every connected occurrence. An explanation
  is complete only when the run the commit was CORRELATED to is still in the
  window — and a disabled or empty ring, an evicted run, and a commit that was
  never correlated to any run are each reported as LOSS with an explicit
  `:dropped :unknown`. See [[explanation]] for the three arms; the short version
  is that a bounded window that has forgotten why a view rendered must say so,
  because a nil `:cause` presented as complete evidence would be asserting that
  nothing caused the render.

  Background tools may fold these bounded explanations into their own live
  summaries. The substrate must not, and does not: this read builds its answer
  and keeps none of it."
  ([] (explain-render nil))
  ([view-id]
   (when interop/debug-enabled?
     (let [rows (cond->> (occurrences/rows)
                  view-id (filterv #(= view-id (:view-id %))))]
       ;; `nil` means this build knows no view under that id AT ALL — neither a
       ;; declaration nor a live occurrence. The declaration alone is not the
       ;; test, because a view can be mounted by a host that minted its cell
       ;; under an id no `v/defview` expansion recorded, and refusing to explain
       ;; a render that demonstrably happened would be the reader's problem to
       ;; work around. An id that IS declared but has nothing mounted answers
       ;; with an empty roster, which is the one empty answer that is a clean
       ;; bill of health: the index is authoritative about what is connected.
       (when (or (nil? view-id) (seq rows) (some? (registry/lookup view-id)))
         ;; One ring read per FRAME, not per occurrence: several occurrences of
         ;; one view commonly commit against one frame, and folding the same
         ;; window once per row would be the same answer computed n times.
         (let [windows (into {}
                             (map (fn [fid] [fid (trace-tooling/trace-buffer fid)]))
                             (distinct (map #(:frame (:commit %)) rows)))]
           (evidence/projection
             {:schema       evidence/schema
              :read         :explain-render
              :view-id      view-id
              :scope        :connected-occurrences
              :basis        :observation
              :complete?    true
              :loss         nil
              :explanations (mapv #(explanation % (get windows (:frame (:commit %)))) rows)})))))))
