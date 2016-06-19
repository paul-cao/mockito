/*
 * Copyright (c) 2007 Mockito contributors
 * This program is made available under the terms of the MIT License.
 */
package org.mockito.internal;

import static org.mockito.exceptions.Reporter.missingMethodInvocation;
import static org.mockito.exceptions.Reporter.mocksHaveToBePassedToVerifyNoMoreInteractions;
import static org.mockito.exceptions.Reporter.mocksHaveToBePassedWhenCreatingInOrder;
import static org.mockito.exceptions.Reporter.notAMockPassedToVerify;
import static org.mockito.exceptions.Reporter.notAMockPassedToVerifyNoMoreInteractions;
import static org.mockito.exceptions.Reporter.notAMockPassedWhenCreatingInOrder;
import static org.mockito.exceptions.Reporter.nullPassedToVerify;
import static org.mockito.exceptions.Reporter.nullPassedToVerifyNoMoreInteractions;
import static org.mockito.exceptions.Reporter.nullPassedWhenCreatingInOrder;
import static org.mockito.internal.verification.VerificationModeFactory.noMoreInteractions;

import java.util.Arrays;
import java.util.List;

import org.mockito.InOrder;
import org.mockito.MockSettings;
import org.mockito.MockingDetails;
import org.mockito.exceptions.misusing.NotAMockException;
import org.mockito.internal.creation.MockSettingsImpl;
import org.mockito.internal.invocation.finder.VerifiableInvocationsFinder;
import org.mockito.internal.progress.MockingProgress;
import org.mockito.internal.progress.ThreadSafeMockingProgress;
import org.mockito.internal.stubbing.InvocationContainer;
import org.mockito.internal.stubbing.OngoingStubbingImpl;
import org.mockito.internal.stubbing.StubberImpl;
import org.mockito.internal.util.DefaultMockingDetails;
import org.mockito.internal.util.MockUtil;
import org.mockito.internal.verification.MockAwareVerificationMode;
import org.mockito.internal.verification.VerificationDataImpl;
import org.mockito.internal.verification.VerificationModeFactory;
import org.mockito.internal.verification.api.InOrderContext;
import org.mockito.internal.verification.api.VerificationDataInOrder;
import org.mockito.internal.verification.api.VerificationDataInOrderImpl;
import org.mockito.invocation.Invocation;
import org.mockito.mock.MockCreationSettings;
import org.mockito.stubbing.OngoingStubbing;
import org.mockito.stubbing.Stubber;
import org.mockito.verification.VerificationMode;

@SuppressWarnings("unchecked")
public class MockitoCore {

    private final MockUtil mockUtil = new MockUtil();
    private final MockingProgress mockingProgress = new ThreadSafeMockingProgress();

    public boolean isTypeMockable(Class<?> typeToMock) {
        return mockUtil.typeMockabilityOf(typeToMock).mockable();
    }

    public <T> T mock(Class<T> typeToMock, MockSettings settings) {
        if (!MockSettingsImpl.class.isInstance(settings)) {
            throw new IllegalArgumentException(
                    "Unexpected implementation of '" + settings.getClass().getCanonicalName() + "'\n"
                    + "At the moment, you cannot provide your own implementations of that class.");
        }
        MockSettingsImpl impl = MockSettingsImpl.class.cast(settings);
        MockCreationSettings<T> creationSettings = impl.confirm(typeToMock);
        T mock = mockUtil.createMock(creationSettings);
        mockingProgress.mockingStarted(mock, typeToMock);
        return mock;
    }

    public <T> OngoingStubbing<T> when(T methodCall) {
        mockingProgress.stubbingStarted();
        OngoingStubbing<T> stubbing = mockingProgress.pullOngoingStubbing();
        if (stubbing == null) {
            mockingProgress.reset();
            throw missingMethodInvocation();
        }
        return stubbing;
    }
    
