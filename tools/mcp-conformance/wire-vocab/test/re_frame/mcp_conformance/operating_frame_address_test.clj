(ns re-frame.mcp-conformance.operating-frame-address-test
  "EP-0023 public frame-addressing wire gate — the MCP-wire shape of
  pair-mcp's EP-0023 frame addressing on the public tool surface.

  ## The wire shape this gate pins

  EP-0023 makes the public model `image -> frame -> event stream`. The
  `set/reset/get-operating-frame` trio addresses ONE public frame-id space:

  - the public address an agent supplies is the FRAME id — `:frame`. There
    is NO public `:realm` pin arg on `set-operating-frame` (EP-0023
    §Surface dispositions).
  - the operating-frame envelope reports `:frames` (all registered) /
    `:app-frames` (reserved-frame-aware) / `:selected` (the tier-2 session
    pin) / `:operating` (the result of full resolution — `nil` means
    AMBIGUOUS: two-plus app frames and no pin).

  This gate pins THAT wire shape, and ties each fixture to the production
  source it mirrors, so a regression that drops a frame-enumeration slot
  trips the conformance corpus.

  ## What is DEFERRED (NOT in scope here)

  The handler-meta / 003-Tool-Catalogue spec text DEFERS the FORWARD
  direction — re-keying handler resolution through the operating frame's
  resolved IMAGE GENERATION, an image-inspection (`describe-image`) tool,
  frame adapter reporting, and the `:rf.provenance/ns` / `:select-ns`
  wire vocabulary — to a follow-on gated on the EP-0023 object-make-frame
  public addressing + frame-image-generation read API. (Under EP-0026 there
  are no image-declared host capabilities — no `:include-ns` / `:exclude-ns`
  / `:rf.image/requires` vocabulary — so that forward surface carries
  none.) NONE of those markers/tools exist on the surface, so this gate
  scopes STRICTLY to the public frame-addressing envelope. The
  same-id/different-image isolation case and the image-descriptor case
  wait on that forward-direction API.

  ## Posture (mirrors `result_envelope_test.clj`)

  The operating-frame envelope is a STRUCTURAL result envelope (like
  `:rf.mcp/result`) — NOT a single-key
  wrapper marker, so it gets its own focused namespace rather than a
  `canonical-markers` row. The schema is co-located here (the
  marker-family-LOCAL posture documented in `schemas.clj`). The EMITTER is
  CLJS (`operating_frame.cljs` + the runtime preload), with no
  JVM-reachable builder — same FIXTURE+source-pin posture as
  `:rf.mcp/summary` / `:rf.mcp/result`."
  (:require [clojure.edn    :as edn]
            [clojure.string :as str]
            [clojure.test   :refer [deftest is testing]]
            [malli.core     :as m]
            [malli.error    :as me]
            [re-frame.mcp-conformance.fixtures :as rf.mcp-conformance.fixtures]))

;; ---------------------------------------------------------------------------
;; Canonical schemas. The operating-frame envelope has a SUCCESS shape and a
;; FAILURE shape, dispatched on `:ok?`. Both are co-located here.
;; ---------------------------------------------------------------------------

(def OperatingFrameSuccess
  "The `{:ok? true ...}` operating-frame envelope per the Tool-Pair
  §Tool-surface-obligations contract under EP-0023.
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

  Open map `{:closed false}` — additive envelope slots (the
  source-uri/freshness decorations the wire-pipeline layers on) compose
  without a schema bump. The LOAD-BEARING claim is that the public
  addressing slots are present and correctly typed."
  [:map
   {:closed false}
   [:ok?        [:= true]]
   [:frames     [:sequential :keyword]]
   [:app-frames [:sequential :keyword]]
   [:selected   [:maybe :keyword]]
   [:operating  [:maybe :keyword]]])

(def OperatingFrameFailure
  "The `{:ok? false :reason <kw> ...}` operating-frame failure envelope.
  `set-operating-frame` emits two failure reasons over the wire:

    :no-such-frame — `:frame` named a frame that is NOT currently
                     registered. The envelope echoes the rejected `:frame`
                     and lists the valid `:frames` so the agent can retry
                     against a real target.
    :missing-frame — no `:frame` arg supplied (the arg is REQUIRED —
                     under EP-0023 the public address is the frame id).

  Open map — `:frames` rides on `:no-such-frame` (it's the corrective
  hint) but is absent on `:missing-frame`."
  [:map
   {:closed false}
   [:ok?    [:= false]]
   [:reason [:enum :no-such-frame :missing-frame]]])

;; ---------------------------------------------------------------------------
;; Fixtures — the success envelopes mirror the map the runtime's
;; `frames-list` builds (the descriptor examples abbreviate it: they omit
;; `:app-frames`); the failure envelopes mirror set-operating-frame's
;; descriptor examples 2 + 3. `fixtures-mirror-their-production-source`
;; compares each with its source.
;; ---------------------------------------------------------------------------

