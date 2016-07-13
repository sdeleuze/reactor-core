/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.scheduler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import reactor.core.flow.Cancellation;
import reactor.core.state.Introspectable;
import reactor.core.util.Exceptions;
import reactor.core.util.PlatformDependent;

/**
 * {@link Schedulers} provide various {@link Scheduler} generator useable by {@link
 * reactor.core.publisher.Flux#publishOn publishOn} or {@link reactor.core.publisher.Mono#subscribeOn
 * subscribeOn} :
 * <p>
 * <ul> <li>{@link #fromExecutorService(ExecutorService)}}. </li> <li>{@link #newParallel}
 * : Optimized for fast {@link Runnable} executions </li> <li>{@link #single} : Optimized
 * for low-latency {@link Runnable} executions </li> <li>{@link #immediate}. </li> </ul>
 *
 * @author Stephane Maldini
 */
public class Schedulers {

	/**
	 * {@link Scheduler} that hosts a fixed pool of single-threaded Event Loop based
	 * workers and is suited for non blocking work.
	 *
	 * @return a reusable {@link Scheduler} that hosts a fixed pool of single-threaded
	 * Event-Loop based workers
	 */
	public static Scheduler computation() {
		return cache(COMPUTATION, () -> newComputation(COMPUTATION, (Runtime.getRuntime()
                                    .availableProcessors() + 1) / 2,
                            true));
	}

	/**
	 * Create a {@link Scheduler} which uses a backing {@link Executor} to schedule
	 * Runnables for async operators.
	 *
	 * @param executor an {@link Executor}
	 *
	 * @return a new {@link Scheduler}
	 */
	public static Scheduler fromExecutor(Executor executor) {
		return fromExecutor(executor, false);
	}

	/**
	 * Create a {@link Scheduler} which uses a backing {@link Executor} to schedule
	 * Runnables for async operators.
	 *
	 * @param executor an {@link Executor}
	 * @param trampoline set to false if this {@link Scheduler} is used by "operators"
	 * that already conflate {@link Runnable} executions (publishOn, subscribeOn...)
	 *
	 * @return a new {@link Scheduler}
	 */
	public static Scheduler fromExecutor(Executor executor, boolean trampoline) {
		return new ExecutorScheduler(executor, trampoline);
	}

	/**
	 * Create a {@link Scheduler} which uses a backing {@link ExecutorService} to schedule
	 * Runnables for async operators.
	 *
	 * @param executorService an {@link ExecutorService}
	 *
	 * @return a new {@link Scheduler}
	 */
	public static Scheduler fromExecutorService(ExecutorService executorService) {
		return new ExecutorServiceScheduler(executorService);
	}

	/**
	 * {@link Scheduler} that dynamically creates ExecutorService-based Workers and caches
	 * the thread pools, reusing them once the Workers have been shut down.
	 * <p>
	 * The maximum number of created thread pools is unbounded.
	 * <p>
	 * The default time-to-live for unused thread pools is 60 seconds, use the appropriate
	 * factory to set a different value.
	 * <p>
	 * This scheduler is not restartable.
	 *
	 * @return a new {@link Scheduler} that hosts a fixed pool of single-threaded
	 * ExecutorService-based workers and is suited for parallel work
	 */
	public static Scheduler elastic() {
		return cache(ELASTIC,
				() -> newElastic(ELASTIC, ElasticScheduler.DEFAULT_TTL_SECONDS, true));
	}

	/**
	 * Executes tasks on the caller's thread immediately.
	 *
	 * @return a reusable {@link Scheduler}
	 */
	public static Scheduler immediate() {
		return ImmediateScheduler.instance();
	}

	/**
	 * {@link Scheduler} that hosts a fixed pool of single-threaded Event Loop based
	 * workers and is suited for non blocking work.
	 *
	 * @param name Thread prefix
	 *
	 * @return a new {@link Scheduler} that hosts a fixed pool of single-threaded
	 * ExecutorService-based workers and is suited for parallel work
	 */
	public static Scheduler newComputation(String name) {
		return newComputation(name,
				(Runtime.getRuntime()
				        .availableProcessors() + 1) / 2,
				false);
	}

