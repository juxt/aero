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

(defmethod reader 'or
  [opts tag value]
  (first (filter some? value)))

(defmethod reader 'profile
  [{:keys [profile]} tag value]
  (cond (contains? value profile) (clojure.core/get value profile)
        (contains? value :default) (clojure.core/get value :default)
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
  [opts tag value]
  (read-config value opts))

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

(defn- resolve-file-path
  [r opts]
  (let [t (type r)]
    (cond (= t java.net.URL) ;; io.resource is a java.net.URL
          (recur (.getFile r) opts)

          (= t java.io.StringReader)
          {:file r
           :parent-path ""}

          :else ;; asuming string representing a file path
          (let [ior (io/file r)
                fp (if (.isAbsolute ior)
                     (.getPath ior)
                     (str (:relative-path opts) (.getPath ior)))
                iof (io/file fp)]
            (if (.exists iof)
              {:file fp
               :parent-path (str (.getParent iof) "/")}
              {:aero/warning (format "Configuration file does not exist: %s" fp)})))))

(defn read-config
  "Optional second argument is a map. Keys are :profile, indicating the
  profile for use with #cond"
  ([r opts]
   (let [fp (resolve-file-path r opts)
         config
         (if (:warning fp)
           fp
           (with-open [pr (java.io.PushbackReader. (io/reader (:file fp)))]
             (edn/read
              {:eof nil
               :default (partial reader (merge {:profile :default
                                                :relative-path (:parent-path fp)}
                                               opts))}
              pr)))]
     (get-in-ref config)))
  ([r] (read-config r {})))
