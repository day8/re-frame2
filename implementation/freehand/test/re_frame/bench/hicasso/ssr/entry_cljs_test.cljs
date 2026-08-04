(ns re-frame.bench.hicasso.ssr.entry-cljs-test
  "THE GATED WITNESSES FOR THE SSR NODE RENDER ENTRY (rf2-2rtt6.86).

  These run under `npm run test:cljs` — the consolidated `:node-test`
  build — which is the right home for them and not a compromise: the
  suite already runs in Node, `react-dom/server` resolves there through
  the same conditional export the bake driver gets
  (`adapters/reagent-slim`'s parity suite has required it from this build
  since rf2-6hyy), and `renderToString` wants no DOM. So the entry's
  correctness is proved by a PR gate rather than by a bench driver that
  can exit 0 while emitting warnings.

  One row per clause the bead lists, plus the two properties that would
  make the whole thing unsafe if they were not true: that a server render
  leaves ZERO durable registration behind, and that the per-request gensym
  never reaches the wire."
  (:require [cljs.reader :as reader]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.dogfood-collector :as collector]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.bench.hicasso.ssr.entry :as entry]
            [re-frame.bench.hicasso.ssr.fixtures :as fixtures]
            [re-frame.bench.hicasso.ssr.host-policy :as host-policy]
            [re-frame.core :as rf]
            [re-frame.ssr.constants :as ssr-constants]
            [re-frame.test-support :as test-support]))

;; The adapter is UIx's for the reason `arm1/runtime_cljs_test` gives: it
;; is the substrate with a real reactivity layer, and Spec 006 allows
;; exactly one per process. **The render entry does not install one** —
;; installing a substrate is a process-level decision a host makes at
;; boot, so it belongs to the driver (`ssr/node.cljs`) and to this
;; fixture, never to a per-request function.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     :init-fn (fn [] (fixtures/register!) (rt/reset-runtime!))}))

(def ^:private dogfood-request
  {:hiccup   [collector/screen {}]
   :snapshot (dogfood/seed-db 4)
   :payload  fixtures/dogfood-payload-keys})

(defn- error-id
  "The `:rf.error/id` a thunk throws, or `::none`."
  [thunk]
  (try (thunk) ::none
       (catch :default e (or (:rf.error/id (ex-data e)) ::no-id))))

;; ---------------------------------------------------------------------------
;; Clause 1 — the render entry
;; ---------------------------------------------------------------------------

