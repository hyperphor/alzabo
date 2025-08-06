(ns org.candelbio.alzabo.config
  (:require [clojure.edn :as edn]
            [aero.core :as aero]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [clojure.string :as str]
            [org.candelbio.multitool.core :as u])
  )

;;; See resources/default-config.edn

(def the-config (atom nil))
(def config-path (atom nil))

(defmethod aero/reader 'split
  [_ _ [s]]
  (and s (str/split s #",")))

(defn set-config!
  [config]                              ;filename or map
  (let [config (if (string? config)
                 (do
                   (reset! config-path config)
                   (aero/read-config config))
                 config)]
    (reset! the-config config)))

(defn set!
  [att value]
  (swap! the-config assoc att value))

(defn config
  [& keys]
  (assert @the-config "Config not set")
  (get-in @the-config keys))

(defn output-path
  [filename]
  (str
   ;; This rigamarole lets you use relative paths and fs/with-cwd
   (fs/file
    (str (u/expand-template (config :output-path) (config))
         filename))))

;; TODO → multitool (with some cleanup)
(defn realize-rel-path
  [base path]
  (str (.getParentFile (fs/file base))
       "/"
       path))

(defn realize-path
  [path]
  (realize-rel-path @config-path path))

;;; This bit of hackery seems to come and go
;; TODO → multitool (with some cleanup)
(defn realize-rel-path
  [base path]
  (str (.getParentFile (fs/file base))
       "/"
       path))

(defn realize-path
  [path]
  (realize-rel-path @config-path path))

