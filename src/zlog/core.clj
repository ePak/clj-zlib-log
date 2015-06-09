(ns zlog.core
  (:require [nio.core :as nio]
            [gloss.core :as gc]
            [gloss.io :as gi]
            [clojure.core.async :as a]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.math.numeric-tower :as math]
            [clj-time.core :as t]
            [clj-time.format :as f]) 
  (:import [java.util.zip Inflater InflaterInputStream]
           [java.io EOFException PrintWriter]
           [com.esotericsoftware.wildcard Paths]
           [java.net Socket])
  (:gen-class))


(def log-entry-type ["Unknown"
                     "Enter Method"
                     "Exit Method"
                     "Debug"
                     "Audit"
                     "Error"
                     "Exception"
                     "Start Test Suite"
                     "End Test Suite"
                     "Start Test Case"
                     "End Test Case"
                     "Warning"
                     "Progress"
                     "Alert"
                     "Event"
                     "Activate"
                     "Deactivate"
                     "Test Case Unit Filename"
                     "Test Case Last Author"])

(defn send-to-logstash [entry-channel host port]
  (let [logstash-socket (Socket. host port)
        logstash-writer (PrintWriter. (.getOutputStream logstash-socket) true)]
    (try
      (loop []
        (when-let [entry (a/<!! entry-channel)]
          (.println logstash-writer 
                    (json/write-str (if (> (count (:data entry)) 30000) 
                                      (update-in entry [:data] #(str (subs % 0 30000) "... <truncated>"))
                                      entry)))
          (recur)))
      true
      (finally (do (.close logstash-writer)
                   (.close logstash-socket))))))

(gc/defcodec string-codec 
  (gc/finite-frame :uint32-le (gc/string :utf-8)))

(gc/defcodec bytes-codec
  (gc/finite-frame :uint32-le (gc/repeated :byte :prefix :none)))

(defn back-to-ordered [entry]
  (array-map :entry-type (:entry-type entry)
             :ref (:ref entry)
             :thread (:thread entry)
             :duration (:duration entry)
             :timestamp (:timestamp entry)
             :host-name (:host-name entry)
             :unitName (:unitName entry)
             :class-name (:class-name entry)
             :method (:method entry)
             :line-number (:line-number entry)
             :process (:process entry)
             :data (:data entry)))

(def log-entry-codec
  (gc/ordered-map :entry-type :uint32-le
                  :ref :uint32-le
                  :thread :uint32-le
                  :duration :uint32-le
                  :timestamp :float64-le 
                  :host-name string-codec
                  :unitName string-codec
                  :class-name string-codec
                  :method string-codec
                  :line-number :uint32-le
                  :process string-codec
                  :data bytes-codec))

(gc/defcodec log-entry-size-codec :uint32-le)

(def base-datetime (t/date-time 1899 12 30))
(def datetime-formatter (f/formatters :date-time))
(def time-zone-offset 0)

(defn timestamp-to-string [timestamp]
  (let [days (math/round (math/floor timestamp))
        millis (-> (- timestamp days)
                  (* 24 60 60 1000))
        datetime (-> base-datetime
                     (t/plus (t/days days) (t/millis millis))
                     (t/from-time-zone (t/time-zone-for-offset @#'time-zone-offset)))]
    (str datetime)))

(def byte-to-char-xform (map #(char (bit-and 0xFF %))))

(defn byte-array-to-string [arr] 
  (->> arr
       (sequence byte-to-char-xform)
       (apply str)))

(defn try-ready-n-byte [stream buffer offset length]
     (try (.read stream buffer offset length)
          (catch EOFException e -1)))

(defn read-n-byte [stream buffer size]
  (loop [offset 0]
    (let [n (try-ready-n-byte stream buffer offset (- size offset))
          byte-read (+ offset n)]
      (cond
        (= -1 n) -1
        (not= byte-read size) (recur byte-read)
        :else byte-read))))

(defn decode-file [filename ch]
  (let [input-stream (InflaterInputStream. (clojure.java.io/input-stream filename)) 
        size-byte-length 4
        size-buffer (make-array Byte/TYPE size-byte-length)]
    (a/go-loop []
               (let [read-size-byte (try-ready-n-byte input-stream size-buffer 0 size-byte-length)
                     entry-size (if (= -1 read-size-byte) 
                                  0
                                  (gi/decode log-entry-size-codec size-buffer))
                     entry-buffer (make-array Byte/TYPE entry-size)
                     read-entry-byte (read-n-byte input-stream entry-buffer entry-size)]
                 (if (or (<= read-size-byte 0) (<= read-entry-byte 0))
                   (do (.close input-stream))
                   (do (a/>! ch (->> entry-buffer
                                     (gi/decode log-entry-codec)
                                     (#(assoc % :filename (.getName filename)))))
                       (recur)))))
    ch))

(defn decode-file' [filename]
  (let [out-chan (a/chan)]
    (a/thread
      (println (str "decoding file: " (.getPath filename)))
      (let [input-stream (InflaterInputStream. (clojure.java.io/input-stream filename)) 
            size-byte-length 4
            size-buffer (make-array Byte/TYPE size-byte-length)]
        (loop []
          (let [read-size-byte (read-n-byte input-stream size-buffer size-byte-length)
                entry-size (if (= -1 read-size-byte) 
                             0
                             (gi/decode log-entry-size-codec size-buffer))
                entry-buffer (make-array Byte/TYPE entry-size)
                read-entry-byte (read-n-byte input-stream entry-buffer entry-size)]
            (if (or (<= read-size-byte 0) (<= read-entry-byte 0))
              (do 
                (.close input-stream)
                (a/close! out-chan))
              (do 
                (a/>!! out-chan (->> entry-buffer
                                     (gi/decode log-entry-codec)
                                     (#(assoc % :filename (.getPath filename)))))
                (recur))))))
      true)
    out-chan))

(def decode-file-xform
  (fn [rf]
    (fn
      ([] (rf))
      ([result] (rf result))
      ([result input]
       (let [entries (decode-file' input)]
         (loop [result result
                entry (a/<!! entries)]
           (if (nil? entry)
             result 
             (recur (rf result entry) (a/<!! entries)))))))))

(defn decode-next-entry [input-stream]
  (let [size-byte-length 4
        size-buffer (make-array Byte/TYPE size-byte-length)
        read-size-byte (read-n-byte input-stream size-buffer size-byte-length)
        entry-size (if (= -1 read-size-byte) 
                     0
                     (gi/decode log-entry-size-codec size-buffer))
        entry-buffer (make-array Byte/TYPE entry-size)
        read-entry-byte (read-n-byte input-stream entry-buffer entry-size) ]
    (println (str "read-size-byte=" read-size-byte ", entry-size=" entry-size ", read-entry-byte=" read-entry-byte))
    entry-buffer))

(defn decode-file-aform [file out-chan]
  (a/thread
    (println (str "decoding file: " (.getPath file)))
    (let [input-stream (InflaterInputStream. (clojure.java.io/input-stream file)) 
          size-byte-length 4
          size-buffer (make-array Byte/TYPE size-byte-length)
          filename (.getPath file)]
      (loop [last-ref -1]
        (let [read-size-byte (read-n-byte input-stream size-buffer size-byte-length)
              entry-size (if (= -1 read-size-byte) 
                           0
                           (gi/decode log-entry-size-codec size-buffer))
              entry-buffer (make-array Byte/TYPE entry-size)
              read-entry-byte (read-n-byte input-stream entry-buffer entry-size)]
          (if (or (<= read-size-byte 0) (<= read-entry-byte 0))
            (do 
              (.close input-stream)
              (a/close! out-chan)
              (println (str "exported " (+ last-ref 1) " entries from " filename)))
            (let [entry (-> entry-buffer
                            (#(gi/decode log-entry-codec %))
                            (update-in [:data] byte-array-to-string)
                            (#(assoc % "@timestamp" (timestamp-to-string (:timestamp %))))
                            (update-in [:entry-type] log-entry-type)
                            (assoc :entry-byte-size read-entry-byte)
                            (assoc :filename filename))]
              (a/>!! out-chan entry)
              (recur (:ref entry)))))))
    true))


(defn decode-files [filename-channel n]
  (let [output-channel (a/chan 10)]
    (a/pipeline-async n output-channel decode-file-aform filename-channel)
    output-channel))


(def cli-options 
  [["-r" "--root ROOT_DIR" "Root directory to search (default: .)"
    :default "."
    :validate [#(->> %
                     (io/file)
                     ((fn [f] (and (.exists f) (.isDirectory f)))))
               "ROOT_DIR not exists or not a directory"]]
   ["-f" "--file FILE" "File pattern (default: *.zlog)"
    :default "*.zlog"]
   [nil "--host HOST" "LogStash hostname (default: localhost)"
    :default "localhost"]
   [nil "--port PORT" "LogStash port number (default: 5140)"
    :default 5140
    :parse-fn #(Integer/parseInt %)]
   ["-t" "--time-zone HOURS" "The time-zone where this logs are recorded in (default: 0, ie UK)"
    :default 0
    :parse-fn #(Integer/parseInt %)]
   ["-h" "--help" "Show this help information"]])

(defn usage [options-summary]
  (->> ["Export zlogs into LogStash."
        ""
        "Usage: clj-zlib-log [options] DIR_1 [DIR_2...]"
        ""
        "Options:"
        options-summary
        ""
        "DIR_1 etc. can be a glob expression."
        ""]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options) (exit 0 (usage summary))
      (= (count arguments) 0) (exit 1 (usage summary))
      errors (exit 1 (error-msg errors)))
    (alter-var-root #'time-zone-offset (constantly (:time-zone options)))
    (let [files (->> arguments
                     (map #(Paths. (:root options) [(str % "/" (:file options))]))
                     (mapcat #(.getFiles %))
                     (set)
                     (lazy-seq))
          file-channel (a/to-chan files)
          entry-channel (decode-files file-channel (min 4 (count files)))
          done (a/thread (send-to-logstash entry-channel (:host options) (:port options)))]
      (a/<!! done)
      (println "done!"))))
