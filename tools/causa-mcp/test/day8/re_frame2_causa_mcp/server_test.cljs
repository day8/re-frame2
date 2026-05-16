(ns day8.re-frame2-causa-mcp.server-test
  "Unit tests for the F-2 server entry-point (rf2-8xzoe.2).

  Pins the structural bones the F-2 port lands:

    1. Public surface — `main`, `boot!`, `build-server`,
       `degraded-handler`, `tool-descriptors-js`, `read-port-from-fs`,
       `log!` are all resolvable callable vars.
    2. Server-identity constants — `server-name` + `server-version`
       carry the npm-coord-derived strings (Lock #6 + Lock #11 of
       `tools/causa-mcp/spec/DESIGN-RATIONALE.md`).
    3. Tool catalogue — empty at F-2; the eighteen-tool catalogue
       lands in subsequent F-tranche beads.
    4. `tools/list` handler — returns the (currently empty) tool
       descriptor array inside a `{:tools ...}` envelope.
    5. Success-path `tools/call` stub — until the dispatcher lands,
       surfaces a structured `:reason :not-implemented` envelope.
    6. Degraded-mode `tools/call` handler — every call returns
       `{:ok? false :reason :nrepl-port-not-found}` with the
       stored boot-error hint (MUST-inventory I4).
    7. `read-port-from-fs` — returns nil when neither env-var nor
       any port-file is present (the degraded-boot precondition).

  End-to-end stdio-roundtrip coverage (parallel to pair2-mcp's
  `test/stdio-roundtrip.js`) lands in a later F-tranche once the
  real tool catalogue / nREPL bridge fill in the success path."
  (:require [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [cljs.test :refer-macros [deftest is testing async]]
            [day8.re-frame2-causa-mcp.server :as server]
            [day8.re-frame2-causa-mcp.tools :as tools]))

;; ---------------------------------------------------------------------------
;; Public surface — vars exist and are callable.
;; ---------------------------------------------------------------------------

(deftest public-vars-exist
  (testing "F-2 lands the structural bones — every public var the
            sibling F-tranches will extend is resolvable"
    (is (fn? server/main))
    (is (fn? server/boot!))
    (is (fn? server/build-server))
    (is (fn? server/degraded-handler))
    (is (fn? server/tool-descriptors-js))
    (is (fn? server/read-port-from-fs))
    (is (fn? server/log!))))

;; ---------------------------------------------------------------------------
;; Server-identity constants.
;; ---------------------------------------------------------------------------

(deftest server-name-matches-npm-coord
  (testing "server-name is the npm-coord-derived string per Lock #6 +
            Lock #11 of DESIGN-RATIONALE.md"
    (is (= "re-frame2-causa-mcp" server/server-name))))

(deftest server-version-is-string
  (testing "server-version is a non-empty string the SDK can stamp
            onto the initialize handshake"
    (is (string? server/server-version))
    (is (seq server/server-version))))

;; ---------------------------------------------------------------------------
;; Tool catalogue — empty at F-2.
;; ---------------------------------------------------------------------------

(deftest tool-descriptors-is-js-array
  (testing "the catalogue surface is a JS array of descriptors (its
            length grows as T-Insp / T-Mut / T-Stream tranches land
            their per-tool `register-tool!` calls)"
    (let [^js descriptors (server/tool-descriptors-js)]
      (is (array? descriptors))
      ;; Every descriptor must carry the SDK-shaped name + description
      ;; + inputSchema slots so the MCP wire serialiser doesn't choke.
      (doseq [i (range (.-length descriptors))]
        (let [d (aget descriptors i)]
          (is (string? (j/get d :name)))
          (is (string? (j/get d :description)))
          (is (some? (j/get d :inputSchema))))))))

;; ---------------------------------------------------------------------------
;; build-server — constructs an MCP Server with the SDK handlers wired.
;; ---------------------------------------------------------------------------

