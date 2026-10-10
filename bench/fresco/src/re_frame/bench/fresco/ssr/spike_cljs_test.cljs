(ns re-frame.bench.fresco.ssr.spike-cljs-test
  "THE SSR SPIKE WITNESS — X1(a): DETERMINISM. Evidence, not a verdict.

  A double server render of the seeded dogfood snapshot yields
  BYTE-IDENTICAL HTML, and its SHA-256 is printed so the studio page
  carries a number a later run can be compared to. It runs in Node, where
  `react-dom/server` resolves through Node's own conditional export.

  The digest is over the BYTES and not `:rf/render-hash`: Fresco's root
  hiccup is a vector whose head is a function, `canonical-edn` renders every
  function identically, and the dogfood screen and the Conduit feed page
  hash alike — [[the-byte-digest-separates-the-two-pages-render-hash-cannot]]
  shows it. A byte-identity row fails by VACUITY (two renders of nothing
  agree), which [[a-different-snapshot-renders-a-different-document]]
  closes by moving one input."
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.fresco.front.dogfood :as rf.bench.fresco.front.dogfood]
            [re-frame.bench.fresco.lane :as rf.bench.fresco.lane]
            [re-frame.bench.fresco.ssr.entry :as rf.bench.fresco.ssr.entry]
            [re-frame.bench.fresco.ssr.fixtures :as rf.bench.fresco.ssr.fixtures]
            [re-frame.ssr.hash :as rf.ssr.hash]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter
     :async?  true
     :init-fn (fn [] (rf.bench.fresco.ssr.fixtures/register!))}))

(defn sha256-hex
  "A promise of `s`'s SHA-256, lower-case hex. `crypto.subtle` rather than
  `node:crypto`: every lane namespace is also compiled into `:fresco-bench`,
  a BROWSER build, where a `node:` require fails (`ssr/node.cljs` states
  the rule)."
  [s]
  (-> (.digest (.-subtle js/crypto) "SHA-256" (.encode (js/TextEncoder.) s))
      (.then (fn [buf]
               (->> (js/Uint8Array. buf)
                    (array-seq)
                    (map (fn [b] (.padStart (.toString b 16) 2 "0")))
                    (str/join))))))

(def ^:private dogfood-row-id "dogfood-snapshot")

(deftest x1a-the-seeded-dogfood-snapshot-renders-byte-identical-documents
  (async done
    (let [row (rf.bench.fresco.ssr.fixtures/row dogfood-row-id)
          {:keys [identical? differs-at] a :first b :second} (rf.bench.fresco.ssr.entry/render-twice row)]
      ;; Two renders on two per-request gensym frames, so byte-identity is
      ;; also the proof that the per-request id is invisible on the wire.
      (is (= [true true] [identical? (not= (:frame-id a) (:frame-id b))])
          (str "two different frames rendered two different documents, first at " differs-at))
      (-> (sha256-hex (:document a))
          (.then
            (fn [ha]
              (println (str ";; FRESCO SSR SPIKE X1a "
                            (pr-str
                              {:row          dogfood-row-id
                               :sha256       ha
                               ;; Bytes, not `count`'s UTF-16 code units: every
                               ;; corpus title carries an em dash.
                               :bytes        (rf.bench.fresco.lane/utf8-bytes (:document a))
                               :render-hash  (str (rf.ssr.hash/render-tree-hash (:hiccup row)))})))))
          ;; Never finishes after `done`: `done` runs the rest of the run
          ;; synchronously, so a `.catch` downstream of it would claim a
          ;; later namespace's throw as this row's.
          (.catch (fn [e] (is false (str "X1(a) threw: " e)) nil))
          (.then (fn [_] (done)))))))

(deftest a-different-snapshot-renders-a-different-document
  ;; Eight seeded to-dos become nine; the document must move with them.
  (let [row (rf.bench.fresco.ssr.fixtures/row dogfood-row-id)]
    (is (not= (:document (rf.bench.fresco.ssr.entry/render row))
              (:document (rf.bench.fresco.ssr.entry/render
                           (assoc row :snapshot (rf.bench.fresco.front.dogfood/seed-db 9))))))))

(deftest the-byte-digest-separates-the-two-pages-render-hash-cannot
  ;; The entry ships no `:rf/render-hash` for an adoption-tier root, and the
  ;; value it would have shipped cannot tell the two pages apart; the
  ;; emitted documents can.
  (let [dog     (rf.bench.fresco.ssr.fixtures/row dogfood-row-id)
        conduit (rf.bench.fresco.ssr.fixtures/row "conduit-feed")
        dog-r   (rf.bench.fresco.ssr.entry/render dog)]
    (is (= [false true true]
           [(contains? (:payload dog-r) :rf/render-hash)
            (= (rf.ssr.hash/render-tree-hash (:hiccup dog)) (rf.ssr.hash/render-tree-hash (:hiccup conduit)))
            (not= (:document dog-r) (:document (rf.bench.fresco.ssr.entry/render conduit)))]))))
