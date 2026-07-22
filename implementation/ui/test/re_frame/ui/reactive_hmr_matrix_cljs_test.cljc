(ns re-frame.ui.reactive-hmr-matrix-cljs-test
  "S2e (rf2-vxgfnd.11) — THE consolidated hot-reload matrix, proven headlessly
  over the REAL observation port + REAL sub-cache (plain-atom) on BOTH hosts
  (node via `test:cljs`, JVM via `clojure -M:test`). The normative source is
  03 §10 (the HMR contract): stable shells + the hook-signature hash, site
  identity, frame non-reseed, sub replacement via handle `current?` rejection of
  disposed nodes, and REPL == file-save reload (one path).

  Every cell exercises the actual substrate primitive — no proxy:

    1. HOOK-SIGNATURE DECIDES PRESERVE vs REMOUNT. Every successful
       registration advances BODY REVISION so mounted shells see the new body.
       A template edit (same hook signature) KEEPS REMOUNT GENERATION; a hook
       edit advances it so the stable shell remounts its stable inner Fiber.
       `sub`/`handle`/event sites are excluded from the signature, so adding your
       first `sub` is a same-signature edit.
    2. STALE-CELL REJECTION AT COMMIT (step 1). A live cell whose generation
       advanced past its in-flight capture is stale-rejected (`:stale`) with no
       ownership touched — the same-shell reload's mark-stale-then-re-render.
    3. SITE IDENTITY survives edits. Sites are keyed by compiler-minted lexical
       identity: a sibling site added above a handle never retargets it;
       a site whose query moves retargets (kept-check fails → release + acquire)
       and a shared node never churns.
    4. SUB REPLACEMENT via handle `current?`. A committed ViewCell handle whose
       sub is re-registered is REJECTED by `current?` (the disposed canonical
       node) and re-acquired at the next commit — the ViewCell-level extension
       of the S2a-confirmed observation-port [S2-CONFIRM] queue-alignment proof
       (`re-frame.observation-port-cljs-test`).
    5. FRAME NON-RESEED. A reloaded root re-runs preflight ENSURE, which finds
       the frame live at the same config fingerprint and does NOT re-seed —
       app-db survives the edit (consolidated here; the disposition table lives
       in `re-frame.ui.preflight-frame-wiring-cljs-test`).
    6. REPL == FILE-SAVE. The generation decision is ONE path: a REPL re-eval
       and a file-save reload carrying the same hook signature both keep the
       generation; both carrying a changed signature both bump — identical
       outcomes. (The build-authority upsert-vs-pass convergence is
       `re-frame.freehand.build-repl-hmr-convergence-jvm-test`.)"
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                  :as rf]
            [re-frame.frame                 :as frame]
            [re-frame.subs                  :as subs]
            [re-frame.substrate.observation :as obs]
            [re-frame.substrate.plain-atom  :as plain-atom]
            [re-frame.test-support          :as test-support]
            [re-frame.ui.fingerprint        :as fp]
            [re-frame.ui.frames             :as frames]
            [re-frame.ui.reactive           :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [t]
    (reactive/reset-scheduler!)
    (frames/reset-installed-plans!)
    (t)
    (reactive/reset-scheduler!)
    (frames/reset-installed-plans!)))

(def ^:private fid :rf/default)

(defn- sub-cache [] (:sub-cache (frame/frame fid)))
(defn- entry [q] (get @(sub-cache) q))
(defn- ref-count [q] (:ref-count (entry q)))
(defn- seed! [db] (frame/replace-app-db! fid db))
(defn- tk [q] [:sub fid q])
(defn- target [q] (rf/with-frame fid (obs/resolve-target {:query-v q})))

(defn- render! [cell queries]
  (rf/with-frame fid
    (reactive/with-capture cell
      (fn [] (mapv (fn [i q]
                     (reactive/sub-read [:hmr/site i] q))
                   (range) queries)))))

(defn- render+commit! [cell queries]
  (let [[_ capture] (render! cell queries)]
    (reactive/commit! cell capture))
  cell)

(defn- render-sites+commit! [cell sites]
  (let [[_ capture]
        (rf/with-frame fid
          (reactive/with-capture cell
            (fn [] (mapv (fn [[sid q]] (reactive/sub-read sid q)) sites))))]
    (reactive/commit! cell capture))
  cell)

;; ===========================================================================
;; Cell 1 — hook-signature hash decides preserve vs remount (03 §10)
;; ===========================================================================
;;
;; Real fingerprint inputs: the signature excludes `sub`/`handle`/event sites and
;; hashes only the ordered `:locals`/`:effects` plan (the S3 host-hook surfaces).
;; At S2 every defview still emits the empty-plan signature, so a REAL S2 edit is
;; always a same-signature edit — the fixtures drive the decision with the varied
;; plans the fingerprint contract already supports.

