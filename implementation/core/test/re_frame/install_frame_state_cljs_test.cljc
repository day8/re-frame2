(ns re-frame.install-frame-state-cljs-test
  "The framework-standard `:rf/install-frame-state` event: the production write
  half of app-authored persistence, per Spec 002 §Installing a persisted
  frame-state.

  The payload is `{:rf.db/app <map>? :rf.db/runtime <map>?}`:

    - a present `:rf.db/app` REPLACES app-db;
    - a present `:rf.db/runtime` is applied PER TOP-LEVEL SUBTREE — each
      subtree it carries replaces that subtree, and the subtrees it omits are
      preserved;
    - an absent partition is untouched.

  A non-map payload, a present non-map partition, or a resource-runtime
  subtree makes the handler throw, so the router reports the one existing
  `:rf.error/handler-exception` and commits nothing.

  Posture split: the install semantics and the refusal are production-real
  and asserted through the always-on error-emit substrate, so the namespace
  runs in `clojure -M:test`, in the `-Dre-frame.debug=false` prod-gate lane
  and in `npm run test:cljs`. The one dev diagnostic this event must NOT fire
  (`:rf.warning/app-handler-runtime-effect`) rides the dev trace bus, so its
  assertion sits inside `(when rf.interop/debug-enabled? …)`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            #?(:cljs [cljs.reader])
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.events :as rf.events]
            [re-frame.frame :as rf.frame]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            #?(:clj [re-frame.test-support :as rf.test-support
                     :refer [with-emit-recorder! with-trace-recorder!]]
               :cljs [re-frame.test-support :as rf.test-support]))
  #?(:cljs (:require-macros [re-frame.test-support
                             :refer [with-emit-recorder! with-trace-recorder!]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf.error-emit/clear-error-listeners!))}))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private frame-counter (atom 0))

(defn- fresh-frame!
  "A registered frame under an id no other test in this process has used."
  []
  (let [fid (keyword "rf.install" (str "frame" (swap! frame-counter inc)))]
    (rf/make-frame {:id fid})
    fid))

(defn- seed!
  "Put `frame-id` into a known frame-state: `app` in app-db (through the
  canonical `:rf/set-db` event) and `runtime` in runtime-db (through the
  internal partition writer — the seed stands for state a live session built)."
  [frame-id app runtime]
  (rf/dispatch-sync [:rf/set-db app] {:frame frame-id})
  (rf.frame/replace-runtime-db! frame-id runtime)
  (rf/frame-state-value frame-id))

(def ^:private live-ssr
  {:hydration {:server-hash "aaaa1111" :version 1}})

(def ^:private live-routing
  {:current {:id :route/home :params {} :query {} :nav-token 7}})

(def ^:private saved-ssr
  {:hydration {:server-hash "bbbb2222" :version 2}})

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(deftest install-frame-state-is-a-core-standard-with-framework-authority
  (testing "core registers `:rf/install-frame-state` in the regular registrar
            AND the image standard registry, stamped with framework-write
            authority so the runtime-db it returns is in-bounds"
    (rf.events/register-install-frame-state-standard!)
    (let [meta (rf.registrar/handler-meta :event :rf/install-frame-state)]
      (is (some? meta) "the event resolves in the regular registrar")
      (is (= rf.events/install-frame-state-handler (:handler-fn meta)))
      (is (true? (:rf/framework-authority? meta))
          "stamped with the general framework-write authority key"))
    (let [std (->> (rf.image-assembly/standard-descriptors)
                   (filter #(and (= :event (:kind %))
                                 (= :rf/install-frame-state (:id %))))
                   first)]
      (is (true? (:standard std))
          "unioned into every resolved image generation")
      (is (true? (:rf/framework-authority? std))))))

;; ---------------------------------------------------------------------------
;; What the install writes
;; ---------------------------------------------------------------------------

