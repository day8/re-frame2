(ns re-frame.ui.local-effect-dispatch-fn-jvm-test
  "rf2-vxgfnd.95.2 — the S3 host hooks (`local` / `effect` / `ui/dispatch-fn`)
  on the JVM Tier-1 structural host, plus their compile-time placement law.

  The JVM structural subset (Spec 004 §The JVM structural subset; 06 §1 / 07 §2):
  `local` exposes its INITIAL value in a structural render, but invoking its
  `set!`/`update!` raises the typed `:rf.error/jvm-host-op`; `effect` bodies do
  NOT run (recorded as capability metadata only); a `ui/dispatch-fn` dispatcher's
  invocation raises the same typed host-op error. Placement REJECTIONS
  (`hook-misplaced`, `bad-effect`) ride the compile path (this suite is the
  owning suite the frozen error roster names for `bad-effect`).

  Client-only behaviour (batched multi-writer, render-phase reject, effect rf=
  deps + cleanup + :connect + StrictMode replay, dispatch-fn stable/retarget/
  fail-after-disconnect, mixed local-set + dispatch one host pass) rides the DOM
  suite `local_effect_dispatch_fn_dom_cljs_test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview local effect dispatch-fn]]
            [re-frame.ui.compiler :as compiler]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.test :as uit]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [t]
    (frames/reset-frame-ops-cache!)
    (try (t) (finally (frames/reset-frame-ops-cache!)))))

;; ---------------------------------------------------------------------------
;; Fixtures — render seam handing the host ops out of a top-level view body
;; ---------------------------------------------------------------------------

(def ^:dynamic *effect-ran* nil)
(def ^:dynamic *capture-ops!* (fn [_ops]))

(defview widget
  "local + effect + dispatch-fn in one view. The JVM render exposes the local's
  initial value and hands the setter/updater/dispatcher to the test seam."
  [{:keys [start]}]
  (let [[n set-n update-n] (local start)
        d (dispatch-fn)
        _ (*capture-ops!* {:set-n set-n :update-n update-n :dispatch d})]
    (effect [n] (some-> *effect-ran* (reset! :value-deps)) (fn [] nil))
    (effect :connect (some-> *effect-ran* (reset! :connect)))
    [:div [:span (str n)]
     [:button {:on-click [:inc]} "+"]]))

;; ---------------------------------------------------------------------------
;; Tier-1 structural render — local initial value exposed
;; ---------------------------------------------------------------------------

(deftest local-exposes-initial-value-and-effects-do-not-run
  (let [ran (atom nil)]
    (binding [*effect-ran* ran]
      (let [t (uit/render [widget {:start 7}])]
        (is (= "7" (uit/text (some #(when (= :span (:tag %)) %) (tree-seq map? :children t))))
            "the structural render exposes local's INITIAL value")
        (is (nil? @ran)
            "effects DO NOT run in a JVM structural render (metadata only)")))))

(deftest jvm-host-mutations-raise-typed-error
  (let [ops (atom nil)]
    (binding [*capture-ops!* #(reset! ops %)]
      (uit/render [widget {:start 0}])
      (let [{:keys [set-n update-n dispatch]} @ops
            id (fn [thunk]
                 (try (thunk) ::no-throw
                      (catch clojure.lang.ExceptionInfo e
                        (:rf.error/id (ex-data e)))))]
        (is (= :rf.error/jvm-host-op (id #(set-n 9)))
            "local set! is host-only on the JVM")
        (is (= :rf.error/jvm-host-op (id #(update-n inc)))
            "local update! is host-only on the JVM")
        (is (= :rf.error/jvm-host-op (id #(dispatch [:x])))
            "ui/dispatch-fn dispatch is host-only on the JVM")))))

;; ---------------------------------------------------------------------------
;; Manifest — capabilities + the HMR hook signature
;; ---------------------------------------------------------------------------

(defn- manifest [view-id]
  (:rf.ui/manifest (registrar/handler-meta :view view-id)))

(deftest manifest-records-hook-capabilities-and-sites
  (let [m (manifest ::widget)]
    (is (contains? (set (:capabilities m)) :local))
    (is (contains? (set (:capabilities m)) :effect))
    (is (contains? (set (:capabilities m)) :dispatch-fn))
    (is (= 1 (count (get-in m [:sites :locals]))))
    (is (= 1 (count (get-in m [:sites :dispatch-fns]))))
    (is (= [:deps :connect] (mapv :kind (get-in m [:sites :effects])))
        "effect sites record their kind in source order")))

(defview plain-view [] [:div "x"])
(defview one-local [] (let [[a _] (local 0)] [:div (str a)]))
(defview two-locals []
  (let [[a _] (local 0) [b _] (local 1)] [:div (str a b)]))

(deftest hook-signature-rides-the-local-count
  (let [hs (fn [id] (:hook-signature (manifest id)))]
    (is (not= (hs ::plain-view) (hs ::one-local))
        "adding the first local changes the HMR hook signature (remount)")
    (is (not= (hs ::one-local) (hs ::two-locals))
        "adding a second local changes the signature again")
    (is (every? #(clojure.string/starts-with? (hs %) "hs1-")
                [::plain-view ::one-local ::two-locals]))))

;; ---------------------------------------------------------------------------
;; Compile-time placement law
;; ---------------------------------------------------------------------------

(defn- expand-error [form]
  (try (macroexpand-1 form) nil
       (catch clojure.lang.ExceptionInfo e (:rf.ui.compile/error (ex-data e)))
       (catch Exception e
         (let [c (.getCause e)]
           (when (instance? clojure.lang.ExceptionInfo c)
             (:rf.ui.compile/error (ex-data c)))))))

(deftest placement-law-rejections
  (testing "local in a conditional branch — host hooks run unconditionally"
    (is (= :rf.ui.compile/hook-misplaced
           (expand-error
            '(re-frame.ui/defview v [{:keys [p]}]
               (if p (let [[a _] (re-frame.ui/local 1)] [:div (str a)]) [:span]))))))
  (testing "local in a loop"
    (is (= :rf.ui.compile/hook-misplaced
           (expand-error
            '(re-frame.ui/defview v []
               [:ul (for [x [1 2]] [:li {:key x} (str (re-frame.ui/local x))])])))))
  (testing "effect as an expression (not a leading statement)"
    (is (= :rf.ui.compile/hook-misplaced
           (expand-error
            '(re-frame.ui/defview v []
               [:div {:title (re-frame.ui/effect [1] (prn 1))}])))))
  (testing "bad-effect: first arg neither a deps vector nor :connect"
    (is (= :rf.ui.compile/bad-effect
           (expand-error
            '(re-frame.ui/defview v []
               (re-frame.ui/effect "nope" (prn 1))
               [:div])))))
  (testing "bad-effect: empty body"
    (is (= :rf.ui.compile/bad-effect
           (expand-error
            '(re-frame.ui/defview v []
               (re-frame.ui/effect [1])
               [:div]))))))

(deftest accepted-shapes-compile
  (is (nil? (expand-error
             '(re-frame.ui/defview v [{:keys [s]}]
                (let [[t set-t update-t] (re-frame.ui/local "")
                      d (re-frame.ui/dispatch-fn)]
                  (re-frame.ui/effect [t] (prn t) (fn [] nil))
                  (re-frame.ui/effect :connect (d [:hi]))
                  [:input {:value t}]))))
      "the canonical local + effect + dispatch-fn view compiles")
  (is (nil? (expand-error
             '(re-frame.ui/defview v []
                (re-frame.ui/effect :connect (prn :connected))
                [:div "top-level connect effect, no local"])))
      "a :connect effect at the defview top region (no wrapping let) compiles"))
