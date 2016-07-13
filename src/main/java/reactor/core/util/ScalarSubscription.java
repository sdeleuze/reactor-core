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
package reactor.core.util;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Subscriber;
import reactor.core.flow.Fuseable;
import reactor.core.flow.Producer;
import reactor.core.flow.Receiver;

/**
 * Represents a fuseable Subscription that emits a single constant value 
 * synchronously to a Subscriber or consumer.
 *
 * @param <T> the value type
 */
public final class ScalarSubscription<T> extends Fuseable.AbstractQueueSubscription<T> implements Fuseable.QueueSubscription<T>, Producer, Receiver {

	final Subscriber<? super T> actual;

	final T value;

	volatile int once;
	@SuppressWarnings("rawtypes")
	static final AtomicIntegerFieldUpdater<ScalarSubscription> ONCE =
	  AtomicIntegerFieldUpdater.newUpdater(ScalarSubscription.class, "once");

	public ScalarSubscription(Subscriber<? super T> actual, T value) {
		this.value = Objects.requireNonNull(value, "value");
		this.actual = Objects.requireNonNull(actual, "actual");
	}

	@Override
	public final Subscriber<? super T> downstream() {
		return actual;
	}

	@Override
	public void request(long n) {
		if (BackpressureUtils.validate(n)) {
			if (ONCE.compareAndSet(this, 0, 1)) {
				Subscriber<? super T> a = actual;
				a.onNext(value);
				a.onComplete();
			}
		}
	}

	@Override
	public void cancel() {
		ONCE.lazySet(this, 1);
	}

	@Override
	public Object upstream() {
		return value;
	}


	@Override
	public int requestFusion(int requestedMode) {
		if ((requestedMode & Fuseable.SYNC) != 0) {
			return Fuseable.SYNC;
		}
		return 0;
	}

	@Override
	public T poll() {
		if (once == 0) {
			ONCE.lazySet(this, 1);
			return value;
		}
		return null;
	}

	@Override
	public boolean isEmpty() {
		return once != 0;
	}

	@Override
	public int size() {
		return isEmpty() ? 0 : 1;
	}

	@Override
	public void clear() {
		ONCE.lazySet(this, 1);
	}
}
