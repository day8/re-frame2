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
  change (the singleton drop).

  ## Posture split (rf2-o5dbf)

  The classification contract itself is production-real and carries NO posture
  guard — the fail-loud `reg-route` validation, the lowering / re-rooting into
  the per-frame elision registry, the singleton drop, the
  `elision/elide-wire-value` redaction and the real SSR
  `payload-policy/project-runtime-db` consumer all run in the ordinary
  `clojure -M:test` suite AND in `scripts/test-routing-prod-gate.sh` (the
  `-Dre-frame.debug=false` lane). rf2-u2x6w established that this egress
  genuinely happens in production, so that is exactly where it must be proven.

  The one dev-only surface here is the rf2-x1x5am QUERY-KEY PROMOTION
  ADVISORY. It is an authoring hint, emitted through `trace/emit! :warning`,
  which sits behind `interop/debug-enabled?` — read once at load time — so
  under the real gate there is no advisory to observe. Its assertions are kept
  VERBATIM inside `(when interop/debug-enabled? …)` arms marked `rf2-o5dbf`.

  Three of those deftests are QUIET tests — `(is (empty? warnings))`. With no
  trace bus every one of them passes VACUOUSLY, reporting \"no advisory fired\"
  for a framework that never looked. They are inside the arm for that reason.
  In their place the DETECTION is asserted posture-independently on
  `classification/unpromoted-query-keys`, the pure always-on predicate
  `advise-query-promotion!` is built from: it is what decides whether a route
  has the footgun, and only the announcement is dev-gated. Nothing was deleted
  or weakened."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.interop :as interop]
            [re-frame.privacy :as privacy]
            [re-frame.projection :as projection]
            [re-frame.routing.classification :as classification]
            [re-frame.routing.test-support]
            [re-frame.routing-test-support :as rts]
            ;; rf2-ugoxyv: drive the REAL SSR egress consumer (project-runtime-db
            ;; / project-routing-egress, #4896). SSR is a test-only dep of the
            ;; routing artefact (routing/deps.edn :test), so the routing suite
            ;; may exercise the real SSR projection.
            [re-frame.ssr.payload-policy :as payload-policy]))

(use-fixtures :each rts/reset-runtime)

(def ^:private sentinel privacy/redacted-sentinel)

(defn- elision-reg
  "Read the per-frame elision registry sub-tree from `:rf/default`'s
  runtime-db."
  []
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/elision]))

