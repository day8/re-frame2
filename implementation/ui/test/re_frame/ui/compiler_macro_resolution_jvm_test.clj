(ns re-frame.ui.compiler-macro-resolution-jvm-test
  "Real CLJS-analyzer resolution proofs for the expression macro barrier."
  (:require [cljs.analyzer :as cljs-analyzer]
            [cljs.analyzer.api :as cljs-api]
            [cljs.core]
            [cljs.env :as cljs-env]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ui.compiler.analyze :as analyze]
            [re-frame.ui.compiler.env :as env]))

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
