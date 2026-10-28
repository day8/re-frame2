// Live-re-frame2-pair MCP-client conformance variant exercising egress
// protection of a classified `:sensitive` app-db slot through the
// pull-mode epoch tools (`trace-window` + `watch-epochs`) and the
// `snapshot` `:epochs` slice.
//
// ## EP-0025 — durable app-db classification rides the commit-plane effects
//
// Durable app-db egress classification is declared by the EP-0025
// commit-plane classification effects: an event handler returns
// `:sensitive [[:auth :token]]` (or `:large` / `:clear-*`) alongside `:db`
// (spec/015-Data-Classification.md; docs/EP/EP-0025-data-classification.md).
// The router folds the declaration into the same atomic commit as the `:db`
// write, installing it into the frame's durable elision registry tagged
// `:source :effect`; the epoch assembler's `sensitive-rollup` and the
// off-box `project-egress` both read THAT registry. There is no durable
// `:sensitive {:app-db …}` *frame annotation* (a frame is not app-db's
// definition site). Schemas describe shape, not durable app-db
// egress policy: there is no schema-attached `:sensitive?` app-db
// classification (EP-0015 §8).
//
// The gate-OFF / gate-ON arms below therefore classify the sensitive slot
// through the commit-plane `:sensitive` effect — the same route an app
// author uses — NOT by hand-seeding the elision registry. Driving the
// public route proves the whole classification path end-to-end: a regression
// where the `:sensitive` effect stopped reaching the epoch rollup /
// project-egress / pair-MCP wire egress goes RED here.
//
// ## What this test guards
//
// The pull-mode epoch tools `trace-window` and `watch-epochs` egress
// `:rf/epoch-record`s carrying `:db-before` / `:db-after` app-db snapshots
// over the MCP wire. A FRAME-declared sensitive slot (e.g. `[:auth
// :password]`) MUST be protected on egress even with the
// `--allow-sensitive-reads` gate OFF (the published-build default). This
// is the end-to-end wire gate that puts a sensitive value into a live
// app-db and asserts it is protected on the wire through these tools —
// coverage neither source-text greps (wire_vocab_test.clj) nor a
// leaf-counter indicator gate provide, since neither asserts the
// sensitive leaf is actually protected on egress.
//
// ## A flagged epoch ships as a redacted skeleton
//
// The assembler sets the record-level rollup `:rf.epoch/sensitive?`
// (`re-frame.epoch.assembly/sensitive-rollup`) `true` whenever a declared
// sensitive PATH holds a non-nil leaf in `:db-before`/`:db-after` —
// exactly the scenario this test sets up. The rollup says the record
// contained classified material; it is a label, not a reason to withhold
// the record (Spec 009 §Privacy / sensitive data in traces). Every record
// the epoch tools egress has crossed `project-egress` app-side, so the
// default (gate-OFF) read returns the flagged record with the
// declared-sensitive slot redacted and its metadata — `:event-id`,
// outcome, timing and the `:rf.epoch/sensitive? true` label — intact.
//
// So the gate-OFF arm asserts the sentinel is ABSENT, the flagged record
// is PRESENT (its `:rf-conformance/write-secret` event id and its
// `:rf.epoch/sensitive? true` label ride the wire), and no
// `:dropped-sensitive` count is reported: the record reached the wire
// redacted, rather than the slot being empty or the window excluding
// the record.
//
// ## What this test drives (across the MCP boundary, via the SDK Client)
//
//   1. Boot the pair-mcp server WITHOUT `--allow-sensitive-reads`
//      (the published default — the redaction MUST hold).
//   2. Via `eval-cljs` against the live runtime:
//        a. classify `[:rf-conformance/secret]` sensitive through the
//           PUBLIC EP-0025 route — the write event returns the commit-plane
//           `:sensitive [[:rf-conformance/secret]]` effect alongside `:db`,
//           installing the `:source :effect` declaration the rollup +
//           project-egress read, in the same atomic commit as the write;
//        b. dispatch an event that writes a recognisable SENTINEL string
//           into that slot, landing it in the next epoch's `:db-after`
//           (and flagging the epoch's `:rf.epoch/sensitive?` rollup true).
//   3. `tools/call trace-window`, `tools/call watch-epochs` AND
//      `tools/call snapshot` over the `:epochs` slice — the three epoch
//      reads whose egress this gate protects.
//   4. ASSERT the sentinel does NOT appear ANYWHERE in any egress
//      payload, AND that the flagged record itself ships: its event id and
//      `:rf.epoch/sensitive? true` are present, with no `:dropped-sensitive`
//      count (proving the record reached the wire redacted, not that the
//      slot was empty or the window excluded the record).
//   5. Boot a SECOND server WITH `--allow-sensitive-reads`, call the same
//      tools with `:include-sensitive true`, and assert the sentinel DOES
//      cross the wire while an event argument and an effect argument stay
//      redacted — pinning BOTH directions of the gate so the gate itself
//      can't silently invert, and pinning that the opt-in widens only the
//      app-db sensitive axis.
//
// ## Why this is a true regression net
//
// Bypass the projection in either tool's eval form and step 4 goes RED on
// the sentinel; withhold flagged records client-side again and it goes RED
// on the missing event id and label. Under the projected-record contract
// the gate-OFF trace-window returns the write-secret record, labelled
// `:rf.epoch/sensitive? true`, with the secret slot `:rf/redacted`.
//
// ## Gating
//
// **Skipped unless `$SHADOW_CLJS_NREPL_PORT` is set.** Same posture as
// the sibling `live-re-frame2-pair-overflow.cjs` / `-turn-observation.cjs`:
// without a live nREPL the server runs degraded and every tool returns
// the same `:nrepl-port-not-found` envelope — no `:db-*` ever crosses, so
// the redaction surface is unreachable. The hermetic orchestrator
// (`scripts/run-re-frame2-pair-live-hermetic-suite.cjs`) boots
// shadow-cljs + Chromium against `skills/re-frame2-pair/tests/fixture/`
// and wires the env so the live path fires on CI.

const path = require('node:path');
const os = require('node:os');
const {
  runWithWatchdog,
  connectServer,
  closeQuietly,
  registerAuxClient,
  unregisterAuxClient,
  responseText,
} = require('./_runner.cjs');

const SERVER = path.resolve(__dirname, '..', '..', 're-frame2-pair-mcp', 'out', 'server.js');

// Recognisable sentinel written into the declared-sensitive slot. Long +
// unique so a substring search across the whole egress payload (EDN text)
// has zero false-negative risk and zero accidental collision with any
// framework key, hint string, or marker vocabulary.
const SENTINEL = 'rf2-q4o83-SECRET-do-not-leak-7f3a9c';

