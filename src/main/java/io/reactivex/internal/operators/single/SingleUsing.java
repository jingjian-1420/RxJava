/**
 * Copyright (c) 2016-present, RxJava Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package io.reactivex.internal.operators.single;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.*;
import io.reactivex.disposables.Disposable;
import io.reactivex.exceptions.*;
import io.reactivex.functions.*;
import io.reactivex.internal.disposables.*;
import io.reactivex.internal.functions.ObjectHelper;
import io.reactivex.plugins.RxJavaPlugins;

public final class SingleUsing<T, U> extends Single<T> {

    final Callable<U> resourceSupplier;
    final Function<? super U, ? extends SingleSource<? extends T>> singleFunction;
    final Consumer<? super U> disposer;
    final boolean eager;

    public SingleUsing(Callable<U> resourceSupplier,
                       Function<? super U, ? extends SingleSource<? extends T>> singleFunction,
                       Consumer<? super U> disposer,
                       boolean eager) {
        this.resourceSupplier = resourceSupplier;
        this.singleFunction = singleFunction;
        this.disposer = disposer;
        this.eager = eager;
    }

    @Override
    protected void subscribeActual(final SingleObserver<? super T> observer) {

        final U resource; // NOPMD

        try {
            resource = resourceSupplier.call();
        } catch (Throwable ex) {
            Exceptions.throwIfFatal(ex);
            EmptyDisposable.error(ex, observer);
            return;
        }

        SingleSource<? extends T> source;

        try {
            source = ObjectHelper.requireNonNull(singleFunction.apply(resource), "The singleFunction returned a null SingleSource");
        } catch (Throwable ex) {
            Exceptions.throwIfFatal(ex);

            if (eager) {
                try {
                    disposer.accept(resource);
                } catch (Throwable exc) {
                    Exceptions.throwIfFatal(exc);
                    ex = new CompositeException(ex, exc);
                }
            }
            EmptyDisposable.error(ex, observer);
            if (!eager) {
                try {
                    disposer.accept(resource);
                } catch (Throwable exc) {
                    Exceptions.throwIfFatal(exc);
                    RxJavaPlugins.onError(exc);
                }
            }
            return;
        }

        source.subscribe(new UsingSingleObserver<T, U>(observer, resource, eager, disposer));
    }

    static final class UsingSingleObserver<T, U> extends
    AtomicReference<Object> implements SingleObserver<T>, Disposable {

        private static final long serialVersionUID = -5331524057054083935L;

        final SingleObserver<? super T> downstream;

        final Consumer<? super U> disposer;

        final boolean eager;

        Disposable upstream;

        UsingSingleObserver(SingleObserver<? super T> actual, U resource, boolean eager,
                Consumer<? super U> disposer) {
            super(resource);
            this.downstream = actual;
            this.eager = eager;
            this.disposer = disposer;
        }

        @Override
        public void dispose() {
            upstream.dispose();
            upstream = DisposableHelper.DISPOSED;
            disposeAfter();
        }

        @Override
        public boolean isDisposed() {
            return upstream.isDisposed();
        }

        @Override
        public void onSubscribe(Disposable d) {
            if (DisposableHelper.validate(this.upstream, d)) {
                this.upstream = d;

                downstream.onSubscribe(this);
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public void onSuccess(T value) {
            upstream = DisposableHelper.DISPOSED;

            if (eager) {
                Object u = getAndSet(this);
                if (u != this) {
                    try {
                        disposer.accept((U)u);
                    } catch (Throwable ex) {
                        Exceptions.throwIfFatal(ex);
                        downstream.onError(ex);
                        return;
                    }
                } else {
                    return;
                }
            }

            downstream.onSuccess(value);

            if (!eager) {
                disposeAfter();
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public void onError(Throwable e) {
            upstream = DisposableHelper.DISPOSED;

            if (eager) {
                Object u = getAndSet(this);
                if (u != this) {
                    try {
                        disposer.accept((U)u);
                    } catch (Throwable ex) {
                        Exceptions.throwIfFatal(ex);
                        e = new CompositeException(e, ex);
                    }
                } else {
                    return;
                }
            }

            downstream.onError(e);

            if (!eager) {
                disposeAfter();
            }
        }

        @SuppressWarnings("unchecked")
        void disposeAfter() {
            Object u = getAndSet(this);
            if (u != this) {
                try {
                    disposer.accept((U)u);
                } catch (Throwable ex) {
                    Exceptions.throwIfFatal(ex);
                    RxJavaPlugins.onError(ex);
                }
            }
        }
    }
}
