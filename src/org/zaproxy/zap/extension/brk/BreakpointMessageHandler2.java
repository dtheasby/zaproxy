/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2016 The ZAP core team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zaproxy.zap.extension.brk;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;
import org.parosproxy.paros.control.Control.Mode;
import org.zaproxy.zap.extension.httppanel.Message;

public class BreakpointMessageHandler2 {

    private static final Logger LOGGER = Logger.getLogger(BreakpointMessageHandler2.class);
    
    protected static final Object SEMAPHORE = new Object();
    
    protected final BreakpointManagementInterface breakMgmt;
    
    protected List<BreakpointMessageInterface> enabledBreakpoints;
    
    private List<String> enabledKeyBreakpoints = new ArrayList<>();
    
    public List<String> getEnabledKeyBreakpoints() {
        return enabledKeyBreakpoints;
    }

    public void setEnabledKeyBreakpoints(List<String> enabledKeyBreakpoints) {
        this.enabledKeyBreakpoints = enabledKeyBreakpoints;
    }

    public BreakpointMessageHandler2(BreakpointManagementInterface aBreakPanel) {
        this.breakMgmt = aBreakPanel;
    }
    
    public void setEnabledBreakpoints(List<BreakpointMessageInterface> breakpoints) {
        this.enabledBreakpoints = breakpoints;
    }
    
    /**
     * Do not call if in {@link Mode#safe}.
     * 
     * @param aMessage
     * @param onlyIfInScope
     * @return False if message should be dropped.
     */
    public boolean handleMessageReceivedFromClient(Message aMessage, boolean onlyIfInScope) {
        if ( ! isBreakpoint(aMessage, true, onlyIfInScope)) {
            return true;
        }
        
        // Do this outside of the semaphore loop so that the 'continue' button can apply to all queued break points
        // but be reset when the next break point is hit
        breakMgmt.breakpointHit();
        BreakEventPublisher.getPublisher().publishActiveEvent(aMessage);

        synchronized(SEMAPHORE) {
            if (breakMgmt.isHoldMessage(aMessage)) {
                setBreakDisplay(aMessage, true);
                waitUntilContinue(aMessage, true);
            }
        }
        breakMgmt.clearAndDisableRequest();
        BreakEventPublisher.getPublisher().publishInactiveEvent(aMessage);
        return ! breakMgmt.isToBeDropped();
    }
    
    /**
     * Do not call if in {@link Mode#safe}.
     * 
     * @param aMessage
     * @param onlyIfInScope
     * @return False if message should be dropped.
     */
    public boolean handleMessageReceivedFromServer(Message aMessage, boolean onlyIfInScope) {
        if (! isBreakpoint(aMessage, false, onlyIfInScope)) {
            return true;
        }
        
        // Do this outside of the semaphore loop so that the 'continue' button can apply to all queued break points
        // but be reset when the next break point is hit
        breakMgmt.breakpointHit();
        BreakEventPublisher.getPublisher().publishActiveEvent(aMessage);

        synchronized(SEMAPHORE) {
            if (breakMgmt.isHoldMessage(aMessage)) {
                setBreakDisplay(aMessage, false);
                waitUntilContinue(aMessage, false);
            }
        }
        breakMgmt.clearAndDisableResponse();
        BreakEventPublisher.getPublisher().publishInactiveEvent(aMessage);
        return ! breakMgmt.isToBeDropped();
    }
    
    private void setBreakDisplay(final Message msg, boolean isRequest) {
        breakMgmt.setMessage(msg, isRequest);
        breakMgmt.breakpointDisplayed();
    }
    
    private void waitUntilContinue(Message aMessage, final boolean isRequest) {
        // Note that multiple requests and responses can get built up, so pressing continue only
        // releases the current break, not all of them.
        while (breakMgmt.isHoldMessage(aMessage)) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                LOGGER.warn(e.getMessage(), e);
            }
        }
        breakMgmt.saveMessage(isRequest);
    }

    /**
     * You have to handle {@link Mode#safe} outside.
     * 
     * @param aMessage
     * @param isRequest
     * @param onlyIfInScope
     * @return True if a breakpoint for given message exists.
     */
    public boolean isBreakpoint(Message aMessage, boolean isRequest, boolean onlyIfInScope) {
        if (aMessage.isForceIntercept()) {
            // The browser told us to do it Your Honour
            return true;
        }
        
        if (onlyIfInScope && ! aMessage.isInScope()) {
            return false;
        }
        
        if (isBreakOnAllRequests(aMessage, isRequest)) {
            // Break on all requests
            return true;
        } else if (isBreakOnAllResponses(aMessage, isRequest)) {
            // Break on all responses
            return true;
        } else if (isBreakOnStepping(aMessage, isRequest)) {
            // Stopping through all requests and responses
            return true;
        }
        
        return isBreakOnEnabledBreakpoint(aMessage, isRequest, onlyIfInScope);
    }

    protected boolean isBreakOnAllRequests(Message aMessage, boolean isRequest) {
        return isRequest && breakMgmt.isBreakRequest();
    }
    
    protected boolean isBreakOnAllResponses(Message aMessage, boolean isRequest) {
        return !isRequest && breakMgmt.isBreakResponse();
    }

    protected boolean isBreakOnStepping(Message aMessage, boolean isRequest) {
        return breakMgmt.isStepping();
    }

    protected boolean isBreakOnEnabledBreakpoint(Message aMessage, boolean isRequest, boolean onlyIfInScope) {
        if (enabledBreakpoints.isEmpty()) {
            // No break points
            return false;
        }
        
        // match against the break points
        synchronized (enabledBreakpoints) {
            Iterator<BreakpointMessageInterface> it = enabledBreakpoints.iterator();
            
            while(it.hasNext()) {
                BreakpointMessageInterface breakpoint = it.next();
                
                if (breakpoint.match(aMessage, isRequest, onlyIfInScope)) {
                    return true;
                }
            }
        }

        return false;
    }
}
