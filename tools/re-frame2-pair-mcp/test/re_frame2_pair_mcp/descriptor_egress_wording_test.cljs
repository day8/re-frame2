(ns re-frame2-pair-mcp.descriptor-egress-wording-test
  "EP-0015 egress-posture wording gate for the generated MCP descriptors.

  THE CONTRACT. The pair-MCP tool catalogue is client-facing root
  metadata — an AI host reads it to learn what each tool does and what its
  privacy posture IS. The descriptions must match EP-0015's frame-owned
  egress policy on two points:

    - `get-path` / `snapshot` must NOT tell clients that `elision false`
      \"bypasses the walk and receives the raw value\". A bare
      `:elision false` does NOT bypass: the rendered form always calls
      `re-frame.core/project-egress`, and a bare `:elision false` keeps
      the `:rf.egress/off-box-tool` boundary with only a large-inclusion
      overlay — large passes, declared-sensitive slots still redact
      (rf2-kuky.88). The bypass wording would train clients/tests to treat
      a direct read as a projection bypass rather than a profile/gate-
      controlled egress.
    - `snapshot` must NOT say the `:machines` slice \"passes through
      unchanged — payload redaction there is the redact-interceptor
      interceptor's job\". `redact-interceptor` is not part of the public
      API, and the snapshot impl default-redacts the `:machines`
      runtime-db slice to `:rf/redacted` under the off-box-tool profile.

  THE GATE. No MCP direct-read tool's published description may carry the
  ungated egress claims. The forbidden phrases are pinned here; a
  descriptor edit (or a regenerated manifest) that introduces one trips a
  RED. The gate scans BOTH the raw registry descriptions (the
  `:description` source) so a drift is caught before the manifest is even
  regenerated, AND the committed `tool-descriptors.edn` once present
  (covered transitively by the `check:descriptors` drift gate, asserted
  here for defence-in-depth).

  WHY phrase-level (not a structural assert). EP-0015's posture lives in
  PROSE — the descriptions teach the boundary. There is no machine-
  readable slot that says \"this tool bypasses projection\", so the
  authoritative check is the absence of the misleading claims. The
  positive posture (`:rf.egress/off-box-tool` default + trusted-local
  opt-in threading through projection) is asserted as a presence check on
  the two amended tools."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.tools.registry :as registry]))

(def ^:private description-by-name
  (into {} (map (juxt :name :description)) registry/tool-descriptors))

;; ---------------------------------------------------------------------------
;; Forbidden EP-0015-drift phrases. Each is a claim the implementation
;; does NOT honour (an ungated raw bypass / a non-public interceptor / a
;; raw machine pass-through). A descriptor carrying one is stale metadata
;; on the client-facing root surface.
;; ---------------------------------------------------------------------------

(def ^:private forbidden-phrases
  ["bypass the walk and receive the raw value"  ; get-path/snapshot
   "to bypass elision and receive the raw value" ; elision-property knob
   "passes through unchanged"                    ; :machines slice
   "redact-interceptor"])                        ; not part of the public API

(deftest no-direct-read-descriptor-advertises-a-raw-bypass
  (testing "no published MCP description carries a retired / ungated egress claim"
    (doseq [[tool desc] description-by-name
            phrase forbidden-phrases]
      (is (not (str/includes? desc phrase))
          (str tool "'s description must not claim '" phrase "' — the landed "
               "egress impl fails closed (sensitive still redacts) and "
               "redact-interceptor is retired (EP-0015). Update the descriptor "
               "source + regenerate tool-descriptors.edn (rf2-nu98y7, rf2-e47yxs).")))))

(deftest amended-descriptors-state-the-ep-0015-posture
  (testing "get-path / snapshot describe the off-box-tool profile + trusted-local opt-in"
    (doseq [tool ["get-path" "snapshot"]
            :let [desc (description-by-name tool)]]
      (is (some? desc) (str "no descriptor registered for " tool))
      (is (str/includes? desc ":rf.egress/off-box-tool")
          (str tool " must name the default off-box-tool egress profile"))
      (is (str/includes? desc "--allow-sensitive-reads")
          (str tool " must name the trusted-local launch gate for raw reads"))))
  (testing "snapshot's :machines slice describes runtime-db redaction, not raw pass-through"
    (let [desc (description-by-name "snapshot")]
      (is (str/includes? desc ":machines")
          "snapshot must still document the :machines slice")
      (is (str/includes? desc ":rf/redacted")
          "snapshot must say :machines egresses redacted under the off-box-tool profile"))))

;; ---------------------------------------------------------------------------
;; rf2-ealv5 / rf2-3x7nj.32.4 — the size override is honoured on EVERY
;; launch; only `include-sensitive` is launch-gated. The descriptions
;; used to say `elision false` was honoured only under
;; `--allow-sensitive-reads` (snapshot: "else both forced safe"), and the
;; shared knob said nothing about a gate at all — two contradicting
;; halves of one contract, neither true after the un-gating.
;; ---------------------------------------------------------------------------

(def ^:private knob-description
  (get-in (into {} (map (juxt :name identity)) registry/tool-descriptors)
          ["get-path" :inputSchema :properties :elision :description]))

(deftest size-override-is-described-as-ungated
  (testing "get-path / snapshot say elision false works without the launch flag, and sensitive stays gated"
    (doseq [tool ["get-path" "snapshot"]
            :let [desc (description-by-name tool)]]
      (is (str/includes? desc "honoured on every launch")
          (str tool " must say the `elision false` size override needs no launch flag"))
      (is (str/includes? desc "`include-sensitive true`")
          (str tool " must still name the sensitive opt-in"))
      (is (str/includes? desc "--allow-sensitive-reads")
          (str tool " must still tie the sensitive opt-in to its launch gate"))
      (is (not (str/includes? desc "else both forced safe"))
          (str tool " must not say the launch gate forces the size override"))))
  (testing "the shared elision knob says the same"
    (is (string? knob-description))
    (is (str/includes? knob-description "honoured on every launch"))
    (is (not (str/includes? knob-description "Schemas are the only nomination path"))
        "a schema `:large?` prop is not a declaration route (EP-0025)")))

;; ---------------------------------------------------------------------------
;; rf2-3x7nj.32.5 — a marker's `:path` is a LIVE app-db locator. Following
;; a marker out of a past epoch record with `get-path` returns TODAY's
;; value, so every surface where the agent meets an epoch marker, or reads
;; how to follow one, must say the path addresses the current app-db.
;; ---------------------------------------------------------------------------

(deftest marker-path-is-described-as-live
  (doseq [tool ["get-path" "trace-window" "watch-epochs"]
          :let [desc (description-by-name tool)]]
    (is (str/includes? desc "CURRENT app-db")
        (str tool " must say a marker's :path addresses the CURRENT app-db (rf2-3x7nj.32.5)")))
  (testing "get-path carries the eval-cljs recipe for a past epoch's value"
    (let [desc (description-by-name "get-path")]
      (is (str/includes? desc "re-frame2-pair.runtime/epoch-by-id"))
      (is (str/includes? desc ":db-before")
          "the recipe names the other side of the record too")
      (is (str/includes? desc "never the `:handle` vector")
          "get-path decodes no handle, so the agent passes the :path")))
  (testing "the shared elision knob carries the live-path rule"
    (is (str/includes? knob-description "CURRENT app-db"))))
