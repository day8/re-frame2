(ns re-frame.bench.fresco.ssr.entry-cljs-test
  "Server rendering preserves payload policy, cleanup, determinism, adoption and declared host behavior."
  (:require [cljs.reader :as reader]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.fresco.arm1.dogfood-collector :as rf.bench.fresco.arm1.dogfood-collector]
            [re-frame.bench.fresco.arm1.runtime :as rf.bench.fresco.arm1.runtime]
            [re-frame.bench.fresco.front.codec :as rf.bench.fresco.front.codec]
            [re-frame.bench.fresco.front.dogfood :as rf.bench.fresco.front.dogfood]
            [re-frame.bench.fresco.front.intent :as rf.bench.fresco.front.intent]
            [re-frame.bench.fresco.ssr.entry :as rf.bench.fresco.ssr.entry]
            [re-frame.bench.fresco.ssr.fixtures :as rf.bench.fresco.ssr.fixtures]
            [re-frame.core :as rf]
            [re-frame.ssr.constants :as rf.ssr.constants]
            [re-frame.test-support :as rf.test-support])
  (:require-macros [re-frame.bench.fresco.arm1.lang :refer [defview]]))

;; UIx, the substrate `re-frame.bench.fresco.ssr.node/boot!` installs for
;; the bake and the live demo, so these renders run under the same one.
;; The render entry does not install one — a substrate is a process-level
;; decision a host makes at boot, so it belongs to the driver and to this
;; fixture.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter
     :init-fn (fn [] (rf.bench.fresco.ssr.fixtures/register!) (rf.bench.fresco.arm1.runtime/reset-runtime!))}))

(def ^:private dogfood-request
  {:hiccup   [rf.bench.fresco.arm1.dogfood-collector/screen {}]
   :snapshot (rf.bench.fresco.front.dogfood/seed-db 4)
   :payload  rf.bench.fresco.ssr.fixtures/dogfood-payload-keys})

(defn- error-id
  "The `:rf.error/id` a thunk throws, or `::none`."
  [thunk]
  (try (thunk) ::none
       (catch :default e (or (:rf.error/id (ex-data e)) ::no-id))))

(defn- html-of [row-id] (:html (rf.bench.fresco.ssr.entry/render (rf.bench.fresco.ssr.fixtures/row row-id))))