	/**
	 * {@link Scheduler} that hosts a fixed pool of single-threaded Event Loop based
	 * workers and is suited for non blocking work.
	 *
	 * @param name Thread prefix
	 * @param parallelism Number of pooled workers.
	 *
	 * @return a new {@link Scheduler} that hosts a fixed pool of single-threaded
	 * ExecutorService-based workers and is suited for parallel work
	 */
	public static Scheduler newComputation(String name, int parallelism) {
		return newComputation(name, parallelism, false);
	}

	/**
	 * {@link Scheduler} that hosts a fixed pool of single-threaded Event Loop based
	 * workers and is suited for non blocking work.
	 *
	 * @param name Thread prefix
	 * @param parallelism Number of pooled workers.
	 * @param bufferSize backlog size to be used by event loops.
	 *
	 * @return a new {@link Scheduler} that hosts a fixed pool of single-threaded
	 * ExecutorService-based workers and is suited for parallel work
	 */
	public static Scheduler newComputation(String name, int parallelism, int bufferSize) {
		return newComputation(name, parallelism, bufferSize, false);
	}

	/**
	 * {@link Scheduler} that hosts a fixed pool of single-threaded Event Loop based
	 * workers and is suited for non blocking work.
	 *
	 * @param name Thread prefix
	 * @param parallelism Number of pooled workers.
	 * @param daemon false if the {@link Scheduler} requires an explicit {@link
	 * Scheduler#shutdown()} to exit the VM.
	 *
	 * @return a new {@link Scheduler} that hosts a fixed pool of single-threaded
	 * ExecutorService-based workers and is suited for parallel work
	 */
	public static Scheduler newComputation(String name, int parallelism, boolean daemon) {
		return newComputation(name,
				parallelism,
				PlatformDependent.MEDIUM_BUFFER_SIZE,
				daemon);
	}

	/**
	 * {@link Scheduler} that hosts a fixed pool of single-threaded Event Loop based
	 * workers and is suited for non blocking work.
	 *
	 * @param name Thread prefix
	 * @param parallelism Number of pooled workers.
	 * @param bufferSize backlog size to be used by event loops.
	 * @param daemon false if the {@link Scheduler} requires an explicit {@link
	 * Scheduler#shutdown()} to exit the VM.
	 *
	 * @return a new {@link Scheduler} that hosts a fixed pool of single-threaded
	 * ExecutorService-based workers and is suited for parallel work
	 */
	public static Scheduler newComputation(String name,
			int parallelism,
			int bufferSize,
			boolean daemon) {
		return newComputation(parallelism,
				bufferSize, new SchedulerFactory(name, daemon, new AtomicLong()));
	}

	/**
	 * {@link Scheduler} that hosts a fixed pool of single-threaded Event Loop based
	 * workers and is suited for non blocking work.
	 *
	 * @param parallelism Number of pooled workers.
	 * @param bufferSize backlog size to be used by event loops.
	 * @param threadFactory a {@link ThreadFactory} to use for the unique thread of the
	 * {@link Scheduler}
	 *
	 * @return a new {@link Scheduler} that hosts a fixed pool of single-threaded
	 * ExecutorService-based workers and is suited for parallel work
	 */
	public static Scheduler newComputation(int parallelism,
			int bufferSize,
			ThreadFactory threadFactory) {
		try {
			return (Scheduler) COMPUTATION_FACTORY.get()
			                                      .invoke(null,
					                                      parallelism,
					                                      bufferSize,
					                                      threadFactory);
		}
		catch (Exception e) {
			throw Exceptions.bubble(e);
		}
	}

