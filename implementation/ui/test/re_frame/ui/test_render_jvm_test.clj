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
      "the override door validates + binds; consumed by the S2 read slice"))

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
