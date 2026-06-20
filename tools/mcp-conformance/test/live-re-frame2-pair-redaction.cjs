// Live-re-frame2-pair MCP-client conformance variant exercising egress
// protection of a FRAME-OWNED `:sensitive {:app-db …}` slot through the
// pull-mode epoch tools (`trace-window` + `watch-epochs`).
// Source: rf2-q4o83 (regression net for rf2-6wvh5; correctness review
// finding rf2-5t8mr.18). Updated for rf2-5613h (epoch-rollup whole-drop)
// and rf2-2h7153 (drive the PUBLIC EP-0015 frame-owned route).
//
// ## EP-0015 — durable app-db policy is FRAME-OWNED (rf2-2h7153)
//
// Durable app-db egress policy is declared on the FRAME, through the
// public `reg-frame` route: `(reg-frame :id {:sensitive {:app-db
// [[:auth :token]]}})` (spec/015-Data-Classification.md §Frame-owned
// durable classification; docs/EP/EP-0015-frame-owned-egress-policy.md).
// `reg-frame` validates the classification and installs it into the
// frame's durable elision registry tagged `:source :frame`; the epoch
// assembler's `sensitive-rollup` and the off-box `projected-record` both
// read THAT registry. Schema-attached `:sensitive?` app-db classification
// was REMOVED (EP-0015 §8): schemas describe shape, not durable app-db
// egress policy, and the former `populate-sensitive-from-schemas!`
// importer is gone.
//
// The gate-OFF / gate-ON arms below therefore declare the sensitive slot
// through the FRAME-OWNED `reg-frame` route — the same route an app
// author uses — NOT by hand-seeding the elision registry. This is the
// load-bearing change rf2-2h7153 fixes: the prior version wrote the
// `:sensitive-declarations` slot directly via `swap-elision-slot!`, which
// EP-0015 treats as internal projection plumbing, so the surface could
// stay GREEN even if `reg-frame` `:sensitive {:app-db …}` policy stopped
// reaching the epoch rollup / projected-record / pair-MCP wire egress.
// Driving the public route proves the whole frame-owned path end-to-end.
//
// ## The hole this closes
//
// rf2-6wvh5 was a HIGH-severity egress leak: the pull-mode epoch tools
// `trace-window` and `watch-epochs` egressed whole `:rf/epoch-record`s
// carrying `:db-before` / `:db-after` app-db snapshots over the MCP wire
// WITHOUT routing them through the framework's off-box projection. A
// schema-declared `:sensitive?` slot (e.g. `[:auth :password]`) rode the
// wire verbatim even with the `--allow-sensitive-reads` gate OFF (the
// published-build default). The original rf2-6wvh5 fix routed each
// egressed record through `re-frame.core/projected-record` server-side —
// value-redacting the sensitive slot to `:rf/redacted`.
//
// The leak shipped GREEN because the cross-server mcp-conformance gate
// had NO scenario that put a sensitive value into a live app-db and
// asserted it was protected on the wire through these tools. The only
// `:rf/redacted` coverage in tools/mcp-conformance was source-text greps
// (wire_vocab_test.clj) and a leaf-counter indicator gate — neither
// asserts the sensitive leaf was actually protected on egress. This test
// is the missing end-to-end wire gate: it would have caught rf2-6wvh5 RED.
//
// ## rf2-5613h — the protection STRENGTHENED to a whole-epoch DROP
//
// rf2-5613h closed a second, related leak: pair-mcp's `sensitive-epoch?`
// (the whole-epoch DROP predicate that `strip-sensitive` feeds in the
// `:epoch-vector` wire-pipeline arm) was reading the UNqualified
// `:sensitive?` key, which the runtime never writes on an epoch record —
// the real record-level rollup is the qualified `:rf.epoch/sensitive?`
// (`re-frame.epoch.assembly/sensitive-rollup`). The assembler sets that
// rollup `true` whenever a schema-declared sensitive PATH holds a non-nil
// leaf in `:db-before`/`:db-after` — exactly the scenario this test sets
// up. Pre-rf2-5613h the mismatch meant the whole-drop never fired for a
// schema-derived sensitive epoch, so its metadata (`:event-id`, timing,
// `:redacted-modified-paths-count`, outcome) shipped across the wire
// alongside the value-redacted `:db-*` slots — a default-DROP violation.
//
// Post-rf2-5613h the default (gate-OFF) posture is fail-closed at the
// RECORD level: a sensitive epoch is WHOLE-DROPPED before egress — it
// never reaches the value-redaction stage at all. So the gate-OFF arm now
// asserts the sentinel is ABSENT *and* the tool reports `:dropped-sensitive`
// >= 1 with no record in `:epochs` (proving the strip-fn dropped it — not
// that the window happened to exclude it or the slot was empty). The
// previous "`:rf/redacted` PRESENT" assertion no longer applies on this
// path: with the whole-drop firing, there is no surviving record to carry
// a redacted slot. (`projected-record` value-redaction is still the
// belt-and-braces inner layer for any sensitive material the record-level
// rollup does NOT catch; this scenario's declared-sensitive non-nil leaf
// DOES flag the rollup, so the outer whole-drop governs.)
//
// ## What this test drives (across the MCP boundary, via the SDK Client)
//
//   1. Boot the pair-mcp server WITHOUT `--allow-sensitive-reads`
//      (the published default — the drop MUST hold).
//   2. Via `eval-cljs` against the live runtime:
//        a. declare `[:rf-conformance/secret]` sensitive through the
//           PUBLIC EP-0015 frame-owned route — `reg-frame` the operating
//           frame with `{:sensitive {:app-db [[:rf-conformance/secret]]}}`
//           (a SURGICAL re-registration that preserves the live app-db /
//           epoch ring; `reg-frame` installs the `:source :frame`
//           declaration the rollup + projected-record read);
//        b. dispatch an event that writes a recognisable SENTINEL string
//           into that slot, landing it in the next epoch's `:db-after`
//           (and flagging the epoch's `:rf.epoch/sensitive?` rollup true).
//   3. `tools/call trace-window` AND `tools/call watch-epochs` — the two
//      pull-mode epoch tools that leaked.
//   4. ASSERT the sentinel does NOT appear ANYWHERE in either egress
//      payload, AND that the tool reports `:dropped-sensitive` >= 1 (the
//      whole sensitive epoch was dropped — proving the strip-fn fired, not
//      that the slot was empty or the window excluded the record).
//   5. Boot a SECOND server WITH `--allow-sensitive-reads`, call the same
//      tools with `:include-sensitive true`, and assert the sentinel DOES
//      cross the wire — pinning BOTH directions of the gate so the gate
//      itself can't silently invert.
//
// ## Why this is a true regression net
//
// Revert the rf2-5613h key fix (point `sensitive-epoch?` back at the
// UNqualified `:sensitive?`) and step 4 goes RED two ways: the whole-drop
// stops firing (`:dropped-sensitive` falls to 0) AND — because the record
// now survives into the value-redaction stage — its raw metadata ships.
// Verified by reproduction during the rf2-5613h greening (the gate-OFF
// trace-window returned `:dropped-sensitive 1, :epochs []` post-fix).
//
// ## Gating
//
// **Skipped unless `$SHADOW_CLJS_NREPL_PORT` is set.** Same posture as
// the sibling `live-re-frame2-pair-overflow.cjs` / `-subscribe.cjs`:
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
//   1. declares `[SECRET_KEY]` sensitive through the PUBLIC EP-0015
//      frame-owned route — `re-frame.frame/reg-frame` re-registers the
//      operating frame with `{:sensitive {:app-db [[SECRET_KEY]]}}`. This
//      is the SAME route an app author uses (spec/015-Data-Classification.md
//      §Frame-owned durable classification); `reg-frame` validates the
//      classification and installs the `:source :frame` declaration into
//      the frame's durable elision registry that `sensitive-rollup` and
//      `projected-record` read. No hand-seeded `swap-elision-slot!`, no
//      schema artefact, no removed `populate-sensitive-from-schemas!`
//      importer;
//   2. registers + dispatch-syncs an event writing the SENTINEL into that
//      slot, landing it in the next epoch's `:db-after`.
// The secret is closed over in the handler so it never appears in the
// trigger-event vector — the frame-declared path is the only sensitive
// leaf, mirroring epoch_mcp_egress_conformance_test.clj's drive pattern.
//
// `re-frame.frame/reg-frame` is the fn-form of the `reg-frame` macro
// (re-frame.core aliases it for HoF / programmatic use). Re-registering an
// already-registered frame is a SURGICAL UPDATE per Spec 002 §reg-frame:
// existing runtime state (app-db, sub-cache, queue) is preserved, only the
// metadata/config is replaced — so the frame's live app-db and the epoch
// ring survive the re-registration. We declare BEFORE the dispatch so the
// epoch the dispatch records has the rollup stamped true at assembly.
//
// Registration uses the fn-form `re-frame.events/reg-event` rather than
// the `re-frame.core/reg-event` MACRO: a runtime cljs-eval form cannot
// expand a macro the analyzer hasn't seen, so the macro call would yield
// nil. The fn-form is a plain call against the events ns the fixture
// already loads (transitively via re-frame.core). `reg-event` is the one
// public event form after EP-0018 (semantically reg-event-fx): the handler
// takes the coeffects map and returns a closed effects map (`{:db ...}`).
// dispatch uses the fn-form `re-frame.core/dispatch-sync*` (the macro's
// runtime counterpart).
//
// Epoch recording must be ACTIVE for the pull-mode tools to have a record
// to egress. The tiny fixture (`counter.core`) neither requires
// `re-frame.epoch` nor configures a ring depth, so its dev build records
// nothing — `epoch-history` stays empty and trace-window / watch-epochs
// return `:epochs []` regardless of what we dispatch. We therefore
// (a) `(require 're-frame.epoch)` so the namespace loads and publishes the
// `:epoch/settle!` capture hook the router looks up at drain-settle, and
// (b) `(configure :epoch-history {:depth 50})` so the ring keeps records.
// This is runtime configuration, NOT a fixture-source change — it keeps
// the scenario self-contained under tools/mcp-conformance.
//
// CRITICAL: the `require`, the `configure`, and the dispatch MUST be
// THREE SEPARATE awaited eval-cljs calls. shadow's runtime `require`
// schedules an ASYNC module load; ANY form that references the
// `re-frame.epoch` ns (the `configure` knob's late-bound hook, or
// `current-config`) BEFORE that load settles fails to resolve. Within a
// single `(do (require ...) (configure ...) ...)` form the configure
// throws (`:repl/exception!`) and depth stays 0, so the dispatch records
// nothing. Splitting lets each prerequisite settle before the next call.
// Verified empirically (ai-local probe): single-form ⇒ :repl/exception! +
// :epoch-count 0; three separate calls ⇒ depth 50 + :epoch-count 1.
//
// Call 1: load the epoch namespace (publishes the `:epoch/settle!` capture
// hook the router looks up at drain-settle, and the `:epoch/configure!`
// knob the next call drives).
const ENABLE_REQUIRE_FORM = `(require 're-frame.epoch)`;