(deftest hook-signature-hash-is-stable-across-a-template-only-edit
  (testing "a template edit moves the TEMPLATE fingerprint but NOT the hook
            signature — the preserve trigger (03 §10)"
    (let [hs   (fp/hook-signature-hash {:locals [] :effects []})
          hs'  (fp/hook-signature-hash {:locals [] :effects []})
          tf1  (fp/template-fingerprint [:div "v1"])
          tf2  (fp/template-fingerprint [:div [:strong "v2"]])]
      (is (= hs hs') "identical hook plan ⇒ identical hook signature")
      (is (not= tf1 tf2) "the template edit moved the template fingerprint"))))

(deftest hook-signature-hash-moves-on-a-hook-edit
  (testing "adding/reordering a user hook site moves the signature — the remount
            trigger"
    (is (not= (fp/hook-signature-hash {:locals [] :effects []})
              (fp/hook-signature-hash {:locals ['draft] :effects []})))
    (is (not= (fp/hook-signature-hash {:locals ['a 'b] :effects []})
              (fp/hook-signature-hash {:locals ['b 'a] :effects []}))
        "hook ORDER is part of the signature (React hook-order safety)")))

(deftest same-signature-reload-advances-body-but-keeps-remount-generation
  (let [vid ::preserve-view
        hs0 (fp/hook-signature-hash {:locals [] :effects []})]
    (is (= 0 (reactive/register-view-generation! vid hs0))
        "first successful registration seeds body revision 0")
    (testing "a template-only reload advances body freshness but never the
              remount key → mounted state is preserved"
      (is (= 1 (reactive/register-view-generation! vid hs0)))
      (is (= 2 (reactive/register-view-generation! vid hs0)))
      (is (= 2 (reactive/view-generation vid)))
      (is (= 0 (reactive/view-remount-generation vid))))))

(deftest changed-signature-reload-bumps-only-the-remount-generation
  (let [vid ::remount-view
        hs0 (fp/hook-signature-hash {:locals [] :effects []})
        hs1 (fp/hook-signature-hash {:locals ['draft] :effects []})
        hs2 (fp/hook-signature-hash {:locals ['draft] :effects ['sync]})]
    (is (= 0 (reactive/register-view-generation! vid hs0)))
    (testing "every registration advances body revision; only hook edits bump
              the remount key"
      (is (= 1 (reactive/register-view-generation! vid hs1)))
      (is (= 1 (reactive/view-generation vid)))
      (is (= 1 (reactive/view-remount-generation vid)))
      (is (= 2 (reactive/register-view-generation! vid hs2))
          "body revision advances once per successful publication")
      (is (= 2 (reactive/view-remount-generation vid)))
      (testing "reverting to a prior signature is itself an incompatibility"
        (is (= 3 (reactive/register-view-generation! vid hs0)))
        (is (= 3 (reactive/view-remount-generation vid)))))))

(deftest freshly-mounted-cell-mints-at-the-view-generation
  (let [vid ::mint-view
        hs0 (fp/hook-signature-hash {:locals [] :effects []})
        hs1 (fp/hook-signature-hash {:locals ['x] :effects []})]
    (reactive/register-view-generation! vid hs0)
    (reactive/register-view-generation! vid hs1)  ; a changed-signature reload
    (is (= 1 (reactive/view-generation vid)))
    (is (= 1 (reactive/view-remount-generation vid)))
    (let [cell (reactive/make-cell vid (reactive/view-generation vid))]
      (is (= 1 (reactive/generation cell))
          "a cell mounted after a remount starts at the current view generation")
      (is (empty? (reactive/committed-target-keys cell))
          "the remounted shell is a FRESH cell — no committed deps (fresh state)"))))

;; ===========================================================================
;; Cell 2 — stale-capture rejection at commit (step 1): generation remount seam
;; ===========================================================================

(deftest stale-authoritative-body-revision-rejected-at-commit-step-1
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [vid  ::stale-revision-view
        hs   (fp/hook-signature-hash {:locals [] :effects []})
        _    (reactive/register-view-generation! vid hs)
        cell (render+commit! (reactive/make-cell vid 0) [[:r/a]])
        handle0 (reactive/committed-handle cell (tk [:r/a]))]
    (is (= 1 (ref-count [:r/a])) "precondition: committed at generation 0")
    (testing "registration lands after render but before layout: the slot moved
              while the cell-local revision did not"
      (let [[_ capture] (render! cell [[:r/a]])]    ; capture at generation 0
        (reactive/register-view-generation! vid hs) ; same-sig body revision 1
        (is (= 0 (reactive/generation cell))
            "mutation tooth: cell-local-only checking would accept this capture")
        (is (= :stale (reactive/commit! cell capture))
            "commit step 1 consults the authoritative slot revision")))
    (testing "no ownership was touched — the prior committed set stays installed"
      (is (identical? handle0 (reactive/committed-handle cell (tk [:r/a]))))
      (is (= 1 (ref-count [:r/a])) "no acquire, no release on a stale rejection"))
    (testing "a fresh render under the new generation commits normally"
      (reactive/advance-generation! cell (reactive/view-generation vid))
      (render+commit! cell [[:r/a]])
      (is (identical? handle0 (reactive/committed-handle cell (tk [:r/a])))
          "the same live target retains its handle across the explicit generation seam"))))

(deftest advance-generation-is-monotone
  (let [cell (reactive/make-cell ::v 2)]
    (reactive/advance-generation! cell 1)   ; lower — ignored
    (is (= 2 (reactive/generation cell)) "a lower generation never regresses a cell")
    (reactive/advance-generation! cell 5)
    (is (= 5 (reactive/generation cell)))))

(deftest same-generation-selected-capture-survives-later-abandoned-capture
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (rf/reg-sub :r/b (fn [db _] (:b db)))
  (seed! {:a 1 :b 2})
  (let [cell (render+commit! (reactive/make-cell ::v 0) [[:r/a]])
        handle-a (reactive/committed-handle cell (tk [:r/a]))
        [_ cap-a] (render! cell [[:r/a]])
        [_ cap-b] (render! cell [[:r/b]])]
    (is (= 0 (:generation cap-a) (:generation cap-b))
        "same-signature/same-generation work needs no generation fence")
    ;; Model React selecting A while the later B Fiber is abandoned. The exact
    ;; effect closure commits A; B cannot redirect the same-generation commit.
    (reactive/commit! cell cap-a)
    (is (= #{(tk [:r/a])} (reactive/committed-target-keys cell)))
    (is (identical? handle-a (reactive/committed-handle cell (tk [:r/a])))
        "same-signature A preserves the exact live handle")
    (is (nil? (entry [:r/b])) "abandoned B owns and materialises nothing")))

;; ===========================================================================
;; Cell 3 — compiler site identity survives edits (not query or position)
;; ===========================================================================

(deftest sibling-site-added-above-never-retargets-an-existing-handle
  (rf/reg-sub :r/a (fn [db _] (:a db)))
  (rf/reg-sub :r/b (fn [db _] (:b db)))
  (seed! {:a 1 :b 2})
  (let [site-a ::site-a
        site-b ::site-b
        cell   (render-sites+commit! (reactive/make-cell ::v)
                                     [[site-a [:r/a]]])
        handle-a (reactive/committed-handle cell (tk [:r/a]))]
    (testing "an edit inserts a NEW compiler site ABOVE :a; :a keeps its
              lexical identity even though its traversal position moved"
      (render-sites+commit! cell [[site-b [:r/b]] [site-a [:r/a]]])
      (is (identical? handle-a (reactive/committed-handle cell (tk [:r/a])))
          ":a keeps the SAME handle — the prepended :b did not shift its owner")
      (is (= #{(tk [:r/a]) (tk [:r/b])} (reactive/committed-target-keys cell)))
      (is (= 1 (ref-count [:r/a])) ":a never re-acquired")
      (is (= 1 (ref-count [:r/b])) ":b acquired fresh"))))

(deftest site-retargets-when-its-query-moves-anchor-loss
  (rf/reg-sub :r/p (fn [db [_ k]] (get db k)))
  (seed! {:x 10 :y 20})
  (let [cell    (render+commit! (reactive/make-cell ::v) [[:r/p :x]])
        handle-x (reactive/committed-handle cell (tk [:r/p :x]))]
    (is (= 1 (ref-count [:r/p :x])))
    (testing "the site's query moves [:r/p :x] → [:r/p :y]: a NEW target identity,
              so the kept-check fails and the site retargets (release old +
              acquire new)"
      (render+commit! cell [[:r/p :y]])
      (is (= #{(tk [:r/p :y])} (reactive/committed-target-keys cell)))
      (is (not (identical? handle-x (reactive/committed-handle cell (tk [:r/p :y])))))
      (is (nil? (entry [:r/p :x])) "the abandoned query's node disposed (zero-owner)")
      (is (= 1 (ref-count [:r/p :y])) "the new query acquired one owner"))))

;; ===========================================================================
;; Cell 4 — sub replacement via handle current? (ViewCell-level; 03 §10)
;; ===========================================================================
;; The observation-port half (one coalesced :hmr notification, current? false,
;; fresh canonical node) is S2a-CONFIRMED in re-frame.observation-port-cljs-test
;; (hmr-reregistration-notifies-former-owners-once-with-cause-hmr). Here the SAME
;; mechanism is driven through the ViewCell commit reconciler.

(deftest reregistered-sub-disposed-handle-rejected-by-current?-and-reacquired
  (rf/reg-sub :r/x (fn [db _] (:a db)))
  (seed! {:a 1})
  (let [cell   (render+commit! (reactive/make-cell ::v) [[:r/x]])
        handle0 (reactive/committed-handle cell (tk [:r/x]))
        tgt    (target [:r/x])]
    (is (= 1 (ref-count [:r/x])))
    (is (true? (obs/current? handle0 tgt)) "the live handle is current before the reload")
    (testing "an HMR sub re-registration disposes the canonical node"
      (rf/reg-sub :r/x (fn [db _] (inc (:a db))))   ; new body
      (is (false? (obs/current? handle0 tgt))
          "current? REJECTS the committed handle — its node was disposed (03 §10)"))
    (testing "the next commit re-acquires the NEW canonical node"
      (render+commit! cell [[:r/x]])
      (let [handle1 (reactive/committed-handle cell (tk [:r/x]))]
        (is (not (identical? handle0 handle1)) "a fresh handle on the new node")
        (is (true? (obs/current? handle1 tgt)))
        (is (= 1 (ref-count [:r/x])) "exactly one owner — no cell pinned the disposed node")
        (is (= {(tk [:r/x]) 2} (reactive/committed-values cell))
            "the view re-read the new body's value on the reload")))))

;; ===========================================================================
;; Cell 5 — frame payloads never reseed on reload (03 §10)
;; ===========================================================================

(deftest frame-payload-never-reseeds-on-reload
  (rf/reg-event :hmr/set (fn [_ [_ db]] {:db db}))
  (rf/reg-event :hmr/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (let [plan {:frame-id :frame/hmr
              :config {:initial-events [[:hmr/set {:n 3}]]}
              :config-fingerprint "cf1-hmraaaaaaaaaaaa"}]
    (frames/execute-frame-plans! :root/page [plan])
    (is (= 3 (:n (rf/app-db-value :frame/hmr))) ":initial-events seeded once at preflight")
    (testing "mutate durable state, then RELOAD (re-run the same plan) — no re-seed"
      (rf/dispatch-sync [:hmr/inc] {:frame :frame/hmr})
      (is (= 4 (:n (rf/app-db-value :frame/hmr))))
      (frames/execute-frame-plans! :root/page [plan])   ; the reload's preflight
      (is (= 4 (:n (rf/app-db-value :frame/hmr)))
          "the reload found the frame live at the same fingerprint — NOT re-init")
      (frames/execute-frame-plans! :root/page [plan])   ; and again
      (is (= 4 (:n (rf/app-db-value :frame/hmr)))
          "idempotent across repeated reloads — the frame keeps its app-db"))))

;; ===========================================================================
;; Cell 6 — REPL == file-save reload (one path; 03 §10, 02 §8)
;; ===========================================================================

(deftest repl-and-file-save-reload-converge-on-one-generation-decision
  ;; The generation decision (`register-view-generation!`) is the ONE seam both
  ;; the shadow file-save re-registration and a REPL re-eval feed. Same input ⇒
  ;; same outcome, so the two paths can never diverge (df9873: REPL evals upsert;
  ;; the build hook drives compile-boundary passes — but the RUNTIME
  ;; re-registration semantics are identical).
  (let [hs0 (fp/hook-signature-hash {:locals [] :effects []})
        hs1 (fp/hook-signature-hash {:locals ['n] :effects []})
        run (fn [view-id]
              (reactive/register-view-generation! view-id hs0)   ; initial reg
              {:body [(reactive/register-view-generation! view-id hs0)
                      (reactive/register-view-generation! view-id hs1)
                      (reactive/register-view-generation! view-id hs0)]
               :remount (reactive/view-remount-generation view-id)})
        file-save (run ::via-file-save)
        repl      (run ::via-repl)]
    (is (= {:body [1 2 3] :remount 2} file-save)
        "every body publishes; only the two incompatible transitions remount")
    (is (= file-save repl)
        "a REPL re-eval and a file-save reload take the IDENTICAL generation path")))
