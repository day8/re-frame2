(ns re-frame.ui.test-render-jvm-test
  "`ui.test/render` — the two accepted root-or-view forms + rejection
  rules (root-identity-and-mount §9), the frame opts (:frame,
  :props, :sub-overrides), the tree-version stamp, and the JVM-only
  selector spellings (defview var; the compiled-view-fn guard)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.test :as uit])
  (:import [java.util.concurrent CountDownLatch CyclicBarrier TimeUnit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  ;; the plan-install registry is process-global; a plan-bearing render
  ;; tears its frames down (invalidating their records), but wipe it around
  ;; each test so a stale record never leaks between cases.
  (fn [t] (frames/reset-installed-plans!) (t) (frames/reset-installed-plans!)))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defview badge
  [{:keys [label]}]
  [:span.badge label])

(defview card
  [{:keys [title n]}]
  [:div.card
   [badge {:label title}]
   [:p "count=" n]])

(defview greeting
  "Reads a sub — the Tier-1 headless read path (03 §3)."
  []
  [:h1.greeting (ui/sub [:greeting/text])])

(def ^:dynamic *render-gate*
  "Concurrency-test seam: the `gated` view calls this thunk while it renders —
  the window BETWEEN a plan-bearing render's frame install and its teardown. A
  top-level view cannot close over a test's latches, so the barrier rides this
  dynamic var (conveyed into the render's thread by `future`). Default no-op."
  (fn []))

(defview gated
  "A pure structural view that trips `*render-gate*` as it renders, so a test
  can hold a plan-bearing render open between install and teardown — no sleeps,
  and no sub read, so a concurrent actor may destroy/re-create the frame under
  the same id without the render's own read racing the swap."
  []
  (let [_ (*render-gate*)]
    [:h1.gated "gated"]))

(defn- err-id [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo ex
      (:rf.error/id (ex-data ex)))))

(defn- expand-error
  "Macroexpand a ui.test/render form; nil when it expands, the
  :rf.ui.compile/error id when it throws. Quoted forms use fully-
  qualified head symbols so resolution is *ns*-independent."
  [form]
  (try
    (macroexpand-1 form)
    nil
    (catch clojure.lang.ExceptionInfo ex
      (:rf.ui.compile/error (ex-data ex)))
    (catch Exception ex
      (let [c (.getCause ex)]
        (when (instance? clojure.lang.ExceptionInfo c)
          (:rf.ui.compile/error (ex-data c)))))))

(defn- eval-error
  "Eval a form (full compilation, so &env locals exist); nil on success,
  the :rf.ui.compile/error id when compilation throws."
  [form]
  (try
    (eval form)
    nil
    (catch clojure.lang.ExceptionInfo ex
      (:rf.ui.compile/error (ex-data ex)))
    (catch clojure.lang.Compiler$CompilerException ex
      (let [c (.getCause ex)]
        (when (instance? clojure.lang.ExceptionInfo c)
          (:rf.ui.compile/error (ex-data c)))))))

;; ---------------------------------------------------------------------------
;; Form 1 — a view reference (compile-resolved defview var/symbol)
;; ---------------------------------------------------------------------------

