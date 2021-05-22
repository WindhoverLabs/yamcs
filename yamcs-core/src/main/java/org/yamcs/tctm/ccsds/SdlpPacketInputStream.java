/* Written by Mathew Benson, Windhover Labs, mbenson@windhoverlabs.com */

package org.yamcs.tctm.ccsds;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.yamcs.YConfiguration;
import org.yamcs.tctm.PacketInputStream;
import org.yamcs.utils.StringConverter;

/**
 * Reads CCSDS packets from an input stream:
 * This packet input stream reads and verifies the data 1 piece at a time, when possible. This 
 * improves data integrity and reliability when parsing streams that are lossy or when it is
 * not guaranteed to begin at the beginning of a packet. This is a stateful design that will reset
 * back to the initial unsynchronized state when it detects the possibility that its parsing invalid
 * data. For example, if the integrator knows the stream will not contain any packet greater than
 * a certain size, not just the maximum CCSDS size, the maximum size can bet set as a configurable
 * parameter. In this case, when a length parameter is read that exceeds the maximum size, the parser
 * will detect this as an out of sync condition, and reset back to the initial out of sync state. 
 * 
 * @author Mathew Benson
 *
 */
public class SdlpPacketInputStream implements PacketInputStream {
    DataInputStream dataInputStream;
    String  asm            = "1ACFFC1D";
    int     asmCursor      = 0;
    int     maxLength      = 32768;
    int     fixedLength    = 0;
    int     asmLength;

    static Logger log = LoggerFactory.getLogger(SdlpPacketInputStream.class.getName());
    
    enum ParserState {
    	OUT_OF_SYNC,
    	IN_SYNC,
    	PARSING_CADU,
    	PARSING_ASM,
    	CADU_COMPLETE
    }
    
    ParserState parserState;

    @Override
    public void init(InputStream inputStream, YConfiguration args) {
        this.dataInputStream = new DataInputStream(inputStream);
        this.asm = args.getString("ASM", asm);
        this.asmLength = asm.length() / 2;
        this.fixedLength = args.getInt("fixedLength", fixedLength);
        this.maxLength = args.getInt("maxLength", maxLength);

        parserState = ParserState.OUT_OF_SYNC;
    }

