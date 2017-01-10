;; Copyright © 2015-2016, JUXT LTD.

(ns aero.core
  (:require
   [clojure.edn :as edn]
   [clojure.string :refer [trim]]
   [clojure.walk :refer [walk postwalk]]
   [clojure.java.io :as io]
   [clojure.java.shell :as sh]))

(declare read-config)

(defmulti reader (fn [opts tag value] tag))

(defmethod reader :default
  [_ tag value]
  (throw (ex-info (format "No reader for tag %s" tag) {:tag tag :value value})))

(defmethod reader 'env
  [opts tag value]
  (System/getenv (str value)))

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
      (java.io.StringReader. (pr-str {:aero/missing-include include})))))

(defn resource-resolver [_ include]
  (io/resource include))

(defn root-resolver [_ include]
  include)

(def default-opts
  {:profile  :default
   :resolver relative-resolver})

(defrecord Deferred [delegate])

(defmacro deferred [& expr]
  `(->Deferred (delay ~@expr)))

(defn realize-deferreds [config]
  (postwalk (fn [x] (if (instance? Deferred x) @(:delegate x) x)) config))

(defn read-config
  "Optional second argument is a map that can include the following keys:
  :profile - indicates the profile to use for #profile extension
  :user - manually set the user for the #user extension
  :resolver - a function or map used to resolve includes."
  ([source given-opts]
   (let [opts (merge default-opts given-opts {:source source})
         tag-fn (partial reader opts)
         config (with-open [pr (-> source io/reader java.io.PushbackReader.)]
                  (edn/read {:eof nil :default tag-fn} pr))]
     (-> config (get-in-ref) (realize-deferreds))))
  ([source] (read-config source {})))
