(ns re-frame.ui.root-mount-jvm-test
  "S1c Layer-1 (build-tier) duplicate/conflict detection + the
  client-entry host guard (rf2-vxgfnd.3). The Layer-1 indexes live in
  `re-frame.ui.compiler.root` and are exercised directly — the
  whole-build scoping with same-file replacement tolerance ([S1-CONFIRM]
  item 3, resolved in the ns docstring there). The mount-surface macros
  are CLIENT entry points: expanding them for the JVM host is the
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
  (root/register-root-site! 'ui/mount :page/shop :authored
                            {:file "app/entry.cljs" :line 10})
  (let [{:keys [id msg data]}
        (thrown-error
         #(root/register-root-site! 'ui/mount :page/shop :authored
                                    {:file "app/other.cljs" :line 4}))]
    (is (= :rf.error/duplicate-root-id id))
    (is (error/message-has-id-token? msg)
        "canonical builder: the message trails the [:rf.error/...] token")
    (is (= [:authored :authored] (:provenance data)))
    (is (= 2 (count (:sites data)))
        "the data map names both parties with source coordinates")))

(deftest both-derived-duplicate-names-the-fix
  (root/register-root-site! 'ui/mount :app.views/app :derived
                            {:file "app/a.cljs" :line 1})
  (let [{:keys [msg]}
        (thrown-error
         #(root/register-root-site! 'ui/mount :app.views/app :derived
                                    {:file "app/b.cljs" :line 1}))]
    (is (re-find #"add :disambiguator or author :root-id" msg)
        "the contract-pinned didactic fix when both ids are derived")))

(deftest same-file-re-registration-replaces
  (root/register-root-site! 'ui/mount :page/shop :authored
                            {:file "app/entry.cljs" :line 10})
  (is (nil? (root/register-root-site! 'ui/mount :page/shop :authored
                                      {:file "app/entry.cljs" :line 22}))
      "watch-mode re-expansion tolerance — a moved line is not a second site")
  (is (= 22 (:line (get @root/build-roots :page/shop)))))

(deftest distinct-root-ids-coexist
  (root/register-root-site! 'ui/mount :page/shop :authored
                            {:file "a.cljs" :line 1})
  (is (nil? (root/register-root-site! 'ui/mount :page/cart :authored
                                      {:file "b.cljs" :line 1})))
  (is (= #{:page/shop :page/cart} (set (keys @root/build-roots)))))

;; ---------------------------------------------------------------------------
;; Layer 1 — frame-plan index
;; ---------------------------------------------------------------------------

(deftest matching-plan-fingerprints-are-idempotent
  (root/register-plan-site! 'ui/mount
                            {:frame-id :shop :config-fingerprint "cf1-aaaa"}
                            {:file "a.cljs" :line 1})
  (is (nil? (root/register-plan-site!
             'ui/mount {:frame-id :shop :config-fingerprint "cf1-aaaa"}
             {:file "b.cljs" :line 9}))
      "matching fingerprint = the ratified idempotent no-op"))

(deftest cross-file-plan-fingerprint-conflict-fails-the-build
  (root/register-plan-site! 'ui/mount
                            {:frame-id :shop :config-fingerprint "cf1-aaaa"}
                            {:file "a.cljs" :line 1})
  (let [{:keys [id data]}
        (thrown-error
         #(root/register-plan-site!
           'ui/mount {:frame-id :shop :config-fingerprint "cf1-bbbb"}
           {:file "b.cljs" :line 9}))]
    (is (= :rf.error/frame-payload-conflict id))
    (is (= ["cf1-aaaa" "cf1-bbbb"] (:fingerprints data)))))

(deftest same-file-plan-change-replaces
  (root/register-plan-site! 'ui/mount
                            {:frame-id :shop :config-fingerprint "cf1-aaaa"}
                            {:file "a.cljs" :line 1})
  (is (nil? (root/register-plan-site!
             'ui/mount {:frame-id :shop :config-fingerprint "cf1-cccc"}
             {:file "a.cljs" :line 1}))
      "editing a plan's config in place is a replacement, not a conflict")
  (is (= "cf1-cccc" (:config-fingerprint (get @root/build-plans :shop)))))

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
