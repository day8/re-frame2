(ns re-frame.ui.test-render-jvm-test
  "`ui.test/render` — the two accepted root-or-view forms + rejection
  rules (root-identity-and-mount §9), the frame opts (:frame XOR :app-db,
  :props, :sub-overrides), the tree-version stamp, and the JVM-only
  selector spellings (defview var; the compiled-view-fn guard)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.test :as uit]))

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
;; Form 2 — a literal root form (the same grammar mount takes)
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
         (err-id #(uit/render badge {:frame :rf/default :app-db {}})))
      "{:frame f} XOR {:app-db v}")
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
  (let [f    (uit/frame {:app-db {:greeting "hello"}})
        tree (uit/render greeting {:frame f})]
    (is (= "hello" (uit/text (uit/find tree :h1)))
        "the compiled view's (sub …) reads the real cache value on the JVM"))
  (testing "app-db movement is reflected on re-render (headless read points)"
    (rf/reg-event ::set-greeting
      (fn [{:keys [db]} [_ v]] {:db (assoc db :greeting v)}))
    (let [f (uit/frame {:app-db {:greeting "hi"}})]
      (uit/dispatch! f [::set-greeting "bye"])
      (is (= "bye" (uit/text (uit/find (uit/render greeting {:frame f}) :h1)))
          "a re-render reads the moved value"))))

(deftest sub-read-honours-the-override-door
  (rf/reg-sub :greeting/text (fn [db _] (:greeting db)))
  (let [f    (uit/frame {:app-db {:greeting "real"}})
        tree (uit/render greeting {:frame f
                                   :sub-overrides {[:greeting/text] "pinned"}})]
    (is (= "pinned" (uit/text (uit/find tree :h1)))
        "the explicit override door intercepts the read (Story-override door,
         JVM spelling — 03 §3)")))

(deftest sub-read-unknown-sub-is-fail-loud
  (let [f (uit/frame {:app-db {}})]
    (is (= :rf.error/no-such-sub
           (err-id #(uit/render greeting {:frame f})))
        "an unregistered entry sub is fail-loud through the observation port")))

;; ---------------------------------------------------------------------------
;; Frame opts — :app-db mints (and destroys) a test frame; :frame targets
;; a held one
;; ---------------------------------------------------------------------------

(deftest app-db-mints-a-transient-test-frame
  (let [before (set (keys @frame/frames))
        tree   (uit/render badge {:props {:label "x"} :app-db {:seed 1}})]
    (is (= 1 (:rf.ui/tree-version tree)))
    (is (= before (set (keys @frame/frames)))
        "the minted frame is destroyed after the render — no leak")))

(deftest frame-opt-targets-a-held-frame
  (let [f    (uit/frame {:app-db {:cart #{7}}})
        tree (uit/render [badge {:label "held"}] {:frame f})]
    (is (= "held" (uit/text (uit/find tree :span))))
    (is (= {:cart #{7}} (rf/app-db-value f))
        "the held frame survives the render (caller-owned lifecycle)")))

(deftest frameless-render-proceeds
  (is (= ::badge (:view-id (uit/render badge {:props {:label "x"}})))
      "with neither :frame nor :app-db, structural rendering proceeds"))

;; ---------------------------------------------------------------------------
;; frame + dispatch! (07 §2)
;; ---------------------------------------------------------------------------

(deftest frame-mints-with-app-db-seed
  (let [f (uit/frame {:app-db {:cart #{} :catalog {42 "Hat"}}})]
    (is (= {:cart #{} :catalog {42 "Hat"}} (rf/app-db-value f))
        "the :app-db seed drains to fixed point before frame returns"))
  (is (map? (rf/app-db-value (uit/frame)))
      "a seed-less test frame is a fresh frame"))

(deftest frame-opts-are-closed
  (is (= :rf.error/ui-test-bad-opts (err-id #(uit/frame {:images []})))
      "richer construction is rf/make-frame's vocabulary")
  (is (= :rf.error/ui-test-bad-opts (err-id #(uit/frame {:app-db [:not-a-map]}))))
  (is (= :rf.error/ui-test-bad-opts (err-id #(uit/frame :not-a-map)))))

(deftest dispatch!-is-real-dispatch-plus-drain
  (rf/reg-event ::add
    (fn [{:keys [db]} [_ id]] {:db (update db :cart conj id)}))
  (let [f (uit/frame {:app-db {:cart #{}}})]
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
;; frame-root's frame as ambient scope, {:frame}/{:app-db} is rejected, and
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
  (let [f (uit/frame {:app-db {:greeting "held"}})]
    (is (= :rf.error/ui-test-bad-opts
           (err-id #(uit/render [ui/frame-root {:id :test/greet} [greeting {}]]
                                {:frame f})))
        "a plan-bearing root form OWNS its frames — {:frame} is rejected")
    (is (= :rf.error/ui-test-bad-opts
           (err-id #(uit/render [ui/frame-root {:id :test/greet} [greeting {}]]
                                {:app-db {:greeting "x"}})))
        "...and {:app-db} likewise")
    (is (= {:greeting "held"} (rf/app-db-value f))
        "the rejection is pure — the held frame is untouched")))

(deftest plan-free-form-still-combines-with-frame-opts
  (reg-greeting!)
  (let [f    (uit/frame {:app-db {:greeting "explicit"}})
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
