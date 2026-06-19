(ns re-frame.mcp-conformance.operating-frame-address-test
  "EP-0023 public frame-addressing wire gate (rf2-f0isjs). The wire
  counterpart of rf2-q8whbb (pair-mcp EP-0023 frame addressing on the
  public tool surface).

  ## What EP-0023 landed (the wire shape this gate pins)

  EP-0023 makes the public model `image -> frame -> event stream` and
  DEMOTES the EP-0013 `(realm, frame)` two-part address to the INTERNAL
  installation/container substrate. q8whbb (`set/reset/get-operating-frame`
  trio) collapsed the two-part address into ONE public frame-id space:

  - the public address an agent supplies is the FRAME id — `:frame`. There
    is NO public `:realm` pin arg on `set-operating-frame` any longer (it
    was dropped per EP-0023 §Surface dispositions).
  - the operating-frame envelope reports `:frames` (all registered) /
    `:app-frames` (reserved-frame-aware) / `:selected` (the tier-2 session
    pin) / `:operating` (the result of full resolution — `nil` means
    AMBIGUOUS: two-plus app frames and no pin).
  - the realm slots SURVIVE on the envelope as the LABELED-INTERNAL
    installation boundary — `:realms` / `:operating-realm` / `:selected-realm`
    — explicitly NOT the central addressing model. A single-realm app collapses
    them to the `:rf.realm/default` no-op. (The per-frame `:frame-realms`
    `{frame-id container-id}` map was REMOVED under rf2-70owfr — the afdlyr
    realm-substrate collapse leaves a single default realm, so a per-frame
    container map carried no information.)

  This gate pins THAT wire shape so a regression that re-promotes the
  public realm pin, drops a frame-enumeration slot, or breaks the
  multi-frame independent-resolution contract trips the conformance corpus.

  ## What is DEFERRED (NOT in scope here — rf2-srobm0 territory)

  q8whbb's own commit + the handler-meta / 003-Tool-Catalogue spec text
  explicitly DEFER the FORWARD direction — re-keying handler resolution
  through the operating frame's resolved IMAGE GENERATION, an
  image-inspection (`describe-image`) tool, frame capabilities / adapter
  reporting, and the `:rf.provenance/ns` / `:include-ns` / `:rf.image/requires`
  wire vocabulary — to the follow-on gated on the EP-0023 object-make-frame
  public addressing + frame-image-generation read API. NONE of those
  markers/tools exist on the landed surface, so this gate scopes STRICTLY to
  the public frame-addressing envelope q8whbb shipped (rf2-f0isjs bead
  finding 2). The same-id/different-image isolation case (bead finding 1,
  the MCP-wire counterpart of engine bead rf2-slvzn3) and the
  image-descriptor / capability-failure cases (findings 3-4) wait on that
  forward-direction API — see rf2-srobm0.

  ## Posture (mirrors `cascade_bundle_test.clj` / `result_envelope_test.clj`)

  The operating-frame envelope is a STRUCTURAL result envelope (like
  `:rf.mcp/result` and the cascade-bundle shape) — NOT a single-key
  wrapper marker, so it gets its own focused namespace rather than a
  `canonical-markers` row. The schema is co-located here (the
  marker-family-LOCAL posture documented in `schemas.clj`). The EMITTER is
  CLJS (`operating_frame.cljs` + the runtime preload), with no
  JVM-reachable builder — same FIXTURE+source-pin posture as
  `:rf.mcp/summary` / `:rf.mcp/result`."
  (:require [clojure.string :as str]
            [clojure.test   :refer [deftest is testing]]
            [malli.core     :as m]
            [malli.error    :as me]
            [re-frame.mcp-conformance.fixtures :as fx]
            [re-frame.mcp-conformance.wire-vocab.source-pins :as pins]))

