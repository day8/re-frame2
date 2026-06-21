(ns re-frame.security.public-profile-projection-security-cljs-test
  "Security tier — EP-0015 PUBLIC egress projection model (the rf2-3cfvt
  adversarial regime, applied to the graduated public projection surface).

  ## Why this surface

  The sibling `mcp-egress-security` namespace exercises the LOW-LEVEL mark /
  elision PLUMBING (`re-frame.mcp-base.sensitive/strip-sensitive` +
  `scrub-snapshot`) — the trace-event drop filter and the per-frame snapshot
  walker. Those are necessary but NOT the EP-0015 public egress model: an
  MCP / direct-read / tool snapshot that ships RAW frame-owned app-db data
  would still pass the `strip-sensitive` gate (it only drops top-level
  `:sensitive?`-stamped trace EVENTS) and the `scrub-snapshot` walker (which
  leaves `:app-db` verbatim BY DESIGN — read-time scrubbing is trace/epoch-
  only). The full off-box boundary is `re-frame.core/project-egress`
  (EP-0015 §10/§11) over a frame's `reg-frame` durable classification
  (EP-0015 §3). Without a security-tier guard on THAT surface, the tier can
  stay green while a tool / direct read leaks raw owner-local data through
  the projected boundary.

  ## What this drives (the PUBLIC EP-0015 surfaces)

    - `reg-frame` `:sensitive`/`:large` `{:app-db …}` durable frame policy
      (EP-0015 §3 — frame-owned, NOT schema-declared live-value redaction);
    - `re-frame.core/project-egress` under the off-box-tool / off-box-
      observability / local-redacted profiles (the AI-tool + hosted-
      monitoring + on-box-redacted boundaries) — sensitive → `:rf/redacted`,
      large → a structural marker, unmarked siblings pass;
    - FAIL-CLOSED: a no-frame / unknown-frame egress redacts the WHOLE value
      (no `:rf/default` synthesis), the deliberate `:rf.size/include-
      sensitive? true` opt-out being the single control point;
    - large elision + the install-time sensitive-WINS-over-large rule (a
      both-marked path redacts, never large-elides — so no path / byte size /
      digest can leak);
    - the SNAPSHOT boundary: alongside the low-level `scrub-snapshot`
      `:app-db`-stays-raw plumbing assertion, a HIGHER-LEVEL test that
      `project-egress` of the same snapshot's `:app-db` DOES redact — so the
      security tier cannot false-green the full boundary on the back of the
      intentionally-narrow plumbing helper.

  ## Threat model

  AI/MCP boundary + logs ONLY (the rf2-3cfvt / rf2-o69h5 / rf2-zsm03 scope).
  The redaction boundary anchored here is the off-box `project-egress` site
  over frame-owned classification. Human-facing on-box egress is out of scope.

  ## Net property (verify-by-revert)

  Reverting `re-frame.frame-classification/install!` (so `reg-frame` no
  longer installs the durable `:source :frame` declarations) makes the
  framed projection tests go RED — `project-egress` ships the sensitive leaf
  raw. Reverting the install-time sensitive-wins drop makes
  `sensitive-wins-over-large-at-projection` go RED (a large marker, leaking
  path/size/digest, surfaces for a sensitive path). Confirmed by temporary
  local revert + restore (see PR Quality gates)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.classification :as classification]
            ;; The low-level plumbing helper, exercised ONLY in the labelled
            ;; narrow plumbing test below — NOT as a stand-in for the public
            ;; EP-0015 boundary.
            [re-frame.mcp-base.sensitive :as sens]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]
            [re-frame.security.gen :as gen]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     ;; EP-0025: classification is derived from the registrar + the per-frame
     ;; elision registry (reset by frame teardown), so this is a no-op kept for
     ;; directory symmetry.
     :init-fn (fn [] (classification/clear-classification!))}))

