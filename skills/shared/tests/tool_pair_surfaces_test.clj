;;;; tests/tool_pair_surfaces_test.clj — structural regression for the
;;;; shared Tool-Pair surface enumeration.
;;;;
;;;; `tool-pair-surfaces.md` is the single shared-corpus pointer at the
;;;; consumer-facing Tool-Pair surfaces. The skills/shared correctness
;;;; review (rf2-eca6x.1) found its direct-read entries named raw runtime
;;;; reads (`app-db-value`, `compute-sub`, `sub-cache`) WITHOUT carrying
;;;; the MUST-level off-box wire-egress contract from spec/Tool-Pair.md —
;;;; so a consuming skill routing/drafting upstream Tool-Pair work from
;;;; this leaf could describe direct reads as raw values rather than
;;;; `rf/elide-wire-value`-scrubbed egress with sensitive/large defaults
;;;; suppressed. The existing `retro_protocol_test.clj` suite does not
;;;; cover this leaf, so the drift was unguarded.
;;;;
;;;; rf2-c9xgp follow-up: the wire-surface list was ALSO stale against the
;;;; current re-frame2-pair-mcp catalogue — it named a standalone
;;;; `get-app-db` / `sub-cache` tool that the catalogue does not ship, and
;;;; omitted the real subscription-read egress (`read-sub`,
;;;; `list-subscriptions` with `:include-values`) and `dispatch-dry-run`,
;;;; all under the same `--allow-sensitive-reads` fail-closed posture.
;;;; This suite now pins the CURRENT privacy-relevant tool names so a
;;;; future catalogue drift (a renamed/dropped read tool, a new app-db
;;;; egress tool that skips the elision posture) fails loudly here.
;;;;
;;;; This file pins the load-bearing direct-read privacy tokens and the
;;;; link to the Tool-Pair direct-read section, so a future edit that
;;;; strips the elision invariant from the shared surface guidance fails
;;;; loudly. Mirrors `retro_protocol_test.clj`'s structural-drift shape.
;;;;
;;;; Run:    bb skills/shared/tests/tool_pair_surfaces_test.clj   (from repo root)
;;;;         bb tests/tool_pair_surfaces_test.clj                 (from skills/shared/)
;;;; Exit:   0 = pass, non-zero = fail.

