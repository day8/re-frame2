(ns re-frame.resources-provenance-stamp-cljs-test
  "The `:ns` image-selection stamp on the resource family — `reg-resource`,
  `reg-mutation`, `reg-resource-scope` (rf2-nrc93, ruled option D).

  Spec 001 §Production elision contract: a PROGRAMMATIC registration (fn-alias,
  JVM-direct, code-generated) leaves the macro's source-coord capture unbound,
  so its descriptor carries no `:rf.provenance/ns` and an explicit image's
  `:select-ns` `:include` globs never match it. Stamping `:ns` (or the qualified
  `:rf.provenance/ns`) in the registration metadata is the documented remedy on
  every REGISTRAR-BACKED kind — and these three build their own registrar map
  from their canonical spec, so before rf2-nrc93 they dropped both keys
  SILENTLY and the descriptor landed under nil provenance.

  Dual-target (`.cljc` + `_cljs_test`): the JVM runner picks it up via the
  `.*-test$` ns regex; Shadow's `:node-test` build via the `cljs-test$` regex.

  READ THE STORE, NOT A RENDERING. `re-frame.source-store/descriptors-for`
  returns the `provenance-ns-string → descriptor` map for `(kind, id)`; a key of
  \"probe.ns\" IS `:select-ns` selectability, and a key of `nil` is its absence.
  Every case here calls the OWNING FN directly (never the `rf/*` macro), so
  `*pending-coords*` is unbound and the stamp is the only provenance in play."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.registrar :as rf.registrar]
   [re-frame.resources]
   [re-frame.resources.mutation-registry :as rf.mutation-registry]
   [re-frame.resources.registry :as rf.resources.registry]
   [re-frame.resources.scope-registry :as rf.scope-registry]
   [re-frame.source-coords :as rf.source-coords]
   [re-frame.source-store :as rf.source-store]))

;; ---- fixtures -------------------------------------------------------------

;; FN form, not the `{:before … :after …}` map form (rf2-4yw1). `cljs.test`
;; accepts both shapes; `clojure.test` accepts only a function, and given a map
;; it invokes it as one — a map called with the test thunk is a KEY LOOKUP that
;; returns nil and never runs the test. The JVM lane then reports zero tests for
;; this namespace, silently and with exit 0. This file is `.cljc`, so it runs on
;; both lanes and must use the shape both accept.
;;
;; `clear-kind!` also drops the matching kind from the provenance source store
;; (registrar.cljc), so each case starts from an empty store slot.
(defn- clear-kinds! []
  (rf.registrar/clear-kind! :resource)
  (rf.registrar/clear-kind! :mutation)
  (rf.registrar/clear-kind! :resource-scope)
  (rf.registrar/clear-kind! :event))

(use-fixtures :each
  (fn [test-fn]
    (clear-kinds!)
    (try
      (test-fn)
      (finally
        (clear-kinds!)))))

;; ---- minimal valid metadata per kind --------------------------------------
;;
;; None of the three validators is a closed map, which is why a caller's `:ns`
;; passes validation today and the drop is SILENT.

(def ^:private request-fn
  (fn [_params _ctx] {:request {:method :get :url "/api/probe"}}))

(def ^:private resolve-fn
  (fn [_inputs] :rf.scope/global))

(defn- resource-meta
  "Minimal valid `reg-resource` metadata (the REQUIRED fail-closed `:scope`
  policy plus `:params-schema`), merged with `overrides`."
  [overrides]
  (merge {:scope :rf.scope/global :params-schema [:map]} overrides))

(defn- mutation-meta
  "Minimal valid `reg-mutation` metadata (the REQUIRED `:params-schema`)."
  [overrides]
  (merge {:params-schema [:map]} overrides))

(defn- scope-meta
  "Minimal valid `reg-resource-scope` metadata (the REQUIRED `:inputs`)."
  [overrides]
  (merge {:inputs {:db [:db []]}} overrides))

;; ---- the three registrars, each called as a plain FN -----------------------

(defn- reg!
  "Register `id` under `kind` through the kind's OWN registration fn with
  `overrides` merged onto the kind's minimal valid metadata. Never the macro —
  `*pending-coords*` stays whatever the caller bound it to."
  [kind id overrides]
  (case kind
    :resource       (rf.resources.registry/reg-resource id (resource-meta overrides) request-fn)
    :mutation       (rf.mutation-registry/reg-mutation  id (mutation-meta overrides) request-fn)
    :resource-scope (rf.scope-registry/reg-resource-scope id (scope-meta overrides) resolve-fn)))

