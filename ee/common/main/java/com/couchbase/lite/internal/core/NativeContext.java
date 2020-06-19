package com.couchbase.lite.internal.core;

import android.support.annotation.GuardedBy;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.couchbase.lite.internal.utils.MathUtils;


// There should be a nanny thread cleaning out all the ref -> null
public class NativeContext<T> {
    @NonNull
    @GuardedBy("this")
    private final Map<Long, WeakReference<T>> contexts = new HashMap<>();

    public synchronized long reserveKey() {
        long key;

        do { key = MathUtils.RANDOM.get().nextLong(); }
        while (contexts.containsKey(key));
        contexts.put(key, null);

        return key;
    }

    public synchronized void bind(long key, @NonNull T obj) { contexts.put(key, new WeakReference<>(obj)); }

    @Nullable
    public synchronized T getObjFromContext(long context) {
        final Long key = context;

        final WeakReference<T> ref = contexts.get(key);
        if (ref == null) { return null; }

        final T obj = ref.get();
        if (obj == null) {
            contexts.remove(key);
            return null;
        }

        return obj;
    }

    @VisibleForTesting
    synchronized int size() { return contexts.size(); }

    @VisibleForTesting
    synchronized void clear() { contexts.clear(); }

    @VisibleForTesting
    synchronized Set<Long> keySet() { return contexts.keySet(); }
}
