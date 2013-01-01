/*
 * jPOS Project [http://jpos.org]
 * Copyright (C) 2000-2013 Alejandro P. Revilla
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

package org.jpos.iso.channel;

import org.jpos.core.Configuration;
import org.jpos.core.ConfigurationException;
import org.jpos.iso.*;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Talks with TCP based NACs
 * Sends [LEN][TPDU][ISOMSG]
 * (len=2 bytes network byte order)
 *
 * @author Alejandro P. Revilla
 * @version $Revision$ $Date$
 * @see ISOMsg
 * @see ISOException
 * @see ISOChannel
 */
public class NACChannel extends BaseChannel {
    /**
     * Public constructor 
     */
    boolean tpduSwap = true;
    public NACChannel () {
        super();
    }
    /**
     * Construct client ISOChannel
     * @param host  server TCP Address
     * @param port  server port number
     * @param p     an ISOPackager
     * @param TPDU  an optional raw header (i.e. TPDU)
     * @see ISOPackager
     */
    public NACChannel (String host, int port, ISOPackager p, byte[] TPDU) {
        super(host, port, p);
        this.header = TPDU;
    }
    /**
     * Construct server ISOChannel
     * @param p     an ISOPackager
     * @param TPDU  an optional raw header (i.e. TPDU)
     * @exception IOException on error
     * @see ISOPackager
     */
    public NACChannel (ISOPackager p, byte[] TPDU) throws IOException {
        super(p);
        this.header = TPDU;
    }
    /**
     * constructs server ISOChannel associated with a Server Socket
     * @param p     an ISOPackager
     * @param TPDU  an optional raw header (i.e. TPDU)
     * @param serverSocket where to accept a connection
     * @exception IOException on error
     * @see ISOPackager
     */
    public NACChannel (ISOPackager p, byte[] TPDU, ServerSocket serverSocket) 
        throws IOException
    {
        super(p, serverSocket);
        this.header = TPDU;
    }
    protected void sendMessageLength(int len) throws IOException {
        serverOut.write (len >> 8);
        serverOut.write (len);
    }
    protected int getMessageLength() throws IOException, ISOException {
        byte[] b = new byte[2];
        serverIn.readFully(b,0,2);
        return ((((int)b[0])&0xFF) << 8) | (((int)b[1])&0xFF);
    }
    protected void sendMessageHeader(ISOMsg m, int len) throws IOException { 
        byte[] h = m.getHeader();
        if (h != null) {
            if (tpduSwap && h.length == 5) {
                // swap src/dest address
                byte[] tmp = new byte[2];
                System.arraycopy (h,   1, tmp, 0, 2);
                System.arraycopy (h,   3,   h, 1, 2);
                System.arraycopy (tmp, 0,   h, 3, 2);
            }
        }
        else 
            h = header;
        if (h != null) 
            serverOut.write(h);
    }
    /**
     * New QSP compatible signature (see QSP's ConfigChannel)
     * @param header String as seen by QSP
     */
    public void setHeader (String header) {
        super.setHeader (ISOUtil.str2bcd(header, false));
    }
    public void setConfiguration (Configuration cfg) 
        throws ConfigurationException
    {
        super.setConfiguration (cfg);
        tpduSwap = cfg.getBoolean ("tpdu-swap", true);
    }
}