    @Override
    public byte[] readPacket() throws IOException {
        byte[] tmpPacket = null;
        byte[] packet = null;
        byte[] hdr = new byte[asmLength];
        int    caduLength = 0;
        int    asmCursor = 0;
        
    	while(parserState != ParserState.CADU_COMPLETE) {
    		switch(parserState) {
    		    case OUT_OF_SYNC: {
		        	/* We are totally out of sync. Start, or keep looking, for the first ASM, one byte
		        	 * at a time.
		        	 */
    		        dataInputStream.readFully(hdr, 0, 1);
    		        
    		        int expectedValue = Integer.parseInt(asm.substring(asmCursor * 2, (asmCursor * 2) + 2), 16) & 0xff;
    	        	
    		        /* Is this the next value of the ASM? */
    		        if(expectedValue == (hdr[0] & 0xff))
    		        {
    		        	/* Yes this is the next ASM value. Advance the cursor to the next byte. */
    		        	asmCursor++;
    		        	
    		        	/* Have we read all of the ASM? */
    		        	if(asmCursor >= asmLength)
    		        	{
    		        		/* Yes. Transition to the IN_SYNC state. */
    		        		parserState = ParserState.IN_SYNC;
    		        		break;
    		        	}
    		        }
    		        else
    		        {
    		        	/* This is not the next ASM value. Remain in this state and keep looking
    		        	 * for a fully formed ASM. */
    		        	asmCursor = 0;
    		        }
    		        
    		        break;
    		    }	

    		    case IN_SYNC: {
    		    	/* We just finished parsing the ASM and are at the start of a new CADU. 
	        		 * Is the CADU length fixed?
	        		 */
                    if(fixedLength > 0)
                    {
                    	/* Yes it is. Go ahead and just read the fixed number of bytes. */
                    	packet = new byte[fixedLength];
        		        dataInputStream.readFully(packet, 0, fixedLength);
        		        caduLength = fixedLength;
        		        
        		        /* Now make sure the next bytes we see are the next ASM. */
        		        asmCursor = 0;
		        		parserState = ParserState.PARSING_ASM;
    		        	break;
                    }
                    else
                    {
                    	/* The CADU is variable length. Start reading the contents of the CADU,
                    	 * one byte at a time.
                    	 */
                    	tmpPacket = new byte[maxLength];
    		        	asmCursor = 0;
    		        	caduLength = 0;
		        		parserState = ParserState.PARSING_CADU;
    		        	break;
                    }
    		    }
    		    
    		    case PARSING_CADU: {
		        	/* We think we're still in sync, but we don't know when the next ASM is going to 
		        	 * appear. Start, or containue, reading the CADU 1 byte at a time.
		        	 */
    		        dataInputStream.readFully(tmpPacket, caduLength, 1);
    		        caduLength++;
    		        
		        	/* Did we parse the maximum number of bytes that our CADU can be? */
    		        if(caduLength >= maxLength)
    		        {
    		        	/* Yes, this is the maximum the CADU can be. Assume this is the end of the CADU
    		        	 * and start looking for the next ASM.
    		        	 */
    		        	packet = tmpPacket;
    		        	parserState = ParserState.PARSING_ASM;
    		        	break;
    		        }

    		        /* Now lets see if we're possibly running into another ASM. */
    		        int expectedValue = Integer.parseInt(asm.substring(asmCursor * 2, (asmCursor * 2) + 2), 16) & 0xff;
    		        
    		        /* Is the current value we just read possibly part of the next ASM? */
    		        if(expectedValue == (tmpPacket[caduLength-1] & 0xff))
    		        {
    		        	/* Yes, this is the first/next expected value of the ASM. */
    		        	asmCursor++;
    		        	
    		        	/* Did we just find the last byte of the ASM? */
    		        	if(asmCursor >= asmLength)
    		        	{
    		        		/* Yes, this is the last byte of the ASM. This marks the end of a CADU.
    		        		 * Return the packet (minus the ASM), and transition back to the 
    		        		 * CADU_COMPLETE state.
    		        		 */
    		        		int truncatedLength = caduLength-asmLength;
    	                    packet = new byte[truncatedLength];
    	                    System.arraycopy(tmpPacket, 0, packet, 0, truncatedLength);

        		        	parserState = ParserState.CADU_COMPLETE;
        		        	break;
    		        	}
    		        }
    		        else
    		        {
    		        	/* No, this is not part of the ASM. Its just part of the CADU. Keep going. */
    		        	asmCursor = 0;
    		        }
    		        
    		        break;
    		    }
    		    
    		    case PARSING_ASM: {
    		        /* We should be parsing the ASM. Make sure it is. */
    		        dataInputStream.readFully(hdr, 0, 1);
    		        
    		        int expectedValue = Integer.parseInt(asm.substring(asmCursor * 2, (asmCursor * 2 ) + 2), 16) & 0xff;

    		        /* Is the current value we just read possibly part of the next ASM? */
    		        if(expectedValue == (hdr[0] & 0xff))
    		        {
    		        	asmCursor++;
    		        	if(asmCursor >= asmLength)
    		        	{
    		        		/* Yes, this is the last byte of the ASM. This marks the end of a CADU.
    		        		 * We only get here when the packet is a fixed length, so the packet
    		        		 * is already set. We were just ensuring that its correct. Just transition
    		        		 * to the  CADU_COMPLETE state.
    		        		 */
    		        		parserState = ParserState.CADU_COMPLETE;

    			        	//log.info("### " + StringConverter.arrayToHexString(packet, 0, packet.length, true));
    		        		break;
    		        	}
    		        }
    		        else
    		        {
    		        	/* No, this is not part of the ASM. Something went wrong and we are totally
    		        	 * of sync. Transition back to OUT_OF_SYNC and start all over again.
    		        	 */
    		        	asmCursor = 0;
		        		parserState = ParserState.OUT_OF_SYNC;
    		        }
    		        
    		        break;
    		    }
    		}
    	}
    	
    	/* We parsed one full CADU. Set the parser to the IN_SYNC state, so the next parse will
    	 * start immediately after an ASM.
    	 */
        parserState = ParserState.IN_SYNC;
                
        return packet;
    }

    @Override
    public void close() throws IOException {
        dataInputStream.close();
    }
}
