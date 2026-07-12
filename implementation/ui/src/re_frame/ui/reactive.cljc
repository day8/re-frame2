(ns re-frame.ui.reactive
  "S2b reactive core of the compiled-view substrate — the ViewCell, the
  render-side probe/record protocol, the 8-step layout-commit reconciler,
  and the three-state lifecycle. Host-agnostic (`.cljc`): the React glue
  that drives it lives in `re-frame.ui.viewcell` (`.cljs`), but every
  ownership decision — kept-check, transactional stage/rollback, evidence
  comparison, publish/release ordering, lifecycle facts — is here, so the
  whole reconciler is graft-checked headlessly against the REAL observation
  port on both hosts (node + JVM, plain-atom adapter).

  Sole reactive consumer of the internal observation port
  (`re-frame.substrate.observation`) — the six operations
  `resolve-target` / `probe` / `acquire!` / `current?` / `read` /
  `release!` (Spec 006 §The internal observation port). Per the S2a
  handoff: `read` returns `:frame-epoch` / `:registry-epoch` ADDITIVELY
  (no second probe at commit step 5); `resolve-target`'s `site-ctx` shape
  is `{:query-v … :frame pin? :override {:value :override-id :version}?}`;
  the value-movement `on-change` watch channel exists only on watchable
  hosts, so headless movement is caught at the commit evidence comparison
  (step 5), not by a callback.

  ## The ViewCell (03 §2)

  Every lexical `(sub …)` in a view is a compile-indexed site; all of a
  view's sites share ONE ViewCell — one `useSyncExternalStore`, one scalar
  revision snapshot, one notification per epoch. Render probes WITHOUT
  ownership (resolve-target + probe, no ref-count / watch / cache node);
  the layout commit acquires the CAPTURED targets. Abandoned renders
  (StrictMode double-render, time-sliced tear-off) retain NOTHING because
  render never acquires — the 10k-abandoned-renders-retain-zero property
  is structural, mirroring the port's S-3 §5 cold-probe exit criterion.

  ## The render capture

  A render pass records each executed site's resolved target + probe
  evidence + value into a per-pass CAPTURE (ownership-free). Sites dedup by
  target identity, so N reads of one query share one committed lease and a
  shared node can never fall through its zero-owner disposal edge. The
  latest finished capture is stashed on the cell; the layout commit
  reconciles the committed dependency set against it — idempotently, so
  StrictMode's mount→unmount→mount effect replay is naturally balanced.

  ## `sub` value stabilization (03 §2, I-8)

  A site returns the PRIOR EXACT value (identical reference) when the new
  read is `rf=` to the last committed value for its target — so an
  `rf=`-stable read does not repaint downstream. Stabilization here is
  keyed by target identity `(frame, stabilized query)`; per-site query-
  object reuse (the parametric-args case) needs compile-site identity and
  lands with the HMR site-identity slice (S2e)."
  (:require [re-frame.error :as error]
            [re-frame.interop :as interop]
            [re-frame.substrate.observation :as obs]
            [re-frame.ui.eq :as eq]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The static override door (03 §3)
;;
;; `resolve-target` consumes a Story-override HIT off the site-ctx. On the
;; JVM there is no React context: `ui.test/render` binds this door
;; explicitly (`{:sub-overrides {query value}}`) — "one honest option, not
;; a pretended same mechanism" (07 §2). On CLJS the dev/full Story skeleton
;; will seed the same door from the public override context with ordinary
;; `useContext` (that React-context wiring is S2f; the static-override LEASE
;; path below is landed + fixtured here so S2f does not duplicate it).
;; ---------------------------------------------------------------------------

(def ^:dynamic *sub-overrides*
  "Override door: a map of query-vector → pinned value, or nil. A HIT
  resolves the site to a `:story-override` target — the pinned value IS the
  resolution (no node), and commit acquires a STATIC lease. `:override-id`
  is the query (the override's stable slot identity); `:version` is the
  pinned value itself, so `current?` (a `=` on version) retargets exactly
  when the pinned value moves."
  nil)

(defn- resolve-override
  [query]
  (when-some [m *sub-overrides*]
    (when (contains? m query)
      (let [v (get m query)]
        {:value v :override-id query :version v}))))

;; ---------------------------------------------------------------------------
;; Ambient render capture
;;
;; Single-threaded on both hosts within one synchronous render; a compiled
;; view's children are ELEMENTS (rendered later by the host), never
;; synchronously nested calls, so renders never nest — a save/restore cell
;; is sufficient and re-entrancy-robust.
;; ---------------------------------------------------------------------------

(def ^:private ambient (atom nil)) ;; {:cell <cell> :capture <volatile>} | nil

(defn- fresh-capture
  [generation]
  {:generation generation :order [] :by-key {}})

(defn- target-key
  [target]
  (case (:kind target)
    :subscription   [:sub (:frame-id target) (:query target)]
    :story-override [:override (:override-id target)]))

(defn- record-site
  "Add a site's observation to `cap`, deduped by target identity (the first
  observation of a target within a pass fixes its order + evidence)."
  [cap tk target ev value]
  (if (contains? (:by-key cap) tk)
    cap
    (-> cap
        (update :order conj tk)
        (assoc-in [:by-key tk] {:target target :evidence ev :value value}))))

;; ---------------------------------------------------------------------------
;; The ViewCell
;; ---------------------------------------------------------------------------

(deftype ViewCell [state]
  ;; Opaque host object with IDENTITY equality (deftype default). `state`
  ;; is an atom of:
  ;;
  ;;   {:view-id vid
  ;;    :generation g            ; view-body generation (HMR); commit rejects
  ;;                             ;   a stale capture (step 1)
  ;;    :lifecycle :fresh|:connected|:disconnected|:dead
  ;;    :committed {tk -> lease} ; installed dependency set
  ;;    :values    {tk -> value} ; last published site values (stabilization
  ;;                             ;   + the revision snapshot's evidence)
  ;;    :revision  int           ; get-snapshot returns this (useSyncExternalStore)
  ;;    :dirty?    bool          ; coalescing flag for the async notify path
  ;;    :latest-capture cap|nil  ; last finished render capture (commit input)
  ;;    :listeners {k -> fn}     ; useSyncExternalStore subscribers
  ;;    :intervals [interval]}   ; lifecycle facts (dev/tool; 03 §4)
  )

(defn cell?
  [x]
  (instance? ViewCell x))

(defn- state
  [^ViewCell cell]
  (.-state cell))

(defn make-cell
  "Mint a fresh ViewCell for view `view-id` at body `generation` (default
  0). Starts `:fresh` — the first successful commit connects it."
  ([view-id] (make-cell view-id 0))
  ([view-id generation]
   (->ViewCell
     (atom {:view-id        view-id
            :generation     generation
            :lifecycle      :fresh
            :committed      {}
            :values         {}
            :revision       0
            :dirty?         false
            :latest-capture nil
            :listeners      {}
            :intervals      []}))))

;; ---- read + query stabilization ---------------------------------------------

(defn sub-read
  "The one bridge `(sub query)` lowers to on both hosts. Resolves the
  site's target (override door → ambient frame), probes ownership-free, and
  returns the value:

    - Inside a live cell render (ambient capture present), RECORDS the site
      (target + evidence) into the capture and returns the `rf=`-stabilized
      value (the prior committed reference when the read is `rf=`).
    - Outside a cell (the JVM `ui.test/render` one-shot headless read, or a
      defensive direct call), returns the freshly probed value — no
      ownership, no capture.

  Fail-loud rides the port: `:rf.error/no-such-sub` on an unknown entry
  sub, `:rf.error/frame-destroyed` against a destroyed frame."
  [query]
  (let [override (resolve-override query)
        site-ctx (cond-> {:query-v query}
                   (some? override) (assoc :override override))
        target   (obs/resolve-target site-ctx)
        ev       (obs/probe target)
        v        (:value ev)
        {:keys [cell capture]} @ambient]
    (if (some? cell)
      (let [tk    (target-key target)
            prior (get (:values @(state cell)) tk ::none)
            v*    (if (and (not (identical? ::none prior)) (eq/rf= v prior))
                    prior
                    v)]
        (vswap! capture record-site tk target ev v*)
        v*)
      v)))

(defn with-capture
  "Run `thunk` (a compiled view body) under a fresh ambient capture, stash
  the finished capture on `cell` as the commit input, and return the
  thunk's value (the host element). Ownership-free: an abandoned render (a
  thunk whose result the host discards) leaves only unreachable garbage."
  [^ViewCell cell thunk]
  (let [cap  (volatile! (fresh-capture (:generation @(state cell))))
        prev @ambient]
    (reset! ambient {:cell cell :capture cap})
    (try
      (let [el (thunk)]
        (swap! (state cell) assoc :latest-capture @cap)
        el)
      (finally
        (reset! ambient prev)))))

;; ---- useSyncExternalStore contract ------------------------------------------

(defn get-snapshot
  "The scalar revision snapshot — a monotonically-advancing integer, stable
  by `=`/`===` between notifications. `useSyncExternalStore`'s getSnapshot."
  [^ViewCell cell]
  (:revision @(state cell)))

(defn subscribe
  "Register `listener` (a zero-arg fn the host re-renders through) under a
  fresh key; returns an unsubscribe thunk. `useSyncExternalStore`'s
  subscribe."
  [^ViewCell cell listener]
  (let [k (gensym "rf-ui-cell-listener")]
    (swap! (state cell) assoc-in [:listeners k] listener)
    (fn unsubscribe [] (swap! (state cell) update :listeners dissoc k))))

(defn- notify-listeners!
  [^ViewCell cell]
  (doseq [f (vals (:listeners @(state cell)))]
    (f)))

(defn- advance-revision!
  "Advance the cell's revision and notify subscribers — the host re-reads
  getSnapshot, sees the new revision, and re-renders. From step 8 this runs
  synchronously inside the layout commit (React corrects BEFORE paint)."
  [^ViewCell cell]
  (swap! (state cell) update :revision inc)
  (notify-listeners! cell))

;; ---- the async dirty path (value-movement on watchable hosts) ---------------
;;
;; `on-change` is constant-work (mark-dirty; never compute — I-5). Multiple
;; movements coalesce to one revision advance per microtask. (Exact
;; once-per-epoch coalescing at the transaction boundary is S2d; per-tick
;; coalescing here already satisfies the useSyncExternalStore contract.)

(defn flush-dirty!
  "Run any pending coalesced notification synchronously (test seam + the
  scheduled-flush body): if the cell is dirty, clear the flag and advance
  the revision once."
  [^ViewCell cell]
  (let [st (state cell)]
    (when (:dirty? @st)
      (swap! st assoc :dirty? false)
      (advance-revision! cell))))