// Call 2: configure a ring depth so the buffer keeps records (default for
// this fixture is effectively disabled — no config ⇒ empty ring), then
// echo the live depth + hook state so seedRuntime can assert both landed.
const ENABLE_CONFIGURE_FORM = `
(do
  (re-frame.core/configure! {:epoch-history {:depth 50}})
  {:hook-installed? (some? (re-frame.late-bind/get-fn :epoch/settle!))
   :depth (:depth (re-frame.epoch/current-config))})`;

// Call 3: declare-sensitive (PUBLIC frame-owned route) + register +
// dispatch the sentinel write.
const SEED_FORM = `
(let [fid (re-frame2-pair.runtime/current-frame)]
  ;; Declare the sensitive slot through the PUBLIC EP-0015 frame-owned
  ;; route — re-register the SAME frame the epoch tools read (the runtime
  ;; operating frame the dispatch below targets) with a
  ;; \`:sensitive {:app-db …}\` classification. \`reg-frame\` installs the
  ;; \`:source :frame\` declaration that projected-record (which reads the
  ;; record's :frame elision registry) and sensitive-rollup both consult,
  ;; so the leaf matches at egress AND the dispatched epoch's rollup stamps
  ;; true. Surgical re-registration preserves the live app-db / epoch ring.
  (re-frame.frame/reg-frame fid {:sensitive {:app-db [[${SECRET_KEY}]]}})
  (re-frame.events/reg-event
    :rf-conformance/write-secret
    (fn [{:keys [db]} _] {:db (assoc db ${SECRET_KEY} "${SENTINEL}")}))
  (re-frame.core/dispatch-sync* [:rf-conformance/write-secret] {:frame fid})
  {:frame fid
   :declared (re-frame.elision/sensitive-declarations fid)
   :epoch-count (count (re-frame.core/epoch-history fid))
   :db-secret (get (re-frame2-pair.runtime/snapshot fid) ${SECRET_KEY})})`;