// The sensitive app-db path. Single segment under a `:rf-conformance/*`
// reserved-style key so it can't collide with any fixture slot.
const SECRET_KEY = ':rf-conformance/secret';

// trace-window's `:ms` window. An epoch's `:committed-at` is a MONOTONIC
// relative clock (sub-ms `performance.now()`-style float — observed ~900
// for a fresh epoch), NOT a `js/Date.now()` wall-clock timestamp.
// trace-window's window filter is `cutoff <= committed-at <= until-ms`
// with `until-ms = Date.now()` (~1.78e12) and `cutoff = until-ms - ms`. A
// modest `:ms` (e.g. 60000) yields `cutoff ≈ 1.78e12`, which excludes the
// tiny committed-at and returns `:epochs []` (the `:window-excludes-history`
// advisory fires). A `:ms` larger than wall-clock-now drives `cutoff`
// negative so every recorded epoch falls inside the window regardless of
// its relative committed-at. We want the FULL ring here, so use a window
// that always covers it. (watch-epochs has no time filter and needs no
// such widening.)
const TRACE_WINDOW_MS = 1_000_000_000_000_000;

// CLJS form (evaluated app-side via eval-cljs) that:
//   1. classifies `[SECRET_KEY]` sensitive through the PUBLIC EP-0025
//      route — the write event returns the commit-plane
//      `:sensitive [[SECRET_KEY]]` effect alongside `:db`. This is the SAME
//      route an app author uses (spec/015-Data-Classification.md;
//      docs/EP/EP-0025-data-classification.md); the router installs the
//      `:source :effect` declaration into the frame's durable elision
//      registry that `sensitive-rollup` and `project-egress` read, in the
//      same atomic commit as the write. No hand-seeded `swap-elision-slot!`,
//      no schema artefact, no frame annotation;
//   2. dispatch-syncs that event, writing the SENTINEL into the slot and
//      classifying it in the SAME commit, landing in the next epoch's
//      `:db-after`.
// The secret is closed over in the handler so it never appears in the
// trigger-event vector — the classified path is the only sensitive
// leaf, mirroring epoch_mcp_egress_conformance_test.clj's drive pattern.
//
// Classifying in the SAME event that writes the value means the rollup is
// stamped true at the assembly of the very epoch the dispatch records (a
// path classified at commit redacts from its first egress).
//
// Registration uses the fn-form `re-frame.events/reg-event` rather than
// the `re-frame.core/reg-event` MACRO: a runtime cljs-eval form cannot
// expand a macro the analyzer hasn't seen, so the macro call would yield
// nil. The fn-form is a plain call against the events ns the fixture
// already loads (transitively via re-frame.core). `reg-event` is the one
// public event form under EP-0018 (semantically reg-event-fx): the handler
// takes the coeffects map and returns a closed effects map (`{:db ...}`).
// dispatch uses the owning-ns fn `re-frame.router/dispatch-sync!` — the
// runtime counterpart `dispatch-sync-impl` aliases in re-frame.core. There
// is no public `dispatch-sync*` fn, and a runtime cljs-eval form cannot
// expand the `dispatch-sync` macro, so it reaches the router fn directly,
// same as a JVM programmatic caller.
//
// Epoch recording must be ACTIVE for the pull-mode tools to have a record
// to egress. `re-frame.core` loads `re-frame.epoch` in a dev build, so
// (a) `(require 're-frame.epoch)` finds it already loaded, with the
// `:epoch/settle!` capture hook the router looks up at drain-settle
// published, and (b) `(configure :epoch-history {:depth 50})` sets the ring
// depth explicitly rather than relying on the fixture's.
// This is runtime configuration, NOT a fixture-source change — it keeps
// the scenario self-contained under tools/mcp-conformance.
//
// The `require`, the `configure`, and the dispatch are THREE SEPARATE
// awaited eval-cljs calls: shadow's runtime `require` schedules an ASYNC
// module load, and a form referencing a namespace before that load settles
// fails to resolve.
//
// Call 1: require the epoch namespace.
const ENABLE_REQUIRE_FORM = `(require 're-frame.epoch)`;

// Call 2: configure a ring depth so the buffer keeps records (default for
// this fixture is effectively disabled — no config ⇒ empty ring), then
// echo the live depth + hook state so seedRuntime can assert both landed.
const ENABLE_CONFIGURE_FORM = `
(do
  (re-frame.core/configure! {:epoch-history {:depth 50}})
  {:hook-installed? (some? (re-frame.late-bind/get-fn :epoch/settle!))
   :depth (:depth (:epoch-history (re-frame.core/current-config)))})`;

// Call 3: declare-sensitive (PUBLIC frame-owned route) + register +
// dispatch the sentinel write.
const SEED_FORM = `
(let [fid (re-frame2-pair.runtime/current-frame)]
  ;; Declare the sensitive slot through the PUBLIC EP-0025 route — the
  ;; commit-plane \`:sensitive\` classification effect, returned by the
  ;; write-secret event alongside \`:db\`. The router folds it into the same
  ;; atomic commit as the \`:db\` write, installing the \`:source :effect\`
  ;; declaration that project-egress (which reads the record's :frame
  ;; elision registry) and sensitive-rollup both consult, so the leaf matches
  ;; at egress AND the dispatched epoch's rollup stamps true. (There is no
  ;; durable \`:sensitive {:app-db …}\` frame annotation.)
  (re-frame.events/reg-event
    :rf-conformance/write-secret
    (fn [{:keys [db]} _]
      {:db (assoc db ${SECRET_KEY} "${SENTINEL}")
       :sensitive [[${SECRET_KEY}]]}))
  (re-frame.router/dispatch-sync! [:rf-conformance/write-secret] {:frame fid})
  {:frame fid
   :declared (re-frame.elision/sensitive-declarations fid)
   :epoch-count (count (re-frame.epoch/epoch-history fid))
   :db-secret (get (re-frame2-pair.runtime/snapshot fid) ${SECRET_KEY})})`;

