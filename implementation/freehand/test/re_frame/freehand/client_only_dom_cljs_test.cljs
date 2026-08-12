(ns re-frame.freehand.client-only-dom-cljs-test
  "FH-ROOT-008 in a real browser — the root-scoped phase flip.

  The structural half of the row lives in
  `re-frame.freehand.client-only-cljs-test` and says what `:server` phase
  renders. This file says how a root LEAVES `:server` phase, which is the
  part only a real React root can answer.

  Three things here are deliberate and load-bearing.

  FIRST, the fixture's two client-only sites are not decoration. \"One
  root-scoped write swaps every site in a single update\" is falsifiable only
  against several sites: a per-site implementation would swap them across two
  updates and pass every assertion a single-site fixture could make.

  SECOND, the client subtrees DISAGREE with the fallbacks by construction, and
  the hydrating case asserts NO `:rf.ssr/hydration-mismatch`. That absence is
  the proof of ORDERING. A root that booted `:client`, or flipped before
  React's adoption commit, would hydrate a client-phase tree against server
  fallback markup — a text divergence React recovers from, reported through
  `onRecoverableError` — so an early flip cannot pass this file. The mismatch
  channel is proved to CAPTURE in `re-frame.freehand.root-hydration-dom-cljs-test`,
  which is what makes an absence here worth asserting.

  THIRD, adoption is proven by NODE IDENTITY. A root that discarded the
  server's fallback markup and client-rendered instead would end up showing
  exactly the same live text after the flip, and every text assertion would
  pass.

  The flip is a post-commit passive effect on React's own schedule rather than
  inside `act`, so the hydrating tests run with the act environment OFF and
  poll for it — the treatment the hydration suite already uses.

  This file rides the browser lane through its `-dom-cljs-test` namespace
  suffix. It also matches the node suites' broader regex, where it has no DOM
  to mount and says so rather than passing quietly."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing]]
            [re-frame.freehand :as v]
            [re-frame.freehand.client-only-views :as views]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.ssr.manifest :as ssr-manifest]
            [re-frame.trace.tooling :as trace-tooling]))

(def root-008 (conf/fixture :FH-ROOT-008))

(defn- reset-all! []
  (root/reset-registry!)
  (fr/reset-boundaries!))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a real React mount needs a DOM host — " why)))

(defn- act
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- server-node!
  "A container carrying the server's bytes and, beside it, the Root Manifest
  the server emits as the container's immediately following element sibling —
  the shape a hydrating page actually presents."
  [html manifest]
  (let [host (js/document.createElement "div")]
    (set! (.-innerHTML host)
          (str "<div>" html "</div>" (ssr-manifest/script-html manifest)))
    (.appendChild js/document.body host)
    (.-firstElementChild host)))

(defn- remove-node! [container]
  (some-> container .-parentElement .remove))

(defn- text [container selector]
  (some-> (.querySelector container selector) .-textContent))

(defn- listen!
  "Collect every event on the diagnostic bus whose `:operation` is `op`."
  [op sink]
  (let [k (keyword (gensym "rf.fh-client-only-"))]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= op (:operation ev)) (swap! sink conj ev))))
    k))

(defn- poll-until
  "Poll `pred` every 5ms up to ~2s, then call `k`. Bounded, so an outcome that
  never arrives lets the assertions fail honestly rather than hang."
  [pred k]
  (let [tries (atom 0)]
    (letfn [(step []
              (if (or (pred) (>= @tries 400))
                (k)
                (do (swap! tries inc) (js/setTimeout step 5))))]
      (step))))

(defn- settle
  "Give React a generous moment, then call `k`. Used where the assertion is an
  ABSENCE — waiting longer can only make an absence claim harder to satisfy."
  [k]
  (js/setTimeout k 300))

;; ===========================================================================
;; A non-hydrating mount is born :client and never flips
;; ===========================================================================