// A defensive sanity form: confirm the live app-db genuinely carries the
// sentinel at the secret slot (so a green no-leak assertion can't be a
// false-pass from the write silently failing). Reads through the runtime
// `snapshot` fn the pair tools already use — but unredacted, because this
// is an internal eval, not a wire egress.
const VERIFY_WRITE_FORM =
  `(get (re-frame2-pair.runtime/snapshot (re-frame2-pair.runtime/current-frame)) ${SECRET_KEY})`;

// ---- INNER projected-record value-redaction arm (rf2-ywn27.3) --------------
//
// Egress under gate-OFF has TWO independent redaction layers:
//
//   1. OUTER whole-epoch DROP — `strip-sensitive` -> `sensitive-epoch?`
//      reads the record's STAMPED `:rf.epoch/sensitive?` rollup, which
//      `sensitive-rollup` (assembly.cljc:240-260) computes ONCE at
//      record-assembly time from the THEN-current sensitive declarations.
//      Drops the WHOLE record (reports `:dropped-sensitive` N).
//   2. INNER value-redaction — `projected-record` (tool_pair.cljc:545-587)
//      runs SERVER-SIDE inside the eval form, reads the CURRENT registry
//      at EGRESS time, and routes the four payload slots through
//      `elide-wire-value` -> declared-sensitive paths become
//      `:rf/redacted`. The stamped rollup passes through UNCHANGED.
//
// The gate-OFF arm above declares the path BEFORE dispatch, so the
// rollup stamps true and the OUTER whole-drop governs — the INNER layer
// is BYPASSED (no record survives to carry a redacted slot; the test's
// own header, lines 42-53, confirms this).
//
// This arm drives the shape where the INNER layer is the SOLE
// protection: record a NON-sensitive epoch FIRST (no declaration ⇒
// `sensitive-rollup` computes the stamp FALSE ⇒ `sensitive-epoch?` later
// returns false ⇒ OUTER whole-drop does NOT fire), THEN declare the path
// sensitive, THEN egress. `projected-record` reads the now-declared path
// against the CURRENT registry and MUST redact `:db-after` to
// `:rf/redacted`. A regression to `projected-record` /
// `elide-payload-slot` (a missed `:db-after` in the cond-> at
// tool_pair.cljc:580-581, or `elide-wire-value` returning the raw value)
// leaks the secret with `:dropped-sensitive 0` — and NO existing gate
// goes RED, because the gate-OFF arm only drives the rollup-flagged
// (whole-drop) shape and the unit `...preserves-redacted-sentinel...`
// test pre-redacts its input.
//
// Distinguishable from the OUTER-drop scenarios by `:dropped-sensitive 0`
// + sentinel ABSENT + the `:rf/redacted` marker PRESENT in the surviving
// record's `:db-after`.
//
// rf2-2h7153 — this arm ALSO drives the PUBLIC frame-owned route:
// `reg-frame fid {}` (no `:sensitive` block) clears the prior frame-owned
// `:source :frame` declarations, and `reg-frame fid {:sensitive {:app-db
// …}}` installs the post-hoc declaration. So the inner-layer arm proves
// the frame-owned route reaches `projected-record` egress for the
// record-then-declare ordering too — not just the whole-drop arms.
// (`re-frame.epoch.state/reset-histories!` is internal projection /
// ring-state plumbing, used here only to isolate the ring; it is NOT a
// durable app-db classification surface and makes no policy claim.)

