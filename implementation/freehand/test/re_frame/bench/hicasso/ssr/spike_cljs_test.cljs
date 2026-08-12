(ns re-frame.bench.hicasso.ssr.spike-cljs-test
  "THE SSR SPIKE WITNESS — X1(a): DETERMINISM (rf2-2rtt6.87).

  Evidence for the P2 sitting. **No verdict is published here and none is
  implied**; the sitting's decider is the operator.

  ## The row

  X1(a) — a double server render of the seeded dogfood snapshot yields
  BYTE-IDENTICAL HTML, stated as a SHA-256 over the whole document.

  It runs under `npm run test:cljs` — the consolidated `:node-test`
  build — for the reason `ssr/entry_cljs_test` gives: `react-dom/server`
  resolves there through Node's own conditional export
  (`server.node.js`), `renderToString` wants no DOM, and a PR gate is a
  better home for a determinism claim than a bench driver that can exit 0
  while emitting warnings. The digest is PRINTED as well as asserted, so
  the studio page carries a number a later run can be compared to.

  ## Why the digest is over the BYTES and not over `:rf/render-hash`

  Because `:rf/render-hash` cannot do this job here, and that is measured
  rather than suspected. Spec 011's hydration-mismatch instrument hashes
  the RENDER TREE, and Hicasso's root hiccup is one vector whose head is
  a function — `canonical-edn` renders every function identically, so the
  dogfood screen and the ~1,200-element Conduit feed page **both hash
  `83b865f8`**. Leaning a determinism row on it would be leaning on a
  constant.

  rf2-2rtt6.91 has since read Spec 011's own answer for this tier — an
  adoption-tier root carries no hash at either end — and the entry emits
  none, so there is now nothing on the wire to lean on either. The
  measurement stands unchanged and
  [[the-byte-digest-separates-the-two-pages-render-hash-cannot]] still
  takes that exact pair, now over `ssr-hash/render-tree-hash` directly:
  the degeneracy is a fact about hashing an unresolved root form, not
  about this entry, so removing the emission does not retire it. It is
  the reason this row is entitled to say `deterministic` where the
  framework's structural hash could not.

  ## What each row can lie about, and what stops it

  A byte-identity row's failure mode is VACUITY: two renders of nothing
  are also identical, and a digest that is a constant agrees with itself
  for ever. Both are closed by mutation —
  [[a-different-snapshot-renders-a-different-document]] moves ONE input
  and requires the digest to move, and the row above requires two
  different pages to take two different digests."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            ;; rf2-2rtt6.121 — for `utf8-bytes` alone. The lane is already on
            ;; the `:node-test` classpath (its `*_dom_cljs_test` siblings
            ;; require it and `cljs-test$` matches them too), so this adds no
            ;; dependency the build did not already carry.
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.ssr.entry :as entry]
            [re-frame.bench.hicasso.ssr.fixtures :as fixtures]
            ;; rf2-2rtt6.91 — the entry ships no render hash, so the control
            ;; row takes the hash it is a control AGAINST directly.
            [re-frame.ssr.hash :as ssr-hash]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     :async?  true
     :init-fn (fn [] (fixtures/register!))}))

;; ---------------------------------------------------------------------------
;; SHA-256
;; ---------------------------------------------------------------------------

(defn sha256-hex
  "A promise of `s`'s SHA-256, lower-case hex.

  `crypto.subtle` rather than `node:crypto`: every namespace in this lane
  is also compiled by `npm run test:hicasso-compile`, which rides
  `:hicasso-bench` — a BROWSER build — so a `node:` require here would
  fail that gate (`ssr/node.cljs` states the same rule). The Web Crypto
  API is present in both runtimes and needs no module."
  [s]
  (-> (.digest (.-subtle js/crypto) "SHA-256" (.encode (js/TextEncoder.) s))
      (.then (fn [buf]
               (->> (js/Uint8Array. buf)
                    (array-seq)
                    (map (fn [b] (.padStart (.toString b 16) 2 "0")))
                    (str/join))))))

(defn- report! [label m]
  (println (str ";; HICASSO SSR SPIKE " label " " (pr-str m)))
  m)

;; ---------------------------------------------------------------------------
;; X1(a)
;; ---------------------------------------------------------------------------

(def ^:private dogfood-row-id "dogfood-snapshot")

