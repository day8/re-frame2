(ns re-frame.ui.compiler-macro-resolution-jvm-test
  "Real CLJS-analyzer resolution proofs for the expression macro barrier."
  (:require [cljs.analyzer :as cljs-analyzer]
            [cljs.analyzer.api :as cljs-api]
            [cljs.core]
            [cljs.env :as cljs-env]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ui.compiler.analyze :as analyze]
            [re-frame.ui.compiler.env :as env]
            [re-frame.ui.compiler.root :as root]))

(defmacro user-binder
  [[binding init] then else]
  `(let [~binding ~init] (if ~binding ~then ~else)))

(defn- compile-error-id [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (:rf.ui.compile/error (ex-data e)))))

(defn- with-real-cljs-env [f]
  (binding [cljs-env/*compiler* (cljs-env/default-compiler-env)]
    ;; A real CLJS compile interns core/user macros into analyzer state. Build
    ;; that exact authority rather than injecting a synthetic :macro flag.
    (cljs-analyzer/intern-macros 'cljs.core)
    (cljs-analyzer/intern-macros
     're-frame.ui.compiler-macro-resolution-jvm-test)
    (let [cljs-e (cljs-api/empty-env)]
      (cljs-api/analyze cljs-e '(def ordinary-call (fn [x] x)))
      (let [base (env/make-env {:host :cljs :cljs-env cljs-e
                                :ns-sym 'cljs.user})
            resolve* (fn [sym]
                       (case sym
                         sub   {:fqn 're-frame.ui/sub :meta {}}
                         lease {:fqn 're-frame.ui/lease :meta {}}
                         (env/resolve-sym base sym)))
            e (env/make-env {:host :cljs :cljs-env cljs-e
                             :ns-sym 'cljs.user
                             :self 'probe :self-id :cljs.user/probe
                             :resolver resolve*})]
        (f base e)))))

(deftest real-cljs-analyzer-macro-authority-is-preserved
  (with-real-cljs-env
    (fn [base _]
      (testing "core and user binder macros carry the analyzer's top-level flag"
        (is (true? (get-in (env/resolve-sym base 'if-let) [:meta :macro])))
        (is (true?
             (get-in
              (env/resolve-sym
               base
               're-frame.ui.compiler-macro-resolution-jvm-test/user-binder)
              [:meta :macro]))))
      (testing "a real analyzed ordinary function is not over-classified"
        (is (not (true? (get-in (env/resolve-sym base 'ordinary-call)
                                [:meta :macro]))))))))

(deftest real-cljs-binder-macros-fail-while-transparent-expressions-lower
  (with-real-cljs-env
    (fn [_ e]
      (doseq [form
              ['[:div {:title (if-let [x maybe] (sub [:q x]) nil)}]
               '[:div {:title
                       (re-frame.ui.compiler-macro-resolution-jvm-test/user-binder
                        [x maybe] (sub [:q x]) nil)}]
               '[:div {:title (-> [:q] sub)}]]]
        (is (= :rf.ui.compile/unsupported-form
               (compile-error-id #(analyze/analyze e form)))
            (pr-str form)))
      (is (= :rf.ui.compile/unsupported-form
             (compile-error-id
              #(analyze/analyze
                e '[:div {:title
                           (re-frame.ui.compiler-macro-resolution-jvm-test/user-binder)}])))
          "an opaque zero-arg invocation cannot inject an invisible site")
      (is (= :rf.ui.compile/unsupported-form
             (compile-error-id
              #(analyze/reject-reactive-binding!
                e '[{:keys [x] :or {x (-> [:q] sub)}}])))
          "real CLJS macro resolution fences a manufactured default call")
      (doseq [form
              ['[:div {:title (ordinary-call (sub [:q]))}]
               '[:div {:title (or (sub [:q]) "")}]
               '[:div {:title (when (sub [:q]) "ready")}]
               '[:div {:title (cond (sub [:q]) "ready" :else "waiting")}]
               '[:div {:title (-> (sub [:q]) ordinary-call)}]]]
        (let [e*  (assoc e :sites (atom {:events [] :subs [] :leases [] :htmls []}))
              ast (analyze/analyze e* form)]
          (is (= 1 (count (:subs @(:sites e*)))) (pr-str form))
          (is (re-find #"re-frame.ui.reactive/sub-read" (pr-str ast))
              (pr-str form)))))))

(deftest real-cljs-destructuring-scope-follows-host-evaluation-order
  ;; rf2-vxgfnd.268 — the ordered-scope correction under REAL CLJS analyzer
  ;; resolution (bare sub/lease resolve to the reactive vars; core macro
  ;; authority is genuine, not an injected :macro flag). Destructuring binds
  ;; SEQUENTIALLY, so a bare reactive var in a default is shadowed ONLY by a
  ;; same-pattern local bound EARLIER. Reverting the ordered scope re-accepts
  ;; the escape rows (the dzyqis over-accept) and re-rejects the earlier-shadow
  ;; rows (the older whole-pattern-blind call scan's under-shadow).
  (with-real-cljs-env
    (fn [_ e]
      (testing "a self / later-bound shadow does not cover an earlier default"
        (doseq [argv ['[{:keys [sub] :or {sub sub}}]
                      '[{:keys [f sub] :or {f sub}}]
                      '[{:keys [lease] :or {lease lease}}]
                      '[{:keys [f lease] :or {f lease}}]]]
          (is (= :rf.ui.compile/unsupported-form
                 (compile-error-id #(analyze/reject-reactive-binding! e argv)))
              (pr-str argv))))
      (testing "an EARLIER-bound local genuinely shadows the reactive var"
        (doseq [argv ['[{:keys [sub f] :or {f sub}}]
                      '[{:keys [sub f] :or {f (sub :fallback)}}]
                      '[{sub :s x sub}]]]
          (is (nil? (compile-error-id #(analyze/reject-reactive-binding! e argv)))
              (pr-str argv)))))))

;; ---------------------------------------------------------------------------
;; rf2-vxgfnd.274 — self-head precedence + real CLJS authoring-head routing
;; ---------------------------------------------------------------------------
;;
;; The .266 pure-analyzer fixtures inject Q5 resolution, and its real-host proof
;; (defview_grammar_jvm_test) covers CLJ macroexpansion + fully-qualified
;; spellings only. Neither exercised production `cljs.analyzer.api/resolve` with
;; REFERRED / ALIASED spellings, nor the separate CLJS root-entry compiler. This
;; harness stands up a REAL CLJS analyzer namespace state where cljs.user refers
;; re-frame.ui/{sub,lease,frame} (and aliases the ns `ui`), so every spelling
;; resolves through the production resolver — NOT an injected stub — and drives
;; both the defview route (analyze) and the root-entry route (root/analyze-root).

(defn- with-referred-cljs-env
  "Populate a real CLJS compiler state where cljs.user REFERS re-frame.ui/sub,
  lease, frame (+ frame-root/frame-provider) and aliases the ns as `ui`, then
  call `(f aenv)` with a live analyzer env over that ns. Resolution runs through
  production `cljs.analyzer.api/resolve` — there is no injected `:resolver`."
  [f]
  (binding [cljs-env/*compiler* (cljs-env/default-compiler-env)]
    (swap! cljs-env/*compiler* update :cljs.analyzer/namespaces merge
           {'re-frame.ui  {:name 're-frame.ui
                           :defs {'sub            {:name 're-frame.ui/sub}
                                  'lease          {:name 're-frame.ui/lease}
                                  'frame          {:name 're-frame.ui/frame}
                                  'frame-root     {:name 're-frame.ui/frame-root}
                                  'frame-provider {:name 're-frame.ui/frame-provider}}}
            'cljs.user    {:name 'cljs.user
                           ;; `(:require [re-frame.ui :as ui :refer [...]])`
                           :requires {'ui 're-frame.ui 're-frame.ui 're-frame.ui}
                           :uses     {'sub 're-frame.ui 'lease 're-frame.ui
                                      'frame 're-frame.ui 'frame-root 're-frame.ui
                                      'frame-provider 're-frame.ui}
                           :defs {}}})
    (f (assoc (cljs-api/empty-env)
              :ns (get-in @cljs-env/*compiler*
                          [:cljs.analyzer/namespaces 'cljs.user])))))

(defn- referred-env
  "An analyzer env over the referred cljs.user; `self-sym` (or nil) is the view
  currently being compiled."
  [aenv self-sym]
  (cond-> (env/make-env {:host :cljs :cljs-env aenv :ns-sym 'cljs.user})
    self-sym (assoc :self self-sym
                    :self-id (keyword "cljs.user" (name self-sym))
                    :self-children? false :self-closed-keys nil)))

(deftest real-cljs-referred-verb-resolution-is-genuine
  ;; Sanity: production cljs.analyzer.api/resolve — not an injected resolver —
  ;; resolves every referred / aliased / fully-qualified spelling to the
  ;; re-frame.ui authoring vars. The rows below route through THIS resolution.
  (with-referred-cljs-env
    (fn [aenv]
      (let [base (referred-env aenv nil)]
        (doseq [[sym fqn] '{sub               re-frame.ui/sub
                            ui/sub            re-frame.ui/sub
                            re-frame.ui/sub   re-frame.ui/sub
                            lease             re-frame.ui/lease
                            ui/lease          re-frame.ui/lease
                            frame             re-frame.ui/frame
                            re-frame.ui/frame re-frame.ui/frame}]
          (is (= fqn (:fqn (env/resolve-sym base sym))) (str sym)))
        (is (nil? (env/resolve-sym base 'not-a-thing))
            "an unresolvable spelling is genuinely unresolved (no stub)")))))

(deftest real-cljs-self-head-outranks-reservation-defview-route
  ;; rf2-vxgfnd.274 defview route under REAL CLJS resolution. The REFERRED
  ;; spelling is the self-recursive case (a view named `sub` recursing on a bare
  ;; [sub …] head) — it classifies as an internal view. The ALIASED and
  ;; FULLY-QUALIFIED spellings in an UNRELATED view (no :self) stay reserved
  ;; typed rejects, never foreign components. A local shadow outranks self.
  (with-referred-cljs-env
    (fn [aenv]
      (testing "a referred self head classifies as an internal view"
        (doseq [verb '[sub lease frame]]
          (let [e   (referred-env aenv verb)
                ast (analyze/analyze e [verb {}])]
            (is (= :view (:op ast)) (str verb))
            (is (= (keyword "cljs.user" (name verb)) (:view-id ast)) (str verb)))))
      (testing "aliased / fully-qualified / referred verbs in an unrelated view stay reserved"
        (let [e (referred-env aenv nil)]
          (doseq [form '[[ui/sub {}] [re-frame.ui/lease {}] [frame]
                         [ui/frame {}] [re-frame.ui/sub {}] [lease {}]]]
            (is (= :rf.ui.compile/unsupported-form
                   (compile-error-id #(analyze/analyze e form)))
                (pr-str form)))))
      (testing "a local shadow of the self spelling is a dynamic head (tier 1 wins)"
        (let [e (referred-env aenv 'sub)]
          (is (= :rf.ui.compile/dynamic-head
                 (compile-error-id #(analyze/analyze e '(let [sub identity] [sub {}])))))))
      (testing "the reservation stays narrow — a direct (sub …) still indexes one site"
        (let [e (referred-env aenv 'panel)]
          (analyze/analyze e '[:div (sub [:q])])
          (is (= 1 (count (:subs @(:sites e))))
              "a direct compiler-owned (sub …) call still lowers to a manifest site"))))))

(deftest real-cljs-root-entry-reserves-reactive-verbs
  ;; rf2-vxgfnd.274 root-entry route. The mount/render!/hydrate-root compiler
  ;; (root/analyze-root) REUSES analyze/analyze, so the reservation holds at the
  ;; root too: a public authoring verb head at root can never become a :foreign
  ;; component. Bypassing the canonical-FQN reservation makes each [verb …] root
  ;; compile :foreign and fail these rows (the root-compilation mutation
  ;; fixture); a legal element root is untouched. (Roots carry no :self, so the
  ;; self-precedence tier never applies here — only the reservation does.)
  (with-referred-cljs-env
    (fn [aenv]
      (doseq [form '[[sub {}] [ui/lease {}] [re-frame.ui/frame] [frame]]]
        (is (= :rf.ui.compile/unsupported-form
               (compile-error-id #(root/analyze-root (referred-env aenv nil) 'ui/mount form)))
            (pr-str form)))
      (testing "a legal element root still analyzes through the root compiler"
        (is (= :element
               (:op (:ast (root/analyze-root (referred-env aenv nil) 'ui/mount '[:div "x"])))))))))