(defn- has? [html & needles] (mapv #(str/includes? html %) needles))

;; The render entry

(deftest the-existing-runtime-renders-under-renderToString
  ;; Seeded with four: the header count is a SUBSCRIPTION read, and the
  ;; keyed rows are a `for` over a subscription value realized in the render.
  (let [{:keys [html]} (rf.bench.fresco.ssr.entry/render dogfood-request)]
    (is (= [true 4] [(str/includes? html "data-remaining=\"4\"") (count (re-seq #"class=\"row\"" html))]))))

(deftest the-per-request-frame-is-destroyed
  (let [a (:frame-id (rf.bench.fresco.ssr.entry/render dogfood-request))
        b (:frame-id (rf.bench.fresco.ssr.entry/render dogfood-request))]
    (is (= [true nil true] [(keyword? a) (rf/app-db-value a) (not= a b)])
        "a per-request frame, destroyed in the finally, and a different one per request")))

(deftest a-server-render-leaves-zero-durable-registration
  ;; React never subscribes under renderToString, and only a commit installs
  ;; a cell or an edge.
  (rf.bench.fresco.ssr.entry/render dogfood-request)
  (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0}
         (select-keys (rf.bench.fresco.arm1.runtime/residue) [:cells :cell-refs :boundaries :edges]))))

;; The payload is the framework's, byte for byte

(deftest the-payload-is-the-frameworks-own
  ;; Spec 011's two always-present keys; no `:rf/frame-id`, because stamping the gensym would
  ;; be a hydration frame-id mismatch on every page; the allowlist; the
  ;; pinned script id; and an EDN round trip.
  (let [{:keys [payload payload-edn payload-script]} (rf.bench.fresco.ssr.entry/render dogfood-request)]
    (is (= [#{:rf/version :rf/app-db} true false
            (set rf.bench.fresco.ssr.fixtures/dogfood-payload-keys)
            true true payload]
           [(set (keys payload))
            (int? (:rf/version payload))
            (str/includes? payload-edn "fresco.ssr")
            (set (keys (:rf/app-db payload)))
            (str/starts-with? payload-script (str "<script id=\"" rf.ssr.constants/payload-script-id
                                                  "\" type=\"application/edn\">"))
            (str/ends-with? payload-script "</script>")
            (reader/read-string payload-edn)]))))

(deftest a-script-breakout-in-the-app-db-is-escaped-by-the-framework
  (let [{:keys [payload-script payload-edn]}
        (rf.bench.fresco.ssr.entry/render {:hiccup   [:div "x"]
                                           :snapshot {:hostile "</script><!-- pwned"}
                                           :payload  [:hostile]})]
    (is (= [false true "</script><!-- pwned"]
           [(str/includes? payload-script "</script><!--")
            (str/includes? payload-script "\\u003c/script>")
            (:hostile (:rf/app-db (reader/read-string payload-edn)))])
        "escaped in the script, and round-trips to the original value")))

(deftest the-payload-policy-is-fail-closed
  ;; No `:payload` is the framework's refusal, not a default; the whole-app-db
  ;; opt-in is explicit.
  (is (= [:rf.error/ssr-missing-payload-policy (set (keys (rf.bench.fresco.front.dogfood/seed-db 4)))]
         [(error-id #(rf.bench.fresco.ssr.entry/render (dissoc dogfood-request :payload)))
          (set (keys (:rf/app-db (:payload (rf.bench.fresco.ssr.entry/render
                                             (assoc dogfood-request :payload :rf.ssr.payload/whole-app-db))))))])))

(deftest every-corpus-row-renders-a-whole-document
  ;; Every row: markup, the doctype, the app root carrying the id the client
  ;; mounts on, the payload script, and the document's close.
  (doseq [{:keys [id] :as row} rf.bench.fresco.ssr.fixtures/corpus]
    (let [{:keys [html document]} (rf.bench.fresco.ssr.entry/render row)]
      (is (= [true true true true true]
             [(boolean (seq html))
              (str/starts-with? document "<!DOCTYPE html>")
              (str/includes? document "<div id=\"app\">")
              (str/includes? document (str "id=\"" rf.ssr.constants/payload-script-id "\""))
              (str/ends-with? document "</body></html>")])
          id))))

(deftest the-server-render-ships-no-mounting-overrides
  ;; THE REGRESSION GUARD. Presence applies its `::h/mounting` overrides to a
  ;; child at `:mounting`, while the hydrating client renders the same
  ;; children born-present. The entry opens the adoption window around
  ;; `renderToString`; remove it and the enter override reaches the bytes.
  ;; The tray rendering its children is the non-vacuity half.
  (is (= [false true true]
         (has? (html-of "presence-mounting") "toast--enter" "toast 0" "toast 1"))))

(deftest the-adoption-window-does-not-outlive-the-request
  ;; The flag is module-level, so a render that threw with it open would
  ;; leave every later request born-present. The throwing render fails AFTER
  ;; renderToString ran, so the window was genuinely open at the throw.
  (is (= [false false :rf.error/ssr-missing-payload-policy false]
         [(rf.bench.fresco.arm1.runtime/adopting?)
          (do (html-of "presence-mounting") (rf.bench.fresco.arm1.runtime/adopting?))
          (error-id #(rf.bench.fresco.ssr.entry/render (dissoc dogfood-request :payload)))
          (rf.bench.fresco.arm1.runtime/adopting?)])))

;; Determinism

(deftest the-same-request-renders-byte-identical-documents
  (is (= {}
         (into {}
               (keep (fn [row]
                       (let [{:keys [identical? differs-at]} (rf.bench.fresco.ssr.entry/render-twice row)]
                         (when-not identical? [(:id row) differs-at]))))
               rf.bench.fresco.ssr.fixtures/corpus))
      "row id -> first differing character, for every row that rendered two documents"))

;; defhost regions honour the :ssr policy server-side
;;
;; These rows read the SERVER HTML a real `(defhost … {:ssr …})` declaration
;; produces through the entry, so they are evidence about the door. The
;; nested row uses the same declarations inside a `defview` body, a
;; position no pre-walk over the entry's input could reach: the policy is
;; the element's own type, answered by the gate `mint-host!` mints.

(deftest a-host-with-no-declared-policy-renders-nothing
  (is (= [:client-only false false]
         (into [(rf.bench.fresco.front.codec/host-ssr rf.bench.fresco.ssr.fixtures/default-host)]
               (has? (html-of "defhost-ssr-policy") "CLIENT-ONLY-WIDGET" "client-widget")))))

(deftest a-host-declaring-a-fallback-renders-the-fallback
  ;; Both rows of the `for` get the fallback — a mechanism that stopped at
  ;; the seq would render only the root-level host — and the chrome around
  ;; the hosts is untouched.
  (let [html (html-of "defhost-ssr-policy")]
    (is (= [{:fallback [:span.host-fallback "loading…"]} 2 true true]
           [(rf.bench.fresco.front.codec/host-ssr rf.bench.fresco.ssr.fixtures/fallback-host)
            (count (re-seq #"loading" html))
            (str/includes? html "class=\"host-fallback\"")
            (str/includes? html "<h1>hosts</h1>")]))))

(deftest a-host-declaring-render-renders-the-component-and-its-children
  ;; The only policy under which a crossing's children reach the server
  ;; response, and a consumer below the provider reads the DECLARED context
  ;; value rather than the default.
  (is (= [:render true true true false]
         (into [(rf.bench.fresco.front.codec/host-ssr rf.bench.fresco.ssr.fixtures/render-host)]
               (has? (html-of "defhost-ssr-render")
                     "RENDER-SUBTREE" "class=\"render-subtree\""
                     "<em class=\"context-reader\">dark</em>" "<em class=\"context-reader\">unset</em>")))))

(deftest a-host-used-inside-a-defview-body-honours-its-policy
  (let [html (html-of "defhost-ssr-nested")]
    (testing "the defview body and the root above it ran on the server"
      (is (= [true true true] (has? html "class=\"nested-hosts\"" "<h2>nested</h2>" "<h1>nested hosts</h1>"))))
    (testing ":client-only, {:fallback …} (exactly one) and :render, at nested use sites"
      (is (= [false false true 1 true true]
             (-> (has? html "CLIENT-ONLY-WIDGET" "client-widget" "class=\"host-fallback\"")
                 (conj (count (re-seq #"loading" html)))
                 (into (has? html "NESTED-RENDER-SUBTREE" "<em class=\"context-reader\">dark</em>"))))))))




;; The host scope does not leak into a per-request body
;;
;; This file's fixture IS the hazard: `test-support`'s default root-binds
;; `*current-frame*` to `:rf/default`, so every render here happens inside a
;; `:rf/default` stamp. Without the refusal, `(rf/capture-frame)` in a body
;; would answer that long-lived process-wide frame while `h/frame` answers
;; the per-request one.

(def ^:private scope-probe-seen (volatile! ::unset))

(defview scope-probe
  "A body that reaches for the ambient frame three ways."
  [_]
  (vreset! scope-probe-seen
           {:ambient  (try (rf/capture-frame)
                           (catch :default e (ex-data e)))
            :composed (:frame (rf/capture-frame (rf.bench.fresco.front.intent/hframe)))
            :hframe   (rf.bench.fresco.front.intent/hframe)})
  [:p.scope-probe "probe"])

(deftest the-hosts-ambient-scope-does-not-answer-inside-a-per-request-body
  (let [{:keys [frame-id]} (rf.bench.fresco.ssr.entry/render {:hiccup  [scope-probe {}]
                                                              :payload rf.bench.fresco.ssr.fixtures/dogfood-payload-keys})
        {:keys [ambient composed hframe]} @scope-probe-seen]
    (is (= {:hframe frame-id :composed frame-id :refusal :rf.error/ambient-frame-refused
            :carried :rf/default :extent frame-id :host-frame? false}
           {:hframe      hframe
            :composed    composed
            :refusal     (:rf.error/id ambient)
            :carried     (:carried-frame ambient)
            :extent      (:extent-frame ambient)
            :host-frame? (= :rf/default frame-id)})
        "the boundary and the composed carry answer the per-request frame; the
         ambient carry refuses rather than answer the host's")))