;; ---------------------------------------------------------------------------
;; Canonical schemas. The operating-frame envelope has a SUCCESS shape and a
;; FAILURE shape, dispatched on `:ok?`. Both are co-located here.
;; ---------------------------------------------------------------------------

(def OperatingFrameSuccess
  "The `{:ok? true ...}` operating-frame envelope per the Tool-Pair
  §Tool-surface-obligations contract as collapsed by EP-0023 (q8whbb).
  Emitted by `get-operating-frame` (pure read), `set-operating-frame` (on
  a successful pin), and `reset-operating-frame` (post-reset).

  PUBLIC addressing slots (the central EP-0023 model):
    :frames     — vector of ALL registered frame-ids. The public address
                  space — the caller picks a target from here.
    :app-frames — the reserved-frame-aware view (reserved `:rf/*` tool
                  frames filtered out). A subset of `:frames`.
    :selected   — the tier-2 SESSION PIN (the frame `set-operating-frame`
                  last pinned); `nil` when unset.
    :operating  — the RESULT of full resolution: the pinned frame, else
                  the sole app frame (tier 3), else `nil` (tier 4 —
                  AMBIGUOUS: two-plus app frames, no pin). A frame-id or nil.

  LABELED-INTERNAL installation-boundary slots (EP-0023 §Surface
  dispositions — tooling MAY expose the internal installation/container
  substrate, labeled as implementation structure, NOT the public model):
    :realms         — vector of internal installation-container ids.
    :operating-realm — the container tier-3 resolution scopes to.
    :selected-realm  — the tier-2 container pin; `nil` when unset.

  (The per-frame `:frame-realms` `{frame-id container-id}` map was REMOVED
  under rf2-70owfr — the afdlyr realm-substrate collapse leaves a single
  default realm, so a per-frame container map carried no information.)

  Open map `{:closed false}` — additive envelope slots (the
  source-uri/freshness decorations the wire-pipeline layers on) compose
  without a schema bump. The LOAD-BEARING claim is that the public
  addressing slots are present and correctly typed, AND that the realm
  slots remain (the internal substrate is still REPORTED, not removed)."
  [:map
   {:closed false}
   [:ok?        [:= true]]
   [:frames     [:sequential :keyword]]
   [:app-frames [:sequential :keyword]]
   [:selected   [:maybe :keyword]]
   [:operating  [:maybe :keyword]]
   ;; Labeled-internal installation boundary — present on the envelope,
   ;; never the public address.
   [:realms          {:optional true} [:sequential :keyword]]
   [:operating-realm {:optional true} [:maybe :keyword]]
   [:selected-realm  {:optional true} [:maybe :keyword]]])

(def OperatingFrameFailure
  "The `{:ok? false :reason <kw> ...}` operating-frame failure envelope.
  `set-operating-frame` emits two failure reasons over the wire:

    :no-such-frame — `:frame` named a frame that is NOT currently
                     registered. The envelope echoes the rejected `:frame`
                     and lists the valid `:frames` so the agent can retry
                     against a real target.
    :missing-frame — no `:frame` arg supplied (the arg is REQUIRED post
                     EP-0023 — the public address is the frame id).

  Open map — `:frames` rides on `:no-such-frame` (it's the corrective
  hint) but is absent on `:missing-frame`."
  [:map
   {:closed false}
   [:ok?    [:= false]]
   [:reason [:enum :no-such-frame :missing-frame]]])

;; ---------------------------------------------------------------------------
;; Fixtures — mirror the descriptor examples in
;; `descriptors_data.cljs` (set/reset/get-operating-frame) verbatim, plus
;; the multi-frame independent-resolution cases (bead finding 2).
;; ---------------------------------------------------------------------------

