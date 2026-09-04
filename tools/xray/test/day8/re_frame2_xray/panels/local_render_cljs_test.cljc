(ns day8.re-frame2-xray.panels.local-render-cljs-test
  "The local-render egress test for Xray's ON-BOX panel value rendering — the
  EP-0015 `:rf.egress/local-redacted` GRADUATING-CONSUMER proof (rf2-t55hxg.12).

  ## The gap this closes

  [spec/015-Data-Classification.md §The graduation gate] requires each of the
  six `:rf.egress/*` profiles be EXERCISED by a real consumer before the names
  lock. `:rf.egress/local-redacted` was the one remaining gap: defined +
  unit-tested in `re-frame.projection` but no on-box dev tool named it as its
  render default. Xray is that consumer — its local panel value rendering now
  routes through `re-frame.core/project-egress` under
  `:rf.egress/local-redacted` (`day8.re-frame2-xray.panels.local-render`). This
  test is the end-to-end exercise the graduation row names: the on-box LOCAL
  render of a value REDACTS a frame-declared sensitive slot by default while
  KEEPING large values (the local-render contract: suppress sensitive display;
  the local operator may see big values).

  ## What's asserted

    1. **DEFAULT redacts sensitive** — a value carrying a sensitive slot at a
       frame-declared sensitive path is projected under the local-render
       default; the sensitive slot becomes `:rf/redacted` WHILE non-sensitive
       siblings + structure ride through.
    2. **DEFAULT keeps large** — a frame-declared `:large` value is NOT elided
       on-box (the operator sees big values; only secrets are withheld).
    3. **per-frame** — projection uses the OBSERVED frame's policy, not a
       borrowed / ambient one; a plain frame ships the same value verbatim.
    4. **`:rf.egress/local-raw` opt-in** — the per-(tool,frame) trusted-local
       grain reveals the sensitive slot (an explicit operator act).
    5. **fail-closed** — an unreachable observed frame redacts the WHOLE value
       under the redacted default rather than ship it raw; the unreachable /
       nil frame is stamped with a NON-NIL dead-frame sentinel (not omitted),
       and (5b) a nil / unreachable observed frame still redacts EVEN WHEN an
       ambient frame is dynamically bound — it must NOT borrow that ambient
       frame's policy and leak the secret (rf2-cra0nq, mirroring rf2-udkj69).
    6. **end-to-end** — the App-DB Diff section model sub
       (`:rf.xray/app-db-state`) redacts a sensitive app-db slot, so what the
       panel hands the edn-inspector is already projected."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels.app-db-diff-helpers :as h]
            [day8.re-frame2-xray.panels.local-render :as local-render]))

;; ---------------------------------------------------------------------------
;; Runtime fixture — mirror the off-box derivation-graph redaction test
;; (rf2-yjarv6): two frames, one with declared :sensitive / :large policy.
;;
;;   :app/secure — declares [:auth :token] sensitive and [:catalog :rows] large.
;;   :app/plain  — no classification (every value renders verbatim).
;; ---------------------------------------------------------------------------

(def secure-frame :app/secure)
(def plain-frame  :app/plain)

(defn- install-policy! []
  ;; EP-0025: durable app-db classification rides the commit-plane
  ;; classification effects (`:source :effect`) — the frame annotation is removed.
  (rf.frame/swap-runtime-db! secure-frame
    (fn [rt] (rf.elision/apply-classification-effects rt
               {:sensitive [[:auth :token]]
                :large     [[:catalog :rows]]}))))

(defn- init-fn []
  (rf/make-frame {:id plain-frame})
  (rf/make-frame {:id secure-frame})
  (install-policy!))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     ;; OPT OUT of the default `:rf/default` ambient scope so the fail-closed
     ;; arm asserts a frameless walk redacts (a bound ambient frame would let
     ;; the frameless projection resolve to its empty-policy identity and ship
     ;; raw — masking the fail-closed contract). Mirrors the off-box sibling
     ;; test's fixture.
     :ambient-frame nil
     :init-fn init-fn}))

;; A value with both a frame-declared sensitive slot and a frame-declared
;; large slot, plus plain siblings — the shape an app-db panel renders.
(def ^:private app-db-value
  {:auth    {:token "secret-session-jwt-abc123"
             :user-id 42}
   :catalog {:rows (vec (range 300))}
   :ui      {:open? true :tab :home}})

