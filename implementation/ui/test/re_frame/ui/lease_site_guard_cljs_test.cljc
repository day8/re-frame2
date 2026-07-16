(ns re-frame.ui.lease-site-guard-cljs-test
  "rf2-vxgfnd.227 — pin the EXACT ex-data of every `re-frame.ui.reactive/lease-
  site` guard that reuses `:rf.error/ui-tree-malformed`.

  The existing coverage was blind to the guards' evidence: the JVM lease-
  descriptor test exercises only `lease-descriptor/validate-descriptor!` (the
  grammar arm), and the existing duplicate-site CLJS test asserts only the error
  id + zero mint. So adding or removing a guard evidence key stayed green. These
  fixtures drive the lease-site guards DIRECTLY through the ViewCell capture seam
  and pin the COMPLETE key set AND values per arm, so the Spec 009 row and the
  runtime cannot drift:

    - the missing-context / nil-site guard (outside a capture, or a nil lexical
      site id) carries base slots + `:site-id` + `:descriptor-summary`, recovery
      `:no-recovery` — and NEVER `:resource-id` (it has no recorded resource);
    - the duplicate lexical-site guard carries base slots + `:site-id` +
      `:descriptor-keys` + `:resource-id`, recovery `:no-recovery`.

  `.cljc` so the same fixtures run on node (`:node-test`/`:node-test-ui`) and on
  the JVM (`cd ui && clojure -M:test`) — both hosts share the reactive lease-site
  runtime."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.live-frame :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui.reactive :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(def ^:private base-thrown-keys
  "The canonical thrown-error slots (Spec 009 §The thrown-error shape)."
  #{:rf.error/id :where :recovery :reason})

(defn- ex-data-of [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (ex-data e))))

;; `diag-value-summary` of the valid descriptor `{:resource :feed/items}` — pinned
;; as a literal so a shape change in the summary is caught here too.
(def ^:private descriptor-summary {:type :map :count 1 :keys [:resource]})

(deftest missing-capture-context-guard-carries-site-id-and-descriptor-summary
  ;; A valid descriptor (so grammar validation passes) executed OUTSIDE any
  ;; ViewCell capture: *ambient* has no cell → the lexical site guard fires.
  (let [data (ex-data-of #(reactive/lease-site ::outside {:resource :feed/items}))]
    (testing "error id / where / recovery"
      (is (= :rf.error/ui-tree-malformed (:rf.error/id data)))
      (is (= 're-frame.ui.reactive/lease-site (:where data)))
      (is (= :no-recovery (:recovery data))))
    (testing "COMPLETE evidence key set — site-id + descriptor-summary, NO resource-id"
      (is (= (into base-thrown-keys [:site-id :descriptor-summary])
             (set (keys data))))
      (is (not (contains? data :resource-id))
          "this arm has no recorded resource, so it must not claim :resource-id"))
    (testing "evidence values"
      (is (= ::outside (:site-id data)))
      (is (= descriptor-summary (:descriptor-summary data))))))

(deftest nil-site-id-guard-inside-a-capture-carries-nil-site-id-and-descriptor-summary
  ;; Inside a live capture but with a nil lexical site id — the SAME guard branch,
  ;; proving it carries :site-id (here nil) + :descriptor-summary and never
  ;; :resource-id.
  (let [cell (reactive/make-cell ::nil-sid)
        data (ex-data-of
              #(reactive/with-capture
                cell
                (fn [] (reactive/lease-site nil {:resource :feed/items}))))]
    (is (= :rf.error/ui-tree-malformed (:rf.error/id data)))
    (is (= 're-frame.ui.reactive/lease-site (:where data)))
    (is (= :no-recovery (:recovery data)))
    (is (= (into base-thrown-keys [:site-id :descriptor-summary])
           (set (keys data))))
    (is (nil? (:site-id data)) "the nil lexical site id is reported as :site-id")
    (is (= descriptor-summary (:descriptor-summary data)))
    (is (not (contains? data :resource-id)))))

(deftest duplicate-lexical-site-guard-carries-site-id-descriptor-keys-and-resource-id
  ;; Two active lease sites aliased onto ONE lexical sid inside one capture — the
  ;; ONLY guard arm that records a resource, so the ONLY arm that carries
  ;; :resource-id (alongside :site-id + :descriptor-keys).
  (let [fid  :lease/guard-frame
        _    (live-frame/make-frame {:id fid})
        cell (reactive/make-cell ::dup)
        data (ex-data-of
              #(rf/with-frame fid
                 (reactive/with-capture
                  cell
                  (fn []
                    (reactive/lease-site ::dup-site {:resource :feed/items})
                    (reactive/lease-site ::dup-site {:resource :feed/items})))))]
    (try
      (testing "error id / where / recovery"
        (is (= :rf.error/ui-tree-malformed (:rf.error/id data)))
        (is (= 're-frame.ui.reactive/lease-site (:where data)))
        (is (= :no-recovery (:recovery data))))
      (testing "COMPLETE evidence key set — site-id + descriptor-keys + resource-id"
        (is (= (into base-thrown-keys [:site-id :descriptor-keys :resource-id])
               (set (keys data)))))
      (testing "evidence values"
        (is (= ::dup-site (:site-id data)))
        (is (= [:resource] (:descriptor-keys data)))
        (is (= :feed/items (:resource-id data))))
      (finally
        (frame/destroy-frame! fid)))))

(deftest descriptor-summary-literal-tracks-diag-value-summary
  ;; Guard the literal above against the real helper, so a genuine shape change
  ;; in diag-value-summary surfaces here rather than as a silent stale literal.
  (is (= descriptor-summary (error/diag-value-summary {:resource :feed/items}))))
