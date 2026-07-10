(ns re-frame.elision-multi-owner-cljs-test
  "rf2-wdm1vg — the per-frame elision registry is a MULTI-OWNER claim registry:
  each axis slot maps a path to the SET of owner identities that claim it, and a
  path is redacted while ANY owner claims it, pruned only when the LAST owner
  drops.

  The correctness property this pins: two INDEPENDENT owners on the SAME path
  UNION, and removing one leaves the other intact. Before the fix the registry
  stored one owner per path, so a second owner's claim either overwrote the
  first (then deleted it on teardown) or was silently ignored — a source-scoped
  clear / subsystem teardown could un-redact a path another owner still
  classifies (a fail-open on the AI-egress privacy boundary).

  The cross-family proofs (effect + route / flow / machine / resource on the same
  path) live in each family's own test suite; this core suite pins the effect
  path + the pure core operations (`add-claims` / `remove-claims` /
  `remove-owner` / `replace-owner-claims`) + the source-aware rollback restore.

  Dual-runtime: `*_cljs_test.cljc` so both the shadow-cljs `:node-test` build
  (`npm run test:cljs`) and the JVM `clojure -M:test` runner run it."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            [re-frame.privacy :as privacy]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

(def ^:private sentinel privacy/redacted-sentinel)

;; A stand-in for a subsystem owner (route / flow / machine / resource): any
;; family lowers by delegating to the SAME core ops with its own owner
;; identity, so a `{:source :route}` owner installed via the shared
;; `swap-elision-slot!` seam models every subsystem claim generically.
(def ^:private subsystem-owner {:source :route})

(defn- claim-subsystem!
  "Install a subsystem owner claim on `slot` for `paths` (the way every family
  lowering does — deriving paths + owner and delegating to `add-claims`)."
  [slot paths]
  (elision/swap-elision-slot! :rf/default
    (fn [reg] (elision/add-claims (or reg {}) slot subsystem-owner paths))))

(defn- drop-subsystem!
  "Drop the subsystem owner's claims from `slot` (its teardown — delegating to
  `remove-owner`)."
  [slot]
  (elision/swap-elision-slot! :rf/default
    (fn [reg] (elision/remove-owner (or reg {}) slot subsystem-owner))))

(defn- wire []
  (elision/elide-wire-value (frame/frame-app-db-value :rf/default)))

;; ===========================================================================
;; (1) UNION + independent removal — the headline correctness property
;; ===========================================================================

(deftest effect-then-subsystem-independent-survival-sensitive
  (testing "effect claims a path, a subsystem ALSO claims it, both survive; the
            subsystem's teardown leaves the effect claim intact (the path stays
            redacted); only clearing the LAST (effect) owner un-classifies it"
    ;; effect classifies [:user :token] alongside its :db write
    (rf/reg-event :classify-token
      (fn [{:keys [db]} _]
        {:db (assoc-in db [:user :token] "Bearer secret") :sensitive [[:user :token]]}))
    (rf/dispatch-sync [:classify-token])
    ;; a subsystem (route-like) claims the SAME absolute path
    (claim-subsystem! :sensitive-declarations [[:user :token]])
    (is (= #{{:source :effect} subsystem-owner}
           (get (elision/sensitive-declarations) [:user :token]))
        "both independent owners are retained as a union")
    (is (= sentinel (get-in (wire) [:user :token]))
        "the doubly-claimed path redacts at egress")
    ;; subsystem teardown (route-leave) — the effect claim MUST survive
    (drop-subsystem! :sensitive-declarations)
    (is (= #{{:source :effect}} (get (elision/sensitive-declarations) [:user :token]))
        "the effect claim survives the subsystem teardown (the fix)")
    (is (= sentinel (get-in (wire) [:user :token]))
        "the path is STILL redacted after the subsystem left — the effect owns it")
    ;; now clear the effect (the sole remaining owner) — the path un-classifies
    (rf/reg-event :clear-token
      (fn [{:keys [db]} _] {:db db :clear-sensitive [[:user :token]]}))
    (rf/dispatch-sync [:clear-token])
    (is (not (contains? (elision/sensitive-declarations) [:user :token]))
        "clearing the last owner prunes the path")
    (is (= "Bearer secret" (get-in (wire) [:user :token]))
        "with no owner the value ships raw")))