	/**
	 * {@link Scheduler} that dynamically creates ExecutorService-based Workers and caches
	 * the thread pools, reusing them once the Workers have been shut down.
	 * <p>
	 * The maximum number of created thread pools is unbounded.
	 * <p>
	 * The default time-to-live for unused thread pools is 60 seconds, use the appropriate
	 * factory to set a different value.
	 * <p>
	 * This scheduler is not restartable.
	 *
	 * @param name Thread prefix
	 *
	 * @return a new {@link Scheduler} that hosts a fixed pool of single-threaded
	 * ExecutorService-based workers and is suited for parallel work
	 */
	public static Scheduler newElastic(String name) {
		return newElastic(name, ElasticScheduler.DEFAULT_TTL_SECONDS);
	}

	/**
	 * {@link Scheduler} that dynamically creates ExecutorService-based Workers and caches
	 * the thread pools, reusing them once the Workers have been shut down.
	 * <p>
	 * The maximum number of created thread pools is unbounded.
	 * <p>
	 * This scheduler is not restartable.
	 *
	 * @param name Thread prefix
	 * @param ttlSeconds Time-to-live for an idle {@link reactor.core.scheduler.Scheduler.Worker}
	 *
	 * @return a new {@link Scheduler} that hosts a fixed pool of single-threaded
	 * ExecutorService-based workers and is suited for parallel work
	 */
	public static Scheduler newElastic(String name, int ttlSeconds) {
		return newElastic(name, ttlSeconds, false);
	}

	/**
	 * {@link Scheduler} that dynamically creates ExecutorService-based Workers and caches
	 * the thread pools, reusing them once the Workers have been shut down.
	 * <p>
	 * The maximum number of created thread pools is unbounded.
	 * <p>
	 * This scheduler is not restartable.
	 *
	 * @param name Thread prefix
	 * @param ttlSeconds Time-to-live for an idle {@link reactor.core.scheduler.Scheduler.Worker}
	 * @param daemon false if the {@link Scheduler} requires an explicit {@link
	 * Scheduler#shutdown()} to exit the VM.
	 *
	 * @return a new {@link Scheduler} that hosts a fixed pool of single-threaded
	 * ExecutorService-based workers and is suited for parallel work
	 */
	public static Scheduler newElastic(String name, int ttlSeconds, boolean daemon) {
		return new ElasticScheduler(new SchedulerFactory(name,
				daemon,
				ElasticScheduler.COUNTER), ttlSeconds);
	}

	/**
	 * {@link Scheduler} that dynamically creates ExecutorService-based Workers and caches
	 * the thread pools, reusing them once the Workers have been shut down.
	 * <p>
	 * The maximum number of created thread pools is unbounded.
	 * <p>
	 * This scheduler is not restartable.
	 *
	 * @param ttlSeconds Time-to-live for an idle {@link reactor.core.scheduler.Scheduler.Worker}
	 * @param threadFactory a {@link ThreadFactory} to use for the unique thread of the
	 * {@link Scheduler}
	 *
	 * @return a new {@link Scheduler} that dynamically creates ExecutorService-based
	 * Workers and caches the thread pools, reusing them once the Workers have been shut
	 * down.
	 */
	public static Scheduler newElastic(int ttlSeconds, ThreadFactory threadFactory) {
		return new ElasticScheduler(threadFactory, ttlSeconds);
	}

	/**
	 * {@link Scheduler} that hosts a fixed pool of single-threaded ExecutorService-based
	 * workers and is suited for parallel work.
	 *
	 * @param name Thread prefix
	 *
	 * @return a new {@link Scheduler} that hosts a fixed pool of single-threaded
	 * ExecutorService-based workers and is suited for parallel work
	 */
	public static Scheduler newParallel(String name) {
		return newParallel(name, Runtime.getRuntime()
				       .availableProcessors());
	}

