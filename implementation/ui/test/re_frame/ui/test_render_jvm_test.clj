(ns re-frame.ui.test-render-jvm-test
  "`ui.test/render` — the ONE accepted input form (a literal view form, props
  carried IN the form), the ONE option (`:sub-overrides`), the rejection rules
  (root-identity-and-mount §9), the tree-version stamp, and the ambient-frame
  headless sub read (03 §3). Frame scope is the caller's ordinary bracket
  (`rf/with-frame` / `rf/with-new-frame`) — render owns no frame lifecycle."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.test :as uit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

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

(defn- strip-view-evidence
  "Recursively drop the DEV host-root view-evidence annotation
  (:data-rf2-source-coord / :data-rf-view) the compiler stamps on each view's
  compiler-owned root element (rf2-hac8p). This golden asserts the boundary-node
  tree shape, not the host-root annotation (own coverage: the emit-annotation
  tests, the parity corpus, test:elision)."
  [node]
  (if (map? node)
    (let [node (if-let [a (:attrs node)]
                 (let [a (dissoc a :data-rf2-source-coord :data-rf-view)]
                   (if (seq a) (assoc node :attrs a) (dissoc node :attrs)))
                 node)]
      (cond-> node
        (:children node) (update :children #(mapv strip-view-evidence %))))
    node))

(defn- first-node
  "The first document-order node of `tree` matching `pred` — ordinary Clojure
  over the structural tree (ui.test ships no selector helper; the traversal
  idiom is `(tree-seq map? :children tree)` + a predicate)."
  [tree pred]
  (some #(when (pred %) %) (tree-seq map? :children tree)))

;; ---------------------------------------------------------------------------
;; The one input form — a literal view form (props carried IN the form)
;; ---------------------------------------------------------------------------

(deftest literal-view-form-renders-the-boundary-node
  (let [tree (strip-view-evidence (uit/render [badge {:label "hi"}]))]
    (is (= {:view-id ::badge
            :props {:label "hi"}
            :children [{:tag :span :attrs {:class "badge"} :children ["hi"]}]
            :rf.ui/tree-version 1}
           tree)
        "the root is the view's boundary node, stamped with the version"))
  (is (= 1 (:rf.ui/tree-version (uit/render [badge {:label "x"}]))))
  (is (= ::badge (:view-id (uit/render [badge {}])))
      "a props-less view renders against {}"))

(deftest literal-view-form-nests
  (let [tree (uit/render [card {:title "T" :n 3}])]
    (is (= ::card (:view-id tree)) "one mounted view per root form")
    (is (= "T" (-> tree (first-node #(= ::badge (:view-id %))) uit/text))
        "internal views expand — boundaries nest")
    (is (= "count=3" (-> tree (first-node #(= :p (:tag %))) uit/text))
        "numeric children canonicalize via the tree builders; props expressions
         evaluate in the caller's lexical scope")))

;; ---------------------------------------------------------------------------
;; Accepted-forms rejections (compile time) — one grammar: a literal view form
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
      "a bare symbol is not a literal view form vector")
  (is (= :rf.ui.compile/bad-test-render-form
         (expand-error '(re-frame.ui.test/render nope-not-a-thing nil)))
      "an unresolvable symbol is not a literal view form vector either")
  (is (= :rf.ui.compile/bad-test-render-form
         (expand-error '(re-frame.ui.test/render 42 nil)))
      "the one accepted form is a literal view vector")
  (is (= :rf.ui.compile/bad-test-render-form
         (expand-error '(re-frame.ui.test/render (vector :div "x") nil)))
      "a runtime-assembled vector is the same compile error as at mount"))

(deftest rejected-local-bindings
  (is (= :rf.ui.compile/bad-test-render-form
         (eval-error '(let [v 1] (re-frame.ui.test/render v nil))))
      "a local binding cannot be a view form — hiccup is compiled")
  (is (= :rf.ui.compile/dynamic-head
         (eval-error '(let [h 1] (re-frame.ui.test/render [h {}] nil))))
      "a local head inside a literal view form is a dynamic head"))

(deftest plan-bearing-form-is-not-a-render-form
  ;; render owns no frame lifecycle: a top-region frame-root (a plan-bearing
  ;; form) is rejected at expansion — establish the frame with rf/with-new-frame
  ;; and render the plain view, or mount under ui.test/with-root.
  (is (= :rf.ui.compile/bad-test-root
         (expand-error
          '(re-frame.ui.test/render
            [re-frame.ui/frame-root
             {:id :test/greet
              :initial-events [[:rf/set-db {:greeting "planned"}]]}
             [re-frame.ui.test-render-jvm-test/greeting {}]]
            nil)))
      "a plan-bearing root form is not a render form (render owns no frames)"))

;; ---------------------------------------------------------------------------
;; Opts validation (runtime; the closed opts map — :sub-overrides only)
;; ---------------------------------------------------------------------------

(deftest opts-validation
  (is (= :rf.error/ui-test-bad-opts
         (err-id #(uit/render [badge {:label "x"}] {:unknown 1})))
      "the opts map is CLOSED — :sub-overrides is the only option")
  (is (= :rf.error/ui-test-bad-opts
         (err-id #(uit/render [badge {:label "x"}] {:props {:label "y"}})))
      ":props is not an option — a literal form carries its props IN the form")
  (is (= :rf.error/ui-test-bad-opts
         (err-id #(uit/render [badge {:label "x"}] "not-a-map")))
      "opts must be a map")
  (is (= :rf.error/ui-test-bad-opts
         (err-id #(uit/render [badge {:label "x"}] {:sub-overrides {:not-a-vector 1}})))
      ":sub-overrides keys are query vectors"))

(deftest sub-overrides-accepted-and-carried
  (is (= 1 (:rf.ui/tree-version
            (uit/render [badge {:label "x"}]
                        {:sub-overrides {[:cart/locked?] true}})))
      "the override door validates + binds; a sub-free view is inert"))

;; ---------------------------------------------------------------------------
;; S2b — the Tier-1 headless sub read (03 §3): a real read against the ambient
;; frame, and the explicit override door intercepting it. Frame scope is the
;; caller's bracket: rf/with-new-frame binds the fresh frame ambient (and
;; destroys it on exit), rf/with-frame pins a held one.
;; ---------------------------------------------------------------------------

(deftest sub-read-against-the-ambient-frame
  (rf/reg-sub :greeting/text (fn [db _] (:greeting db)))
  (rf/with-new-frame [_f (rf/make-frame
                          {:initial-events [[:rf/set-db {:greeting "hello"}]]})]
    (is (= "hello" (uit/text (first-node (uit/render [greeting {}])
                                         #(= :h1 (:tag %)))))
        "the compiled view's (sub …) reads the real cache value on the JVM"))
  (testing "app-db movement is reflected on re-render (headless read points)"
    (rf/reg-event ::set-greeting
      (fn [{:keys [db]} [_ v]] {:db (assoc db :greeting v)}))
    (rf/with-new-frame [f (rf/make-frame
                           {:initial-events [[:rf/set-db {:greeting "hi"}]]})]
      (rf/dispatch-sync [::set-greeting "bye"] {:frame f})
      (is (= "bye" (uit/text (first-node (uit/render [greeting {}])
                                         #(= :h1 (:tag %)))))
          "a re-render reads the moved value"))))

(deftest sub-read-honours-the-override-door
  (rf/reg-sub :greeting/text (fn [db _] (:greeting db)))
  (rf/with-new-frame [_f (rf/make-frame
                          {:initial-events [[:rf/set-db {:greeting "real"}]]})]
    (let [tree (uit/render [greeting {}]
                           {:sub-overrides {[:greeting/text] "pinned"}})]
      (is (= "pinned" (uit/text (first-node tree #(= :h1 (:tag %)))))
          "the explicit override door intercepts the read (Story-override door,
           JVM spelling — 03 §3)"))))

(deftest sub-read-unknown-sub-is-fail-loud
  (rf/with-new-frame [_f (rf/make-frame {:initial-events [[:rf/set-db {}]]})]
    (is (= :rf.error/no-such-sub
           (err-id #(uit/render [greeting {}])))
        "an unregistered entry sub is fail-loud through the observation port")))

;; ---------------------------------------------------------------------------
;; Frame scope is the caller's bracket — there is no :frame option
;; ---------------------------------------------------------------------------

(deftest with-frame-pins-a-held-frame-that-survives-the-render
  (rf/reg-sub :greeting/text (fn [db _] (:greeting db)))
  (let [f (rf/make-frame {:initial-events [[:rf/set-db {:greeting "held"}]]})]
    (try
      (is (= "held"
             (rf/with-frame f
               (uit/text (first-node (uit/render [greeting {}])
                                     #(= :h1 (:tag %))))))
          "rf/with-frame pins the held frame as the render's ambient scope")
      (is (= {:greeting "held"} (rf/app-db-value f))
          "the held frame survives the render — with-frame is caller-owned,
           it binds but never destroys")
      (finally (rf/destroy-frame! f)))))

(deftest frameless-render-proceeds
  (is (= ::badge (:view-id (uit/render [badge {:label "x"}])))
      "with no ambient frame, structural rendering proceeds (badge reads no sub)"))
