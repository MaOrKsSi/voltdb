/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltcore.agreement;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.voltcore.agreement.AgreementSite.FaultMessage;
import org.voltcore.logging.VoltLogger;
import org.voltcore.messaging.HeartbeatMessage;
import org.voltcore.messaging.Mailbox;
import org.voltcore.messaging.VoltMessage;

class MiniSite extends Thread implements MeshAide
{
    final VoltLogger m_siteLog;
    MeshArbiter m_arbiter;
    Mailbox m_mailbox;
    AtomicBoolean m_shouldContinue = new AtomicBoolean(true);
    Set<Long> m_initialHSIds = new HashSet<Long>();
    Set<Long> m_currentHSIds = new HashSet<Long>();
    Set<Long> m_failedHSIds = new HashSet<Long>();

    MiniSite(Mailbox mbox, Set<Long> HSIds, VoltLogger logger)
    {
        m_siteLog = logger;
        m_initialHSIds.addAll(HSIds);
        m_currentHSIds.addAll(HSIds);
        m_mailbox = mbox;
        m_arbiter = new MeshArbiter(mbox.getHSId(), mbox, this);
    }

    void shutdown()
    {
        m_siteLog.info("MiniSite shutting down");
        m_shouldContinue.set(false);
    }

    @Override
    public Long getNewestSafeTransactionForInitiator(Long initiatorId) {
        return 1L;
    }

    @Override
    public void sendHeartbeats(Set<Long> hsIds) {
        for (long initiatorId : hsIds) {
            HeartbeatMessage heartbeat =
                new HeartbeatMessage(m_mailbox.getHSId(), 1, 1);
            m_mailbox.send( initiatorId, heartbeat);
        }
    }

    public void reportFault(long faultingSite, boolean witnessed) {
        m_siteLog.debug("Reported fault: " + faultingSite + ", witnessed?: " + witnessed);
        FaultMessage fm = new FaultMessage(faultingSite, witnessed);
        fm.m_sourceHSId = m_mailbox.getHSId();
        m_mailbox.deliver(fm);
    }

    @Override
    public void run() {
        long lastHeartbeatTime = System.currentTimeMillis();
        while (m_shouldContinue.get()) {
            VoltMessage msg = m_mailbox.recvBlocking(5);
            if (msg != null) {
                processMessage(msg);
            }
            long now = System.currentTimeMillis();
            if (now - lastHeartbeatTime > 5) {
                sendHeartbeats(m_currentHSIds);
                lastHeartbeatTime = now;
            }
        }
    }

    private void processMessage(VoltMessage msg)
    {
        if (!m_currentHSIds.contains(msg.m_sourceHSId)) {
            m_siteLog.info("Dropping message " + msg + " because it is not from a known up site");
        }
        // need heartbeat something in here?
        if (msg instanceof FaultMessage) {
            FaultMessage fm = (FaultMessage)msg;
            discoverGlobalFaultData(fm);
        }
    }

    private void discoverGlobalFaultData(FaultMessage faultMessage)
    {
        m_siteLog.info("Saw fault: " + faultMessage.failedSite + ", witnessed?: " + faultMessage.witnessed);
        Map<Long, Long> results = m_arbiter.reconfigureOnFault(m_currentHSIds, faultMessage);
        if (results.isEmpty()) {
            return;
        }
        m_failedHSIds.addAll(results.keySet());
        m_currentHSIds.removeAll(results.keySet());
        // need to "disconnect" these failed guys somehow?
    }
}