	/**
	 * {@link Scheduler} that hosts a fixed pool of single-threaded ExecutorService-based
	 * workers and is suited for parallel work.
	 *
	 * @param name Thread prefix
	 * @param parallelism Number of pooled workers.
	 *
	 * @return a new {@link Scheduler} that hosts a fixed pool of single-threaded
	 * ExecutorService-based workers and is suited for parallel work
	 */
	public static Scheduler newParallel(String name, int parallelism) {
		return newParallel(name, parallelism, false);
	}

	/**
	 * {@link Scheduler} that hosts a fixed pool of single-threaded ExecutorService-based
	 * workers and is suited for parallel work.
	 *
	 * @param name Thread prefix
	 * @param parallelism Number of pooled workers.
	 * @param daemon false if the {@link Scheduler} requires an explicit {@link
	 * Scheduler#shutdown()} to exit the VM.
	 *
	 * @return a new {@link Scheduler} that hosts a fixed pool of single-threaded
	 * ExecutorService-based workers and is suited for parallel work
	 */
	public static Scheduler newParallel(String name, int parallelism, boolean daemon) {
		return new ParallelScheduler(parallelism, new SchedulerFactory(name, daemon,
				ParallelScheduler.COUNTER));
	}

	/**
	 * {@link Scheduler} that hosts a fixed pool of single-threaded ExecutorService-based
	 * workers and is suited for parallel work.
	 *
	 * @param parallelism Number of pooled workers.
	 * @param threadFactory a {@link ThreadFactory} to use for the unique thread of the
	 * {@link Scheduler}
	 *
	 * @return a new {@link Scheduler} that hosts a fixed pool of single-threaded
	 * ExecutorService-based workers and is suited for parallel work
	 */
	public static Scheduler newParallel(int parallelism, ThreadFactory threadFactory) {
		return new ParallelScheduler(parallelism, threadFactory);
	}

	/**
	 * {@link Scheduler} that hosts a single-threaded ExecutorService-based worker and is
	 * suited for parallel work.
	 *
	 * @param name Component and thread name prefix
	 *
	 * @return a new {@link Scheduler} that hosts a single-threaded ExecutorService-based
	 * worker
	 */
	public static Scheduler newSingle(String name) {
		return newSingle(name, false);
	}

	/**
	 * {@link Scheduler} that hosts a single-threaded ExecutorService-based worker and is
	 * suited for parallel work.
	 *
	 * @param name Component and thread name prefix
	 * @param daemon false if the {@link Scheduler} requires an explicit {@link
	 * Scheduler#shutdown()} to exit the VM.
	 *
	 * @return a new {@link Scheduler} that hosts a single-threaded ExecutorService-based
	 * worker
	 */
	public static Scheduler newSingle(String name, boolean daemon) {
		return new SingleScheduler(new SchedulerFactory(name, daemon,
				SingleScheduler.COUNTER));
	}

	/**
	 * {@link Scheduler} that hosts a single-threaded ExecutorService-based worker and is
	 * suited for parallel work.
	 *
	 * @param threadFactory a {@link ThreadFactory} to use for the unique thread of the
	 * {@link Scheduler}
	 *
	 * @return a new {@link Scheduler} that hosts a single-threaded ExecutorService-based
	 * worker
	 */
	public static Scheduler newSingle(ThreadFactory threadFactory) {
		return new ParallelScheduler(1, threadFactory);
	}

	/**
	 * Create a new {@link TimedScheduler} backed by a single threaded
	 * {@link java.util.concurrent.ScheduledExecutorService}.
	 *
	 * @param name timer thread prefix
	 * @return a new {@link TimedScheduler}
	 */
	public static TimedScheduler newTimer(String name) {
		return newTimer(name, true);
	}

	/**
	 * Create a new {@link TimedScheduler} backed by a single threaded
	 * {@link java.util.concurrent.ScheduledExecutorService}.
	 *
	 * @param name Component and thread name prefix
	 * @param daemon false if the {@link Scheduler} requires an explicit {@link
	 * Scheduler#shutdown()} to exit the VM.
	 *
	 * @return a new {@link TimedScheduler}
	 */
	public static TimedScheduler newTimer(String name, boolean daemon) {
		return newTimer(new SchedulerFactory(name, daemon, SingleScheduler.COUNTER));
	}

