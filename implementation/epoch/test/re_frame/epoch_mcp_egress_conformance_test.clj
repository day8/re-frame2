(ns re-frame.epoch-mcp-egress-conformance-test
  "MCP-style egress conformance for `projected-record` and
  `projected-history`. Any process-boundary forwarder must project raw epoch
  records before egress.

  This file pins the contract from the **forwarder perspective**: the
  per-leaf redaction matrix lives in `epoch_privacy_test.clj`; the
  redact-fn composition matrix lives in `epoch_redact_fn_projection_test.clj`.
  Here we exercise the full off-box-forwarder pattern an MCP server runs:

    1. Build a realistic mixed ring (sensitive + large + bookkeeping-only
       records, halted-destroy records, the empty case). `large` means BOTH
       shapes a large value can arrive in: the app-db PATH declaration and
       the whole-output `:large?` sub REGISTRATION stamp (rf2-isp3i). The
       second is not app-db-rooted, so a fixture that declared only paths
       left this gate's own claim — no raw large bytes ANYWHERE — scanning
       a record the shape never appeared in. `sensitive` likewise means BOTH
       families: the app-db path AND the resource/mutation trace family, whose
       owner-local SCOPED KEYS are classified by the resource OWNER's
       `:sensitive?` declaration rather than by any app-db path (rf2-isp3i's
       rescoping — before the widening this file contained no `reg-resource`
       at all, so the family's projector had never been reached from the gate
       that owns the no-raw-sensitive-egress claim).
    2. Run the ring through `projected-record` (per-record forwarder shape,
       e.g. `register-epoch-listener!` ship!) AND `projected-history` (bulk-egress
       shape, e.g. `watch-epochs` initial snapshot).
    3. Assert the off-box egress contract:
         - No raw sensitive bytes anywhere in the projected output (the
           security claim the MCP wire boundary depends on).
         - No raw large bytes anywhere in the projected output (the
           token-budget claim the MCP wire boundary depends on).
         - Bookkeeping slots (`:epoch-id`, `:frame`, `:committed-at`,
           `:event-id`, `:outcome`, `:halt-reason`, `:schema-digest`,
           `:rf.epoch/sensitive?`) are preserved byte-for-byte.
         - The two functions agree (projected-history is fn-equivalent to
           `(mapv projected-record (epoch-history fid))`).
         - The functions are pure + idempotent — calling them twice over
           the same input is structurally identical, so a forwarder that
           accidentally double-projects (e.g. middleware composition)
           does not corrupt the wire shape.
         - Ordering is deterministic (oldest-first), matching the raw
           ring; an MCP `watch-epochs` initial snapshot relies on this
           to set the resume-cursor's `:after-id`.
         - No side effects: `projected-record` and `projected-history`
           never mutate the underlying ring, the schemas registry, or
           the elision registry.

  Why this file lives in implementation/epoch/test rather than under an
  MCP-server test tree: MCP-server test runners are shadow-cljs + Node
  (`npm test` -> `out/server-test.js`), and the artefacts do not
  statically depend on `re-frame.epoch` — the runtime accessors get into
  the running app via the injected-runtime path
  (`day8.re-frame2-xray.runtime`), not the MCP-server bundle. The
  framework's epoch artefact owns the projection emission site (Spec
  Security.md §Epoch privacy posture); pinning conformance from
  the artefact side keeps the test on the JVM next to the contract owner.
  An MCP-side end-to-end test is the job of the SDK-driven conformance
  `test/end-to-end-*.cjs` paths if and when MCP-server epoch tools ship."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.epoch :as epoch]
            [re-frame.frame :as frame]
            ;; rf2-isp3i — `fx/reg-fx`, the plain fn, NOT the `rf/reg-fx` macro:
            ;; see `install-resource-family!` for why the difference decides
            ;; whether the frame's default image can still be reprojected.
            [re-frame.fx :as fx]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]
            ;; Side-effect requires (mirror epoch_test.clj fixture).
            [re-frame.machines]
            ;; rf2-isp3i — load-bearing: registers the `:rf.resource/*` events
            ;; this fixture dispatches AND publishes the late-bound
            ;; `:resources/project-resource-trace-egress` hook the projection
            ;; consults from `omit-off-box-resource-trace-keys`. Without the
            ;; hook that arm is a nil-safe no-op and the family's rows would
            ;; egress unprojected — the gate would report green over the very
            ;; thing it claims.
            [re-frame.resources]
            ;; rf2-isp3i — `ensure` lowers into the managed-HTTP transport,
            ;; which fails closed with `:rf.error/http-artefact-missing` unless
            ;; this ns has published its late-bind feature probe. Test-only;
            ;; production epoch stays http-free.
            [re-frame.http.managed]))

;; ---- fixtures --------------------------------------------------------------
;;
;; rf2-yw1w1u — canonical capture/restore fixture. Snapshots the
;; registrar at ns-load + restores around each test, fires the epoch
;; reset-hook table (history / listeners / config-to-default), and the
;; `:init-fn` re-applies the suite's non-default `:trace-events-keep 5`
;; (NOT the shipped 50 = :depth; Mike pair-debug 2026-05-27) through the
;; public `configure!` boundary — no test ns reaches into the private
;; `state/config` var.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (rf/configure! {:epoch-history {:trace-events-keep 5}}))}))

;; ---- helpers ---------------------------------------------------------------

(defn- big-string [n] (apply str (repeat n "X")))

(def ^:private secret-password "topsecret-do-not-leak")
(def ^:private payload-size    25000)

;; rf2-isp3i — the whole-output `:large?` SUB shape. A `:large?` stamp on a
;; `reg-sub` is a REGISTRATION marker, not an app-db path declaration, so the
;; app-db-rooted classification this fixture installs below structurally cannot
;; reach it. Before this widening the fixture inhabited only the path shape, and
;; the "no raw large bytes anywhere" scans below therefore ran over a record
;; that had no sub-output slot in it at all — which is how rf2-irwsq shipped a
;; 25000-char payload raw at `[:trace-events <i> :tags :rf.sub/value]` past this
;; very gate.
(def ^:private large-sub-id   :sub/whole-output-large)
;; An UNMARKED sibling sub whose value rides the same two egress slots. It is
;; the negative control: if the elision below ever became a blanket truncation
;; (or the fixture stopped reading subs), this value would stop arriving raw.
(def ^:private control-sub-id :sub/unmarked-control)
(def ^:private control-note   "mcp-egress-unmarked-control")

(defn- install-mcp-style-schemas!
  "A realistic mixed classification set: one sensitive path
  (`[:auth :password]`) and one large path (`[:blob :payload]`) against
  `frame-id`. Matches the shape an app exercising both privacy defences
  would declare.

  EP-0025: durable app-db classification rides the commit-plane
  classification effects. Seeded through `elision/apply-classification-
  effects` (`:source :effect`) — the same registry write a `reg-event`
  returning `:sensitive` / `:large` performs. The frame container is
  make-frame'd by each deftest before this runs. (Classification is
  value-independent: the cascade legitimately leaves each path absent in
  some steps, and a classified path is a harmless no-op over an absent
  value.)"
  [frame-id]
  (frame/swap-runtime-db! frame-id
    (fn [rt] (elision/apply-classification-effects rt
               {:sensitive [[:auth :password]]
                :large     [[:blob :payload]]})))
  nil)