// A second, independent sensitive slot — distinct from SECRET_KEY so
// this arm's declaration cannot interact with any other epoch in a
// shared ring (this arm runs on its OWN server, so the ring holds only
// the one non-sensitive-at-record-time epoch we seed here).
const INNER_SECRET_KEY = ':rf-conformance/inner-secret';
const INNER_SENTINEL = 'rf2-ywn27-INNER-secret-do-not-leak-9b2e4d';

// Step A: enable epoch recording (require + configure — same two awaited
// calls as `seedRuntime`), then register + dispatch the sentinel write
// WITHOUT any sensitive declaration in scope. The epoch must be assembled
// with the rollup stamp FALSE so the OUTER whole-drop does NOT fire for it.
//
// CLEAN-SLATE precondition: this arm shares the live fixture runtime
// (same nREPL / `:rf/default` frame) with the gate-OFF arm that ran
// before it — which declared `:rf-conformance/secret` sensitive (via the
// frame-owned route) AND left its value in app-db. If that declaration is
// still live when our epoch assembles, `sensitive-rollup` (assembly.cljc:
// 240-260) sees a declared path holding a non-nil leaf and stamps the
// rollup TRUE — the OUTER whole-drop would then govern and this arm would
// NOT isolate the inner layer. So the form FIRST clears the frame-owned
// sensitive declaration (re-register the frame with NO `:sensitive`
// block — `reg-frame`'s `install!` drops the prior `:source :frame`
// entries) AND dispatches the secret-write event that also dissocs any
// prior declared key, guaranteeing no declared-sensitive leaf exists at
// assembly time. The clear-declarations re-registration records nothing;
// we read the rollup off the HEAD (last) record — the inner-secret write
// — which is the one the egress tools surface.
const INNER_WRITE_FORM = `
(let [fid (re-frame2-pair.runtime/current-frame)]
  ;; This arm shares the live fixture runtime with the gate-OFF arm that
  ;; ran before it — whose SENSITIVE epoch is still in the ring. Wipe the
  ;; ring so it holds ONLY our one inner epoch: otherwise the old
  ;; sensitive epoch's whole-drop would push :dropped-sensitive above 0
  ;; and break this arm's inner-layer isolation (it pins == 0).
  ;; (reset-histories! is internal ring-state plumbing, not a policy
  ;; surface — see the arm header.)
  (re-frame.epoch.state/reset-histories!)
  ;; Clear the frame-owned sensitive declaration through the PUBLIC route:
  ;; re-register the frame with no \`:sensitive\` block so \`install!\`
  ;; drops the prior \`:source :frame\` entries. The assembler then sees an
  ;; EMPTY sensitive-paths set when our epoch is built (rollup ⇒ false).
  (re-frame.frame/reg-frame fid {})
  (re-frame.events/reg-event
    :rf-conformance/write-inner-secret
    (fn [{:keys [db]} _]
      ;; Drop any prior declared-sensitive slot's value too, so even a
      ;; stray lingering declaration can't find a non-nil leaf.
      {:db (-> db
               (dissoc ${SECRET_KEY})
               (assoc ${INNER_SECRET_KEY} "${INNER_SENTINEL}"))}))
  (re-frame.core/dispatch-sync* [:rf-conformance/write-inner-secret] {:frame fid})
  {:frame fid
   :rollup-at-assembly
   ;; The STAMPED rollup on the just-recorded (HEAD) epoch — MUST be
   ;; false/absent (no declaration existed when the assembler ran),
   ;; proving the OUTER whole-drop will not govern.
   (:rf.epoch/sensitive? (last (re-frame.core/epoch-history fid)))
   :epoch-count (count (re-frame.core/epoch-history fid))
   :db-secret (get (re-frame2-pair.runtime/snapshot fid) ${INNER_SECRET_KEY})})`;

