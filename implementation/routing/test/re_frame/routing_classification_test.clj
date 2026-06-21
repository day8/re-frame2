(ns re-frame.routing-classification-test
  "EP-0025 routes follow-on (rf2-3r6k8i) — route OWNER data classification.

  Pins the `reg-route` subsystem-matrix row of EP-0025 (Spec 012 §Route data
  classification): a route declares projection-relative `:sensitive` / `:large`
  paths (rooted at the route's `{:query … :params …}` projection); they are

    1. VALIDATED fail-loud at `reg-route` (a malformed path / wrong shape →
       `:rf.error/invalid-route-classification`, before any state mutates);
    2. LOWERED into the per-frame elision registry at route activation,
       RE-ROOTED under `[:rf.runtime/routing :current …]` and tagged
       `:source :route`, so the slice's `:query` / `:params` redact at egress;
    3. DROPPED on route change / deactivation — routes are a SINGLETON
       current-route, so activation REPLACES the prior route's `:source :route`
       entries (a route declaring none clears them); and frame teardown drops
       the whole runtime-db elision slot with the frame.

  The unifying invariant: classification lands ATOMICALLY with the slice
  publish (the same `:rf.db/runtime` commit), it is read ONLY at egress (the
  handler / subs always see real values), and it never leaks across a route
  change (the singleton drop)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.privacy :as privacy]
            [re-frame.routing.classification :as classification]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

(def ^:private sentinel privacy/redacted-sentinel)

(defn- elision-reg
  "Read the per-frame elision registry sub-tree from `:rf/default`'s
  runtime-db."
  []
  (get-in (rf/runtime-db-value :rf/default) [:rf.runtime/elision]))

(defn- route-sensitive-paths
  "The set of runtime paths classified `:sensitive` `:source :route` in the
  registry."
  []
  (->> (:sensitive-declarations (elision-reg))
       (filter (fn [[_ decl]] (= :route (:source decl))))
       (map key)
       set))

(defn- route-large-paths
  []
  (->> (:declarations (elision-reg))
       (filter (fn [[_ decl]] (= :route (:source decl))))
       (map key)
       set))

;; ===========================================================================
;; (1) registration-time fail-loud validation (EP-0025 §Failure posture)
;; ===========================================================================

(deftest reg-route-accepts-projection-relative-classification
  (testing "a well-formed :sensitive / :large declaration registers cleanly"
    (is (= :route/oauth
           (rf/reg-route :route/oauth
                         {:sensitive [[:query :token] [:query :code]]
                          :large     [[:params :payload]]}
                         "/oauth/callback"))
        "reg-route returns its id for a valid classification declaration")))

(deftest reg-route-classification-bare-keys-accepted
  (testing ":sensitive / :large pass the authoring-boundary bare-key guard"
    ;; The reserved-key guard rejects bare keys outside the reserved set; the
    ;; EP-0025 keys are now in it, so they don't trip :rf.error/invalid-route-metadata.
    (is (some? (rf/reg-route :route/x {:sensitive [[:query :t]]} "/x")))))

(deftest reg-route-rejects-malformed-classification-loud
  (testing "a non-vector axis fails loud at registration"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"\[:rf.error/invalid-route-classification\]"
          (rf/reg-route :route/bad {:sensitive {:not :a-vector}} "/bad"))))
  (testing "a non-sequential path entry fails loud"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"\[:rf.error/invalid-route-classification\]"
          (rf/reg-route :route/bad2 {:sensitive [:not-a-path]} "/bad2"))))
  (testing "a non-EDN-identity path segment fails loud (the :rf/path boundary)"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"\[:rf.error/invalid-route-classification\]"
          (rf/reg-route :route/bad3 {:large [[:params (fn [] :opaque)]]} "/bad3")))))

