(ns datalevin.lmdb
  "Wrapping LMDB"
  (:refer-clojure :exclude [get iterate])
  (:require [datalevin.bits :as b]
            [datalevin.util :refer [raise]]
            [datalevin.constants :as c]
            [clojure.string :as s])
  (:import [org.lmdbjava Env EnvFlags Env$MapFullException Stat Dbi DbiFlags
            PutFlags Txn CursorIterable CursorIterable$KeyVal KeyRange]
           [clojure.lang IMapEntry]
           [java.util Iterator]
           [java.util.concurrent ConcurrentHashMap]
           [java.nio ByteBuffer]))

(def default-env-flags [EnvFlags/MDB_NOTLS
                        EnvFlags/MDB_NORDAHEAD])

(def default-dbi-flags [DbiFlags/MDB_CREATE])

(defprotocol IBuffer
  (put-key [this data k-type]
    "put data in key buffer, k-type can be :long, :byte, :bytes, :data")
  (put-val [this data v-type]
    "put data in val buffer, v-type can be :long, :byte, :bytes, :data"))

(defprotocol IRange
  (put-start-key [this data k-type]
    "put data in start-key buffer, k-type can be :long, :byte, :bytes, :data
     or index type, :eav etc.")
  (put-stop-key [this data k-type]
    "put data in stop-key buffer, k-type can be :long, :byte, :bytes, :data
    or index type, :eav etc."))

(defprotocol IRtx
  (close-rtx [this] "close the read-only transaction")
  (reset [this] "reset transaction so it can be reused upon renew")
  (renew [this] "renew and return previously reset transaction for reuse"))

(deftype Rtx [^Txn txn
              ^:volatile-mutable use
              ^ByteBuffer kb
              ^ByteBuffer start-kb
              ^ByteBuffer stop-kb]
  IBuffer
  (put-key [_ x t]
    (b/put-buffer kb x t))
  (put-val [_ x t]
    (raise "put-val not allowed for read only txn buffer" {}))

  IRange
  (put-start-key [_ x t]
    (when x
      (.clear start-kb)
      (b/put-buffer start-kb x t)))
  (put-stop-key [_ x t]
    (when x
      (.clear stop-kb)
      (b/put-buffer stop-kb x t)))

  IRtx
  (close-rtx [_]
    (set! use false)
    (.close txn))
  (reset [this]
    (locking this
      (.reset txn)
      (set! use false)
      this))
  (renew [this]
    (locking this
      (when-not use
        (.renew txn)
        (set! use true)
        this))))

(defprotocol IRtxPool
  (close-pool [this] "Close all transactions in the pool")
  (new-rtx [this] "Create a new read-only transaction")
  (get-rtx [this] "Obtain a ready-to-use read-only transaction"))

(deftype RtxPool [^Env env
                  ^ConcurrentHashMap rtxs
                  ^:volatile-mutable ^long cnt]
  IRtxPool
  (close-pool [this]
    (locking this
      (doseq [^Rtx rtx (.values rtxs)] (close-rtx rtx))
      (.clear rtxs)
      (set! cnt 0)))
  (new-rtx [this]
    (locking this
      (when (< cnt c/+max-readers+)
        (let [rtx (->Rtx (.txnRead env)
                         false
                         (ByteBuffer/allocateDirect c/+max-key-size+)
                         (ByteBuffer/allocateDirect c/+max-key-size+)
                         (ByteBuffer/allocateDirect c/+max-key-size+))]
          (.put rtxs cnt rtx)
          (set! cnt (inc cnt))
          (reset rtx)
          (renew rtx)))))
  (get-rtx [this]
    (loop [i (.getId ^Thread (Thread/currentThread))]
      (let [^long i' (mod i cnt)
            ^Rtx rtx (.get rtxs i')]
        (or (renew rtx)
            (new-rtx this)
            (recur (long (inc i'))))))))

(defprotocol IKV
  (put [this txn] [this txn put-flags]
    "Put kv pair given in `put-key` and `put-val` of dbi")
  (del [this txn] "Delete the key given in `put-key` of dbi")
  (get [this rtx] "Get value of the key given in `put-key` of rtx")
  (iterate [this rtx range-type] "Return a CursorIterable"))

(defn- key-range
  [range-type kb1 kb2]
  (case range-type
    :all               (KeyRange/all)
    :all-back          (KeyRange/allBackward)
    :at-least          (KeyRange/atLeast kb1)
    :at-least-back     (KeyRange/atLeastBackward kb1)
    :at-most           (KeyRange/atMost kb1)
    :at-most-back      (KeyRange/atMostBackward kb1)
    :closed            (KeyRange/closed kb1 kb2)
    :closed-back       (KeyRange/closedBackward kb1 kb2)
    :closed-open       (KeyRange/closedOpen kb1 kb2)
    :closed-open-back  (KeyRange/closedOpenBackward kb1 kb2)
    :greater-than      (KeyRange/greaterThan kb1)
    :greater-than-back (KeyRange/greaterThanBackward kb1)
    :less-than         (KeyRange/lessThan kb1)
    :less-than-back    (KeyRange/lessThanBackward kb1)
    :open              (KeyRange/open kb1 kb2)
    :open-back         (KeyRange/openBackward kb1 kb2)
    :open-closed       (KeyRange/openClosed kb1 kb2)
    :open-closed-back  (KeyRange/openClosedBackward kb1 kb2)))

(deftype DBI [^Dbi db ^ByteBuffer kb ^:volatile-mutable ^ByteBuffer vb]
  IBuffer
  (put-key [_ x t]
    (b/put-buffer kb x t))
  (put-val [_ x t]
    (try
      (b/put-buffer vb x t)
      (catch Exception e
        (if (s/includes? (ex-message e) c/buffer-overflow)
          (let [size (b/measure-size x)]
            (set! vb (ByteBuffer/allocateDirect size))
            (b/put-buffer vb x t))
          (throw e)))))

  IKV
  (put [this txn]
    (put this txn nil))
  (put [_ txn flags]
    (.flip kb)
    (.flip vb)
    (if flags
      (.put db txn kb vb (into-array PutFlags flags))
      (.put db txn kb vb (make-array PutFlags 0)))
    (.clear kb)
    (.clear vb))
  (del [_ txn]
    (.flip kb)
    (.delete db txn kb)
    (.clear kb))
  (get [_ rtx]
    (let [^ByteBuffer kb (.-kb ^Rtx rtx)]
      (.flip kb)
      (let [res (.get db (.-txn ^Rtx rtx) kb)]
        (.clear kb)
        res)))
  (iterate [this rtx range-type]
    (let [^ByteBuffer start-kb (.-start-kb ^Rtx rtx)
          ^ByteBuffer stop-kb (.-stop-kb ^Rtx rtx)]
      (.flip start-kb)
      (.flip stop-kb)
      (.iterate db (.-txn ^Rtx rtx) (key-range range-type start-kb stop-kb)))))

(defn- ^IMapEntry map-entry
  [^CursorIterable$KeyVal kv]
  (reify IMapEntry
    (key [_] (.key kv))
    (val [_] (.val kv))
    (equals [_ o] (and (= (.key kv) (key o)) (= (.val kv) (val o))))
    (getKey [_] (.key kv))
    (getValue [_] (.val kv))
    (setValue [_ _] (raise "IMapEntry is immutable"))
    (hashCode [_] (hash-combine (hash (.key kv)) (hash (.val kv))))))

(defprotocol ILMDB
  (close [this] "Close this LMDB env")
  (open-dbi
    [this dbi-name]
    [this dbi-name key-size]
    [this dbi-name key-size val-size]
    [this dbi-name key-size val-size flags]
    "Open a named dbi (i.e. sub-db) in the LMDB")
  (get-dbi [this dbi-name] "Lookup DBI (i.e. sub-db) by name")
  (entries [this dbi-name] "Get the number of data entries in a dbi")
  (transact [this txs]
    "Update db, txs is a seq of [op dbi-name k v k-type v-type put-flags]
     when op is :put; [op dbi-name k k-type] when op is :del;
     See `bits/put-buffer` for allowed k-type and v-type")
  (get-value
    [this dbi-name k]
    [this dbi-name k k-type]
    [this dbi-name k k-type v-type]
    [this dbi-name k k-type v-type ignore-key?]
    "Get kv pair of the specified key, k-type and v-type can be
     :data (default), :byte, :bytes, :attr, :datom or :long;
     if ignore-key? (default true), only return value")
  (get-first
    [this dbi-name k-range]
    [this dbi-name k-range k-type]
    [this dbi-name k-range k-type v-type]
    [this dbi-name k-range k-type v-type ignore-key?]
    "Return the first kv pair in the specified key range;
     k-range is a vector [range-type k1 k2], range-type can be one of
     :all, :at-least, :at-most, :closed, :closed-open, :greater-than,
     :less-than, :open, :open-closed, plus backward variants that put a
     `-back` suffix to each of the above, e.g. :all-back;
     k-type and v-type can be :data (default), :long, :byte, :bytes, :datom,
     or :attr; only the value will be returned if ignore-key? is true;
     If value is to be ignored, put :ignore as v-type")
  (get-range
    [this dbi-name k-range]
    [this dbi-name k-range k-type]
    [this dbi-name k-range k-type v-type]
    [this dbi-name k-range k-type v-type ignore-key?]
    "Return a seq of kv pair in the specified key range;
     k-range is a vector [range-type k1 k2], range-type can be one of
     :all, :at-least, :at-most, :closed, :closed-open, :greater-than,
     :less-than, :open, :open-closed, plus backward variants that put a
     `-back` suffix to each of the above, e.g. :all-back;
     k-type and v-type can be :data (default), :long, :byte, :bytes, :datom,
     or :attr; only values will be returned if ignore-key? is true;
     If value is to be ignored, put :ignore as v-type")
  (get-some
    [this dbi-name pred k-range]
    [this dbi-name pred k-range k-type]
    [this dbi-name pred k-range k-type v-type]
    [this dbi-name pred k-range k-type v-type ignore-key?]
    "Return the first kv pair that has logical true value of (pred x),
     x is an IMapEntry , in the specified key range, or return nil;
     k-range is a vector [range-type k1 k2], range-type can be one of
     :all, :at-least, :at-most, :closed, :closed-open, :greater-than,
     :less-than, :open, :open-closed, plus backward variants that put a
     `-back` suffix to each of the above, e.g. :all-back;
     k-type and v-type can be :data (default), :long, :byte, :bytes, :datom,
     or :attr; only values will be returned if ignore-key? is true")
  (range-filter
    [this dbi-name pred k-range]
    [this dbi-name pred k-range k-type]
    [this dbi-name pred k-range k-type v-type]
    [this dbi-name pred k-range k-type v-type ignore-key?]
    "Return a seq of kv pair in the specified key range, for only those
     return true value for (pred x), where x is an IMapEntry;
     k-range is a vector [range-type k1 k2], range-type can be one of
     :all, :at-least, :at-most, :closed, :closed-open, :greater-than,
     :less-than, :open, :open-closed, plus backward variants that put a
     `-back` suffix to each of the above, e.g. :all-back;
     k-type and v-type can be :data (default), :long, :byte, :bytes, :datom,
     or :attr; only values will be returned if ignore-key? is true;
     If value is to be ignored, put :ignore as v-type"))

(defn- up-db-size [^Env env]
  (.setMapSize env (* 10 (-> env .info .mapSize))))

(defn- fetch-value
  [^DBI dbi ^Rtx rtx k k-type v-type ignore-key?]
  (put-key rtx k k-type)
  (when-let [^ByteBuffer bb (get dbi rtx)]
    (if ignore-key?
      (b/read-buffer bb v-type)
      [(b/expected-return k k-type) (b/read-buffer bb v-type)])))

(defn- fetch-first
  [^DBI dbi ^Rtx rtx [range-type k1 k2] k-type v-type ignore-key?]
  (assert (not (and (= v-type :ignore) ignore-key?))
          "Cannot ignore both key and value")
  (put-start-key rtx k1 k-type)
  (put-stop-key rtx k2 k-type)
  (with-open [^CursorIterable iterable (iterate dbi rtx range-type)]
    (let [^Iterator iter (.iterator iterable)]
      (when (.hasNext iter)
        (let [kv (map-entry (.next iter))
              v  (when (not= v-type :ignore) (b/read-buffer (val kv) v-type))]
          (if ignore-key?
           v
           [(b/read-buffer (key kv) k-type) v]))))))

(defn- fetch-range
  [^DBI dbi ^Rtx rtx [range-type k1 k2] k-type v-type ignore-key?]
  (assert (not (and (= v-type :ignore) ignore-key?))
          "Cannot ignore both key and value")
  (put-start-key rtx k1 k-type)
  (put-stop-key rtx k2 k-type)
  (with-open [^CursorIterable iterable (iterate dbi rtx range-type)]
    (loop [^Iterator iter (.iterator iterable)
           holder         (transient [])]
      (if (.hasNext iter)
        (let [kv      (map-entry (.next iter))
              v       (when (not= v-type :ignore)
                        (b/read-buffer (val kv) v-type))
              holder' (conj! holder
                             (if ignore-key?
                               v
                               [(b/read-buffer (key kv) k-type) v]))]
          (recur iter holder'))
        (persistent! holder)))))

(defn- fetch-some
  [^DBI dbi ^Rtx rtx pred [range-type k1 k2] k-type v-type ignore-key?]
  (assert (not (and (= v-type :ignore) ignore-key?))
          "Cannot ignore both key and value")
  (put-start-key rtx k1 k-type)
  (put-stop-key rtx k2 k-type)
  (with-open [^CursorIterable iterable (iterate dbi rtx range-type)]
    (loop [^Iterator iter (.iterator iterable)]
      (when (.hasNext iter)
        (let [kv (map-entry (.next iter))]
          (if (pred kv)
            (let [v (when (not= v-type :ignore)
                      (b/read-buffer (.rewind ^ByteBuffer (val kv)) v-type))]
              (if ignore-key?
                v
                [(b/read-buffer (.rewind ^ByteBuffer (key kv)) k-type) v]))
            (recur iter)))))))

(defn- fetch-range-filtered
  [^DBI dbi ^Rtx rtx pred [range-type k1 k2] k-type v-type ignore-key?]
  (assert (not (and (= v-type :ignore) ignore-key?))
          "Cannot ignore both key and value")
  (put-start-key rtx k1 k-type)
  (put-stop-key rtx k2 k-type)
  (with-open [^CursorIterable iterable (iterate dbi rtx range-type)]
    (loop [^Iterator iter (.iterator iterable)
           holder         (transient [])]
      (if (.hasNext iter)
        (let [kv (map-entry (.next iter))]
          (if (pred kv)
            (let [v       (when (not= v-type :ignore)
                            (b/read-buffer
                             (.rewind ^ByteBuffer (val kv)) v-type))
                  holder' (conj! holder
                                 (if ignore-key?
                                   v
                                   [(b/read-buffer
                                     (.rewind ^ByteBuffer (key kv)) k-type)
                                    v]))]
              (recur iter holder'))
            (recur iter holder)))
        (persistent! holder)))))

(deftype LMDB [^Env env ^String dir ^RtxPool pool ^ConcurrentHashMap dbis]
  ILMDB
  (close [_]
    (close-pool pool)
    (.close env))
  (open-dbi [this dbi-name]
    (open-dbi this dbi-name c/+max-key-size+ c/+default-val-size+
              default-dbi-flags))
  (open-dbi [this dbi-name key-size]
    (open-dbi this dbi-name key-size c/+default-val-size+ default-dbi-flags))
  (open-dbi [this dbi-name key-size val-size]
    (open-dbi this dbi-name key-size val-size default-dbi-flags))
  (open-dbi [_ dbi-name key-size val-size flags]
    (let [kb  (ByteBuffer/allocateDirect key-size)
          vb  (ByteBuffer/allocateDirect val-size)
          db  (.openDbi env
                        ^String dbi-name
                        ^"[Lorg.lmdbjava.DbiFlags;" (into-array DbiFlags flags))
          dbi (->DBI db kb vb)]
      (.put dbis dbi-name dbi)
      dbi))
  (get-dbi [_ dbi-name]
    (or (.get dbis dbi-name)
        (raise "`open-dbi` was not called for " dbi-name {})))
  (entries [this dbi-name]
    (let [^DBI dbi   (get-dbi this dbi-name)
          ^Rtx rtx   (get-rtx pool)
          ^Stat stat (.stat ^Dbi (.-db dbi) (.-txn rtx))]
      (.-entries stat)))
  (transact [this txs]
    (try
      (with-open [txn (.txnWrite env)]
        (doseq [[op dbi-name k & r] txs
                :let                [dbi (get-dbi this dbi-name)]]
          (case op
            :put (let [[v kt vt flags] r]
                   (put-key dbi k kt)
                   (put-val dbi v vt)
                   (if flags
                     (put dbi txn flags)
                     (put dbi txn)))
            :del (let [[kt] r]
                   (put-key dbi k kt)
                   (del dbi txn))))
        (.commit txn))
      (catch Env$MapFullException e
        (up-db-size env)
        (transact this txs))
      #_(catch Exception e
        (raise "Fail to transact: " (ex-message e) {:txs txs}))))
  (get-value [this dbi-name k]
    (get-value this dbi-name k :data :data true))
  (get-value [this dbi-name k k-type]
    (get-value this dbi-name k k-type :data true))
  (get-value [this dbi-name k k-type v-type]
    (get-value this dbi-name k k-type v-type true))
  (get-value [this dbi-name k k-type v-type ignore-key?]
    (let [dbi (get-dbi this dbi-name)
          rtx (get-rtx pool)]
      (try
        (fetch-value dbi rtx k k-type v-type ignore-key?)
        (catch Exception e
          (raise "Fail to get-value: " (ex-message e)
                 {:dbi dbi-name :k k :k-type k-type :v-type v-type}))
        (finally (reset rtx)))))
  (get-first [this dbi-name k-range]
    (get-first this dbi-name k-range :data :data false))
  (get-first [this dbi-name k-range k-type]
    (get-first this dbi-name k-range k-type :data false))
  (get-first [this dbi-name k-range k-type v-type]
    (get-first this dbi-name k-range k-type v-type false))
  (get-first [this dbi-name k-range k-type v-type ignore-key?]
    (let [dbi (get-dbi this dbi-name)
          rtx (get-rtx pool)]
      (try
        (fetch-first dbi rtx k-range k-type v-type ignore-key?)
        (catch Exception e
          (raise "Fail to get-first: " (ex-message e)
                 {:dbi    dbi-name :k-range k-range
                  :k-type k-type   :v-type  v-type}))
        (finally (reset rtx)))))
  (get-range [this dbi-name k-range]
    (get-range this dbi-name k-range :data :data false))
  (get-range [this dbi-name k-range k-type]
    (get-range this dbi-name k-range k-type :data false))
  (get-range [this dbi-name k-range k-type v-type]
    (get-range this dbi-name k-range k-type v-type false))
  (get-range [this dbi-name k-range k-type v-type ignore-key?]
    (let [dbi (get-dbi this dbi-name)
          rtx (get-rtx pool)]
      (try
        (fetch-range dbi rtx k-range k-type v-type ignore-key?)
        (catch Exception e
          (raise "Fail to get-range: " (ex-message e)
                 {:dbi    dbi-name :k-range k-range
                  :k-type k-type   :v-type  v-type}))
        (finally (reset rtx)))))
  (get-some [this dbi-name pred k-range]
    (get-some this dbi-name pred k-range :data :data false))
  (get-some [this dbi-name pred k-range k-type]
    (get-some this dbi-name pred k-range k-type :data false))
  (get-some [this dbi-name pred k-range k-type v-type]
    (get-some this dbi-name pred k-range k-type v-type false))
  (get-some [this dbi-name pred k-range k-type v-type ignore-key?]
    (let [dbi (get-dbi this dbi-name)
          rtx (get-rtx pool)]
      (try
        (fetch-some dbi rtx pred k-range k-type v-type ignore-key?)
        (catch Exception e
          (raise "Fail to get-some: " (ex-message e)
                 {:dbi    dbi-name :k-range k-range
                  :k-type k-type   :v-type  v-type}))
        (finally (reset rtx)))))
  (range-filter [this dbi-name pred k-range]
    (range-filter this dbi-name pred k-range :data :data false))
  (range-filter [this dbi-name pred k-range k-type]
    (range-filter this dbi-name pred k-range k-type :data false))
  (range-filter [this dbi-name pred k-range k-type v-type]
    (range-filter this dbi-name pred k-range k-type v-type false))
  (range-filter [this dbi-name pred k-range k-type v-type ignore-key?]
    (let [dbi (get-dbi this dbi-name)
          rtx (get-rtx pool)]
      (try
        (fetch-range-filtered dbi rtx pred k-range k-type v-type ignore-key?)
        (catch Exception e
          (raise "Fail to range-filter: " (ex-message e)
                 {:dbi    dbi-name :k-range k-range
                  :k-type k-type   :v-type  v-type}))
        (finally (reset rtx))))))

(defn open-lmdb
  "Open an LMDB env"
  ([dir]
   (open-lmdb dir c/+init-db-size+ default-env-flags))
  ([dir size flags]
   (let [file          (b/file dir)
         builder       (doto (Env/create)
                         (.setMapSize (* ^long size 1024 1024))
                         (.setMaxReaders c/+max-readers+)
                         (.setMaxDbs c/+max-dbs+))
         ^Env env      (.open builder file (into-array EnvFlags flags))
         ^RtxPool pool (->RtxPool env (ConcurrentHashMap.) 0)]
     (new-rtx pool)
     (->LMDB env dir pool (ConcurrentHashMap.)))))
