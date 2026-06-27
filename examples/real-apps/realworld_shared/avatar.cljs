(ns realworld-shared.avatar
  "Default-avatar helper for the RealWorld (Conduit) examples, shared by both
   realworld apps (`realworld.*` and `realworld-resources.*`).

   RealWorld contract conformance. The official RealWorld
   browser/E2E contract expects a non-empty avatar `src` for every author /
   user / comment-author image, falling back to a default when the backend
   returns a nil or blank `image`. The canonical Conduit clients ship a default
   avatar asset; this helper centralises that fallback so every `.user-img`,
   `.user-pic`, and `.comment-author-img` resolves to a real image rather than
   a broken `src=\"\"`.

   `default-avatar` points at `default-avatar.svg`, served from each app's
   asset root next to `index.html`. `avatar-src` returns it for nil/blank
   inputs and passes through any real URL unchanged."
  (:require [clojure.string :as str]))

(def default-avatar
  "The fallback avatar URL for a nil/empty author or user image. Served from the
   app's asset root next to `index.html` (the official RealWorld clients ship an
   equivalent default-avatar asset)."
  "default-avatar.svg")

(defn avatar-src
  "Resolve an avatar `src`: the given `image` URL when it is a non-blank string,
   otherwise the shared `default-avatar`. Used for every `.user-img` /
   `.user-pic` / `.comment-author-img` so an image always has a real `src`
   (RealWorld contract conformance)."
  [image]
  (if (and (string? image) (not (str/blank? image)))
    image
    default-avatar))