(deftest the-existing-runtime-renders-under-renderToString
  (testing "a Hicasso boundary tree renders to markup under react-dom/server,
           with the frame's own state in it"
    (let [{:keys [html]} (entry/render dogfood-request)]
      (is (str/includes? html "class=\"dogfood\""))
      (is (str/includes? html "todos"))
      ;; Seeded with four; the header count is a SUBSCRIPTION read, so
      ;; this is the assertion that the cold probe answered rather than
      ;; that the markup happened to appear.
      (is (str/includes? html "data-remaining=\"4\""))
      (is (str/includes? html "4 left"))
      ;; The keyed list rendered every row — a `for` over a subscription
      ;; value, realized inside the server render.
      (is (= 4 (count (re-seq #"class=\"row\"" html))))
      ;; A controlled input's server markup carries `value` as
      ;; `defaultValue` would demand of hydration (HD-019's rider is
      ;; rf2-2rtt6.84's to witness on the hydrated path; this is only the
      ;; server half, asserted so a change to it is visible here).
      (is (str/includes? html "class=\"new-input\"")))))

(deftest the-per-request-frame-is-destroyed
  (testing "the request's frame does not outlive the request"
    (let [{:keys [frame-id]} (entry/render dogfood-request)]
      (is (keyword? frame-id))
      (is (nil? (rf/app-db-value frame-id))
          "destroy-frame! ran in the finally — a per-request frame, not a per-request leak")))

  (testing "two requests take different frames"
    (let [a (entry/render dogfood-request)
          b (entry/render dogfood-request)]
      (is (not= (:frame-id a) (:frame-id b))))))

(deftest a-server-render-leaves-zero-durable-registration
  (testing "React never subscribes under renderToString, so the ledger is untouched"
    (rt/reset-runtime!)
    (entry/render dogfood-request)
    (let [{:keys [cells cell-refs boundaries edges]} (rt/residue)]
      ;; The four numbers a clean teardown asserts. Here they are zero
      ;; without a teardown at all, because nothing ever committed: every
      ;; `sub` read went through the mutation-free cold probe, and the
      ;; only thing that installs a cell or an edge is a commit.
      (is (= 0 cells))
      (is (= 0 cell-refs))
      (is (= 0 boundaries))
      (is (= 0 edges)))))

;; ---------------------------------------------------------------------------
;; Clause 1(d) — the payload is the framework's, byte for byte
;; ---------------------------------------------------------------------------

(deftest the-payload-is-the-frameworks-own
  (let [{:keys [payload payload-edn payload-script render-hash]} (entry/render dogfood-request)]

    (testing "the three always-present keys, per Spec 011"
      (is (= #{:rf/version :rf/app-db :rf/render-hash} (set (keys payload))))
      (is (int? (:rf/version payload)))
      (is (= render-hash (:rf/render-hash payload))))

    (testing "the per-request gensym NEVER reaches the wire (rf2-lm2yzy)"
      (is (not (contains? payload :rf/frame-id))
          "an absent :rf/frame-id is the documented no-conflict shape for an
           anonymous per-request server frame; stamping the gensym would be
           :rf.error/hydration-frame-id-mismatch on every page")
      (is (not (str/includes? payload-edn "hicasso.ssr"))))

    (testing "the allowlist arm of the fail-closed payload contract"
      (is (= (set fixtures/dogfood-payload-keys) (set (keys (:rf/app-db payload))))))

    (testing "the script is the pinned id and the framework's EDN escaper"
      (is (str/starts-with? payload-script
                            (str "<script id=\"" ssr-constants/payload-script-id
                                 "\" type=\"application/edn\">")))
      (is (= "__rf_payload" ssr-constants/payload-script-id))
      (is (str/ends-with? payload-script "</script>")))

    (testing "the payload round-trips through the EDN reader"
      (is (= payload (reader/read-string payload-edn))))))

(deftest a-script-breakout-in-the-app-db-is-escaped-by-the-framework
  (testing "the escaper on the payload path is re-frame's, and it is doing work"
    (let [{:keys [payload-script payload-edn]}
          (entry/render {:hiccup   [:div "x"]
                         :snapshot {:hostile "</script><!-- pwned"}
                         :payload  [:hostile]})]
      ;; The one thing an EDN payload in a <script> may not contain.
      (is (not (str/includes? payload-script "</script><!--")))
      (is (str/includes? payload-script "\\u003c/script>"))
      ;; And it round-trips to the original value — escaped, not mangled.
      (is (= "</script><!-- pwned" (:hostile (:rf/app-db (reader/read-string payload-edn))))))))

(deftest the-payload-policy-is-fail-closed
  (testing "no :payload declared is the framework's refusal, not a default"
    (is (= :rf.error/ssr-missing-payload-policy
           (error-id #(entry/render (dissoc dogfood-request :payload))))))

  (testing "the whole-app-db opt-in is explicit and works"
    (let [{:keys [payload]} (entry/render (assoc dogfood-request
                                                 :payload :rf.ssr.payload/whole-app-db))]
      (is (= (set (keys (dogfood/seed-db 4))) (set (keys (:rf/app-db payload))))))))

(deftest the-render-hash-is-degenerate-for-an-interpreted-root
  (testing "PINNED AS A DEFECT, not asserted as behaviour: Spec 011's
           hydration-mismatch hash is over the RENDER TREE, and Hicasso's
           root hiccup is one vector whose head is a function — so two
           different screens hash the same and the instrument is fail-open.
           This row exists so the day someone repairs it, it goes red and
           they read the comment in ssr/entry.cljs rather than rediscovering
           the measurement."
    (let [dogfood (:render-hash (entry/render dogfood-request))
          conduit (:render-hash (entry/render (fixtures/row "conduit-feed")))
          markup  (:render-hash (entry/render (fixtures/row "defhost-ssr-policy")))]
      (is (= dogfood conduit)
          "the dogfood screen and the 1,200-element Conduit feed hash the same")
      ;; And the non-vacuity control: a root whose hiccup IS markup does
      ;; hash differently, so the hash function itself works and it is the
      ;; interpreted root that defeats it.
      (is (not= dogfood markup)))))

;; ---------------------------------------------------------------------------
;; Clause 2 — determinism
;; ---------------------------------------------------------------------------

(deftest the-same-request-renders-byte-identical-documents
  (testing "same bundle + same snapshot = byte-identical HTML"
    (doseq [row fixtures/corpus]
      (let [{:keys [identical? differs-at] a :first b :second} (entry/render-twice row)]
        (is identical?
            (str (:id row) " rendered two different documents"
                 (when differs-at
                   (str " — first difference at character " differs-at ": "
                        (pr-str (subs (:document a) differs-at (min (count (:document a))
                                                                    (+ differs-at 40))))
                        " vs "
                        (pr-str (subs (:document b) differs-at (min (count (:document b))
                                                                    (+ differs-at 40))))))))))))

;; ---------------------------------------------------------------------------
;; Clause 6 — defhost regions honour the :ssr policy server-side
;; ---------------------------------------------------------------------------

(deftest a-host-with-no-declared-policy-renders-nothing
  (testing "the ruled :client-only default, reached with no stamping at all"
    (is (= :client-only (host-policy/policy-of fixtures/default-host)))
    (let [{:keys [html]} (entry/render (fixtures/row "defhost-ssr-policy"))]
      (is (not (str/includes? html "CLIENT-ONLY-WIDGET"))
          "a :client-only host's component must not reach the server HTML")
      (is (not (str/includes? html "client-widget"))))))

(deftest a-host-declaring-a-fallback-renders-the-fallback
  (testing "{:fallback hiccup} — including from inside a `for`, the lazy position"
    (let [{:keys [html]} (entry/render (fixtures/row "defhost-ssr-policy"))]
      (is (str/includes? html "class=\"host-fallback\""))
      (is (= 2 (count (re-seq #"loading" html)))
          "both rows of the `for` got their fallback — a walk that stopped at
           the seq would render one (the root-level host) and miss these")
      ;; The chrome around the hosts is untouched, so the walk replaced
      ;; regions rather than pruning the tree.
      (is (str/includes? html "<h1>hosts</h1>")))))

(deftest an-unknown-policy-is-refused-rather-than-rendered
  (testing "fail-CLOSED: a policy this walk does not know never falls through
           to rendering the client's component"
    (let [bogus (codec/mint-host! "ssr-fixture/bogus" (fn [_] nil))]
      (unchecked-set bogus host-policy/policy-slot :sometimes)
      (is (= :rf.error/hicasso-unknown-ssr-policy
             (error-id #(host-policy/apply-policy [:div [bogus {}]])))))))

;; ---------------------------------------------------------------------------
;; The corpus renders at all — the bake's own precondition
;; ---------------------------------------------------------------------------

(deftest every-corpus-row-renders
  (doseq [{:keys [id why] :as row} fixtures/corpus]
    (testing (str id " — " why)
      (let [{:keys [html document payload]} (entry/render row)]
        (is (seq html) (str id " rendered empty markup"))
        (is (str/starts-with? document "<!DOCTYPE html>"))
        (is (str/includes? document "<div id=\"app\" data-rf-render-hash=")
            "the app root carries the id the client bootstrap mounts on —
             a `:or` default that never fires shipped `id=\"\"` once")
        (is (str/includes? document (str "id=\"" ssr-constants/payload-script-id "\"")))
        (is (str/ends-with? document "</body></html>"))
        (is (some? (:rf/render-hash payload)))))))