(deftest a-present-app-partition-replaces-app-db-and-runtime-db-is-untouched
  (testing "`{:rf.db/app m}` replaces app-db with `m`; the absent runtime-db
            partition is left exactly as it was"
    (let [fid    (fresh-frame!)
          before (seed! fid {:old :value :other 1}
                        {:rf.runtime/ssr live-ssr})]
      (rf/dispatch-sync [:rf/install-frame-state {:rf.db/app {:restored :yes}}]
                        {:frame fid})
      (let [after (rf/frame-state-value fid)]
        (is (= {:restored :yes} (:rf.db/app after))
            "app-db is REPLACED, not merged — :old / :other are gone")
        (is (= (:rf.db/runtime before) (:rf.db/runtime after))
            "the omitted runtime-db partition is untouched")))))

(deftest a-present-runtime-partition-replaces-per-subtree
  (testing "each runtime-db subtree the payload carries replaces that subtree;
            every subtree it omits is preserved; the absent app partition is
            untouched"
    (let [fid    (fresh-frame!)
          before (seed! fid {:app :kept}
                        {:rf.runtime/ssr     live-ssr
                         :rf.runtime/routing live-routing})]
      (rf/dispatch-sync [:rf/install-frame-state
                         {:rf.db/runtime {:rf.runtime/ssr saved-ssr}}]
                        {:frame fid})
      (let [after (rf/frame-state-value fid)
            rt    (:rf.db/runtime after)]
        (is (= saved-ssr (:rf.runtime/ssr rt))
            "the supplied subtree REPLACES the live one (the saved value, whole)")
        (is (= live-routing (:rf.runtime/routing rt))
            "a subtree the payload omits is preserved, not wiped")
        (is (= (dissoc (:rf.db/runtime before) :rf.runtime/ssr)
               (dissoc rt :rf.runtime/ssr))
            "nothing else in runtime-db moved")
        (is (= (:rf.db/app before) (:rf.db/app after))
            "the omitted app partition is untouched")))))

(deftest both-partitions-install-as-one-transition
  (testing "a payload carrying both partitions installs both in the one event"
    (let [fid (fresh-frame!)
          _   (seed! fid {:app :old} {:rf.runtime/routing live-routing})]
      (rf/dispatch-sync [:rf/install-frame-state
                         {:rf.db/app     {:app :restored}
                          :rf.db/runtime {:rf.runtime/ssr saved-ssr}}]
                        {:frame fid})
      (let [after (rf/frame-state-value fid)]
        (is (= {:app :restored} (:rf.db/app after)))
        (is (= saved-ssr (get-in after [:rf.db/runtime :rf.runtime/ssr])))
        (is (= live-routing (get-in after [:rf.db/runtime :rf.runtime/routing])))))))

(deftest an-empty-payload-changes-nothing
  (testing "`{}` names no partition, so nothing is installed"
    (let [fid    (fresh-frame!)
          before (seed! fid {:app :kept} {:rf.runtime/ssr live-ssr})]
      (with-emit-recorder! [errs]
        (rf/dispatch-sync [:rf/install-frame-state {}] {:frame fid})
        (is (empty? @errs) "an empty payload is legal, not an error"))
      (is (= before (rf/frame-state-value fid))))))

(deftest a-round-trip-through-frame-state-value-restores-the-frame
  (testing "the documented loop: read with `frame-state-value`, serialise,
            read back, install into a fresh frame"
    (let [src   (fresh-frame!)
          _     (seed! src {:cart [1 2 3]} {:rf.runtime/ssr saved-ssr})
          state (rf/frame-state-value src)
          saved #?(:clj  (read-string (pr-str state))
                   :cljs (cljs.reader/read-string (pr-str state)))
          dst   (fresh-frame!)]
      (is (= state saved) "precondition: the frame-state round-trips as data")
      (rf/dispatch-sync [:rf/install-frame-state
                         {:rf.db/app     (:rf.db/app saved)
                          :rf.db/runtime (select-keys (:rf.db/runtime saved)
                                                      [:rf.runtime/ssr])}]
                        {:frame dst})
      (is (= {:cart [1 2 3]} (rf/app-db-value dst)))
      (is (= saved-ssr (get-in (rf/frame-state-value dst)
                               [:rf.db/runtime :rf.runtime/ssr]))))))

;; ---------------------------------------------------------------------------
;; Fail-closed: the handler throws, the router reports, nothing commits
;; ---------------------------------------------------------------------------

