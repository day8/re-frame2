(ns re-frame.hicasso.read-extent-cljs-test
  "INVARIANT I7, AS ENFORCED — the ambient-read extent (rf2-hic-011).

  > `sub` is legal during the direct synchronous execution of the active
  > body, helpers, branches, and loops included. A read deferred through a
  > callback, promise, timer, lazy sequence, or other escaped extent
  > refuses with source and recovery. Dynamic reads reconcile from the
  > selected render without a general per-boundary dependency ledger.
  >
  > — `docs/design/hicasso/product/invariants.md`, I7

  The law is one sentence with two halves, and this file is the legality
  matrix that makes both halves checkable: three shapes that must work,
  seven crossings that must refuse, and the reconciliation clause that
  says a read set which changed between renders is replaced exactly once,
  at the commit.

  ## Two discriminators, not one, and the matrix needs both

  It is tempting to read I7 as a single guard, and it is not.

  1. **The render frame slot.** [[re-frame.hicasso.impl.collector/read-key!]]
     answers *is a boundary body running right now* by reading one slot
     that [[re-frame.hicasso.impl.collector/run-once]] sets in a `try` and
     clears in the matching `finally`. Every deferral that lands with no
     body on the stack — a fired event callback, a resolved promise, an
     expired timer, a lazy sequence realised after the fact, a top-level
     form at module load — finds that slot nil and refuses with
     `:rf.error/hicasso-sub-outside-render`.
  2. **The boundary crossing walk.** The slot cannot see a deferral forced
     inside *another* body's render, because a body IS running there — the
     wrong one. `re-frame.hicasso.impl.codec/realize-deep` closes the
     structural half at the hand-off: it forces every lazy sequence
     reachable from a boundary's props inside the window of the body that
     WROTE it, and refuses an unforced `delay` with
     `:rf.error/hicasso-deferred-read-at-boundary` rather than forcing it,
     because forcing an author's explicit deferral would change what their
     program means.

  So the matrix carries two refusal ids, and the row that proves the
  second one is needed is [[an-escaped-deferral-forced-in-another-body-is-
  the-declared-limit]] — the residue neither discriminator reaches,
  measured rather than merely disclaimed.

  ## The observable, and why a rendered value would not do

  Every legality row asserts on the **read set** the render resolved
  (`collector/reads-of`) and on **reader membership** at the commit
  (`inventory/cell-readers`), never on what a body painted. That is
  rf2-hic-010's lesson, inherited: a read attributed to the wrong boundary
  paints an identical page, and a read set that accumulated a previous
  body's keys paints an identical page too. What changes is what is
  retained and what will be notified.

  It has a second edge here that it did not have there. A body that reads
  ONE key cannot distinguish *its own read set* from *an accumulated
  scratch* — both are `#{that key}` — so every row in this file that could
  be fooled that way reads at least two distinct keys and asserts the
  whole set, and the rows that follow a refusal assert that the next
  body's set is its own.

  ## Every refusal is shown to be capable of not firing

  A refusal witness that cannot go green is worth as little as a census
  that cannot go non-zero. Three controls run against each `✗` row:

  - **Liveness.** The deferred thunk bumps a counter before it reads, so a
    row can never pass because the deferral silently never ran.
  - **The identical read is legal in the window.** The same query, read
    inside a body, returns its value and records its edge — so the refusal
    is about the *extent* and not about a query that was broken anyway.
  - **The witness answers false.** [[outcome]] is the suite's single
    discriminator and it reports `:returned` for a read that succeeded;
    [[the-refusal-witness-answers-both-ways]] drives it both directions so
    the `:refused` assertions above are not a helper that only knows one
    verb.

  Beyond the suite, each refusal was shown red by removing its guard from
  the runtime and restoring it; the PR body carries that ledger. Nothing
  under `src/` is changed by this bead.

  ## What is NOT witnessed here, and what it is owed to

  The package's lane is Node. Two deferral carriers therefore have no
  witness in this file and are owed to a browser suite: a read deferred
  into a **React effect** (`useEffect` / `useLayoutEffect` run after
  commit), and a read deferred across a **Suspense retry or an Activity
  reveal**. Both are the same discriminator — no body on the stack — and
  neither is *asserted* here, so this docstring says so rather than
  implying the matrix is complete. The event-callback and render-prop rows
  ARE witnessed, at the exact wrapper React would call, obtained off the
  element the render emitted; what a browser would add there is the DOM's
  own dispatch, not the law."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.test-support :as test-support]))

(def ^:private frame-id ::read-extent)

(rf/reg-sub :re/left  (fn [db _] (:left db)))
(rf/reg-sub :re/right (fn [db _] (:right db)))
(rf/reg-sub :re/flag  (fn [db _] (:flag db)))
(rf/reg-sub :re/row   (fn [db [_ i]] (get-in db [:rows i])))

(rf/reg-event :re/seed  (fn [_ [_ db]] {:db db}))
(rf/reg-event :re/bump  (fn [{:keys [db]} _] {:db (update db :left inc)}))
(rf/reg-event :re/raise (fn [{:keys [db]} _] {:db (assoc db :flag true)}))

;; ---------------------------------------------------------------------------
;; THE MODULE-LOAD ROW, taken where a module-load row has to be taken
;; ---------------------------------------------------------------------------
;;
;; This is a genuine top-level read, evaluated by the ClojureScript runtime
;; when this namespace loads — before any fixture, any frame, any React
;; root. It cannot be written as a `deftest` body without ceasing to be the
;; thing it witnesses, so it is written here and asserted below. The `try`
;; is what lets the namespace finish loading; the refusal itself is real.

(defonce ^:private module-load-read
  (try {:returned (h/sub [:re/left])}
       (catch :default e {:refused (ex-data e) :message (ex-message e)})))

;; The UIx adapter rather than plain-atom, for the reason the package smoke
;; gives and rf2-hic-010 repeats: plain-atom has no reactivity layer, so a
;; subscription under it never notifies and every commit assertion would
;; pass vacuously by never firing. `:async? true` selects the MAP form,
;; which the `(async done …)` rows require; `:ambient-frame nil` because
;; this suite seats its own.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- seeded!
  "A frame with a known db. `IS_REACT_ACT_ENVIRONMENT` is set outright for
  the reason rf2-hic-010's harness gives: nothing here runs inside React's
  `act` queue, and the prototype helper that carries the line lives in the
  bench tree the freeze gate forbids this package from importing."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id
    (rf/dispatch-sync [:re/seed {:left 1 :right 2 :flag false
                                 :rows [:a :b :c :d]}]))
  frame-id)