(def ^:private sentinel "S3CR3T-rf2-3cfvt-PUBLIC-PROFILE-DO-NOT-SHIP")

(defn- contains-sentinel?
  "Deep-walk `x`; true when the sentinel appears ANYWHERE (value, nested,
  stringified). The contract is \"the sentinel never survives the boundary.\"
  Thin wrapper over the shared `gen/contains-string?` (rf2-n5bkm7); the
  sentinel is matched as an EXACT string leaf (`exact? true`)."
  [x]
  (gen/contains-string? x sentinel true))

(def ^:private big-string (apply str (repeat 40000 \x)))

(defn- mk-frame! [frame-id]
  (rf/reg-frame frame-id
    {:sensitive {:app-db [[:auth :token]]}
     :large     {:app-db [[:docs :blob]]}}))

(defn- app-db-value []
  {:auth   {:token sentinel}
   :docs   {:blob big-string}
   :public {:count 3}})

;; ---------------------------------------------------------------------------
;; PROPERTY 1 — the public off-box profiles redact frame-owned sensitive app-db
;; data and elide frame-owned large app-db data. THIS is the EP-0015 boundary,
;; over reg-frame durable classification, NOT a low-level mark helper.
;; ---------------------------------------------------------------------------

(deftest off-box-profiles-redact-frame-owned-app-db
  (testing "project-egress under each off-box / redacted profile redacts the
            reg-frame :sensitive :app-db leaf and elides the :large :app-db
            leaf — frame-owned durable classification, EP-0015 §3 + §10"
    (mk-frame! :pub/offbox)
    (doseq [profile [:rf.egress/off-box-tool
                     :rf.egress/off-box-observability
                     :rf.egress/local-redacted]]
      (let [out (rf/project-egress (app-db-value)
                  {:frame :pub/offbox :rf.egress/profile profile})]
        (is (gen/redacted? (get-in out [:auth :token]))
            (str profile ": frame-owned sensitive app-db leaf redacted"))
        (is (gen/large-marker? (get-in out [:docs :blob]))
            (str profile ": frame-owned large app-db leaf elided to a marker"))
        (is (= 3 (get-in out [:public :count]))
            (str profile ": unmarked sibling passes through"))
        (is (not (contains-sentinel? out))
            (str profile ": the sensitive value never crosses the boundary"))))))

