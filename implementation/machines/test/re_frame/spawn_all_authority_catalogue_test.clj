(ns re-frame.spawn-all-authority-catalogue-test
  "The AUTHORITATIVE catalogue/schema proof for `:spawn-all` exact authority
  (rf2-p53o47, completed rf2-2ai15g).

  This pins the normative facts the spec/catalogue reconciliation landed so a
  regression FAILS here rather than drifting the spec silently. Every schema
  assertion validates runtime-produced records against the EXECUTABLE canonical
  schema EXTRACTED from `spec/Spec-Schemas.md` (via
  `re-frame.spawn-all-schema-extract`), and every catalogue assertion is pinned
  to a NARROW read of the actual `spec/009-Instrumentation.md` row — so
  weakening the authoritative document is what turns a test red:

    1. SCHEMA — the `:spawned` slot union has THREE legal runtime arms
       (Spec-Schemas §`Machines`): the `:spawn` leaf keyword, the LIVE
       child-bearing `InvokeAllJoinState` (with `:rf/attempt` REQUIRED — a
       token-less join is not a valid live shape; `join.cljc`'s fold gate
       requires a non-nil exact match), and the childless REJECT sentinel
       `InvokeAllRejectedState` (`{:closed true}`, only `:rf/spawn-all-rejected?
       true`). A real unregistered-child `:spawn-all` fixture validates the
       COMPLETE `:rf.runtime/machines` runtime-db against the extracted
       `Machines` form during the legal pre-parent-exit interval; a sentinel
       carrying `:children`/authority, and a token-less live join, both fail.

    2. CATALOGUE — `:rf.machine.spawn-all/child-completed` emits
       `:rf.reply/correlation` (Spec 009 detailed row + `join.cljc`), carrying
       the fold's parent/invoke/child/spawned identity.

    3. CATALOGUE — the spawn-all completion ops + their classifier key do not
       drift: the POST-resolution straggler fires
       `:rf.machine.spawn-all/late-completion` (Spec 009 quick index + detailed
       row) classified by `:rf.reply/stale-reason`
       `:rf.machine.spawn-all/join-resolved` (a stale-reason VALUE, never an
       operation); the PRE-resolution suppression fires
       `:rf.machine.spawn-all/stale-completion` also classified by
       `:rf.reply/stale-reason` (NOT the bare `:reason`)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            ;; load the machines artefact so its fx handlers + late-bind
            ;; hooks are installed when this ns runs in isolation.
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.spawn-all-schema-extract :as extract]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     ;; the reject fixture fans an always-on `:rf.error/machine-spawn-
     ;; unregistered-type` record; clear the always-on listener registry so a
     ;; leaked listener from an earlier test cannot see it.
     :init-fn (fn [] (error-emit/clear-error-listeners!))})
  mtest/trace-capture-fixture)

;; The canonical `:spawn-all` runtime-db schema forms, EXTRACTED from
;; spec/Spec-Schemas.md (rf2-2ai15g) — NOT hand-copied. Removing / renaming a
;; def, or weakening `:rf/attempt` to `{:optional true}`, or dropping the
;; `InvokeAllRejectedState` arm from the markdown is a test-build / assertion
;; failure here, so the schema surface and the document cannot drift silently.
(def ^:private InvokeAllJoinState     (extract/canonical-invoke-all-join-schema))
(def ^:private InvokeAllRejectedState (extract/canonical-invoke-all-rejected-schema))
(def ^:private SpawnedSlot            (extract/canonical-spawned-slot-schema))
(def ^:private Machines               (extract/canonical-machines-schema))

(defn- machines-runtime-db
  "The frame's live `:rf.runtime/machines` runtime-db partition value."
  []
  (get (mtest/runtime-db) :rf.runtime/machines))

(defn- join-state [parent-id invoke-id]
  (get-in (mtest/runtime-db)
          [:rf.runtime/machines :spawned parent-id invoke-id]))

(defn- mk-child
  "A dispatching child: on `:go` / `:fail` it transitions to a PLAIN
  (non-`:final?`) terminal and dispatches its completion back to
  `parent-id` THROUGH its own handler boundary, so the runtime stamps the
  `:rf/join-auth` attempt authentication (`stamp-join-completion-fx`)."
  [parent-id]
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{data :data ev :event}]
                              {:data (assoc data :id (second ev))})
             :dispatch-done (fn [{data :data}]
                              {:fx [[:dispatch [parent-id [:child/done (:id data)]]]]})
             :dispatch-err  (fn [{data :data}]
                              {:fx [[:dispatch [parent-id [:child/failed (:id data)]]]]})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done   :action :dispatch-done}
                            :fail   {:target :failed :action :dispatch-err}}}
             :done   {}
             :failed {}}})

