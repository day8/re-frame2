(ns re-frame.security.public-profile-projection-security-cljs-test
  "Adversarial tests for the public egress projection boundary.

  `project-egress` applies a frame's durable path classifications to direct
  reads and record values. Redacted profiles replace sensitive leaves and
  structurally elide large leaves; sensitive classification wins when both
  apply. Missing or unknown frame policy fails closed unless the caller uses
  the explicit trusted-local raw opt-in.

  This boundary is distinct from MCP trace plumbing: `strip-sensitive` drops
  stamped trace events and `scrub-snapshot` walks trace and epoch slices, but
  neither projects snapshot `:app-db` values."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.frame :as frame]
            ;; Used only to contrast trace plumbing with public projection.
            [re-frame.mcp-base.sensitive :as sens]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]
            [re-frame.security.gen :as gen]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

(def ^:private sentinel "S3CR3T-rf2-3cfvt-PUBLIC-PROFILE-DO-NOT-SHIP")

(defn- contains-sentinel?
  "True when the sentinel survives anywhere in `x` (substring scan)."
  [x]
  (gen/contains-string? x sentinel))

(def ^:private big-string (apply str (repeat 40000 \x)))

(defn- mk-frame! [frame-id]
  (rf/make-frame {:id frame-id})
  ;; Install the durable app-db classification through the commit-plane path.
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt
               {:sensitive [[:auth :token]]
                :large     [[:docs :blob]]}))))

(defn- app-db-value []
  {:auth   {:token sentinel}
   :docs   {:blob big-string}
   :public {:count 3}})

(deftest off-box-profiles-redact-frame-owned-app-db
  (testing "project-egress under each off-box / redacted profile redacts the
            :sensitive :app-db leaf and elides the :large :app-db leaf under
            frame-owned durable classification (the post-EP-0025 commit-plane
            contract, NOT retired frame config)"
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

(deftest no-frame-egress-fails-closed
  (testing "project-egress with no live frame redacts the whole value — no
            :rf/default synthesis"
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
            permissive identity walk"
    ;; :pub/never-registered is never make-frame'd — its registry is unreachable.
    (binding [frame/*current-frame* nil]
      (let [out (rf/project-egress (app-db-value)
                  {:frame :pub/never-registered
                   :rf.egress/profile :rf.egress/off-box-tool})]
        (is (gen/redacted? out)
            "an unknown / destroyed frame fails closed, identically to frameless")
        (is (not (contains-sentinel? out)))))))

(deftest large-app-db-elides-to-structural-marker
  (testing "a classified :large :app-db leaf elides to a structural marker —
            the off-box-tool profile carries the indicator, no raw bytes"
    (rf/make-frame {:id :pub/large})
    (frame/swap-runtime-db! :pub/large
      (fn [rt] (elision/apply-classification-effects rt {:large [[:upload]]})))
    (let [out (rf/project-egress {:upload big-string :public "ok"}
                {:frame :pub/large :rf.egress/profile :rf.egress/off-box-tool})]
      (is (gen/large-marker? (:upload out)) "the large leaf is a structural marker")
      (is (not= big-string (:upload out)) "the raw large value is NOT shipped")
      (is (= "ok" (:public out))))))

(deftest sensitive-wins-over-large-at-projection
  (testing "a path declared BOTH :sensitive and :large redacts, never
            large-elides — so NO path/size/digest marker can leak for it"
    (rf/make-frame {:id :pub/both})
    (frame/swap-runtime-db! :pub/both
      (fn [rt] (elision/apply-classification-effects rt
                 {:sensitive [[:secret]]
                  :large     [[:secret]]})))
    (let [out (rf/project-egress {:secret big-string}
                {:frame :pub/both
                 :rf.egress/profile :rf.egress/off-box-observability})]
      (is (gen/redacted? (:secret out)) "the both-marked path is :rf/redacted")
      (is (not (gen/large-marker? (:secret out)))
          "NO large marker — no path/size/digest can leak for a sensitive path"))))

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
                        ;; Each draw reuses the frame, so clear the append-only
                        ;; classification slot before installing the new path.
                       (rf/make-frame {:id fid})
                       (frame/swap-runtime-db! fid
                         (fn [rt]
                           (-> (dissoc rt :rf.runtime/elision)
                               (elision/apply-classification-effects {:sensitive [path]}))))
                       (let [out (rf/project-egress value
                                   {:frame fid
                                    :rf.egress/profile :rf.egress/off-box-tool})]
                         (and (gen/redacted? (get-in out path))
                              (not (contains-sentinel? out)))))))]
      (is (nil? result)
          (str "a declared-sensitive path leaked the sentinel: "
               (pr-str (when result (dissoc result :threw))))))))

(deftest snapshot-app-db-plumbing-vs-public-boundary
  (testing "scrub-snapshot leaves :app-db raw (narrow plumbing) BUT
            project-egress of that :app-db redacts under the frame policy
            at the public boundary"
    (mk-frame! :pub/snap)
    (let [snapshot {:pub/snap {:traces []
                               :epochs []
                               :app-db (app-db-value)}}
          ;; scrub-snapshot is a trace/epoch-only walker.
          [scrubbed _dropped] (sens/scrub-snapshot snapshot false)
          raw-app-db (get-in scrubbed [:pub/snap :app-db])]
      (is (= sentinel (get-in raw-app-db [:auth :token]))
          "PLUMBING: scrub-snapshot leaves :app-db raw (trace/epoch-only by design)")
      (is (contains-sentinel? raw-app-db)
          "PLUMBING: the snapshot :app-db still carries the sentinel — this is
           NOT the public egress boundary")
      ;; Tool and direct-read consumers project :app-db before off-box egress.
      (let [projected (rf/project-egress raw-app-db
                        {:frame :pub/snap
                         :rf.egress/profile :rf.egress/off-box-tool})]
        (is (gen/redacted? (get-in projected [:auth :token]))
            "PUBLIC: project-egress of the snapshot :app-db redacts the sensitive leaf")
        (is (gen/large-marker? (get-in projected [:docs :blob]))
            "PUBLIC: project-egress elides the large leaf")
        (is (not (contains-sentinel? projected))
            "PUBLIC: no sentinel crosses the projected boundary")))))

(deftest narrow-plumbing-strip-sensitive-is-trace-event-only
  (testing "PLUMBING ONLY — strip-sensitive drops top-level :sensitive?-stamped
            trace EVENTS, but it is NOT the durable app-db egress boundary: a
            raw app-db-shaped map with no :sensitive? stamp passes UNTOUCHED.
            Durable app-db egress uses frame-owned project-egress."
    (let [[kept dropped] (sens/strip-sensitive
                           [{:operation :x :sensitive? true :tags {:value sentinel}}]
                           false)]
      (is (= [] kept) "PLUMBING: a stamped trace event is dropped")
      (is (= 1 dropped)))
    ;; An unstamped app-db-shaped map is not a trace event the filter can classify.
    (let [raw {:auth {:token sentinel}}
          [kept _dropped] (sens/strip-sensitive [raw] false)]
      (is (= [raw] kept)
          "PLUMBING: strip-sensitive does NOT redact unstamped app-db data —
           the durable boundary is frame-owned project-egress")
      (is (contains-sentinel? kept)))))