(defn mark-dirty!
  "The `on-change` body (also the notification seam S2d refines to exact
  once-per-epoch coalescing): mark the cell dirty and schedule one
  coalesced flush. Never acquires/releases (no reentrant-graph-op) and
  never computes (I-5) — it advances the revision, and the render it
  schedules re-probes. Multiple marks before the scheduled flush coalesce
  to one revision advance."
  [^ViewCell cell]
  (let [st (state cell)]
    (when-not (:dirty? @st)
      (swap! st assoc :dirty? true)
      (interop/next-tick #(flush-dirty! cell)))))

(defn- on-change-fn
  [^ViewCell cell]
  (fn [_payload] (mark-dirty! cell)))

;; ---- lifecycle (03 §4) ------------------------------------------------------
;;
;; Three OBSERVABLE runtime states. The fact emitted at cleanup is always
;; `:disconnected {:reason :unknown}` — the platform gives no
;; hide-vs-unmount signal. Later evidence annotates the PRIOR interval,
;; never the present: a reconnect proves an Activity hide
;; (`:activity-hidden {:proof :reconnect}`); an explicit host/root teardown
;; proves an unmount (`:unmounted {:proof :host-teardown}`).

(defn lifecycle
  "The cell's current runtime state keyword."
  [^ViewCell cell]
  (:lifecycle @(state cell)))

(defn intervals
  "The cell's lifecycle interval log (dev/tool read) — the emitted facts
  plus any retroactive annotations."
  [^ViewCell cell]
  (:intervals @(state cell)))

(defn- release-committed!
  "Release every lease in the committed dependency set and clear it —
  acquire-before-release is not needed here (this is a full teardown, not a
  reconcile). Idempotent via the port's own release! idempotence."
  [^ViewCell cell]
  (let [st (state cell)]
    (doseq [lease (vals (:committed @st))]
      (obs/release! lease))
    (swap! st assoc :committed {})))

(defn- annotate-open-disconnect!
  "Upgrade the still-open `:disconnected {:reason :unknown}` interval's
  reason to `reason`+`proof` (the retroactive annotation). No-op when the
  last interval is not an open disconnect."
  [^ViewCell cell reason proof]
  (swap! (state cell) update :intervals
         (fn [ivs]
           (if (and (seq ivs) (= :disconnected (:state (peek ivs))))
             (conj (pop ivs)
                   (assoc (peek ivs) :reason reason :proof proof))
             ivs))))

(defn- connect!
  "Commit-time lifecycle transition into `:connected`. A transition FROM
  `:disconnected` is a reconnect — it retroactively proves the prior
  interval was an Activity hide."
  [^ViewCell cell]
  (let [st @(state cell)]
    (when (= :disconnected (:lifecycle st))
      (annotate-open-disconnect! cell :activity-hidden :reconnect))
    (swap! (state cell) assoc :lifecycle :connected)))

(defn disconnect!
  "Effects-cleanup transition (React unmount OR Activity hide —
  indistinguishable at this moment): release lease owners (hidden UI must
  not poll) and emit `:disconnected {:reason :unknown}`. The cell is
  reconnectable — a later commit on the same cell reacquires and
  corrects. Idempotent. Returns the cell."
  [^ViewCell cell]
  (let [st (state cell)]
    (when (contains? #{:fresh :connected} (:lifecycle @st))
      (release-committed! cell)
      (swap! st (fn [m]
                  (-> m
                      (assoc :lifecycle :disconnected)
                      (update :intervals conj {:state :disconnected :reason :unknown})))))
    cell))

(defn teardown!
  "Explicit host/root teardown (root unmount, parent teardown, frame
  destroy): the frame/adapter/root is destroyed under this cell's handle —
  the retained interval is proven an unmount. Detaches leases, marks the
  cell `:dead` (no resume), and annotates. Wired by the frame/root
  teardown path (S2c/core) for a frame-scoped destroy; here it is the
  substrate contract + its fixtures. Idempotent. Returns the cell."
  [^ViewCell cell]
  (let [st (state cell)]
    (when-not (= :dead (:lifecycle @st))
      (if (= :disconnected (:lifecycle @st))
        (annotate-open-disconnect! cell :unmounted :host-teardown)
        (swap! st update :intervals conj
               {:state :unmounted :reason :unmounted :proof :host-teardown}))
      (release-committed! cell)
      (swap! st assoc :lifecycle :dead))
    cell))

;; ---------------------------------------------------------------------------
;; The 8-step layout-commit reconciler (03 §3)
;; ---------------------------------------------------------------------------

(defn- evidence-moved?
  "Did the target move in the render→commit gap? Compares the acquire-time
  `read` against the render's `probe` evidence. A cold probe
  (`:node-version nil`) falls back to `rf=` on value; a live probe compares
  the node version and the frame/registry epochs (belt-and-braces the
  two-guard rule leans on)."
  [read-result probe-ev]
  (if (nil? (:node-version probe-ev))
    (not (eq/rf= (:value read-result) (:value probe-ev)))
    (or (not= (:version read-result) (:node-version probe-ev))
        (not= (:frame-epoch read-result) (:frame-epoch probe-ev))
        (not= (:registry-epoch read-result) (:registry-epoch probe-ev)))))

(defn commit!
  "Run the 8-step layout commit for `cell` against its latest render
  capture. Idempotent: an unchanged committed set + capture reconciles to a
  no-op (kept-check retains every lease untouched), so StrictMode's
  release/reacquire replay is naturally balanced.

  1. Reject a stale-generation capture (HMR) — return `:stale`, the host
     re-renders (no ownership touched).
  2. A `:dead` cell fails loudly — reconnection after teardown is not
     allowed.
  3. Kept-check every previously-committed site with `(current? lease
     target)`; unchanged live leases are RETAINED untouched, a failed check
     (disposed node, frame swap, restabilized query, moved override)
     classifies the site as retargeted.
  4. STAGE-acquire every newly-observed or retargeted target BEFORE
     releasing anything (acquire-before-release — a shared node never falls
     through its zero-owner edge). On ANY acquisition failure every staged
     lease is synchronously released in REVERSE acquisition order, the
     prior committed set stays installed, and the typed error propagates.
  5. Compare each acquired node's version + frame/registry epochs against
     the render's probe evidence.
  6. Publish the committed site values + the new dependency set (retained +
     staged) — before the user can interact with the new DOM.
  7. Release the prior leases of dropped + retargeted sites.
  8. If any evidence moved in the render→commit gap, advance the revision
     and notify — React corrects BEFORE paint.

  Returns `cell` on a normal commit, `:stale` on a rejected generation, or
  `:no-capture` when nothing has been rendered yet."
  [^ViewCell cell]
  (let [st  (state cell)
        st0 @st
        cap (:latest-capture st0)]
    (cond
      (nil? cap)
      :no-capture

      ;; step 1 — stale generation
      (not= (:generation cap) (:generation st0))
      :stale

      :else
      (do
        ;; step 2 — dead cell fails loudly (no resume). The context is gone,
        ;; so the always-on `:rf.error/frame-destroyed` is the honest id (no
        ;; new catalogue row): reconnection after teardown is not allowed.
        (when (= :dead (:lifecycle st0))
          (error/throw-error!
            :rf.error/frame-destroyed
            're-frame.ui.reactive/commit!
            (str "a ViewCell commit reached a :dead cell (view " (:view-id st0)
                 ") — the frame/root was torn down under a retained handle; a "
                 "dead cell cannot resume")
            {:extra {:view-id (:view-id st0)}}))
        (let [committed  (:committed st0)          ;; tk -> lease
              new-order  (:order cap)              ;; tk, render order
              new-by     (:by-key cap)
              new-set    (set new-order)
              ;; step 3 — kept-check
              retained   (persistent!
                           (reduce
                             (fn [acc [tk lease]]
                               (if (and (contains? new-set tk)
                                        (obs/current? lease (:target (new-by tk))))
                                 (assoc! acc tk lease)
                                 acc))
                             (transient {})
                             committed))
              retained?  (fn [tk] (contains? retained tk))
              to-release (persistent!
                           (reduce
                             (fn [acc [tk lease]]
                               (if (retained? tk) acc (assoc! acc tk lease)))
                             (transient {})
                             committed))
              to-acquire (into [] (remove retained?) new-order)
              on-change  (on-change-fn cell)
              ;; step 4 — transactional stage-acquire
              staged     (loop [ks     to-acquire
                                acc    []]
                           (if (empty? ks)
                             acc
                             (let [tk     (first ks)
                                   target (:target (new-by tk))
                                   lease  (try
                                            (obs/acquire! target on-change)
                                            (catch #?(:clj Throwable :cljs :default) e
                                              ;; rollback: release staged in
                                              ;; REVERSE acquisition order; the
                                              ;; prior committed set stays
                                              ;; installed; propagate the throw.
                                              (doseq [[_ l] (rseq acc)]
                                                (obs/release! l))
                                              (throw e)))]
                               (recur (rest ks) (conj acc [tk lease])))))
              staged-map (into {} staged)
              ;; step 5 — evidence comparison (moved in the render→commit gap)
              moved?     (boolean
                           (some (fn [[tk lease]]
                                   (evidence-moved? (obs/read lease)
                                                    (:evidence (new-by tk))))
                                 staged))
              new-values (persistent!
                           (reduce (fn [acc tk]
                                     (assoc! acc tk (:value (new-by tk))))
                                   (transient {})
                                   new-order))]
          ;; step 6 — publish (committed values + dependency set)
          (swap! st assoc
                 :committed (merge retained staged-map)
                 :values    new-values)
          ;; step 7 — release dropped + retargeted prior leases
          (doseq [[_ lease] to-release]
            (obs/release! lease))
          ;; lifecycle: connect (reconnect annotation when re-committing a
          ;; hidden cell)
          (connect! cell)
          ;; step 8 — moved evidence corrects before paint
          (when moved?
            (advance-revision! cell))
          cell)))))

;; ---- test/inspection reads --------------------------------------------------

(defn committed-target-keys
  "The target keys of the cell's installed dependency set (tool/test read)."
  [^ViewCell cell]
  (set (keys (:committed @(state cell)))))

(defn committed-values
  "The cell's last-published site values, keyed by target (tool/test read)."
  [^ViewCell cell]
  (:values @(state cell)))

(defn committed-lease
  "The installed lease for target key `tk` (tool/test read), or nil."
  [^ViewCell cell tk]
  (get (:committed @(state cell)) tk))

(defn revision
  "The cell's current revision integer (tool/test read)."
  [^ViewCell cell]
  (:revision @(state cell)))