(defn- provenance-keys
  "The source store's provenance keys for `(kind, id)` — what `:select-ns` reads."
  [kind id]
  (vec (keys (rf.source-store/descriptors-for kind id))))

(def ^:private kinds [:resource :mutation :resource-scope])

;; ---- (c) CONTROL: the instrument reads ABSENCE -----------------------------
;;
;; Passes BEFORE and AFTER the fix. It is what makes every `["probe.ns"]`
;; below a measurement rather than a coincidence: an unstamped programmatic
;; registration really does land under nil provenance.

(deftest unstamped-programmatic-registration-lands-under-nil-provenance
  (doseq [kind kinds]
    (testing (str "CONTROL — an unstamped " kind " registration records a "
                  "nil-provenance descriptor (nothing to :select-ns)")
      (reg! kind :probe/unstamped {})
      (is (= [nil] (provenance-keys kind :probe/unstamped))
          (str kind ": no stamp, no provenance — the instrument reads absence")))))

(deftest positive-control-reg-event-honours-the-bare-ns-stamp
  (testing "CONTROL — the shared registrar has always honoured a bare `:ns`
            stamp, so the instrument is known to read PRESENCE too"
    (rf.registrar/register! :event :probe/control
                            {:ns 'probe.control :handler-fn (fn [_cofx _event] nil)})
    (is (= ["probe.control"] (provenance-keys :event :probe/control))
        "registrar/register! with a bare :ns records under that provenance")))

;; ---- (a) bare `:ns` --------------------------------------------------------

(deftest bare-ns-stamp-is-forwarded-to-the-source-store
  (doseq [kind kinds]
    (testing (str "rf2-nrc93 — a bare `:ns` on " kind "'s metadata reaches the "
                  "source store, so the registration is :select-ns-selectable")
      (reg! kind :probe/bare {:ns 'probe.ns})
      (is (= ["probe.ns"] (provenance-keys kind :probe/bare))
          (str kind ": RED before rf2-nrc93 — the wrapper built its registrar "
               "map from the canonical spec and dropped :ns, giving [nil]")))))

;; ---- (b) qualified `:rf.provenance/ns` ------------------------------------

(deftest qualified-provenance-ns-stamp-is-forwarded-to-the-source-store
  (doseq [kind kinds]
    (testing (str "rf2-nrc93 — the qualified `:rf.provenance/ns` on " kind "'s "
                  "metadata reaches the source store too")
      (reg! kind :probe/qualified {:rf.provenance/ns "probe.qualified"})
      (is (= ["probe.qualified"] (provenance-keys kind :probe/qualified))
          (str kind ": RED before rf2-nrc93 — dropped alongside :ns, giving [nil]")))))

;; ---- (d) precedence: the qualified key wins -------------------------------

(deftest qualified-stamp-wins-over-the-bare-one

  (doseq [kind kinds]
    (testing (str "both stamps on one " kind " metadata map: the store reads the "
                  "explicit `:rf.provenance/ns` FIRST (source_store.cljc), so it "
                  "wins — rf2-nrc93 forwards both keys and changes no precedence")
      (reg! kind :probe/both {:ns 'probe.bare :rf.provenance/ns "probe.qualified"})
      (is (= ["probe.qualified"] (provenance-keys kind :probe/both))
          (str kind ": RED before rf2-nrc93 — both keys dropped, giving [nil]")))))

;; ---- (e) a user `:ns` overrides macro-captured coords ---------------------

(deftest user-ns-stamp-overrides-captured-pending-coords
  (doseq [kind kinds]
    (testing (str "merge-coords' user-overrides-captured rule reaches " kind ": "
                  "with `*pending-coords*` bound (the code-generated / macro "
                  "case), a caller's `:ns` still decides the provenance")
      (binding [rf.source-coords/*pending-coords* {:ns     'probe.generated
                                                   :file   "g.cljc"
                                                   :line   1
                                                   :column 1}]
        (reg! kind :probe/override {:ns 'probe.target}))
      (is (= ["probe.target"] (provenance-keys kind :probe/override))
          (str kind ": RED before rf2-nrc93 — the stamp was dropped and the "
               "captured coords answered instead, giving [\"probe.generated\"]")))))