// Step B: NOW declare the path sensitive — AFTER the epoch was recorded —
// through the PUBLIC frame-owned route. `projected-record` reads this
// CURRENT registry at egress, so the already-recorded epoch's `:db-after`
// must be value-redacted even though its stamped rollup is false.
const INNER_DECLARE_FORM = `
(let [fid (re-frame2-pair.runtime/current-frame)]
  (re-frame.frame/reg-frame fid {:sensitive {:app-db [[${INNER_SECRET_KEY}]]}})
  {:frame fid
   :declared (re-frame.elision/sensitive-declarations fid)
   ;; Re-read the STAMPED rollup on the SAME recorded epoch — it MUST
   ;; still be false/absent: sensitive-rollup ran once at assembly and
   ;; is NOT recomputed on a later declaration. This pins the OUTER drop
   ;; will not fire (so the INNER layer is the sole protection).
   :rollup-after-declare
   (:rf.epoch/sensitive? (last (re-frame.core/epoch-history fid)))})`;

// ---- helpers ---------------------------------------------------------------

function assertOk(resp, name) {
  if (resp.isError) {
    throw new Error(
      name + ' returned isError; the redaction gate cannot assert on an ' +
        'error envelope. Got: ' + responseText(resp).slice(0, 400),
    );
  }
}

// Read the `:dropped-sensitive` count off the envelope text. The pull-mode
// epoch tools surface a `:dropped-sensitive N` indicator (the count of
// whole epoch records `strip-sensitive` dropped on egress). Returns the
// integer, or null when the slot is absent. A regex read keeps this in
// step with the substring-search posture used for the sentinel itself —
// we don't parse the full EDN, we read the one load-bearing slot.
function droppedSensitiveCount(text) {
  const m = /:dropped-sensitive\s+(\d+)\b/.exec(text);
  return m ? Number(m[1]) : null;
}

// The load-bearing assertion (rf2-5613h): the SENTINEL must be ABSENT AND
// the tool must report `:dropped-sensitive` >= 1 — proving the whole
// sensitive epoch was DROPPED by the strip-fn, not merely that the slot
// was empty or the window excluded the record (a window-excludes-everything
// regression reports `:dropped-sensitive 0` and would pass a bare absence
// check while the drop never fired). Pre-rf2-5613h this asserted a
// surviving record carried `:rf/redacted`; with the whole-drop now firing
// for a schema-derived sensitive epoch, no record survives to carry it.
function assertDropped(resp, name) {
  assertOk(resp, name);
  const text = responseText(resp);
  if (text.includes(SENTINEL)) {
    throw new Error(
      name + ' LEAKED the sensitive sentinel over the MCP wire with the ' +
        '--allow-sensitive-reads gate OFF (default). The declared-sensitive ' +
        'slot ' + SECRET_KEY + ' flags the epoch `:rf.epoch/sensitive?`, so ' +
        'the whole record MUST be dropped by `sensitive-epoch?` before egress ' +
        '(rf2-5613h / Spec Security.md §Epoch privacy posture).\nPayload ' +
        '(first 600 chars): ' + text.slice(0, 600),
    );
  }
  const dropped = droppedSensitiveCount(text);
  if (dropped === null || dropped < 1) {
    throw new Error(
      name + ' egress payload does NOT report a sensitive-epoch drop ' +
        '(`:dropped-sensitive` is ' + (dropped === null ? 'absent' : dropped) +
        '; expected >= 1). A bare sentinel-absence check would false-pass if ' +
        'the epoch fell outside the window or the slot were empty; this ' +
        'assertion proves the strip-fn DROPPED the sensitive record ' +
        '(rf2-5613h). Payload (first 600 chars): ' + text.slice(0, 600),
    );
  }
}