// Gate-ON argument axes: an event whose ARGUMENT carries one sentinel and
// whose returned `:dispatch` EFFECT carries another. `:include-sensitive`
// lifts only the app-db sensitive axis, so both arguments must still read
// `:rf/redacted` with the gate on and the opt-in given. The handlers touch
// no classified path.
const EVENT_ARG_SENTINEL = 'rf2-42gpe-EVENT-ARG-do-not-leak-3c1d';
const FX_ARG_SENTINEL = 'rf2-42gpe-FX-ARG-do-not-leak-8e5a';
const CARRY_ARGS_ID = ':rf-conformance/carry-args';
const CARRY_ARGS_FORM = `
(let [fid (re-frame2-pair.runtime/current-frame)]
  (re-frame.events/reg-event :rf-conformance/carry-args-sink
    (fn [_ _] {}))
  (re-frame.events/reg-event ${CARRY_ARGS_ID}
    (fn [_ _]
      {:fx [[:dispatch [:rf-conformance/carry-args-sink "${FX_ARG_SENTINEL}"]]]}))
  (re-frame.router/dispatch-sync! [${CARRY_ARGS_ID} "${EVENT_ARG_SENTINEL}"] {:frame fid})
  {:frame fid :epoch-count (count (re-frame.epoch/epoch-history fid))})`;

// A defensive sanity form: confirm the live app-db genuinely carries the
// sentinel at the secret slot (so a green no-leak assertion can't be a
// false-pass from the write silently failing). Reads through the runtime
// `snapshot` fn the pair tools already use — but unredacted, because this
// is an internal eval, not a wire egress.
const VERIFY_WRITE_FORM =
  `(get (re-frame2-pair.runtime/snapshot (re-frame2-pair.runtime/current-frame)) ${SECRET_KEY})`;

// ---- Classified-after-recording arm ----------------------------------------
//
// The label and the redaction are computed at different times:
//
//   1. The `:rf.epoch/sensitive?` label — `sensitive-rollup`
//      (assembly.cljc) computes it ONCE, at record-assembly time, from the
//      THEN-current sensitive declarations.
//   2. The redaction — `project-egress`, which routes a record stamped
//      `:kind :rf/epoch-record` to the epoch projector (tool_pair.cljc
//      §`project-record`). That projector runs SERVER-SIDE inside the eval
//      form, reads the CURRENT registry at EGRESS time, and routes the
//      `:db-before` / `:db-after` slots through `elide-wire-value` ->
//      declared-sensitive paths become `:rf/redacted`. The stamped label
//      passes through UNCHANGED.
//
// This arm drives the shape where the two disagree: record a
// NON-sensitive epoch FIRST (no declaration ⇒ the label stamps FALSE),
// THEN declare the path sensitive, THEN egress. `project-egress` reads the
// now-declared path against the CURRENT registry and MUST redact
// `:db-after` to `:rf/redacted`, while the record ships unlabelled. A
// regression to `project-egress` / `project-payload-slot` (a missed
// `:db-after` in the cond-> of tool_pair.cljc's `project-record-slots`, or
// `elide-wire-value` returning the raw value), or a redaction keyed to the
// stamped label instead of the registry, would leak the secret here — and
// NO other gate would go RED, because the gate-OFF arm only drives the
// labelled shape and the unit `...preserves-redacted-sentinel...` test
// pre-redacts its input.
//
// Distinguishable from the gate-OFF arm by the ABSENT label + sentinel
// ABSENT + the `:rf/redacted` marker PRESENT in the surviving record's
// `:db-after`.
//
// This arm ALSO drives the PUBLIC EP-0025 classification route:
// the `:clear-sensitive` commit-plane effect drops the prior
// `:source :effect` declaration, then a post-hoc
// `apply-classification-effects` re-classifies the inner slot. So the
// arm proves the classification route reaches `project-egress` egress for
// the record-then-classify ordering too — not just the classify-then-record
// ordering of the gate-OFF arm.
// (`re-frame.epoch.state/reset-histories!` is internal projection /
// ring-state plumbing, used here only to isolate the ring; it is NOT a
// durable app-db classification surface and makes no policy claim.)

// A second, independent sensitive slot — distinct from SECRET_KEY so
// this arm's declaration cannot interact with any other epoch in a
// shared ring (this arm's write form wipes the ring first, so it holds
// only the one non-sensitive-at-record-time epoch we seed here).
const INNER_SECRET_KEY = ':rf-conformance/inner-secret';
const INNER_SENTINEL = 'rf2-ywn27-INNER-secret-do-not-leak-9b2e4d';

// Step A: enable epoch recording (require + configure — same two awaited
// calls as `seedRuntime`), then register + dispatch the sentinel write
// WITHOUT any sensitive declaration in scope. The epoch must be assembled
// with the rollup stamp FALSE so the record ships unlabelled.
//
// CLEAN-SLATE precondition: this arm shares the live fixture runtime
// (same nREPL / `:rf/default` frame) with the gate-OFF arm that ran
// before it — which declared `:rf-conformance/secret` sensitive (via the
// commit-plane `:sensitive` effect) AND left its value in app-db. If that
// declaration were still live when our epoch assembles, `sensitive-rollup`
// (assembly.cljc) would see a declared path holding a non-nil leaf and
// stamp the rollup TRUE — and this arm would no longer separate the
// registry read from the stamped label. So the form FIRST wipes the epoch
// ring, then dispatches a secret-write event that returns
// `:clear-sensitive` for the prior slot AND dissocs its value, guaranteeing
// no declared-sensitive leaf exists at assembly time. We read the rollup
// off the HEAD (last) record — the inner-secret write — which is the one
// the egress tools surface.
const INNER_WRITE_FORM = `
(let [fid (re-frame2-pair.runtime/current-frame)]
  ;; This arm shares the live fixture runtime with the gate-OFF arm that
  ;; ran before it — whose SENSITIVE epoch is still in the ring. Wipe the
  ;; ring so it holds ONLY our one inner epoch: otherwise the old
  ;; epoch's :rf.epoch/sensitive? true label would ship beside ours and
  ;; break this arm's isolation (it pins the label absent).
  ;; (reset-histories! is internal ring-state plumbing, not a policy
  ;; surface — see the arm header.)
  (re-frame.epoch.state/reset-histories!)
  ;; Clear the prior sensitive declaration through the PUBLIC EP-0025 route:
  ;; the write-inner-secret event returns the \`:clear-sensitive\` commit-plane
  ;; effect for the prior secret slot (the set/unset symmetry of the axis),
  ;; dropping its \`:source :effect\` declaration. The assembler then sees an
  ;; EMPTY sensitive-paths set when our epoch is built (rollup ⇒ false).
  ;; (Re-registration does not clear classification — there is no frame
  ;; annotation; \`:clear-sensitive\` is the un-classify path.)
  (re-frame.events/reg-event
    :rf-conformance/write-inner-secret
    (fn [{:keys [db]} _]
      ;; Drop any prior declared-sensitive slot's value too, so even a
      ;; stray lingering declaration can't find a non-nil leaf.
      {:db (-> db
               (dissoc ${SECRET_KEY})
               (assoc ${INNER_SECRET_KEY} "${INNER_SENTINEL}"))
       :clear-sensitive [[${SECRET_KEY}]]}))
  (re-frame.router/dispatch-sync! [:rf-conformance/write-inner-secret] {:frame fid})
  {:frame fid
   :rollup-at-assembly
   ;; The STAMPED rollup on the just-recorded (HEAD) epoch — MUST be
   ;; false/absent (no declaration existed when the assembler ran),
   ;; proving the record ships unlabelled.
   (:rf.epoch/sensitive? (last (re-frame.epoch/epoch-history fid)))
   :epoch-count (count (re-frame.epoch/epoch-history fid))
   :db-secret (get (re-frame2-pair.runtime/snapshot fid) ${INNER_SECRET_KEY})})`;