;; ---------------------------------------------------------------------------
;; WHITE-BOX REACH, JVM LANE ONLY (rf2-ws60, third pass).
;;
;; `local-render-opts` is PRIVATE — that privacy IS the fix (see §7c), so the
;; arms that inspect the opts map have to reach the var by name rather than by
;; reference. `resolve` is used instead of `#'`: Clojure's `var` special form
;; refuses a non-public var from another namespace, and in ClojureScript a
;; cross-namespace private reference compiles with a warning, which would put
;; the very escape hatch this bead closes back into the CLJS build. So the
;; reach exists on the JVM lane only; the source is one shared `.cljc`
;; definition, and every arm that can be written against the PUBLIC surface is
;; written there and runs in both lanes.
;; ---------------------------------------------------------------------------

#?(:clj
   (def ^:private local-render-opts*
     (deref (resolve 'day8.re-frame2-xray.panels.local-render/local-render-opts))))

;; ---------------------------------------------------------------------------
;; 1 + 2. DEFAULT — redact sensitive, KEEP large.
;; ---------------------------------------------------------------------------

(deftest local-render-default-redacts-sensitive-keeps-large
  (let [rendered (local-render/local-render-value app-db-value secure-frame)]
    (testing "(1) the frame-declared sensitive slot is REDACTED under the
              on-box default (:rf.egress/local-redacted)"
      (is (= :rf/redacted (get-in rendered [:auth :token]))
          "the sensitive token must redact in the local render default"))

    (testing "non-sensitive siblings + structure ride through"
      (is (= 42 (get-in rendered [:auth :user-id])) "non-sensitive sibling kept")
      (is (= {:open? true :tab :home} (:ui rendered)) "unrelated subtree kept"))

    (testing "(2) the frame-declared LARGE slot is NOT elided on-box — the
              local operator sees big values; only secrets are withheld
              (the include-large? overlay)"
      (is (= (vec (range 300)) (get-in rendered [:catalog :rows]))
          "a large value renders raw on-box (display-only size bounding is
           the edn-inspector's job, not an egress redaction)"))))

;; ---------------------------------------------------------------------------
;; 3. per-frame — projection uses the OBSERVED frame's policy, not a borrowed one.
;; ---------------------------------------------------------------------------

(deftest local-render-applies-the-observed-frames-policy
  (testing "under the SECURE frame the sensitive token redacts"
    (is (= :rf/redacted
           (get-in (local-render/local-render-value app-db-value secure-frame)
                   [:auth :token]))))
  (testing "under the PLAIN frame (no sensitive decl) the SAME value renders
            verbatim — the policy is per-frame, applied from the observed
            frame, never borrowed or ambient"
    (is (= "secret-session-jwt-abc123"
           (get-in (local-render/local-render-value app-db-value plain-frame)
                   [:auth :token]))
        "no sensitive decl on :app/plain ⇒ the value renders raw")))

;; ---------------------------------------------------------------------------
;; 4. `:rf.egress/local-raw` opt-in — the trusted-local per-(tool,frame) grain.
;; ---------------------------------------------------------------------------

(deftest local-raw-opt-in-reveals-sensitive
  (testing "the redacted default suppresses the sensitive slot"
    (is (= :rf/redacted
           (get-in (local-render/local-render-value app-db-value secure-frame false)
                   [:auth :token]))))
  (testing "the explicit trusted-local :rf.egress/local-raw grain (raw? true)
            reveals it — an operator act, not the process-global default"
    (is (= "secret-session-jwt-abc123"
           (get-in (local-render/local-render-value app-db-value secure-frame true)
                   [:auth :token]))
        "raw? true resolves to :rf.egress/local-raw which includes sensitive"))
  (testing "the profile resolver maps the grain to the named boundary"
    (is (= :rf.egress/local-redacted (local-render/local-render-profile false)))
    (is (= :rf.egress/local-redacted (local-render/local-render-profile nil)))
    (is (= :rf.egress/local-raw (local-render/local-render-profile true)))))

;; ---------------------------------------------------------------------------
;; 5. fail-closed — an UNREACHABLE observed frame redacts the WHOLE value.
;; ---------------------------------------------------------------------------

(deftest local-render-fails-closed-on-unreachable-frame
  (testing "projecting under an unknown / destroyed observed frame redacts the
            whole value to :rf/redacted rather than ship it raw under no policy"
    (is (= :rf/redacted
           (local-render/local-render-value app-db-value :app/does-not-exist))
        "frameless local-redacted render fails closed (no :frame opt ⇒ the
         walker's frameless redact-whole branch)"))
  (testing "a nil observed frame likewise fails closed"
    (is (= :rf/redacted (local-render/local-render-value app-db-value nil))))
  #?(:clj
     (testing "the live frame is carried as the :frame opt; an unreachable one is
               stamped with a NON-NIL dead-frame sentinel (NOT omitted / nil) so
               the walker takes its unresolvable-frame fail-closed branch rather
               than fall through to the ambient frame (rf2-cra0nq)"
       (is (= secure-frame (:frame (local-render-opts* secure-frame))))
       (let [unreachable-opts (local-render-opts* :app/does-not-exist)
             nil-opts         (local-render-opts* nil)]
         (is (contains? unreachable-opts :frame)
             "an unreachable frame MUST still stamp :frame (a sentinel), never omit it")
         (is (some? (:frame unreachable-opts))
             "the stamped :frame is NON-NIL — a nil/absent :frame borrows the ambient frame")
         (is (not= :app/does-not-exist (:frame unreachable-opts))
             "the stamped :frame is a sentinel, not the unreachable id itself")
         (is (contains? nil-opts :frame)
             "a nil frame-id MUST stamp the sentinel, not leave :frame nil/absent")
         (is (some? (:frame nil-opts))
             "the nil-frame :frame opt is the NON-NIL sentinel"))
       (is (= :rf.egress/local-redacted
              (:rf.egress/profile (local-render-opts* secure-frame))))
       (is (true? (:rf.size/include-large? (local-render-opts* secure-frame)))
           "the keep-large overlay is always present"))))

;; ---------------------------------------------------------------------------
;; 5b. THE AMBIENT-BORROW ARM — fail-closed EVEN WHEN an ambient frame is bound
;;     (rf2-cra0nq, mirroring the off-box derivation-graph fix rf2-udkj69).
;;
;; The §5 arm runs under the fixture's `:ambient-frame nil`, so the absent-:frame
;; path resolves NO frame and trivially fails closed — it never exercised the
;; ambient-BORROW leak. Here we dynamically bind an ambient frame (`:app/plain`,
;; which declares NO sensitive policy, so a borrow WOULD ship the token RAW) and
;; assert a nil / unreachable observed frame still redacts the whole value rather
;; than borrow `:app/plain`'s empty policy and leak the secret.
;; ---------------------------------------------------------------------------

(deftest local-render-fails-closed-under-bound-ambient-frame
  (rf/with-frame plain-frame
    (is (some? (rf.frame/resolve-current-frame))
        "PRECONDITION — an ambient frame IS dynamically bound, so an absent /
         nil :frame opt WOULD resolve it (the borrow this arm forbids)")
    (testing "a NIL observed frame redacts the whole value, NOT shipping it raw
              under the borrowed ambient :app/plain (empty) policy (rf2-cra0nq)"
      (let [rendered (local-render/local-render-value app-db-value nil)]
        (is (= :rf/redacted rendered)
            "nil frame ⇒ whole-value redact, never the borrowed-ambient identity walk")
        (is (not= "secret-session-jwt-abc123" (get-in rendered [:auth :token]))
            "the session token must NOT ride through under the borrowed ambient frame")))
    (testing "an UNREACHABLE observed frame likewise redacts under a bound ambient"
      (is (= :rf/redacted
             (local-render/local-render-value app-db-value :app/does-not-exist))
          "destroyed / never-registered frame fails closed, never borrows ambient"))
    (testing "the LOCAL-RAW opt-in still ships raw even with the sentinel — the
              operator has explicitly waived redaction (the opt-out branch precedes
              the fail-closed redact); the sentinel never over-redacts a deliberate
              raw request"
      (is (= "secret-session-jwt-abc123"
             (get-in (local-render/local-render-value app-db-value nil true)
                     [:auth :token]))
          ":rf.egress/local-raw includes-sensitive? ⇒ identity walk even frameless"))))

;; ---------------------------------------------------------------------------
;; 6. end-to-end — the App-DB Diff section-model derivation redacts a sensitive
;;    slot, so what the panel hands the edn-inspector is already projected.
;;
;; Replicates the `:rf.xray/app-db-state` sub's body exactly (project the
;; observed-frame value through `local-render-value`, then decompose into the
;; section model via `current-state-sections`) — exercising the integrated
;; project-then-section path the panel renders without standing up the full
;; reactive Xray sub graph.
;; ---------------------------------------------------------------------------

(deftest app-db-state-section-model-redacts-sensitive-under-observed-frame
  (let [observed-frame secure-frame
        ;; The exact projection the `:rf.xray/app-db-state` sub applies.
        projected (local-render/local-render-value app-db-value observed-frame)
        {:keys [top]} (h/current-state-sections projected nil)]
    (testing "the TOP (user-domain) section's sensitive slot is redacted in the
              model the panel hands to the edn-inspector"
      (is (= :rf/redacted (get-in top [:auth :token]))
          "the section model is projected through :rf.egress/local-redacted"))
    (testing "non-sensitive + large slots survive the section-model projection"
      (is (= 42 (get-in top [:auth :user-id])))
      (is (= (vec (range 300)) (get-in top [:catalog :rows]))
          "large stays on-box; only the secret is withheld")))

  (testing "under a plain frame the section model renders the value verbatim"
    (let [projected (local-render/local-render-value app-db-value plain-frame)
          {:keys [top]} (h/current-state-sections projected nil)]
      (is (= "secret-session-jwt-abc123" (get-in top [:auth :token]))
          "no sensitive decl ⇒ section model is unredacted"))))

;; ---------------------------------------------------------------------------
;; 7. THE SENTINEL-COLLISION ARM (rf2-ws60) — the dead-frame substitute must be
;;    a value NO app can register a frame under.
;;
;; Third carrier of the class fixed at `day8.re-frame2-xray.egress/no-frame`
;; (rf2-7htk7 third pass, PR #8987 commit 449bfd21c7) and at
;; `re-frame.derivation.egress` (rf2-g1vu). A `::`-namespaced keyword reads as
;; private but expands to an ORDINARY PUBLIC keyword — here
;; `:day8.re-frame2-xray.panels.local-render/no-egress-frame` — and the frame
;; registry is keyed by whatever `:id` `make-frame` is handed. So an app CAN
;; register a live frame under the literal. The stamp then RESOLVES, the walker
;; takes its LIVE-frame branch, and that frame's (empty) declaration registry
;; ships the value RAW: the fail-CLOSED stamp becomes fail-OPEN.
;;
;; Modelled on `egress-value-with-nil-frame-fails-closed-under-id-collision`
;; in `app_db_diff_cljs_test.cljs` (renamed under rf2-6r9j.24 when that proof
;; was re-pointed from the retired copy event onto `egress/egress-value`
;; directly; the sentinel it models is unchanged).
;; ---------------------------------------------------------------------------

(def ^:private colliding-sentinel-id
  "The id the `::no-egress-frame` sentinel literal expanded to before rf2-ws60.
  Spelled out in full ON PURPOSE: `::no-egress-frame` here would read as this
  namespace's keyword, not the panel's, and the point of the test is that the
  literal is a public id any app can spell."
  :day8.re-frame2-xray.panels.local-render/no-egress-frame)

(deftest local-render-fails-closed-under-sentinel-id-collision
  (testing "rf2-ws60 — an app registers a LIVE frame under the public keyword
            the dead-frame sentinel used to expand to. A nil / unreachable
            observed frame MUST still redact the whole value: a substitute that
            is itself a registrable frame id resolves to that live frame, takes
            the walker's live-frame branch, and its empty declaration registry
            ships the secret RAW"
    (rf/make-frame {:id colliding-sentinel-id})
    (try
      (testing "a NIL observed frame fails closed despite the collision"
        (let [rendered (local-render/local-render-value app-db-value nil)]
          (is (= :rf/redacted rendered)
              (str "nil observed frame must redact WHOLE even with a live frame "
                   "registered under the sentinel's id. got: " (pr-str rendered)))
          (is (not= "secret-session-jwt-abc123" (get-in rendered [:auth :token]))
              "the session token leaked through the colliding sentinel frame")))
      (testing "an UNREACHABLE observed frame likewise fails closed"
        (let [rendered (local-render/local-render-value app-db-value
                                                        :app/does-not-exist)]
          (is (= :rf/redacted rendered)
              (str "unreachable observed frame must redact WHOLE under the "
                   "collision. got: " (pr-str rendered)))
          (is (not= "secret-session-jwt-abc123" (get-in rendered [:auth :token]))
              "the session token leaked through the colliding sentinel frame")))
      #?(:clj
         (testing "the stamped :frame is NOT a value the app could have registered
                   — the structural half of the fix, so a future re-spelling as a
                   keyword is caught even if the projection happens to redact"
           (let [stamped (:frame (local-render-opts* nil))]
             (is (some? stamped) "the sentinel is still stamped, never nil/absent")
             (is (not (keyword? stamped))
                 (str "the dead-frame sentinel must not be a keyword — every "
                      "keyword is an id an app can register. got: " (pr-str stamped)))
             (is (not= colliding-sentinel-id stamped)
                 "the sentinel must not be the colliding public keyword"))))
      (finally
        ;; Never leave the colliding id live for a sibling test.
        (rf/destroy-frame! colliding-sentinel-id)))))

;; ---------------------------------------------------------------------------
;; 7c. THE PUBLIC-SURFACE INVARIANT (rf2-ws60, THIRD pass — the audit of #9056).
;;
;; This is the arm that actually pins the fix, and each earlier regression is
;; here as a record of what it could not see:
;;
;;   §7   registers the RETIRED KEYWORD and asserts structurally that the
;;        replacement is not a keyword — a test about the SHAPE of the sentinel.
;;        It stayed GREEN while the second and third passes still leaked.
;;   §7b  (below) takes the value a caller was HANDED and registers a frame
;;        under it — a test about the DURABILITY of the sentinel. It stayed
;;        GREEN while the third pass still leaked, because minting a fresh
;;        identity per call defeats the *later*-projection route and not the
;;        *same*-opts-map route: the caller registers a frame under
;;        `(:frame opts)` and hands THAT SAME opts map back to
;;        `rf/project-egress`. Reproduced at the merged commit, source
;;        untouched:
;;          {:registered? true,
;;           :projected {:auth {:token "audit-secret-jwt-9f3a"}}}
;;
;; Neither shape nor freshness is the invariant. The invariant is **does any
;; PUBLIC fn of this namespace RETURN a structure containing the sentinel** —
;; the question both sibling carriers answer NO to (`egress-value` builds its
;; opts INLINE as the walker's argument; `project-graph` binds `walk-opts` in a
;; `let` and returns the transformed graph). So `local-render-opts` is PRIVATE,
;; and what this arm pins is exactly that: no opts map crosses the namespace
;; boundary, under that name or any other. It fails on all three pre-fix
;; sources, where the builder was public.
;;
;; JVM lane only, because it reads the namespace's var table. The source is one
;; shared `.cljc` definition, so a CLJS-lane copy would pin nothing further.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest the-opts-map-carrying-the-sentinel-has-no-public-exit
     (let [publics (ns-publics 'day8.re-frame2-xray.panels.local-render)]
       (testing "rf2-ws60 — the opts BUILDER is private, so nothing outside the
                 namespace can obtain a dead-frame sentinel to register a live
                 frame under, nor an opts map to replay against project-egress"
         (is (nil? (get publics 'local-render-opts))
             (str "local-render-opts must NOT be public: it is the one structure "
                  "that carries a dead-frame sentinel, and a caller holding it "
                  "can register a frame under (:frame opts) and hand the SAME "
                  "map back to rf/project-egress — the #9056 leak.")))

       (testing "POSITIVE CONTROL — the projection seam itself IS public, so a
                 green above is coverage rather than an empty namespace"
         (doseq [sym '[local-render-value local-render-value-at
                       local-render-profile local-redacted-profile
                       local-raw-profile]]
           (is (contains? publics sym)
               (str "the public seam lost " sym " — the invariant above would "
                    "then be passing vacuously"))))

       (testing "and the invariant stated GENERALLY, so a re-exposed builder
                 under a DIFFERENT name is caught too: no public fn of this
                 namespace returns a project-egress opts map"
         (doseq [[sym v] publics
                 :when   (fn? (deref v))
                 arity   [1 2 3]
                 :let    [args (repeat arity nil)
                          ret  (try (apply (deref v) args)
                                    (catch Throwable _ ::not-applicable))]
                 :when   (not= ::not-applicable ret)]
           (is (not (and (map? ret) (contains? ret :frame)))
               (str "public fn " sym " (arity " arity ") returned a map carrying "
                    "a :frame opt: " (pr-str ret) ". An opts map must never "
                    "cross the namespace boundary (rf2-ws60).")))))))

;; ---------------------------------------------------------------------------
;; 7b. THE CALLER-VISIBLE ARM (rf2-ws60, SECOND pass — the audit of PR #9044).
;;
;; Retained as DEFENCE IN DEPTH beneath §7c, not as the invariant. It pins the
;; property that keeps any future escape harmless: each call MINTS A FRESH
;; identity, so a sentinel obtained once governs one projection and no other.
;; Against the second-pass source (one `(Object.)` on a shared `^:private def`)
;; this failed with
;;   {:same-sentinel? true, :registered? true,
;;    :rendered {:auth {:token "secret-session-jwt-abc123"}}}
;;
;; JVM lane only now — the sentinel is no longer obtainable from the public
;; surface, which is the point of §7c, so there is nothing for a CLJS-lane copy
;; of these arms to reach.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest local-render-fails-closed-when-a-frame-is-registered-under-a-leaked-sentinel
     (testing "rf2-ws60 — a frame is registered under a sentinel obtained from
               one opts build. A nil / unreachable observed frame MUST still
               redact the whole value on every LATER projection"
       (let [leaked (:frame (local-render-opts* nil))]
         (rf/make-frame {:id leaked})
         (try
           (testing "a NIL observed frame fails closed despite the registration"
             (let [rendered (local-render/local-render-value app-db-value nil)]
               (is (= :rf/redacted rendered)
                   (str "nil observed frame must redact WHOLE even with a live "
                        "frame registered under a previously minted sentinel. "
                        "got: " (pr-str rendered)))
               (is (not= "secret-session-jwt-abc123" (get-in rendered [:auth :token]))
                   "the session token leaked through the registered sentinel")))

           (testing "an UNREACHABLE observed frame likewise fails closed"
             (let [rendered (local-render/local-render-value app-db-value
                                                             :app/does-not-exist)]
               (is (= :rf/redacted rendered)
                   (str "unreachable observed frame must redact WHOLE. got: "
                        (pr-str rendered)))))

           (testing "the PATH-AWARE sibling shares the seam, so it fails closed too"
             (let [rendered (local-render/local-render-value-at
                              (:auth app-db-value) nil [:auth])]
               (is (= :rf/redacted rendered)
                   (str "local-render-value-at must redact WHOLE under the same "
                        "registration. got: " (pr-str rendered)))))

           (finally
             (rf/destroy-frame! leaked)))))))

#?(:clj
   (deftest dead-frame-sentinel-is-single-use-per-projection
     (testing "rf2-ws60 — each call MINTS A FRESH identity, so a sentinel that
               somehow escaped governs no other projection. A shared singleton
               is the second-pass defect, so assert the values are DISTINCT"
       (let [a (:frame (local-render-opts* nil))
             b (:frame (local-render-opts* nil))
             c (:frame (local-render-opts* :app/does-not-exist))]
         (is (some? a) "the sentinel is still stamped, never nil/absent")
         (is (not (identical? a b))
             (str "two nil-frame opts must NOT share one dead-frame sentinel — a "
                  "reusable identity is registrable by whoever receives it. got: "
                  (pr-str a)))
         (is (not= a b)
             "the two sentinels must not be equal either (identity, not a datum)")
         (is (not (identical? a c))
             "the nil-frame and unreachable-frame sentinels must differ too")))

     (testing "a LIVE frame is still carried verbatim — minting applies only to the
               unreachable branch, so the reachable hot path is unchanged"
       (is (= secure-frame (:frame (local-render-opts* secure-frame))))
       (is (identical? secure-frame
                       (:frame (local-render-opts* secure-frame)))))))