(deftest x1a-the-seeded-dogfood-snapshot-renders-byte-identical-documents
  (async done
    (let [row (fixtures/row dogfood-row-id)
          {:keys [identical? differs-at] a :first b :second} (entry/render-twice row)]
      (is (some? row) "the corpus still carries the row this witness renders")
      (is identical?
           (str "the same bundle and the same snapshot rendered two DIFFERENT "
                "documents" (when differs-at
                              (str " — first difference at character " differs-at))))
      ;; The two renders took two DIFFERENT per-request gensym frames, so
      ;; byte-identity is also the standing proof that the per-request id
      ;; is invisible on the wire. Asserted, not assumed: identical
      ;; documents from the SAME frame id would prove nothing about it.
      (is (not= (:frame-id a) (:frame-id b))
          "the two renders took two different per-request frames")
      (-> (js/Promise.all #js [(sha256-hex (:document a)) (sha256-hex (:document b))])
          (.then
            (fn [[ha hb]]
              (is (= ha hb) "and their SHA-256 digests agree")
              (is (= 64 (count ha)) "a full SHA-256, not a truncated one")
              (report! "X1a"
                       {:row          dogfood-row-id
                        :sha256       ha
                        ;; `lane/utf8-bytes` and not `count` (rf2-2rtt6.121).
                        ;; THIS FIGURE MOVES, and it is the one published
                        ;; figure in this repair that does: measured
                        ;; 2026-08-06, the same document is 3,042 code units
                        ;; and 3,060 bytes, because every corpus title carries
                        ;; an em dash. The +18 is exactly the gap
                        ;; `rf2-2rtt6.114` derived independently on this same
                        ;; document from the other side of the wire —
                        ;; `Buffer.byteLength` in the bake, cross-checked
                        ;; against `fs.statSync` of the file it wrote — so two
                        ;; unrelated instruments agree on the size of the
                        ;; error. The DOCUMENT itself has since shrunk (that
                        ;; bead saw 3,101/3,119), which is corpus drift and
                        ;; nothing to do with the ruler.
                        :bytes        (lane/utf8-bytes (:document a))
                        ;; rf2-2rtt6.91 — the published column, taken where
                        ;; the fact lives now that the entry emits none.
                        :render-hash  (str (ssr-hash/render-tree-hash
                                             (:hiccup (fixtures/row dogfood-row-id))))})))
          ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs the
          ;; whole remainder of the run synchronously, so a `.catch` downstream
          ;; of it would claim a later namespace's throw as this row's and fire
          ;; `done` a second time.
          (.catch (fn [e] (is false (str "X1(a) threw: " e)) nil))
          (.then (fn [_] (done)))))))

(deftest a-different-snapshot-renders-a-different-document
  (testing "**the mutation proof for X1(a).** Byte-identity is a claim two
           renders of NOTHING also satisfy, and a digest that is a constant
           agrees with itself for ever. One input moves — eight seeded
           to-dos become nine — and the digest must move with it, or the
           row above is measuring the renderer's silence rather than its
           determinism"
    (async done
      (let [row (fixtures/row dogfood-row-id)
            eight (:document (entry/render row))
            nine  (:document (entry/render (assoc row :snapshot (dogfood/seed-db 9))))]
        (is (not= eight nine))
        (-> (js/Promise.all #js [(sha256-hex eight) (sha256-hex nine)])
            (.then (fn [[h8 h9]]
                     (is (not= h8 h9))
                     (report! "X1a-mutation" {:seed-8 h8 :seed-9 h9})))
            ;; Reports and RELEASES, as above.
            (.catch (fn [e] (is false (str "the mutation proof threw: " e)) nil))
            (.then (fn [_] (done))))))))

(deftest the-byte-digest-separates-the-two-pages-render-hash-cannot
  (testing "**the non-vacuity control, taken against the hash this tier
           does not carry** (rf2-2rtt6.91). A structural hash over these
           two roots gives the dogfood screen and the ~1,200-element
           Conduit feed the same value, because the only tree available to
           hash is a root vector whose head is a function. The byte digest
           is over what the server actually emitted, so it separates them
           — which is why X1(a) is stated as a SHA-256 over the document
           and never as a render hash.

           Two assertions, and the order is the argument: the entry ships
           no `:rf/render-hash` at all (Spec 011 routes an adoption-tier
           root to React-native adoption instead), and the value it would
           have shipped is a constant, so its absence costs this row
           nothing"
    (async done
      (let [dog       (entry/render (fixtures/row dogfood-row-id))
            conduit   (entry/render (fixtures/row "conduit-feed"))
            dog-h     (ssr-hash/render-tree-hash (:hiccup (fixtures/row dogfood-row-id)))
            conduit-h (ssr-hash/render-tree-hash (:hiccup (fixtures/row "conduit-feed")))]
        (is (not (contains? (:payload dog) :rf/render-hash))
            "no hash on the wire for an adoption-tier root")
        (is (= dog-h conduit-h)
            "and a structural hash over these roots still cannot tell the
             two pages apart — which is why there is none")
        (-> (js/Promise.all #js [(sha256-hex (:document dog))
                                 (sha256-hex (:document conduit))])
            (.then (fn [[hd hc]]
                     (is (not= hd hc)
                         "and the byte digest does")
                     (report! "X1a-vs-render-hash"
                              {:render-hash-shared (str dog-h)
                               :render-hash-on-wire "absent"
                               :dogfood-sha256     hd
                               :conduit-sha256     hc})))
            ;; Reports and RELEASES, as above.
            (.catch (fn [e] (is false (str "the control threw: " e)) nil))
            (.then (fn [_] (done))))))))