(deftest fh-root-008-a-plain-mount-renders-the-client-subtree
  (testing "Per FH-ROOT-008: `v/mount` installs no phase provider, so every
            client-only site under it reads the `:client` default and renders
            its client subtree on the first and only render. NO flip trace
            fires — there is no fallback pass to flip away from, and a root
            that reported a transition it never made would be lying about the
            one thing this trace exists to answer."
    (if-not (browser?)
      (skip! "the browser job runs the phase assertions")
      (async done
        (reset-all!)
        (let [{:keys [props] expected :text flips :flips} (:mount root-008)
              container (js/document.createElement "div")
              flip-evs  (atom [])
              root*     (atom nil)
              k         (listen! (:flip-operation (:hydrate root-008)) flip-evs)]
          (.appendChild js/document.body container)
          (-> (act #(v/mount [views/two-sites props] container))
              (.then
                (fn [mounted]
                  ;; RETAINED, not merely destructured. The timer below settles
                  ;; AFTER the mount resolved, so from here on there IS a live
                  ;; root — and the rejection handler, which is the arm a throw
                  ;; in those assertions now takes, cannot see this fulfilment
                  ;; value. Holding it here is what lets the teardown run on
                  ;; both paths (audit of #7942/#7968).
                  (reset! root* mounted)
                  ;; `settle` is a bare timer, so what it carries has to be
                  ;; AWAITED or it runs off the end of the chain. Returned as a
                  ;; promise these assertions stay UPSTREAM of the rejection
                  ;; handler below — the single `done` sits at the tail with
                  ;; nothing after it — and a throw in here reaches this row's
                  ;; own diagnostic instead of hanging the lane (rf2-o0n1).
                  (js/Promise.
                    (fn [resolve reject]
                      (settle
                        (fn []
                          (try
                            (trace-tooling/unregister-listener! k)
                            (doseq [[selector t] expected]
                              (is (= t (text container selector))
                                  (str selector " shows its CLIENT subtree on the first render")))
                            (is (= flips (count @flip-evs))
                                (str "a non-hydrating mount never flips. Saw: " (pr-str @flip-evs)))
                            (is (false? (root/hydrated? mounted))
                                "and it is not a hydration")
                            (resolve nil)
                            (catch :default e (reject e)))))))))
              ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of it would claim a later namespace's throw as this
              ;; row's and fire `done` a second time. The unregister is written
              ;; on both arms rather than hoisted: the success arm has to drop
              ;; the listener BEFORE it counts what the listener collected.
              (.catch (fn [e]
                        (trace-tooling/unregister-listener! k)
                        (is false (str "mount rejected: " e))
                        nil))
              ;; Shared teardown, hoisted onto the single trailing step: written
              ;; once, run exactly once per path. The unmount reads the RETAINED
              ;; root rather than a fulfilment value, so the row cannot leave a
              ;; live root standing into the next namespace when its own
              ;; assertions throw; `some->` no-ops on the one path that never
              ;; had a root, which is the mount rejecting.
              (.then (fn [_]
                       (some-> @root* v/unmount!)
                       (.remove container)
                       (reset-all!)
                       (done)))))))))

;; ===========================================================================
;; A hydrating root adopts the fallbacks, then flips ONCE
;; ===========================================================================

(deftest fh-root-008-a-hydrating-root-adopts-the-fallbacks-then-flips-once
  (testing "Per FH-ROOT-008: the root boots `:server`, so its first render is
            the fallbacks React adopts the server's own nodes against; then
            ONE root-scoped write moves it to `:client` and BOTH sites swap in
            the single update that write produces. Exactly one
            `:rf.ssr/phase-flip` fires, carrying this root's id, and no
            mismatch is reported even though the client subtrees disagree with
            the fallbacks — which is what says the flip ran strictly AFTER the
            adoption commit."
    (if-not (browser?)
      (skip! "the browser job runs the phase assertions")
      (async done
        (reset-all!)
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
        (let [{:keys [props manifest server-html adopted-nodes flipped-text
                      stable-text flips flip-operation mismatches root-id]}
              (:hydrate root-008)
              node       (server-node! server-html manifest)
              before     (into {} (map (juxt identity #(.querySelector node %))) adopted-nodes)
              flip-evs   (atom [])
              mismatch-evs (atom [])
              kf         (listen! flip-operation flip-evs)
              km         (listen! :rf.ssr/hydration-mismatch mismatch-evs)
              mounted    (v/hydrate-root node [views/two-sites props])]
          (poll-until
            #(seq @flip-evs)
            (fn []
              (settle
                (fn []
                  (trace-tooling/unregister-listener! kf)
                  (trace-tooling/unregister-listener! km)
                  (doseq [[selector node*] before]
                    (is (some? node*) (str "the server rendered " selector))
                    (is (identical? node* (.querySelector node selector))
                        (str selector " is the SAME node object the server created — "
                             "the first client render produced the FALLBACK and React "
                             "adopted it")))
                  (doseq [[selector t] flipped-text]
                    (is (= t (text node selector))
                        (str selector " swapped to its client subtree")))
                  (doseq [[selector t] stable-text]
                    (is (= t (text node selector))
                        (str selector " sits outside both boundaries and is untouched")))
                  (is (= flips (count @flip-evs))
                      (str "exactly one flip for the root. Saw: " (pr-str @flip-evs)))
                  (when-let [ev (first @flip-evs)]
                    (is (= root-id (:root-id (merge (:tags ev) ev)))
                        "and the flip names the root that flipped"))
                  (is (= mismatches (count @mismatch-evs))
                      (str "the flip ran AFTER the adoption commit, so the client "
                           "subtrees never met the server's markup. Saw: "
                           (pr-str @mismatch-evs)))
                  (is (root/hydrated? mounted) "it hydrated")
                  (is (= root-id (:root-id (root/root-descriptor mounted)))
                      "under the manifest's identity")
                  (v/unmount! mounted)
                  (remove-node! node)
                  (reset-all!)
                  (done))))))))))
