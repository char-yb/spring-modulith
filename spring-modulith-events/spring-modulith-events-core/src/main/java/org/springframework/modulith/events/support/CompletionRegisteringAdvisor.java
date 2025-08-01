/*
 * Copyright 2023-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.modulith.events.support;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcher;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalApplicationListenerMethodAdapter;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.Assert;
import org.springframework.util.ConcurrentLruCache;

/**
 * An {@link org.springframework.aop.Advisor} to decorate {@link TransactionalEventListener} annotated methods to mark
 * the previously registered event publications as completed on successful method execution.
 *
 * @author Oliver Drotbohm
 */
public class CompletionRegisteringAdvisor extends AbstractPointcutAdvisor {

	private static final long serialVersionUID = 5649563426118669238L;

	private final Pointcut pointcut;
	private final Advice advice;

	/**
	 * Creates a new {@link CompletionRegisteringAdvisor} for the given {@link EventPublicationRegistry}.
	 *
	 * @param registry must not be {@literal null}.
	 */
	public CompletionRegisteringAdvisor(Supplier<EventPublicationRegistry> registry) {

		Assert.notNull(registry, "EventPublicationRegistry must not be null!");

		this.pointcut = new AnnotationMatchingPointcut(null, TransactionalEventListener.class, true) {

			/*
			 * (non-Javadoc)
			 * @see org.springframework.aop.support.annotation.AnnotationMatchingPointcut#getMethodMatcher()
			 */
			@Override
			public MethodMatcher getMethodMatcher() {
				return new CommitListenerMethodMatcher(super.getMethodMatcher());
			}
		};

		this.advice = new CompletionRegisteringMethodInterceptor(registry);
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.aop.PointcutAdvisor#getPointcut()
	 */
	public Pointcut getPointcut() {
		return pointcut;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.aop.Advisor#getAdvice()
	 */
	@Override
	public Advice getAdvice() {
		return advice;
	}

	/**
	 * An adapter for a delegating {@link MethodMatcher} to verify the
	 *
	 * @author Oliver Drotbohm
	 */
	private static class CommitListenerMethodMatcher extends StaticMethodMatcher {

		private final MethodMatcher delegate;

		/**
		 * Creates a new {@link CommitListenerMethodMatcher} with the given delegate {@link MethodMatcher}.
		 *
		 * @param delegate must not be {@literal null}.
		 */
		public CommitListenerMethodMatcher(MethodMatcher delegate) {
			this.delegate = delegate;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.aop.MethodMatcher#matches(java.lang.reflect.Method, java.lang.Class)
		 */
		@Override
		public boolean matches(Method method, Class<?> targetClass) {

			if (!delegate.matches(method, targetClass)) {
				return false;
			}

			var annotation = AnnotatedElementUtils.findMergedAnnotation(method, TransactionalEventListener.class);

			return annotation != null && annotation.phase().equals(TransactionPhase.AFTER_COMMIT);
		}
	}

	/**
	 * {@link MethodInterceptor} to trigger the completion of an event publication after a transaction event listener
	 * method has been completed successfully.
	 *
	 * @author Oliver Drotbohm
	 */
	static class CompletionRegisteringMethodInterceptor implements MethodInterceptor, Ordered {

		private static final Logger LOG = LoggerFactory.getLogger(CompletionRegisteringMethodInterceptor.class);
		private static final ConcurrentLruCache<Method, String> LISTENER_IDS = new ConcurrentLruCache<>(
				100, CompletionRegisteringMethodInterceptor::lookupListenerId);

		private final @NonNull Supplier<EventPublicationRegistry> registry;

		/**
		 * Creates a new {@link CompletionRegisteringMethodInterceptor} for the given {@link EventPublicationRegistry}.
		 *
		 * @param registry must not be {@literal null}.
		 */
		CompletionRegisteringMethodInterceptor(Supplier<EventPublicationRegistry> registry) {

			Assert.notNull(registry, "EventPublicationRegistry must not be null!");

			this.registry = registry;
		}

		/*
		 * (non-Javadoc)
		 * @see org.aopalliance.intercept.MethodInterceptor#invoke(org.aopalliance.intercept.MethodInvocation)
		 */
		@Override
		public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {

			Object result = null;
			var method = invocation.getMethod();
			var argument = invocation.getArguments()[0];

			registerStateTransition(method, argument, EventPublicationRegistry::markProcessing);

			try {

				result = invocation.proceed();

				if (result instanceof CompletableFuture<?> future) {

					return future
							.thenApply(it -> {
								registerStateTransition(method, argument, EventPublicationRegistry::markCompleted);
								return it;
							})
							.exceptionallyCompose(it -> {
								handleFailure(method, argument, it);
								return CompletableFuture.failedFuture(it);
							});
				}

			} catch (Throwable o_O) {

				handleFailure(method, argument, o_O);

				throw o_O;
			}

			registerStateTransition(method, argument, EventPublicationRegistry::markCompleted);

			return result;
		}

		/*
		 * (non-Javadoc)
		 * @see org.springframework.core.Ordered#getOrder()
		 */
		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE + 10;
		}

		private void handleFailure(Method method, Object event, Throwable o_O) {

			registerStateTransition(method, event, EventPublicationRegistry::markFailed);

			if (LOG.isDebugEnabled()) {
				LOG.debug("Invocation of listener {} failed. Leaving event publication uncompleted.", method, o_O);
			} else {
				LOG.info("Invocation of listener {} failed with message {}. Leaving event publication uncompleted.",
						method, o_O.getMessage());
			}
		}

		private void registerStateTransition(Method method, Object event,
				RegistryInvoker invoker) {

			String adapterId = LISTENER_IDS.get(method);
			PublicationTargetIdentifier identifier = PublicationTargetIdentifier.of(adapterId);

			invoker.invoke(registry.get(), event, identifier);
		}

		private static String lookupListenerId(Method method) {
			return new TransactionalApplicationListenerMethodAdapter("¯\\_(ツ)_/¯", method.getDeclaringClass(), method)
					.getListenerId();
		}

		private interface RegistryInvoker {
			void invoke(EventPublicationRegistry registry, Object event, PublicationTargetIdentifier identifier);
		}
	}
}
