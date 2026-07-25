(ns re-frame.epoch-egress-redaction-cljs-test
  "rf2-p4515 — the epoch **egress-redaction** contract, proved on the host its
  consumers actually run on.

  ## Why this file exists

  The epoch privacy / egress tier is a DATA-LEAK guard: it is the thing that
  stops a `:sensitive`-classified value leaving the process. Before this suite
  that guard was proved by 136 deftests across six `.clj` files — **JVM-only**.
  Every real consumer of the projection is ClojureScript:

    - `day8.re-frame2-xray.runtime/egress-record` calls
      `re-frame.core/projected-record` in the browser;
    - `re-frame2-pair-mcp`'s `watch-epochs` / `trace-window` / `snapshot` tools
      emit CLJS forms that call `re-frame.core/projected-record` **inside the
      running app**;
    - the browser Tool-Pair time-travel path reads the same projected shape.

  So the assertions lived on the one host where no consumer runs. That
  asymmetry — not the raw coverage number — is the defect, and this area has
  already produced one documented false green on exactly this surface (see
  `.github/scripts/report-changed-surfaces.sh`: \"a PR that broke the epoch
  egress/redaction contract merged GREEN at PR time … surfacing only in the
  nightly cron\").

  ## What is mirrored here (and what is deliberately not)

  Mirrored: the arms whose failure means a sensitive value **actually escapes
  to a CLJS consumer** —

    1. the app-db `:sensitive` / `:large` substitution table at egress
       (`:db-before` / `:db-after`, precedence, bookkeeping pass-through);
    2. the **facade** path (`re-frame.core/projected-record`), which is what
       the consumers call — the JVM tier drives the artefact-internal
       `re-frame.epoch/projected-record` almost exclusively, so the
       `late-bind` seam the browser crosses was untested on this host;
    3. the forwarder / bulk-egress shapes the consumers run
       (`register-listener! :epoch` + `projected-history`), including the
       whole-structure \"no secret bytes anywhere\" scan and double-projection
       idempotence;
    4. axis orthogonality — `:include-sensitive?` must not lift the fx-args,
       runtime-db partition, or large axes (the rf2-m9duxl / rf2-5w06uu
       Xray + Pair-MCP bypass leaks, both CLJS-side bugs);
    5. `:trigger-event` event-args fail-closed (rf2-nm611o);
    6. the `:trace-events` slot — the t1/t2 pending-db tag re-root (a leak the
       whole rest of the tier would pass green on) and the off-box
       `:rf.http/off-box-body :omit` fail-closed. Both are read by Xray's
       Issues lens and the Pair-MCP `trace-window` tool, and a browser XHR
       reply lands there the same way a server's does;
    7. classification RETENTION — a path classified once keeps redacting on
       later, unrelated cascades, and the `:rf.epoch/sensitive?` rollup badge
       survives projection;
    8. the `:redact-fn` egress override, including the fail-closed fallback
       when it throws (`catch #?(:clj Throwable :cljs :default)` is one of the
       few reader conditionals in this tier — CLJS `:default` catches
       non-`Error` throws too, so the arm is genuinely host-shaped).

  NOT mirrored, and why (the full list, with reasons, is in the PR body): the
  resource / mutation trace family's egress projector is OWNED by the
  resources artefact behind a late-bound hook and belongs to that artefact's
  test tree; `configure!` argument validation is registry plumbing with no
  egress path; and the `debug-enabled?`-false gate arms are JVM-shaped by
  construction (CLJS has the separate, already-covered
  `epoch_elision_prod_test.cljs` + `check-elision.cjs` DCE gate).

  ## Non-vacuity

  Every redaction assertion is paired with a control that would break it:
  either an in-suite **unclassified control path** carrying the same bytes
  RAW through the same projection call, or the explicit `:include-*` opt-in
  revealing the value. A fixture that silently stopped writing the secret, or
  a classification that silently stopped registering, reds the control.

  Dual-lane `.cljc`: the ns ends `-cljs-test` so the shadow-cljs `:node-test`
  build (`npm run test:cljs`, `:ns-regexp \"cljs-test$\"`) selects it, and it
  also matches cognitect test-runner's `.*-test$` under the artefact's
  `clojure -M:test`. The contract is therefore pinned on BOTH hosts from one
  source of truth."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.epoch :as epoch]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; ---- fixtures --------------------------------------------------------------
;;
;; Same canonical capture/restore fixture the JVM privacy suites use
;; (rf2-yw1w1u): registrar snapshot/restore around each test plus the epoch
;; reset-hook table (history / listeners / config-to-default). The `:init-fn`
;; re-applies this suite's non-default `:trace-events-keep 5` through the
;; PUBLIC `configure!` boundary — no reach into the private `state/config`.
;; plain-atom is the right substrate on both hosts (no DOM under :node-test).
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (rf/configure! {:epoch-history {:trace-events-keep 5}}))}))

;; ---- helpers ---------------------------------------------------------------

;; A UNIQUE sentinel: it appears nowhere else in the corpus, so a whole-record
;; scan that finds it can only be hitting THIS suite's secret.
(def ^:private secret "EPOCH-EGRESS-SECRET-p4515")

