(ns re-frame.freehand.compiled-reactive-cljs-test
  "THE COMPILED TIER READS STATE — and reads it through the one shell.

  A `(v/sub …)` inside a `{:compiled true}` body lowers to
  `re-frame.freehand.reactive/sub-read`. That namespace did not exist, so
  a compiled declaration carrying a subscription could not even LOAD
  through the public `v/defview` door: it failed at macro expansion with
  a missing-namespace error, on both hosts, before any render. Two
  independent slices measured the consequence — the manifest census could
  not populate its `:subscriptions` roster through `v/manifest`, and the
  compiled-schemas suite had to assert that gap executably rather than
  leave it as a comment.

  THE PRE-FIX REGRESSION IS THIS FILE LOADING. `re-frame.freehand.manifest-views`
  declares a compiled sub-bearing view; requiring it is what used to fail,
  and it fails at COMPILE, so a suite asserting over it cannot be green
  for the wrong reason. There were two causes, and both are load-time:
  the missing runtime namespace, and — reachable only once that was
  fixed — a descriptor entry that spliced the manifest UNQUOTED, so a
  query reading a prop (`(v/sub [:basket/total basket-id])`) evaluated
  the body's own local symbol where no such local exists.

  ## What is actually proven here

  Loading is necessary and nowhere near sufficient. The claim this suite
  makes is SEMANTIC AGREEMENT: a compiled view carrying a subscription
  observes the same values, in the same commit discipline, as its
  interpreted twin — because both reach the same atomic shell. Every
  behavioural row below runs the SAME assertions over both arms from one
  table, so a compiled-only regression cannot hide behind an
  interpreted-only green, and a claim that holds for neither fails twice.

  The one thing that is deliberately DIFFERENT is the site key. The
  interpreted walk numbers reads in document order — it has nothing
  better, because an unrestricted body's reads are discovered, not
  proven. The compiled tier's analyzer has already proven a finite set of
  read sites and given each a stable LEXICAL id, so its read is recorded
  under the very id the manifest publishes on its `:subscriptions`
  roster. That is asserted here as the identity it is: the manifest's
  `:sid` IS the committed dependency's key.

  Both hosts, from one file: the shell, the lowering and the runtime are
  all `.cljc`, and a compiled body runs directly on the JVM structural
  host. `t/render` is the public structural door, so nothing below
  reaches past a surface an application has."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.manifest-views :as mv]
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; The two arms
;; ---------------------------------------------------------------------------

(v/defview interpreted-basket-total
  "The INTERPRETED twin of `re-frame.freehand.manifest-views/basket-total`
  — the same parameter vector, the same body, and no `{:compiled true}`.
  The one-line promotion, written out as a second declaration so both
  lowerings are live in one process and one table can drive both."
  [{:keys [basket-id]}]
  [:output.total (v/sub [:basket/total basket-id])])

(def ^:private compiled-view mv/basket-total)

(def ^:private fid :rf/default)
(def ^:private basket-id 7)
(def ^:private query [:basket/total basket-id])

(def ^:private arms
  "mode -> the declaration that mode declares. Every behavioural row runs
  over both, so a claim is a claim about the SUBSTRATE and not about one
  lowering that happens to be green."
  {:compiled    compiled-view
   :interpreted interpreted-basket-total})

(defn- register-sub! []
  (rf/reg-sub :basket/total (fn [db [_ id]] (get-in db [:baskets id :total]))))

(defn- seed! [db] (frame/replace-app-db! fid db))

(defn- render-under-capture!
  "One render pass of `view` inside cell `c`'s candidate — through the
  PUBLIC structural door. Returns `[candidate tree]`; nothing is
  published, because a render owns nothing."
  [c view]
  (let [cand (cell/candidate c fid)
        tree (cell/with-capture cand (fn [] (t/render [view {:basket-id basket-id}])))]
    [cand tree]))

(defn- rendered-total
  [tree]
  (t/text (t/find tree #(= :output (:tag %)))))

;; ---------------------------------------------------------------------------
;; Loading, and the manifest the declaration now carries
;; ---------------------------------------------------------------------------

(deftest a-compiled-subscription-declaration-loads-and-reports-its-site
  (testing "The declaration compiles, the var holds a view, and its manifest
            names the subscription site the body actually carries — the query
            the author wrote (including the prop-reading local symbol that used
            to be evaluated), with a whole source coordinate. Loading is the
            regression: before the runtime landed, requiring the census
            namespace failed at macro expansion."
    (is (v/view? compiled-view) "the compiled declaration produced a view")
    (let [m (v/manifest compiled-view)]
      (is (= 1 (count (:subscriptions m)))
          "exactly the one subscription site the body carries")
      (let [site (first (:subscriptions m))]
        (is (= '[:basket/total basket-id] (:query site))
            "the roster carries the AUTHORED query form, local symbol and all")
        (is (string? (:sid site)))
        (is (seq (:sid site)) "with a real lexical site id")
        (is (= #{:file :line :column} (set (keys (:source-coord site))))
            "and a whole source coordinate")
        (is (pos-int? (:line (:source-coord site))))))))

(deftest the-elision-verdict-matches-the-reactive-site-it-reports
  (testing "A subscription is a reactive site, so the compiled tier retains the
            ViewCell shell and says so — the verdict and the roster are one
            fact, not two that could drift. The inert census arm is the control:
            without it a `:present` verdict could be a constant."
    (let [m (v/manifest compiled-view)]
      (is (= :present (:view-cell m)))
      (is (true? (:reactive? m)))
      (is (contains? (:capabilities m) :sub)
          "the capability set names the site that forced the shell"))
    (testing "and the control — an inert compiled declaration still elides"
      (let [m (v/manifest (:label mv/by-name))]
        (is (= :elided (:view-cell m)))
        (is (false? (:reactive? m)))
        (is (empty? (:subscriptions m)))))))

;; ---------------------------------------------------------------------------
;; The read itself — both arms, one table
;; ---------------------------------------------------------------------------

(deftest a-read-outside-a-render-is-refused-identically-in-both-modes
  (testing "Promotion cannot change what a read MEANS. A subscription read with
            no active declared render has no owner in either mode, so both arms
            are refused with the same stable diagnostic id rather than one of
            them probing a value nobody owns."
    (register-sub!)
    (seed! {:baskets {basket-id {:total 42}}})
    (doseq [[mode view] arms]
      (is (false? (cell/observing?)) (str mode " — no active render"))
      (is (= :rf.error/view-read-outside-render
             (conf/caught-id #(rf/with-frame fid (t/render [view {:basket-id basket-id}]))))
          (str mode " — refused loudly, with the id the other mode raises")))))

(deftest a-render-read-observes-and-the-commit-owns-it-in-both-modes
  (testing "The whole claim, arm for arm: the body reads the subscription's
            current value, the render owns nothing, and the SELECTED commit
            publishes the read as an owned dependency carrying that value. A
            compiled body reaches this through `reactive/sub-read`; an
            interpreted one through `v/sub`; both land in the one shell."
    (register-sub!)
    (seed! {:baskets {basket-id {:total 42}}})
    (doseq [[mode view] arms]
      (let [c           (cell/cell (keyword "compiled-reactive" (name mode)))
            [cand tree] (render-under-capture! c view)]
        (is (= "42" (rendered-total tree))
            (str mode " — the rendered output carries the observed value"))
        (is (empty? (cell/dependencies c))
            (str mode " — before the commit the render owns nothing"))
        (is (= :published (cell/commit! cand))
            (str mode " — the render was current, so its bundle published"))
        (is (= [query] (cell/dependency-queries c))
            (str mode " — the commit owns exactly the query the body read"))
        (is (= [42] (mapv :value (:observations (cell/evidence c))))
            (str mode " — and the published bundle carries its value"))
        (is (every? :owned? (:observations (cell/evidence c)))
            (str mode " — as an OWNED dependency"))
        (is (= 1 (count (cell/dependencies c)))
            (str mode " — non-vacuous: one dependency, not zero"))))))

(deftest a-changed-input-recomputes-and-recommits-in-both-modes
  (testing "The repaint half. When the state a committed read depends on moves,
            a re-render recomputes it and the commit republishes the whole
            bundle against the SAME retained handle — acquire-before-release,
            in both modes, with the compiled arm proving it through a proven
            site key rather than a counted one."
    (register-sub!)
    (doseq [[mode view] arms]
      ;; Re-seeded per arm: each mode starts from the same state and moves it
      ;; itself, so the second arm proves the move rather than inheriting it.
      (seed! {:baskets {basket-id {:total 42}}})
      (let [c              (cell/cell (keyword "compiled-reactive-move" (name mode)))
            [cand1 tree1]  (render-under-capture! c view)]
        (is (= "42" (rendered-total tree1)))
        (is (= :published (cell/commit! cand1)))
        (let [site-key (first (keys (cell/dependencies c)))
              handle1  (:handle (get (cell/dependencies c) site-key))]
          (seed! {:baskets {basket-id {:total 99}}})
          (is (= [42] (mapv :value (:observations (cell/evidence c))))
              (str mode " — the committed bundle is unchanged until a re-commit"))
          (let [[cand2 tree2] (render-under-capture! c view)]
            (is (= "99" (rendered-total tree2))
                (str mode " — the re-render recomputed the moved value"))
            (is (= :published (cell/commit! cand2)))
            (is (= [99] (mapv :value (:observations (cell/evidence c))))
                (str mode " — and the bundle flipped as a unit"))
            (is (identical? handle1 (:handle (get (cell/dependencies c) site-key)))
                (str mode " — against the same retained handle"))
            (is (= 1 (count (cell/dependencies c)))
                (str mode " — non-vacuous: still exactly one dependency"))))))))

;; ---------------------------------------------------------------------------
;; The one difference, asserted as the identity it is
;; ---------------------------------------------------------------------------

(deftest the-compiled-read-is-owned-under-its-proven-lexical-site-id
  (testing "The compiled tier does not count its way to a site — its analyzer
            proved one and named it, and that name is what the manifest
            publishes. So the key the shell files the committed dependency
            under IS the manifest's `:sid`, and a tool holding a manifest can
            find the dependency it describes. The interpreted twin is the
            control: it has no proven site, so it files under the document
            ordinal, which is the honest key an unrestricted body can offer."
    (register-sub!)
    (seed! {:baskets {basket-id {:total 42}}})
    (let [sid (:sid (first (:subscriptions (v/manifest compiled-view))))
          c   (cell/cell :compiled-reactive/keys)
          [cand _] (render-under-capture! c compiled-view)]
      (is (= :published (cell/commit! cand)))
      (is (= [sid] (vec (keys (cell/dependencies c))))
          "the committed dependency is keyed by the manifest's own site id")
      (is (not= [0] (vec (keys (cell/dependencies c))))
          "non-vacuous: it is the lexical id, not the ordinal that would also fit"))
    (let [c        (cell/cell :interpreted-reactive/keys)
          [cand _] (render-under-capture! c interpreted-basket-total)]
      (is (= :published (cell/commit! cand)))
      (is (= [0] (vec (keys (cell/dependencies c))))
          "the interpreted control files under its document ordinal"))))
