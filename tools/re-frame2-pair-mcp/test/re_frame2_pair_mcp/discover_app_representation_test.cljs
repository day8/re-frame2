(ns re-frame2-pair-mcp.discover-app-representation-test
  "Pin the id-representation contract of discover-app's output.

  A discover-app response must use ONE id form throughout a payload —
  never running-builds / frames as short names (`step-deck`) in one place
  and full keywords (`:examples/step-deck`) in the adjacent hint string,
  which would force a first-time caller to guess which field used which
  form.

  ## The contract these tests pin

  The canonical, agent-read representation is the EDN `:content` text
  slot — `wire/ok-text` renders it with `pr-str`. In that slot every
  build/frame id is a FULL KEYWORD (`:rf/default`, `:examples/step-deck`)
  and the embedded `:note` / `:hint` strings use the SAME colon form, so
  the payload reads with one representation throughout.

  The sibling `:structuredContent` slot is the documented lossy JSON
  projection (`clj->js`) where keywords lose their leading colon — that
  is a fixed cross-MCP convention (`wire/ok-text` docstring; story-mcp
  shares it). Callers that want the canonical id form read the EDN text;
  the JSON view is the SDK-friendly fallback. These tests therefore
  assert against the EDN text — the slot that carries the single
  representation."
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.discover-app :as discover-app]
            [re-frame2-pair-mcp.test-utils :as tu]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{} :resolved-build-id nil)
    conn))

(defn- prime! [conn build-id]
  (swap! conn update :probed-builds (fnil conj #{}) build-id))

(def ^:private multi-frame-health
  "An ambiguous-frame health payload — exercises the `:note` string
  branch that embeds the frame ids, where representation consistency
  matters most."
  {:ok?                        true
   :debug-enabled?             true
   :coord-annotation-enabled?  true
   :frames                     [:rf/default :step-deck :rf/xray]
   :ambiguous-frame?           true})

(deftest discover-app-frames-are-full-keywords-in-canonical-text
  ;; The :frames value and the :note string that re-states them must
  ;; BOTH render as full keywords in the EDN text — no short-name leak.
  (async done
    (let [conn (fresh-conn)
          _    (prime! conn :examples/step-deck)
          args (tu/args->js {:build "examples/step-deck"})]
      (-> (tu/with-stubbed-eval! multi-frame-health
            (fn [] (discover-app/discover-app conn args)))
          (.then
            (fn [result]
              (let [text (tu/extract-text result)
                    edn  (tu/extract-edn result)]
                (is (= [:rf/default :step-deck :rf/xray] (:frames edn))
                    ":frames value is a vector of full keywords")
                ;; The note re-states the frames; the colon form must
                ;; appear there too so value + prose agree.
                (is (str/includes? (:note edn) ":rf/default")
                    ":note embeds the colon form, matching the value")
                (is (str/includes? (:note edn) ":step-deck"))
                ;; And no short-name leak anywhere in the canonical text.
                (is (not (str/includes? text "[default step-deck"))
                    "no colon-stripped short-name list in the canonical EDN text"))
              (done)))))))

(deftest discover-app-build-id-is-a-full-keyword-in-canonical-text
  ;; The resolved :build-id echoed on success is a keyword, rendered
  ;; with its colon in the EDN text.
  (async done
    (let [conn (fresh-conn)
          _    (prime! conn :examples/step-deck)
          args (tu/args->js {:build "examples/step-deck"})
          healthy {:ok?                        true
                   :debug-enabled?             true
                   :coord-annotation-enabled?  true
                   :frames                     [:rf/default]
                   :ambiguous-frame?           false}]
      (-> (tu/with-stubbed-eval! healthy
            (fn [] (discover-app/discover-app conn args)))
          (.then
            (fn [result]
              (let [edn (tu/extract-edn result)]
                (is (= :examples/step-deck (:build-id edn))
                    ":build-id is the full keyword")
                (is (str/includes? (tu/extract-text result) ":build-id :examples/step-deck")
                    "rendered with its colon in the canonical text"))
              (done)))))))