(ns tool-pair-surfaces-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

;; ---------------------------------------------------------------------------
;; Filesystem helpers
;; ---------------------------------------------------------------------------

(def ^:private shared-root
  (-> *file*
      (io/file)
      (.getAbsoluteFile)
      (.getParentFile)    ;; tests/
      (.getParentFile)))  ;; skills/shared/

(def ^:private repo-root
  (-> shared-root
      (.getParentFile)    ;; skills/
      (.getParentFile)))  ;; repo root

(def ^:private surfaces-md
  (delay (slurp (io/file shared-root "tool-pair-surfaces.md"))))

(def ^:private tool-pair-spec-md
  (delay (slurp (io/file repo-root "spec/Tool-Pair.md"))))

(def ^:private security-spec-md
  (delay (slurp (io/file repo-root "spec/Security.md"))))

(defn- contains-any? [text alts]
  (some #(str/includes? text %) alts))

;; ---------------------------------------------------------------------------
;; Lock — the direct-read wire-egress contract is pinned on the shared leaf
;; ---------------------------------------------------------------------------

(deftest direct-reads-named-in-current-mcp-tool-terms
  (testing "the direct-read surfaces are named in CURRENT re-frame2-pair-mcp wire terms"
    (let [body @surfaces-md]
      ;; The current re-frame2-pair-mcp catalogue exposes these direct-read
      ;; egress surfaces — NOT a standalone `get-app-db` / `sub-cache` tool.
      ;; A consuming skill drafting upstream work must see the shipped wire
      ;; vocabulary, not only the raw `app-db-value` / `compute-sub` runtime
      ;; API (rf2-eca6x.1) and not a stale tool name (rf2-c9xgp).
      (is (and (str/includes? body "snapshot")
               (str/includes? body "get-path")
               (str/includes? body "read-sub")
               (str/includes? body "list-subscriptions"))
          (str "tool-pair-surfaces.md no longer names the current "
               "re-frame2-pair-mcp direct-read wire surfaces (`snapshot` / "
               "`get-path` / `read-sub` / `list-subscriptions`). Verify "
               "against tools/re-frame2-pair-mcp/README.md §Tool surface "
               "(rf2-c9xgp).")))))

(deftest subscription-reads-and-dry-run-named-as-gated-egress
  (testing "subscription-value reads + dispatch-dry-run are pinned under the same fail-closed posture"
    (let [body @surfaces-md]
      ;; rf2-c9xgp: the original four-name list omitted the real
      ;; subscription-read egress and the dry-run app-db/fx egress. Both
      ;; ride the SAME `--allow-sensitive-reads` posture (read-sub.cljs /
      ;; list_subscriptions.cljs route :value through elide-wire-value;
      ;; dispatch-dry-run is gated per re-frame2-pair-mcp/README.md:200).
      (is (str/includes? body "include-values")
          (str "tool-pair-surfaces.md no longer names `:include-values` — "
               "the `list-subscriptions` arg that ships each subscription's "
               "current `:value` off-box. That value read rides the "
               "fail-closed elision posture and must be visible at this "
               "leaf (rf2-c9xgp)."))
      (is (str/includes? body "dispatch-dry-run")
          (str "tool-pair-surfaces.md no longer names `dispatch-dry-run`. "
               "It mutates nothing but egresses app-db-/fx-derived data "
               "(`:db-state-after-simulation` / `:would-fire-effects`) "
               "under the SAME `--allow-sensitive-reads` posture "
               "(re-frame2-pair-mcp/README.md:200, rf2-c9xgp).")))))

(deftest get-app-db-only-as-generic-not-current-tool
  (testing "get-app-db is framed as a generic/third-party class, never a current pair-mcp tool"
    (let [body @surfaces-md]
      ;; rf2-c9xgp: `get-app-db` is NOT a current re-frame2-pair-mcp tool —
      ;; the catalogue reaches app-db via `snapshot`/`get-path`. The leaf
      ;; may keep `get-app-db` only as a generic / third-party direct-read
      ;; class name. If it appears at all, it must be near a generic/third-
      ;; party qualifier, never presented as a shipped pair-mcp tool.
      (when (str/includes? body "get-app-db")
        (is (or (str/includes? body "generic / third-party")
                (str/includes? body "generic/third-party")
                (str/includes? body "third-party")
                (str/includes? body "third party"))
            (str "tool-pair-surfaces.md mentions `get-app-db` but no longer "
                 "frames it as a generic / third-party direct-read class. "
                 "There is no standalone `get-app-db` tool in the current "
                 "re-frame2-pair-mcp catalogue — naming it as a current "
                 "wire surface is stale (rf2-c9xgp)."))))))

(deftest direct-reads-route-through-elide-wire-value
  (testing "the leaf still names rf/elide-wire-value as the low-level walker beneath the egress boundary"
    (let [body @surfaces-md]
      (is (str/includes? body "rf/elide-wire-value")
          (str "tool-pair-surfaces.md no longer names `rf/elide-wire-value` "
               "as the low-level tree walker for direct-read egress. It is "
               "the leaf-level value primitive the `project-egress` boundary "
               "delegates tree slots to (spec/015 §elide-wire-value, "
               "spec/Tool-Pair.md §Direct-read privacy)."))
      (is (contains-any? body ["MUST"])
          (str "The MUST-level imperative was downgraded. The direct-read "
               "egress contract is normative, not advisory.")))))

;; ---------------------------------------------------------------------------
;; Lock — the public egress boundary is `project-egress`, NOT `elide-wire-value`
;; (rf2-2s4jre)
;;
;; EP-0015 moved the public egress boundary up a layer: `rf/project-egress`
;; is the record-level boundary primitive (resolves a `:rf.egress/*` profile +
;; known `:frame`, applies frame-owned classification, fails closed with no
;; frame), and `rf/elide-wire-value` is the low-level tree walker it delegates
;; to. spec/015 §project-egress + implementation/SECURITY.md pin this split;
;; spec/015:264 says tools/sinks should rarely call `elide-wire-value`
;; directly. The earlier leaf taught `elide-wire-value` as "the single
;; normative direct-value emission site" — that wording teaches new direct-
;; read surfaces to hand-call the low-level walker (hand-assembling
;; `:rf.size/*` opts) and bypass closed-profile validation + fail-closed
;; frame seeding. These pins keep the boundary primitive named and forbid the
;; stale boundary-overload wording from creeping back.
;; ---------------------------------------------------------------------------

(deftest project-egress-named-as-the-public-boundary
  (testing "the leaf names rf/project-egress as the public off-box egress boundary primitive"
    (let [body @surfaces-md]
      (is (str/includes? body "project-egress")
          (str "tool-pair-surfaces.md no longer names `project-egress` — the "
               "EP-0015 public, record-level off-box egress boundary "
               "primitive. Direct reads / sinks must project through it "
               "(known `:frame` + `:rf.egress/*` profile, fail-closed) before "
               "off-box egress; `elide-wire-value` is the low-level walker it "
               "delegates to, not the boundary (spec/015 §project-egress, "
               "rf2-2s4jre)."))
      (is (contains-any? body [":rf.egress/off-box-tool" ":rf.egress/"])
          (str "tool-pair-surfaces.md no longer names a closed `:rf.egress/*` "
               "egress profile (off-box reads use `:rf.egress/off-box-tool`). "
               "The profile is what `project-egress` resolves and validates "
               "fail-closed (rf2-2s4jre).")))))

(deftest elide-wire-value-not-overloaded-as-the-boundary
  (testing "the leaf does NOT teach elide-wire-value as the single normative direct-value emission site"
    (let [body @surfaces-md]
      ;; rf2-2s4jre: the stale framing called the low-level walker "the single
      ;; normative direct-value emission site". Post-EP-0015 the boundary is
      ;; `project-egress`; the walker is delegated to. Guard the exact stale
      ;; phrasings so a regression to walker-as-boundary fails loudly.
      (is (not (str/includes? body "single normative direct-value emission site"))
          (str "tool-pair-surfaces.md again calls `elide-wire-value` \"the "
               "single normative direct-value emission site\". Post-EP-0015 "
               "the public boundary is `project-egress`; the walker is "
               "delegated to, not the emission site (spec/015:264 — tools "
               "should rarely call `elide-wire-value` directly, rf2-2s4jre)."))
      (is (not (str/includes? body "elide-wire-value egress MUST"))
          (str "tool-pair-surfaces.md again labels the linked contract the "
               "\"elide-wire-value egress MUST\". The MUST is the off-box "
               "egress / `project-egress` boundary; `elide-wire-value` is the "
               "walker beneath it (rf2-2s4jre).")))))

(deftest direct-reads-default-suppress-sensitive-and-large
  (testing "the leaf pins the off-box default-suppress posture + indicators"
    (let [body @surfaces-md]
      (is (and (str/includes? body ":rf.size/include-sensitive?")
               (str/includes? body ":rf.size/include-large?"))
          (str "The off-box default-suppress opts "
               "(`:rf.size/include-sensitive?` / `:rf.size/include-large?`, "
               "both default false) are missing. They are what make the "
               "egress fail-closed."))
      (is (and (str/includes? body ":rf/redacted")
               (str/includes? body ":rf.size/large-elided"))
          (str "The elision sentinels (`:rf/redacted` for sensitive, "
               "`:rf.size/large-elided` for oversize) are missing. They "
               "are the observable result of the suppression."))
      (is (contains-any? body ["sensitive drop wins" "sensitive-wins"
                               "sensitive drop"])
          (str "The composition rule — sensitive drop wins over the size "
               "marker — is missing. Without it the leaf doesn't pin how "
               "the two predicates compose (the size marker would leak "
               ":path / :bytes / :digest)."))
      (is (and (str/includes? body ":dropped-sensitive")
               (str/includes? body ":elided-large"))
          (str "The required elision indicators (`:dropped-sensitive` / "
               "`:elided-large`) are missing. spec/009 requires structured "
               "tool responses report elision counts.")))))

(deftest direct-reads-name-the-allow-sensitive-gate-default-off
  (testing "the leaf pins the --allow-sensitive-reads gate, default OFF"
    (let [body @surfaces-md]
      (is (str/includes? body "--allow-sensitive-reads")
          (str "The cross-MCP `--allow-sensitive-reads` boot gate is no "
               "longer named. It is the canonical opt-in that re-opens "
               "sensitive egress (rf2-eca6x.1: gate default OFF)."))
      (is (contains-any? body ["default **OFF**" "default OFF" "OFF**"
                               "default `OFF`"])
          (str "The 'default OFF' qualifier on `--allow-sensitive-reads` "
               "is missing. The gate being off-by-default is the "
               "load-bearing fail-closed posture — a default-ON reading "
               "inverts the contract.")))))

(deftest direct-reads-link-the-tool-pair-section
  (testing "the leaf links the authoritative Tool-Pair direct-read section"
    (let [body @surfaces-md]
      (is (str/includes? body "Tool-Pair.md#direct-read-privacy-posture-for-sub-cache-and-get-path")
          (str "tool-pair-surfaces.md no longer links the authoritative "
               "spec/Tool-Pair.md §Direct-read privacy section. The shared "
               "leaf is a pointer at the contract; the anchored link is "
               "what keeps it from being a second (driftable) source of "
               "truth (rf2-eca6x.1).")))))

;; ---------------------------------------------------------------------------
;; Lock — the leaf enumerates the FULL upstream-routing surface catalogue
;; (rf2-985x1t)
;; ---------------------------------------------------------------------------
;;
;; The independent correctness review (rf2-985x1t) found the leaf advertised
;; only an abbreviated subset of the Tool-Pair surface families: it stopped
;; at trace / registrar / epoch-restore / schema / source-coord / direct
;; reads and OMITTED render-driving + dispatch-settle, view-plane reads /
;; view attribution, the signal recorder, and the operating-frame trio —
;; all of which the authoritative contract (spec/Tool-Pair.md) and the
;; current re-frame2-pair-mcp catalogue ship. A retro finding about
;; deterministic dispatch->settle->DOM, read-ui/read-dom provenance,
;; human-interaction recording, or multi-frame operating-frame ambiguity
;; could therefore be mislabeled as pair-tool friction and filed against
;; the wrong layer. These tests pin the current catalogue tokens so a
;; future re-narrowing of the leaf fails loudly here.

(deftest render-driving-and-settle-enumerated
  (testing "the leaf names render-driving via flush-render! and the dispatch :settle mode"
    (let [body @surfaces-md]
      (is (str/includes? body "flush-render!")
          (str "tool-pair-surfaces.md no longer names `flush-render!` — the "
               "render-driving framework primitive a headless "
               "dispatch->observe-DOM loop needs to be deterministic "
               "(spec/Tool-Pair.md §Driving the render, rf2-985x1t)."))
      (is (contains-any? body [":settle" "dispatch settle" "dispatch-settle"
                               "settle mode"])
          (str "tool-pair-surfaces.md no longer names the dispatch `:settle` "
               "surface that builds on `flush-render!` to return the settled "
               "epoch in one call (rf2-985x1t).")))))

(deftest view-plane-reads-enumerated
  (testing "the leaf names the view-plane reads read-dom / read-ui and the data-rf-view attribution"
    (let [body @surfaces-md]
      (is (and (str/includes? body "read-dom")
               (str/includes? body "read-ui"))
          (str "tool-pair-surfaces.md no longer names the view-plane read "
               "surfaces `read-dom` / `read-ui`. Without them a retro "
               "finding about rendered-content provenance routes to the "
               "wrong layer (spec/Tool-Pair.md §The view→content read, "
               "re-frame2-pair-mcp/README.md, rf2-985x1t)."))
      (is (str/includes? body "data-rf-view")
          (str "tool-pair-surfaces.md no longer names the `data-rf-view` "
               "view-id attribution attribute — the view↔DOM map the "
               "view-plane reads ride (rf2-985x1t).")))))

(deftest signal-recorder-triplet-enumerated
  (testing "the leaf names the signal-recorder triplet record / read-recording / watch-until"
    (let [body @surfaces-md]
      (is (and (str/includes? body "record")
               (str/includes? body "read-recording")
               (str/includes? body "watch-until"))
          (str "tool-pair-surfaces.md no longer names the signal-recorder "
               "triplet (`record` / `read-recording` / `watch-until`) — the "
               "canonical surface for intermittent / human-in-the-loop bugs "
               "(re-frame2-pair-mcp/README.md, rf2-985x1t).")))))

(deftest watch-until-differentiated-from-raf-recorder
  (testing "the leaf differentiates watch-until (server polling) from the rAF record/read-recording recorder (rf2-3bu4ik)"
    (let [body @surfaces-md]
      ;; rf2-3bu4ik: the leaf used to group `watch-until` with the rAF-backed
      ;; `record` / `read-recording` recorder as one "rAF observer" triplet.
      ;; The current contract (tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md
      ;; + tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/watch_until.cljs)
      ;; keeps them separate: `record` installs a rAF observer with a
      ;; `:recording-id` change log; `watch-until` has the MCP SERVER poll a
      ;; cheap runtime read on a fixed cadence — NO rAF loop, NO browser-side
      ;; mailbox, NO recording registry. A consuming skill that conflates them
      ;; misdiagnoses a watch-until failure as an rAF / recording-registry
      ;; problem. This pin fails loudly if the differentiation regresses.
      (is (contains-any? body ["server polls" "server-side poll" "server-side"
                               "server poll" "polls a cheap runtime read"])
          (str "tool-pair-surfaces.md no longer describes `watch-until` as a "
               "SERVER-SIDE poll. Per the catalogue it polls a cheap runtime "
               "read on a fixed cadence, distinct from `record`'s rAF "
               "observer (rf2-3bu4ik)."))
      (is (contains-any? body ["no rAF loop" "no** rAF" "**no** rAF"
                               "not the rAF recorder" "does not use the rAF"
                               "does **not** use the rAF"])
          (str "tool-pair-surfaces.md no longer states that `watch-until` does "
               "NOT use the rAF recorder / loop. Grouping it with the rAF "
               "`record` / `read-recording` machinery is the stale contract "
               "(rf2-3bu4ik): watch-until has no rAF loop, no browser-side "
               "mailbox, and no recording registry / :recording-id.")))))

(deftest operating-frame-trio-enumerated
  (testing "the leaf names the multi-frame operating-frame trio"
    (let [body @surfaces-md]
      (is (and (str/includes? body "set-operating-frame")
               (str/includes? body "reset-operating-frame")
               (str/includes? body "get-operating-frame"))
          (str "tool-pair-surfaces.md no longer names the operating-frame "
               "trio (`set-operating-frame` / `reset-operating-frame` / "
               "`get-operating-frame`) required for multi-frame sessions. A "
               "multi-frame `:ambiguous-frame` finding would route to the "
               "wrong layer without it (spec/Tool-Pair.md §Operating frame, "
               "rf2-985x1t).")))))

;; ---------------------------------------------------------------------------
;; Lock — the operating-frame / registrar guidance teaches the post-EP-0023
;; PUBLIC model with the realm coordinate REMOVED ENTIRELY: the frame id is
;; the whole address; there is NO realm dimension, public OR internal,
;; anywhere in the wire/resolution shape.
;;
;; History of this lock block:
;;   * rf2-wpwckr pinned the EP-0013 disposition-3 realm-aware PUBLIC model
;;     (the `rf/realm-ids` / `rf/frame-realm` facade pair + a public `:realm`
;;     pin). SUPERSEDED.
;;   * The EP-0023 intermediate disposition demoted the realm to
;;     LABELED-INTERNAL installation substrate (read from `re-frame.realm` /
;;     `re-frame.frame`), with the inspect envelope still carrying `:realms` /
;;     `:operating-realm` / `:selected-realm` (always nil) slots. SUPERSEDED.
;;   * rf2-udl74a (PR #4811) ATOMICALLY REMOVED the `re-frame.realm` /
;;     `re-frame.app-value` / `re-frame.migration` substrate. There is now NO
;;     realm coordinate at all — public or internal. The operating-frame wire
;;     envelope is FRAME-ONLY: `:frames` / `:app-frames` / `:selected` /
;;     `:operating`, with the `:realms` / `:operating-realm` / `:selected-realm`
;;     / `:frame-realms` slots GONE (pinned at the wire layer by
;;     tools/mcp-conformance/.../operating_frame_address_test.clj
;;     §no-realm-slots-on-the-envelope; the runtime emitter is the pair preload
;;     `frames-list`).
;;
;; These pins now (a) FAIL LOUDLY if the leaf re-introduces ANY realm coordinate
;; as a LIVE surface — the removed facade pair, a public `:realm` pin, a
;; labeled-internal substrate read, or a realm slot on the inspect envelope —
;; and (b) keep the correct frame-only public model + the realm-removed history
;; pinned. Mirrors the frame-only collapse in the re-frame2-pair / re-frame2-xray
;; skill wording and the wire-vocab conformance gate.
;; ---------------------------------------------------------------------------

(deftest realm-vocab-only-as-removed-history-never-live
  (testing "any realm mention is framed as REMOVED, never as a live facade/substrate surface (rf2-udl74a)"
    (let [body @surfaces-md
          ;; If the leaf names the removed facade exports / internal namespaces
          ;; at all, they MUST appear inside an explicit removed/gone framing,
          ;; never as a live surface. rf2-udl74a deleted the whole substrate.
          names-realm-symbol? (or (str/includes? body "realm-ids")
                                  (str/includes? body "frame-realm")
                                  (str/includes? body "re-frame.realm")
                                  (str/includes? body "re-frame.frame"))]
      (when names-realm-symbol?
        (is (contains-any? body ["were **removed**" "were removed"
                                 "was removed" "removed atomically"
                                 "removed entirely" "are removed"])
            (str "tool-pair-surfaces.md mentions a realm symbol "
                 "(`realm-ids` / `frame-realm` / `re-frame.realm` / "
                 "`re-frame.frame`) but no longer frames it as REMOVED. "
                 "rf2-udl74a (PR #4811) deleted the `re-frame.realm` substrate "
                 "+ the `rf/realm-ids` / `rf/frame-realm` facade exports "
                 "atomically — there is no realm coordinate, public or "
                 "internal. Any realm mention is retired-history only.")))
      ;; The EP-0023-intermediate labeled-internal substrate framing is ALSO
      ;; stale now (the substrate it described was deleted). The leaf must NOT
      ;; teach the realm as a LIVE labeled-internal surface a tool reads.
      (is (not (contains-any? body ["survives only as **labeled-internal"
                                    "retained only as the labeled-internal"
                                    "MAY carry the **labeled-internal installation boundary**"
                                    "reads these from the **internal** `re-frame.realm`"]))
          (str "tool-pair-surfaces.md still teaches the realm as a LIVE "
               "labeled-internal installation substrate a tool reads. "
               "rf2-udl74a removed that substrate entirely; the EP-0023 "
               "intermediate labeled-internal disposition is now stale. The "
               "frame is the only address."))
      ;; No SETTABLE public realm pin, and no live `:selected-realm`-as-slot.
      (is (not (contains-any? body ["accept an optional **`:realm`**"
                                    "accept an optional `:realm`"
                                    "MAY accept an optional `:realm`"
                                    "optional `:realm` alongside the frame"
                                    "clears **both** the frame pin and the realm pin"
                                    "clears both the frame pin and the realm pin"
                                    "`:selected-realm` is always nil"]))
          (str "tool-pair-surfaces.md still teaches a realm pin / slot as live "
               "(a settable `:realm` pin, a reset that clears a realm pin, or "
               "`:selected-realm` as an envelope slot). rf2-udl74a removed the "
               "realm coordinate entirely; the envelope is frame-only.")))))

(deftest public-address-is-the-frame
  (testing "the leaf teaches the EP-0023 public model: the frame id is the whole public address"
    (let [body @surfaces-md]
      (is (contains-any? body ["image → frame → event stream"
                               "image -> frame -> event stream"
                               "public address is the frame"
                               "frame id is the whole public address"])
          (str "tool-pair-surfaces.md no longer teaches the EP-0023 public "
               "model — `image → frame → event stream`, where the frame id is "
               "the whole public address. The earlier (realm, frame) two-part "
               "public address was superseded (spec/Tool-Pair.md §Operating "
               "frame / §Surface dispositions)."))
      (is (contains-any? body ["no public realm pin"
                               "there is no public realm pin"
                               "no realm dimension and no public realm pin"
                               "no realm coordinate"])
          (str "tool-pair-surfaces.md no longer states there is NO realm "
               "coordinate / public realm pin. rf2-udl74a removed the realm "
               "entirely; a tool pins a FRAME, not a (realm, frame) pair.")))))

(deftest operating-frame-envelope-is-frame-only
  (testing "the operating-frame inspect envelope is named frame-only: :frames / :app-frames / :selected / :operating, no realm slots (rf2-udl74a)"
    (let [body @surfaces-md]
      (is (and (str/includes? body ":app-frames")
               (str/includes? body ":selected")
               (str/includes? body ":operating"))
          (str "tool-pair-surfaces.md no longer names the frame-only "
               "operating-frame envelope slots `:app-frames` / `:selected` / "
               "`:operating` (alongside `:frames`). Per the wire-vocab "
               "conformance gate (operating_frame_address_test.clj) the "
               "`get-operating-frame` envelope is exactly these frame slots "
               "(rf2-udl74a)."))
      (is (contains-any? body ["no realm slots" "There are no realm slots"
                               "no realm coordinate"])
          (str "tool-pair-surfaces.md no longer states the envelope carries NO "
               "realm slots. The `:realms` / `:operating-realm` / "
               "`:selected-realm` / `:frame-realms` slots were all removed "
               "from the wire shape (rf2-udl74a, "
               "operating_frame_address_test.clj §no-realm-slots-on-the-"
               "envelope)."))
      (is (str/includes? body ":frame` is REQUIRED")
          (str "tool-pair-surfaces.md no longer states `:frame` is REQUIRED on "
               "`set-operating-frame`. EP-0023: the public address is the "
               "frame id, so the arg is required (no implicit/realm target).")))))

;; ---------------------------------------------------------------------------
;; Lock — the four partition-aware state-injection mutators (rf2-7g9htq.2)
;;
;; The leaf used to name only `replace-app-db!` / `reset-app-db!` (the
;; app-db-only halves), under-teaching the post-EP-0001 partition-aware
;; write API. spec/Tool-Pair.md §Pair-tool writes defines FOUR mutators;
;; a skill that omits the runtime-db / full-frame siblings can describe
;; arbitrary repro / story state injection as app-db-only and miss the
;; full-frame install (`replace-frame-state!`) for machine snapshots /
;; routes / elision / SSR metadata. These pins fail loudly if the leaf
;; mentions `replace-app-db!` but drops either partition-aware sibling.
;; ---------------------------------------------------------------------------

(deftest state-injection-names-all-four-mutators
  (testing "the leaf names the four partition-aware state-injection mutators"
    (let [body @surfaces-md]
      (when (str/includes? body "replace-app-db!")
        (is (and (str/includes? body "replace-runtime-db!")
                 (str/includes? body "replace-frame-state!"))
            (str "tool-pair-surfaces.md names `replace-app-db!` but no longer "
                 "names both `replace-runtime-db!` (runtime-db-only privileged "
                 "write) and `replace-frame-state!` (full-frame atomic "
                 "install). The post-EP-0001 injection surface is FOUR "
                 "partition-aware mutators, not the app-db-only pair "
                 "(spec/Tool-Pair.md §Pair-tool writes, rf2-7g9htq.2)."))
        (is (str/includes? body "reset-app-db!")
            (str "tool-pair-surfaces.md dropped `reset-app-db!` from the "
                 "four-mutator state-injection family (rf2-7g9htq.2).")))
      (is (contains-any? body ["never silently touch" "never silently touches"
                               "never silently"])
          (str "tool-pair-surfaces.md no longer warns that the db-shaped "
               "names (`replace-app-db!` / `reset-app-db!`) preserve "
               "runtime-db — the load-bearing partition guarantee a "
               "story/repro tool relies on (rf2-7g9htq.2).")))))

;; ---------------------------------------------------------------------------
;; Lock — restore-epoch is whole-frame-state, not app-db-only (rf2-7g9htq /
;; rf2-7a1mkv). The leaf must teach that restore reinstalls BOTH partitions
;; via replace-frame-state!, so a consuming skill doesn't describe machine
;; snapshots / routes / elision as surviving time-travel.
;; ---------------------------------------------------------------------------

(deftest restore-epoch-named-as-frame-state-both-partitions
  (testing "restore-epoch is described as whole frame-state (both partitions)"
    (let [body @surfaces-md]
      (is (contains-any? body ["frame-state-after" "frame-state"])
          (str "tool-pair-surfaces.md no longer frames `restore-epoch` as a "
               "frame-state rewind. Restore reinstalls BOTH partitions "
               "(app-db AND runtime-db) via `replace-frame-state!`, not the "
               "app-db projection alone (spec/Tool-Pair.md §Restore)."))
      (is (str/includes? body "replace-frame-state!")
          (str "tool-pair-surfaces.md no longer names `replace-frame-state!` "
               "as the restore install surface (rf2-7a1mkv).")))))

;; ---------------------------------------------------------------------------
;; Lock — the privacy-relevant read catalogue covers the STREAMING reads +
;; the signal recorder, not just the one-shot direct-read set (rf2-7g9htq.3).
;;
;; The earlier leaf scoped the fail-closed egress map to the one-shot set
;; (snapshot / get-path / read-sub / list-subscriptions / dispatch-dry-run)
;; and omitted the streaming reads (subscribe / trace-window / watch-epochs)
;; and the signal recorder — all of which are privacy-bearing off-box reads
;; under the SAME default-off gate. A consuming skill using this leaf as the
;; privacy map could treat streaming epoch reads and recordings as outside
;; the boundary. These pins keep the broader catalogue + its emission sites
;; (elide-wire-value for direct values / recorded samples; projected-record
;; for epoch records) visible at the leaf.
;; ---------------------------------------------------------------------------

(deftest streaming-reads-named-as-gated-egress
  (testing "the leaf names the streaming reads subscribe / trace-window / watch-epochs as gated egress"
    (let [body @surfaces-md]
      (is (and (str/includes? body "subscribe")
               (str/includes? body "trace-window")
               (str/includes? body "watch-epochs"))
          (str "tool-pair-surfaces.md no longer names the streaming reads "
               "(`subscribe` / `trace-window` / `watch-epochs`) as off-box "
               "egress under the same default-off gate. They project / elide "
               "privacy-bearing values and are NOT outside the fail-closed "
               "boundary (re-frame2-pair-mcp §sensitive-reads gate, "
               "rf2-7g9htq.3)."))
      (is (str/includes? body "projected-record")
          (str "tool-pair-surfaces.md no longer names `projected-record` — "
               "the normative emission site for egressed EPOCH records "
               "(distinct from `elide-wire-value` for direct values). "
               "Without it the leaf overloads one emission site across "
               "value shapes (rf2-7g9htq.3).")))))

(deftest signal-recorder-egress-not-read-only-free-pass
  (testing "the leaf states recorded samples are elided before egress, not exempt for being read-only"
    (let [body @surfaces-md]
      (is (contains-any? body ["read-only is not the same as egress-safe"
                               "read-only is not"
                               "before entering the change log"
                               "before they enter the change log"])
          (str "tool-pair-surfaces.md no longer states that the signal "
               "recorder's read-only-by-construction posture does NOT exempt "
               "its recorded samples from elision — samples are value egress "
               "and route through `elide-wire-value` before entering the "
               "change log (rf2-7g9htq.3).")))))

;; ---------------------------------------------------------------------------
;; Lock — the linked authoritative direct-read privacy specs describe the
;; runtime-db elision registry, NOT the retired app-db one (rf2-kvpr74)
;;
;; `tool-pair-surfaces.md` points agents at spec/Tool-Pair.md §Direct-read
;; privacy and spec/Security.md §Direct-read privacy as the "Full contract".
;; Per EP-0001 the elision declaration registry is durable runtime-db state
;; at `[:rf.runtime/elision …]` (implementation/core/src/re_frame/elision.cljc),
;; NOT the retired app-db `[:rf/runtime :elision …]` root. A spec that still
;; teaches the app-db path in CURRENT TENSE produces false-green direct-read
;; privacy checks (a walker reading a dead registry emits raw values). This
;; guard fails on a CURRENT-TENSE `[:rf/runtime :elision …]` reference in
;; either linked spec or the shared leaf, allowing only explicit retired-
;; history mentions (a line that names the path as retired / no-longer-used).
;; ---------------------------------------------------------------------------

(defn- retired-framing?
  "True if the line names the legacy path inside an explicit retired-history
   framing (so it is documentation OF the retirement, not a live claim)."
  [line]
  (let [l (str/lower-case line)]
    (boolean (or (str/includes? l "retired")
                 (str/includes? l "no longer")
                 (str/includes? l "legacy")
                 (str/includes? l "formerly")
                 (str/includes? l "used to")
                 (str/includes? l "briefly sat")))))

(defn- current-tense-app-db-elision-lines
  "Return the lines of `md` that reference the retired app-db elision
   registry `[:rf/runtime :elision …]` in a CURRENT-TENSE framing (i.e. not
   inside a retired-history sentence)."
  [md]
  (->> (str/split-lines md)
       (filter #(re-find #"\[:rf/runtime\s+:elision" %))
       (remove retired-framing?)))

(deftest tool-pair-spec-elision-registry-is-runtime-db-not-app-db
  (testing "spec/Tool-Pair.md direct-read privacy no longer teaches the app-db [:rf/runtime :elision] registry in current tense (rf2-kvpr74)"
    (let [bad (current-tense-app-db-elision-lines @tool-pair-spec-md)]
      (is (empty? bad)
          (str "spec/Tool-Pair.md references the RETIRED app-db "
               "`[:rf/runtime :elision …]` elision registry in current "
               "tense. Per EP-0001 the registry is runtime-db state at "
               "`[:rf.runtime/elision …]` (elision.cljc). Update the text "
               "or frame the mention as retired history. Offending line(s): "
               (pr-str bad))))))

(deftest security-spec-elision-registry-is-runtime-db-not-app-db
  (testing "spec/Security.md no longer teaches the app-db [:rf/runtime :elision] registry in current tense (rf2-kvpr74)"
    (let [bad (current-tense-app-db-elision-lines @security-spec-md)]
      (is (empty? bad)
          (str "spec/Security.md references the RETIRED app-db "
               "`[:rf/runtime :elision …]` elision registry in current "
               "tense. Per EP-0001 the sensitive-rollup reads the runtime-db "
               "`[:rf.runtime/elision :sensitive-declarations]` registry. "
               "Update the text or frame the mention as retired history. "
               "Offending line(s): " (pr-str bad))))))

(deftest shared-leaf-elision-registry-is-runtime-db-not-app-db
  (testing "tool-pair-surfaces.md no longer teaches the app-db [:rf/runtime :elision] registry in current tense (rf2-kvpr74)"
    (let [bad (current-tense-app-db-elision-lines @surfaces-md)]
      (is (empty? bad)
          (str "tool-pair-surfaces.md references the RETIRED app-db "
               "`[:rf/runtime :elision …]` registry in current tense. The "
               "walker reads the runtime-db `[:rf.runtime/elision …]` "
               "registry (elision.cljc). Offending line(s): " (pr-str bad))))))

;; ---------------------------------------------------------------------------
;; Lock — the leaf carries an AVAILABILITY-TIER model, not a flat names-only
;; catalogue (rf2-1inyqr)
;;
;; The skills/shared best-practice review (rf2-1inyqr finding 1) found the
;; leaf taught a flat `## The surfaces` catalogue with no capability /
;; availability annotation — so a consuming skill could name the right
;; Tool-Pair family while teaching the WRONG operational model: treating an
;; absent epoch artefact like a broken pair tool, assuming `sub-cache` is
;; portable to JVM/SSR, expecting `data-rf-view` in a production build, or
;; abandoning usable production probes because the flat list hid which
;; surfaces still answer. The authoritative tiers live in spec/Tool-Pair.md
;; (dev-gate via `interop/debug-enabled?`, the `day8/re-frame2-epoch`
;; artefact home + absent-artefact split, the CLJS-only `sub-cache` note,
;; the 006 `data-rf-view` production-elision gate). These pins fail loudly
;; if the leaf regresses to a names-only catalogue that drops the tier
;; qualifiers.
;; ---------------------------------------------------------------------------

(deftest availability-tiers-section-present
  (testing "the leaf carries an availability-tier section, not a flat catalogue"
    (let [body @surfaces-md]
      (is (str/includes? body "## Availability tiers")
          (str "tool-pair-surfaces.md no longer carries the `## Availability "
               "tiers` section. A flat surface catalogue silently teaches "
               "that every surface answers everywhere — it does not. Agents "
               "routing a finding need the dev-gate / artefact / host / "
               "tool-side axes to avoid mistreating an absent artefact, a "
               "JVM host, or a production build (rf2-1inyqr)."))
      (is (str/includes? body "debug-enabled?")
          (str "tool-pair-surfaces.md no longer names the `debug-enabled?` "
               "dev-gate — the axis that decides which surfaces elide in a "
               "production build (rf2-1inyqr).")))))

(deftest availability-epoch-artefact-tier-pinned
  (testing "the epoch-artefact tier names the artefact home + absent-artefact split"
    (let [body @surfaces-md]
      (is (str/includes? body "day8/re-frame2-epoch")
          (str "tool-pair-surfaces.md no longer names the `day8/re-frame2-epoch` "
               "artefact home for the time-travel surface. Without it an agent "
               "treats an absent-artefact sentinel like a broken pair tool "
               "(spec/Tool-Pair.md §Time-travel, rf2-1inyqr)."))
      (is (str/includes? body ":rf.error/epoch-artefact-missing")
          (str "tool-pair-surfaces.md no longer names the "
               "`:rf.error/epoch-artefact-missing` raise — the absent-artefact "
               "behaviour of the WRITE surfaces (injection mutators), which "
               "differs from the read surfaces' silent sentinels. The split is "
               "load-bearing for routing (rf2-1inyqr).")))))

(deftest availability-sub-cache-is-cljs-only
  (testing "the leaf marks sub-cache as a CLJS-only host-gated surface"
    (let [body @surfaces-md]
      (is (str/includes? body "CLJS-only")
          (str "tool-pair-surfaces.md no longer carries the `CLJS-only` host "
               "qualifier. `sub-cache` has no JVM/SSR equivalent and the call "
               "MUST be host-gated; a portable-by-default reading is wrong "
               "(spec/Tool-Pair.md §Platform-availability note, rf2-1inyqr).")))))

(deftest availability-data-rf-view-production-elision-pinned
  (testing "the leaf pins data-rf-view as production-elided so view-plane reads have an empty map"
    (let [body @surfaces-md]
      ;; data-rf-view is already pinned by view-plane-reads-enumerated; here
      ;; we pin the AVAILABILITY claim — that it rides the production-elision
      ;; gate, so a production-attached session must not expect the view↔DOM
      ;; map to be populated (rf2-1inyqr).
      (is (contains-any? body ["data-rf-view` not stamped"
                               "data-rf-view / data-rf2-source-coord"
                               "data-rf2-source-coord` / `data-rf-view`"
                               "DOM annotations"])
          (str "tool-pair-surfaces.md no longer pins that `data-rf-view` (and "
               "`data-rf2-source-coord`) elide in production, leaving the "
               "view-plane reads with an empty view↔DOM map. An agent that "
               "expects view-plane reads in a production build routes a "
               "finding wrong (spec/006 §Production elision, rf2-1inyqr).")))))

(deftest availability-production-split-dev-gated-vs-still-answers
  (testing "the leaf pins the production split: dev-gated surfaces go dark, others still answer"
    (let [body @surfaces-md]
      ;; The single most consequential tier fact: a production-elided build is
      ;; a MIXED result. The dev-gated surfaces (trace / epoch / schema /
      ;; source-coord) go dark; the registrar query API (orientation), the
      ;; direct-read primitives (own egress posture), the operating-frame trio,
      ;; and the always-on error-emit substrate keep answering. A flat
      ;; catalogue teaches "everything is gone", which abandons usable probes.
      (is (contains-any? body ["production-elision split"
                               "production split"
                               "still answers"
                               "still answer"])
          (str "tool-pair-surfaces.md no longer states the production-elision "
               "split. Under `:advanced` + `goog.DEBUG=false` the build is a "
               "MIXED result, not a total wall — the registrar query / "
               "direct-read / error surfaces still answer (rf2-1inyqr)."))
      (is (and (contains-any? body ["registrar query" "registry" "orientation"])
               (str/includes? body "always-on error")
               (str/includes? body "direct-read"))
          (str "tool-pair-surfaces.md no longer names the surfaces that STILL "
               "answer under production elision — the registrar query / "
               "orientation shape, the direct-read primitives, and the "
               "always-on error-emit substrate. Naming only the dark surfaces "
               "teaches an agent to abandon usable production probes "
               "(spec/009 §What IS available in production, rf2-1inyqr).")))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'tool-pair-surfaces-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