(defn- reg-join-parent!
  "Register a two-child join parent (mode + resolution keys via
  `spawn-all-extra`) + dispatching children and start it. The parent stays
  on `:racing` at resolution (no `:on` for the resolution events), so the
  join slot survives for post-resolution probes. Returns the seeded join
  state."
  [parent-kw child-a-kw child-b-kw spawn-all-extra]
  (rf/reg-machine child-a-kw (mk-child parent-kw))
  (rf/reg-machine child-b-kw (mk-child parent-kw))
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all
                        (merge
                          {:children       [{:id :a :machine-id child-a-kw :start [:set-id :a]}
                                            {:id :b :machine-id child-b-kw :start [:set-id :b]}]
                           :on-child-done  :child/done
                           :on-child-error :child/failed}
                          spawn-all-extra)}}})
  (rf/dispatch-sync [parent-kw [:start]])
  (join-state parent-kw [:racing]))

;; ---------------------------------------------------------------------------
;; 1. SCHEMA — `:rf/attempt` is REQUIRED on a live child-bearing join
;; ---------------------------------------------------------------------------

(deftest live-join-requires-the-attempt-token
  (testing "rf2-2ai15g — a runtime-produced `:spawn-all` join validates
            against the EXTRACTED `InvokeAllJoinState` (token REQUIRED per the
            authoritative Spec-Schemas document), and dissociating `:rf/attempt`
            FAILS the schema — the proof a child-bearing join can never be
            token-less (no optional arm). Guards Spec-009 AC: `:rf/attempt`
            going `{:optional true}` in the authoritative document turns this
            red, because the extracted schema would then accept the dissoc."
    (let [j (reg-join-parent! :sac/p1 :sac/p1a :sac/p1b
                              {:join :all :on-all-complete [:all/done]})]
      (is (int? (:rf/attempt j))
          "the live seed always mints an int per-attempt token")
      (is (m/validate InvokeAllJoinState j)
          (str "the runtime join validates against the extracted required-token "
               "schema; explain: " (m/explain InvokeAllJoinState j)))
      (is (not (m/validate InvokeAllJoinState (dissoc j :rf/attempt)))
          "dissociating the token FAILS the extracted schema — the token is
           required in the authoritative document, not optional"))))

;; ---------------------------------------------------------------------------
;; 1b. SCHEMA — the childless REJECT sentinel is a THIRD legal `:spawned` arm,
;;     and the COMPLETE Machines runtime-db validates with it present
;; ---------------------------------------------------------------------------

(deftest reject-sentinel-is-a-legal-spawned-arm
  (testing "rf2-2ai15g — an UNREGISTERED child TYPE in a `:spawn-all` set seeds
            the childless `InvokeAllRejectedState` sentinel; the COMPLETE
            `:rf.runtime/machines` runtime-db validates against the extracted
            `Machines` form during the legal pre-parent-exit interval, the
            sentinel validates as the reject arm, and both a sentinel carrying
            authority keys and a token-less live join FAIL the extracted
            `:spawned` union."
    (let [ok-child {:initial :running :data {} :states {:running {}}}
          ;; :sac/missing is NEVER reg-machine'd — the unregistered child TYPE.
          parent   {:initial :idle
                    :states
                    {:idle    {:on {:start :forking}}
                     :forking {:spawn-all
                               {:children        [{:id :ok      :machine-id :sac/rok}
                                                  {:id :missing :machine-id :sac/missing}]
                                :join            :all
                                :on-child-done   :c/done
                                :on-child-error  :c/failed
                                :on-all-complete [:all/done]}}
                     :ready   {}}}]
      (rf/reg-machine :sac/rok ok-child)
      (rf/reg-machine :sac/rparent parent)
      ;; ALSO stand up a genuine LIVE join in the same frame, so the complete
      ;; runtime-db carries BOTH a reject sentinel and a live child-bearing
      ;; join at once — the mixed real value the `Machines` form must admit.
      (reg-join-parent! :sac/live :sac/livea :sac/liveb
                        {:join :all :on-all-complete [:all/done]})
      (rf/dispatch-sync [:sac/rparent [:start]])
      (let [sentinel (join-state :sac/rparent [:forking])]
        ;; the runtime seeded the childless reject sentinel (pre-parent-exit).
        (is (= {:rf/spawn-all-rejected? true} sentinel)
            "the reject sentinel (no :children) is seeded")
        ;; COMPLETE Machines runtime-db validates WITH the sentinel present.
        (is (m/validate Machines (machines-runtime-db))
            (str "the complete :rf.runtime/machines validates against the "
                 "extracted Machines form; explain: "
                 (m/explain Machines (machines-runtime-db))))
        ;; the sentinel is a legal reject arm ...
        (is (m/validate InvokeAllRejectedState sentinel)
            "the real sentinel validates against the extracted InvokeAllRejectedState")
        (is (m/validate SpawnedSlot {:sac/rparent {[:forking] sentinel}})
            "the sentinel is accepted by the extracted :spawned union")
        ;; ... but a sentinel that ALSO carries authority is NOT a clean reject:
        ;; the closed map rejects the extra keys, and it is not a live join.
        (is (not (m/validate InvokeAllRejectedState (assoc sentinel :children {:a :a#1})))
            "a sentinel carrying :children FAILS the closed reject schema")
        (is (not (m/validate SpawnedSlot {:sac/rparent {[:forking] (assoc sentinel :rf/attempt 9)}}))
            "a sentinel carrying an authority token FAILS the :spawned union")
        ;; a token-less live join fails BOTH arms → fails the union.
        (let [live (join-state :sac/live [:racing])]
          (is (m/validate SpawnedSlot {:sac/live {[:racing] live}})
              "the real live join is accepted by the :spawned union")
          (is (not (m/validate SpawnedSlot {:sac/live {[:racing] (dissoc live :rf/attempt)}}))
              "a token-less live join FAILS the :spawned union — not a valid live shape"))))))

;; ---------------------------------------------------------------------------
;; 2. CATALOGUE — `child-completed` emits `:rf.reply/correlation`
;; ---------------------------------------------------------------------------

(deftest child-completed-trace-emits-reply-correlation
  (testing "rf2-p53o47 — a NON-DECISIVE fold's
            `:rf.machine.spawn-all/child-completed` trace carries the
            emitted `:rf.reply/correlation` with the fold's parent/invoke
            identity, logical child id, and spawned instance address."
    (let [j (reg-join-parent! :sac/p2 :sac/p2a :sac/p2b
                              {:join :all :on-all-complete [:all/done]})
          a (get-in j [:children :a])]
      ;; A folds first — non-decisive in a two-child :all join.
      (rf/dispatch-sync [a [:go]])
      (let [traces (mtest/events-of :rf.machine.spawn-all/child-completed)]
        (is (= 1 (count traces)) "one child-completed fold trace for A")
        (let [tags        (:tags (first traces))
              correlation (:rf.reply/correlation tags)]
          (is (some? correlation)
              "the child-completed trace emits :rf.reply/correlation")
          (is (= :sac/p2 (:parent-id correlation)) "correlation parent identity")
          (is (= [:racing] (:invoke-id correlation)) "correlation invoke identity")
          (is (= :a (:child-id correlation)) "correlation logical child id")
          (is (= a (:spawned-id correlation))
              "correlation spawned instance address"))))))

;; ---------------------------------------------------------------------------
;; 3. CATALOGUE — the spawn-all completion ops + `:rf.reply/stale-reason`
;;    classifier do not drift
;; ---------------------------------------------------------------------------

(deftest late-completion-op-classifies-by-stale-reason
  (testing "rf2-p53o47 — a POST-resolution straggler fires
            `:rf.machine.spawn-all/late-completion` classified by
            `:rf.reply/stale-reason` :rf.machine.spawn-all/join-resolved
            (the op the quick index must list; the classifier key is
            `:rf.reply/stale-reason`, not the bare `:reason`)."
    (let [j (reg-join-parent! :sac/p3 :sac/p3a :sac/p3b
                              {:join :any :on-some-complete [:any/done]})
          a (get-in j [:children :a])]
      ;; A resolves the :any join; the parent stays on :racing.
      (rf/dispatch-sync [a [:go]])
      (is (true? (:resolved? (join-state :sac/p3 [:racing]))) "the :any join resolved")
      (mtest/reset-captured!)
      ;; :a's EXACT-CURRENT completion re-completes AFTER the :resolved? latch
      ;; flipped — the late-completion path is gated on exact authority
      ;; (rf2-ixjd48), so the carrier is stamped for the current attempt.
      (rf/dispatch-sync
        [:sac/p3 (with-meta [:child/done :a]
                   {:rf/join-auth {:parent-id  :sac/p3
                                   :invoke-id  [:racing]
                                   :child-id   :a
                                   :spawned-id a
                                   :attempt    (:rf/attempt (join-state :sac/p3 [:racing]))}})])
      (let [late (first (mtest/events-of :rf.machine.spawn-all/late-completion))]
        (is (some? late) "the late-completion op fired")
        (is (= :stale (:rf.reply/status (:tags late))))
        (is (= :suppressed (:rf.reply/work-status (:tags late))))
        (is (= :rf.machine.spawn-all/join-resolved
               (:rf.reply/stale-reason (:tags late)))
            "classified by :rf.reply/stale-reason (post-resolution)")))))

(deftest stale-completion-op-classifies-by-stale-reason
  (testing "rf2-p53o47 — a PRE-resolution unstamped carrier fires
            `:rf.machine.spawn-all/stale-completion` classified by
            `:rf.reply/stale-reason` :rf.machine.spawn-all/attempt-unverified
            — the classifier key is `:rf.reply/stale-reason`, guarding
            against a drift back to the bare `:reason`."
    (reg-join-parent! :sac/p4 :sac/p4a :sac/p4b
                      {:join :all :on-all-complete [:all/done]})
    (mtest/reset-captured!)
    ;; A bare hand-dispatched completion never flowed through the member
    ;; child's boundary, so it carries no runtime `:rf/join-auth` stamp.
    (rf/dispatch-sync [:sac/p4 [:child/done :a]])
    (let [stale (first (mtest/events-of :rf.machine.spawn-all/stale-completion))]
      (is (some? stale) "the stale-completion op fired")
      (is (= :stale (:rf.reply/status (:tags stale))))
      (is (= :rf.machine.spawn-all/attempt-unverified
             (:rf.reply/stale-reason (:tags stale)))
          "classified by :rf.reply/stale-reason (pre-resolution)"))))

;; ---------------------------------------------------------------------------
;; 4. CATALOGUE — the Spec-009 rows themselves carry the facts (narrow reads of
;;    the AUTHORITATIVE document; each assertion fails independently)
;; ---------------------------------------------------------------------------

(defn- quick-index-row
  "The ONE quick-index table row that pipe-lists the spawn-all lifecycle ops —
  the row containing both `started` and `child-completed`."
  [text]
  (->> (str/split-lines text)
       (filter #(and (str/starts-with? (str/triml %) "|")
                     (str/includes? % ":rf.machine.spawn-all/started")
                     (str/includes? % ":rf.machine.spawn-all/child-completed")))
       first))

(defn- detailed-row
  "The detailed-catalogue prose bullet that OPENS with `- `<op>`…`."
  [text op-kw]
  (->> (str/split-lines text)
       (filter #(str/starts-with? (str/triml %) (str "- `" op-kw "`")))
       first))

(defn- line-with [text needle]
  (->> (str/split-lines text) (filter #(str/includes? % needle)) first))

(deftest spec-009-catalogue-pins-the-spawn-all-ops
  (testing "rf2-2ai15g — the authoritative Spec-009 rows carry the exact
            vocabulary the runtime emits; each fact fails independently."
    (let [text (extract/spec-009-text)
          qidx (quick-index-row text)
          cc   (detailed-row text :rf.machine.spawn-all/child-completed)
          sc   (detailed-row text :rf.machine.spawn-all/stale-completion)]
      (is (some? qidx) "the spawn-all quick-index row exists")
      ;; late-completion must stay in the QUICK INDEX (not only the detailed row).
      (is (str/includes? qidx ":rf.machine.spawn-all/late-completion")
          "the quick index lists :rf.machine.spawn-all/late-completion")
      ;; the classifier note stays `:rf.reply/stale-reason`, not bare `:reason`.
      (is (str/includes? qidx ":rf.reply/stale-reason")
          "the quick index classifies the stale/late ops by :rf.reply/stale-reason")
      ;; child-completed correlation must stay documented.
      (is (some? cc) "the child-completed detailed row exists")
      (is (str/includes? cc ":rf.reply/correlation")
          "the child-completed row documents :rf.reply/correlation")
      ;; stale-completion classifier key stays `:rf.reply/stale-reason`.
      (is (some? sc) "the stale-completion detailed row exists")
      (is (str/includes? sc ":rf.reply/stale-reason")
          "the stale-completion row classifies by :rf.reply/stale-reason")
      ;; join-resolved is a stale-reason VALUE, never an operation — it appears
      ;; ONLY as `:rf.reply/stale-reason :rf.machine.spawn-all/join-resolved`.
      (let [jr (line-with text ":rf.machine.spawn-all/join-resolved")]
        (is (some? jr) "the join-resolved stale-reason value is documented")
        (is (str/includes? jr ":rf.reply/stale-reason :rf.machine.spawn-all/join-resolved")
            "join-resolved is presented as a :rf.reply/stale-reason VALUE, not an op")))))