	/**
	 * Create a new {@link TimedScheduler} backed by a single threaded
	 * {@link java.util.concurrent.ScheduledExecutorService}.
	 *
	 * @param threadFactory a {@link ThreadFactory} to use for the unique thread of the
	 * {@link Scheduler}
	 *
	 * @return a new {@link TimedScheduler}
	 */
	public static TimedScheduler newTimer(ThreadFactory threadFactory) {
		return new SingleTimedScheduler(threadFactory);
	}

	/**
	 * {@link Scheduler} that hosts a fixed pool of single-threaded ExecutorService-based
	 * workers and is suited for parallel work.
	 *
	 * @return a reusable {@link Scheduler} that hosts a fixed pool of single-threaded ExecutorService-based
	 * workers
	 */
	public static Scheduler parallel() {
		return cache(PARALLEL,
				() -> newParallel(PARALLEL,
						Runtime.getRuntime()
						       .availableProcessors(),
						true));
	}

	/**
	 * Assign a {@link #newComputation} factory using the matching method signature in
	 * the target class.
	 *
	 * @param factoryClass an arbitrary type with static methods matching {@link
	 * #newComputation} signature(s).
	 */
	public static void setComputationFactory(Class<?> factoryClass) {
		Objects.requireNonNull(factoryClass, "factoryClass");
		try {
			Method m = factoryClass.getDeclaredMethod(NEW_COMPUTATION,
					int.class,
					int.class,
					ThreadFactory.class);
			m.setAccessible(true);
			COMPUTATION_FACTORY.lazySet(m);
		}
		catch (NoSuchMethodException e) {
			throw Exceptions.bubble(e);
		}
	}

	/**
	 * Clear any cached {@link Scheduler} and call shutdown on them.
	 */
	public static void shutdownNow() {
		List<CachedScheduler> schedulers;
		Collection<CachedScheduler> view = cachedSchedulers.values();
		for (; ; ) {
			schedulers = new ArrayList<>(view);
			view.clear();
			schedulers.forEach(CachedScheduler::_shutdown);
			if (view.isEmpty()) {
				return;
			}
		}
	}

	/**
	 * {@link Scheduler} that hosts a single-threaded ExecutorService-based worker and is
	 * suited for parallel work. Will cache the returned schedulers for subsequent {@link
	 * #single} calls until shutdown.
	 *
	 * @return a cached {@link Scheduler} that hosts a single-threaded
	 * ExecutorService-based worker
	 */
	public static Scheduler single() {
		return cache(SINGLE, () -> newSingle(SINGLE, true));
	}

	/**
	 * Wraps a single worker of some other {@link Scheduler} and
	 * provides {@link reactor.core.scheduler.Scheduler.Worker} services on top of it.
	 * <p>
	 * Use the {@link Scheduler#shutdown()} to release the wrapped worker.
	 *
	 * @param original a {@link Scheduler} to call upon to get the single
	 * {@link reactor.core.scheduler.Scheduler.Worker}
	 *
	 * @return a wrapping {@link Scheduler} consistently returning a same worker from a
	 * source {@link Scheduler}
	 */
	public static Scheduler single(Scheduler original) {
		return new SingleWorkerScheduler(original);
	}

	/**
	 * Create or reuse a hash-wheel based {@link TimedScheduler} with a resolution of 50MS
	 * All times will rounded up to the closest multiple of this resolution.
	 *
	 * @return a cached hash-wheel based {@link TimedScheduler}
	 */
	public static TimedScheduler timer() {
		return timedCache(TIMER, () -> newTimer(TIMER)).asTimedScheduler();
	}

	// Internals

