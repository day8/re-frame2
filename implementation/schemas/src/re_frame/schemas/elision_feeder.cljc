(ns re-frame.schemas.elision-feeder
  "Per-flag registry feeders for schema-derived elision declarations
  (rf2-v9tw2 / rf2-c1l4d).

  Each `:rf/app-schema-meta` flag (`:large?`, `:sensitive?`) hydrates a
  sibling slot under `[:rf/elision]`:

    :large?      → [:rf/elision :declarations]
    :sensitive?  → [:rf/elision :sensitive-declarations]

  The `frame-*-declarations` fns aggregate per-flag declarations across
  every schema registered against a frame. The `populate-*` fns
  idempotently replace those declarations in a frame's app-db from the
  current frame-derived set on every call. Re-registering a schema with
  the flag removed prunes the stale `:source :schema` entry (rf2-kr3vp);
  re-registering with a new `:hint` refreshes the slot.

  Both pairs are published via per-flag late-bind hooks by the outer
  façade so re-frame.core can call them without statically requiring
  the schemas artefact (per rf2-p7va — schemas is optional)."
  (:require [re-frame.frame :as frame]
            [re-frame.schemas.storage :as storage]
            [re-frame.schemas.walker :as walker]))

#?(:clj (set! *warn-on-reflection* true))

(defn- frame-declarations
  "Aggregate `extract-fn` across every schema registered against
  `frame-id`, returning a `{path declaration}` map."
  [extract-fn frame-id]
  (reduce-kv
    (fn [acc path m] (merge acc (extract-fn (:schema m) path)))
    {}
    (storage/frame-schema-entries frame-id)))

(defn- populate-declarations
  "Idempotent — fold `frame-id`'s schema-derived declarations into `db`
  at `registry-path`. Schema metadata is canonical: the registry slot is
  replaced wholesale with the current frame-derived set so re-registration
  with the flag removed prunes stale entries (rf2-kr3vp)."
  [db frame-id extract-fn registry-path]
  (let [schema-decls (frame-declarations extract-fn frame-id)
        parent-path  (vec (butlast registry-path))
        registry-key (last registry-path)]
    (if (seq schema-decls)
      (assoc-in db registry-path schema-decls)
      (let [db' (update-in db parent-path dissoc registry-key)]
        (if (seq (get-in db' parent-path))
          db'
          (dissoc db' (first parent-path)))))))

(defn frame-elision-declarations
  "Return the merged `{path declaration}` map for every `:large? true`
  slot in every app-schema registered against `frame-id`. Composes
  `extract-large-paths-from-schema` across the frame's schema set.

  Arities:
    (frame-elision-declarations)              ;; current frame
    (frame-elision-declarations frame-id)     ;; explicit frame"
  ([] (frame-elision-declarations (frame/current-frame)))
  ([frame-id] (frame-declarations walker/extract-large-paths-from-schema
                                  frame-id)))

(defn populate-elision-declarations
  "Idempotent — fold the frame's schema-derived `:large?` declarations
  into `db` at `[:rf/elision :declarations]`. See the per-flag registry-
  feeder header above for the full conflict-resolution rule."
  [db frame-id]
  (populate-declarations db frame-id
                         walker/extract-large-paths-from-schema
                         [:rf/elision :declarations]))

(defn frame-sensitive-declarations
  "Return the merged `{path declaration}` map for every `:sensitive? true`
  slot in every app-schema registered against `frame-id`. Composes
  `extract-sensitive-paths-from-schema` across the frame's schema set.

  Arities:
    (frame-sensitive-declarations)              ;; current frame
    (frame-sensitive-declarations frame-id)     ;; explicit frame"
  ([] (frame-sensitive-declarations (frame/current-frame)))
  ([frame-id] (frame-declarations walker/extract-sensitive-paths-from-schema
                                  frame-id)))

(defn populate-sensitive-declarations
  "Idempotent — fold the frame's schema-derived `:sensitive?` declarations
  into `db` at `[:rf/elision :sensitive-declarations]`. See the per-flag
  registry-feeder header above for the full conflict-resolution rule."
  [db frame-id]
  (populate-declarations db frame-id
                         walker/extract-sensitive-paths-from-schema
                         [:rf/elision :sensitive-declarations]))