// The INNER-layer assertion (rf2-ywn27.3): the INNER_SENTINEL must be
// ABSENT, the surviving record must carry the `:rf/redacted` marker, AND
// `:dropped-sensitive` MUST be 0 — proving the protection was the
// SERVER-SIDE `projected-record` value-redaction (the inner layer), NOT
// the OUTER whole-epoch drop. Three conditions, each load-bearing:
//
//   - sentinel ABSENT: the raw secret did not cross the wire.
//   - `:rf/redacted` PRESENT: a record SURVIVED to egress and its
//     `:db-after` slot was value-redacted — distinguishes a genuine
//     inner-redaction from a vacuous empty-ring / window-excludes pass
//     (which would also show no sentinel but carry NO `:rf/redacted`).
//   - `:dropped-sensitive` is 0 (ABSENT or literal 0): the OUTER
//     whole-drop did NOT fire (the record's stamped rollup is false), so
//     the inner layer is provably the SOLE protection. The base envelope
//     splices `:dropped-sensitive` onto the payload ONLY when the count
//     is positive (mcp_base/envelope.cljc:65 `(pos? dropped)`), so a
//     zero-drop egress carries NO `:dropped-sensitive` slot at all —
//     absence here IS the zero-drop signal. If a future regression
//     flipped the rollup to fire the whole-drop, `:dropped-sensitive`
//     would appear with a value >= 1 and this would (correctly) go RED —
//     the two layers must stay distinct so each is independently proven.
function assertInnerRedacted(resp, name) {
  assertOk(resp, name);
  const text = responseText(resp);
  if (text.includes(INNER_SENTINEL)) {
    throw new Error(
      name + ' LEAKED the inner sentinel over the MCP wire (gate OFF). The ' +
        'epoch was recorded BEFORE ' + INNER_SECRET_KEY + ' was declared ' +
        'sensitive, so its stamped `:rf.epoch/sensitive?` rollup is false and ' +
        'the OUTER whole-drop does NOT fire — the INNER `projected-record` ' +
        'value-redaction (tool_pair.cljc:545-587) is the SOLE protection and ' +
        'MUST turn `:db-after` into `:rf/redacted` (rf2-ywn27.3). A leak here ' +
        'means a regression to projected-record / elide-payload-slot.\nPayload ' +
        '(first 600 chars): ' + text.slice(0, 600),
    );
  }
  if (!text.includes(':rf/redacted')) {
    throw new Error(
      name + ' egress payload carries NO `:rf/redacted` marker. The inner ' +
        'projection MUST have value-redacted the surviving record\'s ' +
        '`:db-after` slot to `:rf/redacted`. Its absence means either the ' +
        'record did not survive to egress (a vacuous empty-ring / ' +
        'window-excludes pass — the no-leak check would false-pass) or the ' +
        'inner projection did not fire (the regression rf2-ywn27.3 pins). ' +
        'Payload (first 600 chars): ' + text.slice(0, 600),
    );
  }
  // `:dropped-sensitive` is spliced ONLY when positive — absence (null)
  // means zero whole-drops, which is exactly the inner-layer-SOLE posture.
  // Reject any positive count: that would mean the OUTER whole-drop fired
  // and the arm no longer isolates the inner projection.
  const dropped = droppedSensitiveCount(text);
  if (dropped !== null && dropped > 0) {
    throw new Error(
      name + ' reports `:dropped-sensitive` ' + dropped + ' — expected 0 ' +
        '(absent). This arm pins the INNER value-redaction as the SOLE ' +
        'protection: the OUTER whole-drop must NOT fire (stamped rollup ' +
        'false). A positive count means the whole-drop governed instead, so ' +
        'this arm would no longer isolate the inner layer (rf2-ywn27.3). ' +
        'Payload (first 600 chars): ' + text.slice(0, 600),
    );
  }
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
  if (!seedText.includes('sensitive-declarations') && !seedText.includes(':declared')) {
    throw new Error(
      label + ' eval-cljs seed did not confirm the sensitive declaration ' +
        'landed; got: ' + seedText.slice(0, 300),
    );
  }
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
  // — same rationale as the seedRuntime check above (rf2-6r5qe.2).
  if (!/:hook-installed\?\s+true\b/.test(responseText(enable))) {
    throw new Error(
      label + ' eval-cljs configure-epoch did not install the :epoch/settle! hook ' +
        '(:hook-installed? not true — epoch recording would stay disabled and no ' +
        'epoch would be recorded). Got: ' + responseText(enable).slice(0, 300),
    );
  }
}

