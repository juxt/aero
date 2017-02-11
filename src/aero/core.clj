;; Copyright © 2015-2016, JUXT LTD.

(ns aero.core
  (:require
   [clojure.edn :as edn]
   [clojure.string :refer [trim]]
   [clojure.walk :refer [walk postwalk]]
   [clojure.java.io :as io]
   [clojure.java.shell :as sh])
  (:import (java.io StringReader)))

(declare read-config)

(defmulti reader (fn [opts tag value] tag))

(defmethod reader :default
  [_ tag value]
  (cond
    ;; Given tagification, we now must check data-readers
    (contains? *data-readers* tag)
    ((get *data-readers* tag) value)
    (contains? default-data-readers tag)
    ((get default-data-readers tag) value)
    :else
    (throw (ex-info (format "No reader for tag %s" tag) {:tag tag :value value}))))

(defmethod reader 'env
  [opts tag value]
  (System/getenv (str value)))

(defmethod reader 'envf
  [opts tag value]
  (let [[fmt & args] value]
    (apply format fmt
           (map #(System/getenv (str %)) args))))

(defmethod reader 'prop
  [opts tag value]
  (System/getProperty (str value)))

(defmethod reader 'or
  [opts tag value]
  (first (filter some? value)))

(defmethod reader 'long
  [opts tag value]
  (Long/parseLong (str value)))

(defmethod reader 'double
  [opts tag value]
  (Double/parseDouble (str value)))

(defmethod reader 'keyword
  [opts tag value]
  (if (keyword? value)
    value
    (keyword (str value))))

(defmethod reader 'boolean
  [opts tag value]
  (Boolean/parseBoolean (str value)))

(defmethod reader 'profile
  [{:keys [profile]} tag value]
  (cond (contains? value profile) (get value profile)
        (contains? value :default) (get value :default)
        :otherwise nil))

(defmethod reader 'hostname
  [{:keys [hostname]} tag value]
  (let [hostn (or hostname (-> (sh/sh "hostname") :out trim))]
    (or
     (some (fn [[k v]]
             (when (or (= k hostn)
                       (and (set? k) (contains? k hostn)))
               v))
           value)
     (get value :default))))

(defmethod reader 'user
  [{:keys [user]} tag value]
  (let [user (or user (-> (sh/sh "whoami") :out trim))]
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

(defn relative-resolver [source include]
  (let [fl
        (if (.isAbsolute (io/file include))
          (io/file include)
          (io/file (-> source io/file .getParent) include))]
    (if (.exists fl)
      fl
      (StringReader. (pr-str {:aero/missing-include include})))))

(defn resource-resolver [_ include]
  (io/resource include))

(defn root-resolver [_ include]
  include)

(defn adaptive-resolver [source include]
  (let [include (or (io/resource include)
                    include)]
    (if (string? include)
      (relative-resolver source include)
      include)))

(def default-opts
  {:profile  :default
   :resolver adaptive-resolver})

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
(defn read-config-into-tag-wrapper
  [pr]
  (try
    (edn/read
      {:eof nil
       ;; Make a wrapper of all known readers, this permits mixing of
       ;; post-processed tags with declared data readers
       :readers (into
                  {}
                  (map (fn [[k v]] [k #(tag-wrapper k %)])
                       (merge default-data-readers *data-readers*)))
       :default tag-wrapper}
      pr)
    (catch Exception e
      (let [line (.getLineNumber pr)]
        (throw (ex-info (format "Config error on line %s" line) {:line line} e))))))

(defn read-config
  "Optional second argument is a map that can include the following keys:
  :profile - indicates the profile to use for #profile extension
  :user - manually set the user for the #user extension
  :resolver - a function or map used to resolve includes."
  ([source given-opts]
   (let [opts (merge default-opts given-opts {:source source})
         tag-fn (partial reader opts)
         wrapped-config (with-open [pr (-> source io/reader clojure.lang.LineNumberingPushbackReader.)]
                          (read-config-into-tag-wrapper pr))]
     (-> wrapped-config
         (get-in-ref)
         (resolve-tag-wrappers tag-fn)
         (realize-deferreds))))
  ([source] (read-config source {})))