(defn- k
  "A sub-key: the runtime keys cells by `[frame-kw query-v]`, because two
  frames are isolated contexts holding two different app-dbs."
  [query-v]
  [frame-id query-v])

(defn- probe!
  "Run ONE boundary body under the real generation fence — the same call
  [[re-frame.hicasso.impl.collector/shell]] makes between its two hooks —
  and return the read-set entry the render resolved. Commits nothing."
  [body-fn]
  (collector/render-body frame-id body-fn {})
  (collector/last-reads))

(defn- element-of
  "The React element a body emitted, which is where the codec left every
  lowered callback. Reading a wrapper off here is how a Node witness gets
  hold of *the function React would call* rather than a stand-in for it."
  [body-fn]
  (collector/render-body frame-id body-fn {}))

(defn- outcome
  "**The suite's one discriminator.** Run `thunk` and report which of the
  two things happened, distinguishably: `{:returned v}` when the read was
  allowed through, `{:refused <ex-data> :message …}` when it was refused.

  It is a map with two possible keys rather than a predicate because the
  two failure modes a refusal witness actually has are *the thunk never
  ran* and *something other than the refusal threw*, and both of those
  look like success to a bare `thrown?`. A caller asserts the whole
  ex-data map, so a wrong refusal fails on the compare."
  [thunk]
  (try {:returned (thunk)}
       (catch :default e {:refused (ex-data e) :message (ex-message e)})))

