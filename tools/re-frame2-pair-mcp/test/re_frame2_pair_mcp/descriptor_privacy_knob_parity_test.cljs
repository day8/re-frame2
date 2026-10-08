(ns re-frame2-pair-mcp.descriptor-privacy-knob-parity-test
  "Every privacy knob a tool's handler reads must be published on its
  descriptor. Input schemas are closed (`:additionalProperties false`), so
  an unpublished knob is one a schema-aware client cannot send, and agents
  learn an incomplete privacy boundary. No registry records which knobs a
  handler reads, so the table below is curated from the handlers."
  (:require [cljs.test :refer-macros [deftest is]]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.registry :as registry]))

(def ^:private handler-consumed-knobs
  {"dispatch"           #{:include-sensitive}
   "record"             #{:include-sensitive :elision}
   "watch-until"        #{:include-sensitive :elision}
   "dispatch-dry-run"   #{:include-sensitive :elision}
   "snapshot"           #{:include-sensitive :elision}
   "get-path"           #{:include-sensitive :elision}
   "read-sub"           #{:include-sensitive :elision}
   "list-subscriptions" #{:include-sensitive :elision}})

(deftest every-consumed-knob-is-published-on-the-raw-descriptor
  (let [descriptor-by-name (into {} (map (juxt :name identity)) registry/tool-descriptors)]
    (doseq [[tool knobs] handler-consumed-knobs
            :let [schema (:inputSchema (descriptor-by-name tool))]]
      (is (false? (:additionalProperties schema))
          (str tool " must keep a closed input schema"))
      (doseq [knob knobs
              :let [prop (get-in schema [:properties knob])]]
        (is (= "boolean" (:type prop))
            (str tool " must publish " knob " as a boolean"))
        (is (string? (:description prop))
            (str tool "'s " knob " must carry a :description for the agent"))))))

(deftest published-knobs-survive-the-js-projection
  ;; `tool-descriptors-js` is what tools/list serves.
  (let [js-arr  (tools/tool-descriptors-js)
        by-name (into {} (for [i (range (alength js-arr))
                               :let [d (aget js-arr i)]]
                           [(j/get d :name) d]))]
    (doseq [[tool knobs] handler-consumed-knobs
            :let [props (some-> (get by-name tool) (j/get :inputSchema) (j/get :properties))]]
      (is (some? props) (str tool " missing from tool-descriptors-js"))
      (doseq [knob knobs]
        (is (= "boolean" (some-> props (j/get (name knob)) (j/get :type)))
            (str tool "'s " (name knob) " must surface as a boolean on tools/list"))))))