// ---- INNER projected-record value-redaction arm (rf2-ywn27.3) --------------
//
// A SECOND gate-OFF server (no `--allow-sensitive-reads`) so its ring
// holds ONLY the one epoch we seed here — no other recorded epoch can
// contaminate the `:dropped-sensitive 0` assertion. Drives the
// record-THEN-declare ordering so the OUTER whole-drop does NOT fire and
// the INNER `projected-record` value-redaction is the SOLE protection.
// See the form-block + `assertInnerRedacted` comments above for the
// two-layer model and why this is distinguishable from the whole-drop
// scenarios.
async function runInnerProjectionArm() {
  const { client } = await connectServer({
    clientName: 'mcp-conformance-re-frame2-pair-redaction-inner',
    stderrPrefix: '[server:inner]',
    transportSpec: {
      command: process.execPath,
      // No `--allow-sensitive-reads` — gate OFF, the published default.
      // The inner projection runs because `project?` is true (incl? is
      // forced false), independent of the rollup / whole-drop.
      args: [SERVER],
      cwd: os.tmpdir(),
      env: { ...process.env },
    },
    // Register with the outer watchdog the INSTANT this secondary child is
    // constructed — BEFORE connect awaits — so a hang in THIS arm's boot or
    // a later tool call is reaped by the outer timeout instead of orphaning
    // the secondary Node child (rf2-wqi4n4 finding 1). The bare-caller
    // analogue of the rf2-2js41 finding-2 fix.
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
    // declaration existed) — pins that the OUTER whole-drop cannot govern.
    if (/:rollup-at-assembly\s+true\b/.test(writeText)) {
      throw new Error(
        'inner eval-cljs write: the epoch\'s stamped `:rf.epoch/sensitive?` ' +
          'rollup is already TRUE at assembly time — the OUTER whole-drop ' +
          'would govern and this arm would NOT isolate the inner layer. The ' +
          'fixture must record the epoch BEFORE declaring the path (rf2-ywn27.3). ' +
          'Got: ' + writeText.slice(0, 300),
      );
    }
    console.log('OK   inner write -> non-sensitive epoch recorded (rollup false); sentinel live in app-db');

    // Step B: NOW declare the path sensitive — AFTER the epoch was
    // recorded. `projected-record` reads this CURRENT registry at egress.
    const declare = await client.callTool({ name: 'eval-cljs', arguments: { form: INNER_DECLARE_FORM } });
    assertOk(declare, 'inner eval-cljs declare-sensitive');
    const declareText = responseText(declare);
    if (!declareText.includes('sensitive-declarations') && !declareText.includes(':declared')) {
      throw new Error(
        'inner eval-cljs declare did not confirm the sensitive declaration ' +
          'landed; got: ' + declareText.slice(0, 300),
      );
    }
    // The stamped rollup on the recorded epoch MUST STILL be false after
    // the declaration — `sensitive-rollup` runs once at assembly and is
    // not recomputed. This is the load-bearing precondition: the OUTER
    // drop stays inert, so the INNER layer is the only thing standing
    // between the secret and the wire.
    if (/:rollup-after-declare\s+true\b/.test(declareText)) {
      throw new Error(
        'inner eval-cljs declare: the recorded epoch\'s stamped rollup became ' +
          'TRUE after the post-hoc declaration — the OUTER whole-drop would now ' +
          'fire and this arm would no longer isolate the INNER projection ' +
          '(rf2-ywn27.3). Got: ' + declareText.slice(0, 300),
      );
    }
    console.log('OK   inner declare -> path now sensitive; recorded epoch rollup STILL false (outer drop inert)');

    // trace-window: the INNER projection must redact `:db-after` to
    // `:rf/redacted` with `:dropped-sensitive 0`. `:epochs-mode full` +
    // `:dedup false` so the redacted leaf rides the wire verbatim (not
    // diff-collapsed away), matching the unit pipeline-preservation test.
    const tw = await client.callTool({
      name: 'trace-window',
      arguments: { ms: TRACE_WINDOW_MS, 'epochs-mode': 'full', dedup: false },
    });
    assertInnerRedacted(tw, 'trace-window (inner projected-record value-redaction)');
    console.log('OK   trace-window (gate OFF, inner layer SOLE) -> :db-after :rf/redacted + :dropped-sensitive 0');

    // watch-epochs: the named focus of rf2-ywn27 — same inner-layer SOLE
    // protection at its INDEPENDENT call site.
    const we = await client.callTool({
      name: 'watch-epochs',
      arguments: { 'epochs-mode': 'full', dedup: false },
    });
    assertInnerRedacted(we, 'watch-epochs (inner projected-record value-redaction)');
    console.log('OK   watch-epochs (gate OFF, inner layer SOLE) -> :db-after :rf/redacted + :dropped-sensitive 0');
  } finally {
    // Deregister before the explicit close so the watchdog set doesn't hold
    // a stale handle past teardown (rf2-wqi4n4 finding 1).
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
  // Stand up the second server via the shared spawn+connect primitive
  // (rf2-0ogn7). The `[server:gate-on]` stderr prefix keeps this
  // concurrent boot's logs distinguishable from the runner-managed
  // gate-OFF server's `[server]` lines.
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
    // orphaning the secondary Node child (rf2-wqi4n4 finding 1).
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
          'explicit raw-state opt-in MUST be honoured (rf2-c2dtu gate parity); ' +
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
  } finally {
    // Deregister before the explicit close so the watchdog set doesn't hold
    // a stale handle past teardown (rf2-wqi4n4 finding 1).
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
      // is the configuration the leak shipped under and the only one
      // where the redaction MUST hold regardless of per-call args.
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

    // 3a. trace-window — the first pull-mode tool that leaked. Wide window
    // so the just-dispatched epoch is comfortably inside it. The sensitive
    // epoch is WHOLE-DROPPED (rf2-5613h): sentinel ABSENT + :dropped-sensitive >= 1.
    const tw = await client.callTool({ name: 'trace-window', arguments: { ms: TRACE_WINDOW_MS } });
    assertDropped(tw, 'trace-window');
    console.log('OK   trace-window (gate OFF) -> sentinel ABSENT + sensitive epoch DROPPED');

    // 3b. watch-epochs — the second pull-mode tool that leaked. No
    // :since-id → the full ring (which includes the sentinel epoch).
    const we = await client.callTool({ name: 'watch-epochs', arguments: {} });
    assertDropped(we, 'watch-epochs');
    console.log('OK   watch-epochs (gate OFF) -> sentinel ABSENT + sensitive epoch DROPPED');

    // 3c. Hostile per-call opt-in MUST NOT defeat the gate. A caller
    // passing `:include-sensitive true` to a server booted WITHOUT
    // `--allow-sensitive-reads` cannot talk it into shipping raw state —
    // the boot gate forces `incl? false` regardless (rf2-c2dtu). Pin that
    // the per-call arg is powerless when the operator didn't opt in.
    const twHostile = await client.callTool({
      name: 'trace-window',
      arguments: { ms: TRACE_WINDOW_MS, 'include-sensitive': true },
    });
    assertDropped(twHostile, 'trace-window (hostile :include-sensitive)');
    console.log(
      'OK   trace-window (gate OFF + hostile :include-sensitive true) -> still DROPPED',
    );

    // 3d. watch-epochs hostile per-call opt-in — the SIBLING of 3c
    // (rf2-ywn27.1). trace-window and watch-epochs compute `incl?` at
    // SEPARATE call sites (trace_window.cljs:61-63 vs
    // watch_epochs.cljs:61-63) — each is an independent
    // `(if (raw-state-allowed?) (parse-bool-arg ... :include-sensitive)
    // false)`, NOT shared code. So a regression that drops the boot-gate
    // guard on watch-epochs ALONE (e.g. watch_epochs.cljs:61 simplified
    // to `incl? (parse-bool-arg raw-args :include-sensitive)`) would let
    // a caller's hostile `:include-sensitive true` talk a server booted
    // WITHOUT --allow-sensitive-reads into shipping RAW epoch `:db-after`
    // — `incl? true` ⇒ `project? false` ⇒ no projected-record wrap AND
    // `strip-sensitive [items 0]` ⇒ the whole-drop is bypassed too. The
    // pre-rf2-ywn27.1 suite stayed GREEN because it only ever sent
    // watch-epochs the DEFAULT-args call gate-OFF (3b), which forces
    // `incl? false` regardless. Pin the hostile arg is powerless here.
    const weHostile = await client.callTool({
      name: 'watch-epochs',
      arguments: { 'include-sensitive': true },
    });
    assertDropped(weHostile, 'watch-epochs (hostile :include-sensitive)');
    console.log(
      'OK   watch-epochs (gate OFF + hostile :include-sensitive true) -> still DROPPED',
    );

    // 3e. INNER projected-record value-redaction layer — the sole
    // protection when the OUTER whole-drop does NOT fire (rf2-ywn27.3).
    // See `runInnerProjectionArm` for the two-layer model and why this
    // is distinguishable from the whole-drop scenarios above.
    await runInnerProjectionArm();

    // 4. Gate-ON arm: pin the other direction so the gate can't invert.
    await runGateOnArm();

    console.log('\nRE-FRAME2-PAIR-MCP LIVE EGRESS-PROTECTION CONFORMANCE GREEN');
  },
);