// Step B: NOW classify the path sensitive — AFTER the epoch was recorded.
// This arm deliberately classifies WITHOUT recording a new epoch (so the
// already-recorded epoch stays HEAD and its stamped rollup, computed once at
// assembly, can be re-read), to prove `project-egress` reads the CURRENT
// elision registry at egress. We therefore apply the EP-0025 commit-plane
// `:sensitive` classification effect DIRECTLY onto the frame's runtime-db
// (`apply-classification-effects`, `:source :effect`) — the same registry
// write the effect performs at commit, minus the dispatch/epoch. (An ordinary
// app author would return the `:sensitive` effect from an event; here the
// internal seed avoids perturbing the epoch ring the assertion re-reads.)
const INNER_DECLARE_FORM = `
(let [fid (re-frame2-pair.runtime/current-frame)]
  (re-frame.frame/swap-runtime-db! fid
    (fn [rt] (re-frame.elision/apply-classification-effects
               rt {:sensitive [[${INNER_SECRET_KEY}]]})))
  {:frame fid
   :declared (re-frame.elision/sensitive-declarations fid)
   ;; Re-read the STAMPED rollup on the SAME recorded epoch — it MUST
   ;; still be false/absent: sensitive-rollup ran once at assembly and
   ;; is NOT recomputed on a later declaration, so only the registry read
   ;; at egress can protect the slot.
   :rollup-after-declare
   (:rf.epoch/sensitive? (last (re-frame.epoch/epoch-history fid)))})`;

// ---- helpers ---------------------------------------------------------------

function assertOk(resp, name) {
  if (resp.isError) {
    throw new Error(
      name + ' returned isError; the redaction gate cannot assert on an ' +
        'error envelope. Got: ' + responseText(resp).slice(0, 400),
    );
  }
}

// Extract the `:declared` slot's rendered VALUE (the text of the map
// literal / `nil` immediately following the key) from an eval-cljs
// response's text, matching the shape `re-frame.elision/sensitive-
// declarations` actually returns: a map from classified PATH VECTOR to the
// retained OWNER-SET (the registry is multi-owner), e.g.
// `{[:rf-conformance/secret] #{{:source :effect}}}` (and, when two
// independent owners claim one path, `#{{:source :effect} {:source :route}}`),
// or `{}` when nothing is classified. The owner-set nests THREE brace levels
// deep — the outer map, the `#{…}` owner-set, and each owner map — so the
// regex tolerates three levels of brace nesting (it also matches a bare
// single-map `{:source :effect}` value). Returns `null` if `:declared` is
// absent entirely (a malformed / unexpected eval return — the caller treats
// that as a precondition failure too).
function declaredValueText(text) {
  // The closing `\}` alternative deliberately carries NO trailing `\b`:
  // a map literal is almost always followed by another non-word char
  // (`,` / `}` / end-of-string), and `\b` never matches between two
  // non-word characters — a boundary assertion there would silently
  // fail to match the common case. `nil(?!\w)` gets the equivalent
  // "don't swallow a longer identifier" guard the word-boundary was
  // after, scoped to the one alternative that actually needs it. The
  // brace group is nested to depth 3 to admit the `{path #{{:source …}}}`
  // owner-set shape.
  const m =
    /:declared\s+(\{(?:[^{}]|\{(?:[^{}]|\{[^{}]*\})*\})*\}|nil(?!\w))/.exec(text);
  return m ? m[1] : null;
}

// The load-bearing seed/declare precondition: `:declared` must be a
// NON-EMPTY map whose keys include the path THIS step just classified —
// `pathLiteral` is the rendered path vector, e.g. `[:rf-conformance/secret]`.
//
// `:declared` is a literal key written into every SEED_FORM /
// INNER_DECLARE_FORM return map (`:declared (re-frame.elision/sensitive-
// declarations fid)`), so the substring `:declared` (or
// `sensitive-declarations`) appears in the pr-str on ANY successful eval —
// classified or not. A bare key-PRESENCE check would be a tautology that
// can never fail: it would only prove the eval didn't error, never that
// the classification actually landed. If the EP-0025 commit-plane
// `:sensitive` effect silently failed to register the declaration,
// `sensitive-declarations` would return `{}` / `nil` and such a check would
// pass.
//
// This asserts the VALUE instead: non-empty (rejects the `{}` / `nil`
// shape `sensitive-declarations` returns when nothing is classified) AND
// containing the exact classified-path literal — so a silently-failed
// classification effect trips this precondition rather than passing it
// unnoticed (the downstream sentinel/redaction assertions catch the leak
// too; this makes the precondition itself real, as defense in depth).
function assertDeclared(text, pathLiteral, label) {
  const value = declaredValueText(text);
  if (value === null || value === 'nil' || value === '{}' || !value.includes(pathLiteral)) {
    throw new Error(
      label + ' did not confirm the sensitive declaration landed — ' +
        '`:declared` MUST be a non-empty map containing the classified ' +
        'path ' + pathLiteral + ' (a bare key-presence check is a ' +
        'tautology — `:declared` is a literal key in the eval form\'s ' +
        'return value and renders on ANY successful eval); ' +
        'got :declared value: ' + JSON.stringify(value) + '. Full: ' +
        text.slice(0, 300),
    );
  }
}

// Read the `:dropped-sensitive` count off the envelope text, or null when
// the slot is absent. The epoch tools withhold no record, so the slot is
// always absent (it is omit-when-zero); a count would mean a record was
// withheld. A regex read keeps this in step with the substring-search
// posture used for the sentinel itself — we don't parse the full EDN, we
// read the one load-bearing slot.
function droppedSensitiveCount(text) {
  const m = /:dropped-sensitive\s+(\d+)\b/.exec(text);
  return m ? Number(m[1]) : null;
}