(def ^:private success-fixtures
  "Per-scenario success envelopes mirroring the documented descriptor
  examples + the EP-0023 multi-frame independent-resolution contract.
  Every fixture MUST validate against `OperatingFrameSuccess`."
  {;; --- single-frame, sole-app-frame tier-3 resolution -------------------
   ;; get-operating-frame example 1: nothing pinned, one app frame ⇒
   ;; :operating auto-resolves to the sole frame, realm collapse to default.
   :single-frame-sole-resolution
   {:ok?             true
    :frames          [:rf/default]
    :app-frames      [:rf/default]
    :selected        nil
    :operating       :rf/default
    :realms          [:rf.realm/default]
    :operating-realm :rf.realm/default
    :selected-realm  nil}

   ;; --- multi-frame, nothing pinned: AMBIGUOUS (bead finding 2) ----------
   ;; get-operating-frame example 2 / reset-operating-frame example 1:
   ;; two distinct app frames enumerate independently; with NO pin the
   ;; resolution is AMBIGUOUS ⇒ :operating nil (frame-targeted ops refuse
   ;; rather than guess). The two frame-ids are independent addresses.
   :multi-frame-ambiguous
   {:ok?             true
    :frames          [:rf/default :stories]
    :app-frames      [:rf/default :stories]
    :selected        nil
    :operating       nil
    :realms          [:rf.realm/default]
    :operating-realm :rf.realm/default
    :selected-realm  nil}

   ;; --- multi-frame WITH a pin (set/get-operating-frame example) ---------
   ;; set-operating-frame example 1 / get-operating-frame example 3: the
   ;; pin selects ONE frame-id out of the multi-frame address space; that
   ;; frame becomes :operating. The OTHER frame is unaffected — independent
   ;; resolution. Public address = the frame id alone.
   :multi-frame-pinned
   {:ok?             true
    :frames          [:rf/default :stories]
    :app-frames      [:rf/default :stories]
    :selected        :stories
    :operating       :stories
    :realms          [:rf.realm/default]
    :operating-realm :rf.realm/default
    :selected-realm  nil}

   ;; --- reserved-frame-aware :app-frames view ----------------------------
   ;; discover-app / orient filter reserved `:rf/*` tool frames out of
   ;; :app-frames while :frames still enumerates them — the public-address
   ;; space includes the tool frame, the app-frame view does not.
   :reserved-frame-filtered
   {:ok?             true
    :frames          [:rf/default :rf/xray]
    :app-frames      [:rf/default]
    :selected        nil
    :operating       :rf/default
    :realms          [:rf.realm/default]
    :operating-realm :rf.realm/default
    :selected-realm  nil}})

(def ^:private failure-fixtures
  "Per-reason failure envelopes mirroring set-operating-frame examples 2
  + 3. Every fixture MUST validate against `OperatingFrameFailure`."
  {;; set-operating-frame example 2: :frame named an unregistered frame.
   :no-such-frame
   {:ok?    false
    :reason :no-such-frame
    :frame  :nope
    :frames [:rf/default :stories]}

   ;; set-operating-frame example 3: the (now-REQUIRED) :frame arg omitted.
   :missing-frame
   {:ok?    false
    :reason :missing-frame}})

;; ---------------------------------------------------------------------------
;; Schema-conformance + structural assertions
;; ---------------------------------------------------------------------------

(deftest success-fixtures-conform-to-schema
  (doseq [[fixture-name fixture-value] success-fixtures]
    (testing (str "operating-frame success — fixture " fixture-name)
      (is (m/validate OperatingFrameSuccess fixture-value)
          (str "Fixture " fixture-name " failed OperatingFrameSuccess "
               "validation:\n"
               (me/humanize (m/explain OperatingFrameSuccess fixture-value)))))))

(deftest failure-fixtures-conform-to-schema
  (doseq [[fixture-name fixture-value] failure-fixtures]
    (testing (str "operating-frame failure — fixture " fixture-name)
      (is (m/validate OperatingFrameFailure fixture-value)
          (str "Fixture " fixture-name " failed OperatingFrameFailure "
               "validation:\n"
               (me/humanize (m/explain OperatingFrameFailure fixture-value)))))))