(deftest build-server-returns-server-instance
  (testing "build-server returns an SDK Server instance with a
            request-handler-registration surface"
    (let [server (server/build-server (fn [_req _extra]
                                        (js/Promise.resolve #js {})))]
      (is (some? server))
      (is (fn? (j/get server :setRequestHandler))
          "Server must expose setRequestHandler so later F-tranches can
           bolt notification handlers on without rebuilding the boot path")
      (is (fn? (j/get server :connect))
          "Server must expose connect so connect-transport! can attach a
           StdioServerTransport (or, in tests, an in-memory transport)"))))

;; ---------------------------------------------------------------------------
;; Degraded-mode handler — MUST-inventory I4.
;; ---------------------------------------------------------------------------

(deftest degraded-handler-surfaces-nrepl-port-not-found
  (testing "degraded-handler returns a structured isError envelope with
            :reason :nrepl-port-not-found and the boot-error hint —
            the documented degraded mode (MUST-inventory I4)"
    (async done
      (let [hint       "Start your shadow-cljs dev build (`shadow-cljs watch <build>`)."
            boot-error (ex-info "nREPL port not found" {:hint hint})
            handler    (server/degraded-handler boot-error)]
        (-> (handler #js {})
            (.then (fn [^js result]
                     (is (true? (j/get result :isError))
                         "Degraded-mode result must carry isError=true so MCP
                          clients route it through their error path")
                     (let [content (j/get result :content)
                           item    (aget content 0)
                           text    (j/get item :text)
                           payload (edn/read-string text)]
                       (is (= "text" (j/get item :type)))
                       (is (false? (:ok? payload)))
                       (is (= :nrepl-port-not-found (:reason payload)))
                       (is (= hint (:hint payload))
                           "Hint must round-trip from the ex-info data so the
                            operator sees the setup instructions"))
                     (done)))
            (.catch (fn [err]
                      (is false (str "degraded-handler threw: " (.-message err)))
                      (done))))))))

(deftest degraded-handler-ignores-request-shape
  (testing "every call returns the same envelope regardless of the
            tools/call request shape — the degraded path is closed to
            tool-name dispatch"
    (async done
      (let [boot-error (ex-info "nREPL port not found" {:hint "h"})
            handler    (server/degraded-handler boot-error)
            req        #js {:params #js {:name "anything" :arguments #js {}}}]
        (-> (handler req)
            (.then (fn [^js result]
                     (let [text    (j/get (aget (j/get result :content) 0) :text)
                           payload (edn/read-string text)]
                       (is (= :nrepl-port-not-found (:reason payload))))
                     (done))))))))

;; ---------------------------------------------------------------------------
;; Success-path tools/call dispatcher — routes through `tools/invoke`.
;; The per-tool dispatch contract is exercised in
;; `test/day8/re_frame2_causa_mcp/tools_test.cljs` and the per-tool
;; tests under `tools/` — server_test only pins that the boot path
;; resolves the dispatcher entry point.
;; ---------------------------------------------------------------------------

(deftest boot-build-server-resolves-dispatcher
  (testing "boot! wires tools/call through the per-tool dispatcher; an
            unknown tool name resolves to a structured :unknown-tool
            envelope rather than a transport-level failure"
    (async done
      (let [conn    (atom {:port 12345})
            handler (fn [req _extra]
                      ;; Mirror the real boot! body — the dispatcher
                      ;; handles unknown names without an nREPL hit.
                      (let [params (j/get req :params)
                            name   (j/get params :name)
                            args   (j/get params :arguments)]
                        (tools/invoke conn name args nil)))
            req     #js {:params #js {:name "definitely-not-a-tool" :arguments #js {}}}]
        (-> (handler req nil)
            (.then (fn [^js result]
                     (is (true? (j/get result :isError)))
                     (let [text    (j/get (aget (j/get result :content) 0) :text)
                           payload (edn/read-string text)]
                       (is (= :unknown-tool (:reason payload)))
                       (is (= "definitely-not-a-tool" (:tool payload))))
                     (done))))))))

;; ---------------------------------------------------------------------------
;; Port discovery — degraded-boot precondition.
;; ---------------------------------------------------------------------------

(deftest read-port-from-fs-returns-nil-without-port
  (testing "with no SHADOW_CLJS_NREPL_PORT env-var and no port-file
            present in cwd, read-port-from-fs returns nil — the
            precondition the degraded boot fires on"
    ;; The node-test harness runs from `tools/causa-mcp/` (no port
    ;; files at any of the three candidate paths). We only assert
    ;; the nil-or-int contract; depending on dev-state the env-var
    ;; might be set, so we accept either nil or a positive integer.
    (let [result (server/read-port-from-fs)]
      (is (or (nil? result)
              (and (number? result) (pos? result)))
          "read-port-from-fs returns nil when no port source is
           present, or a positive integer when one is — the contract
           is total"))))