function assertNothingWithheld(text, name) {
  const dropped = droppedSensitiveCount(text);
  if (dropped !== null) {
    throw new Error(
      name + ' reports `:dropped-sensitive ' + dropped + '` — the epoch ' +
        'tools withhold no record; a flagged record ships projected and ' +
        'labelled. Payload (first 600 chars): ' + text.slice(0, 600),
    );
  }
}

// The flagged record's label, as it renders in the EDN text.
const SENSITIVE_LABEL = ':rf.epoch/sensitive? true';
const WRITE_SECRET_ID = ':rf-conformance/write-secret';

// The load-bearing assertion for a flagged record: the SENTINEL must be
// ABSENT, AND the record itself must ship — its event id and its
// `:rf.epoch/sensitive? true` label present, no `:dropped-sensitive`
// count. The presence half is what keeps the absence half honest: a
// window-excludes-everything regression, or a record withheld client-side,
// also shows no sentinel, but carries neither the event id nor the label.
function assertRedactedSkeleton(resp, name) {
  assertOk(resp, name);
  const text = responseText(resp);
  if (text.includes(SENTINEL)) {
    throw new Error(
      name + ' LEAKED the sensitive sentinel over the MCP wire with the ' +
        '--allow-sensitive-reads gate OFF (default). The declared-sensitive ' +
        'slot ' + SECRET_KEY + ' MUST read `:rf/redacted` in the projected ' +
        'record (Spec Security.md §Epoch privacy posture).\nPayload ' +
        '(first 600 chars): ' + text.slice(0, 600),
    );
  }
  if (!text.includes(WRITE_SECRET_ID) || !text.includes(SENSITIVE_LABEL)) {
    throw new Error(
      name + ' egress payload does NOT carry the flagged record (event id ' +
        WRITE_SECRET_ID + ' present: ' + text.includes(WRITE_SECRET_ID) +
        '; label `' + SENSITIVE_LABEL + '` present: ' +
        text.includes(SENSITIVE_LABEL) + '). A record that contained ' +
        'classified material MUST ship, redacted and labelled; a bare ' +
        'sentinel-absence check would false-pass if the record were ' +
        'withheld or fell outside the window. Payload (first 600 chars): ' +
        text.slice(0, 600),
    );
  }
  assertNothingWithheld(text, name);
}

// The classified-after-recording assertion: the INNER_SENTINEL must be
// ABSENT, the surviving record must carry the `:rf/redacted` marker, AND
// no `:rf.epoch/sensitive? true` label may ride the wire — proving the
// protection was the registry read at egress, not anything keyed to the
// stamped label. Three conditions, each load-bearing:
//
//   - sentinel ABSENT: the raw secret did not cross the wire.
//   - `:rf/redacted` PRESENT: a record SURVIVED to egress and its
//     `:db-after` slot was value-redacted — distinguishes a genuine
//     redaction from a vacuous empty-ring / window-excludes pass
//     (which would also show no sentinel but carry NO `:rf/redacted`).
//   - label ABSENT: the record's stamped rollup is false, so a redaction
//     that read the label rather than the CURRENT registry would have
//     shipped the secret. The ring holds only this one epoch, so any
//     label on the wire would mean the isolation broke.
function assertInnerRedacted(resp, name) {
  assertOk(resp, name);
  const text = responseText(resp);
  if (text.includes(INNER_SENTINEL)) {
    throw new Error(
      name + ' LEAKED the inner sentinel over the MCP wire (gate OFF). The ' +
        'epoch was recorded BEFORE ' + INNER_SECRET_KEY + ' was declared ' +
        'sensitive, so its stamped `:rf.epoch/sensitive?` rollup is false; ' +
        'the `project-egress` value-redaction (tool_pair.cljc `project-record`), ' +
        'reading the CURRENT registry, MUST turn `:db-after` into ' +
        '`:rf/redacted`. A leak here ' +
        'means a regression to project-egress / project-payload-slot.\nPayload ' +
        '(first 600 chars): ' + text.slice(0, 600),
    );
  }
  if (!text.includes(':rf/redacted')) {
    throw new Error(
      name + ' egress payload carries NO `:rf/redacted` marker. The ' +
        'projection MUST have value-redacted the surviving record\'s ' +
        '`:db-after` slot to `:rf/redacted`. Its absence means either the ' +
        'record did not survive to egress (a vacuous empty-ring / ' +
        'window-excludes pass — the no-leak check would false-pass) or the ' +
        'projection did not fire. ' +
        'Payload (first 600 chars): ' + text.slice(0, 600),
    );
  }
  if (text.includes(SENSITIVE_LABEL)) {
    throw new Error(
      name + ' ships a `' + SENSITIVE_LABEL + '` label. The only epoch in ' +
        'the ring was recorded BEFORE its path was classified, so its ' +
        'stamped rollup is false; a label means the arm no longer isolates ' +
        'the registry read from the stamped label. ' +
        'Payload (first 600 chars): ' + text.slice(0, 600),
    );
  }
  assertNothingWithheld(text, name);
}

// Enable epoch recording (require → configure, two awaited calls) +
// declare-sensitive + write-sentinel (a third call) against `client`'s
// runtime. The three-call split is load-bearing — see the form comments
// above. Asserts each step landed: the hook is installed, the ring depth
// is configured, an epoch was recorded, and the sentinel is live in
// app-db. Shared by both the gate-OFF and gate-ON arms — both need an
// epoch carrying the secret.
async function seedRuntime(client, label) {
  const req = await client.callTool({ name: 'eval-cljs', arguments: { form: ENABLE_REQUIRE_FORM } });
  assertOk(req, label + ' eval-cljs require-epoch');

  const enable = await client.callTool({ name: 'eval-cljs', arguments: { form: ENABLE_CONFIGURE_FORM } });
  assertOk(enable, label + ' eval-cljs configure-epoch');
  // Assert the STRUCTURED `:hook-installed? true` slot, not a bare 'true'
  // substring. The form returns `{:hook-installed? <bool> :depth <int>}`;
  // a substring search for 'true' anywhere in the rendered EDN would be
  // satisfied by a stray 'true' in any future-added slot (a note string,
  // a richer map) even with the hook NOT installed. Pin the typed value
  // by matching the key+value as a full token — mirror the `:epoch-count
  // \s+0\b` regex style used for the seed assertion below.
  if (!/:hook-installed\?\s+true\b/.test(responseText(enable))) {
    throw new Error(
      label + ' eval-cljs configure-epoch did not install the :epoch/settle! hook ' +
        '(:hook-installed? not true — epoch recording would stay disabled and no ' +
        'epoch would be recorded). Got: ' + responseText(enable).slice(0, 300),
    );
  }

  const seed = await client.callTool({ name: 'eval-cljs', arguments: { form: SEED_FORM } });
  assertOk(seed, label + ' eval-cljs seed');
  const seedText = responseText(seed);
  // The seed return echoes `:epoch-count` — a 0 here means the dispatch
  // recorded no epoch (recording still disabled / async require not yet
  // settled), so the pull-mode tools would egress an empty ring and the
  // no-leak assertion would be a vacuous false-pass. Pin it > 0.
  if (/:epoch-count\s+0\b/.test(seedText)) {
    throw new Error(
      label + ' eval-cljs seed recorded ZERO epochs (:epoch-count 0). The ' +
        'pull-mode tools would egress an empty ring and the no-leak assertion ' +
        'would be vacuous. Got: ' + seedText.slice(0, 300),
    );
  }
  assertDeclared(seedText, '[' + SECRET_KEY + ']', label + ' eval-cljs seed');
  return seed;
}