(deftest reg-route-classification-error-carries-canonical-shape
  (testing "the thrown error carries the canonical Spec 009 thrown-error shape"
    (let [ex (try (rf/reg-route :route/bad {:sensitive [:not-a-path]} "/bad")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))
          data (ex-data ex)]
      (is (= :rf.error/invalid-route-classification (:rf.error/id data)))
      (is (= 'rf/reg-route (:where data)))
      (is (= :fix-route-classification (:recovery data)))
      (is (= :route/bad (:route-id data)))
      (is (= :sensitive (:axis data))))))

;; ===========================================================================
;; (2) activation ADDS the re-rooted registry entry (EP-0025 §Subsystem matrix)
;; ===========================================================================

(deftest activation-adds-re-rooted-sensitive-entry
  (testing "navigating to a :sensitive route installs the re-rooted entry"
    (rf/reg-route :route/oauth
                  {:sensitive [[:query :token]] :query [:map [:token :string]]}
                  "/oauth")
    (is (empty? (route-sensitive-paths))
        "no route-sourced classification before any navigation")
    (rf/dispatch-sync [:rf.route/transitioned "/oauth?token=secret123"])
    (is (= #{[:rf.runtime/routing :current :query :token]}
           (route-sensitive-paths))
        "the projection-relative [:query :token] is re-rooted under [:rf.runtime/routing :current …] and tagged :source :route")))

(deftest activation-adds-large-entry
  (testing "a :large declaration lowers into the large :declarations slot"
    (rf/reg-route :route/upload {:large [[:params :payload]]} "/upload/:payload")
    (rf/dispatch-sync [:rf.route/transitioned "/upload/abc"])
    (is (= #{[:rf.runtime/routing :current :params :payload]}
           (route-large-paths)))
    (is (empty? (route-sensitive-paths)))))

;; ===========================================================================
;; (the param REDACTS at egress — the acceptance criterion)
;; ===========================================================================

(deftest declared-param-redacts-at-egress
  (testing "a route-classified :sensitive query value redacts via elide-wire-value"
    ;; A `:query` schema promotes `token` to the keyword key `:token` in the
    ;; slice (undeclared query keys stay strings — Spec 012 §Query strings), so
    ;; the projection-relative `[:query :token]` lands on the slice value.
    (rf/reg-route :route/oauth
                  {:sensitive [[:query :token]] :query [:map [:token :string]]}
                  "/oauth")
    (rf/dispatch-sync [:rf.route/transitioned "/oauth?token=secret123"])
    (let [rdb     (rf/runtime-db-value :rf/default)
          ;; egress-project the whole runtime-db against :rf/default's
          ;; classification — the route-sourced entry redacts the slice's
          ;; :query :token while leaving the rest of the slice intact.
          elided  (elision/elide-wire-value rdb {:frame :rf/default})
          slice   (get-in elided [:rf.runtime/routing :current])]
      (is (= sentinel (get-in slice [:query :token]))
          "the declared sensitive query value is redacted at egress")
      (is (= :route/oauth (:route-id slice))
          "non-classified slice fields ride verbatim"))
    (testing "the handler / sub still sees the RAW value in-process"
      (is (= "secret123"
             (get-in (rf/runtime-db-value :rf/default)
                     [:rf.runtime/routing :current :query :token]))
          "classification is read ONLY at egress — app code sees real values"))))

;; ===========================================================================
;; (3) route change / deactivation DROPS the entry (the singleton invariant)
;; ===========================================================================

(deftest route-change-drops-leaving-route-classification
  (testing "navigating to a different route replaces the prior :source :route entries"
    (rf/reg-route :route/oauth  {:sensitive [[:query :token]]} "/oauth")
    (rf/reg-route :route/plain  {} "/plain")
    (rf/dispatch-sync [:rf.route/transitioned "/oauth?token=secret"])
    (is (= #{[:rf.runtime/routing :current :query :token]} (route-sensitive-paths))
        "oauth route's classification is installed while active")
    (rf/dispatch-sync [:rf.route/transitioned "/plain"])
    (is (empty? (route-sensitive-paths))
        "navigating to a route declaring NO classification clears the route-sourced entries (no leak)")))

(deftest route-change-swaps-classification
  (testing "a route change drops the leaving route's entry and installs the entering route's"
    (rf/reg-route :route/a {:sensitive [[:query :a-secret]]} "/a")
    (rf/reg-route :route/b {:sensitive [[:params :b-secret]]} "/b/:b-secret")
    (rf/dispatch-sync [:rf.route/transitioned "/a?a-secret=1"])
    (is (= #{[:rf.runtime/routing :current :query :a-secret]} (route-sensitive-paths)))
    (rf/dispatch-sync [:rf.route/transitioned "/b/xyz"])
    (is (= #{[:rf.runtime/routing :current :params :b-secret]} (route-sensitive-paths))
        "only the entering route's classification survives the swap")))

(deftest not-found-drops-classification
  (testing "a route-miss / not-found transition clears the leaving route's classification"
    (rf/reg-route :route/oauth {:sensitive [[:query :token]]} "/oauth")
    (rf/dispatch-sync [:rf.route/transitioned "/oauth?token=secret"])
    (is (seq (route-sensitive-paths)))
    ;; a URL that matches no route → :rf.route/not-found (route-meta may be nil)
    (rf/dispatch-sync [:rf.route/transitioned "/no-such-route"])
    (is (empty? (route-sensitive-paths))
        "a not-found transition drops the prior route's :source :route entries")))

(deftest frame-destroy-drops-classification
  (testing "destroying the frame drops the whole runtime-db elision slot (no leak)"
    (rf/reg-route :route/oauth {:sensitive [[:query :token]]} "/oauth")
    (rf/dispatch-sync [:rf.route/transitioned "/oauth?token=secret"])
    (is (seq (route-sensitive-paths)))
    (rf/destroy-frame! :rf/default)
    ;; the frame's runtime-db (and its [:rf.runtime/elision] slot) is gone with it
    (is (nil? (rf/runtime-db-value :rf/default))
        "the destroyed frame carries no runtime-db, so no classification survives")))

;; ===========================================================================
;; Sensitive wins over large (EP-0025 §Egress rules)
;; ===========================================================================

(deftest sensitive-wins-over-large-at-lowering
  (testing "a path declared BOTH :sensitive and :large lowers as sensitive only"
    (rf/reg-route :route/both
                  {:sensitive [[:query :secret]]
                   :large     [[:query :secret] [:params :big]]}
                  "/both/:big")
    (rf/dispatch-sync [:rf.route/transitioned "/both/x?secret=s"])
    (is (contains? (route-sensitive-paths)
                   [:rf.runtime/routing :current :query :secret]))
    (is (not (contains? (route-large-paths)
                        [:rf.runtime/routing :current :query :secret]))
        "the co-declared path is dropped from :large — no large-elided marker can leak for it")
    (is (contains? (route-large-paths)
                   [:rf.runtime/routing :current :params :big])
        "the large-only path still lowers")))

;; ===========================================================================
;; Pure unit coverage of the lowering seam (no nav needed)
;; ===========================================================================

(deftest apply-route-classification-replaces-route-sourced-only
  (testing "lowering preserves other-sourced (effect/flow) entries"
    (let [base {:rf.runtime/elision
                {:sensitive-declarations
                 {[:auth :token]                              {:source :effect}
                  [:rf.runtime/routing :current :query :old]  {:source :route}}}}
          out  (classification/apply-route-classification
                 base {:sensitive [[:query :new]] :large []})
          sens (get-in out [:rf.runtime/elision :sensitive-declarations])]
      (is (= {:source :effect} (get sens [:auth :token]))
          "the effect-sourced entry survives")
      (is (nil? (get sens [:rf.runtime/routing :current :query :old]))
          "the prior route-sourced entry is dropped (singleton replacement)")
      (is (= {:source :route}
             (get sens [:rf.runtime/routing :current :query :new]))
          "the new route-sourced entry is installed, re-rooted"))))

(deftest apply-empty-classification-clears-route-sourced
  (testing "an empty classification clears the prior :source :route entries"
    (let [base {:rf.runtime/elision
                {:sensitive-declarations
                 {[:rf.runtime/routing :current :query :old] {:source :route}}}}
          out  (classification/apply-route-classification base nil)]
      (is (nil? (get-in out [:rf.runtime/elision :sensitive-declarations]))
          "the emptied axis slot is pruned, not left as {}")
      ;; The base carried a registry, so the result emits an EXPLICIT (empty)
      ;; `:rf.runtime/elision` key — the router's reconcile honours the clear
      ;; verbatim rather than carrying the leaving route's entries forward.
      (is (contains? out :rf.runtime/elision)
          "the explicit (empty) registry key signals the clear to reconcile")
      (is (= {} (:rf.runtime/elision out))
          "the cleared registry is an empty map (read as no declarations)")))

  (testing "no prior slot + no new entries → no :rf.runtime/elision sub-tree"
    (let [out (classification/apply-route-classification {:other :state} nil)]
      (is (not (contains? out :rf.runtime/elision))
          "a route without classification (and no prior slot) allocates nothing"))))
