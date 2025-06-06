/*
 * jPOS Project [http://jpos.org]
 * Copyright (C) 2000-2024 jPOS Software SRL
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jpos.q2.iso;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.BaseUnits;
import org.jdom2.Element;
import org.jpos.core.ConfigurationException;
import org.jpos.core.Environment;
import org.jpos.core.annotation.Config;
import org.jpos.core.handlers.exception.ExceptionHandlerAware;
import org.jpos.core.handlers.exception.ExceptionHandlerConfigAware;
import org.jpos.iso.*;
import org.jpos.metrics.MeterFactory;
import org.jpos.metrics.MeterInfo;
import org.jpos.q2.QBeanSupport;
import org.jpos.q2.QFactory;
import org.jpos.space.Space;
import org.jpos.space.SpaceFactory;
import org.jpos.space.SpaceUtil;
import org.jpos.util.LogSource;
import org.jpos.util.Loggeable;
import org.jpos.util.NameRegistrar;

import java.io.EOFException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author Alejandro Revilla
 */
@SuppressWarnings("unchecked")
public class ChannelAdaptor
    extends QBeanSupport
    implements ChannelAdaptorMBean, Channel, Loggeable, ExceptionHandlerConfigAware
{
    protected Space sp;
    private ISOChannel channel;
    String in, out, ready, reconnect;
    long delay;
    boolean keepAlive = false;
    boolean ignoreISOExceptions = false;
    boolean writeOnly = false;
    int rx, tx, connects;
    long lastTxn = 0l;
    long timeout = 0l;
    boolean waitForWorkersOnStop;
    private Thread receiver;
    private Thread sender;
    private final Object disconnectLock = Boolean.TRUE;

    private ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;

    private Gauge connectionsGauge;

    private Counter msgOutCounter;
    private Counter msgInCounter;
    @Config("soft-stop") private long softStop;

    public ChannelAdaptor () {
        super ();
        resetCounters();
    }
    public void initService() throws ConfigurationException {
        if (softStop < 0)
            throw new ConfigurationException ("Invalid soft-stop %d".formatted(Long.valueOf(softStop)));
        initSpaceAndQueues();
        NameRegistrar.register (getName(), this);
        executor = QFactory.executorService(cfg.getBoolean("virtual-threads", false));
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().factory()
        );
    }
    public void startService () {
        try {
            channel = initChannel ();
            executor.submit(new Sender());
            if (!writeOnly) { // fixes #426 && jPOS-20
                executor.submit (new Receiver());
            }
            initMeters();
        } catch (Exception e) {
            getLog().warn ("error starting service", e);
        }
    }
    public void stopService () {
        try {
            sp.out (in, Boolean.TRUE);
            if (channel != null) {
                if (softStop > 0L)
                    disconnectLater(softStop);
                else
                    disconnect();
            }
            if (waitForWorkersOnStop)
                executor.awaitTermination(Math.max(5000L, softStop), TimeUnit.MILLISECONDS);
            sender = null;
            receiver = null;
            removeMeters();
        } catch (Exception e) {
            getLog().warn ("error disconnecting from remote host", e);
        }
    }
    public void destroyService () {
        NameRegistrar.unregister (getName ());
        NameRegistrar.unregister ("channel." + getName ());
    }

    public synchronized void setReconnectDelay (long delay) {
        getPersist().getChild ("reconnect-delay") 
            .setText (Long.toString (delay));
        this.delay = delay;
        setModified (true);
    }
    public long getReconnectDelay () {
        return delay;
    }
    public synchronized void setInQueue (String in) {
        String old = this.in;
        this.in = in;
        if (old != null)
            sp.out (old, Boolean.TRUE);

        getPersist().getChild("in").setText (in);
        setModified (true);
    }
    public String getInQueue () {
        return in;
    }
    public synchronized void setOutQueue (String out) {
        this.out = out;
        getPersist().getChild("out").setText (out);
        setModified (true);
    }

    /**
     * Queue a message to be transmitted by this adaptor
     * @param m message to send
     */
    public void send (ISOMsg m) {
        sp.out (in, m);
    }
    /**
     * Queue a message to be transmitted by this adaptor
     * @param m message to send
     * @param timeout timeout in millis
     */
    public void send (ISOMsg m, long timeout) {
        sp.out (in, m, timeout);
    }

    /**
     * Receive message
     */
    public ISOMsg receive () {
        return (ISOMsg) sp.in (out);
    }

    /**
     * Receive message
     * @param timeout time to wait for an incoming message
     */
    public ISOMsg receive (long timeout) {
        return (ISOMsg) sp.in (out, timeout);
    }
    /**
     * @return true if channel is connected
     */
    public boolean isConnected () {
        return sp != null && sp.rdp (ready) != null;
    }

    public String getOutQueue () {
        return out;
    }

    public ISOChannel newChannel (Element e, QFactory f) 
        throws ConfigurationException
    {
        String channelName  = QFactory.getAttributeValue (e, "class");
        String packagerName = QFactory.getAttributeValue (e, "packager");

        ISOChannel channel   = (ISOChannel) f.newInstance (channelName);
        ISOPackager packager;
        if (packagerName != null) {
            packager = (ISOPackager) f.newInstance (packagerName);
            channel.setPackager (packager);
            f.setConfiguration (packager, e);
        }
        QFactory.invoke (channel, "setHeader", QFactory.getAttributeValue (e, "header"));
        f.setLogger        (channel, e);
        f.setConfiguration (channel, e);

        if (channel instanceof FilteredChannel) {
            addFilters ((FilteredChannel) channel, e, f);
        }

        if (channel instanceof ExceptionHandlerAware) {
            addExceptionHandlers((ExceptionHandlerAware) channel, e, f);
        }

        if (getName () != null)
            channel.setName (getName ());
        return channel;
    }

    protected void addFilters (FilteredChannel channel, Element e, QFactory fact)
        throws ConfigurationException
    {
        for (Object o : e.getChildren("filter")) {
            Element f = (Element) o;
            if (!QFactory.isEnabled(f)) continue;
            String clazz = QFactory.getAttributeValue(f, "class");
            ISOFilter filter = (ISOFilter) fact.newInstance(clazz);
            fact.setLogger(filter, f);
            fact.setConfiguration(filter, f);
            String direction = QFactory.getAttributeValue(f, "direction");
            if (direction == null)
                channel.addFilter(filter);
            else if ("incoming".equalsIgnoreCase(direction))
                channel.addIncomingFilter(filter);
            else if ("outgoing".equalsIgnoreCase(direction))
                channel.addOutgoingFilter(filter);
            else if ("both".equalsIgnoreCase(direction)) {
                channel.addIncomingFilter(filter);
                channel.addOutgoingFilter(filter);
            }
        }
    }



    protected ISOChannel initChannel () throws ConfigurationException {
        Element persist = getPersist ();
        Element e = persist.getChild ("channel");
        if (e == null)
            throw new ConfigurationException ("channel element missing");

        ISOChannel c = newChannel (e, getFactory());
        String socketFactoryString = getSocketFactory();
        if (socketFactoryString != null && c instanceof FactoryChannel) {
            ISOClientSocketFactory sFac = (ISOClientSocketFactory) getFactory().newInstance(socketFactoryString);
            if (sFac != null && sFac instanceof LogSource) {
                ((LogSource) sFac).setLogger(log.getLogger(),getName() + ".socket-factory");
            }
            getFactory().setConfiguration (sFac, e);
            ((FactoryChannel)c).setSocketFactory(sFac);
        }
        return c;
    }
    protected void initSpaceAndQueues () throws ConfigurationException {
        Element persist = getPersist ();
        sp = grabSpace (persist.getChild ("space"));
        in      = Environment.get(persist.getChildTextTrim ("in"));
        out     = Environment.get(persist.getChildTextTrim ("out"));
        writeOnly = "yes".equalsIgnoreCase (getPersist().getChildTextTrim ("write-only"));
        if (in == null || (out == null && !writeOnly)) {
            throw new ConfigurationException ("Misconfigured channel. Please verify in/out queues");
        }
        String s = Environment.get(persist.getChildTextTrim ("reconnect-delay"));
        delay    = s != null ? Long.parseLong (s) : 10000; // reasonable default
        keepAlive = "yes".equalsIgnoreCase (Environment.get(persist.getChildTextTrim ("keep-alive")));
        ignoreISOExceptions = "yes".equalsIgnoreCase (Environment.get(persist.getChildTextTrim ("ignore-iso-exceptions")));
        String t = Environment.get(persist.getChildTextTrim("timeout"));
        timeout = t != null && t.length() > 0 ? Long.parseLong(t) : 0l;
        ready   = getName() + ".ready";
        reconnect = getName() + ".reconnect";
        waitForWorkersOnStop = "yes".equalsIgnoreCase(Environment.get(persist.getChildTextTrim ("wait-for-workers-on-stop")));
    }

    @SuppressWarnings("unchecked")
    public class Sender implements Runnable {
        public Sender () {
            super ();
        }
        public void run () {
            Thread.currentThread().setName ("channel-sender-" + in);

            while (running()){
                try {
                    checkConnection ();
                    if (!running())
                        break;
                    Object o = sp.in (in, delay);
                    if (o instanceof ISOMsg) {
                        if (!channel.isConnected()) {
                            // push back the message so it can be handled by another channel adaptor
                            sp.push(in, o);
                            continue;
                        }
                        channel.send ((ISOMsg) o);
                        tx++;
                    } else if (o instanceof Integer) {
                        if ((int)o != hashCode()) {
                            // STOP indicator seems to be for another channel adaptor
                            // sharing the same queue push it back and allow the companion
                            // channel to get it
                            sp.push (in, o, 500L);
                            ISOUtil.sleep (1000L); // larger sleep so that the indicator has time to timeout
                        }
                    }
                    else if (keepAlive && channel.isConnected() && channel instanceof BaseChannel) {
                        ((BaseChannel)channel).sendKeepAlive();
                    }
                } catch (ISOFilter.VetoException e) { 
                    // getLog().warn ("channel-sender-"+in, e.getMessage ());
                } catch (ISOException e) {
                    // getLog().warn ("channel-sender-"+in, e.getMessage ());
                    if (!ignoreISOExceptions) {
                        disconnect ();
                    }
                    ISOUtil.sleep (1000); // slow down on errors
                } catch (Exception e) { 
                    // getLog().warn ("channel-sender-"+in, e.getMessage ());
                    disconnect ();
                    ISOUtil.sleep (1000);
                }
            }
        }
    }
    @SuppressWarnings("unchecked")
    public class Receiver implements Runnable {
        public Receiver () {
            super ();
        }
        public void run () {
            Thread.currentThread().setName ("channel-receiver-"+out);
            boolean shuttingDown = false;
            Instant shutdownDeadline = null;
            final Duration gracePeriod = Duration.ofMillis(softStop);
            while (true) {
                if (!shuttingDown && !running()) {
                    if (gracePeriod.isZero())
                        break;
                    shuttingDown = true;
                    shutdownDeadline = Instant.now().plus(gracePeriod);
                    getLog().info("soft-stop (%s)".formatted(shutdownDeadline.atZone(ZoneId.systemDefault())));
                }
                final boolean shouldExit = shuttingDown
                  ? Instant.now().isAfter(shutdownDeadline)
                  : !running();

                if (shouldExit) {
                    getLog().info ("stop");
                    break;
                }
                try {
                    Object r = sp.rd (ready, 5000L);
                    if (r == null) {
                        continue;
                    }
                    ISOMsg m = channel.receive ();
                    msgInCounter.increment();
                    rx++;
                    lastTxn = System.currentTimeMillis();
                    if (timeout > 0)
                        sp.out (out, m, timeout);
                    else
                        sp.out (out, m);
                } catch (ISOFilter.VetoException e) {
                    // getLog().warn ("channel-receiver-"+out+"-veto-exception", e.getMessage());
                } catch (ISOException e) {
                    if (running()) {
                        // getLog().warn ("channel-receiver-"+out, e);
                        if (!ignoreISOExceptions) {
                            sp.out (reconnect, Boolean.TRUE, delay);
                            disconnect ();
                            sp.push (in, hashCode()); // wake-up Sender
                        }
                        ISOUtil.sleep(1000);
                    }
                } catch (SocketTimeoutException | EOFException e) {
                    if (running()) {
                        // getLog().warn ("channel-receiver-"+out, "Read timeout / EOF - reconnecting");
                        sp.out (reconnect, Boolean.TRUE, delay);
                        disconnect ();
                        sp.push (in, hashCode()); // wake-up Sender
                        ISOUtil.sleep(1000);
                    }
                } catch (Exception e) { 
                    if (running()) {
                        // getLog().warn ("channel-receiver-"+out, e);
                        sp.out (reconnect, Boolean.TRUE, delay);
                        disconnect ();
                        sp.push (in, hashCode()); // wake-up Sender
                        ISOUtil.sleep(1000);
                    }
                }
            }
            disconnect();
        }
    }
    protected void checkConnection () {
        while (running() && sp.rdp (reconnect) != null) {
            ISOUtil.sleep(1000);
        }
        while (running() && !channel.isConnected ()) {
            SpaceUtil.wipe(sp, ready);
            try {
                channel.connect ();
            } catch (IOException ignored) {
                // channel.connect already logs - no need for more warnings
            }
            if (!channel.isConnected ())
                ISOUtil.sleep (delay);
            else
                connects++;
        }
        if (running() && sp.rdp (ready) == null)
            sp.out (ready, new Date());
    }
    protected void disconnect () {
        // do not synchronize on this as both Sender and Receiver can deadlock against a thread calling stop()
        synchronized (disconnectLock) {
            try {
                SpaceUtil.wipe(sp, ready);
                channel.disconnect();
            } catch (Exception e) {
                getLog().warn("disconnect", e);
            }
        }
    }
    private void disconnectLater(long delayInMillis) {
         SpaceUtil.wipe(sp, ready);
         scheduledExecutor.schedule(this::disconnect, delayInMillis, TimeUnit.MILLISECONDS);
    }
    public synchronized void setHost (String host) {
        setProperty (getProperties ("channel"), "host", host);
        setModified (true);
    }
    public String getHost () {
        return getProperty (getProperties ("channel"), "host");
    }
    public synchronized void setPort (int port) {
        setProperty (
            getProperties ("channel"), "port", Integer.toString (port)
        );
        setModified (true);
    }
    public int getPort () {
        int port = 0;
        try {
            port = Integer.parseInt (
                getProperty (getProperties ("channel"), "port")
            );
        } catch (NumberFormatException e) {
            getLog().error(e);
        }
        return port;
    }
    public synchronized void setSocketFactory (String sFac) {
        setProperty(getProperties("channel"), "socketFactory", sFac);
        setModified(true);
    }

    public void resetCounters () {
        rx = tx = connects = 0;
        lastTxn = 0l;
    }
    public String getCountersAsString () {
        StringBuilder sb = new StringBuilder();
        append (sb, "tx=", tx);
        append (sb, ", rx=", rx);
        append (sb, ", connects=", connects);
        sb.append (", last=");
        sb.append(lastTxn);
        if (lastTxn > 0) {
            sb.append (", idle=");
            sb.append(System.currentTimeMillis() - lastTxn);
            sb.append ("ms");
        }
        return sb.toString();
    }
    public int getTXCounter() {
        return tx;
    }
    public int getRXCounter() {
        return rx;
    }
    public int getConnectsCounter () {
        return connects;
    }
    public long getLastTxnTimestampInMillis() {
        return lastTxn;
    }
    public long getIdleTimeInMillis() {
        return lastTxn > 0L ? System.currentTimeMillis() - lastTxn : -1L;
    }
    public String getSocketFactory() {
        return getProperty(getProperties ("channel"), "socketFactory");
    }
    public void dump (PrintStream p, String indent) {
        p.println (indent + getCountersAsString());
    }
    protected Space grabSpace (Element e) {
        return SpaceFactory.getSpace (e != null ? e.getText() : "");
    }
    protected void append (StringBuilder sb, String name, int value) {
        sb.append (name);
        sb.append (value);
    }
    private void initMeters() {
        var tags = Tags.of("name", getName(), "type", "client");
        var registry = getServer().getMeterRegistry();
        connectionsGauge =
          MeterFactory.gauge
            (registry, MeterInfo.ISOCHANNEL_CONNECTION_COUNT,
              tags,
              BaseUnits.SESSIONS,
              () -> isConnected() ? 1 : 0
            );

        msgInCounter = MeterFactory.counter(registry, MeterInfo.ISOMSG_IN, tags);
        msgOutCounter = MeterFactory.counter(registry, MeterInfo.ISOMSG_OUT, tags);
    }
    private void removeMeters() {
        MeterFactory.remove (getServer().getMeterRegistry(), connectionsGauge, msgInCounter, msgOutCounter);
    }
}