// Pre-flight SKIP — same posture as the sibling live-* variants.
if (!process.env.SHADOW_CLJS_NREPL_PORT) {
  runWithWatchdog.skip(
    'live-re-frame2-pair-redaction: $SHADOW_CLJS_NREPL_PORT not set.\n' +
      '      This variant requires a live shadow-cljs nREPL — without one\n' +
      '      every tool returns the degraded :nrepl-port-not-found envelope\n' +
      '      and no :db-* ever crosses the wire, so the egress-redaction\n' +
      '      surface is unreachable. The hermetic orchestrator boots the\n' +
      '      fixture runtime and wires the env so this gate fires on CI.',
  );
}

// Enable epoch recording on `client`'s runtime — the require + configure
// half of `seedRuntime`, factored out so the inner-projection arm can
// drive its OWN record-then-declare ordering (it must NOT declare before
// the dispatch, so it can't reuse the full `seedRuntime`).
async function enableEpochRecording(client, label) {
  const req = await client.callTool({ name: 'eval-cljs', arguments: { form: ENABLE_REQUIRE_FORM } });
  assertOk(req, label + ' eval-cljs require-epoch');
  const enable = await client.callTool({ name: 'eval-cljs', arguments: { form: ENABLE_CONFIGURE_FORM } });
  assertOk(enable, label + ' eval-cljs configure-epoch');
  // Structured `:hook-installed? true` token, not a bare 'true' substring
  // — same rationale as the seedRuntime check above.
  if (!/:hook-installed\?\s+true\b/.test(responseText(enable))) {
    throw new Error(
      label + ' eval-cljs configure-epoch did not install the :epoch/settle! hook ' +
        '(:hook-installed? not true — epoch recording would stay disabled and no ' +
        'epoch would be recorded). Got: ' + responseText(enable).slice(0, 300),
    );
  }
}

// ---- Classified-after-recording arm ----------------------------------------
//
// A SECOND gate-OFF server (no `--allow-sensitive-reads`). Its write form
// wipes the shared epoch ring first, so the ring holds ONLY the one epoch
// we seed here — no other recorded epoch can contaminate the label-absent
// assertion. Drives the record-THEN-declare ordering so the record ships
// unlabelled and the registry read at egress is the only thing that can
// redact it. See the form-block + `assertInnerRedacted` comments above
// for why this is distinguishable from the gate-OFF arm.
async function runInnerProjectionArm() {
  const { client } = await connectServer({
    clientName: 'mcp-conformance-re-frame2-pair-redaction-inner',
    stderrPrefix: '[server:inner]',
    transportSpec: {
      command: process.execPath,
      // No `--allow-sensitive-reads` — gate OFF, the published default.
      // The projection redacts because `incl?` is forced false, so the
      // sensitive axis stays closed, independent of the label.
      args: [SERVER],
      cwd: os.tmpdir(),
      env: { ...process.env },
    },
    // Register with the outer watchdog the INSTANT this secondary child is
    // constructed — BEFORE connect awaits — so a hang in THIS arm's boot or
    // a later tool call is reaped by the outer timeout instead of orphaning
    // the secondary Node child.
    onClient: registerAuxClient,
  });
  try {
    await enableEpochRecording(client, 'inner');

    // Step A: record a NON-sensitive epoch (no declaration yet). The
    // assembler stamps the rollup false.
    const write = await client.callTool({ name: 'eval-cljs', arguments: { form: INNER_WRITE_FORM } });
    assertOk(write, 'inner eval-cljs write-secret');
    const writeText = responseText(write);
    if (/:epoch-count\s+0\b/.test(writeText)) {
      throw new Error(
        'inner eval-cljs write recorded ZERO epochs (:epoch-count 0) — the ' +
          'pull-mode tools would egress an empty ring and the assertion would ' +
          'be vacuous. Got: ' + writeText.slice(0, 300),
      );
    }
    if (!writeText.includes(INNER_SENTINEL)) {
      throw new Error(
        'inner eval-cljs write: the live app-db does NOT carry the inner ' +
          'sentinel at ' + INNER_SECRET_KEY + ' — the write never landed, so ' +
          'the no-leak assertion would be a vacuous false-pass. Got: ' +
          writeText.slice(0, 300),
      );
    }
    // The stamped rollup at assembly time MUST be false/absent (no
    // declaration existed) — pins that the record ships unlabelled.
    if (/:rollup-at-assembly\s+true\b/.test(writeText)) {
      throw new Error(
        'inner eval-cljs write: the epoch\'s stamped `:rf.epoch/sensitive?` ' +
          'rollup is already TRUE at assembly time — this arm would NOT ' +
          'separate the registry read from the stamped label. The ' +
          'fixture must record the epoch BEFORE declaring the path. ' +
          'Got: ' + writeText.slice(0, 300),
      );
    }
    console.log('OK   inner write -> non-sensitive epoch recorded (rollup false); sentinel live in app-db');

    // Step B: NOW declare the path sensitive — AFTER the epoch was
    // recorded. `project-egress` reads this CURRENT registry at egress.
    const declare = await client.callTool({ name: 'eval-cljs', arguments: { form: INNER_DECLARE_FORM } });
    assertOk(declare, 'inner eval-cljs declare-sensitive');
    const declareText = responseText(declare);
    assertDeclared(declareText, '[' + INNER_SECRET_KEY + ']', 'inner eval-cljs declare');
    // The stamped rollup on the recorded epoch MUST STILL be false after
    // the declaration — `sensitive-rollup` runs once at assembly and is
    // not recomputed. This is the load-bearing precondition: the label
    // stays false, so the registry read at egress is the only thing
    // standing between the secret and the wire.
    if (/:rollup-after-declare\s+true\b/.test(declareText)) {
      throw new Error(
        'inner eval-cljs declare: the recorded epoch\'s stamped rollup became ' +
          'TRUE after the post-hoc declaration — this arm would not separate ' +
          'the registry read from the stamped label. ' +
          'Got: ' + declareText.slice(0, 300),
      );
    }
    console.log('OK   inner declare -> path now sensitive; recorded epoch rollup STILL false (unlabelled)');

    // trace-window: the projection must redact `:db-after` to
    // `:rf/redacted` with no label on the wire. `:epochs-mode full` +
    // `:dedup false` so the redacted leaf rides the wire verbatim (not
    // diff-collapsed away), matching the unit pipeline-preservation test.
    const tw = await client.callTool({
      name: 'trace-window',
      arguments: { ms: TRACE_WINDOW_MS, 'epochs-mode': 'full', dedup: false },
    });
    assertInnerRedacted(tw, 'trace-window (classified after recording)');
    console.log('OK   trace-window (gate OFF, classified after recording) -> :db-after :rf/redacted, unlabelled');

    // watch-epochs: the same registry read at its INDEPENDENT call site.
    const we = await client.callTool({
      name: 'watch-epochs',
      arguments: { 'epochs-mode': 'full', dedup: false },
    });
    assertInnerRedacted(we, 'watch-epochs (classified after recording)');
    console.log('OK   watch-epochs (gate OFF, classified after recording) -> :db-after :rf/redacted, unlabelled');
  } finally {
    // Deregister before the explicit close so the watchdog set doesn't hold
    // a stale handle past teardown.
    unregisterAuxClient(client);
    await closeQuietly(client);
  }
}

