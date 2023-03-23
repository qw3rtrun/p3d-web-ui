package org.qw3rtrun.p3d.terminal;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

public class TerminalWriter extends Writer {

    public TerminalWriter() {
        this.buffer = new StringWriter();
    }

    StringWriter buffer;

    WritableByteChannel channel;

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        buffer.write(cbuf,off, len);
    }

    @Override
    public void flush() throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(buffer.toString().getBytes());
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