	static final String COMPUTATION     = "computation";
	static final String ELASTIC         = "elastic";
	static final String NEW_COMPUTATION = "newComputation";
	static final String PARALLEL        = "parallel";
	static final String SINGLE          = "single";
	static final String TIMER           = "timer";

	static final AtomicReference<Method>                COMPUTATION_FACTORY =
			new AtomicReference<>();
	static final ConcurrentMap<String, CachedScheduler> cachedSchedulers    =
			new ConcurrentHashMap<>();

	static {
		try {
			Class<?> factory = Schedulers.class.getClassLoader()
			                                   .loadClass(
					                                   "reactor.core.publisher.TopicProcessor");
			setComputationFactory(factory);
		}
		catch (Exception e) {
			throw Exceptions.bubble(e);
		}
	}

	static CachedScheduler cache(String key, Supplier<Scheduler> schedulerSupplier) {
		for (; ; ) {
			CachedScheduler s = cachedSchedulers.get(key);
			if (s != null) {
				return s;
			}
			s = new CachedScheduler(key, schedulerSupplier.get());
			if (cachedSchedulers.putIfAbsent(key, s) == null) {
				return s;
			}
			s._shutdown();
		}
	}

	static CachedScheduler timedCache(String key, Supplier<TimedScheduler> schedulerSupplier) {
		for (; ; ) {
			CachedScheduler s = cachedSchedulers.get(key);
			if (s != null) {
				return s;
			}
			s = new CachedTimedScheduler(key, schedulerSupplier.get());
			if (cachedSchedulers.putIfAbsent(key, s) == null) {
				return s;
			}
			s._shutdown();
		}
	}

	static final class SchedulerFactory implements ThreadFactory, Introspectable {

		final String     name;
		final boolean    daemon;
		final AtomicLong COUNTER;

		SchedulerFactory(String name, boolean daemon, AtomicLong counter) {
			this.name = name;
			this.daemon = daemon;
			this.COUNTER = counter;
		}

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r, name + "-" + COUNTER.incrementAndGet());
			t.setDaemon(daemon);
			return t;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public Object getId() {
			return null;
		}

		@Override
		public int getMode() {
			return 0;
		}

		@Override
		public Throwable getError() {
			return null;
		}

		@Override
		public long getPeriod() {
			return -1L;
		}
	}

	static class CachedScheduler implements Scheduler {

		final Scheduler cached;
		final String    key;

		CachedScheduler(String key, Scheduler cached) {
			this.cached = cached;
			this.key = key;
		}

		@Override
		public Cancellation schedule(Runnable task) {
			return cached.schedule(task);
		}

		@Override
		public Worker createWorker() {
			return cached.createWorker();
		}

		@Override
		public void start() {
			cached.start();
		}

		@Override
		public void shutdown() {
		}

		void _shutdown() {
			cached.shutdown();
		}

		TimedScheduler asTimedScheduler() {
			throw new UnsupportedOperationException("Scheduler is not Timed");
		}
	}

	static final class CachedTimedScheduler extends CachedScheduler
			implements TimedScheduler {

		final TimedScheduler cachedTimed;

		CachedTimedScheduler(String key, TimedScheduler cachedTimed) {
			super(key, cachedTimed);
			this.cachedTimed = cachedTimed;
		}

		@Override
		public Cancellation schedule(Runnable task, long delay, TimeUnit unit) {
			return cachedTimed.schedule(task, delay, unit);
		}

		@Override
		public Cancellation schedulePeriodically(Runnable task,
				long initialDelay,
				long period,
				TimeUnit unit) {
			return cachedTimed.schedulePeriodically(task, initialDelay, period, unit);
		}

		@Override
		public TimedWorker createWorker() {
			return cachedTimed.createWorker();
		}

		@Override
		TimedScheduler asTimedScheduler() {
			return this;
		}

		@Override
		public long now(TimeUnit unit) {
			return unit.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
		}


	}
}
