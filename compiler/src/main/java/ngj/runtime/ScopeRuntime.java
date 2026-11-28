package ngj.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure-JVM structured concurrency helper over virtual threads ({@link Thread#ofVirtual()}).
 */
public final class ScopeRuntime {
    private ScopeRuntime() {}

    public static <T> T runScoped(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread vt = Thread.ofVirtual().start(() -> {
            try {
                result.set(task.call());
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        joinUninterruptibly(vt);
        if (failure.get() != null) {
            throw propagate(failure.get());
        }
        return result.get();
    }

    public static <A, B> Pair<A, B> forkJoin(Callable<A> first, Callable<B> second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");

        AtomicReference<A> resA = new AtomicReference<>();
        AtomicReference<B> resB = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread t1 = Thread.ofVirtual().unstarted(() -> {
            try {
                resA.set(first.call());
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        Thread t2 = Thread.ofVirtual().unstarted(() -> {
            try {
                resB.set(second.call());
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });

        t1.start();
        t2.start();
        joinAll(List.of(t1, t2), failure);

        if (failure.get() != null) {
            throw propagate(failure.get());
        }
        return new Pair<>(resA.get(), resB.get());
    }

    public static <T> List<T> forkAll(List<Callable<T>> tasks) {
        Objects.requireNonNull(tasks, "tasks");
        if (tasks.isEmpty()) {
            return List.of();
        }
        int n = tasks.size();
        List<AtomicReference<T>> slots = new ArrayList<>(n);
        List<Thread> threads = new ArrayList<>(n);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < n; i++) {
            Callable<T> task = Objects.requireNonNull(tasks.get(i), "task[" + i + "]");
            AtomicReference<T> slot = new AtomicReference<>();
            slots.add(slot);
            threads.add(Thread.ofVirtual().unstarted(() -> {
                try {
                    slot.set(task.call());
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }));
        }

        for (Thread t : threads) {
            t.start();
        }
        joinAll(threads, failure);

        if (failure.get() != null) {
            throw propagate(failure.get());
        }
        List<T> results = new ArrayList<>(n);
        for (AtomicReference<T> slot : slots) {
            results.add(slot.get());
        }
        return List.copyOf(results);
    }

    private static void joinAll(List<Thread> threads, AtomicReference<Throwable> failure) {
        boolean interrupted = false;
        for (Thread t : threads) {
            while (true) {
                try {
                    t.join();
                    break;
                } catch (InterruptedException e) {
                    interrupted = true;
                    for (Thread other : threads) {
                        other.interrupt();
                    }
                    failure.compareAndSet(null, e);
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinUninterruptibly(Thread t) {
        boolean interrupted = false;
        while (true) {
            try {
                t.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
                t.interrupt();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static RuntimeException propagate(Throwable t) {
        if (t instanceof RuntimeException re) {
            return re;
        }
        if (t instanceof Error err) {
            throw err;
        }
        return new IllegalStateException("Scoped virtual task failed", t);
    }
}