(defn- drive-mixed-ring!
  "Drive a deterministic, mixed cascade matrix against `frame-id`:
    - :seed — non-sensitive bookkeeping
    - :login — writes the sensitive path (secret value closed-over in the
              handler so this fixture exercises the APP-DB classification
              axis specifically; the frame-declared sensitive path is the
              leaf the projection's wire-elision walker matches against.
              The trigger-event event-args axis is exercised separately by
              the rf2-nm611o `forwarder-trigger-event-*` tests, which drive
              the secret IN the event vector and assert it fails closed —
              before rf2-nm611o the walker could not match arbitrary args,
              so this fixture kept them out; that constraint is now lifted)
    - :upload — writes the large path (large payload closed-over in the
                handler for the same reason)
    - :inc — non-sensitive again
    - :read-subs — reads a whole-output `:large?` sub and an unmarked
                   control sub (rf2-isp3i). This is the SECOND large
                   shape, and it is not app-db-rooted: the `:large?`
                   stamp rides the sub's REGISTRATION, and the computed
                   value reaches egress through two slots of the record
                   the app-db walker never visits — the structured
                   `:sub-runs` row (`:value` / `:prev-value`, projected by
                   `elide-sub-run-row`) and the `:rf.sub/run` trace tag
                   (`:rf.sub/value` / `:rf.sub/prev-value`, projected by
                   `elide-large-sub-trace-values`). Both callers of the
                   shared `elide-whole-output-large-slots` rule are
                   therefore inhabited by one cascade.
  Driven LAST so the existing index-addressed assertions (`(nth _ 1)` =
  :login, `(nth _ 2)` = :upload) keep addressing the same cascades. Five
  cascades against this suite's `:trace-events-keep 5` means every record
  retains its `:trace-events`, so the tag slot is present on all of them.
  Returns the resulting `(epoch-history frame-id)` for direct comparison
  with `(projected-history frame-id)`."
  [frame-id]
  (rf/reg-event :seed   (fn [{:keys [db]} _] {:db {:n 0}}))
  (rf/reg-event :login  (fn [{:keys [db]} _] {:db (assoc-in db [:auth :password] secret-password)}))
  (rf/reg-event :upload (fn [{:keys [db]} _] {:db (assoc-in db [:blob :payload] (big-string payload-size))}))
  (rf/reg-event :inc    (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (rf/reg-sub large-sub-id   {:large? true} (fn [_ _] (big-string payload-size)))
  (rf/reg-sub control-sub-id                (fn [_ _] control-note))
  (rf/reg-event :read-subs
    (fn [_ _]
      (rf/subscribe-once [large-sub-id]   {:frame frame-id})
      (rf/subscribe-once [control-sub-id] {:frame frame-id})
      {}))
  (rf/dispatch-sync [:seed]      {:frame frame-id})
  (rf/dispatch-sync [:login]     {:frame frame-id})
  (rf/dispatch-sync [:upload]    {:frame frame-id})
  (rf/dispatch-sync [:inc]       {:frame frame-id})
  (rf/dispatch-sync [:read-subs] {:frame frame-id})
  (rf/epoch-history frame-id))

(defn- sub-run-row
  "The structured `:sub-runs` row for `sub-id` in `record` — the slot
  `elide-sub-run-row` projects."
  [record sub-id]
  (->> (:sub-runs record) (filter #(= sub-id (:sub-id %))) first))

(defn- sub-run-tags
  "The `:rf.sub/run` trace-event tags for `sub-id` in `record` — the slot
  `elide-large-sub-trace-values` projects. A DIFFERENT slot from the row
  above carrying the SAME computed value; eliding only one is the leak
  rf2-irwsq shipped."
  [record sub-id]
  (->> (:trace-events record)
       (filter #(and (= :rf.sub/run (:operation %))
                     (= sub-id (get-in % [:tags :rf.sub/id]))))
       first
       :tags))

(defn- contains-secret?
  "Walk an arbitrary EDN value looking for the exact secret string. Used
  as the cross-cutting 'no raw sensitive bytes anywhere in the projected
  output' check — the MCP wire boundary's promise. Returns true when ANY
  leaf in the structure equals (or contains as a substring) the secret."
  [x]
  (cond
    (string? x) (.contains ^String x ^String secret-password)
    (map? x)    (or (some contains-secret? (keys x))
                    (some contains-secret? (vals x)))
    (coll? x)   (some contains-secret? x)
    :else       false))

(defn- cedn-tokens
  "Every CEDN-1 encoded scoped key surviving anywhere in `x`. Broader than
  `contains-secret?`: a `state/key-id` is `identity/canonical-bytes` — a
  REVERSIBLE PLAINTEXT encoding, not a digest — so a key-id in a trace tag
  discloses the entry's scope + params in the clear inside a string the
  shape-driven projector can only read as an opaque scalar (rf2-5o52l). This
  catches ANY key-id, not only one carrying this suite's secret, so a future
  emit site that reintroduces the class fails here even with innocuous params.

  Returns the offending tokens rather than a boolean so a failure REPORTS the
  plaintext it found — `(is (empty? …))` prints them, `(is (not (some? …)))`
  prints `true`, and the whole point of this class is that the leaked bytes are
  hiding inside something that looks opaque."
  [x]
  (vec (re-seq #"v\[k:[^\]]*\]*" (pr-str x))))

(defn- secret-leak-paths
  "Every path in `x` whose leaf string carries the secret, each with the
  offending value. The path-reporting counterpart of `contains-secret?` — same
  predicate, but a failure NAMES the slot that leaked instead of printing
  `(not (not true))`. Used by the resource-family scans, where the whole
  diagnostic value is knowing which of a row's dozen tag slots let the bytes
  through."
  [x]
  (let [found (atom [])
        walk  (fn walk [path v]
                (cond
                  (string? v) (when (.contains ^String v ^String secret-password)
                                (swap! found conj [path v]))
                  (map? v)    (doseq [[k vv] v]
                                (walk (conj path k) k)
                                (walk (conj path k) vv))
                  (coll? v)   (doseq [[i vv] (map-indexed vector v)]
                                (walk (conj path i) vv))))]
    (walk [] x)
    @found))

(defn- count-leaf-strings-at-least
  "Walk `x` and count leaf strings whose length is `>= n`. Used to bound
  the 'no raw large bytes anywhere in the projected output' check — a
  projected record MUST NOT egress the full payload as a leaf."
  [n x]
  (let [counter (atom 0)
        walk (fn walk [v]
               (cond
                 (string? v) (when (>= (count v) n) (swap! counter inc))
                 (map? v)    (do (run! walk (keys v)) (run! walk (vals v)))
                 (coll? v)   (run! walk v)))]
    (walk x)
    @counter))

;; ---- the resource/mutation trace family (rf2-isp3i) ------------------------
;;
;; The SECOND sensitive family, and the one this file could not see at all.
;;
;; An app-db `:sensitive` PATH is classified by the frame; a resource's
;; owner-local SCOPED KEY `[scope resource-id params]` is classified by the
;; resource OWNER's `:sensitive?` declaration, and once the key is copied into a
;; trace TAG the app-db-rooted walker is structurally blind to it (Spec 015 §10).
;; The resource family therefore owns its own family-level off-box projector
;; (`re-frame.resources.trace-egress/project-resource-trace-egress`), which the
;; epoch tool-pair consults through a late-bound hook. Before this widening the
;; file contained ZERO occurrences of `reg-resource` / `:rf.resource/` and never
;; required the artefact, so the projector had never had a scoped key to find and
;; the "no raw sensitive bytes ANYWHERE" scans above ran over records the family
;; never appeared in. Two P1 leaks shipped through that blind spot:
;;
;;   - rf2-wd9im — the fail-closed default covered only MAP-valued unknown slots,
;;     so a SEQUENTIAL value fell to the verbatim `:else`. The worst case is the
;;     one every row below carries: a resource `:work/id` is
;;     `[:rf.work/resource <scoped-key> <generation>]`, so the key — and with it
;;     a `:sensitive?` owner's scope and canonical params — sits one level down
;;     inside a vector under a slot no roster names.
;;   - rf2-5o52l — `:rf.resource/owner-released` named the released entries by
;;     `state/key-id`, which is `identity/canonical-bytes`: a REVERSIBLE
;;     PLAINTEXT encoding, so the secret egressed in the clear inside a string
;;     the projector correctly reads as an opaque scalar. `:resource/key` is
;;     NAMED in the projector's vocabulary and the arm still could not help — a
;;     named slot is no protection when the value in it is the wrong
;;     representation.
;;   - rf2-1kiuj — and the third: a correct projector that never RAN on the row.
;;     An `ensure` lowers into effects that address the work BY its scoped key,
;;     so the same keys ride `:rf.fx/args` / `:rf.event/fx` on `:rf.fx/*` rows,
;;     and the epoch tool-pair picks which rows take the family projector by
;;     OPERATION NAMESPACE (`resource-family-op?`) — which those rows fail. 18
;;     raw paths across the three records this fixture settles, 14 of them on
;;     the sensitive `ensure` alone, while that same record's structured
;;     `:effects[*].args` slot read `:rf/redacted` three rows below. Fixed by
;;     reaching the carriers by SLOT (`project-fx-args-egress`) rather than by
;;     widening the op roster, which is why the scans below run over the WHOLE
;;     record and the whole settled history rather than over the family rows.
;;
;; All three are reachable from the cascade `drive-resource-family!` drives, and
;; all three were MEASURED here by reverting the fix and re-running, because a
;; widening that cannot red against a defect that actually shipped has proven
;; nothing:
;;
;;   - revert rf2-wd9im (`trace_egress.cljc` back to the map-only default) → 5
;;     failures, naming all five leaking paths — the three lifecycle rows'
;;     `[:tags :work/id 1 2 :auth-token]`, the release row's `:released` and its
;;     `:aborted` — and printing raw `{:auth-token "…"}` beside raw
;;     `:rf.scope/global`;
;;   - revert rf2-5o52l (`events.cljc` + `ssr.cljc` back to key-ids) → 8
;;     failures, the report printing the raw CEDN-1 string
;;     `v[k::rf.scope/global k::secret/article m{k::auth-token s:"…"}]`, and the
;;     PLAIN control reddening too (a key-id is not a scoped-key vector, so the
;;     plain owner's `:released` member goes missing) — the fixture notices the
;;     change in both directions, not just the leaking one.
;;   - revert rf2-1kiuj (drop the `omit-off-box-fx-args-resource-keys` arm from
;;     `elide-trace-events-slot`) → see the reversion counts recorded on the
;;     bead; the whole-record and whole-history scans name every leaking carrier
;;     path, and the plain control stays GREEN in both directions, which is what
;;     proves the fix discriminates by owner rather than blanket-stripping the
;;     carriers.
;;
;; Note what the second reversion shows about the first: `:released` is NOT in
;; the projector's named `scoped-keys-slot` roster, so carrying scoped keys there
;; only redacts because rf2-wd9im's shape-driven default recognises a key
;; wherever it sits. The two fixes hold this slot up together.

(def ^:private sensitive-resource-id :secret/article)
(def ^:private plain-resource-id     :plain/article)
(def ^:private plain-slug            "mcp-egress-plain-control")
(def ^:private resource-owner        [:app :reader 1])

(defn- install-resource-family!
  "Register the two resource owners the family scans need: one `:sensitive?`
  (its scoped key's scope + params MUST tokenize off-box) and one PLAIN (its key
  MUST ride verbatim — the two-sided over-redaction control, so redacting
  everything fails as loudly as leaking). Plus a no-op managed-HTTP fx so
  `ensure` writes its `:loading` entry and emits its lifecycle rows without a
  request leaving the box.

  `fx/reg-fx`, NOT `rf/reg-fx`, and the difference is load-bearing. `rf/reg-fx`
  is a macro that stamps the calling namespace as the registration's provenance,
  so overriding `:rf.http/managed` through it puts TWO source-store slots under
  one id (`re-frame.http.managed`'s provenance-free one and this ns's) and the
  frame's next default-image reprojection dies on `:rf.error/image-duplicate-id`.
  That failure is swallowed per-frame by the resilient reprojection sweep, so the
  frame silently keeps the generation it was seated with — the one assembled
  BEFORE the `reg-resource` calls below — and every `ensure` then throws
  `:rf.error/resource-not-registered` while `resource-meta` (read outside the
  cascade, off the global registrar) cheerfully reports the resource present. The
  plain fn carries no provenance, so it REPLACES the artefact's own slot and the
  reprojection keeps working. The resources artefact's own suites hit the same
  seam and resolve it the same way."
  []
  (fx/reg-fx :rf.http/managed (fn [_ctx _args] nil))
  (rf/reg-resource sensitive-resource-id
    {:scope         :rf.scope/global
     :sensitive?    true
     :params-schema [:map [:auth-token :string]]}
    (fn [_params _ctx] {:request {:method :get :url "/secret"}}))
  (rf/reg-resource plain-resource-id
    {:scope         :rf.scope/global
     :params-schema [:map [:slug :string]]}
    (fn [_params _ctx] {:request {:method :get :url "/public"}}))
  nil)

(defn- resource-family-row?
  "Whether `event` is a resource/mutation-family trace row — the same
  namespace test the epoch tool-pair's `resource-family-op?` makes when
  deciding which rows take the family projector."
  [event]
  (boolean (some-> (:operation event) namespace #{"rf.resource" "rf.mutation"})))

(defn- drive-resource-family!
  "Drive a REAL resource cascade against `frame-id` and return the
  resource-family trace rows it emitted, exactly as the producer emitted them.

  Two `ensure`s under ONE shared owner — the `:sensitive?` resource with the
  secret in its params, the PLAIN resource beside it — then a
  `release-owner`. That yields `:rf.resource/work-started` /
  `fetch-started` / `owner-attached` per resource plus one
  `:rf.resource/owner-released`, and between them every slot shape the two
  shipped leaks lived in: a NAMED single-key slot (`:resource/key`), the
  UNNAMED work-id whose scoped key is embedded one level down (`:work/id`,
  rf2-wd9im), the `:released` key vector (rf2-5o52l), and the `:aborted`
  work-id vector — each present twice over, once for a sensitive owner and once
  for a plain one.

  The rows are harvested off the trace bus rather than read out of the settled
  epoch records because ONE record is one dequeued event: this cascade is three
  dispatches, so its 7 family rows are spread across 3 records (the sensitive
  `ensure`'s, the plain `ensure`'s, the `release-owner`'s). Every scan below
  wants them together, which `record-carrying-resource-rows` assembles.

  They used also to be harvested here because epoch capture DROPPED them
  outright — `capture-event!` resolved an event's frame from `[:tags :frame]`
  and the whole family stamped only its `:rf.frame/id` EVIDENCE key, so the
  cascade put 7 rows on the bus and 0 into the 3 records it settled. Fixed in
  rf2-hbmeb: `build-event` now supplies the canonical `[:tags :frame]` routing
  tag for emit sites that don't stamp one, and the rows land. The proof that
  they do — bus counted against record, over a real cascade — lives in
  `epoch_egress_resource_trace_test`'s §(8)."
  [frame-id]
  (let [rows (atom [])
        k    ::resource-family-recorder]
    (trace-tooling/register-listener!
      k (fn [ev] (when (resource-family-row? ev) (swap! rows conj ev))))
    (try
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource sensitive-resource-id
                          :params   {:auth-token secret-password}
                          :owner    resource-owner}]
                        {:frame frame-id})
      (rf/dispatch-sync [:rf.resource/ensure
                         {:resource plain-resource-id
                          :params   {:slug plain-slug}
                          :owner    resource-owner}]
                        {:frame frame-id})
      (rf/dispatch-sync [:rf.resource/release-owner {:owner resource-owner}]
                        {:frame frame-id})
      (finally (trace-tooling/unregister-listener! k)))
    @rows))

(defn- record-carrying-resource-rows
  "The frame's last REAL epoch record with the cascade's WHOLE set of family
  rows appended to its `:trace-events` — one record carrying every row the
  scans below want to see at once, which no single real record does (one record
  is one dequeued event, and this cascade is three). Everything else about the
  record is the producer's: its own trace rows, `:sub-runs`, `:effects`,
  bookkeeping slots and frame.

  Since rf2-hbmeb the record already carries its OWN family rows, so the last
  record's `:rf.resource/owner-released` appears twice here. Harmless: every
  scan below is either a whole-set leak scan or a `first`-match lookup by
  `[operation resource-id]`, and both read the same row either way."
  [frame-id rows]
  (update (last (rf/epoch-history frame-id)) :trace-events (fnil into []) rows))

(defn- redacted-token?
  "Whether `c` is the resource family's opaque content-addressed egress token
  `{:rf/redacted <digest>}` — distinct values stay distinct, so a tool's per-key
  joins survive the redaction."
  [c]
  (and (map? c) (contains? c :rf/redacted)))

(defn- family-row
  "The tags of the first row in `rows` whose `:operation` is `op` and whose
  `:resource/key` names `resource-id` (pass nil to match on `op` alone — the
  `owner-released` row names no single resource). Position 1 of a projected
  scoped key is the resource-id, which survives redaction for exactly this kind
  of attribution, so the same lookup works on raw and projected rows."
  [rows op resource-id]
  (->> rows
       (filter (fn [ev]
                 (and (= op (:operation ev))
                      (or (nil? resource-id)
                          (= resource-id (second (:resource/key (:tags ev))))))))
       first
       :tags))

(defn- released-member
  "The `:released` member naming `resource-id` in a row's `tags`. Returns nil
  when no member is a scoped-key VECTOR naming it — which is itself the
  rf2-5o52l signature, since a key-id is a string."
  [tags resource-id]
  (first (filter #(and (vector? %) (= resource-id (second %)))
                 (:released tags))))

(defn- aborted-key
  "The scoped key EMBEDDED at position 1 of the `:aborted` work-id naming
  `resource-id` — `[:rf.work/resource <scoped-key> <generation>]`."
  [tags resource-id]
  (->> (:aborted tags)
       (map #(nth % 1 nil))
       (filter #(and (vector? %) (= resource-id (second %))))
       first))

;; ---- the FX-ARGS carriers (rf2-1kiuj) --------------------------------------
;;
;; The family's keys do not stay on the family's rows. An `ensure` LOWERS INTO
;; EFFECTS, and those effects address the work BY its scoped key, so
;; `re-frame.fx` stamps the key into `:rf.fx/args` (on each `:rf.fx/handled`) and
;; into `:rf.event/fx` (the whole effect vector, on `:rf.fx/do-fx`). Those rows
;; are `rf.fx`, so `resource-family-op?` — the OPERATION-NAMESPACE routing that
;; picks which rows take the family projector — skipped them, and the
;; app-db-rooted walk cannot classify a resolver-owned key either. The result was
;; the sharpest form of the two-carrier shape rf2-irwsq named: this record's
;; structured `:effects[*].args` slot egressed `:rf/redacted` while the
;; `:rf.fx/args` TAG three rows above carried the same payload's secret in the
;; clear.

(defn- fx-carrier-rows
  "Every trace row in `record` carrying one of the FX-ARGS egress carriers.
  Keyed off the SLOT rather than the operation, exactly as the projector is: the
  same pair rides `:rf.fx/handled`, `:rf.fx/skipped-on-platform` and the
  always-on `:rf.error/*` fx-failure traces."
  [record]
  (filter #(or (contains? (:tags %) :rf.fx/args)
               (contains? (:tags %) :rf.event/fx))
          (:trace-events record)))

(defn- fx-row
  "The tags of the first `:rf.fx/handled` row in `record` whose `:rf.fx/id` is
  `fx-id`."
  [record fx-id]
  (->> (:trace-events record)
       (filter #(and (= :rf.fx/handled (:operation %))
                     (= fx-id (get-in % [:tags :rf.fx/id]))))
       first
       :tags))

(defn- embedded-keys-naming
  "Every scoped-key-shaped 3-vector naming `resource-id` embedded ANYWHERE in
  `x`, at any depth. The fx carriers bury the key one to four levels down —
  inside a `[:rf.work/resource <key> <gen>]` work-id, inside a `[:rf.req <frame>
  <work-id>]` request-id, inside a continuation event's arg map — so a
  slot-addressed lookup cannot find them and this walk is what lets the
  assertions below speak about all of them at once."
  [x resource-id]
  (let [found (atom [])
        walk  (fn walk [v]
                (when (coll? v)
                  (when (and (vector? v) (= 3 (count v))
                             (= resource-id (nth v 1)) (map? (nth v 2)))
                    (swap! found conj v))
                  (if (map? v)
                    (doseq [[k vv] v] (walk k) (walk vv))
                    (run! walk v))))]
    (walk x)
    @found))

(defn- carrier-keys-naming
  "Every scoped key naming `resource-id` embedded in ANY fx-args carrier of
  `record`, deduplicated. The set the projection contract speaks about: after
  projection it must be a single element, and that element must be exactly what
  the family rows' own `:resource/key` projected to."
  [record resource-id]
  (into #{} (mapcat #(embedded-keys-naming (:tags %) resource-id))
        (fx-carrier-rows record)))

;; ============================================================================
;;  Forwarder-shape conformance — projected-record (per-record egress)
;; ============================================================================

(deftest forwarder-projected-record-leaks-no-raw-secret-bytes
  (testing "MCP `register-epoch-listener!` forwarder pattern: ship! body runs
            `projected-record` on each record before egress. The projected
            shape MUST NOT carry the raw secret string anywhere — the
            promise the MCP wire boundary makes to Security.md §Epoch
            privacy posture (line 104)."
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (let [shipped (atom [])
          ship!   (fn [record]
                    ;; Tool-side forwarder body — project at egress.
                    (swap! shipped conj (epoch/projected-record record)))]
      (rf/register-listener! :epoch ::forwarder ship!)
      (drive-mixed-ring! :test/mcp)
      (is (pos? (count @shipped))
          "the forwarder saw at least one cascade")
      (is (not-any? contains-secret? @shipped)
          "no projected record carries the raw secret string anywhere
           in its structure — every leaf at the sensitive path is the
           :rf/redacted scalar sentinel"))))

(deftest forwarder-projected-record-bounds-large-leaf-bytes
  (testing "MCP `register-epoch-listener!` forwarder pattern: the projected
            record MUST NOT egress the full large payload as a leaf
            string — the wire-elision walker substitutes a
            :rf.size/large-elided marker (a map containing :path, :bytes,
            :digest), not the raw bytes. An MCP forwarder downstream of
            the token-cap walker depends on this.

            rf2-isp3i — the scan below is cross-cutting, but it can only
            catch what the fixture puts in front of it, and the fixture
            used to inhabit ONE large shape: the app-db PATH `:upload`
            writes. The fixture controls in the second `let` pin the
            SECOND shape — a whole-output `:large?` sub, whose value is
            not app-db-rooted at all — in both of the slots it egresses
            through. Without them a future retention change (or a
            registration that silently stopped stamping) would return
            this gate to reporting green over ground it never covered."
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (let [shipped (atom [])]
      (rf/register-listener! :epoch ::forwarder
                             (fn [record]
                               (swap! shipped conj (epoch/projected-record record))))
      (drive-mixed-ring! :test/mcp)
      (let [raw-leaf-count       (count-leaf-strings-at-least payload-size
                                                              (rf/epoch-history :test/mcp))
            projected-leaf-count (count-leaf-strings-at-least payload-size @shipped)]
        (is (pos? raw-leaf-count)
            "sanity: the raw ring contains at least one large leaf")
        (is (zero? projected-leaf-count)
            "the projected output contains zero leaf strings of the
             large payload's size — the large path landed as a marker,
             not as raw bytes"))
      ;; rf2-isp3i — the whole-output `:large?` SUB shape, in both slots.
      (let [raw       (last (rf/epoch-history :test/mcp))
            raw-row   (sub-run-row  raw large-sub-id)
            raw-tags  (sub-run-tags raw large-sub-id)
            proj      (last @shipped)
            proj-row  (sub-run-row  proj large-sub-id)
            proj-tags (sub-run-tags proj large-sub-id)]
        ;; Fixture controls: the shape the scan above must be able to see.
        (is (= payload-size (count (:value raw-row)))
            "fixture: the raw `:sub-runs` row carries the sub's full computed
             value — the slot `elide-sub-run-row` projects")
        (is (= payload-size (count (:rf.sub/value raw-tags)))
            "fixture: the raw `:rf.sub/run` trace tag carries the SAME value —
             the slot `elide-large-sub-trace-values` projects. The emit
             chokepoint deliberately leaves both raw so the on-box ring keeps
             the exact value (Xray diff / restore-epoch! need it)")
        (is (true? (:large? raw-tags))
            "fixture: the emit chokepoint stamped the bare whole-output
             `:large?` flag — a REGISTRATION marker, so the app-db-rooted
             classification this fixture installs cannot reach it, and the flag
             is the entire contract egress has to honour")
        ;; Both callers of the shared rule, at egress.
        (is (elision/marker? (:value proj-row))
            "`elide-sub-run-row` substituted the marker in the structured row")
        (is (elision/marker? (:rf.sub/value proj-tags))
            "`elide-large-sub-trace-values` substituted the marker in the trace
             tag — the slot whose omission was rf2-irwsq's leak")
        (is (= (get-in (:value proj-row)          [:rf.size/large-elided :bytes])
               (get-in (:rf.sub/value proj-tags)  [:rf.size/large-elided :bytes]))
            "both markers agree on `:bytes` — the structural evidence that ONE
             shared rule produced them, so the two projections cannot drift")
        ;; Negative control: an UNMARKED sub's value rides through raw, so the
        ;; elisions above are driven by the registration stamp and not by some
        ;; blanket truncation on the way out.
        (is (= control-note (:value (sub-run-row proj control-sub-id)))
            "NEGATIVE CONTROL — the unmarked sub's row value survives raw")
        (is (= control-note (:rf.sub/value (sub-run-tags proj control-sub-id)))
            "NEGATIVE CONTROL — and so does its trace tag value")))))

(deftest forwarder-projected-record-preserves-bookkeeping-slots
  (testing "MCP forwarder pattern: the projected record's bookkeeping
            slots are byte-identical to the raw record's. An MCP tool
            uses :epoch-id for the resume cursor, :frame for the scoped
            tool routing, :rf.epoch/sensitive? to display the
            sensitivity badge — all MUST survive the projection
            verbatim (Spec Security.md §Epoch privacy posture line 103)."
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [raw       (rf/epoch-history :test/mcp)
          projected (mapv epoch/projected-record raw)
          bookkeeping-keys [:epoch-id :frame :committed-at :event-id
                            :outcome :halt-reason :schema-digest
                            :rf.epoch/sensitive?]]
      (doseq [k bookkeeping-keys
              [r p] (map vector raw projected)]
        (is (= (get r k) (get p k))
            (str "bookkeeping slot " k
                 " is preserved byte-identically by projected-record"))))))

(deftest forwarder-projected-record-is-pure-no-side-effects
  (testing "MCP forwarder pattern: projected-record is a pure data
            transform — it MUST NOT mutate the underlying ring, the
            schemas registry, or the elision registry. A forwarder
            running on every cascade would compound any side effect."
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [ring-before        (rf/epoch-history :test/mcp)
          schemas-before     (schemas/snapshot-schemas-by-frame)
          ;; Hit projected-record many times — a real forwarder might
          ;; project the same record multiple times (re-trigger, replay).
          _                  (dotimes [_ 25]
                               (mapv epoch/projected-record ring-before))
          ring-after         (rf/epoch-history :test/mcp)
          schemas-after      (schemas/snapshot-schemas-by-frame)]
      (is (= ring-before ring-after)
          "the epoch ring is unchanged — projected-record does not mutate")
      (is (= schemas-before schemas-after)
          "the schemas registry is unchanged"))))

(deftest forwarder-projected-record-is-sensitive-idempotent
  (testing "MCP forwarder pattern: under :sensitive? substitutions
            `projected-record` is idempotent — re-projecting an
            already-projected record returns a structurally-equal value
            at the sensitive slot. The :sensitive? sentinel
            (`:rf/redacted`) is a scalar keyword, so the walker has no
            larger structure to descend into on a re-projection pass;
            a forwarder pipeline that accidentally double-projects (e.g.
            middleware composition, tool-then-watcher fan-out) MUST NOT
            re-leak a sensitive value across passes.

            Sibling test `forwarder-projected-record-is-large-idempotent`
            pins the parallel guarantee for the :large? marker: the
            wire-elision walker is now marker-aware (per rf2-fq8ep), so
            both the sensitive and large substitutions are uniformly
            idempotent under repeated projection. The sensitive case
            holds because `:rf/redacted` is a non-matchable scalar; the
            large case holds because the walker recognises its own
            `:rf.size/large-elided` marker shape at the declared path
            and passes it through unchanged.

            What an MCP forwarder relies on: BOTH substitutions are
            irreversible across passes. Once a record has been projected,
            re-projecting it yields the same shape, byte-for-byte at
            the substitution points."
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [raw    (rf/epoch-history :test/mcp)
          once   (mapv epoch/projected-record raw)
          twice  (mapv epoch/projected-record once)
          thrice (mapv epoch/projected-record twice)]
      ;; Sensitive substitution holds across all three passes.
      (is (every? (fn [r] (= :rf/redacted (get-in r [:db-after :auth :password])))
                  (rest once))
          "every record past :seed carries :rf/redacted at the sensitive
           leaf after one projection pass")
      (is (every? (fn [r] (= :rf/redacted (get-in r [:db-after :auth :password])))
                  (rest twice))
          ":rf/redacted survives a second projection pass — scalar
           sentinel is the walker's substitution target, not re-matchable")
      (is (every? (fn [r] (= :rf/redacted (get-in r [:db-after :auth :password])))
                  (rest thrice))
          ":rf/redacted survives a third projection pass — sensitive
           substitution is irreversible")
      (is (not-any? contains-secret? thrice)
          "the secret is still absent after three projection passes —
           the MCP forwarder's no-leak guarantee holds even under
           accidental double-projection"))))

(deftest forwarder-projected-record-is-large-idempotent
  (testing "MCP forwarder pattern: under :large? substitutions
            `projected-record` is idempotent — re-projecting a record
            whose `:large?`-declared path already carries the
            `:rf.size/large-elided` marker MUST return a structurally-
            equal value at that slot. Per rf2-fq8ep, the wire-elision
            walker is marker-aware: when it encounters a value at a
            `:large?`-declared path that already satisfies
            `elision/marker?`, it passes the value through unchanged
            rather than re-marking it.

            Why this matters: without the guard, a second projection
            pass produced a new marker map whose `:bytes` reflected the
            printed length of the previous marker (not the original
            payload), and the `:digest` rotated similarly. Forwarder
            pipelines that accidentally double-project (middleware
            composition, tool-then-watcher fan-out) would have shipped
            drifting `:bytes` / `:digest` slots across passes — a
            recordkeeping wobble, not a leak, but it broke fingerprint-
            based dedup and confused consumers.

            With the marker-aware walker, the large marker is now
            irreversible across passes: once `:rf.size/large-elided`,
            always `:rf.size/large-elided` with the SAME `:bytes` /
            `:digest` slots. Parallel to the sensitive-case guarantee."
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [raw    (rf/epoch-history :test/mcp)
          once   (mapv epoch/projected-record raw)
          twice  (mapv epoch/projected-record once)
          thrice (mapv epoch/projected-record twice)
          ;; The :upload cascade is the third one driven (index 2):
          ;; that record is the one whose :db-after carries the large
          ;; payload at the frame-declared `[:blob :payload]` slot.
          large-slot (fn [r] (get-in r [:db-after :blob :payload]))]
      (is (elision/marker? (large-slot (nth once 2)))
          "first projection pass substitutes a marker at the large slot")
      (is (= (large-slot (nth once 2))
             (large-slot (nth twice 2)))
          "second projection pass returns the SAME marker (byte-identical
           :bytes / :digest / :path) — the walker passed it through
           unchanged rather than re-marking the marker map")
      (is (= (large-slot (nth once 2))
             (large-slot (nth thrice 2)))
          "third projection pass remains byte-identical — the large
           marker is irreversible across passes, matching the
           sensitive-case guarantee")
      (is (= once twice thrice)
          "across the full record vector, every slot is byte-identical
           across N>=2 projection passes — projected-record is now
           uniformly idempotent under both :sensitive? and :large?
           substitutions"))))

(deftest forwarder-projected-record-handles-mixed-nil-and-real-records
  (testing "MCP forwarder pattern: projected-record returns nil for nil
            input (a missed-epoch lookup MUST NOT throw); it returns a
            projected map for a real record. A forwarder that mixes
            optional / present records (cursor mid-stream, an epoch-id
            lookup that lost the race) MUST be able to call uniformly."
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [raw    (rf/epoch-history :test/mcp)
          mixed  (concat [nil] raw [nil] raw [nil])
          shaped (mapv epoch/projected-record mixed)]
      (is (= (count mixed) (count shaped))
          "every input slot produced an output slot")
      (is (= 3 (count (filter nil? shaped)))
          "the three nil slots project to nil (no throw, no fabrication)")
      (is (= (* 2 (count raw))
             (count (filter some? shaped)))
          "every real record projected to a real (non-nil) record")
      (is (not-any? contains-secret? (filter some? shaped))
          "no projected slot leaks the secret"))))

;; ============================================================================
;;  Bulk-egress conformance — projected-history (full ring snapshot)
;; ============================================================================

(deftest watch-epochs-projected-history-leaks-no-raw-bytes
  (testing "MCP `watch-epochs` initial snapshot pattern: the server
            calls `projected-history` once to emit the full ring. The
            bulk output MUST NOT leak the raw secret OR the raw large
            payload anywhere in its structure — the same per-record
            guarantee, lifted to the bulk surface."
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [snapshot (epoch/projected-history :test/mcp)]
      (is (pos? (count snapshot))
          "sanity: the snapshot is non-empty")
      (is (not-any? contains-secret? snapshot)
          "the bulk snapshot does not leak the raw secret")
      (is (zero? (count-leaf-strings-at-least payload-size snapshot))
          "the bulk snapshot does not leak any raw large-payload bytes"))))

(deftest watch-epochs-projected-history-equals-mapv-projected-record
  (testing "MCP `watch-epochs` initial snapshot pattern: the docstring
            promises `projected-history` is equivalent to
            `(mapv projected-record (epoch-history fid))`. Pin that
            equivalence so the MCP server can use either entry without
            shape drift; the bulk-egress path MUST NOT diverge from
            the per-record path."
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [raw            (rf/epoch-history :test/mcp)
          bulk-projection (epoch/projected-history :test/mcp)
          per-record      (mapv epoch/projected-record raw)]
      (is (= per-record bulk-projection)
          "projected-history is the bulk-shape equivalent of
           (mapv projected-record (epoch-history fid))"))))

(deftest watch-epochs-projected-history-preserves-oldest-first-order
  (testing "MCP `watch-epochs` initial snapshot pattern: the snapshot
            MUST preserve the raw ring's oldest-first ordering so the
            server's resume-cursor (`:after-id` keyed off the last
            epoch-id) addresses a stable point in the projected stream.
            A reordering would break cursor pagination."
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [raw      (rf/epoch-history :test/mcp)
          snapshot (epoch/projected-history :test/mcp)]
      (is (= (mapv :epoch-id raw)
             (mapv :epoch-id snapshot))
          "ordering matches the raw ring epoch-id-by-epoch-id"))))

(deftest watch-epochs-projected-history-empty-on-fresh-frame
  (testing "MCP `watch-epochs` initial snapshot pattern: an MCP server
            attached to a frame with no recorded epochs (a freshly-
            booted app, a just-cleared session) MUST receive the empty
            vector — not a missing-frame error. The snapshot path is
            shape-stable across the empty case."
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (is (= [] (epoch/projected-history :test/mcp))
        "empty-ring snapshot is the empty vector")
    (is (= [] (epoch/projected-history :rf/no-such-frame))
        "missing-frame snapshot is also the empty vector — uniform shape")))

(deftest watch-epochs-projected-history-is-pure-no-side-effects
  (testing "MCP `watch-epochs` initial snapshot pattern: projected-history
            MUST be pure — repeat calls (the initial snapshot, a
            resync-after-reconnect, a debug print) MUST NOT mutate the
            ring or any registry."
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [ring-before    (rf/epoch-history :test/mcp)
          schemas-before (schemas/snapshot-schemas-by-frame)
          _              (dotimes [_ 25] (epoch/projected-history :test/mcp))
          ring-after     (rf/epoch-history :test/mcp)
          schemas-after  (schemas/snapshot-schemas-by-frame)]
      (is (= ring-before ring-after)
          "the ring is unchanged after 25 bulk-projection calls")
      (is (= schemas-before schemas-after)
          "the schemas registry is unchanged"))))

;; ============================================================================
;;  rf2-m9duxl — `:include-sensitive?` routes THROUGH projection, per-axis.
;;
;; The Pair-MCP epoch-egress tools used to treat the operator's
;; `:include-sensitive true` opt-in as a FULL raw epoch bypass — they
;; disabled `projected-record` wholesale. That conflated the app-db
;; sensitive axis with EVERY other independent projection axis and shipped
;; the raw fx-args payload, the raw runtime-db partition, and an
;; un-`:redact-fn`'d record off-box. The fix routes `:include-sensitive`
;; THROUGH the projection as `{:include-sensitive? true}`, lifting ONLY the
;; app-db sensitive axis. These framework-side tests pin the per-axis
;; contract the tool-side form-shape tests depend on: with
;; `{:include-sensitive? true}` the app-db sensitive leaf is REVEALED while
;; `:effects[*].args` / the `:rf.db/runtime` partition / large slots / the
;; `:redact-fn` override all stay at their fail-closed defaults.
;; ============================================================================

(defn- install-fx-and-runtime-schemas!
  "Like `install-mcp-style-schemas!` but ALSO arranges for a record that
  carries (a) a payload-bearing `:effects[*].args` row and (b) a populated
  `:rf.db/runtime` frame-state partition — the two axes orthogonal to the
  app-db sensitive axis. Declares the same `[:auth :password]` sensitive +
  `[:blob :payload]` large app-db paths."
  [frame-id]
  (install-mcp-style-schemas! frame-id))

(deftest include-sensitive-reveals-app-db-but-keeps-fx-args-redacted
  (testing "rf2-m9duxl — `{:include-sensitive? true}` reveals the app-db
            sensitive leaf YET keeps the orthogonal `:effects[*].args`
            redacted (a different keyspace, governed by `:include-fx-args?`).
            This is the exact conflation the include-sensitive bypass
            introduced: asking for sensitive APP-DB values must NOT lift the
            fx-arg payload."
    (rf/make-frame {:id :test/mcp})
    (install-fx-and-runtime-schemas! :test/mcp)
    (let [creds {:password secret-password :token "tok-abc"}]
      (rf/reg-fx :fxp/login (fn [_ _] nil))
      ;; Write the app-db sensitive path AND fire a payload-bearing fx whose
      ;; :args carry the same secret bytes.
      (rf/reg-event :do-login
                       (fn [_ [_ c]]
                         {:db {:auth {:password (:password c)}}
                          :fx [[:fxp/login c]]}))
      (rf/dispatch-sync [:do-login creds] {:frame :test/mcp})
      (let [raw      (last (rf/epoch-history :test/mcp))
            proj     (epoch/projected-record raw {:include-sensitive? true})
            fx-row   (some #(when (= :fxp/login (:fx-id %)) %) (:effects proj))]
        ;; App-db sensitive axis: REVEALED by include-sensitive.
        (is (= secret-password (get-in proj [:db-after :auth :password]))
            "`:include-sensitive? true` reveals the app-db sensitive leaf")
        ;; fx-args axis: STILL redacted (orthogonal — needs :include-fx-args?).
        (is (some? fx-row) "the fixture produced a payload-bearing fx row")
        (is (= :rf/redacted (:args fx-row))
            "`:effects[*].args` STAY redacted under include-sensitive alone —
             the fx-arg keyspace is orthogonal to the app-db sensitive axis")
        (is (= :fxp/login (:fx-id fx-row)) "value-free :fx-id preserved")))))

(deftest include-sensitive-keeps-runtime-db-partition-redacted
  (testing "rf2-m9duxl — `{:include-sensitive? true}` keeps the
            `:rf.db/runtime` frame-state partition REDACTED. The runtime-db
            boundary is governed by the orthogonal `:include-runtime-db?`
            opt; asking for sensitive APP-DB values must not lift the
            machine snapshots / route slice / SSR metadata."
    (rf/make-frame {:id :test/mcp})
    (install-fx-and-runtime-schemas! :test/mcp)
    ;; Write BOTH the app-db sensitive leaf and a runtime-db partition value
    ;; (via the reserved :rf.db/runtime effect) in one cascade.
    (rf/reg-event :seed-both
                     (fn [{rt :rf.db/runtime} _]
                       {:db            {:auth {:password secret-password}}
                        :rf.db/runtime (assoc-in (or rt {})
                                                 [:rf.runtime/machines :snapshots :m/x]
                                                 {:state :live})}))
    (rf/dispatch-sync [:seed-both] {:frame :test/mcp})
    (let [raw  (last (rf/epoch-history :test/mcp))
          proj (epoch/projected-record raw {:include-sensitive? true})]
      ;; Sanity: the raw record DOES carry a populated runtime-db partition
      ;; (the machine snapshot we wrote, alongside the frame's elision
      ;; registry which also lives in the runtime-db partition).
      (is (= {:state :live}
             (get-in raw [:frame-state-after :rf.db/runtime
                          :rf.runtime/machines :snapshots :m/x]))
          "fixture: raw record carries a populated runtime-db partition")
      ;; App-db sensitive axis: REVEALED.
      (is (= secret-password
             (get-in proj [:frame-state-after :rf.db/app :auth :password]))
          "`:include-sensitive? true` reveals the app-db partition's sensitive leaf")
      ;; runtime-db axis: STILL redacted (orthogonal — needs :include-runtime-db?).
      (is (= :rf/redacted (get-in proj [:frame-state-after :rf.db/runtime]))
          "the `:rf.db/runtime` partition STAYS :rf/redacted under
           include-sensitive alone — runtime-db is orthogonal to the app-db
           sensitive axis")
      ;; And the explicit runtime-db opt DOES lift it (negative control).
      (let [proj+rt (epoch/projected-record raw {:include-sensitive?  true
                                                 :include-runtime-db? true})]
        (is (not= :rf/redacted (get-in proj+rt [:frame-state-after :rf.db/runtime]))
            "the explicit `:include-runtime-db? true` opt lifts the partition —
             proving the axis is independently governed")))))

(deftest include-sensitive-keeps-large-elision-independent
  (testing "rf2-m9duxl — `{:include-sensitive? true}` keeps the app-db
            `:large?` slot elided to the `:rf.size/large-elided` marker.
            Large is governed by the independent `:include-large?` opt;
            the sensitive opt-in must not pull the full payload off-box."
    (rf/make-frame {:id :test/mcp})
    (install-fx-and-runtime-schemas! :test/mcp)
    (rf/reg-event :seed-large
                     (fn [{:keys [db]} _] {:db {:auth {:password secret-password}
                                :blob {:payload (big-string payload-size)}}}))
    (rf/dispatch-sync [:seed-large] {:frame :test/mcp})
    (let [raw  (last (rf/epoch-history :test/mcp))
          proj (epoch/projected-record raw {:include-sensitive? true})]
      ;; Sensitive REVEALED; large STILL elided.
      (is (= secret-password (get-in proj [:db-after :auth :password]))
          "`:include-sensitive? true` reveals the app-db sensitive leaf")
      (is (elision/marker? (get-in proj [:db-after :blob :payload]))
          "the app-db large slot STAYS a `:rf.size/large-elided` marker —
           large elision is orthogonal to the sensitive axis")
      (is (zero? (count-leaf-strings-at-least payload-size proj))
          "no raw large-payload bytes egress under include-sensitive alone")
      ;; Negative control: :include-large? true lifts it.
      (let [proj+lg (epoch/projected-record raw {:include-sensitive? true
                                                 :include-large?     true})]
        (is (not (elision/marker? (get-in proj+lg [:db-after :blob :payload])))
            "the explicit `:include-large? true` opt lifts the large slot —
             proving the axis is independently governed")))))

(deftest include-sensitive-still-applies-redact-fn-override
  (testing "rf2-m9duxl — the app-installed `:redact-fn` advanced override
            STILL runs over the projected record under
            `{:include-sensitive? true}`. The override is the post-projection
            stage of the two-stage projection; a raw bypass would skip it
            entirely. We install a `:redact-fn` that stamps a sentinel slot
            and assert it lands even with the sensitive opt-in on."
    (rf/make-frame {:id :test/mcp})
    (install-fx-and-runtime-schemas! :test/mcp)
    (rf/configure! {:epoch-history {:redact-fn (fn [record]
                                 (assoc record :rf.test/redact-fn-ran true))}})
    (rf/reg-event :seed-sensitive
                     (fn [{:keys [db]} _] {:db {:auth {:password secret-password}}}))
    (rf/dispatch-sync [:seed-sensitive] {:frame :test/mcp})
    (let [raw  (last (rf/epoch-history :test/mcp))
          proj (epoch/projected-record raw {:include-sensitive? true})]
      (is (= secret-password (get-in proj [:db-after :auth :password]))
          "`:include-sensitive? true` reveals the app-db sensitive leaf")
      (is (true? (:rf.test/redact-fn-ran proj))
          "the app `:redact-fn` override STILL runs under include-sensitive —
           the projection's post-stage is never skipped (no raw bypass)")
      ;; Negative control: the RAW ring record is untouched by the redact-fn
      ;; (projection-side only) — restore fidelity preserved.
      (is (not (contains? raw :rf.test/redact-fn-ran))
          "the raw ring record is untouched — redact-fn is projection-side"))))

;; ============================================================================
;;  Cross-function sentinel uniformity
;; ============================================================================

(deftest projected-record-and-history-share-redaction-vocabulary
  (testing "Both functions substitute the SAME sentinel vocabulary
            (`:rf/redacted` for sensitive, `:rf.size/large-elided` marker
            map for large). An MCP client that branches on the marker
            vocabulary MUST see uniform shapes across the per-record and
            bulk-egress paths — divergence would force per-path branching
            client-side. Pinned per-record-AND-bulk against the same ring."
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (drive-mixed-ring! :test/mcp)
    (let [raw        (rf/epoch-history :test/mcp)
          bulk       (epoch/projected-history :test/mcp)
          per-record (mapv epoch/projected-record raw)]
      ;; The :login cascade is the second one driven; pull both shapes'
      ;; corresponding record and compare leaf-by-leaf.
      (let [login-bulk (nth bulk       1)
            login-per  (nth per-record 1)]
        (is (= (get-in login-bulk [:db-after :auth :password])
               (get-in login-per  [:db-after :auth :password]))
            "the sensitive leaf substitution matches between bulk and per-record")
        (is (= :rf/redacted
               (get-in login-bulk [:db-after :auth :password]))
            "the bulk-shape sensitive leaf is the :rf/redacted scalar sentinel")
        (is (= :rf/redacted
               (get-in login-per  [:db-after :auth :password]))
            "the per-record-shape sensitive leaf is the :rf/redacted scalar sentinel"))
      ;; The :upload cascade is the third one driven.
      (let [upload-bulk (nth bulk       2)
            upload-per  (nth per-record 2)
            bulk-slot   (get-in upload-bulk [:db-after :blob :payload])
            per-slot    (get-in upload-per  [:db-after :blob :payload])]
        (is (= bulk-slot per-slot)
            "the large-payload slot substitution matches between bulk and per-record")
        (is (elision/marker? bulk-slot)
            "the bulk-shape large slot is an elision marker (`:rf.size/large-elided`)")
        (is (elision/marker? per-slot)
            "the per-record-shape large slot is an elision marker")))))

;; ============================================================================
;;  rf2-nm611o — :trigger-event event-args fail-closed off-box egress.
;;
;;  The dispatched event vector's args are registration-owned transient
;;  payloads (Spec 015 §151), not app-db-rooted, so the app-db classification
;;  walker cannot prove them safe. A secret carried IN the event vector (e.g.
;;  [:login "topsecret"]) previously egressed RAW through the generic
;;  app-db-rooted payload-slot projection. The fix fails closed: args
;;  redacted, head event-id retained; trusted-local :include-event-args?
;;  opts back in. (drive-mixed-ring! keeps secrets OUT of the trigger-event
;;  on purpose — see its :login comment — so these tests drive the secret IN.)
;; ============================================================================

(deftest forwarder-trigger-event-positional-secret-fails-closed
  (testing "rf2-nm611o — an MCP forwarder shipping a record whose dispatched
            event vector carried a secret POSITIONALLY ([:login secret])
            MUST NOT egress the secret. projected-record fails closed: the
            head event-id is retained, the positional arg is :rf/redacted."
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (rf/reg-event :login (fn [{:keys [db]} [_ pw]]
                           {:db (assoc-in db [:auth :password] pw)}))
    (let [shipped (atom [])]
      (rf/register-listener! :epoch ::forwarder
                                   (fn [r] (swap! shipped conj (epoch/projected-record r))))
      (rf/dispatch-sync [:login secret-password] {:frame :test/mcp})
      (is (pos? (count @shipped)) "the forwarder saw the cascade")
      (is (not-any? contains-secret? @shipped)
          "no projected record leaks the positional secret anywhere")
      (let [proj (last @shipped)]
        (is (= [:login :rf/redacted] (:trigger-event proj))
            "trigger-event egresses with the head id retained, arg redacted")
        (is (= :login (:event-id proj))
            "the event-id summary slot is intact")))))

(deftest forwarder-trigger-event-map-secret-fails-closed
  (testing "rf2-nm611o — a secret nested in a MAP arg of the dispatched
            event vector ([:auth/login {:password secret}]) also fails
            closed off-box: the whole arg redacts to :rf/redacted."
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (rf/reg-event :auth/login (fn [{:keys [db]} [_ {:keys [password]}]]
                                {:db (assoc-in db [:auth :password] password)}))
    (rf/dispatch-sync [:auth/login {:password secret-password}] {:frame :test/mcp})
    (let [proj (epoch/projected-record (last (rf/epoch-history :test/mcp)))]
      (is (= [:auth/login :rf/redacted] (:trigger-event proj)))
      (is (not (contains-secret? (:trigger-event proj)))
          "the map-arg secret is absent from the projected trigger-event"))))

(deftest include-event-args-reveals-trigger-event-but-keeps-app-db-axes
  (testing "rf2-nm611o — `{:include-event-args? true}` reveals the raw
            trigger-event args YET is ORTHOGONAL to the app-db
            sensitive/large axes (and vice-versa). Asking for event args
            must not lift the app-db sensitive leaf, and asking for app-db
            sensitive values must not lift the event args."
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (rf/reg-event :login (fn [{:keys [db]} [_ pw]]
                           {:db (assoc-in db [:auth :password] pw)}))
    (rf/dispatch-sync [:login secret-password] {:frame :test/mcp})
    (let [raw (last (rf/epoch-history :test/mcp))]
      ;; include-event-args reveals the args but keeps app-db sensitive redacted.
      (let [proj (epoch/projected-record raw {:include-event-args? true})]
        (is (= [:login secret-password] (:trigger-event proj))
            "`:include-event-args? true` reveals the raw trigger-event args")
        (is (= :rf/redacted (get-in proj [:db-after :auth :password]))
            "the app-db sensitive leaf STAYS redacted — orthogonal axis"))
      ;; include-sensitive reveals the app-db leaf but keeps event args redacted.
      (let [proj (epoch/projected-record raw {:include-sensitive? true})]
        (is (= secret-password (get-in proj [:db-after :auth :password]))
            "`:include-sensitive? true` reveals the app-db sensitive leaf")
        (is (= [:login :rf/redacted] (:trigger-event proj))
            "the trigger-event args STAY redacted — event-args axis is orthogonal")))))

;; ============================================================================
;;  rf2-isp3i — the resource/mutation trace family reaches this gate.
;;
;;  See the fixture block above for what the family is and why an app-db-rooted
;;  fixture could not see it. These two tests are the halves of one claim: the
;;  first says a `:sensitive?` owner's scope + params never egress raw in ANY
;;  representation, the second says a PLAIN owner's ride verbatim in the SAME
;;  rows — so over-redaction fails as loudly as leaking, and neither half can be
;;  satisfied by a blanket strip.
;; ============================================================================

(deftest forwarder-projected-record-leaks-no-raw-resource-family-bytes
  (testing "MCP forwarder pattern over the RESOURCE trace family: a record
            carrying the rows a real `ensure` / `release-owner` cascade emitted
            MUST NOT egress a `:sensitive?` owner's resolved scope or canonical
            params in ANY representation — not as the raw params map, and not
            hidden inside a reversible CEDN-1 `key-id` string. The two leaks
            this gate could not see (rf2-wd9im's embedded work-id key,
            rf2-5o52l's plaintext key-id) both land in the rows below."
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (install-resource-family!)
    (let [rows      (drive-resource-family! :test/mcp)
          raw-hist  (rf/epoch-history :test/mcp)
          record    (record-carrying-resource-rows :test/mcp rows)
          projected (epoch/projected-record record)
          proj-rows (filter resource-family-row? (:trace-events projected))
          proj-hist (mapv epoch/projected-record raw-hist)]

      ;; ---- fixture controls: the shape the scans below must be able to see --
      (testing "FIXTURE — the cascade produced real family rows carrying the
                real secret, so the scans are not passing over an empty set"
        (is (seq rows) "the cascade emitted resource-family rows")
        (is (contains-secret? rows)
            "the RAW rows carry the secret — the projector's input is the
             producer's own output, not an invented tag map")
        (is (= 3 (count (filter #(= sensitive-resource-id
                                    (second (:resource/key (:tags %))))
                                rows)))
            "three lifecycle rows name the :sensitive? owner (work-started /
             fetch-started / owner-attached)")
        (let [raw (family-row rows :rf.resource/work-started sensitive-resource-id)]
          (is (= [:rf.scope/global sensitive-resource-id
                  {:auth-token secret-password}]
                 (:resource/key raw))
              "FIXTURE — the raw `:resource/key` is the full scoped key")
          (is (= (:resource/key raw) (nth (:work/id raw) 1))
              "FIXTURE — and the SAME key is embedded at position 1 of the
               `:work/id`, one level down inside a vector under a slot no
               roster names (the rf2-wd9im shape)"))
        (is (seq (:released (family-row rows :rf.resource/owner-released nil)))
            "FIXTURE — the release row carries a `:released` slot (the
             rf2-5o52l shape)")
        (testing "and its FX rows carry the same key off the family's own rows
                  (rf2-1kiuj) — the carriers the whole-record scan below needs
                  to be able to see"
          (is (seq (mapcat fx-carrier-rows raw-hist))
              "the cascade's real records carry `:rf.fx/args` / `:rf.event/fx`
               rows at all")
          (is (seq (mapcat #(carrier-keys-naming % sensitive-resource-id) raw-hist))
              "and the RAW carriers embed the `:sensitive?` owner's scoped key,
               buried inside a work-id / request-id / continuation arg map where
               no slot-addressed lookup reaches it")
          (is (some #(contains-secret? (:tags %)) (mapcat fx-carrier-rows raw-hist))
              "carrying the secret itself — so a green scan below is the
               projector's doing, not an absent shape")))

      ;; ---- the claim this gate owns ---------------------------------------
      (testing "ACCEPTANCE — no raw sensitive bytes anywhere in the projected
                record, in either representation"
        (is (empty? (secret-leak-paths projected))
            "the raw secret is absent from every leaf of the WHOLE projected
             record — a failure here NAMES the leaking tag slot")
        (is (empty? (mapcat secret-leak-paths proj-hist))
            "and from every leaf of every REAL record the cascade settled,
             projected as a forwarder ships it — the assembled record above
             carries the family rows of all three cascades but only the LAST
             one's fx rows, so the `ensure` record's carriers are only seen
             here (rf2-1kiuj)")
        (is (empty? (cedn-tokens projected))
            "and no CEDN-1 key-id egresses under ANY tag of the WHOLE record —
             a key-id would disclose the same scope + params in the clear while
             looking opaque, and this one holds record-wide today"))

      ;; ---- per-slot, so a failure names the slot that leaked ---------------
      (let [raw  (family-row rows :rf.resource/work-started sensitive-resource-id)
            tags (family-row proj-rows :rf.resource/work-started
                             sensitive-resource-id)
            [pscope rid pparams] (:resource/key tags)
            [marker embedded generation] (:work/id tags)]
        (testing "the NAMED single-key slot tokenizes"
          (is (= sensitive-resource-id rid)
              "the resource-id (position 1) survives for attribution")
          (is (redacted-token? pscope) "the resolved scope is tokenized")
          (is (redacted-token? pparams) "the canonical params are tokenized"))
        (testing "the UNNAMED work-id's EMBEDDED key tokenizes identically
                  (rf2-wd9im — reached by DEPTH, not by slot name)"
          (is (= :rf.work/resource marker) "the work-kind marker rides verbatim")
          (is (= (nth (:work/id raw) 2) generation)
              "the generation rides verbatim (read off the RAW row — the
               counter is frame-lifetime scoped, so a literal here would rot
               the moment another test registration lands before this one)")
          (is (= (:resource/key tags) embedded)
              "the embedded key projects EXACTLY as the row's own
               `:resource/key` — an unnamed slot and a named one cannot drift"))
        (testing "the structural attribution a tool needs rides verbatim"
          (is (= resource-owner (:owner tags)))
          (is (= :running (:status tags)))
          (is (= :test/mcp (:rf.frame/id tags))))
        (is (true? (:sensitive? tags)) "the row is stamped :sensitive?"))

      (let [tags     (family-row proj-rows :rf.resource/owner-released nil)
            released (released-member tags sensitive-resource-id)
            aborted  (aborted-key tags sensitive-resource-id)]
        (testing "`:released` names the entry by SCOPED KEY, tokenized
                  (rf2-5o52l — a key-id here is a string, so this lookup
                  returns nil on the unfixed emit site)"
          (is (some? released)
              "the sensitive owner's released entry is named by a scoped-key
               VECTOR, not a CEDN-1 byte string")
          (is (= sensitive-resource-id (second released))
              "its resource-id survives")
          (is (redacted-token? (first released)) "its scope is tokenized")
          (is (redacted-token? (nth released 2)) "its params are tokenized"))
        (testing "`:released` and `:aborted` agree digest-for-digest on ONE row
                  — the same identity, projected through the same
                  classification, so a tool's per-key joins survive redaction"
          (is (some? aborted) "the sensitive owner's work-id rode under :aborted")
          (is (= released aborted)))
        (is (true? (:sensitive? tags)) "the release row is stamped :sensitive?"))

      ;; ---- the FX-ARGS carriers, per slot (rf2-1kiuj) -----------------------
      (let [ensure-rec (first proj-hist)
            family-key (:resource/key (family-row proj-rows :rf.resource/work-started
                                                  sensitive-resource-id))
            managed    (fx-row ensure-rec :rf.http/managed)
            handle     (fx-row ensure-rec :rf.resource/record-work-handle)
            do-fx      (->> (:trace-events ensure-rec)
                            (filter #(= :rf.fx/do-fx (:operation %)))
                            first :tags)]
        (testing "FIXTURE — the `ensure` record really is the one the bead
                  described: a `:rf.http/managed` row, a work-handle row and a
                  `:rf.fx/do-fx` aggregate, each carrying the key"
          (is (some? managed)   "the lowered `:rf.http/managed` fx row is present")
          (is (some? handle)    "the `:rf.resource/record-work-handle` fx row is present")
          (is (some? do-fx)     "the `:rf.fx/do-fx` aggregate row is present")
          (is (some? family-key) "and the family row projected a key to compare against"))

        (testing "every key embedded in EVERY carrier projects to EXACTLY what
                  the family row's own `:resource/key` projected to — one value,
                  two carriers, ONE rule, so the pair cannot drift"
          (is (= #{family-key} (carrier-keys-naming ensure-rec sensitive-resource-id))))

        (testing "so the carriers name the resource but disclose neither half of
                  the identity"
          (is (= sensitive-resource-id (nth family-key 1))
              "the resource-id survives for attribution")
          (is (redacted-token? (nth family-key 0)) "the resolved scope is tokenized")
          (is (redacted-token? (nth family-key 2)) "the canonical params are tokenized"))

        (testing "and the row's NON-key payload is untouched — the projector
                  speaks for the family's keys, not for the fx family's args"
          (is (= {:method :get :url "/secret"} (:request (:rf.fx/args managed)))
              "the resolver's request map rides verbatim")
          (is (= :test/mcp (:frame-id (:rf.fx/args handle))))
          (is (= :rf.http/managed (:transport (:rf.fx/args handle)))))

        (testing "a carrier row whose key redacted is stamped :sensitive?, the
                  same signal the family rows carry"
          (is (true? (:sensitive? managed)))
          (is (true? (:sensitive? handle)))
          (is (true? (:sensitive? do-fx))))

        (testing "and the structured `:effects[*].args` twin still fails closed —
                  the asymmetry this bead closed was between these two slots, so
                  the fix must not have been to open the redacted one"
          (is (every? #(= :rf/redacted (:args %))
                      (filter #(contains? % :args) (:effects ensure-rec)))))

        (testing "the MIXED slot: the release cascade's
                  `:rf.resource/cancel-poll-timers` args name every timer key
                  the released owner held, so BOTH owners' keys sit in ONE slot
                  on ONE row and the projection must tell them apart. This is
                  the sensitive half; the plain half is the over-redaction
                  control in the sibling deftest below"
          (let [cancel (fx-row (nth proj-hist 2) :rf.resource/cancel-poll-timers)
                keys*  (get-in cancel [:rf.fx/args :resource/keys])
                sk     (first (filter #(= sensitive-resource-id (second %)) keys*))]
            (is (some? cancel) "FIXTURE — the cancel-timers fx row is present")
            (is (= #{plain-resource-id sensitive-resource-id} (set (map second keys*)))
                "FIXTURE — both owners' keys ride the slot, and both
                 resource-ids survive for attribution")
            (is (redacted-token? (nth sk 0)) "the SENSITIVE key's scope tokenizes")
            (is (redacted-token? (nth sk 2)) "and its canonical params tokenize")
            (is (= family-key sk)
                "digest-for-digest the same identity the family row projected —
                 a tool's per-key joins survive across the two families"))))

      ;; ---- forwarder pipelines double-project; that must not re-digest ------
      (testing "idempotent under repeated projection — a forwarder that
                accidentally projects twice (middleware composition,
                tool-then-watcher fan-out) MUST NOT re-hash the tokens"
        (is (= projected (epoch/projected-record projected))
            "re-projecting an already-projected record is a fixed point")))))

(deftest forwarder-projected-record-keeps-plain-resource-owner-verbatim
  (testing "rf2-isp3i two-sided control — a PLAIN (registered, non-`:sensitive?`,
            non-`:large?`) resource owner's scoped key rides its scope + params
            VERBATIM off-box, in the SAME rows that tokenize the sensitive
            owner's. Without this half, the acceptance test above could be
            satisfied by redacting everything, which would destroy the
            attribution every resource tool is built on."
    (rf/make-frame {:id :test/mcp})
    (install-mcp-style-schemas! :test/mcp)
    (install-resource-family!)
    (let [rows      (drive-resource-family! :test/mcp)
          proj-hist (mapv epoch/projected-record (rf/epoch-history :test/mcp))
          projected (epoch/projected-record
                      (record-carrying-resource-rows :test/mcp rows))
          proj-rows (filter resource-family-row? (:trace-events projected))
          plain-key [:rf.scope/global plain-resource-id {:slug plain-slug}]
          tags      (family-row proj-rows :rf.resource/work-started
                                plain-resource-id)
          release   (family-row proj-rows :rf.resource/owner-released nil)]
      (is (= plain-key (:resource/key tags))
          "the plain owner's NAMED key rides verbatim — scope and params intact")
      (is (= plain-key (nth (:work/id tags) 1))
          "and so does the key EMBEDDED in its work-id: the shape-driven default
           projects through the OWNER, so closing the rf2-wd9im leak cost no
           over-redaction on the ordinary row")
      (is (= plain-key (released-member release plain-resource-id))
          "and so does its `:released` member")
      (is (= plain-key (aborted-key release plain-resource-id))
          "and its `:aborted` work-id's embedded key")
      (is (not (:sensitive? tags))
          "a plain row is NOT stamped :sensitive?")
      (is (empty? (cedn-tokens tags))
          "no CEDN-1 token rides the plain row either — the emit-site rule is
           owner-independent")
      (testing "the plain owner's params are the real map, not a token"
        (is (= {:slug plain-slug} (nth (:resource/key tags) 2)))
        (is (not (redacted-token? (nth (:resource/key tags) 2)))))

      ;; ---- and the same control on the FX-ARGS carriers (rf2-1kiuj) --------
      (let [plain-rec (second proj-hist)          ; the PLAIN owner's `ensure`
            release   (nth proj-hist 2)
            managed   (fx-row plain-rec :rf.http/managed)
            cancel    (fx-row release :rf.resource/cancel-poll-timers)]
        (testing "FIXTURE — the plain `ensure` lowered into fx rows too"
          (is (some? managed) "its `:rf.http/managed` row is present")
          (is (seq (carrier-keys-naming plain-rec plain-resource-id))
              "and its carriers embed the plain owner's key"))

        (testing "every key in every carrier of the plain cascade rides its
                  scope + params VERBATIM — closing rf2-1kiuj cost no
                  attribution on the ordinary row, and a blanket redaction of
                  the carriers would fail here"
          (is (= #{plain-key} (carrier-keys-naming plain-rec plain-resource-id)))
          (is (= {:method :get :url "/public"} (:request (:rf.fx/args managed)))
              "as does the non-key payload beside it")
          (is (not (:sensitive? managed))
              "and a carrier row that redacted nothing is NOT stamped
               :sensitive?"))

        ;; The MIXED row — `:rf.resource/cancel-poll-timers`, whose args name
        ;; every timer key the released owner held, so BOTH owners' keys sit in
        ;; ONE slot — is asserted from BOTH sides. Its sensitive half lives with
        ;; the acceptance claim above (it must RED when the fix is reverted);
        ;; only the plain half belongs here, where the whole point is to stay
        ;; GREEN in both directions.
        (testing "the sharpest form of the control: on the ONE slot carrying
                  BOTH owners' keys, the plain key is the one left alone"
          (is (some? cancel) "FIXTURE — the cancel-timers fx row is present")
          (let [keys* (get-in cancel [:rf.fx/args :resource/keys])]
            (is (= 2 (count keys*)) "FIXTURE — both owners' keys ride the slot")
            (is (= plain-key (first (filter #(= plain-resource-id (second %)) keys*)))
                "the PLAIN key rides verbatim beside a tokenized sibling — the
                 projection discriminates by OWNER within a single slot, which
                 no blanket strip and no blanket pass-through can do")))))))