(deftest success-envelope-enforces-public-addressing-slots
  ;; The teeth: the public addressing slots are REQUIRED and typed. A
  ;; regression that dropped `:frames` (the public address space) or
  ;; re-typed `:operating` to a non-keyword would mask the EP-0023
  ;; addressing contract this gate pins.
  (let [base (:multi-frame-pinned success-fixtures)]
    (testing "an envelope missing :frames (the public address space) fails"
      (is (not (m/validate OperatingFrameSuccess (dissoc base :frames)))
          ":frames is the public address space — it MUST ride on every success envelope"))
    (testing "an envelope missing :operating (the resolution result) fails"
      (is (not (m/validate OperatingFrameSuccess (dissoc base :operating)))
          ":operating is the resolved target — it MUST ride on every success envelope"))
    (testing "an envelope missing :selected (the tier-2 pin slot) fails"
      (is (not (m/validate OperatingFrameSuccess (dissoc base :selected)))))
    (testing ":operating MUST be a frame-id keyword or nil, never a string"
      (is (not (m/validate OperatingFrameSuccess (assoc base :operating "stories")))
          "a string :operating is a serialisation regression — frame-ids ride as keywords"))
    (testing ":frames MUST be keywords, never strings"
      (is (not (m/validate OperatingFrameSuccess (assoc base :frames ["rf/default" "stories"])))))))

(deftest realm-slots-survive-as-labeled-internal-substrate
  ;; EP-0023 demoted the realm to the INTERNAL installation substrate but
  ;; KEPT it on the envelope (labeled-internal, not removed). Pin that the
  ;; installation-boundary realm slots (`:realms` / `:operating-realm`) still
  ;; ride AND that a single-realm app collapses them to the `:rf.realm/default`
  ;; no-op (the documented installation-boundary collapse). A regression that
  ;; DROPPED these realm slots entirely (over-reading EP-0023's demotion as a
  ;; removal) would trip these. (The per-frame `:frame-realms` map was removed
  ;; under rf2-70owfr — the single default realm makes it informationless — so
  ;; it is NO LONGER pinned here.)
  (let [collapsed (:single-frame-sole-resolution success-fixtures)]
    (testing "the labeled-internal realm slots are present on the envelope"
      (is (contains? collapsed :realms))
      (is (contains? collapsed :operating-realm)))
    (testing "the per-frame :frame-realms map is GONE (rf2-70owfr)"
      (is (not (contains? collapsed :frame-realms))
          "the single default realm makes a per-frame container map informationless")
      (is (m/validate OperatingFrameSuccess collapsed)
          "the envelope still validates without :frame-realms — the schema dropped the key"))
    (testing "single-realm app collapses the installation boundary to the default no-op"
      (is (= [:rf.realm/default] (:realms collapsed)))
      (is (= :rf.realm/default (:operating-realm collapsed))))
    (testing "the realm slots are typed keywords (the internal substrate is reported faithfully)"
      (is (m/validate OperatingFrameSuccess collapsed)))))