(defn- owners-sourced-by
  "The set of paths in a multi-owner declaration map `decls` (`{path #{owner…}}`)
  whose retained owner-set carries an owner with `:source src`."
  [decls src]
  (->> decls
       (filter (fn [[_ owners]] (some #(= src (:source %)) owners)))
       (map key)
       set))

(defn- route-sensitive-paths
  "The set of runtime paths claimed by a `:source :route` owner in the sensitive
  registry."
  []
  (owners-sourced-by (:sensitive-declarations (elision-reg)) :route))

(defn- route-large-paths
  []
  (owners-sourced-by (:declarations (elision-reg)) :route))

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
    ;; EP-0025 keys are now in it, so they don't trip :rf.error/route-bad-metadata.
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
    (let [rdb     (:rf.db/runtime (rf/frame-state-value :rf/default))
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
             (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
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
    (is (nil? (:rf.db/runtime (rf/frame-state-value :rf/default)))
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
;; (rf2-y8k6br) Nested :large ANCESTOR + :sensitive DESCENDANT — the
;; single most-important EP-0025 Egress-rules clause for routes. The
;; lowering only drops a :large path when it is EXACTLY equal to a
;; :sensitive path (classification.cljc large-only = (remove sens-set)
;; large-paths), so a :large ANCESTOR co-declared with a :sensitive
;; DESCENDANT lowers BOTH entries into the registry. The egress walker's
;; nested-axis suppression (rf2-izlr7f) is the authority: at a :large-matched
;; node whose coordinate STRICTLY SHADOWS a :sensitive descendant, sensitive
;; DOMINATES — the walker descends-and-redacts the descendant rather than
;; emitting a :rf.size/large-elided marker that would leak the ancestor's
;; :path / :bytes / :type (and, off-box-tool, a SHA-256 digest over the
;; secret-bearing subtree).
;; ===========================================================================

(deftest nested-large-ancestor-sensitive-descendant-redacts-at-egress
  (testing "a route declaring a :large ancestor + :sensitive descendant redacts
            the descendant and emits NO large marker leaking the ancestor"
    ;; :large on the :query :payload ANCESTOR subtree; :sensitive on the
    ;; :query :payload :secret DESCENDANT inside it.
    (rf/reg-route :route/nested
                  {:large     [[:query :payload]]
                   :sensitive [[:query :payload :secret]]}
                  "/nested")
    ;; Lowering keeps BOTH (the drop is exact-equal only) — confirm the
    ;; ancestor lands :large and the descendant lands :sensitive. These two
    ;; assertions pin the LOWERING contract and hold on main TODAY (independent
    ;; of the egress-walker fix).
    (rf/dispatch-sync [:rf.route/transitioned "/nested"])
    (is (contains? (route-large-paths)
                   [:rf.runtime/routing :current :query :payload])
        "the :large ancestor lowers (NOT dropped — it is not exactly a sensitive path)")
    (is (contains? (route-sensitive-paths)
                   [:rf.runtime/routing :current :query :payload :secret])
        "the :sensitive descendant lowers")
    ;; The egress projection is where nested-axis suppression MUST win. Place a
    ;; nested oversized + secret value at the slice query path directly — the
    ;; classification paths are what matter at egress.
    (let [rdb     (-> (:rf.db/runtime (rf/frame-state-value :rf/default))
                      (assoc-in [:rf.runtime/routing :current :query :payload]
                                {:secret "topsecret-bearer-token-value"
                                 :public "ok"}))
          elided  (elision/elide-wire-value rdb {:frame :rf/default})
          payload (get-in elided [:rf.runtime/routing :current :query :payload])]
      ;; The descendant secret is the BARE sentinel — redacted, not a marker.
      (is (= sentinel (:secret payload))
          "the :sensitive descendant is redacted (the bare sentinel)")
      ;; The unmarked sibling inside the ancestor rides verbatim.
      (is (= "ok" (:public payload))
          "an unmarked sibling inside the large ancestor rides verbatim")
      ;; CRUCIAL: the ancestor must NOT be a :rf.size/large-elided marker —
      ;; a marker would leak the ancestor's :path / :bytes / :type (and a
      ;; digest off-box) while a sensitive descendant is inside it. The walker
      ;; descends the ancestor (so the descendant redacts) rather than
      ;; collapsing it to a marker.
      (is (not (elision/marker? payload))
          "the :large ancestor is NOT collapsed to a marker — no path/bytes/digest leak while a sensitive descendant lives inside")
      (is (map? payload)
          "the ancestor remains a walked map (descended, so the nested secret redacts)"))))

;; ===========================================================================
;; (rf2-wpvd39) End-to-end :large-redacts-at-egress for a route. The route
;; :large axis was asserted only as far as landing in the registry
;; (activation-adds-large-entry); only :sensitive had an egress assertion.
;; This pins the EP-0025 large-axis promise on the route surface: a
;; route-declared :large path holding an oversized value produces a
;; :rf.size/large-elided size marker AT EGRESS while non-classified slice
;; fields ride verbatim and the in-process handler/sub sees the raw value.
;; ===========================================================================

(deftest declared-large-produces-size-marker-at-egress
  (testing "a route-classified :large param value produces a size marker via elide-wire-value"
    (rf/reg-route :route/upload
                  {:large [[:params :payload]]}
                  "/upload/:payload")
    (rf/dispatch-sync [:rf.route/transitioned "/upload/big-blob-value"])
    (let [rdb     (:rf.db/runtime (rf/frame-state-value :rf/default))
          elided  (elision/elide-wire-value rdb {:frame :rf/default})
          slice   (get-in elided [:rf.runtime/routing :current])
          payload (get-in slice [:params :payload])]
      (is (elision/marker? payload)
          "the declared :large param value is replaced by a :rf.size/large-elided marker off the wire")
      (let [body (:rf.size/large-elided payload)]
        (is (= [:rf.runtime/routing :current :params :payload] (:path body))
            "the marker carries the concrete runtime path")
        (is (= :route (:reason body))
            "the marker's :reason carries the :source :route provenance")
        (is (number? (:bytes body)) "the marker carries a byte count, not the value")
        ;; The marker must NOT carry the raw bulk value.
        (is (not= "big-blob-value" payload) "the raw value is off the wire"))
      (is (= :route/upload (:route-id slice))
          "non-classified slice fields (the route id) ride verbatim"))
    (testing "the handler / sub still sees the RAW value in-process"
      (is (= "big-blob-value"
             (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                     [:rf.runtime/routing :current :params :payload]))
          "classification is read ONLY at egress — app code sees real values"))))

;; ===========================================================================
;; (rf2-v8feh2) Cross-frame isolation of route classification. EP-0025 /
;; Spec 012 §Singleton-drop: route classifications are PER-FRAME. Two
;; frames navigating different :sensitive routes each see ONLY their own
;; route-sourced entry in their registry, and frame A's egress never
;; redacts using frame B's classification (frames-are-isolated-contexts).
;; ===========================================================================

(defn- route-sensitive-paths-for
  "The set of runtime paths classified :sensitive :source :route in
  `frame-id`'s elision registry."
  [frame-id]
  (owners-sourced-by (get-in (:rf.db/runtime (rf/frame-state-value frame-id))
                             [:rf.runtime/elision :sensitive-declarations])
                     :route))

(deftest cross-frame-route-classification-is-isolated
  (testing "two frames navigating different :sensitive routes each carry ONLY
            their own route-sourced classification (no cross-frame leak)"
    ;; A second app frame alongside :rf/default. Routes are shared (the
    ;; registry is process-global), but the lowered classification is
    ;; per-frame runtime-db state.
    (rf/make-frame {:id :frame/b :doc "second app frame for cross-frame isolation"})
    (rf/reg-route :route/a {:sensitive [[:query :a-secret]]} "/a")
    (rf/reg-route :route/b {:sensitive [[:query :b-secret]]} "/b")
    ;; Frame A (:rf/default) → route A; frame B → route B.
    (rf/dispatch-sync [:rf.route/transitioned "/a?a-secret=AAA"] {:frame :rf/default})
    (rf/dispatch-sync [:rf.route/transitioned "/b?b-secret=BBB"] {:frame :frame/b})
    ;; Each frame's registry carries ONLY its own route-sourced entry.
    (is (= #{[:rf.runtime/routing :current :query :a-secret]}
           (route-sensitive-paths-for :rf/default))
        "frame A's registry carries route A's classification ONLY")
    (is (= #{[:rf.runtime/routing :current :query :b-secret]}
           (route-sensitive-paths-for :frame/b))
        "frame B's registry carries route B's classification ONLY")
    (testing "each frame's egress redacts using its OWN classification, not the sibling's"
      (let [a-rdb (-> (:rf.db/runtime (rf/frame-state-value :rf/default))
                      (assoc-in [:rf.runtime/routing :current :query]
                                {:a-secret "AAA" :b-secret "BBB"}))
            b-rdb (-> (:rf.db/runtime (rf/frame-state-value :frame/b))
                      (assoc-in [:rf.runtime/routing :current :query]
                                {:a-secret "AAA" :b-secret "BBB"}))
            a-q   (get-in (elision/elide-wire-value a-rdb {:frame :rf/default})
                          [:rf.runtime/routing :current :query])
            b-q   (get-in (elision/elide-wire-value b-rdb {:frame :frame/b})
                          [:rf.runtime/routing :current :query])]
        ;; Frame A redacts a-secret (its own) and rides b-secret raw (B's, not A's).
        (is (= sentinel (:a-secret a-q)) "frame A redacts its own :a-secret")
        (is (= "BBB" (:b-secret a-q))
            "frame A does NOT redact :b-secret — frame B's classification does not leak into A")
        ;; Frame B redacts b-secret (its own) and rides a-secret raw (A's, not B's).
        (is (= sentinel (:b-secret b-q)) "frame B redacts its own :b-secret")
        (is (= "AAA" (:a-secret b-q))
            "frame B does NOT redact :a-secret — frame A's classification does not leak into B")))))

;; ===========================================================================
;; Pure unit coverage of the lowering seam (no nav needed)
;; ===========================================================================

(deftest apply-route-classification-replaces-route-sourced-only
  (testing "lowering preserves other-owner (effect/flow) claims and unions"
    (let [base {:rf.runtime/elision
                {:sensitive-declarations
                 {[:auth :token]                              #{{:source :effect}}
                  ;; an effect ALSO co-classifies the route's absolute path —
                  ;; it must SURVIVE the route reconcile (rf2-wdm1vg union).
                  [:rf.runtime/routing :current :query :new]  #{{:source :effect}}
                  [:rf.runtime/routing :current :query :old]  #{{:source :route}}}}}
          out  (classification/apply-route-classification
                 base {:sensitive [[:query :new]] :large []})
          sens (get-in out [:rf.runtime/elision :sensitive-declarations])]
      (is (= #{{:source :effect}} (get sens [:auth :token]))
          "the effect-owned entry survives")
      (is (nil? (get sens [:rf.runtime/routing :current :query :old]))
          "the prior route-owned entry is dropped (singleton replacement)")
      (is (= #{{:source :effect} {:source :route}}
             (get sens [:rf.runtime/routing :current :query :new]))
          "the new route claim UNIONS with the co-located effect claim, re-rooted"))))

(deftest apply-empty-classification-clears-route-sourced
  (testing "an empty classification clears the prior :source :route entries"
    (let [base {:rf.runtime/elision
                {:sensitive-declarations
                 {[:rf.runtime/routing :current :query :old] #{{:source :route}}}}}
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

;; ===========================================================================
;; (rf2-z07m4m) Authoring-boundary footguns on reg-route classification —
;; reviewer 3/3 F6 (low). Two minor surfaces with no prior coverage, both
;; pinned here so the intended (fail-open) behaviour is EXPLICIT:
;;
;;   (1) :clear-sensitive / :clear-large — a plausible copy-paste from the
;;       app-db commit-plane effect surface — are NOT classification keys
;;       (validate+extract triggers ONLY on #{:sensitive :large}). A route is a
;;       SINGLETON current-route, so a "clear" verb is meaningless by design.
;;       They are SILENTLY IGNORED by validate+extract (returns nil — no
;;       classification), BUT the reg-route bare-key guard rejects them LOUD
;;       (they are unreserved bare keys → :rf.error/route-bad-metadata), so
;;       a typo does fail at the authoring boundary. Pin both halves.
;;
;;   (2) :sensitive [[:query "token"]] with a STRING segment is accepted at
;;       validation as concrete EDN, but the runtime slice keys an undeclared
;;       query key as a STRING and a DECLARED one as a KEYWORD (coerce-query,
;;       rf2-5ifai). So a string-key declaration can never match a
;;       keyword-promoted slot — it fails OPEN. Spec 012 §319 warns of the
;;       keyword pairing; this locks the string-segment fail-open mode.
;; ===========================================================================

(deftest clear-keys-are-not-classification-keys-ignored-by-extract
  (testing "rf2-z07m4m: :clear-sensitive / :clear-large are NOT classification
            keys — validate+extract returns nil (silently ignored, no decl)"
    ;; validate+extract triggers only on #{:sensitive :large}; a route carrying
    ;; ONLY clear-keys has no classification to lower (a route is a singleton —
    ;; a clear verb is meaningless).
    (is (nil? (classification/validate+extract
                :route/clearish {:clear-sensitive [[:query :token]]}))
        ":clear-sensitive is not a classification key → no classification extracted")
    (is (nil? (classification/validate+extract
                :route/clearish {:clear-large [[:params :payload]]}))
        ":clear-large is not a classification key → no classification extracted")))

(deftest clear-keys-rejected-loud-at-reg-route-bare-key-guard
  (testing "rf2-z07m4m: a bare :clear-sensitive / :clear-large key is NOT in the
            reserved set, so reg-route's authoring-boundary guard fails it LOUD
            (a typo cannot silently no-op at registration)"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"\[:rf.error/route-bad-metadata\]"
          (rf/reg-route :route/clear1 {:clear-sensitive [[:query :token]]} "/clear1"))
        ":clear-sensitive is an unreserved bare key → rejected at reg-route")
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"\[:rf.error/route-bad-metadata\]"
          (rf/reg-route :route/clear2 {:clear-large [[:params :payload]]} "/clear2"))
        ":clear-large is an unreserved bare key → rejected at reg-route")))

(deftest string-query-segment-accepted-at-validation
  (testing "rf2-z07m4m: a :sensitive [[:query \"token\"]] STRING segment is
            accepted at validation as concrete EDN (it does not throw)"
    ;; A string IS a concrete EDN segment, so normalize-concrete admits it — the
    ;; declaration is well-formed and lowers a string-keyed path.
    (let [c (classification/validate+extract :route/strkey {:sensitive [[:query "token"]]})]
      (is (= [[:query "token"]] (:sensitive c))
          "the string-segment path is admitted verbatim as a concrete path")))
  (testing "rf2-z07m4m: reg-route accepts the string-segment declaration too
            (no throw at the authoring boundary)"
    (is (some? (rf/reg-route :route/strkey {:sensitive [[:query "token"]]} "/strkey")))))

(deftest string-query-segment-fails-open-against-keyword-promoted-slice
  (testing "rf2-z07m4m: a :sensitive [[:query \"token\"]] STRING-key declaration
            silently FAILS OPEN — a route that PROMOTES :token keys the slice
            with the KEYWORD :token, so the string-key decl never matches and the
            value ships RAW at egress (the documented fail-open mode)"
    ;; The route promotes `token` to the keyword `:token` via its :query schema,
    ;; so the runtime slice key is `:token` (keyword) — but the classification
    ;; declares the STRING key "token", which can never match.
    (rf/reg-route :route/strmiss
                  {:sensitive [[:query "token"]] :query [:map [:token :string]]}
                  "/strmiss")
    (rf/dispatch-sync [:rf.route/transitioned "/strmiss?token=secret123"])
    ;; The lowered registry carries the re-rooted STRING-key path…
    (is (= #{[:rf.runtime/routing :current :query "token"]}
           (route-sensitive-paths))
        "the string-segment path lowers verbatim (string key, not keyword)")
    ;; …but the runtime slice keys the value under the KEYWORD :token, so at
    ;; egress the classification path does not match → the value ships RAW.
    (let [rdb    (:rf.db/runtime (rf/frame-state-value :rf/default))
          elided (elision/elide-wire-value rdb {:frame :rf/default})
          slice  (get-in elided [:rf.runtime/routing :current])]
      (is (= "secret123" (get-in slice [:query :token]))
          "FAIL-OPEN: the keyword-promoted slot is NOT redacted by the string-key decl"))))

(deftest keyword-query-decl-matches-keyword-promoted-slice
  (testing "rf2-z07m4m (contrast): the CORRECT keyword-segment declaration DOES
            redact the keyword-promoted slice — the pairing the spec prescribes"
    (rf/reg-route :route/kwmatch
                  {:sensitive [[:query :token]] :query [:map [:token :string]]}
                  "/kwmatch")
    (rf/dispatch-sync [:rf.route/transitioned "/kwmatch?token=secret123"])
    (let [rdb    (:rf.db/runtime (rf/frame-state-value :rf/default))
          elided (elision/elide-wire-value rdb {:frame :rf/default})
          slice  (get-in elided [:rf.runtime/routing :current])]
      (is (= sentinel (get-in slice [:query :token]))
          "the keyword-segment decl matches the keyword-promoted slot → redacted"))))

;; ===========================================================================
;; (rf2-x1x5am) reg-route-time query-key promotion ADVISORY. A :sensitive /
;; :large [:query k] path on a route that does NOT promote `k` to a keyword
;; (via :query / :query-defaults) silently fails open; the
;; advisory is a WARN (never a throw — fail-open is intended). These pin the
;; advisory fires for the footgun and stays quiet for the correct pairing.
;; ===========================================================================

(defn- capture-warnings
  "Run `thunk` with a :trace listener that captures every
  :rf.warning/route-classification-query-key-unpromoted advisory; return the
  captured trace tags maps (one per emit)."
  [thunk]
  (let [seen (atom [])]
    (rf/register-listener! :trace ::advisory
                           (fn [ev]
                             (when (= :rf.warning/route-classification-query-key-unpromoted
                                      (:operation ev))
                               (swap! seen conj (:tags ev)))))
    (try (thunk) (finally (rf/unregister-listener! :trace ::advisory)))
    @seen))

(defn- unpromoted
  "The ALWAYS-ON detection behind the advisory: the query keys `route-meta`'s
  classification names that `promoted-keys` does not promote to a keyword.
  `classification/advise-query-promotion!` is exactly this predicate plus a
  dev-gated `trace/emit!`, so this is the posture-independent half — it decides
  whether a route HAS the footgun, and survives -Dre-frame.debug=false."
  [route-id route-meta promoted-keys]
  (classification/unpromoted-query-keys
    (classification/validate+extract route-id route-meta)
    promoted-keys))

(deftest advisory-fires-for-unpromoted-sensitive-query-key
  (testing "rf2-x1x5am: a :sensitive [[:query :token]] on a route with NO :query
            schema naming :token emits the unpromoted-query-key advisory"
    (let [warnings (capture-warnings
                     #(rf/reg-route :route/advmiss {:sensitive [[:query :token]]} "/advmiss"))]
      ;; SEMANTIC, posture-independent (rf2-o5dbf): the detection.
      (is (= #{:token} (unpromoted :route/advmiss {:sensitive [[:query :token]]} #{}))
          "the always-on predicate names :token as the unpromoted classified key")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (is (= 1 (count warnings)) "exactly one advisory fired")
        (let [w (first warnings)]
          (is (= :route/advmiss (:route-id w)) "the advisory names the route")
          (is (= [:token] (:query-keys w)) "the unpromoted key is named")
          (is (empty? (:promoted-keys w)) "the route promotes no query keys")
          (is (string? (:advice w)) "the advisory carries actionable guidance"))))))

(deftest advisory-quiet-when-query-key-promoted
  (testing "rf2-x1x5am: a :sensitive [[:query :token]] paired with a :query
            schema naming :token (the correct pairing) emits NO advisory"
    (let [warnings (capture-warnings
                     #(rf/reg-route :route/advok
                                    {:sensitive [[:query :token]] :query [:map [:token :string]]}
                                    "/advok"))]
      ;; SEMANTIC, posture-independent (rf2-o5dbf): the detection finds nothing
      ;; to advise on. Without this the `(empty? warnings)` leg below would pass
      ;; vacuously under the gate — an empty trace ring satisfies it trivially.
      (is (empty? (unpromoted :route/advok
                              {:sensitive [[:query :token]] :query [:map [:token :string]]}
                              #{:token}))
          "the always-on predicate finds no unpromoted key for the correct pairing")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring); NEGATIVE over
      ;; the trace ring, hence guarded.
      (when interop/debug-enabled?
        (is (empty? warnings) "no advisory when the query key is promoted")))))

(deftest advisory-honours-defaults-promotion
  (testing "rf2-x1x5am: :query-defaults also counts as promotion — a key
            declared there does NOT trigger the advisory"
    (let [warnings (capture-warnings
                    #(rf/reg-route :route/advdef
                                   {:sensitive [[:query :page]] :query-defaults {:page 1}}
                                   "/advdef"))]
      ;; SEMANTIC, posture-independent (rf2-o5dbf): see the twin above.
      (is (empty? (unpromoted :route/advdef
                              {:sensitive [[:query :page]] :query-defaults {:page 1}}
                              #{:page}))
          ":query-defaults promotes :page → the predicate finds nothing")
      ;; rf2-o5dbf — dev-instrumentation arm; NEGATIVE over the trace ring.
      (when interop/debug-enabled?
        (is (empty? warnings) ":query-defaults promotes :page → no advisory")))))

(deftest advisory-vocabulary-is-two-sources-not-three
  (testing "EP-0037 R5: the promotion vocabulary shrank from THREE sources to
            TWO. `:query-retain` is retired, so it neither widens the promoted
            set nor appears in the advice text. A key that used to be promoted
            solely because `:query-retain` named it now WARNS until the route
            declares it in :query or :query-defaults."
    (let [warnings (capture-warnings
                     #(rf/reg-route :route/advret
                                    {:sensitive [[:query :ref]]}
                                    "/advret"))]
      ;; SEMANTIC, posture-independent (rf2-o5dbf): with `:query-retain`
      ;; retired, NOTHING promotes :ref, so the always-on predicate reports it.
      (is (= #{:ref} (unpromoted :route/advret {:sensitive [[:query :ref]]} #{}))
          "a key that was promoted only by the retired :query-retain is now unpromoted")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (is (= 1 (count warnings))
            "a classified query key with no :query / :query-defaults declaration warns")
        (let [{:keys [query-keys promoted-keys advice]} (first warnings)]
          (is (= [:ref] query-keys) "the unpromoted key is named")
          (is (empty? promoted-keys) "nothing promotes it — the retain slot is gone")
          (is (str/includes? advice ":query / :query-defaults")
              "the advice names exactly the two surviving promotion sources")
          (is (not (str/includes? advice ":query-retain"))
              "the retired key is absent from the advice vocabulary")))
      ;; …and `:query-retain` really is inert rather than merely unmentioned:
      ;; naming :ref there does NOT promote it. Posture-independent.
      (is (= #{:ref} (unpromoted :route/advret {:sensitive [[:query :ref]]} #{}))
          ":query-retain widens nothing — the promoted set is the caller's alone"))
    ;; The migration the EP requires: DECLARE the key, and the advisory falls
    ;; silent — the same key is now keyword-promoted for real.
    (let [migrated-meta {:sensitive [[:query :ref]]
                         :query     [:map [:ref {:optional true} :string]]}
          warnings      (capture-warnings
                          #(rf/reg-route :route/advret-migrated migrated-meta
                                         "/advret-migrated"))]
      ;; SEMANTIC, posture-independent (rf2-o5dbf) — without this the negative
      ;; leg below is vacuous under the gate.
      (is (empty? (unpromoted :route/advret-migrated migrated-meta #{:ref}))
          "declaring :ref in the :query schema is the explicit migration")
      ;; rf2-o5dbf — dev-instrumentation arm; NEGATIVE over the trace ring.
      (when interop/debug-enabled?
        (is (empty? warnings)
            "declaring :ref in the :query schema silences the advisory")))))

(deftest advisory-fires-for-string-segment-and-large-axis
  (testing "rf2-x1x5am: a STRING [:query \"token\"] segment (can never be a
            keyword-promoted slot) AND a :large [:query k] unpromoted key both
            trigger the advisory"
    (let [w-str (capture-warnings
                  #(rf/reg-route :route/advstr {:sensitive [[:query "token"]]} "/advstr"))]
      ;; SEMANTIC, posture-independent (rf2-o5dbf): a STRING segment can never
      ;; name a keyword-promoted slot, so the predicate reports it whatever the
      ;; route promotes.
      (is (= #{"token"} (unpromoted :route/advstr {:sensitive [[:query "token"]]} #{:token}))
          "a string [:query \"token\"] segment is unpromotable by construction")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (is (= 1 (count w-str)) "the string-segment query key triggers the advisory")
        (is (= ["token"] (:query-keys (first w-str))) "the string key is named")))
    (let [w-large (capture-warnings
                    #(rf/reg-route :route/advlarge {:large [[:query :blob]]} "/advlarge"))]
      ;; SEMANTIC, posture-independent (rf2-o5dbf): the :large axis is scanned
      ;; too, not just :sensitive.
      (is (= #{:blob} (unpromoted :route/advlarge {:large [[:query :blob]]} #{}))
          "the predicate scans the :large axis as well as :sensitive")
      ;; rf2-o5dbf — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (is (= 1 (count w-large)) "an unpromoted :large [:query k] key triggers the advisory")
        (is (= [:blob] (:query-keys (first w-large))))))))

(deftest advisory-quiet-for-params-axis
  (testing "rf2-x1x5am: a :sensitive [[:params k]] path NEVER triggers the
            advisory — path captures are always keyword-keyed (immune)"
    (let [meta     {:sensitive [[:params :secret]]}
          warnings (capture-warnings
                     #(rf/reg-route :route/advparam meta "/advparam/:secret"))]
      ;; SEMANTIC, posture-independent (rf2-o5dbf): the predicate only ever
      ;; looks under a `[:query …]` head, so the :params axis cannot contribute.
      ;; Without this the negative leg below is vacuous under the gate.
      (is (empty? (unpromoted :route/advparam meta #{}))
          "the :params axis is immune to the query-promotion footgun")
      ;; rf2-o5dbf — dev-instrumentation arm; NEGATIVE over the trace ring.
      (when interop/debug-enabled?
        (is (empty? warnings) "no advisory fires for a :params-axis declaration")))))

;; ===========================================================================
;; (rf2-ugoxyv + rf2-v0k2mq) Real EGRESS-CONSUMER + non-default PROFILE
;; coverage. The egress assertions above call elide-wire-value MANUALLY under
;; the bare {:frame :rf/default} (no :rf.egress/profile), exercising the walker
;; directly but NO real consumer — exactly the gap that masked the rf2-4xut98
;; SSR leak. These drive a sensitive route through:
;;
;;   (rf2-v0k2mq) project-egress under NAMED non-default egress profiles
;;     (:rf.egress/ssr-hydration, :rf.egress/off-box-observability,
;;      :rf.egress/off-box-tool) — profile-AWARE redaction, not bare opts; and
;;   (rf2-ugoxyv) the REAL SSR consumer product — re-frame.ssr.payload-policy/
;;     project-runtime-db + project-routing-egress (the #4896 egress boundary) —
;;     asserting the sensitive route's :query value redacts in the serialized
;;     :rf/runtime-db slice end-to-end, while an unclassified field rides raw.
;;
;; SSR is a TEST-ONLY dep of the routing artefact (routing/deps.edn :test), so
;; the routing test suite may drive the real SSR projection without SSR
;; depending on routing.
;; ===========================================================================

(defn- navigate-sensitive-oauth!
  "reg-route a :sensitive oauth route (token + payload) with a :query schema
  that PROMOTES :token, navigate to it carrying a secret, and return the
  frame's runtime-db value (with the route classification lowered)."
  []
  (rf/reg-route :route/oauth
                {:sensitive [[:query :token]]
                 :large     [[:query :payload]]
                 :query     [:map [:token :string] [:payload :string]]}
                "/oauth")
  (rf/dispatch-sync [:rf.route/transitioned "/oauth?token=secret123&payload=big"])
  (:rf.db/runtime (rf/frame-state-value :rf/default)))

(deftest sensitive-route-redacts-under-ssr-hydration-profile
  (testing "rf2-v0k2mq: a route-declared :sensitive query value redacts under the
            NAMED :rf.egress/ssr-hydration profile (not just bare opts)"
    (let [rdb    (navigate-sensitive-oauth!)
          elided (projection/project-egress
                   rdb {:frame :rf/default
                        :rf.egress/profile :rf.egress/ssr-hydration})
          slice  (get-in elided [:rf.runtime/routing :current])]
      (is (= sentinel (get-in slice [:query :token]))
          "the sensitive query value redacts under :rf.egress/ssr-hydration")
      (is (= :route/oauth (:route-id slice)) "an unclassified field rides verbatim"))))

(deftest sensitive-route-redacts-under-off-box-profiles
  (testing "rf2-v0k2mq: the same route redacts under :rf.egress/off-box-observability
            and :rf.egress/off-box-tool (every named off-box default profile)"
    (let [rdb (navigate-sensitive-oauth!)]
      (doseq [profile [:rf.egress/off-box-observability :rf.egress/off-box-tool]]
        (let [slice (-> (projection/project-egress
                          rdb {:frame :rf/default :rf.egress/profile profile})
                        (get-in [:rf.runtime/routing :current]))]
          (is (= sentinel (get-in slice [:query :token]))
              (str "the sensitive query value redacts under " profile))
          ;; The :large payload elides to a size marker under off-box profiles.
          (is (elision/marker? (get-in slice [:query :payload]))
              (str "the :large query value elides to a size marker under " profile)))))))

(deftest sensitive-route-redacts-through-real-ssr-consumer
  (testing "rf2-ugoxyv: a :sensitive route driven through the REAL SSR consumer
            (project-runtime-db → project-routing-egress, the #4896 egress
            boundary) redacts the :query value in the serialized :rf/runtime-db
            slice end-to-end — the adversarial coverage that would have caught
            the rf2-4xut98 raw-ship leak"
    (let [rdb   (navigate-sensitive-oauth!)
          ;; project-runtime-db resolves the current frame (:rf/default, pinned
          ;; by the reset-runtime fixture's with-frame) and runs the durable
          ;; routing slice through project-routing-egress under
          ;; :rf.egress/ssr-hydration.
          slice (payload-policy/project-runtime-db rdb)
          route (get-in slice [:rf.runtime/routing :current])]
      (is (some? route) "the durable :current route slice rides the payload")
      (is (= sentinel (get-in route [:query :token]))
          "the sensitive :query value is redacted in the SSR :rf/runtime-db slice")
      (is (= :route/oauth (:route-id route))
          "the unclassified route id rides the payload verbatim")
      ;; The transient sibling never rides the wire (fail-closed allowlist).
      (is (not (contains? (:rf.runtime/routing slice) :pending-navigation))
          "only the durable :current slice ships")))
  (testing "rf2-ugoxyv: the in-process slice still carries the RAW value"
    (is (= "secret123"
           (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                   [:rf.runtime/routing :current :query :token]))
        "classification is read ONLY at egress — app code sees the real value")))

(deftest unclassified-route-rides-raw-through-real-ssr-consumer
  (testing "rf2-ugoxyv: a route with NO classification rides its :query verbatim
            through the real SSR consumer (the projection is path-precise, not a
            blanket scrub) — the negative control"
    (rf/reg-route :route/plain {:query [:map [:q :string]]} "/plain")
    (rf/dispatch-sync [:rf.route/transitioned "/plain?q=visible"])
    (let [slice (payload-policy/project-runtime-db (:rf.db/runtime (rf/frame-state-value :rf/default)))
          route (get-in slice [:rf.runtime/routing :current])]
      (is (= "visible" (get-in route [:query :q]))
          "an unclassified query value rides verbatim through the SSR projection"))))

;; ===========================================================================
;; (rf2-wdm1vg) MULTI-OWNER union — an effect-owned absolute path AND a route
;; claim on the SAME absolute path survive INDEPENDENTLY. Spec 015 L149 permits
;; an app to additionally classify a subsystem's absolute runtime-db path from a
;; handler effect (`:source :effect`); a route change must NOT un-redact that
;; effect-owned path. Both dispatch orders are pinned. On the pre-fix single-
;; owner registry the route claim overwrote (forward order) or ignored (reverse
;; order) the effect claim and then DELETED the path on route-leave — a live
;; privacy fail-open on the AI-egress boundary. These are the cross-family
;; reproduce→fix acceptance legs the bead enumerates.
;; ===========================================================================

(def ^:private abs-token-path [:rf.runtime/routing :current :query :token])

(defn- effect-classify-abs-token!
  "An app classifies the route's ABSOLUTE storage path directly via a
  commit-plane `:sensitive` effect (Spec 015 L149) — `:source :effect`."
  []
  (rf/reg-event :app/classify-abs-token
    (fn [{:keys [db]} _] {:db db :sensitive [abs-token-path]}))
  (rf/dispatch-sync [:app/classify-abs-token]))

(defn- token-path-classified?
  "Shape-agnostic: is the absolute token path present in the sensitive registry
  at all (i.e. still classified by SOME owner)?"
  []
  (contains? (:sensitive-declarations (elision-reg)) abs-token-path))

(defn- token-owners []
  (get (:sensitive-declarations (elision-reg)) abs-token-path))

(defn- redacts-token-at-abs-path?
  "Place a value at the absolute token path and egress-project it — is it
  redacted?"
  []
  (let [rdb   (-> (:rf.db/runtime (rf/frame-state-value :rf/default))
                  (assoc-in abs-token-path "secret123"))
        slice (get-in (elision/elide-wire-value rdb {:frame :rf/default})
                      [:rf.runtime/routing :current :query :token])]
    (= sentinel slice)))

(deftest effect-then-route-then-route-leave-preserves-effect-claim
  (testing "forward order: an effect classifies the route's absolute :query
            :token path; a route ALSO claims it (union); after navigating AWAY
            the effect claim SURVIVES and the path stays redacted (rf2-wdm1vg)"
    (effect-classify-abs-token!)
    (is (token-path-classified?) "the effect claim is installed")
    (rf/reg-route :route/oauth
                  {:sensitive [[:query :token]] :query [:map [:token :string]]}
                  "/oauth")
    (rf/reg-route :route/plain {} "/plain")
    (rf/dispatch-sync [:rf.route/transitioned "/oauth?token=secret123"])
    (is (= #{{:source :effect} {:source :route}} (token-owners))
        "the effect and route claims UNION on the same absolute path")
    ;; navigate to a route declaring NO classification (route-leave)
    (rf/dispatch-sync [:rf.route/transitioned "/plain"])
    (is (token-path-classified?)
        "the effect claim SURVIVES the route change (the fix — the path was deleted before)")
    (is (= #{{:source :effect}} (token-owners))
        "only the effect owner remains after the route left")
    (is (redacts-token-at-abs-path?)
        "a value at the effect-classified absolute path still redacts after route-leave")))

(deftest route-then-effect-then-route-leave-preserves-effect-claim
  (testing "reverse order: the route claims the absolute path first, THEN an
            effect classifies it (union — the effect is neither ignored nor a
            clobber); after route-leave the effect claim SURVIVES (rf2-wdm1vg —
            the effect claim was ignored then vanished before the fix)"
    (rf/reg-route :route/oauth
                  {:sensitive [[:query :token]] :query [:map [:token :string]]}
                  "/oauth")
    (rf/reg-route :route/plain {} "/plain")
    (rf/dispatch-sync [:rf.route/transitioned "/oauth?token=secret123"])
    (is (= #{{:source :route}} (token-owners)) "only the route claim so far")
    (effect-classify-abs-token!)
    (is (= #{{:source :route} {:source :effect}} (token-owners))
        "the effect SET UNIONS in — it is not ignored under the standing route claim")
    (rf/dispatch-sync [:rf.route/transitioned "/plain"])
    (is (token-path-classified?)
        "the effect claim SURVIVES the route change (the fix)")
    (is (= #{{:source :effect}} (token-owners))
        "only the effect owner remains after the route left")
    (is (redacts-token-at-abs-path?)
        "a value at the effect-classified absolute path still redacts after route-leave")))
