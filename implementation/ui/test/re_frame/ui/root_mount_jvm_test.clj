(ns re-frame.ui.root-mount-jvm-test
  "S1c Layer-1 (build-tier) duplicate/conflict detection + the
  client-entry host guard (rf2-vxgfnd.3). The Layer-1 indexes live in
  `re-frame.ui.compiler.root` and are exercised directly — the
  whole-build scoping with same-NAMESPACE replacement tolerance
  ([S1-CONFIRM] item 3, resolved in the ns docstring there; the ledger is
  keyed by declaring ns-sym per rf2-df9873). The mount-surface macros are
  CLIENT entry points: expanding them for the JVM host is the
  `:rf.ui.compile/client-entry-on-jvm` compile error, pinned here via
  `macroexpand`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.error :as error]
            [re-frame.ui :as ui]
            [re-frame.ui.compiler.root :as root]))

(use-fixtures :each
  (fn [f]
    (root/reset-build-registries!)
    (try (f) (finally (root/reset-build-registries!)))))

(defn- thrown-error [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         {:id (:rf.error/id (ex-data e))
          :msg (ex-message e)
          :data (ex-data e)})))

;; ---------------------------------------------------------------------------
;; Layer 1 — root-site index
;; ---------------------------------------------------------------------------

(deftest cross-file-duplicate-root-id-fails-the-build
  (root/register-root-site! 'ui/mount :page/shop :authored 'app.entry
                            {:file "app/entry.cljs" :line 10})
  (let [{:keys [id msg data]}
        (thrown-error
         #(root/register-root-site! 'ui/mount :page/shop :authored 'app.other
                                    {:file "app/other.cljs" :line 4}))]
    (is (= :rf.error/duplicate-root-id id))
    (is (error/message-has-id-token? msg)
        "canonical builder: the message trails the [:rf.error/...] token")
    (is (= [:authored :authored] (:provenance data)))
    (is (= 2 (count (:sites data)))
        "the data map names both parties with source coordinates")))

(deftest both-derived-duplicate-names-the-fix
  (root/register-root-site! 'ui/mount :app.views/app :derived 'app.a
                            {:file "app/a.cljs" :line 1})
  (let [{:keys [msg]}
        (thrown-error
         #(root/register-root-site! 'ui/mount :app.views/app :derived 'app.b
                                    {:file "app/b.cljs" :line 1}))]
    (is (re-find #"add :disambiguator or author :root-id" msg)
        "the contract-pinned didactic fix when both ids are derived")))

(deftest same-namespace-re-registration-replaces
  (root/register-root-site! 'ui/mount :page/shop :authored 'app.entry
                            {:file "app/entry.cljs" :line 10})
  (is (nil? (root/register-root-site! 'ui/mount :page/shop :authored 'app.entry
                                      {:file "app/entry.cljs" :line 22}))
      "watch-mode re-expansion tolerance — a moved line is not a second site")
  (is (= 22 (:line (get (root/build-roots) :page/shop)))))

