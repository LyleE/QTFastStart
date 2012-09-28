package com.entwistle.qtfaststart;

import java.io.ByteArrayInputStream;

/*
 * Cheaty hack: exposes the otherwise protected field 'pos' from InputStream.
 */

public class ByteArrayInputStreamPosition extends ByteArrayInputStream {
    
    public ByteArrayInputStreamPosition(byte[] buf) {
        super(buf);
    }
    
    public ByteArrayInputStreamPosition(byte[] buf, int offset, int length) {
        super(buf, offset, length);
    }
    
    public int position() {
        return pos;
    }
}