    public <T> T verify(T mock, VerificationMode mode) {
        if (mock == null) {
            throw nullPassedToVerify();
        } 
        if (!mockUtil.isMock(mock)) {
            throw notAMockPassedToVerify(mock.getClass());
        }
        VerificationMode actualMode = this.mockingProgress.maybeVerifyLazily(mode);
        mockingProgress.verificationStarted(new MockAwareVerificationMode(mock, actualMode));
        return mock;
    }
    
    public <T> void reset(T ... mocks) {
        mockingProgress.validateState();
        mockingProgress.reset();
        mockingProgress.resetOngoingStubbing();
        
        for (T m : mocks) {
            mockUtil.resetMock(m);
        }
    }

    public <T> void clearInvocations(T ... mocks) {
        mockingProgress.validateState();
        mockingProgress.reset();
        mockingProgress.resetOngoingStubbing();

        for (T m : mocks) {
            mockUtil.getMockHandler(m).getInvocationContainer().clearInvocations();
        }
    }
    
    public void verifyNoMoreInteractions(Object... mocks) {
        assertMocksNotEmpty(mocks);
        mockingProgress.validateState();
        for (Object mock : mocks) {
            try {
                if (mock == null) {
                    throw nullPassedToVerifyNoMoreInteractions();
                }
                InvocationContainer invocations = mockUtil.getMockHandler(mock).getInvocationContainer();
                VerificationDataImpl data = new VerificationDataImpl(invocations, null);
                noMoreInteractions().verify(data);
            } catch (NotAMockException e) {
                throw notAMockPassedToVerifyNoMoreInteractions();
            }
        }
    }

    public void verifyNoMoreInteractionsInOrder(List<Object> mocks, InOrderContext inOrderContext) {
        mockingProgress.validateState();
        VerifiableInvocationsFinder finder = new VerifiableInvocationsFinder();
        VerificationDataInOrder data = new VerificationDataInOrderImpl(inOrderContext, finder.find(mocks), null);
        VerificationModeFactory.noMoreInteractions().verifyInOrder(data);
    }    
    
    private void assertMocksNotEmpty(Object[] mocks) {
        if (mocks == null || mocks.length == 0) {
            throw mocksHaveToBePassedToVerifyNoMoreInteractions();
        }
    }
    
    public InOrder inOrder(Object... mocks) {
        if (mocks == null || mocks.length == 0) {
            throw mocksHaveToBePassedWhenCreatingInOrder();
        }
        for (Object mock : mocks) {
            if (mock == null) {
                throw nullPassedWhenCreatingInOrder();
            } 
            if (!mockUtil.isMock(mock)) {
                throw notAMockPassedWhenCreatingInOrder();
            }
        }
        return new InOrderImpl(Arrays.asList(mocks));
    }
    
    public Stubber stubber() {
        mockingProgress.stubbingStarted();
        mockingProgress.resetOngoingStubbing();
        return new StubberImpl();
    }

    public void validateMockitoUsage() {
        mockingProgress.validateState();
    }

    /**
     * For testing purposes only. Is not the part of main API.
     * @return last invocation
     */
    public Invocation getLastInvocation() {
        OngoingStubbingImpl ongoingStubbing = ((OngoingStubbingImpl) mockingProgress.pullOngoingStubbing());
        List<Invocation> allInvocations = ongoingStubbing.getRegisteredInvocations();
        return allInvocations.get(allInvocations.size()-1);
    }

    public Object[] ignoreStubs(Object... mocks) {
        for (Object m : mocks) {
            InvocationContainer invocationContainer = new MockUtil().getMockHandler(m).getInvocationContainer();
            List<Invocation> ins = invocationContainer.getInvocations();
            for (Invocation in : ins) {
                if (in.stubInfo() != null) {
                    in.ignoreForVerification();
                }
            }
        }
        return mocks;
    }

    public MockingDetails mockingDetails(Object toInspect) {
        return new DefaultMockingDetails(toInspect, new MockUtil());
    }
}