(deftest multi-frame-independent-resolution
  ;; Bead rf2-f0isjs finding 2: two distinct frame ids resolve
  ;; INDEPENDENTLY. The landed q8whbb operating-frame envelope is the
  ;; wire surface for this — discover-app/orient/get-operating-frame
  ;; enumerate both frames; pinning one selects it WITHOUT affecting the
  ;; other; with NO pin the resolution is ambiguous (refuses, never
  ;; guesses). This is the public frame-addressing multi-frame contract
  ;; (NOT the deferred same-id/different-image isolation — see ns
  ;; docstring + rf2-srobm0).
  (let [ambiguous (:multi-frame-ambiguous success-fixtures)
        pinned    (:multi-frame-pinned success-fixtures)]
    (testing "both frame ids enumerate in the public :frames address space"
      (is (= [:rf/default :stories] (:frames ambiguous)))
      (is (= [:rf/default :stories] (:frames pinned)))
      (is (= (:frames ambiguous) (:frames pinned))
          "the enumeration is the same regardless of which frame is pinned"))
    (testing "NO pin in a multi-frame app ⇒ :operating nil (AMBIGUOUS, refuses to guess)"
      (is (nil? (:selected ambiguous)))
      (is (nil? (:operating ambiguous))
          "tier-4 ambiguity: two-plus app frames + no pin ⇒ unresolved (frame-targeted ops refuse)"))
    (testing "pinning ONE frame resolves to exactly that frame"
      (is (= :stories (:selected pinned)))
      (is (= :stories (:operating pinned))
          "the pinned frame is the resolved :operating target")
      (is (not= :rf/default (:operating pinned))
          "the OTHER frame is NOT resolved — independent addressing"))
    (testing "the pin is the ONLY difference between the two multi-frame envelopes"
      (is (= (dissoc ambiguous :selected :operating)
             (dissoc pinned :selected :operating))
          "same frame enumeration + same realm collapse; only the pin/resolution differ"))))

