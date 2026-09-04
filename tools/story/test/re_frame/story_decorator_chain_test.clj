(ns re-frame.story-decorator-chain-test
  "Canvas-side regression net for decorator-chain composition and the
  per-variant frame-isolation pair (rf2-b9f3i).

  Spec/002 §Decorator composition pins:

  - Story-level decorators wrap OUTER; variant-level decorators wrap
    INNER. The applied stack reads outer-first → inner-last.
  - Multi-decorator stacks compose in declared order at each level
    (story decorators in declared order outside variant decorators in
    declared order).
  - `:frame-setup` decorators' `:init` events fire BEFORE the variant's
    `:setup` (proven by an `:observe` event reading the
    `:frame-setup`-installed value).
  - `:fx-override` decorators stack their stubs via the framework
    `:fx-overrides` map.

  Spec/002 §Per-variant frame allocation pins:

  - Two variants registered with the same event ids each get an
    independent frame: dispatching into A leaves B's app-db, emitted
    fx, assertions, and trace history untouched.
  - The pair runs the SAME `:script` body against DIFFERENT seed args —
    so the only thing distinguishing the two frames' final app-db is
    the seed.

  Pure-data-side coverage of the resolve order lives in
  `re-frame.story-runtime-test` §`decorators-story-then-variant-order`
  and §`decorators-apply-hiccup-outermost-first`. This namespace covers
  the *end-to-end* invariants: the decorator stack actually runs in
  the spec'd order AND the frame-isolation pair actually keeps two
  parallel runs separate."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.async      :as rf.story.async]
            [re-frame.story.config     :as rf.story.config]
            [re-frame.story.decorators :as rf.story.decorators]
            [re-frame.story.frames     :as rf.story.frames]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.story.play       :as rf.story.play]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.config/set-global-args! {})
  (reset! rf.story.play/stepper-state            {})
  (reset! rf.story.frames/stub-call-log          {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-all)

;; ===========================================================================
;; Decorator-chain composition — multi-decorator stack composes in
;; declared order at each level (story decorators outside variant
;; decorators).
;; ===========================================================================

(deftest hiccup-multi-decorator-applies-in-declared-order
  (testing "two story-level + two variant-level :hiccup decorators wrap in
            the spec'd order: outer (story's first) outside inner
            (variant's last)"
    (rf.story/reg-decorator :wrap-A
      {:kind :hiccup :wrap (fn [body _] [:div.A body])})
    (rf.story/reg-decorator :wrap-B
      {:kind :hiccup :wrap (fn [body _] [:div.B body])})
    (rf.story/reg-decorator :wrap-C
      {:kind :hiccup :wrap (fn [body _] [:div.C body])})
    (rf.story/reg-decorator :wrap-D
      {:kind :hiccup :wrap (fn [body _] [:div.D body])})
    (rf.story/reg-story :story.chain
      {:decorators [[:wrap-A] [:wrap-B]]})
    (rf.story/reg-variant :story.chain/v
      {:decorators [[:wrap-C] [:wrap-D]]
       :setup     []})
    (let [r         (rf.story/resolve-decorators :story.chain/v)
          ids       (mapv :id (:hiccup r))
          wrapped   (rf.story.decorators/apply-hiccup-decorators
                      (:hiccup r) [:span "leaf"] {})]
      (is (= [:wrap-A :wrap-B :wrap-C :wrap-D] ids)
          "stack order: story decorators (declared order) precede
           variant decorators (declared order). spec/002 §Decorator
           composition.")
      ;; The applied tree must wrap outermost-first → leaf-last. The
      ;; topmost wrapper is the FIRST in the stack (story's :wrap-A)
      ;; and the closest-to-leaf wrapper is the LAST (variant's :wrap-D).
      (is (= [:div.A [:div.B [:div.C [:div.D [:span "leaf"]]]]]
             wrapped)
          "applied tree: :wrap-A outermost, :wrap-D closest to leaf"))))

(deftest multi-kind-stack-composition-runs-on-canvas
  (testing "a stack with one :hiccup + one :frame-setup + one :fx-override
            decorator runs cleanly end-to-end through run-variant: the
            :frame-setup :init events fire before :setup, the
            :fx-override redirect is live, and the resolve-decorators
            pack populates all three slots"
    ;; A :hiccup decorator (only inspected on the canvas side via
    ;; resolve-decorators; the JVM run-variant path produces no DOM).
    (rf.story/reg-decorator :centered-pane
      {:kind :hiccup :wrap (fn [body _] [:div.centered body])})
    ;; A :frame-setup decorator whose :init seeds app-db before :setup.
    (rf/reg-event :mock/seed
      (fn [{:keys [db]} _] {:db (assoc db :mock-user {:name "alice" :role :admin})}))
    (rf.story/reg-decorator :seed-user
      {:kind :frame-setup :init [[:mock/seed]]})
    ;; An :fx-override decorator (the registered :rf.story/force-fx-stub
    ;; canonical) — stamps onto :fx-overrides at frame-allocate time.
    (rf/reg-event :record/observed
      (fn [{:keys [db]} _] {:db (assoc db :seen-user (:mock-user db))}))
    (rf/reg-event :emit/track
      (fn [_ _] {:fx [[:analytics {:event :loaded}]]}))
    (rf.story/reg-variant :story.multi-kind/v
      {:decorators [[:centered-pane]
                    [:seed-user]
                    [:rf.story/force-fx-stub :analytics {:ack? true}]]
       :setup     [[:record/observed]]
       ;; :emit/track dispatches in :script so the tape + stub-call log
       ;; (the SSOT :rf.assert/effect-emitted projects from — rf2-luzky) sees it.
       :script [[:dispatch-sync [:rf.assert/path-equals [:seen-user :name] "alice"]]
                    [:dispatch-sync [:emit/track]]
                    [:dispatch-sync [:rf.assert/effect-emitted :analytics]]]})
    ;; Resolve-decorators classifies the stack into the three slots.
    (let [pack (rf.story/resolve-decorators :story.multi-kind/v)]
      (is (= 1 (count (:hiccup pack)))      ":hiccup slot populated")
      (is (= 1 (count (:frame-setup pack))) ":frame-setup slot populated")
      (is (= 1 (count (:fx-override pack))) ":fx-override slot populated")
      (is (empty? (:errors pack))           "no decorator errors"))
    ;; Run end-to-end: :frame-setup fires first, then :setup observe
    ;; the seeded slot, then :script asserts.
    (let [r (rf.story.async/deref-blocking
              (rf.story/run-variant :story.multi-kind/v) 5000)]
      (is (= :ready (:lifecycle r))
          "lifecycle reaches :ready — multi-kind stack composes without crash")
      (is (= "alice" (-> r :app-db :seen-user :name))
          ":frame-setup ran before :setup — :record/observed saw the
           seeded :mock-user even though :seed-user is a decorator
           (not a variant-level event)")
      (let [asserts (:assertions r)]
        (is (= 2 (count asserts)))
        (is (every? :passed? asserts)
            "every play assertion passes — the :fx-override redirect was
             live, the :frame-setup seed landed, the :hiccup decorator
             didn't disturb the data path")))
    (rf.story/destroy-variant! :story.multi-kind/v)))

(deftest extends-inherits-decorators-when-child-declares-none
  (testing ":extends gives a child variant access to its parent's
            :decorators only when the child does NOT declare its own
            :decorators slot. The PLAN COMPILER is the merge authority
            (rf2-f6z88 / rf2-g74i9, spec/017 §305-306): the registrar
            stores the RAW body (`:extends` intact); the compiler walks
            the chain and folds `:decorators` into `[:world :decorators]`
            child-wins (no per-key concat). `resolve-decorators` reads
            the compiled plan, so a bare child INHERITS the parent's
            decorator stack."
    (rf.story/reg-decorator :parent-only-deco
      {:kind :hiccup :wrap (fn [body _] [:div.parent-only body])})
    (rf.story/reg-decorator :child-replacement
      {:kind :hiccup :wrap (fn [body _] [:div.child body])})
    (rf.story/reg-variant :story.ext.dec/parent
      {:decorators [[:parent-only-deco]]
       :setup     []})
    ;; Case 1 — child declares NO :decorators. It inherits the parent's
    ;; vector verbatim.
    (rf.story/reg-variant :story.ext.dec/inherit-bare
      {:extends :story.ext.dec/parent
       :setup  []})
    ;; Case 2 — child declares ITS OWN :decorators. The child's slot
    ;; replaces the parent's (spec'd `merge` semantics). The child's
    ;; story-level decorators are still composed outside (via the
    ;; resolve-decorators story+variant walk) — but the child's
    ;; variant-level slot is its own.
    (rf.story/reg-variant :story.ext.dec/inherit-and-replace
      {:extends    :story.ext.dec/parent
       :decorators [[:child-replacement]]
       :setup     []})
    ;; Case 1: bare child inherits parent's :decorators via the plan.
    (let [body (rf.story/handler-meta :variant :story.ext.dec/inherit-bare)]
      (is (= :story.ext.dec/parent (:extends body))
          ":extends stored RAW on the side-table body")
      (is (nil? (:decorators body))
          "bare child declares no :decorators; the raw body carries none —
           inheritance is resolved downstream at plan-compile"))
    (let [pack (rf.story/resolve-decorators :story.ext.dec/inherit-bare)]
      (is (= [:parent-only-deco] (mapv :id (:hiccup pack)))
          "resolved hiccup stack INHERITS the parent's decorator via the
           compiled plan's [:world :decorators]"))
    ;; Case 2: child with its own :decorators REPLACES parent's.
    (let [body (rf.story/handler-meta :variant :story.ext.dec/inherit-and-replace)]
      (is (= [[:child-replacement]] (:decorators body))
          "the raw body carries the child's OWN :decorators verbatim")
      (is (= :story.ext.dec/parent (:extends body))
          ":extends stored RAW — compiler resolves child-wins (no concat)"))
    (let [pack (rf.story/resolve-decorators :story.ext.dec/inherit-and-replace)]
      (is (= [:child-replacement] (mapv :id (:hiccup pack)))
          "resolved hiccup stack reflects ONLY the child's decorators —
           the parent's :parent-only-deco was replaced, not concatenated"))))

;; ===========================================================================
;; Frame-isolation pair — two variants registered against the same event
;; ids. Dispatching into A leaves B untouched: app-db, emitted fx,
;; assertions, and the per-frame stub-call log all isolate.
;; ===========================================================================

(deftest frame-isolation-pair-app-db-and-emitted-fx
  (testing "two variants — same :setup, different seed args via
            :frame-setup — each gets its own frame; the dispatches into
            A leave B's app-db / :emitted-fx / :assertions / stub log
            untouched. Per spec/002 §Per-variant frame allocation."
    ;; Two :frame-setup decorators each seed a different counter start.
    (rf/reg-event :seed/at-100
      (fn [{:keys [db]} _] {:db (assoc db :counter 100)}))
    (rf/reg-event :seed/at-200
      (fn [{:keys [db]} _] {:db (assoc db :counter 200)}))
    (rf/reg-event :inc-and-track
      (fn [{:keys [db]} _]
        {:db (update db :counter inc)
         :fx [[:analytics {:event :inc :from (:counter db)}]]}))
    (rf.story/reg-decorator :seed-A {:kind :frame-setup :init [[:seed/at-100]]})
    (rf.story/reg-decorator :seed-B {:kind :frame-setup :init [[:seed/at-200]]})
    (rf.story/reg-variant :story.isolation/A
      {:decorators [[:seed-A]
                    [:rf.story/force-fx-stub :analytics {:ack? true}]]
       :setup     [[:inc-and-track] [:inc-and-track]]
       ;; :script emits one more inc-and-track so :rf.assert/effect-emitted
       ;; sees the emission via the tape + stub-call log SSOT (rf2-luzky —
       ;; there is no play-start accumulator reset).
       :script [[:dispatch-sync [:rf.assert/path-equals [:counter] 102]]
                    [:dispatch-sync [:inc-and-track]]
                    [:dispatch-sync [:rf.assert/effect-emitted :analytics]]
                    [:dispatch-sync [:rf.assert/path-equals [:counter] 103]]]})
    (rf.story/reg-variant :story.isolation/B
      {:decorators [[:seed-B]
                    [:rf.story/force-fx-stub :analytics {:ack? true}]]
       :setup     [[:inc-and-track] [:inc-and-track]]
       :script [[:dispatch-sync [:rf.assert/path-equals [:counter] 202]]
                    [:dispatch-sync [:inc-and-track]]
                    [:dispatch-sync [:rf.assert/effect-emitted :analytics]]
                    [:dispatch-sync [:rf.assert/path-equals [:counter] 203]]]})
    ;; Run both variants. They share event ids + decorator ids; only the
    ;; :frame-setup seed differs. The proof of frame isolation is that
    ;; each lands on its own counter terminal value AND each frame's
    ;; stub-call log carries exactly its own emissions.
    (let [rA (rf.story.async/deref-blocking (rf.story/run-variant :story.isolation/A) 5000)
          rB (rf.story.async/deref-blocking (rf.story/run-variant :story.isolation/B) 5000)
          logA (rf.story.frames/stub-call-log-for :story.isolation/A)
          logB (rf.story.frames/stub-call-log-for :story.isolation/B)]
      (is (= :ready (:lifecycle rA)))
      (is (= :ready (:lifecycle rB)))
      ;; app-db isolation: each frame walks its own counter. Two
      ;; events-phase incs + one play-phase inc = 3 increments.
      (is (= 103 (:counter (:app-db rA)))
          "A starts at 100 + two :setup :inc-and-track + one :script
           :inc-and-track = 103")
      (is (= 203 (:counter (:app-db rB)))
          "B starts at 200 + same three increments = 203 — A's
           dispatches did NOT leak into B's frame")
      ;; assertion isolation: each frame has its own :assertions vector.
      (is (every? :passed? (:assertions rA))
          "all A's assertions pass against A's app-db")
      (is (every? :passed? (:assertions rB))
          "all B's assertions pass against B's app-db")
      ;; emitted-fx isolation: the stub-call log keys by frame-id; each
      ;; frame's log carries only its own emissions. Three per frame:
      ;; two during :setup phase + one during :script phase. Note the
      ;; stub-call log accumulates across phases (unlike the assertion
      ;; emitted-fx accumulator which the play-runner resets at play
      ;; start).
      (is (= 3 (count logA))
          "frame A's stub log carries exactly three entries — two from
           :setup + one from :script")
      (is (= 3 (count logB))
          "frame B's stub log carries exactly three entries — and ZERO
           of A's entries leaked across")
      ;; Belt-and-braces: each log's payload carries the per-frame
      ;; counter value at emit time — proving the dispatch saw the
      ;; frame-local app-db, not a shared one.
      (is (= [{:event :inc :from 100}
              {:event :inc :from 101}
              {:event :inc :from 102}]
             (mapv :payload logA)))
      (is (= [{:event :inc :from 200}
              {:event :inc :from 201}
              {:event :inc :from 202}]
             (mapv :payload logB))))
    (rf.story/destroy-variant! :story.isolation/A)
    (rf.story/destroy-variant! :story.isolation/B)))

(deftest frame-isolation-pair-destroy-one-survives-other
  (testing "destroying frame A leaves frame B's app-db / state intact.
            Per spec/002 §Coexistence + §Per-variant frame allocation."
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db (update db :pings (fnil inc 0))}))
    (rf.story/reg-variant :story.iso2/A {:setup [[:ping]]})
    (rf.story/reg-variant :story.iso2/B {:setup [[:ping]]})
    (rf.story.async/deref-blocking (rf.story/run-variant :story.iso2/A) 5000)
    (rf.story.async/deref-blocking (rf.story/run-variant :story.iso2/B) 5000)
    (is (rf.story/variant-frame? :story.iso2/A))
    (is (rf.story/variant-frame? :story.iso2/B))
    (rf.story/destroy-variant! :story.iso2/A)
    (is (not (rf.story/variant-frame? :story.iso2/A))
        "A's frame is gone")
    (is (rf.story/variant-frame? :story.iso2/B)
        "B's frame survives — destroying A had no side effect on B")
    ;; Re-running B against the same id should land cleanly — no shared
    ;; state corruption from A's teardown.
    (let [r (rf.story.async/deref-blocking (rf.story/reset-variant :story.iso2/B) 5000)]
      (is (= :ready (:lifecycle r)))
      (is (= 1 (:pings (:app-db r)))
          "B's app-db is fresh post-reset; A's teardown didn't pollute it"))
    (rf.story/destroy-variant! :story.iso2/B)))