(deftest subsystem-then-effect-clear-preserves-subsystem-sensitive
  (testing "the REVERSE order: a subsystem claims first, an effect SET on the
            same path UNIONS (it is neither ignored nor a clobber), and the
            effect's source-scoped CLEAR leaves the subsystem claim standing —
            the path stays redacted (the reverse-order fail-open the old
            single-owner registry hit)"
    (claim-subsystem! :sensitive-declarations [[:user :token]])
    (rf/reg-event :classify-token
      (fn [{:keys [db]} _]
        {:db (assoc-in db [:user :token] "Bearer secret") :sensitive [[:user :token]]}))
    (rf/dispatch-sync [:classify-token])
    (is (= #{subsystem-owner {:source :effect}}
           (get (elision/sensitive-declarations) [:user :token]))
        "the effect SET UNIONS in — the subsystem claim is not overwritten and the effect claim is not ignored")
    ;; effect CLEAR — source-scoped, removes ONLY the effect owner
    (rf/reg-event :clear-token
      (fn [{:keys [db]} _] {:db db :clear-sensitive [[:user :token]]}))
    (rf/dispatch-sync [:clear-token])
    (is (= #{subsystem-owner} (get (elision/sensitive-declarations) [:user :token]))
        "the subsystem claim survives the effect clear (not silently un-redacted)")
    (is (= sentinel (get-in (wire) [:user :token]))
        "the path is STILL redacted — the subsystem owns it")
    ;; subsystem teardown — now the path is finally free
    (drop-subsystem! :sensitive-declarations)
    (is (not (contains? (elision/sensitive-declarations) [:user :token]))
        "removing the last owner prunes the path")))

(deftest effect-and-subsystem-independent-survival-large
  (testing "the large axis unions the same way: a subsystem teardown leaves the
            effect's :large claim, so the path still elides to a size marker;
            the marker's :reason carries a STABLE provenance"
    (rf/reg-event :classify-blob
      (fn [{:keys [db]} _]
        {:db (assoc-in db [:docs :csv] (apply str (repeat 500 "X"))) :large [[:docs :csv]]}))
    (rf/dispatch-sync [:classify-blob])
    (claim-subsystem! :declarations [[:docs :csv]])
    (is (= #{{:source :effect} subsystem-owner}
           (get (elision/declarations) [:docs :csv]))
        "both owners retained on the large axis")
    (is (elision/marker? (get-in (wire) [:docs :csv]))
        "a doubly-claimed large path elides to a marker")
    (drop-subsystem! :declarations)
    (is (= #{{:source :effect}} (get (elision/declarations) [:docs :csv]))
        "the effect large-claim survives the subsystem teardown")
    (let [slot (get-in (wire) [:docs :csv])]
      (is (elision/marker? slot) "the path still elides after the subsystem left")
      (is (keyword? (get-in slot [:rf.size/large-elided :reason]))
          "the marker carries a stable provenance keyword derived from the owners"))))

;; ===========================================================================
;; (2) source-aware rollback restore — unwinds the in-band effect owner only
;; ===========================================================================

(deftest rollback-unwinds-effect-preserves-out-of-band-owner
  (testing "restore-elision-slot: on a schema rollback the in-band effect owner
            unwinds to its PRE-cascade state while every out-of-band subsystem
            owner (route/flow/machine/resource) is carried forward"
    (let [pre  {:sensitive-declarations {[:a] #{{:source :route}}}}
          ;; during the rejected event: effect ADDED [:a] and [:b]; a subsystem
          ;; owner still claims [:a]; a new subsystem claim lowered [:c].
          live {:sensitive-declarations {[:a] #{{:source :route} {:source :effect}}
                                         [:b] #{{:source :effect}}
                                         [:c] #{{:source :machine :actor-id :m#1}}}}
          restored (elision/restore-elision-slot pre live)
          s        (:sensitive-declarations restored)]
      (is (= #{{:source :route}} (get s [:a]))
          "the during-event effect claim on [:a] unwinds; the out-of-band route claim survives")
      (is (nil? (get s [:b]))
          "an in-band effect-only claim added this event is fully unwound")
      (is (= #{{:source :machine :actor-id :m#1}} (get s [:c]))
          "a during-event out-of-band subsystem lowering survives the rollback"))))

(deftest rollback-restores-effect-claim-cleared-in-band
  (testing "an effect claim that PREDATED the cascade and was cleared in-band by
            the rejected event is restored on rollback (the clear unwinds too)"
    (let [pre      {:sensitive-declarations {[:a] #{{:source :effect}}}}
          live     {:sensitive-declarations {}}
          restored (elision/restore-elision-slot pre live)]
      (is (= #{{:source :effect}} (get-in restored [:sensitive-declarations [:a]]))
          "the pre-cascade effect claim is restored"))))

(deftest rollback-restores-dropped-out-of-band-old-path
  (testing "rf2-o6rsi2: a mid-drain reentrant flow output-path MOVE that dropped
            the OLD path's flow claim is restored from the pre-cascade snapshot,
            while the NEW path's during-event flow claim survives from live"
    (let [owner    {:source :flow :flow-id :f}
          pre      {:sensitive-declarations {[:old] #{owner}}}
          live     {:sensitive-declarations {[:new] #{owner}}}
          restored (elision/restore-elision-slot pre live)
          s        (:sensitive-declarations restored)]
      (is (= #{owner} (get s [:old])) "the dropped old-path flow claim is restored from pre")
      (is (= #{owner} (get s [:new])) "the during-event new-path flow claim survives"))))

;; ===========================================================================
;; (3) the pure multi-owner operations
;; ===========================================================================

(deftest core-ops-union-and-scoped-removal
  (testing "add-claims unions; remove-claims / remove-owner strip one owner and
            prune only when the last owner leaves; replace-owner-claims reconciles"
    (let [e {:source :effect}
          r {:source :route}
          r2 (-> {}
                 (elision/add-claims :sensitive-declarations e [[:a]])
                 (elision/add-claims :sensitive-declarations r [[:a]]))]
      (is (= #{e r} (get-in r2 [:sensitive-declarations [:a]]))
          "two owners union on one path")
      (testing "remove-claims strips one owner at a named path, the other survives"
        (let [r3 (elision/remove-claims r2 :sensitive-declarations e [[:a]])]
          (is (= #{r} (get-in r3 [:sensitive-declarations [:a]])))))
      (testing "removing the last owner prunes the path AND the slot"
        (let [r4 (-> r2
                     (elision/remove-owner :sensitive-declarations e)
                     (elision/remove-owner :sensitive-declarations r))]
          (is (not (contains? r4 :sensitive-declarations))
              "the emptied slot is dissoc'd, not left as {}")))
      (testing "replace-owner-claims reconciles ONE owner's paths, leaving foreign owners"
        (let [r5 (elision/replace-owner-claims r2 :sensitive-declarations r [[:b]])]
          (is (= #{e} (get-in r5 [:sensitive-declarations [:a]]))
              "the route owner is dropped from its old path; the effect owner stays")
          (is (= #{r} (get-in r5 [:sensitive-declarations [:b]]))
              "the route owner is installed at its new path"))))))

(deftest core-ops-are-no-ops-on-empty-inputs
  (testing "add/remove with no paths, or remove against an empty slot, are no-ops"
    (is (= {} (elision/add-claims {} :sensitive-declarations {:source :effect} [])))
    (is (= {} (elision/remove-claims {} :sensitive-declarations {:source :effect} [[:a]])))
    (is (= {} (elision/remove-owner {} :sensitive-declarations {:source :effect})))))
