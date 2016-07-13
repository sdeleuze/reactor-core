/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *	   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core.subscriber;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Fuseable;
import reactor.core.flow.Loopback;
import reactor.core.flow.Producer;
import reactor.core.flow.Receiver;
import reactor.core.state.Cancellable;
import reactor.core.state.Completable;
import reactor.core.util.BackpressureUtils;

/**
 * A Subscriber/Subscription barrier that holds a single value at most and properly gates asynchronous behaviors
 * resulting from concurrent request or cancel and onXXX signals.
 *
 * @param <I> The upstream sequence type
 * @param <O> The downstream sequence type
 */
public class DeferredScalarSubscriber<I, O> extends Fuseable.AbstractQueueSubscription<O> implements Subscriber<I>, Completable, Loopback, Cancellable,
                                                       Receiver, Producer,
                                                       Fuseable.QueueSubscription<O> {

	static final int SDS_NO_REQUEST_NO_VALUE   = 0;
	static final int SDS_NO_REQUEST_HAS_VALUE  = 1;
	static final int SDS_HAS_REQUEST_NO_VALUE  = 2;
	static final int SDS_HAS_REQUEST_HAS_VALUE = 3;

	protected final Subscriber<? super O> subscriber;

	protected O value;

	volatile int state;
	@SuppressWarnings("rawtypes")
	static final AtomicIntegerFieldUpdater<DeferredScalarSubscriber> STATE =
			AtomicIntegerFieldUpdater.newUpdater(DeferredScalarSubscriber.class, "state");

	protected byte outputFused;

	static final byte OUTPUT_NOT_FUSED = 0;
	static final byte OUTPUT_NO_VALUE = 1;
	static final byte OUTPUT_HAS_VALUE = 2;
	static final byte OUTPUT_COMPLETE = 3;

	public DeferredScalarSubscriber(Subscriber<? super O> subscriber) {
		this.subscriber = subscriber;
	}

	@Override
	public void request(long n) {
		if (BackpressureUtils.validate(n)) {
			for (; ; ) {
				int s = getState();
				if (s == SDS_HAS_REQUEST_NO_VALUE || s == SDS_HAS_REQUEST_HAS_VALUE) {
					return;
				}
				if (s == SDS_NO_REQUEST_HAS_VALUE) {
					if (compareAndSetState(SDS_NO_REQUEST_HAS_VALUE, SDS_HAS_REQUEST_HAS_VALUE)) {
						Subscriber<? super O> a = downstream();
						a.onNext(value);
						a.onComplete();
					}
					return;
				}
				if (compareAndSetState(SDS_NO_REQUEST_NO_VALUE, SDS_HAS_REQUEST_NO_VALUE)) {
					return;
				}
			}
		}
	}

	@Override
	public void cancel() {
		setState(SDS_HAS_REQUEST_HAS_VALUE);
	}

	@Override
	@SuppressWarnings("unchecked")
	public void onNext(I t) {
		value = (O) t;
	}

	@Override
	public void onError(Throwable t) {
		subscriber.onError(t);
	}

	@Override
	public void onSubscribe(Subscription s) {
		//if upstream
	}

	@Override
	public void onComplete() {
		subscriber.onComplete();
	}

	@Override
	public final boolean isCancelled() {
		return getState() == SDS_HAS_REQUEST_HAS_VALUE;
	}

	public final int getState() {
		return state;
	}

	public final void setState(int updated) {
		state = updated;
	}

	public final boolean compareAndSetState(int expected, int updated) {
		return STATE.compareAndSet(this, expected, updated);
	}

	@Override
	public final Subscriber<? super O> downstream() {
		return subscriber;
	}

	public void setValue(O value) {
		this.value = value;
	}

	/**
	 * Tries to emit the value and complete the underlying subscriber or
	 * stores the value away until there is a request for it.
	 * <p>
	 * Make sure this method is called at most once
	 * @param value the value to emit
	 */
	public final void complete(O value) {
		Objects.requireNonNull(value);
		for (; ; ) {
			int s = getState();
			if (s == SDS_NO_REQUEST_HAS_VALUE || s == SDS_HAS_REQUEST_HAS_VALUE) {
				return;
			}
			if (s == SDS_HAS_REQUEST_NO_VALUE) {
				if (outputFused == OUTPUT_NO_VALUE) {
					setValue(value); // make sure poll sees it
					outputFused = OUTPUT_HAS_VALUE;
				}
				Subscriber<? super O> a = downstream();
				a.onNext(value);
				if (getState() != SDS_HAS_REQUEST_HAS_VALUE) {
					a.onComplete();
				}
				return;
			}
			setValue(value);
			if (compareAndSetState(SDS_NO_REQUEST_NO_VALUE, SDS_NO_REQUEST_HAS_VALUE)) {
				return;
			}
		}
	}

	@Override
	public boolean isStarted() {
		return state != SDS_NO_REQUEST_NO_VALUE;
	}


	@Override
	public Object connectedOutput() {
		return value;
	}

	@Override
	public Object connectedInput() {
		return null;
	}

	@Override
	public boolean isTerminated() {
		return isCancelled();
	}

	@Override
	public Object upstream() {
		return value;
	}

	@Override
	public int requestFusion(int requestedMode) {
		if ((requestedMode & Fuseable.ASYNC) != 0) {
			outputFused = OUTPUT_NO_VALUE;
			return Fuseable.ASYNC;
		}
		return Fuseable.NONE;
	}

	@Override
	public O poll() {
		if (outputFused == OUTPUT_HAS_VALUE) {
			outputFused = OUTPUT_COMPLETE;
			return value;
		}
		return null;
	}

	@Override
	public boolean isEmpty() {
		return outputFused != OUTPUT_HAS_VALUE;
	}

	@Override
	public void clear() {
		outputFused = OUTPUT_COMPLETE;
	}

	@Override
	public int size() {
		return isEmpty() ? 0 : 1;
	}
}