// ---- gate-ON arm (second server) -------------------------------------------
//
// The runner manages a single client/transport (the gate-OFF arm — the
// load-bearing regression net). The gate-ON arm needs a SECOND server
// booted WITH `--allow-sensitive-reads`, so we stand up an independent
// SDK client/transport here and tear it down explicitly. Pinning the
// gate-ON direction stops the gate itself silently inverting: a
// regression that forced redaction ON even with the operator's opt-in
// would pass a gate-OFF-only test but break the operator's deliberate
// raw-state read.
async function runGateOnArm() {
  // Stand up the second server via the shared spawn+connect primitive.
  // The `[server:gate-on]` stderr prefix keeps this concurrent boot's logs
  // distinguishable from the runner-managed gate-OFF server's `[server]`
  // lines.
  const { client } = await connectServer({
    clientName: 'mcp-conformance-re-frame2-pair-redaction-gate-on',
    stderrPrefix: '[server:gate-on]',
    transportSpec: {
      command: process.execPath,
      args: [SERVER, '--allow-sensitive-reads'],
      cwd: os.tmpdir(),
      env: { ...process.env },
    },
    // Register with the outer watchdog the INSTANT this secondary child is
    // constructed — BEFORE connect awaits — so a hang in this gate-ON boot
    // or a later tool call is reaped by the outer timeout instead of
    // orphaning the secondary Node child.
    onClient: registerAuxClient,
  });
  try {
    // Same seed: enable epoch recording, declare the sensitive slot, and
    // write the sentinel into app-db on this server's runtime.
    await seedRuntime(client, 'gate-on');

    // With the gate ON, `:include-sensitive true` is HONOURED — the
    // operator's explicit opt-in ships raw state. The sentinel MUST cross.
    const tw = await client.callTool({
      name: 'trace-window',
      arguments: { ms: TRACE_WINDOW_MS, 'include-sensitive': true },
    });
    assertOk(tw, 'gate-on trace-window');
    if (!responseText(tw).includes(SENTINEL)) {
      throw new Error(
        'gate-on trace-window did NOT ship the sentinel with ' +
          '--allow-sensitive-reads + :include-sensitive true. The operator\'s ' +
          'explicit raw-state opt-in MUST be honoured (gate parity); ' +
          'forcing redaction ON regardless would break the deliberate read. ' +
          'Payload (first 400 chars): ' + responseText(tw).slice(0, 400),
      );
    }
    console.log('OK   gate-ON trace-window (+:include-sensitive) -> sentinel SHIPS (opt-in honoured)');

    const we = await client.callTool({
      name: 'watch-epochs',
      arguments: { 'include-sensitive': true },
    });
    assertOk(we, 'gate-on watch-epochs');
    if (!responseText(we).includes(SENTINEL)) {
      throw new Error(
        'gate-on watch-epochs did NOT ship the sentinel with ' +
          '--allow-sensitive-reads + :include-sensitive true. Payload ' +
          '(first 400 chars): ' + responseText(we).slice(0, 400),
      );
    }
    console.log('OK   gate-ON watch-epochs (+:include-sensitive) -> sentinel SHIPS (opt-in honoured)');

    // The opt-in widens ONLY the app-db sensitive axis: an event argument
    // and an effect argument stay `:rf/redacted` under it.
    const carry = await client.callTool({ name: 'eval-cljs', arguments: { form: CARRY_ARGS_FORM } });
    assertOk(carry, 'gate-on eval-cljs carry-args');
    const twArgs = await client.callTool({
      name: 'trace-window',
      arguments: { ms: TRACE_WINDOW_MS, 'include-sensitive': true, 'epochs-mode': 'full', dedup: false },
    });
    assertOk(twArgs, 'gate-on trace-window (argument axes)');
    const twArgsText = responseText(twArgs);
    if (!twArgsText.includes(CARRY_ARGS_ID) || !twArgsText.includes(SENTINEL)) {
      throw new Error(
        'gate-on trace-window does not carry both the ' + CARRY_ARGS_ID +
          ' record and the app-db sentinel, so the argument-axis check below ' +
          'would be vacuous. Payload (first 600 chars): ' + twArgsText.slice(0, 600),
      );
    }
    for (const [label, needle] of [['event argument', EVENT_ARG_SENTINEL], ['effect argument', FX_ARG_SENTINEL]]) {
      if (twArgsText.includes(needle)) {
        throw new Error(
          'gate-on trace-window (+:include-sensitive) shipped a raw ' + label +
            '. `:include-sensitive` widens only the app-db sensitive axis; ' +
            'event and effect arguments MUST stay `:rf/redacted`. Payload ' +
            '(first 600 chars): ' + twArgsText.slice(0, 600),
        );
      }
    }
    console.log('OK   gate-ON trace-window (+:include-sensitive) -> event + effect arguments still REDACTED');
  } finally {
    // Deregister before the explicit close so the watchdog set doesn't hold
    // a stale handle past teardown.
    unregisterAuxClient(client);
    await closeQuietly(client);
  }
}

