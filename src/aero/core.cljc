;; Copyright © 2015-2017, JUXT LTD.

(ns aero.core
  #?(:clj (:require
           [clojure.edn :as edn]
           [clojure.string :refer [trim]]
           [clojure.walk :refer [walk postwalk]]
           [clojure.java.io :as io]
           [clojure.java.shell :as sh]
           [aero.vendor.dependency.v0v2v0.com.stuartsierra.dependency :as dep]))
  #?(:clj (:import (java.io StringReader)))
  #?(:cljs (:require [cljs.tools.reader :as edn]
                     [cljs.nodejs :as nodejs]
                     [goog.string :as gstring]
                     goog.string.format
                     [clojure.walk :refer [walk postwalk]]
                     [aero.vendor.dependency.v0v2v0.com.stuartsierra.dependency :as dep])))

(declare read-config)

#?(:cljs (def os (nodejs/require "os")))
#?(:cljs (def fs (nodejs/require "fs")))

(defmulti reader (fn [opts tag value] tag))

(defmethod reader :default
  [_ tag value]
  (cond
    ;; Given tagification, we now must check data-readers
    ;; TODO: Only Clojure for now, but also ClojureScript once *data-readers* are supported in lumo
    (contains? #?(:clj *data-readers* :cljs edn/*data-readers*) tag)
    ((get #?(:clj *data-readers* :cljs edn/*data-readers*) tag) value)
    #?(:clj (contains? default-data-readers tag))
    #?(:clj ((get default-data-readers tag) value))
    :else
    (throw (ex-info (#?(:clj format :cljs gstring/format) "No reader for tag %s" tag) {:tag tag :value value}))))


(defn- env [s]
  #?(:clj (System/getenv (str s)))
  #?(:cljs (aget js/process.env s)))

(defmethod reader 'env
  [opts tag value]
  (env value))

(defmethod reader 'envf
  [opts tag value]
  (let [[fmt & args] value]
    (apply #?(:clj format :cljs gstring/format) fmt
           (map #(env (str %)) args))))

(defmethod reader 'prop
   [opts tag value]
   #?(:clj (System/getProperty (str value))
      :cljs nil))

(defmethod reader 'or
  [opts tag value]
  (first (filter some? value)))

(defmethod reader 'long
  [opts tag value]
  #?(:clj (Long/parseLong (str value)))
  #?(:cljs (js/parseInt (str value))))

(defmethod reader 'double
  [opts tag value]
  #?(:clj (Double/parseDouble (str value)))
  #?(:cljs (js/parseFloat (str value))))

(defmethod reader 'keyword
  [opts tag value]
  (if (keyword? value)
    value
    (keyword (str value))))

(defmethod reader 'boolean
  [opts tag value]
  #?(:clj (Boolean/parseBoolean (str value)))
  #?(:cljs (= "true" (.toLowerCase (str value)))))

(defmethod reader 'profile
  [{:keys [profile]} tag value]
  (cond (contains? value profile) (get value profile)
        (contains? value :default) (get value :default)
        :otherwise nil))

(defmethod reader 'hostname
  [{:keys [hostname]} tag value]
  (let [hostn (or hostname #?(:clj (env "HOSTNAME")
                              :cljs (os.hostname)))]
    (or
     (some (fn [[k v]]
             (when (or (= k hostn)
                       (and (set? k) (contains? k hostn)))
               v))
           value)
     (get value :default))))

(defmethod reader 'user
  [{:keys [user]} tag value]
  (let [user (or user (env "USER"))]
    (or
     (some (fn [[k v]]
             (when (or (= k user)
                       (and (set? k) (contains? k user)))
               v))
           value)
     (get value :default))))

(defmethod reader 'include
  [{:keys [resolver source] :as opts} tag value]
  (read-config
    (if (map? resolver)
      (get resolver value)
      (resolver source value))
    opts))

(defmethod reader 'join
  [opts tag value]
  (apply str value))

(defmethod reader 'read-edn
  [opts tag value]
  (some-> value str edn/read-string))

(defmethod aero.core/reader 'merge
  [opts tag values]
  (apply merge values))

(defn- get-in-ref
  [config]
  (letfn [(get-in-conf [m]
            (postwalk
             (fn [v]
               (if-not (contains? (meta v) :ref)
                 v
                 (get-in-conf (get-in config v))))
             m))]
    (get-in-conf config)))

#?(:clj
   (defn relative-resolver [source include]
     (let [fl
           (if (.isAbsolute (io/file include))
             (io/file include)
             (when-let [source-file
                        (try (io/file source)
                             ;; Handle the case where the source isn't file compatible:
                             (catch java.lang.IllegalArgumentException _ nil))]
               (io/file (.getParent source-file) include)))]
       (if (and fl (.exists fl))
         fl
         (StringReader. (pr-str {:aero/missing-include include}))))))

#?(:clj
   (defn resource-resolver [_ include]
     (io/resource include)))

#?(:clj
   (defn root-resolver [_ include]
     include))

#?(:clj
   (defn adaptive-resolver [source include]
     (let [include (or (io/resource include)
                       include)]
       (if (string? include)
         (relative-resolver source include)
         include))))

(def default-opts
  {:profile :default
   :resolver #?(:clj adaptive-resolver
                :cljs (fn [source include]
                        (if (path.isAbsolute include)
                          include
                          (path.join source ".." include))))})

;; The rationale for deferreds is to realise some values after the
;; config has been read. This allows certain expensive operations to
;; be performed only after #profile has had a chance to filter out all
;; other environments. For example, a :prod profile my do some
;; expensive decryption of secrets (which may not be cheap to run for
;; all environments which don't need them, and probably won't be
;; possible to decrypt, therefore you want to defer until needed).

(defrecord Deferred [delegate])

(defmacro deferred [& expr]
   `(->Deferred (delay ~@expr)))

(defn- realize-deferreds
  [config]
  (postwalk (fn [x] (if (instance? Deferred x) @(:delegate x) x)) config))

(defrecord TagWrapper
  [tag value])

(defn- tag-wrapper
  "Call from :default of clojure.edn to wrap all tags it encounters & cannot handle itself.
   TODO: Anything special needed to support a mirror of dynamic var related to *tags*?"
  [tag value]
  (->TagWrapper tag value))

(defn- tag-wrapper?
  [x]
  (= (type x) TagWrapper))

(defn- tag-wrapper-of?
  [x tag]
  (and (tag-wrapper? x)
       (= (:tag x) tag)))

(defn- tag-wrapper-of-ref?
  [x]
  (tag-wrapper-of? x 'ref))

(defn- resolve-tag-wrappers
  [tagged-config tag-fn]
  (postwalk
    (fn [x]
      (if (tag-wrapper? x)
        (tag-fn (:tag x) (:value x))
        x))
    tagged-config))
(defn- ref-meta-to-tag-wrapper
  [config]
  (letfn [(get-in-conf [m]
            (postwalk
              (fn [v]
                (if-not (contains? (meta v) :ref)
                  v
                  (tag-wrapper 'ref v)))
              m))]
    (get-in-conf config)))

(defn pathify
  "Return a list of tuples. Each tuple will be of a key-path (similar to what get-in takes) and the value at that point.
  NOTE: get-in will not necessarily work on the data structure as get-in doesn't work on lists.
  NOTE: Has a special case for tags (could this be removed?) (tag-kvs)
  NOTE: May have a limit due to recursion not being in the tail position."
  ([f x] (pathify f (f) x))
  ([f pk x]
   (if-some [kvs (cond
                   (map? x)
                   x
                   (sequential? x)
                   (map-indexed vector x))]
     (conj
       (mapcat
         (fn [[k v]]
           (pathify f (f pk k) v))
         kvs)
       [pk x])
     [[pk x]])))

(defn shorter-variations
  "Given a sequence '(1 2 3) return a sequence of all shorter
  possible sequences: '((1 2) (1))"
  [xs]
  (loop [r '()
         ys (butlast (seq xs))]
    (if (seq ys)
      (recur (conj r ys)
             (butlast ys))
      r)))

(defn ref-dependency
  [ref*]
  (:value ref*))

(defn ref-dependencies
  "Recursively checks a ref for nested dependencies"
  [ref*]
  (let [nested-deps (sequence
                      (comp (filter tag-wrapper-of-ref?)
                            (map :value))
                      (:value ref*))]
    (concat
      (when-some [r (ref-dependency ref*)] [r])
      (when (seq nested-deps)
        (concat nested-deps
                (->> nested-deps
                     (mapcat ref-dependencies)))))))

(defn config->ref-graph
  [config]
  (reduce
    (fn [graph [k v]]
      (as-> graph %
        (reduce (fn [acc sk] (dep/depend acc sk k)) % (shorter-variations k))
        (reduce (fn [acc sk] (dep/depend acc k sk)) % (shorter-variations (:value v)))
        (reduce (fn [acc d] (dep/depend acc k d)) % (ref-dependencies v))))
    (dep/graph)
    (filter
      (comp tag-wrapper-of-ref? second)
      (pathify conj [] config))))

(defn resolve-refs
  "Resolves refs & any necessary tags in order to do it's job"
  [config resolve-fn]
  (reduce
    (fn [acc ks]
      (let [resolved-ks (map
                          (fn [x]
                            ;; if there's a sub-ref here, it should already be
                            ;; a value due to recursive deps being resolved for
                            ;; us
                            (if (tag-wrapper-of-ref? x)
                              (get-in acc (ref-dependency x))
                              x))
                          ks)
            b (get-in acc resolved-ks)]
        (cond
          (tag-wrapper-of-ref? b)
          (assoc-in acc resolved-ks (get-in acc (ref-dependency b)))
          (tag-wrapper? b)
          (assoc-in acc
                    resolved-ks
                    (resolve-fn (:tag b)
                                (resolve-tag-wrappers (:value b) resolve-fn)))
          :else
          acc)))
    config
    (dep/topo-sort (config->ref-graph config))))

(defn read-config-into-tag-wrapper
  [source]
  #?(:clj
     (with-open [pr (-> source io/reader clojure.lang.LineNumberingPushbackReader.)]
       (try
         (ref-meta-to-tag-wrapper
          (edn/read
           {:eof nil
            ;; Make a wrapper of all known readers, this permits mixing of
            ;; post-processed tags with declared data readers
            :readers (into
                      {}
                      (map (fn [[k v]] [k #(tag-wrapper k %)])
                           (merge default-data-readers *data-readers*)))
            :default tag-wrapper}
           pr))
         (catch Exception e
           (let [line (.getLineNumber pr)]
             (throw (ex-info (#?(:clj format :cljs gstring/format) "Config error on line %s" line) {:line line} e))))))
     :cljs
     (binding [edn/*default-data-reader-fn* tag-wrapper
               edn/*data-readers* (into {}
                                        (map (fn [[k v]] [k #(tag-wrapper k %)])
                                             edn/*data-readers*))]
       (ref-meta-to-tag-wrapper
        (edn/read-string
         {:eof nil
          ;; Make a wrapper of all known readers, this permits mixing of
          ;; post-processed tags with declared data readers
          :readers {}
          :default tag-wrapper}
         (fs.readFileSync source "utf-8"))))))

(defn read-config
  "Optional second argument is a map that can include the following keys:
  :profile - indicates the profile to use for #profile extension
  :user - manually set the user for the #user extension
  :resolver - a function or map used to resolve includes."
  ([source given-opts]
   (let [opts (merge default-opts given-opts {:source source})
         tag-fn (partial reader opts)
         wrapped-config (read-config-into-tag-wrapper source)]
     (-> wrapped-config
         (resolve-refs tag-fn)
         (resolve-tag-wrappers tag-fn)
         (realize-deferreds))))
  ([source] (read-config source {})))
