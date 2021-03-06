package org.deftserver.io.stream;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public class ByteBufferBackedOutputStream extends OutputStream {
    ByteBuffer buf;

    public ByteBufferBackedOutputStream(ByteBuffer buf) {
        this.buf = buf;
    }

    public void write(int b) {
        buf.put((byte) b);
    }

    public void write(byte[] bytes, int off, int len)
            throws IOException {
        buf.put(bytes, off, len);
    }

}
