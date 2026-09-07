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
  `h/mount!` renders inside `flushSync` — which does not return until
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

(defn- text-at [handle sel]
  (some-> (.querySelector (:container handle) sel) .-textContent))

(defn- detach! [handle]
  (when-some [c (:container handle)]
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
;; the assertion: what is asserted is the markup `h/mount!` itself put on the
;; page, through a boundary whose first render emits no subtree and whose frame
;; is made at commit.

(deftest frame-root-ensures-in-the-tree-and-the-first-paint-is-the-seeded-one
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_ (bare!)
          _ (is (false? (live? ensured))
                "premise: the frame this tree names must not exist yet, or the
                 ENSURE claim below is green against somebody else's frame")
          a (rf.hicasso/mount! (rf.hicasso.impl.mount/fresh-container!) {}
              [rf.hicasso/frame-root
               ;; TWO steps, because ORDER is part of the contract: `::seed`
               ;; installs a whole db and `::relabel` edits it, so running them
               ;; the other way round leaves "first" and the reading
               ;; discriminates.
               {:id ensured :initial-events [[::seed "first"] [::relabel "second"]]}
               [panel {:tag "ensured"}]])]
      (try
        (testing "the TREE created the frame — the door named none"
          (is (true? (live? ensured))
              "`[h/frame-root {:id …}]` named a frame that did not exist and did
               not make it")
          (is (nil? (:frame a))
              "the handle still names a frame; the root door is supposed to
               carry React-root options only now"))

        (testing "and the seed is in the FIRST paint. Nothing is dispatched
                  between the mount and this read, so the markup asserted here
                  is the render `mount!` itself performed — the layout-phase
                  flip inside `flushSync`, not a second turn"
          (is (= "second" (text-at a ".label"))
              (str "the first paint did not carry the `:initial-events` seed; "
                   "got " (pr-str (text-at a ".label")))))

        (testing "the steps ran IN ORDER — `::relabel` last"
          (is (not= "first" (text-at a ".label"))
              ":initial-events ran out of order"))

        (testing "the root is ordinarily wired afterwards — the ensured frame
                  is a real frame, not a one-shot seeding trick"
          (rf.hicasso.impl.mount/dispatch! ensured [::relabel "third"])
          (is (= "third" (text-at a ".label"))))

        (finally
          (rf.hicasso/unmount! a)
          (detach! a)
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
          a (rf.hicasso/mount! (rf.hicasso.impl.mount/fresh-container!) {}
              [rf.hicasso/frame-root
               {:id             ensured
                :initial-events [[::seed "seeded"] [::stamp-via-fx]]
                :fx-overrides   {::stamp-fx (fn [_ _] (reset! !fx-seen :override))}}
               [panel {:tag "opts"}]])]
      (try
        (testing "the override is LIVE on the ensured frame, and it ran during
                  the seed — so `:fx-overrides` reached `make-frame` untouched"
          (is (= :override @!fx-seen)
              (str "the `:fx-overrides` entry did not take: the seeding event's "
                   "effect ran " (pr-str @!fx-seen) " rather than the override")))

        (testing "and the ordinary options went in beside it, on the SAME head:
                  the seed painted"
          (is (= "seeded" (text-at a ".label"))))
        (finally
          (rf.hicasso/unmount! a)
          (detach! a)
          (rf.hicasso.impl.collector/reset-runtime!))))))

;; ---------------------------------------------------------------------------
;; W3 — a SECOND boundary under the same :id JOINs: no re-seed, no refresh
;; ---------------------------------------------------------------------------

(deftest a-second-frame-root-under-one-id-joins-without-re-seeding
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_ (bare!)
          a (rf.hicasso/mount! (rf.hicasso.impl.mount/fresh-container!) {}
              [rf.hicasso/frame-root
               {:id ensured :initial-events [[::seed "creator"]]}
               [panel {:tag "a"}]])
          ;; The joining boundary names its own `:initial-events`, and they must
          ;; be IGNORED. A head that replayed would leave "joiner" on both
          ;; screens, and this row is the only thing on the page to notice.
          b (rf.hicasso/mount! (rf.hicasso.impl.mount/fresh-container!) {}
              [rf.hicasso/frame-root
               {:id ensured :initial-events [[::seed "joiner"]]}
               [panel {:tag "b"}]])]
      (try
        (testing "the joining boundary did not re-seed — the creator's state
                  stands, on both screens"
          (is (= "creator" (text-at a ".label")))
          (is (= "creator" (text-at b ".label"))))

        (testing "and it really is ONE frame: a single dispatch moves both"
          (rf.hicasso.impl.mount/dispatch! ensured [::relabel "shared"])
          (is (= ["shared" "shared"] [(text-at a ".label") (text-at b ".label")])
              "the two roots did not join one frame"))
        (finally
          (rf.hicasso/unmount! a) (rf.hicasso/unmount! b)
          (detach! a) (detach! b)
          (rf.hicasso.impl.collector/reset-runtime!))))))

;; ---------------------------------------------------------------------------
;; W4 — `frame-provider` SCOPEs, and refuses an absent frame
;; ---------------------------------------------------------------------------

(deftest frame-provider-scopes-a-live-frame-and-refuses-an-absent-one
  (if-not (rf.hicasso.impl.mount/browser?)
    (skip! ":node-test has no DOM")
    (let [_ (bare!)
          _ (rf/make-frame {:id scoped :initial-events [[::seed "already here"]]})
          a (rf.hicasso/mount! (rf.hicasso.impl.mount/fresh-container!) {}
              [rf.hicasso/frame-provider {:frame scoped} [panel {:tag "scope"}]])]
      (try
        (testing "the subtree reads the frame the head named, and the head
                  created nothing"
          (is (= "already here" (text-at a ".label"))))

        (testing "an ABSENT frame is refused rather than scoped to nothing —
                  the guardrail that is the whole reason SCOPE is its own verb"
          (is (= :rf.error/frame-provider-frame-absent
                 (:rf.error/id
                   (refusal #(rf.hicasso/mount!
                               (rf.hicasso.impl.mount/fresh-container!) {}
                               [rf.hicasso/frame-provider {:frame absent}
                                [panel {:tag "nope"}]]))))))
        (finally
          (rf.hicasso/unmount! a)
          (detach! a)
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
    (doseq [[door call] [["mount!"   #(rf.hicasso/mount! (rf.hicasso.impl.mount/fresh-container!) % [panel {}])]
                         ["hydrate!" #(rf.hicasso/hydrate! (rf.hicasso.impl.mount/fresh-container!) % [panel {}])]]]
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