;; A benign string of the same shape, written at an UNCLASSIFIED path. Every
;; redaction assertion has a sibling asserting this one rides RAW — the
;; negative control that makes the suite non-vacuous (over-redaction and
;; under-redaction both fail loudly).
(def ^:private benign "EPOCH-EGRESS-BENIGN-p4515")

(def ^:private payload-size 25000)

(defn- big-string [n] (apply str (repeat n "X")))

(def ^:private frame-id         :epoch-egress-redaction/frame)
;; A second, never-classified frame — the control arms make a FRESH frame
;; rather than re-`make-frame`ing `frame-id`, whose app-db would still carry
;; the secret in `:db-before` and roll the badge up true.
(def ^:private control-frame-id :epoch-egress-redaction/control-frame)

(defn- last-record
  ([] (last-record frame-id))
  ([fid] (last (rf/epoch-history fid))))

(defn- classify!
  "Declare `[:auth :password]` sensitive and `[:blob :payload]` large against
  the frame. EP-0025: durable app-db classification rides the commit-plane
  classification effects, so this is seeded through
  `elision/apply-classification-effects` (`:source :effect`) — the same
  registry write a `reg-event` returning `:sensitive` / `:large` performs.
  Classification is value-independent, so a cascade that leaves either path
  absent is a harmless no-op."
  []
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects
               rt {:sensitive [[:auth :password]]
                   :large     [[:blob :payload]]})))
  nil)

(defn- fresh-frame!
  "Make the suite's frame with the classification registered."
  []
  (rf/make-frame {:id frame-id})
  (classify!)
  nil)

(defn- contains-secret?
  "True when `secret` appears ANYWHERE in a nested EDN value — the recursive
  scan an off-box forwarder's wire payload is subject to. `clojure.string`
  is host-neutral, so no reader conditional is needed (the JVM originals used
  `.contains`, which is JVM interop and would not compile under CLJS)."
  [x]
  (cond
    (string? x) (str/includes? x secret)
    (map? x)    (boolean (or (some contains-secret? (keys x))
                             (some contains-secret? (vals x))))
    (coll? x)   (boolean (some contains-secret? x))
    :else       false))

(defn- count-leaves-at-least
  "Count leaf strings of length >= `n` — bounds the \"no raw large bytes on the
  wire\" claim."
  [n x]
  (let [c (atom 0)]
    ((fn walk [v]
       (cond
         (string? v) (when (>= (count v) n) (swap! c inc))
         (map? v)    (do (run! walk (keys v)) (run! walk (vals v)))
         (coll? v)   (run! walk v)))
     x)
    @c))

(defn- reg-login!
  "One event that writes the classified sensitive path AND an unclassified
  sibling path carrying the control value in the same cascade."
  []
  (rf/reg-event :egress/login
    (fn [{:keys [db]} [_ pw]]
      {:db (-> db
               (assoc-in [:auth :password] pw)
               (assoc-in [:audit :note] benign))})))

;; ============================================================================
;;  1. The app-db substitution table at egress
;; ============================================================================

