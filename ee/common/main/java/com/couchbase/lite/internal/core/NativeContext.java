package com.couchbase.lite.internal.core;

import android.support.annotation.GuardedBy;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

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

    public synchronized void bind(long key, @NonNull T listener) { contexts.put(key, new WeakReference<>(listener)); }

    @Nullable
    public synchronized T getListenerFromContext(long context) {
        Long key = context;

        final WeakReference<T> ref = contexts.get(key);
        if (ref == null) { return null; }

        T javaCompanion = ref.get();
        if (javaCompanion == null) {
            contexts.remove(key);
            return null;
        }

        return javaCompanion;
    }
}