// ---- gate-OFF arm (primary — the regression net) ---------------------------
//
// Hard cap so a hung server doesn't wedge CI. nREPL connect + a few eval
// round-trips + two tool calls + the gate-on arm comfortably fit in 60s.
runWithWatchdog(
  {
    watchdogMs: 60000,
    clientName: 'mcp-conformance-re-frame2-pair-redaction',
    transportSpec: {
      command: process.execPath,
      // No `--allow-sensitive-reads` — the published-build default. This
      // is the only configuration where the redaction MUST hold
      // regardless of per-call args.
      args: [SERVER],
      cwd: os.tmpdir(),
      env: { ...process.env },
    },
  },
  async (client) => {
    console.log('OK   connect -> server attached on nREPL', process.env.SHADOW_CLJS_NREPL_PORT);

    // 1. Seed: enable epoch recording, declare the sensitive slot, dispatch
    // the sentinel write. The seed's own return value (`:declared` +
    // `:db-secret`) is an INTERNAL eval result, not a wire egress — it
    // legitimately echoes the raw secret back. That is expected and NOT a
    // leak (the gate is the tool-egress path, not the eval primitive).
    // `seedRuntime` asserts the hook installed, an epoch recorded, and the
    // declaration landed — so a silent failure can't mask a false-pass.
    await seedRuntime(client, 'gate-off');
    console.log('OK   eval-cljs seed -> epoch recorded; sensitive slot declared + sentinel written');

    // 2. Sanity: the live app-db genuinely carries the sentinel at the
    // secret slot. A green no-leak assertion below is only meaningful if
    // the secret is actually present to leak.
    const verify = await client.callTool({
      name: 'eval-cljs',
      arguments: { form: VERIFY_WRITE_FORM },
    });
    assertOk(verify, 'eval-cljs verify-write');
    if (!responseText(verify).includes(SENTINEL)) {
      throw new Error(
        'eval-cljs verify-write: the live app-db does NOT carry the sentinel ' +
          'at ' + SECRET_KEY + ' — the write never landed, so the no-leak ' +
          'assertion would be a vacuous false-pass. Got: ' +
          responseText(verify).slice(0, 400),
      );
    }
    console.log('OK   eval-cljs verify-write -> sentinel confirmed live in app-db (raw, on-box)');

    // 3a. trace-window — the first pull-mode tool. Wide window so the
    // just-dispatched epoch is comfortably inside it. The flagged epoch
    // ships as a redacted skeleton: sentinel ABSENT, event id + label
    // PRESENT, no :dropped-sensitive.
    const tw = await client.callTool({ name: 'trace-window', arguments: { ms: TRACE_WINDOW_MS } });
    assertRedactedSkeleton(tw, 'trace-window');
    console.log('OK   trace-window (gate OFF) -> sentinel ABSENT + flagged record SHIPS redacted');

    // 3b. watch-epochs — the second pull-mode tool. No :since-id → the
    // full ring (which includes the sentinel epoch).
    const we = await client.callTool({ name: 'watch-epochs', arguments: {} });
    assertRedactedSkeleton(we, 'watch-epochs');
    console.log('OK   watch-epochs (gate OFF) -> sentinel ABSENT + flagged record SHIPS redacted');

    // 3b'. snapshot's `:epochs` slice, expanded to `:full` — the third
    // epoch read, crossing the same projection at its own call site.
    const snap = await client.callTool({
      name: 'snapshot',
      arguments: { include: ['epochs'], mode: 'full' },
    });
    assertRedactedSkeleton(snap, 'snapshot :epochs');
    console.log('OK   snapshot :epochs (gate OFF) -> sentinel ABSENT + flagged record SHIPS redacted');

    // 3c. Hostile per-call opt-in MUST NOT defeat the gate. A caller
    // passing `:include-sensitive true` to a server booted WITHOUT
    // `--allow-sensitive-reads` cannot talk it into shipping raw state —
    // the boot gate forces `incl? false` regardless. Pin that the per-call
    // arg is powerless when the operator didn't opt in.
    const twHostile = await client.callTool({
      name: 'trace-window',
      arguments: { ms: TRACE_WINDOW_MS, 'include-sensitive': true },
    });
    assertRedactedSkeleton(twHostile, 'trace-window (hostile :include-sensitive)');
    console.log(
      'OK   trace-window (gate OFF + hostile :include-sensitive true) -> still REDACTED',
    );

    // 3d. watch-epochs hostile per-call opt-in — the SIBLING of 3c.
    // trace-window and watch-epochs compute `incl?` at SEPARATE call sites
    // (the `incl?` bindings in trace_window.cljs and watch_epochs.cljs) —
    // each is an independent
    // `(if (raw-state-allowed?) (parse-bool-arg ... :include-sensitive)
    // false)`, NOT shared code. So a regression that drops the boot-gate
    // guard on watch-epochs ALONE (e.g. watch_epochs.cljs's `incl?`
    // simplified to `incl? (parse-bool-arg raw-args :include-sensitive)`)
    // would let a caller's hostile `:include-sensitive true` talk a server
    // booted WITHOUT --allow-sensitive-reads into shipping RAW epoch
    // `:db-after` — `incl? true` ⇒ `project-egress` lifts the app-db
    // sensitive axis. The
    // default-args gate-OFF call (3b) forces `incl? false` regardless, so
    // it cannot catch this; this hostile-arg call is the one that pins the
    // boot-gate guard on watch-epochs's INDEPENDENT site.
    const weHostile = await client.callTool({
      name: 'watch-epochs',
      arguments: { 'include-sensitive': true },
    });
    assertRedactedSkeleton(weHostile, 'watch-epochs (hostile :include-sensitive)');
    console.log(
      'OK   watch-epochs (gate OFF + hostile :include-sensitive true) -> still REDACTED',
    );

    // 3e. Classified after recording — the redaction reads the CURRENT
    // registry at egress, not the label stamped at assembly. See
    // `runInnerProjectionArm`.
    await runInnerProjectionArm();

    // 4. Gate-ON arm: pin the other direction so the gate can't invert.
    await runGateOnArm();

    console.log('\nRE-FRAME2-PAIR-MCP LIVE EGRESS-PROTECTION CONFORMANCE GREEN');
  },
);