(def ^:private success-fixtures
  "Per-scenario success envelopes — the `re-frame2-pair.runtime/frames-list`
  map that get-operating-frame and reset-operating-frame return, in the
  scenarios the descriptor examples document + the EP-0023 multi-frame
  independent-resolution contract.
  Every fixture MUST validate against `OperatingFrameSuccess`."
  {;; --- single-frame, sole-app-frame tier-3 resolution -------------------
   ;; get-operating-frame example 1: nothing pinned, one app frame ⇒
   ;; :operating auto-resolves to the sole frame.
   :single-frame-sole-resolution
   {:ok?             true
    :frames          [:rf/default]
    :app-frames      [:rf/default]
    :selected        nil
    :operating       :rf/default}

   ;; --- multi-frame, nothing pinned: AMBIGUOUS ---------------------------
   ;; get-operating-frame example 2 / reset-operating-frame example 1:
   ;; two distinct app frames enumerate independently; with NO pin the
   ;; resolution is AMBIGUOUS ⇒ :operating nil (frame-targeted ops refuse
   ;; rather than guess). The two frame-ids are independent addresses.
   :multi-frame-ambiguous
   {:ok?             true
    :frames          [:rf/default :stories]
    :app-frames      [:rf/default :stories]
    :selected        nil
    :operating       nil}

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
    :operating       :stories}

   ;; --- reserved-frame-aware :app-frames view ----------------------------
   ;; discover-app / orient filter reserved `:rf/*` tool frames out of
   ;; :app-frames while :frames still enumerates them — the public-address
   ;; space includes the tool frame, the app-frame view does not.
   :reserved-frame-filtered
   {:ok?             true
    :frames          [:rf/default :rf/xray]
    :app-frames      [:rf/default]
    :selected        nil
    :operating       :rf/default}})

(def ^:private failure-fixtures
  "Per-reason failure envelopes mirroring set-operating-frame examples 2
  + 3. Every fixture MUST validate against `OperatingFrameFailure`."
  {;; set-operating-frame example 2: :frame named an unregistered frame.
   :no-such-frame
   {:ok?    false
    :reason :no-such-frame
    :frame  :nope
    :frames [:rf/default :stories]}

   ;; set-operating-frame example 3: the REQUIRED :frame arg omitted.
   :missing-frame
   {:ok?    false
    :reason :missing-frame}})

;; ---------------------------------------------------------------------------
;; Schema-conformance + structural assertions
;; ---------------------------------------------------------------------------

(deftest fixtures-conform-to-schema
  (doseq [[schema fixtures] [[OperatingFrameSuccess success-fixtures]
                             [OperatingFrameFailure failure-fixtures]]
          [fixture-name fixture-value] fixtures]
    (is (m/validate schema fixture-value)
        (str "Fixture " fixture-name " failed operating-frame validation:\n"
             (me/humanize (m/explain schema fixture-value))))))

(deftest schemas-enforce-the-addressing-slots-and-reasons
  ;; The teeth: the public address space is REQUIRED, frame-ids ride as
  ;; keywords, and the failure reasons are a closed vocabulary.
  (let [base (:multi-frame-pinned success-fixtures)]
    (doseq [[label schema envelope]
            [[":frames is the public address space — it MUST ride on every success envelope"
              OperatingFrameSuccess (dissoc base :frames)]
             ["a string :operating is a serialisation regression — frame-ids ride as keywords"
              OperatingFrameSuccess (assoc base :operating "stories")]
             ["an unrecognised :reason has no enum arm"
              OperatingFrameFailure {:ok? false :reason :bogus-reason}]]]
      (is (not (m/validate schema envelope)) label))))

;; ---------------------------------------------------------------------------
;; Source-text pins — each fixture family against the production source it
;; mirrors.
;; ---------------------------------------------------------------------------

(def ^:private set-operating-frame-descriptor-rel
  "Repo-relative path to the pair-mcp tool-descriptor data — the home of
  the set/reset/get-operating-frame descriptor maps (their inputSchema +
  example envelopes)."
  "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/descriptors_data.cljs")

(def ^:private frames-list-rel
  "Repo-relative path to the runtime preload whose `frames-list` builds the
  success envelope get-operating-frame and reset-operating-frame return."
  "skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs")

(defn- set-operating-frame-block
  "The set-operating-frame descriptor's source text (def-to-def)."
  []
  (-> (rf.mcp-conformance.fixtures/read-source set-operating-frame-descriptor-rel)
      (str/split #"\(def set-operating-frame")
      second
      (str/split #"\(def reset-operating-frame")
      first))

(defn- set-operating-frame-example-reply
  "The reply map of example `n` in set-operating-frame's description — the
  EDN after `->` on its `\"<n>. … -> {…}\"` line — or nil when no such
  example exists."
  [n]
  (some->> (set-operating-frame-block)
           (re-find (re-pattern (str "\"" n "\\. .*?-> (\\{[^}]*\\})")))
           second
           edn/read-string))

(deftest fixtures-mirror-their-production-source
  ;; The fixtures above are hand-written; this compares each family with the
  ;; ONE source it mirrors, so a production envelope that drops a slot the
  ;; fixture carries goes red here rather than leaving every schema test green.
  (testing "every success-fixture key is emitted as data by the runtime's frames-list"
    (let [text (rf.mcp-conformance.fixtures/source-form frames-list-rel "(defn frames-list")]
      (doseq [k (into (sorted-set) (mapcat keys) (vals success-fixtures))]
        (is (re-find (rf.mcp-conformance.fixtures/variant-regex (pr-str k)) text)
            (str "success-fixture key " (pr-str k) " is not emitted as data by "
                 "frames-list in " frames-list-rel)))))
  (testing "every failure-fixture key is in the set-operating-frame example it mirrors"
    (doseq [[fixture-name n] {:no-such-frame 2 :missing-frame 3}
            :let [example (set-operating-frame-example-reply n)]
            k (keys (failure-fixtures fixture-name))]
      (is (contains? example k)
          (str "failure fixture " fixture-name " carries " (pr-str k)
               " but set-operating-frame example " n " replies " (pr-str example))))))
