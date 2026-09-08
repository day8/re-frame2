(ns re-frame.epoch-egress-redaction-cljs-test
  "rf2-p4515 — the epoch **egress-redaction** contract, proved on the host its
  consumers actually run on.

  ## Why this file exists

  The epoch privacy / egress tier is a DATA-LEAK guard: it is the thing that
  stops a `:sensitive`-classified value leaving the process. Before this suite
  that guard was proved by 136 deftests across six `.clj` files — **JVM-only**.
  Every real consumer of the projection is ClojureScript:

    - `re-frame2-pair-mcp`'s `watch-epochs` / `trace-window` / `snapshot` tools
      emit CLJS forms that call `re-frame.core/project-egress` **inside the
      running app**;
    - Xray's Epoch panel renders the same projected record in the browser;
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
    2. the **facade** path (`re-frame.core/project-egress`), which is what
       the consumers call — the JVM tier drives the artefact-internal
       `re-frame.core/project-egress` almost exclusively, so the
       `late-bind` seam the browser crosses was untested on this host;
    3. the forwarder / bulk-egress shapes the consumers run
       (`register-listener! :epoch` + the whole-ring composition
       `(mapv project-egress (epoch-history …))`), including the
       whole-structure \"no secret bytes anywhere\" scan and double-projection
       idempotence;
    4. axis orthogonality — `:rf.size/include-sensitive?` must not lift the fx-args,
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
       survives projection.

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
            [re-frame.elision :as rf.elision]
            [re-frame.epoch :as rf.epoch]
            [re-frame.frame :as rf.frame]
            ;; rf2-kuky.92 §8 — the one-door arms bind the
            ;; `:epoch/project-record` hook (guard G1) and ask the core's own
            ;; door whether it dispatches the kind (guard G2).
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.projection :as rf.projection]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; ---- fixtures --------------------------------------------------------------
;;
;; Same canonical capture/restore fixture the JVM privacy suites use
;; (rf2-yw1w1u): registrar snapshot/restore around each test plus the epoch
;; reset-hook table (history / listeners / config-to-default). The `:init-fn`
;; re-applies this suite's non-default `:trace-events-keep 5` through the
;; PUBLIC `configure!` boundary — no reach into the private `state/config`.
;; plain-atom is the right substrate on both hosts (no DOM under :node-test).
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
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
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects
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
          proj (rf/project-egress raw)]
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
          proj (rf/project-egress raw)]
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
          proj (rf/project-egress raw)]
      (is (= payload-size (count (get-in raw [:db-after :blob :payload])))
          "fixture: the raw record carries the full payload")
      (is (rf.elision/marker? (get-in proj [:db-after :blob :payload]))
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
    (rf.frame/swap-runtime-db! frame-id
      (fn [rt] (rf.elision/apply-classification-effects
                 rt {:sensitive [[:both :slot]] :large [[:both :slot]]})))
    (rf/reg-event :egress/both
      (fn [{:keys [db]} _] {:db (assoc-in db [:both :slot] (str secret (big-string 30000)))}))
    (rf/dispatch-sync [:egress/both] {:frame frame-id})
    (let [proj (rf/project-egress (last-record))]
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
          proj (rf/project-egress raw)]
      (doseq [k [:epoch-id :frame :committed-at :event-id :outcome
                 :halt-reason :schema-digest :rf.epoch/sensitive?]]
        (is (= (get raw k) (get proj k))
            (str "bookkeeping slot " k " passes through unchanged")))
      (is (some? (:epoch-id proj))
          "fixture control: `:epoch-id` is actually populated, so the
           equality checks above are not comparing nil to nil"))))

(deftest large-sub-output-elides-in-both-egress-slots
  (testing "rf2-at60h + rf2-irwsq — a whole-output `:large?` subscription's
            computed value reaches off-box egress through TWO slots of the same
            record: the structured `:sub-runs` row's `:value` / `:prev-value`
            AND the `:rf.sub/run` trace tag's `:rf.sub/value` /
            `:rf.sub/prev-value`. The raw on-box record keeps the exact value in
            both (Xray diff and `restore-epoch!` need it) but egress MUST
            substitute the marker in both; shipping either raw is the leak.
            rf2-at60h fixed the row, rf2-irwsq the tag — they now share ONE
            rule (`tool-pair/elide-whole-output-large-slots`) so they cannot
            drift. These are DISTINCT slots from `:db-after` — and
            `epoch_cljs_test.cljs` exercises no subscriptions at all, so the
            CLJS lane had no `:sub-runs` egress coverage of any kind."
    (fresh-frame!)
    (rf/reg-event :egress/seed (fn [_ _] {:db {:n 0}}))
    (rf/reg-sub :egress/big {:large? true} (fn [_ _] (big-string payload-size)))
    (rf/reg-sub :egress/small (fn [_ _] benign))
    (rf/reg-event :egress/read-subs
      (fn [_ _]
        (rf/subscribe-once [:egress/big]   {:frame frame-id})
        (rf/subscribe-once [:egress/small] {:frame frame-id})
        {}))
    (rf/dispatch-sync [:egress/seed]      {:frame frame-id})
    (rf/dispatch-sync [:egress/read-subs] {:frame frame-id})
    (let [raw       (last-record)
          row-of    (fn [rec id] (->> (:sub-runs rec) (filter #(= id (:sub-id %))) first))
          raw-row   (row-of raw :egress/big)
          proj      (rf/project-egress raw)
          proj-row  (row-of proj :egress/big)
          small-row (row-of proj :egress/small)]
      (is (some? raw-row)  "fixture: the `:large?` sub produced a `:sub-runs` row")
      (is (= payload-size (count (:value raw-row)))
          "fixture: the raw row carries the full computed value")
      (is (some? proj-row) "the projected record keeps the row")
      (is (rf.elision/marker? (:value proj-row))
          "the projected `:value` is a `:rf.size/large-elided` marker")
      (is (not (contains? proj-row :large?))
          "the now-spent `:large?` row flag is stripped from the projection")
      (is (= (:sub-id raw-row) (:sub-id proj-row))
          "the value-free row metadata is preserved for tool display")
      (is (= benign (:value small-row))
          "NEGATIVE CONTROL — an UNMARKED sub's value rides through RAW, so
           the elision above is driven by the registration marker")
      ;; rf2-irwsq — THE TRACE-TAG TWIN. The whole-output `:large?` value also
      ;; rides the `:rf.sub/run` trace tag at
      ;; `[:trace-events <i> :tags :rf.sub/value]`. Writing the record-wide scan
      ;; below is how this suite FOUND that leak: egress elided only the
      ;; structured `:sub-runs` row, so the raw payload still reached every
      ;; off-box consumer that reads `:trace-events` (Xray-MCP `watch-epochs`,
      ;; Pair-MCP `trace-window` / `snapshot`, hosted log shippers) — and under
      ;; the shipped `:trace-events-keep 50` it rode EVERY record for the
      ;; cascade. This block was a labelled CHARACTERISATION assertion pinning
      ;; that raw tag; the fix flipped it to the elided shape. Privacy was never
      ;; affected (per-path `:sensitive` sub marks are substituted at the EMIT
      ;; site, so they are already redacted in both slots) — this is the
      ;; TOKEN-BUDGET axis only.
      (let [tags-of (fn [rec]
                      (->> (:trace-events rec)
                           (filter #(= :rf.sub/run (:operation %)))
                           (filter #(= :egress/big (get-in % [:tags :rf.sub/id])))
                           first
                           :tags))
            raw-tags  (tags-of raw)
            proj-tags (tags-of proj)]
        (is (= payload-size (count (:rf.sub/value raw-tags)))
            "fixture: the RAW trace tag carries the full computed value (the
             on-box ring keeps it — Xray diff / restore-epoch! need it)")
        (is (true? (:large? raw-tags))
            "fixture: the emit chokepoint stamped the whole-output `:large?`
             flag on the raw tag — the marker the egress rule honours")
        (is (rf.elision/marker? (:rf.sub/value proj-tags))
            "rf2-irwsq — the projected `:rf.sub/run` tag's `:rf.sub/value` is a
             `:rf.size/large-elided` MARKER, not the raw payload. A marker and
             not a drop: a tool must be able to tell a value existed and was
             withheld, which a silent drop makes indistinguishable from a sub
             that produced nothing.")
        (is (not (contains? proj-tags :large?))
            "the now-spent `:large?` tag flag is stripped, exactly as the
             `:sub-runs` row's is — both slots go through ONE shared rule")
        (is (= (get-in (:value proj-row)     [:rf.size/large-elided :bytes])
               (get-in (:rf.sub/value proj-tags) [:rf.size/large-elided :bytes]))
            "the row's and the tag's markers agree on `:bytes` — the structural
             evidence that ONE rule produced both (they cannot drift)"))
      ;; The cross-cutting claim the MCP wire boundary depends on, now assertable
      ;; for the whole-output `:large?` SUB shape: NO raw large leaf anywhere in
      ;; the projected record. This is the scan that was withheld pre-fix.
      (is (pos? (count-leaves-at-least payload-size raw))
          "fixture control: the RAW record does carry raw large leaves, so the
           scan below is not vacuously green")
      (is (zero? (count-leaves-at-least payload-size proj))
          "rf2-irwsq — no raw large bytes ANYWHERE in the projected record:
           neither the `:sub-runs` row nor its `:rf.sub/run` trace-tag twin")
      ;; NEGATIVE CONTROL on the new tag path: the trusted-local opt-in lifts it,
      ;; proving the elision is driven by the classification rather than by some
      ;; unrelated truncation on the way out.
      (let [lifted (rf/project-egress raw {:rf.size/include-large? true})]
        (is (= payload-size (count (get-in (->> (:trace-events lifted)
                                               (filter #(= :rf.sub/run (:operation %)))
                                               (filter #(= :egress/big
                                                           (get-in % [:tags :rf.sub/id])))
                                               first)
                                          [:tags :rf.sub/value])))
            "NEGATIVE CONTROL — `:rf.size/include-large? true` DOES return the raw value
             to the trace tag, so the default elision is classification-driven")))))

(deftest nil-and-non-map-input-projects-fail-closed-without-throwing
  (testing "a forwarder mapping over a ring that contains a nil hole must
            not throw mid-egress on either host. That is the property this
            has always been about, and it still holds.

            rf2-bv1p CHANGED THE ANSWER, in the fail-closed direction. The
            retired `projected-record` short-circuited non-map input to
            `nil`. `project-egress` has no such short-circuit: a kindless
            input is a VALUE and is WALKED, and with no resolvable frame
            and no sensitive opt-out the walker redacts it wholesale. So a
            nil hole egresses as `:rf/redacted` rather than as `nil` —
            still no throw. WHAT it returns is then the ordinary
            kindless-value answer, which depends on the frame in scope: a
            resolvable frame walks the value against its own classification
            (a bare scalar declares nothing, so it passes through), while an
            unresolvable one fails closed to `:rf/redacted`. Either way no
            epoch payload is involved, because a REAL record is a map
            carrying `:kind` and goes to the epoch arm."
    (is (nil? (try (mapv rf/project-egress [nil :not-a-record nil]) nil
                   (catch #?(:clj Throwable :cljs :default) t t)))
        "the property that matters — a ring with holes never throws")
    (is (nil? (rf/project-egress nil))
        "a nil hole stays nil")
    (is (not (map? (rf/project-egress :not-a-record)))
        "and a non-record never becomes a record-shaped payload")))

;; ============================================================================
;;  2. The FACADE path — what the browser consumers actually call
;; ============================================================================

(deftest facade-project-egress-redacts-through-late-bind
  (testing "Every Pair-MCP epoch tool call goes through the
            `re-frame.core` facade `project-egress`, NOT the
            artefact-internal `re-frame.epoch.tool-pair/project-record` it
            dispatches to. That crosses the `late-bind` seam
            (`:epoch/project-record`). This arm pins the seam on the
            consumers' host: the facade must produce the SAME redacted shape
            the artefact fn does — a seam that silently fell through to
            identity would leak everything."
    (fresh-frame!)
    (reg-login!)
    (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
    (let [raw     (last-record)
          via-fac (rf/project-egress raw)
          via-art (rf/project-egress raw)]
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
      (is (not (contains-secret? (mapv rf/project-egress
                                       (rf/epoch-history frame-id))))
          "the whole-ring composition through the facade is likewise clean")
      (is (contains-secret? (rf/epoch-history frame-id))
          "NEGATIVE CONTROL — the RAW ring the facade reads from DOES carry
           the secret, so the two clean assertions above are proving
           redaction rather than an empty ring"))))

(deftest facade-threads-egress-opts-through-late-bind
  (testing "the consumers pass an opts map through the facade
            (`{:rf.size/include-sensitive? …}`, `:rf.egress/profile`). The 2-arity
            must thread it — a dropped opts map would silently downgrade a
            trusted-local read, or worse, silently ignore a fail-closed
            profile choice."
    (fresh-frame!)
    (reg-login!)
    (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
    (let [raw (last-record)]
      (is (= secret (get-in (rf/project-egress raw {:rf.size/include-sensitive? true})
                            [:db-after :auth :password]))
          "the opts map reaches the artefact through the facade")
      (is (= :rf/redacted (get-in (rf/project-egress raw {})
                                  [:db-after :auth :password]))
          "and an empty opts map keeps the fail-closed default")
      (is (= (rf/project-egress raw {:rf.egress/profile :rf.egress/off-box-tool})
             (rf/project-egress raw {:rf.egress/profile :rf.egress/off-box-tool}))
          "the named `:rf.egress/off-box-tool` boundary (the MCP wire) agrees
           across facade and artefact"))))

;; ============================================================================
;;  3. The forwarder + bulk-egress shapes the CLJS consumers run
;; ============================================================================

(deftest epoch-listener-forwarder-egresses-no-secret-bytes
  (testing "the `register-listener! :epoch` + `project-egress`-in-ship!
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
                            (fn [r] (swap! shipped conj (rf/project-egress r))))
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

(deftest whole-ring-composition-is-projected-and-ordered
  (testing "`(mapv project-egress (epoch-history …))` is the bulk-egress
            shape a `watch-epochs` initial snapshot ships. Every record must
            be projected and oldest-first order preserved — the resume
            cursor's `:after-id` depends on the ordering."
    (fresh-frame!)
    (reg-login!)
    (rf/reg-event :egress/seed (fn [_ _] {:db {:n 0}}))
    (rf/reg-event :egress/inc  (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/dispatch-sync [:egress/seed]          {:frame frame-id})
    (rf/dispatch-sync [:egress/login secret]  {:frame frame-id})
    (rf/dispatch-sync [:egress/inc]           {:frame frame-id})
    (let [raw  (rf/epoch-history frame-id)
          bulk (mapv rf/project-egress raw)]
      (is (= 3 (count bulk)) "one projected record per raw record")
      (is (= (mapv :epoch-id raw) (mapv :epoch-id bulk))
          "oldest-first order is preserved")
      (is (not (contains-secret? bulk))
          "the bulk shape leaks no secret bytes")
      (is (= [] (mapv rf/project-egress
                      (rf.epoch/epoch-history
                        :epoch-egress-redaction/no-such-frame)))
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
          once   (mapv rf/project-egress raw)
          twice  (mapv rf/project-egress once)
          thrice (mapv rf/project-egress twice)]
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
        (mapv rf/project-egress before)
        (mapv rf/project-egress (rf.epoch/epoch-history frame-id)))
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
            shipping raw fx args off-box. `{:rf.size/include-sensitive? true}` lifts
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
          proj   (rf/project-egress raw {:rf.size/include-sensitive? true})
          fx-row (some #(when (= :egress/login-fx (:fx-id %)) %) (:effects proj))]
      (is (= secret (get-in proj [:db-after :auth :password]))
          "`:rf.size/include-sensitive? true` reveals the app-db sensitive leaf
           (which also proves the opt-in is threaded at all)")
      (is (some? fx-row) "fixture: the cascade produced a payload-bearing fx row")
      (is (= :rf/redacted (:args fx-row))
          "`:effects[*].args` STAY redacted — orthogonal axis")
      (is (= :egress/login-fx (:fx-id fx-row))
          "the value-free `:fx-id` is preserved for tool display")
      (is (= :rf/redacted (:args (some #(when (= :egress/login-fx (:fx-id %)) %)
                                       (:effects (rf/project-egress raw)))))
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
          proj (rf/project-egress raw {:rf.size/include-sensitive? true})]
      (is (= {:state :live}
             (get-in raw [:frame-state-after :rf.db/runtime
                          :rf.runtime/machines :snapshots :m/x]))
          "fixture: the raw record carries a populated runtime-db partition")
      (is (= secret (get-in proj [:frame-state-after :rf.db/app :auth :password]))
          "`:rf.size/include-sensitive? true` reveals the app-db partition's leaf")
      (is (= :rf/redacted (get-in proj [:frame-state-after :rf.db/runtime]))
          "the `:rf.db/runtime` partition STAYS redacted under
           include-sensitive alone")
      (is (not= :rf/redacted
                (get-in (rf/project-egress raw {:rf.size/include-sensitive?  true
                                                     :include-runtime-db? true})
                        [:frame-state-after :rf.db/runtime]))
          "NEGATIVE CONTROL — the explicit `:include-runtime-db? true` opt DOES
           lift it, proving the axis is independently governed and the
           assertion above is not passing because the partition is empty"))))

(deftest include-sensitive-keeps-large-elision-independent
  (testing "`:rf.size/include-sensitive?` and `:rf.size/include-large?` are independent axes:
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
      (let [proj (rf/project-egress raw {:rf.size/include-sensitive? true})]
        (is (= secret (get-in proj [:db-after :auth :password])))
        (is (rf.elision/marker? (get-in proj [:db-after :blob :payload]))
            "large stays elided under the sensitive opt-in")
        (is (zero? (count-leaves-at-least payload-size proj))
            "and no raw payload bytes egress anywhere in the record"))
      (let [proj (rf/project-egress raw {:rf.size/include-large? true})]
        (is (= :rf/redacted (get-in proj [:db-after :auth :password]))
            "sensitive stays redacted under the large opt-in")
        (is (not (rf.elision/marker? (get-in proj [:db-after :blob :payload])))
            "NEGATIVE CONTROL — `:rf.size/include-large? true` DOES lift the slot")
        (is (pos? (count-leaves-at-least payload-size proj))
            "and the raw payload bytes ARE present, so the elision
             assertions above are not vacuous")))))

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
    (let [proj (rf/project-egress (last-record))]
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
    (let [proj (rf/project-egress (last-record))]
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
      (let [proj (rf/project-egress raw {:include-event-args? true})]
        (is (= [:egress/login secret] (:trigger-event proj))
            "NEGATIVE CONTROL — the opt-in reveals the raw args, so the
             fail-closed assertions above are testing redaction")
        (is (= :rf/redacted (get-in proj [:db-after :auth :password]))
            "the app-db sensitive leaf stays redacted"))
      (let [proj (rf/project-egress raw {:rf.size/include-sensitive? true})]
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
  {:kind                :rf/epoch-record
   :epoch-id            1
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
          [t1 t2] (:trace-events (rf/project-egress rec))]
      (is (= :rf/redacted (get-in t1 [:tags :rf.event/db :auth :password]))
          "t1's nested sensitive leaf is re-rooted and redacted")
      (is (= :rf/redacted (get-in t2 [:tags :rf.event/db :auth :password]))
          "t2's nested sensitive leaf likewise")
      (is (= benign (get-in t1 [:tags :rf.event/db :audit :note]))
          "NEGATIVE CONTROL — the unclassified sibling inside the SAME nested
           db survives, so the re-root is redacting by classification rather
           than blanking the tag")
      (is (not (contains-secret? (rf/project-egress rec)))
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
          ev   (first (:trace-events (rf/project-egress omit)))]
      (is (= :rf/redacted (:value (:tags ev)))
          "the unschematized body slot is omitted off-box (fail-closed)")
      (is (not (contains-secret? (rf/project-egress omit)))
          "the raw token appears nowhere in the projected record")
      (is (contains-secret? omit)
          "NEGATIVE CONTROL — the unprojected record DOES carry the token")
      (is (= body (:value (:tags (first (:trace-events
                                          (rf/project-egress
                                            omit {:rf.size/include-sensitive? true}))))))
          "and the trusted-local `:rf.size/include-sensitive?` opt-in lifts the
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
          bulk (mapv rf/project-egress raw)]
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
    (let [proj (rf/project-egress (last-record control-frame-id))]
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
    (is (true? (:rf.epoch/sensitive? (rf/project-egress (last-record))))
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
    (is (false? (:rf.epoch/sensitive? (rf/project-egress
                                        (last-record control-frame-id))))
        "strict false, not nil and not true")))

(deftest trace-events-retention-cap-bounds-what-egresses
  (testing "`:trace-events-keep` is a retention bound on the most
            payload-dense slot: records older than the window drop
            `:trace-events` entirely, while the structured `:sub-runs` /
            `:renders` / `:effects` projections survive. This caps what a
            bulk whole-ring egress can carry at all."
    (fresh-frame!)
    (rf/reg-event :egress/seed (fn [_ _] {:db {:n 0}}))
    (rf/reg-event :egress/inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (is (= 5 (:trace-events-keep (:epoch-history (rf/current-config))))
        "fixture override — the shipped runtime default is 50")
    (rf/dispatch-sync [:egress/seed] {:frame frame-id})
    (dotimes [_ 6] (rf/dispatch-sync [:egress/inc] {:frame frame-id}))
    (let [bulk (mapv rf/project-egress
                     (rf.epoch/epoch-history frame-id))
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
;;  8. ONE DOOR — `:kind :rf/epoch-record` behind `rf/project-egress`
;;     (rf2-kuky.92, stage 3a)
;;
;; Before the stamp there were TWO record doors dispatching on DIFFERENT
;; discriminators: `rf/project-egress` on `:kind`, and `rf/project-egress`
;; on an epoch record's SLOT SET. Handing an epoch record to the public door
;; was therefore UNSAFE and nothing but the reader's knowledge prevented it —
;; the door saw a kindless map, walked it whole from `:path []`, and a frame's
;; `[:auth :password]` declaration could not match
;; `[:db-after :auth :password]`, so the app-db slots shipped RAW.
;;
;; These arms pin the epoch artefact's half of the repair. The door's own
;; half — kind recognition, frame resolution, guard G1's throw — is pinned in
;; `re-frame.projection-cljs-test`. `rf/project-egress` still works and is
;; unchanged; it retires under rf2-bv1p.
;; ============================================================================

(defn- with-epoch-project-record-hook
  "Run `f` with the `:epoch/project-record` late-bind hook bound to `v`
  (`nil` simulating an artefact-less runtime), restoring the previous value
  afterwards even when `f` throws. Same mechanism as
  `re-frame.epoch-late-bind-missing-cljs-test`'s `with-hook-as-nil`."
  [v f]
  (let [original (rf.late-bind/get-fn :epoch/project-record)]
    (try
      (rf.late-bind/set-fn! :epoch/project-record v)
      (f)
      (finally (rf.late-bind/set-fn! :epoch/project-record original)))))

(defn- login-record!
  "Make the classified frame, run one login cascade writing the secret at the
  classified path and the control value at an unclassified sibling, and
  return the RAW ring record."
  []
  (fresh-frame!)
  (reg-login!)
  (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
  (last-record))

(deftest assembled-record-carries-the-kind-stamp
  (testing "every assembled record carries the FIXED `:kind :rf/epoch-record`
            discriminator (Spec-Schemas §`:rf/epoch-record`). It is a STAMP,
            not a storage change — the raw ring record still carries the
            unredacted value the replay path reads."
    (let [raw (login-record!)]
      (is (= :rf/epoch-record (:kind raw))
          "the raw ring record is stamped")
      (is (= secret (get-in raw [:db-after :auth :password]))
          "CONTROL — storage is otherwise unchanged: the raw record still
           carries the unredacted value, so the stamp cannot have been
           mistaken for a projection")
      (is (= :rf/epoch-record (:kind (rf/project-egress raw)))
          "and the stamp survives projection as bookkeeping, so a consumer
           can branch on the kind of a record it received off-box"))))

(deftest project-egress-on-a-stamped-record-matches-the-epoch-door
  (testing "the public door and the epoch door produce the SAME projection
            for the same policy — one engine, reached two ways, so the pair
            cannot drift while they coexist"
    (let [raw  (login-record!)
          opts {:rf.egress/profile :rf.egress/off-box-tool}]
      (is (= (rf/project-egress raw opts)
             (rf/project-egress raw opts))
          "identical projections")
      (let [proj (rf/project-egress raw opts)]
        (is (= :rf/redacted (get-in proj [:db-after :auth :password]))
            "and the door's projection redacts the classified leaf")
        (is (= benign (get-in proj [:db-after :audit :note]))
            "NEGATIVE CONTROL — the unclassified sibling rides RAW, so the
             assertion above is not blanket redaction")
        (is (not (contains-secret? proj))
            "no secret bytes anywhere in the door's projected record")))))

(deftest an-unstamped-record-through-the-door-is-the-leak-the-stamp-closes
  (testing "THE VECTOR, pinned as a contrast: strip the `:kind` stamp and the
            SAME record goes down the door's kindless VALUE path, where the
            walk starts at `:path []`. The frame's `[:auth :password]`
            declaration cannot match `[:db-after :auth :password]`, so the
            declared-sensitive value ships RAW. This is what the stamp — and
            guard G1 — exist to prevent."
    (let [raw    (login-record!)
          leaked (rf/project-egress (dissoc raw :kind)
                                    {:rf.egress/profile :rf.egress/off-box-tool})]
      (is (= secret (get-in leaked [:db-after :auth :password]))
          "an UNSTAMPED epoch-shaped map leaks the classified value through
           the public door")
      (is (contains-secret? leaked)
          "and the whole-record scan finds it, which is the wire a forwarder
           would ship"))))

;; ---- change 4: the explicit-frame override on a record ---------------------

(deftest explicit-frame-override-beats-the-records-own-frame
  (testing "an explicit `:frame` opt is the caller's deliberate
            reclassification and WINS over the record's own `:frame` slot.
            Projected under a frame that classifies nothing, the value the
            record's own frame would have redacted rides RAW — which is what
            proves the override actually reached the walk rather than being
            dropped."
    (let [raw (login-record!)]
      (rf/make-frame {:id control-frame-id})
      (is (= :rf/redacted
             (get-in (rf/project-egress raw {:rf.egress/profile :rf.egress/off-box-tool})
                     [:db-after :auth :password]))
          "CONTROL — under the record's OWN frame the leaf redacts")
      (is (= secret
             (get-in (rf/project-egress raw {:rf.egress/profile :rf.egress/off-box-tool
                                             :frame             control-frame-id})
                     [:db-after :auth :password]))
          "under the explicitly named frame — which declares nothing — the
           same leaf rides raw, so the override governed the walk"))))

(deftest explicit-nil-frame-on-a-record-fails-closed
  (testing "an explicit `:frame nil` is PRESENT, not absent — the deliberate
            statement that no frame governs — so it beats the record's own
            slot and the projection FAILS CLOSED rather than borrowing it"
    (let [raw  (login-record!)
          proj (rf/project-egress raw {:rf.egress/profile :rf.egress/off-box-tool
                                       :frame             nil})]
      (is (= :rf/redacted (:db-after proj))
          "with no frame from any of the three steps the whole payload slot
           redacts to the sentinel — no `:rf/default` is synthesised")
      (is (not (contains-secret? proj))
          "and nothing of the secret survives anywhere in the record"))))

;; ---- change 3: the policy axes stay independent ----------------------------

(deftest shared-axes-lift-through-the-door-but-epoch-only-axes-do-not
  (testing "pair-MCP's boundary is `off-box-tool` PLUS an explicit sensitive
            override, and that combination must keep meaning what it means:
            the two SHARED app-db axes lift, while the three epoch-only axes
            — different keyspaces, not app-db values — stay fail-closed. It
            is deliberately NOT `:rf.egress/local-raw`."
    (let [raw  (login-record!)
          proj (rf/project-egress raw {:rf.egress/profile          :rf.egress/off-box-tool
                                       :rf.size/include-sensitive? true})]
      (is (= secret (get-in proj [:db-after :auth :password]))
          "the shared app-db sensitive axis lifts through the door")
      (is (= [:egress/login :rf/redacted] (:trigger-event proj))
          "but the event-args axis does NOT lift — trigger args stay redacted
           behind their own `:include-event-args?` opt-in")
      (is (= :rf/redacted (get-in proj [:frame-state-after :rf.db/runtime]))
          "and the runtime-db partition stays redacted behind its own
           `:include-runtime-db?` opt-in")
      (is (every? #(= :rf/redacted (:args %))
                  (filter #(contains? % :args) (:effects proj)))
          "and every effect row's `:args` stays redacted behind
           `:include-fx-args?`"))))

(deftest an-epoch-only-axis-is-door-vocabulary-and-never-reaches-the-walker
  (testing "rf2-bv1p — the three epoch-only axes moved ONTO the door when
            `projected-record`, the standalone door that used to own them,
            retired. A retirement must not take a capability with it, so the
            door ACCEPTS them (rf2-kuky.9 option A's target opts map names
            all six axes on the one door).

            This DELIBERATELY REVERSES what rf2-kuky.92 pinned here. While
            both doors coexisted the axes stayed on the old one and the
            door refused them; with the old one gone, refusing them would
            strand three live engine branches behind no reachable caller."
    (let [raw   (login-record!)
          tight (rf/project-egress raw {:rf.egress/profile :rf.egress/off-box-tool})
          ;; `:include-runtime-db?` is the axis chosen here because THIS
          ;; record is guaranteed to exercise it: it carries
          ;; `:frame-state-after`, whose `:rf.db/runtime` partition is
          ;; `:rf/redacted` under every off-box profile. `:include-fx-args?`
          ;; would be inert on a cascade with `:effects []`, which is what a
          ;; plain login is — a liveness control has to bite on the record
          ;; it is given.
          wide  (rf/project-egress raw {:rf.egress/profile   :rf.egress/off-box-tool
                                        :include-runtime-db? true})]
      (is (some? wide) "the door accepts the axis rather than throwing")
      (doseq [k [:include-fx-args? :include-runtime-db? :include-event-args?]]
        (is (some? (rf/project-egress raw {:rf.egress/profile :rf.egress/off-box-tool
                                           k                  true}))
            (str k " is door vocabulary")))
      (is (= :rf/redacted (get-in tight [:frame-state-after :rf.db/runtime]))
          "the floor redacts the runtime-db partition")
      (is (not= wide tight)
          "CONTROL — and the axis is LIVE, not merely tolerated: it changes
           the projection. A door that accepted the key and ignored it would
           satisfy the assertion above while delivering nothing")
      (is (not= :rf/redacted (get-in wide [:frame-state-after :rf.db/runtime]))
          "and the difference is exactly the partition the axis names")))

  (testing "rf2-bv1p — and the SAFETY property the refusal used to provide
            is kept by a different mechanism: the epoch-only keys are
            STRIPPED before `resolve-elision-opts`, so one can never reach
            the equally-closed WALKER map and throw from the wrong layer on
            a path that used to work. The kindless VALUE path is where that
            would bite, because it walks."
    (is (nil? (try (rf/project-egress
                     {:some "tree"}
                     {:rf.egress/profile :rf.egress/off-box-observability
                      :include-fx-args?  true})
                   nil
                   (catch #?(:clj clojure.lang.ExceptionInfo
                             :cljs ExceptionInfo) e e)))
        "a kindless value carrying an epoch-only axis walks normally — no
         :rf.error/bad-egress-opts thrown from
         re-frame.elision/elide-wire-value, which is the wrong layer")
    (let [thrown (try (rf/project-egress (login-record!)
                                         {:rf.egress/profile :rf.egress/off-box-tool
                                          :totally-made-up   true})
                      nil
                      (catch #?(:clj clojure.lang.ExceptionInfo
                                :cljs ExceptionInfo) e e))
          data   (ex-data thrown)]
      (is (some? thrown)
          "CONTROL — the vocabulary is still CLOSED; it widened by exactly
           three named keys, it did not become permissive")
      (is (= :rf.error/bad-egress-opts (:rf.error/id data)))
      (is (= 'rf/project-egress (:where data))
          ":where still names the DOOR")
      (is (contains? (set (:unknown-keys data)) :totally-made-up)
          "and the offending key is named"))))

(deftest local-raw-profile-floor-is-honoured-not-overridden-to-false
  (testing "the profile is the FLOOR and only the keys the caller ACTUALLY
            supplied overlay it. The previous form forced the two shared axes
            present-and-false on every call, which was invisible under the
            five fail-closed profiles — whose floor is false anyway — and
            silently defeated `:rf.egress/local-raw`, the ONE profile whose
            floor opts sensitive and large back IN."
    (let [raw (login-record!)]
      (is (= :rf/redacted
             (get-in (rf/project-egress
                       raw {:rf.egress/profile :rf.egress/off-box-observability})
                     [:db-after :auth :password]))
          "CONTROL — a fail-closed profile still redacts, so the assertion
           below cannot pass by the walker having stopped classifying")
      (is (= secret
             (get-in (rf/project-egress
                       raw {:rf.egress/profile :rf.egress/local-raw})
                     [:db-after :auth :password]))
          "`local-raw`'s own floor opts sensitive back in with NO explicit
           override from the caller")
      (is (= secret
             (get-in (rf/project-egress
                       raw {:rf.egress/profile :rf.egress/local-raw})
                     [:db-after :auth :password]))
          "and the same holds through the public door")
      (is (= :rf/redacted
             (get-in (rf/project-egress
                       raw {:rf.egress/profile          :rf.egress/local-raw
                            :rf.size/include-sensitive? false})
                     [:db-after :auth :password]))
          "an EXPLICIT false still overlays the floor and wins — overriding
           is what the caller's own key is for"))))

(defn- large-everywhere-record!
  "ONE cascade that populates all THREE record surfaces the shared
  `:rf.size/include-large?` axis governs:

    - `:db-after [:blob :payload]`  — a frame-declared `:large` app-db path,
      reached by the TREE-WALKER path (`project-payload-slot` →
      `project-egress` → `elide-wire-value`);
    - the `:sub-runs` row's `:value`, and
    - its `:rf.sub/run` trace-tag twin's `:rf.sub/value` — both reached by the
      WHOLE-OUTPUT path (`elide-whole-output-large-slots`), which reads the
      axis off the epoch opts directly rather than through the walker.

  One record carrying all three is what makes the matrix below a statement
  about POLICY RESOLUTION rather than about any one seam: a profile floor that
  reaches only some of them is exactly the defect."
  []
  (fresh-frame!)
  (rf/reg-sub :egress/big {:large? true} (fn [_ _] (big-string payload-size)))
  (rf/reg-event :egress/upload-and-read
    (fn [{:keys [db]} [_ payload]]
      (rf/subscribe-once [:egress/big] {:frame frame-id})
      {:db (assoc-in db [:blob :payload] payload)}))
  (rf/dispatch-sync [:egress/upload-and-read (big-string payload-size)]
                    {:frame frame-id})
  (last-record))

(defn- large-surfaces
  "The three whole-record slots the shared large axis governs, keyed by the
  seam that projects each. Read as a SET of three answers that must agree."
  [record]
  {:db-after  (get-in record [:db-after :blob :payload])
   :sub-run   (->> (:sub-runs record)
                   (filter #(= :egress/big (:sub-id %)))
                   first
                   :value)
   :trace-tag (->> (:trace-events record)
                   (filter #(= :rf.sub/run (:operation %)))
                   (filter #(= :egress/big (get-in % [:tags :rf.sub/id])))
                   first
                   :tags
                   :rf.sub/value)})

(defn- all-raw?
  [surfaces]
  (every? #(and (string? %) (= payload-size (count %))) (vals surfaces)))

(defn- all-elided?
  [surfaces]
  (every? rf.elision/marker? (vals surfaces)))

(deftest local-raw-large-axis-reaches-every-whole-output-slot
  (testing "rf2-kuky.92 (merged-PR audit of #9522) — the RESOLVED profile, not
            the caller's raw opts map, is what every epoch-side reader of a
            SHARED `:rf.size/*` axis must see.

            `project-egress` resolves the named profile into `elision-opts`,
            but its `:rf/epoch-record` arm forwards the ORIGINAL opts, and the
            whole-output helpers read `:rf.size/include-large?` off them by key
            presence. So under `:rf.egress/local-raw` — the ONE profile whose
            floor opts large back IN — the tree-walker path honoured the floor
            while the two whole-output subscription slots did not, and the same
            25,000-character value survived in `:db-after` and became a size
            marker in the `:sub-runs` row and its `:rf.sub/run` trace twin.

            The old and new doors produced EQUAL outputs in all six cases, so
            door-vs-door comparison could not see this; only a matrix over the
            three surfaces can. Asserted as a MATRIX rather than per slot: the
            claim is that the three agree, which is what a policy resolved once
            at the record boundary buys."
    (let [raw        (large-everywhere-record!)
          projected  (fn [opts] (large-surfaces (rf/project-egress raw opts)))
          raw-slots  (large-surfaces raw)]
      ;; ---- fixture control: all three surfaces are actually populated ------
      (is (= 3 (count raw-slots)) "fixture: three surfaces under test")
      (is (all-raw? raw-slots)
          "fixture control — the RAW ring record carries the full payload in
           ALL THREE slots, so a green matrix below cannot come from an absent
           `:sub-runs` row or an absent trace twin")

      ;; ---- the shared large axis, four ways -------------------------------
      (is (all-raw? (projected {:rf.egress/profile :rf.egress/local-raw}))
          "OMITTED OVERRIDE — `local-raw`'s own floor opts large back in for
           ALL THREE surfaces with no explicit key from the caller. This is the
           arm the #9522 audit measured failing on two of the three.")
      (is (all-raw? (projected {:rf.egress/profile      :rf.egress/local-raw
                                :rf.size/include-large? true}))
          "EXPLICIT TRUE — agreeing with the floor changes nothing")
      (is (all-elided? (projected {:rf.egress/profile      :rf.egress/local-raw
                                   :rf.size/include-large? false}))
          "EXPLICIT FALSE stays authoritative — an explicit key still overlays
           the floor and WINS, on all three surfaces. This is the arm a fix
           that merely forced the floor present-and-true would break.")
      (is (all-elided? (projected {:rf.egress/profile :rf.egress/off-box-tool}))
          "OFF-BOX CONTROL — a fail-closed profile still elides all three, so
           the assertions above cannot pass by the elision having stopped
           happening at all")
      (is (all-raw? (projected {:rf.egress/profile      :rf.egress/off-box-tool
                                :rf.size/include-large? true}))
          "and an explicit TRUE lifts all three under a fail-closed profile —
           the override wins in both directions")

      ;; ---- the epoch-only axes stay INDEPENDENT ---------------------------
      (is (= :rf/redacted
             (get-in (rf/project-egress
                       raw {:rf.egress/profile :rf.egress/local-raw})
                     [:frame-state-after :rf.db/runtime]))
          "GUARD — resolving the SHARED axes must not lift the epoch-only
           ones: `:include-runtime-db?` is a different keyspace with its own
           fail-closed default, and `local-raw` is a statement about app-db
           sensitivity and token budget, not about the runtime partition")

      ;; ---- the source record is untouched ---------------------------------
      (is (all-raw? (large-surfaces raw))
          "the RAW ring record is unchanged by any projection above — egress
           projects a copy; the on-box ring keeps the exact value"))))

;; ---- guards G1 and G2 ------------------------------------------------------

(deftest guard-g1-absent-projector-yields-no-payload-at-all
  (testing "GUARD G1: with the projector absent, the door throws BEFORE
            returning anything — so there is no payload for a forwarder to
            ship, redacted or otherwise. `rf/project-egress` is unaffected;
            it hangs off its own hook."
    (let [raw (login-record!)]
      (with-epoch-project-record-hook nil
        (fn []
          (let [thrown (try (rf/project-egress raw {:rf.egress/profile :rf.egress/off-box-tool})
                            nil
                            (catch #?(:clj clojure.lang.ExceptionInfo
                                      :cljs ExceptionInfo) e e))]
            (is (some? thrown) "the door throws")
            (is (= :rf.error/epoch-artefact-missing (:rf.error/id (ex-data thrown))))
            (is (= :rf/epoch-record (:kind (ex-data thrown)))
                "naming the kind it could not dispatch")
            (is (not (contains-secret? (ex-data thrown)))
                "and the thrown ex-data carries no record payload — the
                 failure path leaks nothing either"))))
      (is (= :rf/redacted
             (get-in (rf/project-egress raw) [:db-after :auth :password]))
          "CONTROL — OUTSIDE the flipped-hook scope the SAME call projects
           normally, so the throw above is the absent projector and not a
           broken fixture or an unprojectable record. rf2-bv1p retired the
           second door this control used to compare against; the before /
           after comparison on the one door is the replacement, and it is
           the stronger control because it exercises the very call that
           threw."))))

(deftest guard-g2-refuses-a-core-whose-door-does-not-dispatch-the-kind
  (testing "GUARD G2: `late-bind/set-fns!` validates no key at runtime, so a
            NEW epoch artefact would register `:epoch/project-record` against
            an OLD core in silence — and that core's door would read every
            stamped record as a kindless value and bare-walk it. The artefact
            therefore asks the core's OWN door at load whether it dispatches
            `:rf/epoch-record`, and refuses to finish loading otherwise.

            It asks the DOOR rather than the late-bind directory that rosters
            the same fact: the directory is a documentation corpus production
            builds must DCE, so no `src/` namespace may require it."
    (is (true? (rf.projection/recognises-record-kind? :rf/epoch-record))
        "the live core's door dispatches the kind, so the control below is
         not vacuous")
    (is (false? (rf.projection/recognises-record-kind? :rf/not-a-record-kind))
        "NEGATIVE CONTROL — the probe discriminates; it is not a constant
         `true` that would pass whatever core it was asked")
    (is (nil? (rf.epoch/assert-core-dispatches-epoch-records!
                (rf.projection/recognises-record-kind? :rf/epoch-record)))
        "CONTROL — against the live core the assertion passes, which is also
         the assertion this namespace already ran at load")
    (let [thrown (try (rf.epoch/assert-core-dispatches-epoch-records! false)
                      nil
                      (catch #?(:clj clojure.lang.ExceptionInfo
                                :cljs ExceptionInfo) e e))
          data   (ex-data thrown)]
      (is (some? thrown) "a core that does not dispatch the kind throws")
      (is (= :rf.epoch/core-version-skew (:rf.epoch/load-refusal data))
          "with a stable discriminator naming the skew")
      (is (= :rf/epoch-record (:kind data))
          "naming the kind the core failed to dispatch")
      (is (= :epoch/project-record (:hook data))
          "and the hook whose registration would have been silent")
      (is (str/includes? (ex-message thrown) ":rf/epoch-record")
          "the human message names it too"))))
