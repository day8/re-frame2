(ns re-frame2-pair-mcp.descriptor-egress-wording-test
  "The tool catalogue is how an AI host learns each tool's privacy posture,
  so its prose must match EP-0015: a direct read always crosses
  `re-frame.core/project-egress`, a bare `elision false` lets only large
  content through on every launch, declared-sensitive slots stay redacted
  unless the launch-gated `include-sensitive` opt-in is given, the
  runtime-db `:machines` slice is redacted by default, and a read of the
  rendered DOM is fail-open, because classification never changes what a
  view renders. No machine-readable slot carries this posture, so the gate
  is phrase-level."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.tools.registry :as registry]))

(def ^:private description-by-name
  (into {} (map (juxt :name :description)) registry/tool-descriptors))

(def ^:private knob-description
  (get-in (into {} (map (juxt :name identity)) registry/tool-descriptors)
          ["get-path" :inputSchema :properties :elision :description]))

(def ^:private forbidden-phrases
  "Claims the implementation does not honour."
  ["bypass the walk and receive the raw value"
   "to bypass elision and receive the raw value"
   "passes through unchanged"                    ; :machines is redacted by default
   "redact-interceptor"                          ; not part of the public API
   ;; Classification redacts at observation boundaries, never in the render.
   "redacted before it renders"
   "redact it at the source"
   "so classify its app-db path"])

(deftest dom-reads-say-the-app-must-keep-content-out-of-the-render
  ;; Spec 015: real values flow through the app unchanged, so classifying an
  ;; app-db path cannot redact what a view renders, and these reads are
  ;; fail-open over the rendered DOM.
  (doseq [tool ["read-dom" "read-ui" "record"]
          needle ["FAIL-OPEN" "the app must omit or mask content it does not want rendered"]]
    (is (str/includes? (description-by-name tool) needle)
        (str tool " must say " needle))))

(deftest no-direct-read-descriptor-advertises-a-raw-bypass
  (doseq [[tool desc] description-by-name
          phrase forbidden-phrases]
    (is (not (str/includes? desc phrase))
        (str tool "'s description must not claim '" phrase "'"))))

(deftest amended-descriptors-state-the-ep-0015-posture
  (doseq [tool ["get-path" "snapshot"]]
    (is (str/includes? (description-by-name tool) ":rf.egress/off-box-tool")
        (str tool " must name the default off-box-tool egress profile")))
  (testing "snapshot describes :machines as redacted and fail-closed"
    (let [desc (description-by-name "snapshot")]
      (doseq [needle [":machines" ":rf/redacted" "`reg-machine`"]]
        (is (str/includes? desc needle) needle))
      (is (or (str/includes? desc "FAILS CLOSED")
              (str/includes? desc "fails closed"))))))

(deftest size-override-is-described-as-ungated
  ;; The size override needs no launch flag; only the sensitive opt-in does.
  (doseq [tool ["get-path" "snapshot"]
          needle ["honoured on every launch" "`include-sensitive true`" "--allow-sensitive-reads"]]
    (is (str/includes? (description-by-name tool) needle)
        (str tool " must say " needle)))
  (is (str/includes? knob-description "honoured on every launch")))

(deftest get-path-says-a-machines-marker-path-reads-the-gated-runtime-db
  ;; A snapshot `:machines` marker's `:path` is a runtime-db coordinate, so
  ;; an AI following it needs to know get-path reads it, and under what gate.
  (doseq [needle [":rf.runtime/* key" ":machines marker's :path" "runtime-db"
                  "reads :rf/redacted unless `include-sensitive true`"]]
    (is (str/includes? (description-by-name "get-path") needle) needle)))

(deftest marker-path-is-described-as-live
  ;; Following a marker out of a past epoch with get-path returns TODAY's value.
  (doseq [tool ["get-path" "trace-window" "watch-epochs"]]
    (is (str/includes? (description-by-name tool) "CURRENT app-db") tool))
  (is (str/includes? knob-description "CURRENT app-db"))
  (testing "get-path carries the eval-cljs recipe for a past epoch's value"
    (doseq [needle ["re-frame2-pair.runtime/epoch-by-id" ":db-before" "never the `:handle` vector"]]
      (is (str/includes? (description-by-name "get-path") needle) needle))))