(deftest distinct-root-ids-coexist
  (root/register-root-site! 'ui/mount :page/shop :authored 'app.a
                            {:file "a.cljs" :line 1})
  (is (nil? (root/register-root-site! 'ui/mount :page/cart :authored 'app.b
                                      {:file "b.cljs" :line 1})))
  (is (= #{:page/shop :page/cart} (set (keys (root/build-roots))))))

;; ---------------------------------------------------------------------------
;; Layer 1 — frame-plan index
;; ---------------------------------------------------------------------------

(deftest matching-plan-fingerprints-are-idempotent
  (root/register-plan-site! 'ui/mount
                            {:frame-id :shop :config-fingerprint "cf1-aaaa"}
                            'app.a {:file "a.cljs" :line 1})
  (is (nil? (root/register-plan-site!
             'ui/mount {:frame-id :shop :config-fingerprint "cf1-aaaa"}
             'app.b {:file "b.cljs" :line 9}))
      "matching fingerprint = the ratified idempotent no-op"))

(deftest cross-file-plan-fingerprint-conflict-fails-the-build
  (root/register-plan-site! 'ui/mount
                            {:frame-id :shop :config-fingerprint "cf1-aaaa"}
                            'app.a {:file "a.cljs" :line 1})
  (let [{:keys [id data]}
        (thrown-error
         #(root/register-plan-site!
           'ui/mount {:frame-id :shop :config-fingerprint "cf1-bbbb"}
           'app.b {:file "b.cljs" :line 9}))]
    (is (= :rf.error/frame-payload-conflict id))
    (is (= ["cf1-aaaa" "cf1-bbbb"] (:fingerprints data)))))

(deftest same-namespace-plan-change-replaces
  (root/register-plan-site! 'ui/mount
                            {:frame-id :shop :config-fingerprint "cf1-aaaa"}
                            'app.a {:file "a.cljs" :line 1})
  (is (nil? (root/register-plan-site!
             'ui/mount {:frame-id :shop :config-fingerprint "cf1-cccc"}
             'app.a {:file "a.cljs" :line 1}))
      "editing a plan's config in place is a replacement, not a conflict")
  (is (= "cf1-cccc" (:config-fingerprint (get (root/build-plans) :shop)))))

;; ---------------------------------------------------------------------------
;; The client-entry host guard
;; ---------------------------------------------------------------------------

(defn- compile-error-id
  "The compile-error id of the ExceptionInfo `f` throws — walking the
  cause chain, since `macroexpand` wraps macro throws in a
  CompilerException."
  [f]
  (try (f) nil
       (catch Throwable t
         (loop [e t]
           (when (some? e)
             (or (:rf.ui.compile/error (ex-data e))
                 (recur (ex-cause e))))))))

(deftest mount-surface-macros-reject-the-jvm-host
  (doseq [form '[(re-frame.ui/mount [:div "x"] node)
                 (re-frame.ui/mount [:div "x"] node {})
                 (re-frame.ui/create-root node {:root-id :page/shop})
                 (re-frame.ui/render! r [:div "x"])
                 (re-frame.ui/hydrate-root node [:div "x"])]]
    (is (= :rf.ui.compile/client-entry-on-jvm
           (compile-error-id #(macroexpand form)))
        (str "JVM-host expansion rejected for " (pr-str (first form))))))

(defn- compile-error-data
  "ex-data of the compile error `f` throws (cause-chain walked)."
  [f]
  (try (f) nil
       (catch Throwable t
         (loop [e t]
           (when (some? e)
             (if (:rf.ui.compile/error (ex-data e))
               (ex-data e)
               (recur (ex-cause e))))))))

(deftest mount-surface-compile-errors-carry-source-anchor
  ;; S1e file:line anchoring parity: errors thrown through the
  ;; mount-surface expansion path carry the call form's source
  ;; coordinates in ex-data (mirrors compiler.cljc's `anchored`).
  (doseq [form ['(re-frame.ui/mount [:div "x"] node)
                '(re-frame.ui/create-root node {:root-id :page/shop})
                '(re-frame.ui/hydrate-root node [:div "x"])]]
    (let [data (compile-error-data
                #(macroexpand (with-meta form {:line 42 :column 7})))]
      (is (some? (:file data)) (str (pr-str (first form)) " anchors :file"))
      (is (= 42 (:line data)) (str (pr-str (first form)) " anchors :line"))
      (is (= 7 (:column data)) (str (pr-str (first form)) " anchors :column"))
      (is (some? (:rf.ui.compile/error data)) "id survives anchoring"))))

(deftest unmount!-is-a-jvm-host-op
  (let [ex (try (ui/unmount! ::fake-root) nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :rf.error/jvm-host-op (:rf.error/id (ex-data ex))))))

(deftest direct-frame-root-call-fails-loud
  (let [ex (try (ui/frame-root {:id :shop}) nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :rf.error/ui-frame-root-outside-root-form
           (:rf.error/id (ex-data ex))))
    (is (error/message-has-id-token? (ex-message ex)))))