(defn- escaped-extent-refusal
  "The exact stable shape every escape past the render frame must carry.

  The four keys are the contract: the stable id, the source coordinate as
  the runtime can state one today, the actionable recovery, and the query
  that was refused. `:reason` is deliberately NOT frozen here — it is
  prose, and rf2-hic-021 rules on re-routing the complaint text through
  `re-frame.error` while rf2-hic-007 adds the file/line/column coordinate
  this `:where` symbol currently stands in for. The rows assert the reason
  NAMES the recovery route instead, which is the part of it I7 requires."
  [query-v]
  {:rf.error/id :rf.error/hicasso-sub-outside-render
   :where       're-frame.hicasso.impl.collector/read-key!
   :recovery    :read-inside-a-boundary-body
   :query-v     query-v})

(defn- refusal-shape
  "An outcome's refusal, with the unfrozen prose projected out."
  [o]
  (dissoc (:refused o) :reason))

(defn- names-the-recovery?
  "I7 requires source AND recovery. The recovery keyword is asserted by
  [[escaped-extent-refusal]]; this is the developer-facing half of it —
  the message has to say where the read is legal and what to use instead."
  [o]
  (let [m (str (:message o))]
    (and (some? (re-find #"outside a boundary render" m))
         (some? (re-find #"subscribe-once" m)))))

;; ---------------------------------------------------------------------------
;; ✓ LEGAL — the direct synchronous extent, helpers and branches included
;; ---------------------------------------------------------------------------

(defn- helper-reads-right
  "An ordinary function. Not a boundary, not a component, not marked in
  any way — which is the point: the extent is the body's dynamic extent
  and a helper called from it is inside that extent."
  []
  (h/sub [:re/right]))

(defn- helper-reads-through-another-helper []
  (+ (helper-reads-right) (h/sub [:re/left])))

(deftest a-nested-helper-donates-its-reads-to-the-enclosing-boundary
  (seeded!)
  (let [entry (probe! (fn [_] [:p (helper-reads-through-another-helper)]))]

    (testing "two helper frames deep, and both keys are the BOUNDARY's —
              there is no helper-level read set for them to belong to"
      (is (= #{(k [:re/left]) (k [:re/right])} (collector/reads-of entry))))

    (let [cleanup (collector/commit-boundary! entry (fn []))]
      (testing "and the commit acquires both on the boundary's behalf: one
                registration in each key's reader list, the same one"
        (let [[a] (inventory/cell-readers (k [:re/left]))
              [b] (inventory/cell-readers (k [:re/right]))]
          (is (some? a))
          (is (identical? a b))))
      (cleanup))))

(deftest a-branch-records-the-arm-it-took-and-nothing-else
  (seeded!)
  ;; rf2-hic-010 witnessed the untaken arm contributing no edge. The row
  ;; here is the other direction and the one the matrix needs: BOTH arms
  ;; are legal, and which keys are recorded is a function of the control
  ;; flow the render actually took rather than of the text of the body.
  (let [body (fn [_] [:p (if (h/sub [:re/flag])
                           (h/sub [:re/right])
                           (h/sub [:re/left]))])
        low  (probe! body)]

    (testing "flag false: the else arm ran, so the else arm's key is the
              one recorded and the then arm's is absent"
      (is (= #{(k [:re/flag]) (k [:re/left])} (collector/reads-of low))))

    (rf/with-frame frame-id (rf/dispatch-sync [:re/raise]))

    (let [high (probe! body)]
      (testing "flag true: the same body, the same text, the other arm's key
                — and the first arm's key is GONE rather than accumulated,
                which is what a two-key assertion can see and a one-key
                assertion cannot"
        (is (= #{(k [:re/flag]) (k [:re/right])} (collector/reads-of high)))
        (is (not (contains? (collector/reads-of high) (k [:re/left]))))))))

(deftest a-variable-length-loop-records-every-row-it-ran
  (seeded!)
  ;; `for` returns a LAZY SEQUENCE, and this is the row that says why the
  ;; collector window closes around the codec rather than around the body's
  ;; return: a window that closed at the return would register no edge for
  ;; any row here, and would look perfectly correct on the first render.
  (let [body-of (fn [n] (fn [_] [:ul (for [i (range n)]
                                       [:li {:key i} (h/sub [:re/row i])])]))
        two     (probe! (body-of 2))]

    (testing "every row of a two-row loop is in the set — the seq was forced
              inside the window by the same pass that made the elements"
      (is (= #{(k [:re/row 0]) (k [:re/row 1])} (collector/reads-of two))))

    (let [four (probe! (body-of 4))]
      (testing "and the length is a render-time fact: four rows, four keys,
                no ledger of what the previous render read"
        (is (= #{(k [:re/row 0]) (k [:re/row 1])
                 (k [:re/row 2]) (k [:re/row 3])}
               (collector/reads-of four)))))

    (let [one (probe! (body-of 1))]
      (testing "shrinking is symmetric: the read set is what THIS render
                read, so three keys left it without anything being diffed"
        (is (= #{(k [:re/row 0])} (collector/reads-of one)))))))

;; ---------------------------------------------------------------------------
;; The reconciliation clause — appearing and disappearing reads land at the
;; commit, exactly once, wholesale
;; ---------------------------------------------------------------------------

(deftest a-changed-read-set-reconciles-exactly-at-the-commit
  (async done
    (seeded!)
    ;; I7's third sentence. The runtime takes no set difference — a changed
    ;; read set is a different entry, a different `subscribe`, and therefore
    ;; a fresh registration whose held edge set is empty by construction, so
    ;; the replacement IS the cleanup plus the new subscribe. What has to be
    ;; true is the OUTCOME: the key that stayed keeps exactly one reader, the
    ;; key that went keeps none, the key that arrived gains one.
    (let [first-entry (probe! (fn [_] [:p (h/sub [:re/left]) (h/sub [:re/right])]))
          release-1   (collector/commit-boundary! first-entry (fn []))]

      (testing "the first commit owns exactly the first read set"
        (is (= #{(k [:re/left]) (k [:re/right])} (collector/reads-of first-entry)))
        (is (= 1 (count (inventory/cell-readers (k [:re/left])))))
        (is (= 1 (count (inventory/cell-readers (k [:re/right])))))
        (is (= [] (inventory/cell-readers (k [:re/flag])))))

      ;; The re-render: `:re/right` disappears, `:re/flag` appears,
      ;; `:re/left` stays.
      (let [second-entry (probe! (fn [_] [:p (h/sub [:re/left]) (h/sub [:re/flag])]))]

        (testing "the render alone changes no membership — render probes"
          (is (= 1 (count (inventory/cell-readers (k [:re/right])))))
          (is (= [] (inventory/cell-readers (k [:re/flag])))))

        (let [release-2 (collector/commit-boundary! second-entry (fn []))]
          ;; React's order at a changed subscription: subscribe the new,
          ;; then release the old. Doing it the other way round is the
          ;; variant `hmr_remount` pinned, and the outcome is the same.
          (release-1)

          (testing "the arrived key gained a reader"
            (is (= 1 (count (inventory/cell-readers (k [:re/flag]))))))

          (testing "the departed key lost its only one"
            (is (= [] (inventory/cell-readers (k [:re/right])))))

          (testing "and the key that stayed has exactly ONE reader, not two
                    — a runtime that acquired for the successor without
                    releasing the predecessor also paints correctly, and
                    this is the number that tells them apart"
            (is (= 1 (count (inventory/cell-readers (k [:re/left]))))))

          (testing "the surviving reader is the SUCCESSOR by identity, which
                    a count alone cannot say"
            (let [[reader] (inventory/cell-readers (k [:re/left]))]
              (is (= #{(k [:re/left]) (k [:re/flag])}
                     (inventory/boundary-reads reader)))))

          (release-2)
          (.then (inventory/quiesced!)
                 (fn [_]
                   (testing "and the pair leaves nothing behind"
                     (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                            (inventory/residue))))
                   (done))))))))

;; ---------------------------------------------------------------------------
;; ✗ ILLEGAL — the escaped extents, one row each
;; ---------------------------------------------------------------------------

(deftest an-event-callback-refuses-its-read-and-keeps-its-dispatch
  (seeded!)
  ;; The wrapper under test is the real one: `intent/event-callback`,
  ;; installed by the codec at an event position, read off the element the
  ;; render emitted. A browser would obtain the same function from the DOM
  ;; and call it with a real event; the extent law does not depend on which.
  (let [!ran     (atom 0)
        element  (element-of
                   (fn [_] [:button {:on-click (h/hfn [_]
                                                 (swap! !ran inc)
                                                 (h/sub [:re/right]))}
                            "go"]))
        on-click (.. element -props -onClick)
        o        (outcome (fn [] (on-click #js {})))]

    (testing "the premise: the lowered handler exists and actually ran.
              Without this the refusal below could be anything at all"
      (is (fn? on-click))
      (is (= 1 @!ran)))

    (testing "and its read refused, with the stable shape"
      (is (= (escaped-extent-refusal [:re/right]) (refusal-shape o)))
      (is (names-the-recovery? o)))

    ;; The control that makes the row a statement about READS rather than
    ;; about callbacks. The same position, the same lowering, the same
    ;; post-render invocation — and the dispatch goes through, because the
    ;; ambient dispatch is captured at lowering time on purpose.
    (let [before   (rf/with-frame frame-id @(rf/subscribe [:re/left]))
          element2 (element-of
                     (fn [_] [:button {:on-click (h/hfn [_] [:re/bump])} "go"]))]
      ((.. element2 -props -onClick) #js {})
      (testing "a callback may DISPATCH after the render — the extent law
                fences reads, and the machinery is demonstrably alive at the
                instant the read above was refused"
        (is (= (inc before) (rf/with-frame frame-id @(rf/subscribe [:re/left]))))))))

(deftest a-render-prop-refuses
  (seeded!)
  ;; A render prop is a function the holder invokes during ITS render, and
  ;; `intent/position-contract` gives every non-event, non-ref position the
  ;; `:render` contract — so this is the real `intent/render-callback`
  ;; wrapper, taken off the emitted element exactly as the event wrapper was.
  ;;
  ;; The wrapper rebinds the intent layer's ambient dispatch and frame for
  ;; the call. It does NOT rebind the collector's render slot, and that is
  ;; the distinction the row exists to pin: a render prop is inside a
  ;; render, but it is not inside THIS body's render, and the runtime will
  ;; not guess which render owns the read.
  (let [!ran    (atom 0)
        element (element-of
                  (fn [_] [:div {:render-row (h/hfn [_]
                                               (swap! !ran inc)
                                               (h/sub [:re/right]))}]))
        render-row (.. element -props -renderRow)
        o          (outcome (fn [] (render-row 7)))]

    (testing "the premise: the render prop crossed as a function and ran"
      (is (fn? render-row))
      (is (= 1 @!ran)))

    (testing "and its read refused, with the stable shape"
      (is (= (escaped-extent-refusal [:re/right]) (refusal-shape o)))
      (is (names-the-recovery? o)))))

(deftest a-promise-continuation-refuses
  (async done
    (seeded!)
    (let [!ran (atom 0)
          !out (atom nil)]
      (collector/render-body
        frame-id
        (fn [_]
          ;; Scheduled from inside the body. The microtask runs after the
          ;; body returned, which is the whole of the escape.
          (-> (js/Promise.resolve)
              (.then (fn []
                       (swap! !ran inc)
                       (reset! !out (outcome (fn [] (h/sub [:re/right])))))))
          [:p (h/sub [:re/left])])
        {})

      (-> (js/Promise.resolve)
          (.then (fn [] nil))
          (.then (fn [] nil))
          (.then (fn [_]
                   (testing "the premise: the continuation ran"
                     (is (= 1 @!ran)))
                   (testing "and its read refused, with the stable shape"
                     (is (= (escaped-extent-refusal [:re/right])
                            (refusal-shape @!out)))
                     (is (names-the-recovery? @!out)))
                   (testing "the body's OWN read is unaffected — the refusal
                             is the crossing's, not the query's"
                     (is (= #{(k [:re/left])}
                            (collector/reads-of (collector/last-reads)))))
                   (done)))))))

(deftest a-timer-callback-refuses
  (async done
    (seeded!)
    (let [!ran (atom 0)]
      (collector/render-body
        frame-id
        (fn [_]
          (js/setTimeout
            (fn []
              (swap! !ran inc)
              (let [o (outcome (fn [] (h/sub [:re/right])))]
                (testing "the premise: the timer fired"
                  (is (= 1 @!ran)))
                (testing "and its read refused, with the stable shape"
                  (is (= (escaped-extent-refusal [:re/right]) (refusal-shape o)))
                  (is (names-the-recovery? o)))
                ;; The scratch is one module-level array reset by overwrite
                ;; at the top of every body. A refused read that had pushed
                ;; its key first, or a reset that stopped being
                ;; unconditional, would contaminate the NEXT body's set —
                ;; and that is a silent wrong answer rather than a loud one.
                ;;
                ;; The follow-up body reads keys DISJOINT from the enclosing
                ;; body's, which is what makes the assertion falsifiable: a
                ;; body that re-read `:re/left` here would swallow the
                ;; accumulated key in its own expected set and this row
                ;; would pass under exactly the defect it is watching for.
                (testing "and nothing was recorded: the next body's read set
                          is its own, with no trace of the refused key and
                          none of the render that deferred it"
                  (let [entry (probe! (fn [_] [:p (h/sub [:re/flag])
                                               (h/sub [:re/row 0])]))]
                    (is (= #{(k [:re/flag]) (k [:re/row 0])}
                           (collector/reads-of entry)))))
                (done)))
            0)
          [:p (h/sub [:re/left])])
        {}))))

(deftest an-escaped-lazy-sequence-refuses-where-an-in-window-one-is-legal
  (seeded!)
  ;; THE ROW THE BEAD SINGLES OUT, and it is the one whose two halves are
  ;; the same carrier. A `for` inside a body is legal because the codec
  ;; forces it inside the window; a lazy sequence that ESCAPES the render —
  ;; parked in a mutable reference rather than returned — is realised with
  ;; no body on the stack and refuses.
  ;;
  ;; Silently allowing it is the defect the two halves bracket: the values
  ;; would be right on the first paint, the edges would belong to nobody,
  ;; and the rows would never update again.
  (let [!escaped (atom nil)
        entry    (probe! (fn [_]
                           (reset! !escaped
                                   (map (fn [i] (h/sub [:re/row i])) (range 3)))
                           ;; the LEGAL half, in the same body
                           [:ul (for [i (range 2)]
                                  [:li {:key i} (h/sub [:re/row i])])]))]

    (testing "the in-window loop's rows are edges of this boundary"
      (is (= #{(k [:re/row 0]) (k [:re/row 1])} (collector/reads-of entry))))

    (testing "the escaped seq really is unrealised — otherwise the row below
              would be measuring a value the body already computed"
      (is (not (realized? @!escaped))))

    (let [o (outcome (fn [] (doall @!escaped)))]
      (testing "and realising it outside the window refuses, on the FIRST
                element, with the stable shape"
        (is (= (escaped-extent-refusal [:re/row 0]) (refusal-shape o)))
        (is (names-the-recovery? o))))))

(deftest a-module-load-read-refuses
  ;; No `seeded!`: this row asserts the capture taken at the top of this
  ;; file, when the ClojureScript runtime loaded the namespace. That is the
  ;; module-load escape in its own habitat — no frame in the registry, no
  ;; React root, no body — and the discriminator is the same one every row
  ;; above used, which is exactly the point.
  (testing "a top-level read, evaluated at namespace load, refused"
    (is (contains? module-load-read :refused))
    (is (= (escaped-extent-refusal [:re/left])
           (dissoc (:refused module-load-read) :reason)))
    (is (names-the-recovery? module-load-read))))

;; ---------------------------------------------------------------------------
;; The SECOND discriminator — the crossing walk, and the residue it declares
;; ---------------------------------------------------------------------------

(h/defview crossing-child
  [{:keys [v]}]
  [:p (str v)])

(deftest an-unforced-delay-at-a-boundary-crossing-refuses
  (seeded!)
  ;; The escape the render frame slot cannot see. A `delay` written in one
  ;; body and handed to a child would be forced inside the CHILD's render,
  ;; where a body IS running and the guard is satisfied — so the read lands
  ;; on the wrong boundary, is cached by the delay, and is dropped on the
  ;; child's next render. `realize-deep` runs at the crossing and refuses,
  ;; rather than forcing it, because forcing an author's explicit deferral
  ;; would change what their program means.
  (let [o (outcome (fn []
                     (collector/render-body
                       frame-id
                       (fn [_] [:div [crossing-child {:v (delay (h/sub [:re/right]))}]])
                       {})))]

    (testing "refused at the crossing, with its own stable id and recovery —
              a second refusal shape, because it is a second law"
      (is (= :rf.error/hicasso-deferred-read-at-boundary
             (:rf.error/id (:refused o))))
      (is (= 'front.codec/boundary-element (:where (:refused o))))
      (is (= :hand-a-function-or-deref-it-in-this-body
             (:recovery (:refused o)))))

    (testing "the offending value is carried, and it is the unforced delay
              itself — the refusal did not force it to find out"
      (let [v (:value (:refused o))]
        (is (delay? v))
        (is (not (realized? v)))))

    (testing "and the message hands the author the two ways out"
      (let [m (str (:message o))]
        (is (some? (re-find #"[Hh]and a FUNCTION" m)))
        (is (some? (re-find #"deref the delay" m))))))

  ;; The control. `realize-deep` refuses only what is UNFORCED — a delay the
  ;; author deref'd in their own body carries a computed value, crosses
  ;; freely, and its read belongs to the body that wrote it. Without this
  ;; row the one above could be "the walk refuses every delay".
  (let [entry (probe! (fn [_]
                        (let [d (delay (h/sub [:re/right]))]
                          @d
                          [:div [crossing-child {:v @d}]])))]
    (testing "a delay forced in the writing body crosses, and its read is
              that body's own edge"
      (is (= #{(k [:re/right])} (collector/reads-of entry))))))

(deftest an-escaped-deferral-forced-in-another-body-is-the-declared-limit
  (seeded!)
  ;; **The residue neither discriminator reaches, measured rather than
  ;; disclaimed.** `realize-deep`'s reach is the walk's reach: it descends
  ;; into data structures, and a mutable reference is not one. A thunk an
  ;; author parks in an atom is outside what any structural pass can see,
  ;; and if it is forced inside ANOTHER body's render the frame slot finds a
  ;; frame and is satisfied.
  ;;
  ;; **The limit is ratified, so this row's green is a decision and not an
  ;; oversight.** rf2-djxr weighed closing it and ruled otherwise: the runtime
  ;; does not chase deferred reads hidden in mutable references, I7's text now
  ;; says so, and the guide warns rather than the runtime enforcing. Do not
  ;; weaken or delete this row — it is the standing measurement of a boundary
  ;; the product chose. If enforcement is ever extended here, this is the row
  ;; that goes red, and that is what it is for.
  (let [!parked (atom nil)]
    (probe! (fn [_]
              (reset! !parked (fn [] (h/sub [:re/right])))
              [:p (h/sub [:re/left])]))

    (testing "forced with no body on the stack it refuses, like every other
              escape"
      (is (= (escaped-extent-refusal [:re/right])
             (refusal-shape (outcome (fn [] (@!parked)))))))

    (let [victim (probe! (fn [_]
                           (@!parked)
                           [:p (h/sub [:re/flag])]))]
      (testing "but forced inside a DIFFERENT body's render it does not
                refuse — and the read is filed under that body, which never
                wrote it. The page paints correctly and the edge is wrong"
        (is (= #{(k [:re/flag]) (k [:re/right])} (collector/reads-of victim)))))))

;; ---------------------------------------------------------------------------
;; The controls that make the matrix worth its greens
;; ---------------------------------------------------------------------------

(deftest the-refusal-witness-answers-both-ways
  (seeded!)
  ;; Every `✗` row above asserts through [[outcome]] and
  ;; [[escaped-extent-refusal]]. If that pair could only ever report a
  ;; refusal, the greens would be the instrument's silence rather than the
  ;; runtime's conduct — the same failure mode rf2-hic-010's residue census
  ;; guarded against, in this file's own currency.
  (let [!seen (atom nil)]
    (probe! (fn [_]
              (reset! !seen (outcome (fn [] (h/sub [:re/right]))))
              [:p "x"]))

    (testing "the IDENTICAL read, inside the window, is reported as allowed
              — so the helper distinguishes, and every refusal above is
              about the extent rather than about the query"
      (is (contains? @!seen :returned))
      (is (= 2 (:returned @!seen)))
      (is (not (contains? @!seen :refused)))))

  (testing "and the shape constructor discriminates on the query, so a row
            cannot pass on somebody else's refusal"
    (is (not= (escaped-extent-refusal [:re/left])
              (escaped-extent-refusal [:re/right])))))

(deftest a-refusal-acquires-nothing-and-leaves-nothing
  (async done
    (seeded!)
    ;; A refusal that also recorded — a key pushed onto the scratch before
    ;; the guard, a cell built, a reader slot taken — would be a leak wearing
    ;; an error message. `read-key!` raises before it touches the scratch,
    ;; and this is the row that says the census agrees.
    ;;
    ;; **This row is insurance rather than a measurement, and it is worth
    ;; saying which.** No perturbation in rf2-hic-011's ledger reds it: with
    ;; the extent guard removed the escaped reads reach `subscribe-once`,
    ;; which refuses before acquiring anything, so the zeros hold either
    ;; way. What keeps it from being a zero the instrument cannot make
    ;; non-zero is that the instrument is shared — `inventory/residue` is
    ;; proven able to answer non-zero by `the-residue-census-can-answer-
    ;; false` in `kernel_commit_owns_cljs_test`, which runs in this same
    ;; bundle. The cross-reference is deliberate: one proven control beats
    ;; a second copy of it.
    (let [!parked (atom nil)]
      (probe! (fn [_]
                (reset! !parked (fn [] (h/sub [:re/right])))
                [:p (h/sub [:re/left])]))

      (dotimes [_ 3] (outcome (fn [] (@!parked))))

      (testing "three refusals later the runtime owns exactly what the
                probing render owned: nothing"
        (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0}
               (dissoc (inventory/residue) :entries)))
        (is (= [] (inventory/cell-readers (k [:re/right]))))
        (is (nil? (inventory/cell-reaction (k [:re/right])))))

      (.then (inventory/quiesced!)
             (fn [_]
               (testing "and the render-phase entry cache evaporates at the
                         runtime's own horizon, refusals included"
                 (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                        (inventory/residue))))
               (done))))))
