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

package org.jpos.iso;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/**
 * @author joconnor
 */
public class IFA_BINARYTest {
    @Test
    public void testPack() throws Exception
    {
        ISOBinaryField field = new ISOBinaryField(12, new byte[] {0x12, 0x34});
        IFA_BINARY packager = new IFA_BINARY(2, "Should be 1234");
        TestUtils.assertEquals("1234".getBytes(), packager.pack(field));
    }

    @Test
    public void testPackWrongLength() throws Exception
    {
        try
        {
            ISOBinaryField field = new ISOBinaryField(12, new byte[] {0x12, 0x34});
            IFA_BINARY packager = new IFA_BINARY(3, "Should be 1234");
            packager.pack(field);
            fail("Packing 2 bytes into 3 should throw an exception");
        }
        catch (Exception ignored)
        {
            // Exception expected - correct behaviour
        }
    }

    @Test
    public void testUnpack() throws Exception
    {
        byte[] raw = "1234".getBytes();
        IFA_BINARY packager = new IFA_BINARY(2, "Should be 1234");
        ISOBinaryField field = new ISOBinaryField(12);
        packager.unpack(field, raw, 0);
        TestUtils.assertEquals(new byte[] {0x12, 0x34}, (byte[])field.getValue());
    }

    @Test
    public void testReversability() throws Exception
    {
        byte[] origin = new byte[] {0x12, 0x34, 0x56, 0x78};
        ISOBinaryField f = new ISOBinaryField(12, origin);
        IFA_BINARY packager = new IFA_BINARY(4, "Should be 12345678");

        ISOBinaryField unpack = new ISOBinaryField(12);
        packager.unpack(unpack, packager.pack(f), 0);
        TestUtils.assertEquals(origin, (byte[])unpack.getValue());
    }
}
