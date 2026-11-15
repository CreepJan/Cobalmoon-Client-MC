/*
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package dev.cobalmoon.api.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Small, allocation-free (on post) event bus. Handlers are matched by exact event class.
 * A handler that throws is reported to the error handler and does not stop the others.
 */
public final class EventBus {
    private final Map<Class<?>, List<Consumer<Object>>> handlers = new ConcurrentHashMap<>();
    private final BiConsumer<Object, Throwable> onError;

    public EventBus(BiConsumer<Object, Throwable> onError) {
        this.onError = onError;
    }

    /** Handle returned by {@link #subscribe}; call {@link #cancel()} to stop receiving events. */
    public interface Subscription {
        void cancel();
    }

    @SuppressWarnings("unchecked")
    public <E> Subscription subscribe(Class<E> type, Consumer<? super E> handler) {
        Consumer<Object> h = (Consumer<Object>) handler;
        List<Consumer<Object>> list = handlers.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>());
        list.add(h);
        return () -> list.remove(h);
    }

    /** Delivers the event to every handler subscribed to its exact class. Returns the event. */
    public <E> E post(E event) {
        List<Consumer<Object>> list = handlers.get(event.getClass());
        if (list != null) {
            for (Consumer<Object> h : list) {
                try {
                    h.accept(event);
                } catch (Throwable t) {
                    onError.accept(event, t);
                }
            }
        }
        return event;
    }

    /** True if anyone listens for this class (lets callers skip building costly events). */
    public boolean hasListeners(Class<?> type) {
        List<Consumer<Object>> list = handlers.get(type);
        return list != null && !list.isEmpty();
    }
}