(defn- refused-install
  "Dispatch `[:rf/install-frame-state payload]` at a seeded frame and return
  `[error-ids before after]`, where `error-ids` are the always-on error records
  the dispatch produced."
  [payload]
  (let [fid    (fresh-frame!)
        before (seed! fid {:app :kept} {:rf.runtime/ssr live-ssr})]
    (with-emit-recorder! [errs]
      (rf/dispatch-sync [:rf/install-frame-state payload] {:frame fid})
      [(mapv :error @errs) before (rf/frame-state-value fid)])))

(deftest a-malformed-payload-is-refused-and-the-frame-state-is-untouched
  (doseq [[label payload] [["nil payload"                 nil]
                           ["vector payload"              [:rf.db/app {}]]
                           ["string payload"              "{:rf.db/app {}}"]
                           ["nil app partition"           {:rf.db/app nil}]
                           ["vector app partition"        {:rf.db/app [1 2]}]
                           ["string runtime partition"    {:rf.db/runtime "x"}]
                           ["valid app, bad runtime"      {:rf.db/app     {:ok 1}
                                                           :rf.db/runtime [:no]}]]]
    (testing label
      (let [[errors before after] (refused-install payload)]
        (is (= [:rf.error/handler-exception] errors)
            "the existing handler-exception reports the refusal — no new error id")
        (is (= before after)
            "no install, no partial commit: the frame-state is unchanged")))))

(deftest a-resource-runtime-subtree-is-refused
  (testing "the resource cache, work ledger and mutation runtime are not
            installable — a persisted resource cache is refused rather than
            installed raw, and the frame refetches instead"
    (doseq [k [:rf.runtime/resources :rf.runtime/work-ledger :rf.runtime/mutations]]
      (testing (str k)
        (let [[errors before after]
              (refused-install {:rf.db/app     {:would :replace}
                                :rf.db/runtime {:rf.runtime/ssr saved-ssr
                                                k               {}}})]
          (is (= [:rf.error/handler-exception] errors))
          (is (= before after)
              "neither partition moved, so the valid parts did not land alone"))))))

(deftest the-refusal-names-the-reason
  (testing "the handler throws an ex-info whose data carries the reason"
    (let [reason (fn [payload]
                   (try (rf.events/install-frame-state-handler
                          {:rf.db/runtime {}} [:rf/install-frame-state payload])
                        nil
                        (catch #?(:clj clojure.lang.ExceptionInfo
                                  :cljs cljs.core/ExceptionInfo) e
                          (:reason (ex-data e)))))]
      (is (string? (reason nil)))
      (is (string? (reason {:rf.db/app 5})))
      (is (string? (reason {:rf.db/runtime {:rf.runtime/mutations {}}})))
      (is (nil? (reason {:rf.db/app {}})) "a well-formed payload does not throw"))))

;; ---------------------------------------------------------------------------
;; The dev diagnostic an app-authored runtime write fires — and this does not
;; ---------------------------------------------------------------------------

(deftest no-runtime-effect-ownership-diagnostic
  (when rf.interop/debug-enabled?
    (testing "the install writes runtime-db with framework authority, so the
              `:rf.warning/app-handler-runtime-effect` diagnostic stays silent —
              while an unstamped app handler returning the same effect fires it"
      (let [fid     (fresh-frame!)
            warning? #(= :rf.warning/app-handler-runtime-effect (:operation %))]
        (rf/reg-event :install-test/app-writes-runtime
          (fn [{rt :rf.db/runtime} _]
            {:rf.db/runtime (assoc rt :rf.runtime/ssr saved-ssr)}))
        (with-trace-recorder! [traces {:pred warning?}]
          (rf/dispatch-sync [:install-test/app-writes-runtime] {:frame fid})
          (is (= 1 (count @traces))
              "control: an unstamped app handler returning :rf.db/runtime fires it"))
        (with-trace-recorder! [traces {:pred warning?}]
          (rf/dispatch-sync [:rf/install-frame-state
                             {:rf.db/runtime {:rf.runtime/ssr live-ssr}}]
                            {:frame fid})
          (is (empty? @traces)
              "the framework-authority install fires no ownership diagnostic"))))))
