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
            [re-frame.bench.hicasso.front.intent :as intent]
            [re-frame.bench.hicasso.ssr.entry :as entry]
            [re-frame.bench.hicasso.ssr.fixtures :as fixtures]
            [re-frame.core :as rf]
            [re-frame.ssr.constants :as ssr-constants]
            ;; rf2-2rtt6.91 — the entry no longer computes a render hash, so
            ;; the row that keeps the measurement live takes it DIRECTLY.
            [re-frame.ssr.hash :as ssr-hash]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

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
  (let [{:keys [payload payload-edn payload-script]} (entry/render dogfood-request)]

    (testing "the two always-present keys, per Spec 011 (rf2-2rtt6.91 — an
             adoption-tier root carries no `:rf/render-hash`, and the
             schema marks the slot `{:optional true}` for exactly this)"
      (is (= #{:rf/version :rf/app-db} (set (keys payload))))
      (is (int? (:rf/version payload))))

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

(deftest the-interpreted-root-ships-no-render-hash
  (testing "rf2-2rtt6.91, and it is Spec 011's own answer rather than a
           concession. §Hydration-mismatch detection tiers detection by
           RENDER-TREE REPRESENTATION: the hash channel is the hiccup
           tier's, and a root that reaches React as an element — a compiled
           root on the donor substrate, a native UIx root, a Freehand root
           — verifies by React-native adoption and `deliberately carries
           no such hash`. This entry is
           that tier, so the key is ABSENT from the payload and the marker
           is absent from the document.

           This row replaces `the-render-hash-is-degenerate-for-an-
           interpreted-root`, which pinned the defect: the entry used to
           hash the root hiccup as handed in, and since that form is
           `[<minted head> {props}]` and `canonical-edn` renders every fn
           as `#fn[]`, two different screens took the same value."
    (let [{:keys [payload document]} (entry/render dogfood-request)]
      (is (not (contains? payload :rf/render-hash))
          "ABSENT, not nil — `:rf/render-hash` is `{:optional true} :string`
           in Spec-Schemas, so a nil-valued key is not a legal spelling of
           absence, and `build-payload` omits it on a nil hash")
      (is (not (str/includes? document "data-rf-render-hash"))
          "and no root marker either — the two ends of one channel"))))

(deftest the-hash-this-root-would-have-had-is-a-constant
  (testing "THE MEASUREMENT THAT SETTLED IT, kept live so the removal above
           cannot decay into folklore. It is a fact about
           `render-tree-hash` over an unresolved root form, not about the
           entry — so it is taken directly, and it goes on being taken
           after the entry stopped emitting.

           A degenerate value is worse than an absent one: an absent value
           cannot be mistaken for evidence, while a constant one is a
           fail-open gate wearing the shape of a check."
    (let [hash-of  #(ssr-hash/render-tree-hash (:hiccup (fixtures/row %)))
          dogfood  (ssr-hash/render-tree-hash (:hiccup dogfood-request))
          conduit  (hash-of "conduit-feed")
          markup   (hash-of "defhost-ssr-policy")]
      (is (= dogfood conduit)
          "the dogfood screen and the 1,200-element Conduit feed would have
           taken the same hash")
      (is (= "83b865f8" dogfood)
          "and it is the published value — the canonical EDN of every
           `[<fn> {}]` root, which is the whole of what the hash could see")
      ;; The non-vacuity control: a root whose hiccup IS markup hashes
      ;; differently, so the hash function itself works and it is the
      ;; interpreted root that defeats it.
      (is (not= dogfood markup)))))

(deftest the-server-render-ships-no-mounting-overrides
  (testing "THE REGRESSION GUARD (rf2-2rtt6.94), and the inversion of the row
           that measured the defect. Presence starts a child at `:mounting`
           and applies its `::h/mounting` overrides while it is there, so a
           server render with no adoption window open emits the ENTER
           appearance — and the hydrating client's first pass renders those
           same children `:present` (born-present, rf2-2rtt6.84), which is a
           hydration mismatch on every presence-managed node. The entry now
           opens the window around `renderToString`, so the server's bytes
           are born-present too and the two halves agree by construction.
           This row goes RED if that window is ever removed, narrowed, or
           closed before the render."
    (let [{:keys [html]} (entry/render (fixtures/row "presence-mounting"))]
      (is (not (str/includes? html "toast--enter"))
          "the server's bytes carry NO enter override — remove
           `rt/open-adoption-window!` from ssr/entry.cljs and this is the
           assertion that goes red")
      (is (zero? (count (re-seq #"toast--enter" html)))
          "zero occurrences, where the defect shipped one per child")
      ;; The non-vacuity control, kept verbatim from the measuring row: the
      ;; tray DID render its children, so the assertion above is about the
      ;; override being absent and not about an empty tray.
      (is (str/includes? html "toast 0"))
      (is (str/includes? html "toast 1")))))

(deftest the-adoption-window-does-not-outlive-the-request
  (testing "shut going in — the window belongs to a render, not to the process"
    (is (false? (rt/adopting?))))

  (testing "and shut again on the way out of a render that RETURNED"
    (entry/render (fixtures/row "presence-mounting"))
    (is (false? (rt/adopting?))))

  (testing "and on the way out of one that THREW, which is why the close is
           in the `finally`. The flag is module-level, so a render that threw
           with it still open would leave every LATER request in this process
           born-present — the failure `close-adoption-window!`'s own
           docstring names."
    (is (= :rf.error/ssr-missing-payload-policy
           (error-id #(entry/render (dissoc dogfood-request :payload))))
        "the render really did throw, and it threw AFTER renderToString had
         run — so the window was genuinely open at the moment of the throw;
         a row where nothing threw would prove nothing")
    (is (false? (rt/adopting?))
        "the finally shut it anyway")))

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
;;
;; ONE MECHANISM, NOT TWO (rf2-2rtt6.92). These rows read the SERVER HTML
;; a real `(defhost … {:ssr …})` declaration produces through the entry,
;; so they are evidence about the door rather than about whichever
;; internal honours it. That was not true when they were written: the
;; fallback row stamped the policy slot onto a minted head by hand, which
;; proves a reader and never the declaration. `ssr/fixtures` now writes
;; both hosts the way an author writes them.
;;
;; The entry's pre-walk (`ssr.host-policy`) is retired. It could only
;; reach the hiccup the entry was HANDED, which is why the third row
;; below exists — the same two declarations at a use site inside a
;; `defview` body, where no such walk can see them.
;; ---------------------------------------------------------------------------

(deftest a-host-with-no-declared-policy-renders-nothing
  (testing "the ruled :client-only default, taken from the door — the
           declaration writes no :ssr at all"
    (is (= :client-only (codec/host-ssr fixtures/default-host)))
    (let [{:keys [html]} (entry/render (fixtures/row "defhost-ssr-policy"))]
      (is (not (str/includes? html "CLIENT-ONLY-WIDGET"))
          "a :client-only host's component must not reach the server HTML")
      (is (not (str/includes? html "client-widget"))))))

(deftest a-host-declaring-a-fallback-renders-the-fallback
  (testing "{:fallback hiccup} — including from inside a `for`, the lazy position"
    (is (= {:fallback [:span.host-fallback "loading…"]}
           (codec/host-ssr fixtures/fallback-host))
        "and the markup below is that declaration's, read back as data")
    (let [{:keys [html]} (entry/render (fixtures/row "defhost-ssr-policy"))]
      (is (str/includes? html "class=\"host-fallback\""))
      (is (= 2 (count (re-seq #"loading" html)))
          "both rows of the `for` got their fallback — a mechanism that
           stopped at the seq would render one (the root-level host) and
           miss these")
      ;; The chrome around the hosts is untouched, so the policy replaced
      ;; regions rather than pruning the tree.
      (is (str/includes? html "<h1>hosts</h1>")))))

(deftest a-host-declaring-render-renders-the-component-and-its-children
  (testing ":ssr :render (rf2-l0wfx) — the third policy, through the same
           entry as the other two. It is the ONLY one under which a
           crossing's children reach the server response at all: under a
           gate the unadopted arm returns something that is not the
           component, so a transparent wrapper such as a context provider
           takes its whole subtree out of the HTML with it"
    (is (= :render (codec/host-ssr fixtures/render-host))
        "read back from the declaration, like every other policy")
    (let [{:keys [html]} (entry/render (fixtures/row "defhost-ssr-render"))]
      (is (str/includes? html "RENDER-SUBTREE")
          "the crossing's CHILDREN are in the server HTML")
      (is (str/includes? html "class=\"render-subtree\"")
          "as markup, not as stray text")
      (is (str/includes? html "<em class=\"context-reader\">dark</em>")
          "and a consumer below the provider read the DECLARED context
           value — the property that separates :render from the rejected
           :children, which would have emitted the context DEFAULT")
      (is (not (str/includes? html "<em class=\"context-reader\">unset</em>"))
          "so the default is nowhere in the bytes"))))

(defn- walk-reachable-host-heads
  "Every minted host head reachable from `form` through vectors and seqs
  — which is exactly what the retired `ssr.host-policy/apply-policy`
  could see, reproduced in six lines so the retirement's load-bearing
  claim is a CHECK rather than a paragraph. Used by the row below."
  [form]
  (cond
    (vector? form) (let [head (nth form 0 nil)]
                     (if (codec/host-head? head)
                       [head]
                       (into [] (mapcat walk-reachable-host-heads) form)))
    (seq? form)    (into [] (mapcat walk-reachable-host-heads) form)
    :else          []))

(deftest a-pre-walk-over-the-handed-in-form-cannot-see-a-nested-host
  (testing "WHY THE WALK IS GONE RATHER THAN KEPT BESIDE THE GATE
           (rf2-2rtt6.92). A walk can only reach the tree it is handed, and
           this is that reach, measured. Keep this row if a server-side
           pre-walk is ever proposed again: it is the whole argument in two
           numbers."
    (is (= 3 (count (walk-reachable-host-heads fixtures/host-screen)))
        "the CONTROL — the handed-in row's three host uses are visible to a
         walk, so the measurement below is about position and not about a
         walk that finds nothing anywhere")
    (is (= 0 (count (walk-reachable-host-heads fixtures/nested-host-screen)))
        "and the nested row's are INVISIBLE to it: those elements do not
         exist until the boundary body runs inside `renderToString`. A walk
         kept alive beside the gate would therefore be a second mechanism
         that covers a strict subset of the first")))

(deftest a-host-used-inside-a-defview-body-honours-its-policy
  (testing "THE POSITION NO PRE-WALK COULD REACH (rf2-2rtt6.92). Both hosts
           are used inside a boundary body, so their elements do not exist
           when this entry is handed its hiccup — that body runs inside
           `renderToString` and the codec's crossing creates them there. The
           policy holds anyway, because it is the element's own TYPE: the
           gate `mint-host!` mints answers `false` from its server snapshot.
           A `ssr.host-policy/apply-policy`-shaped walk over the handed-in
           form renders this row's `:client-only` host's component into the
           HTML, which is the failure this row names."
    (let [{:keys [html]} (entry/render (fixtures/row "defhost-ssr-nested"))]
      ;; Non-vacuity first: the boundary body really did run, and its
      ;; native chrome is in the markup — so an absent host region is an
      ;; absent host region and not an absent page.
      (is (str/includes? html "class=\"nested-hosts\"")
          "the defview body ran on the server")
      (is (str/includes? html "<h2>nested</h2>"))
      (is (str/includes? html "<h1>nested hosts</h1>")
          "and so did the ordinary root above it")

      (testing ":client-only, at a nested use site"
        (is (not (str/includes? html "CLIENT-ONLY-WIDGET"))
            "the foreign component's markup must not reach the server HTML
             from inside a body either")
        (is (not (str/includes? html "client-widget"))))

      (testing "{:fallback …}, at a nested use site"
        (is (str/includes? html "class=\"host-fallback\"")
            "the declared placeholder is what the server wrote there")
        (is (= 1 (count (re-seq #"loading" html)))
            "exactly one — this row has one fallback host, so a count is a
             real assertion and not a presence check in disguise"))

      (testing ":render, at a nested use site (rf2-l0wfx)"
        (is (str/includes? html "NESTED-RENDER-SUBTREE")
            "the crossing's children reached the server response from
             inside a boundary body too")
        (is (str/includes? html "<em class=\"context-reader\">dark</em>")
            "with the declared context value, which is a claim about
             React's own server renderer and not about this codec")))))

;; ---------------------------------------------------------------------------
;; The corpus renders at all — the bake's own precondition
;; ---------------------------------------------------------------------------

(deftest every-corpus-row-renders
  (doseq [{:keys [id why] :as row} fixtures/corpus]
    (testing (str id " — " why)
      (let [{:keys [html document payload]} (entry/render row)]
        (is (seq html) (str id " rendered empty markup"))
        (is (str/starts-with? document "<!DOCTYPE html>"))
        (is (str/includes? document "<div id=\"app\">")
            "the app root carries the id the client bootstrap mounts on —
             a `:or` default that never fires shipped `id=\"\"` once")
        (is (str/includes? document (str "id=\"" ssr-constants/payload-script-id "\"")))
        (is (str/ends-with? document "</body></html>"))
        ;; rf2-2rtt6.91 — EVERY row, not just the two the exclusion row
        ;; names: an adoption-tier root carries no hash at either end.
        (is (not (str/includes? document "data-rf-render-hash"))
            (str id " stamped a render-hash marker on an adoption-tier root"))
        (is (not (contains? payload :rf/render-hash))
            (str id " shipped :rf/render-hash in an adoption-tier payload"))))))

;; ---------------------------------------------------------------------------
;; The host scope does not leak into a per-request body (rf2-nqj22)
;; ---------------------------------------------------------------------------
;;
;; THIS FILE IS WHERE THE DEFECT WAS FOUND, which is the only reason the row
;; lives here rather than beside its siblings in
;; `arm1/ambient_refusal_cljs_test`. `test-support`'s `:ambient-frame` default
;; root-binds `*current-frame*` to `:rf/default`, and the fixture above takes
;; that default — so every witness in this file renders its per-request frame
;; inside a `:rf/default` stamp. Measured on the tree before the fix: a
;; `(rf/capture-frame)` in a body under `renderToString` answered
;; `:rf/default`, while `h/frame` in the same body answered the per-request
;; frame the markup was actually built from. An SSR host is the worst place
;; for that: the wrong frame is a long-lived process-wide one, and what it
;; would carry away is a closure that outlives the request.

(def ^:private scope-probe-seen (volatile! ::unset))

(defview scope-probe
  "A body that reaches for the ambient frame three ways, so the row can say
  which of them the host's outer scope reached."
  [_]
  (vreset! scope-probe-seen
           {:ambient  (try (rf/capture-frame)
                           (catch :default e (ex-data e)))
            :composed (:frame (rf/capture-frame (intent/hframe)))
            :hframe   (intent/hframe)})
  [:p.scope-probe "probe"])

(deftest the-hosts-ambient-scope-does-not-answer-inside-a-per-request-body
  (testing "the render entry mints a per-request frame and renders under it,
           inside whatever scope the host happens to have established — here
           the fixture's `:rf/default`. A body that resolves ambiently must
           not silently answer the host's frame"
    (is (= :rf/default frame/*current-frame*)
        "precondition: the fixture's ambient scope IS live, so a green row
         below is the refusal working rather than nothing to refuse")
    (let [{:keys [frame-id html]} (entry/render {:hiccup  [scope-probe {}]
                                                 :payload fixtures/dogfood-payload-keys})
          {:keys [ambient composed hframe]} @scope-probe-seen]
      (is (str/includes? html "scope-probe") "the body ran under renderToString")
      (is (not= :rf/default frame-id)
          "precondition: the per-request frame is not the host's")
      (is (= frame-id hframe) "the boundary renders the per-request frame")
      (is (= frame-id composed) "and the composed carry has always been immune")
      (is (= :rf.error/ambient-frame-refused (:rf.error/id ambient))
          (str "the ambient carry must refuse rather than answer the host's
                scope; got " (pr-str ambient)))
      (is (= :rf/default (:carried-frame ambient)))
      (is (= frame-id (:extent-frame ambient))))))
