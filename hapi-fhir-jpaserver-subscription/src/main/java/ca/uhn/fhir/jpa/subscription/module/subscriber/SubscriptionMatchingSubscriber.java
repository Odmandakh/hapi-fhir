package ca.uhn.fhir.jpa.subscription.module.subscriber;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.model.interceptor.api.IInterceptorRegistry;
import ca.uhn.fhir.jpa.model.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.subscription.module.ResourceModifiedMessage;
import ca.uhn.fhir.jpa.subscription.module.cache.ActiveSubscription;
import ca.uhn.fhir.jpa.subscription.module.cache.SubscriptionRegistry;
import ca.uhn.fhir.jpa.subscription.module.matcher.ISubscriptionMatcher;
import ca.uhn.fhir.jpa.subscription.module.matcher.SubscriptionMatchResult;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.stereotype.Service;

import java.util.Collection;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

/*-
 * #%L
 * HAPI FHIR Subscription Server
 * %%
 * Copyright (C) 2014 - 2019 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

@Service
public class SubscriptionMatchingSubscriber implements MessageHandler {
	private Logger ourLog = LoggerFactory.getLogger(SubscriptionMatchingSubscriber.class);

	@Autowired
	private ISubscriptionMatcher mySubscriptionMatcher;
	@Autowired
	private FhirContext myFhirContext;
	@Autowired
	private SubscriptionRegistry mySubscriptionRegistry;
	@Autowired
	private IInterceptorRegistry myInterceptorRegistry;

	@Override
	public void handleMessage(Message<?> theMessage) throws MessagingException {
		ourLog.trace("Handling resource modified message: {}", theMessage);

		if (!(theMessage instanceof ResourceModifiedJsonMessage)) {
			ourLog.warn("Unexpected message payload type: {}", theMessage);
			return;
		}

		ResourceModifiedMessage msg = ((ResourceModifiedJsonMessage) theMessage).getPayload();
		matchActiveSubscriptionsAndDeliver(msg);

	}

	public void matchActiveSubscriptionsAndDeliver(ResourceModifiedMessage theMsg) {
		try {
			doMatchActiveSubscriptionsAndDeliver(theMsg);
		} finally {
			myInterceptorRegistry.callHooks(Pointcut.SUBSCRIPTION_AFTER_PERSISTED_RESOURCE_CHECKED, theMsg);
		}
	}

	private void doMatchActiveSubscriptionsAndDeliver(ResourceModifiedMessage theMsg) {
		switch (theMsg.getOperationType()) {
			case CREATE:
			case UPDATE:
			case MANUALLY_TRIGGERED:
				break;
			case DELETE:
			default:
				ourLog.trace("Not processing modified message for {}", theMsg.getOperationType());
				// ignore anything else
				return;
		}

		IIdType resourceId = theMsg.getId(myFhirContext);

		Collection<ActiveSubscription> subscriptions = mySubscriptionRegistry.getAll();

		ourLog.trace("Testing {} subscriptions for applicability", subscriptions.size());

		for (ActiveSubscription nextActiveSubscription : subscriptions) {

			String nextSubscriptionId = getId(nextActiveSubscription);

			if (isNotBlank(theMsg.getSubscriptionId())) {
				if (!theMsg.getSubscriptionId().equals(nextSubscriptionId)) {
					ourLog.debug("Ignoring subscription {} because it is not {}", nextSubscriptionId, theMsg.getSubscriptionId());
					continue;
				}
			}

			if (!validCriteria(nextActiveSubscription, resourceId)) {
				continue;
			}

			SubscriptionMatchResult matchResult = mySubscriptionMatcher.match(nextActiveSubscription.getSubscription(), theMsg);
			if (!matchResult.matched()) {
				continue;
			}

			ourLog.info("Subscription {} was matched by resource {} using matcher {}", nextActiveSubscription.getSubscription().getIdElement(myFhirContext).getValue(), resourceId.toUnqualifiedVersionless().getValue(), matchResult.matcherShortName());

			IBaseResource payload = theMsg.getNewPayload(myFhirContext);

			ResourceDeliveryMessage deliveryMsg = new ResourceDeliveryMessage();
			deliveryMsg.setPayload(myFhirContext, payload);
			deliveryMsg.setSubscription(nextActiveSubscription.getSubscription());
			deliveryMsg.setOperationType(theMsg.getOperationType());
			if (payload == null) {
				deliveryMsg.setPayloadId(theMsg.getId(myFhirContext));
			}

			ResourceDeliveryJsonMessage wrappedMsg = new ResourceDeliveryJsonMessage(deliveryMsg);
			MessageChannel deliveryChannel = nextActiveSubscription.getSubscribableChannel();
			if (deliveryChannel != null) {
				deliveryChannel.send(wrappedMsg);
			} else {
				ourLog.warn("Do not have delivery channel for subscription {}", nextActiveSubscription.getIdElement(myFhirContext));
			}
		}
	}

	private String getId(ActiveSubscription theActiveSubscription) {
		return theActiveSubscription.getIdElement(myFhirContext).toUnqualifiedVersionless().getValue();
	}

	private boolean validCriteria(ActiveSubscription theActiveSubscription, IIdType theResourceId) {
		String criteriaString = theActiveSubscription.getCriteriaString();
		String subscriptionId = getId(theActiveSubscription);
		String resourceType = theResourceId.getResourceType();

		if (StringUtils.isBlank(criteriaString)) {
			return false;
		}

		// see if the criteria matches the created object
		ourLog.trace("Checking subscription {} for {} with criteria {}", subscriptionId, resourceType, criteriaString);
		String criteriaResource = criteriaString;
		int index = criteriaResource.indexOf("?");
		if (index != -1) {
			criteriaResource = criteriaResource.substring(0, criteriaResource.indexOf("?"));
		}

		if (resourceType != null && criteriaString != null && !criteriaResource.equals(resourceType)) {
			ourLog.trace("Skipping subscription search for {} because it does not match the criteria {}", resourceType, criteriaString);
			return false;
		}

		return true;
	}
}