(deftest view-reference-renders
  (let [tree (uit/render badge {:props {:label "hi"}})]
    (is (= {:view-id ::badge
            :props {:label "hi"}
            :children [{:tag :span :attrs {:class "badge"} :children ["hi"]}]
            :rf.ui/tree-version 1}
           tree)
        "the root is the view's boundary node, stamped with the version"))
  (is (= 1 (:rf.ui/tree-version (uit/render badge {:props {:label "x"}}))))
  (is (= ::badge (:view-id (uit/render badge)))
      "opts are optional — a props-less view renders against {}")
  (is (= ::badge (:view-id (uit/render #'badge {:props {:label "v"}})))
      "the var-quote spelling is the same form"))

(deftest view-reference-nests
  (let [tree (uit/render card {:props {:title "T" :n 3}})]
    (is (= "T" (-> tree (uit/find ::badge) uit/text))
        "internal views expand — boundaries nest")
    (is (= "count=3" (-> tree (uit/find :p) uit/text))
        "numeric children canonicalize via the tree builders")))

;; ---------------------------------------------------------------------------
;; Form 2 — mount's literal top-region syntax, tightened to one mounted internal view
;; (:rf.ui.compile/bad-test-root; wrap a multi-view composition in one defview)
;; ---------------------------------------------------------------------------

(deftest literal-root-form-renders
  (let [n    2
        tree (uit/render [card {:title "Lit" :n (+ n 1)}])]
    (is (= 1 (:rf.ui/tree-version tree)) "version stamps the literal form too")
    (is (= ::card (:view-id tree)) "one mounted view per root form")
    (is (= "count=3" (-> tree (uit/find :p) uit/text))
        "props expressions evaluate in the caller's lexical scope")))

;; ---------------------------------------------------------------------------
;; Accepted-forms rejections (compile time)
;; ---------------------------------------------------------------------------

(deftest rejected-root-forms
  (is (= :rf.ui.compile/bad-test-root
         (expand-error '(re-frame.ui.test/render [:div "x"] nil)))
      "a bare element has no view identity — root identity is the view's id")
  (is (= :rf.ui.compile/bad-test-root
         (expand-error '(re-frame.ui.test/render [:<> [:i "a"]] nil)))
      "fragments likewise")
  (is (= :rf.ui.compile/bad-test-root
         (expand-error '(re-frame.ui.test/render
                         [clojure.string/join {}] nil)))
      "a foreign head is not a defview (and never appears in the JVM tree)")
  (is (= :rf.ui.compile/unresolved-head
         (expand-error '(re-frame.ui.test/render [nope-not-a-thing {}] nil)))
      "unresolved heads fail with the analyzer's didactic error")
  (is (= :rf.ui.compile/bad-test-render-form
         (expand-error '(re-frame.ui.test/render clojure.string/join nil)))
      "a resolvable non-view symbol is not a view reference")
  (is (= :rf.ui.compile/bad-test-render-form
         (expand-error '(re-frame.ui.test/render nope-not-a-thing nil)))
      "an unresolvable symbol names neither form")
  (is (= :rf.ui.compile/bad-test-render-form
         (expand-error '(re-frame.ui.test/render 42 nil)))
      "exactly two accepted forms")
  (is (= :rf.ui.compile/bad-test-render-form
         (expand-error '(re-frame.ui.test/render (vector :div "x") nil)))
      "a runtime-assembled vector is the same compile error as at mount"))

(deftest rejected-local-bindings
  (is (= :rf.ui.compile/bad-test-render-form
         (eval-error '(let [v 1] (re-frame.ui.test/render v nil))))
      "a local binding cannot be a view reference — hiccup is compiled")
  (is (= :rf.ui.compile/dynamic-head
         (eval-error '(let [h 1] (re-frame.ui.test/render [h {}] nil))))
      "a local head inside a literal root form is a dynamic head"))

;; ---------------------------------------------------------------------------
;; Opts validation (runtime; the closed opts map)
;; ---------------------------------------------------------------------------

(deftest opts-validation
  (is (= :rf.error/ui-test-bad-opts
         (err-id #(uit/render badge {:props {:label "x"} :unknown 1})))
      "the opts map is CLOSED")
  (is (= :rf.error/ui-test-bad-opts
         (err-id #(uit/render badge {:props [:label "x"]})))
      ":props must be a map — a view is a pure fn of ONE props map")
  (is (= :rf.error/ui-test-bad-opts
         (err-id #(uit/render badge "not-a-map")))
      "opts must be a map")
  (is (= :rf.error/ui-test-bad-opts
         (err-id #(uit/render [badge {:label "x"}] {:props {:label "y"}})))
      "a literal root form carries its props IN the form — {:props} rejected")
  (is (= :rf.error/ui-test-bad-opts
         (err-id #(uit/render badge {:sub-overrides {:not-a-vector 1}})))
      ":sub-overrides keys are query vectors"))

(deftest sub-overrides-accepted-and-carried
  (is (= 1 (:rf.ui/tree-version
            (uit/render badge {:props {:label "x"}
                               :sub-overrides {[:cart/locked?] true}})))
      "the override door validates + binds; a sub-free view is inert"))

;; ---------------------------------------------------------------------------
;; S2b — the Tier-1 headless sub read (03 §3): a real read against a frame,
;; and the explicit override door intercepting it
;; ---------------------------------------------------------------------------

(deftest sub-read-against-a-frame
  (rf/reg-sub :greeting/text (fn [db _] (:greeting db)))
  (let [f    (rf/make-frame {:initial-events [[:rf/set-db {:greeting "hello"}]]})
        tree (uit/render greeting {:frame f})]
    (is (= "hello" (uit/text (uit/find tree :h1)))
        "the compiled view's (sub …) reads the real cache value on the JVM"))
  (testing "app-db movement is reflected on re-render (headless read points)"
    (rf/reg-event ::set-greeting
      (fn [{:keys [db]} [_ v]] {:db (assoc db :greeting v)}))
    (let [f (rf/make-frame {:initial-events [[:rf/set-db {:greeting "hi"}]]})]
      (uit/dispatch! f [::set-greeting "bye"])
      (is (= "bye" (uit/text (uit/find (uit/render greeting {:frame f}) :h1)))
          "a re-render reads the moved value"))))

(deftest sub-read-honours-the-override-door
  (rf/reg-sub :greeting/text (fn [db _] (:greeting db)))
  (let [f    (rf/make-frame {:initial-events [[:rf/set-db {:greeting "real"}]]})
        tree (uit/render greeting {:frame f
                                   :sub-overrides {[:greeting/text] "pinned"}})]
    (is (= "pinned" (uit/text (uit/find tree :h1)))
        "the explicit override door intercepts the read (Story-override door,
         JVM spelling — 03 §3)")))

(deftest sub-read-unknown-sub-is-fail-loud
  (let [f (rf/make-frame {:initial-events [[:rf/set-db {}]]})]
    (is (= :rf.error/no-such-sub
           (err-id #(uit/render greeting {:frame f})))
        "an unregistered entry sub is fail-loud through the observation port")))

;; ---------------------------------------------------------------------------
;; Frame opts — :frame targets a held frame
;; ---------------------------------------------------------------------------

(deftest frame-opt-targets-a-held-frame
  (let [f    (rf/make-frame {:initial-events [[:rf/set-db {:cart #{7}}]]})
        tree (uit/render [badge {:label "held"}] {:frame f})]
    (is (= "held" (uit/text (uit/find tree :span))))
    (is (= {:cart #{7}} (rf/app-db-value f))
        "the held frame survives the render (caller-owned lifecycle)")))

(deftest frameless-render-proceeds
  (is (= ::badge (:view-id (uit/render badge {:props {:label "x"}})))
      "with no :frame, structural rendering proceeds"))

;; ---------------------------------------------------------------------------
;; dispatch! (07 §2)
;; ---------------------------------------------------------------------------

(deftest dispatch!-is-real-dispatch-plus-drain
  (rf/reg-event ::add
    (fn [{:keys [db]} [_ id]] {:db (update db :cart conj id)}))
  (let [f (rf/make-frame {:initial-events [[:rf/set-db {:cart #{}}]]})]
    (uit/dispatch! f [::add 42])
    (is (= #{42} (:cart (rf/app-db-value f)))
        "dispatch! drains synchronously into the target frame")))

;; ---------------------------------------------------------------------------
;; JVM-only selector spellings — defview var; the view-fn guard
;; ---------------------------------------------------------------------------

(deftest var-selector-matches-the-boundary
  (let [tree (uit/render card {:props {:title "T" :n 1}})]
    (is (= ::badge (:view-id (uit/find tree #'badge)))
        "the defview var resolves to its registered id")
    (is (= (uit/find tree ::badge) (uit/find tree #'badge))
        "var and view-id spellings are the same selector")))

(deftest non-view-var-selector-rejected
  (let [tree (uit/render badge {:props {:label "x"}})]
    (is (= :rf.error/ui-test-bad-selector
           (err-id #(uit/find tree #'clojure.string/join)))
        "a var without defview meta is not a view selector")))

(deftest compiled-view-fn-selector-guard
  (let [tree (uit/render card {:props {:title "T" :n 1}})]
    (is (= :rf.error/ui-test-bad-selector
           (err-id #(uit/find tree badge)))
        "the view FN would match everything as a pred-fn — the guard names
         the var/view-id spellings instead")))

;; ---------------------------------------------------------------------------
;; Plan-bearing root forms (rf2-vxgfnd.19) — the literal form follows the
;; SAME root grammar ui/mount takes: a top-region frame-root contributes a
;; static frame plan, ENSURE preflights FRESH test frames before the JVM
;; structural render, the mounted view resolves the innermost enclosing
;; frame-root's frame as ambient scope, {:frame} is rejected, and
;; every test-owned frame is torn down.
;; ---------------------------------------------------------------------------

(defn- reg-greeting! []
  (rf/reg-sub :greeting/text (fn [db _] (:greeting db))))

(deftest plan-bearing-root-renders-from-the-preflighted-frame
  (reg-greeting!)
  (let [before (set (keys @frame/frames))
        tree   (uit/render
                [ui/frame-root {:id :test/greet
                                :initial-events [[:rf/set-db {:greeting "planned"}]]}
                 [greeting {}]])]
    (is (= ::greeting (:view-id (uit/find tree ::greeting)))
        "the one mounted view is found under the transparent frame-root")
    (is (= "planned" (uit/text (uit/find tree :h1)))
        "the view's (sub …) reads the plan's ambient frame on the JVM")
    (is (= 1 (:rf.ui/tree-version tree)) "the tree is version-stamped")
    (is (= before (set (keys @frame/frames)))
        "the test-owned frame is torn down after the render — no residue")))

(deftest plan-bearing-root-tolerates-a-wrapper-head
  (reg-greeting!)
  (let [before (set (keys @frame/frames))
        tree   (uit/render
                [:div.shell
                 [ui/frame-root {:id :test/greet
                                 :initial-events [[:rf/set-db {:greeting "wrapped"}]]}
                  [greeting {}]]])]
    (is (= :div (:tag tree)) "the top-region element wrapper IS the tree root")
    (is (= "wrapped" (uit/text (uit/find tree :h1)))
        "a view nested under element + frame-root still resolves its ambient frame")
    (is (= before (set (keys @frame/frames))) "no frame residue")))

(deftest innermost-frame-root-is-the-ambient-scope
  (reg-greeting!)
  (let [before (set (keys @frame/frames))
        tree   (uit/render
                [ui/frame-root {:id :test/outer
                                :initial-events [[:rf/set-db {:greeting "outer"}]]}
                 [:div
                  [ui/frame-root {:id :test/inner
                                  :initial-events [[:rf/set-db {:greeting "inner"}]]}
                   [greeting {}]]]])]
    (is (= "inner" (uit/text (uit/find tree :h1)))
        "the INNERMOST enclosing frame-root supplies the view's ambient frame
         (mirrors the client's nearest-ancestor React-context scope)")
    (is (= before (set (keys @frame/frames)))
        "both preflighted frames are torn down — no residue")))

(deftest plan-config-expression-evaluates-once-at-preflight
  (reg-greeting!)
  (let [calls (atom 0)
        tree  (uit/render
               [ui/frame-root {:id :test/greet
                               :initial-events [[:rf/set-db
                                                 (do (swap! calls inc)
                                                     {:greeting "once"})]]}
                [greeting {}]])]
    (is (= "once" (uit/text (uit/find tree :h1))))
    (is (= 1 @calls)
        "the plan's config expression evaluates exactly once, at preflight —
         never during speculative tree traversal")))

(deftest plan-bearing-root-rejects-explicit-frame-opts
  (reg-greeting!)
  (let [f (rf/make-frame {:initial-events [[:rf/set-db {:greeting "held"}]]})]
    (is (= :rf.error/ui-test-bad-opts
           (err-id #(uit/render [ui/frame-root {:id :test/greet} [greeting {}]]
                                {:frame f})))
        "a plan-bearing root form OWNS its frames — {:frame} is rejected")
    (is (= {:greeting "held"} (rf/app-db-value f))
        "the rejection is pure — the held frame is untouched")))

(deftest plan-free-form-still-combines-with-frame-opts
  (reg-greeting!)
  (let [f    (rf/make-frame {:initial-events [[:rf/set-db {:greeting "explicit"}]]})
        tree (uit/render [greeting {}] {:frame f})]
    (is (= "explicit" (uit/text (uit/find tree :h1)))
        "a plan-free literal root form still targets an explicit :frame")))

(deftest plan-bearing-root-composes-with-sub-overrides
  (reg-greeting!)
  (let [tree (uit/render
              [ui/frame-root {:id :test/greet
                              :initial-events [[:rf/set-db {:greeting "real"}]]}
               [greeting {}]]
              {:sub-overrides {[:greeting/text] "pinned"}})]
    (is (= "pinned" (uit/text (uit/find tree :h1)))
        "the explicit override door intercepts the read on a plan-bearing form")))

(deftest preflight-failure-leaves-no-residue-and-preserves-the-typed-error
  (reg-greeting!)
  (let [before (set (keys @frame/frames))
        id     (err-id #(uit/render
                         [ui/frame-root {:id :test/bad
                                         :initial-events [:not-a-vector]}
                          [greeting {}]]))]
    (is (some? id) "the malformed plan fails loud with a typed error")
    (is (nil? (frame/frame :test/bad)) "the failing plan leaves no frame")
    (is (= before (set (keys @frame/frames)))
        "a preflight failure leaves no test-frame residue")))

(deftest two-plan-bearing-renders-are-separate-scopes
  (reg-greeting!)
  (let [before (set (keys @frame/frames))
        t1 (uit/render [ui/frame-root {:id :test/scope-a
                                       :initial-events [[:rf/set-db {:greeting "alpha"}]]}
                        [greeting {}]])
        t2 (uit/render [ui/frame-root {:id :test/scope-b
                                       :initial-events [[:rf/set-db {:greeting "beta"}]]}
                        [greeting {}]])]
    (is (= "alpha" (uit/text (uit/find t1 :h1))))
    (is (= "beta" (uit/text (uit/find t2 :h1)))
        "each render preflights its own frames — no state leaks between calls")
    (is (= before (set (keys @frame/frames)))
        "both renders' test-owned frames are torn down; no residue accumulates")))

(deftest plan-bearing-conditional-frame-root-is-a-compile-error
  (is (= :rf.ui.compile/frame-root-misplaced
         (expand-error
          '(re-frame.ui.test/render
            [:div (when true [re-frame.ui/frame-root {:id :x}])] nil)))
      "a frame-root under a control form is the analyzer's misplacement error
       — the literal form scans identically to ui/mount"))

;; ---------------------------------------------------------------------------
;; Fresh-frame contract (rf2-vxgfnd.55) — a plan-bearing render GUARANTEES a
;; fresh ISOLATED frame per declared id. A plan declaring an ALREADY-LIVE id
;; would, via the production ENSURE adopt path (03 §8 create-if-absent),
;; silently reuse ambient state and ignore its own :initial-events/config —
;; a test-isolation violation. The render REJECTS it with a typed error
;; BEFORE any frame/install mutation; production adoption semantics (the
;; shared-frame case in preflight_frame_wiring) are deliberately unchanged.
;; ---------------------------------------------------------------------------

(deftest plan-bearing-render-rejects-a-pre-existing-frame-id
  (reg-greeting!)
  ;; a frame is made LIVE outside the render (a boot rf/make-frame), seeded
  ;; with ambient state the plan must NOT be allowed to silently adopt.
  (let [live   (rf/make-frame {:id :test/greet
                               :initial-events [[:rf/set-db {:greeting "existing"}]]})
        before (set (keys @frame/frames))
        ;; a plan-bearing render declares the SAME id with a DIFFERENT seed.
        id (err-id #(uit/render
                     [ui/frame-root {:id :test/greet
                                     :initial-events [[:rf/set-db {:greeting "planned"}]]}
                      [greeting {}]]))]
    (is (= :rf.error/ui-test-frame-collision id)
        "a plan frame-id already live is a test-isolation violation — the
         render rejects rather than silently ADOPTING the ambient frame
         (the former false positive: the view read \"existing\", the plan's
         :initial-events ignored, and the test still passed)")
    (is (some? (frame/frame :test/greet))
        "the pre-existing frame is still live — the reject is pure")
    (is (= {:greeting "existing"} (rf/app-db-value live))
        "the pre-existing frame's app-db is UNTOUCHED after the failed render —
         the plan's seed never ran (it would read \"planned\" had it adopted)")
    (is (= before (set (keys @frame/frames)))
        "no frame created or destroyed — zero writes on the isolation failure")))

(deftest plan-bearing-collision-diagnostic-names-the-frame-and-root
  (reg-greeting!)
  (rf/make-frame {:id :test/greet
                  :initial-events [[:rf/set-db {:greeting "existing"}]]})
  (let [ex (try (uit/render
                 [ui/frame-root {:id :test/greet
                                 :initial-events [[:rf/set-db {:greeting "planned"}]]}
                  [greeting {}]])
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "the collision throws a typed ex-info")
    (is (= :rf.error/ui-test-frame-collision (:rf.error/id (ex-data ex))))
    (is (= [:test/greet] (:collisions (ex-data ex)))
        "the diagnostic names the colliding frame-id(s)")
    (is (= ::greeting (:root-id (ex-data ex)))
        "…and the arriving root (root identity is the mounted view's id)")))

(deftest plan-bearing-collision-in-a-later-plan-installs-nothing
  (reg-greeting!)
  ;; the INNER frame-root's id is already live; the OUTER is fresh. The
  ;; all-plans preflight must reject before ANY install (atomicity).
  (let [inner  (rf/make-frame {:id :test/inner
                               :initial-events [[:rf/set-db {:greeting "existing"}]]})
        before (set (keys @frame/frames))
        id (err-id #(uit/render
                     [ui/frame-root {:id :test/outer
                                     :initial-events [[:rf/set-db {:greeting "outer"}]]}
                      [:div
                       [ui/frame-root {:id :test/inner
                                       :initial-events [[:rf/set-db {:greeting "planned"}]]}
                        [greeting {}]]]]))]
    (is (= :rf.error/ui-test-frame-collision id)
        "a collision in a LATER plan rejects the whole render")
    (is (nil? (frame/frame :test/outer))
        "the EARLIER fresh plan was NOT installed — the all-plans preflight is
         atomic (a later collision cannot leave an earlier plan installed)")
    (is (= {:greeting "existing"} (rf/app-db-value inner))
        "the pre-existing inner frame is untouched")
    (is (= before (set (keys @frame/frames)))
        "zero writes — no fresh frame leaked from a partially-applied render")))

(deftest destroying-the-pre-existing-frame-restores-fresh-isolation
  (reg-greeting!)
  (rf/make-frame {:id :test/greet
                  :initial-events [[:rf/set-db {:greeting "existing"}]]})
  ;; the reject's advertised recovery: destroy the pre-existing frame, then
  ;; the SAME plan-bearing render mints + SEEDS a fresh frame as intended.
  (frame/destroy-frame! :test/greet)
  (let [before (set (keys @frame/frames))
        tree   (uit/render
                [ui/frame-root {:id :test/greet
                                :initial-events [[:rf/set-db {:greeting "planned"}]]}
                 [greeting {}]])]
    (is (= "planned" (uit/text (uit/find tree :h1)))
        "with the pre-existing frame gone, the fresh frame is seeded from the
         plan's :initial-events (not ambient state) — the isolation the
         harness advertises")
    (is (= before (set (keys @frame/frames)))
        "the fresh test-owned frame is torn down after the render — no residue")))

;; ---------------------------------------------------------------------------
;; Atomic + incarnation-owned acquisition (rf2-vxgfnd.64) — the residual gap
;; rf2-vxgfnd.55 (#5732) left open. #5732 snapshotted live ids BEFORE the plan
;; ran and tore frames down by BARE id: a TOCTOU where a concurrently-created
;; frame could be adopted (and then destroyed) by a losing render, and where
;; teardown could destroy a LATER same-id incarnation it never owned. The fix
;; makes plan-frame acquisition an ATOMIC all-or-nothing claim and teardown
;; INCARNATION-owned. Deterministic barrier fixtures, no sleeps.
;; ---------------------------------------------------------------------------

(deftest plan-frame-claim-is-atomic-one-of-two-racers-wins
  ;; The claim linearizes: two acquisitions for the SAME id cannot both win.
  ;; A rendezvous barrier releases both racers together, then each attempts the
  ;; atomic claim; exactly one reserves the id (nil), the other sees it already
  ;; claimed. (White-box on the private claim primitive — the atomic core of
  ;; the acquisition protocol.)
  (let [claim!   #'re-frame.ui.test/claim-plan-frames!
        release! #'re-frame.ui.test/release-plan-frames!
        gate     (CyclicBarrier. 2)
        attempt  (fn [] (future
                          (.await gate 10 TimeUnit/SECONDS)
                          (claim! [:test/claim-race])))
        r1       (attempt)
        r2       (attempt)
        outcomes [@r1 @r2]]
    (try
      (is (= 1 (count (filter nil? outcomes)))
          "exactly ONE racer wins the claim (nil) — the CAS linearizes them")
      (is (= 1 (count (filter #{[:test/claim-race]} outcomes)))
          "the OTHER racer loses — it sees the id already claimed, no double claim")
      (finally
        (release! [:test/claim-race])))))

(deftest plan-bearing-render-rejects-a-concurrently-created-frame
  ;; Absent-at-snapshot / concurrent-create: the collision check reads LIVE
  ;; state at the ATOMIC CLAIM point, not a snapshot frozen when the render
  ;; began. A frame a concurrent actor creates AFTER the render evaluated its
  ;; plans (id absent then) but BEFORE the claim is seen — the render REJECTS
  ;; (never adopts the ambient frame) and destroys nothing it did not create.
  (reg-greeting!)
  (let [at-plans (CountDownLatch. 1)   ; render evaluated its plans (pre-claim)
        created  (CountDownLatch. 1)   ; actor created the id in the window
        before   (set (keys @frame/frames))
        ;; the plan's config EXPRESSION evaluates at preflight, before the
        ;; claim; it closes over these latches to open a deterministic
        ;; pre-claim window (the render blocks here until the actor creates).
        render   (future
                   (err-id
                    #(uit/render
                      [ui/frame-root {:id :test/appear
                                      :initial-events [[:rf/set-db
                                                        (do (.countDown at-plans)
                                                            (.await created 10 TimeUnit/SECONDS)
                                                            {:greeting "planned"})]]}
                       [greeting {}]])))]
    (.await at-plans 10 TimeUnit/SECONDS)
    (is (nil? (frame/frame :test/appear))
        "the id was ABSENT when the render evaluated its plans")
    ;; a concurrent actor creates :test/appear with its OWN seed, in the window
    ;; between the render's plan evaluation and its atomic claim.
    (let [actor (rf/make-frame {:id :test/appear
                                :initial-events [[:rf/set-db {:greeting "actor"}]]})]
      (.countDown created)
      (let [result @render]
        (is (= :rf.error/ui-test-frame-collision result)
            "the render rejects the concurrently-created frame at its claim — it
             does NOT adopt ambient state (the stale-snapshot false pass)")
        (is (some? (frame/frame :test/appear))
            "the actor's frame is untouched — the losing render destroys nothing")
        (is (= {:greeting "actor"} (rf/app-db-value actor))
            "…and its app-db is the actor's seed, never the render's plan (an
             adopt would have rendered/kept ambient state)")
        (frame/destroy-frame! :test/appear)
        (is (= before (set (keys @frame/frames)))
            "zero residue once the actor's own frame is cleaned up")))))

(deftest plan-bearing-teardown-is-incarnation-owned
  ;; Deterministically open the OLD check-to-act window: the render has decided
  ;; to destroy its frame and entered `destroy-frame!`, but the actual destroy is
  ;; held while another actor replaces the id. The render must carry its pinned
  ;; token INTO core so the resumed destroy cannot tear down the replacement.
  (let [teardown-entered (CountDownLatch. 1)
        swapped          (CountDownLatch. 1)
        render-thread    (atom nil)
        original-destroy frame/destroy-frame!
        before           (set (keys @frame/frames))
        gate-render-destroy
        (fn [destroy]
          (if (identical? @render-thread (Thread/currentThread))
            (do
              (.countDown teardown-entered)
              (.await swapped 10 TimeUnit/SECONDS)
              (destroy))
            (destroy)))]
    (with-redefs [frame/destroy-frame!
                  (fn
                    ([target]
                     (gate-render-destroy #(original-destroy target)))
                    ([target expected-token]
                     (gate-render-destroy
                       #(original-destroy target expected-token))))]
      (let [render (future
                     (reset! render-thread (Thread/currentThread))
                     (uit/render [ui/frame-root {:id :test/incarn
                                                 :initial-events [[:rf/set-db {:n 1}]]}
                                  [gated {}]]))]
        (is (.await teardown-entered 10 TimeUnit/SECONDS)
            "the render reached its teardown decision barrier")
        (let [token-a (frame/frame-incarnation-token :test/incarn)]
          (is (some? token-a) "the render installed its own incarnation")
          (original-destroy :test/incarn)
          (rf/make-frame {:id :test/incarn
                          :initial-events [[:rf/set-db {:n 2}]]})
          (let [token-b (frame/frame-incarnation-token :test/incarn)]
            (is (and (some? token-b) (not (identical? token-a token-b)))
                "the actor installed a distinct replacement incarnation")
            (.countDown swapped)
            (is (map? @render) "the held render completes")
            (is (identical? token-b (frame/frame-incarnation-token :test/incarn))
                "the replacement survives the stale render teardown unchanged")
            (original-destroy :test/incarn)
            (is (= before (set (keys @frame/frames)))
                "no residue after the actor cleans up its replacement")))))))

;; ---------------------------------------------------------------------------
;; Registry-rested EXCLUSIVE install (rf2-vxgfnd.76). The claim linearizes
;; render-vs-render; correctness against a RAW actor (an ordinary core
;; `make-frame`, which knows nothing of the private claim atom) rests on core's
;; guarded-CAS `upsert-frame!` via `:rf.frame/must-create?`, plus core's
;; fail-fast per-id construction reservation. The
;; `frame/*upsert-decide-probe*` seam opens the claim→install window
;; deterministically (no sleeps) — a raw actor acts INSIDE the plan install's
;; decide→CAS window.
;; ---------------------------------------------------------------------------

(deftest plan-bearing-render-rejects-same-id-reentry-in-the-claim-install-window
  ;; The claim proved :test/mc absent, then a raw actor attempts construction in
  ;; the plan's decide probe. The plan already owns the core per-id transaction,
  ;; so nested same-id construction fails promptly and the plan rolls back. The
  ;; actor never installs a competing frame for EXCLUSIVE mode to collide with.
  (reg-greeting!)
  (let [before (set (keys @frame/frames))
        acted  (volatile! false)]
    (binding [frame/*upsert-decide-probe*
              (fn [id]
                ;; Simulate the raw actor attempting :test/mc INSIDE the plan's
                ;; decide→install window (once, with its own proposed seed).
                (when (and (= id :test/mc) (not @acted))
                  (vreset! acted true)
                  (rf/make-frame {:id :test/mc
                                  :initial-events [[:rf/set-db {:greeting "actor"}]]})))]
      (let [result (err-id
                    #(uit/render [ui/frame-root {:id :test/mc
                                                 :initial-events [[:rf/set-db {:greeting "planned"}]]}
                                  [greeting {}]]))]
        (is (= :rf.error/frame-construction-in-progress result)
            "same-id re-entry fails fast at the construction reservation")
        (is (true? @acted) "the deterministic decide-window probe ran")
        (is (nil? (frame/frame :test/mc))
            "the actor cannot publish and the owning plan rolls back")
        (is (= before (set (keys @frame/frames)))
            "neither attempted construction leaves registry residue")))))

(deftest plan-bearing-partial-failure-teardown-is-incarnation-owned
  ;; Multi-plan render: the OUTER plan (:pf/outer) installs; a concurrent actor
  ;; destroys + RE-creates :pf/outer; then the INNER plan (:pf/inner) THROWS
  ;; during its :initial-events. The partial-failure teardown destroys only the
  ;; EXACT incarnations THIS render installed (pinned per-install), so the
  ;; actor's :pf/outer replacement is left ALIVE and no bare id is destroyed.
  ;; (AC3 residual: the outer plan's :initial-events already drained irreversibly
  ;; before the inner throw — this is the executor's partial-failure posture.)
  (reg-greeting!)
  (rf/reg-event :pf/boom (fn [_ _] (throw (ex-info "boom" {}))))
  (let [before      (set (keys @frame/frames))
        acted       (volatile! false)
        actor-token (volatile! nil)]
    (binding [frame/*upsert-decide-probe*
              (fn [id]
                ;; When the INNER plan is about to install, the OUTER plan is
                ;; already installed + pinned. Simulate the actor: destroy the
                ;; render's :pf/outer incarnation and RE-create a fresh one.
                (when (and (= id :pf/inner) (not @acted))
                  (vreset! acted true)
                  (frame/destroy-frame! :pf/outer)
                  (rf/make-frame {:id :pf/outer})
                  (vreset! actor-token (frame/frame-incarnation-token :pf/outer))))]
      (let [threw (try (uit/render
                        [ui/frame-root {:id :pf/outer}
                         [ui/frame-root {:id :pf/inner :initial-events [[:pf/boom]]}
                          [greeting {}]]])
                       nil
                       (catch Throwable _ ::threw))]
        (is (= ::threw threw) "the render threw — the inner plan's :initial-events failed")
        (is (some? (frame/frame :pf/outer))
            "the ACTOR's :pf/outer replacement SURVIVES — teardown destroyed only
             the render's OWN pinned incarnation, never a bare id")
        (is (identical? @actor-token (frame/frame-incarnation-token :pf/outer))
            "…and it is the ACTOR's EXACT incarnation, unchanged")
        (frame/destroy-frame! :pf/outer)
        (is (= before (set (keys @frame/frames)))
            "no residue: the render's frames are gone, the actor's is cleaned")))))
