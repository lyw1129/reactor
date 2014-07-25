/*
 * Copyright (c) 2011-2013 GoPivotal, Inc. All Rights Reserved.
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
package reactor.rx.action;

import com.gs.collections.api.block.predicate.Predicate;
import com.gs.collections.api.block.procedure.Procedure;
import com.gs.collections.api.list.MutableList;
import com.gs.collections.impl.block.procedure.checked.CheckedProcedure;
import com.gs.collections.impl.list.mutable.MultiReaderFastList;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.rx.StreamSubscription;

/**
 * @author Stephane Maldini
 * @since 2.0
 */
public class FanInSubscription<O> extends StreamSubscription<O> {
	private final FanInSubscription.MutableListCheckedProcedure pruneProcedure = new FanInSubscription
			.MutableListCheckedProcedure();

	final Action<?, O> publisher;
	final MultiReaderFastList<InnerSubscription> subscriptions;

	public FanInSubscription(Action<?, O> publisher, Subscriber<O> subscriber) {
		this(publisher, subscriber, MultiReaderFastList.<InnerSubscription>newList(8));
	}

	public FanInSubscription(Action<?, O> publisher, Subscriber<O> subscriber,
	                         MultiReaderFastList<InnerSubscription> subs) {
		super(publisher, subscriber);
		this.publisher = publisher;
		this.subscriptions = subs;
	}

	@Override
	public void request(final int elements) {
		super.request(elements);
		final int parallel = subscriptions.size();

		if (parallel > 0) {
			final int batchSize = elements / parallel;
			final int remaining = (elements % parallel > 0 ? elements : 0);
			if (batchSize == 0 && elements == 0) return;

			subscriptions.forEach(new CheckedProcedure<Subscription>() {
				@Override
				public void safeValue(Subscription subscription) throws Exception {
					subscription.request(batchSize + remaining);
				}
			});

			pruneObsoleteSubscriptions();

		} else if (publisher != null && parallel == 0) {
			publisher.requestUpstream(capacity, buffer.isComplete(), elements);
		}
	}

	@Override
	public void cancel() {
		subscriptions.forEach(new CheckedProcedure<Subscription>() {
			@Override
			public void safeValue(Subscription subscription) throws Exception {
				subscription.cancel();
			}
		});
		super.cancel();
	}

	void addSubscription(final InnerSubscription s) {
		pruneObsoleteSubscriptions();

		subscriptions.
				withWriteLockAndDelegate(new CheckedProcedure<MutableList<FanInSubscription.InnerSubscription>>() {
					@Override
					public void safeValue(MutableList<FanInSubscription.InnerSubscription> streamSubscriptions)
							throws Exception {
						streamSubscriptions.add(s);
					}
				});

	}

	public void pruneObsoleteSubscriptions() {
		subscriptions.withWriteLockAndDelegate(pruneProcedure);
	}



	public static class InnerSubscription implements Subscription {

		final Subscription wrapped;
		boolean toRemove = false;

		public InnerSubscription(Subscription wrapped) {
			this.wrapped = wrapped;
		}

		@Override
		public void request(int n) {
			wrapped.request(n);
		}

		@Override
		public void cancel() {
			wrapped.cancel();
		}

		public Subscription getDelegate() {
			return wrapped;
		}
	}

	private static class MutableListCheckedProcedure extends CheckedProcedure<MutableList<InnerSubscription>> {
		@Override
		public void safeValue(final MutableList<InnerSubscription> innerSubscriptions) throws Exception {
			innerSubscriptions.select(new Predicate<InnerSubscription>() {
				@Override
				public boolean accept(InnerSubscription innerSubscription) {
					return innerSubscription.toRemove;
				}
			}).forEach(new Procedure<InnerSubscription>() {
				@Override
				public void value(InnerSubscription innerSubscription) {
					innerSubscriptions.remove(innerSubscription);
				}
			});
		}
	}
}