(deftest reserved-frames-excluded-from-app-frames
  ;; The reserved-frame-aware view: `:frames` enumerates the FULL public
  ;; address space (incl. reserved `:rf/*` tool frames); `:app-frames`
  ;; filters the reserved ones out. A regression that leaked a reserved
  ;; frame into :app-frames (or dropped it from :frames) trips here.
  (let [filtered (:reserved-frame-filtered success-fixtures)]
    (testing ":frames includes the reserved tool frame (full address space)"
      (is (some #{:rf/xray} (:frames filtered))))
    (testing ":app-frames EXCLUDES the reserved tool frame"
      (is (not (some #{:rf/xray} (:app-frames filtered)))
          "reserved :rf/* tool frames are filtered out of the app-frame view"))
    (testing ":app-frames is a subset of :frames"
      (is (every? (set (:frames filtered)) (:app-frames filtered))))))

(deftest failure-envelope-enforces-reason-vocabulary
  ;; The two documented failure reasons are the closed wire vocabulary;
  ;; `:no-such-frame` carries the corrective `:frames` list. A novel
  ;; reason keyword or an `:ok? true` failure trips the gate.
  (testing "an :ok? true failure envelope is rejected (ok?/reason mismatch)"
    (is (not (m/validate OperatingFrameFailure
                         {:ok? true :reason :no-such-frame}))))
  (testing "an unrecognised :reason has no enum arm and fails"
    (is (not (m/validate OperatingFrameFailure
                         {:ok? false :reason :bogus-reason}))))
  (testing ":no-such-frame echoes the rejected :frame and lists valid :frames"
    (let [nsf (:no-such-frame failure-fixtures)]
      (is (= :nope (:frame nsf)) "the rejected frame is echoed for the agent")
      (is (vector? (:frames nsf)) "the valid frames are listed as the corrective hint")
      (is (not (some #{:nope} (:frames nsf)))
          "the rejected frame is genuinely absent from the registered set")))
  (testing "both documented reasons validate"
    (is (m/validate OperatingFrameFailure (:no-such-frame failure-fixtures)))
    (is (m/validate OperatingFrameFailure (:missing-frame failure-fixtures)))))

;; ---------------------------------------------------------------------------
;; Source-text pins — the EP-0023 collapse, as DATA, in the pair-mcp
;; descriptor + spec. Mirrors the marker-literal pins in
;; `result_envelope_test.clj`.
;; ---------------------------------------------------------------------------

(def ^:private set-operating-frame-descriptor-rel
  "Repo-relative path to the pair-mcp tool-descriptor data — the home of
  the set/reset/get-operating-frame descriptor maps (their inputSchema +
  example envelopes)."
  "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/descriptors_data.cljs")

(deftest set-operating-frame-requires-frame-arg
  ;; EP-0023: the public address is the FRAME id, so `set-operating-frame`
  ;; REQUIRES `:frame`. Pin that the descriptor's inputSchema declares
  ;; `:frame` required (`:required ["frame"]`). A regression that made it
  ;; optional would re-open the pre-EP-0023 implicit-target ambiguity.
  (let [src (fx/read-source set-operating-frame-descriptor-rel)]
    (is (str/includes? src "set-operating-frame")
        "the set-operating-frame descriptor must live in the descriptor data")
    (is (str/includes? src ":required [\"frame\"]")
        (str "set-operating-frame's inputSchema MUST declare :frame REQUIRED "
             "(EP-0023: the public address is the frame id). A missing "
             ":required [\"frame\"] re-opens the implicit-target ambiguity."))))

(deftest set-operating-frame-has-no-public-realm-pin-arg
  ;; The load-bearing EP-0023 collapse (q8whbb): the public `:realm` pin
  ;; arg was DROPPED from set-operating-frame's inputSchema — the realm is
  ;; now the internal installation substrate, never a public address. Pin
  ;; that the set-operating-frame inputSchema's `:properties` carries ONLY
  ;; `:frame` + `:build` (the build-targeting arg every tool shares), and
  ;; specifically NOT a `:realm` / `:realm-id` property. A regression that
  ;; re-promoted the public realm pin trips here — the exact pre-q8whbb
  ;; shape EP-0023 collapsed.
  (let [src (fx/read-source set-operating-frame-descriptor-rel)
        ;; isolate the set-operating-frame descriptor block (def-to-def)
        block (-> src
                  (str/split #"\(def set-operating-frame")
                  second
                  (str/split #"\(def reset-operating-frame")
                  first)]
    (is (some? block)
        "could not isolate the set-operating-frame descriptor block")
    (is (str/includes? block ":frame")
        "the set-operating-frame inputSchema declares the public :frame address")
    (testing "no public :realm pin arg in the set-operating-frame inputSchema"
      ;; The block-local inputSchema must not declare a `:realm` (or
      ;; `:realm-id`) PROPERTY. We look for the property-declaration form
      ;; `:realm {` / `:realm-id {` (an inputSchema property is a map),
      ;; which would only appear if the public realm pin were re-added.
      ;; Prose mentions of "realm" in the docstring are fine (the docstring
      ;; explains the labeled-internal demotion) — we pin the PROPERTY form.
      (is (not (re-find #":realm(-id)?\s*\{" block))
          (str "set-operating-frame's inputSchema MUST NOT declare a public "
               ":realm / :realm-id property — EP-0023 dropped the public realm "
               "pin (q8whbb). The realm is the INTERNAL installation substrate, "
               "never a public address. Re-adding it reverts the EP-0023 "
               "addressing collapse.")))))

(deftest ep-0023-frame-address-prose-in-descriptor-and-spec
  ;; Doc-source pin (looser): the EP-0023 frame-addressing model is taught
  ;; in the pair-mcp descriptor prose + the Tool-Catalogue spec — the human
  ;; home for "the public address is the frame id". A reorganisation that
  ;; dropped the model entirely should surface here. Mirrors
  ;; `result-key-literal-in-mcp-base-vocab-spec-doc-source`.
  (let [descriptor (fx/read-source set-operating-frame-descriptor-rel)
        spec-rel   "tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md"
        spec       (fx/read-source spec-rel)]
    (testing "the descriptor prose teaches the EP-0023 frame-address model"
      (is (str/includes? descriptor "EP-0023")
          "set-operating-frame's descriptor must anchor the EP-0023 model")
      (is (str/includes? descriptor ":no-such-frame")
          "the descriptor documents the :no-such-frame failure reason"))
    (testing "the Tool-Catalogue spec carries the EP-0023 frame-addressing prose"
      (is (str/includes? spec "EP-0023")
          (str spec-rel " must document the EP-0023 frame-addressing model "
               "(the forward-direction deferral + the public-address collapse).")))))