(deftest local-raw-is-the-trusted-boundary
  (testing "the trusted-local profile is the ONE boundary that opts sensitive
            AND large back in (the operator's deliberate raw-egress choice)"
    (mk-frame! :pub/raw)
    (let [out (rf/project-egress (app-db-value)
                {:frame :pub/raw :rf.egress/profile :rf.egress/local-raw})]
      (is (= sentinel (get-in out [:auth :token])) "trusted-local sees sensitive")
      (is (= big-string (get-in out [:docs :blob])) "trusted-local sees large raw"))))

;; ---------------------------------------------------------------------------
;; PROPERTY 2 — FAIL-CLOSED. No frame and unknown/destroyed frame both redact
;; the WHOLE value (no :rf/default synthesis, no borrowing another frame's
;; policy). The deliberate include-sensitive? opt-out is the single control.
;; ---------------------------------------------------------------------------

(deftest no-frame-egress-fails-closed
  (testing "project-egress with no live frame redacts the whole value — no
            :rf/default synthesis (EP-0002 / Spec 015 §Direct reads)"
    ;; Rebind the ambient frame AWAY so the egress is genuinely frameless.
    (binding [frame/*current-frame* nil]
      (let [out (rf/project-egress (app-db-value)
                  {:rf.egress/profile :rf.egress/off-box-tool})]
        (is (gen/redacted? out) "frameless egress fails closed to :rf/redacted")
        (is (not (contains-sentinel? out))))
      ;; The deliberate opt-out: include-sensitive? true gets an identity walk
      ;; against the no-frame policy (the single control point).
      (let [out (rf/project-egress {:auth {:token sentinel}}
                  {:rf.size/include-sensitive? true})]
        (is (= sentinel (get-in out [:auth :token]))
            "explicit include-sensitive? true is the deliberate frameless opt-out")))))

(deftest unknown-frame-egress-fails-closed
  (testing "project-egress naming an UNREGISTERED frame fails closed — an
            unresolvable frame's empty registry must NOT fall through to a
            permissive identity walk (EP-0015 issue 1, rf2-t55hxg.18)"
    ;; :pub/never-registered is never reg-frame'd — its registry is unreachable.
    (binding [frame/*current-frame* nil]
      (let [out (rf/project-egress (app-db-value)
                  {:frame :pub/never-registered
                   :rf.egress/profile :rf.egress/off-box-tool})]
        (is (gen/redacted? out)
            "an unknown / destroyed frame fails closed, identically to frameless")
        (is (not (contains-sentinel? out)))))))

;; ---------------------------------------------------------------------------
;; PROPERTY 3 — large elision + sensitive-WINS-over-large. A both-marked path
;; redacts (never large-elides), so no path / byte-size / digest / handle can
;; leak for a sensitive path (EP-0015 §3, install-time complement).
;; ---------------------------------------------------------------------------

(deftest large-app-db-elides-to-structural-marker
  (testing "a frame-owned :large :app-db leaf elides to a structural marker —
            the off-box-tool profile carries the indicator, no raw bytes"
    (rf/reg-frame :pub/large {:large {:app-db [[:upload]]}})
    (let [out (rf/project-egress {:upload big-string :public "ok"}
                {:frame :pub/large :rf.egress/profile :rf.egress/off-box-tool})]
      (is (gen/large-marker? (:upload out)) "the large leaf is a structural marker")
      (is (not= big-string (:upload out)) "the raw large value is NOT shipped")
      (is (= "ok" (:public out))))))

(deftest sensitive-wins-over-large-at-projection
  (testing "a path declared BOTH :sensitive and :large redacts, never
            large-elides — so NO path/size/digest marker can leak for it"
    (rf/reg-frame :pub/both
      {:sensitive {:app-db [[:secret]]}
       :large     {:app-db [[:secret]]}})
    (let [out (rf/project-egress {:secret big-string}
                {:frame :pub/both
                 :rf.egress/profile :rf.egress/off-box-observability})]
      (is (gen/redacted? (:secret out)) "the both-marked path is :rf/redacted")
      (is (not (gen/large-marker? (:secret out)))
          "NO large marker — no path/size/digest can leak for a sensitive path"))))

;; ---------------------------------------------------------------------------
;; PROPERTY 4 (generated) — across frames classifying a RANDOM app-db path
;; sensitive, project-egress under an off-box profile never ships the sentinel
;; planted at that path.
;; ---------------------------------------------------------------------------

(def ^:private gen-path
  "A 1..3-segment keyword app-db path."
  (gen/gen-vec (gen/gen-int 1 4)
               (gen/gen-elem [:a :b :c :auth :token :tenant :user :data])))

(deftest framed-corpus-redacts-declared-path
  (testing "for 200 random frames classifying a random app-db path sensitive,
            project-egress redacts the sentinel planted at that path"
    (let [result (gen/for-all
                   gen-path 200 41
                   (fn [path]
                     (let [path  (vec path)
                           fid   :pub/gen
                           value (assoc-in {} path sentinel)]
                       ;; Re-register the frame with this draw's sensitive path.
                       (rf/reg-frame fid {:sensitive {:app-db [path]}})
                       (let [out (rf/project-egress value
                                   {:frame fid
                                    :rf.egress/profile :rf.egress/off-box-tool})]
                         (and (gen/redacted? (get-in out path))
                              (not (contains-sentinel? out)))))))]
      (is (nil? result)
          (str "a declared-sensitive path leaked the sentinel: "
               (pr-str (when result (dissoc result :threw))))))))

;; ---------------------------------------------------------------------------
;; PROPERTY 5 — the SNAPSHOT boundary, BOTH layers. The low-level plumbing
;; (scrub-snapshot) leaves :app-db raw BY DESIGN; the PUBLIC EP-0015 boundary
;; (project-egress) over the SAME app-db DOES redact. Asserting both alongside
;; each other means the security tier cannot false-green the full boundary on
;; the back of the intentionally-narrow plumbing helper.
;; ---------------------------------------------------------------------------

(deftest snapshot-app-db-plumbing-vs-public-boundary
  (testing "scrub-snapshot leaves :app-db raw (narrow plumbing) BUT
            project-egress of that :app-db redacts under the frame policy
            (the public EP-0015 boundary) — the tier guards the full boundary"
    (mk-frame! :pub/snap)
    (let [snapshot {:pub/snap {:traces []
                               :epochs []
                               :app-db (app-db-value)}}
          ;; (a) NARROW PLUMBING — scrub-snapshot is a trace/epoch-only walker;
          ;; it intentionally leaves :app-db verbatim (NOT the public boundary).
          [scrubbed _dropped] (sens/scrub-snapshot snapshot false)
          raw-app-db (get-in scrubbed [:pub/snap :app-db])]
      (is (= sentinel (get-in raw-app-db [:auth :token]))
          "PLUMBING: scrub-snapshot leaves :app-db raw (trace/epoch-only by design)")
      (is (contains-sentinel? raw-app-db)
          "PLUMBING: the snapshot :app-db still carries the sentinel — this is
           NOT the public egress boundary")
      ;; (b) PUBLIC BOUNDARY — the tool/direct-read MUST run project-egress on
      ;; the :app-db before it crosses off-box. That DOES redact.
      (let [projected (rf/project-egress raw-app-db
                        {:frame :pub/snap
                         :rf.egress/profile :rf.egress/off-box-tool})]
        (is (gen/redacted? (get-in projected [:auth :token]))
            "PUBLIC: project-egress of the snapshot :app-db redacts the sensitive leaf")
        (is (gen/large-marker? (get-in projected [:docs :blob]))
            "PUBLIC: project-egress elides the large leaf")
        (is (not (contains-sentinel? projected))
            "PUBLIC: no sentinel crosses the projected boundary")))))

;; ---------------------------------------------------------------------------
;; NARROW PLUMBING TEST (labelled) — the low-level mark / strip helpers are
;; NOT the EP-0015 public boundary; they remain pinned only as plumbing.
;; ---------------------------------------------------------------------------

(deftest narrow-plumbing-strip-sensitive-is-trace-event-only
  (testing "PLUMBING ONLY — strip-sensitive drops top-level :sensitive?-stamped
            trace EVENTS, but it is NOT the durable app-db egress boundary: a
            raw app-db-shaped map with no :sensitive? stamp passes UNTOUCHED.
            (Durable app-db egress is frame-owned project-egress, above.)"
    ;; A top-level :sensitive?-stamped trace event drops (the gate's job).
    (let [[kept dropped] (sens/strip-sensitive
                           [{:operation :x :sensitive? true :tags {:value sentinel}}]
                           false)]
      (is (= [] kept) "PLUMBING: a stamped trace event is dropped")
      (is (= 1 dropped)))
    ;; But an app-db-shaped map WITHOUT a :sensitive? stamp is NOT a trace
    ;; event the gate classifies — it passes through raw. This is precisely
    ;; why the public boundary (project-egress, frame-owned) is the real guard.
    (let [raw {:auth {:token sentinel}}
          [kept _dropped] (sens/strip-sensitive [raw] false)]
      (is (= [raw] kept)
          "PLUMBING: strip-sensitive does NOT redact unstamped app-db data —
           the durable boundary is frame-owned project-egress")
      (is (contains-sentinel? kept)))))
