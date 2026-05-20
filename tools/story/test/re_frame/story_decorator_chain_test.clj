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
    `:events` (proven by an `:observe` event reading the
    `:frame-setup`-installed value).
  - `:fx-override` decorators stack their stubs via the framework
    `:fx-overrides` map.

  Spec/002 §Per-variant frame allocation pins:

  - Two variants registered with the same event ids each get an
    independent frame: dispatching into A leaves B's app-db, emitted
    fx, assertions, and trace history untouched.
  - The pair runs the SAME `:play` body against DIFFERENT seed args —
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
            [re-frame.frame            :as frame]
            [re-frame.machines         :as machines]
            [re-frame.registrar        :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story            :as story]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.async      :as async]
            [re-frame.story.config     :as config]
            [re-frame.story.decorators :as decorators]
            [re-frame.story.frames     :as frames]
            [re-frame.story.loaders    :as loaders]
            [re-frame.story.play       :as play]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all [test-fn]
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (config/set-global-args! {})
  (reset! assertions/trace-accumulators {})
  (reset! play/stepper-state            {})
  (reset! frames/stub-call-log          {})
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!)
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
    (story/reg-decorator :wrap-A
      {:kind :hiccup :wrap (fn [body _] [:div.A body])})
    (story/reg-decorator :wrap-B
      {:kind :hiccup :wrap (fn [body _] [:div.B body])})
    (story/reg-decorator :wrap-C
      {:kind :hiccup :wrap (fn [body _] [:div.C body])})
    (story/reg-decorator :wrap-D
      {:kind :hiccup :wrap (fn [body _] [:div.D body])})
    (story/reg-story :story.chain
      {:decorators [[:wrap-A] [:wrap-B]]})
    (story/reg-variant :story.chain/v
      {:decorators [[:wrap-C] [:wrap-D]]
       :events     []})
    (let [r         (story/resolve-decorators :story.chain/v)
          ids       (mapv :id (:hiccup r))
          wrapped   (decorators/apply-hiccup-decorators
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
            :frame-setup :init events fire before :events, the
            :fx-override redirect is live, and the resolve-decorators
            pack populates all three slots"
    ;; A :hiccup decorator (only inspected on the canvas side via
    ;; resolve-decorators; the JVM run-variant path produces no DOM).
    (story/reg-decorator :centered-pane
      {:kind :hiccup :wrap (fn [body _] [:div.centered body])})
    ;; A :frame-setup decorator whose :init seeds app-db before :events.
    (rf/reg-event-db :mock/seed
      (fn [db _] (assoc db :mock-user {:name "alice" :role :admin})))
    (story/reg-decorator :seed-user
      {:kind :frame-setup :init [[:mock/seed]]})
    ;; An :fx-override decorator (the registered :rf.story/force-fx-stub
    ;; canonical) — stamps onto :fx-overrides at frame-allocate time.
    (rf/reg-event-db :record/observed
      (fn [db _] (assoc db :seen-user (:mock-user db))))
    (rf/reg-event-fx :emit/track
      (fn [_ _] {:fx [[:analytics {:event :loaded}]]}))
    (story/reg-variant :story.multi-kind/v
      {:decorators [[:centered-pane]
                    [:seed-user]
                    [:rf.story/force-fx-stub :analytics {:ack? true}]]
       :events     [[:record/observed]]
       ;; :emit/track dispatches in :play so the assertion accumulator
       ;; (reset at play-start by reset-trace-accumulators!) sees it.
       :play-script [[:dispatch-sync [:rf.assert/path-equals [:seen-user :name] "alice"]]
                    [:dispatch-sync [:emit/track]]
                    [:dispatch-sync [:rf.assert/effect-emitted :analytics]]]})
    ;; Resolve-decorators classifies the stack into the three slots.
    (let [pack (story/resolve-decorators :story.multi-kind/v)]
      (is (= 1 (count (:hiccup pack)))      ":hiccup slot populated")
      (is (= 1 (count (:frame-setup pack))) ":frame-setup slot populated")
      (is (= 1 (count (:fx-override pack))) ":fx-override slot populated")
      (is (empty? (:errors pack))           "no decorator errors"))
    ;; Run end-to-end: :frame-setup fires first, then :events observe
    ;; the seeded slot, then :play asserts.
    (let [r (async/deref-blocking
              (story/run-variant :story.multi-kind/v) 5000)]
      (is (= :ready (:lifecycle r))
          "lifecycle reaches :ready — multi-kind stack composes without crash")
      (is (= "alice" (-> r :app-db :seen-user :name))
          ":frame-setup ran before :events — :record/observed saw the
           seeded :mock-user even though :seed-user is a decorator
           (not a variant-level event)")
      (let [asserts (:assertions r)]
        (is (= 2 (count asserts)))
        (is (every? :passed? asserts)
            "every play assertion passes — the :fx-override redirect was
             live, the :frame-setup seed landed, the :hiccup decorator
             didn't disturb the data path")))
    (story/destroy-variant! :story.multi-kind/v)))

(deftest extends-inherits-decorators-when-child-declares-none
  (testing ":extends gives a child variant access to its parent's
            :decorators only when the child does NOT declare its own
            :decorators slot. Per spec/007 §Composed variants the
            merge semantics are plain `(merge parent child)` — child
            keys WIN key-by-key; no per-key concat.

            This pins the rf2-ctlm5 §Decorator inheritance acceptance:
            inheritance happens via key absence, NOT vector append."
    (story/reg-decorator :parent-only-deco
      {:kind :hiccup :wrap (fn [body _] [:div.parent-only body])})
    (story/reg-decorator :child-replacement
      {:kind :hiccup :wrap (fn [body _] [:div.child body])})
    (story/reg-variant :story.ext.dec/parent
      {:decorators [[:parent-only-deco]]
       :events     []})
    ;; Case 1 — child declares NO :decorators. It inherits the parent's
    ;; vector verbatim.
    (story/reg-variant :story.ext.dec/inherit-bare
      {:extends :story.ext.dec/parent
       :events  []})
    ;; Case 2 — child declares ITS OWN :decorators. The child's slot
    ;; replaces the parent's (spec'd `merge` semantics). The child's
    ;; story-level decorators are still composed outside (via the
    ;; resolve-decorators story+variant walk) — but the child's
    ;; variant-level slot is its own.
    (story/reg-variant :story.ext.dec/inherit-and-replace
      {:extends    :story.ext.dec/parent
       :decorators [[:child-replacement]]
       :events     []})
    ;; Case 1: bare child inherits parent's :decorators.
    (let [body (story/handler-meta :variant :story.ext.dec/inherit-bare)]
      (is (= [[:parent-only-deco]] (:decorators body))
          ":extends with no override → inherit parent's :decorators"))
    (let [pack (story/resolve-decorators :story.ext.dec/inherit-bare)]
      (is (= [:parent-only-deco] (mapv :id (:hiccup pack)))
          "resolved hiccup stack reflects the inherited vector"))
    ;; Case 2: child with its own :decorators REPLACES parent's.
    (let [body (story/handler-meta :variant :story.ext.dec/inherit-and-replace)]
      (is (= [[:child-replacement]] (:decorators body))
          ":extends with explicit :decorators → child wins (merge
           semantics, no concat); this is the spec/007 §Composed
           variants contract"))
    (let [pack (story/resolve-decorators :story.ext.dec/inherit-and-replace)]
      (is (= [:child-replacement] (mapv :id (:hiccup pack)))
          "resolved hiccup stack reflects ONLY the child's decorators —
           the parent's :parent-only-deco was replaced, not concatenated"))))

;; ===========================================================================
;; Frame-isolation pair — two variants registered against the same event
;; ids. Dispatching into A leaves B untouched: app-db, emitted fx,
;; assertions, and the per-frame stub-call log all isolate.
;; ===========================================================================

(deftest frame-isolation-pair-app-db-and-emitted-fx
  (testing "two variants — same :events, different seed args via
            :frame-setup — each gets its own frame; the dispatches into
            A leave B's app-db / :emitted-fx / :assertions / stub log
            untouched. Per spec/002 §Per-variant frame allocation."
    ;; Two :frame-setup decorators each seed a different counter start.
    (rf/reg-event-db :seed/at-100
      (fn [db _] (assoc db :counter 100)))
    (rf/reg-event-db :seed/at-200
      (fn [db _] (assoc db :counter 200)))
    (rf/reg-event-fx :inc-and-track
      (fn [{:keys [db]} _]
        {:db (update db :counter inc)
         :fx [[:analytics {:event :inc :from (:counter db)}]]}))
    (story/reg-decorator :seed-A {:kind :frame-setup :init [[:seed/at-100]]})
    (story/reg-decorator :seed-B {:kind :frame-setup :init [[:seed/at-200]]})
    (story/reg-variant :story.isolation/A
      {:decorators [[:seed-A]
                    [:rf.story/force-fx-stub :analytics {:ack? true}]]
       :events     [[:inc-and-track] [:inc-and-track]]
       ;; :play emits one more inc-and-track so :rf.assert/effect-emitted
       ;; sees a fresh emission (the events-phase emissions are wiped by
       ;; reset-trace-accumulators! at play start by design).
       :play-script [[:dispatch-sync [:rf.assert/path-equals [:counter] 102]]
                    [:dispatch-sync [:inc-and-track]]
                    [:dispatch-sync [:rf.assert/effect-emitted :analytics]]
                    [:dispatch-sync [:rf.assert/path-equals [:counter] 103]]]})
    (story/reg-variant :story.isolation/B
      {:decorators [[:seed-B]
                    [:rf.story/force-fx-stub :analytics {:ack? true}]]
       :events     [[:inc-and-track] [:inc-and-track]]
       :play-script [[:dispatch-sync [:rf.assert/path-equals [:counter] 202]]
                    [:dispatch-sync [:inc-and-track]]
                    [:dispatch-sync [:rf.assert/effect-emitted :analytics]]
                    [:dispatch-sync [:rf.assert/path-equals [:counter] 203]]]})
    ;; Run both variants. They share event ids + decorator ids; only the
    ;; :frame-setup seed differs. The proof of frame isolation is that
    ;; each lands on its own counter terminal value AND each frame's
    ;; stub-call log carries exactly its own emissions.
    (let [rA (async/deref-blocking (story/run-variant :story.isolation/A) 5000)
          rB (async/deref-blocking (story/run-variant :story.isolation/B) 5000)
          logA (frames/stub-call-log-for :story.isolation/A)
          logB (frames/stub-call-log-for :story.isolation/B)]
      (is (= :ready (:lifecycle rA)))
      (is (= :ready (:lifecycle rB)))
      ;; app-db isolation: each frame walks its own counter. Two
      ;; events-phase incs + one play-phase inc = 3 increments.
      (is (= 103 (:counter (:app-db rA)))
          "A starts at 100 + two :events :inc-and-track + one :play
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
      ;; two during :events phase + one during :play phase. Note the
      ;; stub-call log accumulates across phases (unlike the assertion
      ;; emitted-fx accumulator which the play-runner resets at play
      ;; start).
      (is (= 3 (count logA))
          "frame A's stub log carries exactly three entries — two from
           :events + one from :play")
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
    (story/destroy-variant! :story.isolation/A)
    (story/destroy-variant! :story.isolation/B)))

(deftest frame-isolation-pair-destroy-one-survives-other
  (testing "destroying frame A leaves frame B's app-db / state intact.
            Per spec/002 §Coexistence + §Per-variant frame allocation."
    (rf/reg-event-db :ping (fn [db _] (update db :pings (fnil inc 0))))
    (story/reg-variant :story.iso2/A {:events [[:ping]]})
    (story/reg-variant :story.iso2/B {:events [[:ping]]})
    (async/deref-blocking (story/run-variant :story.iso2/A) 5000)
    (async/deref-blocking (story/run-variant :story.iso2/B) 5000)
    (is (story/variant-frame? :story.iso2/A))
    (is (story/variant-frame? :story.iso2/B))
    (story/destroy-variant! :story.iso2/A)
    (is (not (story/variant-frame? :story.iso2/A))
        "A's frame is gone")
    (is (story/variant-frame? :story.iso2/B)
        "B's frame survives — destroying A had no side effect on B")
    ;; Re-running B against the same id should land cleanly — no shared
    ;; state corruption from A's teardown.
    (let [r (async/deref-blocking (story/reset-variant :story.iso2/B) 5000)]
      (is (= :ready (:lifecycle r)))
      (is (= 1 (:pings (:app-db r)))
          "B's app-db is fresh post-reset; A's teardown didn't pollute it"))
    (story/destroy-variant! :story.iso2/B)))
