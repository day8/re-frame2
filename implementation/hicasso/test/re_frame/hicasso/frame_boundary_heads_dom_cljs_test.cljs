(ns re-frame.hicasso.frame-boundary-heads-dom-cljs-test
  "THE TWO IN-TREE FRAME BOUNDARIES — `h/frame-root` (ENSURE) and
  `h/frame-provider` (SCOPE) — against a real React root (rf2-kuky.58).

  ## The claim this file exists for

  Hicasso's root door used to ENSURE its frame SYNCHRONOUSLY, before
  `createRoot`, and `rf/make-frame` drains its `:initial-events` to a
  fixed point before returning — so *the first paint is the seeded one*
  held BY CONSTRUCTION. Spelling ENSURE in the tree gives that
  construction up: spec/002's `frame-root` makes the frame in a client
  `useLayoutEffect` at COMMIT, and its FIRST render emits no descendant
  subtree at all.

  **That is the trade this bead turned on, and W1 is its measurement.**
  The property survives, for a reason that is React's rather than ours: a
  layout effect runs before the browser paints, the `useState` flip it
  performs re-renders synchronously in the same layout phase, and
  `h/render!` renders inside `flushSync` — which does not return until
  that work is done. So the door still returns with the seeded markup on
  the page, and W1 reads it with NOTHING dispatched in between. Were the
  ENSURE owned by a passive effect instead, W1 would read the empty first
  pass and go red, which is what makes it a witness rather than a
  restatement.

  ## Why the readings are the DOM here, where its sibling refuses them

  `re-frame.hicasso.public-root-lifecycle-dom-cljs-test` reads the cell
  table rather than the markup because a root whose runtime was emptied
  under it still LOOKS right. This suite's question is the opposite one —
  *what is on the page at the moment the door returns* — so the markup is
  the only honest instrument, and every reading below is taken on the
  line after the mount.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` every DOM claim degrades to a stated
  skip. The refusal rows need no DOM and run in both."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.impl.mount :as rf.hicasso.impl.mount]
            [re-frame.test-support :as rf.test-support]))

(def ^:private ensured ::ensured)
(def ^:private scoped ::scoped)
(def ^:private absent ::never-made)
(def ^:private reloaded ::reloaded)

;; Registered ABOVE `use-fixtures`: the reset fixture captures its baseline
;; when the `use-fixtures` form is EVALUATED, so a registration below it is
;; erased before the first row runs.

(rf/reg-sub ::label (fn [db _] (:label db)))
(rf/reg-sub ::stamp (fn [db _] (:stamp db)))

(rf/reg-event ::seed (fn [_ [_ label]] {:db {:label label}}))
(rf/reg-event ::relabel (fn [{:keys [db]} [_ label]] {:db (assoc db :label label)}))

;; The seeding event fires an EFFECT rather than writing the db, so
;; `:fx-overrides` is what decides which handler runs. That is the whole point
;; of W2: `:fx-overrides` could not ride the old root-door config at all, which
;; is why the one shipped example had to call `rf/make-frame` first and mount to
;; JOIN.
(defonce ^:private !fx-seen (atom nil))

(rf/reg-event ::stamp-via-fx (fn [_ _] {:fx [[::stamp-fx :fired]]}))
(rf/reg-fx ::stamp-fx (fn [_ _] (reset! !fx-seen :real)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     ;; nil, not the default: an ambient dynamic-var frame stamp would let a
     ;; boundary that failed to resolve its own frame answer that one instead,
     ;; and a scoping miss would read as a rendering difference rather than as
     ;; the failure it is.
     :ambient-frame nil
     :init-fn       (fn [] (rf.hicasso.impl.collector/reset-runtime!))}))

(rf.hicasso/defview panel
  "The whole app: two reads and a caller-supplied tag."
  [{:keys [tag]}]
  [:div.panel {:data-tag tag}
   [:span.label (rf.hicasso/sub [::label])]
   [:span.stamp (str (rf.hicasso/sub [::stamp]))]])

(defn- skip! [why] (is true (str "this claim needs a real React DOM — " why)))

(defn- bare!
  "An empty runtime and NO frame at all — the arrangement every ENSURE row
  needs, and the one `rf/make-frame`-first fixtures cannot give it."
  []
  ;; React's `act` queue is not the browser's scheduler, and every reading
  ;; here is taken outside it.
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf.hicasso.impl.collector/reset-runtime!)
  nil)

(defn- live? [frame-kw] (some? (rf.frame/frame-incarnation-token frame-kw)))

;; The readers take the CONTAINER: under Spec 006 §The client root the mount
;; point is the caller's, and the handle hands it back to nobody.
(defn- node-at [container sel] (.querySelector container sel))

(defn- text-at [container sel]
  (some-> (node-at container sel) .-textContent))

(defn- attr-at [container sel a]
  (some-> (node-at container sel) (.getAttribute a)))

(defn- detach! [container]
  (when-some [c container]
    (when-some [p (.-parentNode c)] (.removeChild p c)))
  nil)

(defn- refusal
  "The ex-data of the refusal `f` throws, or nil if it returns."
  [f]
  (try (f) nil (catch :default e (ex-data e))))

;; ---------------------------------------------------------------------------
;; W1 — the ENSURE is in the TREE, and the FIRST PAINT is still the seeded one
;; ---------------------------------------------------------------------------
;;
;; The row that decides this bead. Nothing is dispatched between the mount and
;; the assertion: what is asserted is the markup `h/render!` itself put on the
;; page, through a boundary whose first render emits no subtree and whose frame
;; is made at commit.

(deftest frame-root-ensures-in-the-tree-and-the-first-paint-is-the-seeded-one
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_ (bare!)
          _ (is (false? (live? ensured))
                "premise: the frame this tree names must not exist yet, or the
                 ENSURE claim below is green against somebody else's frame")
          ca (rf.hicasso.impl.mount/fresh-container!)
          a  (rf.hicasso/client-root)
          _  (rf.hicasso/render! a
               [rf.hicasso/frame-root
                ;; TWO steps, because ORDER is part of the contract: `::seed`
                ;; installs a whole db and `::relabel` edits it, so running them
                ;; the other way round leaves "first" and the reading
                ;; discriminates.
                {:id ensured :initial-events [[::seed "first"] [::relabel "second"]]}
                [panel {:tag "ensured"}]]
               ca)]
      (try
        (testing "the TREE created the frame — the door named none"
          (is (true? (live? ensured))
              "`[h/frame-root {:id …}]` named a frame that did not exist and did
               not make it"))

        (testing "and the seed is in the FIRST paint. Nothing is dispatched
                  between the first render and this read, so the markup asserted
                  here is the render `h/render!` itself performed — the
                  layout-phase flip inside `flushSync`, not a second turn"
          (is (= "second" (text-at ca ".label"))
              (str "the first paint did not carry the `:initial-events` seed; "
                   "got " (pr-str (text-at ca ".label")))))

        (testing "the steps ran IN ORDER — `::relabel` last"
          (is (not= "first" (text-at ca ".label"))
              ":initial-events ran out of order"))

        (testing "the root is ordinarily wired afterwards — the ensured frame
                  is a real frame, not a one-shot seeding trick"
          (rf.hicasso.impl.mount/dispatch! ensured [::relabel "third"])
          (is (= "third" (text-at ca ".label"))))

        (finally
          (rf.hicasso/unmount! a)
          (detach! ca)
          (rf.hicasso.impl.collector/reset-runtime!))))))

;; ---------------------------------------------------------------------------
;; W2 — the head takes the WHOLE `make-frame` option map
;; ---------------------------------------------------------------------------
;;
;; `:fx-overrides` is the key that could not ride the old root-door config, and
;; the reason `examples/substrates/hicasso/login` called `rf/make-frame` first
;; and mounted to JOIN. It rides the head like any other option, because the
;; head IS the `make-frame` call.

(deftest frame-root-takes-the-whole-make-frame-option-map
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_ (bare!)
          _ (reset! !fx-seen nil)
          ca (rf.hicasso.impl.mount/fresh-container!)
          a  (rf.hicasso/client-root)
          _  (rf.hicasso/render! a
               [rf.hicasso/frame-root
                {:id             ensured
                 :initial-events [[::seed "seeded"] [::stamp-via-fx]]
                 :fx-overrides   {::stamp-fx (fn [_ _] (reset! !fx-seen :override))}}
                [panel {:tag "opts"}]]
               ca)]
      (try
        (testing "the override is LIVE on the ensured frame, and it ran during
                  the seed — so `:fx-overrides` reached `make-frame` untouched"
          (is (= :override @!fx-seen)
              (str "the `:fx-overrides` entry did not take: the seeding event's "
                   "effect ran " (pr-str @!fx-seen) " rather than the override")))

        (testing "and the ordinary options went in beside it, on the SAME head:
                  the seed painted"
          (is (= "seeded" (text-at ca ".label"))))
        (finally
          (rf.hicasso/unmount! a)
          (detach! ca)
          (rf.hicasso.impl.collector/reset-runtime!))))))

;; ---------------------------------------------------------------------------
;; W3 — a SECOND boundary under the same :id JOINs: DURABLE STATE survives,
;;      `:initial-events` are not replayed, and the record CONFIG refreshes
;; ---------------------------------------------------------------------------
;;
;; The two halves of this row point in opposite directions, and that is the
;; contract rather than a wrinkle. `frame-root`'s ENSURE is a second
;; `rf/make-frame` under the same id, which is spec/002 §`frame-root`'s
;; reuse-without-reseed on the state side (app-db, sub-cache, queue survive;
;; `:initial-events` are re-recorded, never replayed) and `make-frame`'s ruled
;; IDEMPOTENT REPLACEMENT on the config side — the Clojure re-def model, where
;; re-declaring an id refreshes its config while its state survives.
;;
;; The second `testing` block below is the one that had no witness: the facade
;; docstring once promised "no config refresh", which the shared lifecycle does
;; not do and this file could not see, because a state-only reading is green
;; either way. `:fx-overrides` is the instrument, because a live effect handler
;; is config that ANNOUNCES which incarnation is installed.

(deftest a-second-frame-root-under-one-id-joins-without-re-seeding
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_ (bare!)
          _ (reset! !fx-seen nil)
          ca (rf.hicasso.impl.mount/fresh-container!)
          cb (rf.hicasso.impl.mount/fresh-container!)
          a  (rf.hicasso/client-root)
          b  (rf.hicasso/client-root)]
      (rf.hicasso/render! a
        [rf.hicasso/frame-root
         {:id             ensured
          :initial-events [[::seed "creator"]]
          :fx-overrides   {::stamp-fx (fn [_ _] (reset! !fx-seen :creator))}}
         [panel {:tag "a"}]]
        ca)
      ;; The joining boundary names its own `:initial-events`, and they must
      ;; be IGNORED. A head that replayed would leave "joiner" on both
      ;; screens, and this row is the only thing on the page to notice.
      ;; Its `:fx-overrides` is the opposite case: config, which DOES take.
      (rf.hicasso/render! b
        [rf.hicasso/frame-root
         {:id             ensured
          :initial-events [[::seed "joiner"]]
          :fx-overrides   {::stamp-fx (fn [_ _] (reset! !fx-seen :joiner))}}
         [panel {:tag "b"}]]
        cb)
      (try
        (testing "the joining boundary did not re-seed — the creator's state
                  stands, on both screens"
          (is (= "creator" (text-at ca ".label")))
          (is (= "creator" (text-at cb ".label"))))

        (testing "and it really is ONE frame: a single dispatch moves both"
          (rf.hicasso.impl.mount/dispatch! ensured [::relabel "shared"])
          (is (= ["shared" "shared"] [(text-at ca ".label") (text-at cb ".label")])
              "the two roots did not join one frame"))

        (testing "but the record CONFIG did refresh — the joiner's
                  `:fx-overrides` is the live one. This is `make-frame`'s
                  idempotent replacement, not a defect: JOIN with the creator's
                  opts to change nothing"
          (reset! !fx-seen nil)
          (rf.hicasso.impl.mount/dispatch! ensured [::stamp-via-fx])
          (is (= :joiner @!fx-seen)
              (str "the joining head's `:fx-overrides` did not install: the "
                   "effect ran " (pr-str @!fx-seen) ". A `:creator` reading "
                   "here would mean the shared ENSURE had stopped refreshing "
                   "config, which is a CONTRACT CHANGE across every substrate "
                   "riding `frame-root-fc` — not a Hicasso-local fix")))
        (finally
          (rf.hicasso/unmount! a) (rf.hicasso/unmount! b)
          (detach! ca) (detach! cb)
          (rf.hicasso.impl.collector/reset-runtime!))))))

;; ---------------------------------------------------------------------------
;; W4 — `frame-provider` SCOPEs, and refuses an absent frame
;; ---------------------------------------------------------------------------

(deftest frame-provider-scopes-a-live-frame-and-refuses-an-absent-one
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_ (bare!)
          _ (rf/make-frame {:id scoped :initial-events [[::seed "already here"]]})
          ca (rf.hicasso.impl.mount/fresh-container!)
          a  (rf.hicasso/client-root)
          _  (rf.hicasso/render! a
               [rf.hicasso/frame-provider {:frame scoped} [panel {:tag "scope"}]]
               ca)]
      (try
        (testing "the subtree reads the frame the head named, and the head
                  created nothing"
          (is (= "already here" (text-at ca ".label"))))

        (testing "an ABSENT frame is refused rather than scoped to nothing —
                  the guardrail that is the whole reason SCOPE is its own verb"
          (is (= :rf.error/frame-provider-frame-absent
                 (:rf.error/id
                   (refusal #(rf.hicasso/render!
                               (rf.hicasso/client-root)
                               [rf.hicasso/frame-provider {:frame absent}
                                [panel {:tag "nope"}]]
                               (rf.hicasso.impl.mount/fresh-container!)))))))
        (finally
          (rf.hicasso/unmount! a)
          (detach! ca)
          (rf.hicasso.impl.collector/reset-runtime!))))))

;; ---------------------------------------------------------------------------
;; W5 — the did-you-mean pair: each head refuses the OTHER's key
;; ---------------------------------------------------------------------------
;;
;; No DOM: the refusals are raised during LOWERING, which is ordinary CLJS.

(deftest frame-root-given-a-frame-key-is-refused-naming-frame-provider
  (let [d (refusal #(rf.hicasso/as-element
                      [rf.hicasso/frame-root {:frame ensured} [panel {:tag "x"}]]))]
    (is (= :rf.error/frame-root-given-frame (:rf.error/id d)))
    (is (= 're-frame.hicasso/frame-root (:where d))
        "the refusal must name the head the AUTHOR wrote, not an impl fn")))

(deftest frame-provider-given-an-id-key-is-refused-naming-frame-root
  (let [d (refusal #(rf.hicasso/as-element
                      [rf.hicasso/frame-provider {:id ensured} [panel {:tag "x"}]]))]
    (is (= :rf.error/frame-provider-given-id (:rf.error/id d)))
    (is (= 're-frame.hicasso/frame-provider (:where d)))))

(deftest frame-root-without-an-id-is-refused
  (is (= :rf.error/frame-root-missing-id
         (:rf.error/id
           (refusal #(rf.hicasso/as-element
                       [rf.hicasso/frame-root {} [panel {:tag "x"}]]))))))

;; ---------------------------------------------------------------------------
;; W6 — the root doors carry ROOT options only, and fail loud on the rest
;; ---------------------------------------------------------------------------
;;
;; The No-silent-swallow half. The old config was *closed at three keys and
;; every other key ignored without complaint*, which is how `:fx-overrides`
;; handed to a mount went on the floor.

(deftest the-root-doors-refuse-frame-configuration-naming-the-head-that-takes-it
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no container to hand a door")
    ;; ONE door now, in its two first-call modes: the refusal is the same
    ;; `require-root-options!` on the create path and the hydrate path alike.
    (doseq [[door call] [["render!"                #(rf.hicasso/render! (rf.hicasso/client-root) [panel {}] (rf.hicasso.impl.mount/fresh-container!) %)]
                         ["render! {:hydrate? true}" #(rf.hicasso/render! (rf.hicasso/client-root) [panel {}] (rf.hicasso.impl.mount/fresh-container!) (assoc % :hydrate? true))]]]
    (testing (str "h/" door " given `:frame`")
      (is (= :rf.error/hicasso-frame-config-misplaced
             (:rf.error/id (refusal #(call {:frame ensured}))))))
    (testing (str "h/" door " given `:initial-events`")
      (is (= :rf.error/hicasso-frame-config-misplaced
             (:rf.error/id (refusal #(call {:initial-events [[::seed "x"]]}))))))
    (testing (str "h/" door " given a key it does not own")
      (is (= :rf.error/hicasso-unknown-root-option
             (:rf.error/id (refusal #(call {:fx-overrides {}}))))
          "a misspelled or misplaced root option must be refused, not
           silently dropped")))))

;; ---------------------------------------------------------------------------
;; W7 — THE DOCUMENTED BOOT → RELOAD PAIR, and the trap on the other side of it
;; ---------------------------------------------------------------------------
;;
;; Every reload example this package ships now writes the root tree ONCE, as a
;; function both calls take, so a reload hands `frame-root` the same options
;; the boot render did. That is not a stylistic preference: the guides used to boot
;; with `:initial-events` and reload with `{:id …}` alone, on the reasonable-
;; sounding ground that the seed had already run. `frame-root-opts` strips only
;; `:children` / `:fallback`, and `frame-root-fc` compares the committed opts
;; with the current opts on EVERY later render — so the trimmed reload is not a
;; harmless omission, it is `:rf.error/frame-root-reconfigured`, and a reader
;; copying the pair got a throw where the whole point of `render!` is to
;; preserve the mounted tree.
;;
;; Both directions are witnessed, because either alone reads as an accident:
;; the repaired pair must go through (this row), and the trimmed one must be
;; the refusal that made the repair necessary (W8, which needs a different
;; instrument and says why there). Neither could be seen by W2, which measures
;; the ENSURE's option handoff at MOUNT time and never re-renders.

(defn- reload-tree
  "The documented boot tree, parameterised only by the view code that a hot
  reload is what changes. The OPTIONS are fixed by construction, which is the
  shape the guides now teach."
  [tag]
  [rf.hicasso/frame-root
   {:id reloaded :initial-events [[::seed "boot"]]}
   [panel {:tag tag}]])

(deftest the-documented-boot-then-reload-pair-keeps-its-frame-and-its-state
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_ (bare!)
          _ (is (false? (live? reloaded))
                "premise: the frame this boot names must not exist yet, or the
                 ENSURE below is green against somebody else's frame")
          ca (rf.hicasso.impl.mount/fresh-container!)
          a  (rf.hicasso/client-root)
          _  (rf.hicasso/render! a (reload-tree "boot") ca)
          node (node-at ca ".panel")]
      (try
        (testing "premise: the boot ensured its frame and the seed is painted"
          (is (true? (live? reloaded)))
          (is (= "boot" (text-at ca ".label")))
          (is (= "boot" (attr-at ca ".panel" "data-tag"))))

        ;; State that only the RUNNING application put there. A reload that
        ;; replayed `:initial-events` would put "boot" back over it, so this is
        ;; the reading that separates "reused" from "re-seeded".
        (testing "premise: the application has moved on from its seed"
          (rf.hicasso.impl.mount/dispatch! reloaded [::relabel "live"])
          (is (= "live" (text-at ca ".label"))))

        (testing "the documented reload — the SAME options, new view code —
                  goes through rather than raising
                  `:rf.error/frame-root-reconfigured`"
          (is (nil? (refusal #(rf.hicasso/render! a (reload-tree "reloaded") ca)))
              "the repaired boot → reload pair was refused; a reader copying
               the guide's own recipe would get a throw where `render!` is
               supposed to preserve the mounted tree"))

        (testing "the reloaded view code is on the page"
          (is (= "reloaded" (attr-at ca ".panel" "data-tag"))))

        (testing "and it RECONCILED: the frame is the same one, its live state
                  survived, and the very DOM node the boot produced is still
                  the one on the page"
          (is (true? (live? reloaded)))
          (is (= "live" (text-at ca ".label"))
              "the reload replayed `:initial-events` over the frame's live
               state instead of re-recording them")
          (is (identical? node (node-at ca ".panel"))
              "the reload remounted instead of reconciling"))

        (finally
          (rf.hicasso/unmount! a)
          (detach! ca)
          (rf.hicasso.impl.collector/reset-runtime!))))))

;; ---------------------------------------------------------------------------
;; W8 — THE TRAP on the other side of W7, and why it is witnessed THIS way
;; ---------------------------------------------------------------------------
;;
;; W7 shows the repaired pair going through. On its own that is not evidence
;; the repair was NEEDED: a row that passes proves the guard is quiet, never
;; that it would have spoken. This is the half that bites.
;;
;; **The obvious spelling of it does not work, and the reason is worth having
;; rather than rediscovering.** `require-unchanged-root-opts!` throws in
;; `frame-root-fc`'s RENDER body, and React does not rethrow a render-phase
;; throw to whoever called `flushSync`: it reports it as an uncaught Chromium
;; `pageerror` (measured — `cljs$core$ExceptionInfo`, with React naming
;; `<re_frame$views$frame_boundary$frame_root_fc>` and asking for an error
;; boundary), so a `try`/`catch` around `h/render!` sees NOTHING and reads as
;; "the guard never fired". Worse, this lane's verdict policy makes any
;; pageerror fatal on purpose (`_impl-browser-runners-verdict-policy.test.cjs`,
;; rf2-mwx08 / rf2-wf5al), so provoking one to observe it would red the whole
;; browser suite rather than this row.
;;
;; So the refusal is observed through the affordance the guide tells an
;; application to put there anyway: an `h/error-boundary` ABOVE the frame-root
;; catches the throw, React hands it to the boundary instead of the page, and
;; the FALLBACK taking the tree is the reading. That is also the honest
;; statement of the harm — the mounted tree is replaced, which is exactly what
;; `render!` exists not to do.

(def ^:private trimmed-frame ::trimmed)

(defn- guarded
  "The documented root tree with an error boundary over it — `opts` is the
  frame-root's option map, `tag` the view code a reload is what changes."
  [opts tag]
  [rf.hicasso/error-boundary {:fallback [:p.fell "fell"]}
   [rf.hicasso/frame-root opts [panel {:tag tag}]]])

(deftest a-reload-that-trims-the-heads-options-is-refused-as-a-reconfiguration
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_ (bare!)
          ca (rf.hicasso.impl.mount/fresh-container!)
          a  (rf.hicasso/client-root)
          _  (rf.hicasso/render! a
               (guarded {:id trimmed-frame :initial-events [[::seed "boot"]]}
                        "boot")
               ca)]
      (try
        (testing "premise: the guarded boot painted its seed and did NOT fall
                  back, so the fallback below is this row's doing"
          (is (= "boot" (text-at ca ".label")))
          (is (nil? (text-at ca ".fell"))))

        (testing "the reload TRIMS `:initial-events` — precisely the omission
                  the guides used to teach, on the ground that the seed had
                  already run — and the committed boundary refuses it"
          (rf.hicasso/render! a (guarded {:id trimmed-frame} "trimmed") ca)
          (is (= "fell" (text-at ca ".fell"))
              "a committed frame-root accepted a DIFFERENT option map: the
               silent reconfiguration this boundary exists to refuse")
          (is (nil? (text-at ca ".label"))
              "the tree survived a refusal that should have replaced it, so
               the fallback above is not evidence the guard fired"))

        (finally
          (rf.hicasso/unmount! a)
          (detach! ca)
          (rf.hicasso.impl.collector/reset-runtime!))))))