(deftest db-after-sensitive-leaf-redacts-at-egress
  (testing "a frame-declared sensitive path in `:db-after` lands as
            `:rf/redacted` in the projected record, while the RAW ring record
            keeps the value (on-box replay fidelity). The unclassified
            sibling path rides through RAW — the control that proves the
            walker is discriminating, not blanket-redacting."
    (fresh-frame!)
    (reg-login!)
    (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
    (let [raw  (last-record)
          proj (epoch/projected-record raw)]
      (is (= secret (get-in raw [:db-after :auth :password]))
          "fixture: the raw ring record carries the unredacted secret")
      (is (= :rf/redacted (get-in proj [:db-after :auth :password]))
          "projected `:db-after` substitutes `:rf/redacted` at the sensitive leaf")
      (is (= benign (get-in proj [:db-after :audit :note]))
          "NEGATIVE CONTROL — the unclassified sibling value survives
           projection RAW, so the assertion above cannot pass by
           blanket-redaction or by an empty `:db-after`")
      (is (not (contains-secret? proj))
          "and the secret is absent from the WHOLE projected record, not just
           the slot we probed"))))

(deftest db-before-sensitive-leaf-redacts-at-egress
  (testing "`:db-before` is walked too — a value present pre-cascade must not
            escape just because the cascade under egress did not write it."
    (fresh-frame!)
    (reg-login!)
    (rf/reg-event :egress/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
    (rf/dispatch-sync [:egress/inc]          {:frame frame-id})
    (let [raw  (last-record)
          proj (epoch/projected-record raw)]
      (is (= secret (get-in raw [:db-before :auth :password]))
          "fixture: raw `:db-before` carries the value")
      (is (= :rf/redacted (get-in proj [:db-before :auth :password]))
          "projected `:db-before` substitutes `:rf/redacted`")
      (is (not (contains-secret? proj))
          "no secret bytes anywhere in the projected record"))))

(deftest large-leaf-elides-to-marker-at-egress
  (testing "a frame-declared `:large` path egresses as a
            `:rf.size/large-elided` marker, never as raw bytes — the
            token-budget claim the MCP wire boundary depends on."
    (fresh-frame!)
    (rf/reg-event :egress/upload
      (fn [{:keys [db]} [_ payload]] {:db (assoc-in db [:blob :payload] payload)}))
    (rf/dispatch-sync [:egress/upload (big-string payload-size)] {:frame frame-id})
    (let [raw  (last-record)
          proj (epoch/projected-record raw)]
      (is (= payload-size (count (get-in raw [:db-after :blob :payload])))
          "fixture: the raw record carries the full payload")
      (is (elision/marker? (get-in proj [:db-after :blob :payload]))
          "the projected slot is a `:rf.size/large-elided` marker")
      (is (pos? (count-leaves-at-least payload-size raw))
          "fixture control: the raw ring DOES contain a large leaf")
      (is (zero? (count-leaves-at-least payload-size proj))
          "the projected record contains ZERO leaves of the payload's size"))))

(deftest sensitive-wins-over-large-at-egress
  (testing "a path declared BOTH sensitive and large egresses as
            `:rf/redacted`, not as a size marker — the size marker carries
            structural indicators (`:bytes`, `:digest`) that must not
            describe a sensitive value."
    (rf/make-frame {:id frame-id})
    (frame/swap-runtime-db! frame-id
      (fn [rt] (elision/apply-classification-effects
                 rt {:sensitive [[:both :slot]] :large [[:both :slot]]})))
    (rf/reg-event :egress/both
      (fn [{:keys [db]} _] {:db (assoc-in db [:both :slot] (str secret (big-string 30000)))}))
    (rf/dispatch-sync [:egress/both] {:frame frame-id})
    (let [proj (epoch/projected-record (last-record))]
      (is (= :rf/redacted (get-in proj [:db-after :both :slot]))
          "sensitive takes precedence over large at the same path")
      (is (not (contains-secret? proj))
          "and the secret bytes are gone"))))

(deftest bookkeeping-slots-survive-projection
  (testing "the slots a CLJS consumer navigates by — `:epoch-id` (Xray /
            watch-epochs resume cursor), `:frame` (scoped tool routing),
            `:event-id`, `:outcome`, `:rf.epoch/sensitive?` (the badge) —
            are byte-identical after projection. Over-redaction here breaks
            every tool as surely as under-redaction leaks."
    (fresh-frame!)
    (reg-login!)
    (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
    (let [raw  (last-record)
          proj (epoch/projected-record raw)]
      (doseq [k [:epoch-id :frame :committed-at :event-id :outcome
                 :halt-reason :schema-digest :rf.epoch/sensitive?]]
        (is (= (get raw k) (get proj k))
            (str "bookkeeping slot " k " passes through unchanged")))
      (is (some? (:epoch-id proj))
          "fixture control: `:epoch-id` is actually populated, so the
           equality checks above are not comparing nil to nil"))))

(deftest nil-and-non-map-input-projects-to-nil
  (testing "`projected-record` returns nil for non-map input — a forwarder
            mapping over a ring that contains a nil hole must not throw
            mid-egress on either host."
    (is (nil? (epoch/projected-record nil)))
    (is (nil? (epoch/projected-record :not-a-record)))))

;; ============================================================================
;;  2. The FACADE path — what the browser consumers actually call
;; ============================================================================

(deftest facade-projected-record-redacts-through-late-bind
  (testing "Xray's `egress-record` and every Pair-MCP epoch tool call
            `re-frame.core/projected-record`, NOT the artefact-internal
            `re-frame.epoch/projected-record`. That crosses the `late-bind`
            seam (`:epoch/projected-record`, `:on-absent :nil`). This arm
            pins the seam on the consumers' host: the facade must produce the
            SAME redacted shape the artefact fn does — a seam that silently
            fell through to identity would leak everything."
    (fresh-frame!)
    (reg-login!)
    (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
    (let [raw     (last-record)
          via-fac (rf/projected-record raw)
          via-art (epoch/projected-record raw)]
      (is (some? via-fac)
          "the late-bound hook is published (a nil here would mean the
           artefact was not seen, and every consumer would silently egress
           nothing)")
      (is (= via-art via-fac)
          "facade and artefact projections are identical")
      (is (= :rf/redacted (get-in via-fac [:db-after :auth :password]))
          "the facade path redacts the sensitive leaf")
      (is (not (contains-secret? via-fac))
          "no secret bytes anywhere in the facade-projected record")
      (is (not (contains-secret? (rf/projected-history frame-id)))
          "`projected-history` through the facade is likewise clean")
      (is (contains-secret? (rf/epoch-history frame-id))
          "NEGATIVE CONTROL — the RAW ring the facade reads from DOES carry
           the secret, so the two clean assertions above are proving
           redaction rather than an empty ring"))))

(deftest facade-threads-egress-opts-through-late-bind
  (testing "the consumers pass an opts map through the facade
            (`{:include-sensitive? …}`, `:rf.egress/profile`). The 2-arity
            must thread it — a dropped opts map would silently downgrade a
            trusted-local read, or worse, silently ignore a fail-closed
            profile choice."
    (fresh-frame!)
    (reg-login!)
    (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
    (let [raw (last-record)]
      (is (= secret (get-in (rf/projected-record raw {:include-sensitive? true})
                            [:db-after :auth :password]))
          "the opts map reaches the artefact through the facade")
      (is (= :rf/redacted (get-in (rf/projected-record raw {})
                                  [:db-after :auth :password]))
          "and an empty opts map keeps the fail-closed default")
      (is (= (epoch/projected-record raw {:rf.egress/profile :rf.egress/off-box-tool})
             (rf/projected-record raw {:rf.egress/profile :rf.egress/off-box-tool}))
          "the named `:rf.egress/off-box-tool` boundary (the MCP wire) agrees
           across facade and artefact"))))

;; ============================================================================
;;  3. The forwarder + bulk-egress shapes the CLJS consumers run
;; ============================================================================

(deftest epoch-listener-forwarder-egresses-no-secret-bytes
  (testing "the `register-listener! :epoch` + `projected-record`-in-ship!
            pattern is exactly what an off-box forwarder runs. The listener
            fan-out delivers the RAW record on purpose (Xray diff /
            `restore-epoch!` need it), so the forwarder's own projection call
            is the ONLY thing between a sensitive value and the wire."
    (fresh-frame!)
    (reg-login!)
    (rf/reg-event :egress/upload
      (fn [{:keys [db]} [_ p]] {:db (assoc-in db [:blob :payload] p)}))
    (let [raw-seen (atom [])
          shipped  (atom [])]
      (rf/register-listener! :epoch ::raw-tap  (fn [r] (swap! raw-seen conj r)))
      (rf/register-listener! :epoch ::forwarder
                            (fn [r] (swap! shipped conj (epoch/projected-record r))))
      (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
      (rf/dispatch-sync [:egress/upload (big-string payload-size)] {:frame frame-id})
      (rf/unregister-listener! :epoch ::raw-tap)
      (rf/unregister-listener! :epoch ::forwarder)
      (is (= 2 (count @shipped)) "the forwarder saw both cascades")
      (is (contains-secret? @raw-seen)
          "NEGATIVE CONTROL — listener fan-out delivers the RAW record
           (secret present), which is what makes the next assertion a real
           test of the forwarder's projection call")
      (is (not (contains-secret? @shipped))
          "no shipped record carries the secret anywhere in its structure")
      (is (zero? (count-leaves-at-least payload-size @shipped))
          "and no shipped record carries the large payload as raw bytes"))))

(deftest projected-history-is-mapv-projected-record-and-ordered
  (testing "`projected-history` is the bulk-egress shape a `watch-epochs`
            initial snapshot ships. It must be fn-equivalent to
            `(mapv projected-record (epoch-history …))` and preserve
            oldest-first order — the resume cursor's `:after-id` depends on
            the ordering, and any divergence between the two entry points is
            a second, unproved redaction path."
    (fresh-frame!)
    (reg-login!)
    (rf/reg-event :egress/seed (fn [_ _] {:db {:n 0}}))
    (rf/reg-event :egress/inc  (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/dispatch-sync [:egress/seed]          {:frame frame-id})
    (rf/dispatch-sync [:egress/login secret]  {:frame frame-id})
    (rf/dispatch-sync [:egress/inc]           {:frame frame-id})
    (let [raw  (rf/epoch-history frame-id)
          bulk (epoch/projected-history frame-id)]
      (is (= 3 (count bulk)) "one projected record per raw record")
      (is (= (mapv epoch/projected-record raw) bulk)
          "bulk egress is fn-equivalent to per-record egress")
      (is (= (mapv :epoch-id raw) (mapv :epoch-id bulk))
          "oldest-first order is preserved")
      (is (not (contains-secret? bulk))
          "the bulk shape leaks no secret bytes")
      (is (= [] (epoch/projected-history :epoch-egress-redaction/no-such-frame))
          "an unknown frame yields the empty vector, not a throw"))))

(deftest double-projection-is-idempotent-for-both-substitutions
  (testing "middleware composition and tool-then-watcher fan-out can project
            the same record twice. Both substitutions must be irreversible
            across passes: `:rf/redacted` is a non-matchable scalar, and the
            wire-elision walker is marker-aware for `:rf.size/large-elided`
            (rf2-fq8ep) so `:bytes` / `:digest` do not drift."
    (fresh-frame!)
    (reg-login!)
    (rf/reg-event :egress/upload
      (fn [{:keys [db]} [_ p]] {:db (assoc-in db [:blob :payload] p)}))
    (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
    (rf/dispatch-sync [:egress/upload (big-string payload-size)] {:frame frame-id})
    (let [raw    (rf/epoch-history frame-id)
          once   (mapv epoch/projected-record raw)
          twice  (mapv epoch/projected-record once)
          thrice (mapv epoch/projected-record twice)]
      (is (= once twice thrice)
          "projection reaches a fixpoint on the first pass — no drift in the
           large marker's `:bytes` / `:digest` across passes")
      (is (not (contains-secret? thrice))
          "the secret is still absent after three passes"))))

(deftest projection-does-not-mutate-the-ring
  (testing "a forwarder projects on every cascade; a side effect would
            compound. The raw ring must be untouched — otherwise
            `restore-epoch!` on the browser Tool-Pair path would time-travel
            to redacted state."
    (fresh-frame!)
    (reg-login!)
    (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
    (let [before (rf/epoch-history frame-id)]
      (dotimes [_ 10]
        (mapv epoch/projected-record before)
        (epoch/projected-history frame-id))
      (is (= before (rf/epoch-history frame-id))
          "the ring is structurally unchanged after 20 projection calls")
      (is (contains-secret? (rf/epoch-history frame-id))
          "and still carries the raw replay material a restore needs"))))

;; ============================================================================
;;  4. Axis orthogonality — the CLJS-side bypass leaks (rf2-m9duxl, rf2-5w06uu)
;; ============================================================================

(deftest include-sensitive-keeps-fx-args-redacted
  (testing "rf2-m9duxl was a CLJS bug: the Pair-MCP epoch tools treated an
            operator's `:include-sensitive true` as a FULL raw-epoch bypass,
            shipping raw fx args off-box. `{:include-sensitive? true}` lifts
            the APP-DB sensitive axis ONLY; `:effects[*].args` is a different
            keyspace governed by `:include-fx-args?`."
    (fresh-frame!)
    (rf/reg-fx :egress/login-fx (fn [_ _] nil))
    (rf/reg-event :egress/do-login
      (fn [_ [_ creds]]
        {:db {:auth {:password (:password creds)}}
         :fx [[:egress/login-fx creds]]}))
    (rf/dispatch-sync [:egress/do-login {:password secret :token "tok-abc"}]
                      {:frame frame-id})
    (let [raw    (last-record)
          proj   (epoch/projected-record raw {:include-sensitive? true})
          fx-row (some #(when (= :egress/login-fx (:fx-id %)) %) (:effects proj))]
      (is (= secret (get-in proj [:db-after :auth :password]))
          "`:include-sensitive? true` reveals the app-db sensitive leaf
           (which also proves the opt-in is threaded at all)")
      (is (some? fx-row) "fixture: the cascade produced a payload-bearing fx row")
      (is (= :rf/redacted (:args fx-row))
          "`:effects[*].args` STAY redacted — orthogonal axis")
      (is (= :egress/login-fx (:fx-id fx-row))
          "the value-free `:fx-id` is preserved for tool display")
      (is (= :rf/redacted (:args (some #(when (= :egress/login-fx (:fx-id %)) %)
                                       (:effects (epoch/projected-record raw)))))
          "and the bare off-box default redacts the fx args too"))))

(deftest include-sensitive-keeps-runtime-db-partition-redacted
  (testing "rf2-5w06uu was the Xray-side twin: opting in to sensitive APP-DB
            values used to walk the RAW record, lifting the orthogonal
            `:rf.db/runtime` partition (machine snapshots, route slice, SSR
            metadata) off-box as a side effect. The partition stays
            `:rf/redacted` unless `:include-runtime-db? true` is passed."
    (fresh-frame!)
    (rf/reg-event :egress/seed-both
      (fn [{rt :rf.db/runtime} _]
        {:db            {:auth {:password secret}}
         :rf.db/runtime (assoc-in (or rt {})
                                  [:rf.runtime/machines :snapshots :m/x]
                                  {:state :live})}))
    (rf/dispatch-sync [:egress/seed-both] {:frame frame-id})
    (let [raw  (last-record)
          proj (epoch/projected-record raw {:include-sensitive? true})]
      (is (= {:state :live}
             (get-in raw [:frame-state-after :rf.db/runtime
                          :rf.runtime/machines :snapshots :m/x]))
          "fixture: the raw record carries a populated runtime-db partition")
      (is (= secret (get-in proj [:frame-state-after :rf.db/app :auth :password]))
          "`:include-sensitive? true` reveals the app-db partition's leaf")
      (is (= :rf/redacted (get-in proj [:frame-state-after :rf.db/runtime]))
          "the `:rf.db/runtime` partition STAYS redacted under
           include-sensitive alone")
      (is (not= :rf/redacted
                (get-in (epoch/projected-record raw {:include-sensitive?  true
                                                     :include-runtime-db? true})
                        [:frame-state-after :rf.db/runtime]))
          "NEGATIVE CONTROL — the explicit `:include-runtime-db? true` opt DOES
           lift it, proving the axis is independently governed and the
           assertion above is not passing because the partition is empty"))))

(deftest include-sensitive-keeps-large-elision-independent
  (testing "`:include-sensitive?` and `:include-large?` are independent axes:
            asking for sensitive values must not pull a bulk payload onto the
            wire (the token-budget claim), and vice versa."
    (fresh-frame!)
    (reg-login!)
    (rf/reg-event :egress/both
      (fn [{:keys [db]} [_ pw p]]
        {:db (-> db (assoc-in [:auth :password] pw)
                    (assoc-in [:blob :payload] p))}))
    (rf/dispatch-sync [:egress/both secret (big-string payload-size)] {:frame frame-id})
    (let [raw (last-record)]
      (let [proj (epoch/projected-record raw {:include-sensitive? true})]
        (is (= secret (get-in proj [:db-after :auth :password])))
        (is (elision/marker? (get-in proj [:db-after :blob :payload]))
            "large stays elided under the sensitive opt-in")
        (is (zero? (count-leaves-at-least payload-size proj))
            "and no raw payload bytes egress anywhere in the record"))
      (let [proj (epoch/projected-record raw {:include-large? true})]
        (is (= :rf/redacted (get-in proj [:db-after :auth :password]))
            "sensitive stays redacted under the large opt-in")
        (is (not (elision/marker? (get-in proj [:db-after :blob :payload])))
            "NEGATIVE CONTROL — `:include-large? true` DOES lift the slot")
        (is (pos? (count-leaves-at-least payload-size proj))
            "and the raw payload bytes ARE present, so the elision
             assertions above are not vacuous")))))

(deftest include-sensitive-still-applies-the-redact-fn-override
  (testing "the app-installed `:redact-fn` is the SECOND stage of the
            projection. A raw-record bypass on `:include-sensitive?` (the
            original rf2-m9duxl bug) skipped it entirely, so an app relying
            on the override to scrub material the classification registry
            cannot prove would have leaked. The override must still run."
    (fresh-frame!)
    (reg-login!)
    (rf/configure! {:epoch-history
                    {:redact-fn (fn [r] (assoc r :rf.test/redact-fn-ran true))}})
    (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
    (let [raw  (last-record)
          proj (epoch/projected-record raw {:include-sensitive? true})]
      (is (= secret (get-in proj [:db-after :auth :password]))
          "the sensitive opt-in is in force")
      (is (true? (:rf.test/redact-fn-ran proj))
          "the override still ran — the post-projection stage is never skipped")
      (is (not (contains? raw :rf.test/redact-fn-ran))
          "NEGATIVE CONTROL — the RAW ring record is untouched, so the
           override is projection-side only"))))

;; ============================================================================
;;  5. `:trigger-event` event-args fail-closed (rf2-nm611o)
;; ============================================================================

(deftest trigger-event-positional-secret-fails-closed
  (testing "the dispatched event vector's args are registration-owned
            transient payloads, not app-db-rooted, so the classification
            walker cannot prove them safe. Off-box egress fails CLOSED: head
            event-id retained, every arg `:rf/redacted`."
    (fresh-frame!)
    (reg-login!)
    (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
    (let [proj (epoch/projected-record (last-record))]
      (is (= [:egress/login :rf/redacted] (:trigger-event proj))
          "head id retained, positional arg redacted")
      (is (= :egress/login (:event-id proj))
          "the `:event-id` summary slot is intact for tool display")
      (is (not (contains-secret? proj))
          "no secret bytes anywhere"))))

(deftest trigger-event-map-arg-secret-fails-closed
  (testing "a secret NESTED in a map arg also fails closed — the whole arg
            redacts, because the walker cannot descend a keyspace it cannot
            classify."
    (fresh-frame!)
    (rf/reg-event :egress/auth-login
      (fn [{:keys [db]} [_ {:keys [password]}]]
        {:db (assoc-in db [:auth :password] password)}))
    (rf/dispatch-sync [:egress/auth-login {:password secret}] {:frame frame-id})
    (let [proj (epoch/projected-record (last-record))]
      (is (= [:egress/auth-login :rf/redacted] (:trigger-event proj)))
      (is (not (contains-secret? (:trigger-event proj)))))))

(deftest include-event-args-is-orthogonal-to-app-db-axes
  (testing "`:include-event-args?` reveals the raw trigger-event args YET is
            orthogonal to the app-db sensitive axis, and vice versa. Two
            opt-ins, two keyspaces; neither implies the other."
    (fresh-frame!)
    (reg-login!)
    (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
    (let [raw (last-record)]
      (let [proj (epoch/projected-record raw {:include-event-args? true})]
        (is (= [:egress/login secret] (:trigger-event proj))
            "NEGATIVE CONTROL — the opt-in reveals the raw args, so the
             fail-closed assertions above are testing redaction")
        (is (= :rf/redacted (get-in proj [:db-after :auth :password]))
            "the app-db sensitive leaf stays redacted"))
      (let [proj (epoch/projected-record raw {:include-sensitive? true})]
        (is (= secret (get-in proj [:db-after :auth :password])))
        (is (= [:egress/login :rf/redacted] (:trigger-event proj))
            "the event args stay redacted under the app-db opt-in")))))

;; ============================================================================
;;  6. The `:trace-events` slot — the densest payload a CLJS consumer reads
;; ============================================================================
;;
;; Xray's Issues / Schema-timeline lens and the Pair-MCP `trace-window` tool
;; both read `:trace-events` off the PROJECTED record, so this slot is as
;; leak-exposed as `:db-after`. Two arms whose failure is a leak and whose
;; shape is host-neutral (hand-built records — no HTTP artefact, no router
;; timing), mirrored from `epoch_egress_trace_events_test.clj`.

(defn- synthetic-record
  "A minimal `:rf/epoch-record` shell. Hand-building it isolates the egress
  projector from whatever traces the live router happens to emit."
  [fid trace-events]
  {:epoch-id            1
   :frame               fid
   :committed-at        0
   :event-id            :egress/synthetic
   :trigger-event       [:egress/synthetic]
   :db-before           {}
   :db-after            {}
   :outcome             :ok
   :rf.epoch/sensitive? false
   :trace-events        trace-events
   :sub-runs            []
   :renders             []
   :effects             []})

(deftest trace-events-db-pending-tag-is-rerooted-and-redacted
  (testing "the t1 / t2 pending-db traces (`:rf.event/db-pending`,
            `:rf.event/db-pending-post-flow`) carry the FULL pending app-db
            nested under `[:tags :rf.event/db]`. The bulk wire walk would
            root that nested db at `[<i> :tags :rf.event/db …]`, where a
            frame-declared `[:auth :password]` never matches — so egress
            re-roots the walk at the frame's app-db. Delete the re-root and
            the raw secret survives inside the projected trace while every
            `:db-after` assertion stays green; that is precisely the
            false-green shape this whole PR is about."
    (fresh-frame!)
    (let [tags {:rf.event/db {:auth {:password secret} :audit {:note benign}}}
          rec  (synthetic-record
                 frame-id
                 [{:op-type :rf.event :operation :rf.event/db-pending          :tags tags}
                  {:op-type :rf.event :operation :rf.event/db-pending-post-flow :tags tags}])
          [t1 t2] (:trace-events (epoch/projected-record rec))]
      (is (= :rf/redacted (get-in t1 [:tags :rf.event/db :auth :password]))
          "t1's nested sensitive leaf is re-rooted and redacted")
      (is (= :rf/redacted (get-in t2 [:tags :rf.event/db :auth :password]))
          "t2's nested sensitive leaf likewise")
      (is (= benign (get-in t1 [:tags :rf.event/db :audit :note]))
          "NEGATIVE CONTROL — the unclassified sibling inside the SAME nested
           db survives, so the re-root is redacting by classification rather
           than blanking the tag")
      (is (not (contains-secret? (epoch/projected-record rec)))
          "and no secret bytes survive anywhere in the projected record"))))

(deftest off-box-omits-an-unschematized-http-response-body
  (testing "an HTTP response body with no schema is whole-sensitive off-box:
            the transport stamps `:rf.http/off-box-body :omit` and the egress
            projector replaces the body slot with `:rf/redacted`. This is
            NOT server-shaped — a browser app's XHR replies land in
            `:trace-events` exactly the same way, and a bearer token in an
            unschematized reply body is the canonical leak."
    (fresh-frame!)
    (let [body {:token secret :user-id 42}
          omit (synthetic-record
                 frame-id
                 [{:op-type   :rf.trace
                   :operation :rf.http/replied
                   :tags      {:value body :rf.http/off-box-body :omit}}])
          ev   (first (:trace-events (epoch/projected-record omit)))]
      (is (= :rf/redacted (:value (:tags ev)))
          "the unschematized body slot is omitted off-box (fail-closed)")
      (is (not (contains-secret? (epoch/projected-record omit)))
          "the raw token appears nowhere in the projected record")
      (is (contains-secret? omit)
          "NEGATIVE CONTROL — the unprojected record DOES carry the token")
      (is (= body (:value (:tags (first (:trace-events
                                          (epoch/projected-record
                                            omit {:include-sensitive? true}))))))
          "and the trusted-local `:include-sensitive?` opt-in lifts the
           omission, proving the assertion is about the disposition stamp"))))

;; ============================================================================
;;  7. Classification RETENTION
;; ============================================================================

(deftest classification-is-retained-across-later-cascades
  (testing "classification is registered ONCE (commit-plane effect) and lives
            in the frame's runtime-db. Every LATER epoch — including cascades
            that never touch the classified path — must still redact it at
            egress. A registry that only applied to the writing cascade would
            leak the value from every subsequent record, which is exactly
            what a `watch-epochs` stream ships."
    (fresh-frame!)
    (reg-login!)
    (rf/reg-event :egress/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
    (dotimes [_ 4] (rf/dispatch-sync [:egress/inc] {:frame frame-id}))
    (let [raw  (rf/epoch-history frame-id)
          bulk (epoch/projected-history frame-id)]
      (is (= 5 (count bulk)) "fixture: five records in the ring")
      (is (contains-secret? raw)
          "fixture control: the raw ring carries the secret in every
           post-login record's `:db-before` / `:db-after`")
      (is (every? #(= :rf/redacted (get-in % [:db-after :auth :password]))
                  (rest bulk))
          "every record AFTER the writing cascade still redacts — the
           classification is retained, not per-cascade")
      (is (not (contains-secret? bulk))
          "no secret bytes anywhere in the whole projected ring"))))

(deftest classification-retention-negative-control-unclassified-frame
  (testing "the SAME cascade with NO classification registered egresses the
            value RAW. This is the suite's load-bearing negative control: if
            `apply-classification-effects` silently stopped registering, or
            the projection silently blanket-redacted, this arm reds. It also
            documents the actual contract — the projection redacts what the
            app CLASSIFIED, nothing more."
    (rf/make-frame {:id control-frame-id})
    (reg-login!)
    (rf/dispatch-sync [:egress/login secret] {:frame control-frame-id})
    (let [proj (epoch/projected-record (last-record control-frame-id))]
      (is (= secret (get-in proj [:db-after :auth :password]))
          "with no classification declared the value rides through RAW")
      (is (contains-secret? proj)
          "so a redaction assertion against this fixture would go red —
           the classified arms are provably not vacuous"))))

(deftest sensitive-rollup-badge-survives-projection
  (testing "`:rf.epoch/sensitive?` is derived from the RAW record inside
            `build-record`, so it stays a trustworthy off-box branch signal
            after projection. Xray renders the sensitivity badge from it and
            a forwarder may route on it — a rollup that read the projected
            record would collapse to a constant."
    (fresh-frame!)
    (reg-login!)
    (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
    (is (true? (:rf.epoch/sensitive? (epoch/projected-record (last-record))))
        "a cascade whose classified path holds a non-nil leaf rolls up true
         and the flag survives projection")))

(deftest sensitive-rollup-badge-reads-strict-false-without-classification
  (testing "NEGATIVE CONTROL for the badge — the identical projection call on
            a frame with NO classification declared reads strict `false`, so
            the sibling assertion is not passing against a hardcoded true.
            (A separate frame id: `make-frame` on an id whose app-db already
            holds the secret would roll up true from `:db-before` alone.)"
    (rf/make-frame {:id control-frame-id})
    (rf/reg-event :egress/plain (fn [_ _] {:db {:n 0 :audit {:note benign}}}))
    (rf/dispatch-sync [:egress/plain] {:frame control-frame-id})
    (is (false? (:rf.epoch/sensitive? (epoch/projected-record
                                        (last-record control-frame-id))))
        "strict false, not nil and not true")))

(deftest trace-events-retention-cap-bounds-what-egresses
  (testing "`:trace-events-keep` is a retention bound on the most
            payload-dense slot: records older than the window drop
            `:trace-events` entirely, while the structured `:sub-runs` /
            `:renders` / `:effects` projections survive. This caps what a
            bulk `projected-history` egress can carry at all."
    (fresh-frame!)
    (rf/reg-event :egress/seed (fn [_ _] {:db {:n 0}}))
    (rf/reg-event :egress/inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (is (= 5 (:trace-events-keep (epoch/current-config)))
        "fixture override — the shipped runtime default is 50")
    (rf/dispatch-sync [:egress/seed] {:frame frame-id})
    (dotimes [_ 6] (rf/dispatch-sync [:egress/inc] {:frame frame-id}))
    (let [bulk (epoch/projected-history frame-id)
          n    (count bulk)]
      (is (= 7 n) "fixture: seven records")
      (is (every? #(contains? % :sub-runs) bulk)
          "the structured projections survive on every projected record")
      (is (every? #(contains? % :trace-events) (subvec bulk (- n 5) n))
          "the most-recent 5 keep `:trace-events`")
      (is (every? #(not (contains? % :trace-events)) (subvec bulk 0 (- n 5)))
          "older records dropped `:trace-events` — the retention bound holds
           through the egress projection too"))))

;; ============================================================================
;;  8. The `:redact-fn` egress override
;; ============================================================================

(deftest redact-fn-runs-at-egress-not-at-storage
  (testing "the `:redact-fn` advanced override is PROJECTION-side only.
            Storage mutation would corrupt causal replay material, so the
            ring and the listener fan-out stay raw and the override runs
            inside `projected-record`."
    (fresh-frame!)
    (reg-login!)
    (rf/configure! {:epoch-history
                    {:redact-fn (fn [r] (assoc r :db-after :rf/redacted))}})
    (let [seen (atom [])]
      (rf/register-listener! :epoch ::tap (fn [r] (swap! seen conj r)))
      (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
      (rf/unregister-listener! :epoch ::tap))
    (is (= secret (get-in (last-record) [:db-after :auth :password]))
        "the RING record is untouched by the override — restore fidelity")
    (is (= :rf/redacted (:db-after (epoch/projected-record (last-record))))
        "the override IS applied at the egress boundary")))

(deftest throwing-redact-fn-falls-back-to-the-projected-record
  (testing "failure isolation, and a genuinely host-shaped arm: the catch is
            `#?(:clj Throwable :cljs :default)`, and CLJS `:default` catches
            non-`Error` throws (a thrown map, string, keyword) that no JVM
            `Throwable` clause has an analogue for. A throwing override must
            fall back to the frame/profile-PROJECTED record — never to the
            raw one, which would turn a buggy app-supplied fn into a leak."
    (fresh-frame!)
    (reg-login!)
    (rf/configure! {:epoch-history
                    {:redact-fn (fn [_] (throw (ex-info "redact-fn blew up" {})))}})
    (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
    (let [proj (epoch/projected-record (last-record))]
      (is (some? proj) "egress still produces a record")
      (is (= :rf/redacted (get-in proj [:db-after :auth :password]))
          "the fallback is the PROJECTED record — the sensitive leaf is
           still redacted")
      (is (not (contains-secret? proj))
          "and no secret bytes escape through the failure path"))
    ;; A non-Error throw — reachable only under CLJS's `:default` catch.
    (rf/configure! {:epoch-history {:redact-fn (fn [_] (throw #?(:clj (Exception. "raw")
                                                                 :cljs "a bare string")))}})
    (let [proj (epoch/projected-record (last-record))]
      (is (= :rf/redacted (get-in proj [:db-after :auth :password]))
          "a non-`Error` throw is caught too and still falls back closed")
      (is (not (contains-secret? proj))))
    (is (fn? (:redact-fn (epoch/current-config)))
        "and the throwing override stays registered — failure isolation is
         per-call, not a silent de-registration")))
